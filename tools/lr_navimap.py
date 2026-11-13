"""Builds Lightning Returns' area maps ("navimaps") from the game files, for the bottom screen.

The map art is static (docs/dual_screen_map_re.md):
  - scene/lay/scene00100/bin/scene00100.x360.xwp (zone z0100 archive) holds navimap.geb, a GENP
    tree listing 29 navimaps: {id (1..29), origin (world X, Z), 512, rows: [{first tile index,
    x positions}]}. Each row is 512 px tall; a tile's x is value * 128 px.
  - scene/lay/scene00100/bin/scene00100_<id %02x>_split_<tile %02x>.x360.imgb are the tiles:
    512x512 DXT1, Xenos-tiled, 16-bit swapped. Land is the green channel; blue/orange/magenta
    pixels mark gates and crossings.
  - GuiNaviMap scales world deltas by 2.0 (0x820F7BB8): 2 px per world unit. Which image axis is
    +Z, and the exact origin pixel, must be checked on the device (player position vs map).

  python -I tools/lr_navimap.py GAME_ROOT OUT_DIR
writes OUT_DIR/navimap_<id>.png (RGBA, recoloured: land, edges, markers on transparency) and
OUT_DIR/navimaps.json (id, origin, size in px). Needs numpy and Pillow (out/venv).
"""
import json
import os
import re
import struct
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lr_gui_tex as tex  # noqa: E402
import lr_shader_scan as scan  # noqa: E402

SCENE = 'scene/lay/scene00100/bin/'


def genp(data):
    """GENP tree: 'GENP', version byte, then tagged values. Keys are 32-bit name hashes."""
    p = 5
    sizes = {0x50: 1, 0x74: 1, 0x83: 1, 0x84: 1, 0x8a: 2, 0xba: 1, 0xdf: 1}

    def val():
        nonlocal p
        t = data[p]
        p += 1
        if t < 0x50:
            return t
        if t == 0x90:
            p += 4
            return struct.unpack_from('>f', data, p - 4)[0]
        if t == 0x8a:
            p += 2
            return struct.unpack_from('>H', data, p - 2)[0]
        if t in sizes:
            p += sizes[t]
            return data[p - sizes[t]]
        if 0xd0 <= t < 0xdf:
            m = {}
            for _ in range(t & 15):
                if data[p] != 0x87:
                    raise ValueError(f'GENP: key expected at {p:#x}')
                k = data[p + 1:p + 5].hex()
                p += 5
                m[k] = val()
            return m
        if 0xc0 <= t <= 0xcf:
            return [val() for _ in range(t & 15)]
        if t == 0xa7:
            n = data[p]
            p += 1
            return [val() for _ in range(n)]
        if t == 0xb7:
            n = data[p] & 15
            p += 1
            p += n
            return list(data[p - n:p])
        raise ValueError(f'GENP: tag {t:#x} at {p - 1:#x}')

    return val()


def navimaps(root_tree):
    out = []
    for r in root_tree['e688ed50']:
        rows = [(row['8da7fd74'], row['accec0b7']) for row in r['0a8bf4a1']]
        out.append({'id': r['bf396750'], 'origin': [round(v, 3) for v in r['590acad0']],
                    'tile': r['f7c0246a'], 'rows': rows})
    return out


def zone_index(root):
    """System archive entries plus the z0100 zone archive (its file list is in the system archive)."""
    wd = os.path.join(root, 'weiss_data')
    sysidx = tex.archive_index(root)
    lst = tex.read(sysidx, 'zone/filelist_z0100u.x360.bin')
    img = os.path.join(wd, 'zone/white_z0100u_img.x360.bin')
    idx = dict(sysidx)  # scene00100.x360.xwp is in the system archive (white_img2u)
    idx.update({p: (img, sec, us, cs) for sec, us, cs, p in scan.parse_filelist(lst)})
    return idx


def colourise(rgb):
    """Game tiles -> RGBA in the bottom screen's palette (land teal-grey, edges light, markers kept)."""
    g = rgb[..., 1].astype(np.float32)
    marker = (rgb[..., 0] > 40) | (rgb[..., 2] > 40)
    out = np.zeros(rgb.shape[:2] + (4,), np.uint8)
    land = g > 8
    t = np.clip(g / 255.0, 0, 1)[..., None]
    fill = np.array([28, 46, 52], np.float32)      # land
    edge = np.array([150, 190, 196], np.float32)   # bright lines (edges, roads)
    col = fill + (edge - fill) * np.clip((t - 0.55) / 0.45, 0, 1)
    out[land, :3] = col[land].astype(np.uint8)
    out[land, 3] = 255
    out[marker, :3] = rgb[marker]
    out[marker, 3] = 255
    return out


TILE = 256  # 2x tiles: TILE original map pixels -> 2*TILE pixels
PAD = 16    # context around each tile for the upscaler (no seams), cropped away afterwards

# Map look (the owner, 2026-11-11, variant C): one flat land colour, thin flat outlines, soft only at the
# boundaries. The source green channel has two real levels (land ~128-160, lines ~240-255); the
# in-between values (DXT1 blocks, texture) are what looked "brushed" once upscaled.
FILL = np.array([28, 46, 52], np.float32)
LINE = np.array([150, 190, 196], np.float32)
LAND_LO, LAND_HI = 60.0, 90.0
LINE_LO, LINE_HI = 215.0, 235.0


def _smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def flat(g):
    """Float field (any size) -> RGBA: flat land, flat outlines."""
    land = _smoothstep(LAND_LO, LAND_HI, g)
    line = _smoothstep(LINE_LO, LINE_HI, g)
    out = np.zeros(g.shape + (4,), np.uint8)
    out[..., :3] = (FILL + (LINE - FILL) * line[..., None]).astype(np.uint8)
    out[..., 3] = (land * 255).astype(np.uint8)
    return out


