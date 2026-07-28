package com.orbisgpt.atlas.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orbisgpt.atlas.data.EcbFxRepository
import com.orbisgpt.atlas.data.SettingsStore
import com.orbisgpt.atlas.engine.MarketEngine
import com.orbisgpt.atlas.model.CurrencyCatalog
import com.orbisgpt.atlas.model.CurrencyPair
import com.orbisgpt.atlas.model.DecisionAction

class DailyAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).load()
        if (!settings.notificationsEnabled) return Result.success()
        return runCatching {
            val pair = CurrencyPair(
                CurrencyCatalog.byCode(settings.baseCode),
                CurrencyCatalog.byCode(settings.quoteCode)
            )
            val data = EcbFxRepository(applicationContext).load(pair)
            val plan = MarketEngine.analyze(pair, data.points, data.brlPerQuote, settings)
            notify(plan.headline, plan.plainExplanation, plan.action != DecisionAction.WAIT)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun notify(title: String, message: String, actionable: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Análise diária", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(if (actionable) "Orbis Atlas encontrou um plano" else "Orbis Atlas: aguardar")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$message"))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "atlas_daily"
        private const val NOTIFICATION_ID = 7101
    }
}
