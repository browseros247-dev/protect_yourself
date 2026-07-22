package protect.yourself.features.protectedApps

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for the v1.0.69 Accessibility Persistence fixes
 * (A11Y-PERSIST-01..03) — "Accessibility Service disabled automatically".
 *
 * ## What these tests pin
 *
 *  - **A11Y-PERSIST-01**: `selfHealAccessibilityService` /
 *    `guardAllProtectedServices` are `@Synchronized` and perform canonical,
 *    deduped, order-preserving rewrites — racing callers can no longer
 *    produce malformed lists like `"A:B:B"`, and healthy lists are left
 *    byte-identical (no write churn → no ContentObserver feedback loop).
 *  - **A11Y-PERSIST-02**: component-form tolerant matching — Android/OEMs may
 *    store either `pkg/pkg.features.Svc` (full) or `pkg/.features.Svc`
 *    (short) in `enabled_accessibility_services`; both must match.
 *  - **A11Y-PERSIST-03**: the OEM master-switch vector — `accessibility_enabled=0`
 *    while our entry stays in the list — is detected (effective check) AND
 *    repaired (master written back to 1) even when the list itself is fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityPersistTest {

    private val keyServices = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val keyMaster = Settings.Secure.ACCESSIBILITY_ENABLED

    private lateinit var app: Application
    private lateinit var own: String

    /** Short storage form of our component: `pkg/.relative.ClassName`. */
    private val ownShort: String
        get() {
            val cls = protect.yourself.features.blockerPage.service
                .MyAccessibilityService::class.java.name
            val pkg = app.packageName
            check(cls.startsWith(pkg)) { "unexpected class/package mismatch" }
            return "$pkg/${cls.removePrefix(pkg)}"
        }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        own = AccessibilityPersistUtils.ownComponentFlat(app)
        // Deterministic starting state: empty list, master ON.
        Settings.Secure.putString(app.contentResolver, keyServices, "")
        Settings.Secure.putInt(app.contentResolver, keyMaster, 1)
        // Clean registry so guardAll tests are isolated.
        for (c in ProtectedAppsRegistry.getComponents(app).toList()) {
            ProtectedAppsRegistry.remove(app, c)
        }
    }

    // ============================================================================
    // A11Y-PERSIST-03 — master switch vector (the "silently dies" case)
    // ============================================================================

    @Test
    fun `effective check - true when entry present and master on`() {
        Settings.Secure.putString(app.contentResolver, keyServices, own)
        Settings.Secure.putInt(app.contentResolver, keyMaster, 1)

        assertThat(AccessibilityPersistUtils.isOwnServiceEnabled(app)).isTrue()
        assertThat(AccessibilityPersistUtils.isAccessibilityMasterEnabled(app)).isTrue()
        assertThat(AccessibilityPersistUtils.isAccessibilityEffectivelyEnabled(app)).isTrue()
    }

    @Test
    fun `effective check - FALSE when entry present but master flipped off (OEM vector)`() {
        // This is the reported bug state: Settings UI still shows our service
        // listed, entry-only checks report "enabled", but blocking is dead.
        Settings.Secure.putString(app.contentResolver, keyServices, own)
        Settings.Secure.putInt(app.contentResolver, keyMaster, 0)

        assertThat(AccessibilityPersistUtils.isOwnServiceEnabled(app)).isTrue() // entry IS present
        assertThat(AccessibilityPersistUtils.isAccessibilityMasterEnabled(app)).isFalse()
        assertThat(AccessibilityPersistUtils.isAccessibilityEffectivelyEnabled(app)).isFalse()
    }

    @Test
    fun `selfHeal - repairs master switch when entry present but master flipped`() {
        Settings.Secure.putString(app.contentResolver, keyServices, own)
        Settings.Secure.putInt(app.contentResolver, keyMaster, 0)

        val healed = AccessibilityPersistUtils.selfHealAccessibilityService(app)

        assertThat(healed).isTrue()
        // Master restored…
        assertThat(Settings.Secure.getInt(app.contentResolver, keyMaster, -1)).isEqualTo(1)
        // …and the service list left EXACTLY as seeded (no churn).
        assertThat(Settings.Secure.getString(app.contentResolver, keyServices)).isEqualTo(own)
    }

    // ============================================================================
    // A11Y-PERSIST-01 — canonical, deduped, exactly-once writes
    // ============================================================================

    @Test
    fun `selfHeal - appends own entry exactly once when missing`() {
        val other = "com.vendor.tool/com.vendor.tool.VendorA11yService"
        Settings.Secure.putString(app.contentResolver, keyServices, other)

        assertThat(AccessibilityPersistUtils.selfHealAccessibilityService(app)).isTrue()

        // Own entry appended at the end, other services untouched.
        val after = Settings.Secure.getString(app.contentResolver, keyServices)
        assertThat(after).isEqualTo("$other:$own")

        // Second invocation is a no-op: fast path, value unchanged (exactly once).
        assertThat(AccessibilityPersistUtils.selfHealAccessibilityService(app)).isTrue()
        assertThat(Settings.Secure.getString(app.contentResolver, keyServices)).isEqualTo(after)
    }

    @Test
    fun `selfHeal - dedupes racing duplicates into canonical single entries`() {
        val other = "com.vendor.tool/com.vendor.tool.VendorA11yService"
        // The malformed state the OLD racy implementation could produce.
        Settings.Secure.putString(app.contentResolver, keyServices, "$other:$own:$other:$own")
        // Force the repair path (fast path would pass on entry+master alone).
        Settings.Secure.putInt(app.contentResolver, keyMaster, 0)

        assertThat(AccessibilityPersistUtils.selfHealAccessibilityService(app)).isTrue()

        // Canonical rewrite: order preserved, each component exactly once,
        // master repaired.
        assertThat(Settings.Secure.getString(app.contentResolver, keyServices))
            .isEqualTo("$other:$own")
        assertThat(Settings.Secure.getInt(app.contentResolver, keyMaster, -1)).isEqualTo(1)
    }

    @Test
    fun `guardAllProtectedServices - canonical union, idempotent, no duplicates`() {
        val protected3p = "com.example.pwdmgr/com.example.pwdmgr.AutofillA11yService"
        ProtectedAppsRegistry.add(app, protected3p)
        Settings.Secure.putString(app.contentResolver, keyServices, own)

        AccessibilityPersistUtils.guardAllProtectedServices(app)
        val afterFirst = Settings.Secure.getString(app.contentResolver, keyServices)
        assertThat(afterFirst).isEqualTo("$own:$protected3p")

        // Idempotent: second call must not append a duplicate…
        AccessibilityPersistUtils.guardAllProtectedServices(app)
        assertThat(Settings.Secure.getString(app.contentResolver, keyServices)).isEqualTo(afterFirst)
        // …even in short form — pre-seed short form and protect the full form.
        val shortProtected = "com.example.pwdmgr/.AutofillA11yService"
        ProtectedAppsRegistry.remove(app, protected3p)
        Settings.Secure.putString(app.contentResolver, keyServices, "$own:$shortProtected")
        AccessibilityPersistUtils.guardAllProtectedServices(app)
        assertThat(Settings.Secure.getString(app.contentResolver, keyServices))
            .isEqualTo("$own:$shortProtected")
    }

    // ============================================================================
    // A11Y-PERSIST-02 — component form tolerance
    // ============================================================================

    @Test
    fun `isOwnServiceEnabled - matches the SHORT storage form`() {
        assertThat(ownShort).isNotEqualTo(own) // sanity: forms genuinely differ
        Settings.Secure.putString(app.contentResolver, keyServices, ownShort)
        assertThat(AccessibilityPersistUtils.isOwnServiceEnabled(app)).isTrue()
    }

    @Test
    fun `componentEntriesMatch - full vs short form`() {
        assertThat(AccessibilityPersistUtils.componentEntriesMatch(own, ownShort)).isTrue()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch(ownShort, own)).isTrue()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch(own, own)).isTrue()
    }

    @Test
    fun `componentEntriesMatch - rejects different or malformed components without throwing`() {
        assertThat(
            AccessibilityPersistUtils.componentEntriesMatch(own, "com.other.app/com.other.app.Svc")
        ).isFalse()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch(null, own)).isFalse()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch(own, null)).isFalse()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch("", "")).isFalse()
        assertThat(AccessibilityPersistUtils.componentEntriesMatch("garbage", own)).isFalse()
        // Identical unparseable strings still match on exact equality.
        assertThat(AccessibilityPersistUtils.componentEntriesMatch("garbage", "garbage")).isTrue()
    }

    // ============================================================================
    // Cross-cutting: blocked write without permission stays non-destructive
    // ============================================================================

    @Test
    fun `selfHealSafe - never throws and never truncates foreign entries`() {
        val otherA = "com.vendor.a/com.vendor.a.SvcA"
        val otherB = "com.vendor.b/com.vendor.b.SvcB"
        Settings.Secure.putString(app.contentResolver, keyServices, "$otherA:$otherB")

        AccessibilityPersistUtils.selfHealSafe(app)

        // Foreign entries preserved (own may be appended — that's the point).
        val after = Settings.Secure.getString(app.contentResolver, keyServices)
        assertThat(after).contains(otherA)
        assertThat(after).contains(otherB)
    }
}
