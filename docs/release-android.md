<!-- SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Publicar K-Downloader para Android

Una versión de Android es siempre un **pre-release** y nunca **Latest**: «Latest» es el instalador de Windows, del que dependen su botón de descarga y su actualización automática.

| Paso | Quién | Dónde |
|---|---|---|
| Llave y GitHub (una sola vez) | Tú | Docker en tu PC y la configuración del repo |
| Versión, CHANGELOG y PR | Claude (tú haces el merge) | PR a `master` |
| Tag | Tú | `git push origin android-vX.Y.Z` |
| Aprobar la firma | **Solo tú** | Actions › el run › *Review deployments* (web o GitHub Mobile) |
| Revisar y publicar el borrador | Tú | Releases |
| Promover (punteros de la app y la web) | Claude abre el PR, tú haces el merge | `android/scripts/promote.py` |

## 1. Una vez: la llave de firma

La llave firma todas las versiones. **Si se pierde, nadie puede actualizar la app** y la gente tiene que desinstalarla. Si se filtra, otra persona podría publicar una app falsa con el mismo nombre.

1. Crea la llave con Docker (PowerShell). Te pide una contraseña: usa una larga y guárdala en tu gestor de contraseñas.

   ```powershell
   New-Item -ItemType Directory -Force "$env:USERPROFILE\kd-llaves" | Out-Null
   docker run --rm -it -v "$env:USERPROFILE\kd-llaves:/llaves" eclipse-temurin:21.0.12.1_1-jdk-noble `
     keytool -genkeypair -keystore /llaves/k-downloader-android.p12 -storetype PKCS12 -alias kdownloader `
     -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=K-Downloader"
   ```

2. Mira la huella del certificado (es pública):

   ```powershell
   docker run --rm -it -v "$env:USERPROFILE\kd-llaves:/llaves" eclipse-temurin:21.0.12.1_1-jdk-noble `
     keytool -list -v -keystore /llaves/k-downloader-android.p12 -alias kdownloader
   ```

   Copia la línea `SHA256: AB:CD:…` y pásasela a Claude: va a `android/signing/release-cert.sha256` en un PR.

3. Haz **tres respaldos** del archivo `k-downloader-android.p12`:
   - en el gestor de contraseñas, junto con la contraseña;
   - en una memoria USB cifrada con BitLocker To Go;
   - en otra memoria USB cifrada, guardada en otro lugar.

   Nunca lo pongas en el repo, un correo, un chat, una nube sin cifrar ni una captura de pantalla.

4. Sube los secretos (paso 2) y después **borra la copia de trabajo**: `Remove-Item "$env:USERPROFILE\kd-llaves" -Recurse`, y vacía la papelera.

5. En la Android Developer Console, registra el paquete `io.github.kasaco223.kdownloader` con esa huella (guía en PDF: «Guia-registro-Android-y-PayPal.pdf»).

## 2. Una vez: GitHub

1. **Environment** (Settings › Environments › *New environment*): `android-release`.
   - *Required reviewers*: `Kasaco223`. Deja **sin** marcar «Prevent self-review», porque el único revisor eres tú.
   - *Allow administrators to bypass*: **desactivado**.
   - *Deployment branches and tags*: *Selected* › agrega la regla de tag `android-v*`.
   - *Environment secrets*:
     - `ANDROID_KEYSTORE_B64`: el `.p12` en base64. En PowerShell, `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\kd-llaves\k-downloader-android.p12")) | Set-Clipboard` y pégalo.
     - `ANDROID_KEYSTORE_PASSWORD`: la contraseña.
     - `ANDROID_KEY_ALIAS`: `kdownloader`.
2. **Ruleset de tags** (Settings › Rules › Rulesets › *New tag ruleset*): para `refs/tags/v*`, `refs/tags/android-v*` y `refs/tags/android-sources-*`, restringe actualizar y borrar, sin excepciones.
3. **Notificaciones**: activa las de *Deployment review* y *Releases* (Settings de tu cuenta › Notifications).
4. **La aprobación de la firma es solo tuya.** Nunca se aprueba por API, por CLI ni desde una sesión de Claude.

## 3. Cada versión

1. **Preparar** (PR): `VERSION_NAME` en `android/version.properties` (`X.Y.Z` o `X.Y.Z-rc.N`) y la sección `## X.Y.Z - AAAA-MM-DD` en `android/CHANGELOG.md`, en español e inglés. La primera vez, haz antes un **ensayo**: Actions › «Android Release» › *Run workflow* desde la rama. Firma con una llave temporal y no crea nada.
2. **Merge** con «Android CI» y «Workflows y scripts» en verde.
3. **Tag**:

   ```powershell
   git checkout master; git pull
   git tag -a android-vX.Y.Z -m "K-Downloader para Android X.Y.Z"
   git push origin android-vX.Y.Z
   ```

4. **Aprobar**: en el run de «Android Release», cuando `smoke` esté en verde, *Review deployments* › `android-release` › *Approve*. Antes comprueba que el commit del run sea el de `master` (`git rev-parse origin/master`).
5. **Revisar el borrador** (Releases):
   - «Pre-release» marcado y «Latest» sin marcar;
   - 5 archivos: los 3 APK, `android-release.json` y `SHA256SUMS-android.txt`;
   - `Get-FileHash K-Downloader-Android-arm64-v8a.apk` coincide con `SHA256SUMS-android.txt`;
   - la huella del certificado en las notas es la tuya;
   - instálalo encima de la versión anterior: tus descargas siguen ahí, compartir desde YouTube funciona y se bajan un MP4 y un MP3.
6. **Publicar**: `gh release edit android-vX.Y.Z --draft=false --prerelease --latest=false`.
7. **Comprobar Windows**: `gh api repos/Kasaco223/K-YoutubeVideoDownloaderHD/releases/latest --jq .tag_name` sigue diciendo `vX.Y.Z` (Windows).
8. **Promover**: Claude corre `python android/scripts/promote.py --tag android-vX.Y.Z` (con `--channel rc` para una rc) y abre el PR. Tú lo fusionas cuando el check de punteros esté en verde. Así la app instalada se entera de la versión nueva.

## 4. Antes de la 1.0 (obligatorio)

1. `android-v1.0.0-rc.1`: instálala desde GitHub en tu teléfono.
2. `android-v1.0.0-rc.2`: promuévela con `--channel rc`.
3. En el teléfono, Ajustes › «Buscar actualizaciones» tiene que llevarte hasta la rc.2: el permiso de fuentes desconocidas la primera vez, la confirmación de Android y tus datos intactos.
4. Recién entonces, `android-v1.0.0` y la página `/android` de la web.

## 5. Si algo sale mal

- **Todavía no se promovió**: no la promuevas; arregla y sube `X.Y.Z+1`.
- **Ya se promovió**: revierte el PR del puntero y publica `X.Y.Z+1`.
- **Nunca** borres ni muevas tags, ni reuses un `versionCode`.
- **Llave perdida**: sin respaldo no hay solución; la app nueva tendría que ser otro paquete.
- **Llave filtrada**: rota con `apksigner rotate` (ver el plan de entrega, §2.5), agrega la huella nueva como primera línea de `release-cert.sha256` y cambia los secretos.
