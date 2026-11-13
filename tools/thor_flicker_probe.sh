#!/bin/bash
# Flicker probe at the title menu: captures a burst of screenshots and reports frames that differ
# strongly from the per-pixel median of the burst (flicker = a few outlier frames).
# Usage: bash tools/thor_flicker_probe.sh LABEL [frames=16] [am extras]
# Thor rule: ends with force-stop + KEYCODE_SLEEP, also on failure.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; N=${2:-16}; EXTRA=${3:-}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/flicker/${LABEL}_$(date +%H%M%S); mkdir -p "$OUT"
A() { adb -s "$S" "$@"; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.6}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $EXTRA" >/dev/null
sleep 20; press 305; sleep 6; press 305; sleep 8; press 315 0.5; sleep 2; press 314; sleep 8; press 315 0.5; sleep 2; press 314
for i in $(seq 1 30); do
  A shell "grep -c 'LR title menu: hid' $LOG" 2>/dev/null | tr -d '\r' | grep -q -v '^0$' && break
  sleep 3
done
sleep 8
A shell "rm -rf /data/local/tmp/fl; mkdir -p /data/local/tmp/fl; for i in \$(seq 1 $N); do date +%H:%M:%S.%N >> /data/local/tmp/fl/times.txt; screencap /data/local/tmp/fl/f\$i.raw; done"
A pull /data/local/tmp/fl/times.txt "$(cygpath -w "$OUT/times.txt")" >/dev/null 2>&1   # capture start times (device clock, as in lr.log)
A pull $LOG "$(cygpath -w "$OUT/lr.log")" >/dev/null 2>&1
for i in $(seq 1 $N); do A pull /data/local/tmp/fl/f$i.raw "$(cygpath -w "$OUT/f$i.raw")" >/dev/null 2>&1; done
A shell "rm -rf /data/local/tmp/fl"
python -I C:/lr/tools/flicker_analyze.py "$(cygpath -m "$OUT")" "$LABEL"
