package com.lockout.gate

import android.content.Context
import org.json.JSONArray

/** Opt-in, bounded, local counters only. No app names, screen text or keys. */
object TimerLog {
    private fun prefs(context: Context) = context.getSharedPreferences("timer_diagnostics", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", BuildConfig.PERSONAL_SETTINGS_GUARD)
    private fun samples(context: Context) = runCatching { JSONArray(prefs(context).getString("samples", "[]")) }.getOrElse { JSONArray() }
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
    @Synchronized fun clear(context: Context) { prefs(context).edit().remove("samples").apply() }
    @Synchronized fun record(context: Context, time: Long, breakId: Long, measured: Long, charged: Long,
                             remaining: Long, events: Int, queryMs: Long) {
        if (!enabled(context)) return
        val old = samples(context)
        val next = JSONArray()
        for (i in maxOf(0, old.length() - 239) until old.length()) next.put(old.get(i))
        next.put("$time,$breakId,$measured,$charged,$remaining,$events,$queryMs")
        prefs(context).edit().putString("samples", next.toString()).apply()
    }
    @Synchronized fun report(context: Context): String {
        val rows = samples(context)
        return buildString {
            append("time_unix_ms,break_id,measured_ms,charged_ms,remaining_ms,events,query_ms\n")
            for (i in 0 until rows.length()) append(rows.getString(i)).append('\n')
        }
    }
}
