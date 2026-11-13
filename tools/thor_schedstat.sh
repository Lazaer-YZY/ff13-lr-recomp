#!/bin/bash
# Per-thread CPU time and run-queue delay of the running game over SECS seconds
# (/proc/PID/task/*/schedstat: ns on a CPU, ns runnable but waiting for a CPU, timeslices).
# A thread with a large "waiting for a CPU" share is starved by its affinity / core count.
# Only reads; the caller owns waking and stopping the Thor.
# Usage: bash tools/thor_schedstat.sh [SECS=10] [TOP=16]
set -u
export MSYS_NO_PATHCONV=1
SECS=${1:-10}; TOP=${2:-16}
PKG=com.lrrecomp.android
adb shell "pid=\$(pidof $PKG); [ -z \"\$pid\" ] && { echo 'game not running'; exit 1; };
snap() { for t in /proc/\$pid/task/*; do s=\$(cat \$t/schedstat 2>/dev/null) || continue; c=\$(cat \$t/comm 2>/dev/null | tr ' ' '_'); echo \"\${t##*/} \$c \$s\"; done; }
snap > /data/local/tmp/lr_ss0; sleep $SECS; snap > /data/local/tmp/lr_ss1;
cat /data/local/tmp/lr_ss0 | sed 's/^/A /'; cat /data/local/tmp/lr_ss1 | sed 's/^/B /'" | tr -d '\r' |
awk -v secs="$SECS" -v top="$TOP" '
  $1=="A" {r[$2]=$4; d[$2]=$5; s[$2]=$6}
  $1=="B" && ($2 in r) {name[$2]=$3; run=($4-r[$2])/1e6; del=($5-d[$2])/1e6; sl=$6-s[$2];
    if (run+del > 1) {print run, del, sl, $2, $3}
    trun+=run; tdel+=del}
  END {printf "TOTAL %.0f ms on a CPU (%.0f%% of a core), %.0f ms waiting for a CPU, over %d s\n", trun, 100*trun/(secs*1000), tdel, secs > "/dev/stderr"}' |
sort -k1,1 -rn | head -"$TOP" | awk '{printf "%8.0f ms run %7.0f ms wait (%4.1f%%) %6d slices  %s %s\n", $1, $2, 100*$2/($1+$2), $3, $4, $5}'
