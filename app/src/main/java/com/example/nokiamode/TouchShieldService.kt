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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView

/** Touch exploration filters edge gestures before dispatch; the trusted window consumes hover. */
class TouchShieldService : AccessibilityService() {
    companion object {
        const val ACTION_EXIT = "com.example.nokiamode.EXIT_BY_CORNER"
        private const val CORNER_DESCRIPTION = "אזור יציאה ממצב Nokia"
        private var connected: TouchShieldService? = null

        fun isReady(context: Context): Boolean {
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            val conflicting = accessibility.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                it.resolveInfo?.serviceInfo?.packageName != context.packageName &&
                    it.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
            }
            return connected != null && !conflicting &&
                (!NokiaSession.isVisible || connected?.shield != null)
        }

        fun setSessionActive(active: Boolean) {
            connected?.updateShield(active)
        }
    }

    private val cornerExit = CornerExitDetector()
    private var manager: WindowManager? = null
    private var shield: View? = null
    private var badge: TextView? = null
    private var lastCornerEvent = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        if (NokiaSession.isVisible) updateShield(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (shield == null || event?.eventType != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER ||
            event.packageName?.toString() != packageName) return
        val description = event.contentDescription?.toString() ?:
            event.source?.contentDescription?.toString()
        if (description == CORNER_DESCRIPTION) recordCornerContact()
    }
    override fun onInterrupt() = Unit

    override fun onGesture(gestureEvent: android.accessibilityservice.AccessibilityGestureEvent): Boolean {
        // In particular, never forward a double tap into a click or a drag.
        return true
    }

    private fun recordCornerContact() {
        val now = SystemClock.elapsedRealtime()
        // A hover, accessibility event and optional raw DOWN can describe the same finger.
        if (now - lastCornerEvent < 180L) return
        lastCornerEvent = now
        if (cornerExit.onDown(0f, 0f, 1f, 1f, now)) {
            badge?.visibility = View.INVISIBLE
            sendBroadcast(Intent(ACTION_EXIT).setPackage(packageName))
        } else {
            badge?.let { label ->
                label.text = "${cornerExit.progress}/10"
                label.visibility = View.VISIBLE
                label.postDelayed({
                    if (SystemClock.elapsedRealtime() - lastCornerEvent >= 1150L)
                        label.visibility = View.INVISIBLE
                }, 1200L)
            }
        }
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
                val view = object : FrameLayout(this) {
                    override fun onTouchEvent(event: MotionEvent): Boolean = true
                    override fun onHoverEvent(event: MotionEvent): Boolean = true
                    override fun onGenericMotionEvent(event: MotionEvent): Boolean = true
                }.apply {
                    isClickable = true
                    isFocusable = false
                    setBackgroundColor(Color.TRANSPARENT)
                }
                val corner = object : FrameLayout(this) {
                    private var hovering = false

                    override fun onHoverEvent(event: MotionEvent): Boolean {
                        // The base implementation also emits TYPE_VIEW_HOVER_ENTER for the service.
                        super.onHoverEvent(event)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_HOVER_ENTER -> {
                                hovering = true
                                recordCornerContact()
                            }
                            MotionEvent.ACTION_HOVER_MOVE -> if (!hovering) {
                                hovering = true
                                recordCornerContact()
                            }
                            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
                        }
                        return true
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) recordCornerContact()
                        return true
                    }
                }.apply {
                    isClickable = true
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = CORNER_DESCRIPTION
                    setBackgroundColor(Color.TRANSPARENT)
                }
                val metrics = resources.displayMetrics
                view.addView(corner, FrameLayout.LayoutParams(
                    (metrics.widthPixels * .24f).toInt().coerceAtLeast(1),
                    (metrics.heightPixels * .22f).toInt().coerceAtLeast(1),
                    Gravity.TOP or Gravity.LEFT
                ))
                val label = TextView(this).apply {
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xBB262333.toInt())
                    visibility = View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                corner.addView(label, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.LEFT
                ))
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
                badge = label
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
        badge = null
        cornerExit.reset()
    }

    override fun onDestroy() {
        updateShield(false)
        if (connected === this) connected = null
        super.onDestroy()
    }
}
