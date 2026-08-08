package com.pqcmeshchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * BLEMeshService - A minimal Foreground Service whose sole purpose is to keep
 * the app process alive when the user presses Home or switches apps.
 *
 * All BLE work (advertising, scanning, GATT) continues to run inside
 * BLEMeshModule, which was already started by React Native. This service
 * simply prevents Android from killing the process by calling startForeground().
 */
class BLEMeshService : Service() {

    companion object {
        private const val CHANNEL_ID = "pqc_mesh_service_channel"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            val intent = Intent(context, BLEMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i("BLEMeshService", "Service start requested")
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BLEMeshService::class.java))
            Log.i("BLEMeshService", "Service stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i("BLEMeshService", "Foreground service started — BLE mesh kept alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if killed by OS, restart automatically
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i("BLEMeshService", "Foreground service destroyed")
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PQC Mesh Node Active")
            .setContentText("BLE mesh is running — tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PQC Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE mesh running in background"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
