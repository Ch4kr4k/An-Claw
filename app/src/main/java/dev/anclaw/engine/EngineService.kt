package dev.anclaw.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import dev.anclaw.AnClawApp
import dev.anclaw.MainActivity
import dev.anclaw.R

/**
 * Foreground service that keeps the process (and so llama-server and claw,
 * its children) alive while the app is in the background, with a partial
 * wake lock while the engine runs.
 */
class EngineService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AnClawApp.instance.engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Running"
        startForeground(1, notification(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        running = true
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anclaw:engine")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "dev.anclaw.STOP"
        private const val EXTRA_TEXT = "text"
        @Volatile private var running = false

        private fun notification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, AnClawApp.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("an-claw")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop engine", stop).build())
            .build()
        }

        /** Starts the service, or just updates its notification when it already runs. */
        fun update(context: Context, text: String) {
            if (running) {
                context.getSystemService(android.app.NotificationManager::class.java).notify(1, notification(context, text))
                return
            }
            // Not allowed from the background on Android 12+; the app starts it while in front.
            runCatching {
                context.startForegroundService(Intent(context, EngineService::class.java).putExtra(EXTRA_TEXT, text))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
