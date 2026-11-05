package dev.stockflow.domain

import kotlinx.coroutines.flow.Flow

data class InventoryTask(val id: Long, val title: String, val location: String)

data class InventoryItem(
    val id: Long,
    val taskId: Long,
    val name: String,
    val barcode: String,
    val expectedQuantity: Int,
    val countedQuantity: Int?,
)

interface InventoryRepository {
    fun observeTasks(): Flow<List<InventoryTask>>
    fun observeItems(taskId: Long): Flow<List<InventoryItem>>
    suspend fun createDemoTask()
    suspend fun updateQuantity(itemId: Long, quantity: Int)
    suspend fun discardRejectedChanges(operationId: String)
}

/** Null means the item has not been counted; zero is a valid count. */
fun parseQuantity(input: String): Int? = input.takeIf {
    it.isNotEmpty() && it.all { character -> character in '0'..'9' }
}?.toIntOrNull()

class UpdateQuantity(private val repository: InventoryRepository) {
    suspend operator fun invoke(itemId: Long, input: String) {
        val quantity = requireNotNull(parseQuantity(input)) { "Введите целое неотрицательное число" }
        repository.updateQuantity(itemId, quantity)
    }
}
