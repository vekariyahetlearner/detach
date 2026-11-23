package io.github.detach

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonWriter
import java.io.Reader
import java.io.Writer
import java.time.LocalDate
import kotlin.math.abs

internal data class ArchivedUsage(
    val day: Long,
    val pkg: String,
    val label: String,
    val ms: Long,
    val complete: Boolean,
)

internal class UsageArchive(context: Context) : SQLiteOpenHelper(context, "screen-time.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE usage (
            day INTEGER NOT NULL, pkg TEXT NOT NULL, label TEXT NOT NULL,
            ms INTEGER NOT NULL, complete INTEGER NOT NULL, PRIMARY KEY(day,pkg)
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun capture(report: ScreenTimeHistory.Report, selected: Set<String>, installed: Map<String, Pair<String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            selected.forEach { pkg ->
                val app = installed[pkg]
                val label = app?.first ?: labelFor(pkg) ?: pkg
                report.days.forEach { day ->
                    val ms = day.apps[pkg]?.ms ?: 0L
                    val eligible = pkg != Targets.YOUTUBE || report.youtubeTrackedSince?.let { day.start >= it } == true
                    // A missing or newly installed app is not evidence of zero use on an older day.
                    val complete = eligible && day != report.today && day.complete && app?.let { it.second <= day.start } == true
                    if (complete || ms > 0) {
                        merge(db, ArchivedUsage(day.date.toEpochDay(), pkg, label, ms, complete))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun labelFor(pkg: String): String? = readableDatabase.rawQuery(
        "SELECT label FROM usage WHERE pkg = ? ORDER BY day DESC LIMIT 1", arrayOf(pkg),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun range(start: LocalDate, endExclusive: LocalDate): List<ArchivedUsage> =
        readableDatabase.rawQuery(
            "SELECT day,pkg,label,ms,complete FROM usage WHERE day >= ? AND day < ? ORDER BY day",
            arrayOf(start.toEpochDay().toString(), endExclusive.toEpochDay().toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ArchivedUsage(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4) == 1,
                ))
            }
        }

    fun firstDate(): LocalDate? = readableDatabase.rawQuery("SELECT MIN(day) FROM usage", null).use {
        if (it.moveToFirst() && !it.isNull(0)) LocalDate.ofEpochDay(it.getLong(0)) else null
    }

    fun export(output: Writer) {
        JsonWriter(output).apply {
            beginObject().name("format").value("detach-screen-time-v1").name("days").beginArray()
            readableDatabase.rawQuery("SELECT day,pkg,label,ms,complete FROM usage ORDER BY day,pkg", null).use {
                while (it.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    beginArray().value(it.getLong(0)).value(it.getString(1)).value(it.getString(2))
                        .value(it.getLong(3)).value(it.getInt(4) == 1).endArray()
                }
            }
            endArray().endObject()
            flush()
        }
    }

    /** All-or-nothing restore. Existing and imported snapshots are merged, never summed. */
    fun restore(input: Reader): Int {
        val db = writableDatabase
        val json = JsonReader(input)
        val min = LocalDate.of(2000, 1, 1).toEpochDay()
        val max = LocalDate.now().plusDays(1).toEpochDay()
        var rows = 0
        db.beginTransaction()
        try {
            json.beginObject()
            require(json.nextName() == "format" && json.nextString() == "detach-screen-time-v1")
            require(json.nextName() == "days")
            json.beginArray()
            while (json.hasNext()) {
                require(++rows <= 2_000_000)
                json.beginArray()
                val day = json.nextLong()
                val pkg = json.nextString()
                val label = json.nextString()
                val ms = json.nextLong()
                val complete = json.nextBoolean()
                json.endArray()
                require(day in min..max && pkg.length in 1..255 && pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' })
                require(label.length in 1..255 && ms in 0..(26 * 60 * ScreenTimeHistory.MINUTE))
                merge(db, ArchivedUsage(day, pkg, label, ms, complete), restoring = true)
            }
            json.endArray()
            json.endObject()
            require(json.peek() == android.util.JsonToken.END_DOCUMENT)
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    private fun merge(db: SQLiteDatabase, row: ArchivedUsage, restoring: Boolean = false) {
        val values = ContentValues().apply {
            put("day", row.day)
            put("pkg", row.pkg)
            put("label", row.label.take(255))
            put("ms", row.ms)
            put("complete", if (row.complete) 1 else 0)
        }
        if (db.insertWithOnConflict("usage", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0L) return
        // Prefer retaining an observation if a later Android query has lost some of its events.
        val replaceable = if (restoring && row.complete) "" else " AND complete=0"
        db.update("usage", values, "day=? AND pkg=? AND ms<=?$replaceable",
            arrayOf(row.day.toString(), row.pkg, row.ms.toString()))
    }
}

/** Compare matched weekdays for the same set of apps, excluding incomplete observations. */
internal object WeeklyUsage {
    data class Change(val pairs: Int, val oldAverage: Long, val newAverage: Long) {
        val savedPerDay get() = oldAverage - newAverage
        val stable get() = abs(savedPerDay) < 5 * ScreenTimeHistory.MINUTE
    }

    fun compare(today: LocalDate, rows: List<ArchivedUsage>, apps: Set<String>): Change? {
        if (apps.isEmpty()) return null
        val byDay = rows.filter { it.complete && it.pkg in apps }.groupBy { it.day }
        fun completeTotal(day: LocalDate) = byDay[day.toEpochDay()]
            ?.takeIf { records -> records.map { it.pkg }.toSet() == apps }?.sumOf { it.ms }
        val pairs = (1L..7L).mapNotNull { offset ->
            val day = today.minusDays(offset)
            val current = completeTotal(day) ?: return@mapNotNull null
            val previous = completeTotal(day.minusWeeks(1)) ?: return@mapNotNull null
            previous to current
        }
        if (pairs.size < 4) return null
        return Change(pairs.size, pairs.sumOf { it.first } / pairs.size, pairs.sumOf { it.second } / pairs.size)
    }
}
