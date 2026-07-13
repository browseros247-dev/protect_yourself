# Development Environment Setup

This directory contains everything needed to recreate the Protect-Yourself
build environment from scratch on a fresh machine. The setup is fully
automated — one script downloads and installs the correct JDK, Android SDK,
and all required SDK packages, then verifies the build works.

## Quick Start (one command)

```bash
cd protect_yourself/
./setup/setup-env.sh
```

That's it. After the script finishes (3–5 minutes), you can build the app:

```bash
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon :app:testDebugUnitTest
```

## What the script does

| Step | Action | Downloads |
|------|--------|-----------|
| 1 | Downloads + extracts **JDK 17** (Temurin 17.0.13+11) | ~190 MB |
| 2 | Downloads + extracts **Android cmdline-tools** | ~150 MB |
| 3 | Accepts SDK licenses + installs SDK packages | ~500 MB |
| 4 | Writes `local.properties` pointing to the SDK | — |
| 5 | Runs `gradlew help` to verify the build works | — |

**Total download**: ~840 MB (one-time)
**Total disk usage**: ~1.5 GB (JDK + SDK + Gradle cache)

## Prerequisites

The host machine needs these commands available:

```bash
curl   # HTTP downloads
unzip  # Extract Android cmdline-tools
tar    # Extract JDK
java   # Any Java (the script downloads the correct JDK 17 itself)
```

On Debian/Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y curl unzip tar
```

## Versions (pinned)

These versions are pinned in `setup-env.sh` for reproducibility. Update them
in the script if you need to upgrade.

| Component | Version | Notes |
|-----------|---------|-------|
| JDK | Temurin 17.0.13+11 | Required for Kotlin 2.0.21 + AGP 8.7.2 |
| Android cmdline-tools | 12.0 | Latest as of 2026-07 |
| Android Platform | android-35 (API 35) | Matches `compileSdk = 35` |
| Android Build-tools | 35.0.0 | Matches `compileSdk = 35` |
| Android platform-tools | latest | For `adb` |
| Gradle | 8.10.2 | Auto-downloaded by the Gradle wrapper |
| AGP | 8.7.2 | Declared in `gradle/libs.versions.toml` |
| Kotlin | 2.0.21 | Declared in `gradle/libs.versions.toml` |

## Custom install location

By default, the script installs to `/home/z/my-project/`. To use a different
location:

```bash
./setup/setup-env.sh --install-dir /opt/android-dev
```

This installs:
- JDK → `/opt/android-dev/tools/jdk-17.0.13+11/`
- SDK → `/opt/android-dev/android-sdk/`

## Skipping components

If you already have a JDK or SDK installed, skip those steps:

```bash
# Use system JDK, only install SDK
./setup/setup-env.sh --skip-jdk

# Use system SDK, only install JDK
./setup/setup-env.sh --skip-sdk

# Use both system JDK + SDK (just write local.properties + verify)
./setup/setup-env.sh --skip-jdk --skip-sdk
```

When skipping, set the environment variables before running:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./setup/setup-env.sh --skip-jdk --skip-sdk
```

## Idempotent — safe to re-run

The script detects existing installations and skips re-downloading. If your
environment is partially set up, just re-run the script — it will only
download what's missing.

```bash
# First run: downloads everything
./setup/setup-env.sh

# Second run: detects existing installs, skips downloads, just verifies
./setup/setup-env.sh
```

## Files in this directory

| File | Purpose |
|------|---------|
| `setup-env.sh` | Main setup script — run this first |
| `sdk-packages.txt` | List of SDK packages to install (reference) |
| `README.md` | This file |
| `local.properties.template` | Template for `local.properties` (for manual setup) |

## Troubleshooting

### `gradlew help` fails with "Could not load module"

This is a Kotlin daemon crash caused by low memory. The `gradle.properties`
file is already configured with `kotlin.compiler.execution.strategy=in-process`
and `-Xmx2048m` to work around this. If it still fails:

```bash
# Increase heap in gradle.properties
sed -i 's/-Xmx2048m/-Xmx3072m/' gradle.properties
./gradlew --no-daemon :app:compileDebugKotlin
```

### `sdkmanager: command not found` after script

The script adds `cmdline-tools/latest/bin` to `PATH` for the current session.
To make it permanent, add to your `~/.bashrc`:

```bash
export JAVA_HOME=/home/z/my-project/tools/jdk-17.0.13+11
export ANDROID_HOME=/home/z/my-project/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### Out of disk space

The JDK + SDK + Gradle cache need ~3 GB. Check with:

```bash
df -h /home/z/my-project
du -sh /home/z/my-project/tools /home/z/my-project/android-sdk ~/.gradle
```

If disk is full, clear the Gradle cache:

```bash
rm -rf ~/.gradle/caches
```

### Android SDK license not accepted

Re-run:

```bash
yes | sdkmanager --licenses
```

### Build fails on Android 12+ with "Exact alarm permission denied"

The app already handles this (`canScheduleExactAlarms()` check in
`StopMeManager.kt`), but if you need to grant the permission for testing:

```bash
adb shell pm grant protect.yourself android.permission.SCHEDULE_EXACT_ALARM
```

## Permanent environment setup (optional)

To make the environment available in every terminal session, add these lines
to `~/.bashrc` or `~/.zshrc`:

```bash
# Protect-Yourself dev environment
export JAVA_HOME=/home/z/my-project/tools/jdk-17.0.13+11
export ANDROID_HOME=/home/z/my-project/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Then reload:

```bash
source ~/.bashrc
```

## Files NOT included in this backup (too large)

These are downloaded by the setup script — they are NOT committed to the
repository:

| Component | Size | Source |
|-----------|------|--------|
| JDK 17 | ~190 MB | Adoptium Temurin |
| Android SDK | ~1.5 GB | Google |
| Gradle distribution | ~120 MB | Auto-downloaded by wrapper |
| Gradle dependency cache | ~500 MB | Auto-populated on first build |

The total repository size (excluding these) is ~25 MB (mostly the NopoX
reference APK + Lottie assets).

## CI/CD usage

This script is also suitable for CI/CD pipelines. Example GitHub Actions
snippet:

```yaml
- name: Setup build environment
  run: ./setup/setup-env.sh --install-dir $HOME/dev

- name: Build debug APK
  run: ./gradlew --no-daemon :app:assembleDebug
  env:
    JAVA_HOME: $HOME/dev/tools/jdk-17.0.13+11
    ANDROID_HOME: $HOME/dev/android-sdk
```
