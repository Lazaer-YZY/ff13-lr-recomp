#!/bin/bash
# "First visit" version of the warp bench (the owner, 2026-11-10: replicate first-visit hitches by
# clearing the caches every run). Moves the shader/pipeline caches aside (guest shader storage .xsh,
# pipeline descriptions .xpso, the driver pipeline cache, library keys and precompile marker; the
# offline-scan .psmeta stays, as on every device), then by default (PRECOMPILE=1) starts the game
# at the title until the one-time pixel shader library precompile has finished (as on the owner's device,
# which did it on its first boot), and runs tools/thor_bench_warp.sh: everything specific to the
# warp area (vertex shaders, linked pipelines, texture sign variants) is then new. Afterwards the
# originals are put back (also on failure) and the cold run's files deleted (KEEP=1 keeps them as
# *.LABEL). Every device step ends with force-stop + KEYCODE_SLEEP (Thor rule).
# Usage: [CLEAR=all] [PRECOMPILE=0] bash tools/thor_warp_cold.sh LABEL ["am extras"] [HOLD=140]
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; EXTRAS=${2:-}; HOLD=${3:-140}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '')}; PKG=com.lrrecomp.android
DIR=/sdcard/Android/data/$PKG/files/cache/shaders/shareable
# CLEAR=driver (default): keep the guest shader storage (.xsh, which on a player's device holds the
# ~7,600 shaders of the offline scan plus everything seen) and clear what a first visit lacks: the
# driver pipeline cache, library keys, precompile marker and pipeline descriptions; the title
# precompile then rebuilds every pixel shader library (as after the first boot) and the warp area
# compiles its vertex shaders, links and texture sign variants on demand. CLEAR=all also clears the
# shader storage (exaggerates: every library is compiled during play).
if [ "${CLEAR:-driver}" = all ]; then
  FILES="5351081B.xsh 5351081B.fbo.vk.xpso 5351081B.vk.pipelinecache 5351081B.vk.libkeys 5351081B.vk.libprecompiled"
else
  FILES="5351081B.fbo.vk.xpso 5351081B.vk.pipelinecache 5351081B.vk.libkeys 5351081B.vk.libprecompiled"
fi
A() { adb -s "$S" "$@"; }
RA() { A shell "run-as $PKG sh -c '$1'"; }
restore() {
  A shell am force-stop $PKG >/dev/null 2>&1; A shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  if [ "${KEEP:-0}" = 1 ]; then
    RA "cd $DIR && for f in $FILES; do [ -f \$f ] && mv -f \$f \$f.$LABEL; [ -f \$f.warm ] && mv -f \$f.warm \$f; done; ls -la"
  else
    RA "cd $DIR && for f in $FILES; do rm -f \$f; [ -f \$f.warm ] && mv -f \$f.warm \$f; done; ls -la"
  fi
}
trap restore EXIT
A shell am force-stop $PKG >/dev/null 2>&1
RA "cd $DIR && for f in $FILES; do [ -f \$f ] && mv -f \$f \$f.warm; done; ls"
if [ "${PRECOMPILE:-1}" = 1 ]; then
  bash /c/lr/tools/thor_warm_libs.sh "$EXTRAS"
fi
cp /c/lr/tools/thor_bench_warp.sh /c/lr/out/thor/bench/_warp_cold_run.sh
bash /c/lr/out/thor/bench/_warp_cold_run.sh "$LABEL" "$EXTRAS" "$HOLD" 45
