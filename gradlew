#!/usr/bin/env sh

set -eu

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Gradle and Java are both not available."
  echo "Install Java 17 and Gradle, or add a compatible 'gradle' binary to PATH."
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required to run the bundled Gradle fallback."
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to download Gradle for the fallback wrapper."
  exit 1
fi

GRADLE_VERSION="8.5"
GRADLE_DIST="gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME="${HOME}/.gradle/wrapper/dists/${GRADLE_DIST%.zip}"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  TMPDIR="$(mktemp -d)"
  ZIP_PATH="${TMPDIR}/${GRADLE_DIST}"
  EXTRACT_DIR="$TMPDIR/extracted"
  curl -fsSL -o "$ZIP_PATH" "https://services.gradle.org/distributions/$GRADLE_DIST"
  unzip -q "$ZIP_PATH" -d "$EXTRACT_DIR"
  mkdir -p "$(dirname "$GRADLE_HOME")"
  rm -rf "$GRADLE_HOME"
  mv "$EXTRACT_DIR/gradle-${GRADLE_VERSION}" "$GRADLE_HOME"
  rm -rf "$TMPDIR"
fi

exec "${GRADLE_HOME}/bin/gradle" "$@"
