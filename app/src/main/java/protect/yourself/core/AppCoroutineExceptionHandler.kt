package protect.yourself.core

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import protect.yourself.features.crashLog.CrashLogger
import protect.yourself.features.crashLog.CrashSeverity
import timber.log.Timber

/**
 * AppCoroutineExceptionHandler — global CoroutineExceptionHandler that
 * routes uncaught coroutine exceptions to CrashLogger with full
 * diagnostic context (scope name, dispatcher, job, coroutine context).
 *
 * # Why this exists
 *
 * Before this handler, the app had 8 `CoroutineScope(SupervisorJob() + ...)`
 * sites with ZERO `CoroutineExceptionHandler`s installed. Consequences:
 *
 *  1. `async { throw ... }` without `await()` — the exception is stored in
 *     the `Deferred` and silently lost (never delivered to any handler).
 *  2. `launch { throw ... }` — exceptions propagate to the thread's
 *     `UncaughtExceptionHandler` (captured by our global handler), but
 *     without coroutine-specific context (which scope, which dispatcher,
 *     which job) — making root-cause analysis much harder.
 *
 * # Installation
 *
 * Install in any `CoroutineScope` by adding the handler to the context:
 *
 * ```
 * val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + AppCoroutineExceptionHandler(scopeName = "MyService"))
 * ```
 *
 * Or use the [appCoroutineScope] factory for a ready-to-use scope with the
 * handler installed.
 *
 * # Behaviour
 *
 * - `CancellationException` is re-thrown (NOT logged) — it's a normal
 *   control-flow signal, not an error.
 * - Other throwables are logged to CrashLogger with severity ERROR,
 *   tag = "Coroutine", and extraContext containing scope name, dispatcher,
 *   job, and coroutine context.
 * - The handler does NOT re-throw — it logs and swallows, so the coroutine
 *   is considered handled. This prevents the exception from propagating
 *   to the thread's UncaughtExceptionHandler (which would also log it,
 *   causing duplicate entries).
 */
class AppCoroutineExceptionHandler(
    private val scopeName: String,
    private val appContext: Context? = null
) : CoroutineExceptionHandler {

    override val key: CoroutineExceptionHandler.Key = CoroutineExceptionHandler

    override fun handleException(coroutineContext: kotlin.coroutines.CoroutineContext, exception: Throwable) {
        // CancellationException is a normal control-flow signal — never log it.
        if (exception is CancellationException) {
            throw exception
        }

        val crashLogger = if (appContext != null) {
            CrashLogger.getInstance(appContext)
        } else {
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()
        }

        val dispatcher = coroutineContext[CoroutineDispatcher]?.toString() ?: "unknown"
        val job = coroutineContext[Job]
        val jobId = job?.hashCode()?.toString(16) ?: "no-job"

        val extraContext = mutableMapOf(
            "scope" to scopeName,
            "dispatcher" to dispatcher,
            "jobId" to jobId,
            "coroutineContext" to coroutineContext.toString()
        )

        // Also log to Timber so it appears in logcat immediately
        Timber.e(exception, "Coroutine exception in scope '$scopeName' (dispatcher=$dispatcher, job=$jobId)")

        try {
            crashLogger?.logThrowable(
                throwable = exception,
                severity = CrashSeverity.ERROR,
                tag = "Coroutine:$scopeName",
                message = "Uncaught coroutine exception in scope '$scopeName'",
                extraContext = extraContext
            )
        } catch (_: Throwable) {
            // Never let the crash logger itself cause issues
        }
    }
}

/**
 * Create a CoroutineScope with [AppCoroutineExceptionHandler] installed.
 *
 * Usage:
 * ```
 * private val serviceScope = appCoroutineScope("MyService", Dispatchers.IO)
 * ```
 */
fun appCoroutineScope(
    scopeName: String,
    dispatcher: CoroutineDispatcher,
    context: Context? = null
): CoroutineScope {
    val handler = AppCoroutineExceptionHandler(scopeName, context)
    return CoroutineScope(SupervisorJob() + dispatcher + handler)
}
