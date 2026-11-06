"""Avisa a Bing (y a los demás buscadores de IndexNow) de las páginas de web/sitemap.xml.

Se corre a mano después de un deploy que cambie páginas:  python scripts/indexnow.py
La clave es pública por diseño: es el archivo web/<clave>.txt, que prueba que el sitio es nuestro.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"
HOST = "ksnagapp.vercel.app"
ENDPOINT = "https://api.indexnow.org/indexnow"


def key() -> str:
    found = [p for p in WEB.glob("*.txt") if re.fullmatch(r"[0-9a-f]{32}", p.stem)]
    if len(found) != 1:
        sys.exit(f"Se esperaba un solo archivo de clave IndexNow en web/, hay {len(found)}")
    value = found[0].read_text(encoding="utf-8").strip()
    if value != found[0].stem:
        sys.exit(f"{found[0].name}: el contenido no coincide con el nombre del archivo")
    return value


def urls() -> list[str]:
    sitemap = (WEB / "sitemap.xml").read_text(encoding="utf-8")
    return re.findall(r"<loc>\s*([^<\s]+)\s*</loc>", sitemap)


def main() -> None:
    k = key()
    payload = {
        "host": HOST,
        "key": k,
        "keyLocation": f"https://{HOST}/{k}.txt",
        "urlList": urls(),
    }
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        print(f"IndexNow respondió {response.status} para {len(payload['urlList'])} URLs")


if __name__ == "__main__":
    main()
