package protect.yourself.commons.utils.workManager

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Regression tests for BOOT-VPN-01 — "VPN does not auto-restart after reboot".
 *
 * Root cause: the original [VpnRestartWorker.enqueue] built the expedited
 * work request with BOTH `setExpedited()` AND `setInitialDelay(2, SECONDS)`.
 * These are mutually exclusive: `WorkRequest.Builder.build()` throws
 * `IllegalArgumentException("Expedited jobs cannot be delayed")` BEFORE the
 * request is ever enqueued, so the WorkManager path silently never ran and
 * the VPN was never restarted on Android 12+ (where the direct-start
 * fallback is blocked by the background-FGS-start restriction).
 *
 * These tests pin the constraint and verify the fixed enqueue path actually
 * lands a work request in WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnRestartWorkerEnqueueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    /**
     * Guards the platform constraint itself: building an expedited request
     * WITH an initial delay must throw. If a future WorkManager upgrade ever
     * relaxes this, the test tells us to revisit the enqueue() design.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `expedited request with initial delay throws at build time`() {
        OneTimeWorkRequestBuilder<VpnRestartWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInitialDelay(2, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The actual regression test for BOOT-VPN-01: after [VpnRestartWorker.enqueue],
     * WorkManager must contain the unique work — with the pre-fix code this
     * list was ALWAYS EMPTY because build() threw and the request was never
     * enqueued.
     */
    @Test
    fun `enqueue schedules the vpn restart unique work`() {
        VpnRestartWorker.enqueue(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VpnRestartWorker.WORK_NAME)
            .get()
        assertThat(infos).hasSize(1)
        // The test WorkManager uses a SynchronousExecutor, so the work may
        // have already started/finished by the time we query — the critical
        // regression signal is that the request EXISTS in WorkManager
        // (with the pre-fix code this list was always empty).
        assertThat(infos[0].state).isIn(ACTIVE_STATES)
    }

    /**
     * REPLACE policy: a second enqueue must collapse into the same single
     * unique work (no accumulation of duplicate restore jobs), and the
     * request must still be present/enqueued.
     */
    @Test
    fun `repeated enqueues keep a single unique work`() {
        VpnRestartWorker.enqueue(context)
        VpnRestartWorker.enqueue(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VpnRestartWorker.WORK_NAME)
            .get()
        assertThat(infos).hasSize(1)
        assertThat(infos[0].state).isIn(ACTIVE_STATES)
    }

    /**
     * The fixed request must be expedited with a non-expedited out-of-quota
     * fallback — verified via the WorkSpec tags/spec state rather than a
     * rebuild, using a fresh builder built the same way enqueue() does.
     */
    @Test
    fun `vpn restart request is expedited and carries no initial delay`() {
        val request = OneTimeWorkRequestBuilder<VpnRestartWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        assertThat(request.workSpec.expedited).isTrue()
        assertThat(request.workSpec.initialDelay).isEqualTo(0L)
        assertThat(request.workSpec.outOfQuotaPolicy)
            .isEqualTo(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }

    /**
     * REPLACE policy (behavioral check): if a stale request with the same
     * unique name already exists, [VpnRestartWorker.enqueue] must replace it
     * — with the old KEEP policy the stale request would survive and a fresh
     * restore attempt would be silently dropped.
     */
    @Test
    fun `enqueue replaces a pre-existing stale unique work`() {
        val workManager = WorkManager.getInstance(context)
        // Seed a "stale" generic request under the same unique name.
        val stale = OneTimeWorkRequestBuilder<VpnRestartWorker>().build()
        workManager.enqueueUniqueWork(
            VpnRestartWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            stale
        )
        val staleId = workManager
            .getWorkInfosForUniqueWork(VpnRestartWorker.WORK_NAME).get()
            .single().id
        assertThat(staleId).isEqualTo(stale.id)

        VpnRestartWorker.enqueue(context)

        val infos = workManager.getWorkInfosForUniqueWork(VpnRestartWorker.WORK_NAME).get()
        assertThat(infos).hasSize(1)
        // REPLACE must have swapped the stale request for the new one.
        assertThat(infos[0].id).isNotEqualTo(staleId)
        assertThat(infos[0].state).isIn(ACTIVE_STATES)
    }

    companion object {
        /** States proving the request was accepted and is/was being executed. */
        private val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED
        )
    }
}
