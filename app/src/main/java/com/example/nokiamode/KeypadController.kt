package com.example.nokiamode

import android.view.KeyEvent

internal enum class KeyAction { UP, DOWN, LEFT, RIGHT, OK, SOFT_LEFT, SOFT_RIGHT, CALL, END, DELETE, DIGIT, UNKNOWN }
internal data class KeyPress(val action: KeyAction, val symbol: String = "")

/** Hardware keycodes, not scan codes: scan codes differ between F21 Pro firmware variants. */
internal object KeypadController {
    fun map(event: KeyEvent): KeyPress {
        val code = event.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                (code - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "*"
            KeyEvent.KEYCODE_POUND -> "#"
            else -> null
        }
        if (digit != null) return KeyPress(KeyAction.DIGIT, digit)
        return KeyPress(when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> KeyAction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyAction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyAction.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyAction.OK
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> KeyAction.SOFT_LEFT
            KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> KeyAction.SOFT_RIGHT
            KeyEvent.KEYCODE_CALL -> KeyAction.CALL
            KeyEvent.KEYCODE_ENDCALL -> KeyAction.END
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_CLEAR -> KeyAction.DELETE
            else -> KeyAction.UNKNOWN
        })
    }
}

/** Nokia-style multi-tap. Same key within 850 ms cycles the pending character. */
internal class MultiTapEngine {
    enum class Mode { HEBREW, ENGLISH, NUMBERS }
    var mode = Mode.HEBREW
        private set
    var text = ""
        private set
    var caret = 0
        private set
    private var pendingKey = ""
    private var pendingAt = 0L
    private var pendingIndex = 0

    fun reset() { text = ""; caret = 0; mode = Mode.HEBREW; commit() }
    fun commit() { pendingKey = "" }
    fun switchMode() { commit(); mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size] }
    fun move(delta: Int) { commit(); caret = (caret + delta).coerceIn(0, text.length) }
    fun delete() {
        if (caret > 0) {
            text = text.removeRange(caret - 1, caret)
            caret--
        }
        commit()
    }
    fun insert(key: String, time: Long) {
        if (key == "#") { switchMode(); return }
        val choices = when {
            mode == Mode.NUMBERS -> key
            key == "0" -> " "
            key == "*" -> ".,?!-:;"
            key == "1" -> ".,?!1"
            mode == Mode.HEBREW -> mapOf(
                "2" to "אבג", "3" to "דהו", "4" to "זחט", "5" to "יכל",
                "6" to "מנס", "7" to "עפצ", "8" to "קרש", "9" to "שתץ"
            )[key] ?: key
            else -> mapOf(
                "2" to "abc2", "3" to "def3", "4" to "ghi4", "5" to "jkl5",
                "6" to "mno6", "7" to "pqrs7", "8" to "tuv8", "9" to "wxyz9"
            )[key] ?: key
        }
        if (choices.isEmpty()) return
        val cycling = key == pendingKey && time - pendingAt in 0..850 &&
            caret > 0 && choices.length > 1
        if (cycling) {
            pendingIndex = (pendingIndex + 1) % choices.length
            text = text.replaceRange(caret - 1, caret, choices[pendingIndex].toString())
        } else {
            pendingIndex = 0
            text = text.substring(0, caret) + choices[0] + text.substring(caret)
            caret++
        }
        pendingKey = if (choices.length > 1) key else ""
        pendingAt = time
    }
}
