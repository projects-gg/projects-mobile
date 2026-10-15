"""Generates the Projects Launcher (Android) icon, splash and brand bitmaps.

Usage: python -I make_brand_assets.py <brand_dir> <res_dir> <playstore_png>
  brand_dir     folder with mascot_512x512.png and app-icon-1024.png (ProLauncher2/assets/brand)
  res_dir       app_pojavlauncher/src/main/res of the fork
  playstore_png path of the 512 px Play Store icon to write

Adaptive icon geometry: 108 dp canvas, 66 dp safe circle. The mascot (a round emblem) is drawn
at 64 dp so the whole ring stays inside the safe zone on every launcher mask.
"""
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
OBSIDIAN = (10, 9, 12)
EMBER_GLOW = (74, 44, 18)


def radial_background(size):
    """Obsidian with a warm, low-contrast glow in the middle (matches app-icon-1024)."""
    img = Image.new("RGB", (size, size), OBSIDIAN)
    px = img.load()
    c = (size - 1) / 2.0
    radius = size * 0.62
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / radius
            t = max(0.0, 1.0 - d) ** 1.6
            px[x, y] = tuple(int(OBSIDIAN[i] + (EMBER_GLOW[i] - OBSIDIAN[i]) * t) for i in range(3))
    return img


def fit_mascot(mascot, box):
    m = mascot.copy()
    m.thumbnail((box, box), Image.LANCZOS)
    return m


def centered(canvas_size, img, mode="RGBA"):
    canvas = Image.new(mode, (canvas_size, canvas_size), (0, 0, 0, 0))
    off = ((canvas_size - img.width) // 2, (canvas_size - img.height) // 2)
    canvas.paste(img, off, img)
    return canvas


def monochrome(mascot):
    """Bright (gold) parts of the emblem as an opaque white mask, dark outlines as gaps."""
    rgba = mascot.convert("RGBA")
    lum = rgba.convert("L")
    bright = lum.point(lambda v: 255 if v >= 92 else 0)
    alpha = ImageChops.multiply(bright, rgba.split()[3])
    white = Image.new("RGBA", rgba.size, (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def save_webp(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "WEBP", lossless=True, quality=100, method=6)


def circle_mask(size):
    scale = 4
    big = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * scale - 1, size * scale - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def main():
    brand_dir, res_dir, playstore_png = sys.argv[1:4]
    mascot = Image.open(os.path.join(brand_dir, "mascot_512x512.png")).convert("RGBA")
    app_icon = Image.open(os.path.join(brand_dir, "app-icon-1024.png")).convert("RGBA")
    mono = monochrome(mascot)

    for name, scale in DENSITIES.items():
        canvas = round(108 * scale)
        emblem = round(64 * scale)
        mip = os.path.join(res_dir, "mipmap-" + name)
        # Adaptive layers.
        save_webp(centered(canvas, fit_mascot(mascot, emblem)), os.path.join(mip, "ic_launcher_foreground.webp"))
        save_webp(radial_background(canvas), os.path.join(mip, "ic_launcher_background.webp"))
        save_webp(centered(canvas, fit_mascot(mono, emblem)), os.path.join(mip, "ic_launcher_monochrome.webp"))
        # Legacy icons (API 21-25): 48 dp.
        legacy = round(48 * scale)
        save_webp(app_icon.resize((legacy, legacy), Image.LANCZOS), os.path.join(mip, "ic_launcher.webp"))
        round_bg = radial_background(legacy).convert("RGBA")
        round_bg.putalpha(circle_mask(legacy))
        round_bg.alpha_composite(centered(legacy, fit_mascot(mascot, round(legacy * 0.78))))
        save_webp(round_bg, os.path.join(mip, "ic_launcher_round.webp"))

    drawable = os.path.join(res_dir, "drawable")
    nodpi = os.path.join(res_dir, "drawable-nodpi")
    # Emblem at 128 dp per density (pre-Android 12 splash bitmap and the in-app header).
    for name, scale in DENSITIES.items():
        save_webp(fit_mascot(mascot, round(128 * scale)), os.path.join(res_dir, "drawable-" + name, "projects_emblem.webp"))
    # Profile icon for the "Projects" profile (square, like other profile icons).
    save_webp(fit_mascot(mascot, 256), os.path.join(nodpi, "ic_projects_profile.webp"))
    # Status bar icon: white silhouette.
    notif = centered(184, fit_mascot(mono, 168))
    notif.save(os.path.join(drawable, "notif_icon.png"), "PNG", optimize=True)
    # Unused upstream bitmap kept by name; replaced so no Amethyst art ships.
    save_webp(centered(512, fit_mascot(mono, 320)), os.path.join(drawable, "ic_launcher_monochrome.webp"))

    store = radial_background(512).convert("RGBA")
    store.alpha_composite(centered(512, fit_mascot(mascot, 380)))
    store.save(playstore_png, "PNG", optimize=True)
    print("ok")


if __name__ == "__main__":
    main()
