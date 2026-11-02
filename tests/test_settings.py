"""settings: valores por defecto, validación estricta, guardado atómico y recuperación ante archivos dañados."""

from __future__ import annotations

import json
import threading
from dataclasses import asdict, fields
from pathlib import Path

import pytest

from kdownloader import paths, settings
from kdownloader.settings import CHOICES, RANGES, SCHEMA_VERSION, Settings, SettingsStore

# Campos del contrato `Settings` de docs/architecture.md §6 (+ la versión del esquema).
CONTRACT_FIELDS = {
    "language", "theme", "reduce_motion", "download_dir", "favorite_preset", "quick_download", "filename_style",
    "max_concurrent", "engine_channel", "auto_update_engine", "max_codec", "clipboard_suggest", "notify_on_finish",
    "open_folder_on_finish", "cookies_file", "accepted_terms", "first_run_done", "check_app_updates",
}


@pytest.fixture
def store_path(tmp_path: Path) -> Path:
    return tmp_path / "settings.json"


def test_fields_match_contract():
    assert {f.name for f in fields(Settings)} == CONTRACT_FIELDS | {"version"}


def test_defaults(store_path: Path):
    s = SettingsStore(store_path).get()
    assert s.version == SCHEMA_VERSION == 1
    assert (s.language, s.theme, s.reduce_motion) == ("auto", "system", False)
    assert (s.favorite_preset, s.quick_download, s.filename_style) == ("recommended", False, "title_id")
    assert s.max_concurrent == 2
    assert (s.engine_channel, s.auto_update_engine, s.max_codec) == ("nightly", True, "vp9")
    assert (s.clipboard_suggest, s.notify_on_finish, s.open_folder_on_finish) == (True, True, False)
    assert (s.cookies_file, s.accepted_terms, s.first_run_done, s.check_app_updates) == ("", False, False, True)
    assert not store_path.exists()  # leer no escribe


def test_default_download_dir(store_path: Path, tmp_path: Path, monkeypatch):
    monkeypatch.setattr(paths, "downloads_dir", lambda: tmp_path)
    expected = tmp_path / "K-Snag"
    assert settings.default_download_dir() == str(expected)
    assert SettingsStore(store_path).get().download_dir == str(expected)


def test_default_download_dir_keeps_the_old_folder(tmp_path: Path, monkeypatch):
    # Quien ya tenía Descargas/K-Downloader (antes del cambio de nombre) sigue descargando ahí.
    monkeypatch.setattr(paths, "downloads_dir", lambda: tmp_path)
    (tmp_path / "K-Downloader").mkdir()
    assert settings.default_download_dir() == str(tmp_path / "K-Downloader")
    (tmp_path / "K-Snag").mkdir()
    assert settings.default_download_dir() == str(tmp_path / "K-Snag")


def test_default_values_are_valid_choices():
    s = Settings()
    for name, options in CHOICES.items():
        assert getattr(s, name) in options, name
    for name, (lo, hi) in RANGES.items():
        assert lo <= getattr(s, name) <= hi


def test_update_persists_and_reloads(store_path: Path):
    store = SettingsStore(store_path)
    patch = {"theme": "dark", "language": "en", "max_concurrent": 4, "quick_download": True,
             "favorite_preset": "mp3", "filename_style": "legacy_k", "engine_channel": "stable",
             "max_codec": "av1", "download_dir": "D:\\Videos\\Ñandú", "cookies_file": "C:\\c\\cookies.txt"}
    result = store.update(patch)
    for key, value in patch.items():
        assert getattr(result, key) == value
    on_disk = json.loads(store_path.read_text(encoding="utf-8"))
    assert on_disk["version"] == SCHEMA_VERSION
    assert set(on_disk) == CONTRACT_FIELDS | {"version"}
    for key, value in patch.items():
        assert on_disk[key] == value
    again = SettingsStore(store_path).get()
    assert asdict(again) == asdict(result)


def test_update_leaves_no_temp_files(store_path: Path):
    store = SettingsStore(store_path)
    for i in range(5):
        store.update({"max_concurrent": 1 + i % 4})
    assert [p.name for p in store_path.parent.iterdir()] == ["settings.json"]


def test_get_returns_a_copy(store_path: Path):
    store = SettingsStore(store_path)
    s = store.get()
    s.theme = "dark"
    assert store.get().theme == "system"
    r = store.update({"theme": "light"})
    r.theme = "dark"
    assert store.get().theme == "light"


def test_strings_are_trimmed_and_empty_dir_resets(store_path: Path):
    store = SettingsStore(store_path)
    assert store.update({"download_dir": "  D:\\Descargas  "}).download_dir == "D:\\Descargas"
    assert store.update({"download_dir": ""}).download_dir == settings.default_download_dir()
    assert store.update({"download_dir": "   "}).download_dir == settings.default_download_dir()


