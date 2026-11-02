# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prueba sin red promote.py --check con los dos canales. Uso: python -I test_promote_channels.py <promote.py> <vercel.json>"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

script, vercel = sys.argv[1], sys.argv[2]
REPO = "Kasaco223/K-YoutubeVideoDownloaderHD"


def manifest(version, codes, mvc=None):
    tag = f"android-v{version}"
    base = f"https://github.com/{REPO}/releases/download/{tag}/"
    return {"schema": 1, "version": version, "tag": tag, "package": "io.github.kasaco223.kdownloader", "min_sdk": 26,
            "notes_url": f"https://github.com/{REPO}/releases/tag/{tag}", "cert_sha256": "a" * 64,
            "published_at": "2026-11-15T12:00:00Z", "min_version_code": mvc,
            "apks": {abi: {"url": f"{base}K-Downloader-Android-{abi}.apk", "sha256": "b" * 64, "size": 100,
                           "version_code": c} for abi, c in codes.items()}}


STABLE = manifest("1.0.0", {"armeabi-v7a": 10000991, "arm64-v8a": 10000993, "x86_64": 10000994})
RC = manifest("1.1.0-rc.2", {"armeabi-v7a": 10100021, "arm64-v8a": 10100023, "x86_64": 10100024})


def case(name, stable, rc, want):
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "web/data").mkdir(parents=True)
        shutil.copy(vercel, root / "web/vercel.json")
        if stable is not None and stable["tag"] == STABLE["tag"]:
            # Las redirecciones de Android del web/vercel.json real apuntan a la versión publicada hoy: se
            # reescriben como las dejaría `promote.py --tag` para la versión de prueba, así el caso no depende
            # de qué versión esté publicada.
            cfg = json.loads((root / "web/vercel.json").read_text(encoding="utf-8"))
            base = f"https://github.com/{REPO}/releases/download/{STABLE['tag']}/K-Downloader-Android-"
            wanted = {"/android/descargar": "arm64-v8a", **{f"/android/descargar/{a}": a for a in ("arm64-v8a", "armeabi-v7a", "x86_64")}}
            cfg["redirects"] = [r for r in cfg.get("redirects", []) if r.get("source") not in wanted] + [
                {"source": src, "destination": f"{base}{abi}.apk", "permanent": False} for src, abi in wanted.items()
            ]
            (root / "web/vercel.json").write_text(json.dumps(cfg, indent=2), encoding="utf-8")
        if stable is not None:
            (root / "web/data/android.json").write_text(json.dumps(stable), encoding="utf-8")
        if rc is not None:
            (root / "web/data/android-rc.json").write_text(json.dumps(rc), encoding="utf-8")
        r = subprocess.run([sys.executable, "-I", "-X", "utf8", script, "--check"], env=dict(os.environ, KD_ROOT=d, PYTHONIOENCODING="utf-8"),
                           capture_output=True, text=True, encoding="utf-8")
        ok = r.returncode == want
        print("OK  " if ok else "FAIL", name, "rc=", r.returncode, "|", r.stdout.strip().splitlines()[-1])
        if not ok:
            print(r.stdout, r.stderr)
        return ok


res = [
    case("estable + rc válidos", STABLE, RC, 0),
    case("solo estable", STABLE, None, 0),
    case("rc en el archivo estable", RC, None, 1),
    case("estable en el archivo rc", STABLE, STABLE, 1),
    case("min_version_code en rc", STABLE, {**RC, "min_version_code": 10100021}, 1),
    case("min_version_code mayor que un version_code", {**STABLE, "min_version_code": 10000994}, None, 1),
    case("min_version_code válido", {**STABLE, "min_version_code": 10000991}, None, 0),
]
print(f"{sum(res)}/{len(res)} casos OK")
sys.exit(0 if all(res) else 1)
