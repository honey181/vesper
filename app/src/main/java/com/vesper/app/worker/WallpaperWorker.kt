package com.vesper.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vesper.app.MainActivity
import com.vesper.app.R
import com.vesper.app.VesperApplication
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ScheduleMode
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class WallpaperWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VesperApplication ?: return Result.failure()
        val settings = app.container.settingsRepository.getSettingsOnce()
        if (settings.apiKey.isBlank() || settings.scheduleMode == ScheduleMode.MANUAL_ONLY) {
            return Result.success()
        }

        return try {
            val dossier = app.container.wallpaperPipeline.generateAndOptionallyApply(
                wasAutoRun = true,
                applyToDevice = true
            )
            if (settings.notifyOnAutoUpdate) {
                ScheduleManager.postNewWallpaperNotification(
                    context = applicationContext,
                    title = dossier.clueTitle,
                    briefing = dossier.realWorldBriefing
                )
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object ScheduleManager {
    private const val WORK_NAME = "vesper_periodic_wallpaper"
    const val CHANNEL_ID = "vesper_updates"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vesper Wallpapers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Quiet notification when a new daily or weekly wallpaper brief is ready."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun syncSchedule(context: Context, settings: AppSettings) {
        ScheduleCoordinator.syncSchedule(context, settings)
    }

    fun postNewWallpaperNotification(context: Context, title: String, briefing: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureNotificationChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vesper_sparkle)
            .setContentTitle("Vesper · $title")
            .setContentText(briefing)
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefing))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }
}
