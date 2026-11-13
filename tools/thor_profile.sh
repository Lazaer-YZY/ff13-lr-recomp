#!/bin/bash
# CPU profile of the game while it is already running (e.g. driven by a player agent in battle).
# Read-only: never wakes, starts or stops anything. The app is debuggable, so simpleperf --app works.
# Usage: [CG=thread-name-part] [SYM=symbol] bash tools/thor_profile.sh LABEL [seconds=10]
#   CG: also record call graphs (CGMODE=dwarf|fp, default dwarf) of that thread for 4 s; SYM (default __kernel_clock_gettime) limits
#   the caller report to samples in that symbol. Offsets in our stripped libs: symbolize on the host with
#   llvm-symbolizer against C:/dev/rexglue-android/lib/*.so or out/build/android-arm64/libmain.so.
# Output: out/thor/profile/LABEL_HHMMSS.txt (by thread+library, then thread+library+symbol) and .data.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; SECS=${2:-10}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}; PKG=com.lrrecomp.android
A() { adb -s "$S" "$@"; }
OUT=/c/lr/out/thor/profile; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S)"
if [ -z "$(A shell pidof $PKG | tr -d '\r')" ]; then echo "game not running"; exit 1; fi
A shell "cd /data/local/tmp && rm -f lr.perf && simpleperf record --app $PKG --duration $SECS -f 2000 -o lr.perf" >/dev/null 2>&1
A pull /data/local/tmp/lr.perf "$(cygpath -w "$F.data")" >/dev/null 2>&1
{ echo "== by thread, library"; A shell "cd /data/local/tmp && simpleperf report -i lr.perf --sort comm,dso 2>/dev/null" | tr -d '\r' | head -45
  echo "== by thread, library, symbol"; A shell "cd /data/local/tmp && simpleperf report -i lr.perf --sort comm,dso,symbol 2>/dev/null" | tr -d '\r' | head -150; } > "$F.txt"
A shell "top -H -b -n 1 -p \$(pidof $PKG) | head -25" | tr -d '\r' > "$F.threads.txt"
if [ -n "${CG:-}" ]; then
  tid=$(A shell "for t in /proc/\$(pidof $PKG)/task/*; do n=\$(cat \$t/comm); case \"\$n\" in *\"$CG\"*) basename \$t; break;; esac; done" | tr -d '\r')
  if [ -n "$tid" ]; then
    A shell "cd /data/local/tmp && rm -f lr_cg.perf && simpleperf record --app $PKG -t $tid --call-graph ${CGMODE:-dwarf} --duration 4 -o lr_cg.perf" >/dev/null 2>&1
    A shell "cd /data/local/tmp && simpleperf report -i lr_cg.perf -g caller --symbols '${SYM:-__kernel_clock_gettime}' 2>/dev/null" | tr -d '\r' | head -150 > "$F.cg.txt"
    echo "call graph of thread $tid ($CG), callers of ${SYM:-__kernel_clock_gettime}: $F.cg.txt"
  fi
fi
echo "profile: $F.txt (threads now: $F.threads.txt)"
grep -E '^ *[0-9.]+%' "$F.txt" | head -16 | tr -s ' ' | cut -c1-150
