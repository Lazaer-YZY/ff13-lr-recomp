"""Decode Lightning Returns (Xbox 360) GUI textures to PNG.

GUI packs are WPD archives (.xgr) of txbh entries (a 'SEDBtxb' wrapper around a GTEX header);
the pixels are in the paired .imgb. GTEX (offsets from 'GTEX'): +6 format, +7 mip count,
+9 type, +10 u16 width, +12 u16 height, +16 u32 offset of the mip table, whose entries are
u32 offset + u32 size into the .imgb. Formats: 24 DXT1, 25 DXT3, 26 DXT5, 3/4 32-bit ARGB.
Xbox 360 data is tiled (Xenos 2D tiling, in 4x4 blocks for DXT) and stored as big-endian words.

  python -I tools/lr_gui_tex.py GAME_ROOT OUT_DIR PATH_SUBSTRING [PATH_SUBSTRING ...]
    decodes every texture of every .xgr whose archive path contains one of the substrings,
    e.g. "gui/resident/pack/pack0" "shop/shop31". Needs numpy and Pillow.
Writes OUT_DIR/<xgr name>__<entry>.png and prints format and size per texture.
"""
import os
import struct
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lr_shader_scan as scan  # noqa: E402

LISTS = [('sys/filelistu.x360.bin', 'sys/white_imgu.x360.bin'),
         ('sys/filelist2u.x360.bin', 'sys/white_img2u.x360.bin'),
         ('sys/filelist_scru.x360.bin', 'sys/white_scru.x360.bin')]


def archive_index(root):
    wd = os.path.join(root, 'weiss_data')
    index = {}
    for fl, img in LISTS:
        for sec, us, cs, p in scan.parse_filelist(open(os.path.join(wd, fl), 'rb').read()):
            index.setdefault(p, (os.path.join(wd, img), sec, us, cs))
    return index


def read(index, path):
    img, sec, us, cs = index[path]
    with open(img, 'rb') as fh:
        return scan.read_entry(fh, sec, us, cs)


def wpd_entries(d):
    if d[:4] != b'WPD\0':
        return []
    n = struct.unpack_from('>I', d, 4)[0]
    out = []
    for i in range(n):
        o = 16 + 32 * i
        name = d[o:o + 16].split(b'\0')[0].decode('latin1')
        off, size = struct.unpack_from('>II', d, o + 16)
        ext = d[o + 24:o + 32].split(b'\0')[0].decode('latin1')
        out.append((name, ext, d[off:off + size]))
    return out


def tiled_offsets(bw, bh, bpb):
    """Xenos XGAddress2DTiledOffset for every (x, y) block; result in blocks."""
    aw = (bw + 31) & ~31
    log_bpp = (bpb >> 2) + ((bpb >> 1) >> (bpb >> 2))
    y, x = np.mgrid[0:bh, 0:bw].astype(np.int64)
    macro = ((x >> 5) + (y >> 5) * (aw >> 5)) << (log_bpp + 7)
    micro = ((x & 7) + ((y & 6) << 2)) << log_bpp
    off = macro + ((micro & ~15) << 1) + (micro & 15) + ((y & 8) << (3 + log_bpp)) + ((y & 1) << 4)
    off = (((off & ~511) << 3) + ((off & 448) << 2) + (off & 63) + ((y & 16) << 7)
           + (((((y & 8) >> 2) + (x >> 3)) & 3) << 6)) >> log_bpp
    return off


def untile(data, bw, bh, bpb, tiled=True):
    raw = np.frombuffer(data, dtype=np.uint8)
    if tiled:
        offs = tiled_offsets(bw, bh, bpb)
        need = (int(offs.max()) + 1) * bpb
        if len(raw) < need:
            raw = np.concatenate([raw, np.zeros(need - len(raw), np.uint8)])
        blocks = raw.reshape(-1, bpb)[offs.reshape(-1)]
    else:
        pitch = (bw + 31) & ~31 if len(raw) >= ((bw + 31) & ~31) * bh * bpb else bw
        blocks = raw[:pitch * bh * bpb].reshape(bh, pitch, bpb)[:, :bw].reshape(-1, bpb)
    return blocks.reshape(bh, bw, bpb)


def rgb565(c):
    r = ((c >> 11) & 31).astype(np.uint32) * 255 // 31
    g = ((c >> 5) & 63).astype(np.uint32) * 255 // 63
    b = (c & 31).astype(np.uint32) * 255 // 31
    return np.stack([r, g, b], -1)


