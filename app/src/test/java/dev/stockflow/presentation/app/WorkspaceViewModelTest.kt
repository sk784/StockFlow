package dev.stockflow.presentation.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.stockflow.domain.*
import dev.stockflow.presentation.components.LoadState
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    private val fake = FakeWorkspace()
    private var key = 0
    private fun model(state: SavedStateHandle = SavedStateHandle()): WorkspaceViewModel =
        WorkspaceViewModel(fake, UpdateQuantity(fake), state, fake, fake, fake, fake, fake, fake).also { store.put("vm-${key++}", it) }
    @After fun clear() { store.clear(); main.dispatcher.scheduler.runCurrent() }

    @Test fun `draft and editor selection restore from SavedStateHandle`() = runTest {
        val state = SavedStateHandle()
        val first = model(state)
        first.openEditor(fake.item)
        first.changeDraft("0012")
        val restored = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        assertEquals(1L, restored.editingItemId.value)
        assertEquals("0012", restored.quantityDraft.value)
    }

    @Test fun `save completion closes editor without a composition callback`() = runTest {
        val vm = model()
        fake.saveGate = CompletableDeferred()
        vm.openEditor(fake.item)
        vm.changeDraft("0")
        vm.saveQuantity()
        runCurrent()
        assertTrue(vm.saving.value)
        vm.closeEditor()
        vm.changeDraft("99")
        assertEquals(1L, vm.editingItemId.value)
        assertEquals("0", vm.quantityDraft.value)
        fake.saveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, fake.lastQuantity)
        assertNull(vm.editingItemId.value)
        assertEquals(1, fake.enqueued)
    }

    @Test fun `failed local save retains draft and does not schedule upload`() = runTest {
        val vm = model()
        fake.failSave = true
        vm.openEditor(fake.item)
        vm.changeDraft("12")
        vm.saveQuantity()
        advanceUntilIdle()
        assertEquals(1L, vm.editingItemId.value)
        assertEquals("12", vm.quantityDraft.value)
        assertNotNull(vm.error.value)
        assertEquals(0, fake.enqueued)
        assertFalse(vm.saving.value)
    }

    @Test fun `scheduler failure does not present persisted quantity as a failed save`() = runTest {
        val vm = model()
        fake.failScheduler = true
        vm.openEditor(fake.item)
        vm.changeDraft("12")
        vm.saveQuantity()
        advanceUntilIdle()
        assertEquals(12, fake.lastQuantity)
        assertNull(vm.editingItemId.value)
        assertTrue(vm.error.value!!.startsWith("Данные сохранены"))
    }

    @Test fun `refresh failure retains cached task state and clears loading`() = runTest {
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect() }
        runCurrent()
        fake.failRefresh = true
        vm.refreshTasks()
        assertTrue(vm.refreshing.value)
        advanceUntilIdle()
        assertEquals(1, (vm.tasks.value as LoadState.Ready).value.size)
        assertFalse(vm.refreshing.value)
        assertNotNull(vm.error.value)
    }

    @Test fun `rapid refresh and send requests are coalesced`() = runTest {
        val vm = model()
        vm.refreshTasks(); vm.refreshTasks()
        vm.requestSync(); vm.requestSync()
        advanceUntilIdle()
        assertEquals(1, fake.refreshed)
        assertEquals(1, fake.enqueued)
        assertEquals(1, fake.immediateRequests)
        assertFalse(vm.scheduling.value)
    }

    @Test fun `discard keeps other commands out until server verification completes`() = runTest {
        val vm = model()
        fake.discardGate = CompletableDeferred()
        vm.discardRejectedChanges("rejected")
        runCurrent()
        assertTrue(vm.saving.value)
        vm.requestSync()
        assertEquals(0, fake.immediateRequests)
        fake.discardGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("rejected", fake.discarded)
        assertEquals(1, fake.enqueued)
        assertFalse(vm.saving.value)
    }

    @Test fun `discard failure does not schedule upload or pretend to succeed`() = runTest {
        val vm = model()
        fake.failDiscard = true
        vm.discardRejectedChanges("rejected")
        advanceUntilIdle()
        assertEquals(0, fake.enqueued)
        assertTrue(vm.error.value!!.contains("Данные сохранены"))
        assertFalse(vm.saving.value)
    }

    @Test fun `failed read can be retried without recreating ViewModel`() = runTest {
        fake.failRead = true
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tasks.collect() }
        runCurrent()
        assertEquals(LoadState.Failed, vm.tasks.value)
        fake.failRead = false
        vm.retryLoading()
        runCurrent()
        assertTrue(vm.tasks.value is LoadState.Ready)
    }

    private class FakeWorkspace : InventoryRepository, InventoryReadModel, CatalogRepository,
        ConflictRepository, ConnectionMonitor, SyncQueue, SyncScheduler {
        val item = InventoryItem(1, 1, "Товар", "00123", 24, null)
        var lastQuantity: Int? = null
        var failSave = false
        var failRefresh = false
        var failScheduler = false
        var failRead = false
        var enqueued = 0
        var immediateRequests = 0
        var discarded: String? = null
        var failDiscard = false
        var discardGate: CompletableDeferred<Unit>? = null
        var refreshed = 0
        var saveGate: CompletableDeferred<Unit>? = null
        override fun observeTasks() = flowOf(listOf(InventoryTask(1, "Задание", "Склад")))
        override fun observeItems(taskId: Long) = flowOf(listOf(item))
        override suspend fun createDemoTask() = Unit
        override suspend fun discardRejectedChanges(operationId: String) {
            discardGate?.await()
            if (failDiscard) throw IOException("offline")
            discarded = operationId
        }
        override suspend fun updateQuantity(itemId: Long, quantity: Int) {
            saveGate?.await()
            if (failSave) throw IOException("disk unavailable")
            lastQuantity = quantity
        }
        override fun observeTaskProgress(): Flow<List<TaskProgress>> = flow {
            if (failRead) throw IOException("read error")
            emit(listOf(TaskProgress(1, "Задание", "Склад", 1, 0, 0, 0, 0, 0)))
        }
        override fun observeSyncOperations() = flowOf(emptyList<SyncOperation>())
        override suspend fun refreshTasks() { refreshed++; if (failRefresh) throw IOException("offline") }
        override fun observeConflicts() = flowOf(emptyList<InventoryConflict>())
        override suspend fun resolveConflict(operationId: String, choice: ConflictChoice) = Unit
        override fun observeConnection() = flowOf(ConnectionState.CONNECTED)
        override fun observeStatus() = flowOf(QueueStatus())
        override suspend fun next(): PendingOperation? = null
        override suspend fun acknowledge(operationId: String, revision: Long) = Unit
        override suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean) = Unit
        override suspend fun recordConflict(operationId: String, remote: QuantitySnapshot) = Unit
        override suspend fun retryBlocked() = Unit
        override fun enqueue() { if (failScheduler) error("scheduler unavailable"); enqueued++ }
        override fun enqueueNow() { immediateRequests++; enqueue() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
