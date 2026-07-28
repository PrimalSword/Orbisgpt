package com.orbisgpt.atlas

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.orbisgpt.atlas.worker.DailyAnalysisWorker
import java.util.concurrent.TimeUnit

class AtlasApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val request = PeriodicWorkRequestBuilder<DailyAnalysisWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "orbis_atlas_daily_analysis",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
