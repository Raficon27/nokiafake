package com.example.nokiamode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/** Touch exploration filters edge gestures before dispatch; the trusted window consumes hover. */
class TouchShieldService : AccessibilityService() {
    companion object {
        const val ACTION_EXIT = "com.example.nokiamode.EXIT_BY_CORNER"
        private var connected: TouchShieldService? = null

        fun isReady(context: Context): Boolean {
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            val conflicting = accessibility.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                it.resolveInfo?.serviceInfo?.packageName != context.packageName &&
                    it.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
            }
            return connected != null && !conflicting
        }

        fun setSessionActive(active: Boolean) {
            connected?.updateShield(active)
        }
    }

    private val cornerExit = CornerExitDetector()
    private var manager: WindowManager? = null
    private var shield: View? = null
    private var hoverX = -1f
    private var hoverY = -1f

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        if (NokiaSession.isVisible) updateShield(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onGesture(gestureEvent: android.accessibilityservice.AccessibilityGestureEvent): Boolean {
        // In particular, never forward a double tap into a click or a drag.
        return true
    }

    private fun updateShield(active: Boolean) {
        if (active && shield != null || !active && shield == null) return
        if (active) {
            val info = serviceInfo ?: return
            val originalFlags = info.flags
            info.flags = originalFlags or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES or
                AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP
            try {
                setServiceInfo(info)
                if (Build.VERSION.SDK_INT >= 30) {
                    setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
                    setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
                }
                manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = object : View(this) {
                    override fun onTouchEvent(event: MotionEvent): Boolean = true

                    override fun onHoverEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                                hoverX = event.x; hoverY = event.y
                            }
                            MotionEvent.ACTION_HOVER_EXIT -> {
                                val x = if (hoverX < 0f) event.x else hoverX
                                val y = if (hoverY < 0f) event.y else hoverY
                                if (cornerExit.onDown(x, y, width.toFloat(), height.toFloat(),
                                        SystemClock.elapsedRealtime())) {
                                    sendBroadcast(Intent(ACTION_EXIT).setPackage(packageName))
                                }
                                hoverX = -1f; hoverY = -1f
                            }
                        }
                        return true
                    }

                    override fun onGenericMotionEvent(event: MotionEvent): Boolean = true
                }.apply {
                    isClickable = true
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundColor(Color.TRANSPARENT)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                }
                manager?.addView(view, params)
                shield = view
            } catch (_: Exception) {
                removeShield()
                info.flags = originalFlags
                setServiceInfo(info)
            }
        } else {
            removeShield()
            serviceInfo?.let { info ->
                info.flags = info.flags and
                    (AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                        AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES or
                        AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP).inv()
                setServiceInfo(info)
            }
        }
    }

    private fun removeShield() {
        try { shield?.let { manager?.removeView(it) } } catch (_: Exception) { }
        shield = null
        hoverX = -1f; hoverY = -1f
    }

    override fun onDestroy() {
        updateShield(false)
        if (connected === this) connected = null
        super.onDestroy()
    }
}
