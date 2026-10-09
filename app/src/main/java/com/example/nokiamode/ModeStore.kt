package com.example.nokiamode

import android.content.Context

internal enum class NokiaMode { FULL, DEMO, SAFE }

internal object ModeStore {
    private const val PREFS = "nokia_mode_setup"
    private const val MODE = "mode"
    private const val SETUP_VERSION = "setup_version"
    private const val STARTED = "started"
    private const val TOTAL = "total"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SETUP_VERSION, 0) >= 3

    fun get(context: Context): NokiaMode = when (
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "DEMO")
    ) {
        "FULL" -> NokiaMode.FULL
        "SAFE" -> NokiaMode.SAFE
        else -> NokiaMode.DEMO
    }

    fun set(context: Context, mode: NokiaMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MODE, mode.name).putInt(SETUP_VERSION, 4).apply()
    }

    fun start(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong(STARTED, 0L) == 0L) p.edit().putLong(STARTED, System.currentTimeMillis()).apply()
    }

    fun stop(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = p.getLong(STARTED, 0L)
        if (started > 0L) p.edit().putLong(TOTAL,
            p.getLong(TOTAL, 0L) + (System.currentTimeMillis() - started).coerceAtLeast(0L))
            .remove(STARTED).apply()
    }

    fun duration(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = p.getLong(STARTED, 0L)
        return p.getLong(TOTAL, 0L) + if (started > 0L)
            (System.currentTimeMillis() - started).coerceAtLeast(0L) else 0L
    }
}
