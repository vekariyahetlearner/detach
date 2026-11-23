package io.github.detach

import android.content.Context

/** Persisted, wall-clock based Focus session. It survives activity recreation and process death. */
object FocusSession {
    private const val PREFS = "focus_session"
    private const val END = "end_wall"

    fun start(context: Context, minutes: Int) {
        startSeconds(context, minutes.coerceIn(1, 24 * 60) * 60L)
    }

    fun startSeconds(context: Context, seconds: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(END, System.currentTimeMillis() + seconds.coerceIn(1L, 24L * 60 * 60) * 1_000L).apply()
    }

    fun end(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(END).apply()

    fun remainingMs(context: Context): Long {
        val remaining = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(END, 0L) - System.currentTimeMillis()
        if (remaining <= 0L) end(context)
        return remaining.coerceAtLeast(0L)
    }

    fun active(context: Context) = remainingMs(context) > 0L
}
