package com.example.nokiamode

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Full-screen transparent touch sink. Hardware keys continue to the focused app. */
class TouchShieldService : Service() {
    companion object { const val ACTION_EXIT = "com.example.nokiamode.EXIT_BY_CORNER" }
    private var windowManager: WindowManager? = null
    private var shield: View? = null
    private var cornerTaps = 0
    private var lastCornerTap = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shield != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT < 26) { stopSelf(); return START_NOT_STICKY }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        shield = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val topLeft = event.rawX < resources.displayMetrics.widthPixels * 0.18f &&
                        event.rawY < resources.displayMetrics.heightPixels * 0.14f
                    if (topLeft) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        cornerTaps = if (now - lastCornerTap < 1500L) cornerTaps + 1 else 1
                        lastCornerTap = now
                        if (cornerTaps >= 10) {
                            cornerTaps = 0
                            sendBroadcast(Intent(ACTION_EXIT).setPackage(packageName))
                        }
                    } else cornerTaps = 0
                }
                return true
            }
        }.apply { isClickable = true; isFocusable = false; setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { windowManager?.addView(shield, params) } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { shield?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        shield = null
        super.onDestroy()
    }
}
