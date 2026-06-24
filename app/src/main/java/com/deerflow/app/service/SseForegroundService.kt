package com.deerflow.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (and at foreground priority) while an AG-UI run is
 * streaming, so SSE delivery continues when the app is backgrounded. The actual
 * stream collection lives in ConversationRepository's app-scoped coroutine; this
 * service only holds the foreground guarantee.
 */
class SseForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeerFlow")
            .setContentText("Receiving agent stream...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent stream", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "sse_stream"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SseForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SseForegroundService::class.java))
        }
    }
}
