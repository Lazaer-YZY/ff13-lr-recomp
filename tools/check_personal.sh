#!/bin/bash
# Fails when a tracked file (or the given export directory) contains a personal identifier.
# Patterns are one extended regex per line in out/personal_patterns.txt (git-ignored, so the
# patterns themselves never get published). Third-party files that legitimately contain a
# matching name are skipped (stb_image.h credits).
# Usage: bash tools/check_personal.sh [DIR]   (default: the tracked files of this repo)
set -u
PAT=/c/lr/out/personal_patterns.txt
[ -s "$PAT" ] || { echo "no patterns in $PAT"; exit 2; }
if [ -n "${1:-}" ]; then
  hits=$(grep -rnIE -f "$PAT" --exclude=stb_image.h --exclude-dir=.git "$1" | grep -v 'Tom Clay\|Tom Seddon')
else
  hits=$(git -C /c/lr grep -nIE -f "$PAT" -- . ':!src/stb_image.h' | grep -v 'Tom Clay\|Tom Seddon')
fi
if [ -n "$hits" ]; then echo "$hits" | cut -c1-200; echo "personal identifiers found: $(echo "$hits" | wc -l) lines"; exit 1; fi
echo "clean: no personal identifiers"
