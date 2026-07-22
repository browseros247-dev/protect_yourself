package protect.yourself.features.blockerPage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.utils.BlockScreenImageLoader
import timber.log.Timber

/**
 * PornBlockActivity — full-screen block overlay shown when content is blocked.
 *
 * Ported from original `PornBlockPage.kt` + `page_porn_block.xml`.
 *
 * Layout: `R.layout.page_porn_block`
 *
 * Behavior:
 *  1. Show app logo + name header
 *  2. Show optional motivation image (user-configured) — see [loadMotivationImageAndMessage]
 *  3. Show block message (dynamic per block reason)
 *  4. Show optional rating prompt (after N blocks)
 *  5. Show "Why am I seeing this?" expandable text
 *  6. Show Close button (with optional countdown timer 3-300s)
 *  7. On Close: finish activity OR redirect to URL if configured
 *
 * REMOVED from original:
 *  - AdMob banner (no AdView container)
 *  - PU promotion banner (no imgPuBanner)
 *
 * BUGFIX HISTORY (block-screen motivation image + message):
 *  - The previous implementation stored a `content://` URI under
 *    `BLOCK_SCREEN_STORE_IMAGE_PATH` but loaded it via `File(imagePath)`
 *    and `BitmapFactory.decodeFile()`, which silently failed for content
 *    URIs — the motivation image NEVER actually displayed. We now use
 *    [BlockScreenImageLoader] which handles both content:// URIs and
 *    filesystem paths via ContentResolver.openInputStream().
 *  - The "Why am I seeing this?" affordance previously had two TextViews
 *    both labeled "Why" (txtWhy + txtWhyContainer). The toggle is now
 *    labeled "Why am I seeing this?" / "Hide details" so the affordance is
 *    discoverable.
 *  - Hard-coded English strings are now localized via strings.xml.
 *  - The motivation image now preserves aspect ratio (adjustViewBounds +
 *    fitCenter) instead of being squashed to 200x200dp.
 *  - Bitmap decode failures now log a clear warning AND emit a user-facing
 *    toast so the user knows their image was moved/deleted.
 */
class PornBlockActivity : AppCompatActivity() {

    private val uiScope = appCoroutineScope(
        scopeName = "PornBlockActivity-ui",
        dispatcher = kotlinx.coroutines.Dispatchers.Main,
        context = this
    )
    private val ioScope = appCoroutineScope(
        scopeName = "PornBlockActivity-io",
        dispatcher = kotlinx.coroutines.Dispatchers.IO,
        context = this
    )

    private var countDownTimer: CountDownTimer? = null

