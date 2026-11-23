package io.github.detach

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.DocumentsContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object UsageArchiveJobs {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "usage-archive").apply { isDaemon = true } }
    private const val JOB_ID = 7241
    internal const val BACKUP_JOB_ID = 7242
    private const val INTERVAL = 6 * 60 * ScreenTimeHistory.MINUTE
    private const val MIDNIGHT = "local_midnight"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        runCatching {
            if (!ScreenTimePreferences(context).enabled) {
                scheduler.cancel(JOB_ID)
            } else if (scheduler.getPendingJob(JOB_ID) == null) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UsageArchiveService::class.java))
                    .setPeriodic(INTERVAL).setPersisted(true).setRequiresStorageNotLow(true).build())
            }
            scheduleBackup(context)
        }
    }

    fun nextMidnight(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone)
        .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun scheduleBackup(context: Context, force: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!UsageBackup.configured(context)) {
            scheduler.cancel(BACKUP_JOB_ID)
            return
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val next = nextMidnight(now, zone)
        val pending = scheduler.getPendingJob(BACKUP_JOB_ID)
        val alreadySaved = UsageBackup.savedOn(context, today)
        val target = if (alreadySaved) next else today.atStartOfDay(zone).toInstant().toEpochMilli()
        if (!force && pending?.extras?.getLong(MIDNIGHT) == target) return
        val extras = PersistableBundle().apply { putLong(MIDNIGHT, target) }
        scheduler.schedule(JobInfo.Builder(BACKUP_JOB_ID, ComponentName(context, UsageArchiveService::class.java))
            .setMinimumLatency((target - now).coerceAtLeast(0L))
            .setPersisted(true)
            .setRequiresStorageNotLow(true)
            .setExtras(extras)
            .build())
    }

    fun collect(context: Context) {
        val settings = ScreenTimePreferences(context)
        if (settings.enabled && settings.distractingApps.isNotEmpty() && PauseForegroundMonitor.hasUsageAccess(context)) {
            runCatching { ScreenTimePage.capture(context, settings.distractingApps) }
        }
    }
}

class UsageArchiveService : JobService() {
    private val active = mutableMapOf<Int, JobParameters>()
    private val work = mutableMapOf<Int, Future<*>>()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        active[id] = params
        work[id] = UsageArchiveJobs.worker.submit {
            val backup = id == UsageArchiveJobs.BACKUP_JOB_ID
            var retry = false
            try {
                UsageArchiveJobs.collect(applicationContext)
                if (backup && !Thread.currentThread().isInterrupted && UsageBackup.configured(applicationContext)) {
                    retry = !UsageBackup.save(applicationContext, force = false)
                }
            } finally {
                main.post {
                    if (active[id] === params) {
                        active.remove(id)
                        work.remove(id)
                        jobFinished(params, retry)
                        if (backup && !retry) runCatching { UsageArchiveJobs.scheduleBackup(applicationContext, force = true) }
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)
        work.remove(params.jobId)?.cancel(true)
        return true
    }
}

class UsageArchiveClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            runCatching { UsageArchiveJobs.scheduleBackup(context, force = true) }
        }
    }
}

internal object UsageBackup {
    private fun prefs(context: Context) = context.getSharedPreferences("usage_backup", Context.MODE_PRIVATE)
    fun configured(context: Context) = prefs(context).contains("folder")
    fun lastSaved(context: Context): Long = prefs(context).getLong("saved_at", 0L)
    fun failed(context: Context): Boolean = prefs(context).getBoolean("failed", false)
    fun savedOn(context: Context, day: LocalDate) = !failed(context) && prefs(context).getString("saved_day", null) == day.toString()

    fun configure(context: Context, folder: Uri) {
        val settings = prefs(context)
        val previous = settings.getString("folder", null)
        if (previous != folder.toString()) {
            if (previous != null) release(context, Uri.parse(previous))
            settings.edit().putString("folder", folder.toString()).remove("a").remove("b")
                .remove("last_slot").remove("saved_at").remove("saved_day").remove("failed").apply()
        }
    }

    fun disable(context: Context) {
        val settings = prefs(context)
        settings.getString("folder", null)?.let { release(context, Uri.parse(it)) }
        settings.edit().clear().apply()
    }

    private fun release(context: Context, uri: Uri) = runCatching {
        context.contentResolver.releasePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Alternate files so an interrupted write cannot destroy the last successful backup. */
    fun save(context: Context, force: Boolean): Boolean {
        val settings = prefs(context)
        val folder = settings.getString("folder", null)?.let(Uri::parse) ?: return false
        val today = LocalDate.now()
        if (!force && savedOn(context, today)) return true
        val slot = if (settings.getString("last_slot", null) == "a") "b" else "a"
        val resolver = context.contentResolver
        val saved = runCatching {
            val target = settings.getString(slot, null)?.let(Uri::parse) ?: run {
                val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
                val file = DocumentsContract.createDocument(resolver, parent, "application/json", "Detach-screen-time-$slot.json")
                    ?: error("Could not create backup")
                settings.edit().putString(slot, file.toString()).commit()
                file
            }
            val output = try {
                resolver.openOutputStream(target, "wt")
            } catch (error: java.io.FileNotFoundException) {
                settings.edit().remove(slot).apply()
                throw error
            } ?: error("Could not open backup")
            output.bufferedWriter().use { writer -> UsageArchive(context).use { it.export(writer) } }
            settings.edit().putString("last_slot", slot).putString("saved_day", today.toString())
                .putLong("saved_at", System.currentTimeMillis()).putBoolean("failed", false).commit()
        }.getOrDefault(false)
        if (!saved) settings.edit().putBoolean("failed", true).apply()
        return saved
    }

    fun restore(context: Context, source: Uri): Int {
        val input = context.contentResolver.openInputStream(source) ?: error("Could not read backup")
        return input.bufferedReader().use { reader -> UsageArchive(context).use { it.restore(reader) } }
    }
}
