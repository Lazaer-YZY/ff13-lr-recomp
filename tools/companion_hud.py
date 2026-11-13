"""The bottom screen's HUD pieces, from the player's game files (build_android.sh stage).

  python -I tools/companion_hud.py GAME_ROOT OUT_DIR

Decodes the game's system GUI atlas (gui/resident/system.x360.xgr: plate frames and gauge
housings, controller buttons, battle icons, command dial, window ornaments, clock), upscales
the pieces the bottom screen uses 4x with Real-ESRGAN when it is installed (tools/upscale_assets.py;
otherwise they are bundled at their original size) and writes OUT_DIR/<name>.png plus
OUT_DIR/hud.json ({name: scale}: 4 when upscaled, 1 when not, so crop rectangles given in the
original atlas pixels can be scaled). Derived from the game: APK assets only, never git.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PIECES = ['ws_pnl01', 'gp_parts01', 'button_set01', 'battle_parts01', 'btl_cmd_en', 'atb_uv',
          'syswin_bg01', 'sys_clk', 'sys_clk_waku', 'libra',
          # the minimap: rings, rust overlay, icon sheets (the follow map mode and its markers)
          'nvmap_waku', 'nvmap_waku2', 'nvmap_sabi', 'naviicon', 'weiss_navi_icon', 'nmapgrid',
          # the game's own number / label fonts (battle digits, squared battle font) and the logo
          'btlnum', 'btl_number01', 'FF13_font']
NO_UPSCALE = {'atb_uv'}  # a cloudy fill texture: tiled and blurred anyway
# Pieces of the game's map screens (gui/resident/shop packs): name -> (archive path substring, decoded
# file stem, scale): mapbg = the worn metal behind the in-game map (soft: 1x), wmap = the illustrated
# world map (1280x720 DXT1: upscaled, kept at 2x so it fits in memory on the phone).
MAP_SCREEN = {'mapbg': ('gui/resident/shop/shop03.', 'shop03__mapbg', 1),
              'wmap': ('gui/resident/shop/shop02.', 'shop02__wmap', 2)}


def main(root, out):
    os.makedirs(out, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix='lr_hud_')
    try:
        dec = os.path.join(tmp, 'dec')
        subprocess.run([sys.executable, '-I', os.path.join(HERE, 'lr_gui_tex.py'), root, dec,
                        'gui/resident/system.x360'], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([sys.executable, '-I', os.path.join(HERE, 'lr_gui_tex.py'), root, dec]
                       + sorted({v[0] for v in MAP_SCREEN.values()}), check=True, stdout=subprocess.DEVNULL)
        up_in = os.path.join(tmp, 'up_in')
        os.makedirs(up_in)
        scales = {}
        import upscale_assets
        from PIL import Image
        for name, (_, stem, scale) in MAP_SCREEN.items():
            src = os.path.join(dec, stem + '.png')
            if not os.path.exists(src):
                print(f'companion_hud: {stem} not found in the game files')
                continue
            if scale == 1 or not os.path.exists(upscale_assets.EXE):
                shutil.copy(src, os.path.join(out, name + '.png'))
                scales[name] = 1
                continue
            one_in, one_out = os.path.join(tmp, 'map_in'), os.path.join(tmp, 'map_out')
            os.makedirs(one_in, exist_ok=True)
            shutil.copy(src, os.path.join(one_in, name + '.png'))
            upscale_assets.run(one_in, one_out)
            im = Image.open(os.path.join(one_out, name + '.png'))
            w, h = Image.open(src).size
            im.resize((w * scale, h * scale), Image.LANCZOS).save(os.path.join(out, name + '.png'))
            scales[name] = scale
        for p in PIECES:
            src = os.path.join(dec, f'system__{p}.png')
            if not os.path.exists(src):
                print(f'companion_hud: {p} not found in the game files')
                continue
            if p in NO_UPSCALE:
                shutil.copy(src, os.path.join(out, p + '.png'))
                scales[p] = 1
            else:
                shutil.copy(src, os.path.join(up_in, p + '.png'))
        if os.path.exists(upscale_assets.EXE):
            upscale_assets.run(up_in, out)
            for n in os.listdir(up_in):
                scales[n[:-4]] = 4
        else:
            print('companion_hud: Real-ESRGAN not installed: bundling the pieces at their original size')
            for n in os.listdir(up_in):
                shutil.copy(os.path.join(up_in, n), os.path.join(out, n))
                scales[n[:-4]] = 1
        with open(os.path.join(out, 'hud.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(scales, f, indent=1, sort_keys=True)
        print(f'companion_hud: {len(scales)} pieces -> {out}')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.path.insert(0, HERE)
    main(sys.argv[1], sys.argv[2])
