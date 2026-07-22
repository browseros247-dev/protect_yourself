package protect.yourself.perfOptimization

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * PERF-01/05 (v1.0.67) regression guards for the size/performance round.
 *
 * These read the real build files (unit-test working dir = app module) and
 * pin the properties that make the R8-enabled release build CORRECT:
 *
 *  - Release shrinking must stay enabled (the 15 MB→smaller APK depends on it).
 *  - Every reflection entry point must stay in proguard-rules.pro:
 *    Gson models (crash logger + backup envelope incl. Room entities),
 *    WorkManager workers (instantiated reflectively by persisted FQN),
 *    enum constants (JSON + Room TypeConverter values are name()-based).
 *  - Dropped dependencies must not silently come back.
 */
class ProguardRulesRegressionTest {

    private val moduleDir = File(".")
    private val proguard: String by lazy { File(moduleDir, "proguard-rules.pro").readText() }
    private val buildScript: String by lazy { File(moduleDir, "build.gradle.kts").readText() }

    @Test
    fun `release build keeps R8 shrinking enabled`() {
        // Locate the release block and assert both flags.
        val releaseBlock = buildScript.substringAfter("release {").substringBefore("        }")
        assertThat(releaseBlock).contains("isMinifyEnabled = true")
        assertThat(releaseBlock).contains("isShrinkResources = true")
    }

    @Test
    fun `release keeps English-only resource configurations`() {
        assertThat(buildScript).contains("resourceConfigurations += setOf(\"en\")")
    }

    @Test
    fun `gson backup models are kept`() {
        listOf("BackupEnvelope", "BackupTables", "BackupStats").forEach { model ->
            assertThat(proguard).contains("-keep class protect.yourself.features.backupRestore.$model { *; }")
        }
    }

    @Test
    fun `gson crash log models are kept`() {
        listOf("CrashLogEntry", "CrashLogExport", "Breadcrumb", "DeviceInfo").forEach { model ->
            assertThat(proguard).contains("-keep class protect.yourself.features.crashLog.$model { *; }")
        }
    }

    @Test
    fun `room entity field names are preserved for backup serialization`() {
        // BackupManager serializes entities via Gson — class keep alone is insufficient.
        assertThat(proguard).contains("-keepclassmembers @androidx.room.Entity class * { *; }")
    }

    @Test
    fun `workmanager workers keep names and reflective constructor`() {
        assertThat(proguard).contains("-keepnames class * extends androidx.work.ListenableWorker")
        assertThat(proguard).contains("public <init>(android.content.Context, androidx.work.WorkerParameters);")
    }

    @Test
    fun `enum constants in app package are preserved`() {
        // Enum names are persisted (Gson JSON + Room TypeConverters).
        assertThat(proguard).contains("-keepclassmembers enum protect.yourself.** { *; }")
    }

    @Test
    fun `unused dependencies stay removed`() {
        assertThat(buildScript).doesNotContain("implementation(libs.lottie.compose)")
        assertThat(buildScript).doesNotContain("implementation(libs.androidx.compose.runtime.livedata)")
    }
}
