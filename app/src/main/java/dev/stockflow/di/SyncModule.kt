package dev.stockflow.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.stockflow.BuildConfig
import dev.stockflow.data.sync.InventoryApi
import dev.stockflow.data.sync.RetrofitInventoryRemote
import dev.stockflow.data.sync.RoomSyncQueue
import dev.stockflow.data.sync.WorkManagerSyncScheduler
import dev.stockflow.domain.InventoryRemote
import dev.stockflow.domain.InventoryServer
import dev.stockflow.domain.SyncEngine
import dev.stockflow.domain.SyncQueue
import dev.stockflow.domain.SyncScheduler
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun queue(implementation: RoomSyncQueue): SyncQueue = implementation

    @Provides
    @Singleton
    fun remote(implementation: RetrofitInventoryRemote): InventoryRemote = implementation

    @Provides
    fun server(implementation: RetrofitInventoryRemote): InventoryServer = implementation

    @Provides
    fun scheduler(implementation: WorkManagerSyncScheduler): SyncScheduler = implementation

    @Provides
    @Singleton
    fun engine(queue: SyncQueue, remote: InventoryRemote) = SyncEngine(queue, remote)

    @Provides
    @Singleton
    fun api(): InventoryApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(InventoryApi::class.java)
}
