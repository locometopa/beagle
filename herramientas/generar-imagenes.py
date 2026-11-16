#!/usr/bin/env python3
"""Dibuja el icono y el banner (el rótulo que sale en el inicio de la tele) de la app.
Uso: python3 herramientas/generar-imagenes.py [Nombre]     (por defecto, Beagle)
Necesita Pillow (pip install pillow)."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

NOMBRE = sys.argv[1] if len(sys.argv) > 1 else "Beagle"
FONDO, PANEL, ACENTO = (32, 32, 32), (45, 45, 45), (198, 242, 78)
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
S = 4  # se dibuja a 4x y se reduce, para que los bordes salgan suaves


def letra(tam):
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, tam)
    return ImageFont.load_default()


def marca(d, cx, cy, r):
    """Círculo verde lima con el triángulo de «reproducir»."""
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ACENTO)
    t = r * 0.46
    d.polygon([(cx - t * 0.62, cy - t), (cx - t * 0.62, cy + t), (cx + t * 1.05, cy)], fill=FONDO)


def guardar(img, carpeta, nombre, tam):
    os.makedirs(os.path.join(RES, carpeta), exist_ok=True)
    img.resize(tam, Image.LANCZOS).save(os.path.join(RES, carpeta, nombre), optimize=True)


# Icono (192 px)
n = 192 * S
img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((0, 0, n - 1, n - 1), radius=n * 0.22, fill=FONDO)
marca(d, n / 2, n / 2, n * 0.30)
guardar(img, "drawable-xxxhdpi", "icono.png", (192, 192))

# Banner para Google TV / Fire TV (320 x 180)
w, h = 320 * S, 180 * S
img = Image.new("RGB", (w, h), FONDO)
d = ImageDraw.Draw(img)
r = h * 0.20
f = letra(int(h * 0.30))
ancho = d.textlength(NOMBRE, font=f)
total = 2 * r + h * 0.10 + ancho
x = (w - total) / 2
marca(d, x + r, h / 2, r)
d.text((x + 2 * r + h * 0.10, h / 2), NOMBRE, font=f, fill=(244, 244, 244), anchor="lm")
guardar(img, "drawable-xhdpi", "banner.png", (320, 180))
print("Icono y banner de «%s» generados" % NOMBRE)
