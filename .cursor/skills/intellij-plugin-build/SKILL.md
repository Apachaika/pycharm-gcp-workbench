---
name: intellij-plugin-build
description: >-
  Build, test, and run the Vertex Workbench IntelliJ/PyCharm plugin sandbox.
  Use when running gradle, fixing compile errors, plugin verification, or
  sandbox debugging for dev.vertexworkbench.pycharm.
---

# IntelliJ plugin build (this repo)

Two build lines exist — see [dual-version-maintenance](../dual-version-maintenance/SKILL.md). All commands below assume you set `working_directory` to the desired line root.

## Commands

```bash
./scripts/build-release.sh   # test + ZIP → build/distributions/
./gradlew test buildPlugin   # same
./gradlew runIde             # PyCharm sandbox
```

Uses **Gradle Wrapper 9.0** + **Java 17** (script sets `JAVA_HOME` from PyCharm JBR if needed).

## Key config

| Line | Root | `build.gradle.kts` target | Sandbox log |
|------|------|---------------------------|-------------|
| 2025.3.x | `/Users/oleksii/Work/pycharm-plugin` | `pycharm("2025.3.5")`, `platformVersion=2025.3.5` | `.intellijPlatform/sandbox/vertex-workbench-pycharm/PY-2025.3.5/log/idea.log` |
| 2026.1.x | `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1` | `pycharm("2026.1.2")`, `platformVersion=2026.1.2` | `.intellijPlatform/sandbox/vertex-workbench-pycharm/PY-2026.1.2/log/idea.log` |

Both lines depend on the same bundled plugins: `PythonCore`, `Pythonid`, `intellij.jupyter`, `com.intellij.notebooks.core`, `org.jetbrains.plugins.terminal`.

## After code changes

1. Prefer `./gradlew test` before `./gradlew runIde`.
2. Reconnect in sandbox: Tools → Vertex Workbench → Connect.
3. Search logs for `dev.vertexworkbench.pycharm` or `Vertex Workbench`.
4. If the change is not Jupyter-runtime-specific, port it to the other line and run `./gradlew test` there too (see dual-version-maintenance).

## Common compile deps

Bundled Jupyter APIs: `com.intellij.jupyter.core.*`, `com.intellij.jupyter.py.*` — only available against IntelliJ Platform classpath, not plain Kotlin. Their signatures **differ between 2025.3.x and 2026.1.x** (notably `JupyterExecutionManager.getInstance(...)` and `JupyterServerConfig` abstract methods); that is the reason we maintain two source trees.
