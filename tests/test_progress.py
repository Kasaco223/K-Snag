"""engine.progress: el protocolo KDL sobre transcripciones reales de yt-dlp y entradas dañadas."""

from __future__ import annotations

import json

import pytest

from kdownloader.engine import progress
from kdownloader.engine.progress import KdlEvent, parse_line, stream_kind

TRANSCRIPTS = ["max_sections", "mp3_sections", "rec_full_unicode", "rec_sections", "uni_full_360"]
ALL_FILES = [f"{name}.{stream}.txt" for name in TRANSCRIPTS for stream in ("stdout", "stderr")]


@pytest.mark.parametrize("name", ALL_FILES)
def test_every_kdl_line_in_fixtures_parses(name, fixture_lines):
    for line in fixture_lines(name):
        ev = parse_line(line)
        if "KDL|" not in line:
            assert ev is None, line
            continue
        assert isinstance(ev, KdlEvent), line
        assert ev.kind in ("dl", "pp", "file")
        if ev.kind == "dl":
            assert ev.data["status"] in ("downloading", "finished")
            assert isinstance(ev.data["downloaded_bytes"], int)
            assert ev.info.get("format_id")
        elif ev.kind == "pp":
            assert ev.data["status"] in ("started", "finished")
            assert ev.data["postprocessor"]
        else:
            assert ev.path and ev.path.startswith("C:\\KD\\out\\")


def _events(fixture_lines, name: str) -> list[KdlEvent]:
    lines = fixture_lines(f"{name}.stdout.txt") + fixture_lines(f"{name}.stderr.txt")
    return [ev for ev in map(parse_line, lines) if ev is not None]


@pytest.mark.parametrize(
    ("name", "format_id", "kind", "ext"),
    [
        ("rec_sections", "299+140", "av", ".mp4"),  # recomendado: H.264 1080p60 + AAC
        ("max_sections", "315+251", "av", ".mkv"),  # máxima: VP9 4K + Opus
        ("mp3_sections", "251", "audio", ".mp3"),  # MP3 desde Opus
    ],
)
def test_section_transcripts(fixture_lines, name, format_id, kind, ext):
    events = _events(fixture_lines, name)
    dl = [e for e in events if e.kind == "dl"]
    files = [e for e in events if e.kind == "file"]
    assert dl and dl[-1].data["status"] == "finished"
    assert dl[-1].info["format_id"] == format_id
    assert stream_kind(dl[-1].info) == kind
    assert dl[-1].data["downloaded_bytes"] == dl[-1].data["total_bytes"]
    assert len(files) == 1 and files[0].path.endswith(ext)
    assert events[-1].kind == "file"  # after_move llega al final


def test_rec_sections_postprocessor_sequence(fixture_lines):
    pp = [(e.data["postprocessor"], e.data["status"]) for e in _events(fixture_lines, "rec_sections") if e.kind == "pp"]
    assert pp == [
        ("VideoRemuxer", "started"), ("VideoRemuxer", "finished"),
        ("Metadata", "started"), ("Metadata", "finished"),
        ("EmbedThumbnail", "started"), ("EmbedThumbnail", "finished"),
        ("MoveFiles", "started"), ("MoveFiles", "finished"),
    ]


def test_mp3_transcript_has_extract_audio(fixture_lines):
    names = [e.data["postprocessor"] for e in _events(fixture_lines, "mp3_sections") if e.kind == "pp"]
    assert names[0] == "ExtractAudio"
    assert "EmbedThumbnail" in names


def test_unicode_full_download_two_streams_and_merge(fixture_lines):
    events = _events(fixture_lines, "uni_full_360")
    dl = [e for e in events if e.kind == "dl"]
    assert {e.info["format_id"] for e in dl} == {"134", "140"}
    # Primero el video, después el audio: la UI muestra "Paso 1 de 2 · Video" y "Paso 2 de 2 · Audio".
    kinds = [stream_kind(e.info) for e in dl]
    first_audio = kinds.index("audio")
    assert set(kinds[:first_audio]) == {"video"} and set(kinds[first_audio:]) == {"audio"}
    video = [e for e in dl if e.info["format_id"] == "134"]
    sizes = [e.data["downloaded_bytes"] for e in video]
    assert sizes == sorted(sizes)  # el progreso nunca retrocede
    assert all(e.data["total_bytes"] == 7640800 for e in video)
    assert "eta" in video[0].data and "speed" in video[0].data
    assert any(e.kind == "pp" and e.data["postprocessor"] == "Merger" for e in events)
    path = next(e.path for e in events if e.kind == "file")
    # El JSON de yt-dlp viene en ASCII con escapes \uXXXX: el título llega intacto aunque la consola no.
    assert path.endswith("Soy el Ñandú ｜ La Granja de Zenón 6 [IKLKB49EqS8].mp4")


def test_error_transcript_has_no_events(fixture_lines):
    assert _events(fixture_lines, "rec_full_unicode") == []


