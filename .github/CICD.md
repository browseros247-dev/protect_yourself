# CI/CD Pipeline

This document describes the Continuous Integration and Continuous Deployment
pipeline for the `protect_yourself` Android app.

## Pipeline Overview

```
                    ┌──────────────────┐
                    │  Push to branch  │
                    │   or PR opened   │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────┐
        │   CI     │  │  Code    │  │ Security │
        │ workflow │  │ Quality  │  │ workflow │
        └────┬─────┘  └────┬─────┘  └────┬─────┘
             │              │              │
             ▼              ▼              ▼
       ┌─────────────────────────────────────┐
       │  PR Validation (merge-blocking)    │
       │  - compile + unit tests            │
       │  - lint (errors only)              │
       │  - branch up-to-date check         │
       └─────────────────┬───────────────────┘
                         │ (after merge to main)
                         ▼
                ┌──────────────────┐
                │  Tag pushed      │
                │  (v1.0.x)        │
                └────────┬─────────┘
                         ▼
                ┌──────────────────┐
                │ Release workflow │
                │ - build signed APK │
                │ - generate SBOM   │
                │ - create Release  │
                └──────────────────┘
```

## Workflows

### 1. CI (`.github/workflows/ci.yml`)

Runs on every push to `main`/`develop` and on every PR.

| Job | Purpose | Timeout |
|---|---|---|
| `code-quality` | Detekt + ktlint → SARIF reports uploaded to code scanning | 15 min |
| `compile` | Compile debug + release Kotlin sources | 20 min |
| `unit-tests` | Run `testDebugUnitTest`, publish JUnit report as check run | 30 min |
| `lint` | Android Lint, fail on errors, upload SARIF | 25 min |
| `build-apks` | Assemble debug + release APKs, upload as artifacts | 40 min |
| `security-scan` | OWASP dependency-check, upload HTML report | 10 min |

### 2. PR Validation (`.github/workflows/pr-validation.yml`)

Runs on every PR. Designed for fast feedback (< 10 min target).

**Merge-blocking checks** (configure in GitHub branch protection):

- `compile-and-test` — compile + unit tests in one job (saves cold-start time)
- `lint-critical` — Android Lint, fail on ERROR only
- `up-to-date` — fails if PR is behind base branch
- `pr-metadata` — checks labels and PR size

### 3. Code Quality (`.github/workflows/code-quality.yml`)

Runs on push to `main`/`develop`, on PRs (only when `.kt` files change),
and nightly at 03:00 UTC.

- **Detekt** — static analysis with custom rules in `config/detekt/detekt.yml`
- **ktlint** — Kotlin style enforcement
- **API surface check** — comments on PRs that modify public functions

All results upload as SARIF to GitHub's code scanning UI.

### 4. Security (`.github/workflows/security.yml`)

| Tool | Schedule | On PR | On Push |
|---|---|---|---|
| CodeQL (SAST) | Weekly | Skip | Yes |
| Trivy (SCA) | Weekly | Skip | Yes |
| Gitleaks (secrets) | Weekly | Yes | Yes |
| Dependency Review | — | Yes | Skip |
| MobSF (Android-specific) | Weekly | Skip | Skip |

### 5. Release (`.github/workflows/release.yml`)

Triggers:
- Push of a tag matching `v*.*.*` (e.g. `v1.0.55`)
- Manual dispatch from Actions tab

Pipeline:
1. Determine version (from tag or input)
2. Patch `build.gradle.kts` with the release version
3. Decode release keystore from GitHub secret
4. Build signed release APK
5. Verify APK signature with `apksigner`
6. Generate CycloneDX SBOM
7. Create GitHub Release with APK + mapping.txt + SBOM attached
8. Auto-generate changelog from commits since last tag

## Required GitHub Secrets

Configure these in **Settings → Secrets and variables → Actions**:

| Secret | Required for | Description |
|---|---|---|
| `RELEASE_KEYSTORE` | Release workflow | Base64-encoded release keystore file |
| `RELEASE_KEYSTORE_PASS` | Release workflow | Keystore password |
| `RELEASE_KEY_ALIAS` | Release workflow | Key alias (e.g. `release`) |
| `RELEASE_KEY_PASS` | Release workflow | Key password |

To generate the keystore:

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias release -keyalg RSA -keysize 2048 -validity 10000

# Encode for GitHub secret:
base64 -i release.keystore -o release.keystore.b64
```

If secrets are missing, the release workflow falls back to debug signing and
tags the release as "Unsigned (debug-signed)".

## Branch Protection Rules

Configure in **Settings → Branches → Branch protection rules** for `main`:

### Require status checks to pass before merging

- [x] Require status checks to pass before merging
- [x] Require branches to be up to date before merging

**Required status checks:**

- `compile-and-test` (from PR Validation)
- `lint-critical` (from PR Validation)
- `up-to-date` (from PR Validation)

### Other recommended settings

- [x] Require conversation resolution before merging
- [x] Require linear history (avoids merge commits on `main`)
- [x] Do not allow bypassing the above settings
- [x] Restrict who can push to matching branches (admins only)

## Local Development

### Run the same checks locally

```bash
# Code quality
./gradlew detekt ktlintCheck

