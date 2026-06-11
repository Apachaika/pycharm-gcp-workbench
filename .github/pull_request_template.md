<!--
Thanks for the contribution! Please tick the boxes that apply.
The plugin ships as TWO build lines from one repo (2025.3.x and 2026.1.x).
Behavior changes typically have to land in BOTH of them — see
.cursor/skills/dual-version-maintenance/SKILL.md for the why.
-->

## Summary

<!-- 1-3 sentences: what changed and why. Reference issue numbers with `Fixes #N`. -->

## Build lines touched

- [ ] 2025.3.x (root: `./`)
- [ ] 2026.1.x (root: `pycharm-plugin-2026.1/`)
- [ ] N/A — docs/CI only

## Checklist

- [ ] `./gradlew test` passes locally for each touched build line
- [ ] `./scripts/build-release.sh` (or `./gradlew buildPlugin`) succeeds for each touched build line
- [ ] Version in `build.gradle.kts` is bumped in **both** lines (if this PR ships a release)
- [ ] [`CHANGELOG.md`](../CHANGELOG.md) updated with the new version section
- [ ] Did NOT add any value to `secrets.properties` or other gitignored secrets file
- [ ] If the change affects user-visible behavior, [`docs/FEATURES.md`](../docs/FEATURES.md) is updated

## Manual test plan

<!-- e.g. "Connected to Workbench instance X, opened notebooks/foo.ipynb, ran cell, verified upload" -->
