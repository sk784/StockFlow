package dev.stockflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stockflow.data.sync.RoomSyncQueue
import dev.stockflow.domain.CatalogItem
import dev.stockflow.domain.ConflictChoice
import dev.stockflow.domain.InventoryCatalog
import dev.stockflow.domain.InventoryServer
import dev.stockflow.domain.InventoryTask
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.RemoteItemNotFoundException
import dev.stockflow.domain.SyncEngine
import dev.stockflow.domain.SyncResult
import dev.stockflow.domain.RemoteResult
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryConflictPersistenceTest {
    private lateinit var database: InventoryDatabase
    private lateinit var repository: RoomInventoryRepository
    private lateinit var queue: RoomSyncQueue
    private lateinit var server: FakeServer

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), InventoryDatabase::class.java,
        ).build()
        server = FakeServer()
        repository = RoomInventoryRepository(database, server)
        queue = RoomSyncQueue(database)
        repository.createDemoTask()
    }

    @After
    fun close() = database.close()

    @Test
    fun catalogRefreshPreservesDirtyCountAndOriginalRevision() = runBlocking {
        repository.updateQuantity(1, 12)
        server.current = QuantitySnapshot(7, 4)
        repository.refreshTasks()
        val item = database.inventoryDao().item(1)!!
        assertEquals(12, item.countedQuantity)
        assertEquals(0L, item.serverRevision)
        assertEquals("Server item", item.name)
        assertEquals(0L, queue.next()!!.baseRevision)
    }

    @Test
    fun catalogRefreshUpdatesCleanCountButRejectsOlderSnapshot() = runBlocking {
        server.current = QuantitySnapshot(7, 4)
        repository.refreshTasks()
        server.current = QuantitySnapshot(3, 2)
        repository.refreshTasks()
        val item = database.inventoryDao().item(1)!!
        assertEquals(7, item.countedQuantity)
        assertEquals(4L, item.serverRevision)
    }

    @Test
    fun requestRevisionIsFrozenBeforeFirstSend() = runBlocking {
        repository.updateQuantity(1, 12)
        val operation = queue.next()!!
        database.inventoryDao().setRevision(1, 9)
        assertEquals(operation, queue.next())
    }

    @Test
    fun acknowledgedRevisionFeedsNextEditWithoutChangingLocalLatestCount() = runBlocking {
        repository.updateQuantity(1, 2)
        repository.updateQuantity(1, 9)
        val first = queue.next()!!
        queue.acknowledge(first.operationId, 1)
        assertEquals(1L, queue.next()!!.baseRevision)
        assertEquals(9, database.inventoryDao().item(1)!!.countedQuantity)
    }

    @Test
    fun genericRetryCannotClearConflictAndLocalEditingIsBlocked() = runBlocking {
        repository.updateQuantity(1, 12)
        val operation = queue.next()!!
        queue.recordConflict(operation.operationId, server.current)
        queue.retryBlocked()
        assertTrue(queue.next()!!.blocked)
        assertEquals(1, queue.observeStatus().first().conflicts)
        try { repository.updateQuantity(1, 99); fail("Conflicting item was editable") }
        catch (_: IllegalStateException) { }
        assertEquals(12, repository.observeConflicts().first().single().localQuantity)
        assertEquals(1, queue.observeStatus().first().pending)
    }

    @Test
    fun keepLocalCollapsesOnlyThisItemsEditsIntoNewOperationAtFreshRevision() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.updateQuantity(1, 19)
        repository.updateQuantity(2, 4)
        val original = queue.next()!!
        queue.recordConflict(original.operationId, server.current)
        server.current = QuantitySnapshot(8, 2)
        repository.resolveConflict(original.operationId, ConflictChoice.KEEP_LOCAL)
        assertTrue(repository.observeConflicts().first().isEmpty())
        assertEquals(2, queue.observeStatus().first().pending)
        val other = queue.next()!!
        assertEquals(2L, other.itemId)
        queue.acknowledge(other.operationId, 1)
        val resolved = queue.next()!!
        assertNotEquals(original.operationId, resolved.operationId)
        assertEquals(19, resolved.quantity)
        assertEquals(2L, resolved.baseRevision)
        assertEquals(19, database.inventoryDao().item(1)!!.countedQuantity)
    }

    @Test
    fun useServerDiscardsOnlyChosenItemsChangesAndCanAcceptZero() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.updateQuantity(1, 19)
        repository.updateQuantity(2, 4)
        val original = queue.next()!!
        queue.recordConflict(original.operationId, server.current)
        server.current = QuantitySnapshot(0, 3)
        repository.resolveConflict(original.operationId, ConflictChoice.USE_SERVER)
        assertEquals(0, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(3L, database.inventoryDao().item(1)!!.serverRevision)
        assertEquals(1, queue.observeStatus().first().pending)
        assertEquals(2L, queue.next()!!.itemId)
        assertTrue(repository.observeConflicts().first().isEmpty())
    }

    @Test
    fun failedServerRefreshLeavesConflictAndAllLocalEditsIntact() = runBlocking {
        repository.updateQuantity(1, 12)
        val original = queue.next()!!
        queue.recordConflict(original.operationId, server.current)
        server.fail = true
        try { repository.resolveConflict(original.operationId, ConflictChoice.USE_SERVER); fail("Network error expected") }
        catch (_: IOException) { }
        assertEquals(12, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(original.operationId, queue.next()!!.operationId)
        assertEquals(1, repository.observeConflicts().first().size)
    }

    @Test
    fun taskProgressDoesNotMultiplyCountsWhenOneItemHasSeveralOperations() = runBlocking {
        repository.updateQuantity(1, 0)
        repository.updateQuantity(1, 12)
        repository.updateQuantity(2, 12)
        val progress = RoomInventoryReadModel(database).observeTaskProgress().first().single()
        assertEquals(3, progress.totalItems)
        assertEquals(2, progress.countedItems)
        assertEquals(1, progress.differences)
        assertEquals(3, progress.pendingOperations)
    }

    @Test
    fun operationReadModelShowsFailuresAndConflictResolutionRemovesOnlyActualQueue() = runBlocking {
        repository.updateQuantity(1, 12)
        val operation = queue.next()!!
        queue.recordFailure(operation.operationId, "HTTP 503", false)
        val readModel = RoomInventoryReadModel(database)
        val failed = readModel.observeSyncOperations().first().single()
        assertEquals(dev.stockflow.domain.OperationStatus.RETRY, failed.status)
        assertEquals(1, failed.attempts)
        assertEquals("HTTP 503", failed.lastError)
        server.current = QuantitySnapshot(7, 2)
        queue.recordConflict(operation.operationId, server.current)
        assertEquals(dev.stockflow.domain.OperationStatus.CONFLICT, readModel.observeSyncOperations().first().single().status)
        assertEquals(1, readModel.observeTaskProgress().first().single().conflicts)
        repository.resolveConflict(operation.operationId, ConflictChoice.USE_SERVER)
        assertTrue(readModel.observeSyncOperations().first().isEmpty())
        assertEquals(0, readModel.observeTaskProgress().first().single().conflicts)
    }

    @Test
    fun discardingRejectedChangesRestoresServerAndUnblocksOtherItems() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.updateQuantity(1, 19)
        repository.updateQuantity(2, 4)
        val rejected = queue.next()!!
        queue.recordFailure(rejected.operationId, "HTTP 400", true)
        server.current = QuantitySnapshot(0, 3)
        repository.discardRejectedChanges(rejected.operationId)
        assertEquals(0, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(3L, database.inventoryDao().item(1)!!.serverRevision)
        assertEquals(1, queue.observeStatus().first().pending)
        assertEquals(2L, queue.next()!!.itemId)
        assertEquals(SyncResult.Drained, SyncEngine(queue) { RemoteResult.Accepted(it.baseRevision + 1) }.synchronize())
        assertEquals(4, database.inventoryDao().item(2)!!.countedQuantity)
    }

    @Test
    fun failedDiscardPreservesCountAndEntireQueue() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.updateQuantity(1, 19)
        val rejected = queue.next()!!
        queue.recordFailure(rejected.operationId, "HTTP 400", true)
        server.fail = true
        try { repository.discardRejectedChanges(rejected.operationId); fail("Must retain data") }
        catch (_: IOException) { }
        assertEquals(19, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(2, queue.observeStatus().first().pending)
        assertTrue(queue.next()!!.blocked)
    }

    @Test
    fun confirmedDeletedServerItemCanBeRemovedWithoutBlockingOtherItems() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.updateQuantity(2, 4)
        val rejected = queue.next()!!
        queue.recordFailure(rejected.operationId, "HTTP 404", true)
        server.missing = true
        repository.discardRejectedChanges(rejected.operationId)
        assertNull(database.inventoryDao().item(1))
        assertEquals(1, queue.observeStatus().first().pending)
        assertEquals(2L, queue.next()!!.itemId)
        assertEquals(4, database.inventoryDao().item(2)!!.countedQuantity)
    }

    @Test
    fun retryDuringServerCheckPreventsDiscardingAnInFlightOperation() = runBlocking {
        repository.updateQuantity(1, 12)
        val rejected = queue.next()!!
        queue.recordFailure(rejected.operationId, "HTTP 400", true)
        server.beforeReturn = { queue.retryBlocked() }
        try { repository.discardRejectedChanges(rejected.operationId); fail("Operation changed during fetch") }
        catch (_: IllegalStateException) { }
        assertEquals(12, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(rejected.operationId, queue.next()!!.operationId)
        assertEquals(1, queue.observeStatus().first().pending)
    }

    @Test
    fun failedRestoreRollsBackDeletionOfPendingOperations() = runBlocking {
        repository.updateQuantity(1, 12)
        val rejected = queue.next()!!
        queue.recordFailure(rejected.operationId, "HTTP 400", true)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_restore BEFORE UPDATE ON items
            BEGIN SELECT RAISE(ABORT, 'simulated disk write failure'); END
        """.trimIndent())
        try { repository.discardRejectedChanges(rejected.operationId); fail("Restore must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(12, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(rejected.operationId, queue.next()!!.operationId)
        assertTrue(queue.next()!!.blocked)
    }

    @Test
    fun activeOrConflictedOperationCannotBeDiscardedAsRejected() = runBlocking {
        repository.updateQuantity(1, 12)
        val operation = queue.next()!!
        try { repository.discardRejectedChanges(operation.operationId); fail("Active operation") }
        catch (_: IllegalStateException) { }
        queue.recordConflict(operation.operationId, server.current)
        try { repository.discardRejectedChanges(operation.operationId); fail("Use explicit conflict resolution") }
        catch (_: IllegalStateException) { }
        assertEquals(12, database.inventoryDao().item(1)!!.countedQuantity)
        assertEquals(1, queue.observeStatus().first().conflicts)
    }

    private class FakeServer : InventoryServer {
        var current = QuantitySnapshot(7, 1)
        var fail = false
        var missing = false
        var beforeReturn: (suspend () -> Unit)? = null
        override suspend fun loadCatalog() = InventoryCatalog(
            listOf(InventoryTask(1, "Server task", "Warehouse")),
            listOf(CatalogItem(1, 1, "Server item", "2000000000015", 24, current)),
        )
        override suspend fun getQuantity(itemId: Long): QuantitySnapshot {
            if (fail) throw IOException("offline")
            if (missing) throw RemoteItemNotFoundException()
            beforeReturn?.invoke()
            return current
        }
    }
}
