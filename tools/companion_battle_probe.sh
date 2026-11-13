#!/bin/bash
# Second-screen research, run while a battle is on screen (the game started by thor_pad.sh): guest
# snapshots with each of the three schemata active (RB between them), each with a screenshot of the
# HUD as ground truth (schema name, ATB, HP). Leaves the game running: end with thor_pad.sh stop.
# Usage: bash tools/companion_battle_probe.sh LABEL   -> out/dual/dumps/LABEL_s{0,1,2}.gmem + .png
set -u
export MSYS_NO_PATHCONV=1
LABEL=${1:?label}
OUT=out/dual/dumps; mkdir -p "$OUT"
for i in 0 1 2; do
  bash tools/companion_dump.sh "${LABEL}_s$i" "$OUT"
  adb exec-out screencap -p > "$OUT/${LABEL}_s$i.png"
  [ $i -lt 2 ] && { bash /c/lr/tools/thor_pad.sh press RB 250 >/dev/null; sleep 1.5; }
done
bash tools/companion_dump.sh "${LABEL}_s2b" "$OUT"
adb exec-out screencap -p > "$OUT/${LABEL}_s2b.png"
