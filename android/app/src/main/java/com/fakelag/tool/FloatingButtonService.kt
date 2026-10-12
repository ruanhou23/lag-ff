package com.fakelag.tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * Floating circle button — toggle Fake Lag from anywhere.
 * Green circle = ON, grey circle = OFF.
 * Tap to toggle, drag to move, long press to open app.
 */
class FloatingButtonService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 10
        const val ACTION_UPDATE_SIZE = "UPDATE_SIZE"
        @Volatile var isRunning = false; private set
    }

    private var wm: WindowManager? = null
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var engine: FakeLagEngine? = null
    private var currentSize = 56

    // Drag
    private var ix = 0; private var iy = 0
    private var tx = 0f; private var ty = 0f
    private var drag = false; private var ts = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            "fb", "Fake Lag", NotificationManager.IMPORTANCE_LOW
        ))

        engine = FakeLagEngine(this)
        
        // Poll engine state to keep UI in sync with MainActivity
        CoroutineScope(Dispatchers.Main).launch {
            while (isRunning) {
                updateLook()
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_SIZE) {
            val size = intent.getIntExtra("size", currentSize)
            updateSize(size)
            return START_STICKY
        }
        
        currentSize = intent?.getIntExtra("size", 56) ?: 56

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, Notification.Builder(this, "fb")
            .setContentTitle("Fake Lag")
            .setContentText("● Chạm để bật/tắt")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build())

        showCircle()
        return START_STICKY
    }

    private fun showCircle() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createView()
        wm?.addView(view, lp)
    }

    private fun createView() {
        val density = resources.displayMetrics.density
        val sizePx = (currentSize * density).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        val circle = TextView(this).apply {
            text = "●"
            textSize = (currentSize * 0.6f) // Tỉ lệ kích thước chữ theo nút
            setTextColor(if (FakeLagEngine.isRunning) Color.rgb(85, 204, 85) else Color.GRAY)
            gravity = Gravity.CENTER
            alpha = 0.9f
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply { gravity = Gravity.CENTER }
        }

        container.addView(circle)
        view = container
        container.setOnTouchListener { _, e -> onTouch(e); true }

        lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 400
        }
    }

    private fun updateSize(size: Int) {
        currentSize = size
        val density = resources.displayMetrics.density
        val sizePx = (size * density).toInt()
        
        lp?.width = sizePx
        lp?.height = sizePx
        
        val circle = (view as? FrameLayout)?.getChildAt(0) as? TextView
        circle?.textSize = (size * 0.6f)
        val params = circle?.layoutParams as? FrameLayout.LayoutParams
        params?.width = sizePx
        params?.height = sizePx
        circle?.layoutParams = params
        
        view?.let { wm?.updateViewLayout(it, lp) }
    }

    private fun onTouch(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                ix = lp!!.x; iy = lp!!.y
                tx = e.rawX; ty = e.rawY
                ts = System.currentTimeMillis(); drag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - tx; val dy = e.rawY - ty
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) drag = true
                if (drag) { 
                    lp!!.x = (ix + dx).toInt()
                    lp!!.y = (iy + dy).toInt()
                    wm?.updateViewLayout(view, lp) 
                }
            }
            MotionEvent.ACTION_UP -> {
                val dt = System.currentTimeMillis() - ts
                val mv = Math.abs(e.rawX - tx) + Math.abs(e.rawY - ty)
                when {
                    dt > 500 && mv < 15 -> openApp()
                    !drag && mv < 15 -> toggle()
                    // drag -> snap() // Removed snap to allow placing ANYWHERE
                }
            }
        }
    }

    private fun toggle() {
        try {
            if (FakeLagEngine.isRunning) {
                // Stop everything
                engine?.stop()
                val stopIntent = Intent(this, VpnProxyService::class.java).apply {
                    action = VpnProxyService.ACTION_STOP
                }
                startService(stopIntent)
                stopService(stopIntent)
            } else {
                if (RootManager.hasRoot) {
                    engine?.start()
                } else {
                    // Try to start VPN directly if already prepared
                    val vpnIntent = android.net.VpnService.prepare(this)
                    if (vpnIntent == null) {
                        ContextCompat.startForegroundService(
                            this, Intent(this, VpnProxyService::class.java)
                        )
                    } else {
                        // Need to open app to show VPN dialog
                        openApp()
                        Toast.makeText(this, "Cần cấp quyền VPN trong App", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {}
        
        // Use a slight delay to allow state to propagate
        CoroutineScope(Dispatchers.Main).launch {
            delay(200)
            updateLook()
            beep()
        }
    }

    private fun updateLook() {
        val on = FakeLagEngine.isRunning
        ((view as? FrameLayout)?.getChildAt(0) as? TextView)?.apply {
            setTextColor(if (on) Color.rgb(85, 204, 85) else Color.GRAY)
            // Optional: add a small visual indicator like a glow or different char
        }
    }

    private fun snap() {
        val w = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm?.currentWindowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
        } else {
            resources.displayMetrics.widthPixels
        }
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        lp!!.x = if (lp!!.x + (size / 2) < w / 2) 0 else w - size
        wm?.updateViewLayout(view, lp)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun beep() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val tg = ToneGenerator(AudioAttributes.USAGE_NOTIFICATION, 80)
                val on = FakeLagEngine.isRunning
                tg.startTone(if (on) ToneGenerator.TONE_PROP_ACK
                else ToneGenerator.TONE_PROP_BEEP, 200)
                delay(300)
                tg.release()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        wm = null; view = null
        engine?.destroy(); engine = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
