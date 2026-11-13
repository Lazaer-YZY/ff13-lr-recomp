#!/bin/bash
# Field benchmark on the Thor (A/B of renderer options in gameplay, no player agent needed):
# waits until the Thor has cooled down, starts the game, loads the newest save with fixed presses
# (title -> CONTINUE -> newest save -> yes -> notices -> story screen), turns the camera to a fixed
# view of the Luxerion cathedral hall (the owner's save of 2026-11-09), holds still for HOLD seconds and
# reports: frames over 45 ms and frame time (game's 10-s stats), GPU clock and busy (every 5 s),
# and the displayed cadence (SurfaceFlinger: frames on screen for 1/2/3 refreshes; smooth 30 fps
# on the 60 Hz panel = all 2). Always ends with force-stop + KEYCODE_SLEEP (Thor rule).
# Usage: bash tools/thor_bench_field.sh LABEL ["am extras", e.g. "--es cvars '--vulkan_submit_on_primary_buffer_end'"] [HOLD=60] [COOL_C=45]
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; EXTRAS=${2:-}; HOLD=${3:-60}; COOL=${4:-45}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/bench; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S)"
A() { adb -s "$S" "$@"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; [ -n "${BENCH_SAVE_SET:-}" ] && bash /c/lr/tools/thor_saves.sh restore >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
hot() {  # hottest cpu*/gpu* thermal zone in C (some zones refuse reads: pair type and temp per zone)
  A shell 'for z in /sys/class/thermal/thermal_zone*; do v=$(cat $z/temp 2>/dev/null) && echo "$(cat $z/type) $v"; done' |
    tr -d '\r' | awk '$1 ~ /^(cpu|gpu)/ && $2 > m {m=$2} END{printf "%.1f", m/1000}'; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.7}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
GA=305; START=315  # the game's A on the Thor's Nintendo button mode (see thor_pad.sh)
rstick() { A shell "sendevent $DEV 3 2 $1; sendevent $DEV 3 5 $2; sendevent $DEV 0 0 0; sleep $3; sendevent $DEV 3 2 0; sendevent $DEV 3 5 0; sendevent $DEV 0 0 0"; }
# Cool down (asleep) to COOL C on the cpu/gpu zones, at most 10 minutes.
for i in $(seq 60); do t=$(hot); awk -v t="$t" -v c="$COOL" 'BEGIN{exit !(t<=c)}' && break; sleep 10; done
T0=$(hot); echo "[$LABEL] start temperature $T0 C"
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
BENCH_SAVE=${SAVE:-lux_plaza}  # bench save (tools/thor_saves.sh); SAVE=newest keeps the device's saves
if [ "$BENCH_SAVE" != newest ]; then bash /c/lr/tools/thor_saves.sh use "$BENCH_SAVE" && BENCH_SAVE_SET=1 || exit 1; fi
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $EXTRAS" >/dev/null
sleep 24; press $GA 0.3; sleep 4; press $START 0.6; sleep 5; press $START 0.6; sleep 6
press $GA; sleep 4; press $GA; sleep 3; press $GA; sleep 6; press $GA; sleep 8   # CONTINUE, save, yes, "Data loaded"
press $GA; sleep 12; press $GA; sleep 14                                          # autosave notice, story screen
rstick 32767 0 1.5; sleep 1; rstick 32767 0 3.0; sleep 4
A exec-out screencap -p > "$F.start.png" 2>/dev/null
mark=$(A shell date '+%Y-%m-%d_%H:%M:%S' | tr -d '\r' | tr _ ' '); end=$(( $(date +%s) + HOLD ))
layer=$(A shell "dumpsys SurfaceFlinger --list" | tr -d '\r' | grep -i "lrrecomp" | grep -i "SurfaceView" | grep -vi "background" | tail -1)
: > "$F.gpu"; : > "$F.frames"
while [ "$(date +%s)" -lt "$end" ]; do
  A shell "cat /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage" | tr -d '\r %' | paste -sd' ' >> "$F.gpu"
  A shell "dumpsys SurfaceFlinger --latency '$layer'" | tr -d '\r' >> "$F.frames"
  sleep 4
done
A exec-out screencap -p > "$F.end.png" 2>/dev/null
A exec-out cat "$LOG" > "$F.lr.log" 2>/dev/null
T1=$(hot)
finish; trap - EXIT
gpu=$(awk '{m+=$1; b+=$2; n++} END{printf "GPU %d MHz, %d%% busy (%d samples)", m/n/1e6, b/n, n}' "$F.gpu")
cad=$(awk 'NR==1 {period=$1} NF==1 && $1>1000000 {period=$1} NF==3 && $2>0 && $2<9e18 {seen[$2]=1}
  END {n=asorti(seen, t, "@ind_num_asc"); for (i=2;i<=n;i++) {v=int((t[i]-t[i-1])/period+0.5); if (v>8) v=8; h[v]++; c++}
       printf "displayed %d frames:", c; for (v=0;v<=8;v++) if (h[v]) printf " %dx%d", h[v], v; printf " (count x refreshes)"}' "$F.frames")
stats=$(grep -a "LR main thread (10 s)" "$F.lr.log" | awk -v m="$mark" 'substr($0,2,19) >= m' |
  sed 's/.*(10 s): \([0-9]*\) frames, \([0-9]*\) over 45 ms; per frame \([0-9.]*\) ms.*/\1 \2 \3/' |
  awk '{f+=$1; s+=$2; ms+=$3; n++} END{printf "%d frames, %d over 45 ms (%.1f%%), %.1f ms per frame (%d windows)", f, s, 100*s/(f?f:1), ms/(n?n:1), n}')
echo "[$LABEL] $stats; $gpu; $cad; temp $T0 -> $T1 C" | tee "$F.txt"
echo "[$LABEL] shots: $(cygpath -w "$F.start.png") $(cygpath -w "$F.end.png")"
