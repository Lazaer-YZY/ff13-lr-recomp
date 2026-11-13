# Re-clones the read-only reference repos under refs/ (git-ignored). Shallow clones; submodules only for Lost Odyssey.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$refs = Join-Path $root "refs"
New-Item -ItemType Directory -Force $refs | Out-Null
$repos = @(
  "naubasurq/Lighthing-Recomp",
  "freefrank/LostOdysseyRecomp",
  "hedge-dev/XenonRecomp",
  "hedge-dev/XenosRecomp",
  "hedge-dev/UnleashedRecomp",
  "rexglue/rexglue-sdk"
)
foreach ($r in $repos) {
  $name = Split-Path -Leaf $r
  $dst = Join-Path $refs $name
  if (Test-Path $dst) { Write-Host "have $name"; continue }
  git clone --depth 1 "https://github.com/$r.git" $dst
}
git -C (Join-Path $refs "LostOdysseyRecomp") submodule update --init --recursive --depth 1
