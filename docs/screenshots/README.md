# Capturas de pantalla

El README principal muestra estas capturas. Se generan desde el modo simulado de la interfaz, así salen siempre iguales y sin datos personales.

| Archivo | Escenario | URL del modo simulado |
| --- | --- | --- |
| `inicio.png` | S1 Inicio | `index.html?mock=1&scenario=S1&theme=dark&lang=es` |
| `formato.png` | S3 Formato | `index.html?mock=1&scenario=S3&theme=dark&lang=es` |
| `descargas.png` | S4 Descargas | `index.html?mock=1&scenario=S4&theme=dark&lang=es` |

> Se rehicieron en noviembre de 2026, después del cambio de nombre a K-Snag y del botón «Apoyar el proyecto». El modo simulado muestra una etiqueta «DEMO» en la barra lateral: para las capturas se oculta con el estilo `.demo-tag { display: none }` (por ejemplo, desde las herramientas del navegador).

## Cómo generarlas

1. Sirve la interfaz:

   ```powershell
   cd src\kdownloader\ui
   python -m http.server 8000
   ```

2. Abre cada URL de la tabla en `http://localhost:8000/`, con la ventana del navegador a 1000×680, que es el tamaño inicial de la app.
3. Captura solo el contenido de la página, sin la barra del navegador, y guarda el PNG con el nombre de la tabla.
4. **Antes de subirlas:**
   - Optimízalas, sin pasar de unos 300 KB cada una.
   - Revisa que no salga ningún dato real: el modo simulado usa títulos y canales de ejemplo.
