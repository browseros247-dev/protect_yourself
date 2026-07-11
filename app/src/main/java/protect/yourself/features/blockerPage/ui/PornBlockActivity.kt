package protect.yourself.features.blockerPage.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import timber.log.Timber
import java.io.File

/**
 * PornBlockActivity — full-screen block overlay shown when content is blocked.
 *
 * Ported from original `PornBlockPage.kt` + `page_porn_block.xml`.
 *
 * Layout: `R.layout.page_porn_block`
 *
 * Behavior:
 *  1. Show app logo + name header
 *  2. Show optional motivation image (user-configured)
 *  3. Show block message (dynamic per block reason)
 *  4. Show optional rating prompt (after N blocks)
 *  5. Show "Why am I seeing this?" expandable text
 *  6. Show Close button (with optional countdown timer 3-300s)
 *  7. On Close: finish activity OR redirect to URL if configured
 *
 * REMOVED from original:
 *  - AdMob banner (no AdView container)
 *  - PU promotion banner (no imgPuBanner)
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

        bindViews()
        configureBlockScreen()
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

        Timber.i("Block screen shown for pkg=$blockPackage messageKey=$messageKey keyword=$matchedKeyword")

        // 1. Set message
        val messageResId = resources.getIdentifier(messageKey, "string", packageName)
        txtPageMessage.text = if (messageResId != 0) getString(messageResId) else getString(R.string.block_page_default_message)

        // 2. Configure Why text (initially hidden, tap to expand)
        // KB-19: if we have a matched keyword, show it in the "why" expansion
        // instead of the generic default message.
        txtWhy.text = getString(R.string.why)
        txtWhy.setOnClickListener {
            if (txtWhyContainer.visibility == View.VISIBLE) {
                txtWhyContainer.visibility = View.GONE
            } else {
                txtWhyContainer.text = if (!matchedKeyword.isNullOrBlank()) {
                    "Blocked because the URL or content matched keyword: \"$matchedKeyword\""
                } else {
                    getString(R.string.block_page_default_porn_blocker_message)
                }
                txtWhyContainer.visibility = View.VISIBLE
            }
        }

        // 3. Load user motivation image (if set)
        loadMotivationImage()

        // 4. Configure Close button (with optional countdown)
        configureCloseButton()

        // 5. Maybe show rating prompt
        maybeShowRatingPrompt()

        // 6. Increment block count + persist
        incrementBlockCount()
    }

    private fun loadMotivationImage() {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                val imagePath = switchValues.getBlockScreenStoreImagePath()

                if (!imagePath.isNullOrBlank()) {
                    val imageFile = File(imagePath)
                    if (imageFile.exists()) {
                        // CRASH FIX: use inSampleSize to downsample large images
                        // before decoding, preventing OutOfMemoryError on devices
                        // with limited heap. The block screen is shown frequently,
                        // so repeated full-resolution decoding exhausts the heap.
                        val boundsOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(imagePath, boundsOptions)
                        val targetSize = 512  // max width/height in pixels
                        var sampleSize = 1
                        while (boundsOptions.outWidth / sampleSize > targetSize ||
                               boundsOptions.outHeight / sampleSize > targetSize) {
                            sampleSize *= 2
                        }
                        val decodeOptions = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                        val bitmap = BitmapFactory.decodeFile(imagePath, decodeOptions)
                        if (bitmap != null) {
                            uiScope.launch {
                                imgMotivation.setImageBitmap(bitmap)
                                imgMotivation.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                // 7. Apply custom block message if set
                val customMessage = switchValues.getBlockScreenCustomMessage()
                if (!customMessage.isNullOrBlank()) {
                    uiScope.launch {
                        txtPageMessage.text = customMessage
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "Failed to load motivation image / custom message")
            }
        }
    }

    private fun configureCloseButton() {
        ioScope.launch {
            try {
                val db = AppDatabase.getInstance(this@PornBlockActivity)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                val countdownSeconds = switchValues.getBlockScreenCountDownSeconds()
                val isRedirectUrlSet = switchValues.isBlockScreenRedirectUrlSet()
                val redirectUrl = switchValues.getBlockScreenRedirectUrl()

                if (countdownSeconds > 0) {
                    // Show countdown on Close button
                    uiScope.launch {
                        startCountdown(countdownSeconds)
                    }
                } else {
                    // No countdown — Close button is immediate
                    txtCloseContainer.setOnClickListener {
                        handleClose(isRedirectUrlSet, redirectUrl)
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "Failed to configure close button — using immediate close")
                txtCloseContainer.setOnClickListener { finish() }
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        val totalMs = seconds * 1000L
        txtCloseContainer.isClickable = false
        countDownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = millisUntilFinished / 1000
                txtClose.text = "${getString(R.string.close)} ($secs)"
            }

            override fun onFinish() {
                txtClose.text = getString(R.string.close)
                txtCloseContainer.isClickable = true

                ioScope.launch {
                    val db = AppDatabase.getInstance(this@PornBlockActivity)
                    val switchValues = SwitchStatusValues(db.switchStatusDao())
                    val isRedirectUrlSet = switchValues.isBlockScreenRedirectUrlSet()
                    val redirectUrl = switchValues.getBlockScreenRedirectUrl()
                    uiScope.launch {
                        txtCloseContainer.setOnClickListener {
                            handleClose(isRedirectUrlSet, redirectUrl)
                        }
                    }
                }
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
                Timber.w(t, "Failed to redirect to $redirectUrl")
                Toast.makeText(this, "Could not open redirect URL", Toast.LENGTH_SHORT).show()
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
                Timber.w(t, "Failed to check rating prompt")
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
                Toast.makeText(this, "Could not open Play Store", Toast.LENGTH_SHORT).show()
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
                Timber.d("Block count incremented")
            } catch (t: Throwable) {
                Timber.w(t, "Failed to increment block count")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    override fun onBackPressed() {
        // Block back button — user must use Close button
        // (or wait for countdown to finish)
    }

    companion object {
        private const val TAG = "PornBlockActivity"
    }
}
