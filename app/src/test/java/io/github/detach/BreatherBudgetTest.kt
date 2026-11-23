package io.github.detach

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BreatherBudgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("breather_budget", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 9)
    }

    @Test fun `daily allowance is prepaid and cannot be exceeded`() {
        assertTrue(BreatherBudget.charge(context, 20))
        assertEquals(10, BreatherBudget.status(context)!!.available)
        assertFalse(BreatherBudget.charge(context, 11))
        assertTrue(BreatherBudget.charge(context, 10))
        assertTrue(BreatherBudget.restrictionsLocked(context))
    }

    @Test fun `daily allowance can be selected once and begins tomorrow`() {
        assertTrue(BreatherBudget.setDailyLimit(context, 45))
        val today = BreatherBudget.status(context)!!
        assertEquals(30, today.dailyLimit)
        assertEquals(45, today.nextLimit)
        assertTrue(today.dailyChanged)
        assertFalse(BreatherBudget.setDailyLimit(context, 15))
        midnight()
        val tomorrow = BreatherBudget.status(context)!!
        assertEquals(45, tomorrow.dailyLimit)
        assertEquals(45, tomorrow.available)
        assertFalse(tomorrow.dailyChanged)
    }

    @Test fun `unchanged selection does not consume the daily edit`() {
        assertTrue(BreatherBudget.setDailyLimit(context, 30))
        assertFalse(BreatherBudget.status(context)!!.dailyChanged)
        assertTrue(BreatherBudget.setDailyLimit(context, 20))
        assertTrue(BreatherBudget.status(context)!!.dailyChanged)
    }

    @Test fun `cooldown and history remain intact`() {
        assertTrue(BreatherBudget.setCooldown(context, true))
        assertTrue(BreatherBudget.charge(context, 5))
        assertEquals(listOf(5), BreatherBudget.history(context).map { it.minutes })
        assertFalse(BreatherBudget.charge(context, 1))
    }

    private fun midnight() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("end", now - 1).putLong("started_wall", now - 2)
            .putLong("started_elapsed", (SystemClock.elapsedRealtime() - 2_000).coerceAtLeast(0)).putInt("boot", 8).commit()
    }
}
