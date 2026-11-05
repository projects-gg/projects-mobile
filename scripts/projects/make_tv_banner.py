"""Renders the Android TV banner (320 x 180 px, xhdpi) from the app's own icon and fonts.

Usage (from the repo root, Pillow installed):
  python scripts/projects/make_tv_banner.py app_pojavlauncher/src/main/res
      app_pojavlauncher/src/main/res/drawable-xhdpi/projects_tv_banner.png 1
  and the same with drawable-xxxhdpi and scale 2 (4K TVs).
The banner is the launcher's look: obsidian plate, the gold dragon mark, "Projects" in Tektur and
"Launcher" in Figtree. No glow, no gradient text, no decoration.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

res, out = sys.argv[1], sys.argv[2]
scale = int(sys.argv[3]) if len(sys.argv) > 3 else 1
W, H = 320 * scale, 180 * scale
S = 4  # supersampling
w, h = W * S, H * S

OBSIDIAN_950 = (0x0A, 0x09, 0x0C)
OBSIDIAN_850 = (0x16, 0x13, 0x1A)
BONE_50 = (0xFB, 0xF6, 0xEC)
ASH_400 = (0x9B, 0x93, 0xA3)

img = Image.new("RGB", (w, h), OBSIDIAN_950)
d = ImageDraw.Draw(img)
# A quiet vertical shade, obsidian 850 at the top to 950 at the bottom (no glow).
for y in range(h):
    t = y / (h - 1)
    c = tuple(round(OBSIDIAN_850[i] * (1 - t) + OBSIDIAN_950[i] * t) for i in range(3))
    d.line([(0, y), (w, y)], fill=c)

# The dragon mark, from the adaptive icon's foreground (its 66 % safe zone holds the art).
fg = Image.open(res + "/mipmap-xxxhdpi/ic_launcher_foreground.webp").convert("RGBA")
fw, fh = fg.size
crop = round(fw * 0.19)
fg = fg.crop((crop, crop, fw - crop, fh - crop))
mark = round(h * 0.58)
fg = fg.resize((mark, mark), Image.LANCZOS)
mx = round(w * 0.07)
my = (h - mark) // 2
img.paste(fg, (mx, my), fg)

tektur = ImageFont.truetype(res + "/font/tektur_semibold.ttf", round(h * 0.19))
figtree = ImageFont.truetype(res + "/font/figtree_semibold.ttf", round(h * 0.10))
tx = mx + mark + round(w * 0.045)
title = "Projects"
sub = "Launcher"
tb = d.textbbox((0, 0), title, font=tektur)
assert tx + tb[2] <= w - round(w * 0.06), "title runs past the edge"
sb = d.textbbox((0, 0), sub, font=figtree)
gap = round(h * 0.035)
block = (tb[3] - tb[1]) + gap + (sb[3] - sb[1])
ty = (h - block) // 2 - tb[1]
d.text((tx, ty), title, font=tektur, fill=BONE_50)
sy = (h - block) // 2 + (tb[3] - tb[1]) + gap - sb[1]
d.text((tx + round(w * 0.004), sy), sub, font=figtree, fill=ASH_400)
img = img.resize((W, H), Image.LANCZOS)
img.save(out, optimize=True)
print(out, img.size)
