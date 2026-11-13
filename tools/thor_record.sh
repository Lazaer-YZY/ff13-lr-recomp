#!/bin/bash
# Record both Thor screens while someone plays (read-only: never starts, stops or touches the game).
#   bash tools/thor_record.sh start [MINUTES=30]  -> chained 3-min clips per screen on the device
#   bash tools/thor_record.sh stop [OUT_DIR]      -> stops recording, pulls clips to OUT_DIR (default out/thor/rec/<time>), deletes them on the device
# Top: 1920x1080, bottom: 1240x1080, H.264 at 24 Mbit/s; frame rate follows the screens' updates (up to 60).
# screenrecord caps each clip at 180 s, so a device-side loop chains clips (top_NNN.mp4 / bottom_NNN.mp4).
set -u
export MSYS_NO_PATHCONV=1
S=${LR_ADB_SERIAL:-$(adb get-serialno | tr -d '\r')}
A() { adb -s "$S" "$@"; }
DIR=/sdcard/Movies/lr_rec
TOP=4630946441858561667; BOTTOM=4630946482288158084
case "${1:-}" in
  start)
    MIN=${2:-30}; N=$(( (MIN * 60 + 179) / 180 ))
    A shell "rm -rf $DIR; mkdir -p $DIR"
    for side in top bottom; do
      id=$TOP; [ $side = bottom ] && id=$BOTTOM
      A shell "nohup sh -c 'i=0; while [ \$i -lt $N ] && [ ! -f $DIR/.stop ]; do screenrecord --display-id $id --bit-rate 24000000 --time-limit 180 $DIR/${side}_\$(printf %03d \$i).mp4; i=\$((i+1)); done' >/dev/null 2>&1 &"
    done
    echo "recording both screens for up to $MIN min into $DIR ('stop' to end)" ;;
  stop)
    OUT=${2:-out/thor/rec/$(date +%Y%m%d_%H%M%S)}
    A shell "touch $DIR/.stop; pkill -INT screenrecord"
    sleep 3
    mkdir -p "$OUT"
    for f in $(A shell "ls $DIR/*.mp4 2>/dev/null" | tr -d '\r'); do A pull "$f" "$OUT/" >/dev/null && echo "  $(basename "$f")"; done
    A shell "rm -rf $DIR"
    echo "clips in $OUT" ;;
  *) sed -n 2,6p "$0"; exit 1 ;;
esac
