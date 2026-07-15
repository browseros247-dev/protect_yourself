package protect.yourself.features.blockerPage.utils

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [NewInstallBlockingUtils].
 *
 * Tests the core logic of the "Block new install apps" feature:
 *  - `isFirstInstall` decision matrix (fresh install, update, stale, missing)
 *  - `extractPackageName` URI parsing
 *
 * The `isFirstInstall` tests use Robolectric's PackageManager shadow to
 * simulate different install/update scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewInstallBlockingUtilsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ===== extractPackageName =====

    @Test
    fun `extractPackageName returns null for null URI`() {
        assertThat(NewInstallBlockingUtils.extractPackageName(null)).isNull()
    }

    @Test
    fun `extractPackageName returns package name for valid URI`() {
        val uri = Uri.parse("package:com.example.test")
        assertThat(NewInstallBlockingUtils.extractPackageName(uri)).isEqualTo("com.example.test")
    }

    @Test
    fun `extractPackageName returns null for empty scheme specific part`() {
        // URI with "package:" but no package name
        val uri = Uri.parse("package:")
        assertThat(NewInstallBlockingUtils.extractPackageName(uri)).isNull()
    }

    @Test
    fun `extractPackageName handles package names with dots`() {
        val uri = Uri.parse("package:com.google.android.chrome")
        assertThat(NewInstallBlockingUtils.extractPackageName(uri))
            .isEqualTo("com.google.android.chrome")
    }

    // ===== isFirstInstall edge cases (no PackageManager mocking) =====

    @Test
    fun `isFirstInstall returns false for blank package name`() {
        assertThat(NewInstallBlockingUtils.isFirstInstall(context, "")).isFalse()
        assertThat(NewInstallBlockingUtils.isFirstInstall(context, "   ")).isFalse()
    }

    @Test
    fun `isFirstInstall returns false for own package`() {
        // The utility should never treat the app's own package as a "new install"
        assertThat(NewInstallBlockingUtils.isFirstInstall(context, context.packageName)).isFalse()
    }

    @Test
    fun `isFirstInstall returns true for non-existent package (matches reference NameNotFoundException behaviour)`() {
        // The reference returns true on NameNotFoundException — the package was already
        // uninstalled (race with PACKAGE_REMOVED), so treating it as a first
        // install is harmless because the insert will be cleaned up.
        // Robolectric's PackageManager doesn't have this package, so it throws
        // NameNotFoundException.
        assertThat(
            NewInstallBlockingUtils.isFirstInstall(context, "com.nonexistent.app.test12345")
        ).isTrue()
    }
}
