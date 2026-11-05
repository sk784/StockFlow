package dev.stockflow.data.sync

import dev.stockflow.domain.PendingOperation
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.RemoteResult
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class RetrofitInventoryRemoteTest {
    private val operation = PendingOperation("stable-key", 1, 12, baseRevision = 4)

    @Test
    fun `request keeps operation key quantity and frozen revision across retries`() = runBlocking {
        val api = FakeApi()
        val remote = RetrofitInventoryRemote(api)
        api.response = Response.error(503, "".toResponseBody())
        assertTrue(remote.send(operation) is RemoteResult.Retryable)
        api.response = Response.success(QuantityDto(12, 5))
        assertEquals(RemoteResult.Accepted(5), remote.send(operation))
        assertEquals(listOf("stable-key", "stable-key"), api.calls.map { it.first })
        assertEquals(listOf(QuantityRequest(12, 4), QuantityRequest(12, 4)), api.calls.map { it.second })
    }

    @Test
    fun `invalid acknowledgements never remove a local operation`() = runBlocking {
        val api = FakeApi()
        val remote = RetrofitInventoryRemote(api)
        listOf(QuantityDto(99, 5), QuantityDto(12, 4), QuantityDto(12, 6), QuantityDto(null, 5),
            QuantityDto(12, null)).forEach { body ->
            api.response = Response.success(body)
            assertTrue(remote.send(operation) is RemoteResult.Retryable)
        }
        api.response = Response.success<QuantityDto>(204, null)
        assertTrue(remote.send(operation) is RemoteResult.Rejected)
    }

    @Test
    fun `revision conflict preserves remote zero and version`() = runBlocking {
        val api = FakeApi()
        api.response = error(409, """{"reason":"revision_conflict","quantity":0,"revision":8}""")
        assertEquals(RemoteResult.Conflict(QuantitySnapshot(0, 8)), RetrofitInventoryRemote(api).send(operation))
    }

    @Test
    fun `malformed conflict and key reuse are rejected without inventing server state`() = runBlocking {
        val api = FakeApi()
        val remote = RetrofitInventoryRemote(api)
        listOf("not json", "null", "{}", """{"reason":"revision_conflict","revision":-1}""",
            """{"reason":"idempotency_conflict"}""").forEach { body ->
            api.response = error(409, body)
            assertTrue(remote.send(operation) is RemoteResult.Rejected)
        }
    }

    @Test
    fun `temporary and permanent http errors have distinct retry policies`() = runBlocking {
        val api = FakeApi()
        val remote = RetrofitInventoryRemote(api)
        listOf(408, 425, 429, 500, 503).forEach { code ->
            api.response = error(code, "")
            assertTrue("HTTP $code", remote.send(operation) is RemoteResult.Retryable)
        }
        listOf(400, 401, 403, 404, 409).forEach { code ->
            api.response = error(code, "")
            assertTrue("HTTP $code", remote.send(operation) is RemoteResult.Rejected)
        }
    }

    @Test
    fun `catalog maps parent task and validates before returning`() = runBlocking {
        val api = FakeApi()
        val catalog = RetrofitInventoryRemote(api).loadCatalog()
        assertEquals(2L, catalog.items.single().taskId)
        assertEquals("00123", catalog.items.single().barcode)
        assertEquals(QuantitySnapshot(null, 0), catalog.items.single().snapshot)
        api.catalog = api.catalog + api.catalog
        try { RetrofitInventoryRemote(api).loadCatalog(); fail("Duplicate tasks accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `single item fetch returns validated latest snapshot`() = runBlocking {
        assertEquals(QuantitySnapshot(0, 8), RetrofitInventoryRemote(FakeApi()).getQuantity(1))
    }

    @Test
    fun `confirmed missing item is distinguished from an unavailable server`() = runBlocking {
        val api = FakeApi()
        api.quantityError = retrofit2.HttpException(error(404, ""))
        try { RetrofitInventoryRemote(api).getQuantity(1); fail("Expected missing item") }
        catch (_: dev.stockflow.domain.RemoteItemNotFoundException) { }
        api.quantityError = retrofit2.HttpException(error(503, ""))
        try { RetrofitInventoryRemote(api).getQuantity(1); fail("Expected unavailable server") }
        catch (failure: retrofit2.HttpException) { assertEquals(503, failure.code()) }
    }

    private fun error(code: Int, body: String): Response<QuantityDto> =
        Response.error(code, body.toResponseBody("application/json".toMediaType()))

    private class FakeApi : InventoryApi {
        var response: Response<QuantityDto> = Response.success(QuantityDto(12, 5))
        var catalog = listOf(CatalogTaskDto(2, "Task", "Warehouse",
            listOf(CatalogItemDto(1, "Item", "00123", 10, null, 0))))
        val calls = mutableListOf<Pair<String, QuantityRequest>>()
        var quantityError: Exception? = null
        override suspend fun tasks() = catalog
        override suspend fun quantity(itemId: Long): QuantityDto {
            quantityError?.let { throw it }
            return QuantityDto(0, 8)
        }
        override suspend fun setQuantity(itemId: Long, operationId: String, request: QuantityRequest): Response<QuantityDto> {
            calls += operationId to request
            return response
        }
    }
}
