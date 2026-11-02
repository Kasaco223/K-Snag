# Arquitectura de K-Snag 2.0

Documento de contratos entre las partes de la app. Si cambias un contrato, actualiza este archivo primero.

## 1. Visión general

```text
Usuario ─► Instalar-K-Snag.cmd ─► kd-bundle-<ver>.zip (GitHub Releases, SHA-256 verificado)
                                           │
%LOCALAPPDATA%\Programs\K-Downloader\      ▼
  runtime\pythonw.exe launch.pyw ─► kdownloader.app (pywebview + WebView2)
        UI (HTML/CSS/JS)  ◄── js_api (llamadas) / run_js (eventos) ──►  backend Python
                                                                          │ subprocess + Job Object
                                                                          ▼
%LOCALAPPDATA%\K-Downloader\engine\  yt-dlp.pyz (zipapp) · ffmpeg.exe/ffprobe.exe (gyan.dev) · deno.exe
```

**Por qué así:** Windows 11 con Smart App Control bloquea cualquier `.exe` nuevo sin firma.

- **Lo que corre:** Python oficial (firmado por la PSF), Deno (firmado), FFmpeg de gyan.dev (con reputación) y scripts.
- **Lo que evitamos:** no generamos `.exe` propios.
- **yt-dlp** se usa como zipapp de Python puro.

## 2. Layout del repositorio

| Ruta | Contenido |
|---|---|
| `src/kdownloader/` | Paquete Python (backend + UI estática en `ui/`) |
| `src/kdownloader/engine/` | Motor: componentes, procesos, presets, progreso, errores, pistas |
| `src/kdownloader/ui/` | Interfaz HTML/CSS/JS sin build ni frameworks |
| `packaging/` | Build del bundle, plantilla del instalador `.cmd`, `launch.pyw`, ícono |
| `web/` | Sitio estático para Vercel |
| `engine.json` | Pistas remotas del motor (se lee desde raw.githubusercontent.com) |
| `tests/` | pytest: unitarias (sin red), `network` y `e2e` marcadas |
| `INSTALADOR/Instalador.bat` | Compatibilidad: abre la página de descarga |

## 3. Layout instalado (máquina del usuario)

```text
%LOCALAPPDATA%\Programs\K-Downloader\        (raíz de instalación, solo usuario actual, sin admin)
  runtime\                 Python embeddable 3.13 + Lib\site-packages (pywebview, pythonnet, comtypes, curl_cffi…)
  app\<versión>\kdownloader\   código de la app (una carpeta por versión)
  current                  texto con la versión activa, p. ej. "2.0.0"
  launch.pyw               lanzador estable (ver §8)
  K-Downloader.ico
  uninstall.cmd
  licenses\
%LOCALAPPDATA%\K-Downloader\                 (datos; KD_DATA_DIR lo sobrescribe en pruebas)
  settings.json  history.json  jobs.json  hints.json
  engine\manifest.json  engine\ytdlp\<tag>\yt-dlp.pyz  engine\ffmpeg\gyan-<ver>\  engine\deno\<tag>\deno.exe
  t\<id8>\  (temporales por trabajo)  cache\  logs\app.log  updates\
```

## 4. API del puente (JS → Python)

- **Llamada:** JS llama `await window.pywebview.api.<método>(args)` después del evento `pywebviewready`.
- **Respuesta:** siempre `{ok: true, ...}` o `{ok: false, error: ErrorView}`.
- **Seguridad:** JS nunca envía rutas de archivos ni URLs para abrir. Envía IDs (`job_id`, `token`, claves de página).

