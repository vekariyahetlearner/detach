package io.github.detach

import android.content.Context

/** Persisted, wall-clock based Focus session. It survives activity recreation and process death. */
object FocusSession {
    private const val PREFS = "focus_session"
    private const val END = "end_wall"
    private const val DURATION = "duration_ms"
    private const val LAST_DURATION = "last_duration_seconds"
    const val MAX_PICKER_SECONDS = 24 * 60 * 60L - 1
    private const val DEFAULT_SECONDS = 25 * 60L

    fun start(context: Context, minutes: Int) {
        startSeconds(context, minutes.coerceIn(1, 24 * 60) * 60L)
    }

    fun startSeconds(context: Context, seconds: Long) {
        val duration = seconds.coerceIn(1L, 24L * 60 * 60) * 1_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(END, System.currentTimeMillis() + duration).putLong(DURATION, duration)
            .putLong(LAST_DURATION, (duration / 1_000L).coerceAtMost(MAX_PICKER_SECONDS)).apply()
    }

    fun end(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(END).remove(DURATION).apply()

    fun durationMs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DURATION, 0L)

    fun lastDurationSeconds(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(LAST_DURATION, DEFAULT_SECONDS).coerceIn(1L, MAX_PICKER_SECONDS)

    fun durationText(seconds: Long) = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    fun remainingMs(context: Context): Long {
        val remaining = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(END, 0L) - System.currentTimeMillis()
        return remaining.coerceAtLeast(0L)
    }

    fun active(context: Context) = remainingMs(context) > 0L

    fun remainingLabel(milliseconds: Long) = "${remainingTime(milliseconds)} remaining"

    fun remainingTime(milliseconds: Long): String {
        val seconds = (milliseconds + 999L) / 1_000L
        return if (seconds >= 3_600L) {
            "%d:%02d:%02d".format(seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
        } else {
            "%d:%02d".format(seconds / 60L, seconds % 60L)
        }
    }
}
