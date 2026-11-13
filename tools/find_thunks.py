"""Find tail-call thunks that the analyser merged into GAP_FILL functions or left in holes.

Why: ReXGlue's gap-fill creates one function over a gap even when the gap holds several 16-byte
thunks (`lis/li/addi; b target`). Calls through pointers to the inner thunks then die at runtime with
"Call to invalid or unregistered function". Data-pointer scanning does not find them (the pointers are
built in code), but the structure does: inside holes and GAP_FILL functions, a straight-line sequence
that ends with an unconditional `b`/`blr`/`bctr` and starts after the previous terminator is a function.

Inputs: image.bin + image.bin.json (rexglue codegen --dump-image) and functions.txt (--dump-functions).
Usage:
  python -I tools/find_thunks.py out/image.bin out/functions.txt [--max-insns 16] [--emit]
"""
import json, struct, sys
from bisect import bisect_right

NOP = 0x60000000
BLR = 0x4E800020
BCTR = 0x4E800420


def num(v):
    return int(v, 16) if isinstance(v, str) else int(v)


def load_functions(path):
    funcs = []
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if len(p) >= 4 and p[0].startswith("0x"):
            funcs.append((int(p[0], 16), int(p[1], 16), p[2], p[3]))
    funcs.sort()
    return funcs


def decode_branch(insn, addr):
    """Return ('b', target) for unconditional non-linking branch, 'ret' for blr/bctr, 'cond' for
    conditional branches/bl, or None."""
    op = insn >> 26
    if op == 18:
        lk = insn & 1
        aa = (insn >> 1) & 1
        li = insn & 0x03FFFFFC
        if li & 0x02000000:
            li -= 0x04000000
        target = li if aa else (addr + li) & 0xFFFFFFFF
        return ("bl", target) if lk else ("b", target)
    if insn == BLR or insn == BCTR:
        return ("ret", None)
    if op == 16:
        return ("cond", None)
    if op == 19 and ((insn >> 1) & 0x3FF) in (16, 528):  # bclr / bcctr variants
        bo = (insn >> 21) & 0x1F
        if bo & 0x14 == 0x14:  # branch always
            return ("ret", None)
        return ("cond", None)
    return None


def main():
    img_path, fn_path = sys.argv[1], sys.argv[2]
    max_insns = int(sys.argv[sys.argv.index("--max-insns") + 1]) if "--max-insns" in sys.argv else 16
    emit = "--emit" in sys.argv
    meta = json.load(open(img_path + ".json"))
    base = num(meta["image_base"])
    code_start, code_end = num(meta["code_start"]), num(meta["code_end"])
    img = open(img_path, "rb").read()
    funcs = load_functions(fn_path)
    starts = [f[0] for f in funcs]
    known = set(starts)

    def insn_at(a):
        return struct.unpack_from(">I", img, a - base)[0]

    # Regions to scan: holes between functions inside the code range, and GAP_FILL function bodies.
    regions = []  # (start, end, kind, owner)
    prev_end = code_start
    for s, z, name, auth in funcs:
        if s < code_start or s >= code_end:
            continue
        if s > prev_end:
            regions.append((prev_end, s, "hole", None))
        if auth == "GAP_FILL":
            regions.append((s, s + z, "gapfill", name))
        prev_end = max(prev_end, s + z)
    if prev_end < code_end:
        regions.append((prev_end, code_end, "hole", None))

    cands = []  # (addr, kind, owner, n_insns, terminator, target)
    for rs, re_, kind, owner in regions:
        # Branch targets inside the region: conditional/unconditional -> labels; bl -> function entries.
        labels, bl_targets = set(), set()
        for a in range(rs, re_ - 3, 4):
            br = decode_branch(insn_at(a), a)
            if br and br[1] is not None and rs <= br[1] < re_:
                (bl_targets if br[0] == "bl" else labels).add(br[1])
        a = rs
        while a + 4 <= re_ and insn_at(a) in (NOP, 0):
            a += 4
        seg_start = a
        n = 0
        straight = True
        prev_was_thunk = False
        while a + 4 <= re_:
            insn = insn_at(a)
            n += 1
            br = decode_branch(insn, a)
            if br and br[0] in ("cond", "bl"):
                straight = False
            if br and br[0] in ("b", "ret"):
                term, target = br
                is_thunk = straight and n <= max_insns
                if term == "b" and seg_start <= target < a + 4:
                    is_thunk = False  # backward/forward jump inside the segment: a loop, not a thunk
                entry = seg_start not in known and seg_start not in labels
                if entry and (is_thunk or prev_was_thunk or seg_start in bl_targets):
                    cands.append((seg_start, kind, owner, n, term, target, "thunk" if is_thunk else ("after-thunk" if prev_was_thunk else "bl-target")))
                prev_was_thunk = is_thunk
                a += 4
                while a + 4 <= re_ and insn_at(a) in (NOP, 0):
                    a += 4
                seg_start = a
                n = 0
                straight = True
                continue
            a += 4
        # a trailing segment with no terminator inside the region: still an entry if it follows a thunk
        if a > seg_start and seg_start not in known and seg_start not in labels and (prev_was_thunk or seg_start in bl_targets):
            cands.append((seg_start, kind, owner, n, "none", None, "after-thunk" if prev_was_thunk else "bl-target"))
    cands.sort()
    if emit:
        for addr, kind, owner, n, term, target, why in cands:
            t = f" -> 0x{target:08X}" if target else ""
            print(f"0x{addr:08X} = {{}}  # thunk-scan {why} {kind}{' in ' + owner if owner else ''} {n} insns {term}{t}")
    else:
        print(f"functions: {len(funcs)}; regions scanned: {len(regions)}; thunk candidates: {len(cands)}")
        kinds = {}
        for c in cands:
            kinds[c[1]] = kinds.get(c[1], 0) + 1
        print("by region kind:", kinds)
        whys = {}
        for c in cands:
            whys[c[6]] = whys.get(c[6], 0) + 1
        print("by reason:", whys)
        for addr, kind, owner, n, term, target, why in cands[:40]:
            t = f" -> 0x{target:08X}" if target else ""
            print(f"0x{addr:08X}  {why:11s} {kind:8s} {owner or '':16s} {n:2d} insns {term}{t}")
        if len(cands) > 40:
            print(f"... {len(cands) - 40} more (--emit for all)")


if __name__ == "__main__":
    main()