| Método | Parámetros | Respuesta (`ok: true`) |
|---|---|---|
| `ui_ready()` | — | `{state: AppState}` · arranca el envío de eventos |
| `get_state()` | — | `{state: AppState}` |
| `accept_terms()` | — | `{settings}` |
| `finish_onboarding(patch)` | `Partial<Settings>` | `{settings}` · marca `first_run_done` |
| `engine_setup()` | — | `{}` · instala lo que falte en segundo plano (eventos `engine`) |
| `engine_update(force)` | `bool` | `{}` |
| `engine_repair(component)` | `"ytdlp"\|"ffmpeg"\|"deno"\|null` | `{}` |
| `probe(url, playlist)` | `str`, `bool` | `{media: MediaView}` · puede tardar segundos |
| `cancel_probe()` | — | `{}` |
| `enqueue(token, option, items)` | `str`, `OptionKey`, `"all"\|string[]\|null` | `{job_ids: string[]}` |
| `quick_download(url)` | `str` | `{job_ids: string[]}` · usa la opción favorita |
| `cancel(job_id)` / `remove(job_id)` | `str` | `{}` |
| `retry(job_id, mode)` | `str`, `"normal"\|"with_cookies"\|"best"` | `{}` |
| `cancel_all()` / `clear_finished()` | — | `{}` |
| `open_file(job_id)` / `show_in_folder(job_id)` | `str` | `{}` |
| `open_source(job_id)` | `str` | `{}` · abre el video original en el navegador |
| `copy_details(job_id)` | `str` | `{text}` · JS lo copia con `navigator.clipboard` |
| `open_help(code)` | código de error | `{}` · abre `https://<web>/ayuda#<code>` |
| `open_page(key)` | `"home"\|"help"\|"responsible"\|"download"\|"github"\|"releases"\|"issues"` | `{}` |
| `choose_folder()` / `choose_cookies_file()` | — | `{settings}` o `{ok:false}` si se cancela |
| `clear_cookies_file()` | — | `{settings}` |
| `save_settings(patch)` | `Partial<Settings>` | `{settings}` |
| `read_clipboard()` | — | `{url: string\|null}` · solo devuelve algo con forma de URL http(s) |
| `history(query)` | `str` | `{items: HistoryItem[]}` |
| `history_remove(id)` / `history_clear()` | — | `{}` |
| `check_app_update()` | — | `{update: UpdateView\|null}` |
| `apply_app_update()` | — | `{}` · descarga, verifica y reinicia la app |
| `confirm_close()` | — | `{}` · el usuario confirmó cerrar con descargas activas |

`OptionKey`: `"recommended" | "max" | "mp3" | "m4a" | "h<altura>"` (p. ej. `"h720"`).

## 5. Eventos (Python → JS)

Python llama `window.kd.emit(evento)` (vía `run_js`, desde un único hilo, a ≤10 Hz). La UI define `window.kd.emit` antes de `ui_ready()`.

| `type` | Campos |
|---|---|
| `state` | `state: AppState` (refresco completo, poco frecuente) |
| `job` | `job: JobView` (alta o actualización) |
| `job_removed` | `id` |
| `engine` | `engine: EngineView` |
| `settings` | `settings: Settings` |
| `incoming_url` | `url` (desde `kdownloader://` o una segunda instancia) |
| `update` | `update: UpdateView` |
| `notice` | `notice: {es, en}` (pistas remotas) |
| `confirm_close` | `active: number` (descargas en curso al intentar cerrar) |

## 6. Estructuras

