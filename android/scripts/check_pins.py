# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Cruza las dos fuentes de verdad de los AAR de youtubedl-android que firmamos y distribuimos:
android/gradle/verification-metadata.xml (Gradle rechaza cualquier artefacto con otro SHA-256) y
android/third_party/components.json (los SHA-256 publicados en Maven Central).

    python3 android/scripts/check_pins.py      # ci_repo_checks.sh

Mientras el catálogo de Gradle no traiga youtubedl-android, no hay nada que cruzar.
Solo biblioteca estándar.
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = Path(os.environ.get("KD_ROOT") or Path(__file__).resolve().parents[2]) / "android"
NS = {"v": "https://schema.gradle.org/dependency-verification"}
META = "android/gradle/verification-metadata.xml"


COORD = "io.github.junkfood02.youtubedl-android"


def error(message: str, file: str = META) -> None:
    print(f"::error file={file}::{message}")


def build_ytdl_version(android: Path) -> str | None:
    """Versión de youtubedl-android que usa el build de Gradle, o None si ningún archivo la nombra.
    Copia de la misma función de sources_mirror.py (los scripts corren con -I y no se importan entre sí)."""
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


def main() -> int:
    version = build_ytdl_version(ANDROID)
    if not version:
        print("El build de Gradle no usa youtubedl-android: no hay pins que cruzar.")
        return 0
    components = json.loads((ANDROID / "third_party/components.json").read_text(encoding="utf-8"))
    pins = {coord: v for c in components["components"] for coord, v in (c.get("maven") or {}).items()}
    if not pins:
        error("components.json no fija los AAR de youtubedl-android (campo maven)", "android/third_party/components.json")
        return 1
    status = 0
    for coord in pins:
        if coord.rsplit(":", 1)[1] != version:
            error(f"{coord}: Gradle usa la versión {version}", "android/third_party/components.json")
            status = 1
    meta = ANDROID / "gradle/verification-metadata.xml"
    if not meta.exists():
        error("Falta. Genéralo en Docker: kdl-build --write-verification-metadata sha256 help (android/README.md)")
        return 1
    root = ET.parse(meta).getroot()
    if root.findtext("v:configuration/v:verify-metadata", namespaces=NS) != "true":
        error("verify-metadata debe ser true")
        return 1
    for coord, want in sorted(pins.items()):
        group, name, version = coord.split(":")
        found = None
        for comp in root.iterfind("v:components/v:component", NS):
            if (comp.get("group"), comp.get("name"), comp.get("version")) == (group, name, version):
                art = comp.find(f"v:artifact[@name='{name}-{version}.aar']", NS)
                sha = art.find("v:sha256", NS) if art is not None else None
                found = sha.get("value") if sha is not None else None
        if found != want["aar_sha256"]:
            error(f"{coord}: sha256 {found}, components.json dice {want['aar_sha256']}")
            status = 1
        else:
            print(f"{coord}: {found[:12]}… coincide")
    return status


if __name__ == "__main__":
    sys.exit(main())
