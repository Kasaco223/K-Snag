# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Promueve una versión de Android ya publicada al puntero que leen la app (y la web) para actualizarse.

    python android/scripts/promote.py --tag android-v1.0.0                 # estable: web/data/android.json
                                                                            #   + redirecciones /android/descargar
    python android/scripts/promote.py --channel rc --tag android-v1.1.0-rc.2   # prueba: web/data/android-rc.json
    python android/scripts/promote.py --tag android-v1.0.1 --min-version-code 10000993   # "actualización necesaria"
    python android/scripts/promote.py --check [--online]                  # CI (android-pointer.yml)

El puntero es copia exacta del android-release.json del Release + published_at (+ min_version_code).
Solo biblioteca estándar. GH_TOKEN es opcional (sube el límite de la API de 60 a 5000 peticiones por hora).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("KD_ROOT") or Path(__file__).resolve().parents[2])
REPO = "Kasaco223/K-Snag"
# Los punteros (android.json y las redirecciones de los APK) siguen con el nombre anterior del repo: las apps de
# Android 1.0.0-1.0.3 solo aceptan esa dirección. GitHub la redirige al repo renombrado.
LEGACY_REPO = "Kasaco223/K-YoutubeVideoDownloaderHD"


def legacy_url(url: str) -> str:
    """La misma dirección de GitHub con el nombre anterior del repo (la API ya devuelve el nuevo)."""
    return url.replace(f"https://github.com/{REPO}/", f"https://github.com/{LEGACY_REPO}/", 1)

VERCEL = ROOT / "web" / "vercel.json"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
CHANNELS = {  # canal -> (archivo del puntero, tags aceptados)
    "stable": (ROOT / "web" / "data" / "android.json", re.compile(r"^android-v(\d{1,3}\.\d{1,2}\.\d{1,2})$")),
    "rc": (ROOT / "web" / "data" / "android-rc.json",
           re.compile(r"^android-v(\d{1,3}\.\d{1,2}\.\d{1,2}-rc\.[1-9]\d?)$")),
}
LOCAL_FIELDS = {"published_at", "min_version_code"}  # lo único que el puntero agrega al android-release.json
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REDIRECTS = {"/android/descargar": "arm64-v8a", **{f"/android/descargar/{abi}": abi for abi in ABIS}}
# Desde la 2.0.3 el instalador se llama Instalar-K-Snag.cmd (el Release trae además la copia con el nombre anterior).
WINDOWS = f"https://github.com/{REPO}/releases/latest/download/Instalar-K-Snag.cmd"


def http_json(url: str, api: bool = False) -> dict:
    headers = {"User-Agent": f"K-Snag-promote (+https://github.com/{REPO})"}
    if api:
        headers["Accept"] = "application/vnd.github+json"
        if os.environ.get("GH_TOKEN"):
            headers["Authorization"] = f"Bearer {os.environ['GH_TOKEN']}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as res:
        return json.loads(res.read(256_000))


def problems_in(manifest: dict, channel: str) -> list[str]:
    out: list[str] = []
    match = CHANNELS[channel][1].match(str(manifest.get("tag", "")))
    if manifest.get("schema") != 1:
        out.append("schema debe ser 1")
    if not match or match.group(1) != manifest.get("version"):
        out.append(f"tag/version no válidos para el canal {channel}: {manifest.get('tag')!r} / {manifest.get('version')!r}")
        return out
    base = f"https://github.com/{LEGACY_REPO}/releases/download/{manifest['tag']}/"
    if manifest.get("notes_url") != f"https://github.com/{LEGACY_REPO}/releases/tag/{manifest['tag']}":
        out.append("notes_url no apunta a la página del Release")
    if not SHA256.match(str(manifest.get("cert_sha256", ""))):
        out.append("cert_sha256 debe ser el SHA-256 del certificado actual (64 hex en minúsculas)")
    apks = manifest.get("apks") or {}
    if set(apks) != set(ABIS):
        out.append(f"apks debe tener exactamente {list(ABIS)}")
    codes = set()
    for abi, apk in apks.items():
        if apk.get("url") != f"{base}K-Downloader-Android-{abi}.apk":
            out.append(f"{abi}: url inesperada {apk.get('url')!r}")
        if not SHA256.match(str(apk.get("sha256", ""))):
            out.append(f"{abi}: sha256 inválido")
        if not isinstance(apk.get("size"), int) or apk["size"] <= 0:
            out.append(f"{abi}: size inválido")
        if not isinstance(apk.get("version_code"), int) or apk["version_code"] in codes:
            out.append(f"{abi}: version_code inválido o repetido")
        codes.add(apk.get("version_code"))
    mvc = manifest.get("min_version_code")
    if mvc is not None and (channel != "stable" or not isinstance(mvc, int) or mvc <= 0
                            or any(isinstance(c, int) and mvc > c for c in codes)):
        out.append("min_version_code: solo en estable, entero positivo y menor o igual que cada version_code")
    return out