```ts
AppState = {
  version: string, lang: "es"|"en", platform: string,
  settings: Settings, engine: EngineView, jobs: JobView[],
  first_run: boolean, update: UpdateView|null, notice: {es?: string, en?: string}|null,
  web_base: string
}
Settings = {
  language: "auto"|"es"|"en", theme: "system"|"light"|"dark", reduce_motion: boolean,
  download_dir: string, favorite_preset: "recommended"|"max"|"mp3"|"m4a", quick_download: boolean,
  filename_style: "title_id"|"legacy_k"|"simple", max_concurrent: 1..4,
  engine_channel: "nightly"|"stable", auto_update_engine: boolean, max_codec: "vp9"|"av1",
  clipboard_suggest: boolean, notify_on_finish: boolean, open_folder_on_finish: boolean,
  cookies_file: string, accepted_terms: boolean, first_run_done: boolean, check_app_updates: boolean
}
EngineView = {
  ready: boolean, busy: boolean, channel: "nightly"|"stable",
  components: { ytdlp: Component, ffmpeg: Component, deno: Component }
}
Component = {
  state: "missing"|"checking"|"downloading"|"installing"|"ready"|"error"|"blocked",
  version: string|null, source: "managed"|"system"|null,
  progress: number|null /*0..1*/, done: number, total: number, error: string|null,
  approx_size: number /*bytes, para mostrar antes de bajar*/
}
MediaView = {
  token: string, title: string, uploader: string, duration: number|null, thumbnail: string|null,
  url: string, site: string, is_youtube: boolean, is_live: boolean, age_limit: number,
  is_playlist: boolean, count?: number, total_duration?: number,
  entries?: {id: string, title: string, duration: number|null}[],
  degraded: boolean, warnings: string[],
  options: OptionView[],  // las 3 grandes: recommended, max, mp3
  more: OptionView[]      // alturas concretas + m4a
}
OptionView = {
  key: OptionKey, kind: "video"|"audio", container: "mp4"|"mkv"|"mp3"|"m4a",
  height: number|null, fps: number|null, vcodec: "h264"|"vp9"|"av1"|"other"|null,
  acodec: "aac"|"opus"|"mp3"|"other"|null, hdr: boolean,
  size: number|null, size_approx: boolean, available: boolean
}
JobView = {
  id: string, title: string, uploader: string, thumbnail: string|null, site: string,
  option: OptionKey, container: string, height: number|null,
  state: "queued"|"probing"|"downloading"|"processing"|"done"|"error"|"canceled"|"interrupted",
  step: {index: number, total: number, kind: "video"|"audio"|"av"}|null,
  postprocessor: string|null,  // Merger | ExtractAudio | EmbedThumbnail | Metadata | VideoRemuxer | MoveFiles…
  progress: number|null, downloaded: number, total: number, speed: number|null, eta: number|null,
  file_name: string|null, file_exists: boolean,
  error: ErrorView|null, auto: "updating_engine"|"retrying"|"quiet_mode"|"fallback"|null,
  notes: ("fallback_quality"|"already_downloaded"|"slow_site"|"cookies_used")[],
  created_at: number, finished_at: number|null
}
ErrorView = {
  code: string, action: string, auto: string|null, title: string, message: string, details: string,
  texts: {es: {title, message, help?}, en: {title, message, help?}}
}
HistoryItem = {
  id, title, uploader, thumbnail, site, option, container, file_name, file_exists, size, finished_at
}
UpdateView = { version: string, notes_url: string }
```

Acciones de error (`ErrorView.action`), que la UI convierte en botones:

| Acción | Botón y efecto |
|---|---|
| `update_retry` | "Actualizar y reintentar" → `retry(id,"normal")`, tras `engine_update(true)` |
| `retry` | "Reintentar" |
| `retry_later` | "Reintentar en unos minutos" |
| `choose_quality` | "Elegir otra calidad" → `retry(id,"best")` |
| `cookies` | "Usar cookies.txt" → `choose_cookies_file()` y luego `retry(id,"with_cookies")` |
| `choose_folder` | "Elegir otra carpeta" |
| `repair_engine` | "Reparar componentes" |
| `open_browser` | "Abrir en el navegador" → `open_source(id)` |
| `none` | Sin botón principal |

Siempre se ofrece además "Ver detalles" / "Copiar detalles" y "Ayuda" → `open_help(code)`.

## 7. Protocolo con yt-dlp

