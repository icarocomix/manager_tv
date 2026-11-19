"""Genera le risorse grafiche di Manager TV a partire da art/icona.png.

La sorgente è un'icona già composta: telecomando con onde Wi-Fi su una piastra bianca
arrotondata, con un fondo grigio-azzurro chiaro negli angoli. Ne ricavo:

- il primo piano dell'icona adattiva (drawable-<densità>/ic_launcher_art.png, 108 dp): solo il
  disegno, scontornato dal bianco ("color to alpha": ogni pixel diventa il colore che, sopra il
  bianco, darebbe quello originale), così bagliori e ombre restano semitrasparenti; il fondo
  bianco dell'icona adattiva ricostruisce la piastra e la maschera del launcher la ritaglia;
- il livello monocromatico per le icone a tema di Android 13+ (ic_launcher_art_mono.png): la
  stessa sagoma in bianco, dove contano solo le trasparenze;
- il logo per l'interfaccia e i crediti (drawable-nodpi/logo_manager_tv.png): l'icona originale
  con gli angoli esterni alla piastra resi trasparenti.

Uso (dalla radice del progetto):
    python3 -I art/genera_icone.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "art", "icona.png")
RES = os.path.join(ROOT, "common", "src", "main", "res")

# Densità del launcher: dimensione in pixel del livello di 108 dp.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Altezza del disegno nel livello di 108 dp: resta nel cerchio di sicurezza (66 dp) che ogni
# maschera dei launcher mostra per intero.
ART_HEIGHT_DP = 60

# Sotto questa opacità un pixel è fondo (piastra bianca o angoli grigio chiaro), non disegno.
ALPHA_FLOOR = 0.03

# Il monocromo usa solo l'opacità: il corpo del telecomando (blu, opacità ~0,8 rispetto al
# bianco) va reso pieno, l'ombra leggera sotto di esso (opacità < MONO_CUT) sparisce e i
# dettagli chiari (play, accensione) restano trafori.
MONO_CUT = 0.15
MONO_FULL = 0.65

# Rientro (px della sorgente) dal bordo della piastra entro cui cercare il disegno.
PLATE_INSET = 24


def unblend_from_white(rgb):
    """Colore e opacità che, composti sul bianco, ridanno esattamente [rgb] (valori 0..1).

    Il colore va calcolato con l'opacità piena: ridurla dopo farebbe risultare il disegno
    sbiadito. Sotto ALPHA_FLOOR il pixel è fondo e diventa del tutto trasparente.
    """
    alpha = (1.0 - rgb).max(axis=2)
    safe = np.where(alpha > 0, alpha, 1.0)[..., None]
    color = np.clip((rgb - (1.0 - safe)) / safe, 0.0, 1.0)
    alpha = np.where(alpha >= ALPHA_FLOOR, alpha, 0.0)
    return color, alpha


def to_image(color, alpha):
    rgba = np.dstack([color, alpha]) * 255.0
    return Image.fromarray(np.round(rgba).astype(np.uint8), "RGBA")


def mono_alpha(a):
    """Opacità del monocromo a partire da quella del disegno (0..255)."""
    t = (a / 255.0 - MONO_CUT) / (MONO_FULL - MONO_CUT)
    return round(255 * min(1.0, max(0.0, t)))


def art_layer(art, size_px, monochrome=False):
    """Disegno centrato in un livello quadrato di [size_px] (108 dp) con altezza ART_HEIGHT_DP."""
    target_h = round(size_px * ART_HEIGHT_DP / 108)
    target_w = max(1, round(art.width * target_h / art.height))
    scaled = art.resize((target_w, target_h), Image.LANCZOS)
    if monochrome:
        white = Image.new("RGBA", scaled.size, (255, 255, 255, 255))
        white.putalpha(scaled.getchannel("A").point(mono_alpha))
        scaled = white
    layer = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    # alpha_composite e non paste con maschera: su un livello trasparente paste moltiplica
    # l'opacità per sé stessa e il disegno risulta sbiadito.
    layer.alpha_composite(scaled, ((size_px - target_w) // 2, (size_px - target_h) // 2))
    return layer


def logo(source):
    """Icona originale con il fondo esterno alla piastra trasparente (riempimento dagli angoli)."""
    img = source.copy()
    w, h = img.size
    for corner in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if img.getpixel(corner)[3] > 0:
            ImageDraw.floodfill(img, corner, (0, 0, 0, 0), thresh=12)
    return img


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("scritto", os.path.relpath(path, ROOT))


def main():
    source = Image.open(SOURCE).convert("RGBA")
    rgb = np.asarray(source, dtype=np.float64)[..., :3] / 255.0
    color, alpha = unblend_from_white(rgb)
    # Bordo e ombra della piastra non sono disegno: cerco il disegno solo all'interno della
    # piastra bianca, rientrando abbastanza da escludere gli angoli arrotondati.
    plate = (rgb.min(axis=2) * 255.0) >= 253
    plate_rows = np.where(plate.any(axis=1))[0]
    plate_cols = np.where(plate.any(axis=0))[0]
    inside = np.zeros_like(alpha, dtype=bool)
    inside[plate_rows[0] + PLATE_INSET:plate_rows[-1] - PLATE_INSET + 1,
           plate_cols[0] + PLATE_INSET:plate_cols[-1] - PLATE_INSET + 1] = True
    alpha = np.where(inside, alpha, 0.0)
    rows = np.where(alpha.max(axis=1) > 0)[0]
    cols = np.where(alpha.max(axis=0) > 0)[0]
    if rows.size == 0:
        sys.exit("Nessun disegno trovato in " + SOURCE)
    box = (cols[0], rows[0], cols[-1] + 1, rows[-1] + 1)
    art = to_image(color, alpha).crop(box)
    print("disegno", art.size, "nel riquadro", box)

    for density, size in DENSITIES.items():
        save(art_layer(art, size), "drawable-" + density, "ic_launcher_art.png")
        save(art_layer(art, size, monochrome=True), "drawable-" + density, "ic_launcher_art_mono.png")
    save(logo(source), "drawable-nodpi", "logo_manager_tv.png")


if __name__ == "__main__":
    main()
