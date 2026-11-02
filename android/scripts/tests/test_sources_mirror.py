# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prueba sources_mirror.py sin red. Uso: python3 -I -X utf8 test_sources_mirror.py <sources_mirror.py>"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

script = sys.argv[1]
COMMIT = "8ddd9a7fc8e06330545487d81bb7591cb40f1aa6"
SNAP = f"termux-packages-{COMMIT[:12]}.tar.gz"
CAT = '[versions]\nyoutubedl = "0.18.1"\n\n[libraries]\nyoutubedl-library = { module = "io.github.junkfood02.youtubedl-android:library", version.ref = "youtubedl" }\n'
BLOBS = {"ffmpeg-7.1.1.tar.xz": b"ffmpeg", "readline-8.3.tar.gz": b"readline", "readline83-001": b"patch",
         "srt-1.5.4.tar.gz": b"srt", "youtubedl-android-0.18.1.tar.gz": b"ytdl"}
SHA = {k: hashlib.sha256(v).hexdigest() for k, v in BLOBS.items()}


def src(name, url=None):
    return {"url": url or f"https://example.org/{name}", "file": name, "sha256": SHA[name]}


COMPONENTS = {
    "schema": 1,
    "mirror_release": {"tag": "android-sources-ytdl-0.18.1-r1"},
    "recipes_snapshot": {"repo": "https://github.com/termux/termux-packages", "commit": COMMIT, "note": "Prueba."},
    "components": [
        {"id": "youtubedl-android", "name": "youtubedl-android", "version": "0.18.1", "spdx": "GPL-3.0-only",
         "mirror": True, "sources": [src("youtubedl-android-0.18.1.tar.gz")]},
        {"id": "ffmpeg", "name": "FFmpeg", "version": "7.1.1", "spdx": "GPL-3.0-or-later", "mirror": True,
         "recipe": "packages/ffmpeg", "sources": [src("ffmpeg-7.1.1.tar.xz")]},
        {"id": "readline", "name": "GNU Readline", "version": "8.3", "spdx": "GPL-3.0-or-later", "mirror": True,
         "recipe": "packages/readline", "sources": [src("readline-8.3.tar.gz"), src("readline83-001")]},
        {"id": "libsrt", "name": "SRT", "version": "1.5.4", "spdx": "MPL-2.0", "mirror": True,
         "recipe": "packages/libsrt", "sources": [src("srt-1.5.4.tar.gz")]},
        {"id": "zlib", "name": "zlib", "version": "1.3.1", "spdx": "Zlib", "mirror": False,
         "recipe": "packages/zlib", "sources": [{"url": "https://x/zlib.tar.xz", "file": "zlib.tar.xz", "sha256": "0" * 64}]},
        {"id": "androidx", "name": "AndroidX", "match": "androidx.*:*", "spdx": "Apache-2.0", "mirror": False},
    ]}


def tree(d, catalog=CAT, components=None):
    root = Path(d)
    (root / "android/third_party").mkdir(parents=True)
    (root / "android/gradle").mkdir(parents=True)
    (root / "android/gradle/libs.versions.toml").write_text(catalog, encoding="utf-8")
    (root / "android/third_party/components.json").write_text(json.dumps(components or COMPONENTS), encoding="utf-8")
    return root


def run(root, *args, **env):
    e = dict(os.environ, KD_ROOT=str(root), PYTHONIOENCODING="utf-8", **env)
    return subprocess.run([sys.executable, "-I", "-X", "utf8", script, *args], env=e, capture_output=True, text=True)


results = []


def check(name, ok, r=None):
    print(("OK  " if ok else "FAIL"), name)
    if not ok and r is not None:
        print(r.stdout, r.stderr)
    results.append(ok)


def verify_case(name, release, want, catalog=CAT):
    with tempfile.TemporaryDirectory() as d:
        root = tree(d, catalog)
        rel = root / "rel.json"
        rel.write_text(json.dumps(release), encoding="utf-8")
        r = run(root, "--verify", KDL_MIRROR_RELEASE_JSON=str(rel))
        check(f"--verify: {name}", r.returncode == want, r)


def asset(name, sha=None):
    return {"name": name, "digest": f"sha256:{sha or SHA.get(name, 'd' * 64)}"}


