package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class UsageArchiveView(private val activity: Activity) {
    private val ui = Ui(activity)
    private val today = LocalDate.now()
    private val currentMonth = YearMonth.from(today)
    private var month = currentMonth
    private var scope: String? = null
    private var archivedMonth: List<ArchivedUsage> = emptyList()
    private val dateFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val body = column()
    private val monthLabel = ui.text("", 20f, strong = true)
    private val prev = ui.secondary("‹") { showMonth(month.minusMonths(1)) }.apply {
        contentDescription = "Previous month"
    }
    private val next = ui.secondary("›") { showMonth(month.plusMonths(1)) }.apply {
        contentDescription = "Next month"
    }

    fun build(): View = column().apply {
        addView(ui.section("Weekly assessment"))
        val weekly = card()
        addView(weekly)
        addView(ui.section("Patterns & forecast"))
        val models = card()
        addView(models)
        models.addView(ui.note("Reading saved history…"))
        addView(ui.section("Monthly history"))
        addView(card().apply {
            addView(monthLabel)
            addView(LinearLayout(activity).apply {
                addView(prev)
                addView(ui.quiet("Jump to month") { jump() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(next)
            })
            addView(body)
        })
        weekly.addView(ui.note("Reading saved history…"))
        UsageArchiveJobs.worker.execute {
            val apps = ScreenTimePreferences(activity).distractingApps
            val result = runCatching {
                UsageArchive(activity.applicationContext).use {
                    UsageInsights.analyze(today, it.range(today.minusDays(56), today), apps)
                }
            }.getOrNull()
            weekly.post {
                if (activity.isDestroyed) return@post
                weekly.removeAllViews()
                val changeResult = result?.weekly
                if (changeResult == null) {
                    weekly.addView(ui.text("Not enough comparable days", 19f, strong = true))
                    weekly.addView(ui.note("At least four matching weekdays with complete data are needed across the last two weeks."))
                } else {
                    val change = changeResult.savedPerDay
                    val title = when {
                        changeResult.stable -> "Holding steady"
                        change > 0L -> "Less time in distracting apps"
                        else -> "More time in distracting apps"
                    }
                    weekly.addView(ui.text(title, 20f, if (change < 0 && !changeResult.stable) ui.caution else ui.accent, true))
                    val max = maxOf(changeResult.oldAverage, changeResult.newAverage, 1L)
                    weekly.addView(comparisonBar("Previous week", changeResult.oldAverage, max, ui.controlOutline))
                    weekly.addView(comparisonBar("Last 7 days", changeResult.newAverage, max, ui.accent))
                    weekly.addView(ui.space(8))
                    if (!changeResult.stable) {
                        val direction = if (change > 0) "less" else "more"
                        val percent = if (changeResult.oldAverage >= ScreenTimeHistory.MINUTE) {
                            " (${(100.0 * abs(change) / changeResult.oldAverage).roundToInt()}%)"
                        } else ""
                        weekly.addView(ui.note("${usageDuration(abs(change))} $direction per day$percent"))
                        weekly.addView(ui.note("${usageDuration(abs(change) * 7)} $direction per week at this pace"))
                    }
                    weekly.addView(ui.note("Same selected apps · ${changeResult.pairs} matching weekdays").apply {
                        setPadding(0, ui.dp(6), 0, 0)
                    })
                    result.driver?.let { driver ->
                        val direction = if (driver.differenceMs > 0) "less" else "more"
                        weekly.addView(ui.note("Biggest change: ${driver.label}, ${usageDuration(abs(driver.differenceMs))} $direction per day."))
                    }
                }
                showModels(models, result)
            }
        }
        showMonth(month)
    }

    private fun showModels(host: LinearLayout, result: UsageInsights.Result?) {
        host.removeAllViews()
        if (result == null) {
            host.addView(ui.note("Insights are unavailable."))
            return
        }
        fun insight(title: String, detail: String) {
            if (host.childCount > 0) host.addView(ui.space(12))
            host.addView(ui.text(title, 16f, strong = true))
            host.addView(ui.note(detail))
        }
        val forecast = result.forecast
        if (forecast == null) {
            insight("Today’s forecast", "Available after at least 14 comparable days, including a recent complete day.")
        } else {
            insight("Today’s forecast · ${usageDuration(forecast.typicalMs)}",
                "Based on ${forecast.samples} complete days. Recent test predictions were off by ${usageDuration(forecast.errorMs)} on average.")
        }
        result.highDay?.let {
            insight("Highest-use weekday · ${it.day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())}",
                "Typical use was ${usageDuration(it.medianMs)} across ${it.samples} matching days.")
        }
        result.outlier?.let {
            val day = it.date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
            insight("Unusually high day · $day", "${usageDuration(it.actualMs)} compared with your usual ${usageDuration(it.baselineMs)}.")
        }
        if (result.outlier == null) result.baselineMs?.let { insight("Typical day", "${usageDuration(it)} across recent comparable days.") }
    }

    private fun showMonth(target: YearMonth) {
        month = minOf(target, currentMonth)
        scope = null
        val requested = month
        monthLabel.text = month.format(dateFormat)
        prev.isEnabled = false
        next.isEnabled = month < currentMonth
        body.removeAllViews()
        body.addView(ui.note("Reading saved history…"))
        UsageArchiveJobs.worker.execute {
            val result = runCatching {
                UsageArchive(activity.applicationContext).use {
                    it.firstDate() to it.range(requested.atDay(1), requested.plusMonths(1).atDay(1))
                }
            }
            body.post {
                if (activity.isDestroyed || month != requested) return@post
                val value = result.getOrNull()
                if (value == null) {
                    body.removeAllViews()
                    body.addView(ui.note("Saved history couldn’t be read."))
                } else {
                    prev.isEnabled = value.first?.let { YearMonth.from(it) < month } == true
                    archivedMonth = value.second
                    drawMonth()
                }
            }
        }
    }

    private fun jump() {
        DatePickerDialog(activity, { _, year, monthIndex, _ ->
            showMonth(YearMonth.of(year, monthIndex + 1))
        }, month.year, month.monthValue - 1, 1).apply {
            setTitle("Choose a month")
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun drawMonth() {
        body.removeAllViews()
        if (archivedMonth.isEmpty()) {
            body.addView(ui.note("No saved records for this month."))
            return
        }
        val names = archivedMonth.associate { it.pkg to it.label }
        val subset = archivedMonth.filter { scope == null || it.pkg == scope }
        val days = subset.groupBy { LocalDate.ofEpochDay(it.day).dayOfMonth }
        val totals = List(month.lengthOfMonth()) { days[it + 1]?.sumOf { row -> row.ms } }
        val partial = days.filterValues { it.any { row -> !row.complete } }.keys
        val scopeLabel = scope?.let { names[it] } ?: "All logged apps"
        body.addView(ui.secondary(scopeLabel) {
            val entries = names.entries.sortedBy { it.value.lowercase(Locale.ROOT) }
            AlertDialog.Builder(activity).setTitle("Chart apps")
                .setItems((listOf("All logged apps") + entries.map { it.value }).toTypedArray()) { _, which ->
                    scope = entries.getOrNull(which - 1)?.key
                    drawMonth()
                }.show()
        })
        body.addView(ui.space(10))
        body.addView(ui.text(usageDuration(subset.sumOf { it.ms }), 28f, ui.accent, true))
        body.addView(ui.note("${days.size} logged days · ${partial.size} partial"))
        val readout = ui.text("Tap a day for its total", 14f, ui.accent, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(MonthChart(activity, ui, totals, partial) { day ->
            val total = totals[day - 1]
            val label = month.atDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM"))
            readout.text = if (total == null) "$label · No record" else "$label · ${usageDuration(total)}" +
                if (day in partial) " · partial" else ""
        }, LinearLayout.LayoutParams(-1, ui.dp(178)))
        body.addView(readout)
        body.addView(ui.note("Muted bars are partial. Missing days have no bar. App selection can vary across the archive."))
        body.addView(ui.quiet("View daily records") {
            val items = days.entries.sortedByDescending { it.key }.map { (day, records) ->
                "${month.atDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM"))}   ${usageDuration(records.sumOf { it.ms })}" +
                    if (day in partial) " · partial" else ""
            }
            val dialog = AlertDialog.Builder(activity).setTitle(month.format(dateFormat))
                .setItems(items.toTypedArray()) { _, index ->
                    val records = days.entries.sortedByDescending { it.key }[index].value
                    AlertDialog.Builder(activity).setTitle(items[index]).setItems(
                        records.sortedByDescending { it.ms }.map { "${it.label} · ${usageDuration(it.ms)}" }.toTypedArray(), null,
                    ).setPositiveButton("Done", null).show()
                }.setPositiveButton("Done", null).create()
            dialog.setOnShowListener { ui.style(dialog) }
            dialog.show()
        })
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun comparisonBar(label: String, duration: Long, max: Long, color: Int) = column().apply {
        addView(ui.note("$label · ${usageDuration(duration)} / day").apply { setPadding(0, ui.dp(7), 0, ui.dp(4)) })
        val fraction = (duration.toDouble() / max).toFloat()
        addView(LinearLayout(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = ui.shape(ui.bg, radius = 4)
            if (fraction > 0f) addView(View(activity).apply { background = ui.shape(color, radius = 4) },
                LinearLayout.LayoutParams(0, -1, fraction))
            if (fraction < 1f) addView(View(activity), LinearLayout.LayoutParams(0, -1, 1f - fraction))
        }, LinearLayout.LayoutParams(-1, ui.dp(7)))
    }
    private fun card() = ui.card()
}

private class MonthChart(
    context: Activity,
    private val ui: Ui,
    private val totals: List<Long?>,
    private val partial: Set<Int>,
    private val onDay: (Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maxMinutes = maxOf(30, ceil((totals.filterNotNull().maxOrNull() ?: 0L) / (30.0 * ScreenTimeHistory.MINUTE)).toInt() * 30)
    private var selected = -1
    private var tapX = 0f
    private var tapY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    init {
        contentDescription = "Daily usage bar chart. Use View daily records for exact values."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = event.x
                tapY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val plotWidth = width - ui.dp(49)
                if (plotWidth > 0 && abs(event.x - tapX) <= slop && abs(event.y - tapY) <= slop) {
                    selected = ((event.x - ui.dp(46)) * totals.size / plotWidth).toInt().coerceIn(totals.indices)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (selected !in totals.indices) return false
        invalidate()
        onDay(selected + 1)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val left = ui.dp(46).toFloat()
        val right = width - ui.dp(3).toFloat()
        val top = ui.dp(15).toFloat()
        val bottom = height - ui.dp(23).toFloat()
        if (right <= left || bottom <= top) return
        val slot = (right - left) / totals.size
        paint.textSize = ui.dp(11).toFloat()
        paint.typeface = ui.medium
        for (index in 0..2) {
            val y = bottom - (bottom - top) * index / 2f
            paint.color = ui.line
            canvas.drawLine(left, y, right, y, paint)
            paint.color = ui.sub
            val minutes = maxMinutes * index / 2
            val label = if (minutes % 60 == 0 && minutes > 0) "${minutes / 60}h" else "${minutes}m"
            canvas.drawText(label, 0f, y + ui.dp(4), paint)
        }
        totals.forEachIndexed { index, ms ->
            val x = left + slot * index
            if (index == selected) {
                paint.color = ui.line
                canvas.drawRoundRect(x, top, x + slot, bottom, 3f, 3f, paint)
            }
            if (ms != null) {
                val size = ((bottom - top) * ms / (maxMinutes * ScreenTimeHistory.MINUTE)).coerceIn(ui.dp(2).toFloat(), bottom - top)
                paint.color = if (index + 1 in partial) ui.controlOutline else ui.accent
                canvas.drawRoundRect(x + slot * .12f, bottom - size, x + slot * .86f, bottom, 3f, 3f, paint)
            }
            if (index == 0 || (index + 1) % 7 == 0) {
                paint.color = ui.sub
                canvas.drawText("${index + 1}", x, height - ui.dp(5).toFloat(), paint)
            }
        }
    }
}

internal fun usageDuration(ms: Long): String {
    val minutes = ms / ScreenTimeHistory.MINUTE
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes > 0 -> "$minutes min"
        ms > 0L -> "<1 min"
        else -> "0 min"
    }
}
