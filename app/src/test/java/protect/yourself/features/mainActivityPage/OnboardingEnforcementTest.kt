package protect.yourself.features.mainActivityPage

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * OB-ENFORCE-01 (v1.0.72) static pins — required onboarding settings are
 * mandatory everywhere in the flow; the skippable design must not come back.
 *
 *  1. The permissions-step "Continue to App" button is gated on
 *     `allRequiredGranted` and the old skippable copy is gone.
 *  2. The app-state gate re-checks required permissions after terms, and
 *     re-opens onboarding DIRECTLY at the permissions checklist.
 *  3. The onResume re-check deliberately skips the permission gate
 *     (entry-only enforcement — no surprise mid-session bounce).
 */
class OnboardingEnforcementTest {

    private val mainJava = File(".", "src/main/java")

    private val mainActivity: String by lazy {
        File(mainJava, "protect/yourself/features/mainActivityPage/MainActivity.kt").readText()
    }

    // ---- permissions step gating --------------------------------------------

    @Test
    fun `continue to app button is disabled until required permissions are granted`() {
        assertThat(mainActivity).contains("val requiredReady = OnboardingPermissions.allRequiredGranted(rows)")
        assertThat(mainActivity).contains("enabled = requiredReady")
    }

    @Test
    fun `skippable guidance text is removed`() {
        assertThat(mainActivity).doesNotContain("You can continue without them")
        assertThat(mainActivity).doesNotContain("finish_with_missing")
    }

    @Test
    fun `mandatory guidance and recommended-stay-optional logging exist`() {
        assertThat(mainActivity).contains("Grant the Required permissions above to continue")
        assertThat(mainActivity).contains("finish_required_complete")
    }

    // ---- app-state gate ------------------------------------------------------

    @Test
    fun `app state gate rechecks required permissions after terms`() {
        assertThat(mainActivity).contains("OnboardingPermissions.allRequiredGranted(")
        assertThat(mainActivity).contains("!requiredPermissionsReady -> AppState.ONBOARDING")
        assertThat(mainActivity).contains("onboardingStartAtPermissions = termsAccepted && !requiredPermissionsReady")
    }

    @Test
    fun `gate evaluation crash is fail-open with crash log, not a hard lockout`() {
        assertThat(mainActivity).contains("Required-permission gate evaluation crashed (fail-open)")
        assertThat(mainActivity).contains("\"required-permission gate failed open\"")
    }

    @Test
    fun `onResume recheck skips permission gate - entry-only enforcement`() {
        assertThat(mainActivity).contains("checkAppState(enforceRequiredPermissions = false)")
        assertThat(mainActivity).contains("private fun checkAppState(enforceRequiredPermissions: Boolean = true)")
    }

    @Test
    fun `onboarding can start directly at the permissions step`() {
        assertThat(mainActivity).contains("startAtPermissions: Boolean")
        assertThat(mainActivity).contains("if (startAtPermissions) OnboardingStep.PERMISSIONS.name else OnboardingStep.TERMS.name")
    }
}
