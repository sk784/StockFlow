package dev.stockflow.data

import androidx.room.withTransaction
import dev.stockflow.domain.CatalogRepository
import dev.stockflow.domain.ConflictChoice
import dev.stockflow.domain.ConflictRepository
import dev.stockflow.domain.InventoryServer
import dev.stockflow.domain.QuantitySnapshot
import dev.stockflow.domain.mergeRemoteCount
import dev.stockflow.domain.InventoryItem
import dev.stockflow.domain.InventoryRepository
import dev.stockflow.domain.InventoryTask
import dev.stockflow.domain.RemoteItemNotFoundException
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import java.util.UUID
import dev.stockflow.data.sync.OperationEntity

class RoomInventoryRepository @Inject constructor(
    private val database: InventoryDatabase,
    private val server: InventoryServer,
) : InventoryRepository, CatalogRepository, ConflictRepository {
    private val dao = database.inventoryDao()

    override fun observeTasks() = dao.observeTasks().map { tasks ->
        tasks.map { InventoryTask(it.id, it.title, it.location) }
    }

    override fun observeItems(taskId: Long) = dao.observeItems(taskId).map { items ->
        items.map { InventoryItem(it.id, it.taskId, it.name, it.barcode, it.expectedQuantity, it.countedQuantity) }
    }

    // Fixed IDs and IGNORE make repeated demo creation safe without resetting counts.
    override suspend fun createDemoTask() = database.withTransaction {
        dao.insertTask(TaskEntity(1, "Демо: инвентаризация", "Учебный склад · Стеллаж A"))
        dao.insertItems(listOf(
            ItemEntity(1, 1, "Кабель USB-C", "2000000000015", 24),
            ItemEntity(2, 1, "Зарядное устройство", "2000000000022", 12),
            ItemEntity(3, 1, "Беспроводная мышь", "2000000000039", 8),
        ))
    }

    override suspend fun updateQuantity(itemId: Long, quantity: Int) {
        require(quantity >= 0)
        database.withTransaction {
            check(!database.syncDao().hasConflict(itemId)) { "Сначала разрешите конфликт товара" }
            check(dao.updateQuantity(itemId, quantity) == 1) { "Товар больше не существует" }
            database.syncDao().insert(OperationEntity(
                operationId = UUID.randomUUID().toString(), itemId = itemId, quantity = quantity,
            ))
        }
    }

    override suspend fun refreshTasks() {
        val catalog = server.loadCatalog().validate()
        database.withTransaction {
            dao.upsertTasks(catalog.tasks.map { TaskEntity(it.id, it.title, it.location) })
            catalog.items.forEach { remote ->
                val local = dao.item(remote.id)
                val snapshot = if (local == null) remote.snapshot else mergeRemoteCount(
                    QuantitySnapshot(local.countedQuantity, local.serverRevision), remote.snapshot,
                    database.syncDao().hasPending(remote.id),
                )
                dao.upsertItem(ItemEntity(remote.id, remote.taskId, remote.name, remote.barcode,
                    remote.expectedQuantity, snapshot.quantity, snapshot.revision))
            }
        }
    }

    override suspend fun discardRejectedChanges(operationId: String) {
        val sync = database.syncDao()
        val original = checkNotNull(sync.operation(operationId)) { "Операция уже обработана" }
        check(original.blocked && original.conflictRevision == null) { "Операция уже изменилась" }
        val remote = try {
            server.getQuantity(original.itemId)
        } catch (_: RemoteItemNotFoundException) {
            null
        }
        database.withTransaction {
            // A manual retry during the fetch must not let us discard an in-flight request.
            val current = checkNotNull(sync.operation(operationId)) { "Операция уже обработана" }
            check(current == original && current.blocked && current.conflictRevision == null) {
                "Операция уже изменилась. Обновите очередь и повторите."
            }
            val item = checkNotNull(dao.item(current.itemId))
            sync.deleteForItem(item.id)
            if (remote == null) dao.deleteItem(item.id)
            else dao.upsertItem(item.copy(countedQuantity = remote.quantity, serverRevision = remote.revision))
        }
    }

    override fun observeConflicts() = database.syncDao().observeConflicts()

    override suspend fun resolveConflict(operationId: String, choice: ConflictChoice) {
        val sync = database.syncDao()
        val conflict = checkNotNull(sync.operation(operationId)) { "Конфликт уже разрешён" }
        check(conflict.conflictRevision != null)
        // Refresh outside the Room transaction. A new server edit after this fetch causes a new 409.
        val remote = server.getQuantity(conflict.itemId)
        database.withTransaction {
            val current = checkNotNull(sync.operation(operationId)) { "Конфликт уже разрешён" }
            check(current.conflictRevision != null)
            val item = checkNotNull(dao.item(current.itemId))
            val localQuantity = item.countedQuantity
            if (choice == ConflictChoice.KEEP_LOCAL) checkNotNull(localQuantity)
            sync.deleteForItem(item.id)
            dao.upsertItem(item.copy(
                countedQuantity = if (choice == ConflictChoice.USE_SERVER) remote.quantity else localQuantity,
                serverRevision = remote.revision,
            ))
            if (choice == ConflictChoice.KEEP_LOCAL) {
                sync.insert(OperationEntity(operationId = UUID.randomUUID().toString(), itemId = item.id,
                    quantity = checkNotNull(localQuantity), baseRevision = remote.revision))
            }
        }
    }
}
