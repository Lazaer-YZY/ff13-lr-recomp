#!/bin/bash
# Thor smoke test: does the port still boot, load a save, render, hold its frame rate, fight and
# run its cheats? Run after changes to the renderer, codegen / translation, the SDK, src/ hooks
# or the Android app, and before every release (CLAUDE.md, "Regression tests").
#
# Usage: bash tools/thor_regress.sh [LABEL=regress] [--install] [--baseline] [--only boot,field,cheats,battle]
#   --install   install the APK built last (android/app/build/outputs/apk/debug/app-debug.apk) first;
#               build it with tools/build_android.sh build stage apk (or regress_host.sh --build)
#   --baseline  keep this run's numbers and pictures as the reference (out/regress/thor/baseline/)
#   FORCE=1     run even when the Thor looks in use (awake, or a game running): only when it is ours
#   FPS=30|60g  frame-rate mode for the run (default 30, the dev default; the public build ships 60g)
#
# Tests (each a cold start with only its bench save present, tools/thor_saves.sh):
#   boot    cold start to the title menu (A until the opening movie): no crash, Vulkan + shader storage up, title picture vs reference
#   field   lux_plaza: loads into the field, stands 30 s: avg fps / 1% low vs reference, the clock runs,
#           field picture vs reference
#   cheats  lux_plaza with LR_CHEATS 3: the hooks report both cheats on, the clock stands still,
#           EP is readable and, when the save's EP is below its maximum, topped up to it
#   battle  save_0756 (a journal page, then a battle): the battle starts and runs 20 s without a crash,
#           avg fps / 1% low vs reference
# Fails a test on: the game gone, a native crash in logcat, '[critical]' / 'LR hang' lines, the
# expected log lines missing, fps below the reference (avg < 92 %, 1% low < 75 %), a picture
# unlike the reference (SSIM below the limit) or black / flat.
# Report: out/regress/thor/<LABEL>_<time>/report.txt (PASS/FAIL per test, then avg fps, 1% low, CPU and
# GPU temperature avg/peak, power draw: the owner's format). Exit 0 = all passed.
# Always ends with force-stop, the player's saves restored and KEYCODE_SLEEP (Thor rule), also on failure.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=regress INSTALL=0 BASELINE=0 ONLY=boot,field,cheats,battle
while [ $# -gt 0 ]; do
  case "$1" in
    --install) INSTALL=1 ;;
    --baseline) BASELINE=1 ;;
    --only) ONLY=$2; shift ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) LABEL=$1 ;;
  esac
  shift
done
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}
PKG=com.lrrecomp.android
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
PAD=/c/lr/tools/thor_pad.sh
REF=/c/lr/out/regress/thor/baseline
RUN=/c/lr/out/regress/thor/${LABEL}_$(date +%Y%m%d_%H%M%S)
mkdir -p "$RUN" "$REF"
A() { adb -s "$S" "$@"; }
# The frame-time overlay off whatever the player chose: it changes every frame and would lower the SSIM.
EXTRAS="--es LR_DIAG 1 --es LR_FPS ${FPS:-30} --es LR_AA off --es REX_PRESENT_EFFECT bilinear --es LR_FRAME_OVERLAY 0"

say() { echo "[$LABEL] $*" | tee -a "$RUN/run.log"; }
results=()
metrics=()
failed=0
heat_pid=""
finish() {
  A shell am force-stop $PKG >/dev/null 2>&1
  bash /c/lr/tools/thor_saves.sh restore 2>&1 | grep -v "no bench save in use" | sed "s/^/[$LABEL] /"
  A shell rm -f /data/local/tmp/lr_failsafe >/dev/null 2>&1
  A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  [ -n "$heat_pid" ] && kill "$heat_pid" 2>/dev/null
}
trap finish EXIT

