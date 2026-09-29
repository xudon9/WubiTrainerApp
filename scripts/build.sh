#!/usr/bin/env bash
# Build helper.
#
# Gradle needs a JDK 17+; the one bundled with Android Studio is the natural choice on this
# machine, and is not on PATH by default.
set -euo pipefail

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/android-studio/jbr "$HOME/android-studio/jbr"; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "error: set JAVA_HOME to a JDK 17+ (e.g. /opt/android-studio/jbr)" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
echo "JAVA_HOME=$JAVA_HOME"
exec ./gradlew "$@"
