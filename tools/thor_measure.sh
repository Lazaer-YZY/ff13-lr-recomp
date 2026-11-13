#!/bin/bash
# Steady-scene power/perf measurement on the Thor: launch, get to the title menu, sample for N seconds.
# Usage: [PROFILE=10] [PROFILE_CG=SDLThread] [AFFINITY=f8] [FRAMES=1] [SCHEDSTAT=1] [SHOT=1] bash tools/thor_measure.sh LABEL [seconds=150] [extra am-start args, e.g. "--es LR_ADPF 0"]
# Samples every 5 s: clock of the little (cpu0), mid (cpu3) and prime (cpu7) cores, hottest thermal zone.
# At the end prints averages plus the game's own 10-s stats (frames, game code, process CPU).
# Thor rule: ends with force-stop + KEYCODE_SLEEP, also on failure.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; SECS=${2:-150}; EXTRA=${3:-}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/measure; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S)"
A() { adb -s "$S" "$@"; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.6}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
start_temp=$(A shell "cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null" | tr -d '\r' | sort -n | tail -1)
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $EXTRA" >/dev/null
t0=$(date +%s)
# press-any at ~20-26 s, skip the attract movie, wait for the title menu event
sleep 20; press 305; sleep 6; press 305; sleep 8; press 315 0.5; sleep 2; press 314; sleep 8; press 315 0.5; sleep 2; press 314
menu=""
for i in $(seq 1 30); do
  A shell "grep -c 'LR title menu: hid' $LOG" 2>/dev/null | tr -d '\r' | grep -q -v '^0$' && { menu=yes; break; }
  sleep 3
done
echo "[$LABEL] start temp $((start_temp/1000))C, title menu: ${menu:-NOT reached} after $(( $(date +%s) - t0 ))s" | tee "$F.txt"
# AFFINITY=MASK (hex, e.g. f8 = cpus 3-7): pin every app thread once the title menu is up (run-as,
# same uid). Prints the CPU each key thread last ran on beforehand.
if [ -n "${AFFINITY:-}" ]; then
  apid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
  A shell "ps -T -p $apid -o TID,PSR,CMD" | tr -d '\r' | grep -E "vfx|Main Thread|GPU Commands|Audio Worker|XThread" | tr -s ' ' | head -10 | sed "s/^/[$LABEL] before pin: /" | tee -a "$F.txt"
  A shell "run-as $PKG sh -c 'for t in /proc/$apid/task/*; do taskset -p $AFFINITY \${t##*/} >/dev/null 2>&1; done'"
  echo "[$LABEL] affinity $AFFINITY set on all threads" | tee -a "$F.txt"
fi
# SCHEDSTAT=1: per-thread time running vs. waiting for a CPU (runnable, not running) over the
# sampling window, from /proc/<pid>/task/<tid>/schedstat (run-as). Top 12 by run time.
if [ -n "${SCHEDSTAT:-}" ]; then
  spid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
  snap() { A shell "run-as $PKG sh -c 'for t in /proc/$spid/task/*; do echo \${t##*/} \$(cat \$t/schedstat) \$(tr \" \" _ < \$t/comm); done'" | tr -d '\r'; }
  snap > "$F.sched0"; sched_t0=$(date +%s)
fi
mark=$(date +%H:%M:%S)
for i in $(seq 1 $((SECS/5))); do
  A shell "echo \$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq) \$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)" | tr -d '\r' >> "$F.samples"
  sleep 5
done
if [ -n "${SCHEDSTAT:-}" ] && [ -n "${spid:-}" ]; then
  snap > "$F.sched1"; dt=$(( $(date +%s) - sched_t0 ))
  echo "[$LABEL] per thread over ${dt}s: running ms/s, waiting-for-CPU ms/s, wakeups/s" | tee -a "$F.txt"
  awk -v dt="$dt" 'NR==FNR {r[$1]=$2; w[$1]=$3; n[$1]=$4; next} ($1 in r) {printf "%-18s %6.1f %6.1f %7.1f\n", $5, ($2-r[$1])/1e6/dt, ($3-w[$1])/1e6/dt, ($4-n[$1])/dt}' "$F.sched0" "$F.sched1" | sort -k2 -rn | head -12 | sed "s/^/[$LABEL]   /" | tee -a "$F.txt"
fi
pid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
if [ -n "$pid" ]; then
  echo "[$LABEL] busiest threads (top -H, 3 s window):" | tee -a "$F.txt"
  A shell "top -H -b -d 3 -n 2 -p $pid -o TID,%CPU,CMD" | tr -d '\r' | awk '/TID/{blk++; next} blk==2' | sort -k2 -rn | head -15 | tr -s ' ' | tee -a "$F.txt"