# --- Thor in use? (2026-11-14: an install killed the owner's live game; other projects share the Thor)
busy() {
  local wake top
  wake=$(A shell 'dumpsys power | grep -m1 mWakefulness=' 2>/dev/null | tr -d '\r' | sed 's/.*=//')
  top=$(A shell 'dumpsys activity activities | grep -m1 topResumedActivity' 2>/dev/null | tr -d '\r')
  if [ "$wake" != Asleep ]; then echo "the Thor is awake ($top)"; return 0; fi
  if echo "$top" | grep -q -E 'recomp|RuntimeActivity'; then echo "a game is open: $top"; return 0; fi
  # Another port's game still running in the background (the DS2 port shares the Thor, 2026-11-18).
  local other
  other=$(A shell 'ps -A -o NAME' 2>/dev/null | tr -d '\r' | grep -i recomp | grep -v -x "$PKG" | head -1)
  if [ -n "$other" ]; then echo "another game is running: $other"; return 0; fi
  return 1
}
[ -n "$S" ] && A get-state >/dev/null 2>&1 || { echo "no Thor on adb"; trap - EXIT; exit 2; }
if [ "${FORCE:-0}" != 1 ] && why=$(busy); then
  echo "[$LABEL] not started: $why (FORCE=1 if it is ours)"; trap - EXIT; exit 4
fi
say "run folder $RUN; extras: $EXTRAS"

if [ "$INSTALL" = 1 ]; then
  apk=/c/lr/android/app/build/outputs/apk/debug/app-debug.apk
  [ -f "$apk" ] || { say "no APK at $apk"; exit 2; }
  A shell am force-stop $PKG
  A install -r "$(cygpath -w "$apk")" > "$RUN/install.txt" 2>&1 || { cat "$RUN/install.txt"; say "install FAILED"; exit 1; }
  say "installed $(stat -c %y "$apk" | cut -c1-16) APK"
fi

bash /c/lr/tools/thor_heat_sampler.sh "${LABEL}_regress" 1500 10 > "$RUN/heat.txt" 2>&1 &
heat_pid=$!

