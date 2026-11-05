package dev.stockflow.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.stockflow.domain.QueueStatus
import dev.stockflow.domain.SyncOperation
import dev.stockflow.domain.InventoryConflict
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insert(operation: OperationEntity)

    @Query("SELECT * FROM pending_operations ORDER BY sequence LIMIT 1")
    suspend fun next(): OperationEntity?

    @Query("DELETE FROM pending_operations WHERE operationId = :operationId")
    suspend fun acknowledge(operationId: String)

    @Query("UPDATE pending_operations SET attempts = attempts + 1, lastError = :reason, blocked = :blocked WHERE operationId = :operationId")
    suspend fun recordFailure(operationId: String, reason: String, blocked: Boolean)

    @Query("UPDATE pending_operations SET blocked = 0, lastError = NULL WHERE blocked = 1 AND conflictRevision IS NULL")
    suspend fun retryBlocked()

    @Query("SELECT COUNT(*) AS pending, COALESCE(SUM(CASE WHEN blocked = 1 THEN 1 ELSE 0 END), 0) AS blocked, COUNT(conflictRevision) AS conflicts FROM pending_operations")
    fun observeStatus(): Flow<QueueStatus>

    @Query("SELECT * FROM pending_operations WHERE operationId = :operationId")
    suspend fun operation(operationId: String): OperationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pending_operations WHERE itemId = :itemId)")
    suspend fun hasPending(itemId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pending_operations WHERE itemId = :itemId AND conflictRevision IS NOT NULL)")
    suspend fun hasConflict(itemId: Long): Boolean

    @Query("UPDATE pending_operations SET baseRevision = :revision WHERE operationId = :operationId AND baseRevision IS NULL")
    suspend fun bindRevision(operationId: String, revision: Long)

    @Query("UPDATE pending_operations SET attempts = attempts + 1, blocked = 1, conflictRevision = :revision, conflictQuantity = :quantity, lastError = 'Конфликт версий' WHERE operationId = :operationId")
    suspend fun recordConflict(operationId: String, quantity: Int?, revision: Long)

    @Query("DELETE FROM pending_operations WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("SELECT p.operationId, p.itemId, i.name AS itemName, i.countedQuantity AS localQuantity, p.conflictQuantity AS remoteQuantity, p.conflictRevision AS remoteRevision FROM pending_operations p JOIN items i ON i.id = p.itemId WHERE p.conflictRevision IS NOT NULL ORDER BY p.sequence")
    fun observeConflicts(): Flow<List<InventoryConflict>>
    @Query("""
        SELECT p.operationId, p.itemId, i.name AS itemName, i.taskId, t.title AS taskTitle,
            p.quantity, p.attempts, p.blocked, p.baseRevision, p.lastError, p.conflictRevision
        FROM pending_operations p JOIN items i ON i.id = p.itemId JOIN tasks t ON t.id = i.taskId
        ORDER BY p.sequence
    """)
    fun observeOperations(): Flow<List<SyncOperation>>
}
