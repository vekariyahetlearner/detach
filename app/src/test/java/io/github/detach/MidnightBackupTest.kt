package io.github.detach

import android.app.job.JobScheduler
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class MidnightBackupTest {
    @Test fun `a successful backup schedules tomorrow while a missed day is eligible now`() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val id = UsageArchiveJobs.BACKUP_JOB_ID
        assertNull(scheduler.getPendingJob(id))
        UsageBackup.configure(context, Uri.parse("content://example/tree/test"))
        val settings = context.getSharedPreferences("usage_backup", Context.MODE_PRIVATE)
        settings.edit().putString("saved_day", LocalDate.now().toString()).commit()
        val now = System.currentTimeMillis()
        UsageArchiveJobs.scheduleBackup(context)
        val midnight = scheduler.getPendingJob(id)!!
        val delay = UsageArchiveJobs.nextMidnight(now, ZoneId.systemDefault()) - now
        assertTrue(midnight.isPersisted)
        assertTrue(abs(midnight.minLatencyMillis - delay) < 5_000L)

        settings.edit().putString("saved_day", LocalDate.now().minusDays(1).toString()).commit()
        UsageArchiveJobs.scheduleBackup(context)
        assertEquals(0L, scheduler.getPendingJob(id)!!.minLatencyMillis)

        UsageBackup.disable(context)
        UsageArchiveJobs.scheduleBackup(context)
        assertNull(scheduler.getPendingJob(id))
    }
}
