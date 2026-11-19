"""Launcher icon from an image of your choice (the release APK's is the game's key art).

    python -I tools/make_icon.py IMAGE [--background white|#RRGGBB] [--height-dp 64]
    python -I tools/make_icon.py IMAGE --photo CX,CY,SIDE

Writes an adaptive icon to out/branding/res (git-ignored: the repository holds no game
artwork). android/app/build.gradle uses it when it exists, else the placeholder bolt in
res/. The launcher shows the middle 72 dp of the 108 dp icon (66 dp safe circle).

Emblem mode (default): the image's opaque part (alpha > 16), or its non-background part when
it has no transparency, is cropped, scaled to --height-dp (64 dp keeps tall shapes inside
the safe circle) and centred on the background colour.

Photo mode (--photo): a full-bleed picture. The launcher's visible 72 dp show the source
square of SIDE pixels centred on CX,CY; the hidden rim (to 108 dp, for parallax and odd mask
shapes) is the picture around it, or the background colour past the image's edge.
Needs ffmpeg on PATH.
"""
import argparse
import os
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, 'out', 'branding')
SIZE = 432  # 108 dp at xxxhdpi (4x)


def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', *args], check=True)


def rgba(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                          'stream=width,height', '-of', 'csv=p=0', path],
                         check=True, capture_output=True, text=True).stdout.strip()
    w, h = (int(v) for v in out.split(',')[:2])
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-frames:v', '1', '-f', 'rawvideo',
                          '-pix_fmt', 'rgba', '-'], check=True, capture_output=True).stdout
    if len(raw) != w * h * 4:
        sys.exit(f'make_icon: unexpected decode size for {path}')
    return w, h, raw


def bbox(w, h, raw):
    """Bounding box of the drawn part: alpha > 16, or (no transparency) pixels unlike the corner."""
    alpha = raw[3::4]
    transparent = min(alpha) < 250
    corner = raw[0:3]
    xs, ys = [], []
    for y in range(h):
        row = y * w
        for x in range(w):
            i = (row + x) * 4
            if transparent:
                if raw[i + 3] > 16:
                    xs.append(x); ys.append(y)
            elif max(abs(raw[i + k] - corner[k]) for k in range(3)) > 24:
                xs.append(x); ys.append(y)
    if not xs:
        sys.exit('make_icon: the image is empty')
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('image')
    ap.add_argument('--background', default='white', help='white, black or #RRGGBB (default white)')
    ap.add_argument('--height-dp', type=float, default=64.0)
    ap.add_argument('--photo', metavar='CX,CY,SIDE',
                    help='full-bleed picture: the visible 72 dp show the SIDE-pixel square centred on CX,CY')
    a = ap.parse_args()
    bg = {'white': '#FFFFFF', 'black': '#000000'}.get(a.background.lower(), a.background)
    if len(bg) != 7 or not bg.startswith('#'):
        sys.exit('make_icon: --background must be white, black or #RRGGBB')

    w, h, raw = rgba(a.image)
    if a.photo:
        try:
            cx, cy, side = (int(v) for v in a.photo.split(','))
        except ValueError:
            sys.exit('make_icon: --photo takes CX,CY,SIDE in source pixels')
        if side < 16 or not (0 <= cx < w and 0 <= cy < h):
            sys.exit(f'make_icon: --photo {a.photo} is outside the {w}x{h} image')
        layer = round(side * 108 / 72)  # the whole 108 dp layer, in source pixels
        p = layer  # pad wide enough that the layer never leaves the padded image
        vf = (f'format=rgba,pad=iw+{2 * p}:ih+{2 * p}:{p}:{p}:color={bg.replace("#", "0x")}FF,'
              f'crop={layer}:{layer}:{cx - layer // 2 + p}:{cy - layer // 2 + p},'
              f'scale={SIZE}:{SIZE}:flags=lanczos')
        what = f'photo {side}px square at {cx},{cy} (layer {layer}px)'
    else:
        x, y, bw, bh = bbox(w, h, raw)
        # Fit the box into height_dp tall and at most 64 dp wide, never past the icon.
        scale = min(a.height_dp * SIZE / 108 / bh, 64 * SIZE / 108 / bw)
        tw, th = max(1, round(bw * scale)), max(1, round(bh * scale))
        vf = (f'format=rgba,crop={bw}:{bh}:{x}:{y},scale={tw}:{th}:flags=lanczos,'
              f'pad={SIZE}:{SIZE}:(ow-iw)/2:(oh-ih)/2:color=0x00000000')
        what = f'box {bw}x{bh} at {x},{y} -> {tw}x{th} px'

    res = os.path.join(OUT, 'res')
    shutil.rmtree(res, ignore_errors=True)
    for d in ('drawable-xxxhdpi', 'mipmap-anydpi-v26', 'values'):
        os.makedirs(os.path.join(res, d))
    src_ext = os.path.splitext(a.image)[1].lower()
    for old in os.listdir(OUT):  # one source image, whatever its extension
        if old.startswith('source.') and old != 'source' + src_ext:
            os.remove(os.path.join(OUT, old))
    if os.path.abspath(a.image) != os.path.abspath(os.path.join(OUT, 'source' + src_ext)):
        shutil.copyfile(a.image, os.path.join(OUT, 'source' + src_ext))
    fg = os.path.join(res, 'drawable-xxxhdpi', 'ic_launcher_lr_foreground.png')
    ffmpeg('-i', a.image, '-frames:v', '1', '-vf', vf, fg)
    with open(os.path.join(res, 'values', 'ic_launcher_lr.xml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- tools/make_icon.py -->\n<resources>\n'
                f'    <color name="ic_launcher_lr_background">{bg}</color>\n</resources>\n')
    with open(os.path.join(res, 'mipmap-anydpi-v26', 'ic_launcher_lr.xml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- tools/make_icon.py -->\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_lr_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_lr_foreground" />\n'
                '</adaptive-icon>\n')
    # A round preview (the launcher's 72 dp circle) next to the resources.
    prev = os.path.join(OUT, 'preview.png')
    r = SIZE // 3
    ffmpeg('-f', 'lavfi', '-i', f'color=c={bg.replace("#", "0x")}:s={SIZE}x{SIZE}', '-i', fg, '-filter_complex',
           f"[0][1]overlay=format=auto,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':"
           f"a='if(lte(hypot(X-{SIZE // 2},Y-{SIZE // 2}),{r}),255,0)',crop={2 * r}:{2 * r}:{SIZE // 2 - r}:{SIZE // 2 - r}",
           '-frames:v', '1', prev)
    print(f'make_icon: {a.image} {what} on {bg}; {res} (preview {prev})')


if __name__ == '__main__':
    main()
