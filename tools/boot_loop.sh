#!/bin/bash
# Boot loop for the Windows harness: run lr.exe, harvest the fatal "unregistered function" guest address,
# add it to lr_manifest.toml as a bare [entrypoint.functions] entry, regenerate, rebuild, repeat.
# Usage: bash /c/lr/tools/boot_loop.sh [iterations=10] [run_seconds=150]
# Stops when: the run survives run_seconds with no fatal (success), codegen fails (needs manual triage),
# the address is already in the manifest (needs manual look), or iterations are exhausted.
set -u
source /c/lr/tools/env.sh
ITER=${1:-10}
RUNSECS=${2:-150}
ROOT=/c/lr
BUILD=$ROOT/out/build/win-amd64-release
LOG=$ROOT/out/boot_loop.log
MANIFEST=$ROOT/lr_manifest.toml
mkdir -p "$ROOT/out"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

ensure_plugin() { [ -f "$BUILD/rexgpu-xenos.dll" ] || cp /c/dev/rexglue/bin/rexgpu-xenos.dll "$BUILD/"; }

for i in $(seq 1 "$ITER"); do
  ensure_plugin
  RUNLOG=$ROOT/out/boot_run_$i.log
  rm -f "$RUNLOG"
  log "iter $i: running lr.exe for up to ${RUNSECS}s"
  (cd "$BUILD" && timeout "$RUNSECS" ./lr.exe --game_data_root=C:/lrgame --gpu_plugin=xenos --log_file="C:/lr/out/boot_run_$i.log" >/dev/null 2>&1)
  rc=$?
  fatal=$(grep -E "\[FATAL\]" "$RUNLOG" 2>/dev/null | tail -1)
  addr=$(echo "$fatal" | grep -o -E "guest address 0x[0-9A-Fa-f]{8}" | grep -o -E "0x[0-9A-Fa-f]{8}" | tail -1)
  lines=$(wc -l < "$RUNLOG" 2>/dev/null || echo 0)
  if [ -z "$fatal" ]; then
    if [ "$rc" = 124 ]; then
      log "iter $i: SURVIVED ${RUNSECS}s with no FATAL (log $lines lines). Stopping - inspect $RUNLOG for progress."
    else
      log "iter $i: exited rc=$rc without a FATAL line (log $lines lines). Stopping - inspect $RUNLOG."
      grep -E -i "critical|error" "$RUNLOG" | tail -5 | tee -a "$LOG"
    fi
    break
  fi
  log "iter $i: $fatal"
  if [ -z "$addr" ]; then
    # "Unresolved branch from 0xX to 0xY": a seeded entry split a function between Y and X. Prune it.
    pair=$(echo "$fatal" | grep -o -E "from 0x[0-9A-Fa-f]{8} to 0x[0-9A-Fa-f]{8}")
    if [ -n "$pair" ]; then
      x=$(echo "$pair" | awk '{print $2}'); y=$(echo "$pair" | awk '{print $4}')
      n=$(python -I "$ROOT/tools/prune_range.py" "C:/lr/lr_manifest.toml" "$y" "$x")
      if [ "$n" -gt 0 ]; then
        log "iter $i: pruned $n seeded entr(y/ies) in ($y, $x]; codegen..."
        A=""
      else
        log "iter $i: no seeded entry between $y and $x - genuine split, manual triage needed. Stopping."
        break
      fi
    else
      log "iter $i: FATAL without a guest address - manual triage needed. Stopping."
      break
    fi
  else
    A=$(echo "$addr" | tr 'a-f' 'A-F')
    if grep -q -i "^$addr" "$MANIFEST"; then
      log "iter $i: $A is already in the manifest and still fatal - manual look needed. Stopping."
      break
    fi
    echo "$A = {}  # boot loop iter $i $(date +%F)" >> "$MANIFEST"
  fi
  [ -n "$A" ] && log "iter $i: added $A to manifest; codegen..."
  if ! rexglue codegen "$MANIFEST" --ignore-stamp --log-file "$ROOT/out/boot_codegen_$i.log" > "$ROOT/out/boot_codegen_$i.console.txt" 2>&1; then
    log "iter $i: CODEGEN FAILED - see out/boot_codegen_$i.log"
    python -I "$ROOT/tools/triage_codegen.py" "C:/lr/out/boot_codegen_$i.log" | tee -a "$LOG"
    break
  fi
  python -I "$ROOT/tools/patch_generated.py" >/dev/null 2>&1 || log "iter $i: patch_generated.py failed"
  log "iter $i: codegen ok; building..."
  if ! (cd "$ROOT" && cmake --build --preset win-amd64-release --parallel 8 -- -k 0 > "$ROOT/out/boot_build_$i.log" 2>&1); then
    log "iter $i: build failed ($(grep -c 'error:' "$ROOT/out/boot_build_$i.log") errors); pruning split functions and retrying once"
    python -I "$ROOT/tools/prune_from_build.py" "C:/lr/out/boot_build_$i.log" "C:/lr/lr_manifest.toml" | tee -a "$LOG"
    rexglue codegen "$MANIFEST" --ignore-stamp --log-file "$ROOT/out/boot_codegen_${i}b.log" > "$ROOT/out/boot_codegen_${i}b.console.txt" 2>&1 || { log "iter $i: CODEGEN FAILED on retry"; break; }
    if ! (cd "$ROOT" && cmake --build --preset win-amd64-release --parallel 8 -- -k 0 > "$ROOT/out/boot_build_${i}b.log" 2>&1); then
      log "iter $i: BUILD FAILED again - see out/boot_build_${i}b.log"; grep -E "error:" "$ROOT/out/boot_build_${i}b.log" | head -3 | tee -a "$LOG"; break
    fi
  fi
  log "iter $i: build ok"
done
log "boot loop finished; manifest now has $(grep -c '^0x' "$MANIFEST") function entries"
