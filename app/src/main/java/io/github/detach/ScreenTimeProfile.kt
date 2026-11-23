package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

internal data class ScreenTimeProfile(val name: String, val birthDate: LocalDate) {
    fun ageOn(today: LocalDate): Int {
        val years = today.year - birthDate.year
        return (years - if (today.isBefore(birthDate.plusYears(years.toLong()))) 1 else 0).coerceAtLeast(0)
    }

    fun yearsUntil70(today: LocalDate): Double =
        ChronoUnit.DAYS.between(today, birthDate.plusYears(70)).coerceAtLeast(0) / ScreenTimeHistory.DAYS_PER_YEAR

    companion object {
        fun validDate(date: LocalDate, today: LocalDate) = date in today.minusYears(120)..today
    }
}

internal fun editScreenTimeProfile(activity: Activity, prefs: ScreenTimePreferences, saved: () -> Unit) {
    val ui = Ui(activity)
    val current = prefs.profile
    val today = LocalDate.now()
    var birthDate = current?.birthDate
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val name = ui.style(EditText(activity)).apply {
        hint = "Name or nickname"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(40))
        setText(current?.name.orEmpty())
    }
    lateinit var dateButton: android.widget.Button
    fun updateDate() {
        dateButton.text = birthDate?.format(dateFormat) ?: "Choose date of birth"
        dateButton.contentDescription = "Date of birth. ${dateButton.text}"
    }
    dateButton = ui.secondary("") {
        val initial = birthDate ?: today.minusYears(25)
        val zone = ZoneId.systemDefault()
        DatePickerDialog(activity, { _, year, month, day ->
            birthDate = LocalDate.of(year, month + 1, day)
            updateDate()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            setTitle("Date of birth")
            datePicker.minDate = today.minusYears(120).atStartOfDay(zone).toInstant().toEpochMilli()
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }
    updateDate()
    val fields = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(22), 0, ui.dp(22), ui.dp(8))
        addView(ui.note("Your name personalizes the report. Your date of birth keeps your age and projection up to date. Both stay on this phone."))
        addView(name)
        addView(ui.space(8))
        addView(dateButton)
    }
    val builder = AlertDialog.Builder(activity)
        .setTitle(if (current == null) "Personalize your analysis" else "Edit analysis profile")
        .setView(android.widget.ScrollView(activity).apply { addView(fields) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton(if (current == null) "Continue" else "Save", null)
    if (current != null) builder.setNeutralButton("Remove profile") { _, _ ->
        prefs.profile = null
        saved()
    }
    val dialog = builder.create()
    dialog.setOnShowListener {
        ui.style(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = name.text.toString().trim()
            val date = birthDate
            when {
                label.isEmpty() -> {
                    name.error = "Enter a name or nickname."
                    name.requestFocus()
                }
                date == null || !ScreenTimeProfile.validDate(date, today) ->
                    android.widget.Toast.makeText(activity, "Choose a valid date of birth.", android.widget.Toast.LENGTH_SHORT).show()
                else -> {
                    prefs.profile = ScreenTimeProfile(label, date)
                    dialog.dismiss()
                    saved()
                }
            }
        }
    }
    dialog.show()
}
