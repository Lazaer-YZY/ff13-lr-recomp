#!/usr/bin/env bash
# Export a clean snapshot of this repo to the public GitHub repository.
#
#   bash tools/export_public.sh [REF] [--push]
#
# REF (default main) is exported with `git archive` (tracked files only, so no game data,
# generated code or refs), minus the paths in tools/public_exclude.txt. The snapshot is
# committed on top of the public repo's own history (never this repo's history) with the
# neutral identity, after tools/check_personal.sh passes on it. Without --push it stops
# after the commit so the result can be inspected in out/public/repo.
#
# Auth: the public account's token from gh (PUBLIC_USER), passed per command; gh's active
# account stays the private one (see CLAUDE.md, Known traps).
set -euo pipefail

SRC=/c/lr
PUBLIC_USER=${PUBLIC_USER:-Lazaer-YZY}
PUBLIC_REPO=${PUBLIC_REPO:-$PUBLIC_USER/ff13-lr-recomp}
OUT=$SRC/out/public
REF=main
PUSH=0
for a in "$@"; do
  case "$a" in
    --push) PUSH=1 ;;
    -*) echo "unknown option $a" >&2; exit 2 ;;
    *) REF=$a ;;
  esac
done

TOKEN=$(gh auth token --user "$PUBLIC_USER") || { echo "gh has no login for $PUBLIC_USER" >&2; exit 1; }
URL=https://github.com/$PUBLIC_REPO.git
pgit() { GH_TOKEN=$TOKEN git -c credential.helper= -c 'credential.helper=!gh auth git-credential' \
           -c user.name="LR Recomp" -c user.email="noreply@lr-recomp.invalid" -C "$OUT/repo" "$@"; }

rev=$(git -C "$SRC" rev-parse --short "$REF")
echo "exporting $REF ($rev) to $PUBLIC_REPO"

# 1. Working copy of the public repo (its own history; empty on the first export).
mkdir -p "$OUT/repo"; find "$OUT/repo" -mindepth 1 -maxdepth 1 -exec rm -rf {} +   # contents only: the folder may be open elsewhere
git -C "$OUT/repo" init -q -b main
pgit remote add origin "$URL"
if pgit ls-remote --exit-code origin main >/dev/null 2>&1; then
  pgit fetch -q --depth 1 origin main
  pgit reset -q --soft FETCH_HEAD
fi

# 2. Replace the tree with the snapshot.
find "$OUT/repo" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$SRC" archive "$REF" | tar -x -C "$OUT/repo"
if [ -f "$SRC/tools/public_exclude.txt" ]; then
  while IFS= read -r p; do
    p=${p%$'\r'}; [ -z "$p" ] || [ "${p:0:1}" = "#" ] && continue
    rm -rf "$OUT/repo/$p"
  done < "$SRC/tools/public_exclude.txt"
fi

# Public defaults for a fresh install (60 fps, debug logging off): see android/app/build.gradle.
sed -i 's/^lrPublicDefaults=false$/lrPublicDefaults=true/' "$OUT/repo/android/gradle.properties"
grep -qx 'lrPublicDefaults=true' "$OUT/repo/android/gradle.properties" || { echo "lrPublicDefaults not set" >&2; exit 1; }

# 3. Personal-data check on exactly what will be published.
bash "$SRC/tools/check_personal.sh" "$OUT/repo"

# 4. Commit (nothing to do if the tree is unchanged).
pgit add -A
if pgit diff --cached --quiet; then
  echo "no changes since the last public export"
else
  pgit commit -q -m "Release $(date +%Y-%m-%d)"
  pgit log --oneline -1
  pgit diff --stat HEAD~1 2>/dev/null | tail -1 || true
fi

if [ "$PUSH" = 1 ]; then
  pgit push origin main
  echo "pushed to $URL"
else
  echo "not pushed (inspect $OUT/repo, then rerun with --push)"
fi
