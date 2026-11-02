"""Ajustes del usuario: dataclass con valores validados, guardado atómico y migraciones."""

from __future__ import annotations

import threading
from dataclasses import asdict, dataclass, fields
from pathlib import Path
from typing import Any

from . import paths
from .util import log, read_json, write_json_atomic

SCHEMA_VERSION = 1

CHOICES: dict[str, tuple[str, ...]] = {
    "language": ("auto", "es", "en"),
    "theme": ("system", "light", "dark"),
    "favorite_preset": ("recommended", "max", "mp3", "m4a"),
    "filename_style": ("title_id", "legacy_k", "simple"),
    "engine_channel": ("nightly", "stable"),
    "max_codec": ("vp9", "av1"),
}
RANGES: dict[str, tuple[int, int]] = {"max_concurrent": (1, 4)}


@dataclass
class Settings:
    version: int = SCHEMA_VERSION
    language: str = "auto"
    theme: str = "system"
    reduce_motion: bool = False
    download_dir: str = ""
    favorite_preset: str = "recommended"
    quick_download: bool = False
    filename_style: str = "title_id"
    max_concurrent: int = 2
    engine_channel: str = "nightly"
    auto_update_engine: bool = True
    max_codec: str = "vp9"
    clipboard_suggest: bool = True
    notify_on_finish: bool = True
    open_folder_on_finish: bool = False
    cookies_file: str = ""
    accepted_terms: bool = False
    first_run_done: bool = False
    check_app_updates: bool = True

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def default_download_dir() -> str:
    """Descargas/K-Snag; si ya existe Descargas/K-Downloader (de antes del cambio de nombre) y no la nueva, esa."""
    base = paths.downloads_dir()
    new, old = base / "K-Snag", base / "K-Downloader"
    return str(old if old.is_dir() and not new.exists() else new)


def _coerce(name: str, value: Any, current: Any) -> Any:
    """Valida un valor según el tipo y las opciones permitidas; lanza ValueError si no sirve."""
    if isinstance(current, bool):
        if not isinstance(value, bool):
            raise ValueError(f"{name} debe ser booleano")
        return value
    if isinstance(current, int):
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{name} debe ser entero")
        lo, hi = RANGES.get(name, (value, value))
        if not lo <= value <= hi:
            raise ValueError(f"{name} fuera de rango")
        return value
    if isinstance(current, str):
        if not isinstance(value, str):
            raise ValueError(f"{name} debe ser texto")
        if name in CHOICES and value not in CHOICES[name]:
            raise ValueError(f"{name} no admite {value!r}")
        return value.strip()
    raise ValueError(f"{name} no se puede modificar")


class SettingsStore:
    """Acceso con lock: los métodos del puente corren en hilos distintos."""

    READ_ONLY = frozenset({"version"})

    def __init__(self, path: Path | None = None):
        self.path = path or paths.settings_file()
        self._lock = threading.Lock()
        self._settings = self._load()

    def _load(self) -> Settings:
        raw = read_json(self.path, default={})
        if not isinstance(raw, dict):
            raw = {}
        base = Settings()
        for f in fields(Settings):
            if f.name in raw and f.name not in self.READ_ONLY:
                try:
                    setattr(base, f.name, _coerce(f.name, raw[f.name], getattr(base, f.name)))
                except ValueError as exc:
                    log.warning("Ajuste ignorado: %s", exc)
        if not base.download_dir:
            base.download_dir = default_download_dir()
        base.version = SCHEMA_VERSION
        return base

    def get(self) -> Settings:
        with self._lock:
            return Settings(**asdict(self._settings))

    def update(self, patch: dict[str, Any]) -> Settings:
        if not isinstance(patch, dict):
            raise ValueError("Se esperaba un objeto")
        with self._lock:
            updated = Settings(**asdict(self._settings))
            valid = {f.name for f in fields(Settings)} - self.READ_ONLY
            for key, value in patch.items():
                if key not in valid:
                    raise ValueError(f"Ajuste desconocido: {key}")
                setattr(updated, key, _coerce(key, value, getattr(updated, key)))
            if not updated.download_dir:
                updated.download_dir = default_download_dir()
            write_json_atomic(self.path, updated.to_dict())
            self._settings = updated
            return Settings(**asdict(updated))

    def save(self) -> None:
        with self._lock:
            write_json_atomic(self.path, self._settings.to_dict())
