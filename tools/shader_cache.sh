#!/bin/bash
# Keep or share the game's compiled-shader cache (Lightning Returns recomp on Android), over adb.
#
#   bash tools/shader_cache.sh pull [DIR]   copy the cache from the device to DIR (default
#                                           out/shader_cache/YYYYMMDD_HHMMSS) plus DIR.zip to share
#   bash tools/shader_cache.sh push SRC     merge SRC (a folder or .zip from pull, e.g. a friend's)
#                                           into the device's cache; nothing already there is lost
#
# What is shared: 5351081B.xsh (the game's shaders as the game loaded them) and
# 5351081B.fbo.vk.xpso (which shader + render state combinations were used). At every start the
# game compiles everything listed there before the title screen, so places the cache's owner has
# visited don't hitch on first sight. The driver's 5351081B.vk.pipelinecache (compiled GPU code)
# is pulled as a backup of this device only and never pushed: it only fits the same GPU and driver
# build, and every device builds its own in the background. The first start after a push takes
# longer (it compiles the new entries once); later starts are fast again.
# Caches only merge between the same app build (the files carry a format version).
# The .xsh file contains shader code from the game: only share it with people who own the game.
#
# The game is force-stopped first (it rewrites these files while running) and the device is put
# to sleep at the end. With several adb devices set LR_ADB_SERIAL.
set -u
export MSYS_NO_PATHCONV=1
[ -f /c/lr/tools/env.sh ] && source /c/lr/tools/env.sh >/dev/null 2>&1
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$HERE/.." && pwd)
PKG=com.lrrecomp.android
DEV=/storage/emulated/0/Android/data/$PKG/files/cache/shaders/shareable
FILES="5351081B.xsh 5351081B.fbo.vk.xpso"
A() { adb ${LR_ADB_SERIAL:+-s "$LR_ADB_SERIAL"} "$@"; }
win() { command -v cygpath >/dev/null && cygpath -m "$1" || echo "$1"; }
PYBIN=python; python -c "" >/dev/null 2>&1 || PYBIN=python3
PY() { "$PYBIN" -I "$@"; }
finish() { A shell am force-stop $PKG >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1; }
die() { echo "error: $*" >&2; exit 1; }

cmd=${1:-}; trap finish EXIT
A get-state >/dev/null 2>&1 || die "no adb device (USB debugging on, cable in, authorised?)"
A shell am force-stop $PKG
case "$cmd" in
  pull)
    out=${2:-$REPO/out/shader_cache/$(date +%Y%m%d_%H%M%S)}; mkdir -p "$out"; out=$(cd "$out" && pwd)
    for f in $FILES 5351081B.vk.pipelinecache; do
      A pull "$DEV/$f" "$(win "$out")/$f" >/dev/null 2>&1 && echo "pulled $f ($(wc -c < "$out/$f") bytes)"
    done
    [ -f "$out/5351081B.xsh" ] || die "no shader cache on the device yet (play a bit first)"
    zip="$out.zip"; ( cd "$out" && PY -m zipfile -c "$(win "$zip")" $FILES ) && echo "to share: $zip (without the device-specific driver cache)"
    ;;
  push)
    src=${2:?usage: shader_cache.sh push FOLDER_OR_ZIP}
    tmp=$(mktemp -d); mkdir -p "$tmp/in" "$tmp/device" "$tmp/merged"
    case "$src" in /*|[A-Za-z]:*) ;; *) src="$PWD/$src" ;; esac
    if [ -f "$src" ]; then PY -m zipfile -e "$(win "$src")" "$(win "$tmp/in")" || die "cannot unzip $src"
    elif [ -d "$src" ]; then cp "$src"/5351081B.xsh "$src"/5351081B.fbo.vk.xpso "$src"/5351081B.psmeta "$tmp/in/" 2>/dev/null
    else die "$src not found"; fi
    [ -f "$tmp/in/5351081B.xsh" ] || die "$src has no 5351081B.xsh"
    for f in $FILES; do A pull "$DEV/$f" "$(win "$tmp/device")/$f" >/dev/null 2>&1; done
    PY "$(win "$HERE/merge_shader_cache.py")" "$(win "$tmp/merged")" "$(win "$tmp/device")" "$(win "$tmp/in")" || die "merge failed, device unchanged"
    # Files must end up owned by the game, or it can't add to them (adb writes as the shell user).
    # Debug builds: write them as the app with run-as. Otherwise the game rewrites them as its own
    # copy at the next start (SDK patch 70).
    for f in $FILES; do
      [ -f "$tmp/merged/$f" ] || continue
      A push "$(win "$tmp/merged/$f")" "/data/local/tmp/lr_shader_cache_$f" >/dev/null 2>&1 || die "push of $f failed"
      if A shell "run-as $PKG sh -c 'mkdir -p $DEV && cat /data/local/tmp/lr_shader_cache_$f > $DEV/$f.new && mv -f $DEV/$f.new $DEV/$f'" 2>/dev/null; then
        echo "$f written as the app"
      else
        A shell "mkdir -p $DEV && cp /data/local/tmp/lr_shader_cache_$f $DEV/$f.new && mv -f $DEV/$f.new $DEV/$f" || die "copy of $f failed"
        echo "$f written as the shell user (the game takes it over at the next start)"
      fi
      A shell "rm -f /data/local/tmp/lr_shader_cache_$f"
    done
    # Pixel shader metadata for the ahead-of-time pipeline libraries (tools/lr_shader_scan.py
    # output; the same for everyone with the same game build): copied as is when present.
    if [ -f "$tmp/in/5351081B.psmeta" ]; then
      A push "$(win "$tmp/in/5351081B.psmeta")" /data/local/tmp/lr_shader_cache_psmeta >/dev/null 2>&1 &&
        { A shell "run-as $PKG sh -c 'cat /data/local/tmp/lr_shader_cache_psmeta > $DEV/5351081B.psmeta'" 2>/dev/null ||
          A shell "cp /data/local/tmp/lr_shader_cache_psmeta $DEV/5351081B.psmeta"; } &&
        echo "5351081B.psmeta copied (pixel shader libraries are precompiled at the next start)"
      A shell "rm -f /data/local/tmp/lr_shader_cache_psmeta"
    fi
    echo "merged cache pushed; the next start compiles the new entries once"
    rm -rf "$tmp"
    ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
