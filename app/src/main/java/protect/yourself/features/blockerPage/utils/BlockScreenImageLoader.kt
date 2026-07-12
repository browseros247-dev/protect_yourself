package protect.yourself.features.blockerPage.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.InputStream

/**
 * BlockScreenImageLoader — centralised, fault-tolerant loader for the
 * user-selected "motivation image" shown on the block screen.
 *
 * # Why this class exists
 *
 * The previous implementation in `PornBlockActivity.loadMotivationImage()`
 * stored whatever `ActivityResultContracts.GetContent()` returned (a
 * `content://` URI) but then loaded it via `File(imagePath)` and
 * `BitmapFactory.decodeFile(imagePath)`. Those two APIs only work for
 * filesystem paths — they silently fail for `content://` URIs, so the
 * motivation image never actually displayed. See the worklog for the full
 * bug write-up.
 *
 * This loader accepts BOTH `content://` URIs (the modern, SAF-backed case)
 * AND plain filesystem paths (legacy / migrated entries), decodes them with
 * aggressive downsampling, and never throws — callers receive `null` on any
 * failure and a logged warning.
 *
 * # Memory safety
 *
 * Block screens are shown frequently — sometimes several times a minute —
 * so we MUST keep memory pressure low. We:
 *   1. Probe the source dimensions first (inJustDecodeBounds).
 *   2. Compute an `inSampleSize` that downsampled the longest edge to
 *      `TARGET_MAX_EDGE_PX` (default 768 — crisp on a 1080p block screen,
 *      ~1.5 MB in ARGB_8888).
 *   3. Optionally re-encode to RGB_565 to halve memory if the source is
 *      opaque (motivation images usually are).
 *
 * # Persistable URI permissions
 *
 * Callers MUST take a persistable URI permission via
 * `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
 * before saving a `content://` URI — otherwise the URI becomes invalid
 * after process death. See [BlockerPageHome] for the launcher setup.
 *
 * This loader does NOT take the permission itself — it only reads.
 */
object BlockScreenImageLoader {

    /** Maximum edge (width or height) in pixels of the decoded bitmap. */
    const val TARGET_MAX_EDGE_PX = 768

    /** Hard cap on input file size (~20 MB) to prevent picking huge raw photos. */
    const val MAX_SOURCE_BYTES = 20L * 1024L * 1024L

    /**
     * Result type for [decodeWithReason]. Distinguishes between the various
     * failure modes so callers can show appropriate user-facing messages
     * (e.g. "image too large" vs "image could not be opened").
     */
    sealed class DecodeResult {
        /** Decode succeeded. */
        data class Success(val bitmap: Bitmap) : DecodeResult()
        /** Input was null or blank. */
        data object NoInput : DecodeResult()
        /** Source file size exceeds [MAX_SOURCE_BYTES]. */
        data object TooLarge : DecodeResult()
        /** Source could not be opened / decoded (deleted, permission revoked, corrupt). */
        data object DecodeFailed : DecodeResult()
    }

    /**
     * Decode a bitmap from either a `content://` URI or a filesystem path.
     *
     * Returns `null` if:
     *   - [uriOrPath] is null/blank
     *   - The source cannot be opened (deleted, permission revoked, corrupt)
     *   - The source exceeds [MAX_SOURCE_BYTES] (only enforceable for content
     *     URIs that report `OpenableColumns.SIZE`)
     *   - Decoding fails for any reason
     *
     * All failures are logged via Timber at WARN level. No exceptions are
     * thrown to the caller.
     *
     * For callers that need to distinguish between failure modes (e.g. to
     * show a more specific toast), use [decodeWithReason] instead.
     */
    fun decode(context: Context, uriOrPath: String?): Bitmap? {
        return decodeWithReason(context, uriOrPath).let { result ->
            when (result) {
                is DecodeResult.Success -> result.bitmap
                else -> null
            }
        }
    }

    /**
     * Same as [decode] but returns a [DecodeResult] so the caller can
     * distinguish between failure modes. Useful for surfacing specific
     * user-facing messages (e.g. "image too large, pick a smaller one"
     * vs "image could not be opened").
     */
    fun decodeWithReason(context: Context, uriOrPath: String?): DecodeResult {
        if (uriOrPath.isNullOrBlank()) return DecodeResult.NoInput

        return try {
            val uri = parseUriOrPath(uriOrPath)
            Timber.d("BlockScreenImageLoader: decoding uri=$uri")

            // Enforce size cap up-front for content URIs (file paths get checked
            // implicitly during decode — there's no cheap SIZE column for them).
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val size = querySize(context, uri)
                if (size != null && size > MAX_SOURCE_BYTES) {
                    Timber.w("BlockScreenImageLoader: source size %d bytes > cap %d — refusing",
                        size, MAX_SOURCE_BYTES)
                    return DecodeResult.TooLarge
                }
            }

            // Step 1: probe bounds
            val bounds = probeBounds(context, uri) ?: run {
                Timber.w("BlockScreenImageLoader: could not probe bounds for $uri")
                return DecodeResult.DecodeFailed
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Timber.w("BlockScreenImageLoader: invalid bounds %dx%d for %s",
                    bounds.outWidth, bounds.outHeight, uri)
                return DecodeResult.DecodeFailed
            }

            val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)

