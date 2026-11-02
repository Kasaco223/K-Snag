"""Cola de descargas: concurrencia, progreso por etapas, reintentos automáticos y recuperación."""

from __future__ import annotations

import shutil
import tempfile
import threading
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from . import paths
from .engine import errors, presets
from .engine.binaries import EngineManager
from .engine.hints import HintsStore
from .engine.progress import KdlEvent, stream_kind
from .engine.ytdlp import DownloadRun, EngineConfig
from .history import History, HistoryItem
from .settings import SettingsStore
from .util import log, read_json, short_id, write_json_atomic

ACTIVE = ("probing", "downloading", "processing")
TERMINAL = ("done", "error", "canceled", "interrupted")
MAX_KEPT_JOBS = 200
QUIET_ARGS = ["--sleep-requests", "0.75", "--sleep-interval", "10", "--max-sleep-interval", "20"]
QUIET_MINUTES = 30
GROUP_GAP_SECONDS = 5
MIN_FREE_BYTES = 200 * 1024 * 1024
SLOW_BYTES_PER_SEC = 150 * 1024


@dataclass
class Job:
    id: str
    url: str
    title: str = ""
    uploader: str = ""
    thumbnail: str | None = None
    site: str = ""
    option: str = "recommended"
    container: str = "mp4"
    height: int | None = None
    is_youtube: bool = False
    group: str | None = None
    dest_dir: str = ""
    legacy_stamp: str | None = None
    state: str = "queued"
    step_index: int = 0
    step_total: int = 0
    step_kind: str | None = None
    postprocessor: str | None = None
    progress: float | None = None
    downloaded: int = 0
    total: int = 0
    speed: float | None = None
    eta: int | None = None
    file_path: str | None = None
    error: dict[str, Any] | None = None
    auto: str | None = None
    notes: list[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    finished_at: float | None = None
    attempts: int = 0
    engine_updated: bool = False
    net_retries: int = 0
    use_cookies: bool | None = None
    force_best: bool = False
    formats_seen: list[str] = field(default_factory=list)
    slow_since: float | None = None

    def view(self) -> dict[str, Any]:
        path = Path(self.file_path) if self.file_path else None
        return {
            "id": self.id, "title": self.title or self.url, "uploader": self.uploader, "thumbnail": self.thumbnail,
            "site": self.site, "option": self.option, "container": self.container, "height": self.height,
            "state": self.state,
            "step": ({"index": self.step_index, "total": self.step_total, "kind": self.step_kind}
                     if self.step_total else None),
            "postprocessor": self.postprocessor, "progress": self.progress, "downloaded": self.downloaded,
            "total": self.total, "speed": self.speed, "eta": self.eta,
            "file_name": path.name if path else None, "file_exists": bool(path and path.exists()),
            "error": self.error, "auto": self.auto, "notes": list(self.notes),
            "created_at": self.created_at, "finished_at": self.finished_at,
        }

    def persist(self) -> dict[str, Any]:
        return asdict(self)

    def reset_progress(self) -> None:
        self.step_index = self.step_total = 0
        self.step_kind = self.postprocessor = None
        self.progress = self.speed = None
        self.eta = None
        self.downloaded = self.total = 0
        self.formats_seen = []
        self.slow_since = None


def option_to_choice(option: str) -> presets.Choice:
    if option.startswith("h") and option[1:].isdigit():
        return presets.Choice("height", int(option[1:]))
    if option in presets.ALL_PRESETS:
        return presets.Choice(option)
    raise ValueError(f"Opción desconocida: {option}")


def container_for(option: str, height: int | None) -> str:
    if option in ("mp3", "m4a"):
        return option
    if option == "max" or (option.startswith("h") and (height or 0) > 1080):
        return "mkv"
    return "mp4"


class DownloadManager:
    def __init__(self, settings: SettingsStore, engine: EngineManager, history: History, hints: HintsStore,
                 config: Callable[[], EngineConfig], emit: Callable[[dict[str, Any], str | None], None],
                 lang: Callable[[], str], on_finished: Callable[[Job], None] | None = None,
                 prevent_sleep: Callable[[bool], None] | None = None):
        self.settings = settings
        self.engine = engine
        self.history = history
        self.hints = hints
        self._config = config
        self._emit = emit
        self._lang = lang
        self._on_finished = on_finished
        self._prevent_sleep = prevent_sleep
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._runs: dict[str, DownloadRun] = {}
        self._canceled: set[str] = set()
        self._workers: dict[str, threading.Thread] = {}
        self._group_last_end: dict[str, float] = {}
        self._quiet_until = 0.0
        self._dirty = False
        self._ensuring = False
        self._stamps: set[str] = set()
        self.jobs: dict[str, Job] = {}
        self._load()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="kd-scheduler")

    # ------------------------------------------------------------------ ciclo de vida
    def start(self) -> None:
        self._cleanup_orphans()
        self._thread.start()

    def shutdown(self) -> None:
        self._stop.set()
        self._wake.set()
        with self._lock:
            for job_id, run in list(self._runs.items()):
                self._canceled.add(job_id)
                run.cancel()
        for t in list(self._workers.values()):
            t.join(timeout=10)
        with self._lock:
            for job in self.jobs.values():
                if job.state in ACTIVE or job.state == "queued":
                    job.state = "interrupted"
            self._persist()
        if self._prevent_sleep:
            self._prevent_sleep(False)

    def _load(self) -> None:
        raw = read_json(paths.jobs_file(), default=[]) or []
        for entry in raw if isinstance(raw, list) else []:
            try:
                job = Job(**entry)
            except TypeError:
                continue
            if job.state not in TERMINAL:
                job.state = "interrupted"
                job.auto = None
                job.speed = job.eta = None
            self.jobs[job.id] = job

    def _persist(self) -> None:
        with self._lock:
            jobs = sorted(self.jobs.values(), key=lambda j: j.created_at)[-MAX_KEPT_JOBS:]
            write_json_atomic(paths.jobs_file(), [j.persist() for j in jobs])
            self._dirty = False

    def _cleanup_orphans(self) -> None:
        keep = {job_id for job_id, j in self.jobs.items() if j.state in ("interrupted", "error", "queued")}
        for child in paths.temp_root().iterdir():
            if child.is_dir() and child.name not in keep:
                shutil.rmtree(child, ignore_errors=True)

    # ------------------------------------------------------------------ API pública
    def views(self) -> list[dict[str, Any]]:
        with self._lock:
            return [j.view() for j in sorted(self.jobs.values(), key=lambda j: j.created_at, reverse=True)]

    def active_count(self) -> int:
        with self._lock:
            return sum(1 for j in self.jobs.values() if j.state in ACTIVE or j.state == "queued")

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self.jobs.get(job_id)

    def enqueue(self, media: dict[str, Any], option: str, items: str | list[str] | None = None) -> list[str]:
        option_to_choice(option)  # valida
        s = self.settings.get()
        dest = s.download_dir
        created: list[str] = []
        entries: list[dict[str, Any]]
        if media.get("is_playlist"):
            all_entries = media.get("entries") or []
            if items in (None, "all"):
                entries = all_entries
            else:
                wanted = set(items or [])
                entries = [e for e in all_entries if e.get("id") in wanted]
            group = media.get("token")
        else:
            entries = [media]
            group = None
        height = int(option[1:]) if option.startswith("h") and option[1:].isdigit() else None
        with self._lock:
            for e in entries:
                url = e.get("url") or ""
                if media.get("is_playlist") and media.get("is_youtube") and e.get("id") and not url.startswith("http"):
                    url = f"https://www.youtube.com/watch?v={e['id']}"
                if not url.startswith(("http://", "https://")):
                    continue
                thumb = e.get("thumbnail")
                if not thumb and media.get("is_youtube") and e.get("id") and media.get("is_playlist"):
                    thumb = f"https://i.ytimg.com/vi/{e['id']}/hqdefault.jpg"
                job = Job(
                    id=short_id(), url=url, title=e.get("title") or "", uploader=media.get("uploader") or "",
                    thumbnail=thumb, site=media.get("site") or "", option=option,
                    container=container_for(option, height), height=height,
                    is_youtube=bool(media.get("is_youtube")), group=group, dest_dir=dest,
                    legacy_stamp=self._legacy_stamp() if s.filename_style == "legacy_k" else None,
                )
                self.jobs[job.id] = job
                created.append(job.id)
                self._emit_job(job)
            self._dirty = True
        self._wake.set()
        return created

    def cancel(self, job_id: str) -> None:
        with self._lock:
            job = self.jobs.get(job_id)
            if not job:
                return
            if job.state == "queued":
                job.state, job.finished_at = "canceled", time.time()
                self._emit_job(job)
                return
            run = self._runs.get(job_id)
            self._canceled.add(job_id)
        if run:
            run.cancel()

    def cancel_all(self) -> None:
        with self._lock:
            ids = [j.id for j in self.jobs.values() if j.state in ACTIVE or j.state == "queued"]
        for job_id in ids:
            self.cancel(job_id)

    def retry(self, job_id: str, mode: str = "normal") -> None:
        with self._lock:
            job = self.jobs.get(job_id)
            if not job or job.state in ACTIVE:
                return
            job.state, job.error, job.auto, job.finished_at = "queued", None, None, None
            job.engine_updated = False
            job.net_retries = 0
            if mode == "with_cookies":
                job.use_cookies = True
                if "cookies_used" not in job.notes:
                    job.notes.append("cookies_used")
            elif mode == "best":
                job.force_best = True
                if "fallback_quality" not in job.notes:
                    job.notes.append("fallback_quality")
            job.reset_progress()
            self._emit_job(job)
            self._dirty = True
        self._wake.set()

    def remove(self, job_id: str) -> None:
        self.cancel(job_id)
        with self._lock:
            job = self.jobs.pop(job_id, None)
            self._dirty = True
        if job:
            shutil.rmtree(paths.temp_root() / job.id, ignore_errors=True)
            self._emit({"type": "job_removed", "id": job_id}, f"job:{job_id}")

    def clear_finished(self) -> None:
        with self._lock:
            ids = [j.id for j in self.jobs.values() if j.state in TERMINAL]
        for job_id in ids:
            self.remove(job_id)

    def details(self, job_id: str) -> str:
        job = self.get(job_id)
        if not job:
            return ""
        engine = self.engine.status()
        lines = [
            f"K-Snag · trabajo {job.id}",
            f"Enlace: {job.url}",
            f"Opción: {job.option} · estado: {job.state} · intentos: {job.attempts}",
            f"yt-dlp: {engine['ytdlp'].get('version')} · FFmpeg: {engine['ffmpeg'].get('version')} · "
            f"Deno: {engine['deno'].get('version')}",
        ]
        if job.error:
            lines += ["", f"[{job.error.get('code')}] {job.error.get('title')}", job.error.get("details") or ""]
        return "\n".join(lines)

    # ------------------------------------------------------------------ planificador
    def _legacy_stamp(self) -> str:
        base = datetime.now().strftime("%Y%m%d_%H%M%S")
        stamp, n = base, 2
        while stamp in self._stamps:
            stamp, n = f"{base}_{n}", n + 1
        self._stamps.add(stamp)
        return stamp

    def _emit_job(self, job: Job) -> None:
        self._dirty = True
        self._emit({"type": "job", "job": job.view()}, f"job:{job.id}")

    def _quiet(self) -> bool:
        return time.time() < self._quiet_until

    def _loop(self) -> None:
        last_persist = 0.0
        sleeping = False
        while not self._stop.is_set():
            self._wake.wait(1.0)
            self._wake.clear()
            if self._stop.is_set():
                break
            try:
                self._schedule()
            except Exception:  # noqa: BLE001
                log.exception("Error en el planificador")
            busy = bool(self._workers)
            if self._prevent_sleep and busy != sleeping:
                self._prevent_sleep(busy)  # se llama siempre desde este hilo (SetThreadExecutionState es por hilo)
                sleeping = busy
            if self._dirty and time.time() - last_persist > 2:
                self._persist()
                last_persist = time.time()

    def _schedule(self) -> None:
        if not self.engine.ready():
            if any(j.state == "queued" for j in self.jobs.values()) and not self._ensuring:
                self._ensuring = True
                channel = self.settings.get().engine_channel

                def ensure() -> None:
                    try:
                        self.engine.ensure(channel=channel)
                    except Exception:  # noqa: BLE001
                        log.exception("No se pudo preparar el motor")
                    finally:
                        self._ensuring = False
                        self._wake.set()

                threading.Thread(target=ensure, daemon=True, name="kd-ensure").start()
            return
        if self.engine.busy():
            return  # "Actualizar y reintentar": los reintentos esperan a que termine la actualización
        limit = 1 if self._quiet() else self.settings.get().max_concurrent
        with self._lock:
            running = {jid for jid, t in self._workers.items() if t.is_alive()}
            for jid in list(self._workers):
                if jid not in running:
                    self._workers.pop(jid, None)
            busy_groups = {self.jobs[j].group for j in running if j in self.jobs and self.jobs[j].group}
            queued = sorted((j for j in self.jobs.values() if j.state == "queued"), key=lambda j: j.created_at)
            for job in queued:
                if len(self._workers) >= limit:
                    break
                if job.group and (job.group in busy_groups
                                  or time.time() - self._group_last_end.get(job.group, 0) < GROUP_GAP_SECONDS):
                    continue
                job.state = "probing"
                job.attempts += 1
                self._emit_job(job)
                t = threading.Thread(target=self._worker, args=(job,), daemon=True, name=f"kd-job-{job.id}")
                self._workers[job.id] = t
                if job.group:
                    busy_groups.add(job.group)
                t.start()

    # ------------------------------------------------------------------ ejecución de un trabajo
    def _error_from_code(self, code: str, details: str = "") -> dict[str, Any]:
        lang = self._lang()
        data = errors.catalog()
        rule = next((r for r in data["rules"] if r["code"] == code), data["fallback"])
        return {"code": rule["code"], "action": rule["action"], "auto": rule.get("auto"),
                "title": rule[lang]["title"], "message": rule[lang]["message"], "details": details,
                "texts": {"es": rule["es"], "en": rule["en"]}}

    def _preflight(self, job: Job) -> dict[str, Any] | None:
        dest = Path(job.dest_dir)
        try:
            dest.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=dest, prefix=".kd-", delete=True):
                pass
        except OSError as exc:
            return self._error_from_code("permission", f"{dest}: {exc}")
        try:
            if shutil.disk_usage(dest).free < MIN_FREE_BYTES:
                return self._error_from_code("disk_full", str(dest))
        except OSError:
            pass
        return None

    def _on_event(self, job: Job, ev: KdlEvent) -> None:
        with self._lock:
            if ev.kind == "dl":
                d, info = ev.data, ev.info
                fid = str(info.get("format_id") or "")
                if fid and fid not in job.formats_seen:
                    job.formats_seen.append(fid)
                kind = stream_kind(info)
                if not job.step_total:
                    job.step_total = 1 if (job.option in ("mp3", "m4a") or kind == "av") else 2
                job.step_index = max(1, min(len(job.formats_seen), 9))
                job.step_total = max(job.step_total, job.step_index)
                job.step_kind = kind
                total = int(d.get("total_bytes") or d.get("total_bytes_estimate") or 0)
                done = int(d.get("downloaded_bytes") or 0)
                frac: float | None
                if d.get("status") == "finished":
                    frac = 1.0
                elif total:
                    frac = min(done / total, 1.0)
                elif d.get("fragment_count"):
                    frac = min(int(d.get("fragment_index") or 0) / int(d["fragment_count"]), 1.0)
                else:
                    frac = None
                weights = [0.9, 0.1] if job.step_total == 2 else [1 / job.step_total] * job.step_total
                if frac is not None:
                    idx = min(job.step_index, len(weights))
                    job.progress = min(sum(weights[: idx - 1]) + weights[idx - 1] * frac, 0.99)
                job.downloaded, job.total = done, total
                job.speed = d.get("speed")
                job.eta = d.get("eta")
                job.state, job.postprocessor, job.auto = "downloading", None, None
                speed = job.speed or 0
                if job.is_youtube and d.get("status") == "downloading" and 0 < speed < SLOW_BYTES_PER_SEC:
                    job.slow_since = job.slow_since or time.time()
                    if time.time() - job.slow_since > 20 and "slow_site" not in job.notes:
                        job.notes.append("slow_site")
                elif speed >= SLOW_BYTES_PER_SEC:
                    job.slow_since = None
            elif ev.kind == "pp" and ev.data.get("status") == "started":
                job.state = "processing"
                job.postprocessor = str(ev.data.get("postprocessor") or "")
                job.speed = job.eta = None
            elif ev.kind == "file" and ev.path:
                job.file_path = ev.path
            self._emit_job(job)

    def _worker(self, job: Job) -> None:
        try:
            self._run(job)
        except Exception as exc:  # noqa: BLE001 - nunca dejar un trabajo colgado
            log.exception("Fallo inesperado en el trabajo %s", job.id)
            with self._lock:
                job.state, job.finished_at = "error", time.time()
                job.error = self._error_from_code("unknown", repr(exc))
                self._emit_job(job)
        finally:
            with self._lock:
                self._runs.pop(job.id, None)
                self._canceled.discard(job.id)
                if job.group:
                    self._group_last_end[job.group] = time.time()
            self._wake.set()

    def _run(self, job: Job) -> None:
        problem = self._preflight(job)
        if problem:
            with self._lock:
                job.state, job.error, job.finished_at = "error", problem, time.time()
                self._emit_job(job)
            return
        s = self.settings.get()
        choice = option_to_choice(job.option)
        choice_args = (presets.fallback_args(choice) if job.force_best
                       else presets.build_args(choice, is_youtube=job.is_youtube, max_codec=s.max_codec))
        out_args = presets.output_template(s.filename_style, job.legacy_stamp)
        temp = paths.temp_root() / job.id
        run = DownloadRun(self._config(), job.url, choice_args, out_args, Path(job.dest_dir), temp,
                          on_event=lambda ev: self._on_event(job, ev), use_cookies=job.use_cookies,
                          extra=QUIET_ARGS if self._quiet() else None)
        taken = self.engine.acquire()
        try:
            with self._lock:
                if job.id in self._canceled:
                    job.state, job.finished_at = "canceled", time.time()
                    self._emit_job(job)
                    return
                self._runs[job.id] = run
                run.start()
            result = run.wait()
        finally:
            self.engine.release(taken)
        canceled = job.id in self._canceled or result.canceled
        if canceled:
            with self._lock:
                job.state, job.finished_at, job.speed, job.eta = "canceled", time.time(), None, None
                self._emit_job(job)
            shutil.rmtree(temp, ignore_errors=True)
            return
        if result.returncode == 0 or (result.already_downloaded and not result.files):
            self._finish_ok(job, result)
            shutil.rmtree(temp, ignore_errors=True)
            return
        self._handle_failure(job, result.lines)

    def _finish_ok(self, job: Job, result) -> None:
        with self._lock:
            if result.already_downloaded and "already_downloaded" not in job.notes:
                job.notes.append("already_downloaded")
                if not job.file_path:
                    found = _path_from_already_line(result.lines)
                    if found and _inside(found, job.dest_dir):
                        job.file_path = found
        size = 0
        if job.file_path:
            try:
                size = Path(job.file_path).stat().st_size
            except OSError:
                size = 0
        with self._lock:
            job.state, job.progress, job.finished_at = "done", 1.0, time.time()
            job.speed = job.eta = None
            job.postprocessor = None
            job.auto = None
            if size:
                job.downloaded = job.total = size  # en "listo" la UI muestra el tamaño final del archivo
            self._emit_job(job)
        if job.file_path:
            self.history.add(HistoryItem(
                id=job.id, url=job.url, title=job.title or Path(job.file_path).stem, uploader=job.uploader,
                thumbnail=job.thumbnail, site=job.site, option=job.option, container=job.container,
                file_path=job.file_path, size=size, finished_at=job.finished_at or time.time()))
        if self._on_finished:
            self._on_finished(job)

    def _handle_failure(self, job: Job, lines: list[str]) -> None:
        err = errors.classify(lines, self._lang())
        auto = err.auto
        if auto == "update_retry" and not job.engine_updated:
            with self._lock:
                job.auto, job.state = "updating_engine", "probing"
                self._emit_job(job)
            try:
                hints = self.hints.hints
                channel = self.settings.get().engine_channel
                self.engine.check_updates(channel=channel, force=True, minimum=hints.min_ytdlp.get(channel))
            except Exception:  # noqa: BLE001
                log.exception("No se pudo actualizar el motor durante la autorreparación")
            self._requeue(job, engine_updated=True)
            return
        if auto == "fallback_best" and not job.force_best:
            with self._lock:
                job.force_best = True
                if "fallback_quality" not in job.notes:
                    job.notes.append("fallback_quality")
            self._requeue(job)
            return
        if auto == "retry_network" and job.net_retries < 3:
            delay = 5 * (2 ** job.net_retries)
            with self._lock:
                job.net_retries += 1
                job.auto, job.state = "retrying", "probing"
                self._emit_job(job)
            end = time.time() + delay
            while time.time() < end:
                if self._stop.is_set() or job.id in self._canceled:
                    break
                time.sleep(0.25)
            self._requeue(job)
            return
        if auto == "quiet_mode":
            self._quiet_until = time.time() + QUIET_MINUTES * 60
        with self._lock:
            job.state, job.finished_at, job.auto = "error", time.time(), None
            job.speed = job.eta = None
            job.error = err.to_dict()
            self._emit_job(job)
        if self._on_finished:
            self._on_finished(job)

    def _requeue(self, job: Job, engine_updated: bool | None = None) -> None:
        with self._lock:
            if job.id in self._canceled:
                job.state, job.finished_at = "canceled", time.time()
            else:
                job.state = "queued"
                job.auto = "retrying"
                if engine_updated is not None:
                    job.engine_updated = engine_updated
                job.reset_progress()
            self._emit_job(job)
        self._wake.set()


def _inside(path: str, folder: str) -> bool:
    try:
        Path(path).resolve().relative_to(Path(folder).resolve())
        return True
    except (OSError, ValueError):
        return False


def _path_from_already_line(lines: list[str]) -> str | None:
    for line in reversed(lines):
        marker = " has already been downloaded"
        if marker in line and line.startswith("[download] "):
            return line[len("[download] "): line.index(marker)].strip()
    return None