@pytest.mark.parametrize(
    "line",
    [
        "",
        "   ",
        "hola",
        "[download]  45.3% of 10.00MiB at 1.00MiB/s ETA 00:05",
        "KDL",
        "KDL|",
        "KDL|dl",
        "KDL|dl|",
        "KDL|dl|{",
        'KDL|dl|{"status": "downl',  # línea cortada a la mitad
        "KDL|dl|[1, 2, 3]",  # JSON válido pero no es un objeto
        "KDL|dl|null",
        'KDL|pp|"Merger"',
        "KDL|pp|{bad}",
        "KDL|pp|",
        'KDL|file|""',  # ruta vacía
        "KDL|file|123",
        "KDL|file|null",
        'KDL|file|C:\\sin\\comillas.mp4',
        "KDL|xx|{}",
        "KDL|DL|{}",  # el tipo distingue mayúsculas
        "kdl|dl|{}",
        "KDL dl {}",
    ],
)
def test_garbage_and_partial_lines_return_none(line):
    assert parse_line(line) is None


def test_json_with_pipes_inside_strings():
    line = 'KDL|dl|{"status": "downloading", "note": "a|b|c"}|{"format_id": "1|2", "vcodec": "none", "acodec": "opus"}'
    ev = parse_line(line)
    assert ev is not None and ev.kind == "dl"
    assert ev.data == {"status": "downloading", "note": "a|b|c"}
    assert ev.info["format_id"] == "1|2"
    ev = parse_line('KDL|file|"C:\\\\Videos\\\\a|b ｜ c.mp4"')
    assert ev is not None and ev.path == "C:\\Videos\\a|b ｜ c.mp4"
    ev = parse_line('KDL|pp|{"status": "started", "postprocessor": "Merger|x"}')
    assert ev is not None and ev.data["postprocessor"] == "Merger|x"


def test_dl_without_or_with_bad_info_part():
    ev = parse_line('KDL|dl|{"status": "downloading", "downloaded_bytes": 10}')
    assert ev is not None and ev.info == {}
    ev = parse_line('KDL|dl|{"status": "downloading"}|null')
    assert ev is not None and ev.info == {}
    ev = parse_line('KDL|dl|{"status": "downloading"}|[1]')
    assert ev is not None and ev.info == {}


def test_trailing_noise_tolerated():
    payload = {"status": "finished", "postprocessor": "MoveFiles"}
    for line in (
        "KDL|pp|" + json.dumps(payload) + "\r",
        "KDL|pp|" + json.dumps(payload) + "   basura",
        "  KDL|pp|" + json.dumps(payload),
    ):
        ev = parse_line(line)
        assert ev is not None and ev.kind == "pp" and ev.data == payload, line


def test_null_fields_from_ytdlp_survive():
    # yt-dlp imprime null cuando no conoce un campo (total_bytes en HLS, eta al inicio…)
    ev = parse_line('KDL|dl|{"status": "downloading", "downloaded_bytes": 1024, "total_bytes": null, '
                    '"total_bytes_estimate": 5000000.0, "speed": null, "eta": null, "fragment_index": 3, '
                    '"fragment_count": 120}|{"format_id": "312", "vcodec": "avc1.64002A", "acodec": "none", '
                    '"height": 1080, "ext": "mp4"}')
    assert ev is not None
    assert ev.data["total_bytes"] is None and ev.data["fragment_count"] == 120
    assert stream_kind(ev.info) == "video"


@pytest.mark.parametrize(
    ("info", "kind"),
    [
        ({"vcodec": "avc1.64002A", "acodec": "none"}, "video"),
        ({"vcodec": "vp9"}, "video"),
        ({"vcodec": "none", "acodec": "opus"}, "audio"),
        ({"acodec": "mp4a.40.2"}, "audio"),
        ({"vcodec": None, "acodec": "mp4a.40.2"}, "audio"),
        ({"vcodec": "avc1", "acodec": "mp4a.40.2"}, "av"),
        ({"vcodec": "vp9", "acodec": "opus"}, "av"),
        ({}, "audio"),
    ],
)
def test_stream_kind(info, kind):
    assert stream_kind(info) == kind


def test_templates_contain_kdl_markers():
    dl = progress.PROGRESS_TEMPLATE_DL
    assert dl.startswith("download:KDL|dl|")
    for key in ("status", "downloaded_bytes", "total_bytes", "total_bytes_estimate", "speed", "eta",
                "fragment_index", "fragment_count"):
        assert key in dl
    assert "%(progress.{" in dl and "%(info.{" in dl and dl.count("})j") == 2
    assert "format_id" in dl and "vcodec" in dl and "acodec" in dl
    assert progress.PROGRESS_TEMPLATE_PP == "postprocess:KDL|pp|%(progress.{status,postprocessor})j"
    assert progress.PRINT_FILE == "after_move:KDL|file|%(filepath)j"


def test_template_output_roundtrip():
    """Lo que yt-dlp produce con esas plantillas (json.dumps ASCII) vuelve igual por parse_line."""
    prog = {"status": "downloading", "downloaded_bytes": 2048, "total_bytes": 4096, "total_bytes_estimate": None,
            "speed": 1234.5, "eta": 2, "elapsed": 0.5, "fragment_index": None, "fragment_count": None}
    info = {"format_id": "299", "vcodec": "avc1.64002A", "acodec": "none", "height": 1080, "ext": "mp4"}
    ev = parse_line(f"KDL|dl|{json.dumps(prog)}|{json.dumps(info)}")
    assert ev == KdlEvent("dl", prog, info)
    path = "C:\\Users\\Ana\\Descargas\\K-Snag\\Ñandú ｜ 日本語 [abc].mp4"
    ev = parse_line("KDL|file|" + json.dumps(path))
    assert ev == KdlEvent("file", path=path)
