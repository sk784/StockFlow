package dev.stockflow.data.sync

import com.google.gson.Gson
import dev.stockflow.domain.*
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Exercises the real converter, not an API fake that already returns decoded objects. */
class InventoryHttpContractTest {
    @Test fun malformedSuccessCanRecoverWithSameOperationAndFrozenPayload() {
        listOf("""{"quantity":"not-a-number","revision":5}""", """{"quantity": """, "null").forEach { broken ->
            withResponses(listOf(broken, """{"quantity":12,"revision":5}""")) { remote, requests ->
                val queue = MemoryQueue()
                val engine = SyncEngine(queue, remote)
                assertEquals(SyncResult.Retry, engine.synchronize())
                assertEquals(1, queue.failures)
                assertNotNull(queue.reason)
                assertEquals("stable-key", queue.pending!!.operationId)
                assertEquals(SyncResult.Drained, engine.synchronize())
                assertNull(queue.pending)
                assertEquals(listOf("stable-key", "stable-key"), requests.map { it.first })
                assertEquals(listOf(QuantityRequest(12, 4), QuantityRequest(12, 4)),
                    requests.map { Gson().fromJson(it.second, QuantityRequest::class.java) })
            }
        }
    }

    @Test fun wrongAcknowledgementIsRetriedWithoutDiscardingACommittedRequest() {
        withResponses(listOf("""{"quantity":99,"revision":5}""")) { remote, _ ->
            val queue = MemoryQueue()
            assertEquals(SyncResult.Retry, SyncEngine(queue, remote).synchronize())
            assertFalse(queue.pending!!.blocked)
            assertEquals(1, queue.failures)
        }
    }

    private fun withResponses(bodies: List<String>, block: suspend (RetrofitInventoryRemote, List<Pair<String, String>>) -> Unit) = runBlocking {
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        val executor = Executors.newSingleThreadExecutor()
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val served = executor.submit {
            bodies.forEach { body ->
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableMapOf<String, String>()
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.contains(':')) headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        line = reader.readLine()
                    }
                    val payload = CharArray(headers.getValue("content-length").toInt())
                    var offset = 0
                    while (offset < payload.size) {
                        val count = reader.read(payload, offset, payload.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    requests += headers.getValue("idempotency-key") to String(payload)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val api = Retrofit.Builder().baseUrl("http://127.0.0.1:${server.localPort}/")
                .client(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(InventoryApi::class.java)
            block(RetrofitInventoryRemote(api), requests)
            served.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private class MemoryQueue : SyncQueue {
        var pending: PendingOperation? = PendingOperation("stable-key", 1, 12, baseRevision = 4)
        var failures = 0
        var reason: String? = null
        override fun observeStatus() = flowOf(QueueStatus(if (pending == null) 0 else 1))
        override suspend fun next() = pending
        override suspend fun acknowledge(operationId: String, revision: Long) { pending = null }
        override suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean) {
            failures++
            this.reason = reason
            pending = pending!!.copy(attempts = failures, blocked = blocked)
        }
        override suspend fun recordConflict(operationId: String, remote: QuantitySnapshot) { error("Unexpected conflict") }
        override suspend fun retryBlocked() { pending = pending?.copy(blocked = false) }
    }
}
