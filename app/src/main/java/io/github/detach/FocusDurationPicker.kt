package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast

/** The same HH:MM:SS controls are used from Sessions and the home-screen widget. */
internal class FocusDurationPicker(activity: Activity, initialSeconds: Long) {
    private val ui = Ui(activity)
    private val changeListeners = mutableListOf<(Long) -> Unit>()
    private val wheels = listOf(
        wheel(activity, "Hours", 23, (initialSeconds / 3600).toInt()),
        wheel(activity, "Minutes", 59, (initialSeconds / 60 % 60).toInt()),
        wheel(activity, "Seconds", 59, (initialSeconds % 60).toInt()),
    )
    val seconds: Long get() = wheels[0].value * 3600L + wheels[1].value * 60L + wheels[2].value
    val view = LinearLayout(activity).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        for (picker in wheels) {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(136)))
                addView(ui.note(picker.contentDescription).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun onChange(listener: (Long) -> Unit) {
        changeListeners.add(listener)
        listener(seconds)
    }

    private fun wheel(activity: Activity, unit: String, max: Int, initial: Int) = NumberPicker(activity).apply {
        minValue = 0
        maxValue = max
        wrapSelectorWheel = true
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        setFormatter { "%02d".format(it) }
        value = initial.coerceIn(0, max)
        contentDescription = unit
        if (Build.VERSION.SDK_INT >= 29) {
            textColor = ui.fg
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
            selectionDividerHeight = ui.dp(2)
        }
        setOnValueChangedListener { _, _, _ -> changeListeners.forEach { it(seconds) } }
    }

    companion object {
        fun show(activity: Activity, onStart: (Long) -> Unit) {
            val ui = Ui(activity)
            val picker = FocusDurationPicker(activity, FocusSession.lastDurationSeconds(activity))
            picker.view.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8))
            val dialog = AlertDialog.Builder(activity).setTitle("Focus duration")
                .setView(picker.view).setNegativeButton("Cancel", null).setPositiveButton("Start Focus", null).create()
            dialog.setOnShowListener {
                ui.style(dialog)
                val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                picker.onChange { start.isEnabled = it > 0L }
                start.setOnClickListener {
                    val seconds = picker.seconds
                    if (seconds > 0L) {
                        dialog.dismiss()
                        onStart(seconds)
                    }
                }
            }
            dialog.show()
        }
    }
}

/** Widget and app entry points have the same checks. Opening Focus never restarts an active timer. */
internal object FocusEntry {
    fun startOrReturn(activity: Activity, seconds: Long) {
        if (!DetachService.isRunning || !Pause.isServiceEnabled(activity) || Pause.isPending(activity)) {
            if (Pause.isPending(activity)) {
                Toast.makeText(activity, "Resume protection before starting Focus.", Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(activity, MainActivity::class.java))
            } else {
                Toast.makeText(activity, "Turn on Accessibility protection before starting Focus.", Toast.LENGTH_LONG).show()
                runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            return
        }
        if (!FocusSession.active(activity)) {
            if (seconds !in 1..FocusSession.MAX_PICKER_SECONDS) return
            FocusSession.startSeconds(activity, seconds)
            DetachService.refreshSettings()
            FocusWidgetProvider.updateAll(activity)
        }
        DetachService.openFocusHome()
    }
}