    // Views
    private lateinit var imgMotivation: ImageView
    private lateinit var txtPageMessage: TextView
    private lateinit var llRatingContainer: LinearLayout
    private lateinit var txtRatingMessage: TextView
    private lateinit var ratingBar: RatingBar
    private lateinit var txtWhyContainer: TextView
    private lateinit var txtWhy: TextView
    private lateinit var txtCloseContainer: FrameLayout
    private lateinit var txtClose: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.page_porn_block)
        // BUG-08 fix: register the predictive-back callback
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        isShowing.set(true)
        bindViews()
        configureBlockScreen()
    }

    /**
     * BLOCK-SCREEN-03 (v1.0.68): the manifest declares this activity
     * `singleTop`. When a second block fires while an older instance is still
     * alive on top of its task, Android reuses it and delivers the intent
     * here — the old code had no override, so the screen kept showing the
     * PREVIOUS block's package/message ("appears but does not function
     * correctly"). Rebind against the new extras and cancel any stale
     * countdown before re-arming it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Timber.i("Block screen re-launched via onNewIntent — rebinding to new extras")
        try {
            countDownTimer?.cancel()
            countDownTimer = null
            // Views may be re-bound cheaply; reset dynamic state first.
            if (::imgMotivation.isInitialized) {
                imgMotivation.setImageDrawable(null)
                imgMotivation.visibility = View.GONE
            }
            if (::llRatingContainer.isInitialized) {
                llRatingContainer.visibility = View.GONE
            }
            if (::txtWhyContainer.isInitialized) {
                txtWhyContainer.visibility = View.GONE
            }
            if (::txtCloseContainer.isInitialized) {
                // CLOSE-BTN-01 (v1.0.70): keep a WORKING close action during
                // re-setup instead of a dead (null, non-clickable) button —
                // wireCloseGate installs the correct gated listener as soon as
                // the new config resolves.
                txtCloseContainer.setOnClickListener { finish() }
            }
            configureBlockScreen()
        } catch (t: Throwable) {
            Timber.w(t, "PornBlockActivity: onNewIntent rebind failed")
        }
    }

    companion object {
        /**
         * BLOCK-SCREEN-02 (v1.0.68): surfaced to MyAccessibilityService so the
         * fallback launcher can VERIFY the screen actually appeared (guards
         * against silent background-activity-launch drops on API 29+).
         */
        val isShowing = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun bindViews() {
        imgMotivation = findViewById(R.id.imgMotivation)
        txtPageMessage = findViewById(R.id.txtPageMessage)
        llRatingContainer = findViewById(R.id.llRatingContainer)
        txtRatingMessage = findViewById(R.id.txtRatingMessage)
        ratingBar = findViewById(R.id.ratingBar)
        txtWhyContainer = findViewById(R.id.txtWhyContainer)
        txtWhy = findViewById(R.id.txtWhy)
        txtCloseContainer = findViewById(R.id.txtCloseContainer)
        txtClose = findViewById(R.id.txtClose)
    }

    private fun configureBlockScreen() {
        val blockPackage = intent.getStringExtra(MyAccessibilityService.EXTRA_BLOCK_PACKAGE)
        val messageKey = intent.getStringExtra(MyAccessibilityService.EXTRA_BLOCK_MESSAGE_KEY)
            ?: "block_page_default_message"
        // KB-19 fix: read the matched keyword extra (if present) so we can show
        // the user WHY they were blocked. Helps them understand false positives
        // and adjust their keyword list.
        val matchedKeyword = intent.getStringExtra(MyAccessibilityService.EXTRA_MATCHED_KEYWORD)

        Timber.i("Block screen shown for pkg=%s messageKey=%s keyword=%s",
            blockPackage, messageKey, matchedKeyword)

        // 1. Set message
        val messageResId = resources.getIdentifier(messageKey, "string", packageName)
        txtPageMessage.text = if (messageResId != 0) getString(messageResId) else getString(R.string.block_page_default_message)

        // 2. Configure Why text (initially hidden, tap to expand)
        // The toggle label changes between "Why am I seeing this?" and
        // "Hide details" so the affordance is obvious. The detail text is
        // populated below the toggle when expanded.
        configureWhyToggle(matchedKeyword)

        // 3. Load user motivation image (if set) + apply custom message
        loadMotivationImageAndMessage()

        // 4. Configure Close button (with optional countdown)
        configureCloseButton()

        // 5. Maybe show rating prompt
        maybeShowRatingPrompt()

        // 6. Increment block count + persist
        incrementBlockCount()
    }

    /**
     * Configure the "Why am I seeing this?" toggle. Tapping it expands or
     * collapses the explanation below it. The toggle label changes to make
     * the affordance discoverable.
     */
    private fun configureWhyToggle(matchedKeyword: String?) {
        // Detail text shown when expanded.
        val detailText = if (!matchedKeyword.isNullOrBlank()) {
            getString(R.string.block_screen_why_matched_keyword, matchedKeyword)
        } else {
            getString(R.string.block_page_default_porn_blocker_message)
        }
        txtWhyContainer.text = detailText
        txtWhyContainer.visibility = View.GONE

        // Make the toggle label a clickable span so the whole label is tappable
        // (not just the text region that happens to be over the TextView).
        val collapsedLabel = getString(R.string.why)
        val expandedLabel = getString(R.string.why_hide)

        txtWhy.text = collapsedLabel
        txtWhy.movementMethod = LinkMovementMethod.getInstance()
        val span = SpannableStringBuilder(collapsedLabel)
        val click = object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (txtWhyContainer.visibility == View.VISIBLE) {
                    txtWhyContainer.visibility = View.GONE
                    txtWhy.text = collapsedLabel
                    refreshWhySpan(collapsedLabel)
                } else {
                    txtWhyContainer.text = detailText
                    txtWhyContainer.visibility = View.VISIBLE
                    txtWhy.text = expandedLabel
                    refreshWhySpan(expandedLabel)
                }
            }
        }
        span.setSpan(click, 0, collapsedLabel.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtWhy.setText(span)

        // Also forward plain clicks (some OEMs strip ClickableSpan clicks)
        txtWhy.setOnClickListener {
            if (txtWhyContainer.visibility == View.VISIBLE) {
                txtWhyContainer.visibility = View.GONE
                txtWhy.text = collapsedLabel
                refreshWhySpan(collapsedLabel)
            } else {
                txtWhyContainer.text = detailText
                txtWhyContainer.visibility = View.VISIBLE
                txtWhy.text = expandedLabel
                refreshWhySpan(expandedLabel)
            }
        }
    }

    private fun refreshWhySpan(label: String) {
        val span = SpannableStringBuilder(label)
        val click = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // Re-dispatch to the View's own onClickListener — keeps the
                // span clickable indefinitely.
                txtWhy.performClick()
            }
        }
        span.setSpan(click, 0, label.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtWhy.setText(span)
    }

    /**
     * Load the user's motivation image (if set) and override the default
     * block message with the user's custom message (if set).
     *
     * Both happen in a single IO coroutine to avoid two separate DB hits.
     *
     * CRITICAL FIX: The previous implementation used `File(imagePath)` +
     * `BitmapFactory.decodeFile()` which silently failed for `content://`
     * URIs returned by the image picker. We now use [BlockScreenImageLoader]
     * which handles content URIs via ContentResolver.openInputStream().
     */
    private fun loadMotivationImageAndMessage() {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                val imagePath = switchValues.getBlockScreenStoreImagePath()
                Timber.d("PornBlockActivity: motivation imagePath=%s", imagePath)

                if (!imagePath.isNullOrBlank()) {
                    // Use decodeWithReason() so we can show a specific toast
                    // for each failure mode (too large vs could-not-open).
                    val result = BlockScreenImageLoader.decodeWithReason(
                        this@PornBlockActivity, imagePath
                    )
                    when (result) {
                        is BlockScreenImageLoader.DecodeResult.Success -> {
                            uiScope.launch {
                                imgMotivation.setImageBitmap(result.bitmap)
                                imgMotivation.visibility = View.VISIBLE
                            }
                        }
                        is BlockScreenImageLoader.DecodeResult.TooLarge -> {
                            Timber.w("PornBlockActivity: motivation image too large for path=%s", imagePath)
                            uiScope.launch {
                                Toast.makeText(
                                    this@PornBlockActivity,
                                    R.string.block_screen_image_too_large_toast,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        is BlockScreenImageLoader.DecodeResult.DecodeFailed -> {
                            // Decode failed — log + show a one-shot toast so the
                            // user knows their image was moved/deleted and isn't
                            // just silently missing.
                            Timber.w("PornBlockActivity: motivation image decode failed for path=%s", imagePath)
                            uiScope.launch {
                                Toast.makeText(
                                    this@PornBlockActivity,
                                    R.string.block_screen_image_load_error_toast,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        is BlockScreenImageLoader.DecodeResult.NoInput -> {
                            // No path stored — nothing to do (image stays GONE)
                        }
                    }
                }

                // Apply custom block message if set
                val customMessage = switchValues.getBlockScreenCustomMessage()
                if (!customMessage.isNullOrBlank()) {
                    uiScope.launch {
                        txtPageMessage.text = customMessage
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: failed to load motivation image / custom message")
                uiScope.launch {
                    Toast.makeText(
                        this@PornBlockActivity,
                        R.string.block_screen_image_load_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * CLOSE-BTN-01 (v1.0.70): resolve the dwell + redirect config ONCE (single
     * IO read), then hand off to [wireCloseGate] on the main thread. The old
     * implementation armed the listener through a second DB round-trip inside
     * CountDownTimer.onFinish, leaving the button dead whenever that chain
     * stalled post-dwell.
     */
    private fun configureCloseButton() {
        ioScope.launch {
            val countdownSeconds: Int
            val isRedirectUrlSet: Boolean
            val redirectUrl: String?
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                countdownSeconds = switchValues.getBlockScreenCountDownSeconds()
                isRedirectUrlSet = switchValues.isBlockScreenRedirectUrlSet()
                redirectUrl = switchValues.getBlockScreenRedirectUrl()
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: close config read failed — immediate close")
                uiScope.launch { wireCloseGate(0, false, null) }
                return@launch
            }
            uiScope.launch {
                wireCloseGate(countdownSeconds, isRedirectUrlSet, redirectUrl)
            }
        }
    }

    /**
     * CLOSE-BTN-01 (v1.0.70): install the Close click listener SYNCHRONOUSLY
     * and exactly once — it is never absent, so the button can never sit
     * dead.
     *
     * Semantics (via [CloseGatePolicy]):
     *  - dwell elapsed (or none) → [handleClose] runs;
     *  - during the dwell → a short toast with the remaining seconds. A
     *    silent, unresponsive button reads as "broken" — that was the
     *    reported "Close button has no effect" perception, now replaced by
     *    visible countdown text AND tap feedback.
     *
     * The countdown label is purely cosmetic; the time-based gate is the
     * single source of truth (no second DB read at arm time).
     */
    private fun wireCloseGate(dwellSeconds: Int, isRedirectUrlSet: Boolean, redirectUrl: String?) {
        countDownTimer?.cancel()
        countDownTimer = null

        val gate = CloseGatePolicy(dwellSeconds)
        txtCloseContainer.isClickable = true
        txtCloseContainer.setOnClickListener {
            when (val click = gate.onClick()) {
                is CloseGatePolicy.Click.Close ->
                    handleClose(isRedirectUrlSet, redirectUrl)
                is CloseGatePolicy.Click.Blocked ->
                    Toast.makeText(
                        this,
                        getString(R.string.block_screen_close_available_in, click.remainingSeconds),
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }

        if (dwellSeconds > 0) {
            startCountdownLabel(dwellSeconds)
        } else {
            txtClose.text = getString(R.string.close)
        }
        Timber.d("PornBlockActivity: close gate wired (dwell=%ds)", dwellSeconds)
    }

    /** Cosmetic countdown on the Close label — the gate policy decides clicks. */
    private fun startCountdownLabel(seconds: Int) {
        val totalMs = seconds * 1000L
        countDownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Ceil so the user sees 3,2,1 (not 2,1,0) during a 3s dwell.
                val secs = (millisUntilFinished + 999) / 1000
                txtClose.text = "${getString(R.string.close)} ($secs)"
            }

            override fun onFinish() {
                txtClose.text = getString(R.string.close)
            }
        }.start()
    }

    private fun handleClose(isRedirectUrlSet: Boolean, redirectUrl: String?) {
        if (isRedirectUrlSet && !redirectUrl.isNullOrBlank()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: failed to redirect to %s", redirectUrl)
                Toast.makeText(this, R.string.block_screen_redirect_failed_toast, Toast.LENGTH_SHORT).show()
            }
        } else {
            // ACTIVITY-BLOCK-01 (v1.0.70): land the user on the launcher after
            // Close. With the old overlay kill-timer gone, the blocked app's
            // task would still be sitting underneath — returning straight to
            // it would look like the block "did nothing" (and would instantly
            // re-trigger another block). A CATEGORY_HOME intent from our own
            // foreground activity is reliable and needs no global action.
            try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: HOME intent failed after close")
            }
        }
        finish()
    }

    private fun maybeShowRatingPrompt() {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                val isRatingGiven = switchValues.isRatingGiven()
                if (isRatingGiven) return@launch

                val blockCount = db.blockScreenCountDao().getCount()?.count ?: 0
                // Show rating prompt after every 20 blocks
                if (blockCount > 0 && blockCount % 20 == 0) {
                    uiScope.launch {
                        llRatingContainer.visibility = View.VISIBLE
                        ratingBar.onRatingBarChangeListener = android.widget.RatingBar.OnRatingBarChangeListener { _, rating, _ ->
                            if (rating >= 4) {
                                // Open Play Store
                                openPlayStore()
                            } else {
                                Toast.makeText(
                                    this@PornBlockActivity,
                                    R.string.low_rating_message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            // Mark rating as given
                            ioScope.launch {
                                switchValues.storeSwitchStatus(SwitchIdentifier.RATING_GIVEN_STATUS, true)
                            }
                            llRatingContainer.visibility = View.GONE
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: failed to check rating prompt")
            }
        }
    }

    private fun openPlayStore() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // Play Store not installed — open web URL
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, R.string.block_screen_play_store_failed_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun incrementBlockCount() {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val current = db.blockScreenCountDao().getCount()
                if (current == null) {
                    db.blockScreenCountDao().upsert(
                        protect.yourself.database.blockScreensCount.BlockScreenCountItemModel(0, 1)
                    )
                } else {
                    db.blockScreenCountDao().increment()
                }
                Timber.d("PornBlockActivity: block count incremented")
            } catch (t: Throwable) {
                Timber.w(t, "PornBlockActivity: failed to increment block count")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing.set(false)
        countDownTimer?.cancel()
        // Drop the motivation bitmap eagerly to release memory faster — the
        // ImageView may hold a reference until the next GC.
        try {
            (imgMotivation.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                if (!it.isRecycled) {
                    imgMotivation.setImageDrawable(null)
                }
            }
        } catch (_: Throwable) {
            // Best-effort — don't crash on cleanup.
        }
    }

    /**
     * BUG-08 fix: the deprecated `onBackPressed()` is bypassed by Android 14+
     * predictive back gesture. The user can swipe back from the edge to dismiss
     * the block screen without triggering `onBackPressed()`.
     *
     * The fix is to register an `OnBackPressedCallback` with
     * `OnBackPressedDispatcher`. The callback is enabled for the lifetime of
     * the activity, so the predictive back gesture is also intercepted.
     *
     * The callback does nothing (swallow the back press) — the user must use
     * the Close button (or wait for the countdown to finish).
     */
    private val backPressedCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Swallow — user must use Close button
            Timber.d("PornBlockActivity: back press swallowed (predictive back intercepted)")
        }
    }

    @Deprecated("Deprecated in Java — use OnBackPressedDispatcher via backPressedCallback", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        // Block back button — user must use Close button
        // (or wait for countdown to finish)
        // BUG-08 fix: this is now handled by backPressedCallback for predictive back.
        // This override is kept for backward compatibility with Android < 13.
        onBackPressedDispatcher.onBackPressed()
    }
}
