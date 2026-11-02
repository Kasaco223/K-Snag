"""engine.fetch: sumas SHA-256, descargas verificadas y atómicas, cancelación y resolución de la última versión."""

from __future__ import annotations

import hashlib
import os
import threading
import urllib.error
from pathlib import Path

import pytest

from kdownloader.engine import fetch
from kdownloader.engine.fetch import Cancelled, IntegrityError, find_sha256

H1 = "36de87e6276c4bbaa2d9fd4d1295b25f4e824fe9bbfa20e05e175fd0cecee858"
H2 = "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba"
H3 = "a0c3101b4158d1dfb7d6a78a7bf0f3de80c96bb423c152beec8beb22786f2238"

# Igual que SHA2-256SUMS de yt-dlp: "hash  nombre" (GNU coreutils).
GNU_SUMS = f"""{H2}  yt-dlp.exe
{H3}  yt-dlp_win.zip
{H1}  yt-dlp
{"0" * 64}  yt-dlp.tar.gz
"""


# ----------------------------------------------------------------------------- find_sha256


def test_find_sha256_gnu_exact_name():
    assert find_sha256(GNU_SUMS, "yt-dlp") == H1  # no confunde "yt-dlp" con "yt-dlp.exe"
    assert find_sha256(GNU_SUMS, "yt-dlp.exe") == H2
    assert find_sha256(GNU_SUMS, "yt-dlp_win.zip") == H3


def test_find_sha256_star_prefixed_binary_mode():
    sums = f"{H1} *ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip\n{H2} *ffmpeg-master-latest-win64-gpl.zip\n"
    assert find_sha256(sums, "ffmpeg-n9.0-latest-win64-lgpl-shared-9.0.zip") == H1
    assert find_sha256(sums, "ffmpeg-master-latest-win64-gpl.zip") == H2


def test_find_sha256_crlf_and_uppercase():
    sums = f"{H1.upper()}  yt-dlp\r\n{H2}  other\r\n"
    assert find_sha256(sums, "yt-dlp") == H1  # siempre en minúsculas


def test_find_sha256_missing_name():
    assert find_sha256(GNU_SUMS, "yt-dlp_macos") is None
    assert find_sha256(GNU_SUMS, "dlp") is None
    assert find_sha256("", "yt-dlp") is None


def test_find_sha256_ignores_malformed_hashes():
    sums = f"{H1[:-1]}  yt-dlp\nzz{H1[2:]}  yt-dlp\n"
    assert find_sha256(sums, "yt-dlp") is None


def test_find_sha256_single_hash_file():
    # gyan.dev: ffmpeg-release-essentials.zip.sha256 trae solo el hash
    assert find_sha256(f"{H2}\n") == H2
    assert find_sha256(f"{H2}  ffmpeg-release-essentials.zip\n") == H2
    assert find_sha256("sin hash aquí") is None
    assert find_sha256("") is None


def test_find_sha256_powershell_get_filehash_list_style():
    # Deno publica `.sha256sum` generado con Get-FileHash | Format-List (hash en mayúsculas)
    text = (
        "\r\n"
        "Algorithm : SHA256\r\n"
        f"Hash      : {H3.upper()}\r\n"
        "Path      : D:\\a\\deno\\deno\\target\\release\\deno-x86_64-pc-windows-msvc.zip\r\n"
        "\r\n"
    )
    assert find_sha256(text) == H3


def test_find_sha256_powershell_get_filehash_table_style():
    text = (
        "Algorithm       Hash                                                                   Path\n"
        "---------       ----                                                                   ----\n"
        f"SHA256          {H1.upper()}       C:\\build\\deno-x86_64-pc-windows-msvc.zip\n"
    )
    assert find_sha256(text) == H1


def test_find_sha256_first_hash_without_name():
    assert find_sha256(GNU_SUMS) == H2


# ----------------------------------------------------------------------------- download


@pytest.fixture
def dl(tmp_path: Path) -> Path:
    """Carpeta vacía solo para las descargas de cada prueba."""
    folder = tmp_path / "dl"
    folder.mkdir()
    return folder


PAYLOAD = os.urandom(700 * 1024)  # varios bloques de 256 KB
PAYLOAD_SHA = hashlib.sha256(PAYLOAD).hexdigest()


def test_download_ok_is_atomic(http_server, dl: Path):
    url = http_server.add("/files/yt-dlp", PAYLOAD)
    dest = dl / "engine" / "ytdlp" / "tag" / "yt-dlp.pyz"
    part = dest.with_name(dest.name + ".part")
    seen: list[tuple[int, int]] = []

    def on_progress(done: int, total: int) -> None:
        seen.append((done, total))
        assert not dest.exists(), "el destino final no debe aparecer antes de verificar el hash"
        assert part.exists()

    digest = fetch.download(url, dest, expected_sha256=PAYLOAD_SHA, on_progress=on_progress)
    assert digest == PAYLOAD_SHA
    assert dest.read_bytes() == PAYLOAD
    assert not part.exists()
    assert seen[0] == (0, len(PAYLOAD))
    assert seen[-1] == (len(PAYLOAD), len(PAYLOAD))
    dones = [d for d, _ in seen]
    assert dones == sorted(dones)
    assert sorted(p.name for p in dest.parent.iterdir()) == ["yt-dlp.pyz"]


def test_download_accepts_uppercase_expected_hash(http_server, dl: Path):
    url = http_server.add("/f", b"hola")
    dest = dl / "f.bin"
    assert fetch.download(url, dest, expected_sha256=hashlib.sha256(b"hola").hexdigest().upper()) == \
        hashlib.sha256(b"hola").hexdigest()
    assert dest.read_bytes() == b"hola"


