#!/usr/bin/env python3
"""Generate launcher icons, round icons, adaptive-icon foreground/monochrome
layers, Play Store icon, splash screens and the start-screen logo from
design/source/app-icon.png and design/source/splash.png (spec section 4Б.3).

Run from repo root:  python3 design/make_assets.py
Result assets are committed; re-run whenever the source artwork changes.

Sizes (px), per spec:
  ic_launcher.png / ic_launcher_round.png : mdpi 48, hdpi 72, xhdpi 96,
      xxhdpi 144, xxxhdpi 192
  ic_launcher_foreground.png / ic_launcher_monochrome.png : canvas 108dp ->
      108/162/216/324/432, subject fitted into a 66dp circle ->
      66/99/132/198/264 centered on the canvas; rest transparent
  play-icon-512.png : 512x512 (NOT bundled into the APK)
  splash_icon.png : canvas 288dp -> 288/432/576/864/1152, subject fits into
      192dp circle -> 192/288/384/576/768
  ic_sikkatu_logo_large.png : width 200/300/400/600/800, height by source
      aspect (start-screen logo)
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'design', 'source')
OUT_DESIGN = os.path.join(ROOT, 'design', 'out')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')

ICON = Image.open(os.path.join(SRC, 'app-icon.png')).convert('RGB')
SPLASH = Image.open(os.path.join(SRC, 'splash.png')).convert('RGB')
print('icon source :', ICON.size)
print('splash source:', SPLASH.size)


def density_dir(name):
    d = os.path.join(RES, name)
    os.makedirs(d, exist_ok=True)
    return d


ICON_SIZES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

# 1. Square + round launcher icons
for folder, size in ICON_SIZES.items():
    sq = ICON.resize((size, size), Image.LANCZOS)
    sq_path = os.path.join(density_dir(folder), 'ic_launcher.png')
    sq.save(sq_path, 'PNG')
    print('  ', os.path.relpath(sq_path, ROOT))

    # round: mask circular
    round_img = sq.convert('RGBA')
    mask = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    round_img.putalpha(mask)
    round_path = os.path.join(density_dir(folder), 'ic_launcher_round.png')
    round_img.save(round_path, 'PNG')
    print('  ', os.path.relpath(round_path, ROOT), '(round)')

# 2. Adaptive foreground + monochrome (canvas 108dp, subject in 66dp circle)
CANVAS_DP = 108
SUBJECT_DP = 66
LIGHT_DENSITIES = [
    ('mipmap-mdpi', 1, 108, 66),
    ('mipmap-hdpi', 1.5, 162, 99),
    ('mipmap-xhdpi', 2.0, 216, 132),
    ('mipmap-xxhdpi', 3.0, 324, 198),
    ('mipmap-xxxhdpi', 4.0, 432, 264),
]

for folder, _mult, canvas_px, subject_px in LIGHT_DENSITIES:
    subj = ICON.resize((subject_px, subject_px), Image.LANCZOS)
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    off = (canvas_px - subject_px) // 2
    # circular mask on the subject so nothing sticks outside the 66dp icon mask
    mask = Image.new('L', (subject_px * 4, subject_px * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, subject_px * 4 - 1, subject_px * 4 - 1), fill=255)
    mask = mask.resize((subject_px, subject_px), Image.LANCZOS)
    subj_rgba = subj.convert('RGBA')
    subj_rgba.putalpha(mask)
    canvas.paste(subj_rgba, (off, off), subj_rgba)
    fg_path = os.path.join(density_dir(folder), 'ic_launcher_foreground.png')
    canvas.save(fg_path, 'PNG')
    print('  ', os.path.relpath(fg_path, ROOT))

    # monochrome (Android 13+): shape from the alpha of the foreground layer
    mono = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    alpha = canvas.split()[-1]
    solid = Image.new('RGBA', (canvas_px, canvas_px), (255, 255, 255, 255))
    mono = Image.composite(solid, mono, alpha)
    mono_path = os.path.join(density_dir(folder), 'ic_launcher_monochrome.png')
    mono.save(mono_path, 'PNG')
    print('  ', os.path.relpath(mono_path, ROOT), '(monochrome)')

# 3. Play Store icon 512x512 (not shipped in the APK)
os.makedirs(OUT_DESIGN, exist_ok=True)
play = ICON.resize((512, 512), Image.LANCZOS)
play_path = os.path.join(OUT_DESIGN, 'play-icon-512.png')
play.save(play_path, 'PNG')
print('  ', os.path.relpath(play_path, ROOT), '(store asset, not in APK)')

# 4. Splash icon (canvas 288dp, subject in 192dp circle)
SPLASH_DENSITIES = [
    ('drawable-mdpi', 288, 192),
    ('drawable-hdpi', 432, 288),
    ('drawable-xhdpi', 576, 384),
    ('drawable-xxhdpi', 864, 576),
    ('drawable-xxxhdpi', 1152, 768),
]
for folder, canvas_px, subject_px in SPLASH_DENSITIES:
    subj = SPLASH.resize((subject_px, subject_px), Image.LANCZOS)
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    off = (canvas_px - subject_px) // 2
    mask = Image.new('L', (subject_px * 4, subject_px * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, subject_px * 4 - 1, subject_px * 4 - 1), fill=255)
    mask = mask.resize((subject_px, subject_px), Image.LANCZOS)
    subj_rgba = subj.convert('RGBA')
    subj_rgba.putalpha(mask)
    canvas.paste(subj_rgba, (off, off), subj_rgba)
    path = os.path.join(density_dir(folder), 'splash_icon.png')
    canvas.save(path, 'PNG')
    print('  ', os.path.relpath(path, ROOT))

# 5. Start-screen logo (width 200/300/400/600/800, height by source aspect)
w, h = SPLASH.size
aspect = h / w
for folder, width in [
    ('drawable-mdpi', 200), ('drawable-hdpi', 300), ('drawable-xhdpi', 400),
    ('drawable-xxhdpi', 600), ('drawable-xxxhdpi', 800),
]:
    height = int(round(width * aspect))
    logo = SPLASH.resize((width, height), Image.LANCZOS)
    path = os.path.join(density_dir(folder), 'ic_sikkatu_logo_large.png')
    logo.save(path, 'PNG')
    print('  ', os.path.relpath(path, ROOT), f'({width}x{height})')

# stale copy of the plain (non-density) logo: removed from res/drawable by caller