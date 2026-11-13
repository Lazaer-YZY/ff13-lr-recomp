#!/bin/bash
# Pushes the extracted game tree to the Thor's app-private game folder over adb, one file at a time with retries.
# Usage: bash /c/lr/tools/push_game_data.sh [source_dir=C:/lrgame]
# Needs: USB debugging on, device authorised (`adb devices` shows it), the app installed once.
# Thor rule: the screen is woken only while the transfer runs (a sleeping device suspends USB and breaks
# multi-GB pushes) and is put to sleep at the end, also on failure.
set -u
export MSYS_NO_PATHCONV=1   # Git Bash must not rewrite /sdcard/... device paths into C:\... paths
source /c/lr/tools/env.sh
SRC=${1:-C:/lrgame}
PKG=com.lrrecomp.android
DST=/sdcard/Android/data/$PKG/files/game
ADB=(adb ${LR_ADB_SERIAL:+-s "$LR_ADB_SERIAL"})
finish() { "${ADB[@]}" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true; }
trap finish EXIT
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "no device: plug in the Thor and accept the USB debugging prompt"; exit 1; }
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB[@]}" shell mkdir -p "$DST/weiss_data/movie" "$DST/weiss_data/sys" "$DST/weiss_data/zone" || { echo "cannot create $DST (is the app installed?)"; exit 1; }
fail=0
push_one() { # local_path device_path
  local size dsize attempt
  size=$(stat -c %s "$1")
  dsize=$("${ADB[@]}" shell stat -c %s "$2" 2>/dev/null | tr -d '\r')
  if [ "$dsize" = "$size" ]; then echo "have $(basename "$1")"; return 0; fi
  for attempt in 1 2 3; do
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    if "${ADB[@]}" push "$1" "$2" 2>&1 | tail -1; then
      dsize=$("${ADB[@]}" shell stat -c %s "$2" 2>/dev/null | tr -d '\r')
      [ "$dsize" = "$size" ] && return 0
      echo "size mismatch after push ($dsize vs $size), retrying"
    fi
    echo "retry $attempt for $(basename "$1")"; sleep 5
    "${ADB[@]}" get-state >/dev/null 2>&1 || { echo "device gone"; return 1; }
  done
  return 1
}
push_one "$SRC/default.xex" "$DST/default.xex" || fail=1
for rel in weiss_data/img.key $(cd "$SRC" && find weiss_data -type f ! -name img.key | sort); do
  [ -f "$SRC/$rel" ] || continue
  push_one "$SRC/$rel" "$DST/$rel" || { fail=1; echo "FAILED: $rel"; }
done
echo "--- device listing ---"
"${ADB[@]}" shell "du -sh $DST/weiss_data; find $DST -type f | wc -l"
echo "local files: $(find "$SRC/weiss_data" -type f | wc -l) + default.xex"
[ $fail -eq 0 ] && echo "push complete" || echo "push INCOMPLETE (see FAILED lines)"