def dxt_color(blk, dxt1):
    """blk: (..., 8) little-endian color block -> (..., 16, 4) RGBA."""
    c0 = blk[..., 0].astype(np.uint16) | (blk[..., 1].astype(np.uint16) << 8)
    c1 = blk[..., 2].astype(np.uint16) | (blk[..., 3].astype(np.uint16) << 8)
    bits = (blk[..., 4].astype(np.uint32) | (blk[..., 5].astype(np.uint32) << 8)
            | (blk[..., 6].astype(np.uint32) << 16) | (blk[..., 7].astype(np.uint32) << 24))
    p0, p1 = rgb565(c0), rgb565(c1)
    four = (c0 > c1) | (not dxt1)
    four = np.broadcast_to(four, c0.shape)[..., None]
    p2 = np.where(four, (2 * p0 + p1) // 3, (p0 + p1) // 2)
    p3 = np.where(four, (p0 + 2 * p1) // 3, 0)
    pal = np.stack([p0, p1, p2, p3], -2)  # (..., 4, 3)
    alpha = np.full(pal.shape[:-1] + (1,), 255, np.uint32)
    if dxt1:
        alpha[..., 3, 0] = np.where(four[..., 0], 255, 0)
    pal = np.concatenate([pal, alpha], -1)
    idx = (bits[..., None] >> (2 * np.arange(16))) & 3
    return np.take_along_axis(pal, idx[..., None].astype(np.int64).repeat(4, -1), -2)


def dxt5_alpha(blk):
    a0 = blk[..., 0].astype(np.int32)
    a1 = blk[..., 1].astype(np.int32)
    bits = np.zeros(blk.shape[:-1], np.uint64)
    for i in range(6):
        bits |= blk[..., 2 + i].astype(np.uint64) << np.uint64(8 * i)
    idx = ((bits[..., None] >> (np.uint64(3) * np.arange(16, dtype=np.uint64))) & np.uint64(7)).astype(np.int64)
    pal = [a0, a1]
    for i in range(1, 7):
        pal.append(np.where(a0 > a1, ((7 - i) * a0 + i * a1) // 7, 0))
    six = [((5 - i) * a0 + i * a1) // 5 for i in range(1, 5)] + [np.zeros_like(a0), np.full_like(a0, 255)]
    for i in range(6):
        pal[2 + i] = np.where(a0 > a1, ((6 - i) * a0 + (1 + i) * a1) // 7, six[i])
    pal = np.stack(pal, -1)
    return np.take_along_axis(pal, idx, -1)


def decode(fmt, w, h, data, tiled=True):
    if fmt in (24, 25, 26):
        bpb = 8 if fmt == 24 else 16
        bw, bh = (w + 3) // 4, (h + 3) // 4
        blocks = untile(data, bw, bh, bpb, tiled)
        blocks = blocks.reshape(bh, bw, bpb // 2, 2)[..., ::-1].reshape(bh, bw, bpb)  # 8-in-16 swap
        if fmt == 24:
            px = dxt_color(blocks, True)
        else:
            px = dxt_color(blocks[..., 8:], False)
            if fmt == 26:
                px[..., 3] = dxt5_alpha(blocks[..., :8])
            else:
                a = blocks[..., :8]
                nib = np.stack([a & 15, a >> 4], -1).reshape(bh, bw, 16).astype(np.uint32)
                px[..., 3] = nib * 17
        img = px.reshape(bh, bw, 4, 4, 4).transpose(0, 2, 1, 3, 4).reshape(bh * 4, bw * 4, 4)
        return img[:h, :w].astype(np.uint8)
    if fmt in (3, 4):
        px = untile(data, w, h, 4, tiled)  # big-endian ARGB words
        return np.stack([px[..., 1], px[..., 2], px[..., 3], px[..., 0]], -1)
    return None


def textures(xgr, imgb):
    for name, ext, body in wpd_entries(xgr):
        if ext != 'txbh':
            continue
        g = body.find(b'GTEX')
        if g < 0:
            continue
        fmt, mips, typ = body[g + 6], body[g + 7], body[g + 9]
        w, h = struct.unpack_from('>HH', body, g + 10)
        table = struct.unpack_from('>I', body, g + 16)[0]
        off, size = struct.unpack_from('>II', body, g + table)
        yield name, fmt, typ, w, h, imgb[off:off + size]


def main():
    root, out = sys.argv[1], sys.argv[2]
    subs = sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    index = archive_index(root)
    for path in sorted(index):
        if not path.endswith('.xgr') or not any(s in path for s in subs):
            continue
        xgr = read(index, path)
        ipath = path[:-4] + '.imgb'
        imgb = read(index, ipath) if ipath in index else b''
        stem = os.path.basename(path).split('.')[0]
        for name, fmt, typ, w, h, data in textures(xgr, imgb):
            img = decode(fmt, w, h, data, tiled=typ != 0)
            tag = f'{stem}__{name}'
            if img is None:
                print(f'{tag}: format {fmt} not decoded ({w}x{h})')
                continue
            Image.fromarray(img, 'RGBA').save(os.path.join(out, tag + '.png'))
            print(f'{tag}: format {fmt} type {typ} {w}x{h}')


if __name__ == '__main__':
    main()
