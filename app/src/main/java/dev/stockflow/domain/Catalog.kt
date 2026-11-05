package dev.stockflow.domain

import kotlinx.coroutines.flow.Flow

data class QuantitySnapshot(val quantity: Int?, val revision: Long) {
    init {
        require(quantity == null || quantity >= 0)
        require(revision >= 0)
    }
}

data class CatalogItem(
    val id: Long,
    val taskId: Long,
    val name: String,
    val barcode: String,
    val expectedQuantity: Int,
    val snapshot: QuantitySnapshot,
)

data class InventoryCatalog(val tasks: List<InventoryTask>, val items: List<CatalogItem>) {
    fun validate(): InventoryCatalog {
        require(tasks.all { it.id > 0 && it.title.isNotBlank() })
        require(tasks.map { it.id }.distinct().size == tasks.size)
        val taskIds = tasks.map { it.id }.toSet()
        require(items.all {
            it.id > 0 && it.taskId in taskIds && it.name.isNotBlank() && it.barcode.isNotBlank() &&
                it.barcode == it.barcode.trim() && it.expectedQuantity >= 0
        })
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.taskId to it.barcode }.distinct().size == items.size)
        return this
    }
}

interface InventoryServer {
    suspend fun loadCatalog(): InventoryCatalog
    suspend fun getQuantity(itemId: Long): QuantitySnapshot
}

/** Only a confirmed HTTP 404 is mapped to absence; network/protocol errors must propagate. */
class RemoteItemNotFoundException : Exception("Товар отсутствует на сервере")

interface CatalogRepository {
    suspend fun refreshTasks()
}

data class InventoryConflict(
    val operationId: String,
    val itemId: Long,
    val itemName: String,
    val localQuantity: Int?,
    val remoteQuantity: Int?,
    val remoteRevision: Long,
)

enum class ConflictChoice { KEEP_LOCAL, USE_SERVER }

interface ConflictRepository {
    fun observeConflicts(): Flow<List<InventoryConflict>>
    suspend fun resolveConflict(operationId: String, choice: ConflictChoice)
}

/** A response started before an upload must not roll back its later acknowledgement. */
fun mergeRemoteCount(local: QuantitySnapshot, remote: QuantitySnapshot, hasPending: Boolean): QuantitySnapshot =
    if (hasPending || local.revision > remote.revision) local else remote

sealed interface BarcodeMatch {
    data class Found(val item: InventoryItem) : BarcodeMatch
    data object NotFound : BarcodeMatch
    data object Ambiguous : BarcodeMatch
    data object Empty : BarcodeMatch
}

fun findByBarcode(items: List<InventoryItem>, taskId: Long, input: String): BarcodeMatch {
    val barcode = input.trim()
    if (barcode.isEmpty()) return BarcodeMatch.Empty
    val matches = items.filter { it.taskId == taskId && it.barcode == barcode }
    return when (matches.size) {
        0 -> BarcodeMatch.NotFound
        1 -> BarcodeMatch.Found(matches.single())
        else -> BarcodeMatch.Ambiguous
    }
}
