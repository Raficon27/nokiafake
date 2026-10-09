package com.example.nokiamode

/** Ten deliberate taps in the upper-left part of the app's visible surface. */
internal class CornerExitDetector {
    private var count = 0
    private var lastTap = 0L
    val progress: Int get() = count

    fun reset() { count = 0; lastTap = 0L }

    fun onDown(x: Float, y: Float, width: Float, height: Float, time: Long): Boolean {
        // The target extends below the system gesture edge. It remains usable when
        // Android reserves the first few pixels for the notification gesture.
        val inCorner = x >= 0f && x < width * 0.24f && y >= 0f && y < height * 0.22f
        if (!inCorner) { count = 0; return false }
        count = if (count > 0 && time - lastTap <= 2400L) count + 1 else 1
        lastTap = time
        if (count < 10) return false
        count = 0
        return true
    }
}
