package io.github.detach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageInsightsTest {
    private val today = LocalDate.of(2026, 11, 23)
    private val minute = ScreenTimeHistory.MINUTE

    @Test fun `forecasts use held out days and find the weekday pattern and the largest weekly driver`() {
        val rows = (1L..28L).flatMap { offset ->
            val date = today.minusDays(offset)
            val base = if (date.dayOfWeek == DayOfWeek.SUNDAY) 40 else 20
            listOf(
                ArchivedUsage(date.toEpochDay(), "app.a", "A", (base + if (offset > 7) 10 else 0) * minute, true),
                ArchivedUsage(date.toEpochDay(), "app.b", "B", 5 * minute, true),
                ArchivedUsage(date.toEpochDay(), "app.study", "Study", 400 * minute, true),
            )
        }
        val result = UsageInsights.analyze(today, rows, setOf("app.a", "app.b"))
        val forecast = result.forecast!!
        assertEquals(28, forecast.samples)
        assertTrue(forecast.typicalMs in 25 * minute..45 * minute)
        assertTrue(forecast.errorMs > 0)
        assertEquals(DayOfWeek.SUNDAY, result.highDay!!.day)
        assertEquals(10 * minute, result.weekly!!.savedPerDay)
        assertEquals(UsageInsights.Driver("A", 10 * minute), result.driver)
        assertNull(UsageInsights.analyze(today, rows, setOf("app.a", "app.missing")).forecast)
    }

    @Test fun `partial observations and a day in progress do not drive forecasts or hide an outlier`() {
        val rows = (0L..16L).map { offset ->
            val value = when (offset) { 0L, 2L -> 1000L; 1L -> 65L; else -> 20L }
            ArchivedUsage(today.minusDays(offset).toEpochDay(), "app.a", "A", value * minute, offset != 2L)
        }
        val result = UsageInsights.analyze(today, rows, setOf("app.a"))
        assertEquals(today.minusDays(1), result.outlier!!.date)
        assertEquals(20 * minute, result.outlier.baselineMs)
        assertEquals(15, result.forecast!!.samples)
        assertNull(UsageInsights.analyze(today, rows.drop(4), setOf("app.a")).forecast)
    }

    @Test fun `age and remaining lifespan update at calendar boundaries`() {
        val profile = ScreenTimeProfile("Nickname", LocalDate.of(2000, 2, 29))
        assertEquals(24, profile.ageOn(LocalDate.of(2025, 2, 27)))
        assertEquals(25, profile.ageOn(LocalDate.of(2025, 2, 28)))
        assertTrue(profile.yearsUntil70(LocalDate.of(2070, 2, 27)) > 0.0)
        assertEquals(0.0, profile.yearsUntil70(LocalDate.of(2070, 2, 28)), 0.0)
        assertFalse(ScreenTimeProfile.validDate(today.plusDays(1), today))
    }

    @Test fun `midnight uses the local calendar across DST`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val expected = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()
        assertEquals(expected, UsageArchiveJobs.nextMidnight(now, zone))
        assertNotNull(UsageInsights.analyze(today, emptyList(), emptySet()))
    }
}
