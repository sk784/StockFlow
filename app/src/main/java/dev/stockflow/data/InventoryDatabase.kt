package dev.stockflow.data

import androidx.room.ColumnInfo
import androidx.room.Upsert
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import dev.stockflow.data.sync.OperationEntity
import dev.stockflow.data.sync.SyncDao
import dev.stockflow.domain.TaskProgress

@Entity(tableName = "tasks")
data class TaskEntity(@PrimaryKey val id: Long, val title: String, val location: String)

@Entity(
    tableName = "items",
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class ItemEntity(
    @PrimaryKey val id: Long,
    val taskId: Long,
    val name: String,
    val barcode: String,
    val expectedQuantity: Int,
    val countedQuantity: Int? = null,
    @ColumnInfo(defaultValue = "0") val serverRevision: Long = 0,
)

@Dao
interface InventoryDao {
    @Query("SELECT * FROM tasks ORDER BY id")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("""
        SELECT t.id, t.title, t.location,
            COALESCE(s.totalItems, 0) AS totalItems, COALESCE(s.countedItems, 0) AS countedItems,
            COALESCE(s.differences, 0) AS differences,
            COALESCE(p.pendingOperations, 0) AS pendingOperations,
            COALESCE(p.blockedOperations, 0) AS blockedOperations, COALESCE(p.conflicts, 0) AS conflicts
        FROM tasks t
        LEFT JOIN (
            SELECT taskId, COUNT(*) AS totalItems, COUNT(countedQuantity) AS countedItems,
                SUM(CASE WHEN countedQuantity IS NOT NULL AND countedQuantity != expectedQuantity THEN 1 ELSE 0 END) AS differences
            FROM items GROUP BY taskId
        ) s ON s.taskId = t.id
        LEFT JOIN (
            SELECT i.taskId, COUNT(*) AS pendingOperations,
                SUM(CASE WHEN p.blocked = 1 THEN 1 ELSE 0 END) AS blockedOperations,
                COUNT(p.conflictRevision) AS conflicts
            FROM pending_operations p JOIN items i ON i.id = p.itemId GROUP BY i.taskId
        ) p ON p.taskId = t.id
        ORDER BY t.id
    """)
    fun observeTaskProgress(): Flow<List<TaskProgress>>

    @Query("SELECT * FROM items WHERE taskId = :taskId ORDER BY id")
    fun observeItems(taskId: Long): Flow<List<ItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Upsert
    suspend fun upsertItem(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :itemId")
    suspend fun item(itemId: Long): ItemEntity?

    @Query("UPDATE items SET serverRevision = MAX(serverRevision, :revision) WHERE id = :itemId")
    suspend fun setRevision(itemId: Long, revision: Long)

    @Query("UPDATE items SET countedQuantity = :quantity WHERE id = :itemId")
    suspend fun updateQuantity(itemId: Long, quantity: Int): Int

    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)
}

@Database(entities = [TaskEntity::class, ItemEntity::class, OperationEntity::class], version = 3, exportSchema = true)
abstract class InventoryDatabase : RoomDatabase() {
    abstract fun inventoryDao(): InventoryDao
    abstract fun syncDao(): SyncDao
}
