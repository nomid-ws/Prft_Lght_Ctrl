package app.prft.lightctrl.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Android 14+ (API 34+) connectedDevice ForegroundService maintaining open BLE GATT studio links
 * when Samsung Galaxy Fold 7 switches displays or locks between shots.
 *
 * Always fulfills Android's ServiceRecord.fgRequired contract via promoteToForeground() before
 * any stopSelf() transition so rapid multi-head connect/disconnect bursts can never trigger
 * ForegroundServiceDidNotStartInTimeException.
 */
class ProfotoBleForegroundService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val count = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1)?.coerceAtLeast(0) ?: 1
        // Always satisfy startForeground() first so ActivityManager clears fgRequired even if
        // an immediate ACTION_STOP raced a pending startForegroundService() call.
        promoteToForeground(count)

        if (intent?.action == ACTION_STOP || count <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun promoteToForeground(activeDevices: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Profoto BLE Studio Link",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintains active BLE connection to Profoto lights and remotes"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Profoto Studio Link Active")
            .setContentText("$activeDevices Profoto device(s) connected over BLE")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val hasBtConnect = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasBtConnect) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "prft_ble_studio_link"
        private const val NOTIFICATION_ID = 4501
        private const val ACTION_START = "app.prft.lightctrl.ble.START_LINK_SERVICE"
        private const val ACTION_STOP = "app.prft.lightctrl.ble.STOP_LINK_SERVICE"
        private const val EXTRA_ACTIVE_COUNT = "active_count"

        private val stateLock = Any()
        private var lastDispatchedCount: Int = 0

        fun updateActiveLinkCount(context: Context, connectedCount: Int) {
            synchronized(stateLock) {
                val normalized = connectedCount.coerceAtLeast(0)
                if (normalized == lastDispatchedCount) {
                    return
                }
                val wasRunning = lastDispatchedCount > 0
                lastDispatchedCount = normalized

                try {
                    if (normalized > 0) {
                        val intent = Intent(context, ProfotoBleForegroundService::class.java).apply {
                            action = ACTION_START
                            putExtra(EXTRA_ACTIVE_COUNT, normalized)
                        }
                        ContextCompat.startForegroundService(context, intent)
                    } else if (wasRunning) {
                        val intent = Intent(context, ProfotoBleForegroundService::class.java).apply {
                            action = ACTION_STOP
                            putExtra(EXTRA_ACTIVE_COUNT, 0)
                        }
                        context.startService(intent)
                    }
                } catch (_: Throwable) {
                    // Ignored in unit tests or background start restrictions without permission
                }
            }
        }
    }
}
