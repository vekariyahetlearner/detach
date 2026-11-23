package io.github.detach

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BreatherUsageHistoryTest {
    @Test fun `early end records only elapsed time and never the remaining prepaid time`() {
        val context = RuntimeEnvironment.getApplication()
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        BreatherUsageHistory.start(context, wall, elapsed, 10 * 60_000L)
        try {
            BreatherUsageHistory.finish(wall + 2 * 60_000L, elapsed + 2 * 60_000L)
            val snapshot = BreatherUsageHistory.snapshot(context, wall + 4 * 60_000L)
            assertEquals(wall, snapshot.since)
            assertEquals(listOf(BreatherWindow(wall, wall + 2 * 60_000L)), snapshot.windows)
        } finally {
            BreatherUsageHistory.finish()
        }
    }
}
