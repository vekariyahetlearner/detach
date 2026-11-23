package io.github.detach

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/** Records confirmed YouTube-enabled Breather intervals, independently of enforcement. */
internal data class BreatherWindow(val start: Long, val end: Long)

internal object BreatherUsageHistory {
    data class Snapshot(val since: Long, val windows: List<BreatherWindow>)
    private data class Active(val context: Context, val wall: Long, val elapsed: Long, val duration: Long)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var active: Active? = null
    private val checkpoint = Runnable { runCatching { checkpoint() } }
    private const val RETENTION = 10 * ScreenTimeHistory.DAY
    private const val CHECKPOINT_MS = 15_000L

    @Synchronized fun start(context: Context, wall: Long, elapsed: Long, duration: Long) {
        finish()
        val app = context.applicationContext
        settings(app, wall)
        active = Active(app, wall, elapsed, duration)
        handler.postDelayed(checkpoint, minOf(CHECKPOINT_MS, duration))
    }

    @Synchronized fun finish(now: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) {
        val current = active ?: return
        active = null
        handler.removeCallbacks(checkpoint)
        record(current, now, elapsed)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("breather_usage_history", Context.MODE_PRIVATE)

    private fun settings(context: Context, now: Long) = prefs(context).apply {
        if (!contains("since")) edit().putLong("since", now).apply()
    }

    private fun confirmedEnd(current: Active, now: Long, elapsed: Long = SystemClock.elapsedRealtime()): Long? {
        val elapsedDelta = elapsed - current.elapsed
        val wallDelta = now - current.wall
        if (elapsedDelta < 0L || wallDelta < 0L || abs(wallDelta - elapsedDelta) > 2_000L) return null
        return current.wall + minOf(elapsedDelta, wallDelta, current.duration)
    }

    private fun windows(context: Context, now: Long) =
        prefs(context).getStringSet("windows", emptySet()).orEmpty().mapNotNull { entry ->
            val start = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val end = entry.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
            BreatherWindow(start, end).takeIf { start > 0 && end > start && end > now - RETENTION && end <= now }
        }

    private fun record(current: Active, now: Long, elapsed: Long): Long? {
        val end = confirmedEnd(current, now, elapsed) ?: return null
        if (end <= current.wall) return end
        val entries = windows(current.context, now).filterNot { it.start == current.wall } + BreatherWindow(current.wall, end)
        prefs(current.context).edit().putStringSet("windows", entries.mapTo(mutableSetOf()) { "${it.start}:${it.end}" }).apply()
        return end
    }

    @Synchronized private fun checkpoint() {
        val current = active ?: return
        val end = record(current, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val remaining = if (end == null) 0L else current.wall + current.duration - end
        if (remaining > 0L) handler.postDelayed(checkpoint, minOf(CHECKPOINT_MS, remaining)) else active = null
    }

    @Synchronized fun snapshot(context: Context, now: Long): Snapshot {
        val since = settings(context, now).getLong("since", now)
        val live = active?.let { current ->
            confirmedEnd(current, System.currentTimeMillis())?.let { BreatherWindow(current.wall, minOf(it, now)) }
        }
        val combined = (windows(context, now) + listOfNotNull(live)).filter { it.end > it.start }.sortedBy { it.start }
        val merged = mutableListOf<BreatherWindow>()
        combined.forEach { next ->
            val last = merged.lastOrNull()
            if (last != null && next.start <= last.end) {
                merged[merged.lastIndex] = BreatherWindow(last.start, maxOf(last.end, next.end))
            } else merged.add(next)
        }
        return Snapshot(since, merged)
    }
}