- **Ejecución:** `runtime\python.exe -I -X utf8 yt-dlp.pyz --ignore-config --no-update --no-plugin-dirs --color never …`. Va en un Job Object con `KILL_ON_JOB_CLOSE` (ver `engine/process.py`).
- **Progreso:** líneas `KDL|dl|…`, `KDL|pp|…` y `KDL|file|…` (ver `engine/progress.py`), todas por stdout gracias a `--no-quiet`.
- **Presets:** en `engine/presets.py`. Están validados contra el cliente `visionos`: recomendado = `299+140` (H.264 1080p60 + AAC, MP4) y máxima = `315+251` (VP9 4K + Opus, MKV).

## 8. Lanzador, instalación y protocolo

- **`launch.pyw`:**
  1. Lee `current`, inserta `app\<versión>` en `sys.path` y define `KD_INSTALL_ROOT`.
  2. Despacha:
     - sin argumentos → `kdownloader.app.main(argv)`;
     - `--protocol <uri>` → la app con la URL;
     - `--install-finish` / `--uninstall` → `kdownloader.installer`.
- **Acceso directo:**
  - destino `runtime\pythonw.exe` con argumento `"<raíz>\launch.pyw"`;
  - ícono `K-Downloader.ico` y AppUserModelID `Kasaco223.KDownloader`.
  - Ubicación: menú Inicio, y escritorio si se elige.
- **Protocolo `kdownloader://download?url=<URL codificada>`:** registro `HKCU\Software\Classes\kdownloader`, con `URL Protocol` vacío y `shell\open\command` = `"<raíz>\runtime\pythonw.exe" "<raíz>\launch.pyw" --protocol "%1"`. Solo se acepta http(s) y siempre se muestra la vista previa antes de descargar.
- **Desinstalación:** `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\K-Downloader`.
  - `UninstallString` = `"%SystemRoot%\System32\cmd.exe" /c ""<raíz>\uninstall.cmd""`.
  - `uninstall.cmd` ejecuta `runtime\python.exe launch.pyw --uninstall`.
- **Instalador `Instalar-K-Snag.cmd`** (ASCII, generado por versión con `VERSION`, `BUNDLE_URL` y `BUNDLE_SHA256`):
  1. Baja el bundle con `curl.exe` (o usa `--local <zip>`) y verifica el SHA-256.
  2. Cierra K-Snag si está abierto y extrae con `tar.exe`.
  3. Ejecuta `runtime\python.exe launch.pyw --install-finish --version <v> [--desktop]` y abre la app.

## 9. Archivos remotos

```jsonc
// latest.json (asset de cada Release; la app lo lee desde /releases/latest/download/latest.json)
{ "version": "2.0.0", "runtime": "3.13.16-1",
  "app":       { "url": ".../kd-app-2.0.0.zip",    "sha256": "…" },
  "bundle":    { "url": ".../kd-bundle-2.0.0.zip", "sha256": "…" },
  "installer": { "url": ".../Instalar-K-Snag.cmd", "sha256": "…" },
  "notes_url": "https://github.com/Kasaco223/K-Snag/releases/tag/v2.0.0" }

// engine.json (raíz del repo; solo campos en lista blanca, sin URLs)
{ "schema": 1, "min_ytdlp": { "nightly": "2026.09.27.232945", "stable": "2026.08.19" },
  "youtube_player_client": null, "notice": { "es": null, "en": null } }
```

- **Autoactualización:** si `runtime` coincide con el instalado, se baja solo `kd-app-<ver>.zip` (~1 MB) a `app\<ver>\`, se reescribe `current` y se reinicia. Si no coincide, se abre la página de descarga.

## 10. Reglas de seguridad

- **Remoto:** títulos, canales y mensajes se pintan siempre con `textContent`, nunca con `innerHTML`.
- **CSP de la UI:** `default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'`. pywebview necesita `unsafe-eval` para su puente.
- **Procesos:** nunca `shell=True`; las URLs van siempre después de `--`.
- **Entrada externa:** `kdownloader://`, el portapapeles y `engine.json` son entrada hostil. Se validan con listas blancas.
- **Descargas de componentes:** siempre con SHA-256 publicado. Si no hay hash, no se instala.
