package com.lockout.gate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lockout.gate.state.SessionStore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Periodic check-in nag while a work session is active. WorkManager's floor
 * for guaranteed periodic work is 15 minutes (Config.NAG_INTERVAL_MINUTES) —
 * it can't match the server's ~45-second budget check-in cadence, since that
 * would require a persistent foreground service instead.
 */
class NagWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val store = SessionStore(applicationContext)
        if (!store.active) return Result.success()

        postNagNotification(applicationContext)
        return Result.success()
    }

    private fun postNagNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.nag_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.nag_channel_desc) }
            manager.createNotificationChannel(channel)
        }

        val openApp = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val phrase = Config.NAG_PHRASES[Random.nextInt(Config.NAG_PHRASES.size)]
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(phrase)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "nag_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "nag_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NagWorker>(
                Config.NAG_INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
