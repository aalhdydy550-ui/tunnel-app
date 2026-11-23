#!/usr/bin/env bash
# Measures the footprint of a built Ayman Tunnel Plus APK:
# APK size, install size, and live RAM while connected.
#
# Usage:
#   ./scripts/measure-footprint.sh app/build/outputs/apk/debug/app-debug.apk
#
# - APK size + badging (version, ABIs, permissions) need only the APK
#   (aapt2 from the Android SDK, or `unzip -l` as a fallback).
# - Install size + RAM need a device connected over adb with the app
#   installed and the tunnel running. Without a device, those sections
#   print "no device" instead of guessing.
#
# No numbers in README come from this script's output unless the script
# actually ran — the footprint section records measured numbers only.
set -u

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: $0 <path-to-apk>" >&2
    exit 1
fi

PKG="dev.aalhdydy550.tunnel"

echo "== APK file =="
ls -lh "$APK"
stat -c 'size_bytes=%s' "$APK" 2>/dev/null || stat -f 'size_bytes=%z' "$APK"

AAPT2="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}" -name aapt2 -type f 2>/dev/null | head -n 1)"
if [ -n "$AAPT2" ]; then
    echo "== aapt2 dump badging =="
    "$AAPT2" dump badging "$APK" | grep -E "package:|sdkVersion|targetSdkVersion|native-code|launchable-activity" || true
    echo "== permissions =="
    "$AAPT2" dump badging "$APK" | grep -c "uses-permission" || true
else
    echo "== aapt2 not found; zip listing instead =="
    unzip -l "$APK" | tail -5
    echo "native libs:"
    unzip -l "$APK" | grep -E "lib/[^/]+/" | awk '{print $4}' | cut -d/ -f2 | sort -u
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "== adb not found: install size / RAM need a connected device =="
    exit 0
fi
if ! adb get-state >/dev/null 2>&1; then
    echo "== no device connected: install size / RAM need a device =="
    exit 0
fi

echo "== install size (device) =="
adb shell dumpsys package "$PKG" 2>/dev/null | grep -A2 "codePath" | head -5
adb shell du -sh /data/app/*/*tunnel* 2>/dev/null | head -3
adb shell du -sh /data/data/"$PKG" 2>/dev/null || echo "(app data dir not readable without root)"

echo "== RAM while connected (device) =="
adb shell dumpsys meminfo "$PKG" 2>/dev/null | grep -E "TOTAL|Native Heap|Dalvik Heap" | head -8 || echo "(app not running)"
