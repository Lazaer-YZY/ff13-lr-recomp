"""Upscales decoded game textures 4x for the bottom screen (Real-ESRGAN, ncnn/Vulkan build).

  out/venv/Scripts/python.exe -I tools/upscale_assets.py IN_DIR OUT_DIR [MODEL]

Every IN_DIR/*.png is upscaled 4x. Real-ESRGAN works on colour only, so the alpha channel is
upscaled as its own greyscale image and merged back (colour under fully transparent pixels is
filled from its neighbours first, so edges don't pick up a dark fringe). MODEL defaults to
realesr-animevideov3-x4 plus a light unsharp mask (clean shapes, no painted texture). The tool lives in C:/dev/realesrgan
(realesrgan-ncnn-vulkan-20220424-windows.zip from github.com/xinntao/Real-ESRGAN v0.2.5.0,
45,474,481 bytes, sha256 abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d).
Output is derived from the game files: keep it out of git (APK assets, out/).
"""
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageFilter

EXE = os.environ.get('LR_REALESRGAN', 'C:/dev/realesrgan/realesrgan-ncnn-vulkan.exe')
# the owner, 2026-11-11: x4plus-anime looked painterly ("paintbrush"); animevideov3 keeps the shapes
# clean, a light unsharp mask restores the crispness (out/dual/cmp/compare_hud2.png).
DEFAULT_MODEL = 'realesr-animevideov3-x4'
SHARPEN = ImageFilter.UnsharpMask(radius=2, percent=80, threshold=2)


def bleed(rgba):
    """Fill the colour of transparent pixels from nearby opaque ones (alpha-weighted box blurs)."""
    a = np.asarray(rgba, dtype=np.float32)
    rgb, w = a[..., :3], (a[..., 3:4] > 8).astype(np.float32)
    out = rgb * w
    have = w.copy()
    for r in (1, 2, 4, 8, 16, 32):
        k = 2 * r + 1
        pad = lambda x: np.pad(x, ((r, r), (r, r), (0, 0)), mode='edge')
        def box(x):
            c = pad(x).cumsum(0).cumsum(1)
            c = np.pad(c, ((1, 0), (1, 0), (0, 0)))
            return c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]
        num, den = box(out * have), box(have)
        fill = (have == 0) & (den > 0)
        out = np.where(fill, num / np.maximum(den, 1e-6), out)
        have = np.maximum(have, (den > 0).astype(np.float32))
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGB')


def mismatch(src, up):
    """Mean difference between the original and the upscale brought back to its size (alpha and the
    colour of opaque pixels), 0-255: a sound upscale stays within a few units."""
    back = np.asarray(up.resize(src.size, Image.LANCZOS), np.float32)
    s = np.asarray(src, np.float32)
    da = np.abs(back[..., 3] - s[..., 3]).mean()
    opaque = s[..., 3] > 200
    dc = np.abs(back[..., :3] - s[..., :3])[opaque].mean() if opaque.any() else 0.0
    return float(max(da, dc))


def run(src_dir, dst_dir, model=DEFAULT_MODEL, sharpen=True):
    if not os.path.exists(EXE):
        sys.exit(f'upscale_assets: {EXE} not found (see the docstring)')
    os.makedirs(dst_dir, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix='lr_up_')
    try:
        tin, tout = os.path.join(tmp, 'in'), os.path.join(tmp, 'out')
        os.makedirs(tin)
        os.makedirs(tout)
        names = sorted(n for n in os.listdir(src_dir) if n.lower().endswith('.png'))
        for n in names:
            im = Image.open(os.path.join(src_dir, n)).convert('RGBA')
            stem = n[:-4]
            bleed(im).save(os.path.join(tin, stem + '__rgb.png'))
            Image.merge('RGB', [im.getchannel('A')] * 3).save(os.path.join(tin, stem + '__a.png'))
        # One image at a time (-j 1:1:1): batches processed concurrently came out corrupted on the
        # Intel Arc (transparency lost over most of an atlas, speckled colours), 2026-11-11.
        subprocess.run([EXE, '-i', tin, '-o', tout, '-n', model, '-s', '4', '-f', 'png', '-j', '1:1:1'], check=True)
        for n in names:
            stem = n[:-4]
            src = Image.open(os.path.join(src_dir, n)).convert('RGBA')
            out = None
            for attempt in range(2):
                if attempt:  # redo this image alone
                    for part in ('__rgb', '__a'):
                        one = os.path.join(tmp, 'one')
                        shutil.rmtree(one, ignore_errors=True)
                        os.makedirs(one)
                        shutil.copy(os.path.join(tin, stem + part + '.png'), one)
                        subprocess.run([EXE, '-i', one, '-o', tout, '-n', model, '-s', '4', '-f', 'png', '-j', '1:1:1'],
                                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                rgb = Image.open(os.path.join(tout, stem + '__rgb.png')).convert('RGB')
                a = Image.open(os.path.join(tout, stem + '__a.png')).convert('L')
                plain = rgb.copy()
                plain.putalpha(a)
                err = mismatch(src, plain)  # measured before sharpening
                if err < 16:
                    out = rgb.filter(SHARPEN) if sharpen else rgb
                    out = out.copy()
                    out.putalpha(a)
                    break
                print(f'upscale_assets: {n}: upscale looks wrong (error {err:.1f}), ' + ('retrying' if not attempt else 'using smooth scaling'))
            if out is None:
                out = src.resize((src.size[0] * 4, src.size[1] * 4), Image.LANCZOS)
            out.save(os.path.join(dst_dir, n))
            print(f'{n}: {out.size[0]}x{out.size[1]}')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    run(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else DEFAULT_MODEL)
