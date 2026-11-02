# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prueba notices.py sin red. Uso: python3 -I -X utf8 test_notices.py <notices.py>"""
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

script = sys.argv[1]
SHA = "a" * 64


def base():
    return {
        "schema": 1,
        "mirror_release": {"tag": "android-sources-ytdl-0.18.1-r1"},
        "recipes_snapshot": {"repo": "https://github.com/termux/termux-packages", "commit": "8" * 40, "note": "Prueba."},
        "components": [
            {"id": "youtubedl-android", "name": "youtubedl-android", "version": "0.18.1", "spdx": "GPL-3.0-only",
             "mirror": True, "sources": [{"url": "https://x/y.tar.gz", "file": "youtubedl-android-0.18.1.tar.gz", "sha256": SHA}],
             "maven": {"g:library:0.18.1": {"aar_sha256": SHA, "aar_size": 1}}, "license_files": ["youtubedl-android/LICENSE"]},
            {"id": "ffmpeg", "name": "FFmpeg", "version": "7.1.1", "spdx": "GPL-3.0-or-later", "mirror": True,
             "sources": [{"url": "https://x/ffmpeg-7.1.1.tar.xz", "file": "ffmpeg-7.1.1.tar.xz", "sha256": SHA}],
             "files": ["jni:libffmpeg.so", "libffmpeg.zip.so:usr/lib/libav*.so*"], "license_files": ["ffmpeg/COPYING.GPLv3"]},
            {"id": "freetype", "name": "FreeType", "version": "2.14.1", "spdx": "FTL OR GPL-2.0-or-later", "mirror": False,
             "recipe": "packages/freetype", "files": ["libffmpeg.zip.so:usr/lib/libfreetype.so*"], "license_files": ["freetype/FTL.TXT"]},
            {"id": "python", "name": "CPython", "version": "3.12.11", "spdx": "PSF-2.0", "mirror": False,
             "sources": [{"url": "https://x/Python-3.12.11.tar.xz", "file": "Python-3.12.11.tar.xz", "sha256": SHA}],
             "files": ["jni:libpython.so", "libpython.zip.so:usr/lib/python3.12/**"], "license_files": ["python/LICENSE"]},
            {"id": "ca-certificates", "name": "CA", "version": "2025-09-09", "spdx": "MPL-2.0", "mirror": False,
             "mirror_exempt": "Va sin cambios.", "recipe": "packages/ca-certificates", "files": ["libpython.zip.so:usr/etc/tls/cert.pem"],
             "license_files": ["common/MPL-2.0.txt"]},
            {"id": "yt-dlp", "name": "yt-dlp", "version": "2026.10.29", "spdx": "Unlicense", "mirror": False,
             "sources": [{"url": "https://x/yt-dlp", "file": "yt-dlp", "sha256": SHA}],
             "files": ["apk:res/raw/ytdlp"], "license_files": ["common/MPL-2.0.txt"]},
            {"id": "androidx", "name": "AndroidX", "match": "androidx.*:*", "spdx": "Apache-2.0", "mirror": False,
             "license_files": ["common/MPL-2.0.txt"]},
        ]}


LICS = ["youtubedl-android/LICENSE", "ffmpeg/COPYING.GPLv3", "freetype/FTL.TXT", "python/LICENSE", "common/MPL-2.0.txt"]


def tree(d, data, lics=LICS):
    root = Path(d)
    tp = root / "android/third_party"
    for f in lics:
        (tp / "licenses" / f).parent.mkdir(parents=True, exist_ok=True)
        (tp / "licenses" / f).write_text("texto\n", encoding="utf-8")
    (tp / "components.json").write_text(json.dumps(data), encoding="utf-8")
    return root


def run(root, *args):
    e = dict(os.environ, KD_ROOT=str(root), PYTHONIOENCODING="utf-8")
    return subprocess.run([sys.executable, "-I", "-X", "utf8", script, *args], env=e, capture_output=True, text=True)


results = []


def check(name, ok, r):
    print(("OK  " if ok else "FAIL"), name)
    if not ok:
        print(r.stdout, r.stderr)
    results.append(ok)


def case(name, mutate, want, lics=LICS):
    with tempfile.TemporaryDirectory() as d:
        data = base()
        mutate(data)
        root = tree(d, data, lics)
        gen = run(root)
        r = run(root, "--check")
        check(f"--check: {name}", r.returncode == want and (want == 1 or gen.returncode == 0), r)


def comp(data, cid):
    return next(c for c in data["components"] if c["id"] == cid)


