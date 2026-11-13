#!/bin/bash
# Samples one thread of the running game N times with debuggerd -b (symbolized native stacks of a
# debuggable app) and counts the distinct top-of-stack sequences, e.g. what the GPU command
# thread is blocked in. Read-only: never wakes, starts or stops anything.
# Usage: bash tools/thor_stacks.sh LABEL "THREAD NAME PART" [N=20] [DEPTH=10]
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; NAME=${2:?thread name}; N=${3:-20}; DEPTH=${4:-10}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}; PKG=com.lrrecomp.android
A() { adb -s "$S" "$@"; }
OUT=/c/lr/out/thor/profile; mkdir -p "$OUT"; F="$OUT/${LABEL}_stacks_$(date +%H%M%S)"
pid=$(A shell pidof $PKG | tr -dc '0-9 ' | awk '{print $1}')
[ -n "$pid" ] || { echo "game not running"; exit 1; }
: > "$F.raw.txt"
for i in $(seq "$N"); do
  A shell "run-as $PKG debuggerd -b $pid 2>&1" | tr -d '\r' |
    awk -v name="$NAME" '/^"/ {keep = index($0, name) > 0} keep' >> "$F.raw.txt"
  echo "----" >> "$F.raw.txt"
done
# One line per sample: the top DEPTH frames' function names (offsets dropped).
awk -v d="$DEPTH" '
  /^----/ { if (s != "") print s; s = ""; n = 0; next }
  /^ *#[0-9]+ pc/ { if (n < d) { f = $0; sub(/.*\) *\(/, "", f); sub(/\+[0-9]+\)$/, "", f); sub(/\)$/, "", f);
      if (f == $0) { f = $NF } s = s (n ? " < " : "") f; n++ } }' "$F.raw.txt" |
  sort | uniq -c | sort -rn > "$F.txt"
echo "$N samples of '$NAME': $F.txt"
cut -c1-400 "$F.txt" | head -12
