package dev.stockflow

import android.app.Application
import androidx.work.Configuration as WorkManagerConfiguration
import dagger.hilt.android.HiltAndroidApp
import dev.stockflow.data.sync.InventoryWorkerFactory
import dev.stockflow.data.sync.WorkManagerSyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class StockFlowApplication : Application(), WorkManagerConfiguration.Provider {
    @Inject lateinit var workerFactory: InventoryWorkerFactory
    @Inject lateinit var scheduler: WorkManagerSyncScheduler

    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        scheduler.scheduleRecovery()
    }
}
