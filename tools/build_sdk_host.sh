#!/bin/bash
# Builds and installs the ReXGlue SDK for the Windows host (codegen CLI + Windows runtime for the debug harness).
# Usage: bash /c/lr/tools/build_sdk_host.sh [configure|build|install|all]   (default all)
# Output: build tree C:\lr\sdk\rexglue-sdk\out\build\win-amd64, install prefix C:\dev\rexglue
set -u
source /c/lr/tools/env.sh
SDK=/c/lr/sdk/rexglue-sdk
BUILD=$SDK/out/build/win-amd64
PREFIX='C:/dev/rexglue'
LOG=/c/dev/logs/build_sdk_host.log
step=${1:-all}
mkdir -p /c/dev/logs
{
echo "=== build_sdk_host $step start $(date) ==="
cd "$SDK"
if [ "$step" = configure ] || [ "$step" = all ]; then
  cmake --preset win-amd64 \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_EXE_LINKER_FLAGS=-fuse-ld=lld \
    -DCMAKE_SHARED_LINKER_FLAGS=-fuse-ld=lld \
    -DCMAKE_MODULE_LINKER_FLAGS=-fuse-ld=lld \
    -DCMAKE_RC_COMPILER=llvm-rc \
    -DREXGLUE_USE_VULKAN=ON \
    -DREXGLUE_ENABLE_TRACY=OFF \
    || { echo "CONFIGURE FAILED"; exit 1; }
fi
if [ "$step" = build ] || [ "$step" = all ]; then
  cmake --build "$BUILD" --config Release --parallel 8 || { echo "BUILD FAILED"; exit 1; }
fi
if [ "$step" = install ] || [ "$step" = all ]; then
  cmake --install "$BUILD" --config Release || { echo "INSTALL FAILED"; exit 1; }
  ls "$PREFIX/bin" | head
fi
echo "=== build_sdk_host $step end $(date) ==="
} 2>&1 | tee -a "$LOG"
