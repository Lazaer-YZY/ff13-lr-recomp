#!/bin/bash
# Save games on the Thor for consistent benches (the owner, 2026-11-11): back up the player's saves, keep
# named bench saves, and run a bench with ONLY its bench save present, so it is the newest (the
# load screen opens on it) whatever the player saved since. Anything the game writes during the
# bench (autosaves on entering an area) lands in the temporary folder and is discarded; the
# player's own folder is put back untouched.
#   thor_saves.sh backup                 -> out/thor/saves/backup_YYYYmmdd_HHMMSS.tar (whole user folder)
#   thor_saves.sh list                   -> slots on the device with times, and the kept bench saves
#   thor_saves.sh keep NAME SLOT         -> copy device slot (e.g. 07 for ff13-07) to out/thor/saves/bench/NAME/
#   thor_saves.sh use NAME               -> game stopped; player's saves moved aside, only bench save NAME present
#   thor_saves.sh restore                -> game stopped; the bench folder removed, the player's saves back
# Bench saves (2026-11-11): ark_warp = ff13-07 (the owner, by the Ark warp, 11-10 07:10), lux_plaza = ff13-50
# (autosave on arriving at Luxerion North Station, 11-11 11:37), lux_scaffold = ff13-08 (Luxerion North Station, scaffolding view, day 6 21:39; NOT Yusnaan).
# The game must be stopped for use/restore (both force-stop it first). Never edit while a bench runs.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}; PKG=com.lrrecomp.android
USER_DIR=/sdcard/Android/data/$PKG/files/user/B13EBABEBABEBABE
GAME=$USER_DIR/5351081B
PLAYER=$USER_DIR/5351081B.player   # the player's saves while a bench save is in use
LOCAL=/c/lr/out/thor/saves
A() { adb -s "$S" "$@"; }
RA() { A shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
mkdir -p "$LOCAL/bench"
case "${1:-}" in
  backup)
    f="$LOCAL/backup_$(date +%Y%m%d_%H%M%S).tar"
    A exec-out "run-as $PKG tar -cf - -C /sdcard/Android/data/$PKG/files user" > "$f"
    n=$(tar -tf "$f" 2>/dev/null | grep -c 'APP.DAT$')
    [ "$n" -gt 0 ] || { echo "backup failed (no APP.DAT in $f)"; rm -f "$f"; exit 1; }
    echo "backup: $f ($n save files, $(du -h "$f" | cut -f1))" ;;
  list)
    RA "[ -d $PLAYER ] && echo \"NOTE: a bench save is in use (player saves in 5351081B.player)\";
        for f in $GAME/00000001/*/APP.DAT; do stat -c \"%y %n\" \$f; done" | sed -E 's#\.[0-9]+ \+[0-9]+ .*/(ff13-[0-9]+)\.dat/APP.DAT# \1#'
    echo "kept bench saves:"; ls "$LOCAL/bench" ;;
  keep)
    name=${2:?name}; slot=${3:?slot like 07}
    [ -n "$(RA "[ -d $PLAYER ] && echo busy")" ] && { echo "a bench save is in use: restore first"; exit 1; }
    d="$LOCAL/bench/$name"; rm -rf "$d"; mkdir -p "$d"
    A exec-out "run-as $PKG cat $GAME/00000001/ff13-$slot.dat/APP.DAT" > "$d/APP.DAT"
    A exec-out "run-as $PKG cat $GAME/Headers/00000001/ff13-$slot.dat.header" > "$d/header"
    echo "$slot" > "$d/slot"
    [ -s "$d/APP.DAT" ] && [ -s "$d/header" ] || { echo "slot ff13-$slot not found"; rm -rf "$d"; exit 1; }
    echo "kept ff13-$slot as $name ($(stat -c %s "$d/APP.DAT") bytes)" ;;
  use)
    name=${2:?name}; d="$LOCAL/bench/$name"
    [ -f "$d/APP.DAT" ] || { echo "no bench save $name (thor_saves.sh keep $name SLOT)"; exit 1; }
    slot=$(cat "$d/slot")
    A shell am force-stop $PKG
    if [ -z "$(RA "[ -d $PLAYER ] && echo busy")" ]; then
      RA "mv $GAME $PLAYER" || { echo "could not move the player's saves aside"; exit 1; }
    fi
    # Fresh bench folder with only this save (also replaces one left by an earlier bench).
    A push "$(cygpath -w "$d/APP.DAT")" /data/local/tmp/lr_bench_save >/dev/null 2>&1 &&
      A push "$(cygpath -w "$d/header")" /data/local/tmp/lr_bench_header >/dev/null 2>&1 || { echo "push failed"; exit 1; }
    RA "rm -rf $GAME && mkdir -p $GAME/00000001/ff13-$slot.dat $GAME/Headers/00000001 &&
        cat /data/local/tmp/lr_bench_save > $GAME/00000001/ff13-$slot.dat/APP.DAT &&
        cat /data/local/tmp/lr_bench_header > $GAME/Headers/00000001/ff13-$slot.dat.header" ||
      { echo "could not set up the bench save"; exit 1; }
    A shell rm -f /data/local/tmp/lr_bench_save /data/local/tmp/lr_bench_header
    echo "bench save $name (ff13-$slot) in use; player saves in 5351081B.player" ;;
  restore)
    A shell am force-stop $PKG
    if [ -n "$(RA "[ -d $PLAYER ] && echo busy")" ]; then
      RA "rm -rf $GAME && mv $PLAYER $GAME" && echo "player saves restored" || echo "RESTORE FAILED: player saves are in $PLAYER"
    else
      echo "no bench save in use"
    fi ;;
  *) sed -n 2,14p "$0"; exit 1 ;;
esac
