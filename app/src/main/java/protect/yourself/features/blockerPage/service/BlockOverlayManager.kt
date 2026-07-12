package protect.yourself.features.blockerPage.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.utils.BlockScreenImageLoader
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BlockOverlayManager — shows a non-dismissible WindowManager overlay on top
 * of the offending app, then runs a 500ms timer that presses
 * `GLOBAL_ACTION_HOME ×5 + GLOBAL_ACTION_BACK ×1` to actually kill the
 * offending activity underneath.
 *
 * ## Why an overlay, not an Activity
 *
 * NopoX (the reference implementation, decompiled via jadx) uses a
 * `WindowManager` overlay of type `TYPE_APPLICATION_OVERLAY` (2032) with
 * flags `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
 * (296) and `MATCH_PARENT` dimensions.
 *
 * An Activity-based block screen (the previous approach) can be dismissed
 * by the user via:
 *   - Home gesture / button → activity goes to back stack → offending app
 *     is still visible underneath
 *   - Recents → swipe the block activity away → back to offending app
 *   - Back button (only this one can be intercepted via `onBackPressed`,
 *     but the gesture nav back is not interceptable)
 *
 * A `WindowManager` overlay cannot be dismissed by any of these — it stays
 * on top until we explicitly `removeView()`. The user must use the Close
 * button (or wait for the optional countdown).
 *
 * ## The 500ms HOME×5 + BACK×1 timer
 *
 * The overlay only covers the offending window visually. The window is
 * still alive underneath and will reappear when the overlay is removed.
 * NopoX solves this with a `Timer` that fires every 500ms, pressing
 * `GLOBAL_ACTION_HOME` five times then `GLOBAL_ACTION_BACK` once. This
 * kills the offending activity (HOME returns to launcher; BACK finishes
 * the top activity if HOME didn't).
 *
 * ## Overlay permission
 *
 * `TYPE_APPLICATION_OVERLAY` requires the `SYSTEM_ALERT_WINDOW` permission
 * (declared in the manifest). On Android 6+ the user must grant it via
 * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. If not granted, the overlay
 * cannot be shown and we fall back to the Activity-based block screen
 * (`PornBlockActivity`) — which is dismissible but better than nothing.
 *
 * ## Thread safety
 *
 * All overlay add/remove is done on the main thread (WindowManager
 * requires it). The single-flight guard (`isOverlayShowing`) ensures only
 * one overlay is visible at a time — matching NopoX's `if (isPageShow) return`
 * pattern. Per-package throttling is intentionally NOT used (NopoX doesn't
 * either) because it creates a bypass window.
 *
 * ## User customizations (motivation image + custom message)
 *
 * BUGFIX: The previous implementation ignored the user's custom block
 * screen message and motivation image. Only the fallback PornBlockActivity
 * applied them, and even there the image-loading was broken (see
 * [PornBlockActivity] history). The overlay now ALSO loads the user's
 * custom message + motivation image so the customisations appear
 * regardless of which block path is taken.
 *
 * Ported from NopoX `PornBlockPage.java` (decompiled) + adapted to the
 * Protect Yourself block screen layout.
 */
class BlockOverlayManager(
    private val service: AccessibilityService
) {

    private val windowManager: WindowManager? = try {
        service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } catch (t: Throwable) {
        Timber.e(t, "BlockOverlayManager: failed to get WindowManager")
        null
    }

    @Volatile
    private var overlayView: View? = null

    @Volatile
    private var motivationImageView: ImageView? = null

    @Volatile
    private var killTimer: KillTimer? = null

    private val isOverlayShowing = AtomicBoolean(false)

    /**
     * Coroutine scope for IO-bound DB / image decode work. Uses a
     * SupervisorJob so a failure loading the motivation image doesn't
     * cancel sibling work (e.g. the close-button countdown wiring).
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether we have permission to draw an overlay. If false, callers
     * should fall back to the Activity-based block screen.
     */
    fun canDrawOverlays(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(service)
            } else {
                true  // Pre-M grants SYSTEM_ALERT_WINDOW automatically if declared
            }
        } catch (t: Throwable) {
            Timber.w(t, "BlockOverlayManager: canDrawOverlays check failed")
            false
        }
    }

    /**
     * Show the block overlay on top of the offending app, then start the
     * HOME×5 + BACK×1 kill timer.
     *
     * @param packageName the offending app's package name (for logging)
     * @param messageResKey the string resource key for the block message
     * @param matchedKeyword optional keyword that triggered the block (shown in "Why")
     * @return true if the overlay was shown, false if it could not be shown
     *   (caller should fall back to Activity-based block)
     */
    @Synchronized
    fun showBlockOverlay(
        packageName: String,
        messageResKey: String,
        matchedKeyword: String? = null
    ): Boolean {
        // Single-flight guard — only one overlay at a time.
        if (!isOverlayShowing.compareAndSet(false, true)) {
            Timber.d("BlockOverlayManager: overlay already showing — skipping")
            return true
        }

        val wm = windowManager ?: run {
            isOverlayShowing.set(false)
            return false
        }

        if (!canDrawOverlays()) {
            Timber.w("BlockOverlayManager: SYSTEM_ALERT_WINDOW not granted — cannot show overlay")
            isOverlayShowing.set(false)
            return false
        }

        // Build the overlay view
        val view = try {
            buildOverlayView(messageResKey, matchedKeyword)
        } catch (t: Throwable) {
            Timber.e(t, "BlockOverlayManager: failed to build overlay view")
            isOverlayShowing.set(false)
            return false
        }

        // Layout params — match NopoX: type=2032, flags=296, MATCH_PARENT
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // Soft input mode — don't allow keyboard to push the overlay
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            // Title for accessibility
            title = "Protect Yourself Block Screen"
        }

        return try {
            wm.addView(view, params)
            overlayView = view
            Timber.i("BlockOverlayManager: overlay shown for pkg=%s messageKey=%s", packageName, messageResKey)

            // Start the kill timer — HOME×5 + BACK×1 every 500ms, for 3 seconds
            // (6 iterations). This is what actually kills the offending activity.
            killTimer = KillTimer(service).also { it.start() }

            // Asynchronously apply the user's custom message + motivation image.
            // This is non-blocking — the overlay is already visible with the
            // default message, and the customisations are applied as soon as
            // the DB + image decode complete (typically < 50ms).
            applyUserCustomisations(messageText = defaultMessageText(messageResKey))

            true
        } catch (t: Throwable) {
            Timber.e(t, "BlockOverlayManager: addView failed for pkg=%s", packageName)
            // Clean up partial state
            try { if (overlayView != null) wm.removeView(overlayView) } catch (_: Throwable) {}
            overlayView = null
            isOverlayShowing.set(false)
            false
        }
    }

    /**
     * Resolve the default message text for [messageResKey]. Falls back to
     * [R.string.block_page_default_message] if the resource can't be found.
     */
    private fun defaultMessageText(messageResKey: String): String {
        val resId = try {
            service.resources.getIdentifier(messageResKey, "string", service.packageName)
        } catch (_: Throwable) { 0 }
        return if (resId != 0) {
            try { service.getString(resId) } catch (_: Throwable) {
                service.getString(R.string.block_page_default_message)
            }
        } else {
            service.getString(R.string.block_page_default_message)
        }
    }

    /**
     * Asynchronously load the user's custom block screen message + motivation
     * image from the DB and apply them to the current overlay.
     *
     * Safe to call from any thread. All view mutations are posted to the
     * main thread via [overlayView]?.post { ... }.
     */
    private fun applyUserCustomisations(messageText: String) {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(service)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                // 1. Custom message override
                val customMessage = switchValues.getBlockScreenCustomMessage()
                if (!customMessage.isNullOrBlank()) {
                    overlayView?.post {
                        try {
                            (overlayView?.findViewWithTag<TextView>("messageText"))?.text = customMessage
                        } catch (t: Throwable) {
                            Timber.w(t, "BlockOverlayManager: failed to apply custom message")
                        }
                    }
                }

                // 2. Motivation image — use decodeWithReason() so we can log
                // the specific failure mode (too large vs could-not-open).
                val imagePath = switchValues.getBlockScreenStoreImagePath()
                if (!imagePath.isNullOrBlank()) {
                    val result = BlockScreenImageLoader.decodeWithReason(service, imagePath)
                    when (result) {
                        is BlockScreenImageLoader.DecodeResult.Success -> {
                            overlayView?.post {
                                try {
                                    motivationImageView?.let { iv ->
                                        iv.setImageBitmap(result.bitmap)
                                        iv.visibility = View.VISIBLE
                                    }
                                } catch (t: Throwable) {
                                    Timber.w(t, "BlockOverlayManager: failed to apply motivation image")
                                }
                            }
                        }
                        is BlockScreenImageLoader.DecodeResult.TooLarge ->
                            Timber.w("BlockOverlayManager: motivation image too large for path=%s", imagePath)
                        is BlockScreenImageLoader.DecodeResult.DecodeFailed ->
                            Timber.w("BlockOverlayManager: motivation image decode failed for path=%s", imagePath)
                        is BlockScreenImageLoader.DecodeResult.NoInput -> { /* no-op */ }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "BlockOverlayManager: failed to apply user customisations")
            }
        }
    }

    /**
     * Remove the block overlay + stop the kill timer.
     * Safe to call multiple times.
     */
    @Synchronized
    fun hideBlockOverlay() {
        killTimer?.cancel()
        killTimer = null

        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                Timber.w(t, "BlockOverlayManager: removeView failed (already removed?)")
            }
            overlayView = null
        }
        motivationImageView = null
        isOverlayShowing.set(false)
    }

    /**
     * Build the overlay view — a simple full-screen layout with:
     *   - App logo + name header
     *   - Optional motivation image (loaded async after the view is attached)
     *   - Block message (from string resource, optionally overridden by user)
     *   - "Why am I seeing this?" expandable text (shows matched keyword)
     *   - Close button (immediate, no countdown — overlay is already non-dismissible)
     *
     * Built programmatically (no XML inflation) to keep this class
     * self-contained and avoid layout resource conflicts.
     */
    private fun buildOverlayView(messageResKey: String, matchedKeyword: String?): View {
        val ctx = service

        // Root container — vertical linear layout, dark background
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xF0181818.toInt())  // 94% opaque dark
            setPadding(dp(32), dp(64), dp(32), dp(48))
        }

        // App name header
        val appName = try {
            ctx.getString(R.string.app_name)
        } catch (_: Throwable) { "Protect Yourself" }
        val header = TextView(ctx).apply {
            text = appName
            setTextColor(0xFFFFB74B.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        root.addView(header)

        // Block icon (warning)
        val icon = TextView(ctx).apply {
            text = "⚠"
            textSize = 56f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(icon)

        // Optional motivation image (initially GONE — shown async after
        // applyUserCustomisations() decodes the bitmap). Wrapped in a
        // horizontal LinearLayout so we can constrain its width.
        val motivationContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val motivationIv = ImageView(ctx).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            // Cap dimensions so a portrait photo doesn't push the message off-screen.
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER
            }
            contentDescription = ctx.getString(R.string.block_screen_motivation_image_cd)
        }
        motivationContainer.addView(motivationIv)
        root.addView(motivationContainer)
        // Save a reference so applyUserCustomisations() can set the bitmap.
        motivationImageView = motivationIv

        // Block message
        val messageResId = try {
            ctx.resources.getIdentifier(messageResKey, "string", ctx.packageName)
        } catch (_: Throwable) { 0 }
        val messageText = if (messageResId != 0) {
            try { ctx.getString(messageResId) } catch (_: Throwable) {
                ctx.getString(R.string.block_page_default_message)
            }
        } else {
            ctx.getString(R.string.block_page_default_message)
        }
        val message = TextView(ctx).apply {
            tag = "messageText"
            text = messageText
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(message)

        // "Why am I seeing this?" expandable
        val collapsedLabel = ctx.getString(R.string.why)
        val expandedLabel = ctx.getString(R.string.why_hide)
        val detailText = if (!matchedKeyword.isNullOrBlank()) {
            ctx.getString(R.string.block_screen_why_matched_keyword, matchedKeyword)
        } else {
            messageText
        }

        val whyToggle = TextView(ctx).apply {
            text = collapsedLabel
            setTextColor(0xFF90CAF9.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                val detail = (parent as? ViewGroup)?.findViewWithTag<TextView>("whyDetail")
                detail?.let {
                    if (it.visibility == View.VISIBLE) {
                        it.visibility = View.GONE
                        text = collapsedLabel
                    } else {
                        it.text = detailText
                        it.visibility = View.VISIBLE
                        text = expandedLabel
                    }
                }
            }
        }
        root.addView(whyToggle)

        val whyDetail = TextView(ctx).apply {
            tag = "whyDetail"
            text = detailText
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(16))
            visibility = View.GONE
        }
        root.addView(whyDetail)

        // Spacer
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(24))
        })

        // Close button — full-width, prominent
        val closeBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.close)
            setTextColor(0xFF000000.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFFFB74B.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setOnClickListener {
                hideBlockOverlay()
            }
        }
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(closeBtn, closeParams)

        // Make the root focusable so it captures the back key
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            // Block ALL key events — especially BACK. The overlay cannot be
            // dismissed by any key. The user MUST use the Close button.
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_HOME ||
                keyCode == KeyEvent.KEYCODE_APP_SWITCH
            ) {
                Timber.d("BlockOverlayManager: swallowed key %d", keyCode)
                true
            } else {
                false
            }
        }

        return root
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }

    /**
     * Kill timer — presses GLOBAL_ACTION_HOME ×5 then GLOBAL_ACTION_BACK ×1,
     * every 500ms, for a total of 6 iterations (3 seconds).
     *
     * This is what actually kills the offending activity underneath the
     * overlay. The overlay only covers it visually.
     *
     * Ported from NopoX's `TimersKt.timer().scheduleAtFixedRate(..., 0L, 500L)`.
     */
    private class KillTimer(private val service: AccessibilityService) {
        private var timer: java.util.Timer? = null
        private var iteration = 0
        private val maxIterations = 6  // 6 × 500ms = 3 seconds

        fun start() {
            timer = java.util.Timer("BlockOverlay-KillTimer", true)
            timer?.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        if (iteration >= maxIterations) {
                            cancel()
                            return
                        }
                        // Press HOME 5 times
                        repeat(5) {
                            try {
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            } catch (t: Throwable) {
                                Timber.w(t, "KillTimer: GLOBAL_ACTION_HOME failed")
                            }
                        }
                        // Press BACK once
                        try {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        } catch (t: Throwable) {
                            Timber.w(t, "KillTimer: GLOBAL_ACTION_BACK failed")
                        }
                        iteration++
                    } catch (t: Throwable) {
                        Timber.w(t, "KillTimer: iteration failed")
                    }
                }
            }, 0L, 500L)
        }

        fun cancel() {
            try {
                timer?.cancel()
            } catch (_: Throwable) {}
            timer = null
        }
    }
}
