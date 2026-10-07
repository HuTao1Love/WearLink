package dev.wearlink.core.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.wearlink.core.WearLinkCore
import java.util.concurrent.TimeUnit

/** Periodically refreshes subscriptions so server lists and traffic stats stay current. */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (WearLinkCore.store.current.subscriptions.isEmpty()) return Result.success()
        val failed = WearLinkCore.importer.refreshAll()
        return if (failed == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "subscription-refresh"
        private const val INTERVAL_HOURS = 12L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
