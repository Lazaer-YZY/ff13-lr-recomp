#!/bin/bash
# Re-checks-out git symlink entries as real symlinks in a repo and all its submodules (Windows needs core.symlinks=true).
# Usage: bash tools/fix_symlinks.sh /c/lr/sdk/rexglue-sdk
set -u
root=${1:?repo path}
fix_one() {
  local repo=$1
  git -C "$repo" config core.symlinks true
  local links; links=$(git -C "$repo" ls-files -s | awk '/^120000/{print $4}')
  [ -z "$links" ] && return 0
  echo "$repo: $(echo "$links" | wc -l) symlink(s)"
  (cd "$repo" && echo "$links" | tr '\n' '\0' | xargs -0 rm -f && echo "$links" | tr '\n' '\0' | xargs -0 git checkout --quiet -- )
}
fix_one "$root"
for sm in $(git -C "$root" submodule --quiet foreach --recursive 'echo $toplevel/$sm_path'); do fix_one "$sm"; done
