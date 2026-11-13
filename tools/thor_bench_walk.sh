#!/bin/bash
# Walking benchmark on the Thor: like thor_bench_field.sh (cool-down, load the owner's newest save with
# fixed presses) but then walks and turns for WALK seconds instead of standing still, with the GPU
# per-draw trace on, a simpleperf CPU profile (tools/thor_profile.sh) in the middle and the heat
# sampler alongside. Reports the game's frame stats, GPU clock/busy, and where the profile and
# logs went. Always ends with force-stop + KEYCODE_SLEEP (Thor rule).
# Usage: bash tools/thor_bench_walk.sh LABEL ["am extras"] [WALK=90] [COOL_C=45] [PROFILE_S=30]
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; EXTRAS=${2:-}; WALK=${3:-90}; COOL=${4:-45}; PROFILE_S=${5:-30}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
OUT=/c/lr/out/thor/bench; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S)"
A() { adb -s "$S" "$@"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; [ -n "${BENCH_SAVE_SET:-}" ] && bash /c/lr/tools/thor_saves.sh restore >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
trap finish EXIT
hot() { A shell 'for z in /sys/class/thermal/thermal_zone*; do v=$(cat $z/temp 2>/dev/null) && echo "$(cat $z/type) $v"; done' |
    tr -d '\r' | awk '$1 ~ /^(cpu|gpu)/ && $2 > m {m=$2} END{printf "%.1f", m/1000}'; }
press() { A shell "sendevent $DEV 1 $1 1; sendevent $DEV 0 0 0; sleep ${2:-0.7}; sendevent $DEV 1 $1 0; sendevent $DEV 0 0 0"; }
GA=305; START=315
rstick() { A shell "sendevent $DEV 3 2 $1; sendevent $DEV 3 5 $2; sendevent $DEV 0 0 0; sleep $3; sendevent $DEV 3 2 0; sendevent $DEV 3 5 0; sendevent $DEV 0 0 0"; }
lstick() { A shell "sendevent $DEV 3 0 $1; sendevent $DEV 3 1 $2; sendevent $DEV 0 0 0; sleep $3; sendevent $DEV 3 0 0; sendevent $DEV 3 1 0; sendevent $DEV 0 0 0"; }
for i in $(seq 60); do t=$(hot); awk -v t="$t" -v c="$COOL" 'BEGIN{exit !(t<=c)}' && break; sleep 10; done
T0=$(hot); echo "[$LABEL] start temperature $T0 C"
A shell am force-stop $PKG; A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
BENCH_SAVE=${SAVE:-lux_plaza}  # bench save (tools/thor_saves.sh); SAVE=newest keeps the device's saves
if [ "$BENCH_SAVE" != newest ]; then bash /c/lr/tools/thor_saves.sh use "$BENCH_SAVE" && BENCH_SAVE_SET=1 || exit 1; fi
A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $EXTRAS" >/dev/null
sleep 24; press $GA 0.3; sleep 4; press $START 0.6; sleep 5; press $START 0.6; sleep 6
# SAVE_RIGHT=n: move n slots right in the save carousel (it opens on the newest save) before loading.
press $GA; sleep 4; for _ in $(seq ${SAVE_RIGHT:-0}); do press 547 0.12; sleep 1.2; done
press $GA; sleep 3; press $GA; sleep 6; press $GA; sleep 8
press $GA; sleep 12; press $GA; sleep 14
# A quest or tip screen can still be up: press A until the field shows (field ~2.8 MB, menus ~1 MB).
for _ in 1 2 3 4 5 6; do
  A exec-out screencap -p > "$F.chk.png" 2>/dev/null
  [ "$(stat -c %s "$F.chk.png")" -gt 2000000 ] && break
  press $GA; sleep 5
done
rm -f "$F.chk.png"; sleep 4
A exec-out screencap -p > "$F.start.png" 2>/dev/null
mark=$(A shell date '+%Y-%m-%d_%H:%M:%S' | tr -d '\r' | tr _ ' ')
bash /c/lr/tools/thor_heat_sampler.sh "${LABEL}_walk" $((WALK + 30)) 5 > "$F.heat.txt" 2>&1 &
heat_pid=$!
# Walk: forward 4 s, turn ~90 degrees, forward 4 s, ... (the Ark plaza is open; walls are fine).
( end=$(( $(date +%s) + WALK )); while [ "$(date +%s)" -lt "$end" ]; do
    lstick 0 -32767 4; rstick 32767 0 0.8; lstick 0 -32767 4; rstick 32767 0 0.8; lstick 32767 0 2; rstick -32767 0 0.6
  done ) &
walk_pid=$!
# USB input power every 5 s during the walk (the whole device with a full battery).
( end=$(( $(date +%s) + WALK )); while [ "$(date +%s)" -lt "$end" ]; do
    A shell 'echo $(cat /sys/class/power_supply/usb/current_now) $(cat /sys/class/power_supply/usb/voltage_now) $(cat /sys/class/power_supply/battery/current_now)' | tr -d '\r' >> "$F.pwr"
    sleep 5
  done ) &
pwr_pid=$!
if [ "$PROFILE_S" != 0 ]; then
  sleep 15
  bash /c/lr/tools/thor_profile.sh "${LABEL}_walk" "$PROFILE_S" > "$F.profile.txt" 2>&1
fi
wait $walk_pid $pwr_pid
A exec-out screencap -p > "$F.end.png" 2>/dev/null
A exec-out cat "$LOG" > "$F.lr.log" 2>/dev/null
T1=$(hot)
finish; trap - EXIT
wait $heat_pid 2>/dev/null
stats=$(grep -a "LR main thread (10 s)" "$F.lr.log" | awk -v m="$mark" 'substr($0,2,19) >= m' |
  sed 's/.*(10 s): \([0-9]*\) frames, \([0-9]*\) over 45 ms; per frame \([0-9.]*\) ms = game code \([0-9.]*\).*/\1 \2 \3 \4/' |
  awk '{f+=$1; s+=$2; ms+=$3; g+=$4; n++} END{printf "%d frames, %d over 45 ms (%.1f%%), %.1f ms per frame, game code %.1f ms (%d windows)", f, s, 100*s/(f?f:1), ms/(n?n:1), g/(n?n:1), n}')
lows=$(grep -a "LR frame times (10 s)" "$F.lr.log" | awk -v m="$mark" 'substr($0,2,19) >= m' |
  awk '{for (i = 2; i < NF; i++) { if ($i == "low" && $(i-1) == "1%") a = $(i+1); if ($i == "low" && $(i-1) == "0.1%") b = $(i+1) } print a, b}' |
  awk 'NF==2 {a+=$1; b+=$2; n++; if (!wa || $1<wa) wa=$1; if (!wb || $2<wb) wb=$2} END{if (n) printf "1%% low %.1f fps (worst window %.1f), 0.1%% low %.1f fps (worst %.1f)", a/n, wa, b/n, wb}')
stats="$stats; $lows"
pwr=$(awk 'NF>=3 {p+=$1/1e6*$2/1e6; b+=$3/1e3; n++} END{if (n) printf "USB input %.2f W, battery %.0f mA", p/n, b/n}' "$F.pwr" 2>/dev/null)
echo "[$LABEL] $stats; $pwr; temp $T0 -> $T1 C" | tee "$F.txt"
echo "[$LABEL] profile: $(ls -t /c/lr/out/thor/profile/${LABEL}_walk_*.txt | head -1); heat: $F.heat.txt; log: $F.lr.log"
