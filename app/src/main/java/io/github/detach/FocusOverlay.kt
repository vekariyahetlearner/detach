package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/** Full-screen Accessibility surface used as Focus home; it is not an Activity/Recents task. */
class FocusOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private data class Surface(
        val root: View,
        val clock: TextView,
        val remaining: TextView,
        val meter: ProgressBar,
        val duration: Long,
        val configuration: Configuration,
    )
    private var surface: Surface? = null
    private var attached = false
    private val tick = object : Runnable {
        override fun run() {
            val left = FocusSession.remainingMs(service)
            if (left == 0L) { clear(); return }
            val current = surface ?: return
            val time = DateFormat.getTimeFormat(service).format(Date())
            if (current.clock.text != time) current.clock.text = time
            current.remaining.text = FocusSession.remainingTime(left)
            if (current.duration > 0L) current.meter.progress = (left * 1000L / current.duration).toInt().coerceIn(0, 1000)
            main.postDelayed(this, (left - 1L) % 1_000L + 1L)
        }
    }

    fun show() {
        if (!FocusSession.active(service)) return
        val configuration = service.resources.configuration
        if (surface?.configuration != configuration) clear()
        if (attached) return
        val current = surface ?: buildSurface(configuration).also { surface = it }
        runCatching { wm.addView(current.root, layoutParams()) }.onSuccess {
            attached = true
            tick.run()
        }
    }

    fun hide() {
        main.removeCallbacks(tick)
        if (attached) surface?.let { runCatching { wm.removeViewImmediate(it.root) } }
        attached = false
    }

    fun clear() {
        hide()
        surface = null
    }

    private fun buildSurface(configuration: Configuration): Surface {
        val ui = Ui(service)
        val pinnedHeader = configuration.screenHeightDp >= 500 && configuration.fontScale < 1.5f
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val side = maxOf(ui.dp(20), ui.dp((configuration.screenWidthDp - 640) / 2))
            setPadding(side, ui.dp(12), side, if (pinnedHeader) 0 else ui.dp(20))
        }
        val clockView = ui.text("", 13f, ui.accent, true).apply {
            background = ui.shape(ui.accentSurface, radius = 14)
            setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
            gravity = Gravity.END
        }
        val remainingView = ui.display("", if (configuration.fontScale >= 1.3f) 40f else 58f, ui.accent).apply {
            fontFeatureSettings = "tnum"
        }
        val duration = FocusSession.durationMs(service)
        val meter = ui.meter().apply { if (duration <= 0L) visibility = View.GONE }
        content.addView(LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.badge(R.drawable.ic_mark, 32, ui.onAccent, ui.accent))
            addView(ui.text("Focus active", 19f, strong = true).apply { isAccessibilityHeading = true },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = ui.dp(10)
                    marginEnd = ui.dp(6)
                })
            addView(clockView)
        })
        content.addView(ui.hero(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(17), ui.dp(12), ui.dp(17), ui.dp(15))
            addView(ui.note("Time remaining"))
            addView(remainingView)
            if (duration > 0L) {
                addView(ui.space(10))
                addView(meter)
            }
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(14)
        })
        content.addView(ui.text("Allowed apps", 17f, strong = true).apply {
            isAccessibilityHeading = true
            setPadding(0, ui.dp(17), 0, ui.dp(7))
        })
        val apps = FocusAppPicker.allowedApps(service)
        val grid = if (pinnedHeader) LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(16))
        } else content
        if (apps.isEmpty()) grid.addView(ui.note("No allowed apps are installed."))
        val columns = when {
            configuration.fontScale >= 1.3f -> 2
            configuration.screenWidthDp >= 560 -> 4
            configuration.screenWidthDp >= 360 -> 3
            else -> 2
        }
        fun slot(index: Int) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = if (index == 0) 0 else ui.dp(8)
        }
        apps.chunked(columns).forEach { row ->
            grid.addView(LinearLayout(service).apply {
                gravity = Gravity.TOP
                row.forEachIndexed { index, app -> addView(tile(app, ui), slot(index)) }
                for (index in row.size until columns) addView(View(service), slot(index))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(8)
            })
        }
        val root = if (pinnedHeader) content.apply {
            addView(ScrollView(service).apply { addView(grid) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else ScrollView(service).apply {
            addView(content)
        }
        root.setBackgroundColor(ui.bg)
        return Surface(root, clockView, remainingView, meter, duration, Configuration(configuration))
    }

    private fun layoutParams() = WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            title = "Focus lock"
            if (Build.VERSION.SDK_INT >= 30) {
                // Accessibility overlays are above the shade. Leave exactly Android's system
                // bar insets available and let the service hide this window when the shade opens.
                fitInsetsTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                setFitInsetsIgnoringVisibility(true)
            }
        }

    private fun tile(app: PaymentAppPicker.App, ui: Ui) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = ui.dp(92)
        setPadding(ui.dp(6), ui.dp(9), ui.dp(6), ui.dp(9))
        isFocusable = true
        background = ui.ripple(ui.surface, ui.line, radius = 19)
        contentDescription = "Open ${app.name}"
        addView(ImageView(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val manager = service.packageManager
            setImageDrawable(runCatching { manager.getApplicationIcon(app.info) }.getOrElse { manager.defaultActivityIcon })
        }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        addView(ui.text(app.name, 13f, strong = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ui.dp(6), 0, 0)
        })
        setOnClickListener { launch(app) }
    }

    private fun launch(app: PaymentAppPicker.App) {
        if (FocusLaunchGate.awaiting() || !FocusSession.active(service)) return
        val pkg = app.info.packageName
        val intent = FocusAppPicker.launchIntent(service.packageManager, pkg)
        if (intent == null) {
            Toast.makeText(service, "${app.name} is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        FocusLaunchGate.begin(pkg)
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            FocusLaunchGate.clear()
            Toast.makeText(service, "Couldn’t open ${app.name}.", Toast.LENGTH_SHORT).show()
        }
    }
}
