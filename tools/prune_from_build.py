"""Prune seeded manifest entries that split real functions, using compile errors of the generated code.

Symptom: `lr_recomp.N.cpp:LINE: error: use of undeclared label 'loc_XXXXXXXX'` - a parent function jumps to a
label that now lies inside a separately generated (seeded) function. Fix: comment out the seeded entry that cut
the parent: the greatest seeded address a with parent_start < a <= label; if there is none and the parent itself
was seeded, drop the parent entry.
Usage: python -I tools/prune_from_build.py out/build.log lr_manifest.toml
"""
import os, re, sys
from bisect import bisect_right

log, manifest = sys.argv[1], sys.argv[2]
ERR = re.compile(r"(C:/lr/generated/default/lr_recomp\.\d+\.cpp):(\d+):\d+: error: use of undeclared label 'loc_([0-9A-Fa-f]{8})'")
errors = set(ERR.findall(open(log, encoding="utf-8", errors="replace").read()))
lines = open(manifest, encoding="utf-8").read().split("\n")
seeded = {}
for i, l in enumerate(lines):
    m = re.match(r"^0x([0-9A-Fa-f]{8}) = \{\}\s*#\s*(thunk-scan|ptr-scan|boot loop)", l)
    if m:
        seeded[int(m.group(1), 16)] = i
addrs = sorted(seeded)
func_cache = {}

def parent_of(path, line_no):
    if path not in func_cache:
        starts = []
        with open(path, encoding="utf-8", errors="replace") as f:
            for n, l in enumerate(f, 1):
                m = re.match(r"DEFINE_REX_FUNC\(sub_([0-9A-Fa-f]{8})\)", l)
                if m:
                    starts.append((n, int(m.group(1), 16)))
        func_cache[path] = starts
    starts = func_cache[path]
    i = bisect_right([s[0] for s in starts], line_no) - 1
    return starts[i][1] if i >= 0 else None

bad = set()
for path, line_no, label in errors:
    label = int(label, 16)
    parent = parent_of(path, int(line_no))
    if parent is None:
        continue
    i = bisect_right(addrs, label) - 1
    while i >= 0 and addrs[i] > parent:
        bad.add(addrs[i]); i -= 1; break  # the nearest seeded start at/below the label, inside the parent
    else:
        if parent in seeded:
            bad.add(parent)
for a in bad:
    lines[seeded[a]] = f"# pruned 0x{a:08X} (build: split a function)"
open(manifest, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
print(f"{len(errors)} label errors -> pruned {len(bad)} seeded entries: " + " ".join(f"0x{a:08X}" for a in sorted(bad)))
