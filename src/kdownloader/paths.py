"""Rutas de datos, motor e instalación. Todo vive en carpetas del usuario (nunca Program Files)."""

from __future__ import annotations

import os
import sys
from pathlib import Path

from .constants import DIR_NAME

IS_WINDOWS = sys.platform == "win32"


def data_dir() -> Path:
    """Carpeta de datos: ajustes, historial, motor, temporales y registros."""
    override = os.environ.get("KD_DATA_DIR")
    if override:
        base = Path(override)
    elif IS_WINDOWS:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / DIR_NAME
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / DIR_NAME
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share") / "k-downloader"
    base.mkdir(parents=True, exist_ok=True)
    return base


def _sub(name: str) -> Path:
    path = data_dir() / name
    path.mkdir(parents=True, exist_ok=True)
    return path


def engine_dir() -> Path:
    return _sub("engine")


def temp_root() -> Path:
    # Ruta corta a propósito: los fragmentos de yt-dlp no deben pasar de 260 caracteres.
    return _sub("t")


def cache_dir() -> Path:
    return _sub("cache")


def logs_dir() -> Path:
    return _sub("logs")


def updates_dir() -> Path:
    return _sub("updates")


def settings_file() -> Path:
    return data_dir() / "settings.json"


def history_file() -> Path:
    return data_dir() / "history.json"


def jobs_file() -> Path:
    return data_dir() / "jobs.json"


def package_dir() -> Path:
    return Path(__file__).resolve().parent


def ui_dir() -> Path:
    return package_dir() / "ui"


def install_root() -> Path | None:
    """Carpeta de instalación (…/Programs/K-Downloader) si la app corre instalada; None en desarrollo."""
    override = os.environ.get("KD_INSTALL_ROOT")
    if override:
        return Path(override)
    exe = Path(sys.executable).resolve()
    if exe.parent.name.lower() == "runtime" and (exe.parent.parent / "current").is_file():
        return exe.parent.parent
    return None


def console_python() -> Path:
    """Intérprete con consola (python.exe) para lanzar el motor; con CREATE_NO_WINDOW no abre ventana.

    KD_ENGINE_PYTHON permite usar otro intérprete (p. ej. el runtime embebible) durante el desarrollo.
    """
    override = os.environ.get("KD_ENGINE_PYTHON")
    if override:
        return Path(override)
    exe = Path(sys.executable)
    if exe.name.lower() == "pythonw.exe":
        candidate = exe.with_name("python.exe")
        if candidate.exists():
            return candidate
    return exe


def downloads_dir() -> Path:
    """Carpeta Descargas real del usuario (puede estar movida o traducida)."""
    if IS_WINDOWS:
        try:
            return Path(_known_folder("{374DE290-123F-4565-9164-39C4925E467B}"))
        except OSError:
            pass
    return Path.home() / "Downloads"


def _known_folder(guid: str) -> str:
    import ctypes
    from ctypes import wintypes

    class GUID(ctypes.Structure):
        _fields_ = [
            ("Data1", wintypes.DWORD),
            ("Data2", wintypes.WORD),
            ("Data3", wintypes.WORD),
            ("Data4", wintypes.BYTE * 8),
        ]

    g = GUID()
    if ctypes.windll.ole32.CLSIDFromString(ctypes.c_wchar_p(guid), ctypes.byref(g)) != 0:
        raise OSError("CLSIDFromString falló")
    out = ctypes.c_wchar_p()
    hr = ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(out))
    if hr != 0:
        raise OSError(f"SHGetKnownFolderPath falló ({hr:#x})")
    try:
        return out.value or ""
    finally:
        ctypes.windll.ole32.CoTaskMemFree(out)
