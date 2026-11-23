package io.github.detach

import android.os.SystemClock

/**
 * Keeps repeated taps from starting multiple apps while Android brings a Focus tile's app
 * to the foreground. Focus remains visible over the source window during the handoff.
 */
object FocusLaunchGate {
    // Cold starts (especially WhatsApp after force-stop/reboot) can take several seconds before
    // their first window is reported. The gate still accepts only its exact selected package.
    private const val HANDOFF_MS = 8_000L
    @Volatile private var target: String? = null
    @Volatile private var until = 0L

    fun begin(packageName: String) {
        target = packageName
        until = SystemClock.uptimeMillis() + HANDOFF_MS
    }

    fun awaiting(): Boolean = target != null && SystemClock.uptimeMillis() < until

    fun accepts(packageName: String): Boolean = awaiting() && packageName == target

    fun clear() { target = null; until = 0L }
}
