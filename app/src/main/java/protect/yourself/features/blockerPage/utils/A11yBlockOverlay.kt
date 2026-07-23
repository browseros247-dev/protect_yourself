package protect.yourself.features.blockerPage.utils

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.ui.CloseGatePolicy
import timber.log.Timber

/**
 * A11Y-OVL-01 (v1.0.76): block surface drawn as a full-screen
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window owned by
 * the accessibility service itself.
 *
 * ## Why this exists (reverse-engineering of NopoX 1.0.53)
 *
 * Our v1.0.70–v1.0.75 lineage avoided drawing ANY UI over the
 * accessibility-management screens after learning the hard way that Android
 * auto-disables an accessibility service (~1–5 s) when the app's block UI
 * covers them. The lineage reference (NopoX) has covered those exact pages
 * for years without kills — the decompiled difference is the window TYPE:
 *
 *  - Our block screen was an **Activity** (a top-level app window) → the
 *    obscuring/consent protection fired and disabled our service.
 *  - NopoX's `PornBlockPage` is a **`TYPE_ACCESSIBILITY_OVERLAY` (2032)**
 *    window added from the service — Android's sanctioned accessibility
 *    drawing channel (TalkBack/Voice Access), exempt from that protection.
 *
 * With this surface, PU-gated protections of our own accessibility-service
 * detail page and the system VPN settings page can COVER the screen (the
 * toggle is unreachable) instead of evicting the user — matching the
 * reference behavior and restoring the v1.0.75 deadline without the kill.
 *
 * ## Mechanics (mirrors the reference 1:1)
 *
 *  - Full-screen (MATCH_PARENT × MATCH_PARENT), gravity TOP,
 *    [PixelFormat.TRANSLUCENT], window animations from android.Dialog.
 *  - flags = `FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_TOUCH_MODAL |
 *    FLAG_NOT_FOCUSABLE` (296) — touchable, swallowing all input to the
 *    blocked page, never stealing focus.
 *  - Sticky singleton: one [show] at a time; re-[show] while visible only
 *    swaps the message (cheap; lets PU land VPN ↔ a11y page messages correctly).
 *    The user dismisses via the same CLOSE-BTN-01 close-gate the activity
 *    uses ([CloseGatePolicy] + cosmetic countdown), after which we leave
 *    the screen via a CATEGORY_HOME intent (so the blocked page doesn't
 *    instantly re-trigger).
 *  - Every step is try/caught and [show] returns false on failure so
 *    callers keep their previous fallback (HOME eviction / activity block).
 *
 * NOTE: this window type must NOT be used from any context other than the
 * accessibility service (adding an a11y overlay requires the service's
 * window token capabilities).
 */
object A11yBlockOverlay {

    private const val TAG = "A11yBlockOverlay"

    /** LayoutParams.flags value used by the reference (LAYOUT_IN_SCREEN|NOT_TOUCH_MODAL|NOT_FOCUSABLE). */
    internal const val OVERLAY_FLAGS =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    @Volatile
    var isShowing: Boolean = false
        private set

    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var countDownTimer: CountDownTimer? = null
    private var scope: CoroutineScope? = null

    /**
     * Shows (or, when already visible, re-targets) the block overlay with
     * [messageResId] as its page message. Returns false if the window could
     * not be added — callers then use their previous fallback.
     */
    fun show(service: AccessibilityService, messageResId: Int): Boolean {
        try {
            if (isShowing && overlayView != null) {
                overlayView?.findViewById<TextView>(R.id.txtPageMessage)
                    ?.setText(messageResId)
                return true
            }
            val windowManager = service.getSystemService(WindowManager::class.java)
                ?: return fail("no WindowManager")
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                gravity = Gravity.TOP
                format = PixelFormat.TRANSLUCENT
                flags = OVERLAY_FLAGS
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                windowAnimations = android.R.style.Animation_Dialog
            }
            val view = LayoutInflater.from(service)
                .inflate(R.layout.page_porn_block, FrameLayout(service))
            view.findViewById<TextView>(R.id.txtPageMessage)?.setText(messageResId)
            // The rating/why/motivation containers are not relevant for
            // settings-page protection; hide them for a clean single-purpose
            // surface. (They stay intact for PornBlockActivity's own usage.)
            runCatching { view.findViewById<View>(R.id.llRatingContainer)?.visibility = View.GONE }
            runCatching { view.findViewById<View>(R.id.txtWhyContainer)?.visibility = View.GONE }

            windowManager.addView(view, params)
            wm = windowManager
            overlayView = view
            lp = params
            isShowing = true
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            wireCloseGate(service)
            Timber.i("$TAG: overlay shown (messageRes=$messageResId)")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "A11yBlockOverlay",
                "shown messageRes=$messageResId"
            )
            return true
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: show failed")
            cleanup()
            return false
        }
    }

    private fun fail(why: String): Boolean {
        Timber.w("$TAG: $why")
        return false
    }

    /**
     * Same CLOSE-BTN-01 semantics as PornBlockActivity: dwell seconds from
     * the DB (single IO read), [CloseGatePolicy] as the single source of
     * truth, cosmetic countdown on the Close label, toast during the dwell.
     */
    private fun wireCloseGate(service: AccessibilityService) {
        val view = overlayView ?: return
        val closeContainer = view.findViewById<View>(R.id.txtCloseContainer) ?: return
        val closeLabel = view.findViewById<TextView>(R.id.txtClose)
        scope?.launch {
            val dwellSeconds = try {
                val db = AppDatabase.getInstance(service)
                SwitchStatusValues(db.switchStatusDao()).getBlockScreenCountDownSeconds()
            } catch (t: Throwable) {
                Timber.w(t, "$TAG: dwell read failed — immediate close")
                0
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val gate = CloseGatePolicy(dwellSeconds)
                closeContainer.isClickable = true
                closeContainer.setOnClickListener {
                    when (val click = gate.onClick()) {
                        is CloseGatePolicy.Click.Close -> handleClose(service)
                        is CloseGatePolicy.Click.Blocked -> Toast.makeText(
                            service,
                            service.getString(R.string.block_screen_close_available_in, click.remainingSeconds),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                if (dwellSeconds > 0 && closeLabel != null) {
                    countDownTimer?.cancel()
                    countDownTimer = object : CountDownTimer(dwellSeconds * 1000L, 1000) {
                        override fun onTick(millisUntilFinished: Long) {
                            val secs = (millisUntilFinished + 999) / 1000
                            closeLabel.text = "${service.getString(R.string.close)} ($secs)"
                        }
                        override fun onFinish() {
                            closeLabel.text = service.getString(R.string.close)
                        }
                    }.start()
                } else {
                    closeLabel?.text = service.getString(R.string.close)
                }
            }
        }
    }

    /** Close button pressed after the gate: remove the window, land on the launcher. */
    private fun handleClose(service: AccessibilityService) {
        hide()
        try {
            service.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: HOME intent after close failed")
        }
    }

    /** Removes the overlay (idempotent). Called from Close and service teardown paths. */
    fun hide() {
        countDownTimer?.cancel()
        countDownTimer = null
        val view = overlayView
        val windowManager = wm
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view)
                Timber.i("$TAG: overlay hidden")
            } catch (t: Throwable) {
                Timber.w(t, "$TAG: removeView failed")
            }
        }
        cleanup()
    }

    private fun cleanup() {
        overlayView = null
        wm = null
        lp = null
        isShowing = false
        scope?.cancel()
        scope = null
    }
}
