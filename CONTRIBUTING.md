# Cómo colaborar con K-Snag

¡Gracias por querer ayudar! Puedes escribir en español o en inglés (*English is welcome too*).

El repositorio tiene tres partes:

| Parte | Carpeta | Licencia |
| --- | --- | --- |
| App de Windows (Python + pywebview) | `src/`, `packaging/`, `tests/` | MIT |
| App de Android (Kotlin + Jetpack Compose) | `android/` | GPL-3.0-or-later |
| Sitio web estático (ksnagapp.vercel.app) | `web/` | MIT |

Esta guía explica:

- cómo preparar el entorno de cada parte;
- qué estilo seguimos;
- cómo reportar un problema sin exponer tus datos.

## Antes de empezar

- Lee [docs/architecture.md](docs/architecture.md). Ahí están los contratos entre las partes de la app:
  - la API del puente entre JS y Python;
  - los eventos;
  - los archivos remotos;
  - el layout instalado.

  Si cambias un contrato, actualiza ese documento en el mismo pull request.
- Para Android, lee [android/README.md](android/README.md): estructura, motor, Docker, pruebas y CI.
- Para algo grande (una función nueva, otro sitio, otro formato), abre primero un issue y lo conversamos. Así nadie pierde tiempo.

## Preparar el entorno

### App de Windows

Necesitas Windows 10 u 11, Python 3.11 o posterior y Git. Node 22 es opcional, solo para revisar la sintaxis del JS.

```powershell
git clone https://github.com/Kasaco223/K-Snag.git
cd K-Snag
python -m venv .venv
.venv\Scripts\pip install -e ".[dev]"
.venv\Scripts\python -m kdownloader
```

Para no mezclar tus pruebas con una instalación real, usa una carpeta de datos aparte:

```powershell
$env:KD_DATA_DIR = "$env:TEMP\kd-dev"
```

### Variables útiles para pruebas

| Variable | Para qué |
| --- | --- |
| `KD_DATA_DIR` | Carpeta de datos (ajustes, historial, motor, registros) |
| `KD_WEB_BASE` | URL base de la web (por defecto `https://ksnagapp.vercel.app`) |
| `KD_UPDATE_URL` | `latest.json` alternativo, para probar la autoactualización |
| `KD_HINTS_URL` | `engine.json` alternativo, para probar las pistas del motor |

### Ver la interfaz sin Python

La UI tiene un modo simulado que corre en cualquier navegador:

```powershell
cd src\kdownloader\ui
python -m http.server 8000
```

Abre <http://localhost:8000/index.html?mock=1&scenario=S4>.

- `scenario` va de `S0` a `S9`.
- Puedes agregar `&theme=light|dark` y `&lang=es|en`.
- Sirve para revisar pantallas y sacar capturas reproducibles ([docs/screenshots/README.md](docs/screenshots/README.md)).

### App de Android

No hace falta instalar el SDK de Android: todo se compila con Docker Desktop. La primera vez hay que aceptar la licencia del SDK, como explica [android/README.md](android/README.md).

```powershell
# Primera vez: construye la imagen (5 a 10 minutos)
docker compose -f android/tools/docker/compose.yaml build

# APK debug para tu celular (se instala aparte de la versión normal)
docker compose -f android/tools/docker/compose.yaml run --rm kdl :app:assembleGithubDebug -Pkdl.devAbi=arm64-v8a
```

El APK queda en `android/build/docker-out/apk/github/debug/`. Si prefieres Android Studio, abre la carpeta `android/`.

### Sitio web

Es HTML, CSS y JS sin build:

```powershell
cd web
python -m http.server 8000
```

Abre <http://localhost:8000/index.html>. Las direcciones sin `.html` (`/android`, `/donar`) y las redirecciones de `vercel.json` solo funcionan en Vercel: en local, agrega `.html`.

## Estilo de código

### Python

- **Ruff**, con la configuración de `pyproject.toml`: largo de línea **120**, reglas `E`, `F`, `W`, `I`, `B`, `UP`, `SIM` y `RUF`.
  - Antes de cada commit: `.venv\Scripts\ruff check src tests`.
  - Lo automático se arregla con `ruff check --fix`.
- **Tipado:** `from __future__ import annotations` y anotaciones de tipos en las funciones públicas.
- **Comentarios y docstrings** en español, como el resto del código.
- **Procesos:**
  - nunca `shell=True`;
  - los argumentos van en una lista y las URLs siempre después de `--`.
- **Entrada externa:** las URLs de `kdownloader://`, el portapapeles y `engine.json` se tratan como entrada hostil y se validan con listas blancas.

