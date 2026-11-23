package io.github.detach

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, prepaid daily allowance. An edited allowance always starts next local day. */
object BreatherBudget {
    const val MAX_MINUTES = 60
    const val DEFAULT_DAILY_MINUTES = 30
    private const val PREFS = "breather_budget"
    private val lock = Any()

    data class Status(
        val dailyLimit: Int, val nextLimit: Int, val used: Int, val available: Int,
        val dailyChanged: Boolean, val cooldownEnabled: Boolean, val cooldownRemainingMs: Long,
        val periodEnd: Long,
    )
    data class Entry(val startedAt: Long, val minutes: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun nextMidnight(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        .plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun refresh(context: Context): Status? {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (now <= 0 || elapsed < 0 || boot < 0) return null
        if (!p.contains("end")) {
            if (p.all.isNotEmpty()) return null
            if (!p.edit().putLong("end", nextMidnight(now)).putLong("started_wall", now)
                    .putLong("started_elapsed", elapsed).putInt("boot", boot)
                    .putInt("limit", DEFAULT_DAILY_MINUTES).putInt("next", DEFAULT_DAILY_MINUTES)
                    .putInt("used", 0).commit()) return null
        }
        val end = p.getLong("end", -1); val start = p.getLong("started_wall", -1)
        val startElapsed = p.getLong("started_elapsed", -1); val savedBoot = p.getInt("boot", -1)
        // limit is today's allowance; next is the one selected for tomorrow.
        val limit = p.getInt("limit", -1); val next = p.getInt("next", limit); val used = p.getInt("used", -1)
        val dailyChanged = p.getBoolean("daily_changed", false); val cooldown = p.getBoolean("cooldown_enabled", false)
        val cooldownWall = p.getLong("cooldown_wall", 0); val cooldownElapsed = p.getLong("cooldown_elapsed", 0)
        val cooldownBoot = p.getInt("cooldown_boot", boot); val cooldownDelay = p.getLong("cooldown_delay", 15 * 60_000L)
        if (start <= 0 || end <= start || startElapsed < 0 || savedBoot < 0 || now < start ||
            limit !in 0..MAX_MINUTES || next !in 0..MAX_MINUTES || used !in 0..MAX_MINUTES ||
            cooldownWall < 0 || cooldownElapsed < 0 || cooldownBoot < 0 || cooldownDelay !in 15 * 60_000L..75 * 60_000L) return null
        val waitWall = (cooldownWall - now).coerceAtLeast(0)
        val waitElapsed = if (cooldownBoot == boot) (cooldownElapsed - elapsed).coerceAtLeast(0)
            else if (cooldownWall > 0) (cooldownDelay - elapsed).coerceAtLeast(0) else 0
        val cooldownRemaining = maxOf(waitWall, waitElapsed)
        if (cooldownWall > 0 && cooldownRemaining == 0L && !p.edit().remove("cooldown_wall")
                .remove("cooldown_elapsed").remove("cooldown_boot").remove("cooldown_delay").commit()) return null
        if (now >= end) {
            if (boot == savedBoot && (elapsed < startElapsed || elapsed - startElapsed < end - start)) return null
            if (boot != savedBoot && elapsed < end - start) return null
            val nextEnd = nextMidnight(now)
            if (!p.edit().putLong("end", nextEnd).putLong("started_wall", now).putLong("started_elapsed", elapsed)
                    .putInt("boot", boot).putInt("limit", next).putInt("next", next).putInt("used", 0)
                    .putBoolean("daily_changed", false).remove("pending_daily").remove("daily_limit")
                    .remove("limit_changed").remove("scheduled_increase").remove("reduced_elapsed").remove("history").commit()) return null
            return Status(next, next, 0, next, false, cooldown, cooldownRemaining, nextEnd)
        }
        return Status(limit, next, used, (limit - used).coerceAtLeast(0), dailyChanged, cooldown, cooldownRemaining, end)
    }

    fun status(context: Context): Status? = synchronized(lock) { runCatching { refresh(context) }.getOrNull() }
    fun restrictionsLocked(context: Context): Boolean = status(context)?.let { it.available == 0 } ?: true

    fun setCooldown(context: Context, enabled: Boolean): Boolean = synchronized(lock) {
        val current = runCatching { refresh(context) }.getOrNull() ?: return@synchronized false
        if (current.used != 0 || current.cooldownRemainingMs > 0) return@synchronized false
        runCatching { prefs(context).edit().putBoolean("cooldown_enabled", enabled).commit() }.getOrDefault(false)
    }

    /** Saves tomorrow's allowance; the current day's allowance is never altered. */
    fun setDailyLimit(context: Context, minutes: Int): Boolean = synchronized(lock) {
        if (minutes !in 0..MAX_MINUTES) return@synchronized false
        val current = runCatching { refresh(context) }.getOrNull() ?: return@synchronized false
        if (current.dailyChanged) return@synchronized false
        if (minutes == current.nextLimit) return@synchronized true
        runCatching { prefs(context).edit().putInt("next", minutes).putBoolean("daily_changed", true)
            .remove("pending_daily").remove("daily_limit").commit() }.getOrDefault(false)
    }

    fun history(context: Context): List<Entry> = synchronized(lock) { runCatching {
        refresh(context) ?: return@synchronized emptyList()
        val records = JSONArray(prefs(context).getString("history", "[]"))
        (0 until records.length()).map { records.getJSONObject(it) }.map { Entry(it.getLong("start"), it.getInt("minutes")) }
            .filter { it.startedAt > 0 && it.minutes in 1..MAX_MINUTES }
    }.getOrDefault(emptyList()) }

    fun charge(context: Context, minutes: Int): Boolean = synchronized(lock) {
        val current = runCatching { refresh(context) }.getOrNull() ?: return@synchronized false
        if (minutes !in 1..current.available || current.cooldownRemainingMs > 0) return@synchronized false
        runCatching {
            val now = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (boot < 0 || elapsed < 0 || now <= 0) return@runCatching false
            val history = JSONArray(prefs(context).getString("history", "[]")).put(JSONObject().put("start", now).put("minutes", minutes))
            val edit = prefs(context).edit().putInt("used", current.used + minutes).putString("history", history.toString())
            if (current.cooldownEnabled) {
                val delay = (minutes + 15) * 60_000L
                edit.putLong("cooldown_wall", now + delay).putLong("cooldown_elapsed", elapsed + delay)
                    .putInt("cooldown_boot", boot).putLong("cooldown_delay", delay)
            }
            edit.commit()
        }.getOrDefault(false)
    }
}
