package io.github.detach

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.ScrollView

/** The only home surface shown during an active Focus session. */
class FocusActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var remaining: TextView
    private lateinit var clock: TextView
    private val tick = object : Runnable {
        override fun run() {
            val left = FocusSession.remainingMs(this@FocusActivity)
            if (left == 0L) { showComplete(); return }
            remaining.text = "%d:%02d remaining".format(left / 60_000, (left / 1_000) % 60)
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1_000L - System.currentTimeMillis() % 1_000L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Kept only to drain task-stack entries created by pre-overlay builds.
        if (DetachService.isRunning) { finish(); return }
        immersive()
        if (!FocusSession.active(this)) { finish(); return }
        renderFocus()
    }

    override fun onResume() {
        super.onResume()
        if (DetachService.isRunning) { finish(); return }
        active = true; immersive(); handler.removeCallbacks(tick); tick.run()
    }
    override fun onPause() { active = false; handler.removeCallbacks(tick); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { /* Focus home intentionally has no back route. */ }

    private fun immersive() {
        // Some framework test windows do not create a decor view until after setContentView.
        runCatching {
            // Keep the status bar: notifications remain visible. Hide only navigation, and do
            // not opt into transient swipe-reveal bars that make Focus look dismissible.
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
        }
    }

    private fun renderFocus() {
        val ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(20)); setBackgroundColor(ui.bg)
        }
        clock = ui.text("", 42f, strong = true).apply { gravity = Gravity.CENTER }
        remaining = ui.text("", 17f, ui.accent, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }
        root.addView(clock); root.addView(ui.text("FOCUS ACTIVE", 12f, ui.sub, true).apply { gravity = Gravity.CENTER; letterSpacing = .14f; setPadding(0, ui.dp(14), 0, 0) })
        root.addView(remaining)
        root.addView(ui.text("Only selected apps are available.", 15f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(20)) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // OEMs commonly ship both AOSP/Motorola and Google variants of Phone or Contacts.
        // Show one useful tile per visible app name, while retaining the selected package behind it.
        val apps = Settings(this).focusAllowedPackages.mapNotNull { packageName -> app(packageName) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .distinctBy { it.name.lowercase(Locale.getDefault()) }
        if (apps.isEmpty()) grid.addView(ui.text("No selected apps are installed.", 15f, ui.sub).apply { gravity = Gravity.CENTER })
        apps.chunked(2).forEach { rowApps ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            rowApps.forEach { item -> row.addView(tile(item), LinearLayout.LayoutParams(0, ui.dp(108), 1f).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }) }
            if (rowApps.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, ui.dp(108), 1f))
            grid.addView(row)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = false; clipToPadding = false; addView(grid)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ui.text("Locked until the timer ends", 13f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, 0) })
        setContentView(root)
    }

    private data class FocusApp(val packageName: String, val name: String, val icon: android.graphics.drawable.Drawable)

    private fun app(packageName: String): FocusApp? = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        FocusApp(packageName, packageManager.getApplicationLabel(info).toString(), packageManager.getApplicationIcon(info))
    }.getOrNull()

    private fun tile(app: FocusApp): View {
        val ui = Ui(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = ui.ripple(ui.surface, ui.line); contentDescription = "Open ${app.name}"
            addView(ImageView(this@FocusActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
            addView(ui.text(app.name, 14f, strong = true).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, ui.dp(10), 0, 0) })
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    FocusLaunchGate.begin(app.packageName)
                    startActivity(it)
                }
            }
        }
    }

    private fun showComplete() {
        handler.removeCallbacks(tick)
        val ui = Ui(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28)); setBackgroundColor(ui.bg)
            addView(ui.text("Complete", 34f, strong = true).apply { gravity = Gravity.CENTER })
            addView(ui.text("Your Focus session is finished.", 16f, ui.sub).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, ui.dp(24)) })
            addView(ui.primary("Done") { finish() })
        })
    }

    companion object { @Volatile var active = false }
}
