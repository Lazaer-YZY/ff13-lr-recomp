# Exports our ReXGlue SDK changes (branch lr-android on top of tag v0.10.1) as patch files into patches/rexglue-sdk/.
# Run after every commit in C:\lr\sdk\rexglue-sdk so the main repo stays self-contained.
$ErrorActionPreference = 'Stop'
$sdk = 'C:\lr\sdk\rexglue-sdk'
$base = 'v0.10.1'
$out = 'C:\lr\patches\rexglue-sdk'
if (-not (Test-Path $sdk)) { throw "SDK clone missing at $sdk" }
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Filter '*.patch' | Remove-Item
git -C $sdk format-patch --no-stat --no-signature -o $out "$base..lr-android" | ForEach-Object { Write-Host "wrote $_" }
"# Patches on top of rexglue-sdk $base, exported from branch lr-android by tools/export_sdk_patches.ps1`n`nApply: ``git -C <sdk> am patches/rexglue-sdk/*.patch``" | Set-Content -Encoding utf8 (Join-Path $out 'README.md')