full = [asset(n) for n in BLOBS] + [asset("FUENTES.md"), asset("SHA256SUMS.txt"), asset(SNAP)]
pub = {"draft": False, "prerelease": True, "published_at": "2026-11-01T00:00:00Z"}
without = lambda n: [a for a in full if a["name"] != n]  # noqa: E731
verify_case("completo y publicado", {**pub, "assets": full}, 0)
verify_case("borrador", {**pub, "draft": True, "published_at": None, "assets": full}, 1)
verify_case("no es pre-release", {**pub, "prerelease": False, "assets": full}, 1)
verify_case("falta SRT (MPL-2.0)", {**pub, "assets": without("srt-1.5.4.tar.gz")}, 1)
verify_case("falta el parche de readline (segunda fuente)", {**pub, "assets": without("readline83-001")}, 1)
verify_case("falta la copia de las recetas", {**pub, "assets": without(SNAP)}, 1)
verify_case("falta FUENTES.md", {**pub, "assets": without("FUENTES.md")}, 1)
verify_case("SHA-256 distinto", {**pub, "assets": without("ffmpeg-7.1.1.tar.xz") + [asset("ffmpeg-7.1.1.tar.xz", "e" * 64)]}, 1)
verify_case("Release inexistente (404)", None, 1)
verify_case("sin youtubedl en el catálogo", {}, 0, catalog='coil = "3.6.3"\n')
verify_case("catálogo con otra versión de youtubedl", {**pub, "assets": full}, 1, catalog=CAT.replace("0.18.1", "0.19.0"))
verify_case("error de la API distinto de 404", {"http_error": 503}, 1)
verify_case("clave del catálogo en camelCase", {**pub, "assets": full}, 0,
            catalog=CAT.replace("youtubedl =", "youtubedlAndroid =").replace('"youtubedl" }', '"youtubedlAndroid" }'))
verify_case("versión escrita en la línea del catálogo (sin espejo)", None, 1,
            catalog='[libraries]\nytdl = { module = "io.github.junkfood02.youtubedl-android:library", version = "0.18.1" }\n')
verify_case("nombrado sin versión que se pueda resolver", {**pub, "assets": full}, 1,
            catalog='[libraries]\nytdl = { module = "io.github.junkfood02.youtubedl-android:library", version.ref = "nada" }\n')

with tempfile.TemporaryDirectory() as d:
    root = tree(d, catalog='[versions]\ncoil = "3.6.3"\n')
    (root / "android/app").mkdir()
    (root / "android/app/build.gradle.kts").write_text(
        'dependencies { implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1") }\n', encoding="utf-8")
    rel = root / "rel.json"
    rel.write_text("null", encoding="utf-8")
    r = run(root, "--verify", KDL_MIRROR_RELEASE_JSON=str(rel))
    check("--verify: dependencia directa en build.gradle.kts (fuera del catálogo)", r.returncode == 1, r)

with tempfile.TemporaryDirectory() as d:
    root = tree(d)
    fake = root / "fake"
    fake.mkdir()
    for n, b in BLOBS.items():
        (fake / n).write_bytes(b)
    out = root / "mirror"
    out.mkdir()
    (out / SNAP).write_bytes(b"recetas")
    r = run(root, "--build", str(out), KDL_MIRROR_FAKE_DOWNLOADS=str(fake))
    sums = (out / "SHA256SUMS.txt").read_text(encoding="utf-8") if (out / "SHA256SUMS.txt").exists() else ""
    md = (out / "FUENTES.md").read_text(encoding="utf-8") if (out / "FUENTES.md").exists() else ""
    listed = {line.split("  ", 1)[1] for line in sums.splitlines()}
    check("--build: baja las 5 fuentes, FUENTES.md y SHA256SUMS.txt",
          r.returncode == 0 and listed == {*BLOBS, "FUENTES.md", SNAP} and "readline83-001" in md
          and COMMIT in md and "zlib" not in md and "android-sources-ytdl-0.18.1-r1" in md, r)

    (fake / "srt-1.5.4.tar.gz").write_bytes(b"otra cosa")
    out2 = root / "mirror2"
    r = run(root, "--build", str(out2), KDL_MIRROR_FAKE_DOWNLOADS=str(fake))
    check("--build: rechaza un SHA-256 distinto", r.returncode != 0 and "SHA-256" in r.stdout + r.stderr
          and not (out2 / "srt-1.5.4.tar.gz").exists(), r)

    bad = json.loads(json.dumps(COMPONENTS))
    bad["components"][1]["sources"][0]["url"] = "http://example.org/ffmpeg-7.1.1.tar.xz"
    (root / "android/third_party/components.json").write_text(json.dumps(bad), encoding="utf-8")
    r = run(root, "--build", str(root / "mirror3"), KDL_MIRROR_FAKE_DOWNLOADS=str(fake))
    check("--build: solo https", r.returncode != 0 and "https" in r.stdout + r.stderr, r)

with tempfile.TemporaryDirectory() as d:
    root = tree(d)
    r = run(root, "--recipes")
    check("--recipes: commit y nombre de la copia del árbol", r.stdout.split() == [COMMIT, SNAP], r)
    r = run(root, "--tag")
    check("--tag: la etiqueta con revisión", r.stdout.strip() == "android-sources-ytdl-0.18.1-r1", r)

print(f"{sum(results)}/{len(results)} casos OK")
sys.exit(0 if all(results) else 1)
