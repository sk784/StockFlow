package dev.stockflow.presentation.app

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stockflow.domain.*
import dev.stockflow.presentation.theme.StockFlowTheme
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val fake = UiWorkspace()
    private lateinit var vm: WorkspaceViewModel
    @Before fun setup() {
        compose.runOnUiThread {
            vm = WorkspaceViewModel(fake, UpdateQuantity(fake), SavedStateHandle(), fake, fake, fake, fake, fake, fake)
            store.put("workspace", vm)
        }
    }
    @After fun clear() { compose.runOnUiThread { store.clear() } }
    private fun content() { compose.setContent { StockFlowTheme { StockFlowApp(vm) } } }

    @Test fun tabsPreserveInventoryAndBackReturnsToTasks() {
        content()
        compose.onNodeWithTag("tasks-list").performScrollToNode(hasText("Задание А"))
        compose.onNodeWithText("Задание А").performClick()
        compose.onNodeWithText("Инвентаризация").assertExists()
        compose.onNodeWithContentDescription("Сканировать товар").assertIsDisplayed()
        compose.onNodeWithTag("nav-sync").performClick()
        compose.onNodeWithText("Синхронизация").assertExists()
        compose.onNodeWithTag("nav-attention").performClick()
        compose.onNodeWithText("Конфликтов нет").assertExists()
        compose.onNodeWithTag("nav-work").performClick()
        compose.onNodeWithText("Инвентаризация").assertExists()
        compose.onNodeWithContentDescription("Сканировать товар").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tasks-list").assertExists()
    }

    @Test fun taskSearchAndStatusFiltersHaveRealEmptyStates() {
        content()
        compose.onNode(hasSetTextAction()).performTextInput("нет такого задания")
        compose.onNodeWithText("Заданий не найдено").assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Посчитаны").performScrollTo().performClick()
        compose.onNodeWithTag("tasks-list").performScrollToNode(hasText("Задание Б"))
        compose.onNodeWithText("Задание Б").assertExists()
        compose.onNodeWithText("Задание А").assertDoesNotExist()
    }

    @Test fun syncScreenShowsActualRetryAndCanScheduleSend() {
        fake.operations.value = listOf(SyncOperation("op", 1, "Товар", 1, "Задание А", 12, 2, false, 0, "HTTP 503", null))
        fake.queue.value = QueueStatus(1)
        content()
        compose.onNodeWithTag("nav-sync").performClick()
        compose.onNodeWithText("Отправить сейчас").performClick()
        compose.waitUntil { fake.enqueued == 1 }
        compose.onNodeWithTag("sync-list").performScrollToNode(hasText("Неудачных попыток: 2"))
        compose.onNodeWithText("HTTP 503").assertExists()
        compose.onNodeWithText("Повторим автоматически").assertExists()
    }

    @Test fun conflictReadErrorShowsRetryAndNeverClaimsResolution() {
        fake.conflictData.value = listOf(InventoryConflict("conflict", 1, "Товар", 12, 7, 1))
        content()
        compose.onNodeWithTag("nav-attention").performClick()
        compose.onNodeWithText("Разрешить конфликт").performScrollTo().performClick()
        compose.onNodeWithText("Разрешение конфликта").assertExists()
        fake.failConflictRead.value = true
        compose.waitUntil { vm.conflicts.value is dev.stockflow.presentation.components.LoadState.Failed }
        compose.onNodeWithText("Не удалось загрузить данные").assertExists()
        compose.onNodeWithText("Конфликт уже разрешён").assertDoesNotExist()
        compose.onAllNodesWithText("Оставить моё").assertCountEquals(0)
        fake.failConflictRead.value = false
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("На сервере").assertExists()
        compose.onNodeWithText("Конфликт уже разрешён").assertDoesNotExist()
    }

    @Test fun rejectedChangesRequireConfirmationAndCannotBeSubmittedTwice() {
        fake.operations.value = listOf(
            SyncOperation("rejected", 1, "Товар", 1, "Задание А", 12, 1, true, 0, "HTTP 400", null),
            SyncOperation("other", 2, "Другой товар", 1, "Задание А", 4, 0, false, null, null, null),
        )
        fake.queue.value = QueueStatus(2, 1)
        fake.discardGate = CompletableDeferred()
        content()
        compose.onNodeWithTag("nav-sync").performClick()
        compose.onNodeWithTag("sync-list").performScrollToNode(hasText("Отменить изменения товара"))
        compose.onNodeWithText("Отменить изменения товара").performClick()
        compose.onNodeWithText("Отменить локальные изменения?").assertExists()
        assertNull(fake.discarded)
        compose.onNodeWithText("Назад").performClick()
        assertNull(fake.discarded)
        compose.onNodeWithText("Отменить изменения товара").performClick()
        compose.onNodeWithText("Отменить изменения").performClick()
        compose.onNodeWithText("Проверка сервера…").assertIsNotEnabled()
        compose.onNodeWithTag("nav-work").assertIsNotEnabled()
        compose.runOnIdle { fake.discardGate!!.complete(Unit) }
        compose.waitUntil { !vm.saving.value }
        compose.onNodeWithText("Отменить локальные изменения?").assertDoesNotExist()
        assertEquals("rejected", fake.discarded)
        assertEquals(listOf("other"), fake.operations.value.map { it.operationId })
        assertEquals(1, fake.enqueued)
    }

    private class UiWorkspace : InventoryRepository, InventoryReadModel, CatalogRepository, ConflictRepository,
        ConnectionMonitor, SyncQueue, SyncScheduler {
        val operations = MutableStateFlow(emptyList<SyncOperation>())
        val queue = MutableStateFlow(QueueStatus())
        val conflictData = MutableStateFlow(emptyList<InventoryConflict>())
        val failConflictRead = MutableStateFlow(false)
        var discardGate: CompletableDeferred<Unit>? = null
        @Volatile var discarded: String? = null
        @Volatile var enqueued = 0
        override fun observeTaskProgress() = flowOf(listOf(
            TaskProgress(1, "Задание А", "Склад", 1, 0, 0, 0, 0, 0),
            TaskProgress(2, "Задание Б", "Приёмка", 1, 1, 0, 0, 0, 0),
        ))
        override fun observeSyncOperations() = operations
        override fun observeTasks() = flowOf(listOf(InventoryTask(1, "Задание А", "Склад")))
        override fun observeItems(taskId: Long) = flowOf(listOf(InventoryItem(1, taskId, "Товар", "00123", 10, null)))
        override suspend fun createDemoTask() = Unit
        override suspend fun discardRejectedChanges(operationId: String) {
            discardGate?.await()
            discarded = operationId
            operations.value = operations.value.filterNot { it.operationId == operationId }
            queue.value = QueueStatus(operations.value.size)
        }
        override suspend fun updateQuantity(itemId: Long, quantity: Int) = Unit
        override suspend fun refreshTasks() = Unit
        override fun observeConflicts(): Flow<List<InventoryConflict>> = failConflictRead.flatMapLatest { fail ->
            if (fail) flow { throw IOException("simulated Room read error") } else conflictData
        }
        override suspend fun resolveConflict(operationId: String, choice: ConflictChoice) = Unit
        override fun observeConnection() = flowOf(ConnectionState.CONNECTED)
        override fun observeStatus() = queue
        override suspend fun next(): PendingOperation? = null
        override suspend fun acknowledge(operationId: String, revision: Long) = Unit
        override suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean) = Unit
        override suspend fun recordConflict(operationId: String, remote: QuantitySnapshot) = Unit
        override suspend fun retryBlocked() = Unit
        override fun enqueue() { enqueued++ }
        override fun enqueueNow() { enqueue() }
    }
}
