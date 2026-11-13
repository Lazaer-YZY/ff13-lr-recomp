#!/bin/bash
# Downloads + extracts the pinned portable toolchain into C:\dev (no admin needed).
set -u
cd /c/dev/dl
LOG=/c/dev/logs/fetch_toolchain.log
# NOTE: no `exec > >(tee ...)` here: a bare `wait` would also wait on the tee process and deadlock.
{
echo "=== fetch start $(date) ==="
pids=()
dl() { # name url
  if [ -s "$1" ]; then echo "have $1"; return 0; fi
  curl -L --retry 4 --retry-delay 5 -sS -o "$1.part" "$2" && mv "$1.part" "$1" && echo "done $1 ($(du -h "$1" | cut -f1))" || echo "FAILED $1"
}
dl ninja-win.zip  https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-win.zip & pids+=($!)
dl cmake-4.4.5-windows-x86_64.zip https://github.com/Kitware/CMake/releases/download/v4.4.5/cmake-4.4.5-windows-x86_64.zip & pids+=($!)
dl xwin-0.10.0-x86_64-pc-windows-msvc.tar.gz https://github.com/Jake-Shadle/xwin/releases/download/0.10.0/xwin-0.10.0-x86_64-pc-windows-msvc.tar.gz & pids+=($!)
dl xwin.sha256 https://github.com/Jake-Shadle/xwin/releases/download/0.10.0/xwin-0.10.0-x86_64-pc-windows-msvc.tar.gz.sha256 & pids+=($!)
dl OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip" & pids+=($!)
dl commandlinetools-win-15859902_latest.zip https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip & pids+=($!)
dl clang+llvm-22.1.8-x86_64-pc-windows-msvc.tar.xz "https://github.com/llvm/llvm-project/releases/download/llvmorg-22.1.8/clang%2Bllvm-22.1.8-x86_64-pc-windows-msvc.tar.xz" & pids+=($!)
wait "${pids[@]}"
echo "=== downloads finished $(date) ==="; ls -la
echo "--- xwin sha256 check ---"; (sha256sum -c <(echo "$(cut -d' ' -f1 xwin.sha256)  xwin-0.10.0-x86_64-pc-windows-msvc.tar.gz") || echo "XWIN SHA MISMATCH")
echo "=== extracting ==="
mkdir -p /c/dev/ninja && unzip -oq ninja-win.zip -d /c/dev/ninja && echo "ninja ok"
unzip -oq cmake-4.4.5-windows-x86_64.zip -d /c/dev && mv -T /c/dev/cmake-4.4.5-windows-x86_64 /c/dev/cmake 2>/dev/null; ls /c/dev/cmake/bin/cmake.exe && echo "cmake ok"
mkdir -p /c/dev/xwin-bin && tar -xzf xwin-0.10.0-x86_64-pc-windows-msvc.tar.gz -C /c/dev/xwin-bin --strip-components=1 && ls /c/dev/xwin-bin/xwin.exe && echo "xwin ok"
unzip -oq OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip -d /c/dev && mv -T /c/dev/jdk-17.0.21+9 /c/dev/jdk17 2>/dev/null; ls /c/dev/jdk17/bin/java.exe && echo "jdk ok"
mkdir -p /c/dev/android-sdk/cmdline-tools && unzip -oq commandlinetools-win-15859902_latest.zip -d /c/dev/android-sdk/cmdline-tools && mv -T /c/dev/android-sdk/cmdline-tools/cmdline-tools /c/dev/android-sdk/cmdline-tools/latest 2>/dev/null; ls /c/dev/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat && echo "cmdline-tools ok"
mkdir -p /c/dev/llvm && tar -xJf clang+llvm-22.1.8-x86_64-pc-windows-msvc.tar.xz -C /c/dev/llvm --strip-components=1 && ls /c/dev/llvm/bin/clang.exe && echo "llvm ok"
echo "=== fetch end $(date) ==="
} 2>&1 | tee -a "$LOG"
