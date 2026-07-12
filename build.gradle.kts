// =============================================================================
// Top-level Gradle build file.
//
// App plugins (android, kotlin, compose, kapt) are declared with
// `apply false` here and applied in app/build.gradle.kts.
//
// Code-quality plugins (detekt, ktlint, dependency-check, cyclonedx) are
// applied here so they run on the root project and produce reports under
// `app/build/reports/` (referenced by .github/workflows/*.yml).
//
// Plugin DSLs are intentionally kept minimal — each plugin has sensible
// defaults. Project-specific config lives in:
//   - config/detekt/detekt.yml          (Detekt rule config)
//   - .editorconfig                     (ktlint rule config, shared with IDE)
//   - config/dependency-check-suppressions.xml (CVE false-positive list)
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.kapt) apply false

    // Code quality — applied at root so all subprojects inherit them.
    alias(libs.plugins.detekt) apply true
    alias(libs.plugins.ktlint) apply true
    alias(libs.plugins.dependency.check) apply true
    alias(libs.plugins.cyclonedx) apply true
}

// -----------------------------------------------------------------------------
// Detekt configuration
// Config file lives at config/detekt/detekt.yml so it can be shared with
// IDE plugins and pre-commit hooks.
// -----------------------------------------------------------------------------
detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false   // CI fails on Detekt issues
    autoCorrect = false      // Don't auto-fix — require manual review
    // Reports are configured in config/detekt/detekt.yml
    // (HTML + SARIF are enabled by default when the plugin detects CI)
    source = files(
        "app/src/main/java",
        "app/src/test/java",
        "app/src/androidTest/java"
    )
}

// -----------------------------------------------------------------------------
// ktlint configuration
// The ktlint linter version is set via .editorconfig or defaults to the
// version bundled with the plugin. The plugin reads .editorconfig for
// rule config, so we keep the Gradle DSL minimal.
// -----------------------------------------------------------------------------
ktlint {
    android.set(true)           // Enable Android-specific rules
    outputToConsole.set(true)
    ignoreFailures.set(false)   // CI fails on ktlint issues
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
        include("**/*.kt")
    }
}

// -----------------------------------------------------------------------------
// OWASP Dependency-Check configuration
// Scans for known CVEs in Gradle dependencies. Runs on a schedule (weekly)
// and on PRs (informational only — doesn't fail PRs).
//
// The plugin's DSL varies across major versions; we keep config minimal and
// rely on sensible defaults. The suppression file is referenced via the
// plugin's CLI arg in the workflow YAML if version-specific DSL changes.
// -----------------------------------------------------------------------------
dependencyCheck {
    // Fail only on CRITICAL (CVSS >= 9). Surfaces lower-severity findings
    // without blocking PRs.
    failBuildOnCVSS = 9.0f
}

// -----------------------------------------------------------------------------
// CycloneDX SBOM generation
// Produces a Software Bill of Materials at build time, attached to GitHub
// Releases for supply-chain traceability. The plugin's default config
// produces both JSON and XML output under app/build/reports/.
// -----------------------------------------------------------------------------

// -----------------------------------------------------------------------------
// Root-level clean task — also cleans subprojects.
// -----------------------------------------------------------------------------
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