case("inventario válido", lambda d: None, 0)
case("copyleft sin espejo", lambda d: comp(d, "ffmpeg").update(mirror=False), 1)
case("MPL sin espejo ni mirror_exempt", lambda d: comp(d, "ca-certificates").pop("mirror_exempt"), 1)
case("espejo por http", lambda d: comp(d, "ffmpeg")["sources"][0].update(url="http://x/ffmpeg-7.1.1.tar.xz"), 1)
case("espejo sin SHA-256", lambda d: comp(d, "ffmpeg")["sources"][0].update(sha256="<rellenar>"), 1)
case("dos fuentes del espejo con el mismo nombre",
     lambda d: comp(d, "ffmpeg")["sources"][0].update(file="youtubedl-android-0.18.1.tar.gz"), 1)
case("nombre de archivo con ruta", lambda d: comp(d, "ffmpeg")["sources"][0].update(file="../ffmpeg.tar.xz"), 1)
case("ruta de payload inválida", lambda d: comp(d, "python")["files"].append("usr/lib/libz.so"), 1)
case("ruta apk: fuera de res/, assets/ o lib/", lambda d: comp(d, "yt-dlp")["files"].append("apk:META-INF/x.so"), 1)
case("componente sin files", lambda d: comp(d, "python").pop("files"), 1)
case("componente sin fuente ni receta", lambda d: comp(d, "freetype").pop("recipe"), 1)
case("licencia citada que no existe", lambda d: comp(d, "python")["license_files"].append("python/NOTICE"), 1)
case("licencia que nadie cita", lambda d: None, 1, lics=LICS + ["huerfana/COPYING"])
case("id repetido", lambda d: d["components"].append(dict(comp(d, "python"))), 1)
case("etiqueta del espejo sin revisión", lambda d: d["mirror_release"].update(tag="android-sources-ytdl-0.18.1"), 1)
case("etiqueta de otra versión", lambda d: d["mirror_release"].update(tag="android-sources-ytdl-0.19.0-r1"), 1)
case("ruta de licencia absoluta", lambda d: comp(d, "python")["license_files"].append("/etc/passwd"), 1)
case("SPDX con paréntesis y copyleft", lambda d: comp(d, "freetype").update(spdx="(FTL OR GPL-2.0-or-later) AND MIT"), 1)

with tempfile.TemporaryDirectory() as d:
    root = tree(d, base())
    run(root)
    notices = root / "android/THIRD_PARTY_NOTICES.md"
    text = notices.read_text(encoding="utf-8")
    check("el aviso lleva SPDX, el enlace al espejo y cada componente",
          "SPDX-License-Identifier: GPL-3.0-or-later" in text and "releases/tag/android-sources-ytdl-0.18.1" in text
          and all(c["name"] in text for c in base()["components"]), subprocess.CompletedProcess([], 0, text, ""))
    notices.write_text(text.replace("FreeType", "Freetype"), encoding="utf-8")
    r = run(root, "--check")
    check("--check: aviso desactualizado", r.returncode == 1, r)


def aar(path, jni, payloads):
    with zipfile.ZipFile(path, "w") as z:
        for abi in ("arm64-v8a", "x86_64"):
            for name in jni:
                z.writestr(f"jni/{abi}/{name}", b"\x7fELF")
            for pname, files in payloads.items():
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, "w") as inner:
                    for f in files:
                        inner.writestr(f, b"x")
                z.writestr(f"jni/{abi}/{pname}", buf.getvalue())


def payload_case(name, ffmpeg_files, want, extra_jni=(), python_files=()):
    with tempfile.TemporaryDirectory() as d:
        root = tree(d, base())
        lib, ff = root / "library.aar", root / "ffmpeg.aar"
        aar(lib, ["libpython.so", *extra_jni], {"libpython.zip.so": ["usr/lib/python3.12/os.py", "usr/etc/tls/cert.pem", *python_files]})
        aar(ff, ["libffmpeg.so"], {"libffmpeg.zip.so": ffmpeg_files})
        r = run(root, "--payload", str(lib), str(ff))
        check(f"--payload: {name}", r.returncode == want, r)


payload_case("cada archivo tiene dueño", ["usr/lib/libavcodec.so.61", "usr/lib/libfreetype.so"], 0)
payload_case("archivo sin dueño", ["usr/lib/libavcodec.so.61", "usr/lib/libfreetype.so", "usr/lib/libnuevo.so"], 1)
payload_case("lanzador sin dueño", ["usr/lib/libavcodec.so.61", "usr/lib/libfreetype.so"], 1, extra_jni=["libqjs.so"])
payload_case("patrón que no encuentra nada", ["usr/lib/libavcodec.so.61"], 1)
payload_case("paquete nuevo en site-packages (no es de CPython)", ["usr/lib/libavcodec.so.61", "usr/lib/libfreetype.so"], 1,
             python_files=["usr/lib/python3.12/site-packages/certifi/cacert.pem"])

print(f"{sum(results)}/{len(results)} casos OK")
sys.exit(0 if all(results) else 1)
