package com.mg.wazealerts.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.settings.AppSettings
import java.util.concurrent.TimeUnit

object ServiceWatchdog {
    private const val TAG = "Watchdog"
    private const val WORK_NAME = "alert-monitor-watchdog"
    private const val HEARTBEAT_REQUEST = 7501
    private const val IMMEDIATE_REQUEST = 7502

    fun startMonitoring(context: Context) {
        val intent = Intent(context, AlertMonitorService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { AppLogger.w(TAG, "startForegroundService failed: ${it.message}") }
    }

    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelWatchdog(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun setHeartbeat(context: Context, intervalMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = restartPendingIntent(context, HEARTBEAT_REQUEST)
        val triggerAt = SystemClock.elapsedRealtime() + intervalMillis
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelHeartbeat(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(restartPendingIntent(context, HEARTBEAT_REQUEST))
    }

    fun scheduleImmediateRestart(context: Context, delayMillis: Long = 1_500L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = restartPendingIntent(context, IMMEDIATE_REQUEST)
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    private fun restartPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, RestartReceiver::class.java).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppLogger.init(applicationContext)
        val settings = AppSettings(applicationContext)
        if (!settings.monitoringEnabled) {
            ServiceWatchdog.cancelWatchdog(applicationContext)
            return Result.success()
        }
        AppLogger.d("Watchdog", "Periodic check — ensuring AlertMonitorService is up")
        ServiceWatchdog.startMonitoring(applicationContext)
        return Result.success()
    }
}
