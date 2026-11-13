"""Summarise a `rexglue codegen` log: analysis errors by category, hottest unresolved targets.

Usage:
  python -I tools/triage_codegen.py out/codegen1.log            # summary
  python -I tools/triage_codegen.py out/codegen1.log --targets  # one line per unresolved target, sorted by site count
  python -I tools/triage_codegen.py out/codegen1.log --emit     # candidate [functions] lines for unresolved targets

Report format (src/codegen/analyze.cpp PrintReport):
  <Category> (<n>):
    0xADDR from 0xSITE: <message>
    0xADDR: <message>
"""
import re, sys
from collections import Counter, defaultdict

CAT_RE = re.compile(r"(UnresolvedCall|MissingJumpTable|JumpTargetOutOfBounds|DiscontinuousFunction|UnimplementedInsn) \((\d+)\):")
ENTRY_RE = re.compile(r"\s0x([0-9A-Fa-f]{8})(?: from 0x([0-9A-Fa-f]{8}))?: (.*)$")


def parse(path):
    cat = None
    entries = defaultdict(list)  # cat -> [(addr, site, msg)]
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = CAT_RE.search(line)
            if m:
                cat = m.group(1)
                continue
            if cat:
                m = ENTRY_RE.search(line)
                if m:
                    entries[cat].append((int(m.group(1), 16), int(m.group(2), 16) if m.group(2) else 0, m.group(3).strip()))
                elif "Total:" in line:
                    cat = None
    return entries


def main():
    path = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else "--summary"
    e = parse(path)
    if mode == "--summary":
        for cat, lst in sorted(e.items(), key=lambda kv: -len(kv[1])):
            targets = Counter(a for a, _, _ in lst)
            print(f"{cat}: {len(lst)} entries, {len(targets)} distinct targets")
            kinds = Counter(re.sub(r"0x[0-9A-Fa-f]+", "0x?", m) for _, _, m in lst)
            for k, n in kinds.most_common(4):
                print(f"    {n:6d}  {k[:110]}")
        if not e:
            print("no analysis errors found in log")
        return
    unresolved = e.get("UnresolvedCall", [])
    by_target = defaultdict(list)
    for a, s, m in unresolved:
        by_target[a].append((s, m))
    rows = sorted(by_target.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    if mode == "--targets":
        for a, sites in rows:
            kinds = Counter("bl" if m.startswith("bl") else "b" for _, m in sites)
            print(f"0x{a:08X}  sites={len(sites):4d}  bl={kinds['bl']:4d} b={kinds['b']:4d}  e.g. from 0x{sites[0][0]:08X}")
    elif mode == "--emit":
        for a, sites in rows:
            print(f"0x{a:08X} = {{ }}  # {len(sites)} site(s)")


if __name__ == "__main__":
    main()
