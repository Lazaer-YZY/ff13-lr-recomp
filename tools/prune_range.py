"""Comment out seeded manifest entries with lo < address <= hi (used for 'Unresolved branch from X to Y' fatals:
the seeded start that split the function lies between the branch target and the branch site).
Usage: python -I tools/prune_range.py lr_manifest.toml 0xLO 0xHI   -> prints how many were pruned"""
import re, sys
manifest, lo, hi = sys.argv[1], int(sys.argv[2], 16), int(sys.argv[3], 16)
if lo > hi:
    lo, hi = hi, lo
lines = open(manifest, encoding="utf-8").read().split("\n")
n = 0
for i, l in enumerate(lines):
    m = re.match(r"^0x([0-9A-Fa-f]{8}) = \{\}\s*#\s*(thunk-scan|ptr-scan)", l)
    if m and lo < int(m.group(1), 16) <= hi:
        lines[i] = f"# pruned 0x{int(m.group(1), 16):08X} (unresolved branch 0x{lo:08X}..0x{hi:08X})"
        n += 1
open(manifest, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
print(n)
