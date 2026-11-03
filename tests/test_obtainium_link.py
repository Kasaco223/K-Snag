"""El botón «Agregar a Obtainium» de /android lleva toda la configuración dentro del enlace.

Obtainium elige el Release de Android por su título y saca la versión del tag. Si cambia el título
de los Releases (.github/workflows/android-release.yml) o el formato de los tags, el botón dejaría
de encontrar las actualizaciones sin que nadie lo note: estos tests avisan antes.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
PAGE = ROOT / "web" / "android.html"
WORKFLOW = ROOT / ".github" / "workflows" / "android-release.yml"
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
PREFIX = "obtainium://app/"


def _config() -> tuple[dict, dict]:
    html = PAGE.read_text(encoding="utf-8")
    links = re.findall(r'href="(obtainium://app/[^"]+)"', html)
    assert len(links) == 1, "android.html debe tener un solo enlace obtainium://app/"
    config = json.loads(unquote(links[0].removeprefix(PREFIX)))
    return config, json.loads(config["additionalSettings"])


def _android_title(version: str) -> str:
    text = WORKFLOW.read_text(encoding="utf-8")
    match = re.search(r'--title "([^"$]*)\$\{VERSION\}"', text)
    assert match, "no encontré el --title del Release en android-release.yml"
    return match.group(1) + version


def test_identifica_la_app():
    config, _ = _config()
    gradle = GRADLE.read_text(encoding="utf-8")
    app_id = re.search(r'applicationId = "([^"]+)"', gradle)
    assert app_id
    assert config["id"] == app_id.group(1)
    assert config["url"] == "https://github.com/Kasaco223/K-Snag"
    assert config["name"] == "K-Snag"


def test_solo_toma_releases_estables_de_android():
    _, settings = _config()
    title = re.compile(settings["filterReleaseTitlesByRegEx"])
    assert title.search(_android_title("1.0.4"))
    assert title.search(_android_title("12.3.45"))
    assert not title.search(_android_title("1.1.0-rc.1"))
    assert not title.search("K-Snag 2.0.5")  # Windows
    assert not title.search("Código fuente de terceros · ytdl-0.18.1-r1")
    # Android se publica siempre como pre-release, y el Release más reciente puede ser de Windows.
    assert settings["includePrereleases"] is True
    assert settings["fallbackToOlderReleases"] is True


def test_saca_la_version_del_tag():
    _, settings = _config()
    version = re.compile(settings["versionExtractionRegEx"])
    assert version.findall("android-v1.0.4")[-1] == "1.0.4"
    assert version.findall("android-v12.3.45")[-1] == "12.3.45"
