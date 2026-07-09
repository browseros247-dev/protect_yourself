# Contributing to protect.yourself

Thanks for considering a contribution! This is a community-maintained rebuild of Protect Yourself.

## How to contribute

### Reporting bugs

1. Check existing issues at <https://github.com/258044aamm-Dev/Protect-Yourself/issues>
2. If not already reported, open a new issue with:
   - Device model + Android version
   - App version (find in Profile → About)
   - Steps to reproduce
   - Expected vs actual behavior
   - Logcat output (if possible)

### Suggesting features

Open an issue with the `enhancement` label. Describe:
- The problem you're trying to solve
- Your proposed solution
- Alternatives considered

### Submitting code

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes — follow the existing code style
4. Add tests for new functionality
5. Run tests: `./gradlew test`
6. Commit with a clear message (see below)
7. Push + open a Pull Request

### Commit message style

```
Phase X: Brief summary

- Detail 1
- Detail 2
```

Example:
```
Phase 6: Add biometric prompt for app lock

- Implement launchBiometricPrompt() with BiometricPrompt
- Wire to AppPasswordPage when Touch ID switch is ON
- Fallback to PIN/password if biometric unavailable
```

## Code style

- **Kotlin**: Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose**: Use Material 3 components; prefer stateless composables
- **Naming**:
  - Classes: PascalCase (`BlockerPageViewModel`)
  - Functions: camelCase (`toggleSwitch`)
  - Constants: SCREAMING_SNAKE_CASE (`EXTRA_BLOCK_PACKAGE`)
  - Packages: lowercase (`protect.yourself.features.blockerPage`)
- **Tests**: Method names in backticks with descriptive sentences
  - Example: `` `isDetectWord finds keyword in plain text`() ``

## Project structure

See [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the full architecture.

Key directories:
- `app/src/main/java/protect/yourself/core/` — Application class + DI container
- `app/src/main/java/protect/yourself/database/` — Room (9 entities, 9 DAOs)
- `app/src/main/java/protect/yourself/features/` — All UI features
- `app/src/main/java/protect/yourself/commons/` — Shared utilities
- `app/src/main/java/protect/yourself/theme/` — Compose theme

## Testing

```bash
# Unit tests (run on JVM — no device needed)
./gradlew test

# Instrumentation tests (need emulator or device)
./gradlew connectedAndroidTest
```

Aim for:
- Unit tests for all new utility functions
- At least one Compose UI test for new screens
- Robolectric tests for code that touches Android framework

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

## Code of conduct

Be respectful. We're all here to build a useful tool for people trying to break porn addiction.
