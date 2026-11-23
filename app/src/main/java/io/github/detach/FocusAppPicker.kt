package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

/** Reuses the installed-app discovery already used by the payment picker, but saves an allowlist. */
object FocusAppPicker {
    fun show(activity: Activity, saved: () -> Unit) {
        val current = Settings(activity).focusAllowedPackages
        val apps = PaymentAppPicker.loadApps(activity.packageManager, activity.packageName, current, blocking = true)
        val selected = current.toMutableSet()
        val ui = Ui(activity)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
        }
        val checks = mutableListOf<CheckBox>()
        apps.forEach { app ->
            val check = CheckBox(activity).apply {
                text = app.name; textSize = 16f; isChecked = app.info.packageName in selected
                minHeight = ui.dp(52); setPadding(0, 0, 0, 0)
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(app.info.packageName) else selected.remove(app.info.packageName) }
            }
            checks += check
            rows.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.note("Select only the apps you need. Links to any other app return to Focus.").apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8)) })
            addView(ui.quiet("Clear selection") {
                selected.clear()
                checks.forEach { it.isChecked = false }
            }.apply { setPadding(ui.dp(12), 0, ui.dp(12), 0) })
            addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420)))
        }
        AlertDialog.Builder(activity)
            .setTitle("Allowed during Focus")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Settings(activity).focusAllowedPackages = selected
                saved()
            }.create().also { dialog -> dialog.setOnShowListener { Ui(activity).style(dialog) }; dialog.show() }
    }
}
