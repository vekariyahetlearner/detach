package io.github.detach

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PaymentWidgetProviderTest {
    private val widget = PaymentWidgetProvider()

    @Test fun `one-row widgets follow the agreed per-span capacities`() {
        assertShape(80, 90, PaymentWidgetProvider.Kind.STRIP, 1, 1, 1)
        assertShape(180, 90, PaymentWidgetProvider.Kind.STRIP, 3, 1, 3)
        assertShape(280, 90, PaymentWidgetProvider.Kind.STRIP, 4, 1, 4)
        assertShape(380, 90, PaymentWidgetProvider.Kind.STRIP, 5, 1, 5)
        assertShape(500, 90, PaymentWidgetProvider.Kind.STRIP, 5, 1, 5)
    }

    @Test fun `two-row widgets use exact agreed folder grids`() {
        assertShape(160, 150, PaymentWidgetProvider.Kind.GRID_3, 3, 3, 9)
        assertShape(240, 150, PaymentWidgetProvider.Kind.GRID_4, 4, 2, 8)
        assertShape(320, 150, PaymentWidgetProvider.Kind.GRID_5, 5, 2, 10)
    }

    @Test fun `all nine payment cells and their labels fit the compact grid`() {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val labels = intArrayOf(
            R.id.payment_label_0, R.id.payment_label_1, R.id.payment_label_2,
            R.id.payment_label_3, R.id.payment_label_4, R.id.payment_label_5,
            R.id.payment_label_6, R.id.payment_label_7, R.id.payment_label_8,
        )
        val remote = RemoteViews(context.packageName, R.layout.widget_payment_double)
        labels.forEach { id -> remote.setTextViewText(id, "Bank App") }
        val grid = remote.apply(context, null) as ViewGroup
        val width = (160 * density).toInt()
        for (heightDp in listOf(130, 150)) {
            val height = (heightDp * density).toInt()
            grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            grid.layout(0, 0, width, height)
            labels.forEach { id ->
                val label = grid.findViewById<View>(id)
                val cell = label.parent as View
                val bounds = Rect(0, 0, cell.width, cell.height)
                grid.offsetDescendantRectToMyCoords(cell, bounds)
                assertTrue("Cell $id must fit at ${heightDp}dp: $bounds", bounds.top >= 0 && bounds.bottom <= height)
                assertTrue("Label $id must fit its cell", label.height > 0 && label.bottom <= cell.height)
            }
        }
    }

    private fun assertShape(width: Int, height: Int, kind: PaymentWidgetProvider.Kind, columns: Int, rows: Int, capacity: Int) {
        val shape = widget.shapeFor(width, height)
        assertEquals(kind, shape.kind)
        assertEquals(columns, shape.columns)
        assertEquals(rows, shape.rows)
        assertEquals(capacity, shape.capacity)
    }
}
