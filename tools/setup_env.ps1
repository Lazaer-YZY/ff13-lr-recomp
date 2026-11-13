# PowerShell environment for the LR recomp toolchain (portable tools under C:\dev, no admin).
# Usage: . C:\lr\tools\setup_env.ps1
$Dev = 'C:\dev'
$Repo = Split-Path -Parent $PSScriptRoot  # the clone this script lives in

# Recreate the space-free junction if it is missing.
if (-not (Test-Path 'C:\lr')) {
  New-Item -ItemType Junction -Path 'C:\lr' -Target $Repo | Out-Null
}
if (-not (Test-Path 'C:\lrgame')) {
  New-Item -ItemType Junction -Path 'C:\lrgame' -Target (Join-Path $Repo 'Input - Data\extracted') | Out-Null
}

$env:PATH = "$Dev\llvm\bin;$Dev\cmake\bin;$Dev\ninja;$Dev\jdk17\bin;$Dev\android-sdk\platform-tools;$Dev\android-sdk\cmdline-tools\latest\bin;$Dev\rexglue\bin;" + $env:PATH
$env:JAVA_HOME = "$Dev\jdk17"
$env:ANDROID_HOME = "$Dev\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_VERSION = '28.2.13676358'
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\$env:ANDROID_NDK_VERSION"
$env:CMAKE_POLICY_VERSION_MINIMUM = '3.5'
$env:CMAKE_PREFIX_PATH = 'C:/dev/rexglue'
$env:RC = 'llvm-rc'
$env:CC = 'clang'
$env:CXX = 'clang++'

# MSVC CRT/STL + Windows SDK from `xwin splat --use-winsysroot-style`.
$xwin = "$Dev\xwin"
if (Test-Path "$xwin\VC\Tools\MSVC") {
  $msvcVer = (Get-ChildItem "$xwin\VC\Tools\MSVC" | Sort-Object { [version]$_.Name } | Select-Object -Last 1).Name
  $sdkVer  = (Get-ChildItem "$xwin\Windows Kits\10\Include" | Sort-Object { [version]$_.Name } | Select-Object -Last 1).Name
  $m = "$xwin\VC\Tools\MSVC\$msvcVer"
  $k = "$xwin\Windows Kits\10"
  $env:INCLUDE = "$m\include;$k\Include\$sdkVer\ucrt;$k\Include\$sdkVer\um;$k\Include\$sdkVer\shared;$k\Include\$sdkVer\winrt;$k\Include\$sdkVer\cppwinrt"
  $env:LIB = "$m\lib\x64;$k\Lib\$sdkVer\ucrt\x64;$k\Lib\$sdkVer\um\x64"
  $env:WINSYSROOT = $xwin
}
Write-Host "LR env ready: clang=$((Get-Command clang -ErrorAction SilentlyContinue).Source) cmake=$((Get-Command cmake -ErrorAction SilentlyContinue).Source)"
