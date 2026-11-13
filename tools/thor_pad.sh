#!/bin/bash
# Thor controls for a player agent: one action per call, prints what it did.
#   thor_pad.sh start ["--cvar=v ..."] -> wake the Thor and (re)start the game, optionally with extra runtime cvars
#   thor_pad.sh driver system|<id>     -> select the GPU driver for the next start (system or an installed package id)
#   thor_pad.sh shot [name]            -> screenshot to out/thor/live/<name or timestamp>.png, prints the path
#   thor_pad.sh press A|B|X|Y|START|BACK(=SELECT)|UP|DOWN|LEFT|RIGHT|LB|RB [hold_ms=600]
#   thor_pad.sh grep PATTERN [n]       -> last n (5) lines of the game log matching PATTERN
#   thor_pad.sh native                 -> native backtraces of all game threads (debuggerd) to out/thor/live/native_*.txt
#   thor_pad.sh threads                -> top 12 threads of the game by CPU right now (thread names)
#   thor_pad.sh stick <x> <y> <ms>     -> left stick, x/y in -100..100 (up is y=-100), held for ms, then centred
#   thor_pad.sh rstick <x> <y> <ms>    -> right stick (camera), same units (ABS_Z / ABS_RZ on the Thor)
#   thor_pad.sh status                 -> game running?, hottest thermal zone, last 'LR hang'/error lines of the game log
#   thor_pad.sh stop                   -> pull logs to out/thor/live/, force-stop the game, KEYCODE_SLEEP (Thor rule)
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android; DEV=/dev/input/event9; OUT=/c/lr/out/thor/live
LOG=/sdcard/Android/data/$PKG/files/logs/lr.log
mkdir -p "$OUT"
A() { adb -s "$S" "$@"; }
# Inputs go only to our game in the foreground (2026-11-13: a stalled script's presses resumed after the
# failsafe had stopped the game, reached the ES-DE launcher and started another emulator).
GUARD="dumpsys activity activities 2>/dev/null | grep -m1 'ResumedActivity' | grep -q $PKG || { echo 'not sent: Lightning Returns is not in the foreground'; exit 3; };"
case "${1:-}" in
  start)
    A shell am force-stop $PKG
    # SAVE=name (tools/thor_saves.sh): only that bench save present, so agent sessions load it and
    # their autosaves never move the player's newest save; 'stop' puts the player's saves back.
    if [ -n "${SAVE:-}" ]; then bash /c/lr/tools/thor_saves.sh use "$SAVE" || exit 1; fi
    A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard >/dev/null 2>&1
    # Extra am-start extras for experiments (e.g. "--es LR_AFFINITY 78"), read from out/thor/start_extras.txt.
    X=$(cat /c/lr/out/thor/start_extras.txt 2>/dev/null | tr -d '')
    if [ -n "${2:-}" ]; then A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $X --es cvars '${2}'" >/dev/null; else A shell "am start -n $PKG/.RuntimeActivity --es LR_DIAG 1 $X" >/dev/null; fi       && echo "game started (screen awake)${2:+ with cvars: $2}${X:+ extras: $X}; call 'stop' when done"
    # Failsafe on the device itself (2026-11-12: a session cut off mid-run left the game running all
    # night): after FAILSAFE_S (default 1800) it force-stops the game and sleeps the screen, unless
    # 'stop' or a newer 'start' changed the token first. Bench saves are put back by the next
    # session (thor_saves.sh list shows the NOTE line).
    tok="$(date +%s)$$"
    A shell "echo $tok > /data/local/tmp/lr_failsafe; setsid sh -c 'sleep ${FAILSAFE_S:-1800}; [ \"\$(cat /data/local/tmp/lr_failsafe 2>/dev/null)\" = $tok ] && { am force-stop $PKG; input keyevent KEYCODE_SLEEP; rm -f /data/local/tmp/lr_failsafe; }' >/dev/null 2>&1 </dev/null &" ;;
  driver)
    # driver system | driver <installed id, e.g. Turnip_v26.0.0_R8>  -> select the GPU driver for the next start
    id="${2:-}"; [ "$id" = system ] && id=""
    A shell "run-as $PKG sh -c 'mkdir -p shared_prefs && printf \"%s\n\" \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?>\" \"<map>\" \"    <string name=\\\"selected\\\">$id</string>\" \"    <boolean name=\\\"choice_made\\\" value=\\\"true\\\" />\" \"</map>\" > shared_prefs/gpu_driver.xml && cat shared_prefs/gpu_driver.xml'" ;;
  shot)
    f="$OUT/${2:-$(date +%H%M%S)}.png"
    # Device time of each screenshot (it stalls the game ~1 s): frame-stat windows containing one
    # can be left out of an analysis.
    A shell "date '+%Y-%m-%d %H:%M:%S'" | tr -d '\r' >> "$OUT/shots.log"
    A exec-out screencap -p > "$f" 2>/dev/null && echo "$(cygpath -w "$f" 2>/dev/null || echo "$f")" ;;
  press)
    # The Thor in its Nintendo button mode: the SDK swaps the face buttons back to positions
    # (gamepad_face_buttons_by_position), so the game's A is BTN_EAST 305, B 304, X 308, Y 307.
    case "${2:-}" in A) c=305;; B) c=304;; X) c=308;; Y) c=307;; START) c=315;; SELECT|BACK) c=314;; LB) c=310;; RB) c=311;;
      UP) c=544;; DOWN) c=545;; LEFT) c=546;; RIGHT) c=547;; *) echo "unknown button ${2:-}"; exit 1;; esac
    ms=${3:-600}; secs=$(awk "BEGIN{printf \"%.3f\", $ms/1000}")
    A shell "$GUARD sendevent $DEV 1 $c 1; sendevent $DEV 0 0 0; sleep $secs; sendevent $DEV 1 $c 0; sendevent $DEV 0 0 0" && echo "pressed $2 for ${ms}ms" ;;
  stick)
    x=$(( ${2:-0} * 32767 / 100 )); y=$(( ${3:-0} * 32767 / 100 )); ms=${4:-1000}; secs=$(awk "BEGIN{printf \"%.3f\", $ms/1000}")
    A shell "$GUARD sendevent $DEV 3 0 $x; sendevent $DEV 3 1 $y; sendevent $DEV 0 0 0; sleep $secs; sendevent $DEV 3 0 0; sendevent $DEV 3 1 0; sendevent $DEV 0 0 0" && echo "stick $2,$3 for ${ms}ms" ;;
  rstick)
    x=$(( ${2:-0} * 32767 / 100 )); y=$(( ${3:-0} * 32767 / 100 )); ms=${4:-1000}; secs=$(awk "BEGIN{printf \"%.3f\", $ms/1000}")
    A shell "$GUARD sendevent $DEV 3 2 $x; sendevent $DEV 3 5 $y; sendevent $DEV 0 0 0; sleep $secs; sendevent $DEV 3 2 0; sendevent $DEV 3 5 0; sendevent $DEV 0 0 0" && echo "rstick $2,$3 for ${ms}ms" ;;
  status)
    pid=$(A shell pidof $PKG | tr -d '\r')
    hot=$(A shell "cat /sys/class/thermal/thermal_zone*/temp" 2>/dev/null | tr -d '\r' | sort -n | tail -1)
    echo "game: ${pid:+running (pid $pid)}${pid:-NOT RUNNING}; thermal max $((${hot:-0}/1000))C"
    A shell "grep -E 'LR hang|\\[critical\\]|FATAL' $LOG 2>/dev/null | tail -3" | cut -c1-200 ;;
  grep)
    A shell "grep -E '${2:-LR hang}' $LOG 2>/dev/null | tail -${3:-5}" | cut -c1-220 ;;
  native)
    # native backtraces of every thread (debuggable app): debuggerd -b, via run-as if plain shell is refused
    pid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
    [ -n "$pid" ] || { echo "game NOT RUNNING"; exit 0; }
    f="$OUT/native_$(date +%H%M%S).txt"
    A shell "debuggerd -b $pid" > "$f" 2>&1
    if ! grep -q "backtrace:" "$f"; then A shell "run-as $PKG debuggerd -b $pid" > "$f" 2>&1; fi
    echo "$(grep -c '^"' "$f") threads dumped to $(cygpath -w "$f")"; head -3 "$f" | cut -c1-160 ;;
  threads)
    pid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
    [ -n "$pid" ] || { echo "game NOT RUNNING"; exit 0; }
    A shell "top -H -b -n 1 -d 1 -p $pid -o TID,%CPU,CMD | sed -n '5,17p'" | tr -s ' ' ;;
  stop)
    d="$OUT/logs_$(date +%H%M%S)"; A pull "/sdcard/Android/data/$PKG/files/logs" "$(cygpath -w "$d")" >/dev/null 2>&1 || echo "WARNING: log pull failed"
    A logcat -d > "$d.logcat.txt" 2>/dev/null
    A shell "rm -f /data/local/tmp/lr_failsafe"  # cancels the start's failsafe
    A shell am force-stop $PKG; bash /c/lr/tools/thor_saves.sh restore | grep -v "no bench save in use"
    A shell input keyevent KEYCODE_SLEEP; echo "logs in $d; game stopped; Thor asleep" ;;
  *) sed -n '2,14p' "$0"; exit 1 ;;
esac
