#!/bin/bash
# Host regression checks: everything that runs on the PC without the Thor, in a few minutes.
# Run before every commit that touches src/, android/ or tools/ (CLAUDE.md, "Regression tests").
#
#   bash tools/regress_host.sh [--build] [--quick]
#     --quick  skip the Gradle unit tests (the slowest step, ~1-3 min)
#     --build  also build the native libraries and the debug APK (build_android.sh build stage apk;
#              incremental, but a change in generated/ or the SDK means a long build)
#
# Checks: lr_manifest.toml parses; tools/*.py parse; tools/*.sh parse (bash -n); the cheat bits
# agree between Java and C++; host C++ unit tests (tests/host/test_*.cpp, built with the host
# clang); the Android unit tests (Gradle :app:testDebugUnitTest); no personal identifiers in
# tracked files. Prints a PASS/FAIL table; exit 1 when anything failed.
# Logs: out/regress/host/<check>.log
set -u
REPO=/c/lr
OUT=$REPO/out/regress/host
mkdir -p "$OUT"
source "$REPO/tools/env.sh" >/dev/null 2>&1
QUICK=0 BUILD=0
for a in "$@"; do
  case "$a" in
    --quick) QUICK=1 ;;
    --build) BUILD=1 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

results=()
failed=0
run() {  # run NAME CMD...: logs to $OUT/NAME.log, records PASS/FAIL with the time taken
  local name=$1; shift
  local t0=$SECONDS
  if ( cd "$REPO" && "$@" ) > "$OUT/$name.log" 2>&1; then
    results+=("PASS  $(printf '%-22s' "$name") $((SECONDS - t0)) s")
  else
    results+=("FAIL  $(printf '%-22s' "$name") $((SECONDS - t0)) s  (out/regress/host/$name.log)")
    failed=1
    tail -n 15 "$OUT/$name.log" | sed "s/^/  [$name] /"
  fi
}

check_manifest() {
  python -I -c "import tomllib; d = tomllib.load(open('C:/lr/lr_manifest.toml', 'rb')); assert 'project' in d, 'no [project]'; print('manifest ok:', len(d.get('entrypoint', {}).get('functions', {})), 'entrypoint functions')"
}

check_python() {
  python -I -c "
import ast, glob, sys
bad = 0
files = sorted(glob.glob('C:/lr/tools/*.py') + glob.glob('C:/lr/tests/host/*.py'))
for f in files:
    try:
        ast.parse(open(f, encoding='utf-8').read(), f)
    except SyntaxError as e:
        print('syntax error:', f, e); bad += 1
print(len(files), 'python files,', bad, 'with errors')
sys.exit(1 if bad else 0)"
}

check_shell() {
  local bad=0 n=0
  for f in tools/*.sh tools/android/*.sh; do
    [ -f "$f" ] || continue
    n=$((n + 1))
    bash -n "$f" || { echo "parse error: $f"; bad=$((bad + 1)); }
  done
  echo "$n shell scripts, $bad with errors"
  [ "$bad" = 0 ]
}

host_cpp_tests() {
  local bin=$OUT/bin bad=0 sdk_inc=()
  mkdir -p "$bin"
  # Tests of SDK headers (test_letterbox) build against the SDK checkout when there is one.
  [ -d sdk/rexglue-sdk/include ] && sdk_inc=(-Isdk/rexglue-sdk/include)
  for t in tests/host/test_*.cpp; do
    local name exe
    name=$(basename "$t" .cpp)
    exe="$bin/$name.exe"
    clang++ -std=c++20 -O1 -Wall -Werror -fuse-ld=lld -Isrc "${sdk_inc[@]}" "$t" -o "$exe" || { echo "build failed: $t"; bad=1; continue; }
    "$exe" || bad=1
  done
  [ "$bad" = 0 ]
}

gradle_tests() {
  cd "$REPO/android" && ./gradlew --no-daemon --console=plain :app:testDebugUnitTest
}

build_apk() {
  bash "$REPO/tools/build_android.sh" build stage apk
}

run manifest_toml check_manifest
run python_parse check_python
run shell_parse check_shell
run cheat_bits python -I tests/host/check_cheat_bits.py C:/lr
run host_cpp_tests host_cpp_tests
[ "$QUICK" = 1 ] || run android_unit_tests gradle_tests
run personal_identifiers bash tools/check_personal.sh
[ "$BUILD" = 1 ] && run native_and_apk build_apk

echo
echo "host regression checks ($(date '+%Y-%m-%d %H:%M'))"
printf '%s\n' "${results[@]}"
[ "$failed" = 0 ] && echo "result: PASS" || echo "result: FAIL"
exit "$failed"
