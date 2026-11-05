package dev.stockflow.domain.sync

import dev.stockflow.domain.InventoryRemote
import dev.stockflow.domain.PendingOperation
import dev.stockflow.domain.QueueStatus
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.RemoteResult
import dev.stockflow.domain.SyncEngine
import dev.stockflow.domain.SyncQueue
import dev.stockflow.domain.SyncResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    @Test
    fun `sends operations in order including repeated edits of one item`() = runBlocking {
        val queue = FakeQueue(op("first", 2), op("second", 9))
        val sent = mutableListOf<PendingOperation>()
        val engine = SyncEngine(queue) { sent += it; RemoteResult.Accepted(1) }
        assertEquals(SyncResult.Drained, engine.synchronize())
        assertEquals(listOf(2, 9), sent.map { it.quantity })
        assertTrue(queue.operations.isEmpty())
    }

    @Test
    fun `lost response retries the same operation without duplicate server effect`() = runBlocking {
        val queue = FakeQueue(op("stable-key", 12))
        val applied = mutableSetOf<String>()
        val requestIds = mutableListOf<String>()
        var first = true
        val engine = SyncEngine(queue) {
            requestIds += it.operationId
            applied += it.operationId
            if (first) { first = false; throw IOException("response lost after commit") }
            RemoteResult.Accepted(1)
        }
        assertEquals(SyncResult.Retry, engine.synchronize())
        assertEquals(1, queue.operations.single().attempts)
        assertEquals(SyncResult.Drained, engine.synchronize())
        assertEquals(listOf("stable-key", "stable-key"), requestIds)
        assertEquals(1, applied.size)
    }

    @Test
    fun `retryable server error preserves FIFO and stops later requests`() = runBlocking {
        val queue = FakeQueue(op("first"), op("second"))
        var calls = 0
        val engine = SyncEngine(queue) { calls++; RemoteResult.Retryable("HTTP 503") }
        assertEquals(SyncResult.Retry, engine.synchronize())
        assertEquals(1, calls)
        assertEquals(listOf("first", "second"), queue.operations.map { it.operationId })
        assertEquals(1, queue.operations.first().attempts)
    }

    @Test
    fun `permanent rejection blocks the queue until explicit retry`() = runBlocking {
        val queue = FakeQueue(op("first"), op("second"))
        var calls = 0
        var reject = true
        val engine = SyncEngine(queue) {
            calls++
            if (reject) RemoteResult.Rejected("HTTP 409") else RemoteResult.Accepted(1)
        }
        assertEquals(SyncResult.Blocked, engine.synchronize())
        assertEquals(SyncResult.Blocked, engine.synchronize())
        assertEquals(1, calls)
        assertEquals(2, queue.operations.size)
        queue.retryBlocked()
        reject = false
        assertEquals(SyncResult.Drained, engine.synchronize())
    }

    @Test
    fun `acknowledging old operation does not delete an edit made during upload`() = runBlocking {
        val queue = FakeQueue(op("old", 1))
        val sent = mutableListOf<Int>()
        val engine = SyncEngine(queue) {
            sent += it.quantity
            if (it.operationId == "old") queue.operations += op("new", 7)
            RemoteResult.Accepted(1)
        }
        assertEquals(SyncResult.Drained, engine.synchronize())
        assertEquals(listOf(1, 7), sent)
    }

    @Test
    fun `cancellation leaves operation unchanged and propagates`() = runBlocking {
        val operation = op("cancelled")
        val queue = FakeQueue(operation)
        val engine = SyncEngine(queue) { throw CancellationException("stopped") }
        try {
            engine.synchronize()
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(listOf(operation), queue.operations)
    }

    @Test
    fun `unexpected failure is not disguised as a network problem`() = runBlocking {
        val queue = FakeQueue(op("bug"))
        val engine = SyncEngine(queue) { error("invalid client state") }
        try {
            engine.synchronize()
            fail("Unexpected exception swallowed")
        } catch (_: IllegalStateException) { }
        assertEquals(0, queue.operations.single().attempts)
    }

    @Test
    fun `bounded batch leaves remaining work for next worker attempt`() = runBlocking {
        val queue = FakeQueue(op("first"), op("second"))
        val engine = SyncEngine(queue) { RemoteResult.Accepted(1) }
        assertEquals(SyncResult.Retry, engine.synchronize(batchSize = 1))
        assertEquals("second", queue.operations.single().operationId)
        assertEquals(SyncResult.Drained, engine.synchronize(batchSize = 1))
    }

    @Test
    fun `concurrent workers cannot send the same queue head simultaneously`() = runBlocking {
        val queue = FakeQueue(op("single"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val engine = SyncEngine(queue) {
            calls++
            entered.complete(Unit)
            release.await()
            RemoteResult.Accepted(1)
        }
        val first = async { engine.synchronize() }
        entered.await()
        val second = async { engine.synchronize() }
        release.complete(Unit)
        assertEquals(SyncResult.Drained, first.await())
        assertEquals(SyncResult.Drained, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `empty queue never calls the network`() = runBlocking {
        val engine = SyncEngine(FakeQueue()) { error("No request expected") }
        assertEquals(SyncResult.Drained, engine.synchronize())
    }

    @Test
    fun `revision conflict blocks queue without discarding local edits`() = runBlocking {
        val queue = FakeQueue(op("stale", 12), op("newer-local", 19))
        var sent = 0
        val engine = SyncEngine(queue) {
            sent++
            RemoteResult.Conflict(QuantitySnapshot(7, 4))
        }
        assertEquals(SyncResult.Blocked, engine.synchronize())
        assertEquals(SyncResult.Blocked, engine.synchronize())
        assertEquals(1, sent)
        assertEquals(listOf(12, 19), queue.operations.map { it.quantity })
        assertTrue(queue.operations.first().blocked)
    }

    private fun op(id: String, quantity: Int = 0) = PendingOperation(id, itemId = 1, quantity = quantity)

    private class FakeQueue(vararg initial: PendingOperation) : SyncQueue {
        val operations = initial.toMutableList()
        override fun observeStatus() = flowOf(QueueStatus(operations.size, operations.count { it.blocked }))
        override suspend fun next() = operations.firstOrNull()
        override suspend fun acknowledge(operationId: String, revision: Long) {
            operations.removeAll { it.operationId == operationId }
        }
        override suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean) {
            val index = operations.indexOfFirst { it.operationId == operationId }
            operations[index] = operations[index].copy(attempts = operations[index].attempts + 1, blocked = blocked)
        }
        override suspend fun recordConflict(operationId: String, remote: QuantitySnapshot) {
            recordFailure(operationId, "conflict", true)
        }
        override suspend fun retryBlocked() {
            operations.replaceAll { it.copy(blocked = false) }
        }
    }
}
