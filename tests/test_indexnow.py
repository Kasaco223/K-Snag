"""La clave de IndexNow (web/<clave>.txt) tiene que existir una sola vez y contener su propio nombre."""

from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("indexnow", ROOT / "scripts" / "indexnow.py")
indexnow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(indexnow)


def test_clave_valida():
    assert len(indexnow.key()) == 32


def test_urls_del_sitemap_son_del_sitio():
    found = indexnow.urls()
    assert found
    assert all(u.startswith(f"https://{indexnow.HOST}/") for u in found)
