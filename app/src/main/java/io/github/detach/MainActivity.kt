package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings as SysSettings

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private enum class Page(val label: String, val description: String, val icon: Int) {
        SESSIONS("Sessions", "Start Focus or take a breather.", R.drawable.ic_sessions),
        LIMITS("Limits", "Keep the parts of your apps you came for.", R.drawable.ic_limits),
        SCREEN_TIME("Screen time", "Where your attention goes.", R.drawable.ic_screen_time),
        PAYMENTS("Payments", "Pause protection when you need to pay.", R.drawable.ic_payments),
        SETTINGS("Settings", "Permissions, protection, and backups.", R.drawable.ic_settings),
    }

    private lateinit var settings: Settings
    private lateinit var lock: Lock
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var navigation: LinearLayout
    private val ui by lazy { Ui(this) }
    private var unlocked = false
    private var silent = false
    private var rendered = false
    private var page = Page.SESSIONS
    private val pageScroll = mutableMapOf<Page, Int>()
    private var pageChanged = true
    private var revealDiagnostics = false
    private var lastStatus: String? = null
    private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private val expanded = mutableSetOf<String>()
    private val protectedDialogs = mutableSetOf<AlertDialog>()
    private var authorizationVisit = 0L
    private var visitActive = false
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        lock = Lock(this)
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded") ?: emptyList())
        page = Page.entries.find { it.name == savedInstanceState?.getString("page") } ?: Page.SESSIONS
        Page.entries.forEach { pageScroll[it] = savedInstanceState?.getInt("scroll-${it.name}") ?: 0 }
        revealDiagnostics = savedInstanceState?.getBoolean("revealDiagnostics") ?: (intent?.action == Health.ACTION_DIAGNOSTICS)
        if (revealDiagnostics) {
            page = Page.SETTINGS
            expanded.add("diagnostic-info")
        }
        ui.systemBars(window)
        window.decorView.setBackgroundColor(ui.bg)
        root = column().apply {
            val side = maxOf(ui.dp(20), ui.dp((resources.configuration.screenWidthDp - 640) / 2))
            setPadding(side, ui.dp(18), side, ui.dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isSaveEnabled = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        navigation = LinearLayout(this).apply {
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            background = ui.shape(ui.surface, ui.line, 22)
        }
        val shell = column().apply {
            setBackgroundColor(ui.bg)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val side = maxOf(ui.dp(10), ui.dp((resources.configuration.screenWidthDp - 660) / 2))
            addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = side
                marginEnd = side
                topMargin = ui.dp(5)
                bottomMargin = ui.dp(6)
            })
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(shell)
        buildNavigation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("expanded", ArrayList(expanded))
        rememberScroll()
        outState.putString("page", page.name)
        pageScroll.forEach { (key, offset) -> outState.putInt("scroll-${key.name}", offset) }
        outState.putBoolean("revealDiagnostics", revealDiagnostics)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Health.ACTION_DIAGNOSTICS) {
            expanded.add("diagnostic-info")
            revealDiagnostics = true
            navigate(Page.SETTINGS)
        }
    }

    @Deprecated("Used by Android versions before predictive Back")
    override fun onBackPressed() {
        if (page == Page.SESSIONS) super.onBackPressed() else navigate(Page.SESSIONS)
    }

    private fun rememberScroll() {
        if (!pageChanged && pendingPreDraw == null) pageScroll[page] = scroll.scrollY
    }

    private fun navigate(destination: Page) {
        if (destination != page) {
            rememberScroll()
            page = destination
            pageChanged = true
            updateNavigation()
        } else if (!revealDiagnostics) return
        render()
    }

    private fun buildNavigation() {
        Page.entries.forEach { destination ->
            val item = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(55)
                setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
                isFocusable = true
                contentDescription = destination.label
                tag = "nav-${destination.name.lowercase(java.util.Locale.ROOT)}"
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                }
                addView(android.widget.FrameLayout(this@MainActivity).apply {
                    addView(ui.icon(destination.icon, 21), android.widget.FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(26)))
                addView(ui.text(destination.label, 11f, strong = true).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnClickListener { navigate(destination) }
            }
            navigation.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = ui.dp(2)
                marginEnd = ui.dp(2)
            })
        }
        updateNavigation()
    }

    private fun updateNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = if (page == Page.SESSIONS) null else android.window.OnBackInvokedCallback { navigate(Page.SESSIONS) }
            backCallback?.let {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        Page.entries.forEachIndexed { index, destination ->
            val item = navigation.getChildAt(index) as LinearLayout
            val selected = destination == page
            item.isSelected = selected
            item.background = ui.ripple(if (selected) ui.accentSurface else android.graphics.Color.TRANSPARENT, radius = 17)
            val indicator = item.getChildAt(0) as ViewGroup
            (indicator.getChildAt(0) as android.widget.ImageView).imageTintList =
                ui.colors(if (selected) ui.accent else ui.sub)
            (item.getChildAt(1) as TextView).setTextColor(if (selected) ui.accent else ui.sub)
        }
    }

    private val pauseTick = Runnable {
        Pause.resumeIfDue(this)
        render()
    }

    private val connectionTick = Runnable { render() }
    private val breatherTick = Runnable { DetachService.refreshSettings(); render() }
    private val focusExpiry = Runnable { render() }

    override fun onResume() {
        super.onResume()
        visitActive = true
        UsageArchiveJobs.schedule(this)
        Pause.resumeIfDue(this)
        Pause.refreshNotification(this)
        render()
    }

    override fun onPause() {
        root.removeCallbacks(pauseTick)
        root.removeCallbacks(connectionTick)
        root.removeCallbacks(breatherTick)
        root.removeCallbacks(focusExpiry)
        super.onPause()
    }

    override fun onStop() {
        visitActive = false
        authorizationVisit++
        protectedDialogs.toList().forEach { it.dismiss() }
        unlocked = false
        super.onStop()
        DetachService.refreshSettings()
        Auth.cancel()
    }

    private fun render() {
        pendingPreDraw?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        val samePage = rendered && !pageChanged && pendingPreDraw == null
        val previousY = if (samePage) scroll.scrollY else pageScroll[page] ?: 0
        val anchor = (0 until (if (samePage) root.childCount else 0)).map { root.getChildAt(it) }
            .firstOrNull { it.bottom > previousY && it.tag is String }
        val anchorTag = anchor?.tag
        val anchorOffset = anchor?.let { previousY - it.top } ?: 0
        android.transition.TransitionManager.endTransitions(root)
        root.removeCallbacks(pauseTick)
        root.removeCallbacks(connectionTick)
        root.removeCallbacks(breatherTick)
        root.removeCallbacks(focusExpiry)
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.badge(R.drawable.ic_mark, 32, ui.onAccent, ui.accent))
            addView(ui.text("Detach", 16f, ui.fg, strong = true).apply { setPaddingRelative(ui.dp(9), 0, 0, 0) })
        })
        root.addView(ui.display(page.label, 38f).apply {
            isAccessibilityHeading = true
            setPadding(0, ui.dp(18), 0, 0)
        })
        root.addView(ui.note(page.description).apply { setPadding(0, ui.dp(4), 0, ui.dp(16)) })

        val paused = Pause.isPending(this)
        val currentStatus = when {
            paused -> if (Pause.isExpired(this)) "reminder-due" else "paused"
            serviceEnabled() && DetachService.isRunning -> "active"
            serviceEnabled() -> "connecting"
            else -> "off"
        }
        if (page == Page.SESSIONS || paused && page == Page.PAYMENTS || currentStatus != "active" && page == Page.LIMITS) root.addView(status(paused).apply {
            tag = "status"
            if (rendered && currentStatus != lastStatus && !revealDiagnostics) Motion.enter(this)
        })
        lastStatus = currentStatus
        if (paused && !Pause.isExpired(this)) {
            root.postDelayed(pauseTick, (Pause.until(this) - System.currentTimeMillis()).coerceAtLeast(0) + 1000)
        }
        if (serviceEnabled() && !DetachService.isRunning) root.postDelayed(connectionTick, 1000)
        val breatherRemaining = Breather.remainingMs(this)
        val budget = BreatherBudget.status(this)
        val cooldownRemaining = budget?.cooldownRemainingMs ?: 0L
        val resetRemaining = budget?.let { (it.periodEnd - System.currentTimeMillis()).coerceAtLeast(1L) } ?: 0L
        val nextBreatherChange = listOf(breatherRemaining, cooldownRemaining, resetRemaining).filter { it > 0 }.minOrNull()
        if (nextBreatherChange != null) root.postDelayed(breatherTick, nextBreatherChange + 1L)
        val focusRemaining = FocusSession.remainingMs(this)
        if (focusRemaining > 0L) root.postDelayed(focusExpiry, focusRemaining)
        when (page) {
            Page.SESSIONS -> {
                root.addView(ui.space(12))
                root.addView(focus(focusRemaining > 0L).apply { tag = "focus" })
                root.addView(ui.space(12))
                root.addView(BreatherControls.build(this) {
                    DetachService.refreshSettings()
                    Pause.refreshNotification(this)
                    render()
                }.apply { tag = "breather" })
            }
            Page.LIMITS -> {
                if (currentStatus != "active") root.addView(ui.space(12))
                root.addView(instagram().apply { tag = "instagram" })
                root.addView(ui.space(10))
                root.addView(youtube().apply { tag = "youtube" })
                root.addView(ui.space(10))
                root.addView(blockedApps().apply { tag = "blocked-apps" })
            }
            Page.SCREEN_TIME -> root.addView(ScreenTimePage.build(this, ::render) {
                open(PauseForegroundMonitor.usageAccessIntent(this))
            }.apply { tag = "screen-time" })
            Page.PAYMENTS -> root.addView(payments(paused).apply { tag = "payments" })
            Page.SETTINGS -> {
                root.addView(setup().apply { tag = "setup" })
                root.addView(ui.space(10))
                root.addView(protection().apply { tag = "protection" })
                root.addView(ui.space(10))
                root.addView(usageBackupSettings().apply { tag = "usage-backup" })
                root.addView(ui.space(10))
                root.addView(ui.card(14).apply {
                    addView(privacy().apply { tag = "privacy" })
                    addView(ui.hairline())
                    addView(diagnostics().apply { tag = "diagnostics" })
                })
            }
        }

        val animateReveal = rendered && Motion.enabled(this)
        pendingPreDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                pendingPreDraw = null
                val replacement = anchorTag?.let { root.findViewWithTag<View>(it) }
                scroll.scrollTo(0, replacement?.let { it.top + anchorOffset } ?: previousY)
                if (revealDiagnostics) {
                    revealDiagnostics = false
                    val position = android.graphics.Rect().also { rect ->
                        val diagnostics = root.findViewWithTag<View>("diagnostics")
                        diagnostics.getDrawingRect(rect)
                        root.offsetDescendantRectToMyCoords(diagnostics, rect)
                    }.top
                    if (animateReveal) scroll.smoothScrollTo(0, position) else scroll.scrollTo(0, position)
                    root.findViewWithTag<View>("diagnostic-info").sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }
                pageScroll[page] = scroll.scrollY
                return true
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(pendingPreDraw)
        if (!rendered && previousY == 0 && !revealDiagnostics) {
            for (index in 0 until minOf(4, root.childCount)) Motion.enter(root.getChildAt(index), index * 24L)
        }
        rendered = true
        pageChanged = false
    }

    private fun usageBackupSettings() = ui.card().apply {
        addView(cardHeading(R.drawable.ic_screen_time, "Screen time backup"))
        addView(ui.space(10))
        val configured = UsageBackup.configured(this@MainActivity)
        val saved = UsageBackup.lastSaved(this@MainActivity)
        val status = when {
            !configured -> "Choose a folder for automatic backups at local midnight. Use a synced folder or copy the files before changing phones."
            UsageBackup.failed(this@MainActivity) -> "The last backup failed. Retry or choose another folder."
            saved > 0L -> "Last saved ${android.text.format.DateFormat.getMediumDateFormat(this@MainActivity).format(java.util.Date(saved))}. Scheduled for local midnight. Android may delay background saves."
            else -> "Midnight backup is enabled."
        }
        addView(ui.note(status).apply { setPadding(0, 0, 0, ui.dp(8)) })
        addView(ui.secondary(if (configured) "Change backup folder" else "Choose backup folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            ), BACKUP_FOLDER)
        })
        if (configured) {
            addView(ui.quiet("Back up now") {
                backupAction {
                    UsageArchiveJobs.collect(it)
                    if (UsageBackup.save(it, force = true)) "Screen time backup saved." else "Couldn’t save the backup. Check the folder."
                }
            })
            addView(ui.quiet("Stop daily backups") {
                backupAction {
                    UsageBackup.disable(it)
                    UsageArchiveJobs.schedule(it)
                    "Daily backups stopped. Existing files were kept."
                }
            })
        }
        addView(ui.quiet("Restore screen time backup") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), BACKUP_RESTORE)
        })
        addView(ui.note("Restore merges daily totals without counting them twice. Backups contain app names and usage totals.").apply {
            setPadding(0, ui.dp(4), 0, ui.dp(12))
        })
    }

    @Deprecated("Activity result from the system file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in setOf(BACKUP_FOLDER, BACKUP_RESTORE)) return
        val result = data ?: return
        val uri = result.data?.takeIf { it.scheme == "content" } ?: return
        if (requestCode == BACKUP_FOLDER) {
            if (runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.isFailure) {
                toast("Couldn’t keep access to that backup folder.")
                return
            }
            backupAction {
                UsageBackup.configure(it, uri)
                UsageArchiveJobs.collect(it)
                val saved = UsageBackup.save(it, force = true)
                UsageArchiveJobs.schedule(it)
                if (saved) "Midnight backup enabled." else "Couldn’t write to the chosen folder."
            }
        } else {
            backupAction {
                val count = UsageBackup.restore(it, uri)
                "Backup merged. $count daily app records checked."
            }
        }
    }

    private fun backupAction(action: (android.content.Context) -> String) {
        val app = applicationContext
        UsageArchiveJobs.worker.execute {
            val message = runCatching { action(app) }.getOrDefault("Couldn’t read this backup. Existing history was kept.")
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(message)
                    if (visitActive) render()
                }
            }
        }
    }

    private fun status(paused: Boolean): View {
        val active = serviceEnabled() && DetachService.isRunning && !paused
        val connecting = serviceEnabled() && !DetachService.isRunning && !paused
        val color = if (active) ui.accent else ui.caution
        val title = when {
            paused -> "Protection is paused"
            active -> "Protection is on"
            connecting -> "Waiting for Android"
            else -> "Protection is off"
        }
        val detail = when {
            paused && serviceEnabled() -> "Resume requested. Waiting for Android to reconnect; protection is not confirmed yet."
            paused && Pause.isExpired(this) -> "The pause timer is due. If automatic resume is unavailable or delayed, use Resume protection."
            paused && Pause.canSelfEnable(this) -> "Automatic resume after ${Pause.untilText(this)}. You can resume sooner below."
            paused -> "Reminder at ${Pause.untilText(this)}. Automatic resume needs the optional USB setup in Payments."
            active && Breather.remainingMs(this) > 0 -> "Your breather is on. Instagram and YouTube are unblocked until it ends."
            active -> null
            connecting -> "Accessibility is enabled, but the service hasn’t connected yet. Protection is not confirmed."
            else -> "One Android permission to get started."
        }
        return ui.card(12).apply {
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.TOP
                addView(ui.badge(if (paused) R.drawable.ic_pause else R.drawable.ic_shield, 32, color))
                addView(column().apply {
                    setPaddingRelative(ui.dp(10), ui.dp(3), 0, 0)
                    addView(ui.text(title, 15f, strong = true).apply { isAccessibilityHeading = true })
                    if (detail != null) addView(ui.note(detail))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(column().apply {
                if (connecting) addView(ui.quiet("Check Accessibility settings") {
                    open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                })
                if (!active && !connecting && !paused) addView(ui.quiet("Set up protection") {
                    navigate(Page.SETTINGS)
                })
                if (paused) {
                    addView(ui.space(14))
                    addView(ui.primary("Resume protection") {
                        if (Pause.resume(this@MainActivity)) {
                            toast("Resume requested. Waiting for Android.")
                            render()
                        } else {
                            Toast.makeText(this@MainActivity, "Turn Use Detach on within 3 minutes. If you take longer, return to Detach and tap Resume again.", Toast.LENGTH_LONG).show()
                            open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    })
                    addView(ui.note(if (Pause.canSelfEnable(this@MainActivity)) "The timer can also resume protection. Optional app detection is configured under Payments."
                        else "Opens Accessibility. Turn Use Detach on within 3 minutes of tapping Resume. If you take longer, return here and tap Resume again.").apply { setPadding(0, ui.dp(8), 0, 0) })
                }
            })
        }
    }

    private fun setup() = ui.card().apply {
        val enabled = serviceEnabled()
        addView(cardHeading(R.drawable.ic_settings, "Accessibility", if (enabled) "Enabled in Android settings."
            else "Open Detach under Downloaded apps, then turn on Use Detach."))
        addView(ui.space(12))
        addView(ui.secondary("Open Accessibility") { open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) })
        if (!enabled && Build.VERSION.SDK_INT >= 33) {
            addView(disclosure("restricted", "Android says Restricted setting?") {
                addView(ui.note("For an app installed from a file: open App info, tap the top-right menu and choose Allow restricted settings. Then return to Accessibility."))
                addView(ui.space(8))
                addView(ui.secondary("Open App info") {
                    open(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                })
            })
        }
        if (needsNotificationPermission()) {
            addView(ui.quiet("Allow notifications · optional") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            })
            addView(ui.note("For protection status, payment reminders and app compatibility warnings."))
        }
    }

    private fun focus(active: Boolean) = ui.hero(column().apply {
            setPadding(ui.dp(18), ui.dp(17), ui.dp(18), ui.dp(18))
            addView(ui.badge(R.drawable.ic_sessions, 40, ui.onAccent, ui.accent))
            addView(ui.space(10))
            addView(ui.display("Focus mode", 31f).apply { isAccessibilityHeading = true })
            if (active) {
                addView(ui.note("Session in progress"))
                addView(ui.space(12))
                addView(ui.primary("Return to Focus") {
                    if (DetachService.isRunning) {
                        DetachService.openFocusHome()
                    } else {
                        open(Intent(this@MainActivity, FocusActivity::class.java))
                    }
                })
            } else {
                addView(ui.note("Keep the apps you need and pause the rest.").apply {
                    setPadding(0, ui.dp(4), 0, ui.dp(14))
                })
                addView(ui.primary("Set Focus duration") { chooseFocusDuration() })
                addView(ui.space(8))
                addView(focusAppsRow())
                addView(ui.quiet("Add Focus widget") { addHomeWidget(FocusWidgetProvider::class.java, "Focus timer") })
            }
    })

    private fun focusAppsRow(): View {
        val apps = FocusAppPicker.allowedApps(this)
        val preview = apps.take(3).joinToString(", ") { it.name }
        val summary = when {
            apps.isEmpty() -> "No apps available"
            apps.size <= 3 -> preview
            else -> "$preview +${apps.size - 3} more"
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.fieldSurface, ui.line)
            setPaddingRelative(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            minimumHeight = ui.dp(56)
            isFocusable = true
            contentDescription = "Choose allowed apps. ${apps.size} available during Focus."
            addView(ui.badge(R.drawable.ic_apps, 32))
            addView(column().apply {
                setPaddingRelative(ui.dp(9), 0, ui.dp(6), 0)
                addView(ui.text("Choose allowed apps", 14f, strong = true))
                addView(ui.note(summary).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.icon(R.drawable.ic_chevron, 20, ui.accent).apply { rotation = -90f })
            setOnClickListener { FocusAppPicker.show(this@MainActivity) { render() } }
        }
    }

    private fun chooseFocusDuration() = FocusDurationPicker.show(this) { seconds ->
        FocusEntry.startOrReturn(this, seconds)
        render()
    }

    private fun instagram() = ui.card(14).apply {
        val options = column()
        addView(appRow(R.drawable.ic_instagram_glyph, "Instagram", settings.igEnabled,
            { if (it) "Your selected limits are on" else "No limits applied" }) {
            settings.igEnabled = it
            setEnabledLook(options, it)
            DetachService.refreshSettings()
        })
        options.addView(optionRow("Hide home feed", "Stories can stay available", settings.igFeed) { settings.igFeed = it })
        options.addView(optionRow("Block Reels tab", "Shared reels are controlled separately below", settings.igReelsTab) { settings.igReelsTab = it })
        options.addView(optionRow("Hide Explore & reels in search", "Account search stays available", settings.igExplore) { settings.igExplore = it })
        options.addView(optionRow("Hide others’ profile posts", "Their name, bio and buttons stay visible", settings.igOtherProfiles) { settings.igOtherProfiles = it })
        options.addView(ui.hairline())
        options.addView(optionRow("Allow Stories", "Off leaves Stories immediately", settings.igStories) { settings.igStories = it })
        options.addView(optionRow("Allow DMs", "Inbox and chat threads", settings.igMessages) { settings.igMessages = it })
        options.addView(optionRow("Allow reels sent in DMs", "One shared reel; no swiping to the next", settings.igSharedReels) { settings.igSharedReels = it })
        setEnabledLook(options, settings.igEnabled, animate = false)
        addView(disclosure("instagram-options", "Customize Instagram") { addView(options) })
    }

    private fun youtube() = ui.card(14).apply {
        addView(appRow(R.drawable.ic_youtube_glyph, "YouTube", settings.ytShorts,
            { if (it) "Shorts blocked. Videos stay available." else "No limits applied" }) {
            settings.ytShorts = it
            DetachService.refreshSettings()
        })
    }

    private fun blockedApps() = ui.card().apply {
        addView(cardHeading(R.drawable.ic_lock, "Blocked apps", "Whole-app blocks apply while protection is on."))
        val names = settings.blockedPackages.mapNotNull { packageName ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
                .getOrNull()
        }.sortedBy { it.lowercase() }
        addView(ui.note(if (names.isEmpty()) "No apps selected" else names.joinToString(", ")).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        })
        addView(ui.secondary("Choose apps to block") {
            requirePassword { PaymentAppPicker.showBlockedApps(this@MainActivity) { DetachService.refreshSettings(); render() } }
        })
    }

    private fun payments(paused: Boolean) = column().apply {
        if (paused) addView(ui.space(12))
        addView(ui.hero(column().apply {
            setPadding(ui.dp(17), ui.dp(16), ui.dp(17), ui.dp(17))
            addView(cardHeading(R.drawable.ic_payments, "Payment pause",
                "Temporarily switches off Detach’s Accessibility service so you can use payment apps."))
            if (!paused && DetachService.isRunning) {
                addView(ui.space(12))
                addView(ui.primary("Pause protection for payment") {
                    Auth.confirm(this@MainActivity, "Pause for payments") {
                        toast(if (DetachService.pauseForPayments()) "Protection paused. Your payment timer is set."
                            else "Could not pause protection. Check Accessibility or try again.")
                        render()
                    }
                })
            }
        }))
        addView(ui.space(10))
        addView(ui.card().apply {
            addView(cardHeading(R.drawable.ic_apps, "Home screen widget", "Open a payment app from your home screen. Detach pauses protection first."))
            addView(ui.space(10))
            addView(ui.secondary("Add payment widget") { addHomeWidget(PaymentWidgetProvider::class.java, "Payment apps") })
        })
        addView(ui.space(10))
        val preferences = disclosure("payment-options", "Payment preferences") {
            addView(optionRow("Auto-pause for payment apps", "Checks selected package names, never screen content", settings.autoPausePayments) {
                settings.autoPausePayments = it
            })
            addView(ui.note("Opening a selected app bypasses your password and turns all protection off during the pause. Instagram, YouTube and uninstalling Detach are unrestricted until protection resumes. Only selected apps are detected; embedded payments are not.").apply {
                setPadding(0, 0, 0, ui.dp(12))
            })
            addView(ui.secondary("Choose payment & banking apps") { requirePassword { choosePaymentApps() } })
            addView(ui.note("${settings.paymentPackages.size} apps selected. Four UPI apps are included by default; select any other banks you use.").apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            addView(reminderRow())
            addView(optionRow("Resume when Instagram or YouTube opens", "Also resumes for blocked apps during payment pauses", settings.resumeOnSocialForeground) { enabled ->
                if (enabled) confirmForegroundResume() else {
                    settings.resumeOnSocialForeground = false
                    PauseForegroundMonitor.stop(this@MainActivity)
                    render()
                }
            })
            if (settings.resumeOnSocialForeground && !PauseForegroundMonitor.hasUsageAccess(this@MainActivity)) {
                addView(ui.note("App detection is unavailable without Usage Access. The payment timer still applies."))
                addView(ui.quiet("Review Usage Access") { open(PauseForegroundMonitor.usageAccessIntent(this@MainActivity)) })
            }
            addView(optionRow("Show protection status", "Silent notification with a payment-pause action", settings.pauseNotification) {
                settings.pauseNotification = it
                Pause.refreshNotification(this@MainActivity)
            })
            if (!Pause.canSelfEnable(this@MainActivity)) addView(disclosure("usb", "Enable automatic Resume over USB") {
                addView(ui.note("This optional grant lets the timer, your explicit Resume tap, and opt-in app detection re-enable Detach. Without it, Android requires you to turn Detach on in Accessibility settings. Timer-only mode cannot tell whether you are still banking; choose enough time."))
                addView(ui.note(Pause.GRANT_COMMAND).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                })
            })
        }
        addView(ui.card(14).apply { addView(preferences) })
    }

    private fun reminderRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        addView(ui.text("Payment pause", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun adjust(label: String, delta: Int) = ui.secondary(label) {
            val next = (settings.pauseMinutes + delta).coerceIn(1, 60)
            if (next != settings.pauseMinutes) requirePassword {
                if (next > settings.pauseMinutes && BreatherBudget.restrictionsLocked(this@MainActivity)) {
                    toast("Payment pause cannot be extended until the daily allowance resets.")
                } else {
                    settings.pauseMinutes = next
                    render()
                }
            }
        }.apply {
            contentDescription = if (delta < 0) "Decrease payment pause by one minute" else "Increase payment pause by one minute"
            isEnabled = if (delta < 0) settings.pauseMinutes > 1 else settings.pauseMinutes < 60
        }
        addView(adjust("−", -1))
        addView(ui.text("${settings.pauseMinutes} min", 14f, strong = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), 0, ui.dp(10), 0)
            contentDescription = "Payment pause for ${settings.pauseMinutes} minutes"
        })
        addView(adjust("+", 1))
    }

    private fun choosePaymentApps() {
        PaymentAppPicker.show(this) {
            DetachService.refreshSettings()
            PaymentWidgetProvider.updateAll(this)
            toast("Payment apps saved on this device")
            render()
        }
    }

    private fun confirmForegroundResume() {
        val visit = authorizationVisit
        val ready = Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)
        val detail = "While payment protection is paused, a separate local monitor checks foreground app packages about every 2 seconds while your screen is unlocked. It does not read screen content, save app history, or send data anywhere. It stops after resume and does not poll while the screen is off or locked. Android can delay detection or prevent it from starting.\n\nThis needs Usage Access and the optional USB grant. Timer-only mode has no app monitor and cannot know whether you are still banking. Enabling applies to your next payment pause."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enable local app detection?")
            .setMessage(detail)
            .setNegativeButton("Not now") { _, _ -> render() }
            .setOnCancelListener { render() }
            .setPositiveButton(if (ready) "Enable" else "Review setup") { _, _ ->
                if (!visitActive || authorizationVisit != visit || isFinishing || isDestroyed) return@setPositiveButton
                if (ready && Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)) {
                    settings.resumeOnSocialForeground = true
                    render()
                } else {
                    expanded.add("payment-options")
                    expanded.add("usb")
                    render()
                    if (!PauseForegroundMonitor.hasUsageAccess(this)) open(PauseForegroundMonitor.usageAccessIntent(this))
                    else toast("Complete the optional USB grant, then enable app detection.")
                }
            }
            .create()
        protectDialog(dialog)
        dialog.show()
        ui.style(dialog)
    }

    private fun protectDialog(dialog: AlertDialog) {
        protectedDialogs.add(dialog)
        dialog.setOnDismissListener { protectedDialogs.remove(dialog) }
    }

    private fun protection() = ui.card().apply {
        addView(cardHeading(R.drawable.ic_lock, "Settings protection"))
        addView(ui.space(10))
        addView(ui.text(when {
            !lock.isSet -> "No password set"
            unlocked -> "Unlocked for this visit"
            else -> "Your settings are locked"
        }, 19f, strong = true))
        addView(ui.note(when {
            !lock.isSet -> "A password keeps an impulse from changing your limits."
            unlocked -> "They lock again when you leave this screen."
            Auth.biometricAvailable(this@MainActivity) -> "Use your fingerprint or password to change app controls."
            else -> "Use your password to change app controls."
        }).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
        if (!lock.isSet) addView(ui.secondary("Set password") { setPasswordDialog() })
        else addView(LinearLayout(this@MainActivity).apply {
            addView(ui.secondary("Change password") { setPasswordDialog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.quiet("Remove") { removePassword() })
        })
        addView(disclosure("lock-info", "How protection works") {
            addView(ui.note("Detach protects its system settings from being switched off, force-stopped, cleared or uninstalled. Once your daily breather allowance is exhausted, those pages stay locked until the allowance resets. Otherwise unlocking uses your password or fingerprint, or a 30-second wait if neither is available. Changing or removing a password always requires the current password."))
        })
    }

    private fun privacy() = disclosure("privacy-info", "Private by design") {
        addView(ui.note("Detach has no internet permission.\n\nScreen content is read only in Instagram, YouTube and protected Settings pages. Payment monitoring checks package names. Screen time saves daily totals for selected apps on this phone. If you set up a backup, those totals and app names are copied to the folder you choose.\n\nPreferences, a hashed password, pause timing, recent Breather timing and diagnostic counters stay in private on-device storage. Screen content is not saved."))
        addView(ui.space(12))
    }

    private fun diagnostics() = disclosure("diagnostic-info", "Diagnostics") {
        addView(ui.note(Health(this@MainActivity).report()).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, ui.dp(12))
        })
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun cardHeading(icon: Int, title: String, detail: String? = null) = LinearLayout(this).apply {
        gravity = if (detail == null) Gravity.CENTER_VERTICAL else Gravity.TOP
        addView(ui.badge(icon))
        addView(column().apply {
            setPaddingRelative(ui.dp(12), ui.dp(1), 0, 0)
            addView(ui.text(title, 18f, strong = true).apply { isAccessibilityHeading = true })
            if (detail != null) addView(ui.note(detail))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun disclosure(key: String, label: String, content: LinearLayout.() -> Unit): View {
        val box = column()
        val body = column().apply {
            content()
            visibility = if (key in expanded) View.VISIBLE else View.GONE
        }
        lateinit var header: ViewGroup
        header = ui.disclosure(label, key in expanded) {
            val opening = key !in expanded
            if (opening) expanded.add(key) else expanded.remove(key)
            Motion.section(root)
            body.visibility = if (opening) View.VISIBLE else View.GONE
            ui.updateDisclosure(header, opening)
        }.apply { tag = key }
        box.addView(header)
        box.addView(body)
        return box
    }

    private fun appRow(icon: Int, title: String, value: Boolean, subtitle: (Boolean) -> String, change: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(54)
        }
        row.addView(ui.badge(icon))
        val labels = column().apply { setPaddingRelative(ui.dp(12), 0, ui.dp(6), 0) }
        labels.addView(ui.text(title, 18f, strong = true))
        val detail = ui.note(subtitle(value))
        labels.addView(detail)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(guardedSwitch(value, "$title protection") {
            change(it)
            detail.text = subtitle(it)
        })
        return row
    }

    private fun optionRow(title: String, hint: String, value: Boolean, change: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(10), 0, ui.dp(10))
        val labels = column().apply {
            setPadding(0, 0, ui.dp(10), 0)
            addView(ui.text(title, 15f))
            addView(ui.note(hint).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(guardedSwitch(value, title) {
            change(it)
            DetachService.refreshSettings()
        })
    }

    private fun guardedSwitch(value: Boolean, label: String, change: (Boolean) -> Unit): Switch = ui.toggle(value, label).apply {
        setOnCheckedChangeListener { control, wanted ->
            if (silent) return@setOnCheckedChangeListener
            val relaxes = when (label) {
                "Instagram protection", "YouTube protection", "Hide home feed", "Block Reels tab",
                "Hide Explore & reels in search", "Hide others’ profile posts" -> !wanted
                "Allow Stories", "Allow DMs", "Allow reels sent in DMs" -> wanted
                "Resume when Instagram or YouTube opens" -> !wanted
                "Auto-pause for payment apps" -> wanted
                else -> false
            }
            // A short breather can leave allowance unused. Do not let it be used to turn off
            // the permanent feed/Reels/Explore protection before it expires. DM permissions
            // remain independently configurable because messaging is an intended use.
            val coreScrollProtection = label in setOf(
                "Instagram protection", "Hide home feed", "Block Reels tab",
                "Hide Explore & reels in search", "Hide others’ profile posts"
            )
            val lockedForActiveBreather = coreScrollProtection && Breather.remainingMs(this@MainActivity) > 0L
            val blocked = relaxes && (BreatherBudget.restrictionsLocked(this@MainActivity) || lockedForActiveBreather)
            if (blocked) {
                flip(control as Switch, !wanted)
                toast(if (lockedForActiveBreather) "Core scrolling blocks stay on until the breather ends."
                    else "Restrictions are locked until the daily allowance resets.")
                return@setOnCheckedChangeListener
            }
            if (!lock.isSet || unlocked) {
                change(wanted)
            } else {
                flip(control as Switch, !wanted)
                requirePassword {
                    val stillLockedForBreather = coreScrollProtection && Breather.remainingMs(this@MainActivity) > 0L
                    val stillBlocked = relaxes && (BreatherBudget.restrictionsLocked(this@MainActivity) || stillLockedForBreather)
                    if (stillBlocked) {
                        toast(if (stillLockedForBreather) "Core scrolling blocks stay on until the breather ends."
                            else "Restrictions are locked until the daily allowance resets.")
                    } else {
                        flip(control, wanted)
                        change(wanted)
                    }
                }
            }
        }
    }

    private fun flip(control: Switch, value: Boolean) {
        silent = true
        control.isChecked = value
        silent = false
    }

    private fun setEnabledLook(group: ViewGroup, enabled: Boolean, animate: Boolean = true) {
        fun walk(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(group)
        if (animate) Motion.emphasis(group, enabled) else group.alpha = 1f
    }

    private fun requirePassword(alwaysAsk: Boolean = false, action: () -> Unit) {
        if (!visitActive) return
        if (!lock.isSet || (unlocked && !alwaysAsk)) {
            action()
            return
        }
        val visit = authorizationVisit
        val confirmed = {
            if (visitActive && authorizationVisit == visit && !isFinishing && !isDestroyed) {
                if (!alwaysAsk) unlocked = true
                action()
                render()
            }
        }
        if (alwaysAsk) Auth.passwordDialog(this, positive = "Continue", onSuccess = confirmed)
        else Auth.confirm(this, "Unlock Detach's switches", onSuccess = confirmed)
    }

    private fun removePassword() = requirePassword(alwaysAsk = true) {
        lock.clear()
        unlocked = false
        toast("Password removed")
    }

    private fun setPasswordDialog() {
        val visit = authorizationVisit
        val changing = lock.isSet
        val current = if (changing) passwordEditText(this, "Current password") else null
        val first = passwordEditText(this, "New password (at least 4 characters)")
        val second = passwordEditText(this, "Repeat new password")
        val box = column().apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            current?.let { addView(it) }
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (changing) "Change password" else "Set a password")
            .setMessage("You'll need it to change controls and unlock protected system settings. If you forget it, reinstalling Detach resets everything.")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        protectDialog(dialog)
        dialog.setOnShowListener {
            ui.style(dialog)
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                if (!visitActive || authorizationVisit != visit || dialog !in protectedDialogs) return@setOnClickListener
                val password = first.text.toString()
                val repeated = second.text.toString()
                when {
                    password.length < 4 -> { first.error = "At least 4 characters"; return@setOnClickListener }
                    password != repeated -> { second.error = "Doesn't match"; return@setOnClickListener }
                }
                if (current != null && lock.lockoutSeconds() > 0) {
                    current.error = "Too many tries. Wait ${lock.lockoutSeconds()} s"
                    return@setOnClickListener
                }
                val old = current?.text?.toString()
                save.isEnabled = false
                HashWorker.run(first, {
                    if (old != null && !lock.verify(old)) false else { lock.set(password); true }
                }) { saved ->
                    save.isEnabled = true
                    if (saved) {
                        unlocked = false
                        dialog.dismiss()
                        toast(if (changing) "Password changed" else "Password saved")
                        render()
                    } else {
                        current?.text?.clear()
                        val wait = lock.lockoutSeconds()
                        current?.error = if (wait > 0) "Too many tries. Wait $wait s" else "Wrong current password"
                    }
                }
            }
        }
        dialog.show()
        (current ?: first).requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun needsNotificationPermission() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun addHomeWidget(providerClass: Class<out android.appwidget.AppWidgetProvider>, label: String) {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, providerClass)
        if (manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(provider, null, null)) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add $label to Home")
            .setMessage("Touch and hold an empty area of your home screen, choose Widgets, find Detach, then drag $label onto the home screen.")
            .setPositiveButton("Got it", null)
            .create()
        dialog.setOnShowListener { ui.style(dialog) }
        dialog.show()
    }

    private fun serviceEnabled(): Boolean {
        val services = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return services.split(':').any { it.startsWith("$packageName/") }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Couldn't open that screen on this phone") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val BACKUP_FOLDER = 81
        const val BACKUP_RESTORE = 82
    }
}
