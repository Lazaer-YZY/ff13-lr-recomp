#!/bin/bash
# Stage 2 of the toolchain setup (after tools/fetch_toolchain.sh): no admin needed.
#  1. xwin splat: MSVC CRT/STL + Windows SDK headers/libs into C:\dev\xwin (VS-like layout).
#  2. Android SDK packages via sdkmanager into C:\dev\android-sdk (pinned to Lost Odyssey Recomp's versions).
# Usage: bash /c/lr/tools/setup_sdks.sh [xwin|android|all]
set -u
what=${1:-all}
LOG=/c/dev/logs/setup_sdks.log
mkdir -p /c/dev/logs
exec > >(tee -a "$LOG") 2>&1
echo "=== setup_sdks $what start $(date) ==="

if [ "$what" = xwin ] || [ "$what" = all ]; then
  if [ -d /c/dev/xwin/VC/Tools/MSVC ]; then
    echo "xwin: already splatted"
  else
    mkdir -p /c/dev/dl/xwin-cache
    /c/dev/xwin-bin/xwin.exe --accept-license --arch x86_64 --variant desktop \
      --cache-dir 'C:\dev\dl\xwin-cache' \
      splat --output 'C:\dev\xwin' --use-winsysroot-style --preserve-ms-arch-notation \
      --disable-symlinks --include-debug-libs \
      && echo "xwin: ok" || echo "xwin: FAILED"
    ls /c/dev/xwin/VC/Tools/MSVC "/c/dev/xwin/Windows Kits/10/Include" 2>/dev/null
  fi
fi

if [ "$what" = android ] || [ "$what" = all ]; then
  export JAVA_HOME='C:\dev\jdk17'
  export PATH="/c/dev/jdk17/bin:$PATH"
  SDKM=/c/dev/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat
  ROOT='C:\dev\android-sdk'
  yes | "$SDKM" --sdk_root="$ROOT" --licenses >/dev/null 2>&1 && echo "android: licenses accepted"
  "$SDKM" --sdk_root="$ROOT" --install \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1" \
    && echo "android: packages ok" || echo "android: FAILED"
  "$SDKM" --sdk_root="$ROOT" --list_installed 2>/dev/null | sed -n '1,20p'
fi
echo "=== setup_sdks $what end $(date) ==="
