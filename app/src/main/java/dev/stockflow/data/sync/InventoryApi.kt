package dev.stockflow.data.sync

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import dev.stockflow.domain.CatalogItem
import dev.stockflow.domain.InventoryCatalog
import dev.stockflow.domain.InventoryRemote
import dev.stockflow.domain.InventoryServer
import dev.stockflow.domain.InventoryTask
import dev.stockflow.domain.PendingOperation
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.RemoteResult
import dev.stockflow.domain.RemoteItemNotFoundException
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import javax.inject.Inject
import javax.inject.Singleton

data class QuantityRequest(val quantity: Int, val baseRevision: Long)
data class QuantityDto(val quantity: Int?, val revision: Long?) {
    fun snapshot() = QuantitySnapshot(quantity, requireNotNull(revision))
}
data class ConflictDto(val reason: String?, val quantity: Int?, val revision: Long?)
data class CatalogItemDto(
    val id: Long?, val name: String?, val barcode: String?, val expectedQuantity: Int?,
    val countedQuantity: Int?, val revision: Long?,
)
data class CatalogTaskDto(val id: Long?, val title: String?, val location: String?, val items: List<CatalogItemDto>?)

interface InventoryApi {
    @GET("api/tasks")
    suspend fun tasks(): List<CatalogTaskDto>

    @GET("api/items/{itemId}")
    suspend fun quantity(@Path("itemId") itemId: Long): QuantityDto

    @PUT("api/items/{itemId}/quantity")
    suspend fun setQuantity(
        @Path("itemId") itemId: Long,
        @Header("Idempotency-Key") operationId: String,
        @Body request: QuantityRequest,
    ): Response<QuantityDto>
}

@Singleton
class RetrofitInventoryRemote @Inject constructor(private val api: InventoryApi) : InventoryRemote, InventoryServer {
    override suspend fun loadCatalog(): InventoryCatalog {
        val tasks = api.tasks()
        return InventoryCatalog(
            tasks.map { InventoryTask(requireNotNull(it.id), requireNotNull(it.title), requireNotNull(it.location)) },
            tasks.flatMap { task ->
                requireNotNull(task.items).map { item ->
                    CatalogItem(requireNotNull(item.id), requireNotNull(task.id), requireNotNull(item.name),
                        requireNotNull(item.barcode), requireNotNull(item.expectedQuantity),
                        QuantitySnapshot(item.countedQuantity, requireNotNull(item.revision)))
                }
            },
        ).validate()
    }

    override suspend fun getQuantity(itemId: Long): QuantitySnapshot = try {
        api.quantity(itemId).snapshot()
    } catch (failure: HttpException) {
        if (failure.code() == 404) throw RemoteItemNotFoundException()
        throw failure
    }

    override suspend fun send(operation: PendingOperation): RemoteResult {
        val response = try {
            api.setQuantity(operation.itemId, operation.operationId,
                QuantityRequest(operation.quantity, operation.baseRevision))
        } catch (_: JsonParseException) {
            // The server may have committed. Keep the UUID and frozen payload for a safe retry.
            return RemoteResult.Retryable("Не удалось прочитать ответ сервера. Повторим отправку.")
        } catch (_: MalformedJsonException) {
            return RemoteResult.Retryable("Не удалось прочитать ответ сервера. Повторим отправку.")
        } catch (_: EOFException) {
            return RemoteResult.Retryable("Ответ сервера неполный. Повторим отправку.")
        }
        if (response.code() == 200) {
            val body = response.body()
            return if (body?.quantity == operation.quantity && operation.baseRevision < Long.MAX_VALUE &&
                body.revision == operation.baseRevision + 1) {
                RemoteResult.Accepted(checkNotNull(body.revision))
            } else RemoteResult.Retryable("Некорректное подтверждение сервера. Повторим отправку.")
        }
        if (response.code() == 409) {
            val conflict = try {
                response.errorBody()?.use { Gson().fromJson(it.string(), ConflictDto::class.java) }
            } catch (_: JsonParseException) { null }
            if (conflict?.reason == "revision_conflict" && conflict.revision != null && conflict.revision >= 0 &&
                (conflict.quantity == null || conflict.quantity >= 0)) {
                return RemoteResult.Conflict(QuantitySnapshot(conflict.quantity, conflict.revision))
            }
        }
        return if (response.code() == 408 || response.code() == 425 || response.code() == 429 || response.code() >= 500) {
            RemoteResult.Retryable("Сервер временно недоступен: HTTP ${response.code()}")
        } else RemoteResult.Rejected("Сервер отклонил изменение: HTTP ${response.code()}")
    }
}
