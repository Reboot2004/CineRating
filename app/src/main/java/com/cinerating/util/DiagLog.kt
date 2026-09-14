package com.cinerating.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * No-ADB log: ring buffer in SharedPreferences so failures can be diagnosed
 * on the TV itself via DiagnosticsActivity.
 */
object DiagLog {
    private const val PREFS = "cinerating_diag"
    private const val KEY = "log_lines"
    const val MAX_LINES = 60

    @Synchronized
    fun log(context: Context, msg: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val lines = prefs.getString(KEY, "").orEmpty().lines().toMutableList()
            lines.add("$time $msg")
            prefs.edit().putString(KEY, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
        }
    }

    fun snapshot(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().ifBlank { "(empty — open Hotstar/Netflix, browse, then Refresh)" }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
