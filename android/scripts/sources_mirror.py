# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Espejo del código fuente de los componentes copyleft que trae el APK (GPL-3.0 §6, LGPL, MPL-2.0 §3.2).

    python3 android/scripts/sources_mirror.py --tag           # etiqueta del espejo (mirror_release.tag)
    python3 android/scripts/sources_mirror.py --recipes       # commit de termux-packages y nombre de su copia
    python3 android/scripts/sources_mirror.py --build DIR     # android-sources.yml: baja y verifica las fuentes
    python3 android/scripts/sources_mirror.py --verify        # CI y releases: el espejo está publicado y completo

El espejo es el Release android-sources-ytdl-<versión de youtubedl-android>-r<N> (mirror_release.tag):
pre-release que publica el dueño del repo y que no se cambia nunca; una corrección sube la revisión. Lleva
cada fuente de los componentes con "mirror": true de android/third_party/components.json, la copia del
árbol de termux-packages (termux-packages-<commit>.tar.gz), FUENTES.md y SHA256SUMS.txt.

--verify usa la API de GitHub (GH_TOKEN opcional). Exige que el Release esté PUBLICADO, que sea
pre-release y que cada archivo esté con el mismo SHA-256 (el "digest" que calcula GitHub). Si el catálogo
de Gradle todavía no trae youtubedl-android, no exige nada: ningún APK lleva aún esos binarios.
Solo biblioteca estándar.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("KD_ROOT") or Path(__file__).resolve().parents[2])
REPO = os.environ.get("GH_REPO") or "Kasaco223/K-YoutubeVideoDownloaderHD"
COMPONENTS = ROOT / "android" / "third_party" / "components.json"
CATALOG = ROOT / "android" / "gradle" / "libs.versions.toml"
UA = {"User-Agent": f"K-Snag-sources (+https://github.com/{REPO})"}
MAX_BYTES = 1 << 30  # ninguna fuente pesa 1 GiB; youtubedl-android, la mayor, ~220 MB


def load() -> dict:
    return json.loads(COMPONENTS.read_text(encoding="utf-8"))


COORD = "io.github.junkfood02.youtubedl-android"


