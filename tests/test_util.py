"""util: ANSI, JSON atómico, tamaños legibles e identificadores cortos; paths: carpeta de datos aislada."""

from __future__ import annotations

import json
import re
import threading
from pathlib import Path

import pytest

from kdownloader import paths, util


@pytest.mark.parametrize(
    ("raw", "clean"),
    [
        ("\x1b[0;31mERROR:\x1b[0m [youtube] abc: Private video", "ERROR: [youtube] abc: Private video"),
        ("\x1b[1;33mWARNING:\x1b[0m algo", "WARNING: algo"),
        ("\x1b[2K\x1b[1G[download]  50.0%", "[download]  50.0%"),
        ("\x1b[?25lsin cursor\x1b[?25h", "sin cursor"),
        ("texto normal sin escapes", "texto normal sin escapes"),
        ("ñandú ｜ 日本語", "ñandú ｜ 日本語"),
        ("", ""),
    ],
)
def test_strip_ansi(raw, clean):
    assert util.strip_ansi(raw) == clean


def test_write_json_atomic_roundtrip_and_no_temp_files(tmp_path: Path):
    target = tmp_path / "sub" / "dir" / "data.json"  # crea las carpetas que falten
    data = {"título": "Soy el Ñandú ｜ La Granja", "n": 3, "lista": [1, 2, None]}
    util.write_json_atomic(target, data)
    assert json.loads(target.read_text(encoding="utf-8")) == data
    assert "Ñandú" in target.read_text(encoding="utf-8")  # ensure_ascii=False: legible a mano
    util.write_json_atomic(target, {"v": 2})  # reemplaza
    assert util.read_json(target) == {"v": 2}
    assert sorted(p.name for p in target.parent.iterdir()) == ["data.json"]


def test_write_json_atomic_failure_keeps_previous_file(tmp_path: Path):
    target = tmp_path / "data.json"
    util.write_json_atomic(target, {"ok": True})
    with pytest.raises(TypeError):
        util.write_json_atomic(target, {"bad": object()})  # no serializable: falla a mitad de camino
    assert util.read_json(target) == {"ok": True}
    assert sorted(p.name for p in tmp_path.iterdir()) == ["data.json"]  # el temporal se borró


def test_write_json_atomic_concurrent_writers(tmp_path: Path):
    target = tmp_path / "data.json"
    errors: list[BaseException] = []

    def writer(n: int) -> None:
        try:
            for i in range(10):
                util.write_json_atomic(target, {"writer": n, "i": i})
        except PermissionError:
            pass  # Windows puede negar un reemplazo simultáneo; nunca debe quedar un JSON roto
        except BaseException as exc:
            errors.append(exc)

    threads = [threading.Thread(target=writer, args=(n,)) for n in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors
    data = util.read_json(target)
    assert isinstance(data, dict) and set(data) == {"writer", "i"}
    assert not list(tmp_path.glob("*.tmp"))


def test_read_json_accepts_bom(tmp_path: Path):
    # Notepad y PowerShell (Set-Content -Encoding utf8) guardan con BOM: no debe perderse la configuración.
    path = tmp_path / "bom.json"
    path.write_bytes(b"\xef\xbb\xbf" + '{"download_dir": "C:/ñandú"}'.encode())
    assert util.read_json(path) == {"download_dir": "C:/ñandú"}


def test_read_json_defaults(tmp_path: Path):
    missing = tmp_path / "nope.json"
    assert util.read_json(missing) is None
    assert util.read_json(missing, default={"a": 1}) == {"a": 1}
    corrupt = tmp_path / "corrupt.json"
    corrupt.write_text('{"a": 1,', encoding="utf-8")
    assert util.read_json(corrupt, default="fallback") == "fallback"
    binary = tmp_path / "binary.json"
    binary.write_bytes(b"\xff\xfe\x00garbage")
    assert util.read_json(binary, default=[]) == []
    folder = tmp_path / "folder.json"
    folder.mkdir()
    assert util.read_json(folder, default=7) == 7  # OSError (es una carpeta) → valor por defecto


@pytest.mark.parametrize(
    ("num", "text"),
    [
        (None, ""),
        (0, ""),
        (1, "1 B"),
        (512, "512 B"),
        (1023, "1023 B"),
        (1024, "1 KB"),
        (1536, "2 KB"),
        (1024 * 1024, "1.0 MB"),
        (int(1.5 * 1024 * 1024), "1.5 MB"),
        (257619653, "245.7 MB"),
        (1362269481, "1.3 GB"),
        (3 * 1024**4, "3.0 TB"),
        (5000 * 1024**4, "5000.0 TB"),  # nunca se queda sin unidad
    ],
)
def test_human_size(num, text):
    assert util.human_size(num) == text


def test_short_id_is_8_hex_chars_and_random():
    ids = {util.short_id() for _ in range(200)}
    assert len(ids) == 200
    assert all(re.fullmatch(r"[0-9a-f]{8}", i) for i in ids)


def test_data_dir_uses_override_and_creates_subfolders(data_dir: Path):
    assert paths.data_dir() == data_dir
    assert data_dir.is_dir()
    for fn, name in [(paths.engine_dir, "engine"), (paths.temp_root, "t"), (paths.cache_dir, "cache"),
                     (paths.logs_dir, "logs"), (paths.updates_dir, "updates")]:
        sub = fn()
        assert sub == data_dir / name and sub.is_dir()
    assert paths.settings_file() == data_dir / "settings.json"
    assert paths.history_file() == data_dir / "history.json"
    assert paths.jobs_file() == data_dir / "jobs.json"


def test_temp_root_stays_short(data_dir: Path):
    # El presupuesto de 260 caracteres asume una carpeta temporal corta: <datos>\t
    assert paths.temp_root().name == "t"


def test_console_python_prefers_python_exe(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    (runtime / "pythonw.exe").write_bytes(b"")
    (runtime / "python.exe").write_bytes(b"")
    monkeypatch.setattr(paths.sys, "executable", str(runtime / "pythonw.exe"))
    assert paths.console_python() == runtime / "python.exe"
    monkeypatch.setattr(paths.sys, "executable", str(runtime / "python.exe"))
    assert paths.console_python() == runtime / "python.exe"


def test_install_root_detection(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("KD_INSTALL_ROOT", raising=False)
    root = tmp_path / "Programs" / "K-Snag"
    (root / "runtime").mkdir(parents=True)
    (root / "runtime" / "pythonw.exe").write_bytes(b"")
    monkeypatch.setattr(paths.sys, "executable", str(root / "runtime" / "pythonw.exe"))
    assert paths.install_root() is None  # sin el puntero `current` no es una instalación
    (root / "current").write_text("2.0.0", encoding="ascii")
    assert paths.install_root() == root.resolve()
    monkeypatch.setenv("KD_INSTALL_ROOT", str(tmp_path / "otro"))
    assert paths.install_root() == tmp_path / "otro"
