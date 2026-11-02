# Avisos de terceros · Third-party notices

K-Snag se publica con la [licencia MIT](LICENSE). Se apoya en software libre de otras personas, que conserva su propia licencia. Este archivo dice qué componentes hay, bajo qué licencia y cómo los usa la app.

*English summary: K-Snag is MIT-licensed. The tables below list every third-party component, its license (SPDX identifier) and how it is used: bundled in the installer's runtime, or downloaded at first run from the official upstream source and never redistributed by this project.*

- Las versiones exactas del runtime están en [`packaging/runtime.json`](packaging/runtime.json) y [`packaging/requirements-runtime.txt`](packaging/requirements-runtime.txt).
- Los textos completos de cada licencia se copian en la carpeta `licenses\` de la instalación.

## 1. Incluidos en el instalador (`runtime\`)

Van dentro de `kd-bundle-<versión>.zip` sin modificaciones. El código Python va como código fuente (`.py`), y algunos paquetes traen además módulos compilados (`.pyd`/`.dll`) publicados por sus autores en PyPI.

| Componente | Licencia | Para qué lo usa K-Snag |
| --- | --- | --- |
| [Python](https://www.python.org/) embeddable (Windows x64) | PSF-2.0 | Intérprete oficial firmado por la Python Software Foundation; ejecuta la app y yt-dlp. Su `LICENSE.txt` cubre también bzip2, libffi, OpenSSL (Apache-2.0) y el código distribuible de Microsoft (`vcruntime140.dll`); incluye SQLite, que es de dominio público. |
| [pywebview](https://github.com/r0x0r/pywebview) | BSD-3-Clause | Ventana de la app con WebView2. Trae las DLL del SDK de Microsoft WebView2 (`Microsoft.Web.WebView2.*.dll`, `WebView2Loader.dll`), con la licencia BSD de Microsoft para ese SDK. |
| [pythonnet](https://github.com/pythonnet/pythonnet) | MIT | Puente entre Python y .NET que usa pywebview. Trae ensamblados de fachada de .NET (MIT, .NET Foundation). |
| [clr_loader](https://github.com/pythonnet/clr-loader) | MIT | Carga el runtime de .NET para pythonnet. |
| [cffi](https://github.com/python-cffi/cffi) | MIT-0 | Dependencia de clr_loader y curl_cffi. |
| [pycparser](https://github.com/eliben/pycparser) | BSD-3-Clause | Dependencia de cffi. |
| [proxy_tools](https://github.com/jtushman/proxy_tools) | MIT | Dependencia de pywebview. |
| [bottle](https://github.com/bottlepy/bottle) | MIT | Dependencia de pywebview. |
| [typing_extensions](https://github.com/python/typing_extensions) | PSF-2.0 | Dependencia de pywebview. |
| [comtypes](https://github.com/enthought/comtypes) | MIT | Accesos directos con el ícono y el identificador de la app (AppUserModelID). |
| [Windows-Toasts](https://github.com/DatGuy1/Windows-Toasts) | Apache-2.0 | Notificación "Descarga lista". |
| [winrt-runtime y winrt-Windows.\*](https://github.com/pywinrt/pywinrt) | MIT | Dependencias de Windows-Toasts. |
| [curl_cffi](https://github.com/lexiforest/curl_cffi) | MIT | Extra de yt-dlp: conexiones que imitan a un navegador real. Incluye `libcurl-impersonate` (MIT, sobre curl, que tiene licencia curl), BoringSSL (Apache-2.0, con partes OpenSSL/ISC), bibliotecas de HTTP/2 y HTTP/3 (nghttp2, ngtcp2 y nghttp3, MIT) y zlib (Zlib), entre otras. |
| [Brotli](https://github.com/google/brotli) | MIT | Extra de yt-dlp: compresión Brotli. |
| [websockets](https://github.com/python-websockets/websockets) | BSD-3-Clause | Extra de yt-dlp: sitios que transmiten por WebSocket. |
| [mutagen](https://github.com/quodlibet/mutagen) | GPL-2.0-or-later | Extra de yt-dlp: incrusta portadas y metadatos. Es Python puro y se distribuye sin cambios, como código fuente y con su licencia (`COPYING`). K-Snag no lo importa: solo lo usa yt-dlp, que corre como un programa aparte. |
| [certifi](https://github.com/certifi/python-certifi) | MPL-2.0 | Certificados raíz para conexiones HTTPS. Se distribuye sin cambios; su código fuente está en el enlace. |
| [requests](https://github.com/psf/requests) | Apache-2.0 | Extra de yt-dlp: cliente HTTP. Incluye su archivo `NOTICE`. |
| [urllib3](https://github.com/urllib3/urllib3) | MIT | Dependencia de requests. |
| [charset-normalizer](https://github.com/jawah/charset_normalizer) | MIT | Dependencia de requests. |
| [idna](https://github.com/kjd/idna) | BSD-3-Clause | Dependencia de requests. |

## 2. Descargados en el primer uso, sin redistribuirse

K-Snag **no incluye** estos programas en su instalador ni en sus Releases. La app los baja en tu PC directamente desde la fuente oficial, comprueba su SHA-256 publicado y los guarda en `%LOCALAPPDATA%\K-Downloader\engine\`. Cada uno conserva su licencia y la distribuye su autor.

| Componente | Licencia | Fuente | Para qué lo usa K-Snag |
| --- | --- | --- | --- |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp), zipapp `yt-dlp` | Unlicense | [yt-dlp-nightly-builds](https://github.com/yt-dlp/yt-dlp-nightly-builds/releases) (por defecto) o [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases) (estable), verificado con `SHA2-256SUMS` | Motor de descarga. Incluye [yt-dlp-ejs](https://github.com/yt-dlp/ejs) (Unlicense). Corre con el Python del runtime como proceso aparte. |
| [FFmpeg](https://ffmpeg.org/), build *release essentials* de [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) | GPL-3.0-or-later | gyan.dev, verificado con su `.sha256` | Une audio y video, convierte a MP3/M4A e incrusta portadas. Solo se usan `ffmpeg.exe` y `ffprobe.exe`, como programas aparte. El código fuente de FFmpeg está en <https://ffmpeg.org/download.html>. |
| [Deno](https://deno.com/) | MIT | [denoland/deno](https://github.com/denoland/deno/releases), verificado con su `.sha256sum` | Entorno de JavaScript que yt-dlp usa para resolver los desafíos de YouTube. |

Si en tu PC ya hay un FFmpeg 9 o posterior, o un Deno 2.3 o posterior, en el `PATH`, la app puede usarlos en lugar de descargarlos.

## 3. Interfaz y sitio web

| Componente | Licencia | Uso |
| --- | --- | --- |
| [Lucide](https://lucide.dev/) | ISC (con partes de [Feather](https://feathericons.com/), MIT) | Íconos SVG de la app (`src/kdownloader/ui/js/icons.js`) y del sitio web (`web/assets/icons.svg`, con su licencia en `web/assets/icons-LICENSE.txt`). |

La interfaz no incluye fuentes tipográficas propias: usa las del sistema (Segoe UI y Cascadia Mono). El logo y el ícono de K-Snag son parte de este proyecto (MIT).

## 4. Solo para desarrollar (no se distribuyen)

| Herramienta | Licencia | Uso |
| --- | --- | --- |
| [pytest](https://github.com/pytest-dev/pytest) | MIT | Pruebas automáticas. |
| [Ruff](https://github.com/astral-sh/ruff) | MIT | Revisión de estilo y errores. |
| [Pillow](https://github.com/python-pillow/Pillow) | MIT-CMU | Solo `packaging/make_icon.py`, para generar `K-Downloader.ico`. |

---

¿Falta algo o hay un dato incorrecto? Abre un [issue](https://github.com/Kasaco223/K-Snag/issues). Lo corregimos con prioridad.
