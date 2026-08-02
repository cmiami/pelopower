#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/headless/app-headless.apk}"
PACKAGE="dev.pelopower.bridge"
ADB="${ADB:-adb}"
ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB_ARGS+=( -s "$ADB_SERIAL" ); fi
adb_run() {
    if (( ${#ADB_ARGS[@]} )); then
        "$ADB" "${ADB_ARGS[@]}" "$@"
    else
        "$ADB" "$@"
    fi
}

if [[ ! -f "$APK" ]]; then
    echo "Missing APK: $APK" >&2
    echo "Build it with: ./gradlew assembleHeadless" >&2
    exit 1
fi

# Stop a separately installed discovery flavor before production claims CPS/CSCS.
adb_run shell am force-stop dev.pelopower.bridge.debug || true
adb_run install -r "$APK"

sdk="$(adb_run shell getprop ro.build.version.sdk | tr -d '\r')"
if (( sdk >= 31 )); then
    adb_run shell pm grant "$PACKAGE" android.permission.BLUETOOTH_ADVERTISE
    adb_run shell pm grant "$PACKAGE" android.permission.BLUETOOTH_CONNECT
fi
# POST_NOTIFICATIONS is intentionally not granted. On Android 13+, the required foreground-service
# notice remains available in Task Manager without adding an ongoing item to the notification drawer.

# Starting a component once clears Android's newly-installed/stopped state. No window appears.
adb_run shell am start -n "$PACKAGE/.BootstrapActivity"

echo "PeloPower is running headlessly and will restart after future device boots."
