#!/bin/bash
# Builds tools/native/xxh3.dll (Windows host) for tools/lr_shader_scan.py.
set -e
source /c/lr/tools/env.sh >/dev/null 2>&1
cd /c/lr/tools/native
clang -O2 -shared -fuse-ld=lld -I/c/lr/sdk/rexglue-sdk/thirdparty/xxHash xxh3_shim.c -o xxh3.dll
echo "built $(pwd)/xxh3.dll"
