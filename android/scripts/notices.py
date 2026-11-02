# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
# SPDX-License-Identifier: GPL-3.0-or-later
"""Avisos de terceros de K-Downloader para Android, generados desde android/third_party/components.json.

    python3 android/scripts/notices.py                     # escribe android/THIRD_PARTY_NOTICES.md
    python3 android/scripts/notices.py --check             # CI: inventario válido y avisos al día
    python3 android/scripts/notices.py --payload AAR...    # cada archivo de los payloads tiene dueño

--check revisa components.json:
  - todo componente copyleft (GPL, LGPL, MPL, EPL…) sin alternativa permisiva lleva "mirror": true, salvo
    "mirror_exempt" con la razón;
  - la etiqueta del espejo es android-sources-ytdl-<versión de youtubedl-android>-r<N>;
  - las fuentes del espejo traen URL https, nombre de archivo seguro y único, y SHA-256;
  - cada archivo de licencia citado existe en third_party/licenses/ y no sobra ninguno.
--payload abre los AAR de youtubedl-android (library y ffmpeg) y exige que cada archivo de jni/<abi>/ y de
los paquetes lib*.zip.so coincida con algún patrón "files" del inventario, y que cada patrón encuentre algo.
Un paquete de site-packages tiene que tener su propio componente: no vale que lo cubra el patrón de CPython.
Solo biblioteca estándar. Lee los AAR como datos: no ejecuta nada.
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
import zipfile
from pathlib import Path

ANDROID = Path(os.environ.get("KD_ROOT") or Path(__file__).resolve().parents[2]) / "android"
TP = ANDROID / "third_party"
COMPONENTS = TP / "components.json"
LICENSES = TP / "licenses"
NOTICES = ANDROID / "THIRD_PARTY_NOTICES.md"
REPO_URL = "https://github.com/Kasaco223/K-YoutubeVideoDownloaderHD"
COPYLEFT = re.compile(r"\b(A?GPL|LGPL|MPL|EPL|CDDL|EUPL|OSL|CC-BY-SA|GFDL)-")
PAYLOADS = ("libpython.zip.so", "libffmpeg.zip.so")
FILE_RE = re.compile(r"^(jni:[^/]+|lib(python|ffmpeg)\.zip\.so:[^\s]+|apk:(res|assets|lib)/[^\s]+)$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]*$")
SECTIONS = [  # (título, prueba sobre el componente)
    ("Motor de descargas: yt-dlp y certificados (`res/raw/ytdlp`, `assets/kd/cacert.pem`)",
     lambda c: any(f.startswith("apk:") for f in c.get("files", []))),
    ("Motor de descargas: Python (`libpython.zip.so`, `libpython.so`, `libqjs.so`)",
     lambda c: any(f.startswith(("libpython.zip.so:", "jni:libpython", "jni:libqjs")) for f in c.get("files", []))
     and not any(f.startswith("libffmpeg.zip.so:") for f in c.get("files", []))),
    ("Motor de descargas: FFmpeg (`libffmpeg.zip.so`, `libffmpeg.so`, `libffprobe.so`)",
     lambda c: any(f.startswith(("libffmpeg.zip.so:", "jni:libff")) for f in c.get("files", []))),
    ("Biblioteca youtubedl-android", lambda c: "maven" in c),
    ("Dependencias Java y Kotlin (Maven)", lambda c: "match" in c),
]


def load() -> dict:
    return json.loads(COMPONENTS.read_text(encoding="utf-8"))


SITE_PACKAGES = "libpython.zip.so:usr/lib/python3.12/site-packages/"


def mirror_tag(data: dict) -> str:
    return data["mirror_release"]["tag"]


def needs_mirror(spdx: str) -> bool:
    """Copyleft sin alternativa permisiva: cada opción de un OR nombra una licencia copyleft. Con paréntesis
    no se interpreta la expresión: basta que nombre una copyleft."""
    if "(" in spdx:
        return bool(COPYLEFT.search(spdx))
    return all(COPYLEFT.search(alt) for alt in spdx.split(" OR "))


def validate(data: dict) -> list[str]:
    problems = []
    if data.get("schema") != 1:
        problems.append("schema debe ser 1")
    if not re.fullmatch(r"[0-9a-f]{40}", str(data.get("recipes_snapshot", {}).get("commit", ""))):
        problems.append("recipes_snapshot.commit debe ser un SHA-1 de 40 caracteres")
    ytdl = next((c for c in data.get("components", []) if c.get("id") == "youtubedl-android"), {})
    tag = str(data.get("mirror_release", {}).get("tag", ""))
    if not re.fullmatch(rf"android-sources-ytdl-{re.escape(str(ytdl.get('version')))}-r[1-9][0-9]*", tag):
        problems.append(f"mirror_release.tag debe ser android-sources-ytdl-{ytdl.get('version')}-r<N> (hay {tag!r})")
    seen, files_seen, cited = set(), {}, set()
    for c in data.get("components", []):
        cid = c.get("id", "?")
        where = f"components.json: {cid}"
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", cid) or cid in seen:
            problems.append(f"{where}: id inválido o repetido")
        seen.add(cid)
        for key in ("name", "spdx"):
            if not c.get(key):
                problems.append(f"{where}: falta {key}")
        if "match" not in c:
            if not c.get("version"):
                problems.append(f"{where}: falta version")
            if not c.get("files") and "maven" not in c:
                problems.append(f"{where}: falta files (rutas de los payloads que cubre)")
            for f in c.get("files", []):
                if not FILE_RE.match(f):
                    problems.append(f"{where}: ruta inválida en files: {f}")
            if not c.get("sources") and not c.get("recipe"):
                problems.append(f"{where}: falta sources o recipe (dónde está su código fuente)")
        for s in c.get("sources", []):
            if not str(s.get("url", "")).startswith(("https://", "http://")):
                problems.append(f"{where}: URL de fuente inválida")
            if s.get("fetch", True) and not re.fullmatch(r"[0-9a-f]{64}", str(s.get("sha256", ""))):
                problems.append(f"{where}: {s.get('file')} sin sha256 válido")
        if c.get("mirror"):
            if not c.get("sources"):
                problems.append(f"{where}: mirror=true sin sources")
            for s in c.get("sources", []):
                name = str(s.get("file", ""))
                if not SAFE_NAME.match(name) or name in ("FUENTES.md", "SHA256SUMS.txt"):
                    problems.append(f"{where}: nombre de archivo no apto para el espejo: {name!r}")
                elif name in files_seen:
                    problems.append(f"{where}: {name} repetido (también en {files_seen[name]})")
                files_seen[name] = cid
                if s.get("fetch") is False:
                    problems.append(f"{where}: una fuente del espejo no puede tener fetch=false")
                if not str(s.get("url", "")).startswith("https://"):
                    problems.append(f"{where}: el espejo baja solo por https ({name})")
        elif needs_mirror(c.get("spdx", "")) and not c.get("mirror_exempt"):
            problems.append(f"{where}: {c['spdx']} es copyleft: necesita mirror=true (o mirror_exempt con la razón)")
        lic = c.get("license_files", [])
        if not lic:
            problems.append(f"{where}: falta license_files")
        for f in lic:
            cited.add(f)
            if ".." in Path(f).parts or Path(f).is_absolute() or ":" in f or not (LICENSES / f).is_file():
                problems.append(f"{where}: no existe third_party/licenses/{f}")
    for p in sorted(LICENSES.rglob("*")):
        if p.is_file() and p.relative_to(LICENSES).as_posix() not in cited:
            problems.append(f"third_party/licenses/{p.relative_to(LICENSES).as_posix()}: ningún componente lo cita")
    return problems


def link(text: str, url: str | None) -> str:
    return f"[{text}]({url})" if url else text


def render(data: dict) -> str:
    tag = mirror_tag(data)
    mirror_url = f"{REPO_URL}/releases/tag/{tag}"
    snap = data["recipes_snapshot"]
    out = [
        "<!--",
        "SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader",
        "SPDX-License-Identifier: GPL-3.0-or-later",
        "Generado por android/scripts/notices.py desde third_party/components.json: no lo edites a mano.",
        "-->",
        "# Avisos de terceros de K-Downloader para Android · Third-party notices",
        "",
        "K-Downloader para Android se distribuye bajo la [GPL-3.0-or-later](LICENSE). Incluye software libre de otras",
        "personas, que conserva su propia licencia. Esta lista dice qué componentes trae el APK con el motor de",
        "descargas, con qué licencia y dónde está su código fuente. Los textos completos de las licencias están en",
        "[`third_party/licenses/`](third_party/licenses/).",
        "",
        "*English summary: K-Downloader for Android is GPL-3.0-or-later. The tables list every third-party component",
        "in the APK with its SPDX license. The unmodified source code of every component marked “espejo” (all of",
        f"them except the Android NDK libraries) is published in the pre-release [`{tag}`]({mirror_url}) of this",
        "repository, together with the full Termux build tree (recipes and patches).*",
        "",
        "## Código fuente",
        "",
        "El motor de descargas viene de [youtubedl-android](https://github.com/yausername/youtubedl-android) "
        f"{next(c['version'] for c in data['components'] if c['id'] == 'youtubedl-android')}, que trae Python, FFmpeg y sus",
        "bibliotecas ya compiladas con el sistema de paquetes de [Termux](https://github.com/termux/termux-packages).",
        "El código fuente de cada componente marcado con **espejo** (todos menos las bibliotecas del Android NDK,",
        f"que son Apache-2.0) está en la pre-release [`{tag}`]({mirror_url}):",
        "",
        "- el archivo original de cada proyecto, con su SHA-256 verificado contra la receta de Termux o PyPI y, si",
        "  upstream lo firma, contra su firma GPG;",
        f"- el árbol completo de termux-packages en el commit [`{snap['commit'][:12]}`]"
        f"({snap['repo']}/tree/{snap['commit']}): el sistema de compilación, las recetas y sus parches;",
        "- `FUENTES.md` (qué es cada archivo) y `SHA256SUMS.txt`.",
        "",
        "youtubedl-android no publicó qué commit exacto de Termux usó para compilar sus binarios (issues",
        "[#358](https://github.com/yausername/youtubedl-android/issues/358) y",
        "[#363](https://github.com/yausername/youtubedl-android/issues/363)). Ese commit es el que mejor coincide:",
        "las versiones y revisiones de sus recetas son las de los binarios. Si upstream publica otra cosa, el espejo",
        "se corrige con una revisión nueva (`-r2`), sin tocar la publicada.",
    ]
    comps = data["components"]
    used = set()
    for title, test in SECTIONS:
        rows = [c for c in comps if c["id"] not in used and test(c)]
        if not rows:
            continue
        used.update(c["id"] for c in rows)
        out += ["", f"## {title}", ""]
        if rows[0].get("match"):
            out += ["| Componente | Artefactos | Licencia |", "| --- | --- | --- |"]
            for c in rows:
                out.append(f"| {c['name']} | `{c['match']}` | {c['spdx']} |")
            continue
        out += ["| Componente | Versión | Licencia | Código fuente | Textos | Notas |",
                "| --- | --- | --- | --- | --- | --- |"]
        for c in rows:
            src = c.get("sources") or []
            if c.get("mirror"):
                where = f"[espejo]({mirror_url}): " + ", ".join(f"`{s['file']}`" for s in src)
            elif src:
                where = ", ".join(link(s["file"], s.get("upstream_url") or s["url"]) for s in src)
            else:
                where = link("receta de Termux", f"{snap['repo']}/tree/{snap['commit']}/{c['recipe']}")
            lic = ", ".join(f"[`{Path(f).name}`](third_party/licenses/{f})" for f in c.get("license_files", [])) or "—"
            name = link(c["name"], c.get("homepage"))
            note = c.get("note") or c.get("mirror_exempt") or ""
            out.append(f"| {name} | {c['version']} | {c['spdx']} | {where} | {lic} | {note} |")
    left = [c["id"] for c in comps if c["id"] not in used]
    if left:
        raise SystemExit(f"notices.py: componentes sin sección: {', '.join(left)}")
    return "\n".join(out) + "\n"


def glob_re(pattern: str) -> re.Pattern:
    rx = re.escape(pattern).replace(r"\*\*", "\0").replace(r"\*", "[^/]*").replace("\0", ".*")
    return re.compile(f"^{rx}$")


def payload(aars: list[str]) -> int:
    data = load()
    # Las rutas apk: (res/raw/ytdlp, assets/kd/cacert.pem) no salen de los AAR: aquí no se cuentan.
    pats = [(c["id"], f, glob_re(f)) for c in data["components"] for f in c.get("files", []) if not f.startswith("apk:")]
    python_pats = {f for cid, f, _ in pats if cid == "python"}
    hits = {f: 0 for _, f, _ in pats}
    orphans = set()
    for aar in aars:
        with zipfile.ZipFile(aar) as z:
            for name in z.namelist():
                m = re.match(r"jni/([^/]+)/([^/]+)$", name)
                if not m:
                    continue
                keys = [f"jni:{m.group(2)}"]
                if m.group(2) in PAYLOADS:
                    inner = zipfile.ZipFile(io.BytesIO(z.read(name)))
                    keys = [f"{m.group(2)}:{i.filename}" for i in inner.infolist() if not i.is_dir()]
                for key in keys:
                    owners = [f for _, f, rx in pats if rx.match(key)]
                    for f in owners:
                        hits[f] += 1
                    if key.startswith(SITE_PACKAGES) and set(owners) <= python_pats:
                        owners = []  # un paquete nuevo de site-packages no es parte de CPython
                    if not owners and not key.startswith(tuple(f"jni:{p}" for p in PAYLOADS)):
                        orphans.add(key)
    problems = [f"sin dueño en components.json: {k}" for k in sorted(orphans)]
    problems += [f"el patrón {f} no encuentra nada en los AAR" for f, n in sorted(hits.items()) if not n]
    for p in problems:
        print(f"::error file=android/third_party/components.json::{p}")
    total = sum(hits.values())
    print(f"Payloads: {total} archivos con dueño, {len(problems)} problema(s)")
    return 1 if problems else 0


def main(args: list[str]) -> int:
    if args[:1] == ["--payload"] and len(args) > 1:
        return payload(args[1:])
    data = load()
    problems = validate(data)
    for p in problems:
        print(f"::error file=android/third_party/components.json::{p}")
    if problems:
        return 1
    text = render(data)
    if args == ["--check"]:
        current = NOTICES.read_text(encoding="utf-8") if NOTICES.exists() else ""
        if current != text:
            print("::error file=android/THIRD_PARTY_NOTICES.md::No está al día con components.json. "
                  "Corre: python3 android/scripts/notices.py")
            return 1
        print(f"Avisos de terceros: OK ({len(data['components'])} componentes, "
              f"{sum(1 for c in data['components'] if c.get('mirror'))} con espejo)")
        return 0
    if args:
        print(__doc__)
        return 2
    NOTICES.write_text(text, encoding="utf-8", newline="\n")
    print(f"Escrito {NOTICES.relative_to(ANDROID.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
