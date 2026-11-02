"""Lanzador estable de K-Snag (solo biblioteca estándar).

Vive en la raíz de instalación y no cambia entre versiones:

    <raíz>\\runtime\\pythonw.exe <raíz>\\launch.pyw [argumentos]

1. Lee <raíz>\\current (la versión activa) e inserta <raíz>\\app\\<versión> en sys.path.
2. Define KD_INSTALL_ROOT=<raíz>.
3. Despacha:
     (sin argumentos)                       -> kdownloader.app.main(argv)
     --protocol <uri>                       -> kdownloader.app.main(["--protocol", uri])
     --install-finish --version V [...]     -> kdownloader.installer.main(argv)
     --uninstall [--keep-data] [--quiet]    -> kdownloader.installer.main(argv)
     --selftest                             -> kdownloader.app.selftest()
   argv es la lista de argumentos sin el nombre del programa.

Si algo falla al importar, se guarda el error en %LOCALAPPDATA%\\K-Downloader\\logs\\launch.log y,
cuando hay ventana (modo normal o --protocol), se muestra un aviso de Windows en español.
"""

import os
import re
import sys
import time
import traceback
from pathlib import Path

APP_NAME = "K-Snag"
DIR_NAME = "K-Downloader"  # carpeta de datos: no cambia con el nombre (instalaciones existentes)
DOWNLOAD_PAGE = "https://ksnagapp.vercel.app/descargar"
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$")
INSTALLER_COMMANDS = ("--install-finish", "--uninstall")


def install_root() -> Path:
    return Path(__file__).resolve().parent


def log_path() -> Path:
    base = os.environ.get("KD_DATA_DIR") or os.path.join(
        os.environ.get("LOCALAPPDATA") or os.path.join(os.path.expanduser("~"), "AppData", "Local"), DIR_NAME
    )
    return Path(base) / "logs" / "launch.log"


def _version_key(name: str) -> tuple:
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[.+_-]", name))


def find_app_dir(root: Path) -> Path:
    """Carpeta app\\<versión> indicada por 'current'; si falta, la versión instalada más reciente."""
    apps = root / "app"
    try:
        version = (root / "current").read_text(encoding="utf-8-sig").strip()
    except OSError:
        version = ""
    if VERSION_RE.match(version) and (apps / version / "kdownloader" / "__init__.py").is_file():
        return apps / version
    candidates = []
    if apps.is_dir():
        for entry in apps.iterdir():
            if VERSION_RE.match(entry.name) and (entry / "kdownloader" / "__init__.py").is_file():
                candidates.append(entry)
    if not candidates:
        detail = f"'current' = {version!r}" if version else "no se encontró el archivo 'current'"
        raise RuntimeError(f"No se encontró el código de la app en {apps} ({detail}).")
    return max(candidates, key=lambda p: _version_key(p.name))


def _write_log(text: str) -> Path | None:
    path = log_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"--- {time.strftime('%Y-%m-%d %H:%M:%S')} {sys.executable} {sys.argv!r}\n{text}\n")
        return path
    except OSError:
        return None


def _message_box(text: str, title: str = APP_NAME) -> None:
    try:
        import ctypes

        # MB_OK | MB_ICONERROR | MB_SETFOREGROUND
        ctypes.windll.user32.MessageBoxW(None, text, title, 0x0 | 0x10 | 0x10000)
    except Exception:
        pass


def _stderr(text: str) -> None:
    stream = sys.stderr
    if stream is not None:
        try:
            stream.write(text + "\n")
            stream.flush()
        except (OSError, ValueError):
            pass


def report_failure(exc: BaseException, interactive: bool, started: bool) -> int:
    details = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    path = _write_log(details)
    where = str(path) if path else str(log_path())
    _stderr(details.rstrip())
    if interactive:
        headline = (
            "K-Snag se cerró por un error inesperado."
            if started
            else "K-Snag no pudo iniciar."
        )
        _message_box(
            f"{headline}\n\n"
            f"{type(exc).__name__}: {exc}\n\n"
            f"Encontrarás los detalles en:\n{where}\n\n"
            "Si vuelve a pasar, descarga e instala K-Snag otra vez desde\n"
            f"{DOWNLOAD_PAGE}\n(tus ajustes y tu historial se conservan)."
        )
    return 1


def resolve(argv: list[str]):
    """Devuelve (función, argumentos) según la línea de comandos. Importa el módulo necesario."""
    # --protocol gana siempre: la URL viene de afuera y nunca debe activar otro comando.
    if "--protocol" in argv:
        i = argv.index("--protocol")
        uri = argv[i + 1] if i + 1 < len(argv) else ""
        from kdownloader import app

        return app.main, (["--protocol", uri],)
    if argv and argv[0] in INSTALLER_COMMANDS:
        from kdownloader import installer

        return installer.main, (argv,)
    if argv and argv[0] == "--selftest":
        from kdownloader import app

        return app.selftest, ()
    from kdownloader import app

    return app.main, (argv,)


def _suppress_error_dialogs() -> None:
    """Sin diálogos "Bad Image" del sistema si Windows bloquea un módulo (los hijos heredan el modo)."""
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x0002 | 0x8000)
        except (OSError, AttributeError):
            pass


def main() -> int:
    _suppress_error_dialogs()
    argv = sys.argv[1:]
    # Hay ventana en el modo normal y con --protocol; los comandos de consola informan por stderr.
    interactive = not argv or "--protocol" in argv or (argv[0] == "--uninstall" and "--quiet" not in argv)
    started = False
    try:
        root = install_root()
        os.environ["KD_INSTALL_ROOT"] = str(root)
        sys.path.insert(0, str(find_app_dir(root)))
        func, args = resolve(argv)
        started = True
        rc = func(*args)
    except SystemExit as exc:  # argparse y similares
        code = exc.code
        return code if isinstance(code, int) else (0 if code is None else 1)
    except BaseException as exc:
        return report_failure(exc, interactive, started)
    if rc is None:
        return 0
    return rc if isinstance(rc, int) else 1


if __name__ == "__main__":
    sys.exit(main())
