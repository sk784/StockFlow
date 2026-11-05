package dev.stockflow.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pending_operations", indices = [Index(value = ["operationId"], unique = true)])
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val itemId: Long,
    val quantity: Int,
    val attempts: Int = 0,
    val blocked: Boolean = false,
    val lastError: String? = null,
    val baseRevision: Long? = null,
    val conflictRevision: Long? = null,
    val conflictQuantity: Int? = null,
)
