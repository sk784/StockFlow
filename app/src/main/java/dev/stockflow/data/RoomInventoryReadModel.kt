package dev.stockflow.data

import dev.stockflow.domain.InventoryReadModel
import javax.inject.Inject

class RoomInventoryReadModel @Inject constructor(private val database: InventoryDatabase) : InventoryReadModel {
    override fun observeTaskProgress() = database.inventoryDao().observeTaskProgress()
    override fun observeSyncOperations() = database.syncDao().observeOperations()
}
