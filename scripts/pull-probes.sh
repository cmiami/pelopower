#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB_ARGS+=( -s "$ADB_SERIAL" ); fi
adb_run() { "$ADB" "${ADB_ARGS[@]}" "$@"; }
ROOT="${1:-captures/probes}"
mkdir -p "$ROOT"
adb_run wait-for-device
adb_run pull /sdcard/Android/data/dev.pelopower.bridge.debug/files/probes "$ROOT"
