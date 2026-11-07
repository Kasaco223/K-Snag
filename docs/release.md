# Cómo publicar una versión

Lista de pasos para sacar una versión de K-Snag. Se hace de arriba abajo, sin saltarse pasos.

## Resumen

| Paso | Quién | Dónde |
| --- | --- | --- |
| 1. Subir `__version__` y probar en local | Tú | `src/kdownloader/__init__.py` |
| 2. Merge a `master` con CI en verde | Tú | Pull request |
| 3. Crear y subir el tag `vX.Y.Z` | Tú | Git |
| 4. Build, prueba de instalación y borrador del Release | GitHub Actions | `.github/workflows/release.yml` |
| 5. Revisar el borrador | Tú | GitHub → Releases |
| 6. Publicar | Tú | GitHub → Releases |
| 7. Comprobar la descarga y la autoactualización | Tú | Una PC con Windows 11 |
| 8. Desplegar la web, solo si cambió `web/` | Tú | Vercel, con **tu cuenta personal** |

## Antes de empezar

- `master` está en verde en **Actions → CI**.
- Elige el número de versión:
  - `X.Y.Z+1` para arreglos;
  - `X.Y+1.0` para funciones nuevas;
  - `X.Y.Z-rc.N` para una versión de prueba, que se marca como *pre-release*.
- Si cambiaste el runtime (`packaging/runtime.json` o `packaging/requirements-runtime.txt`), las instalaciones existentes no se pueden autoactualizar solo con el código. Al aceptar la actualización, la app abre la página de descarga. Avísalo en las notas.

## 1. Subir la versión

La versión vive **solo** en `src/kdownloader/__init__.py`. `pyproject.toml` la lee de ahí.

```python
__version__ = "2.0.1"
```

Prueba el build y la instalación en local (PowerShell, desde la raíz del repo), sin tocar tu instalación real:

```powershell
powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -Version 2.0.1
.\dist\Instalar-K-Snag.cmd --local "$PWD\dist\kd-bundle-2.0.1.zip" --root "$env:TEMP\kd-prueba" --no-registry
& "$env:TEMP\kd-prueba\runtime\python.exe" "$env:TEMP\kd-prueba\launch.pyw" --selftest
```

Abre la app de prueba con una carpeta de datos aparte, para no mezclarla con tus ajustes ni tu historial:

```powershell
$env:KD_DATA_DIR = "$env:TEMP\kd-prueba-datos"
& "$env:TEMP\kd-prueba\runtime\pythonw.exe" "$env:TEMP\kd-prueba\launch.pyw"
```

Prueba al menos:

- un video de YouTube en Recomendado;
- un MP3;
- cancelar una descarga.

## 2. Llevar el cambio a `master`

1. Commit: `Versión 2.0.1` (y lo que haga falta).
2. Abre un pull request hacia `master`.
3. Espera a que **CI** quede en verde: pruebas en Python 3.11 y 3.13, sintaxis JS y build.
4. Haz el merge.

## 3. Crear el tag

Desde `master` actualizado:

```powershell
git switch master
git pull
git tag -a v2.0.1 -m "K-Snag 2.0.1"
git push origin v2.0.1
```

El tag debe ser exactamente `v` + `__version__`. Si no coincide, el workflow se detiene sin publicar nada.

## 4. Lo que hace GitHub Actions

El workflow **Release** arranca solo con el tag y hace esto:

