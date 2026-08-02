#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB_ARGS+=( -s "$ADB_SERIAL" ); fi
adb_run() { "$ADB" "${ADB_ARGS[@]}" "$@"; }
ROOT="${1:-captures}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/$STAMP"
mkdir -p "$OUT"

adb_run wait-for-device
adb_run devices -l > "$OUT/adb-devices.txt"
adb_run shell getprop > "$OUT/getprop.txt"
adb_run shell dumpsys bluetooth_manager > "$OUT/bluetooth-manager.txt"
adb_run shell dumpsys package com.onepeloton.affernetservice > "$OUT/affernet-package.txt" || true
adb_run shell dumpsys package com.onepeloton.android > "$OUT/peloton-package.txt" || true
adb_run shell dumpsys activity activities > "$OUT/activities.txt"
adb_run shell pm list packages -f | tr -d '\r' > "$OUT/packages.txt"

{
    echo "Capture: $STAMP"
    adb_run shell getprop ro.product.manufacturer
    adb_run shell getprop ro.product.model
    adb_run shell getprop ro.build.version.release
    adb_run shell getprop ro.build.version.sdk
    adb_run shell getprop ro.build.display.id
} > "$OUT/summary.txt"

echo "Read-only device capture saved to $OUT"
