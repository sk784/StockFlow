package dev.stockflow.data.sync

import androidx.room.withTransaction
import dev.stockflow.data.InventoryDatabase
import dev.stockflow.domain.PendingOperation
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.SyncQueue
import javax.inject.Inject

class RoomSyncQueue @Inject constructor(private val database: InventoryDatabase) : SyncQueue {
    private val dao = database.syncDao()
    override fun observeStatus() = dao.observeStatus()

    // Freeze the expected revision before the first request. Retries reuse the exact same payload.
    override suspend fun next(): PendingOperation? = database.withTransaction {
        val operation = dao.next() ?: return@withTransaction null
        val revision = operation.baseRevision ?: checkNotNull(database.inventoryDao().item(operation.itemId))
            .serverRevision.also { dao.bindRevision(operation.operationId, it) }
        PendingOperation(operation.operationId, operation.itemId, operation.quantity,
            operation.attempts, operation.blocked, revision)
    }

    override suspend fun acknowledge(operationId: String, revision: Long) = database.withTransaction {
        val operation = dao.operation(operationId) ?: return@withTransaction
        database.inventoryDao().setRevision(operation.itemId, revision)
        dao.acknowledge(operationId)
    }

    override suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean) =
        dao.recordFailure(operationId, reason, blocked)

    override suspend fun recordConflict(operationId: String, remote: QuantitySnapshot) =
        dao.recordConflict(operationId, remote.quantity, remote.revision)

    override suspend fun retryBlocked() = dao.retryBlocked()
}
