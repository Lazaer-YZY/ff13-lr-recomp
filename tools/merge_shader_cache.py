"""Merge Lightning Returns shader caches (the shareable part of the runtime's shader storage).

Usage: python -I tools/merge_shader_cache.py OUT_DIR IN_DIR [IN_DIR ...]
Each IN_DIR holds 5351081B.xsh (the game's shaders) and/or 5351081B.fbo.vk.xpso (pipeline
descriptions), e.g. a folder pulled with tools/shader_cache.sh. OUT_DIR gets both files with the
union of all records (first occurrence wins, order kept), ready to push to a device.
The driver's .vk.pipelinecache is not merged: it only works on the same GPU and driver build,
and each device rebuilds its own in the background.
"""
import os
import struct
import sys

TITLE = '5351081B'
XSH_MAGIC = 0x48534558   # 'XESH'
XPSO_MAGIC = 0x53504558  # 'XEPS'
XPSO_RECORD = 66         # uint64 hash + PipelineDescription (58 bytes) at storage version below


def read_xsh(path):
    data = open(path, 'rb').read()
    if len(data) < 8 or struct.unpack_from('<I', data, 0)[0] != XSH_MAGIC:
        sys.exit(f'{path}: not a shader storage file')
    header, records, pos = data[:8], [], 8
    while pos + 12 <= len(data):
        h, packed = struct.unpack_from('<QI', data, pos)
        size = 12 + (packed & 0x7FFFFFFF) * 4
        if pos + size > len(data):
            break  # truncated tail, as the runtime does
        records.append((h, data[pos:pos + size]))
        pos += size
    return header, records


def read_xpso(path):
    data = open(path, 'rb').read()
    if len(data) < 12 or struct.unpack_from('<I', data, 0)[0] != XPSO_MAGIC:
        sys.exit(f'{path}: not a pipeline storage file')
    body = data[12:]
    if len(body) % XPSO_RECORD:
        sys.exit(f'{path}: {len(body)} bytes of records is not a multiple of {XPSO_RECORD}; '
                 'the storage format changed, update XPSO_RECORD in this tool')
    return data[:12], [(body[i:i + 8], body[i:i + XPSO_RECORD])
                       for i in range(0, len(body), XPSO_RECORD)]


def merge(name, reader, out_dir, in_dirs):
    header, seen, out = None, set(), []
    for d in in_dirs:
        p = os.path.join(d, name)
        if not os.path.isfile(p):
            continue
        h, recs = reader(p)
        if header is None:
            header = h
        elif h != header:
            sys.exit(f'{p}: made by a different build (header {h.hex()} vs {header.hex()}); '
                     'caches only merge between the same app version')
        new = 0
        for key, rec in recs:
            if key not in seen:
                seen.add(key)
                out.append(rec)
                new += 1
        print(f'  {p}: {len(recs)} records, {new} new')
    if header is None:
        print(f'  {name}: in none of the inputs')
        return
    with open(os.path.join(out_dir, name), 'wb') as f:
        f.write(header)
        f.writelines(out)
    print(f'{name}: {len(out)} records -> {os.path.join(out_dir, name)}')


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out_dir, in_dirs = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    merge(f'{TITLE}.xsh', read_xsh, out_dir, in_dirs)
    merge(f'{TITLE}.fbo.vk.xpso', read_xpso, out_dir, in_dirs)


if __name__ == '__main__':
    main()
