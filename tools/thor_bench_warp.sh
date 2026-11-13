#!/bin/bash
# Warp cutscene bench (the owner's, 2026-11-10): their newest save stands next to the Ark's warp device. Loads it, walks
# onto the warp (stick up 2.5 s), Examine (A), advances Hope's dialog cutscene (4 x A, then UP + A at the prompt), answers
# "Return to Nova Chrysalia?" with UP + A, and measures from the first step for HOLD seconds (default 75): the
# dialog cutscene and the warp both stutter. Same samplers as thor_bench_cpu.sh (PROFILE=secs at +PROF_AT s,
# default 20; OFFCPU=thread at +20 s; AT20='cmd'). Counts frames over 33 / 50 ms (LR_SLOW_FRAME_MS=25 is added).
# Always ends with force-stop + KEYCODE_SLEEP (Thor rule).
# Usage: [PROFILE=15] bash tools/thor_bench_warp.sh LABEL ["am extras"] [HOLD=75] [COOL_C=45]
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; EXTRAS=${2:-}; HOLD=${3:-75}; COOL=${4:-45}; PROFILE=${PROFILE:-0}; TURN=${TURN:-0}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/bench; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S)"
A() { adb -s "$S" "$@"; }
PERFC=/sys/class/kgsl/kgsl-3d0/perfcounter
finish() { A shell am force-stop $PKG >/dev/null 2>&1; [ -n "${BENCH_SAVE_SET:-}" ] && bash /c/lr/tools/thor_saves.sh restore >/dev/null 2>&1; A shell "echo 0 > $PERFC" >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
hot() {
  A shell 'for z in /sys/class/thermal/thermal_zone*; do v=$(cat $z/temp 2>/dev/null) && echo "$(cat $z/type) $v"; done' |
    tr -d '\r' | awk '$1 ~ /^(cpu|gpu)/ && $2 > m {m=$2} END{printf "%.1f", m/1000}'; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.7}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
GA=305; START=315
for i in $(seq 60); do t=$(hot); awk -v t="$t" -v c="$COOL" 'BEGIN{exit !(t<=c)}' && break; sleep 10; done
T0=$(hot); echo "[$LABEL] start temperature $T0 C"
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
# LR_GPU_COUNTERS=1 in the extras: the kernel only allows counter reads with perfcounter=1 (shell-writable; back to 0 in finish).
case "$EXTRAS" in *LR_GPU_COUNTERS*) A shell "echo 1 > $PERFC" ;; esac
BENCH_SAVE=${SAVE:-ark_warp}  # bench save (tools/thor_saves.sh); SAVE=newest keeps the device's saves
if [ "$BENCH_SAVE" != newest ]; then bash /c/lr/tools/thor_saves.sh use "$BENCH_SAVE" && BENCH_SAVE_SET=1 || exit 1; fi
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 --es LR_SLOW_FRAME_MS 25 $EXTRAS" >/dev/null
sleep ${BOOT_S:-34}; press $GA 0.3; sleep 4; press $START 0.6; sleep 5; press $START 0.6; sleep 6
# SAVE_RIGHT=n / SAVE_LEFT=n: move n slots right / left in the save carousel (it opens on the last-used save) before loading.
press $GA; sleep 4; for _ in $(seq ${SAVE_RIGHT:-0}); do press 547 0.12; sleep 1.2; done; for _ in $(seq ${SAVE_LEFT:-0}); do press 546 0.12; sleep 1.2; done
press $GA; sleep 3; press $GA; sleep 6; press $GA; sleep 8
press $GA; sleep 12; press $GA; sleep 14
A exec-out screencap -p > "$F.start.png" 2>/dev/null
sleep 3  # the screenshot itself stalls the game for ~1 s
stick_up() { A shell "sendevent $DEV 3 0 0; sendevent $DEV 3 1 -32767; sendevent $DEV 0 0 0; sleep $1; sendevent $DEV 3 1 0; sendevent $DEV 0 0 0"; }
mark=$(A shell date '+%Y-%m-%d_%H:%M:%S' | tr -d '\r' | tr _ ' '); t_start=$(date +%s); end=$(( t_start + HOLD ))
# The first stick input after a load only turns Lightning, so: nudge, then walk. A press during a text's
# type-out only completes it, and voiced lines advance by themselves: Examine + 4 x A (3 s apart), wait 12 s,
# one more A (the last line waits for it), wait 8 s for the prompt, UP (it opens on "No"), A. An A while the prompt animates in answers No.
# No screenshots during the measurement: screencap itself causes ~1 s bursts of 50-80 ms frames.
( sleep 1; stick_up 0.5; sleep 1.5; stick_up 2.5; sleep 0.8; press $GA 0.3
  for _ in 1 2 3 4; do sleep 3; press $GA 0.3; done; sleep 12; press $GA 0.3; sleep 8
  press 544 0.3; sleep 1.5; press $GA 0.3 ) &
