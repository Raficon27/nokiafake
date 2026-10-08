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
    private var windowManager: WindowManager? = null
    private var shield: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shield != null) return START_STICKY
        if (Build.VERSION.SDK_INT < 26) { stopSelf(); return START_NOT_STICKY }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        shield = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean = true
        }.apply { isClickable = true; isFocusable = false }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0.01f }
        try { windowManager?.addView(shield, params) } catch (_: Exception) { stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        try { shield?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        shield = null
        super.onDestroy()
    }
}
