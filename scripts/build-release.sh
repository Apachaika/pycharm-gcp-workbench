#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "$HOME/Applications/PyCharm.app/Contents/jbr/Contents/Home" \
    "/Applications/PyCharm.app/Contents/jbr/Contents/Home" \
    "/Applications/PyCharm Professional.app/Contents/jbr/Contents/Home"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "JAVA_HOME not set and PyCharm JBR not found. Install Java 17+ or set JAVA_HOME." >&2
  exit 1
fi

export PATH="${JAVA_HOME}/bin:${PATH}"

./gradlew test buildPlugin

echo ""
echo "Release ZIP:"
ls -lh build/distributions/*.zip
