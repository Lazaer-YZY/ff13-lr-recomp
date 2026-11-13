#!/usr/bin/env bash
# Build the Android arm64 native libraries (NDK CMake), stage them into the
# Gradle project's jniLibs, assemble the APK, and optionally install + launch.
#
# Usage: tools/build_android.sh [configure] [build] [stage] [apk] [install] [launch] [all]
#                                [logs] [--install] [--launch] [-j N]
#   no step args / "all" = configure build stage apk
#   --install / --launch  add those steps after the selected ones; `logs` pulls logcat + files/logs
#   Device steps wake the Thor only for the run and end with force-stop + KEYCODE_SLEEP (owner's rule).
#   Env: LR_RUN_SECS (120), LR_ADB_SERIAL, LR_THOR_LOGS (C:/lr/out/thor), LR_SCREENSHOTS="20 45 80" (seconds), LR_KEYS="25:pad:A 35:pad:START" (pad: = sendevent on LR_PAD_DEV, default /dev/input/event9)
#
# Env overrides: LR_ANDROID_SDK_PREFIX (default C:/dev/rexglue-android),
#                LR_ANDROID_BUILD_TYPE (default Release).
# Logs: C:/dev/logs/android_lr_{configure,build,gradle}.log
set -euo pipefail

REPO=${LR_REPO:-C:/lr}   # LR_REPO: another worktree (its own out/build); shared assets stay in C:/lr/out
MAIN_OUT=C:/lr/out
BUILD=${LR_BUILD_DIR:-$REPO/out/build/android-arm64}   # LR_BUILD_DIR: override (testing)
SDK_PREFIX=${LR_ANDROID_SDK_PREFIX:-C:/dev/rexglue-android}
BUILD_TYPE=${LR_ANDROID_BUILD_TYPE:-Release}
# Clang PGO (configure step): default = the merged profile in out/pgo/lr.profdata when present
# (git-ignored: derived from the game); LR_PGO= (empty) builds without, LR_PGO=generate builds
# the instrumented library. Re-collect after a codegen change (see DECISIONS 2026-11-10 PGO).
if [ -z "${LR_PGO+x}" ] && [ -f "$MAIN_OUT/pgo/lr.profdata" ]; then LR_PGO="$MAIN_OUT/pgo/lr.profdata"; fi
JNI_DIR=$REPO/android/app/src/main/jniLibs/arm64-v8a
APK=$REPO/android/app/build/outputs/apk/debug/app-debug.apk
PKG=com.lrrecomp.android
ACTIVITY=${LR_ACTIVITY:-.GpuDriverActivity}   # LAUNCHER activity (forwards to RuntimeActivity); LR_ACTIVITY=.RuntimeActivity skips the GPU-driver page
LOGS=${LR_LOGS:-C:/dev/logs}
JOBS=8
HOOK_TARGETS=(hook_impl main_hook file_redirect_hook gsl_alloc_hook)

die() { echo "build_android: ERROR: $*" >&2; exit 1; }
# Header check without `readelf | grep -q` (pipefail + SIGPIPE would give false failures).
is_aarch64() { local h; h=$("$NDK_BIN/llvm-readelf.exe" --file-header "$1" 2>/dev/null) || return 1; grep -q "AArch64" <<<"$h"; }
step() { echo; echo "=== $* ==="; }

# --- args ---------------------------------------------------------------
steps=()
extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    configure|build|stage|apk|install|launch|logs) steps+=("$1") ;;
    all) steps+=(configure build stage apk) ;;
    --install) extra+=(install) ;;
    --launch) extra+=(launch) ;;
    -j) shift; JOBS=${1:?-j needs a number} ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
  shift
