"""Genera packaging/assets/K-Downloader.ico a partir de la geometría de packaging/assets/logo.svg.

Uso:  python packaging/make_icon.py [--out RUTA] [--png-dir CARPETA]

Necesita Pillow (no es dependencia de la app; solo de este script). Dibuja el logo a 16x la
resolución final y lo reduce con LANCZOS para que los bordes queden suaves. El .ico resultante
lleva 16, 24, 32, 48 y 64 px como BMP de 32 bits (lo más compatible con el Explorador) y 128 y
256 px como PNG, igual que los íconos de Windows.

La geometría debe coincidir con logo.svg (viewBox 0 0 256 256):
  rect x=8 y=8 240x240 rx=56, degradado diagonal #6366F1 -> #8B5CF6 (0.55) -> #F97362
  K:      M68 52h34v60l52-60h42l-60 66 34 39h-44l-24-28v63H68z  (blanco)
  círculo cx=178 cy=178 r=46                                     (blanco)
  flecha: M168 150h20v28h16l-26 28-26-28h16z                      (#6366F1)
"""

from __future__ import annotations

import argparse
import io
import struct
from itertools import pairwise
from pathlib import Path

from PIL import Image, ImageDraw

SIZES = (16, 24, 32, 48, 64, 128, 256)
PNG_MIN = 128  # desde este tamaño las entradas van como PNG
SUPERSAMPLE = 16
VIEW = 256.0

STOPS = ((0.0, (0x63, 0x66, 0xF1)), (0.55, (0x8B, 0x5C, 0xF6)), (1.0, (0xF9, 0x73, 0x62)))
WHITE = (255, 255, 255, 255)
INDIGO = (0x63, 0x66, 0xF1, 255)

RECT = (8.0, 8.0, 248.0, 248.0)
RADIUS = 56.0
# Polígonos del SVG, ya convertidos a coordenadas absolutas.
K_SHAPE = [(68, 52), (102, 52), (102, 112), (154, 52), (196, 52), (136, 118), (170, 157),
           (126, 157), (102, 129), (102, 192), (68, 192)]
CIRCLE = (178.0, 178.0, 46.0)
ARROW = [(168, 150), (188, 150), (188, 178), (204, 178), (178, 206), (152, 178), (168, 178)]


def _gradient_color(t: float) -> tuple[int, int, int]:
    t = min(1.0, max(0.0, t))
    for (t0, c0), (t1, c1) in pairwise(STOPS):
        if t <= t1:
            f = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1, strict=True))  # type: ignore[return-value]
    return STOPS[-1][1]


def render(size: int, supersample: int = SUPERSAMPLE) -> Image.Image:
    """Dibuja el logo a size*supersample y lo reduce a size x size (RGBA)."""
    big = size * supersample
    scale = big / VIEW

    def pt(x: float, y: float) -> tuple[float, float]:
        # El SVG usa bordes de píxel; Pillow, centros de píxel: se corre medio píxel.
        return (x * scale - 0.5, y * scale - 0.5)

    # Degradado del SVG: objectBoundingBox de (0,0) a (1,1) sobre el cuadrado 8..248.
    # En un cuadrado, t = ((x - x0) + (y - y0)) / (2 * lado): depende solo de x + y.
    x0, y0, x1, _ = (v * scale for v in RECT)
    side = x1 - x0
    strip = Image.new("RGB", (2 * big, 1))
    strip.putdata([_gradient_color(((d + 1.0) - 2 * x0) / (2 * side)) for d in range(2 * big)])
    grad = Image.new("RGB", (big, big))
    for y in range(big):
        grad.paste(strip.crop((y, 0, y + big, 1)), (0, y))

    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (x0, y0, x1 - 1, x1 - 1), radius=RADIUS * scale, fill=255
    )
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)

    draw = ImageDraw.Draw(img)
    draw.polygon([pt(x, y) for x, y in K_SHAPE], fill=WHITE)
    cx, cy, r = CIRCLE
    draw.ellipse(((cx - r) * scale, (cy - r) * scale, (cx + r) * scale - 1, (cy + r) * scale - 1), fill=WHITE)
    draw.polygon([pt(x, y) for x, y in ARROW], fill=INDIGO)

    return _downsample(img, size)


def _downsample(img: Image.Image, size: int) -> Image.Image:
    # Reducir con alfa premultiplicado evita halos oscuros en los bordes transparentes.
    premult = img.convert("RGBa").resize((size, size), Image.Resampling.LANCZOS)
    return premult.convert("RGBA")


def _bmp_entry(img: Image.Image) -> bytes:
    """Entrada BMP de 32 bits (BGRA, de abajo hacia arriba) con su máscara AND de 1 bit."""
    w, h = img.size
    header = struct.pack("<IiiHHIIiiII", 40, w, h * 2, 1, 32, 0, 0, 0, 0, 0, 0)
    rgba = img.tobytes("raw", "BGRA", 0, -1)  # filas de abajo hacia arriba
    row_bytes = ((w + 31) // 32) * 4
    alpha = img.getchannel("A")
    and_mask = bytearray()
    for y in range(h - 1, -1, -1):
        row = bytearray(row_bytes)
        for x in range(w):
            if alpha.getpixel((x, y)) == 0:
                row[x // 8] |= 0x80 >> (x % 8)
        and_mask += row
    return header + rgba + bytes(and_mask)


def _png_entry(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def write_ico(images: list[Image.Image], path: Path) -> None:
    entries = [(_png_entry(im) if im.width >= PNG_MIN else _bmp_entry(im), im.width) for im in images]
    out = bytearray(struct.pack("<HHH", 0, 1, len(entries)))
    offset = 6 + 16 * len(entries)
    for data, size in entries:
        dim = 0 if size >= 256 else size
        out += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    for data, _ in entries:
        out += data
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=here / "assets" / "K-Downloader.ico")
    parser.add_argument("--png-dir", type=Path, help="también guarda cada tamaño como PNG (para revisar)")
    args = parser.parse_args(argv)

    images = [render(s) for s in SIZES]
    write_ico(images, args.out)
    if args.png_dir:
        args.png_dir.mkdir(parents=True, exist_ok=True)
        for im in images:
            im.save(args.png_dir / f"K-Snag-{im.width}.png")
    print(f"{args.out} ({args.out.stat().st_size} bytes): {', '.join(str(s) for s in SIZES)} px")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
