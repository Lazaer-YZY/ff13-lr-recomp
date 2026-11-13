"""Offline shader extraction for Lightning Returns (Xbox 360, US): every Xenos shader in the game.

Usage: python -I tools/lr_shader_scan.py GAME_ROOT OUT_DIR [--image IMAGE.bin] [--compare RUNTIME.xsh]
  --image: the decompressed executable image (engine shaders live there), dumped with
  `rexglue codegen <manifest> --dump-image IMAGE.bin` (see docs/shader-pipeline.md).
  GAME_ROOT holds default.xex and weiss_data/ (e.g. "C:/lr/Input - Data/extracted").
  OUT_DIR gets:
    5351081B.xsh        every unique shader as a runtime shader storage file (same format as
                        cache/shaders/shareable/5351081B.xsh), so the runtime knows them all from
                        the first start
    shaders.csv         one row per unique shader: hash, type, dwords, first archive path seen
    scan_report.txt     archives, entries, containers, duplicates, rejects and why
  --compare: a runtime .xsh (pulled from a device): reports how many of the shaders the game
  actually loaded at runtime were found offline (the ucode hash must match exactly).

Formats (sources: refs/lr-ffxiii-port/converters/jpvoice/lr_jpvoice.cpp for the 360 filelists and
archives; refs/XenosRecomp/XenosRecomp/{main.cpp,shader.h} for shader containers):
  filelist: optional 32-byte crypt header (u32 LE tag 0x1DE03478 at 0x14, BE body size at 0x10),
  8-byte XOR-table cipher; then u32 chunk table offset, chunk data offset, entry count; 8-byte
  entries (code, u16 string offset | chunk low bit, chunk high bits, file type); 12-byte chunk
  records (uncompressed, compressed, offset) of zlib text "sector:usize:csize:path" (hex).
  archive entry: at sector * 2048, csize bytes, zlib-compressed unless csize == usize.
  shader container: 9 big-endian u32 (flags, virtualSize, physicalSize, fieldC, constantTable,
  definitionTable, shaderOffset, 0, 0) with (flags & 0xFFFFFF00) == 0x102A1100; flags & 1 = vertex;
  shader header at container + shaderOffset (physicalOffset, size, ...); microcode at
  container + virtualSize + physicalOffset. The runtime hashes the microcode as uploaded
  (big-endian dwords) with XXH3_64bits (tools/native/xxh3.dll, build_xxh3.sh).
"""
import collections
import ctypes
import os
import re
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
_xxh = ctypes.CDLL(os.path.join(HERE, 'native', 'xxh3.dll'))
_xxh.lr_xxh3_64.restype = ctypes.c_uint64
_xxh.lr_xxh3_64.argtypes = [ctypes.c_char_p, ctypes.c_uint64]


def xxh3(data):
    return _xxh.lr_xxh3_64(bytes(data), len(data))


M32 = 0xFFFFFFFF


def _xor_table(seed64):
    seed = seed64.to_bytes(8, 'little')[::-1]
    a = int.from_bytes(seed[0:4], 'little')
    b = int.from_bytes(seed[4:8], 'little')
    a = ((a << 8) | (a >> 24)) & M32
    b = ((b >> 16) | (b << 16)) & M32
    blk = bytearray(b.to_bytes(4, 'little') + a.to_bytes(4, 'little'))
    blk[0] = (blk[0] + 0x45) & 0xFF
    for i in range(1, 8):
        t = blk[i] + 0xD4 + blk[i - 1]
        t ^= blk[i - 1] << 2
        t ^= 0x45
        blk[i] = t & 0xFF
    table = bytearray(264)
    table[0:8] = blk
    prev = int.from_bytes(blk, 'little')
    for i in range(1, 0x21):
        ha, hb = prev & M32, prev >> 32
        x = (prev * 5) & 0xFFFFFFFFFFFFFFFF
        x ^= hb << 32
        ta = ha ^ (x & M32)
        tb = x >> 32
        x = ha | (x & 0xFFFFFFFF00000000)
        xa = (x & M32) ^ ta
        xb = tb ^ hb
        table[i * 8:i * 8 + 4] = xa.to_bytes(4, 'little')
        table[i * 8 + 4:i * 8 + 8] = xb.to_bytes(4, 'little')
        prev = xa | (xb << 32)
    return table


