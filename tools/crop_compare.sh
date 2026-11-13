#!/bin/bash
# Crops the same region out of several screenshots and tiles them side by side (ffmpeg), for
# anti-aliasing / upscaling comparisons at 1:1 pixels.
# Usage: bash tools/crop_compare.sh OUT.png "X:Y:W:H" IMG1.png IMG2.png [IMG3.png ...]
# e.g. bash tools/crop_compare.sh out/thor/aa/compare.png "1100:300:480:270" a.png b.png c.png
set -eu
out=$1; crop=$2; shift 2
IFS=: read -r x y w h <<<"$crop"
inputs=(); filter=""; n=0
for img in "$@"; do
  inputs+=(-i "$(cygpath -m "$img" 2>/dev/null || echo "$img")")
  filter+="[$n:v]crop=$w:$h:$x:$y,scale=iw*2:ih*2:flags=neighbor[c$n];"
  n=$((n+1))
done
if [ "$n" -gt 1 ]; then
  for i in $(seq 0 $((n-1))); do filter+="[c$i]"; done
  filter+="hstack=inputs=$n"
else
  filter="[0:v]crop=$w:$h:$x:$y,scale=iw*2:ih*2:flags=neighbor"
fi
ffmpeg -y -loglevel error "${inputs[@]}" -filter_complex "$filter" "$(cygpath -m "$out" 2>/dev/null || echo "$out")"
echo "$out"
