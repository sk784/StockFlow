package dev.stockflow.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.stockflow.domain.SyncEngine
import dev.stockflow.domain.SyncResult
import dev.stockflow.domain.SyncScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class InventorySyncWorker(
    context: Context,
    parameters: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = when (engine.synchronize()) {
        SyncResult.Drained, SyncResult.Blocked -> Result.success()
        SyncResult.Retry -> Result.retry()
    }
}

class InventoryWorkerFactory @Inject constructor(private val engine: SyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == InventorySyncWorker::class.java.name) InventorySyncWorker(appContext, workerParameters, engine)
        else null
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(@param:ApplicationContext private val context: Context) : SyncScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun enqueue() {
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    override fun enqueueNow() {
        // Explicit retry starts a fresh chain instead of waiting behind an old backoff.
        // Cancellation preserves the outbox; UUIDs make a committed-but-unacknowledged PUT safe.
        enqueue(ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(policy: ExistingWorkPolicy) {
        val manager = WorkManager.getInstance(context)
        // Appending avoids losing a wake-up when an existing worker is about to finish.
        manager.enqueueUniqueWork(
            "stockflow-upload", policy,
            OneTimeWorkRequestBuilder<InventorySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun scheduleRecovery() {
        // Repairs the commit/enqueue gap even if the app was killed after the Room transaction.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "stockflow-upload-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InventorySyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        enqueue()
    }
}
