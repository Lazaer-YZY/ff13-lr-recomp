#!/bin/bash
# One-command build (and optional install) of the Lightning Returns Android port, from a fresh clone.
#
#   bash tools/quickstart.sh /path/to/Lightning_Returns_USA.iso            # build the APK
#   bash tools/quickstart.sh /path/to/Lightning_Returns_USA.iso --install  # ...then install it and copy the game files
#   bash tools/quickstart.sh --install                                     # install only (APK and game files already here)
#
# Every step is skipped when its result is already there, so the script can be re-run after a failure
# or an interruption. Force a step again by deleting what it made (paths are printed per step).
# Needs: Windows 10/11, Git for Windows (run this from Git Bash), Python 3, Developer Mode on
# (Settings > System > For developers) so Git can create symlinks. No admin rights.
# Logs: C:/dev/logs/*.log (toolchain and SDK builds), C:/lr/out/quickstart_*.log (recompile).
set -euo pipefail
export MSYS_NO_PATHCONV=1

ISO=""; INSTALL=0
for a in "$@"; do
  case "$a" in
    --install) INSTALL=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) ISO="$a" ;;
  esac
done

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
skip() { printf '    already done (%s)\n' "$*"; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# --- 0. The scripts expect the repository at C:\lr (space-free paths for the NDK and Gradle). ---
HERE=$(cd "$(dirname "$0")/.." && pwd -W)
if [ ! -f /c/lr/lr_manifest.toml ]; then
  step "Linking C:\\lr -> $HERE"
  [ -e /c/lr ] && die "C:\\lr exists but is not this repository. Move it away and re-run."
  powershell.exe -NoProfile -Command "New-Item -ItemType Junction -Path 'C:\\lr' -Target '$HERE' | Out-Null" \
    || die "could not create the C:\\lr junction"
fi
cd /c/lr
command -v python >/dev/null || die "Python 3 not found on PATH (install it from python.org, tick 'Add to PATH')."
command -v git >/dev/null || die "git not found (run this from Git Bash)."
mkdir -p /c/dev/dl /c/dev/logs out

# --- 1. Toolchain (portable, into C:\dev; ~6 GB download incl. the Android SDK). ---
step "1/8 Toolchain into C:\\dev (LLVM, CMake, Ninja, xwin, JDK 17, Android SDK + NDK)"
if [ -x /c/dev/llvm/bin/clang.exe ] && [ -x /c/dev/cmake/bin/cmake.exe ] && [ -x /c/dev/jdk17/bin/java.exe ] \
   && [ -f /c/dev/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat ]; then skip "C:\\dev\\llvm, cmake, jdk17, android-sdk"
else bash tools/fetch_toolchain.sh; fi
if [ -d /c/dev/xwin/VC/Tools/MSVC ] && [ -d /c/dev/android-sdk/ndk/28.2.13676358 ]; then skip "C:\\dev\\xwin, NDK 28.2"
else bash tools/setup_sdks.sh all; fi
git submodule update --init thirdparty/libadrenotools
# shellcheck disable=SC1091
set +u; source /c/lr/tools/env.sh >/dev/null || true; set -u

# --- 2. ReXGlue SDK v0.10.1 + this project's patches. ---
step "2/8 ReXGlue SDK v0.10.1 + patches/rexglue-sdk (into C:\\lr\\sdk\\rexglue-sdk)"
if [ -d sdk/rexglue-sdk/.git ]; then skip "sdk/rexglue-sdk; delete it to start over"
else
  git -c core.symlinks=true clone --recurse-submodules https://github.com/rexglue/rexglue-sdk.git sdk/rexglue-sdk
  git -C sdk/rexglue-sdk checkout -b lr-android 546a082
  git -C sdk/rexglue-sdk -c user.name=quickstart -c user.email=quickstart@localhost am --quiet /c/lr/patches/rexglue-sdk/*.patch
  bash tools/fix_symlinks.sh /c/lr/sdk/rexglue-sdk || true
fi

# --- 3. Recompiler (host CLI) into C:\dev\rexglue. ---
step "3/8 Recompiler for the PC (C:\\dev\\rexglue)"
if [ -x /c/dev/rexglue/bin/rexglue.exe ]; then skip "C:\\dev\\rexglue\\bin\\rexglue.exe"
else bash tools/build_sdk_host.sh all; fi

# --- 4. Android runtime into C:\dev\rexglue-android. ---
step "4/8 Runtime for Android (C:\\dev\\rexglue-android)"
if [ -f /c/dev/rexglue-android/lib/librexruntime.so ]; then skip "C:\\dev\\rexglue-android\\lib\\librexruntime.so"
else
  (cd sdk/rexglue-sdk && scripts/configure_android.sh \
    && cmake --build out/build/android-arm64 --target install --parallel "${JOBS:-8}") > /c/dev/logs/android_sdk_build.log 2>&1 \
    || die "Android runtime build failed, see C:/dev/logs/android_sdk_build.log"
fi

# --- 5. Game files from your ISO. ---
XEX="Input - Data/extracted/default.xex"
step "5/8 Game files (Input - Data\\extracted)"
if [ -f "$XEX" ]; then skip "$XEX"
else
  [ -n "$ISO" ] || die "pass the path to your Lightning Returns (USA) ISO: bash tools/quickstart.sh /path/to/game.iso"
  [ -f "$ISO" ] || die "ISO not found: $ISO"
  python -I tools/xdvdfs.py "$ISO" extract "Input - Data/extracted"
fi
want=52d3c11378fa041e086dabfd79994e7eaa63e47f28e5c98878c71573823d5afb
have=$(sha256sum "$XEX" | cut -d' ' -f1)
[ "$have" = "$want" ] || printf '    \033[1;33mWARNING: default.xex is not the tested USA disc release (sha256 %s).\n    Other releases need their own manifest; the build will likely fail or crash.\033[0m\n' "$have"

# --- 6. Recompile the game's PowerPC code to C++ (~5 min). ---
step "6/8 Recompiling default.xex to C++ (generated\\, ~5 min)"
if [ -f generated/rexglue.cmake ] && ls generated/default/lr_recomp.*.cpp >/dev/null 2>&1; then skip "generated\\; delete it to recompile"
else
  rexglue codegen lr_manifest.toml --ignore-stamp > out/quickstart_codegen.log 2>&1 \
    || die "codegen failed, see C:/lr/out/quickstart_codegen.log"
  python -I tools/patch_generated.py
fi

[ "${QS_STOP_BEFORE_BUILD:-}" = 1 ] && exit 0  # testing aid

# --- 7/8. Build the APK (the first build compiles the game for one to several hours). ---
step "7/8 Building the APK (first build: 1 to several hours)"
APK=android/app/build/outputs/apk/debug/app-debug.apk
[ -f android/local.properties ] || echo "sdk.dir=C:/dev/android-sdk" > android/local.properties
bash tools/build_android.sh configure build stage apk
[ -f "$APK" ] || die "no APK at $APK; see C:/dev/logs/android_lr_*.log"
step "8/8 Done: C:\\lr\\$APK"

# --- Optional: install on the device and copy the game files (7 GB). ---
if [ "$INSTALL" = 1 ]; then
  command -v adb >/dev/null || die "adb not found (it is in C:\\dev\\android-sdk\\platform-tools after step 1)"
  n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
  [ "$n" -ge 1 ] || die "no device: connect it over USB, enable USB debugging and accept the prompt on the screen"
  [ "$n" -eq 1 ] || [ -n "${LR_ADB_SERIAL:-}" ] || die "several devices connected: set LR_ADB_SERIAL=<serial> (see 'adb devices')"
  step "Installing the APK"
  bash tools/build_android.sh install
  step "Copying the game files to the device (7 GB, takes a while; re-run to resume)"
  bash tools/push_game_data.sh "C:/lr/Input - Data/extracted"
  printf '\nAll set. Start "Lightning Returns" from the app drawer. The first start compiles shaders for a minute.\n'
fi
