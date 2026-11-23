package io.github.detach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class UsageArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.now().minusDays(10)
    private val pkg = "app.sample"

    @Test fun `restoring twice keeps one record and preserves the saved name of an uninstalled app`() {
        UsageArchive(context).use { archive ->
            archive.restore(file(0))
            archive.restore(file(240))
            val output = StringWriter()
            archive.export(output)
            archive.restore(StringReader(output.toString()))
            val rows = archive.range(day, day.plusDays(1))
            assertEquals(listOf(ArchivedUsage(day.toEpochDay(), pkg, "Old app", 240, true)), rows)
            assertEquals(day, archive.firstDate())
        }
    }

    @Test fun `a truncated backup leaves the existing archive intact`() {
        UsageArchive(context).use { archive ->
            archive.restore(file(100))
            val truncated = file(300).readText().dropLast(2)
            assertTrue(runCatching { archive.restore(StringReader(truncated)) }.isFailure)
            assertEquals(100L, archive.range(day, day.plusDays(1)).single().ms)
        }
    }

    @Test fun `an absent or newly installed app cannot replace a saved day with zero`() {
        val now = day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val history = ScreenTimeHistory(now, java.time.ZoneOffset.UTC)
        history.event(now - 2 * ScreenTimeHistory.DAY, ScreenTimeHistory.Kind.SCREEN_OFF)
        val report = history.finish()
        UsageArchive(context).use { archive ->
            archive.restore(file(180_000L))
            archive.capture(report, setOf(pkg), emptyMap())
            archive.capture(report, setOf(pkg), mapOf(pkg to ("Reinstalled" to now)))
            assertEquals(listOf(ArchivedUsage(day.toEpochDay(), pkg, "Old app", 180_000L, true)),
                archive.range(day, day.plusDays(1)))
        }
    }

    private fun file(ms: Long) =
        StringReader("""{"format":"detach-screen-time-v1","days":[[${day.toEpochDay()},"$pkg","Old app",$ms,true]]}""")
}

class WeeklyUsageTest {
    @Test fun `pairs matching weekdays and excludes partial missing or unrelated app records`() {
        val today = LocalDate.of(2026, 4, 8)
        val rows = (1L..14L).flatMap { offset ->
            val date = today.minusDays(offset).toEpochDay()
            listOf(
                ArchivedUsage(date, "app.a", "A", (if (offset <= 7) 30 else 45) * ScreenTimeHistory.MINUTE, offset != 2L),
                ArchivedUsage(date, "app.b", "B", 10 * ScreenTimeHistory.MINUTE, offset !in listOf(3L, 11L)),
                ArchivedUsage(date, "app.noise", "Unselected", 900 * ScreenTimeHistory.MINUTE, true),
            )
        }
        val change = WeeklyUsage.compare(today, rows, setOf("app.a", "app.b"))
        assertNotNull(change)
        assertEquals(4, change!!.pairs)
        assertEquals(15 * ScreenTimeHistory.MINUTE, change.savedPerDay)
        assertNull(WeeklyUsage.compare(today, rows, setOf("app.a", "app.missing")))
    }
}
