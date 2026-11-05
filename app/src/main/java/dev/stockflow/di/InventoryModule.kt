package dev.stockflow.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.stockflow.data.AndroidConnectionMonitor
import dev.stockflow.data.RoomInventoryReadModel
import dev.stockflow.domain.ConnectionMonitor
import dev.stockflow.domain.InventoryReadModel
import dev.stockflow.data.InventoryDatabase
import dev.stockflow.data.MIGRATION_1_2
import dev.stockflow.data.MIGRATION_2_3
import dev.stockflow.domain.CatalogRepository
import dev.stockflow.domain.ConflictRepository
import dev.stockflow.data.RoomInventoryRepository
import dev.stockflow.domain.InventoryRepository
import dev.stockflow.domain.UpdateQuantity
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InventoryModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): InventoryDatabase =
        Room.databaseBuilder(context, InventoryDatabase::class.java, "inventory.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    @Provides
    @Singleton
    fun repository(implementation: RoomInventoryRepository): InventoryRepository = implementation

    @Provides
    fun updateQuantity(repository: InventoryRepository) = UpdateQuantity(repository)
    @Provides
    fun catalogRepository(implementation: RoomInventoryRepository): CatalogRepository = implementation

    @Provides
    fun conflictRepository(implementation: RoomInventoryRepository): ConflictRepository = implementation

    @Provides
    fun readModel(implementation: RoomInventoryReadModel): InventoryReadModel = implementation

    @Provides
    fun connectionMonitor(implementation: AndroidConnectionMonitor): ConnectionMonitor = implementation
}