1. Comprueba que el tag coincide con `__version__`.
2. Ejecuta `packaging/build.ps1 -Version 2.0.1 -BaseUrl https://github.com/Kasaco223/K-Snag/releases/download/v2.0.1`, con ruff y pytest incluidos.
3. Si SignPath está configurado (ver [Firma con SignPath](#firma-con-signpath)), manda `Instalar-K-Snag.exe` a firmar y **espera tu aprobación en SignPath**. Después usa el `.exe` firmado y actualiza su línea en `SHA256SUMS.txt`.
4. Comprueba que `latest.json` y `SHA256SUMS.txt` coinciden con los archivos de `dist/`, y que `Instalar-K-Snag.exe` y `Instalar-K-Snag.cmd` traen el mismo bundle (`packaging/check_installer_exe.py`).
5. Instala en limpio en el runner:
   - `Instalar-K-Snag.cmd --local … --root $RUNNER_TEMP\kd --no-registry --quiet`;
   - luego `runtime\python.exe launch.pyw --selftest`;
   - y las pruebas de `Instalar-K-Snag.exe` (`packaging/smoke_installer_exe.ps1`): instalación nueva, reinstalación encima, hash equivocado y que la ventana abra.
6. Crea el Release **como borrador**, con notas generadas automáticamente y estos 7 archivos:
   - `Instalar-K-Snag.cmd` (y su copia `Instalar-K-Downloader.cmd`, el nombre anterior)
   - `Instalar-K-Snag.exe`: el mismo instalador como `.exe`. Sin firmar, Smart App Control lo bloquea igual que al `.cmd`; la web sigue ofreciendo el `.cmd` hasta que el `.exe` salga firmado.
   - `kd-bundle-2.0.1.zip`
   - `kd-app-2.0.1.zip`
   - `latest.json`
   - `SHA256SUMS.txt`

Si algo falla, corrige en una rama nueva, haz merge y crea un tag con la versión siguiente. **No muevas un tag ya publicado.**

Mientras siga en borrador, sí puedes regenerar los archivos:

- Actions → Release → **Run workflow**;
- elige el tag en el selector de rama;
- desmarca *dry_run*.

### Ensayo sin publicar

Para probar el workflow sin crear nada:

- Actions → Release → **Run workflow**;
- elige una rama y deja *dry_run* marcado.

Desde una rama siempre es un ensayo. Los archivos quedan como artefacto del workflow durante 30 días.

## 5. Revisar el borrador

En **GitHub → Releases**, abre el borrador `K-Snag 2.0.1` y revisa:

- [ ] Están los **7 archivos** de la lista anterior.
- [ ] Si SignPath está configurado: en las propiedades de `Instalar-K-Snag.exe` (*Firmas digitales*) aparece la firma de SignPath Foundation.
- [ ] El título es `K-Snag 2.0.1`, el tag `v2.0.1` y apunta al commit correcto de `master`.
- [ ] *Set as a pre-release* está marcado **solo** si la versión lleva `-rc`.
- [ ] Descarga `latest.json` y ábrelo:
  - `version` es `2.0.1`;
  - las tres URL apuntan a `/releases/download/v2.0.1/`;
  - `notes_url` apunta a esta página.
- [ ] Arriba de las notas automáticas, escribe un resumen corto en español y en inglés:
  - qué cambió para la persona usuaria;
  - si hace falta reinstalar porque cambió el runtime.

## 6. Publicar

Pulsa **Publish release**.

- El workflow **Mantener Latest en la app de Windows** (`.github/workflows/release-latest.yml`, con la lógica en `.github/scripts/latest-guard.sh`) deja como **Latest** la versión de Windows `vX.Y.Z` más alta. GitHub no siempre lo hace solo al publicar un borrador.
  - Corre al publicar, editar o borrar un Release, y además **cada hora**.
  - Los Releases de Android (`android-*`) son siempre *pre-release* y nunca pueden ser Latest. Si alguno se publica como versión normal, el guardián lo pasa a *pre-release*.
  - Comprueba en **Releases** que la versión de Windows diga **Latest**. Si no: *Edit* → **Set as the latest release** → *Update release*, o Actions → **Mantener Latest en la app de Windows** → **Run workflow**.
- Desde ese momento, `https://github.com/Kasaco223/K-Snag/releases/latest/download/latest.json` sirve la versión nueva.
- Las apps instaladas la detectan en menos de 24 horas.
- Las *pre-releases* **no** cuentan como *latest*: ni la web ni la autoactualización las ofrecen. Sirven para probar con el enlace directo del Release.

## 7. Comprobar en una PC real

En una PC con Windows 11, idealmente con Smart App Control activo:

- [ ] <https://ksnagapp.vercel.app/descargar> baja `Instalar-K-Snag.cmd` de la versión nueva.
- [ ] Con Smart App Control activo, el `.cmd` recién descargado **no** abre (ni desde el navegador ni con doble clic). Tras **Propiedades → Desbloquear**, el doble clic instala y abre la app.
- [ ] Con **Ejecutar como administrador** también instala, y la app se abre **sin** permisos elevados (en el Administrador de tareas, columna *Elevado* = No).
- [ ] El instalador termina y abre la app.
  - Anota los avisos del navegador y de SmartScreen: deben coincidir con lo que dice el README.
- [ ] El primer uso prepara los componentes y descarga un video de prueba.
- [ ] Con la versión anterior instalada:
  - aparece "Hay una nueva versión", o ve a Ajustes → Acerca de → Buscar actualizaciones;
  - la actualización se aplica y la app conserva tus ajustes y tu historial.

## 8. Web en Vercel (solo si cambió `web/`)

`/descargar` redirige al instalador del último Release publicado. **Una versión nueva no necesita redesplegar la web**, salvo que hayas cambiado algo en `web/`.

> **Cuenta de Vercel:** la web vive en una cuenta **separada y dedicada a este proyecto** (proyecto `kdownloader`). No la publiques desde una cuenta que aloje otros sitios o dominios importantes: una denuncia contra un descargador podría suspender la cuenta completa.

El proyecto `kdownloader` de Vercel (equipo **K**, `k23`) está conectado a este repo:

- **Root Directory:** `web`.
- **Rama de producción:** `master`.
- **Ignored Build Step:** `git diff --quiet HEAD^ HEAD -- . 2>/dev/null || exit 1`.
  - Vercel se salta el build si no cambió `web/`.
  - Si no puede comparar (por ejemplo, porque `.vercelignore` dejó fuera la carpeta `.git`), construye igual: Vercel trata como error cualquier código de salida distinto de 0 o 1.

Por eso **cada merge a `master` que toque `web/` se despliega solo**. Las demás ramas generan previews.

Despliegue manual con la CLI, siempre **desde la raíz del repo**: `.vercelignore` hace que solo se suba `web/`.

```powershell
vercel whoami                       # Debe mostrar la cuenta del proyecto. Si es otra: vercel logout / vercel login
vercel deploy --scope k23           # preview: revisa la URL que imprime
vercel deploy --prod --scope k23    # producción: https://ksnagapp.vercel.app
```

Después del deploy, comprueba:

- [ ] La página de inicio, `/ayuda` y `/uso-responsable` cargan.
  - Revísalas en el celular (375 px) y en escritorio.
- [ ] `/descargar` responde con una redirección al `Instalar-K-Snag.cmd` del último Release.
- [ ] `/ayuda` muestra los códigos de error actuales.
  - Si cambiaste `errors_catalog.json`, primero ejecuta `python scripts/export_web_data.py` y haz commit de `web/data/errors.json`.

## Firma con SignPath

`Instalar-K-Snag.exe` (código en `packaging/installer/exe`, C# con .NET Framework 4.8) es el mismo instalador que el `.cmd`, pero se puede firmar. La firma la da gratis [SignPath Foundation](https://signpath.org) a proyectos de código abierto. El paso de firma de `release.yml` está **apagado** hasta que SignPath apruebe el proyecto: solo se activa si existe la variable `SIGNPATH_ORGANIZATION_ID`.

**Mientras SignPath no apruebe el proyecto, no pongas en la web ni en el README que K-Snag está firmado.**

Cuando lo aprueben, una sola vez:

1. En SignPath, con la verificación en dos pasos activada:
   - crea (o confirma) el proyecto, conectado a este repositorio de GitHub, y anota su *slug*;
   - crea una *artifact configuration* para un único archivo PE, `Instalar-K-Snag.exe`, firmado con Authenticode. Si el artefacto llega como `.zip`, la configuración es un `<zip-file>` con un `<pe-file path="Instalar-K-Snag.exe">` dentro;
   - usa la *signing policy* `release-signing` (o la que te asignen), con **aprobación manual**: tú apruebas cada firma;
   - crea un usuario de CI (*CI user*) con permiso para enviar solicitudes a esa política y copia su *API token*.
2. En GitHub → **Settings → Secrets and variables → Actions**:
   - secreto `SIGNPATH_API_TOKEN` = el *API token* del usuario de CI (lo pegas tú, nunca en un chat);
   - variables:
     - `SIGNPATH_ORGANIZATION_ID`: el ID de la organización (Settings en SignPath);
     - `SIGNPATH_PROJECT_SLUG`: el *slug* del proyecto;
     - `SIGNPATH_SIGNING_POLICY_SLUG`: por ejemplo `release-signing`;
     - `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG`: el *slug* de la configuración del paso 1 (vacío = la predeterminada).
3. En SignPath, conecta el *Trusted Build System* de GitHub Actions al proyecto, como indica su guía.
4. Prueba con una versión de prueba (`vX.Y.Z-rc.1`): el run se detiene en **Firmar Instalar-K-Snag.exe con SignPath** hasta que apruebas la solicitud en SignPath. Luego revisa la firma en el borrador (*Propiedades → Firmas digitales*).
5. Con la firma comprobada, en otro PR:
   - la web (`/descargar` y la guía de instalación) pasa a ofrecer `Instalar-K-Snag.exe`;
   - el README y la página de política de firma que pide SignPath dicen que los instaladores los firma SignPath Foundation.

En cada versión, la firma la apruebas tú en SignPath, igual que la de Android en GitHub.

## Si una versión salió mal

- **La app se rompe al abrirse:**
  1. Edita el Release y conviértelo de nuevo en borrador (*Edit → Save as draft*). La versión anterior vuelve a ser *latest* y la autoactualización deja de ofrecer la mala.
  2. Publica un arreglo como `X.Y.Z+1`.
- **YouTube cambió algo y el motor falla:** no hace falta publicar la app. Usa las pistas de [`engine.json`](engine-hints.md).
- **Nunca borres ni muevas un tag publicado:**
  - las instalaciones guardan la URL y el SHA-256 de esa versión;
  - los instaladores `.cmd` y `.exe` de esa versión traen incrustado el hash de su bundle.
