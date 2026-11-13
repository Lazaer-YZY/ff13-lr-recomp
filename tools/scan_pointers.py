"""Find pointer-only functions: code addresses referenced from data that the analyser did not claim as functions.

Inputs (produced by `rexglue codegen --dump-image out/image.bin --dump-functions out/functions.txt`):
  image.bin       raw guest image from image_base (big-endian, as mapped)
  image.bin.json  {"image_base":..., "image_size":..., "code_start":..., "code_end":..., ...}
  functions.txt   lines "0xADDR 0xSIZE name authority"

Usage:
  python -I tools/scan_pointers.py out/image.bin out/functions.txt [--min-run 2] [--interior] [--emit]
  --interior also lists targets that lie inside a known function's extent (gap-fill may have swallowed a thunk)

Heuristics:
  * scan every 4-byte-aligned word of the image for big-endian values in [code_start, code_end), 4-aligned;
  * keep targets that are not inside any known function [start, start+size);
  * require the first instruction at the target to decode as a plausible PPC function start
    (mflr/stwu/lis/li/addi/b/bl/blr/stw/lwz/mr/cmp...: any valid primary opcode that is not 0 and not all-ones);
  * report pointer runs (consecutive pointer words), which is how initializer and vtable arrays look.
Output: a table of candidates sorted by (run length desc, address), and with --emit the manifest lines.
"""
import json, struct, sys
from bisect import bisect_right

VALID_PRIMARY = {7, 8, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36, 37, 38, 39,
                 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 58, 59, 62, 63, 4, 5, 6}


def load_functions(path):
    funcs = []
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) < 2 or not parts[0].startswith("0x"):
            continue
        funcs.append((int(parts[0], 16), int(parts[1], 16)))
    funcs.sort()
    return funcs


def inside_known(funcs, starts, addr):
    i = bisect_right(starts, addr) - 1
    if i < 0:
        return False
    s, z = funcs[i]
    return s <= addr < s + max(z, 4)


def main():
    img_path, fn_path = sys.argv[1], sys.argv[2]
    min_run = 1
    emit = "--emit" in sys.argv
    include_interior = "--interior" in sys.argv
    if "--min-run" in sys.argv:
        min_run = int(sys.argv[sys.argv.index("--min-run") + 1])
    meta = json.load(open(img_path + ".json"))
    def num(v):
        return int(v, 16) if isinstance(v, str) else int(v)
    base, size = num(meta["image_base"]), num(meta["image_size"])
    code_start, code_end = num(meta["code_start"]), num(meta["code_end"])
    img = open(img_path, "rb").read()
    assert len(img) >= size, (len(img), size)
    funcs = load_functions(fn_path)
    starts = [s for s, _ in funcs]
    known = set(starts)

    def insn_at(addr):
        off = addr - base
        return struct.unpack_from(">I", img, off)[0] if 0 <= off + 4 <= len(img) else None

    # Pass 1: every pointer word -> target
    cands = {}  # target -> list of referencing addresses
    run_len = {}  # target -> length of the pointer run it belongs to
    i = 0
    n_words = size // 4
    cur_run = []
    while i < n_words:
        v = struct.unpack_from(">I", img, i * 4)[0]
        is_ptr = code_start <= v < code_end and (v & 3) == 0
        if is_ptr:
            cur_run.append((base + i * 4, v))
        else:
            if cur_run:
                for ref, tgt in cur_run:
                    cands.setdefault(tgt, []).append(ref)
                    run_len[tgt] = max(run_len.get(tgt, 0), len(cur_run))
                cur_run = []
        i += 1
    for ref, tgt in cur_run:
        cands.setdefault(tgt, []).append(ref)
        run_len[tgt] = max(run_len.get(tgt, 0), len(cur_run))

    rows = []
    for tgt, refs in cands.items():
        if tgt in known:
            continue
        if inside_known(funcs, starts, tgt) and not include_interior:
            continue
        insn = insn_at(tgt)
        if insn is None or insn == 0 or insn == 0xFFFFFFFF or (insn >> 26) not in VALID_PRIMARY:
            continue
        if run_len[tgt] < min_run:
            continue
        rows.append((run_len[tgt], tgt, len(refs), refs[0], insn))
    rows.sort(key=lambda r: (-r[0], r[1]))
    if emit:
        for run, tgt, nrefs, ref0, insn in rows:
            print(f"0x{tgt:08X} = {{}}  # ptr-scan run={run} refs={nrefs} from 0x{ref0:08X} insn=0x{insn:08X}")
    else:
        print(f"known functions: {len(funcs)}; pointer targets: {len(cands)}; candidates not in any function: {len(rows)}")
        for run, tgt, nrefs, ref0, insn in rows[:60]:
            print(f"0x{tgt:08X}  run={run:4d} refs={nrefs:3d} from 0x{ref0:08X}  insn=0x{insn:08X}")
        if len(rows) > 60:
            print(f"... {len(rows) - 60} more (use --emit for all)")


if __name__ == "__main__":
    main()
