#!/bin/bash
# Where one thread of the running game spends its time, on and OFF the CPU (blocked in futexes,
# ioctls, fence waits): simpleperf --trace-offcpu with DWARF call graphs for SECS seconds.
# Read-only: never wakes, starts or stops anything.
# Usage: bash tools/thor_offcpu.sh LABEL THREAD_NAME_PART [SECS=3]
# Output: out/thor/profile/LABEL_offcpu_HHMMSS.txt (caller tree, children = inclusive time).
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; NAME=${2:?thread name part}; SECS=${3:-3}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}; PKG=com.lrrecomp.android
A() { adb -s "$S" "$@"; }
OUT=/c/lr/out/thor/profile; mkdir -p "$OUT"; F="$OUT/${LABEL}_offcpu_$(date +%H%M%S)"
tid=$(A shell "for t in /proc/\$(pidof $PKG)/task/*; do n=\$(cat \$t/comm); case \"\$n\" in *\"$NAME\"*) basename \$t; break;; esac; done" | tr -d '\r')
[ -z "$tid" ] && { echo "thread '$NAME' not found"; exit 1; }
A shell "cd /data/local/tmp && rm -f lr_off.perf && simpleperf record --app $PKG -t $tid -e cpu-clock -f 4000 --trace-offcpu --call-graph ${CGMODE:-dwarf} --duration $SECS -o lr_off.perf" > "$F.rec.txt" 2>&1
A shell "cd /data/local/tmp && simpleperf report -i lr_off.perf --children --sort symbol 2>/dev/null" | tr -d '\r' | head -60 > "$F.txt"
echo "== caller graph" >> "$F.txt"
A shell "cd /data/local/tmp && simpleperf report -i lr_off.perf -g caller --percent-limit 3 --sort symbol 2>/dev/null" | tr -d '\r' | head -400 >> "$F.txt"
A pull /data/local/tmp/lr_off.perf "$(cygpath -w "$F.data")" >/dev/null 2>&1
echo "thread $tid ($NAME): $F.txt"
grep -E '^ *[0-9.]+% +[0-9.]+%' "$F.txt" | head -25 | tr -s ' ' | cut -c1-160