# Lint
./gradlew lintDebug

# Unit tests
./gradlew testDebugUnitTest

# Build APKs
./gradlew assembleDebug assembleRelease
```

### Pre-commit hook (optional, recommended)

Install a git hook that runs `detekt` and `ktlintCheck` on staged files:

```bash
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
echo "Running pre-commit checks..."
./gradlew detekt ktlintCheck --quiet
if [ $? -ne 0 ]; then
  echo "❌ Pre-commit checks failed. Fix the issues above and try again."
  exit 1
fi
echo "✅ Pre-commit checks passed."
EOF
chmod +x .git/hooks/pre-commit
```

## Artifact Retention

| Artifact | Retention | Reason |
|---|---|---|
| APKs (debug + release) | 30 days | QA testing window |
| Test reports | 30 days | Debugging failed runs |
| Lint reports | 30 days | Trend analysis |
| Mapping files | 90 days | Play Console requirement |
| SBOM | 365 days | Supply-chain audit |
| Detekt/ktlint reports | 14 days | Quick review, then GitHub code scanning |

## Caching Strategy

The pipeline caches the following to reduce build times:

| Cache | Key | Size |
|---|---|---|
| Gradle dependencies | `gradle-${OS}-${hash(*.gradle*, libs.versions.toml)}` | ~500 MB |
| Gradle wrapper | Included in Gradle cache | ~50 MB |
| Konan (Kotlin/Native) | `konan-${OS}-${hash(libs.versions.toml)}` | ~100 MB |
| Android SDK | Provided by `setup-android` action | ~1 GB |

**Expected cache hit rate:** 90%+ on PR runs, 100% on main runs (until
`libs.versions.toml` changes).

## Performance Targets

| Metric | Target | Current |
|---|---|---|
| PR validation (cold cache) | < 15 min | ~12 min |
| PR validation (warm cache) | < 8 min | ~6 min |
| Full CI (cold cache) | < 40 min | ~35 min |
| Full CI (warm cache) | < 25 min | ~20 min |
| Release build | < 15 min | ~10 min |

## Monitoring and Alerts

- **Failures**: GitHub sends email to the commit author on any workflow failure.
- **Slack integration** (optional): add a Slack webhook to post failures to a
  channel. See `.github/workflows/notify.yml` (not yet implemented).
- **Code scanning alerts**: view at `Security → Code scanning alerts`.
- **Dependabot alerts**: view at `Security → Dependabot`.

## Troubleshooting

### Build failed: "Could not determine the dependencies of task"

**Cause**: Gradle cache corruption.

**Fix**: Clear the cache and rerun:

```bash
rm -rf ~/.gradle/caches .gradle
./gradlew --refresh-dependencies assembleDebug
```

In CI, add `cache: false` to the `actions/cache` step temporarily, run once,
then re-enable.

### Detekt fails on generated code (Room, DataStore)

**Cause**: Generated code in `app/build/generated/` is being scanned.

**Fix**: The `detekt` block in `build.gradle.kts` already excludes `build/`.
If the issue persists, add the specific path to the `source` filter:

```kotlin
detekt {
    source = files(
        "app/src/main/java",
        "app/src/test/java",
        // Exclude generated sources explicitly
    ).filter { !it.path.contains("/generated/") }
}
```

### ktlint reports "File not formatted correctly"

**Fix**: Run `./gradlew ktlintFormat` to auto-fix style issues.

### Release workflow can't find the keystore

**Cause**: The `RELEASE_KEYSTORE` secret is empty or malformed.

**Fix**:
1. Verify the secret exists in repo settings.
2. Re-encode the keystore with `base64 -i release.keystore -o release.keystore.b64`.
3. Copy the **entire** contents of `release.keystore.b64` into the secret.

### APK signature verification fails

**Cause**: Keystore password or alias mismatch.

**Fix**: Verify the secrets match the keystore:

```bash
keytool -list -v -keystore release.keystore
# Enter the keystore password — should list the alias and cert.
```

Compare the alias and password with `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASS` secrets.

## Adding a New Workflow

1. Create a new file in `.github/workflows/`.
2. Follow the naming convention: `kebab-case.yml`.
3. Include:
   - `name:` (human-readable)
   - `on:` (triggers)
   - `concurrency:` (group + cancel-in-progress)
   - `permissions:` (least-privilege)
   - `env:` (GRADLE_OPTS, JAVA_TOOL_OPTIONS)
   - Use the `./.github/actions/setup-android` composite action for JDK + SDK.
4. Add a section to this document.
5. Test with `workflow_dispatch` before enabling on `push`/`pull_request`.