def markers_rgba(rgb):
    """The game's coloured marks (gates, crossings) on transparency."""
    m = (rgb[..., 0] > 40) | (rgb[..., 2] > 40)
    out = np.zeros(rgb.shape[:2] + (4,), np.uint8)
    out[m, :3] = rgb[m]
    out[m, 3] = 255
    return out


def compose(base, marks):
    b = Image.fromarray(base, 'RGBA')
    b.alpha_composite(Image.fromarray(marks, 'RGBA'))
    return b


def flat_1x(rgb, ss=4):
    """The overview map at 1x: the field supersampled, made flat, averaged back (anti-aliased)."""
    g = Image.fromarray(rgb[..., 1])
    big = flat(np.asarray(g.resize((g.size[0] * ss, g.size[1] * ss), Image.LANCZOS), np.float32))
    h, w = rgb.shape[:2]
    f = big.astype(np.float32)
    a = f[..., 3:4] / 255.0
    pre = np.concatenate([f[..., :3] * a, f[..., 3:4]], -1).reshape(h, ss, w, ss, 4).mean(axis=(1, 3))
    alpha = pre[..., 3:4]
    col = np.where(alpha > 0, pre[..., :3] / np.maximum(alpha / 255.0, 1e-6), 0)
    out = np.concatenate([np.clip(col, 0, 255), alpha], -1).astype(np.uint8)
    return np.asarray(compose(out, markers_rgba(rgb)))


def upscale_2x(out_dir, raws):
    """Follow mode shows the maps at ~2x: each tile's field is upscaled 4x with Real-ESRGAN (the AI
    only shapes the edges), reduced to 2x and made flat; written as navimap_NN_2x/<row>_<col>.png."""
    import shutil
    import tempfile
    import upscale_assets
    if not os.path.exists(upscale_assets.EXE):
        print('lr_navimap: Real-ESRGAN not installed: no 2x map tiles')
        return False
    tmp = tempfile.mkdtemp(prefix='lr_navi_')
    try:
        tin, tout = os.path.join(tmp, 'in'), os.path.join(tmp, 'out')
        os.makedirs(tin)
        src = {}
        for mid, rgb in raws:
            h, w = rgb.shape[:2]
            for r in range(0, h, TILE):
                for c in range(0, w, TILE):
                    if rgb[r:r + TILE, c:c + TILE, 1].max() <= 8:
                        continue
                    y0, x0 = max(0, r - PAD), max(0, c - PAD)
                    y1, x1 = min(h, r + TILE + PAD), min(w, c + TILE + PAD)
                    name = f'{mid:02d}_{r // TILE}_{c // TILE}_{r - y0}_{c - x0}'
                    g = rgb[y0:y1, x0:x1, 1]
                    Image.fromarray(np.stack([g] * 3, -1)).save(os.path.join(tin, name + '.png'))
                    src[name] = rgb[y0:y1, x0:x1]
        upscale_assets.run(tin, tout, sharpen=False)
        for n in os.listdir(tout):
            stem = n[:-4]
            mid, row, col, oy, ox = (int(v) for v in stem.split('_'))
            raw = src[stem]
            g = Image.open(os.path.join(tout, n)).convert('L')
            g = np.asarray(g.resize((raw.shape[1] * 2, raw.shape[0] * 2), Image.LANCZOS), np.float32)
            marks = np.asarray(Image.fromarray(markers_rgba(raw), 'RGBA').resize((raw.shape[1] * 2, raw.shape[0] * 2), Image.LANCZOS))
            im = compose(flat(g), marks)
            d = os.path.join(out_dir, f'navimap_{mid:02d}_2x')
            os.makedirs(d, exist_ok=True)
            im.crop((ox * 2, oy * 2, ox * 2 + 2 * TILE, oy * 2 + 2 * TILE)).save(os.path.join(d, f'{row}_{col}.png'))
        return True
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def build(root, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    idx = zone_index(root)
    xwp = tex.read(idx, SCENE + 'scene00100.x360.xwp')
    geb = next(body for name, ext, body in tex.wpd_entries(xwp) if name == 'navimap' and ext == 'geb')
    maps = navimaps(genp(geb))
    meta = []
    canvases = []
    for m in maps:
        cells = [(ri, v, first + j) for ri, (first, xs) in enumerate(m['rows']) for j, v in enumerate(xs)]
        if not cells:
            continue
        w = max(v for _, v, _ in cells) * 128 + 512
        h = len(m['rows']) * 512
        raw = np.zeros((h, w, 3), np.uint8)
        for ri, v, t in cells:
            path = SCENE + f"scene00100_{m['id']:02x}_split_{t:02x}.x360.imgb"
            if path not in idx:
                continue
            rgb = tex.decode(24, 512, 512, tex.read(idx, path), True)[..., :3]
            raw[ri * 512:(ri + 1) * 512, v * 128:v * 128 + 512] = rgb
        Image.fromarray(flat_1x(raw)).save(os.path.join(out_dir, f"navimap_{m['id']:02d}.png"))
        canvases.append((m['id'], raw))
        meta.append({'id': m['id'], 'origin': m['origin'], 'width_px': w, 'height_px': h,
                     'px_per_unit': 2.0, 'tiles': len(cells)})
        print(f"navimap {m['id']:2d}: {w}x{h} px, {len(cells)} tiles, origin {m['origin']}")
    if upscale_2x(out_dir, canvases):
        for e in meta:
            e['tile_2x'] = TILE
    with open(os.path.join(out_dir, 'navimaps.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(meta, f, indent=1)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    build(sys.argv[1], sys.argv[2])