done
[ ${#steps[@]} -eq 0 ] && steps=(configure build stage apk)
steps+=("${extra[@]}")
has() { local s; for s in "${steps[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

# --- environment --------------------------------------------------------
# shellcheck disable=SC1091
source /c/lr/tools/env.sh
# env.sh targets the Windows host build: drop the host compiler and SDK prefix
# so neither leaks into the NDK configure.
unset CC CXX RC CMAKE_PREFIX_PATH
NDK=$(cygpath -m "$ANDROID_NDK_HOME")
NDK_BIN=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin
LIBCXX=$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || die "NDK not found at $NDK"
mkdir -p "$LOGS"

# --- configure ----------------------------------------------------------
if has configure; then
  step configure
  [ -d "$SDK_PREFIX/lib/cmake/rexglue" ] || die "Android ReXGlue SDK not installed at $SDK_PREFIX (missing lib/cmake/rexglue)"
  # Codegen is a host step (rexglue codegen on Windows); generated/rexglue.cmake
  # skips the build-time codegen command when the SDK has no rex::rexglue CLI,
  # as with this Android install, so generated/default must already be current.
  [ -f "$REPO/generated/default/sources.cmake" ] || die "generated/default/sources.cmake missing: run host codegen first"
  cmake -S "$REPO" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DLR_PGO="${LR_PGO:-}" \
    -DCMAKE_FIND_ROOT_PATH="$SDK_PREFIX" -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DCMAKE_PREFIX_PATH="$SDK_PREFIX" -Drexglue_DIR="$SDK_PREFIX/lib/cmake/rexglue" \
    > "$LOGS/android_lr_configure.log" 2>&1 \
    || { grep -n -i "error" "$LOGS/android_lr_configure.log" | head -30 || true; die "configure failed (log: $LOGS/android_lr_configure.log)"; }
  grep -E "Found ReXGlue" "$LOGS/android_lr_configure.log" || true
  echo "configure OK"
fi

# --- build --------------------------------------------------------------
if has build; then
  step build
  [ -f "$BUILD/build.ninja" ] || die "not configured; run '$0 configure' first"
  # The adrenotools hook libs are not dependencies of lr: build them explicitly.
  if ! cmake --build "$BUILD" --target lr "${HOOK_TARGETS[@]}" --parallel "$JOBS" \
      > "$LOGS/android_lr_build.log" 2>&1; then
    grep -n -E "error:|FAILED:|undefined (symbol|reference)" "$LOGS/android_lr_build.log" | head -30 || true
    die "build failed (log: $LOGS/android_lr_build.log)"
  fi
  [ -f "$BUILD/libmain.so" ] || die "build reported success but $BUILD/libmain.so is missing"
  ls -l "$BUILD/libmain.so" || die "cannot stat libmain.so"
  echo "build OK"
fi

# --- stage --------------------------------------------------------------
find_one() { # name, search dirs...
  local name=$1; shift; local d f
  for d in "$@"; do
    [ -d "$d" ] || continue
    f=$(find "$d" -name "$name" -type f 2>/dev/null | head -1) || true   # head closing the pipe may SIGPIPE find (pipefail)
    [ -n "$f" ] && { echo "$f"; return 0; }
  done
  return 1
}
if has stage; then
  step stage
  mkdir -p "$JNI_DIR"
  declare -A SRC
  SRC[libmain.so]=$BUILD/libmain.so
  for lib in libSDL3.so librexruntime.so librexgpu-xenos.so; do
    SRC[$lib]=$(find_one "$lib" "$SDK_PREFIX/lib" "$SDK_PREFIX/bin" "$SDK_PREFIX") \
      || die "$lib not found under $SDK_PREFIX/{lib,bin}"
  done
  SRC[libc++_shared.so]=$LIBCXX
  for t in "${HOOK_TARGETS[@]}"; do
    SRC[lib$t.so]=$(find_one "lib$t.so" "$BUILD/thirdparty/libadrenotools") \
      || die "lib$t.so not built (run the build step)"
  done
  for lib in "${!SRC[@]}"; do
    src=${SRC[$lib]}
    [ -f "$src" ] || die "missing $src"
    is_aarch64 "$src" || die "$src is not an AArch64 ELF"
    "$NDK_BIN/llvm-strip.exe" --strip-unneeded -o "$JNI_DIR/$lib" "$src" \
      || die "llvm-strip failed on $src"
    is_aarch64 "$JNI_DIR/$lib" || die "staged $lib is not an AArch64 ELF"
    printf '  %-26s %10s bytes  <- %s\n' "$lib" "$(stat -c %s "$JNI_DIR/$lib")" "$src"
  done
  echo "stage OK -> $JNI_DIR"
  # The GPU driver every Adreno device runs (GpuDriverStore.BUNDLED_TURNIP_*): whitebelyash's
  # Stable Turnip v2 (Mesa 26.2, MIT), pinned by release and checksum, packed as an APK asset.
  # Not in git (binary): fetched into out/drivers/ once.
  TURNIP_URL=https://github.com/whitebelyash/AdrenoToolsDrivers/releases/download/stu_v2/stable-turnip-V2.zip
  TURNIP_SHA256=426846964f9b9c3f182b9298a805e85c1f062a590f622cf4ddc9b0b5934a581e
  TURNIP_ZIP=$MAIN_OUT/drivers/stable_v2/stable-turnip-V2.zip
  if [ ! -f "$TURNIP_ZIP" ] || [ "$(sha256sum "$TURNIP_ZIP" | cut -d' ' -f1)" != "$TURNIP_SHA256" ]; then
    mkdir -p "$(dirname "$TURNIP_ZIP")"
    curl -fsSL -o "$TURNIP_ZIP" "$TURNIP_URL" || die "could not download $TURNIP_URL"
  fi
  [ "$(sha256sum "$TURNIP_ZIP" | cut -d' ' -f1)" = "$TURNIP_SHA256" ] || die "$TURNIP_ZIP: checksum mismatch"
  mkdir -p "$REPO/android/app/src/main/assets/gpu_driver"
  cp -f "$TURNIP_ZIP" "$REPO/android/app/src/main/assets/gpu_driver/stable-turnip-V2.zip"
  echo "  bundled GPU driver: Stable Turnip v2 ($(stat -c %s "$TURNIP_ZIP") bytes, sha256 ok)"
  # Second screen: the area maps, built once from the game files (tools/lr_navimap.py). Derived from
  # the game like the generated code, so never in git; bundled as APK assets. Needs a Python with
  # numpy + Pillow (LR_PY_NUMPY, default out/venv) and the game tree (LR_GAME_ROOT, default C:/lrgame).
  NAVI_DIR=$REPO/android/app/src/main/assets/navimap
  NAVI_PY=${LR_PY_NUMPY:-$REPO/out/venv/Scripts/python.exe}
  NAVI_STAMP=$(cat "$REPO/tools/lr_navimap.py" "$REPO/tools/upscale_assets.py" | sha256sum | cut -c1-16)
  if [ ! -f "$NAVI_DIR/navimaps.json" ] || [ "$(cat "$NAVI_DIR/.stamp" 2>/dev/null)" != "$NAVI_STAMP" ]; then
    if [ -x "$NAVI_PY" ]; then
      rm -rf "$NAVI_DIR"
      mkdir -p "$NAVI_DIR"
      ( cd "$REPO" && "$NAVI_PY" -I tools/lr_navimap.py "${LR_GAME_ROOT:-C:/lrgame}" "$NAVI_DIR" )         || die "lr_navimap.py failed (the bottom screen's area maps)"
      rm -f "$NAVI_DIR"/_sheet*.png
      echo "$NAVI_STAMP" > "$NAVI_DIR/.stamp"
    else
      echo "  WARNING: no Python with numpy/Pillow at $NAVI_PY: the APK ships without area maps"
    fi
  fi
  [ -f "$NAVI_DIR/navimaps.json" ] && echo "  area maps: $(ls "$NAVI_DIR"/navimap_*.png | wc -l) navimaps bundled"
  # ... and the HUD pieces it is drawn with (tools/companion_hud.py; 4x Real-ESRGAN when installed).
  # Rebuilt whenever the tools change (a stamp of their checksums), so a new piece or a new
  # upscale setting reaches the APK without deleting the folder by hand.
  HUD_DIR=$REPO/android/app/src/main/assets/hud
  HUD_STAMP=$(cat "$REPO/tools/companion_hud.py" "$REPO/tools/upscale_assets.py" "$REPO/tools/lr_gui_tex.py" | sha256sum | cut -c1-16)
  if [ -x "$NAVI_PY" ] && { [ ! -f "$HUD_DIR/hud.json" ] || [ "$(cat "$HUD_DIR/.stamp" 2>/dev/null)" != "$HUD_STAMP" ]; }; then
    rm -rf "$HUD_DIR"
    ( cd "$REPO" && "$NAVI_PY" -I tools/companion_hud.py "${LR_GAME_ROOT:-C:/lrgame}" "$HUD_DIR" )       || die "companion_hud.py failed (the bottom screen's HUD pieces)"
    echo "$HUD_STAMP" > "$HUD_DIR/.stamp"
  fi
  [ -f "$HUD_DIR/hud.json" ] && echo "  HUD pieces: $(ls "$HUD_DIR"/*.png | wc -l) bundled"
  # The game's text font is not a file in its data (system.xgr only has the logo and "Now loading");
  # the closest match is Verdana (wide, large x-height, double-storey a, regular weight), copied from
  # the builder's own Windows fonts (never in git, like the game assets); without it, the system sans.
  FONT_DIR=$REPO/android/app/src/main/assets/fonts
  rm -f "$FONT_DIR/hud_rounded.ttf"
  if [ -f /c/Windows/Fonts/verdana.ttf ]; then
    mkdir -p "$FONT_DIR" && cp -f /c/Windows/Fonts/verdana.ttf "$FONT_DIR/hud_text.ttf" && echo "  HUD font: Verdana (local)"
    [ -f /c/Windows/Fonts/verdanab.ttf ] && cp -f /c/Windows/Fonts/verdanab.ttf "$FONT_DIR/hud_bold.ttf"
  fi
fi

# --- apk ----------------------------------------------------------------
if has apk; then
  step apk
  # Remove the old APK first so a failed or no-op Gradle run can never leave a stale one that looks fresh.
  rm -f "$APK" || die "cannot remove old APK $APK"
  ( cd "$REPO/android" && ./gradlew --no-daemon --console=plain :app:assembleDebug ) \
    > "$LOGS/android_lr_gradle.log" 2>&1 \
    || { grep -n -E "FAILURE|error|What went wrong" -A3 "$LOGS/android_lr_gradle.log" | head -30 || true; die "gradle failed (log: $LOGS/android_lr_gradle.log)"; }
  [ -f "$APK" ] || die "APK not produced at $APK"
  stale=$(find "$JNI_DIR" -type f -newer "$APK" 2>/dev/null | head -1) || true
  [ -z "$stale" ] || die "APK is older than staged lib $stale (re-run the stage + apk steps)"
  echo "APK: $APK ($(stat -c %s "$APK") bytes)"
fi

# --- install / launch / logs ---------------------------------------------
# Thor rule (owner): wake the device only for an actual test and put it to sleep right after; never
# leave the game running. Every device step below ends with force-stop + KEYCODE_SLEEP, also on failure.
export MSYS_NO_PATHCONV=1   # device paths like /sdcard/... must reach adb unchanged (Git Bash rewrites them otherwise)
ADB=(adb ${LR_ADB_SERIAL:+-s "$LR_ADB_SERIAL"})
RUN_SECS=${LR_RUN_SECS:-120}
THOR_LOGS=${LR_THOR_LOGS:-C:/lr/out/thor}
device_sleep() { "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true; "${ADB[@]}" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true; }
if has install || has launch || has logs; then
  command -v adb >/dev/null || die "adb not on PATH"
  "${ADB[@]}" get-state >/dev/null 2>&1 || die "no device (plug in the Thor, accept the USB debugging prompt)"
  # Thor rule: always force-stop + sleep, but keep the failing exit status.
  on_exit() { local rc=$?; trap - EXIT; device_sleep; exit "$rc"; }
  trap on_exit EXIT
fi
if has install; then
  step install
  [ -f "$APK" ] || die "no APK at $APK; run the apk step"
  "${ADB[@]}" install -r "$APK" || die "adb install failed"
fi
if has launch; then
  step launch
  mkdir -p "$THOR_LOGS"
  stamp=$(date +%Y%m%d-%H%M%S)
  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP || die "adb wakeup failed"
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  am_out=$("${ADB[@]}" shell am start -n "$PKG/$ACTIVITY" 2>&1) || { echo "$am_out"; die "adb am start failed"; }
  echo "$am_out"
  # `am start` exits 0 even when the activity is missing; its output then has an "Error" line.
  ! grep -q -E "^Error" <<<"$am_out" || die "am start reported an error for $PKG/$ACTIVITY"
  echo "running for ${RUN_SECS}s (LR_RUN_SECS) ..."
  # LR_SCREENSHOTS="20 45 80": capture the screen at those seconds into $THOR_LOGS/shot_<stamp>_<sec>.png
  # LR_KEYS="25:pad:A 35:pad:START" (pad: = sendevent on LR_PAD_DEV, default /dev/input/event9): inject key events at those seconds (input test).
  elapsed=0
  events=$( { for t in ${LR_SCREENSHOTS:-}; do echo "$t shot"; done; for k in ${LR_KEYS:-}; do echo "${k%%:*} key ${k#*:}"; done; } | sort -n )
  while read -r t kind arg; do
    [ -n "$t" ] || continue
    if [ "$t" -gt "$elapsed" ]; then sleep $((t - elapsed)); elapsed=$t; fi
    if [ "$kind" = shot ]; then
      "${ADB[@]}" exec-out screencap -p > "$THOR_LOGS/shot_${stamp}_${t}s.png" 2>/dev/null </dev/null || die "screencap failed at ${t}s"
      echo "screenshot at ${t}s: $THOR_LOGS/shot_${stamp}_${t}s.png"
    else
      # arg = KEYCODE, source:KEYCODE (input tool sources), or pad:BTN (kernel press on the built-in pad via sendevent:
      # game A=305, B=304, X=308, Y=307 (the Thor's Nintendo button mode, swapped back by the SDK), BTN_SOUTH 304, BTN_EAST 305, BTN_START 315, BTN_SELECT 314, or a number)
      src=""; key="$arg"; case "$arg" in *:*) src="${arg%%:*}"; key="${arg#*:}";; esac
      if [ "$src" = pad ]; then
        hold="0.12"; case "$key" in *:*) hold="0.$(printf '%03d' "${key#*:}")"; key="${key%%:*}";; esac   # pad:A:600 = hold 600 ms
        case "$key" in A) code=305;; B) code=304;; X) code=308;; Y) code=307;; BTN_SOUTH) code=304;; BTN_EAST) code=305;; BTN_NORTH) code=307;; BTN_WEST) code=308;; BTN_START|START) code=315;; BTN_SELECT|SELECT) code=314;; UP) code=544;; DOWN) code=545;; LEFT) code=546;; RIGHT) code=547;; *) code=$key;; esac
        dev=${LR_PAD_DEV:-/dev/input/event9}
        "${ADB[@]}" shell "sendevent $dev 1 $code 1; sendevent $dev 0 0 0; sleep $hold; sendevent $dev 1 $code 0; sendevent $dev 0 0 0" </dev/null || die "pad sendevent failed at ${t}s"
        echo "pad press $key ($code, ${hold}s) at ${t}s"
      else
        "${ADB[@]}" shell input $src keyevent "$key" </dev/null || die "input keyevent failed at ${t}s"   # </dev/null: adb must not eat the event list
        echo "key $src $key at ${t}s"
      fi
    fi
  done <<< "$events"
  if [ "$elapsed" -lt "$RUN_SECS" ]; then sleep $((RUN_SECS - elapsed)); fi
  "${ADB[@]}" logcat -d > "$THOR_LOGS/logcat_$stamp.txt" 2>/dev/null || die "logcat dump failed"
  "${ADB[@]}" pull "/sdcard/Android/data/$PKG/files/logs" "$THOR_LOGS/logs_$stamp" >/dev/null 2>&1 || true
  device_sleep
  echo "Thor logs: $THOR_LOGS/logcat_$stamp.txt and $THOR_LOGS/logs_$stamp/ (device force-stopped and asleep)"
  echo "Triage:  grep -E -i 'lr |FATAL|fault|violation|E/|F/' $THOR_LOGS/logcat_$stamp.txt | tail -40"
fi
if has logs; then
  step logs
  mkdir -p "$THOR_LOGS"
  stamp=$(date +%Y%m%d-%H%M%S)
  "${ADB[@]}" logcat -d > "$THOR_LOGS/logcat_$stamp.txt" 2>/dev/null || die "logcat dump failed"
  "${ADB[@]}" pull "/sdcard/Android/data/$PKG/files/logs" "$THOR_LOGS/logs_$stamp" >/dev/null 2>&1 || echo "warning: no files/logs to pull (app never wrote any?)" >&2
  device_sleep
  echo "Thor logs: $THOR_LOGS/logcat_$stamp.txt and $THOR_LOGS/logs_$stamp/ (device asleep)"
fi
