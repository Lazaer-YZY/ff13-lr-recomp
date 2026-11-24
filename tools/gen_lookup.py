"""Static look-ups in the generated recomp sources (generated/default), for reverse engineering without reading the
16,000-line files: every command prints a compact view.
  gen_lookup.py index                    build the index (function -> file, line range, callees) in out/gen_index.json;
                                         run it again after a codegen
  gen_lookup.py asm sub_X [max] [skip]   the function's PowerPC instructions only (the generated comments)
  gen_lookup.py body sub_X [max] [skip]  the generated C++
  gen_lookup.py callers sub_X            functions that call sub_X directly
  gen_lookup.py callees sub_X            functions sub_X calls, in order
  gen_lookup.py size sub_X ...           line counts and files
  gen_lookup.py near LO HI               functions with addresses in [LO, HI] (hex): lines, number of callees
  gen_lookup.py grepf REGEX              functions whose generated text matches REGEX (first three matching lines)
Used to find the UI's projection for wide screens (DECISIONS 2026-11-24). LR_REPO overrides C:/lr.
"""
import json
import os
import re
import sys

ROOT = os.environ.get('LR_REPO', 'C:/lr')
GEN = ROOT + '/generated/default'
IDX = ROOT + '/out/gen_index.json'
DEF = re.compile(r'^DEFINE_REX_FUNC\((sub_[0-9A-F]+)\)')
CALL = re.compile(r'\b(sub_[0-9A-F]{8})\(ctx, base\)')


def build():
    idx = {}
    for name in sorted(os.listdir(GEN)):
        if not (name.startswith('lr_recomp.') and name.endswith('.cpp')):
            continue
        with open(os.path.join(GEN, name), encoding='utf-8', errors='replace') as f:
            cur = None
            for no, line in enumerate(f, 1):
                m = DEF.match(line)
                if m:
                    if cur:
                        idx[cur][2] = no - 1
                    cur = m.group(1)
                    idx[cur] = [name, no, no, []]
                elif cur:
                    for c in CALL.findall(line):
                        if c not in idx[cur][3]:
                            idx[cur][3].append(c)
            if cur:
                idx[cur][2] = no
    with open(IDX, 'w') as f:
        json.dump(idx, f)
    print(len(idx), 'functions')


def load():
    with open(IDX) as f:
        return json.load(f)


def body(idx, fn):
    name, a, b, _ = idx[fn]
    with open(os.path.join(GEN, name), encoding='utf-8', errors='replace') as f:
        lines = f.readlines()[a - 1:b]
    return lines


def main():
    cmd = sys.argv[1]
    if cmd == 'index':
        build()
        return
    idx = load()
    if cmd == 'body':
        lines = body(idx, sys.argv[2])
        mx = int(sys.argv[3]) if len(sys.argv) > 3 else 400
        skip = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        print(''.join(lines[skip:skip + mx]), end='')
        if len(lines) > skip + mx:
            print('... (%d lines total)' % len(lines))
    elif cmd == 'asm':
        # compact view: only the PPC comment lines; calls keep their target, constants loaded via lis/addi are visible
        lines = body(idx, sys.argv[2])
        mx = int(sys.argv[3]) if len(sys.argv) > 3 else 600
        skip = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        out = []
        for ln in lines:
            t = ln.strip()
            if t.startswith('// '):
                out.append(t[3:])
            elif t.startswith('loc_') and t.endswith(':'):
                out.append(t)
        print('\n'.join(out[skip:skip + mx]))
        if len(out) > skip + mx:
            print('... (%d asm lines total)' % len(out))
    elif cmd == 'near':
        # functions with addresses in [lo, hi]
        lo, hi = int(sys.argv[2], 16), int(sys.argv[3], 16)
        for k in sorted(idx):
            a = int(k[4:], 16)
            if lo <= a <= hi:
                v = idx[k]
                print(k, v[2] - v[1] + 1, len(v[3]))
    elif cmd == 'callers':
        t = sys.argv[2]
        print(' '.join(sorted(k for k, v in idx.items() if t in v[3])))
    elif cmd == 'callees':
        print(' '.join(idx[sys.argv[2]][3]))
    elif cmd == 'size':
        for fn in sys.argv[2:]:
            v = idx.get(fn)
            print(fn, v[2] - v[1] + 1 if v else 'missing', v[0] if v else '')
    elif cmd == 'grepf':
        rx = re.compile(sys.argv[2])
        byfile = {}
        for k, v in idx.items():
            byfile.setdefault(v[0], []).append((v[1], v[2], k))
        for name, fns in sorted(byfile.items()):
            fns.sort()
            with open(os.path.join(GEN, name), encoding='utf-8', errors='replace') as f:
                lines = f.readlines()
            for a, b, k in fns:
                hits = [(i, lines[i - 1].rstrip()) for i in range(a, b + 1) if rx.search(lines[i - 1])]
                if hits:
                    print(k, len(hits), '|', ' || '.join(h[1].strip() for h in hits[:3]))


main()
