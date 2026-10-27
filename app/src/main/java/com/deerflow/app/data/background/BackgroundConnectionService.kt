package com.deerflow.app.data.background

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.deerflow.app.data.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Owns only a foreground subscription lease. The server owns execution. */
class BackgroundConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: ConversationRepository
    private lateinit var notifications: TaskNotifications
    private var stopping = false
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        repo = ConversationRepository.get(applicationContext)
        notifications = TaskNotifications(this)
        try {
            ServiceCompat.startForeground(this, TaskNotifications.PROGRESS_ID,
                notifications.progress(BackgroundWork(), false), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            promoted = true
            repo.backgroundServiceStarted()
        } catch (_: RuntimeException) {
            repo.pauseBackgroundConnection("系统暂不允许后台接收，返回应用后会恢复")
            finish()
            return
        }
        scope.launch {
            combine(repo.backgroundWork, repo.isConnected) { work, connected -> work to connected }
                .distinctUntilChanged().collect { (work, connected) ->
                    if (!stopping) {
                        if (!work.hasWork || repo.backgroundConnection.value.pauseReason != null) finish()
                        else notifications.updateProgress(notifications.progress(work, connected))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            repo.pauseBackgroundConnection("已暂停后台接收，返回应用后会恢复")
            finish()
        }
        // Do not resurrect the process or replay user commands after a system/user stop.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        repo.pauseBackgroundConnection("后台接收已达到系统时限，返回应用后会恢复")
        finish()
    }

    private fun finish() {
        if (stopping) return
        stopping = true
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::repo.isInitialized) repo.backgroundServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE = "com.deerflow.app.PAUSE_BACKGROUND_CONNECTION"
    }
}
