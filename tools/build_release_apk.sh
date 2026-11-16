#!/usr/bin/env bash
# Builds the public APK attached to GitHub releases.
#
#   bash tools/build_release_apk.sh VERSION [--skip-native] [--test] [--pull-seed]
#
# VERSION is x.y.z (versionName; versionCode major*1000000 + minor*10000 + patch, so releases
# install over each other). The native libraries come from this checkout (tools/build_android.sh
# build stage; --skip-native packages what is already staged). The APK gets the public defaults
# (60 fps, debug logging off), the second screen's area maps and HUD pieces, and no Verdana
# (lrRedistributable: its licence forbids passing it on; the app looks for one on the device,
# then uses the bundled DejaVu Sans).
# --test builds com.lrrecomp.android.test instead: it installs beside the normal app with its own
# empty data folder, to try a first start (GPU driver page, ISO import) on a device.
#
# Output: out/release/lr-recomp-android-arm64.apk (+ .sha256), or ...-test.apk.
# Signed with this PC's Android debug keystore (DECISIONS 2026-11-16): keep a backup of it, since
# an update installs over the previous release only when both carry the same signature.
#
# It also carries a shader cache seed (out/shader_seed, see below), so a fresh install precompiles
# every shader at the title instead of drawing white menus and stuttering on first sight.
#
# The APK carries code compiled from the game's executable, the second screen's images and the
# shader cache made from the game (the owner's decision, DECISIONS 2026-11-16), never the game files themselves:
# the script fails on any other asset (fonts included), when the maps or HUD pieces are missing,
# and on the builder's identifiers or user paths in the libraries and the dex.
set -euo pipefail
export MSYS_NO_PATHCONV=1

