"""Guest-memory snapshots of the running game (src/lr_companion.inc, tools/companion_dump.sh).

A .gmem file is a sequence of records: u32 BE guest address, u32 BE size, then the bytes.
Used to find where the game keeps what the second screen shows (battle flag, schemata, ATB,
HP, area, position). Needs numpy: run with out/venv/Scripts/python.exe -I tools/gmem.py ...

  regions DUMP                         list the regions
  find DUMP PATTERN                    every address of a value; PATTERN = u32:1234 | u16:2500 |
                                       f32:93.0 | str:Thunder | utf16:Thunder | hex:DEADBEEF
  read DUMP ADDR [LEN]                 hex dump (ADDR hex)
  track TYPE V1 D1 V2 D2 ...           addresses where dump Di holds value Vi for every i
                                       (TYPE u8|u16|u32|f32; Vi may be '*' = any, '!x' = not x,
                                       'a..b' = range); prints up to 200 addresses
  same TYPE D1 D2 ... -- D3 D4 ...     addresses equal across the first group and different from
                                       all of the second (e.g. a flag set only in battle dumps)
"""
import struct
import sys

import numpy as np

TYPES = {'u8': ('>u1', 1), 'u16': ('>u2', 2), 'u32': ('>u4', 4), 'f32': ('>f4', 4), 's32': ('>i4', 4)}


def load(path):
    regions = []
    with open(path, 'rb') as f:
        data = f.read()
    off = 0
    while off + 8 <= len(data):
        addr, size = struct.unpack_from('>II', data, off)
        off += 8
        regions.append((addr, np.frombuffer(data, dtype=np.uint8, count=size, offset=off)))
        off += size
    return regions


def region_map(regions):
    return {a: buf for a, buf in regions}


def read_at(regions, addr, n):
    for a, buf in regions:
        if a <= addr < a + len(buf):
            o = addr - a
            return bytes(buf[o:o + n])
    return None


def pattern(spec):
    kind, _, val = spec.partition(':')
    if kind == 'u32': return struct.pack('>I', int(val, 0))
    if kind == 's32': return struct.pack('>i', int(val, 0))
    if kind == 'u16': return struct.pack('>H', int(val, 0))
    if kind == 'f32': return struct.pack('>f', float(val))
    if kind == 'str': return val.encode('latin1')
    if kind == 'utf16': return val.encode('utf-16-be')
    if kind == 'hex': return bytes.fromhex(val)
    raise SystemExit('unknown pattern ' + spec)


def cmd_find(path, spec, limit=500):
    pat = pattern(spec)
    n = 0
    for a, buf in load(path):
        b = buf.tobytes()
        i = b.find(pat)
        while i >= 0:
            print(f'{a + i:08X}')
            n += 1
            if n >= limit:
                print('...')
                return
            i = b.find(pat, i + 1)
    print(f'{n} hits')


def cmd_read(path, addr, n=256):
    regions = load(path)
    b = read_at(regions, addr, n)
    if b is None:
        raise SystemExit('not in dump')
    for i in range(0, len(b), 16):
        row = b[i:i + 16]
        asc = ''.join(chr(c) if 32 <= c < 127 else '.' for c in row)
        print(f'{addr + i:08X}  {row.hex(" ", 4):<40}  {asc}')


def matcher(spec, dtype):
    is_float = dtype.endswith('f4')
    conv = float if is_float else (lambda x: int(x, 0))
    if spec == '*':
        return lambda v: np.ones(v.shape, bool)
    if spec.startswith('!'):
        x = conv(spec[1:])
        return lambda v: v != x
    if '..' in spec:
        lo, hi = spec.split('..')
        lo, hi = conv(lo), conv(hi)
        return lambda v: (v >= lo) & (v <= hi)
    x = conv(spec)
    if is_float:
        return lambda v: np.abs(v - x) <= max(0.01, abs(x) * 1e-4)
    return lambda v: v == x


def views(regions, dtype, size):
    """Per region: aligned values as numpy arrays keyed by region address."""
    out = {}
    for a, buf in regions:
        n = len(buf) // size * size
        out[a] = buf[:n].view(dtype)
    return out


def cmd_track(typ, pairs):
    dtype, size = TYPES[typ]
    dumps = [(matcher(v, dtype), views(load(d), dtype, size)) for v, d in pairs]
    first = dumps[0][1]
    hits = []
    for a, vals in first.items():
        mask = dumps[0][0](vals)
        for m, vw in dumps[1:]:
            other = vw.get(a)
            if other is None or len(other) != len(vals):
                mask &= False
                break
            mask &= m(other)
        idx = np.nonzero(mask)[0]
        hits.extend(a + int(i) * size for i in idx)
    print(f'{len(hits)} addresses')
    for h in hits[:200]:
        vals = []
        for _, vw in dumps:
            for a, arr in vw.items():
                if a <= h < a + len(arr) * size:
                    vals.append(arr[(h - a) // size].item())
                    break
        print(f'{h:08X}  ' + ' '.join(str(v) for v in vals))


def cmd_same(typ, group_a, group_b):
    dtype, size = TYPES[typ]
    va = [views(load(d), dtype, size) for d in group_a]
    vb = [views(load(d), dtype, size) for d in group_b]
    hits = []
    for a, base in va[0].items():
        mask = np.ones(base.shape, bool)
        for v in va[1:] + vb:
            if a not in v or len(v[a]) != len(base):
                mask[:] = False
        if not mask.any():
            continue
        for v in va[1:]:
            mask &= v[a] == base
        for v in vb:
            mask &= v[a] != base
        hits.extend((a + int(i) * size, base[i].item(), [v[a][i].item() for v in vb]) for i in np.nonzero(mask)[0])
    print(f'{len(hits)} addresses')
    for h, x, others in hits[:300]:
        print(f'{h:08X}  {x}  vs {others}')


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    cmd = a[0]
    if cmd == 'regions':
        total = 0
        for addr, buf in load(a[1]):
            print(f'{addr:08X} {len(buf):10d}')
            total += len(buf)
        print(f'total {total >> 20} MB')
    elif cmd == 'find':
        cmd_find(a[1], a[2])
    elif cmd == 'read':
        cmd_read(a[1], int(a[2], 16), int(a[3], 0) if len(a) > 3 else 256)
    elif cmd == 'track':
        rest = a[2:]
        cmd_track(a[1], list(zip(rest[0::2], rest[1::2])))
    elif cmd == 'same':
        rest = a[2:]
        i = rest.index('--')
        cmd_same(a[1], rest[:i], rest[i + 1:])
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