fi
# PROFILE=N: simpleperf the whole process for N s (the app is debuggable): CPU time by thread and
# library, then by symbol. Full report in $F.perf.txt.
if [ -n "${PROFILE:-}" ] && [ -n "$pid" ]; then
  A shell "cd /data/local/tmp && rm -f lr.perf && simpleperf record --app $PKG --duration $PROFILE -f 2000 -o lr.perf" >/dev/null 2>&1
  A pull /data/local/tmp/lr.perf "$(cygpath -w "$F.perf.data")" >/dev/null 2>&1  # for host-side symbols
  { echo "== by thread, library"; A shell "cd /data/local/tmp && simpleperf report -i lr.perf --sort comm,dso 2>/dev/null" | tr -d '\r' | head -45
    echo "== by thread, library, symbol"; A shell "cd /data/local/tmp && simpleperf report -i lr.perf --sort comm,dso,symbol 2>/dev/null" | tr -d '\r' | head -120; } > "$F.perf.txt"
  echo "[$LABEL] profile, top entries (full: $F.perf.txt):" | tee -a "$F.txt"
  grep -E '^ *[0-9.]+%' "$F.perf.txt" | head -14 | tr -s ' ' | cut -c1-150 | tee -a "$F.txt"
fi
# PROFILE_CG=NAME: also record call graphs (DWARF) of the first thread whose name contains NAME
# for 4 s and save the caller trees to $F.cg.txt.
if [ -n "${PROFILE_CG:-}" ] && [ -n "$pid" ]; then
  tid=$(A shell "ps -T -p $pid -o TID,CMD" | tr -d '\r' | awk -v n="$PROFILE_CG" 'index($0, n) {print $1; exit}')
  echo "[$LABEL] call graphs of '$PROFILE_CG' (tid $tid) -> $F.cg.txt" | tee -a "$F.txt"
  A shell "cd /data/local/tmp && rm -f lr_cg.perf && simpleperf record --app $PKG -t $tid --call-graph dwarf --duration 4 -o lr_cg.perf" > "$F.cg.txt" 2>&1
  A shell "cd /data/local/tmp && simpleperf report -i lr_cg.perf --children --sort dso,symbol -g caller --percent-limit 4 2>/dev/null" | tr -d '\r' | head -400 >> "$F.cg.txt"
fi
# FRAMES=1: the displayed cadence from SurfaceFlinger (last ~127 presents of the game's layer):
# how many frames stayed on screen for 1, 2, 3... refresh periods. Smooth 30 fps = all 2.
if [ -n "${FRAMES:-}" ]; then
  layer=$(A shell "dumpsys SurfaceFlinger --list" | tr -d '\r' | grep -i "lrrecomp" | grep -i "SurfaceView" | grep -vi "background" | tail -1)
  A shell "dumpsys SurfaceFlinger --latency '$layer'" | tr -d '\r' > "$F.frames"
  awk -v label="$LABEL" 'NR==1 {period=$1; next}
    NF==3 && $2>0 && $2<9e18 {if (prev) {v=int(($2-prev)/period+0.5); h[v]++; n++} prev=$2}
    END {printf "[%s] displayed frames (%d, refresh %.1f ms): ", label, n, period/1e6;
         for (v=0; v<=8; v++) if (h[v]) printf "%dx%d ", h[v], v; print "(count x refreshes on screen)"}' "$F.frames" | tee -a "$F.txt"
fi
# SHOT=1: a screenshot at the end of sampling ($F.png), to check rendering of an A/B.
if [ -n "${SHOT:-}" ]; then A exec-out screencap -p > "$F.png" 2>/dev/null && echo "[$LABEL] screenshot $F.png" | tee -a "$F.txt"; fi
awk '{l+=$1; m+=$2; p+=$3; if($4>t)t=$4; n++} END{printf "[%s] avg MHz little %d, mid %d, prime %d; max temp %.1fC (%d samples)\n", label, l/n/1000, m/n/1000, p/n/1000, t/1000, n}' label="$LABEL" "$F.samples" | tee -a "$F.txt"
A exec-out cat "$LOG" > "$F.lr.log" 2>/dev/null
grep -a -E "LR main thread \(10 s\)|LR CPU \(10 s\)|LR ADPF" "$F.lr.log" | awk -v m="$mark" 'substr($0,13,8) >= m || /ADPF/' | tail -8 | cut -c13-20,40-200 | tee -a "$F.txt"
