<!--
  Pull Request Template
  Filled out by the PR author. The CI workflow checks for the required
  labels and links (issue, screenshot).
-->

## Description

<!-- Brief description of what this PR changes and why. -->

## Type of Change

<!-- Check all that apply. The CI workflow requires at least one label. -->

- [ ] 🐛 Bug fix (label: `bug`) — non-breaking change which fixes an issue
- [ ] ✨ New feature (label: `enhancement`) — non-breaking change which adds functionality
- [ ] 💥 Breaking change (label: `breaking`) — fix or feature that would cause existing functionality to not work as expected
- [ ] 📚 Documentation (label: `docs`) — changes to documentation only
- [ ] 🔧 Chore (label: `chore`) — dependency updates, CI config, refactoring

## Related Issue

<!-- Link to the issue this PR addresses. Use "Fixes #123" or "Closes #123" to auto-close. -->

Fixes #

## Testing

<!-- Describe how you tested your changes. -->

- [ ] Unit tests pass (`./gradlew testDebugUnitTest`)
- [ ] Lint passes (`./gradlew lintDebug`)
- [ ] Detekt passes (`./gradlew detekt`)
- [ ] ktlint passes (`./gradlew ktlintCheck`)
- [ ] Debug APK builds (`./gradlew assembleDebug`)
- [ ] Release APK builds (`./gradlew assembleRelease`)
- [ ] Manually tested on device/emulator (API __)

### Test scenarios covered

<!-- List the states/transitions tested. For features with toggles, test
     both ON and OFF. -->

-

## Screenshots / Recordings

<!-- If the PR includes UI changes, attach screenshots or screen recordings.
     Required for any visual change. -->

| Before | After |
|---|---|
| | |

## Checklist

- [ ] My code follows the project's style guidelines (detekt + ktlint pass)
- [ ] I have performed a self-review of my own code
- [ ] I have commented my code, particularly in hard-to-understand areas
- [ ] I have made corresponding changes to the documentation
- [ ] My changes generate no new warnings
- [ ] I have added tests that prove my fix is effective or my feature works
- [ ] New and existing unit tests pass locally with my changes
- [ ] Any dependent changes have been merged and published in downstream modules
- [ ] I have checked my code and corrected any misspellings

## Release Notes

<!-- What should appear in the changelog / release notes for users? -->

```

```

## Additional Notes

<!-- Anything else reviewers should know. E.g. migration steps, config changes
     required, performance implications, etc. -->