            // Step 2: actually decode
            val bitmap = decodeSampled(context, uri, sampleSize) ?: run {
                Timber.w("BlockScreenImageLoader: decode returned null for $uri")
                return DecodeResult.DecodeFailed
            }

            Timber.i("BlockScreenImageLoader: decoded %dx%d (sample=%d) from %s",
                bitmap.width, bitmap.height, sampleSize, uri)
            DecodeResult.Success(bitmap)
        } catch (t: Throwable) {
            // Catch OOM, SecurityException, IOException, etc. — never propagate.
            Timber.w(t, "BlockScreenImageLoader: failed to decode %s", uriOrPath)
            DecodeResult.DecodeFailed
        }
    }

    /**
     * Returns true if [uriOrPath] looks like a content:// URI that we can
     * attempt to open. Useful for callers that want to skip useless File
     * checks when the value is a content URI.
     */
    fun isContentUri(uriOrPath: String?): Boolean {
        if (uriOrPath.isNullOrBlank()) return false
        return uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")
    }

    /**
     * Parse a stored string as either a Uri (if it has a scheme) or treat it
     * as a filesystem path and wrap it in a file:// Uri for uniform handling.
     */
    private fun parseUriOrPath(uriOrPath: String): Uri {
        return if (uriOrPath.startsWith("content:") ||
            uriOrPath.startsWith("file:") ||
            uriOrPath.startsWith("android.resource:")) {
            Uri.parse(uriOrPath)
        } else {
            // Treat as filesystem path — wrap as file:// Uri so the rest of
            // the pipeline can use ContentResolver/openInputStream() uniformly.
            Uri.fromFile(java.io.File(uriOrPath))
        }
    }

    /**
     * Query the SAF size column for the URI. Returns null if unknown.
     */
    private fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0)
                } else null
            }
        } catch (t: Throwable) {
            Timber.d(t, "BlockScreenImageLoader: SIZE query failed for %s", uri)
            null
        }
    }

    /**
     * Probe the dimensions of the source without allocating pixel memory.
     */
    private fun probeBounds(context: Context, uri: Uri): BitmapFactory.Options? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            openStream(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            opts
        } catch (t: Throwable) {
            Timber.w(t, "BlockScreenImageLoader: probeBounds failed for %s", uri)
            null
        }
    }

    /**
     * Decode the actual bitmap with the computed sample size.
     *
     * On Android 9+ we prefer [ImageDecoder] because it handles EXIF rotation
     * and partial-decode failures more gracefully than BitmapFactory. On older
     * APIs we fall back to BitmapFactory.decodeStream.
     */
    private fun decodeSampled(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeViaImageDecoder(context, uri)
        } else {
            decodeViaBitmapFactory(context, uri, sampleSize)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeViaImageDecoder(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Reuse ImageDecoder's built-in downsampling to hit our target.
                val size = info.size
                val longest = maxOf(size.width, size.height)
                if (longest > TARGET_MAX_EDGE_PX) {
                    val ratio = longest.toFloat() / TARGET_MAX_EDGE_PX.toFloat()
                    decoder.setTargetSampleSize(ratio.toInt().coerceAtLeast(1))
                }
                // Use low-memory policy (RGB_565) for opaque photos. The block
                // screen background is dark; minor banding is acceptable, and
                // it halves memory which matters because the block screen is
                // shown frequently.
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }
        } catch (t: Throwable) {
            Timber.w(t, "BlockScreenImageLoader: ImageDecoder failed for %s — trying BitmapFactory", uri)
            decodeViaBitmapFactory(context, uri, 1)
        }
    }

    private fun decodeViaBitmapFactory(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            // Prefer RGB_565 for opaque photos to halve memory. The block
            // screen background is dark; minor banding is acceptable.
            inPreferredConfig = Bitmap.Config.RGB_565
            // Don't scale based on density — we want raw pixels.
            inScaled = false
        }
        return try {
            openStream(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (t: Throwable) {
            Timber.w(t, "BlockScreenImageLoader: BitmapFactory failed for %s", uri)
            null
        }
    }

    /**
     * Open an InputStream for the URI. Handles both content:// and file://
     * schemes via the ContentResolver.
     */
    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            Timber.w(t, "BlockScreenImageLoader: openInputStream failed for %s", uri)
            null
        }
    }

    /**
     * Compute the inSampleSize so that the decoded bitmap's longest edge is
     * at most [TARGET_MAX_EDGE_PX]. Mirrors the official Android sample.
     */
    private fun computeSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val longestEdge = maxOf(width, height)
        while (longestEdge / sampleSize > TARGET_MAX_EDGE_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Convenience helper: read just the dimensions of the source as a
     * [Size]. Useful for the settings preview card to compute aspect ratio
     * without decoding the full bitmap.
     */
    fun probeSize(context: Context, uriOrPath: String?): Size? {
        if (uriOrPath.isNullOrBlank()) return null
        return try {
            val uri = parseUriOrPath(uriOrPath)
            val opts = probeBounds(context, uri) ?: return null
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            Size(opts.outWidth, opts.outHeight)
        } catch (t: Throwable) {
            Timber.w(t, "BlockScreenImageLoader: probeSize failed")
            null
        }
    }
}
