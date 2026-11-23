package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class BreatherControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = BreatherTestSupport.reset()

    @Test fun `start is a full width action above its secondary controls`() {
        val activity = build()
        val daily = button(activity, "Daily allowance")
        val start = button(activity, "Start a breather")
        val cooldown = button(activity, "Cooldown: off")
        val history = button(activity, "History")
        val controls = start.parent as LinearLayout
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, start.layoutParams.width)
        assertEquals(LinearLayout.VERTICAL, controls.orientation)
        assertTrue(controls.indexOfChild(start) < controls.indexOfChild(daily))
        assertTrue(cooldown.parent === history.parent)
        assertEquals(LinearLayout.HORIZONTAL, (cooldown.parent as LinearLayout).orientation)
        assertTrue(listOf(start, daily, cooldown, history).all { it.minimumHeight >= Ui(context).dp(48) })
    }

    @Test fun `daily allowance editor clearly applies tomorrow and locks after saving`() {
        val activity = build()
        button(activity, "Daily allowance").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Daily allowance", shadowOf(dialog).title.toString())
        val text = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString(" ") { it.text }
        assertTrue(text.contains("Today's allowance will not change"))
        val picker = views(dialog.window!!.decorView).filterIsInstance<NumberPicker>().single()
        picker.value = 45
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(45, BreatherBudget.status(context)!!.nextLimit)
        assertFalse(button(activity, "Daily allowance").isEnabled)
    }

    @Test fun `breather picker cannot exceed remaining allowance`() {
        assertTrue(BreatherBudget.charge(context, 25))
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        button(activity, "Start a breather").performClick()
        val picker = views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<NumberPicker>().single()
        assertEquals(5, picker.maxValue)
    }

    private fun build(): Activity = BreatherTestSupport.activity().also { activity ->
        activity.setContentView(BreatherControls.build(activity) {})
    }
    private fun button(activity: Activity, text: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
}
