package io.github.detach

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FocusWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("focus_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `supported sizes keep time text and actions inside their resized widget`() {
        val sizes = listOf(56 to 56, 56 to 160, 111 to 56, 112 to 56, 223 to 64, 125 to 120,
            126 to 110, 152 to 137, 325 to 180)
        for (fontScale in listOf(1f, 1.7f)) {
            val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val themed = context.createConfigurationContext(config)
            val ui = Ui(themed)
            for ((dpWidth, dpHeight) in sizes) {
                val layout = FocusWidgetProvider.layoutFor(dpWidth, dpHeight)
                val widget = FocusWidgetProvider.views(themed, 1, layout, "23:59:59").apply(themed, null)
                val width = ui.dp(dpWidth)
                val height = ui.dp(dpHeight)
                widget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                widget.layout(0, 0, width, height)
                val root = widget as ViewGroup
                val labels = listOfNotNull(root.findViewById<TextView>(R.id.focus_widget_label),
                    root.findViewById(R.id.focus_widget_time), root.findViewById(R.id.focus_widget_start))
                labels.forEach { label ->
                    val area = android.graphics.Rect(0, 0, label.width, label.height)
                    root.offsetDescendantRectToMyCoords(label, area)
                    assertTrue("$layout at $dpWidth × $dpHeight: $area", area.left >= 0 && area.top >= 0 && area.right <= width && area.bottom <= height)
                    assertTrue("Label has usable space", label.width > 0 && label.height > 0)
                    assertEquals(0, label.layout.getEllipsisCount(0))
                }
                if (layout != FocusWidgetProvider.Layout.COMPACT) {
                    assertEquals("23:59:59", root.findViewById<TextView>(R.id.focus_widget_time).text.toString())
                }
                assertEquals(layout == FocusWidgetProvider.Layout.PANEL, root.findViewById<View>(R.id.focus_widget_start) != null)
            }
        }
    }

    @Test fun `editing and quick start have distinct internal intents`() {
        val layout = FocusWidgetProvider.Layout.PANEL
        val widget = FocusWidgetProvider.views(context, 17, layout, "00:02:03").apply(context, null)
        val time = widget.findViewById<View>(R.id.focus_widget_time)
        val start = widget.findViewById<View>(R.id.focus_widget_start)
        assertEquals("Change Focus duration, 00:02:03", time.contentDescription)
        assertTrue(time.performClick())
        val chooseIntent = shadowOf(context).nextStartedActivity
        assertEquals(FocusWidgetActivity.CHOOSE_DURATION, chooseIntent?.action)
        assertTrue(start.performClick())
        val startIntent = shadowOf(context).nextStartedActivity
        assertEquals(FocusWidgetActivity.START_SAVED, startIntent?.action)
        assertEquals(FocusWidgetActivity::class.java.name, startIntent?.component?.className)
    }

    @Test fun `home screen picker loads the last duration without starting Focus`() {
        FocusSession.startSeconds(context, 3_723L)
        FocusSession.end(context)
        val intent = Intent(context, FocusWidgetActivity::class.java).setAction(FocusWidgetActivity.CHOOSE_DURATION)
        val activity = Robolectric.buildActivity(FocusWidgetActivity::class.java, intent).setup().get()
        val wheels = flatten(activity.window.decorView).filterIsInstance<NumberPicker>()
        assertEquals(listOf("Hours", "Minutes", "Seconds"), wheels.map { it.contentDescription })
        assertEquals(listOf(1, 2, 3), wheels.map { it.value })
        assertTrue(flatten(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "01:02:03" })
        assertFalse(FocusSession.active(context))
    }

    @Test fun `an all zero draft cannot be started and quick start requires Accessibility protection`() {
        val intent = Intent(context, FocusWidgetActivity::class.java).setAction(FocusWidgetActivity.CHOOSE_DURATION)
        val state = Bundle().apply { putLong("draft", 0L) }
        val activity = Robolectric.buildActivity(FocusWidgetActivity::class.java, intent).create(state).get()
        val start = flatten(activity.window.decorView).filterIsInstance<Button>().firstOrNull { it.text == "Start Focus" }
        assertNotNull(start)
        assertFalse(start!!.isEnabled)
        val quickStart = Intent(context, FocusWidgetActivity::class.java).setAction(FocusWidgetActivity.START_SAVED)
        val quick = Robolectric.buildActivity(FocusWidgetActivity::class.java, quickStart).create().get()
        assertTrue(quick.isFinishing)
        assertFalse(FocusSession.active(context))
    }

    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) }
    } else emptyList()
}
