<!-- SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# K-Snag para Android · Cambios

Hasta la 1.0.2 la app se llamaba K-Downloader.

Cada versión tiene su sección `## X.Y.Z - AAAA-MM-DD`; el pipeline de publicación la copia a las notas del Release.

## 1.0.4 - 2026-11-06

**Español**
- El motor que trae la app (yt-dlp) pasa a la versión 2026.11.04. Si ya lo tenías actualizado, no cambia nada.
- Primera versión preparada para F-Droid. Allá la app se actualiza por F-Droid y el motor no se actualiza solo a menos que lo actives en Ajustes. La versión de GitHub sigue igual que siempre.

**English**
- The engine bundled with the app (yt-dlp) moves to 2026.11.04. If yours was already up to date, nothing changes.
- First version ready for F-Droid. There, F-Droid handles app updates and the engine only updates itself if you turn it on in Settings. The GitHub version works the same as before.

## 1.0.3 - 2026-11-02

**Español**
- **K-Downloader ahora se llama K-Snag.** Es la misma app: se actualiza encima, sin perder tu historial ni tus ajustes. Las descargas nuevas quedan en **Descargas › K-Snag**; las de antes siguen en Descargas › K-Downloader y se abren igual desde el historial. La web nueva es **ksnagapp.vercel.app**.
- «Donar» en la barra de abajo y un botón con corazón junto al cuadro del enlace en Inicio. Los dos abren la página de donaciones en el navegador.

**English**
- **K-Downloader is now K-Snag.** Same app: it updates in place and keeps your history and settings. New downloads go to **Downloads › K-Snag**; older ones stay in Downloads › K-Downloader and still open from the history. The new website is **ksnagapp.vercel.app**.
- "Donate" in the bottom bar and a heart button next to the link box on Home. Both open the donation page in the browser.

## 1.0.2 - 2026-11-02

**Español**
- Nuevo: «Apoya K-Downloader» en Ajustes y en Acerca de. Abre la página de donaciones de la web: Bre-B desde Colombia o PayPal desde cualquier país. Donar es voluntario y no cambia nada de la app.

**English**
- New: "Support K-Downloader" in Settings and About. It opens the website's donation page: PayPal from any country, or Bre-B in Colombia. Donating is optional and changes nothing in the app.

## 1.0.1 - 2026-11-02

**Español**
- Arreglo: al compartir un enlace desde YouTube (u otra app) a K-Downloader, la app no llegaba a pedir el permiso de notificaciones, así que no aparecía la notificación con el porcentaje. Ahora lo pide al empezar la descarga, también desde la hoja de compartir.
- Si las notificaciones están apagadas, Inicio lo avisa con un botón para activarlas, y Ajustes › Descargas tiene un acceso directo a los ajustes de notificaciones.

**English**
- Fix: when sharing a link from YouTube (or another app) to K-Downloader, the app never got to ask for the notification permission, so the notification with the percentage didn't show up. It now asks when the download starts, from the share sheet too.
- If notifications are off, Home says so with a button to turn them on, and Settings › Downloads has a shortcut to the notification settings.

## 1.0.0 - 2026-11-02

**Español**
- Primera versión estable de K-Downloader para Android: pega o comparte un enlace y guárdalo como video (MP4 hasta 1080p o la máxima calidad en MKV) o audio (MP3 o M4A) en Descargas › K-Downloader.
- Antes de bajar, la app analiza el enlace: miniatura, título, calidades y tamaños reales; listas de hasta 500 videos.
- El motor (yt-dlp) se actualiza solo cuando YouTube cambia, con reintentos automáticos.
- Las descargas siguen en segundo plano, se retoman si Android cierra la app y pueden esperar el Wi-Fi.
- La app se actualiza sola desde GitHub (Android pide confirmación).
- Funciona en celulares con páginas de memoria de 16 KB (Android 15+).

**English**
- First stable release of K-Downloader for Android: paste or share a link and save it as video (MP4 up to 1080p, or best quality in MKV) or audio (MP3 or M4A) to Downloads › K-Downloader.
- Before downloading, the app checks the link: thumbnail, title, real qualities and sizes; playlists of up to 500 videos.
- The engine (yt-dlp) updates itself when YouTube changes, with automatic retries.
- Downloads keep going in the background, resume if Android closes the app and can wait for Wi-Fi.
- The app updates itself from GitHub (Android asks for confirmation).
- Works on phones with 16 KB memory pages (Android 15+).

## 1.0.0-rc.2 - 2026-11-02

**Español**
- Segunda versión de prueba: sirve para comprobar que la rc.1 encuentra esta versión y se actualiza sola desde Ajustes › Actualizaciones de la app.
- Las sumas SHA-256 del Release ya no listan archivos .idsig que no se publican.

**English**
- Second test release: it checks that rc.1 finds this version and updates itself from Settings › App updates.
- The Release's SHA-256 sums no longer list .idsig files that aren't published.

## 1.0.0-rc.1 - 2026-11-02

**Español**
- Primera versión de prueba: pega o comparte un enlace y bájalo como video (MP4 hasta 1080p o la máxima calidad en MKV) o audio (MP3 o M4A), a Descargas › K-Downloader.
- Antes de bajar, la app analiza el enlace: miniatura, título, calidades y tamaños reales; listas de hasta 500 videos.
- El motor (yt-dlp) se actualiza solo cuando YouTube cambia, con reintentos automáticos.
- Las descargas siguen en segundo plano, se retoman si Android cierra la app y pueden esperar el Wi-Fi.
- Funciona en celulares con páginas de memoria de 16 KB (Android 15+).

**English**
- First test release: paste or share a link and save it as video (MP4 up to 1080p, or best quality in MKV) or audio (MP3 or M4A) to Downloads › K-Downloader.
- Before downloading, the app checks the link: thumbnail, title, real qualities and sizes; playlists of up to 500 videos.
- The engine (yt-dlp) updates itself when YouTube changes, with automatic retries.
- Downloads keep going in the background, resume if Android closes the app and can wait for Wi-Fi.
- Works on phones with 16 KB memory pages (Android 15+).
