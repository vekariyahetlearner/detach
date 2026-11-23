package io.github.detach

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reduces chronological UsageEvents into local calendar days without storing raw history. */
internal class ScreenTimeHistory(
    now: Long,
    private val zone: ZoneId,
    private val breather: BreatherUsageHistory.Snapshot? = null,
    private val selectedApps: Set<String>? = null,
) {
    companion object {
        const val MINUTE = 60_000L
        const val DAY = 24L * 60 * MINUTE
        const val LIFESPAN_YEARS = 70
        const val DAYS_PER_YEAR = 365.2425
    }

    enum class Kind { SCREEN_ON, SCREEN_OFF, LOCK, UNLOCK, RESUME, PAUSE, RESTART }
    data class Usage(val ms: Long, val entries: Int)
    data class Day(
        val date: LocalDate,
        val start: Long,
        val end: Long,
        val dayPartEnds: List<Long>,
        var measuredMs: Long = 0L,
        var screenMs: Long = 0L,
        var unlocks: Int = 0,
        var longestMs: Long = 0L,
        var unattributedMs: Long = 0L,
        val parts: LongArray = LongArray(4),
        val apps: MutableMap<String, Usage> = mutableMapOf(),
        val appParts: MutableMap<String, LongArray> = mutableMapOf(),
    ) {
        val complete get() = measuredMs == end - start
    }

    data class Report(val days: List<Day>, val asOf: Long, val youtubeTrackedSince: Long?) {
        val today get() = days.last()
        val history get() = days.dropLast(1)
        val completeDays get() = history.filter { it.complete }
        val averageMs get() = completeDays.takeIf { it.isNotEmpty() }?.map { it.screenMs }?.average()

        fun apps(days: List<Day>): Map<String, Usage> = buildMap {
            days.forEach { day ->
                day.apps.forEach { (pkg, usage) ->
                    val old = get(pkg) ?: Usage(0, 0)
                    put(pkg, Usage(old.ms + usage.ms, old.entries + usage.entries))
                }
            }
        }

        fun distractingAverageMs(packages: Set<String>): Double? {
            if (packages.isEmpty()) return null
            return eligibleDays(packages).takeIf { it.isNotEmpty() }?.map { distractingMs(it, packages) }?.average()
        }

        fun eligibleDays(packages: Set<String>) = completeDays.filter {
            Targets.YOUTUBE !in packages || youtubeTrackedSince?.let { since -> it.start >= since } == true
        }

        fun distractingMs(day: Day, packages: Set<String>): Long = packages.sumOf { day.apps[it]?.ms ?: 0L }

        fun lifeYears(dailyMs: Double, age: Int) = dayShare(dailyMs) * yearsUntil70(age)

        fun dayShare(dailyMs: Double) = (dailyMs / DAY).coerceIn(0.0, 1.0)

        fun yearsUntil70(age: Int) = (LIFESPAN_YEARS - age).coerceAtLeast(0)
    }

    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    private fun LocalDate.atHour(hour: Int): Long =
        atStartOfDay().plusHours(hour.toLong()).atZone(zone).toInstant().toEpochMilli()

    private val days = (7L downTo 0L).map { offset ->
        val date = today.minusDays(offset)
        Day(date, date.atHour(0), date.atHour(24), (1..4).map { date.atHour(it * 6) })
    }
    val queryStart = today.minusDays(8).atHour(0)
    private val report = Report(days, now, breather?.since)
    private val youtubeWindows = breather?.windows.orEmpty()
    private var youtubeIndex = 0
    private var cursor = queryStart
    private var dayIndex = 0
    private var interactive: Boolean? = null
    private var unlocked: Boolean? = null
    private var activity: Pair<String, String?>? = null
    private var lastApp: String? = null
    private var lastAppEnd = 0L
    private var sessionMs = 0L
    private val usingScreen get() = interactive == true && unlocked == true

    fun event(at: Long, kind: Kind, pkg: String? = null, activityName: String? = null) {
        if (at < cursor || at >= report.asOf) return
        if (kind == Kind.RESTART && usingScreen) {
            // A shutdown timestamp can follow the last recorded activity by an unknown gap.
            interactive = null
            unlocked = null
            sessionMs = 0L
            lastApp = null
        }
        advance(at)
        val wasUsing = usingScreen
        when (kind) {
            Kind.SCREEN_ON -> interactive = true
            Kind.SCREEN_OFF -> interactive = false
            Kind.LOCK -> unlocked = false
            Kind.UNLOCK -> {
                if (unlocked == false && at >= days.first().start) days[dayIndex].unlocks++
                unlocked = true
            }
            Kind.RESUME -> if (pkg != null) { activity = pkg to activityName }
            Kind.PAUSE -> if (activity == (pkg to activityName)) activity = null
            Kind.RESTART -> {
                interactive = false
                unlocked = false
                activity = null
            }
        }
        if (wasUsing && !usingScreen) {
            sessionMs = 0L
            lastApp = null
        }
    }

    private fun advance(until: Long) {
        var start = maxOf(cursor, days.first().start)
        while (start < until) {
            val day = days[dayIndex]
            if (start >= day.end) {
                dayIndex++
                sessionMs = 0L
                continue
            }
            val part = day.dayPartEnds.indexOfFirst { it > start }
            val end = minOf(until, day.dayPartEnds[part])
            val duration = end - start
            if (interactive == false || unlocked == false || usingScreen) day.measuredMs += duration
            if (usingScreen) {
                day.screenMs += duration
                day.parts[part] += duration
                sessionMs += duration
                day.longestMs = maxOf(day.longestMs, sessionMs)
                val pkg = activity?.first
                if (pkg == null || selectedApps?.contains(pkg) == false) {
                    day.unattributedMs += duration
                } else if (pkg == Targets.YOUTUBE) {
                    while (youtubeIndex < youtubeWindows.size && youtubeWindows[youtubeIndex].end <= start) youtubeIndex++
                    for (index in youtubeIndex until youtubeWindows.size) {
                        val window = youtubeWindows[index]
                        if (window.start >= end) break
                        recordApp(day, part, pkg, maxOf(start, window.start), minOf(end, window.end))
                    }
                } else {
                    recordApp(day, part, pkg, start, end)
                }
            }
            start = end
        }
        cursor = until
        // An event right at midnight belongs to the new day, even if the loop ended there.
        if (dayIndex < days.lastIndex && until >= days[dayIndex].end) {
            dayIndex++
            sessionMs = 0L
        }
    }

    private fun recordApp(day: Day, part: Int, pkg: String, start: Long, end: Long) {
        if (end <= start) return
        val duration = end - start
        val old = day.apps[pkg] ?: Usage(0L, 0)
        val newEntry = pkg != lastApp || start - lastAppEnd > 2_000L
        day.apps[pkg] = Usage(old.ms + duration, old.entries + if (newEntry) 1 else 0)
        day.appParts.getOrPut(pkg) { LongArray(4) }[part] += duration
        lastApp = pkg
        lastAppEnd = end
    }

    fun finish(): Report {
        advance(report.asOf)
        return report
    }
}
