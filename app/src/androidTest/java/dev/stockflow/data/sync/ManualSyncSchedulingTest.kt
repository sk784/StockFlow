package dev.stockflow.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Uses persisted WorkManager state to catch accidental APPEND behind a retry. */
class ManualSyncSchedulingTest {
    @Test fun manualRequestReplacesAWorkerWaitingForBackoff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val name = "stockflow-upload"
        manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        val waiting = OneTimeWorkRequestBuilder<RetryProbe>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS).build()
        try {
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, waiting).result.get(10, TimeUnit.SECONDS)
            waitUntil {
                manager.getWorkInfoById(waiting.id).get(5, TimeUnit.SECONDS)?.let {
                    it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1
                } == true
            }
            WorkManagerSyncScheduler(context).enqueueNow()
            waitUntil {
                val states = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
                val previous = manager.getWorkInfoById(waiting.id).get(5, TimeUnit.SECONDS)
                (previous == null || previous.state == WorkInfo.State.CANCELLED) &&
                    states.any { it.id != waiting.id && it.state != WorkInfo.State.BLOCKED }
            }
            val previous = manager.getWorkInfoById(waiting.id).get()
            assertTrue(previous == null || previous.state == WorkInfo.State.CANCELLED)
        } finally {
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        }
    }

    private fun waitUntil(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("WorkManager did not reach the expected state")
            Thread.sleep(50)
        }
    }

    class RetryProbe(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork() = Result.retry()
    }
}
