package com.vknxworks.soundctrl.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.vknxworks.soundctrl.MainActivity
import com.vknxworks.soundctrl.R
import com.vknxworks.soundctrl.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps SoundCtrl running while firmware is sent to a device (foreground service, type
 * connectedDevice), with a progress notification. It runs only during an update and stops itself.
 */
class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ch_update), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.ch_update_desc)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(ID, build(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val updater = container.updater
        scope.launch {
            updater.step.collect { s ->
                when (s) {
                    is UpdateStep.Sending -> getSystemService(NotificationManager::class.java).notify(ID, build(s.sent, s.total))
                    else -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(sent: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(getString(R.string.notif_updating))
            .setContentText(getString(R.string.notif_updating_body, pct))
            .setProgress(100, pct, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "update"
        const val ID = 7
    }
}
