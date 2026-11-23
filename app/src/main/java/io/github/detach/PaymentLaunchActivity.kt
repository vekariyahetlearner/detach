package io.github.detach

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Brief hand-off screen used by the widget. The bank app is never started until Detach is off. */
class PaymentLaunchActivity : Activity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAt = 0L
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private lateinit var message: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (closeDuringFocus()) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null || packageName !in Settings(this).paymentPackages ||
            this.packageManager.getLaunchIntentForPackage(packageName) == null) {
            finish()
            return
        }
        showPreparing(packageName)
        startedAt = SystemClock.elapsedRealtime()
        handler.post { begin(packageName) }
    }

    private fun begin(packageName: String) {
        if (isFinishing || isDestroyed || closeDuringFocus()) return
        if (packageName in Settings(this).blockedPackages) {
            showError("This app is in your blocked apps list. Remove it there before opening it.")
            return
        }
        if (Pause.isPending(this) && !Pause.isServiceEnabled(this)) {
            launch(packageName)
            return
        }
        if (!DetachService.pauseForPayments(packageName)) {
            showError("Detach needs to be active before it can prepare a payment app.")
            return
        }
        awaitDisabled(packageName)
    }

    private fun awaitDisabled(packageName: String) {
        if (!Pause.isServiceEnabled(this)) {
            // Keep the transition perceptible but do not add a noticeable wait.
            val remaining = (120L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            handler.postDelayed({ launch(packageName) }, remaining)
        } else if (SystemClock.elapsedRealtime() - startedAt < DISABLE_TIMEOUT_MS) {
            handler.postDelayed({ awaitDisabled(packageName) }, CHECK_INTERVAL_MS)
        } else {
            showError("Detach took too long to pause. Your payment app was not opened.")
        }
    }

    private fun launch(packageName: String) {
        if (isFinishing || isDestroyed || closeDuringFocus()) return
        if (Pause.isServiceEnabled(this)) {
            showError("Protection resumed before the payment app could open. Try the shortcut again.")
            return
        }
        if (packageName in Settings(this).blockedPackages) {
            showError("This app is in your blocked apps list. Remove it there before opening it.")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null || packageName !in Settings(this).paymentPackages) {
            showError("This payment app is no longer available.")
            return
        }
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            .onFailure { showError("Couldn’t open this payment app.") }
            .onSuccess { finish() }
    }

    private fun closeDuringFocus(): Boolean {
        if (!FocusSession.active(this)) return false
        Toast.makeText(this, "Payment shortcuts are available after Focus ends.", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    private fun showPreparing(packageName: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("your payment app")
        val ui = Ui(this)
        val padding = ui.dp(28)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.window))
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        title = ui.text("Preparing $label", 23f, strong = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, ui.dp(8))
            isAccessibilityHeading = true
        }
        root.addView(title)
        message = ui.text("Turning off Detach before opening your payment app.", 15f, ui.sub).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(message)
        setContentView(root)
    }

    private fun showError(errorMessage: String) {
        progress.visibility = View.GONE
        title.text = "Payment app couldn’t open"
        message.text = errorMessage
        val ui = Ui(this)
        (message.parent as LinearLayout).addView(
            ui.primary("Close") { finish() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) },
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "payment_package"
        private const val CHECK_INTERVAL_MS = 16L
        private const val DISABLE_TIMEOUT_MS = 2_000L
    }
}