( sleep 10; bash /c/lr/tools/thor_schedstat.sh 10 18 > "$F.sched.txt" 2>&1 ) &
sched_pid=$!
if [ -n "${OFFCPU:-}" ]; then ( sleep ${OFFCPU_AT:-20}; bash /c/lr/tools/thor_offcpu.sh "$LABEL" "$OFFCPU" ${OFFCPU_S:-3} > "$F.offcpu.txt" 2>&1 ) & fi
if [ -n "${AT20:-}" ]; then ( sleep 20; eval "$AT20" > "$F.at20.txt" 2>&1 ) & fi
if [ "$PROFILE" != 0 ]; then ( sleep ${PROF_AT:-20}; bash /c/lr/tools/thor_profile.sh "$LABEL" "$PROFILE" > "$F.profile.txt" 2>&1 ) & fi
: > "$F.clk"
while [ "$(date +%s)" -lt "$end" ]; do
  A shell 'c=""; for p in 0 3 7; do c="$c $(cat /sys/devices/system/cpu/cpufreq/policy$p/scaling_cur_freq)"; done;
    echo "$c $(cat /sys/class/kgsl/kgsl-3d0/gpuclk) $(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage) $(cat /sys/class/power_supply/usb/current_now) $(cat /sys/class/power_supply/usb/voltage_now) $(cat /sys/class/power_supply/battery/current_now)"' | tr -d '\r%' >> "$F.clk"
  sleep 5
done
wait
A exec-out cat "$LOG" > "$F.lr.log" 2>/dev/null
A exec-out screencap -p > "$F.end.png" 2>/dev/null
T1=$(hot)
finish; trap - EXIT
clk=$(awk 'NF>=5 {a+=$1; b+=$2; c+=$3; g+=$4; u+=$5; n++} END{printf "clocks little %d / mid %d / prime %d MHz, GPU %d MHz %d%% busy", a/n/1000, b/n/1000, c/n/1000, g/n/1e6, u/n}' "$F.clk")
# USB input power: the whole device when the battery is full (battery current ~0); charging skews it.
clk="$clk; $(awk 'NF>=8 {p+=$6/1e6*$7/1e6; b+=$8/1e3; n++} END{if (n) printf "USB input %.2f W, battery %.0f mA", p/n, b/n}' "$F.clk")"
sel() { grep -a "$1" "$F.lr.log" | awk -v m="$mark" 'substr($0,2,19) >= m'; }
stats=$(sel "LR main thread (10 s)" |
  sed 's/.*(10 s): \([0-9]*\) frames, \([0-9]*\) over 45 ms; per frame \([0-9.]*\) ms = game code \([0-9.]*\) + sleeping \([0-9.]*\) + waiting \([0-9.]*\).*/\1 \2 \3 \4 \5 \6/' |
  awk '{f+=$1; s+=$2; ms+=$3; g+=$4; sl+=$5; w+=$6; n++} END{printf "%d frames, %d over 45 ms (%.1f%%), %.1f ms/frame = code %.1f + sleep %.1f + wait %.1f (%d windows)", f, s, 100*s/(f?f:1), ms/(n?n:1), g/(n?n:1), sl/(n?n:1), w/(n?n:1), n}')
lows=$(grep -a "LR frame times (10 s)" "$F.lr.log" | awk -v m="$mark" 'substr($0,2,19) >= m' |
  awk '{for (i = 2; i < NF; i++) { if ($i == "low" && $(i-1) == "1%") a = $(i+1); if ($i == "low" && $(i-1) == "0.1%") b = $(i+1) } print a, b}' |
  awk 'NF==2 {a+=$1; b+=$2; n++; if (!wa || $1<wa) wa=$1; if (!wb || $2<wb) wb=$2} END{if (n) printf "1%% low %.1f fps (worst window %.1f), 0.1%% low %.1f fps (worst %.1f)", a/n, wa, b/n, wb}')
slow=$(sel "LR slow frame: " | sed 's/.*LR slow frame: \([0-9]*\) ms.*/\1/' | awk '$1>33{a++} $1>50{b++} END{printf "%d frames over 33 ms, %d over 50 ms", a, b}')
stats="$stats; $lows; $slow"
echo "[$LABEL] $stats; $clk; temp $T0 -> $T1 C" | tee "$F.txt"
sel "LR CPU (10 s)" | tail -2 | cut -c60-400 | tee -a "$F.txt"
sel "LR waits (10 s): main" | tail -2 | cut -c60-300 | tee -a "$F.txt"
sel "LR effect spin" | tail -2 | cut -c60-250 | tee -a "$F.txt"
sel "GPU timing (" | tail -2 | cut -c50-600 | tee -a "$F.txt"
sel "GPU work: average" | tail -2 | cut -c50-300 | tee -a "$F.txt"
cat "$F.sched.txt" | tee -a "$F.txt"
[ -f "$F.at20.txt" ] && cat "$F.at20.txt" | tee -a "$F.txt"
echo "[$LABEL] shots: $(cygpath -w "$F.start.png") $(cygpath -w "$F.end.png")"
