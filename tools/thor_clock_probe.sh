#!/bin/bash
# CPU clock probe at the title menu: does input raise the CPU clocks?
# Usage: bash tools/thor_clock_probe.sh LABEL [phase_seconds=30] [key=317 (L3, ignored by the game)] [am extras]
# Phases: idle, one key press every 3 s, idle again. Samples cpu0/cpu3/cpu7 and GPU clocks
# twice a second and prints per-phase averages. Thor rule: ends with force-stop + KEYCODE_SLEEP.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; SECS=${2:-30}; KEY=${3:-317}; EXTRA=${4:-}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/measure; mkdir -p "$OUT"; F="$OUT/clock_${LABEL}_$(date +%H%M%S)"
A() { adb -s "$S" "$@"; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.3}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $EXTRA" >/dev/null
sleep 20; press 305 0.6; sleep 6; press 305 0.6; sleep 8; press 315 0.5; sleep 2; press 314; sleep 8; press 315 0.5; sleep 2; press 314
for i in $(seq 1 30); do
  A shell "grep -c 'LR title menu: hid' $LOG" 2>/dev/null | tr -d '\r' | grep -q -v '^0$' && break
  sleep 3
done
sleep 10
# On-device sampler: N samples, 0.5 s apart, of cpu0/cpu3/cpu7/gpu MHz and the hottest zone.
sample() {
  A shell "for i in \$(seq 1 $(( $1 * 2 ))); do echo $2 \$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq) \$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq) \$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1); sleep 0.5; done" | tr -d '\r'
}
sample $SECS idle1 > "$F.samples"
( for i in $(seq 1 $(( SECS / 3 ))); do press $KEY 0.3; sleep 2.7; done ) &
sample $SECS press >> "$F.samples"
wait
sample $SECS idle2 >> "$F.samples"
awk -v label="$LABEL" '{n[$1]++; c0[$1]+=$2; c3[$1]+=$3; c7[$1]+=$4; g[$1]+=$5; if ($6>t[$1]) t[$1]=$6}
  END {for (p in n) printf "[%s] %-6s cpu0 %4d  cpu3 %4d  cpu7 %4d  gpu %3d MHz  max %.1f C  (%d samples)\n",
       label, p, c0[p]/n[p]/1000, c3[p]/n[p]/1000, c7[p]/n[p]/1000, g[p]/n[p]/1000000, t[p]/1000, n[p]}' "$F.samples" | sort -k2 | tee "$F.txt"