def decrypt_filelist(data):
    body = int.from_bytes(data[16:20], 'big') + 8
    s = data
    seed32 = (s[9] << 24) | (s[12] << 16) | (s[2] << 8) | s[0]
    seed = seed32 | (0xFFFFFFFF00000000 if seed32 & 0x80000000 else 0)
    table = _xor_table(seed)
    out = bytearray(data)
    pos = 32
    if 32 + body > len(data):
        raise ValueError('file list truncated')
    for n in range(body // 8):
        counter = n * 8
        cur = data[pos:pos + 8]
        off = counter & 0xF8
        tab = table[off:off + 8]

        def loop(v):
            for k in range(8):
                v = (((v + 120) & 0xFF) - tab[k]) & 0xFF
            return v

        d = [loop(((n ^ 69) & 255) ^ cur[0])] + [loop(cur[k - 1] ^ cur[k]) for k in range(1, 8)]
        arr = bytes([d[4], d[5], d[6], d[7], d[0], d[1], d[2], d[3]])
        hi = int.from_bytes(arr[0:4], 'little')
        lo = int.from_bytes(arr[4:8], 'little')
        xlo = int.from_bytes(table[off:off + 4], 'little')
        xhi = int.from_bytes(table[off + 4:off + 8], 'little')
        c = counter
        ab, cd, ef = c << 10, c << 20, c << 30
        cv = (cd & M32) | (ab & M32)
        dv = (cd >> 32) | (ab >> 32)
        ev = ((ef & M32) | c | cv) & M32
        fv = ((ef >> 32) | dv) & M32
        carry1 = 1 if ev > (~0xA1652347 & M32) else 0
        k1 = (ev + 0xA1652347) & M32
        k2 = (fv + carry1) & M32
        lo_x = (lo - xlo) & M32
        hi_x = (hi - xhi - (1 if lo < xlo else 0)) & M32
        lo_x ^= k1
        hi_x ^= k2
        lo_x ^= xlo
        hi_x ^= xhi
        out[pos:pos + 4] = hi_x.to_bytes(4, 'little')
        out[pos + 4:pos + 8] = lo_x.to_bytes(4, 'little')
        pos += 8
    size = int.from_bytes(out[16:20], 'big')
    chk = int.from_bytes(out[32 + size + 4:32 + size + 8], 'little')
    calc = sum(out[32 + 4 * i] for i in range(size // 4)) & M32
    if chk != calc:
        raise ValueError('file list checksum mismatch after decrypt')
    return bytes(out)


def parse_filelist(raw):
    base = 0
    if len(raw) >= 24 and int.from_bytes(raw[20:24], 'little') == 0x1DE03478:
        raw = decrypt_filelist(raw)
        base = 32
    be = int.from_bytes(raw[base + 8:base + 12], 'little') >= 0x100000
    end = 'big' if be else 'little'
    rd = lambda o, n=4: int.from_bytes(raw[o:o + n], end)
    chunk_tab, chunk_data, count = rd(base), rd(base + 4), rd(base + 8)
    ents = []
    nchunks = 0
    for i in range(count):
        p = base + 12 + i * 8
        code = rd(p)
        info = rd(p + 4, 2)
        chunk = (raw[p + 6] << 1) | (info >> 15)
        ents.append((code, info & 0x7FFF, chunk, raw[p + 7]))
        nchunks = max(nchunks, chunk + 1)
    chunks = []
    for c in range(nchunks):
        t = base + chunk_tab + 12 * c
        un, cm, cp = rd(t), rd(t + 4), rd(t + 8)
        p = base + chunk_data + cp
        data = zlib.decompress(raw[p:p + cm])
        if len(data) != un:
            raise ValueError('file list chunk size')
        chunks.append(data)
    out = []
    for code, info, chunk, ftype in ents:
        b = chunks[chunk]
        text = b[info:b.index(b'\0', info)].decode('ascii', 'replace')
        sec, us, cs, path = text.split(':', 3)
        out.append((int(sec, 16), int(us, 16), int(cs, 16), path.strip()))
    return out


def read_entry(fh, sector, usize, csize):
    fh.seek(sector * 2048)
    b = fh.read(csize)
    if csize == usize:
        return b
    return zlib.decompress(b)


CONTAINER = re.compile(rb'\x10\x2a\x11[\x00-\xff]', re.S)


def find_shaders(data, rejects):
    """Yields (type, ucode bytes) for every valid container in data (4-byte aligned scan)."""
    n = len(data)
    for m in CONTAINER.finditer(data):
        o = m.start()
        if o & 3 or o + 36 > n:
            continue
        flags, vsize, psize, _c, _ct, _dt, shader_off, f1c, f20 = struct.unpack_from('>9I', data, o)
        if f1c or f20:
            rejects['header fields 0x1C/0x20 not zero'] += 1
            continue
        if vsize + psize > n - o or shader_off + 8 > vsize:
            rejects['sizes out of range'] += 1
            continue
        phys_off, size = struct.unpack_from('>2I', data, o + shader_off)
        # Shader header word 3 (pixel shaders): bit 1 = the shader reads the screen position
        # (Direct3D enables parameter generation), bits 8-15 = its register.
        field_c = struct.unpack_from('>I', data, o + shader_off + 12)[0] if shader_off + 16 <= vsize else 0
        # The size field is in bytes or dwords; the microcode must fit the physical part.
        for nbytes in (size, size * 4):
            start = o + vsize + phys_off
            if nbytes and nbytes % 4 == 0 and phys_off + nbytes <= psize and start + nbytes <= n:
                yield (0 if flags & 1 else 1), data[start:start + nbytes], nbytes == size, field_c
                break
        else:
            rejects['microcode outside the physical part'] += 1


def write_xsh(path, shaders, header):
    with open(path, 'wb') as f:
        f.write(header)
        for h, (stype, ucode) in sorted(shaders.items()):
            f.write(struct.pack('<QI', h, (len(ucode) // 4) | (stype << 31)))
            f.write(ucode)


def read_xsh(path):
    data = open(path, 'rb').read()
    out, pos = {}, 8
    while pos + 12 <= len(data):
        h, packed = struct.unpack_from('<QI', data, pos)
        n = (packed & 0x7FFFFFFF) * 4
        out[h] = (packed >> 31, data[pos + 12:pos + 12 + n])
        pos += 12 + n
    return data[:8], out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    compare = sys.argv[sys.argv.index('--compare') + 1] if '--compare' in sys.argv else None
    image = sys.argv[sys.argv.index('--image') + 1] if '--image' in sys.argv else None
    for opt in (compare, image):
        if opt in args:
            args.remove(opt)
    root, out_dir = args[0], args[1]
    os.makedirs(out_dir, exist_ok=True)
    wd = os.path.join(root, 'weiss_data')
    pairs = [('sys/filelistu.x360.bin', 'sys/white_imgu.x360.bin'),
             ('sys/filelist2u.x360.bin', 'sys/white_img2u.x360.bin'),
             ('sys/filelist_scru.x360.bin', 'sys/white_scru.x360.bin')]
    report = []
    shaders, first_path, ps_meta = {}, {}, {}
    rejects = collections.Counter()
    units = collections.Counter()
    containers = 0
    zone_lists = []

    def scan_archive(list_name, list_bytes, img_rel):
        nonlocal containers
        entries = parse_filelist(list_bytes)
        img = os.path.join(wd, img_rel)
        found = 0
        failed = 0
        with open(img, 'rb') as fh:
            for sector, usize, csize, path in entries:
                try:
                    data = read_entry(fh, sector, usize, csize)
                except Exception:
                    failed += 1
                    continue
                if re.search(r'filelist_?z\d+', path) and path.endswith('.bin'):
                    zone_lists.append((path, data))
                for stype, ucode, size_in_bytes, field_c in find_shaders(data, rejects):
                    containers += 1
                    found += 1
                    units['bytes' if size_in_bytes else 'dwords'] += 1
                    h = xxh3(ucode)
                    if h not in shaders:
                        shaders[h] = (stype, ucode)
                        first_path[h] = path
                    if stype == 1:
                        ps_meta.setdefault(h, field_c)
        report.append(f'{list_name} -> {img_rel}: {len(entries)} entries, {failed} unreadable, '
                      f'{found} shader containers')

    for lst, img in pairs:
        scan_archive(lst, open(os.path.join(wd, lst), 'rb').read(), img)
    for path, data in zone_lists:
        m = re.search(r'z(\d+)', path)
        img = f'zone/white_z{m.group(1)}u_img.x360.bin'
        if os.path.exists(os.path.join(wd, img)):
            try:
                scan_archive(path, data, img)
            except Exception as e:
                report.append(f'{path} -> {img}: failed ({e})')
        else:
            report.append(f'{path}: no {img} on disc')
    if image:
        found = 0
        for stype, ucode, size_in_bytes, field_c in find_shaders(open(image, 'rb').read(), rejects):
            containers += 1
            found += 1
            h = xxh3(ucode)
            if h not in shaders:
                shaders[h] = (stype, ucode)
                first_path[h] = 'default.xex (image)'
            if stype == 1:
                ps_meta.setdefault(h, field_c)
        report.append(f'executable image {image}: {found} shader containers')
    vs = sum(1 for t, _ in shaders.values() if t == 0)
    report.append(f'total: {containers} containers, {len(shaders)} unique shaders ({vs} vertex, '
                  f'{len(shaders) - vs} pixel); size field read as {dict(units)}')
    report.append(f'rejected candidates: {dict(rejects) or "none"}')
    header = struct.pack('<I', 0x48534558) + struct.pack('>I', 0x20201219)
    if compare:
        cmp_header, runtime = read_xsh(compare)
        header = cmp_header
        found = sum(1 for h in runtime if h in shaders)
        report.append(f'runtime shaders ({compare}): {len(runtime)}, found offline: {found}, '
                      f'missing: {len(runtime) - found}')
        by_type = collections.Counter('vertex' if t == 0 else 'pixel'
                                      for h, (t, u) in runtime.items() if h not in shaders)
        report.append(f'missing by type: {dict(by_type) or "none"}')
        # Vertex shaders are patched by the 360 Direct3D runtime when bound to a vertex
        # declaration (vfetch fields), so the uploaded ucode differs from the container's.
        bylen = collections.defaultdict(list)
        for h, (t, u) in shaders.items():
            bylen[(t, len(u))].append(u)
        near = collections.Counter()
        for h, (t, u) in runtime.items():
            if h in shaders:
                continue
            best = min((sum(1 for i in range(0, len(u), 4) if u[i:i + 4] != c[i:i + 4])
                        for c in bylen.get((t, len(u)), [])), default=None)
            near['no same-length shader' if best is None else
                 'differs in <= 12 dwords (vfetch patches)' if best <= 12 else
                 'differs in > 12 dwords'] += 1
        report.append(f'missing vs closest offline shader: {dict(near) or "none"}')
        missing = [f'{h:016X} type {t} dwords {len(u) // 4}' for h, (t, u) in runtime.items()
                   if h not in shaders]
        if missing:
            report.append('missing: ' + ', '.join(missing[:40]))
    write_xsh(os.path.join(out_dir, '5351081B.xsh'), shaders, header)
    # Pixel shader metadata for ahead-of-time pipeline libraries (the runtime derives the
    # parameter generation part of the canonical modification from it): 'XEPM', u32 count, then
    # (u64 ucode hash, u32 shader header word 3) records, little-endian.
    with open(os.path.join(out_dir, '5351081B.psmeta'), 'wb') as f:
        f.write(struct.pack('<II', 0x4D504558, len(ps_meta)))
        for h, field_c in sorted(ps_meta.items()):
            f.write(struct.pack('<QI', h, field_c))
    report.append(f'pixel shader metadata: {len(ps_meta)} records '
                  f'({sum(1 for v in ps_meta.values() if v & 2)} with parameter generation)')
    with open(os.path.join(out_dir, 'shaders.csv'), 'w') as f:
        f.write('hash,type,dwords,first_path\n')
        for h, (t, u) in sorted(shaders.items()):
            f.write(f'{h:016X},{"vertex" if t == 0 else "pixel"},{len(u) // 4},{first_path[h]}\n')
    with open(os.path.join(out_dir, 'scan_report.txt'), 'w') as f:
        f.write('\n'.join(report) + '\n')
    print('\n'.join(report))


if __name__ == '__main__':
    main()