@pytest.mark.parametrize(
    ("patch", "fragment"),
    [
        ({"reduce_motion": "true"}, "booleano"),
        ({"reduce_motion": 1}, "booleano"),
        ({"quick_download": None}, "booleano"),
        ({"max_concurrent": True}, "entero"),  # bool es int en Python: debe rechazarse igual
        ({"max_concurrent": "2"}, "entero"),
        ({"max_concurrent": 2.0}, "entero"),
        ({"max_concurrent": 0}, "rango"),
        ({"max_concurrent": 5}, "rango"),
        ({"max_concurrent": -1}, "rango"),
        ({"theme": "neon"}, "no admite"),
        ({"language": "fr"}, "no admite"),
        ({"favorite_preset": "h720"}, "no admite"),
        ({"filename_style": "K"}, "no admite"),
        ({"engine_channel": "master"}, "no admite"),
        ({"max_codec": "h264"}, "no admite"),
        ({"theme": "Dark"}, "no admite"),  # sin normalizar mayúsculas: la UI manda los valores exactos
        ({"download_dir": 5}, "texto"),
        ({"cookies_file": ["a"]}, "texto"),
        ({"theme": None}, "texto"),
        ({"version": 2}, "desconocido"),  # solo lectura
        ({"unknown_key": 1}, "desconocido"),
        ({"__class__": "x"}, "desconocido"),
    ],
)
def test_update_rejects_invalid(store_path: Path, patch, fragment):
    store = SettingsStore(store_path)
    before = asdict(store.get())
    with pytest.raises(ValueError, match=fragment):
        store.update(patch)
    assert asdict(store.get()) == before
    assert not store_path.exists()


@pytest.mark.parametrize("patch", [None, [], "theme=dark", 3, [("theme", "dark")]])
def test_update_requires_object(store_path: Path, patch):
    with pytest.raises(ValueError):
        SettingsStore(store_path).update(patch)


def test_update_is_all_or_nothing(store_path: Path):
    store = SettingsStore(store_path)
    store.update({"theme": "light"})
    with pytest.raises(ValueError):
        store.update({"theme": "dark", "max_concurrent": 99})
    assert store.get().theme == "light"
    assert json.loads(store_path.read_text(encoding="utf-8"))["theme"] == "light"


@pytest.mark.parametrize("content", ["{not json", "", "\x00\x01", '{"theme": "dark",'])
def test_corrupt_file_gives_defaults(store_path: Path, content):
    store_path.write_text(content, encoding="utf-8")
    s = SettingsStore(store_path).get()
    assert asdict(s) == asdict(Settings(download_dir=settings.default_download_dir()))


def test_corrupt_file_is_repaired_on_next_save(store_path: Path):
    store_path.write_text("{not json", encoding="utf-8")
    store = SettingsStore(store_path)
    store.update({"theme": "dark"})
    assert json.loads(store_path.read_text(encoding="utf-8"))["theme"] == "dark"


def test_invalid_values_in_file_are_ignored_one_by_one(store_path: Path):
    store_path.write_text(json.dumps({
        "version": 99, "theme": "neon", "max_concurrent": 3, "quick_download": "yes", "language": "en",
        "unknown": 1, "max_codec": 7, "download_dir": "E:\\K",
    }), encoding="utf-8")
    s = SettingsStore(store_path).get()
    assert s.version == SCHEMA_VERSION  # la versión del archivo nunca se copia
    assert s.theme == "system"
    assert s.max_concurrent == 3
    assert s.quick_download is False
    assert s.language == "en"
    assert s.max_codec == "vp9"
    assert s.download_dir == "E:\\K"
    assert not hasattr(s, "unknown")


def test_missing_download_dir_key_uses_default(store_path: Path):
    store_path.write_text(json.dumps({"theme": "dark"}), encoding="utf-8")
    s = SettingsStore(store_path).get()
    assert s.theme == "dark"
    assert s.download_dir == settings.default_download_dir()


@pytest.mark.parametrize("content", ["[1, 2]", '"texto"', "42", "true"])
def test_non_object_json_gives_defaults(store_path: Path, content):
    store_path.write_text(content, encoding="utf-8")
    assert SettingsStore(store_path).get().theme == "system"


@pytest.mark.parametrize("bad", ["", "   ", 123, ["C:/x"]])
def test_invalid_download_dir_in_file_uses_default(store_path: Path, bad):
    store_path.write_text(json.dumps({"download_dir": bad}), encoding="utf-8")
    assert SettingsStore(store_path).get().download_dir == settings.default_download_dir()


def test_save_writes_current_values(store_path: Path):
    store = SettingsStore(store_path)
    store.save()
    data = json.loads(store_path.read_text(encoding="utf-8"))
    assert data == asdict(store.get())


def test_default_path_is_in_data_dir(data_dir: Path):
    store = SettingsStore()
    assert store.path == data_dir / "settings.json"
    store.update({"accepted_terms": True})
    assert (data_dir / "settings.json").is_file()


def test_concurrent_updates_are_serialized(store_path: Path):
    store = SettingsStore(store_path)
    errors: list[BaseException] = []

    def worker(n: int) -> None:
        try:
            for i in range(15):
                store.update({"max_concurrent": 1 + (n + i) % 4, "theme": ("light", "dark")[i % 2]})
        except BaseException as exc:
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(n,)) for n in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors
    on_disk = json.loads(store_path.read_text(encoding="utf-8"))
    assert asdict(SettingsStore(store_path).get()) == asdict(store.get()) == on_disk
