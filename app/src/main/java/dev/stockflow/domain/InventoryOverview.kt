package dev.stockflow.domain

import kotlinx.coroutines.flow.Flow

data class TaskProgress(
    val id: Long,
    val title: String,
    val location: String,
    val totalItems: Int,
    val countedItems: Int,
    val differences: Int,
    val pendingOperations: Int,
    val blockedOperations: Int,
    val conflicts: Int,
) {
    val stage: TaskStage get() = when {
        totalItems > 0 && countedItems == totalItems -> TaskStage.COUNTED
        countedItems > 0 -> TaskStage.IN_PROGRESS
        else -> TaskStage.NEW
    }
    val needsAttention: Boolean get() = differences > 0 || blockedOperations > 0 || conflicts > 0
    val progress: Float get() = if (totalItems == 0) 0f else countedItems.toFloat() / totalItems
}

enum class TaskStage { NEW, IN_PROGRESS, COUNTED }
enum class TaskFilter { ALL, NEW, IN_PROGRESS, COUNTED, ATTENTION }
enum class ItemFilter { ALL, UNCOUNTED, COUNTED, DIFFERENCES }

data class SyncOperation(
    val operationId: String,
    val itemId: Long,
    val itemName: String,
    val taskId: Long,
    val taskTitle: String,
    val quantity: Int,
    val attempts: Int,
    val blocked: Boolean,
    val baseRevision: Long?,
    val lastError: String?,
    val conflictRevision: Long?,
) {
    val status: OperationStatus get() = when {
        conflictRevision != null -> OperationStatus.CONFLICT
        blocked -> OperationStatus.REJECTED
        attempts > 0 -> OperationStatus.RETRY
        else -> OperationStatus.QUEUED
    }
}

enum class OperationStatus { QUEUED, RETRY, REJECTED, CONFLICT }

interface InventoryReadModel {
    fun observeTaskProgress(): Flow<List<TaskProgress>>
    fun observeSyncOperations(): Flow<List<SyncOperation>>
}

enum class ConnectionState { UNKNOWN, CONNECTED, OFFLINE }
interface ConnectionMonitor {
    fun observeConnection(): Flow<ConnectionState>
}

fun filterTasks(tasks: List<TaskProgress>, query: String, filter: TaskFilter): List<TaskProgress> {
    val search = query.trim()
    return tasks.filter { task ->
        (search.isEmpty() || task.title.contains(search, true) || task.location.contains(search, true)) && when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.NEW -> task.stage == TaskStage.NEW
            TaskFilter.IN_PROGRESS -> task.stage == TaskStage.IN_PROGRESS
            TaskFilter.COUNTED -> task.stage == TaskStage.COUNTED
            TaskFilter.ATTENTION -> task.needsAttention
        }
    }
}

fun filterItems(items: List<InventoryItem>, query: String, filter: ItemFilter): List<InventoryItem> {
    val search = query.trim()
    return items.filter { item ->
        (search.isEmpty() || item.name.contains(search, true) || item.barcode.contains(search)) && when (filter) {
            ItemFilter.ALL -> true
            ItemFilter.UNCOUNTED -> item.countedQuantity == null
            ItemFilter.COUNTED -> item.countedQuantity != null
            ItemFilter.DIFFERENCES -> item.countedQuantity != null && item.countedQuantity != item.expectedQuantity
        }
    }
}
