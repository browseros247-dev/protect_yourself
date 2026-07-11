plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.kapt)
    // REMOVED: google.services plugin — generates FirebaseInitProvider config
    // that auto-initializes Firebase BEFORE Application.onCreate(), crashing
    // the app if the Firebase project is invalid/misconfigured.
    // Firebase can be re-added later when properly configured.
}

android {
    namespace = "protect.yourself"
    compileSdk = 35

    defaultConfig {
        applicationId = "protect.yourself"
        minSdk = 26
        targetSdk = 35
        versionCode = 45
        versionName = "1.0.45"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Room schema export
        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
                arg("room.incremental", "true")
                arg("room.expandProjection", "true")
            }
        }

        // Custom app name placeholder
        manifestPlaceholders["appLabel"] = "Protect Yourself"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            // No applicationIdSuffix — Firebase google-services.json only has
            // 'protect.yourself' package. User can add a second app to Firebase
            // for 'protect.yourself.debug' if side-by-side install is needed.
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Protect Yourself DEBUG"
        }
        release {
            isDebuggable = false
            // Disable R8 minification to fit in memory-constrained build environments.
            // User can re-enable for production Play Store builds.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // For rebuild, sign with debug keystore (user can re-sign)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-Xjvm-default=all"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Disable viewBinding + dataBinding (not used in Compose-only rebuild)
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.material)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.livedata)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Biometric
    implementation(libs.androidx.biometric)

    // Browser (custom tabs) — removed, not used
    // implementation(libs.androidx.browser)

    // Other AndroidX
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment.ktx)
    // implementation(libs.androidx.viewpager2)  // removed, not used
    // implementation(libs.androidx.recyclerview)  // removed, not used
    // implementation(libs.androidx.preference.ktx)  // removed, not used

    // Firebase — ALL REMOVED
    // FirebaseInitProvider auto-initializes Firebase BEFORE Application.onCreate()
    // and crashes the app if the project config is invalid.
    // The app's core features (blocking, VPN, Stop Me, Streak, widgets) don't need Firebase.
    // Firebase (Auth, Firestore, Messaging) can be re-added later when properly configured.
    // implementation(platform(libs.firebase.bom))
    // implementation(libs.firebase.auth.ktx)
    // implementation(libs.firebase.firestore.ktx)
    // implementation(libs.firebase.messaging.ktx)
    // implementation(libs.firebase.config.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // implementation(libs.kotlinx.coroutines.play.services)  // removed with Firebase

    // UI libraries
    implementation(libs.lottie.compose)
    // implementation(libs.image.cropper)  // removed, not used in code
    // implementation(libs.ratingbar)  // removed, not used (using built-in RatingBar)
    // implementation(libs.androidx.glance.appwidget)  // removed, using traditional widgets
    // implementation(libs.androidx.glance.material3)  // removed

    // Utility
    implementation(libs.timber)
    // Splitties removed - only available on JitPack, not used in rebuild
    implementation(libs.joda)
    implementation(libs.gson)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
