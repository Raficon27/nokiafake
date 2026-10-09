package com.example.nokiamode

/** Keep the shield active only while one of our Nokia screens is visible. */
internal object NokiaSession {
    private var startedScreens = 0
    val isVisible: Boolean get() = startedScreens > 0

    fun onScreenStarted() {
        startedScreens++
        TouchShieldService.setSessionActive(true)
    }

    fun onScreenStopped() {
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
        if (!isVisible) TouchShieldService.setSessionActive(false)
    }
}
