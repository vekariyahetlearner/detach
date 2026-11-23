package io.github.detach

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** A short, excluded-from-recents sheet over the home screen. */
class FocusWidgetActivity : Activity() {
    private var picker: FocusDurationPicker? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val lastDuration = FocusSession.lastDurationSeconds(this)
        if (intent.action == START_SAVED || FocusSession.active(this)) {
            start(lastDuration)
            return
        }
        val ui = Ui(this)
        val duration = FocusDurationPicker(this, state?.getLong("draft", lastDuration) ?: lastDuration)
        picker = duration
        val preview = ui.display("", 27f, ui.accent).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        val start = ui.primary("Start Focus") { start(duration.seconds) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(12))
            addView(LinearLayout(this@FocusWidgetActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.badge(R.drawable.ic_sessions))
                addView(ui.display("Focus duration", 24f).apply {
                    isAccessibilityHeading = true
                    setPaddingRelative(ui.dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(ui.space(12))
            addView(preview)
            addView(duration.view)
            addView(ui.space(16))
            addView(start)
            addView(ui.quiet("Cancel") { finish() })
        }
        duration.onChange {
            preview.text = FocusSession.durationText(it)
            start.isEnabled = it > 0L
        }
        ui.dialogPage(this, ScrollView(this).apply { addView(content) })
        setFinishOnTouchOutside(true)
    }

    private fun start(seconds: Long) {
        FocusEntry.startOrReturn(this, seconds)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        picker?.let { outState.putLong("draft", it.seconds) }
    }

    companion object {
        const val CHOOSE_DURATION = "io.github.detach.action.CHOOSE_FOCUS_DURATION"
        const val START_SAVED = "io.github.detach.action.START_SAVED_FOCUS"
    }
}