def test_download_replaces_existing_file(http_server, dl: Path):
    url = http_server.add("/f", PAYLOAD)
    dest = dl / "f.bin"
    dest.write_bytes(b"version vieja")
    fetch.download(url, dest, expected_sha256=PAYLOAD_SHA)
    assert dest.read_bytes() == PAYLOAD


def test_download_wrong_hash_leaves_nothing(http_server, dl: Path):
    url = http_server.add("/f", PAYLOAD)
    dest = dl / "f.bin"
    with pytest.raises(IntegrityError):
        fetch.download(url, dest, expected_sha256="0" * 64)
    assert not dest.exists()
    assert list(dl.iterdir()) == []  # el .part también se borró


def test_download_wrong_hash_keeps_previous_good_file(http_server, dl: Path):
    url = http_server.add("/f", b"contenido alterado")
    dest = dl / "f.bin"
    dest.write_bytes(b"version buena")
    with pytest.raises(IntegrityError):
        fetch.download(url, dest, expected_sha256=PAYLOAD_SHA)
    assert dest.read_bytes() == b"version buena"
    assert sorted(p.name for p in dl.iterdir()) == ["f.bin"]


@pytest.mark.parametrize("missing", [None, ""])
def test_download_without_expected_hash_refuses(http_server, dl: Path, missing):
    url = http_server.add("/f", PAYLOAD)
    dest = dl / "sub" / "f.bin"
    with pytest.raises(IntegrityError):
        fetch.download(url, dest, expected_sha256=missing)
    assert http_server.requests == []  # ni siquiera se conecta
    assert not dest.exists() and not dest.with_name("f.bin.part").exists()


def test_download_cancel(http_server, dl: Path):
    url = http_server.add("/slow", PAYLOAD * 4, chunk_delay=0.01)
    dest = dl / "f.bin"
    cancel = threading.Event()

    def on_progress(done: int, total: int) -> None:
        if done > 0:
            cancel.set()

    with pytest.raises(Cancelled):
        fetch.download(url, dest, expected_sha256="0" * 64, on_progress=on_progress, cancel=cancel)
    assert list(dl.iterdir()) == []


def test_download_cancel_before_start(http_server, dl: Path):
    url = http_server.add("/f", PAYLOAD)
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(Cancelled):
        fetch.download(url, dl / "f.bin", expected_sha256=PAYLOAD_SHA, cancel=cancel)
    assert list(dl.iterdir()) == []


def test_download_http_error_leaves_nothing(http_server, dl: Path):
    url = http_server.url("/no-existe")
    with pytest.raises(urllib.error.HTTPError) as info:
        fetch.download(url, dl / "f.bin", expected_sha256=PAYLOAD_SHA)
    assert info.value.code == 404
    assert list(dl.iterdir()) == []


def test_download_sends_user_agent(http_server, dl: Path, monkeypatch: pytest.MonkeyPatch):
    captured: list[str] = []
    real = fetch._request

    def spy(url: str, method: str = "GET"):
        req = real(url, method)
        captured.append(req.get_header("User-agent"))
        return req

    monkeypatch.setattr(fetch, "_request", spy)
    url = http_server.add("/f", b"x")
    fetch.download(url, dl / "f", expected_sha256=hashlib.sha256(b"x").hexdigest())
    assert captured and captured[0].startswith("K-Snag/")


def test_sha256_file(dl: Path):
    p = dl / "a.bin"
    p.write_bytes(PAYLOAD)
    assert fetch.sha256_file(p) == PAYLOAD_SHA


def test_get_text(http_server):
    url = http_server.add("/sums", GNU_SUMS)
    assert fetch.get_text(url) == GNU_SUMS
    assert len(fetch.get_text(url, limit=10)) == 10
    bad = http_server.add("/latin", b"caf\xe9")
    assert fetch.get_text(bad) == "caf\ufffd"  # nunca lanza por la codificación


# ----------------------------------------------------------------------------- latest_tag


@pytest.fixture
def github(http_server, monkeypatch: pytest.MonkeyPatch):
    """Redirige https://github.com/... al servidor local, sin tocar el resto de fetch."""
    real = fetch._request

    def local(url: str, method: str = "GET"):
        assert url.startswith("https://github.com/")
        return real(http_server.base + url[len("https://github.com"):], method)

    monkeypatch.setattr(fetch, "_request", local)
    return http_server


@pytest.mark.parametrize(
    ("repo", "location", "tag"),
    [
        ("yt-dlp/yt-dlp-nightly-builds",
         "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/2026.09.27.232945", "2026.09.27.232945"),
        ("yt-dlp/yt-dlp", "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19", "2026.08.19"),
        ("denoland/deno", "https://github.com/denoland/deno/releases/tag/v2.9.7", "v2.9.7"),
        ("x/y", "https://github.com/x/y/releases/tag/v1.0%2Bbuild", "v1.0+build"),  # se decodifica
    ],
)
def test_latest_tag_follows_redirect_without_api(github, repo, location, tag):
    github.add(f"/{repo}/releases/latest", status=302, headers={"Location": location})
    assert fetch.latest_tag(repo) == tag
    assert github.requests == [("HEAD", f"/{repo}/releases/latest")]  # un solo HEAD, sin seguir el redirect


def test_latest_tag_without_releases(github):
    with pytest.raises(RuntimeError, match="x/none"):
        fetch.latest_tag("x/none")  # 404


def test_latest_tag_redirect_elsewhere(github):
    github.add("/x/y/releases/latest", status=302, headers={"Location": "https://github.com/x/y/releases"})
    with pytest.raises(RuntimeError):
        fetch.latest_tag("x/y")


def test_latest_tag_unexpected_200(github):
    github.add("/x/y/releases/latest", body="<html>", status=200)
    with pytest.raises(RuntimeError):
        fetch.latest_tag("x/y")
