"""Motor real contra YouTube (opt-in): `pytest -m network tests/network`.

Usa un EngineManager de verdad en una carpeta temporal e instala yt-dlp (zipapp nightly), FFmpeg (gyan.dev)
y Deno con verificación SHA-256. Variables de entorno:

- KD_TEST_PYTHON: intérprete que corre el motor (por defecto, el Python embebible 3.13 de la Fase 0).
  Debe tener los extras de yt-dlp (curl_cffi, brotli, websockets, mutagen, pycryptodomex, certifi, requests).
- KD_TEST_ENGINE_SEED: carpeta `engine` ya instalada (con manifest.json) para copiarla y no bajar ~150 MB.
"""

from __future__ import annotations

import json
import os
import shutil
import threading
from pathlib import Path
from typing import Any

import pytest

from kdownloader.engine import ytdlp
from kdownloader.engine.binaries import MIN_YTDLP, EngineManager
from kdownloader.engine.presets import Choice, build_args, output_template
from kdownloader.engine.process import run_capture
from kdownloader.engine.progress import KdlEvent, stream_kind
from kdownloader.engine.versions import semver_at_least, ytdlp_at_least

pytestmark = pytest.mark.network

BBB = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
DOWNLOAD_TIMEOUT = 300


@pytest.fixture(scope="module")
def engine_python() -> Path:
    # Un Python con los extras de yt-dlp, p. ej. <instalación>\runtime\python.exe de K-Snag.
    value = os.environ.get("KD_TEST_PYTHON")
    if not value:
        pytest.skip("Define KD_TEST_PYTHON con el intérprete del motor (p. ej. runtime\\python.exe)")
    python = Path(value)
    if not python.is_file():
        pytest.skip(f"No está el intérprete del motor ({python})")
    return python


@pytest.fixture(scope="module")
def manager(tmp_path_factory: pytest.TempPathFactory, engine_python: Path) -> EngineManager:
    root = tmp_path_factory.mktemp("live") / "engine"
    seed = os.environ.get("KD_TEST_ENGINE_SEED")
    if seed:
        shutil.copytree(seed, root, ignore=shutil.ignore_patterns("downloads", "*.part"))
    mgr = EngineManager(root, engine_python, use_system_tools=False)
    assert mgr.ensure(channel="nightly"), mgr.status()
    return mgr


@pytest.fixture(scope="module")
def cfg(manager: EngineManager, engine_python: Path, tmp_path_factory: pytest.TempPathFactory) -> ytdlp.EngineConfig:
    return ytdlp.EngineConfig(
        python=engine_python,
        ytdlp=manager.ytdlp_path(),
        ffmpeg_dir=manager.ffmpeg_dir(),
        deno=manager.deno_path(),
        cache_dir=tmp_path_factory.mktemp("cache"),
    )


@pytest.fixture(scope="module")
def bbb(cfg: ytdlp.EngineConfig) -> dict[str, Any]:
    info, _warnings = ytdlp.probe(cfg, BBB, timeout=180)
    return info


def test_components_ready(manager: EngineManager):
    st = manager.status()
    assert st["ready"] is True
    assert ytdlp_at_least(st["ytdlp"]["version"], MIN_YTDLP["nightly"])
    assert semver_at_least(st["ffmpeg"]["version"], "9.0")
    assert st["deno"]["state"] == "ready", st["deno"]
    assert manager.ytdlp_path().suffix == ".pyz"


def test_probe_bbb_full_quality(bbb: dict[str, Any]):
    s = ytdlp.summarize(bbb)
    assert "Big Buck Bunny" in s["title"]
    assert s["max_height"] == 2160
    assert ytdlp.is_degraded(bbb) is False
    assert s["thumbnail"] and s["thumbnail"].startswith("https://")


def test_simulate_presets(cfg: ytdlp.EngineConfig, bbb: dict[str, Any]):
    choices = {
        "recommended": build_args(Choice("recommended"), is_youtube=True),
        "max": build_args(Choice("max"), is_youtube=True, max_codec="vp9"),
        "mp3": build_args(Choice("mp3"), is_youtube=True),
    }
    res = ytdlp.simulate(cfg, bbb, choices)
    assert res["recommended"]["format_id"] == "299+140"  # H.264 1080p60 + AAC
    assert res["max"]["format_id"] == "315+251"  # VP9 4K + Opus
    assert res["mp3"]["format_id"] == "251"
    assert res["recommended"]["height"] == 1080 and res["max"]["height"] == 2160


def _download(cfg: ytdlp.EngineConfig, choice: Choice, tmp_path: Path) -> tuple[ytdlp.RunResult, list[KdlEvent]]:
    events: list[KdlEvent] = []
    run = ytdlp.DownloadRun(
        cfg, BBB, build_args(choice, is_youtube=True), output_template("title_id"),
        home=tmp_path / "Descargas ñ", temp=tmp_path / "t" / "live0001", on_event=events.append,
        extra=["--download-sections", "*10-14"],
    )
    run.start()
    timer = threading.Timer(DOWNLOAD_TIMEOUT, run.cancel)
    timer.start()
    try:
        result = run.wait()
    finally:
        timer.cancel()
    assert not result.canceled, "la descarga tardó demasiado"
    assert result.returncode == 0, "\n".join(result.lines[-20:])
    return result, events


def _streams(cfg: ytdlp.EngineConfig, path: str) -> dict[str, set[str]]:
    """Códecs por tipo de pista (la portada incrustada aparece como una pista de video extra)."""
    rc, out, err = run_capture([str(cfg.ffmpeg_dir / "ffprobe.exe"), "-v", "error", "-show_entries",
                                "stream=codec_type,codec_name", "-of", "json", path], timeout=60)
    assert rc == 0, err
    found: dict[str, set[str]] = {}
    for s in json.loads(out)["streams"]:
        found.setdefault(s["codec_type"], set()).add(s["codec_name"])
    return found


def test_download_recommended_section(cfg: ytdlp.EngineConfig, tmp_path: Path):
    result, events = _download(cfg, Choice("recommended"), tmp_path)
    assert len(result.files) == 1
    path = Path(result.files[0])
    assert path.suffix == ".mp4" and path.is_file() and path.stat().st_size > 100_000
    assert path.parent == tmp_path / "Descargas ñ"
    dl = [e for e in events if e.kind == "dl"]
    assert dl and dl[-1].data["status"] == "finished"
    assert stream_kind(dl[-1].info) in ("av", "video")
    streams = _streams(cfg, str(path))
    assert "h264" in streams.get("video", set()) and streams.get("audio") == {"aac"}
    assert not list((tmp_path / "t" / "live0001").glob("*.part"))


def test_download_mp3_section(cfg: ytdlp.EngineConfig, tmp_path: Path):
    result, events = _download(cfg, Choice("mp3"), tmp_path)
    path = Path(result.files[0])
    assert path.suffix == ".mp3" and path.stat().st_size > 10_000
    assert any(e.kind == "pp" and e.data.get("postprocessor") == "ExtractAudio" for e in events)
    assert _streams(cfg, str(path)).get("audio") == {"mp3"}
