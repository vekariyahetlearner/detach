package io.github.detach

import android.content.Context
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FocusSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("focus_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        FocusActivity.active = false
    }

    @Test fun `one minute test session is active and expires automatically`() {
        FocusSession.start(context, 1)
        assertTrue(FocusSession.active(context))
        assertTrue(FocusSession.remainingMs(context) in 1..60_000)
        context.getSharedPreferences("focus_session", Context.MODE_PRIVATE).edit()
            .putLong("end_wall", System.currentTimeMillis() - 1).commit()
        assertFalse(FocusSession.active(context))
    }

    @Test fun `useful communication apps are preselected for a first Focus test`() {
        val allowed = Settings(context).focusAllowedPackages
        assertTrue("com.whatsapp" in allowed)
        assertTrue("com.google.android.apps.docs" in allowed)
        assertTrue(allowed.any { it.endsWith(".dialer") })
        assertTrue(allowed.any { it.endsWith(".contacts") })
    }

    @Test fun `countdown rounds partial seconds and displays hours for long sessions`() {
        assertEquals("1:00 remaining", FocusSession.remainingLabel(59_999))
        assertEquals("0:01 remaining", FocusSession.remainingLabel(1))
        assertEquals("1:01:01 remaining", FocusSession.remainingLabel(3_660_001))
    }

    @Test fun `empty saved allowlist recovers to useful defaults`() {
        Settings(context).focusAllowedPackages = emptySet()
        assertTrue("com.whatsapp" in Settings(context).focusAllowedPackages)
    }

    @Test fun `Focus home has no back exit while its session remains active`() {
        FocusSession.start(context, 1)
        val activity = Robolectric.buildActivity(FocusActivity::class.java).setup().get()
        activity.onBackPressed()
        assertFalse(activity.isFinishing)
        assertTrue(FocusSession.active(context))
    }

    @Test fun `Focus home communicates active state and remaining time`() {
        FocusSession.start(context, 1)
        val activity = Robolectric.buildActivity(FocusActivity::class.java).setup().get()
        val text = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().joinToString(" ") { it.text }
        assertTrue(text.contains("FOCUS ACTIVE"))
        assertTrue(text.contains("remaining"))
        assertTrue(text.contains("Locked until the timer ends"))
    }

    private fun descendants(view: View): List<View> {
        val children = if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return listOf(view) + children
    }
}
