package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal class ScreenTimePreferences(context: Context) {
    private val store = context.getSharedPreferences("screen_time", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = store.getBoolean("enabled", false)
        set(value) { store.edit().putBoolean("enabled", value).apply() }
    var profile: ScreenTimeProfile?
        get() {
            val name = store.getString("profile_name", null)?.takeIf { it.isNotBlank() } ?: return null
            val date = runCatching { LocalDate.parse(store.getString("birth_date", null)) }.getOrNull() ?: return null
            return ScreenTimeProfile(name, date)
        }
        set(value) {
            store.edit().remove("age").putString("profile_name", value?.name)
                .putString("birth_date", value?.birthDate?.toString()).apply()
        }
    var distractingApps: Set<String>
        get() = store.getStringSet("distracting_apps", emptySet()).orEmpty().toSet()
        set(value) { store.edit().putStringSet("distracting_apps", value.toSet()).apply() }
}

internal object ScreenTimePage {
    fun build(activity: Activity, reload: () -> Unit, openUsageAccess: () -> Unit): View {
        val ui = Ui(activity)
        val panel = column(activity)
        val settings = ScreenTimePreferences(activity)
        val enabled = settings.enabled
        if (settings.profile == null) {
            panel.addView(ui.card().apply {
                addView(ui.text("Make this analysis yours", 25f, strong = true))
                addView(ui.note("Add a name for your dashboard and a date of birth for your lifetime projection."))
                addView(ui.space(12))
                addView(ui.primary("Set up your profile") {
                    editScreenTimeProfile(activity, settings, reload)
                })
            })
            panel.addView(UsageArchiveView(activity).build())
            return panel
        }
        if (!enabled || !PauseForegroundMonitor.hasUsageAccess(activity)) {
            panel.addView(ui.card().apply {
                addView(ui.text("Read your screen time", 20f, strong = true))
                addView(ui.note("Detach uses Android’s Usage Access to save daily totals for the apps you select. Your archive stays on this phone unless you set up a backup.").apply {
                    setPadding(0, ui.dp(6), 0, ui.dp(12))
                })
                if (enabled) addView(ui.note("In Usage Access, select Detach and allow access.").apply {
                    setPadding(0, 0, 0, ui.dp(12))
                })
                addView(ui.primary(if (enabled) "Open Usage Access" else "Enable screen time") {
                    settings.enabled = true
                    UsageArchiveJobs.schedule(activity)
                    if (PauseForegroundMonitor.hasUsageAccess(activity)) reload() else openUsageAccess()
                })
            })
            panel.addView(UsageArchiveView(activity).build())
            panel.addView(ui.quiet("Edit analysis profile") { editScreenTimeProfile(activity, settings, reload) })
            return panel
        }

        panel.addView(ui.note("Analyzing usage on this phone…").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        val app = activity.applicationContext
        val selectedApps = settings.distractingApps
        val task = UsageArchiveJobs.worker.submit {
            val data = runCatching { capture(app, selectedApps) }.getOrNull()
            panel.post {
                if (!panel.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return@post
                if (!PauseForegroundMonitor.hasUsageAccess(activity)) {
                    reload()
                    return@post
                }
                panel.removeAllViews()
                if (data == null) {
                    panel.addView(ui.note("Android’s usage history is unavailable. Unlock the phone and try again."))
                    panel.addView(UsageArchiveView(activity).build())
                } else {
                    ReportView(activity, ui, data, settings, reload).render(panel)
                }
                panel.addView(ui.space(12))
                panel.addView(ui.quiet("Refresh report", reload))
                panel.addView(ui.quiet("Turn off screen time report") {
                    settings.enabled = false
                    UsageArchiveJobs.schedule(activity)
                    reload()
                })
            }
        }
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { task.cancel(true) }
        })
        return panel
    }

    internal data class Data(val report: ScreenTimeHistory.Report, val labels: Map<String, String>)

    fun capture(context: Context, selectedApps: Set<String>): Data? {
        val now = System.currentTimeMillis()
        val history = ScreenTimeHistory(now, ZoneId.systemDefault(), BreatherUsageHistory.snapshot(context, now), selectedApps)
        if (selectedApps.isEmpty()) return Data(history.finish(), emptyMap())
        val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(history.queryStart, now) ?: return null
        val event = UsageEvents.Event()
        while (events.hasNextEvent() && !Thread.currentThread().isInterrupted && events.getNextEvent(event)) {
            val kind = toKind(event.eventType) ?: continue
            history.event(event.timeStamp, kind, event.packageName, event.className)
        }
        if (Thread.currentThread().isInterrupted) return null
        val report = history.finish()
        val manager = context.packageManager
        val installed = PaymentAppPicker.appsFromPackages(
            manager, selectedApps, blocking = true,
        ).associate {
            val pkg = it.info.packageName
            @Suppress("DEPRECATION")
            val installedAt = runCatching { manager.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(now)
            pkg to (it.name to installedAt)
        }
        if (Thread.currentThread().isInterrupted) return null
        UsageArchive(context).use { it.capture(report, selectedApps, installed) }
        return Data(report, installed.mapValues { it.value.first })
    }

    @Suppress("DEPRECATION") // Android 9 uses the same event values under their legacy names.
    private fun toKind(type: Int): ScreenTimeHistory.Kind? = when (type) {
        UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenTimeHistory.Kind.SCREEN_ON
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenTimeHistory.Kind.SCREEN_OFF
        UsageEvents.Event.KEYGUARD_SHOWN -> ScreenTimeHistory.Kind.LOCK
        UsageEvents.Event.KEYGUARD_HIDDEN -> ScreenTimeHistory.Kind.UNLOCK
        UsageEvents.Event.MOVE_TO_FOREGROUND -> ScreenTimeHistory.Kind.RESUME
        UsageEvents.Event.MOVE_TO_BACKGROUND -> ScreenTimeHistory.Kind.PAUSE
        else -> if (Build.VERSION.SDK_INT >= 29) when (type) {
            UsageEvents.Event.ACTIVITY_STOPPED -> ScreenTimeHistory.Kind.PAUSE
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> ScreenTimeHistory.Kind.RESTART
            else -> null
        } else null
    }

    private class ReportView(
        private val activity: Activity,
        private val ui: Ui,
        private val data: Data,
        private val preferences: ScreenTimePreferences,
        private val reload: () -> Unit,
    ) {
        private val report = data.report
        private val today = report.today
        private val profile = preferences.profile
        private val age = profile?.ageOn(today.date)
        private val remainingYears = profile?.yearsUntil70(today.date) ?: 0.0
        private val distractingApps = preferences.distractingApps
        private val days = report.eligibleDays(distractingApps)
        private val youtubeSelected = Targets.YOUTUBE in distractingApps
        private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
        private val dayParts = listOf("Midnight–6 am", "6 am–noon", "Noon–6 pm", "6 pm–midnight")
        private var recent = false
        private var page = 0

        fun render(panel: LinearLayout) {
            if (distractingApps.isEmpty()) {
                panel.addView(projection())
                panel.addView(UsageArchiveView(activity).build())
                return
            }
            val tabs = LinearLayout(activity).apply {
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                background = ui.shape(ui.surface, ui.line, 18)
            }
            val body = column(activity)
            val pages = arrayOf<() -> View>(
                { column(activity).apply { renderOverview(this) } },
                { column(activity).apply { addView(UsageArchiveView(activity).build()); renderPatterns(this) } },
                { column(activity).apply {
                    addView(ui.section("Selected apps"))
                    addView(ui.secondary("Edit distracting apps (${distractingApps.size})") { chooseApps() })
                    addView(ui.space(10))
                    addView(appActivity())
                    addView(method())
                } },
            )
            val screens = arrayOfNulls<View>(pages.size)
            fun switchTo(index: Int) {
                page = index
                tabs.removeAllViews()
                listOf("Overview", "Trends", "Apps").forEachIndexed { i, label ->
                    val select = { if (page != i || body.childCount == 0) switchTo(i) }
                    val button = if (i == page) ui.primary(label, select) else ui.quiet(label, select)
                    button.isSelected = i == page
                    button.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8))
                    tabs.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                body.removeAllViews()
                body.addView(screens[index] ?: pages[index]().also { screens[index] = it })
            }
            panel.addView(tabs)
            panel.addView(ui.space(12))
            panel.addView(body)
            switchTo(page)
        }

        private fun renderOverview(panel: LinearLayout) {
            val todayMs = report.distractingMs(today, distractingApps)
            panel.addView(ui.hero(column(activity).apply {
                setPadding(ui.dp(18), ui.dp(17), ui.dp(18), ui.dp(18))
                addView(ui.badge(R.drawable.ic_screen_time, 38, ui.onAccent, ui.accent))
                addView(ui.space(10))
                addView(ui.display(profile?.let { "Hey, ${it.name}" } ?: "Today", 25f).apply {
                    isAccessibilityHeading = true
                })
                addView(ui.note("Distraction time today").apply { setPadding(0, ui.dp(8), 0, 0) })
                addView(ui.display(if (today.measuredMs == 0L) "No usage data" else duration(todayMs), 44f, ui.accent).apply {
                    fontFeatureSettings = "tnum"
                })
                addView(ui.note("${distractingApps.size} selected · Updated ${DateFormat.getTimeFormat(activity).format(Date(report.asOf))}"))
                if (youtubeSelected) addView(ui.note("YouTube counts only during recorded Breathers.").apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
                if (today.measuredMs in 1 until (report.asOf - today.start)) {
                    addView(ui.note("Android only reported part of today.").apply { setPadding(0, ui.dp(6), 0, 0) })
                }
            }))
            panel.addView(ui.space(10))
            panel.addView(recentUsage())
            panel.addView(ui.section("Distraction estimate"))
            panel.addView(projection())
        }

        private fun recentUsage() = ui.card(14).apply {
            addView(ui.text("Last 7 days", 17f, strong = true).apply { isAccessibilityHeading = true })
            val sample = report.days.takeLast(7)
            val values = sample.map { day ->
                if (day.measuredMs > 0 && (day == today || hasYoutubeCoverage(day))) report.distractingMs(day, distractingApps) else null
            }
            val peak = values.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
            val readout = ui.note("Tap a day to see its total.").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            val chart = LinearLayout(activity)
            val shortDay = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
            addView(chart)
            addView(readout)
            sample.forEachIndexed { index, day ->
                val time = values[index]
                val height = if (time == null) 0 else (ui.dp(44) * (time.toDouble() / peak)).roundToInt().coerceAtLeast(ui.dp(3))
                val state = if (day == today || !day.complete) " · partial" else ""
                val summary = day.date.format(dayFormat) + if (time == null) " · No record" else " · ${duration(time)}$state"
                chart.addView(column(activity).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = ui.dp(72)
                    setPadding(0, ui.dp(4), 0, ui.dp(4))
                    background = ui.ripple()
                    contentDescription = summary
                    isFocusable = true
                    addView(android.widget.FrameLayout(activity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        if (time != null) addView(View(activity).apply {
                            background = ui.shape(if (day.complete) ui.accent else ui.controlOutline, radius = 5)
                        }, android.widget.FrameLayout.LayoutParams(ui.dp(13), height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)))
                    addView(ui.text(day.date.format(shortDay), 12f, ui.sub, strong = true).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        gravity = Gravity.CENTER
                    })
                    setOnClickListener {
                        for (i in 0 until chart.childCount) {
                            val child = chart.getChildAt(i)
                            child.isSelected = i == index
                            child.background = ui.ripple(if (i == index) ui.accentSurface else android.graphics.Color.TRANSPARENT)
                        }
                        readout.text = summary
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        private fun renderPatterns(panel: LinearLayout) {
            panel.addView(ui.section("Time of day"))
            val sample = days.ifEmpty { listOf(today) }
            val partTotals = dayParts.indices.map { index ->
                sample.sumOf { day -> distractingApps.sumOf { day.appParts[it]?.get(index) ?: 0L } }
            }
            val active = partTotals.sum()
            if (active > 0L) {
                panel.addView(ui.note(if (days.isEmpty()) "Share of today’s selected app time" else "Share of selected app time across complete days"))
                dayParts.forEachIndexed { index, label ->
                    val fraction = partTotals[index].toDouble() / active
                    panel.addView(bar(label, percent(fraction), fraction))
                }
            } else {
                panel.addView(ui.note("No measured screen activity to break down yet."))
            }
            panel.addView(method())
        }

        private fun method() = column(activity).apply {
            val method = ui.note(
                "This report counts foreground time while the phone was unlocked, for your selected apps only. " +
                    "Selected Instagram use counts in full. YouTube counts only while a recorded Breather was active. " +
                    "This is an estimate of Shorts use and can include long videos watched during a Breather. " +
                    "Visits are approximate. The lifetime estimate uses complete days and your remaining years to age 70.",
            ).apply { visibility = View.GONE }
            val disclosure = ui.disclosure("How this is measured", false) {} as ViewGroup
            disclosure.setOnClickListener {
                val open = method.visibility == View.GONE
                method.visibility = if (open) View.VISIBLE else View.GONE
                ui.updateDisclosure(disclosure, open)
            }
            addView(disclosure)
            addView(method)
        }

        private fun hasYoutubeCoverage(day: ScreenTimeHistory.Day) =
            !youtubeSelected || report.youtubeTrackedSince?.let { day.start >= it } == true

        private fun projection() = ui.card().apply {
            if (age == null) {
                addView(ui.text("Personalize your estimate", 20f, strong = true))
                addView(ui.note("Add your name and date of birth to calculate your projection."))
                addView(ui.space(12))
                addView(ui.primary("Set up your profile") { editProfile() })
                return@apply
            }
            if (distractingApps.isEmpty()) {
                addView(ui.text("Which apps distract you?", 20f, strong = true))
                addView(ui.note("Leave study, work, and other useful apps unchecked."))
                addView(ui.space(12))
                addView(ui.primary("Choose distracting apps") { chooseApps() })
                addView(ui.quiet("Edit analysis profile") { editProfile() })
                return@apply
            }
            val average = report.distractingAverageMs(distractingApps)
            when {
                remainingYears == 0.0 -> {
                    addView(ui.text("Age 70 reached", 20f, strong = true))
                    addView(ui.note("No years remain in this projection. Your distraction metrics are still available."))
                }
                average == null -> {
                    addView(ui.text("More history needed", 20f, strong = true))
                    addView(ui.note(if (youtubeSelected) "The estimate needs a complete day after YouTube Breather tracking began with this update."
                        else "The estimate needs at least one complete day of Android usage history."))
                }
                else -> {
                    val years = report.dayShare(average) * remainingYears
                    addView(ui.text(yearValue(years), 29f, ui.accent, true))
                    addView(ui.note("In selected apps by age 70 at this pace. You’re $age now."))
                }
            }
            addView(ui.space(8))
            if (average != null) {
                addView(metric("Daily distraction average", duration(average.toLong())))
                addView(metric("Days per year at this pace", decimal(report.dayShare(average) * ScreenTimeHistory.DAYS_PER_YEAR)))
                addView(ui.note("Average based on ${days.size} complete day${if (days.size == 1) "" else "s"}.").apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            addView(ui.space(8))
            addView(ui.quiet("Edit analysis profile") { editProfile() })
            if (remainingYears > 0 && average != null && average >= ScreenTimeHistory.MINUTE) {
                val simulator = savingsSimulator(average).apply { visibility = View.GONE }
                val button = ui.disclosure("Explore a smaller daily limit", false) {} as ViewGroup
                button.setOnClickListener {
                    val expanded = simulator.visibility == View.GONE
                    simulator.visibility = if (expanded) View.VISIBLE else View.GONE
                    ui.updateDisclosure(button, expanded)
                }
                addView(button)
                addView(simulator)
            }
        }

        private fun chooseApps() = PaymentAppPicker.showDistractingApps(activity, reload)

        private fun editProfile() = editScreenTimeProfile(activity, preferences, reload)

        private fun savingsSimulator(average: Double) = column(activity).apply {
            val output = ui.text("", 15f, ui.accent, true)
            fun update(minutes: Int) {
                val perDay = minutes * ScreenTimeHistory.MINUTE
                val saved = report.dayShare(perDay.toDouble()) * remainingYears
                output.text = "$minutes min less per day · ${duration(perDay * 7)} back per week\n${yearValue(saved)} back by age 70"
            }
            val slider = SeekBar(activity).apply {
                contentDescription = "Minutes to cut from each day"
                max = minOf(120, (average / ScreenTimeHistory.MINUTE).toInt())
                progress = minOf(max, 15)
                progressTintList = ui.colors(ui.accent)
                thumbTintList = ui.colors(ui.accent)
                minimumHeight = ui.dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = update(progress)
                })
                update(progress)
            }
            addView(ui.note("Try reducing your daily average. This is an estimate and won’t change any settings."))
            addView(slider)
            addView(output)
        }

        private fun appActivity() = column(activity).apply {
            val tabs = LinearLayout(activity)
            val items = column(activity)
            fun renderApps() {
                tabs.removeAllViews()
                listOf("Today", "Complete days").forEachIndexed { index, label ->
                    val selected = (index == 1) == recent
                    val change = { recent = index == 1; renderApps() }
                    val button = if (selected) ui.primary(label, change) else ui.secondary(label, change)
                    button.isSelected = selected
                    button.isEnabled = index == 0 || days.isNotEmpty()
                    tabs.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 1) marginStart = ui.dp(8)
                    })
                }
                items.removeAllViews()
                val sample = if (recent) days else listOf(today)
                val total = sample.sumOf { report.distractingMs(it, distractingApps) }
                val appTimes = report.apps(sample)
                val sorted = distractingApps.sortedWith(compareByDescending<String> { appTimes[it]?.ms ?: 0L }.thenBy { appName(it) })
                val extra = column(activity).apply { visibility = View.GONE }
                sorted.forEachIndexed { index, pkg ->
                    val time = appTimes[pkg]?.ms ?: 0L
                    val share = if (total > 0L) time.toDouble() / total else 0.0
                    val name = appName(pkg)
                    val value = if (!recent && today.measuredMs == 0L) "Unavailable" else duration(time)
                    val row = appInsightRow(pkg, name, value, share)
                    (if (index < 5) items else extra).addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = ui.dp(6)
                    })
                }
                if (sorted.size > 5) {
                    val toggle = ui.disclosure("Show all ${sorted.size} apps", false) {} as ViewGroup
                    toggle.setOnClickListener {
                        val expanded = extra.visibility == View.GONE
                        extra.visibility = if (expanded) View.VISIBLE else View.GONE
                        ui.updateDisclosure(toggle, expanded)
                    }
                    items.addView(toggle)
                    items.addView(extra)
                }
            }
            addView(tabs)
            addView(items)
            renderApps()
        }

        private fun appInsightRow(pkg: String, name: String, value: String, share: Double) = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(68)
            setPaddingRelative(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
            background = ui.ripple(ui.surface, ui.line, radius = 16)
            isFocusable = true
            val scope = if (pkg == Targets.YOUTUBE) "Breather only" else "${percent(share)} of selected time"
            contentDescription = "$name · $value · $scope"
            val icon = runCatching { activity.packageManager.getApplicationIcon(pkg) }.getOrNull()
            if (icon == null) addView(ui.badge(R.drawable.ic_apps, 36))
            else addView(android.widget.ImageView(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageDrawable(icon)
            }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
            addView(column(activity).apply {
                setPaddingRelative(ui.dp(10), 0, ui.dp(10), 0)
                addView(ui.text(name, 15f, strong = true))
                addView(ui.note(scope))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text(value, 14f, ui.accent, true).apply { gravity = Gravity.END })
            setOnClickListener { showApp(pkg, name, recent) }
        }

        private fun appName(pkg: String): String {
            val label = data.labels[pkg] ?: return if (pkg == Targets.YOUTUBE) "YouTube" else pkg
            return if (data.labels.values.count { it == label } > 1) "$label ($pkg)" else label
        }

        private fun showApp(pkg: String, name: String, showRecent: Boolean) {
            val sample = if (showRecent) days else listOf(today)
            val usage = report.apps(sample)[pkg] ?: ScreenTimeHistory.Usage(0, 0)
            val total = sample.sumOf { report.distractingMs(it, distractingApps) }
            val content = column(activity).apply {
                setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(12))
                addView(ui.note(if (showRecent) "Across ${sample.size} complete days" else "Today so far"))
                if (!showRecent && today.measuredMs == 0L) {
                    addView(ui.note("No measured usage data for today."))
                } else {
                    addView(metric(if (pkg == Targets.YOUTUBE) "Time during Breather" else "Foreground time", duration(usage.ms)))
                    addView(metric("Share of selected app time", percent(if (total > 0) usage.ms.toDouble() / total else 0.0)))
                    addView(metric("Approximate counted visits", usage.entries.toString()))
                }
                if (days.isNotEmpty()) {
                    val daily = (report.apps(days)[pkg]?.ms ?: 0L).toDouble() / days.size
                    addView(metric("Daily average", duration(daily.toLong())))
                    if (pkg in distractingApps && remainingYears > 0) {
                        addView(metric("At this pace by age 70", yearValue(report.dayShare(daily) * remainingYears)))
                    }
                }
                addView(ui.section("By day"))
                report.days.forEach { day ->
                    if (day.measuredMs > 0L && (day == today || hasYoutubeCoverage(day))) {
                        val label = if (day == today) "Today" else day.date.format(dayFormat) + if (!day.complete) " · partial" else ""
                        addView(metric(label, duration(day.apps[pkg]?.ms ?: 0L)))
                    }
                }
            }
            val dialog = AlertDialog.Builder(activity).setTitle(name)
                .setView(ScrollView(activity).apply { addView(content) })
                .setPositiveButton("Done", null).create()
            dialog.setOnShowListener { ui.style(dialog) }
            dialog.show()
        }

        private fun metric(label: String, value: String) = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(5), 0, ui.dp(5))
            addView(ui.note(label), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
            addView(ui.text(value, 14f, strong = true).apply { gravity = Gravity.END })
        }

        private fun bar(label: String, value: String, fraction: Double) = column(activity).apply {
            addView(metric(label, value))
            addView(LinearLayout(activity).apply {
                background = ui.shape(ui.surface, radius = 3)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val fill = fraction.toFloat().coerceIn(0f, 1f)
                if (fill > 0f) addView(View(activity).apply { background = ui.shape(ui.accent, radius = 3) }, LinearLayout.LayoutParams(0, -1, fill))
                if (fill < 1f) addView(View(activity), LinearLayout.LayoutParams(0, -1, 1f - fill))
            }, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { bottomMargin = ui.dp(4) })
        }
    }

    private fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun percent(fraction: Double) = "${(fraction * 100).roundToInt()}%"
    private fun decimal(value: Double) = "%,.1f".format(value)
    private fun yearValue(value: Double) = if (value in 0.0..0.049999 && value > 0) "<0.1 years" else "${decimal(value)} years"
    private fun duration(ms: Long) = usageDuration(ms)
}