# --- helpers ---------------------------------------------------------------------------------
devtime() { A shell "date '+%Y-%m-%d %H:%M:%S'" | tr -d '\r'; }
# Log lines since MARK matching PATTERN (ERE).
since() { A shell "grep -a -E '$2' $LOG 2>/dev/null" | tr -d '\r' | awk -v m="$1" 'substr($0,2,19) >= m'; }
alive() { [ -n "$(A shell pidof $PKG | tr -d '\r')" ]; }
# Wait up to SECS for a log line since MARK matching PATTERN; prints it. 1 = not seen, 2 = game gone.
wait_log() {
  local mark=$1 pat=$2 secs=$3 t0=$SECONDS line
  while [ $((SECONDS - t0)) -lt "$secs" ]; do
    line=$(since "$mark" "$pat" | tail -1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    alive || return 2
    sleep 2
  done
  return 1
}
# The newest companion state since MARK (every 5 s with LR_DIAG): "key=value;..." without the tail.
state() { since "$1" 'LR companion state:' | tail -1 | sed 's/.*LR companion state: //; s/ |.*//'; }
field_now() {  # in the world, no loading screen, no battle
  local s; s=$(state "$1")
  echo "$s" | grep -q 'worldx=' && ! echo "$s" | grep -q -E 'loading=1|battle=1'
}
start_game() {  # start_game SAVE "extra am extras" -> prints the device time of the start
  A shell am force-stop $PKG
  bash /c/lr/tools/thor_saves.sh use "$1" > /dev/null || { echo "bench save $1 missing" >&2; return 1; }
  A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
  local m; m=$(devtime)
  A shell "am start -n $PKG/.RuntimeActivity $EXTRAS $2" >/dev/null
  echo "$m"
}
press() { timeout 20 bash "$PAD" press "$@" >/dev/null; }
# From a cold start to the title menu. Before the opening movie the game waits on a black screen
# until a button is pressed (2026-11-18: none in 2 min with the screen on and no press; the old
# benches pressed A at 24 s). So: A every ~10 s from 20 s on until the movie starts (~23 s), A once
# more to skip it (unless that A already did), then the menu ~4 s later. Sets MOVIE_S (seconds to
# the movie). 1 = no movie / menu, 2 = game gone.
MOVIE_S=""
to_title() {
  local mark=$1 t0=$SECONDS
  MOVIE_S=""
  sleep 20
  until since "$mark" 'LR movie: BinkOpen' | grep -q .; do
    alive || return 2
    [ $((SECONDS - t0)) -lt 300 ] || return 1
    press A 300
    wait_log "$mark" 'LR movie: BinkOpen' 10 >/dev/null && break
  done
  MOVIE_S=$((SECONDS - t0))
  sleep 2
  since "$mark" 'LR movie: BinkClose' | grep -q . || press A 300
  wait_log "$mark" 'LR title menu:' 20 >/dev/null && return 0
  press A 300
  wait_log "$mark" 'LR title menu:' 30 >/dev/null
}
# From a cold start into the only save present (the save list opens on it): title, CONTINUE, the save,
# the confirmations (timings of tools/thor_bench_walk.sh, from the title menu on).
open_save() {
  to_title "$1" || return 1
  sleep 1; press START 600; sleep 5; press START 600; sleep 6
  press A; sleep 4; press A; sleep 3; press A; sleep 6; press A; sleep 8
  press A; sleep 12
}
# open_save, then A until the companion state says field (quest / tip screens).
load_save() {
  local mark=$1
  open_save "$mark" || return 1
  for _ in $(seq 12); do
    field_now "$mark" && return 0
    alive || return 2
    press A 300; sleep 5
  done
  field_now "$mark"
}
# 10-s stats since MARK -> "avg_fps low1 over45 windows"
fps_since() {
  local main low
  main=$(since "$1" 'LR main thread \(10 s\)' | sed 's/.*(10 s): \([0-9]*\) frames, \([0-9]*\) over 45 ms.*/\1 \2/' |
    awk '{f+=$1; s+=$2; n++} END{if (n) printf "%.1f %d %d", f/(10*n), s, n; else print "0 0 0"}')
  low=$(since "$1" 'LR frame times \(10 s\)' | sed -n 's/.* 1% low \([0-9.]*\) fps.*/\1/p' | awk '{a+=$1; n++} END{if (n) printf "%.1f", a/n; else print 0}')
  set -- $main
  echo "$1 $low $2 $3"
}
# Problems since MARK: crashes, critical lines, hangs.
problems() {
  local mark=$1 md crash crit
  md=$(echo "$mark" | cut -c6-19)  # MM-DD HH:MM:SS (logcat's time format)
  crash=$(A logcat -b crash -d 2>/dev/null | tr -d '\r' | awk -v m="$md" 'substr($0,1,14) >= m' | grep -c -E "$PKG|Fatal signal")
  crit=$(since "$mark" '\[critical\]|LR hang|FATAL' | wc -l)
  [ "$crash" = 0 ] || echo "native crash in logcat ($crash lines)"
  [ "$crit" = 0 ] || echo "$crit critical/hang lines in lr.log, first: $(since "$mark" '\[critical\]|LR hang|FATAL' | head -1 | cut -c26-160)"
}
# Screenshot vs reference: SSIM (ffmpeg) and a black / flat check. pic NAME LIMIT -> "ssim" (or "new"); 1 = fail
pic() {
  local name=$1 limit=$2 shot="$RUN/$1.png" ref="$REF/$1.png" st yavg yrange ssim
  A exec-out screencap -p > "$shot" 2>/dev/null
  # ffmpeg is a Windows program and MSYS_NO_PATHCONV=1 is on: give it Windows paths.
  local wshot wref; wshot=$(cygpath -w "$shot"); wref=$(cygpath -w "$ref")
  st=$(ffmpeg -v error -i "$wshot" -vf signalstats,metadata=print:file=- -f null - 2>/dev/null | tr -d '\r')
  yavg=$(echo "$st" | sed -n 's/.*signalstats.YAVG=\([0-9.]*\).*/\1/p' | head -1)
  yrange=$(echo "$st" | awk -F= '/YMAX=/ {mx=$2} /YMIN=/ {mn=$2} END{print mx-mn}')
  # Limited-range luma: black is 16, so a black screen is Y avg ~16 with no range.
  if awk -v a="${yavg:-0}" -v r="${yrange:-0}" 'BEGIN{exit !(a < 20 || r < 40)}'; then echo "black/flat (Y avg ${yavg:-?}, range ${yrange:-?})"; return 1; fi
  if [ "$BASELINE" = 1 ] || [ ! -f "$ref" ]; then cp "$shot" "$ref"; echo "new"; return 0; fi
  ssim=$(ffmpeg -v info -i "$wshot" -i "$wref" -lavfi "[0:v]scale=640:-2[a];[1:v]scale=640:-2[b];[a][b]ssim" -f null - 2>&1 |
    sed -n 's/.*SSIM.* All:\([0-9.]*\).*/\1/p' | tail -1)
  echo "${ssim:-?}"
  awk -v s="${ssim:-0}" -v l="$limit" 'BEGIN{exit !(s >= l)}'
}
# Reference numbers: baseline/numbers.txt lines "name value".
ref_num() { awk -v k="$1" '$1 == k {print $2}' "$REF/numbers.txt" 2>/dev/null; }
keep_num() {
  [ "$BASELINE" = 1 ] || [ -z "$(ref_num "$1")" ] || return 0
  { grep -v "^$1 " "$REF/numbers.txt" 2>/dev/null; echo "$1 $2"; } > "$REF/numbers.tmp" && mv "$REF/numbers.tmp" "$REF/numbers.txt"
}
record() {  # record TEST STATUS DETAIL
  results+=("$(printf '%-6s %-7s %s' "$2" "$1" "$3")")
  [ "$2" = PASS ] || failed=1
  say "$1: $2 $3"
}
wanted() { echo ",$ONLY," | grep -q ",$1,"; }
stop_game() { A shell am force-stop $PKG; }

# --- boot ------------------------------------------------------------------------------------
if wanted boot; then
  fails=()
  mark=$(start_game lux_plaza "--es LR_CHEATS 0") || fails+=("start failed")
  v=$(wait_log "$mark" 'Vulkan setup took' 40) || fails+=("no 'Vulkan setup took' in 40 s")
  vk=$(echo "$v" | sed -n 's/.*Vulkan setup took \([0-9]*\) ms.*/\1/p')
  sh=$(wait_log "$mark" 'Shader storage loaded in' 60 | sed -n 's/.*loaded in \([0-9]*\) ms.*/\1/p')
  [ -n "$sh" ] || fails+=("no 'Shader storage loaded' in 60 s")
  if to_title "$mark"; then
    sleep 3
    ssim=$(pic title 0.95) || fails+=("title picture: $ssim")
  else
    [ -n "$MOVIE_S" ] && fails+=("no title menu after the opening movie") || fails+=("no opening movie in 300 s")
    A exec-out screencap -p > "$RUN/title_stuck.png" 2>/dev/null; ssim="-"
  fi
  alive || fails+=("game gone")
  p=$(problems "$mark"); [ -n "$p" ] && fails+=("$p")
  stop_game
  metrics+=("boot: Vulkan setup ${vk:-?} ms, shader storage ${sh:-?} ms, opening movie after ${MOVIE_S:-?} s$([ "$INSTALL" = 1 ] && echo ' (first start after the install)')")
  detail="Vulkan setup ${vk:-?} ms, shader storage ${sh:-?} ms, movie after ${MOVIE_S:-?} s, title picture SSIM ${ssim}"
  [ ${#fails[@]} = 0 ] && record boot PASS "$detail" || record boot FAIL "$(IFS='; '; echo "${fails[*]}") | $detail"
fi

# --- field -----------------------------------------------------------------------------------
if wanted field; then
  fails=()
  mark=$(start_game lux_plaza "--es LR_CHEATS 0") || fails+=("start failed")
  if load_save "$mark"; then
    sleep 5; m2=$(devtime); c0=$(state "$mark" | grep -o 'clock=[0-9:]*')
    sleep 32
    set -- $(fps_since "$m2"); fps=$1; low=$2; over=$3; win=$4
    c1=$(state "$m2" | grep -o 'clock=[0-9:]*')
    [ "$win" -ge 2 ] || fails+=("only $win stats windows (LR_DIAG off?)")
    rf=$(ref_num field_fps); rl=$(ref_num field_low1)
    if [ -n "$rf" ] && [ "$BASELINE" != 1 ]; then
      awk -v f="$fps" -v r="$rf" 'BEGIN{exit !(f >= 0.92 * r)}' || fails+=("avg fps $fps < 92% of reference $rf")
      awk -v f="$low" -v r="$rl" 'BEGIN{exit !(f >= 0.75 * r)}' || fails+=("1% low $low < 75% of reference $rl")
    fi
    keep_num field_fps "$fps"; keep_num field_low1 "$low"
    [ -n "$c1" ] && [ "$c1" != "$c0" ] || fails+=("game clock did not run (${c0:-?} -> ${c1:-?})")
    ssim=$(pic field 0.75) || fails+=("field picture: $ssim")
    metrics+=("field: avg $fps fps, 1% low $low fps, $over frames over 45 ms in $win windows (reference ${rf:-none}/${rl:-none})")
    detail="avg $fps fps, 1% low $low, clock ${c0:-?} -> ${c1:-?}, picture SSIM $ssim"
  else
    fails+=("did not reach the field (state: $(state "$mark" | cut -c1-120))"); detail=""
    A exec-out screencap -p > "$RUN/field_stuck.png" 2>/dev/null
  fi
  alive || fails+=("game gone")
  p=$(problems "$mark"); [ -n "$p" ] && fails+=("$p")
  stop_game
  [ ${#fails[@]} = 0 ] && record field PASS "$detail" || record field FAIL "$(IFS='; '; echo "${fails[*]}") | $detail"
fi

# --- cheats ----------------------------------------------------------------------------------
if wanted cheats; then
  fails=()
  mark=$(start_game lux_plaza "--es LR_CHEATS 3") || fails+=("start failed")
  wait_log "$mark" 'LR cheats at start: infinite EP on, clock frozen' 40 >/dev/null ||
    fails+=("no 'LR cheats at start ... on, clock frozen' line")
  if load_save "$mark"; then
    sleep 5; m2=$(devtime); c0=$(state "$mark" | grep -o 'clock=[0-9:]*;day=[0-9-]*')
    sleep 30
    c1=$(state "$m2" | grep -o 'clock=[0-9:]*;day=[0-9-]*')
    [ -n "$c0" ] && [ "$c0" = "$c1" ] || fails+=("clock moved with the cheat on (${c0:-?} -> ${c1:-?})")
    held=$(since "$mark" 'LR cheats: clock held at' | head -1 | sed 's/.*LR cheats: //' | cut -c1-90)
    [ -n "$held" ] || fails+=("no 'clock held' line")
    # Infinite EP: the hook logs the EP it first sees, then every top-up (the saves have EP below 5).
    ep=$(since "$mark" 'LR cheats: EP [0-9.]+ of [0-9.]+, held' | head -1 | sed 's/.*LR cheats: EP //; s/,.*//')
    topup=$(since "$mark" 'LR cheats: EP [0-9.]+ -> [0-9.]+' | head -1 | sed 's/.*LR cheats: //')
    if [ -z "$ep" ]; then
      fails+=("EP never readable (no 'LR cheats: EP ... held' line)")
    elif awk -v e="${ep% of *}" -v m="${ep#* of }" 'BEGIN{exit !(e < m)}' && [ -z "$topup" ]; then
      fails+=("EP $ep and never topped up")
    fi
    detail="clock ${c0:-?} -> ${c1:-?}; $held; EP ${ep:-?}${topup:+, $topup}"
  else
    fails+=("did not reach the field"); detail=""
  fi
  alive || fails+=("game gone")
  p=$(problems "$mark"); [ -n "$p" ] && fails+=("$p")
  stop_game
  [ ${#fails[@]} = 0 ] && record cheats PASS "$detail" || record cheats FAIL "$(IFS='; '; echo "${fails[*]}") | $detail"
fi

# --- battle ----------------------------------------------------------------------------------
if wanted battle; then
  fails=()
  mark=$(start_game save_0756 "--es LR_CHEATS 0") || fails+=("start failed")
  open_save "$mark"; press A
  inb=""
  # The save opens on a story-journal page first ("Proceed"): A until the battle starts.
  for _ in $(seq 12); do
    state "$mark" | grep -q 'battle=1' && { inb=1; break; }
    alive || break
    press A 300; sleep 5
  done
  if [ -n "$inb" ]; then
    m2=$(devtime); sleep 22
    set -- $(fps_since "$m2"); fps=$1; low=$2; over=$3; win=$4
    [ "$win" -ge 2 ] || fails+=("only $win stats windows")
    rf=$(ref_num battle_fps); rl=$(ref_num battle_low1)
    if [ -n "$rf" ] && [ "$BASELINE" != 1 ]; then
      awk -v f="$fps" -v r="$rf" 'BEGIN{exit !(f >= 0.92 * r)}' || fails+=("avg fps $fps < 92% of reference $rf")
    fi
    if [ -n "$rl" ] && [ "$BASELINE" != 1 ]; then
      awk -v f="$low" -v r="$rl" 'BEGIN{exit !(f >= 0.75 * r)}' || fails+=("1% low $low < 75% of reference $rl")
    fi
    keep_num battle_fps "$fps"; keep_num battle_low1 "$low"
    metrics+=("battle: avg $fps fps, 1% low $low fps, $over frames over 45 ms in $win windows (reference ${rf:-none}/${rl:-none})")
    detail="battle reached; avg $fps fps, 1% low $low"
  else
    fails+=("no battle (state: $(state "$mark" | cut -c1-120))"); detail=""
    A exec-out screencap -p > "$RUN/battle_stuck.png" 2>/dev/null
  fi
  alive || fails+=("game gone")
  p=$(problems "$mark"); [ -n "$p" ] && fails+=("$p")
  stop_game
  [ ${#fails[@]} = 0 ] && record battle PASS "$detail" || record battle FAIL "$(IFS='; '; echo "${fails[*]}") | $detail"
fi

pid=$heat_pid; heat_pid=""
finish; trap - EXIT
# The sampler prints its summary (CPU / GPU avg and peak, power) when it ends, 60 s after the game.
say "waiting for the heat sampler's summary (~60 s)"
wait "$pid" 2>/dev/null
temps=$(grep -E '^  (CPU|GPU|power):' "$RUN/heat.txt" 2>/dev/null | sed 's/^  //; s/, end [0-9.]* C//' | paste -sd ';' - | sed 's/;/; /g')
{
  echo "Thor smoke test $LABEL, $(date '+%Y-%m-%d %H:%M'), $(git -C C:/lr log -1 --format='%h %s' | cut -c1-70)$(git -C C:/lr diff --quiet || echo ' + uncommitted changes')"
  printf '%s\n' "${results[@]}"
  printf '%s\n' "${metrics[@]}"
  echo "report: ${temps:-no CPU/GPU temperatures (heat sampler: $RUN/heat.txt)}"
  [ "$failed" = 0 ] && echo "result: PASS" || echo "result: FAIL"
} | tee "$RUN/report.txt"
exit "$failed"
