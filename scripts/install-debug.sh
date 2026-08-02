#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB_ARGS+=( -s "$ADB_SERIAL" ); fi
adb_run() { "$ADB" "${ADB_ARGS[@]}" "$@"; }
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
    echo "Missing $APK; run ./gradlew assembleDebug first" >&2
    exit 1
fi

adb_run wait-for-device
# Only one process can own the standard cycling GATT services at a time.
adb_run shell am force-stop dev.pelopower.bridge || true
adb_run install -r "$APK"
adb_run shell am start -n dev.pelopower.bridge.debug/dev.pelopower.bridge.MainActivity
