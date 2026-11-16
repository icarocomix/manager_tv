"""Genera il banner Android TV (320x180 px, xhdpi) di IcaroManagerTV.

Il simbolo riproduce l'icona vettoriale dell'app (televisore, cartella ambra, play) sullo stesso
gradiente blu notte, affiancato dal nome dell'app e dall'autore. Disegno a 4x e riduco con
LANCZOS per avere bordi antialiasati.

Uso (dalla radice del progetto):
    python3 -I art/genera_banner.py app-tv/src/main/res/drawable-xhdpi/tv_banner.png
"""
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
S = 4  # fattore di supersampling
W, H = 320 * S, 180 * S

BG_TOP = (0x1F, 0x4C, 0x7A)
BG_BOTTOM = (0x0C, 0x1A, 0x2B)
ACCENT = (0xF2, 0xB8, 0x4B)
WHITE = (0xFF, 0xFF, 0xFF)
PLAY = (0x16, 0x30, 0x4D)
SECONDARY = (0xB8, 0xC6, 0xD8)

FONT_BOLD = "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf"

img = Image.new("RGB", (W, H))
draw = ImageDraw.Draw(img)

# Gradiente verticale come il fondo dell'icona adattiva.
for y in range(H):
    t = y / (H - 1)
    color = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
    draw.line([(0, y), (W, y)], fill=color)

# Simbolo: coordinate della griglia 108 dp dell'icona, scalate e posizionate a sinistra.
scale = 1.2 * S
ox, oy = 2 * S, (H - 108 * scale) / 2


def p(x, y):
    return (ox + x * scale, oy + y * scale)


stroke = int(4 * scale)
# Cornice del televisore (rettangolo arrotondato 26..82 x 30..72, raggio 5).
draw.rounded_rectangle([p(26, 30), p(82, 72)], radius=int(5 * scale), outline=WHITE, width=stroke)
# Piedistallo.
draw.line([p(54, 72), p(54, 78)], fill=WHITE, width=stroke)
draw.line([p(44, 79), p(64, 79)], fill=WHITE, width=stroke)
r = stroke / 2
for x in (44, 64):
    cx, cy = p(x, 79)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)
# Cartella: linguetta + corpo.
draw.polygon([p(37, 41), p(37, 39), p(48, 39), p(51, 42), p(71, 42), p(71, 64), p(37, 64)], fill=ACCENT)
draw.rounded_rectangle([p(37, 42), p(71, 64)], radius=int(2 * scale), fill=ACCENT)
# Play.
draw.polygon([p(49, 46.5), p(49, 58.5), p(59, 52.5)], fill=PLAY)

# Testi a destra del simbolo.
tx = int(ox + 88 * scale)
title_font = ImageFont.truetype(FONT_BOLD, 21 * S)
sub_font = ImageFont.truetype(FONT_REGULAR, 13 * S)
title_1, title_2 = "IcaroManager", "TV"
draw.text((tx, 62 * S), title_1, font=title_font, fill=WHITE)
w1 = draw.textlength(title_1, font=title_font)
draw.text((tx + w1, 62 * S), title_2, font=title_font, fill=ACCENT)
draw.text((tx + 1 * S, 94 * S), "di IcaroComix", font=sub_font, fill=SECONDARY)

img.resize((320, 180), Image.LANCZOS).save(OUT, optimize=True)
print("Banner scritto in", OUT)
