#!/bin/bash
# Heat sampler for gameplay sessions driven by someone else (a player agent using tools/thor_pad.sh).
# Does NOT wake, start or stop anything: it only reads the device while the game runs.
# Usage: bash tools/thor_heat_sampler.sh LABEL [max_seconds=1200] [interval=10]
# Writes out/thor/heat/LABEL_HHMMSS.csv: time, game running, hottest zone (C), GPU MHz, GPU busy %,
# cpu0/cpu3/cpu7 kHz. Stops when the game has been gone for 60 s after it was seen, or at max_seconds.
# Summary (printed at the end): samples while the game ran, avg/peak temperature, avg GPU MHz/busy.
set -u
export MSYS_NO_PATHCONV=1
source /c/lr/tools/env.sh >/dev/null 2>&1
LABEL=${1:?label}; MAX=${2:-1200}; STEP=${3:-10}
S=${LR_ADB_SERIAL:-$(adb get-serialno 2>/dev/null | tr -d '\r')}; PKG=com.lrrecomp.android
OUT=/c/lr/out/thor/heat; mkdir -p "$OUT"; F="$OUT/${LABEL}_$(date +%H%M%S).csv"
# temp_c = hottest sensor on the chip (die temperature); board_c = xo-therm (mainboard sensor beside the chip;
# the Thor exposes no case sensor, the case is between battery_c and board_c); battery_c = battery pack.
# cpu_c = hottest CPU sensor (cpu-*, cpuss-*), gpu_c = hottest GPU sensor (gpuss-*) (the owner, 2026-11-13:
# report CPU and GPU temperature separately). usb_w = USB input power; batt_ma/batt_mv = battery current
# (sign as the kernel reports it) and voltage, for the device's power draw. New columns are appended
# so older column positions stay valid.
echo "time,running,temp_c,gpu_mhz,gpu_busy,cpu0_khz,cpu3_khz,cpu7_khz,board_c,battery_c,cpu_c,gpu_c,usb_w,batt_ma,batt_mv" > "$F"
zone_of() { adb -s "$S" shell "for z in /sys/class/thermal/thermal_zone*; do [ \"\$(cat \$z/type)\" = $1 ] && echo \$z/temp && break; done" | tr -d '\r'; }
zones_like() { adb -s "$S" shell "for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in $1) echo \$z/temp;; esac; done" | tr -d '\r' | tr '\n' ' '; }
ZB=$(zone_of xo-therm); ZBAT=$(zone_of battery)
ZCPU=$(zones_like 'cpu-*|cpuss-*'); ZGPU=$(zones_like 'gpuss-*')
PS=/sys/class/power_supply
c1() { echo "$(( ${1:-0} / 1000 )).$(( ${1:-0} % 1000 / 100 ))"; }  # milli-degrees -> one decimal
seen=0; gone=0; start=$(date +%s)
while [ $(( $(date +%s) - start )) -lt "$MAX" ]; do
  line=$(adb -s "$S" shell "p=\$(pidof $PKG); t=\$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1); \
xb=\$(cat ${ZB:-/dev/null} 2>/dev/null); xa=\$(cat ${ZBAT:-/dev/null} 2>/dev/null); \
g=\$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null); b=\$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null); \
r=0; [ -n \"\$p\" ] && r=1; echo \$r \$t \$g \${b%%[!0-9]*} \$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) \
\$(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq) \${xb:-0} \${xa:-0} \
\$(cat ${ZCPU:-/dev/null} 2>/dev/null | sort -n | tail -1) \$(cat ${ZGPU:-/dev/null} 2>/dev/null | sort -n | tail -1) \
\$(cat $PS/usb/current_now) \$(cat $PS/usb/voltage_now) \$(cat $PS/battery/current_now) \$(cat $PS/battery/voltage_now)" 2>/dev/null | tr -d '\r')
  set -- $line
  run=${1:-0}; temp=${2:-0}; gclk=${3:-0}; busy=${4:-0}
  busy=${busy%%[^0-9]*}
  usbw=$(awk -v i="${12:-0}" -v v="${13:-0}" 'BEGIN{printf "%.2f", i/1e6*v/1e6}')
  echo "$(date +%H:%M:%S),$run,$((temp/1000)),$((gclk/1000000)),${busy:-0},${5:-0},${6:-0},${7:-0},$(c1 "${8:-0}"),$(c1 "${9:-0}"),$(c1 "${10:-0}"),$(c1 "${11:-0}"),$usbw,$(( ${14:-0} / 1000 )),$(( ${15:-0} / 1000 ))" >> "$F"
  if [ "$run" = 1 ]; then seen=1; gone=0; elif [ "$seen" = 1 ]; then gone=$((gone + STEP)); [ "$gone" -ge 60 ] && break; fi
  sleep "$STEP"
done
python -I - "$(cygpath -m "$F")" <<'EOF'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r['running'] == '1']
if not rows:
    print('no samples with the game running'); sys.exit()
t = [int(r['temp_c']) for r in rows]; g = [int(r['gpu_mhz']) for r in rows]; b = [int(r['gpu_busy'] or 0) for r in rows]
print(f"{sys.argv[1]}: {len(rows)} samples with the game running; temp avg {sum(t)/len(t):.1f} C, peak {max(t)} C, "
      f"last 5 avg {sum(t[-5:])/len(t[-5:]):.1f} C; GPU avg {sum(g)/len(g):.0f} MHz, busy {sum(b)/len(b):.0f}%")
for k, name in (('cpu_c', 'CPU'), ('gpu_c', 'GPU'), ('board_c', 'board (xo-therm)'), ('battery_c', 'battery')):
    v = [float(r[k]) for r in rows if r.get(k) and float(r[k]) > 0]
    if v:
        print(f"  {name}: avg {sum(v)/len(v):.1f} C, peak {max(v):.1f} C, end {v[-1]:.1f} C")
# Power draw of the whole device: on battery = battery V x I; on USB with the battery full (|I| < 50 mA)
# = USB input; on USB while charging neither is the device alone, so both are printed.
p = [(float(r['usb_w']), int(r['batt_ma']), int(r['batt_mv'])) for r in rows if r.get('batt_mv')]
if p:
    usb = sum(x[0] for x in p) / len(p); ma = sum(x[1] for x in p) / len(p); mv = sum(x[2] for x in p) / len(p)
    if usb < 0.5:
        print(f"  power: {abs(ma) * mv / 1e6:.2f} W average (on battery, {abs(ma):.0f} mA at {mv / 1000:.2f} V)")
    elif abs(ma) < 50:
        print(f"  power: {usb:.2f} W average (USB input, battery full)")
    else:
        print(f"  power: not the device alone (charging): USB input {usb:.2f} W, battery {ma:.0f} mA")
EOF
