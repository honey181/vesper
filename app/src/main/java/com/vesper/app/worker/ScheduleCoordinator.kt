package com.vesper.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.vesper.app.VesperApplication
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ScheduleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object ScheduleCoordinator {

    private const val TAG = "VesperSchedule"
    internal const val LEGACY_PERIODIC_WORK_NAME = "vesper_periodic_wallpaper"
    internal const val IMMEDIATE_WORK_NAME = "vesper_scheduled_wallpaper_run"
    private const val ACTION_SCHEDULED_ALARM = "com.vesper.app.action.SCHEDULED_WALLPAPER_ALARM"
    private const val ALARM_REQUEST_CODE = 4201

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bootstrapFromAppContext(context: Context) {
        val appContext = context.applicationContext
        appScope.launch {
            runCatching {
                val app = appContext as? VesperApplication ?: return@launch
                val settings = app.container.settingsRepository.settingsFlow.first()
                syncSchedule(appContext, settings)
            }.onFailure { error ->
                Log.w(TAG, "Failed to bootstrap wallpaper schedule", error)
            }
        }
    }

    fun syncSchedule(
        context: Context,
        settings: AppSettings,
        now: ZonedDateTime = ZonedDateTime.now(),
        advancePastCurrentOccurrence: Boolean = false
    ): ZonedDateTime? {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        // Always purge legacy PeriodicWorkRequest so WorkManager never keeps a drifted 24h flex window.
        wm.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)

        if (settings.scheduleMode == ScheduleMode.MANUAL_ONLY || settings.apiKey.isBlank()) {
            cancelAlarm(appContext)
            wm.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            return null
        }

        val nextTrigger = computeNextTriggerTime(
            now = now,
            mode = settings.scheduleMode,
            hour = settings.scheduleHour,
            minute = settings.scheduleMinute,
            advancePastCurrentOccurrence = advancePastCurrentOccurrence
        )

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildAlarmPendingIntent(appContext, PendingIntent.FLAG_UPDATE_CURRENT)
        val triggerAtMillis = nextTrigger.toInstant().toEpochMilli()

        val scheduledExact = if (canScheduleExactAlarms(appContext)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                false
            }
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            false
        }

        Log.i(
            TAG,
            "Armed ${settings.scheduleMode.name} alarm (${if (scheduledExact) "exact" else "inexact-idle"}) for $nextTrigger"
        )
        return nextTrigger
    }

    fun enqueueScheduledRun(context: Context, settings: AppSettings) {
        val appContext = context.applicationContext
        if (settings.scheduleMode == ScheduleMode.MANUAL_ONLY || settings.apiKey.isBlank()) {
            cancelAlarm(appContext)
            return
        }

        // Arm tomorrow's/next week's anchored trigger first so a transient failure never drops future updates.
        syncSchedule(
            context = appContext,
            settings = settings,
            advancePastCurrentOccurrence = true
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(settings.requireCharging)
            .build()

        val requestBuilder = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)

        // WorkManager 2.12 throws IllegalArgumentException if setExpedited is paired with requiresCharging,
        // and pre-S devices invoke getForegroundInfo(), so restrict expedited dispatch cleanly to API 31+ without charging constraint.
        if (!settings.requireCharging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            requestBuilder.build()
        )
        Log.i(TAG, "Enqueued scheduled WallpaperWorker execution")
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun computeNextTriggerTime(
        now: ZonedDateTime,
        mode: ScheduleMode,
        hour: Int,
        minute: Int,
        advancePastCurrentOccurrence: Boolean = false
    ): ZonedDateTime {
        if (mode == ScheduleMode.MANUAL_ONLY) return now

        val clampedHour = hour.coerceIn(0, 23)
        val clampedMinute = minute.coerceIn(0, 59)
        var candidate = now.withHour(clampedHour)
            .withMinute(clampedMinute)
            .withSecond(0)
            .withNano(0)

        // Guard against clock/HAL jitter (~seconds early) when rescheduling from a fired alarm.
        val cutoff = if (advancePastCurrentOccurrence) {
            now.plusMinutes(5)
        } else {
            now.plusSeconds(15)
        }
        val stepDays = if (mode == ScheduleMode.WEEKLY) 7L else 1L

        while (!candidate.isAfter(cutoff)) {
            candidate = candidate.plusDays(stepDays)
        }
        return candidate
    }

    fun formatNextScheduleSummary(
        context: Context,
        settings: AppSettings,
        now: ZonedDateTime = ZonedDateTime.now()
    ): String {
        if (settings.apiKey.isBlank()) {
            return "Paused until a Gemini API key is saved."
        }
        if (settings.scheduleMode == ScheduleMode.MANUAL_ONLY) {
            return "Manual only — automatic refresh disabled."
        }
        val next = computeNextTriggerTime(
            now = now,
            mode = settings.scheduleMode,
            hour = settings.scheduleHour,
            minute = settings.scheduleMinute
        )
        val precision = if (canScheduleExactAlarms(context)) "exact silent wake" else "battery-aware idle wake"
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' HH:mm", Locale.getDefault())
        return "Next auto-update: ${next.format(formatter)} ($precision)"
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULED_ALARM
        }
        val existing = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    private fun buildAlarmPendingIntent(context: Context, flags: Int): PendingIntent {
        val intent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULED_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class WallpaperAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = appContext as? VesperApplication ?: return@launch
                val settings = app.container.settingsRepository.settingsFlow.first()
                ScheduleCoordinator.enqueueScheduledRun(appContext, settings)
            } catch (error: Throwable) {
                Log.e("VesperSchedule", "Alarm trigger failed to enqueue worker", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class WallpaperRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = appContext as? VesperApplication ?: return@launch
                val settings = app.container.settingsRepository.settingsFlow.first()
                ScheduleCoordinator.syncSchedule(appContext, settings)
            } catch (error: Throwable) {
                Log.w("VesperSchedule", "Reschedule failed for action=$action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
