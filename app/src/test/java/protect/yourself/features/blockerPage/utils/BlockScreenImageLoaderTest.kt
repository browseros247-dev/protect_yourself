package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [BlockScreenImageLoader].
 *
 * We only test the pure-logic helpers here. The actual bitmap decode
 * requires an Android Context + ContentResolver and is exercised by
 * the instrumentation test suite.
 */
class BlockScreenImageLoaderTest {

    @Test
    fun `isContentUri returns false for null`() {
        assertThat(BlockScreenImageLoader.isContentUri(null)).isFalse()
    }

    @Test
    fun `isContentUri returns false for blank`() {
        assertThat(BlockScreenImageLoader.isContentUri("")).isFalse()
        assertThat(BlockScreenImageLoader.isContentUri("   ")).isFalse()
    }

    @Test
    fun `isContentUri returns true for content scheme`() {
        assertThat(BlockScreenImageLoader.isContentUri("content://media/external/images/media/42"))
            .isTrue()
    }

    @Test
    fun `isContentUri returns true for file scheme`() {
        assertThat(BlockScreenImageLoader.isContentUri("file:///sdcard/Pictures/motivation.jpg"))
            .isTrue()
    }

    @Test
    fun `isContentUri returns false for filesystem path`() {
        // Legacy entries (pre-fix) were stored as plain filesystem paths
        // without a scheme. The loader still handles these via
        // Uri.fromFile(), but isContentUri() correctly reports false so
        // callers can distinguish "modern URI" from "legacy path".
        assertThat(BlockScreenImageLoader.isContentUri("/sdcard/Pictures/motivation.jpg"))
            .isFalse()
    }

    @Test
    fun `isContentUri returns false for android resource scheme`() {
        // android.resource:// URIs are NOT considered "content URIs" by
        // isContentUri() — they're handled differently by the loader
        // (treated as a Uri directly, not as a filesystem path).
        assertThat(BlockScreenImageLoader.isContentUri("android.resource://protect.yourself/drawable/icon"))
            .isFalse()
    }

    @Test
    fun `MAX_SOURCE_BYTES is 20 MB`() {
        // 20 MB cap — picking a 50 MB raw photo should be refused.
        assertThat(BlockScreenImageLoader.MAX_SOURCE_BYTES).isEqualTo(20L * 1024L * 1024L)
    }

    @Test
    fun `TARGET_MAX_EDGE_PX is 768`() {
        // Crisp on a 1080p block screen, ~1.5 MB in ARGB_8888.
        assertThat(BlockScreenImageLoader.TARGET_MAX_EDGE_PX).isEqualTo(768)
    }
}
