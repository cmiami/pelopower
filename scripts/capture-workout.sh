#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB_ARGS+=( -s "$ADB_SERIAL" ); fi
adb_run() { "$ADB" "${ADB_ARGS[@]}" "$@"; }
ROOT="${1:-captures}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/$STAMP-workout"
mkdir -p "$OUT"

adb_run wait-for-device
adb_run shell dumpsys activity activities > "$OUT/activity-before.txt"
echo "Capturing logcat to $OUT/logcat.txt. Start and finish a short ride, then press Ctrl-C."
adb_run logcat -v threadtime -T 1 > "$OUT/logcat.txt"