VERSION=""; NATIVE=1; TEST=0; PULL_SEED=0
for a in "$@"; do
  case "$a" in
    --skip-native) NATIVE=0 ;;
    --pull-seed) PULL_SEED=1 ;;
    --test) TEST=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    -*) echo "unknown option $a" >&2; exit 2 ;;
    *) VERSION=${a#v} ;;
  esac
done
die() { echo "build_release_apk: ERROR: $*" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: build_release_apk.sh VERSION (x.y.z) [--skip-native] [--test]"

REPO=C:/lr
OUT=$REPO/out/release
LOG=C:/dev/logs/android_lr_release.log
PAT=$REPO/out/personal_patterns.txt
mkdir -p "$OUT"
cd /c/lr
# shellcheck disable=SC1091
set +u; source /c/lr/tools/env.sh >/dev/null; set -u
[ -s "$PAT" ] || die "no personal patterns in $PAT"
if [ "$TEST" = 0 ] && ! git diff --quiet HEAD -- . ':!lr_manifest.toml'; then
  die "uncommitted changes: a release APK is built from a commit"
fi

if [ "$NATIVE" = 1 ]; then
  echo "== native libraries (tools/build_android.sh build stage)"
  bash tools/build_android.sh build stage
fi

# Shader cache seed (android ShaderSeed.java): the shaders, render-state combinations and pixel
# shader metadata of a cache collected by playing through the game, kept in out/shader_seed
# (git-ignored: derived from the game). --pull-seed refreshes it from the development install on
# the connected device first (read-only: adb pull, nothing is started or stopped).
SEED_FILES="5351081B.xsh 5351081B.fbo.vk.xpso 5351081B.psmeta"
SEED_SRC=$REPO/out/shader_seed
SEED_DIR=$REPO/android/app/src/main/assets/shader_seed
if [ "$PULL_SEED" = 1 ]; then
  mkdir -p "$SEED_SRC"
  for f in $SEED_FILES; do
    adb ${LR_ADB_SERIAL:+-s "$LR_ADB_SERIAL"} pull \
      "/storage/emulated/0/Android/data/com.lrrecomp.android/files/cache/shaders/shareable/$f" "$SEED_SRC/$f" >/dev/null \
      || die "could not pull $f from the device"
  done
fi
for f in $SEED_FILES; do [ -s "$SEED_SRC/$f" ] || die "shader seed $SEED_SRC/$f missing (run with --pull-seed)"; done
rm -rf "$SEED_DIR"; mkdir -p "$SEED_DIR"
for f in $SEED_FILES; do cp -f "$SEED_SRC/$f" "$SEED_DIR/$f"; done
echo "$(sha256sum "$SEED_DIR/5351081B.xsh" | cut -c1-16) $(stat -c %s "$SEED_DIR/5351081B.xsh")" > "$SEED_DIR/stamp.txt"
echo "== shader seed: $(cat "$SEED_DIR/stamp.txt") (sha prefix, bytes of shaders)"

SUFFIX=""; NAME=lr-recomp-android-arm64
[ "$TEST" = 1 ] && { SUFFIX=.test; NAME=$NAME-test; }
echo "== APK $VERSION${SUFFIX:+ ($SUFFIX)} (log: $LOG)"
( cd android && ./gradlew --no-daemon --console=plain :app:assembleRelease \
    -PlrPublicDefaults=true -PlrRedistributable=true -PlrVersionName="$VERSION" \
    ${SUFFIX:+-PlrAppIdSuffix=$SUFFIX} ) > "$LOG" 2>&1 || { tail -30 "$LOG"; die "gradle failed, see $LOG"; }
SRC_APK=$REPO/android/app/build/outputs/apk/release/app-release.apk
[ -f "$SRC_APK" ] || die "no $SRC_APK"
APK=$OUT/$NAME.apk
cp -f "$SRC_APK" "$APK"

echo "== checks"
BT=$ANDROID_HOME/build-tools/35.0.0
# 1. Assets: the GPU driver, the second screen's maps and HUD pieces, ART's baseline profile.
assets=$(unzip -Z1 "$APK" | grep '^assets/' || true)
extra=$(grep -vE '^assets/(gpu_driver|navimap|hud|fonts_free|shader_seed|dexopt)/' <<<"$assets" || true)
[ -z "$extra" ] || die "assets that must not ship:
$extra"
for need in gpu_driver/stable-turnip-V2.zip navimap/navimaps.json hud/hud.json fonts_free/DejaVuSans.ttf \
            shader_seed/5351081B.xsh shader_seed/5351081B.fbo.vk.xpso shader_seed/5351081B.psmeta shader_seed/stamp.txt; do
  grep -qx "assets/$need" <<<"$assets" || die "assets/$need missing (tools/build_android.sh stage builds it)"
done
# 2. Package, version, not debuggable.
badging=$("$BT/aapt2.exe" dump badging "$APK")
grep -q "name='com.lrrecomp.android$SUFFIX' versionCode='[0-9]*' versionName='$VERSION'" <<<"$badging" \
  || die "unexpected package/version: $(head -1 <<<"$badging")"
! grep -q "application-debuggable" <<<"$badging" || die "APK is debuggable"
# 3. Builder's identifiers or user paths: in the printable strings of the libraries, the dex and
#    the manifest, and in the JSON assets (images and fonts are compressed data, where random bytes
#    match short names). Third-party texts that contain a matching name are allowed.
TMP=$OUT/.check   # a C:/ path: llvm-strings is a Windows program and cannot open /tmp
rm -rf "$TMP"; mkdir -p "$TMP"; trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" -d "$TMP"   # whole APK: this unzip does not match the lib/ entries by pattern
STRINGS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe
: > "$TMP/strings.txt"
for f in "$TMP"/lib/*/*.so "$TMP"/classes*.dex "$TMP"/AndroidManifest.xml; do
  "$STRINGS" -n 6 "$f" >> "$TMP/strings.txt" || die "llvm-strings could not read $f"
done
find "$TMP/assets" -name '*.json' -exec cat {} + >> "$TMP/strings.txt"
[ "$(wc -c < "$TMP/strings.txt")" -gt 1000000 ] || die "too few strings read from the APK"
hits=$(LC_ALL=C grep -E -f "$PAT" "$TMP/strings.txt" \
  | grep -v "Tom Clay\|Tom Seddon\|Tom's lossless Audio" | cut -c1-160 | sort -u || true)
users=$(LC_ALL=C grep -oiE '[A-Z]:[/\\]Users[/\\][^/\\"]+|/home/[a-z][a-z0-9_-]+/' "$TMP/strings.txt" | sort -u | head || true)
[ -z "$hits$users" ] || die "personal identifiers or user paths in the APK:
$hits
$users"
# 4. Signature.
"$BT/apksigner.bat" verify --print-certs "$APK" | grep -m1 "certificate SHA-256 digest" || die "apksigner verify failed"

(cd "$OUT" && sha256sum "$NAME.apk" > "$NAME.apk.sha256")
echo "== $APK ($(du -m "$APK" | cut -f1) MB)"
cat "$OUT/$NAME.apk.sha256"
unzip -l "$APK" | awk '/lib\/arm64-v8a\/|assets\// { printf "   %9d  %s\n", $1, $4 }'
