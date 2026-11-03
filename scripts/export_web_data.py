"""Genera web/data/errors.json y el catálogo en HTML de /ayuda a partir del catálogo de errores de la app.

La app y la web comparten una sola fuente (src/kdownloader/engine/errors_catalog.json):
la página /ayuda muestra exactamente los mismos códigos y textos que ve el usuario en la app.

El catálogo también se escribe en español dentro de web/ayuda.html (entre los marcadores
<!-- catalogo:inicio --> y <!-- catalogo:fin -->), para que los buscadores y las IA lo lean sin JavaScript.
Con JavaScript, js/ayuda.js lo reemplaza por la versión con buscador, grupos y cambio de idioma.

Uso:
    python scripts/export_web_data.py            # escribe web/data/errors.json y actualiza web/ayuda.html
    python scripts/export_web_data.py --check    # falla (código 1) si alguno está desactualizado
"""

from __future__ import annotations

import argparse
import html
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
OUT = ROOT / "web" / "data" / "errors.json"
HELP_PAGE = ROOT / "web" / "ayuda.html"
START = "<!-- catalogo:inicio (generado por scripts/export_web_data.py; no editar a mano) -->"
END = "<!-- catalogo:fin -->"

if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from kdownloader.engine.errors import export_for_web  # noqa: E402

REQUIRED_LANGS = ("es", "en")


def build() -> list[dict]:
    items = export_for_web()
    codes: set[str] = set()
    for item in items:
        code = item.get("code")
        if not code or not isinstance(code, str):
            raise ValueError(f"Entrada sin código: {item!r}")
        if code in codes:
            raise ValueError(f"Código repetido en el catálogo: {code}")
        codes.add(code)
        for lang in REQUIRED_LANGS:
            texts = item.get(lang) or {}
            if not texts.get("title") or not texts.get("message"):
                raise ValueError(f"Faltan textos '{lang}' para el código {code}")
    return items


def render(data: list[dict]) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def render_html(data: list[dict], indent: str = "          ") -> str:
    """Tarjetas en español, con el mismo marcado que pinta js/ayuda.js (sin los botones, que necesitan JS)."""
    esc = html.escape
    lines = [f'{indent}<div class="err-grid">']
    for item in data:
        code, es = item["code"], item["es"]
        lines += [
            f'{indent}  <article class="err-card" id="{esc(code)}" data-code="{esc(code)}"'
            f' aria-labelledby="{esc(code)}-title">',
            f'{indent}    <div class="err-head"><a class="err-code" href="#{esc(code)}">'
            f'<svg class="icon icon-sm" aria-hidden="true"><use href="/assets/icons.svg#i-hash"/></svg>'
            f"<span>{esc(code)}</span></a></div>",
            f'{indent}    <h3 class="err-title" id="{esc(code)}-title">{esc(es["title"])}</h3>',
            f'{indent}    <p class="err-message">{esc(es["message"])}</p>',
        ]
        if es.get("help"):
            lines.append(
                f'{indent}    <div class="err-help"><svg class="icon icon-sm" aria-hidden="true">'
                f'<use href="/assets/icons.svg#i-info"/></svg><div><span class="err-help-label">Qué puedes hacer</span>'
                f'<p class="err-help-text">{esc(es["help"])}</p></div></div>'
            )
        lines.append(f"{indent}  </article>")
    lines.append(f"{indent}</div>")
    return "\n".join(lines)


def updated_help_page(data: list[dict]) -> tuple[str, str]:
    """(contenido actual, contenido con el catálogo al día) de web/ayuda.html."""
    current = HELP_PAGE.read_text(encoding="utf-8")
    start, end = current.find(START), current.find(END)
    if start < 0 or end < start:
        raise ValueError(f"{HELP_PAGE.relative_to(ROOT)}: faltan los marcadores del catálogo")
    line_start = current.rfind("\n", 0, start) + 1
    indent = current[line_start:start]
    block = f"{START}\n{render_html(data, indent)}\n{indent}{END}"
    return current, current[:start] + block + current[end + len(END):]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="solo comprueba que el archivo esté al día")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")

    items = build()
    text = render(items)
    page_now, page_new = updated_help_page(items)
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        stale = [path for path, ok in ((OUT, current == text), (HELP_PAGE, page_now == page_new)) if not ok]
        for path in stale:
            print(f"{path.relative_to(ROOT)} está desactualizado; ejecuta scripts/export_web_data.py", file=sys.stderr)
        if stale:
            return 1
        print(f"{OUT.relative_to(ROOT)} y {HELP_PAGE.relative_to(ROOT)} están al día")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    with open(HELP_PAGE, "w", encoding="utf-8", newline="\n") as f:
        f.write(page_new)
    print(f"Escrito {OUT.relative_to(ROOT)} y el catálogo de {HELP_PAGE.relative_to(ROOT)} ({len(items)} códigos)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
