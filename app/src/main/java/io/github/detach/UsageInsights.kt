package io.github.detach

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

/** Local models use comparable completed days. No online model or personal details are needed. */
internal object UsageInsights {
    data class Forecast(val typicalMs: Long, val errorMs: Long, val samples: Int)
    data class Pattern(val day: DayOfWeek, val medianMs: Long, val samples: Int)
    data class Driver(val label: String, val differenceMs: Long)
    data class Outlier(val date: LocalDate, val actualMs: Long, val baselineMs: Long)
    data class Result(
        val weekly: WeeklyUsage.Change?,
        val driver: Driver?,
        val forecast: Forecast?,
        val highDay: Pattern?,
        val outlier: Outlier?,
        val baselineMs: Long?,
    )

    fun analyze(today: LocalDate, rows: List<ArchivedUsage>, apps: Set<String>): Result {
        val complete = rows.filter { it.complete && it.pkg in apps && it.day < today.toEpochDay() }
            .groupBy { LocalDate.ofEpochDay(it.day) }.filterValues { it.map { row -> row.pkg }.toSet() == apps }
        val daily = complete.mapValues { (_, records) -> records.sumOf { it.ms } }.toSortedMap()
        val previous = daily.filterKeys { !it.isBefore(today.minusDays(14)) }
        val recent = daily.filterKeys { !it.isBefore(today.minusDays(28)) }
        val forecast = if (recent.size >= 14 && recent.keys.last() >= today.minusDays(3)) forecast(today, recent) else null
        val med = recent.values.takeIf { it.size >= 5 }?.let { median(it.toList()) }
        val peak = daily.entries.groupBy { it.key.dayOfWeek }.mapNotNull { (dow, entries) ->
            if (entries.size >= 3) Pattern(dow, median(entries.map { it.value }), entries.size) else null
        }.sortedByDescending { it.medianMs }.let {
            it.firstOrNull()?.takeIf { top -> it.size >= 2 && top.medianMs - it.last().medianMs >= 10 * ScreenTimeHistory.MINUTE }
        }
        val previousDays = recent.entries.toList().dropLast(1)
        val latest = recent.entries.lastOrNull()
        val outlier = if (latest != null && latest.key >= today.minusDays(3) && previousDays.size >= 7) {
            val center = median(previousDays.map { it.value })
            val deviation = median(previousDays.map { abs(it.value - center) })
            if (latest.value - center > maxOf(20 * ScreenTimeHistory.MINUTE, 3 * deviation)) Outlier(latest.key, latest.value, center)
            else null
        } else null
        val pairs = previous.keys.filter { !it.isBefore(today.minusDays(7)) && previous.containsKey(it.minusWeeks(1)) }
        val driver = if (pairs.size >= 4) apps.mapNotNull { pkg ->
            val differences = pairs.map { date ->
                val new = complete.getValue(date).first { it.pkg == pkg }
                val old = complete.getValue(date.minusWeeks(1)).first { it.pkg == pkg }
                new.label to (old.ms - new.ms)
            }
            val avg = differences.sumOf { it.second } / differences.size
            if (abs(avg) >= 5 * ScreenTimeHistory.MINUTE) Driver(differences.last().first, avg) else null
        }.maxByOrNull { abs(it.differenceMs) } else null
        return Result(WeeklyUsage.compare(today, rows, apps), driver, forecast, peak, outlier, med)
    }

    private fun forecast(target: LocalDate, data: Map<LocalDate, Long>): Forecast {
        fun predict(day: LocalDate, known: Map<LocalDate, Long>): Double {
            val ages = known.map { (date, ms) ->
                val age = (day.toEpochDay() - date.toEpochDay()).coerceAtLeast(1L)
                val weight = .5.pow(age / 10.0) * if (day.dayOfWeek == date.dayOfWeek) 2.0 else 1.0
                weight to ms
            }
            return ages.sumOf { it.first * it.second } / ages.sumOf { it.first }
        }
        val heldOut = data.entries.toList().takeLast(5)
        val errors = heldOut.map { (day, actual) -> abs(predict(day, data.filterKeys { it < day }) - actual) }
        return Forecast(predict(target, data).toLong(), errors.average().toLong(), data.size)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}