def vercel_problems(text: str, manifest: dict) -> list[str]:
    redirects = {r["source"]: r for r in json.loads(text).get("redirects", [])}
    out = []
    for source in ("/descargar", "/instalar"):  # contrato de la app de Windows: nunca cambia
        if redirects.get(source, {}).get("destination") != WINDOWS:
            out.append(f"{source} debe seguir apuntando a {WINDOWS}")
    for source, abi in REDIRECTS.items():
        r = redirects.get(source)
        if not r or r.get("permanent") is not False:
            out.append(f"falta la redirección temporal {source}")
        elif r.get("destination") != manifest["apks"][abi]["url"]:
            out.append(f"{source} apunta a {r.get('destination')!r}, se esperaba el APK {abi} de {manifest['tag']}")
    return out


def point_vercel(text: str, manifest: dict) -> str:
    def repl(m: re.Match) -> str:
        return m.group(1) + manifest["apks"][REDIRECTS[m.group(2)]]["url"] + m.group(3)

    pattern = r'("source":\s*"(/android/descargar(?:/[a-z0-9_-]+)?)",\s*"destination":\s*")[^"]*(")'
    new = re.sub(pattern, repl, text)
    missing = vercel_problems(new, manifest)
    if missing:
        sys.exit("web/vercel.json: " + "; ".join(missing))
    return new


def release_manifest(tag: str) -> tuple[dict, dict]:
    """(Release de la API, su android-release.json). El Release debe estar publicado y ser pre-release."""
    rel = http_json(f"https://api.github.com/repos/{REPO}/releases/tags/{tag}", api=True)
    asset = next((a for a in rel.get("assets", []) if a["name"] == "android-release.json"), None)
    if not asset:
        sys.exit(f"{tag} no tiene android-release.json")
    return rel, http_json(asset["browser_download_url"])


def online_problems(pointer: dict) -> list[str]:
    rel, original = release_manifest(pointer["tag"])
    out = []
    if rel.get("draft") or not rel.get("published_at"):
        out.append(f"{pointer['tag']} todavía no está publicado")
    if not rel.get("prerelease"):
        out.append(f"{pointer['tag']} debe ser pre-release (los Releases de Android nunca son Latest)")
    for key in sorted((set(pointer) | set(original)) - LOCAL_FIELDS):
        if pointer.get(key) != original.get(key):
            out.append(f"{key} no coincide con el android-release.json del Release")
    assets = {a["name"]: a for a in rel.get("assets", [])}
    for abi, apk in pointer["apks"].items():
        asset = assets.get(f"K-Downloader-Android-{abi}.apk")
        if not asset:
            out.append(f"{abi}: el Release no tiene el APK")
        elif legacy_url(str(asset.get("browser_download_url"))) != apk["url"] or asset.get("size") != apk["size"]:
            out.append(f"{abi}: url o tamaño distintos de los del Release")
        elif asset.get("digest") != f"sha256:{apk['sha256']}":
            out.append(f"{abi}: el SHA-256 no coincide con el del Release ({asset.get('digest')})")
    return out


def check(online: bool) -> int:
    problems: list[str] = []
    for channel, (path, _) in CHANNELS.items():
        if not path.exists():
            print(f"No hay {path.relative_to(ROOT).as_posix()} todavía: nada que revisar.")
            continue
        pointer = json.loads(path.read_text(encoding="utf-8"))
        found = problems_in(pointer, channel)
        if not found and channel == "stable":
            found += vercel_problems(VERCEL.read_text(encoding="utf-8"), pointer)
        if not found and online:
            found += online_problems(pointer)
        problems += [f"{path.name}: {p}" for p in found]
    for p in problems:
        print(f"::error file=web/data/{p}")
    print("OK" if not problems else f"{len(problems)} problema(s)")
    return 1 if problems else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--tag", help="android-vX.Y.Z (o android-vX.Y.Z-rc.N con --channel rc) ya publicado")
    group.add_argument("--check", action="store_true")
    parser.add_argument("--channel", choices=sorted(CHANNELS), default="stable")
    parser.add_argument("--min-version-code", type=int, help="estable: por debajo de esto la app pide actualizar")
    parser.add_argument("--online", action="store_true", help="con --check: compara con el Release en GitHub")
    args = parser.parse_args()
    if args.check:
        return check(args.online)

    path, tag_re = CHANNELS[args.channel]
    if not tag_re.match(args.tag):
        sys.exit(f"{args.tag} no es un tag del canal {args.channel} "
                 "(estable: android-vX.Y.Z; rc: android-vX.Y.Z-rc.N con --channel rc).")
    rel, manifest = release_manifest(args.tag)
    if rel.get("draft"):
        sys.exit(f"{args.tag} es un borrador: publícalo primero (docs/release-android.md).")
    manifest["published_at"] = rel.get("published_at")
    manifest["min_version_code"] = args.min_version_code
    problems = problems_in(manifest, args.channel) + online_problems(manifest)
    if problems:
        sys.exit("No se promueve:\n  - " + "\n  - ".join(problems))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.channel == "stable":
        VERCEL.write_text(point_vercel(VERCEL.read_text(encoding="utf-8"), manifest), encoding="utf-8")
    print(f"Listo: {path.relative_to(ROOT).as_posix()} apunta a {args.tag}. Haz commit y abre el PR.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as exc:
        sys.exit(f"GitHub respondió {exc.code} para {exc.url} (¿existe el tag? ¿está publicado?)")
