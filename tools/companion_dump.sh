#!/usr/bin/env bash
# Guest-memory snapshot of the running game (second-screen research, src/lr_companion.inc).
# The game must run with debug logging (all tools/ launches pass --es LR_DIAG 1).
#   bash tools/companion_dump.sh LABEL [OUT_DIR]   -> OUT_DIR/LABEL.gmem (default out/dual/dumps)
# The snapshot is written on the device by the game (~1-2 s stall), pulled, then deleted there.
# Never take one inside a measurement window. Analysis: out/venv/Scripts/python.exe -I tools/gmem.py
set -euo pipefail
export MSYS_NO_PATHCONV=1
LABEL=${1:?label}
OUT=${2:-out/dual/dumps}
PKG=com.lrrecomp.android
DIR=/sdcard/Android/data/$PKG/files
mkdir -p "$OUT"
adb shell "pidof $PKG" >/dev/null || { echo "companion_dump: the game is not running" >&2; exit 1; }
adb shell "rm -f $DIR/dumps/$LABEL.gmem; setprop debug.lr.dump $LABEL"
# Wait for the log line that says the snapshot is complete.
for _ in $(seq 1 120); do
  sleep 0.5
  if adb shell "grep -q \"companion dump '$LABEL'\" $DIR/logs/lr.log" 2>/dev/null; then break; fi
done
adb shell "grep \"companion dump '$LABEL'\" $DIR/logs/lr.log | tail -1"
adb pull "$DIR/dumps/$LABEL.gmem" "$OUT/$LABEL.gmem" >/dev/null
adb shell "rm -f $DIR/dumps/$LABEL.gmem"
ls -la "$OUT/$LABEL.gmem"
