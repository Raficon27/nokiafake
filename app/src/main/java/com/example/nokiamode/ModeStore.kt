package com.example.nokiamode

import android.content.Context

internal enum class NokiaMode { FULL, DEMO }

internal object ModeStore {
    private const val PREFS = "nokia_mode_setup"
    private const val MODE = "mode"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(MODE)

    fun get(context: Context): NokiaMode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "DEMO") == "FULL")
            NokiaMode.FULL else NokiaMode.DEMO

    fun set(context: Context, mode: NokiaMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MODE, mode.name).apply()
    }
}
