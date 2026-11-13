"""Flicker analysis of a burst of raw Android screencaps (header: width, height, format[, colorspace]).

For each frame: the share of sampled pixels that differ by more than 40 (max channel) from the
per-pixel median of the burst. Frames far above the rest are flicker candidates; their diff
regions are reported on a coarse 8x4 grid. Writes a downscaled PNG of the worst frame next to
the median frame.
Usage: python -I flicker_analyze.py DIR LABEL
"""
import glob
import os
import struct
import sys
import zlib

STEP = 6  # sample every 6th pixel in x and y


def load_raw(path):
    data = open(path, 'rb').read()
    w, h, fmt = struct.unpack('<III', data[:12])
    # Android 10+: 16-byte header (with colour space); older: 12.
    header = 16 if len(data) >= 16 + w * h * 4 else 12
    px = data[header:header + w * h * 4]
    return w, h, px


def main():
    d, label = sys.argv[1], sys.argv[2]
    files = sorted(glob.glob(os.path.join(d, 'f*.raw')), key=lambda p: int(os.path.basename(p)[1:-4]))
    frames = []
    for f in files:
        w, h, px = load_raw(f)
        samples = []
        for y in range(0, h, STEP):
            row = y * w * 4
            for x in range(0, w, STEP):
                i = row + x * 4
                samples.append((px[i], px[i + 1], px[i + 2]))
        frames.append(samples)
    n = len(frames)
    if n < 3:
        print(f'[{label}] only {n} frames')
        return
    gw, gh = (w + STEP - 1) // STEP, (h + STEP - 1) // STEP
    count = len(frames[0])
    median = []
    for i in range(count):
        cs = [fr[i] for fr in frames]
        median.append(tuple(sorted(c[k] for c in cs)[n // 2] for k in range(3)))
    scores = []
    for fi, fr in enumerate(frames):
        diff = 0
        grid = [[0] * 8 for _ in range(4)]
        for i in range(count):
            a, m = fr[i], median[i]
            if max(abs(a[0] - m[0]), abs(a[1] - m[1]), abs(a[2] - m[2])) > 40:
                diff += 1
                gx, gy = (i % gw) * 8 // gw, (i // gw) * 4 // gh
                grid[gy][gx] += 1
        scores.append((diff / count * 100.0, fi, grid))
    pcts = sorted(s[0] for s in scores)
    base = pcts[n // 2]
    print(f'[{label}] {n} frames {w}x{h}: per-frame % of pixels off the median: '
          + ' '.join(f'{s[0]:.1f}' for s in scores))
    outliers = [s for s in scores if s[0] > max(3.0, base * 3 + 1.0)]
    print(f'[{label}] median frame {base:.1f}%, flicker candidates: {len(outliers)}')
    for pct, fi, grid in sorted(outliers, reverse=True)[:3]:
        print(f'[{label}]   frame f{fi + 1}: {pct:.1f}%; regions (8x4 grid, % of cell):')
        cell = count / 32
        for row in grid:
            print('      ' + ' '.join(f'{100 * c / cell:3.0f}' for c in row))
    # Worst frame next to the median, downscaled.
    worst = max(scores)[1]
    rows = []
    for y in range(gh):
        line = bytearray()
        for src in (frames[worst], median):
            for x in range(gw):
                line += bytes(src[y * gw + x])
        rows.append(bytes(line))
    raw = b''.join(b'\x00' + r for r in rows)
    chunk = lambda t, b: struct.pack('>I', len(b)) + t + b + struct.pack('>I', zlib.crc32(t + b) & 0xffffffff)
    out = os.path.join(d, f'worst_vs_median.png')
    open(out, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', gw * 2, gh, 8, 2, 0, 0, 0))
                          + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
    print(f'[{label}] worst frame f{worst + 1} (left) vs median (right): {out}')


if __name__ == '__main__':
    main()
