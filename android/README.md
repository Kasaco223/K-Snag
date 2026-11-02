<!-- SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# K-Snag para Android: guía para desarrollar

Esta carpeta tiene la app de Android. Hoy es una **versión de prueba que ya descarga**: pegas o compartes un
enlace, eliges el formato (Video MP4, Máxima MKV, MP3 o M4A) y el archivo queda en Descargas/K-Snag. Lo
que falta (analizar el enlace antes, listas, reintentos automáticos, seguir tras un cierre) llega en los
próximos PR.

## Licencia

- Todo el código propio de `android/` es **GPL-3.0-or-later** ([LICENSE](LICENSE), el texto oficial sin
  cambios). El APK completo se distribuye bajo la GPL v3. Las dependencias conservan su licencia: las de
  la app son Apache-2.0 (AndroidX, Kotlin, corrutinas) y las del motor, las de cada componente.
- El resto del repositorio (la app de Windows, la web) sigue con la licencia MIT de la raíz. No copies
  código GPL fuera de `android/`.
- Los componentes de terceros (el motor de youtubedl-android trae Python, FFmpeg y unas 70 bibliotecas)
  están en [`third_party/components.json`](third_party/components.json), con sus textos de licencia en
  `third_party/licenses/`. De ese inventario sale [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); ver
  [Avisos de terceros y espejo de fuentes](#avisos-de-terceros-y-espejo-de-fuentes).
- Cada archivo propio de `android/` lleva este encabezado (en el formato de comentario de su lenguaje):

  ```text
  SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
  SPDX-License-Identifier: GPL-3.0-or-later
  ```

  No lo llevan `LICENSE`, los binarios, los avisos de terceros (`.txt`) ni el wrapper de Gradle (`gradlew`,
  `gradlew.bat` y `gradle/wrapper/`, que son archivos de Gradle con licencia Apache-2.0). CI comprueba las
  dos líneas.

## Estructura

```text
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  version.properties          VERSION_NAME: la única fuente de la versión
  engine-pins.properties      yt-dlp y cacert.pem que van en el APK (URL y SHA-256)
  gradle/libs.versions.toml   catálogo de versiones
  gradle/verification-metadata.xml  SHA-256 de cada dependencia (Gradle rechaza cualquier otra)
  gradle/wrapper/, gradlew    Gradle 9.8.2 (distribución fijada por SHA-256)
  buildSrc/                   tareas propias del build (KdTasks.kt): motor, certificados, licencias
  core/                       Kotlin puro, sin android.*: lógica que se prueba en la JVM (Urls, core/engine)
  app/                        la app (Compose, Material 3)
    debug.keystore            llave de debug, pública a propósito
    src/main/                 código, recursos y manifiesto
    src/test/                 pruebas en la JVM
    src/androidTest/          pruebas instrumentadas (emulador o celular)
  scripts/                    scripts de CI (repo, emulador, páginas de 16 KB, avisos, espejo de fuentes)
  third_party/                inventario de terceros (components.json) y textos de sus licencias
  THIRD_PARTY_NOTICES.md      avisos de terceros, generados desde third_party/components.json
  tools/docker/               imagen para compilar en Docker
```

Paquetes de la app (`io.github.kasaco223.kdownloader`): `share/` (la hoja que aparece al compartir),
`ui/` (pantallas y tema), `platform/` (diagnóstico, acciones del sistema, canales de notificación),
`contracts/` (contratos con el resto de la app), `engine/` (el motor), `download/` (la cola, el servicio en
primer plano, la publicación en Descargas y las notificaciones), `ui/jobs` y `ui/history`.

## Las descargas

- **`:core` (`core/download/`)**, sin Android y probado en la JVM con los datos de Windows:
  - `Presets` y `Args`: los argumentos de yt-dlp, iguales byte a byte a `engine/presets.py`;
  - `Kdl` (con `JsonStrict`): las líneas `KDL|…` de progreso;
  - `ProgressModel`: el porcentaje, igual que `downloads._on_event`;
  - `ExitMapper` y `ErrorCatalog`: el resultado y el error, con el mismo `errors_catalog.json`.
  Las pruebas leen `tests/fixtures/ytdlp` (transcripciones reales) y
  `core/src/test/resources/desktop_error_vectors.json` (los ejemplos de `tests/test_errors.py`).
- **`DownloadQueue`**: una descarga a la vez, en `DownloadService` (primer plano, `dataSync`). Guarda
  `kd/jobs.json` y `kd/history.json`; cada trabajo baja en `kd/jobs/<id>/` y `Publisher` lo pasa a
  Descargas/K-Snag (MediaStore desde Android 10; copia directa y FileProvider en Android 8 y 9, con el
  permiso de almacenamiento).
- **Permisos**: el de notificaciones se pide una vez, después de la primera descarga (Android 13+); sin él la
  descarga sigue, solo sin avisos. En Android 8 y 9, el de almacenamiento se pide antes de encolar.

## El motor de descargas

- **De dónde sale.** De los AAR de youtubedl-android (`gradle/libs.versions.toml` › `youtubedl`) solo se toman
  sus paquetes nativos: `jni/<abi>/` con `libpython.so`, `libffmpeg.so`, `libffprobe.so`, `libqjs.so`
  (lanzadores) y `libpython.zip.so` y `libffmpeg.zip.so` (Python, FFmpeg y sus bibliotecas). La tarea
  `kdPayload` los copia a los jniLibs; el código Java, los recursos y las dependencias del AAR no entran al
  APK. Gradle verifica cada AAR contra `gradle/verification-metadata.xml`, y `scripts/check_pins.py` cruza
  ese SHA-256 con `third_party/components.json`.
- **Qué agrega el build.** `res/raw/ytdlp` (yt-dlp, para el primer inicio sin red) y `assets/kd/cacert.pem`
  (certificados raíz para Python), los dos fijados en `engine-pins.properties`; y los avisos de terceros en
  `assets/licenses/third_party/`.
- **Qué hace la app** (`engine/KdEngine.kt`), en un servicio en primer plano la primera vez:
  1. revisa el espacio;
  2. descomprime los paquetes en `noBackupFilesDir/kd/payload/`, sin los enlaces que taparían bibliotecas
     del sistema (youtubedl-android #318);
  3. instala los certificados y la copia verificada de yt-dlp;
  4. se prueba: Python con sesión propia (`setsid`, para poder cancelar a FFmpeg y QuickJS junto con Python)
     y SSL, `yt-dlp --version` y `ffmpeg -version`.
  Después de eso, abrir la app solo revisa marcas y hashes. «Acerca de» › «Reparar el motor» lo rehace.
- **Páginas de 16 KB.** Tres bibliotecas de WebP de FFmpeg vienen alineadas a 4 KB
  (`scripts/elf_alignment_allowlist.txt`, `pending=M5`). Hasta que llegue nuestra libwebp (PR-9), en un
  celular con páginas de 16 KB el motor queda en `engine_incompatible` y la app lo explica.
- **Actualizar los pins.** Cambia la URL, la versión y el SHA-256 en `engine-pins.properties` (yt-dlp:
  la línea `yt-dlp` de `SHA2-256SUMS` de su Release; cacert: `https://curl.se/ca/cacert.pem.sha256`) y la
  misma entrada en `third_party/components.json`, y corre `python3 scripts/notices.py`.

## Compilar con Docker

En esta PC (Windows con Smart App Control) **Gradle no corre directamente en Windows**: AGP descarga un
`aapt2.exe` sin firmar y Gradle usa DLL nativas sin firmar, que Windows bloquea. Por eso se compila en un
contenedor Linux de Docker Desktop.

### La licencia del SDK de Android

La imagen instala el SDK de Android (cmdline-tools, la plataforma 37 y build-tools 36.0.0), que Google
distribuye con el **Android Software Development Kit License Agreement**. Instalarlo implica aceptar esa
licencia, y esa decisión es tuya: la imagen **no se construye** si no la aceptas de forma explícita.

1. Lee la licencia: <https://developer.android.com/studio/terms>.
2. Si estás de acuerdo, indícalo en la terminal donde construyes la imagen:

   ```powershell
   $env:KDL_ACCEPT_ANDROID_SDK_LICENSE = "yes"
   ```

Sin esa variable, `docker compose build` se detiene antes de descargar nada del SDK.

**En GitHub Actions** la CI de Android también usa el SDK de Android, bajo esa misma licencia de Google, en
los runners de GitHub:

- El job **build** usa el SDK que ya trae la imagen del runner (con las licencias aceptadas en la imagen).
  Su paso de `sdkmanager` no acepta ninguna: si un paquete pidiera una licencia nueva, el paso falla.
- Los jobs **emulator** usan la acción `reactivecircus/android-emulator-runner`, que corre
  `yes | sdkmanager --licenses` (acepta **todas** las licencias que conoce `sdkmanager`, no solo la del SDK)
  y descarga el emulador, `platforms;android-36`, build-tools y las imágenes del sistema. Si eso no te
  parece bien, hay que reemplazar la acción por un script propio (`avdmanager` y `emulator`); igual seguiría
  usando el SDK.

Abrir un PR que toque `android/` dispara esos jobs.

### Comandos

Desde la raíz del repositorio, en PowerShell:

```powershell
# Primera vez (construye la imagen: 5 a 10 minutos)
docker compose -f android/tools/docker/compose.yaml build

# APK debug para tu celular (un solo APK, arm64)
docker compose -f android/tools/docker/compose.yaml run --rm kdl :app:assembleGithubDebug -Pkdl.devAbi=arm64-v8a

# Pruebas en la JVM y lint
docker compose -f android/tools/docker/compose.yaml run --rm kdl :core:test :app:testGithubDebugUnitTest :app:lintGithubDebug
```

- Los APK quedan en `android/build/docker-out/apk/` y los reportes en `android/build/docker-out/reports/`
  (git ignora esa carpeta).
- El repo se monta **solo lectura**; `kdl-build.sh` copia `android/` a un volumen de Linux (compilar
  sobre `C:\` es muy lento) y conserva los `build/` entre corridas.
- **Contenedor tibio** (compilaciones de 1 a 3 minutos):

  ```powershell
  docker compose -f android/tools/docker/compose.yaml run -d --name kdl-dev --entrypoint sleep kdl infinity
  docker exec kdl-dev kdl-build :app:assembleGithubDebug -Pkdl.devAbi=arm64-v8a
  ```

- Después de cambiar el `Dockerfile`, borra el volumen del SDK para que se vuelva a llenar:
  `docker volume rm kdl-android_kdl-sdk`.
- Para instalar en el celular: `adb install -r <apk>` (adb está firmado por Google) o copia el APK al
  celular y ábrelo.

## Variantes, versiones y firma

| | |
| --- | --- |
| Sabor | `github` (Releases de GitHub). `foss` (F-Droid) queda reservado. |
| Tipos de build | `debug` (`.debug`), `qa` (como release, con R8, firmado con la llave de debug; `.qa`) y `release` (sin firmar: se firma aparte). |
| SDK | minSdk 26 (Android 8), targetSdk 36, compileSdk 37 (`platforms;android-37.0`). |
| ABI | Un APK por ABI: `arm64-v8a`, `armeabi-v7a` y `x86_64`. Sin APK universal. `-Pkdl.devAbi=<abi>` arma un solo APK. |
| Versión | `version.properties` → `VERSION_NAME`: `X.Y.Z` o `X.Y.Z-rc.N` (X, Y y Z de 0 a 99; N de 1 a 98). |
| versionCode | `(X·1 000 000 + Y·10 000 + Z·100 + (rc ? N : 99))·10 + ABI`, con armeabi-v7a = 1, arm64-v8a = 3 y x86_64 = 4 (0 = sin dividir). 1.0.0-rc.1 → 10000011/3/4; 1.0.0 → 10000991/3/4. |

- **Llave de debug**: `app/debug.keystore` (PKCS12, alias `androiddebugkey`, contraseña `android`; SHA-256
  del certificado `58:7B:24:36:BD:EB:76:82:A9:8B:10:B0:C3:3D:34:69:91:5D:36:58:36:67:0F:A6:E9:99:37:C2:71:A4:8D:C7`).
  Es pública para que los APK de prueba se instalen uno sobre otro. Los paquetes `.debug` y `.qa` nunca
  pueden actualizar la app publicada. CI fija el archivo por SHA-256 y falla si aparece cualquier otra llave
  en git.
- **Como la llave es pública, cualquiera puede firmar un APK que actualice una instalación `.debug`** (también
  los PR de forks, cuyos artefactos aparecen en la misma lista de Actions). Instala solo APK de corridas de
  `master` o de tus propias ramas.
- En el celular, el build debug se llama **«K-Snag (prueba)»** («K-Snag (test)» en inglés) y el
  qa **«K-Snag QA»**, en el launcher y en «Compartir», para no confundirlos con la app publicada
  (`src/debug/res`, `src/qa/res`).
- **«Acerca de» → «Código fuente»** enlaza al árbol del commit con el que se compiló (`KDL_SOURCE_COMMIT`);
  una versión publicada enlaza a su tag `android-v<versión>`.

## Idiomas

- `values/` es el **inglés** (recursos por defecto, `res/resources.properties`: `unqualifiedResLocale=en-US`)
  y `values-es/` el **español**, que es el texto fuente: se escribe primero en español y después en inglés.
- Español neutro latinoamericano con tuteo: "video" (nunca "vídeo"), "toca", "celular".
- El build genera el `localeConfig`, así que en Android 13+ el idioma de la app se puede elegir en la
  configuración del celular.

## Pruebas

| Dónde | Qué | Comando |
| --- | --- | --- |
| `core/src/test` | `Urls` contra los resultados del código de Windows (`actions.js`, `media.validate_url`, `ytdlp.is_youtube`, con todos los casos de `tests/test_ytdlp.py`) | `:core:test` |
| `app/src/test` | Lógica sin Android: versionCode por ABI, diagnóstico, enlace al código, texto de la licencia, enlaces compartidos | `:app:testGithubDebugUnitTest` |
| `app/src/androidTest` | En el emulador: abrir la app, «Acerca de» y «Diagnóstico», la licencia, el deep link y la hoja de «Compartir» | `:app:connectedGithubDebugAndroidTest` |

- El runner es `io.github.kasaco223.kdownloader.testing.KdlTestRunner`.
- Las pruebas que necesitan internet de verdad llevan `@NetworkTest` (mismo paquete) y CI no las corre:
  desde un centro de datos YouTube pide comprobar que no eres un robot.

## Integración continua

`.github/workflows/android-ci.yml` corre en los PR y en `master` cuando cambia `android/` (o el catálogo de
errores, `tests/fixtures/` o `engine.json`, que la app usará), y los lunes.

1. **build** (ubuntu-24.04):
   - En un PR compila el commit de la rama (no el merge temporal con `master`), el mismo que enlaza
     «Acerca de» › «Código fuente».
   - `scripts/ci_repo_checks.sh`: scripts ejecutables y con LF, encabezados SPDX, ninguna llave de firma
     salvo la de debug (fijada por SHA-256), `LICENSE` sin cambios y wrapper de Gradle fijado por SHA-256.
     Además, el inventario de terceros válido y los avisos al día (`notices.py --check`) y, cuando el
     catálogo traiga youtubedl-android, sus pins (`check_pins.py`) y el espejo de fuentes publicado
     (`sources_mirror.py --verify`).
   - Instala `platforms;android-37.0` y `build-tools;36.0.0`; `setup-gradle` valida el wrapper.
   - `:core:test`, `:app:testGithubDebugUnitTest`, `:app:lintGithubDebug` (lint también revisa `:core`
     con el minSdk de la app) y los APK debug, qa y de pruebas.
   - `scripts/check_elf_alignment.py`: toda biblioteca nativa de 64 bits debe cargar con páginas de 16 KB,
     también las que van dentro de los paquetes del motor, salvo las de `elf_alignment_allowlist.txt` (las
     tres de WebP, `pending=M5`: aviso en CI). No hay código nativo propio, así que no hace falta el NDK
     (`keepDebugSymbols` deja los `.so` sin tocar).
   - El build falla si un APK trae una biblioteca nativa que no sea la de Compose
     (`libandroidx.graphics.path.so`) o las del motor.
   - Artefactos: reportes (14 días) y `android-apk-<sha>` (7 días) con los tres APK debug (el `arm64-v8a`
     es el del celular), el qa x86_64 y el de pruebas. El APK de pruebas es solo para el emulador e incluye
     JUnit 4.13.2 (EPL-1.0) y Hamcrest 1.3 (BSD-3-Clause); su aviso
     (`app/src/androidTest/THIRD-PARTY-NOTICES.txt`) va en el mismo artefacto.
2. **emulator**: API 26 (`default`), API 36 (`google_apis`) y API 36 con páginas de 16 KB
   (`google_apis_ps16k`), los tres obligatorios. No recompila: instala los APK del build y corre
   `scripts/ci_emulator.sh`, que ejecuta las pruebas instrumentadas, abre el build qa (R8) para ver que no
   se cierre, y corre la autoprueba del motor (`kdl.selftest`: una línea `KDL-SELFTEST` con `PASS {…}`).
   En el emulador de 16 KB, mientras `elf_alignment_allowlist.txt` tenga líneas `pending=M5`, lo esperado
   es `FAIL engine_incompatible` (y cualquier otro resultado falla); con M5 se exige `PASS` también ahí.

- Subir una rama no dispara la CI de Android: corre al abrir el PR (o en `master`).
- Las acciones de terceros (`gradle/actions/setup-gradle` y `reactivecircus/android-emulator-runner`) van
  fijadas por SHA de commit, con la versión en un comentario.
- La CI de Windows (`ci.yml`) ignora los cambios que solo tocan Android.

## Avisos de terceros y espejo de fuentes

El APK con el motor redistribuye binarios GPL, LGPL y MPL (FFmpeg, x264, GLib, GnuTLS, Readline, SRT y
otros). Por eso su código fuente tiene que estar publicado **antes** de que salga cualquier APK que los
traiga, también un artefacto de CI: en un repo público, cualquiera con cuenta de GitHub los puede bajar.

- **Inventario** (`third_party/components.json`): cada componente con su versión, licencia SPDX, fuente
  (URL y SHA-256), receta de Termux y las rutas que cubre dentro de los paquetes del motor (`files`).
  `"mirror": true` manda su fuente al espejo: es obligatorio para todo lo copyleft, y hoy va todo salvo
  las bibliotecas del NDK (la GPLv3 cuenta como fuente correspondiente las bibliotecas que FFmpeg y Python
  requieren, y así no dependemos de que cada sitio de descarga siga arriba).
- **Avisos**: `python3 scripts/notices.py` regenera `THIRD_PARTY_NOTICES.md`; CI falla si no está al día
  o si el inventario tiene un hueco (algo copyleft sin espejo, una licencia citada que falta o sobra).
  `python3 scripts/notices.py --payload library-<v>.aar ffmpeg-<v>.aar` comprueba que cada archivo de los
  paquetes del motor tenga dueño en el inventario (el workflow del espejo lo corre con los AAR de Maven
  Central).
- **Espejo**: Actions › **Android · fuentes de terceros** › *Run workflow* (desde `master`) arma el
  borrador `mirror_release.tag` (hoy `android-sources-ytdl-0.18.1-r1`): cada fuente verificada contra su
  SHA-256, el árbol completo de termux-packages en el commit fijado, `FUENTES.md` y `SHA256SUMS.txt`. Lo
  revisas y lo publicas **como pre-release**; publicado, no se cambia más. Para corregirlo, sube la
  revisión (`-r2`) en `components.json` y vuelve a correr el workflow.
- Mientras Gradle no use youtubedl-android (se busca su coordenada Maven en el catálogo y en cada
  `*.gradle.kts`), `sources_mirror.py --verify` y `check_pins.py` no exigen nada. Desde el PR que lo
  agregue, CI falla si el espejo no está publicado y completo.
- **Procedencia:** youtubedl-android no publicó con qué commit de Termux compiló sus binarios (#358, #363).
  El inventario usa `termux-packages@8ddd9a7f`, cuyas recetas coinciden en versión y revisión con los
  binarios (FFmpeg 7.1.1-r6, Python 3.12.11-r1). Las fuentes se verificaron contra esas recetas y, cuando
  upstream firma, contra su firma GPG.

## Pendiente

- Analizar el enlace antes de bajar (calidades y tamaños), listas, reintentos automáticos, seguir tras un
  cierre de la app, el actualizador de yt-dlp, la libwebp propia para páginas de 16 KB, el pipeline de
  releases y el actualizador de la app llegan en sus propios PR.

## Verificación de dependencias

`gradle/verification-metadata.xml` guarda el SHA-256 de cada dependencia y plugin: Gradle rechaza cualquier
otro archivo. Al agregar o cambiar una dependencia, regenéralo en Docker con las mismas tareas que corre la CI
y copia el resultado al repo:

```powershell
docker compose -f android/tools/docker/compose.yaml run --rm kdl --write-verification-metadata sha256 `
  help :core:test :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug `
  :app:assembleGithubQa :app:assembleGithubRelease :app:assembleGithubDebugAndroidTest
Copy-Item android\build\docker-out\verification-metadata.xml android\gradle\
```

Revisa el diff antes del commit: cada artefacto nuevo tiene que ser uno que agregaste a propósito.
