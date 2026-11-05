package dev.stockflow.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stockflow.data.sync.OperationEntity
import dev.stockflow.domain.InventoryServer
import dev.stockflow.domain.InventoryCatalog
import dev.stockflow.domain.QuantitySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryPersistenceTest {
    private lateinit var database: InventoryDatabase
    private lateinit var repository: RoomInventoryRepository

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), InventoryDatabase::class.java,
        ).build()
        repository = RoomInventoryRepository(database, object : InventoryServer {
            override suspend fun loadCatalog(): InventoryCatalog = error("No network expected")
            override suspend fun getQuantity(itemId: Long): QuantitySnapshot = error("No network expected")
        })
        repository.createDemoTask()
    }

    @After
    fun close() = database.close()

    @Test
    fun zeroIsSavedWithDurableOperation() = runBlocking {
        repository.updateQuantity(1, 0)
        assertEquals(0, repository.observeItems(1).first().first().countedQuantity)
        val operation = database.syncDao().next()!!
        assertEquals(0, operation.quantity)
        assertEquals(1L, operation.itemId)
        assertTrue(operation.operationId.isNotBlank())
    }

    @Test
    fun repeatedEditsRemainDistinctAndAcknowledgingOldDoesNotEraseNew() = runBlocking {
        repository.updateQuantity(1, 2)
        val first = database.syncDao().next()!!
        repository.updateQuantity(1, 9)
        database.syncDao().acknowledge(first.operationId)
        val second = database.syncDao().next()!!
        assertNotEquals(first.operationId, second.operationId)
        assertEquals(9, second.quantity)
        assertEquals(9, repository.observeItems(1).first().first().countedQuantity)
    }

    @Test
    fun failedQueueInsertionRollsBackQuantityInSameTransaction() = runBlocking {
        val operation = OperationEntity(operationId = "duplicate", itemId = 1, quantity = 3)
        database.syncDao().insert(operation)
        try {
            database.withTransaction {
                database.inventoryDao().updateQuantity(1, 99)
                database.syncDao().insert(operation)
            }
            fail("Unique key violation expected")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertNull(repository.observeItems(1).first().first().countedQuantity)
        assertEquals(1, database.syncDao().observeStatus().first().pending)
    }

    @Test
    fun nonexistentItemCannotCreateOrphanOperation() = runBlocking {
        try {
            repository.updateQuantity(999, 4)
            fail("Missing item must be rejected")
        } catch (_: IllegalStateException) { }
        assertNull(database.syncDao().next())
    }

    @Test
    fun demoCreationDoesNotResetCountsOrQueue() = runBlocking {
        repository.updateQuantity(1, 12)
        repository.createDemoTask()
        assertEquals(12, repository.observeItems(1).first().first().countedQuantity)
        assertEquals(1, database.syncDao().observeStatus().first().pending)
    }
}
