# Pistas remotas del motor (`engine.json`)

`engine.json`, en la raíz del repositorio, te permite corregir el comportamiento del motor en todas las instalaciones **sin publicar una versión nueva de la app**. Sirve para emergencias: YouTube cambia algo, yt-dlp publica el arreglo y necesitas que todos lo reciban ya.

La app lo lee desde la rama `master`:

```text
https://raw.githubusercontent.com/Kasaco223/K-Snag/master/engine.json
```

> Solo cuenta la copia de `master`. Editar el archivo en otra rama o en un fork no tiene ningún efecto.

## Qué se puede cambiar

El archivo tiene una **lista blanca** de campos (ver `src/kdownloader/engine/hints.py`). La app descarta todo lo demás. No acepta URLs, comandos ni argumentos libres, así que un error de edición nunca le hace daño a nadie.

```json
{
  "schema": 1,
  "min_ytdlp": {
    "nightly": "2026.09.27.232945",
    "stable": "2026.08.19"
  },
  "youtube_player_client": null,
  "notice": {
    "es": null,
    "en": null
  }
}
```

| Campo | Valor válido | Efecto en la app |
| --- | --- | --- |
| `schema` | Siempre `1` | Con cualquier otro valor, la app ignora el archivo entero y se queda sin pistas. |
| `min_ytdlp.nightly` | Versión `AAAA.MM.DD.HHMMSS` (por ejemplo `"2026.10.21.031522"`) | Si la versión instalada de yt-dlp (canal nightly) es más vieja, la app se actualiza en la próxima revisión. |
| `min_ytdlp.stable` | Versión `AAAA.MM.DD` (por ejemplo `"2026.10.24"`) | Igual que la anterior, para el canal estable. |
| `youtube_player_client` | `null` o una lista de clientes separados por comas, en minúsculas, con `-` delante para excluir: `^-?[a-z_]+(,-?[a-z_]+)*$`, hasta 120 caracteres | La app agrega `--extractor-args youtube:player_client=<valor>` a todo lo que hace con YouTube. `null` vuelve a los valores de yt-dlp. |
| `notice.es` / `notice.en` | `null` o texto plano de hasta 280 caracteres | La app muestra un aviso con este texto en la parte de arriba. Se pinta como texto: el HTML y los enlaces se ven tal cual, sin formato. `null` o `""` lo quitan. |

### Qué no se puede hacer (a propósito)

- **Bajar de versión.** Una pista nunca instala un yt-dlp más viejo que el que ya está instalado: la app siempre baja la última versión publicada del canal.
- **Usar versiones que todavía no existen.** Si pones una versión que todavía no está publicada, la app no tiene nada que instalar: solo revisa una y otra vez sin resultado. Primero espera el release en [yt-dlp-nightly-builds](https://github.com/yt-dlp/yt-dlp-nightly-builds/releases) o en [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases).
- **Cambiar FFmpeg o Deno, ni la URL de ningún componente.** Las fuentes y sus SHA-256 están fijas en el código.

## Cuándo llega el cambio

- La app relee el archivo **como máximo una vez cada 24 horas** y lo guarda en `%LOCALAPPDATA%\K-Downloader\hints.json`.
- `raw.githubusercontent.com` además tiene su propia caché de unos 5 minutos.
- Cuenta con **hasta un día** para que el cambio llegue a todas las instalaciones.
- Para probarlo en tu propia PC al momento: cierra K-Snag, borra `%LOCALAPPDATA%\K-Downloader\hints.json` y ábrela de nuevo.
- Si el archivo queda con un error de sintaxis JSON, la app conserva las últimas pistas válidas y vuelve a intentarlo en la próxima revisión.

## Cómo publicar una pista de emergencia (desde el navegador)

1. Entra a <https://github.com/Kasaco223/K-Snag/blob/master/engine.json> con tu cuenta, que debe tener la verificación en dos pasos activa.
2. Pulsa el lápiz (**Edit this file**).
3. Cambia **solo los valores**:
   - Respeta las comillas en las versiones y los textos.
   - `null` va sin comillas.
   - No dejes una coma después del último elemento de un bloque.
4. Pulsa **Commit changes…**:
   - Mensaje: `engine.json: <motivo>`, por ejemplo `engine.json: exigir yt-dlp 2026.10.21 por el 403 de YouTube`.
   - Elige **Commit directly to the `master` branch**.
5. Abre la pestaña **Actions**. El flujo **CI** valida el archivo en el paso "Validar engine.json".
   - Si queda en rojo, corrígelo enseguida: la app descarta los campos inválidos.
6. Comprueba la copia pública en <https://raw.githubusercontent.com/Kasaco223/K-Snag/master/engine.json> (puede tardar unos minutos).
7. **Cuando ya no haga falta, revierte el cambio:**
   - El cliente vuelve a `null` y el aviso a `null`.
   - Los mínimos pueden quedarse: no molestan.

### Ejemplos

**YouTube devuelve 403 a todos y yt-dlp ya publicó el arreglo en nightly:**

```json
"min_ytdlp": {
  "nightly": "2026.10.21.031522",
  "stable": "2026.08.19"
},
```

Cuando salga la versión estable con el mismo arreglo, sube también `stable`.

**Un cliente de YouTube falla y los mantenedores de yt-dlp recomiendan excluirlo mientras tanto:**

```json
"youtube_player_client": "default,-android_vr",
```

Usa exactamente lo que recomienden en el issue de yt-dlp. Vuelve a `null` cuando salga el arreglo.

**Avisar a todos mientras llega la solución:**

```json
"notice": {
  "es": "YouTube cambió algo hoy y algunas descargas fallan. Ya hay un arreglo en camino: la app se actualizará sola.",
  "en": "YouTube changed something today and some downloads fail. A fix is on the way: the app will update itself."
}
```

Escribe siempre los dos idiomas: cada persona ve el aviso en el idioma de su app.

## Comprobarlo en local antes de publicar

Desde la raíz del repo, con el entorno de desarrollo activo:

```powershell
.venv\Scripts\python -c "import json; from kdownloader.engine.hints import validate; print(validate(json.load(open('engine.json', encoding='utf-8'))).to_dict())"
```

Si un campo que escribiste no aparece en la salida, la app lo va a ignorar.
