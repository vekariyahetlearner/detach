package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.os.Build
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen Accessibility surface used as Focus home; it is not an Activity/Recents task. */
class FocusOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!FocusSession.active(service)) { hide(); return }
            view?.findViewWithTag<android.widget.TextView>("focus-clock")?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val left = FocusSession.remainingMs(service)
            if (left == 0L) { hide(); return }
            view?.findViewWithTag<android.widget.TextView>("focus-remaining")?.text = "%d:%02d remaining".format(left / 60_000, (left / 1000) % 60)
            main.postDelayed(this, 1000)
        }
    }
    fun show() {
        if (!FocusSession.active(service)) return
        if (view == null) add()
        main.removeCallbacks(tick); tick.run()
    }
    fun hide() { main.removeCallbacks(tick); view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null }
    private fun add() {
        val ui = Ui(service)
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(ui.bg)
            // The overlay begins below Android's status/camera region; this additional breathing
            // room keeps the clock visually separate from a centred punch-hole camera.
            setPadding(ui.dp(28), ui.dp(8), ui.dp(28), ui.dp(18))
        }
        root.addView(ui.text("", 38f, strong = true).apply { tag = "focus-clock"; gravity = Gravity.CENTER })
        root.addView(ui.text("FOCUS ACTIVE", 12f, ui.sub, true).apply { gravity = Gravity.CENTER; letterSpacing = .14f; setPadding(0, ui.dp(10), 0, 0) })
        root.addView(ui.text("", 17f, ui.accent, true).apply { tag = "focus-remaining"; gravity = Gravity.CENTER; setPadding(0, ui.dp(5), 0, ui.dp(12)) })
        val grid = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val apps = Settings(service).focusAllowedPackages.mapNotNull { pkg -> app(pkg) }.sortedBy { it.name }.distinctBy { it.name }
        apps.chunked(2).forEach { pair ->
            grid.addView(LinearLayout(service).apply {
                gravity = Gravity.CENTER
                pair.forEach { addView(tile(it), LinearLayout.LayoutParams(0, ui.dp(108), 1f).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }) }
                if (pair.size == 1) addView(View(service), LinearLayout.LayoutParams(0, ui.dp(108), 1f))
            })
        }
        root.addView(ScrollView(service).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ui.text("Locked until the timer ends", 13f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) })
        // Reserve Android's status/camera strip. This keeps notification gestures accessible;
        // DetachService removes the rest of the overlay while the shade is foreground.
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
        )
        val topStrip = minOf(insets.top, ui.dp(28))
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE).apply {
            width = metrics.bounds.width() - insets.left - insets.right
            height = metrics.bounds.height() - topStrip - insets.bottom
            x = insets.left; y = topStrip; gravity = Gravity.TOP or Gravity.START; title = "Focus lock"
        }
        runCatching { wm.addView(root, lp) }.onSuccess { view = root }
    }
    private data class App(val pkg: String, val name: String, val icon: android.graphics.drawable.Drawable)
    private fun app(pkg: String) = runCatching { val i = service.packageManager.getApplicationInfo(pkg, 0); App(pkg, service.packageManager.getApplicationLabel(i).toString(), service.packageManager.getApplicationIcon(i)) }.getOrNull()
    private fun tile(app: App): View { val ui = Ui(service); return LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; background = ui.ripple(ui.surface, ui.line)
        addView(ImageView(service).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        addView(ui.text(app.name, 14f, strong = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) })
        setOnClickListener { service.packageManager.getLaunchIntentForPackage(app.pkg)?.let { intent ->
            FocusLaunchGate.begin(app.pkg)
            (service as? DetachService)?.onFocusAllowedLaunch()
            hide()
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
    } }
}