### JavaScript, HTML y CSS (`src/kdownloader/ui/` y `web/`)

- JavaScript "a mano": sin frameworks, sin build y sin dependencias de npm.
- Los datos remotos (títulos, canales, mensajes de error) se pintan **siempre con `textContent`**, nunca con `innerHTML`.
- **Respeta la CSP:**
  - la de la app está en `docs/architecture.md`, y la de la web, en `web/vercel.json`;
  - nada de scripts en línea;
  - nada de recursos externos.
- **Íconos:** de [Lucide](https://lucide.dev/), con licencia ISC, en línea y con su atribución.

### Kotlin (`android/`)

- Jetpack Compose y Material 3. La lógica sin Android va en `android/core` (JVM pura), para probarla sin emulador.
- **Encabezado SPDX en cada archivo propio** (CI lo comprueba):

  ```text
  SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
  SPDX-License-Identifier: GPL-3.0-or-later
  ```

- **Textos:** `values-es/strings.xml` (español, el texto fuente) y `values/strings.xml` (inglés).
- **Dependencias nuevas:** regenera `android/gradle/verification-metadata.xml`, como explica [android/README.md](android/README.md) en «Verificación de dependencias».

### Textos

- **Todo texto visible va en los dos idiomas:** `es` y `en`.
- **Español neutro latinoamericano, tratando de "tú":**
  - "video" (sin tilde), "enlace" y "descargar";
  - "Pega aquí el enlace del video", no "Introduzca la URL".
- **Mensajes de error:**
  - dicen qué pasó y qué hacer;
  - sin jerga ni códigos crudos;
  - el texto técnico va en "Detalles".

### Licencias

- **App de Windows y web: MIT.** No copies código ni textos de proyectos con licencia GPL o AGPL, como Open Video Downloader, cobalt, ytDownloader o Seal. Tampoco "adaptes" sus mensajes de error.
- **App de Android: GPL-3.0-or-later.** Lo que aportes en `android/` queda bajo esa licencia. Nunca copies código de `android/` fuera de esa carpeta.
- **Si agregas una dependencia al runtime de Windows:**
  - fíjala con versión y SHA-256 en `packaging/requirements-runtime.txt`;
  - agrégala a [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Si agregas un componente de terceros a Android:** agrégalo a `android/third_party/components.json` y regenera los avisos con `notices.py` ([android/README.md](android/README.md)).

### Nombres internos que no se cambian

La app se llamó K-Downloader hasta noviembre de 2026. Estos nombres conservan el nombre anterior a propósito, porque las instalaciones existentes dependen de ellos. **No los «corrijas»:**

- **Windows:**
  - las carpetas `%LOCALAPPDATA%\K-Downloader` y `Programs\K-Downloader`, el ícono `K-Downloader.ico` y la clave `Uninstall\K-Downloader` (constante `DIR_NAME`);
  - el módulo `kdownloader`, el protocolo `kdownloader://`, el AUMID `Kasaco223.KDownloader`, el mutex y el pipe.
- **Android:**
  - el paquete `io.github.kasaco223.kdownloader`;
  - los archivos `K-Downloader-Android-<abi>.apk`;
  - las direcciones de los punteros con el nombre anterior del repo (`LEGACY_REPO`). Las versiones 1.0.0 a 1.0.3 solo aceptan esas direcciones ([docs/release-android.md](docs/release-android.md)).
- **Web:** `kdownloader.vercel.app/data/` sigue sirviendo los datos que leen las apps viejas.

## Pruebas

**App de Windows:**

```powershell
.venv\Scripts\ruff check src tests
.venv\Scripts\pytest              # unitarias, sin red (las que corre el CI)
.venv\Scripts\pytest -m network   # motor real: usa internet, yt-dlp y FFmpeg
.venv\Scripts\pytest -m e2e       # de punta a punta, con la ventana real
```

**App de Android** (en Docker):

```powershell
docker compose -f android/tools/docker/compose.yaml run --rm kdl :core:test :app:testGithubDebugUnitTest :app:lintGithubDebug
```

Las pruebas en el emulador (API 26, API 36 y API 36 con páginas de 16 KB) las corre el CI.

**Scripts de publicación** (`android/scripts/`), igual que en el CI:

```powershell
python -I -X utf8 android/scripts/tests/test_promote_channels.py android/scripts/promote.py web/vercel.json
python -I -X utf8 android/scripts/tests/test_notices.py android/scripts/notices.py
python -I -X utf8 android/scripts/tests/test_sources_mirror.py android/scripts/sources_mirror.py
python -I -X utf8 android/scripts/tests/test_check_pins.py android/scripts/check_pins.py
```

- **Nada de red en las pruebas unitarias.** Las que la necesiten van marcadas con `@pytest.mark.network`.
- **Datos reales:** `tests/fixtures/ytdlp/` guarda transcripciones reales de yt-dlp (stdout y stderr). Si agregas una regla de error o cambias el parser de progreso, pruébalo con texto real, no inventado.
- **Antes del pull request:** si tocaste el catálogo de errores, regenera los datos de la web con `python scripts/export_web_data.py` y súbelos en el mismo PR.

## Flujo de trabajo

1. Crea una rama desde `master`: `git switch -c arreglo/nombre-corto`.
2. **Commits:** pequeños y con mensajes claros, en español o inglés. Por ejemplo, `Motor: reintentar con AAC cuando falla la unión`.
3. **Pull request** hacia `master`, con la plantilla completa.
4. **CI:** debe quedar en verde. Cada workflow corre solo si tocaste su parte:
   - **CI (Windows):** ruff y pytest en Python 3.11 y 3.13, sintaxis del JS y build del bundle;
   - **Android CI:** revisión del repositorio (SPDX, licencias, espejo de fuentes), pruebas, lint, APK y los tres emuladores;
   - **Workflows y scripts:** actionlint, shellcheck y las pruebas de `android/scripts/`;
   - **Punteros:** `web/data/*.json` y `web/vercel.json`;
   - **Vercel:** una vista previa de la web.
5. **Publicaciones:** los tags, la aprobación de la firma de Android y las Releases los hace el mantenedor. Las guías son [docs/release.md](docs/release.md) (Windows) y [docs/release-android.md](docs/release-android.md) (Android).

## Reportar un problema

Usa la [plantilla de errores](https://github.com/Kasaco223/K-Snag/issues/new/choose): hay una para Windows y otra para Android. Incluye:

- **Versión de K-Snag:**
  - Windows: Ajustes → Acerca de; incluye también tu versión de Windows.
  - Android: Ajustes › Acerca de K-Snag; incluye el modelo del celular y la versión de Android.
- **Qué hiciste y qué pasó.** Si es un video público, incluye el enlace.
- **Detalles técnicos:**
  - Windows: en la tarjeta del error, pulsa **Ver detalles → Copiar detalles** y pégalo en el issue.
  - Android: **Ajustes › Acerca de K-Snag › Diagnóstico › Copiar detalles**.
- **Registro de la app de Windows, si te lo pedimos:**
  - está en `%LOCALAPPDATA%\K-Downloader\logs\app.log`;
  - para abrir la carpeta: Win + R → `%LOCALAPPDATA%\K-Downloader\logs` → Enter;
  - antes de adjuntarlo, revísalo: puede tener nombres de archivos y carpetas.

> **Nunca publiques cookies**: ni tu `cookies.txt`, ni partes de él, ni capturas donde se vean. Un `cookies.txt` equivale a tu sesión: quien lo tenga entra a tu cuenta. Tampoco publiques contraseñas ni datos personales. Si los pegaste por error, borra el comentario y cierra la sesión de esa cuenta en todos los dispositivos.

### Problemas del motor (yt-dlp): primero aquí

Muchos errores vienen de yt-dlp o de un cambio de YouTube. **Repórtalos primero aquí, no en el repositorio de yt-dlp:**

- yt-dlp no da soporte a programas de terceros como K-Snag.
- Muchas veces el arreglo ya existe y solo falta que la app lo reciba.
- El mantenedor de K-Snag lo reproduce con yt-dlp puro (`yt-dlp -vU <enlace>`). Solo si el error también está ahí, lo reporta en yt-dlp siguiendo sus reglas.

> **No abras issues en yt-dlp con texto generado por IA.** Sus reglas prohíben los issues escritos con herramientas de IA. Tampoco pegues ahí reportes copiados de K-Snag.

### Vulnerabilidades

Si encontraste un problema de seguridad, **no abras un issue público**. Por ejemplo: en el protocolo `kdownloader://`, el actualizador de cualquiera de las dos apps, `engine.json`, la hoja de «Compartir» de Android o la firma de los APK.

- usa **Security → Report a vulnerability** en GitHub;
- si esa opción no aparece, abre un issue sin detalles y pide un canal privado.

## Código de conducta

Trata a las demás personas con respeto y paciencia. Muchas personas que reportan errores no son técnicas: ayúdalas a dar los datos que faltan sin hacerlas sentir mal.
