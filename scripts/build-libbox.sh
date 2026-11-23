#!/usr/bin/env bash
# Builds libbox.aar from the sing-box source and places it where the
# Gradle build expects it: feature/connection/libs/libbox.aar
#
# libbox is NOT published on Maven Central, so there are no Maven
# coordinates for it — the AAR is always built from source.
#
# Requirements: Go 1.25.5, JDK 17, Android NDK 28.0.13004108,
# ANDROID_HOME / ANDROID_NDK_HOME set (see ~/Android/env.sh).
#
# Usage: ./scripts/build-libbox.sh
set -euo pipefail

SINGBOX_VERSION="${SINGBOX_VERSION:-v1.14.4}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${TMPDIR:-/tmp}/tunnel-libbox-build"
DEST="$REPO_ROOT/feature/connection/libs/libbox.aar"

: "${ANDROID_HOME:?ANDROID_HOME is not set}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"

echo "==> Building libbox $SINGBOX_VERSION"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
git clone --depth 1 --branch "$SINGBOX_VERSION" \
    https://github.com/SagerNet/sing-box.git sing-box
cd sing-box

echo "==> Installing gomobile (make lib_install)"
make lib_install

echo "==> Building the Android library (make lib_android)"
make lib_android

AAR="$(find . -name 'libbox.aar' -not -path '*/legacy/*' | head -n 1)"
if [ -z "$AAR" ]; then
    echo "ERROR: libbox.aar was not produced" >&2
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
cp "$AAR" "$DEST"
echo "==> Copied to $DEST"
ls -lh "$DEST"
echo "==> Done. Now run: ./gradlew :app:assembleDebug"
