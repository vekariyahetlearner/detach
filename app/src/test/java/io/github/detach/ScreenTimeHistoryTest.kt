package io.github.detach

import io.github.detach.ScreenTimeHistory.Kind
import io.github.detach.ScreenTimeHistory.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScreenTimeHistoryTest {
    private val utc = ZoneId.of("UTC")
    private fun time(stamp: String) = Instant.parse(stamp).toEpochMilli()

    @Test fun `screen time excludes lock screen and attributes one foreground app at a time`() {
        val start = time("2026-11-23T09:00:00Z")
        val history = ScreenTimeHistory(start + 120 * MINUTE, utc)
        history.event(start, Kind.LOCK)
        history.event(start, Kind.SCREEN_ON)
        history.event(start + 10 * MINUTE, Kind.UNLOCK)
        history.event(start + 10 * MINUTE, Kind.RESUME, "app.one", "Main")
        history.event(start + 25 * MINUTE, Kind.RESUME, "app.two", "Main")
        history.event(start + 26 * MINUTE, Kind.PAUSE, "app.one", "Main")
        history.event(start + 40 * MINUTE, Kind.PAUSE, "app.two", "Main")
        history.event(start + 50 * MINUTE, Kind.SCREEN_OFF)
        history.event(start + 60 * MINUTE, Kind.SCREEN_ON)
        history.event(start + 65 * MINUTE, Kind.LOCK)
        val today = history.finish().today
        assertEquals(45 * MINUTE, today.screenMs)
        assertEquals(40 * MINUTE, today.longestMs)
        assertEquals(1, today.unlocks)
        assertEquals(15 * MINUTE, today.apps.getValue("app.one").ms)
        assertEquals(15 * MINUTE, today.apps.getValue("app.two").ms)
        assertEquals(15 * MINUTE, today.unattributedMs)
        assertEquals(today.screenMs, today.parts[1])
    }

    @Test fun `gaps in retained history and today are excluded from the lifetime pace`() {
        val now = time("2026-11-23T12:00:00Z")
        val history = ScreenTimeHistory(now, utc)
        history.event(time("2026-11-21T17:00:00Z"), Kind.SCREEN_OFF)
        history.event(time("2026-11-22T09:00:00Z"), Kind.SCREEN_ON)
        history.event(time("2026-11-22T09:00:00Z"), Kind.UNLOCK)
        history.event(time("2026-11-22T09:00:00Z"), Kind.RESUME, "app.distraction")
        history.event(time("2026-11-22T12:00:00Z"), Kind.SCREEN_OFF)
        history.event(time("2026-11-23T08:00:00Z"), Kind.SCREEN_ON)
        val report = history.finish()
        assertEquals(1, report.completeDays.size)
        assertEquals(180 * MINUTE.toDouble(), report.averageMs!!, 0.1)
        assertEquals(180 * MINUTE.toDouble(), report.distractingAverageMs(setOf("app.distraction"))!!, 0.1)
        assertEquals(240 * MINUTE, report.today.screenMs)
        assertFalse(report.history[5].complete)
    }

    @Test fun `remaining lifespan counts selected app time and includes zero use days in the average`() {
        val now = time("2026-11-23T12:00:00Z")
        val history = ScreenTimeHistory(now, utc)
        history.event(time("2026-11-20T22:00:00Z"), Kind.SCREEN_OFF)
        history.event(time("2026-11-21T09:00:00Z"), Kind.SCREEN_ON)
        history.event(time("2026-11-21T09:00:00Z"), Kind.UNLOCK)
        history.event(time("2026-11-21T09:00:00Z"), Kind.RESUME, "app.study")
        history.event(time("2026-11-21T11:00:00Z"), Kind.RESUME, "app.distraction")
        history.event(time("2026-11-21T15:00:00Z"), Kind.SCREEN_OFF)
        history.event(time("2026-11-22T10:00:00Z"), Kind.SCREEN_ON)
        history.event(time("2026-11-22T10:00:00Z"), Kind.RESUME, "app.study")
        history.event(time("2026-11-22T13:00:00Z"), Kind.SCREEN_OFF)
        val report = history.finish()
        val distracting = report.distractingAverageMs(setOf("app.distraction"))!!
        assertEquals(120 * MINUTE.toDouble(), distracting, 0.1)
        assertEquals(270 * MINUTE.toDouble(), report.averageMs!!, 0.1)
        assertEquals(3.75, report.lifeYears(distracting, 25), 0.001)
        assertEquals(0.0, report.lifeYears(distracting, 70), 0.001)
        assertEquals(0.0, report.lifeYears(distracting, 82), 0.001)
        assertNull(report.distractingAverageMs(emptySet()))
    }

    @Test fun `activity splits at local midnight and daylight saving boundaries`() {
        val history = ScreenTimeHistory(time("2026-03-09T06:30:00Z"), ZoneId.of("America/New_York"))
        history.event(time("2026-03-07T18:00:00Z"), Kind.SCREEN_OFF)
        history.event(time("2026-03-08T04:30:00Z"), Kind.SCREEN_ON)
        history.event(time("2026-03-08T04:30:00Z"), Kind.UNLOCK)
        history.event(time("2026-03-08T04:30:00Z"), Kind.RESUME, "app.one")
        history.event(time("2026-03-08T10:30:00Z"), Kind.SCREEN_OFF)
        val report = history.finish()
        val saturday = report.history[5]
        val sunday = report.history[6]
        assertEquals(30 * MINUTE, saturday.screenMs)
        assertEquals(330 * MINUTE, sunday.screenMs)
        assertEquals(300 * MINUTE, sunday.parts[0])
        assertEquals(30 * MINUTE, sunday.parts[1])
        assertEquals(23 * 60 * MINUTE, sunday.end - sunday.start)
        assertTrue(sunday.complete)
        assertEquals(0, sunday.apps.getValue("app.one").entries)
    }

    @Test fun `selected YouTube only counts Breather overlap while Instagram counts in full`() {
        val now = time("2026-11-23T12:00:00Z")
        val start = time("2026-11-22T09:00:00Z")
        val since = time("2026-11-21T12:00:00Z")
        val windows = listOf(
            BreatherWindow(start + 15 * MINUTE, start + 25 * MINUTE),
            BreatherWindow(start + 50 * MINUTE, start + 75 * MINUTE),
        )
        val selected = setOf(Targets.YOUTUBE, Targets.INSTAGRAM)
        val history = ScreenTimeHistory(now, utc, BreatherUsageHistory.Snapshot(since, windows), selected)
        history.event(time("2026-11-20T18:00:00Z"), Kind.SCREEN_OFF)
        history.event(start, Kind.SCREEN_ON)
        history.event(start, Kind.UNLOCK)
        history.event(start, Kind.RESUME, Targets.YOUTUBE)
        history.event(start + 60 * MINUTE, Kind.RESUME, Targets.INSTAGRAM)
        history.event(start + 90 * MINUTE, Kind.RESUME, "app.study")
        history.event(start + 105 * MINUTE, Kind.SCREEN_OFF)
        val report = history.finish()
        val day = report.history.last()
        assertEquals(ScreenTimeHistory.Usage(20 * MINUTE, 2), day.apps.getValue(Targets.YOUTUBE))
        assertEquals(30 * MINUTE, day.apps.getValue(Targets.INSTAGRAM).ms)
        assertFalse(day.apps.containsKey("app.study"))
        assertEquals(20 * MINUTE, day.appParts.getValue(Targets.YOUTUBE)[1])
        assertEquals(1, report.eligibleDays(selected).size)
        assertEquals(2, report.eligibleDays(setOf(Targets.INSTAGRAM)).size)
        assertEquals(50 * MINUTE.toDouble(), report.distractingAverageMs(selected)!!, 0.1)
    }

    @Test fun `an open session with an unknown shutdown time is not extrapolated`() {
        val start = time("2026-11-23T09:00:00Z")
        val history = ScreenTimeHistory(start + 8 * 60 * MINUTE, utc)
        history.event(start, Kind.SCREEN_ON)
        history.event(start, Kind.UNLOCK)
        history.event(start + 10 * MINUTE, Kind.RESUME, "app.one")
        history.event(start + 7 * 60 * MINUTE, Kind.RESTART)
        val report = history.finish()
        assertEquals(10 * MINUTE, report.today.screenMs)
        assertNull(report.distractingAverageMs(setOf("app.one")))
    }
}
