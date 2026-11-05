package dev.stockflow.domain

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingOperation(
    val operationId: String,
    val itemId: Long,
    val quantity: Int,
    val attempts: Int = 0,
    val blocked: Boolean = false,
    val baseRevision: Long = 0,
)

data class QueueStatus(val pending: Int = 0, val blocked: Int = 0, val conflicts: Int = 0)

interface SyncQueue {
    fun observeStatus(): Flow<QueueStatus>
    suspend fun next(): PendingOperation?
    suspend fun acknowledge(operationId: String, revision: Long)
    suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean)
    suspend fun recordConflict(operationId: String, remote: QuantitySnapshot)
    suspend fun retryBlocked()
}

sealed interface RemoteResult {
    data class Accepted(val revision: Long) : RemoteResult
    data class Conflict(val remote: QuantitySnapshot) : RemoteResult
    data class Retryable(val reason: String) : RemoteResult
    data class Rejected(val reason: String) : RemoteResult
}

fun interface InventoryRemote {
    /** The same operation ID and payload must be reused after a lost response. */
    suspend fun send(operation: PendingOperation): RemoteResult
}

interface SyncScheduler {
    fun enqueue()
    fun enqueueNow()
}

sealed interface SyncResult {
    data object Drained : SyncResult
    data object Retry : SyncResult
    data object Blocked : SyncResult
}

/** One FIFO consumer per process; Room remains the durable source of the queue. */
class SyncEngine(private val queue: SyncQueue, private val remote: InventoryRemote) {
    private val mutex = Mutex()

    suspend fun synchronize(batchSize: Int = 100): SyncResult = mutex.withLock {
        require(batchSize > 0)
        repeat(batchSize) {
            val operation = queue.next() ?: return@withLock SyncResult.Drained
            if (operation.blocked) return@withLock SyncResult.Blocked
            val result = try {
                remote.send(operation)
            } catch (_: IOException) {
                RemoteResult.Retryable("Сеть недоступна")
            }
            when (result) {
                is RemoteResult.Accepted -> queue.acknowledge(operation.operationId, result.revision)
                is RemoteResult.Conflict -> {
                    queue.recordConflict(operation.operationId, result.remote)
                    return@withLock SyncResult.Blocked
                }
                is RemoteResult.Retryable -> {
                    queue.recordFailure(operation.operationId, result.reason, blocked = false)
                    return@withLock SyncResult.Retry
                }
                is RemoteResult.Rejected -> {
                    queue.recordFailure(operation.operationId, result.reason, blocked = true)
                    return@withLock SyncResult.Blocked
                }
            }
        }
        if (queue.next() == null) SyncResult.Drained else SyncResult.Retry
    }
}
