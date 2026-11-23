package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import android.provider.Settings as SystemSettings

/**
 * A breather is deliberately general: it unblocks every Detach restriction for a short,
 * authenticated window, then protection returns on its own. Length is chosen on the native
 * Android number wheel.
 */
object BreatherControls {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = BreatherBudget.MAX_MINUTES
    const val DEFAULT_MINUTES = 10

    fun build(activity: Activity, onChanged: () -> Unit): View {
        val ui = Ui(activity)
        val column = ui.card(16).apply {
            tag = "breather-controls"
        }
        fun render() {
            column.removeAllViews()
            val remaining = Breather.remainingMs(activity)
            val budget = BreatherBudget.status(activity)
            column.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.badge(R.drawable.ic_pause))
                addView(ui.display("Breather", 23f).apply {
                    isAccessibilityHeading = true
                    setPaddingRelative(ui.dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            column.addView(ui.space(10))
            column.addView(ui.text(if (budget == null) "Allowance unavailable" else "Remaining today: ${budget.available} min", 17f, strong = true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            if (budget != null && budget.dailyLimit > 0) {
                column.addView(ui.space(8))
                column.addView(ui.meter().apply { progress = budget.available * 1000 / budget.dailyLimit })
                column.addView(ui.space(5))
            }
            column.addView(ui.note(if (budget == null) "Breathers and restriction changes are temporarily locked while the daily allowance cannot be verified. After a reboot, leave the phone on to allow the safety check to finish."
                else "Daily allowance: ${budget.dailyLimit} min").apply {
                setPadding(0, ui.dp(2), 0, 0)
            })
            if (budget != null && remaining > 0) column.addView(ui.note("Breather active · ${(remaining + 59_999L) / 60_000L} min left"))
            else if (budget != null && budget.cooldownRemainingMs > 0) column.addView(ui.note("Cooldown: ${(budget.cooldownRemainingMs + 59_999L) / 60_000L} min left"))
            column.addView(ui.space(14))
            column.addView(ui.primary(if (remaining > 0) "Extend breather" else "Start a breather") {
                if (!Auth.available(activity)) {
                    authenticationRequired(activity)
                } else {
                    choose(activity) {
                        render()
                        onChanged()
                    }
                }
            }.apply { isEnabled = budget != null && budget.available > 0 && budget.cooldownRemainingMs == 0L },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(ui.space(8))
            column.addView(ui.secondary("Daily allowance") { chooseDailyAllowance(activity) { render(); onChanged() } }.apply {
                isEnabled = budget != null && budget.dailyChanged.not()
                contentDescription = "Set tomorrow's daily breather allowance"
            })
            val secondaryActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "breather-secondary-actions"
            }
            secondaryActions.addView(ui.quiet(if (budget?.cooldownEnabled == true) "Cooldown: on" else "Cooldown: off") {
                val current = budget ?: return@quiet
                if (BreatherBudget.setCooldown(activity, !current.cooldownEnabled)) { render(); onChanged() }
                else Toast.makeText(activity, "Cooldown can be changed before today's first breather.", Toast.LENGTH_LONG).show()
            }.apply {
                isEnabled = budget != null && budget.used == 0 && budget.cooldownRemainingMs == 0L
                contentDescription = "15 minute cooldown ${if (budget?.cooldownEnabled == true) "on" else "off"}"
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(4) })
            secondaryActions.addView(ui.quiet("History") { showHistory(activity) }.apply {
                contentDescription = "Today's breather history"
                isEnabled = budget != null
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4) })
            column.addView(secondaryActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(8)
            })
            if (budget != null) column.addView(ui.note(when {
                budget.nextLimit != budget.dailyLimit -> "Tomorrow's allowance: ${budget.nextLimit} min"
                budget.available == 0 -> "Today used up · Resets at midnight"
                budget.dailyChanged -> "Daily allowance set for tomorrow"
                else -> "Change once per day · changes start tomorrow"
            }).apply { setPadding(0, ui.dp(8), 0, 0) })
            if (remaining > 0) {
                column.addView(ui.space(8))
                column.addView(ui.quiet("End breather now") {
                    Breather.end(activity)
                    render()
                    onChanged()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        render()
        return column
    }

    private fun authenticationRequired(activity: Activity) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("Authentication required")
            .setMessage(if (Build.VERSION.SDK_INT >= 29) {
                "A breather needs your fingerprint or Detach password every time. Set a Detach password in the app’s password settings, or enroll biometrics in Android settings. A phone PIN alone cannot start a breather."
            } else {
                "On this Android version, a breather needs your Detach password every time. Set a Detach password in the app’s password settings first. A phone PIN alone cannot start a breather."
            })
            .setNegativeButton("Back", null)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setPositiveButton("Enroll biometrics") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(SystemSettings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        SystemSettings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_WEAK,
                    )
                } else {
                    Intent(SystemSettings.ACTION_SECURITY_SETTINGS)
                }
                runCatching { activity.startActivity(intent) }.onFailure {
                    Toast.makeText(activity, "Open Android security settings to enroll biometrics, or set a Detach password.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener { Ui(activity).style(dialog) }
        dialog.show()
    }

    /** The native wheel used to pick a breather length; exposed for tests. */
    internal fun minutePicker(activity: Activity, ui: Ui, initial: Int, maximum: Int = MAX_MINUTES, minimum: Int = MIN_MINUTES) = NumberPicker(activity).apply {
        tag = "breather-minutes"
        minValue = minimum.coerceIn(0, MAX_MINUTES)
        maxValue = maximum.coerceIn(minValue, MAX_MINUTES)
        value = initial.coerceIn(minValue, maxValue)
        wrapSelectorWheel = false
        // Wheel-only: avoids an unexpected keyboard and keeps values always in range.
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "Breather length in minutes"
        if (Build.VERSION.SDK_INT >= 29) {
            textColor = ui.fg
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
            selectionDividerHeight = ui.dp(2)
        }
    }

    private fun endsAt(activity: Activity, minutes: Int): String =
        "Ends at ${DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + minutes * 60_000L))}"

    private fun choose(activity: Activity, onChanged: () -> Unit) {
        val ui = Ui(activity)
        val available = BreatherBudget.status(activity)?.available ?: 0
        if (available < 1) return
        val remaining = Breather.remainingMs(activity)
        val initial = if (remaining > 0) ((remaining + 59_999L) / 60_000L).toInt() else DEFAULT_MINUTES
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8))
        }
        content.addView(ui.note(if (remaining > 0) {
            "Pick a new length. It replaces the time left and the full new duration counts toward today's allowance."
        } else {
            "Instagram and YouTube feature limits pause temporarily; whole-app blocks stay on. The full chosen time counts even if you end early. If enabled, cooldown starts after the scheduled end."
        }))
        val picker = minutePicker(activity, ui, initial, available)
        val unit = ui.text("min", 18f, ui.sub, strong = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wheel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, ui.dp(4))
            addView(picker, LinearLayout.LayoutParams(ui.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(unit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(4)
            })
        }
        content.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val summary = ui.text(endsAt(activity, picker.value), 14f, ui.sub).apply {
            gravity = Gravity.CENTER
            tag = "breather-ends"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        picker.setOnValueChangedListener { _, _, value -> summary.text = endsAt(activity, value) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (remaining > 0) "Extend your breather" else "Take a breather")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Authenticate & start", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            ui.style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = picker.value.coerceIn(MIN_MINUTES, MAX_MINUTES)
                dialog.dismiss()
                if (!Auth.available(activity)) {
                    authenticationRequired(activity)
                    return@setOnClickListener
                }
                Breather.confirmAndBegin(activity, minutes, Breather.Feature.entries.toSet()) { started ->
                    if (started) {
                        onChanged()
                        BreatherIntro.play(activity, minutes)
                    } else {
                        Toast.makeText(activity, "Breather not started. No new access was granted.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun chooseDailyAllowance(activity: Activity, onChanged: () -> Unit) {
        val status = BreatherBudget.status(activity) ?: return
        if (status.dailyChanged) return
        val ui = Ui(activity)
        val picker = minutePicker(activity, ui, status.dailyLimit, MAX_MINUTES, minimum = 0).apply {
            contentDescription = "Tomorrow's daily breather allowance in minutes"
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            addView(ui.note("Choose tomorrow's daily allowance from 0 to 60 minutes. Today's allowance will not change."))
            addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
        val preview = ui.text("Today: ${status.dailyLimit} min · Tomorrow: ${picker.value} min", 14f, strong = true).apply {
            gravity = Gravity.CENTER
            tag = "breather-limit-preview"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        content.addView(preview)
        content.addView(ui.note("You can change this once today. The new allowance begins at local midnight.").apply {
            setPadding(0, ui.dp(8), 0, 0)
        })
        picker.setOnValueChangedListener { _, _, value -> preview.text = "Today: ${status.dailyLimit} min · Tomorrow: $value min" }
        val dialog = AlertDialog.Builder(activity).setTitle("Daily allowance").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                if (BreatherBudget.setDailyLimit(activity, picker.value)) onChanged()
                else Toast.makeText(activity, "Could not save daily limit.", Toast.LENGTH_LONG).show()
            }.create()
        dialog.setOnShowListener { ui.style(dialog) }
        dialog.show()
    }

    private fun showHistory(activity: Activity) {
        val entries = BreatherBudget.history(activity)
        val format = DateFormat.getTimeFormat(activity)
        val message = if (entries.isEmpty()) "No breathers recorded today." else entries.joinToString("\n") {
            "${format.format(Date(it.startedAt))} · ${it.minutes} min charged"
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Today's breathers").setMessage(message)
            .setPositiveButton("Done", null).create()
        dialog.setOnShowListener { Ui(activity).style(dialog) }
        dialog.show()
    }
}
