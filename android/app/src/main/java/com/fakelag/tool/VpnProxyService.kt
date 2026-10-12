package com.fakelag.tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.IBinder

/**
 * Thin VpnService wrapper. Only used when device has no root.
 * Creates its own FakeLagEngine for the VPN backend.
 */
class VpnProxyService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.fakelag.tool.STOP"
    }

    private var engine: FakeLagEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("fake_lag_vpn", "Fake Lag VPN", NotificationManager.IMPORTANCE_LOW)
        )

        engine = FakeLagEngine(this)
        val ok = engine!!.establishVpn(this)

        if (ok) {
            startForeground(1, Notification.Builder(this, "fake_lag_vpn")
                .setContentTitle("Fake Lag Tool")
                .setContentText("ACTIVE — dropping UDP 50-250B")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true).build())
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopVpn() {
        engine?.stop()
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
