package protect.yourself.features.appPasswordPage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.appPasswordPage.identifiers.AppLockType

/**
 * Regression tests for LOCKSESSION-01/02/03 (v1.0.65) —
 * "password field not reset when returning to the app".
 *
 * ROOT CAUSE being pinned: AppLockViewModel is scoped to (retained by) the
 * host Activity and its [AppLockState] survived every lock re-engagement.
 * Returning to the app recomposed the lock screen with the previous
 * session's `input` still present (the reported pre-filled field), stale
 * `isUnlocked=true` (which ALSO ignored all pattern dots and full PIN pads,
 * hard-locking users out), and stale error/shake state.
 *
 * These tests pin the new session-reset API:
 *  - `beginLockSession()` — full reset on every lock-screen entry
 *  - `onForegroundReturn()` — light reset on foreground return while already
 *    on the lock screen (composition never disposed)
 *  - `beginSetupSession()` — fresh setup session on setup-page entry
 *  - success paths clear the plaintext input immediately
 *
 * Implementation note: runBlocking + UnconfinedTestDispatcher-as-Main +
 * [awaitState] polling. The ViewModel's coroutines hop onto REAL
 * dispatchers/executors midway (PBKDF2 on Dispatchers.Default, Room IO),
 * so a virtual-time StandardTestDispatcher + advanceUntilIdle() asserted
 * before the real work completed. Polling the StateFlow for the awaited
 * value in real time is robust against CI load variance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockSessionResetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Neutralize the persisted rate limiter from any prior test class —
        // the manager singleton reads it once at construction.
        context.getSharedPreferences("app_lock_rate_limiter", 0)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AppLockViewModel =
        AppLockViewModel(context, AppDatabase.getInstance(context))

    /** Poll the condition in real time until it holds or the deadline passes. */
    private suspend fun awaitState(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(40)
        }
    }

    /**
     * The core reported bug: partial input left behind when the app is
     * backgrounded + returned must be cleared when a new lock session begins.
     */
    @Test
    fun `beginLockSession clears leftover partial input`() = runBlocking {
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.onInputChange("73")
        assertThat(viewModel.state.value.input).isEqualTo("73")

        viewModel.beginLockSession()
        awaitState { !viewModel.state.value.isLoading }

        assertThat(viewModel.state.value.input).isEmpty()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isUnlocked).isFalse()
    }

    /**
     * After a successful unlock, the plaintext input must be wiped from the
     * retained state immediately — not just when a new session begins.
     */
    @Test
    fun `successful unlock clears the plaintext input from state`() = runBlocking {
        val manager = AppLockManager.getInstance(context)
        manager.setLock(AppLockType.PIN, "1234")
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        var unlocked = false
        viewModel.tryUnlockWithInput("1234") { unlocked = true }
        awaitState { unlocked }

        assertThat(unlocked).isTrue()
        assertThat(viewModel.state.value.isUnlocked).isTrue()
        assertThat(viewModel.state.value.input).isEmpty()
    }

    /**
     * The latent hard-lock: a stale isUnlocked=true from the previous session
     * made the PIN pad (input.length >= 4) and EVERY pattern dot ignore taps
     * — the user could not enter any credential until the app was killed.
     * A new session must reset isUnlocked and accept input again.
     */
    @Test
    fun `new session after previous unlock accepts entry again`() = runBlocking {
        val manager = AppLockManager.getInstance(context)
        manager.setLock(AppLockType.PIN, "1234")
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.tryUnlockWithInput("1234") { }
        awaitState { viewModel.state.value.isUnlocked }
        assertThat(viewModel.state.value.isUnlocked).isTrue()

        // App backgrounded + returned → re-lock → new session begins.
        viewModel.beginLockSession()
        awaitState { !viewModel.state.value.isLoading }

        assertThat(viewModel.state.value.isUnlocked).isFalse()
        assertThat(viewModel.state.value.input).isEmpty()
        // PIN pad / pattern dots respond again (guards read live state).
        viewModel.onInputChange("5")
        assertThat(viewModel.state.value.input).isEqualTo("5")
    }

    /**
     * Returning to the foreground WHILE still on the lock screen (the
     * composition was never disposed, so beginLockSession does not fire) must
     * still clear the input field.
     */
    @Test
    fun `onForegroundReturn clears input while on lock screen`() = runBlocking {
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.onInputChange("19")
        viewModel.onForegroundReturn()

        assertThat(viewModel.state.value.input).isEmpty()
        assertThat(viewModel.state.value.error).isNull()
    }

    /**
     * onForegroundReturn must not disturb a just-completed unlock that is
     * racing to AppState.MAIN.
     */
    @Test
    fun `onForegroundReturn does not disturb a completed unlock`() = runBlocking {
        val manager = AppLockManager.getInstance(context)
        manager.setLock(AppLockType.PIN, "1234")
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.tryUnlockWithInput("1234") { }
        awaitState { viewModel.state.value.isUnlocked }
        assertThat(viewModel.state.value.isUnlocked).isTrue()

        viewModel.onForegroundReturn()

        assertThat(viewModel.state.value.isUnlocked).isTrue()
    }

    /**
     * The lock configuration is re-read for every new session so a lock-type
     * change made inside the app is honored at the next re-lock.
     */
    @Test
    fun `beginLockSession reloads lock configuration`() = runBlocking {
        val manager = AppLockManager.getInstance(context)
        manager.setLock(AppLockType.PASSWORD, "hunter42")
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.beginLockSession()
        awaitState { !viewModel.state.value.isLoading }

        assertThat(viewModel.state.value.lockType).isEqualTo(AppLockType.PASSWORD)
    }

    /**
     * Lockout state must be synchronized at session start — a user returning
     * while locked out must see the countdown immediately, not a stale value.
     */
    @Test
    fun `beginLockSession refreshes lockout state immediately`() = runBlocking {
        val viewModel = newViewModel()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.beginLockSession()
        awaitState { !viewModel.state.value.isLoading }

        assertThat(viewModel.state.value.isLockedOut).isFalse()
        assertThat(viewModel.state.value.lockoutRemainingMs).isEqualTo(0L)
    }

    /**
     * LOCKSESSION-03: mid-setup leftovers (typed first entry, reached CONFIRM
     * step) must be cleared when the setup page is re-entered — otherwise the
     * user lands on a confirmation screen for a credential they no longer see.
     */
    @Test
    fun `beginSetupSession clears mid-setup progress`() = runBlocking {
        val setupViewModel = AppLockSetupViewModel(context, AppDatabase.getInstance(context))
        awaitState { !setupViewModel.state.value.isLoading }

        setupViewModel.selectLockType(AppLockType.PIN)
        setupViewModel.setFirstEntry("1234")
        setupViewModel.proceedToConfirm()
        assertThat(setupViewModel.state.value.setupStep).isEqualTo(SetupStep.CONFIRM)
        assertThat(setupViewModel.state.value.firstEntry).isEqualTo("1234")

        // App backgrounded mid-setup, setup page re-entered → fresh session.
        setupViewModel.beginSetupSession()
        awaitState { !setupViewModel.state.value.isLoading }

        assertThat(setupViewModel.state.value.setupStep).isEqualTo(SetupStep.NONE)
        assertThat(setupViewModel.state.value.firstEntry).isEmpty()
        assertThat(setupViewModel.state.value.secondEntry).isEmpty()
        assertThat(setupViewModel.state.value.selectedLockType).isNull()
        assertThat(setupViewModel.state.value.error).isNull()
    }
}
