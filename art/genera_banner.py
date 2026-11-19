"""Genera il banner Android TV (320x180 px, xhdpi) di Manager TV.

A sinistra il logo dell'app (art/icona.png, con gli angoli fuori dalla piastra resi trasparenti
da art/genera_icone.py), a destra il nome e una breve descrizione, sul gradiente blu notte del
tema dell'app. Disegno a 4x e riduco con LANCZOS per avere bordi antialiasati.

Uso (dalla radice del progetto, dopo art/genera_icone.py):
    python3 -I art/genera_banner.py app-tv/src/main/res/drawable-xhdpi/tv_banner.png
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO = os.path.join(ROOT, "common", "src", "main", "res", "drawable-nodpi", "logo_manager_tv.png")

OUT = sys.argv[1]
S = 4  # fattore di supersampling
W, H = 320 * S, 180 * S

BG_TOP = (0x1F, 0x4C, 0x7A)
BG_BOTTOM = (0x0C, 0x1A, 0x2B)
ACCENT = (0x4F, 0xC3, 0xF7)
WHITE = (0xFF, 0xFF, 0xFF)
SECONDARY = (0xB8, 0xC6, 0xD8)

FONT_BOLD = "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf"

img = Image.new("RGBA", (W, H))
draw = ImageDraw.Draw(img)

# Gradiente verticale come il fondo del tema.
for y in range(H):
    t = y / (H - 1)
    color = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
    draw.line([(0, y), (W, y)], fill=color)

# Logo a sinistra, centrato in verticale.
logo_size = 116 * S
logo = Image.open(LOGO).convert("RGBA").resize((logo_size, logo_size), Image.LANCZOS)
lx, ly = 16 * S, (H - logo_size) // 2
img.alpha_composite(logo, (lx, ly))

# Nome e descrizione a destra del logo.
tx = lx + logo_size + 14 * S
title_font = ImageFont.truetype(FONT_BOLD, 24 * S)
sub_font = ImageFont.truetype(FONT_REGULAR, 12 * S)
title_1, title_2 = "Manager ", "TV"
draw.text((tx, 64 * S), title_1, font=title_font, fill=WHITE)
w1 = draw.textlength(title_1, font=title_font)
draw.text((tx + w1, 64 * S), title_2, font=title_font, fill=ACCENT)
draw.text((tx + 1 * S, 98 * S), "File e video della TV", font=sub_font, fill=SECONDARY)

img.convert("RGB").resize((320, 180), Image.LANCZOS).save(OUT, optimize=True)
print("Banner scritto in", OUT)
