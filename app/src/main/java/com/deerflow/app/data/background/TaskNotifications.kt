package com.deerflow.app.data.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.deerflow.app.R
import com.deerflow.app.ui.MainActivity

/** Notifications contain status only: no prompts, tool arguments, outputs, or credentials. */
class TaskNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "后台任务连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示后台连接与任务同步状态"
        })
        manager.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, "任务完成与待处理提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "任务结束、需要授权或输入时提醒"
        })
    }

    private fun openApp() = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_task_notification)
        .setContentIntent(openApp())
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setShowWhen(false)

    fun progress(work: BackgroundWork, connected: Boolean): Notification {
        val pause = PendingIntent.getService(context, 1,
            Intent(context, BackgroundConnectionService::class.java).setAction(BackgroundConnectionService.ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder(PROGRESS_CHANNEL)
            .setContentTitle("DeerFlow · 后台接收中")
            .setContentText(if (connected) work.progressText else "连接正在恢复，服务器任务继续执行")
            .setSubText("暂停接收不会停止服务器任务")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "暂停后台接收", pause)
            .build()
    }

    fun updateProgress(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(PROGRESS_ID, notification)
    }

    fun event(sessionId: String, needsAttention: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(sessionId, EVENT_ID, builder(EVENT_CHANNEL)
            .setContentTitle(if (needsAttention) "DeerFlow · 任务需要处理" else "DeerFlow · 任务已结束")
            .setContentText(if (needsAttention) "有任务等待授权或输入，点此返回应用" else "点此查看执行结果")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(needsAttention)
            .build())
    }

    companion object {
        const val PROGRESS_ID = 4101
        private const val EVENT_ID = 4102
        private const val PROGRESS_CHANNEL = "deerflow_background_connection"
        private const val EVENT_CHANNEL = "deerflow_task_events"
    }
}
