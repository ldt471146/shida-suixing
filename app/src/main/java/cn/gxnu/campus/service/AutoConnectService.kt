package cn.gxnu.campus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.gxnu.campus.CampusApplication
import cn.gxnu.campus.MainActivity
import cn.gxnu.campus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AutoConnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as CampusApplication).runtime
    private var started = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "校园网自动连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "监听校园 Wi-Fi 并在需要时认证；可随时暂停。"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            runtime.setAutoConnect(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!runtime.mayRunForegroundService()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification("等待校园 Wi-Fi，需要时自动认证。")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
            runtime.onForegroundStarted()
            // Foreground visibility and while-in-use location access precede any network callback.
            if (!started) {
                started = true
                serviceScope.launch {
                    runtime.uiState.map { it.message }.distinctUntilChanged().collect { message ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
                    }
                }
                serviceScope.launch {
                    while (isActive) {
                        delay(5_000)
                        if (!runtime.mayRunForegroundService()) {
                            runtime.onForegroundPermissionLost()
                            stopSelf()
                            return@launch
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            runtime.onForegroundFailed()
            stopSelf()
        }
        // Force stop/reboot deliberately requires reopening the App; there is no boot receiver.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (started) runtime.onForegroundStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, AutoConnectService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("师大随行 · 自动连接")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "暂停自动连接", pause)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "campus_auto_connect"
        private const val NOTIFICATION_ID = 1606
        private const val ACTION_PAUSE = "cn.gxnu.campus.PAUSE_AUTO_CONNECT"
    }
}
