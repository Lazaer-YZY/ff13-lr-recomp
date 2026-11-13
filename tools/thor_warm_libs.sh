#!/bin/bash
# Starts the game at the title with the given am extras and waits (up to 20 min) until the
# background pixel shader library precompile finishes (after a translation switch changes, it
# rebuilds every library: ~9 min). Ends with force-stop + KEYCODE_SLEEP (Thor rule).
# Usage: bash tools/thor_warm_libs.sh ["am extras"]
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; P=com.lrrecomp.android; LOG=/sdcard/Android/data/$P/files/logs/lr.log
A() { adb -s "$S" "$@"; }
finish() { A shell am force-stop $P >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
A shell am force-stop $P; A shell input keyevent KEYCODE_WAKEUP
start=$(A shell date '+%Y-%m-%d_%H:%M:%S' | tr -d '\r' | tr _ ' ')
A shell "am start -n $P/.RuntimeActivity --es LR_DIAG 1 ${1:-}" >/dev/null
t0=$(date +%s)
while [ $(( $(date +%s) - t0 )) -lt 1200 ]; do
  sleep 20
  l=$(A shell "tail -c 300000 $LOG" | grep -a -E "pixel shader library precompile (finished|skipped|already)|precompile .*: [0-9]* libraries|libraries for [0-9]* pixel shaders already precompiled" | awk -v m="$start" 'substr($0,2,19) >= m' | tail -1)
  [ -n "$l" ] && { echo "$l" | cut -c1-220; break; }
done
echo "warm-up took $(( $(date +%s) - t0 )) s"