def build_ytdl_version(android: Path) -> str | None:
    """Versión de youtubedl-android que usa el build de Gradle, o None si ningún archivo la nombra.

    Busca la coordenada Maven en el catálogo (version.ref, version o "grupo:nombre:versión") y en cada
    *.gradle.kts. Si la nombra y no se puede saber la versión, o hay más de una, falla: la puerta del espejo
    no puede quedar abierta por un cambio de formato. (Copia de la misma función en check_pins.py.)
    """
    found, named = set(), False
    catalog = android / "gradle" / "libs.versions.toml"
    if catalog.exists():
        text = catalog.read_text(encoding="utf-8")
        block = re.search(r"^\[versions\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S)
        versions = dict(re.findall(r'^\s*([\w.-]+)\s*=\s*"([^"]+)"', block.group(1), re.M)) if block else {}
        for line in text.splitlines():
            if COORD not in line:
                continue
            named = True
            ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', line)
            if ref:
                found.add(versions.get(ref.group(1), "?"))
            found.update(re.findall(r'\bversion\s*=\s*"([^"]+)"', line))
            found.update(re.findall(re.escape(COORD) + r':[\w.-]+:([\w.+-]+)"', line))
    for f in sorted(android.rglob("*.gradle.kts")):
        if {"build", ".gradle"} & set(f.relative_to(android).parts):
            continue
        for line in f.read_text(encoding="utf-8").splitlines():
            if COORD in line:
                named = True
                found.update(re.findall(re.escape(COORD) + r':[\w.-]+:([\w.+-]+)"', line) or ["?"])
    if not named:
        return None
    if len(found) != 1 or "?" in found:
        raise SystemExit(f"::error::No se pudo saber qué versión de youtubedl-android usa Gradle ({sorted(found)}). "
                         "Declárala en gradle/libs.versions.toml con version.ref.")
    return found.pop()


def catalog_version() -> str | None:
    return build_ytdl_version(ROOT / "android")


def ytdl_version(components: dict) -> str:
    return next(c["version"] for c in components["components"] if c["id"] == "youtubedl-android")


def mirror_tag(components: dict) -> str:
    return components["mirror_release"]["tag"]


def snapshot_name(components: dict) -> str:
    return f"termux-packages-{components['recipes_snapshot']['commit'][:12]}.tar.gz"


def mirrored(components: dict) -> list[tuple[dict, dict]]:
    """(componente, fuente) de todo lo que va al espejo; falla si falta un SHA-256."""
    out = []
    for c in components["components"]:
        if not c.get("mirror"):
            continue
        if not c.get("sources"):
            raise SystemExit(f"components.json: {c['id']} tiene mirror=true y no tiene sources")
        for s in c["sources"]:
            if not re.fullmatch(r"[0-9a-f]{64}", str(s.get("sha256", ""))):
                raise SystemExit(f"components.json: {c['id']} ({s.get('file')}) no tiene un sha256 válido")
            out.append((c, s))
    return out


def api_json(url: str):
    fake = os.environ.get("KDL_MIRROR_RELEASE_JSON")  # pruebas: null simula un 404; {"http_error": N}, otro error
    if fake:
        data = json.loads(Path(fake).read_text(encoding="utf-8"))
        if data is None or "http_error" in data:
            code = 404 if data is None else data["http_error"]
            raise urllib.error.HTTPError(url, code, "simulado", {}, None)
        return data
    headers = dict(UA, Accept="application/vnd.github+json")
    if os.environ.get("GH_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['GH_TOKEN']}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as res:
        return json.loads(res.read(5_000_000))


def verify() -> int:
    version = catalog_version()
    if not version:
        print("El catálogo de Gradle no trae youtubedl-android: todavía no hace falta el espejo de fuentes.")
        return 0
    components = load()
    if version != ytdl_version(components):
        print(f"::error file=android/third_party/components.json::El catálogo usa youtubedl-android {version} y "
              f"components.json describe {ytdl_version(components)}: actualiza el inventario y el espejo juntos.")
        return 1
    tag = mirror_tag(components)
    need = {s["file"]: s["sha256"] for _, s in mirrored(components)}
    try:
        rel = api_json(f"https://api.github.com/repos/{REPO}/releases/tags/{tag}")
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            print(f"::error::No se pudo consultar {tag} en GitHub (HTTP {exc.code}). Reintenta; en CI, revisa GH_TOKEN.")
            return 1
        print(f"::error::Falta publicar {tag}, el código fuente de FFmpeg, Python y compañía. Ningún APK puede "
              "salir antes: Actions › Android · fuentes de terceros, revisa el borrador y publícalo como pre-release.")
        return 1
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"::error::No se pudo consultar {tag} en GitHub ({exc}). Reintenta.")
        return 1
    problems = []
    if rel.get("draft") or not rel.get("published_at"):
        problems.append("no está publicado (sigue como borrador)")
    if not rel.get("prerelease"):
        problems.append("debe ser pre-release (nunca Latest)")
    assets = {a["name"]: a for a in rel.get("assets", [])}
    for name in ("FUENTES.md", "SHA256SUMS.txt", snapshot_name(components)):
        if name not in assets:
            problems.append(f"falta {name}")
    for name, sha in sorted(need.items()):
        asset = assets.get(name)
        if not asset:
            problems.append(f"falta {name}")
        elif asset.get("digest") != f"sha256:{sha}":
            problems.append(f"{name}: SHA-256 {asset.get('digest')}, components.json dice {sha}")
    for p in problems:
        print(f"::error::Espejo de fuentes {tag}: {p}")
    print(f"{tag}: {'OK, ' if not problems else ''}{len(need)} fuentes exigidas, {len(problems)} problema(s)")
    return 1 if problems else 0


def fetch(url: str, dest: Path, sha: str) -> None:
    """Baja a un archivo temporal, verifica el SHA-256 y recién entonces lo deja con su nombre."""
    if not url.startswith("https://"):
        raise SystemExit(f"{dest.name}: el espejo baja solo por https ({url})")
    fake = os.environ.get("KDL_MIRROR_FAKE_DOWNLOADS")  # pruebas: copia desde esta carpeta
    tmp = dest.with_name(dest.name + ".part")
    last: Exception | None = None
    for attempt in range(3):
        if attempt:
            time.sleep(5 * attempt)
        try:
            h, size = hashlib.sha256(), 0
            if fake:
                shutil.copyfile(Path(fake) / dest.name, tmp)
                h.update(tmp.read_bytes())
            else:
                with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as res, \
                        open(tmp, "wb") as out:
                    while chunk := res.read(1 << 20):
                        size += len(chunk)
                        if size > MAX_BYTES:
                            out.close()
                            tmp.unlink(missing_ok=True)
                            raise SystemExit(f"{dest.name}: pesa más de {MAX_BYTES >> 20} MB")
                        h.update(chunk)
                        out.write(chunk)
            got = h.hexdigest()
            if got != sha:
                tmp.unlink(missing_ok=True)
                raise SystemExit(f"{dest.name}: SHA-256 {got}, components.json dice {sha} ({url})")
            tmp.replace(dest)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            last = exc
    tmp.unlink(missing_ok=True)
    raise SystemExit(f"{dest.name}: no se pudo bajar {url}: {last}")


def fuentes_md(components: dict, rows: list[tuple[dict, dict]], version: str) -> str:
    snap = components["recipes_snapshot"]
    commit = snap["commit"]
    lines = [
        f"# Código fuente de terceros · K-Snag para Android (youtubedl-android {version})",
        "",
        "Este Release publica, sin cambios, el código fuente de los componentes de terceros que trae el APK de",
        "K-Snag para Android (todos menos las bibliotecas del Android NDK, Apache-2.0), más el árbol de",
        "Termux con el que se compilan. Se publica para cumplir la GPL-3.0 (sección 6), la GPL-2.0 (sección 3),",
        "la LGPL-2.1 (sección 6), la LGPL-3.0 (sección 4) y la MPL-2.0 (sección 3.2). No se modifica nunca: una",
        f"corrección se publica como otra revisión (`{mirror_tag(components)}` → `-r2`).",
        "",
        "*English: unmodified source code of the third-party components shipped in the K-Snag for Android",
        "APK (all but the Apache-2.0 Android NDK libraries), plus the Termux build tree used to compile them.*",
        "",
        "## De dónde salen los binarios",
        "",
        f"- K-Snag usa los binarios de [youtubedl-android](https://github.com/yausername/youtubedl-android) "
        f"{version} (Maven Central), sin recompilarlos.",
        "- youtubedl-android los arma con paquetes de [Termux](https://github.com/termux/termux-packages), pero no",
        "  publicó qué commit exacto usó (issues [#358](https://github.com/yausername/youtubedl-android/issues/358) y",
        "  [#363](https://github.com/yausername/youtubedl-android/issues/363)).",
        f"- `{snapshot_name(components)}` es `git archive` del árbol completo de termux-packages en el commit",
        f"  [`{commit}`]({snap['repo']}/tree/{commit}): `build-package.sh`, `scripts/`, `ndk-patches/` y la",
        "  receta (`build.sh` y parches) de cada paquete. " + snap["note"],
        "- Para compilar un paquete, sigue",
        "  [Building packages](https://github.com/termux/termux-packages/wiki/Building-packages)",
        "  (`./scripts/run-docker.sh ./build-package.sh -a aarch64 <paquete>`). `build-package.sh` baja cada fuente",
        "  de la URL de su receta y comprueba su SHA-256; si esa URL ya no existe, copia el archivo de este",
        "  Release a la caché del paquete (`~/.termux-build/<paquete>/cache/`) con el nombre que espera la receta.",
        "",
        "## Archivos",
        "",
        "Cada archivo es el original de su proyecto. **Verificado** dice contra qué se comprobó su SHA-256:",
        f"la receta de Termux en `{commit[:12]}`, PyPI o una firma GPG de upstream (huella de la llave).",
        "",
        "| Componente | Versión | Licencia | Archivo | SHA-256 | Verificado | Receta |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for c, s in rows:
        checks = "; ".join(s.get("checks") or []) or "—"
        recipe = f"`{c['recipe']}`" if c.get("recipe") else "—"
        origin = s.get("upstream_url") or s["url"]
        lines.append(f"| {c['name']} | {c['version']} | {c['spdx']} | [`{s['file']}`]({origin}) | "
                     f"`{s['sha256'][:16]}…` | {checks} | {recipe} |")
    lines += [
        "",
        "`SHA256SUMS.txt` trae el SHA-256 completo de cada archivo de este Release. La lista completa de",
        "componentes y sus textos de licencia están en `android/THIRD_PARTY_NOTICES.md` del repositorio.",
        "",
    ]
    return "\n".join(lines)


def build(out: Path) -> int:
    components = load()
    rows = mirrored(components)
    out.mkdir(parents=True, exist_ok=True)
    names = [s["file"] for _, s in rows]
    if len(names) != len(set(names)):
        raise SystemExit("components.json: dos fuentes del espejo tienen el mismo nombre de archivo")
    for c, s in rows:
        dest = out / s["file"]
        print(f"{c['id']}: {s['file']}", flush=True)
        fetch(s["url"], dest, s["sha256"])
    version = ytdl_version(components)
    (out / "FUENTES.md").write_text(fuentes_md(components, rows, version), encoding="utf-8", newline="\n")
    sums = "".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
                   for p in sorted(out.iterdir()) if p.is_file() and p.name != "SHA256SUMS.txt")
    (out / "SHA256SUMS.txt").write_text(sums, encoding="utf-8", newline="\n")
    if not (out / snapshot_name(components)).exists():
        print(f"::warning::Falta {snapshot_name(components)} en {out} (lo agrega android-sources.yml)")
    print(f"Listo: {len(rows)} fuentes verificadas en {out}")
    return 0


def main(args: list[str]) -> int:
    if args == ["--verify"]:
        return verify()
    if args == ["--recipes"]:
        components = load()
        print(components["recipes_snapshot"]["commit"], snapshot_name(components))
        return 0
    if args == ["--tag"]:
        print(mirror_tag(load()))
        return 0
    if args[:1] == ["--build"] and len(args) == 2:
        return build(Path(args[1]))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
