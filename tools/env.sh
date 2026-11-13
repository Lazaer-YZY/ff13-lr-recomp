# Git Bash environment for the LR recomp toolchain (portable tools under C:\dev, no admin).
# Usage: source /c/lr/tools/env.sh
DEV=/c/dev
DEVW='C:\dev'

export PATH="$DEV/llvm/bin:$DEV/cmake/bin:$DEV/ninja:$DEV/jdk17/bin:$DEV/android-sdk/platform-tools:$DEV/android-sdk/cmdline-tools/latest/bin:$DEV/rexglue/bin:$PATH"
export JAVA_HOME="$DEVW\\jdk17"
export ANDROID_HOME="$DEVW\\android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_VERSION=28.2.13676358
export ANDROID_NDK_HOME="$ANDROID_HOME\\ndk\\$ANDROID_NDK_VERSION"
export CMAKE_POLICY_VERSION_MINIMUM=3.5
export RC=llvm-rc
export CC=clang
export CXX=clang++

# MSVC CRT/STL + Windows SDK from `xwin splat --use-winsysroot-style` (layout mirrors a VS install).
XWIN=$DEV/xwin
if [ -d "$XWIN/VC/Tools/MSVC" ]; then
  MSVC_VER=$(ls "$XWIN/VC/Tools/MSVC" | sort -V | tail -1)
  SDK_VER=$(ls "$XWIN/Windows Kits/10/Include" | sort -V | tail -1)
  M="$DEVW\\xwin\\VC\\Tools\\MSVC\\$MSVC_VER"
  K="$DEVW\\xwin\\Windows Kits\\10"
  export INCLUDE="$M\\include;$K\\Include\\$SDK_VER\\ucrt;$K\\Include\\$SDK_VER\\um;$K\\Include\\$SDK_VER\\shared;$K\\Include\\$SDK_VER\\winrt;$K\\Include\\$SDK_VER\\cppwinrt"
  export LIB="$M\\lib\\x64;$K\\Lib\\$SDK_VER\\ucrt\\x64;$K\\Lib\\$SDK_VER\\um\\x64"
  export WINSYSROOT="$DEVW\\xwin"
fi
# Prevent MSYS from rewriting Windows-style env values when passing them to native tools.
export MSYS2_ENV_CONV_EXCL="INCLUDE;LIB;JAVA_HOME;ANDROID_HOME;ANDROID_SDK_ROOT;ANDROID_NDK_HOME;WINSYSROOT"

# Installed host SDK (find_package(rexglue) from game projects)
export CMAKE_PREFIX_PATH="C:/dev/rexglue${CMAKE_PREFIX_PATH:+;$CMAKE_PREFIX_PATH}"
export MSYS2_ENV_CONV_EXCL="$MSYS2_ENV_CONV_EXCL;CMAKE_PREFIX_PATH"
