"""Punto de entrada: crea la ventana pywebview, conecta los servicios y gestiona el ciclo de vida."""

from __future__ import annotations

import os
import sys
from pathlib import Path

from . import __version__, paths, winintegration
from .constants import APP_NAME
from .instance import InstanceGuard, url_from_argv
from .util import log, setup_logging


def _migrate_name_later() -> None:
    """La app se llamaba K-Downloader: en una instalación actualizada sola, cambia el acceso directo y el nombre
    de «Desinstalar» a K-Snag. En segundo plano y una sola vez (después ya no queda nada viejo que cambiar)."""
    root = paths.install_root()
    if root is None or sys.platform != "win32":
        return

    def run() -> None:
        try:
            from . import installer

            changed = installer.migrate_name(root, __version__)
            if changed:
                log.info("Nombre actualizado a %s: %s", APP_NAME, ", ".join(changed))
        except Exception:  # noqa: BLE001 - nunca impide abrir la app
            log.warning("No se pudo actualizar el nombre de los accesos directos", exc_info=True)

    import threading

    threading.Thread(target=run, name="kd-migrate-name", daemon=True).start()


def _icon_path() -> str | None:
    root = paths.install_root()
    candidates = [root / "K-Downloader.ico"] if root else []
    candidates.append(paths.package_dir().parent.parent / "packaging" / "assets" / "K-Downloader.ico")
    for c in candidates:
        if c.exists():
            return str(c)
    return None


def _bring_to_front(window) -> None:
    try:
        window.restore()
        from System import Func, Object  # type: ignore[import-not-found]

        def activate():
            window.native.Activate()
            return None

        window.native.Invoke(Func[Object](activate))
    except Exception:  # noqa: BLE001
        pass


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    winintegration.suppress_error_dialogs()
    debug = "--debug" in argv or os.environ.get("KD_DEBUG") == "1"
    setup_logging(paths.logs_dir(), debug)
    log.info("K-Snag %s (Python %s) desde %s", __version__, sys.version.split()[0], sys.executable)
    winintegration.set_app_id()

    guard = InstanceGuard(paths.data_dir())
    if not guard.acquire():
        if "--after-update" in argv:
            if not guard.wait_for_release():
                log.warning("La instancia anterior no se cerró a tiempo")
                return 1
        else:
            guard.forward(argv)
            return 0

    _migrate_name_later()

    import mimetypes

    # Algunos Windows tienen el registro roto y sirven .js como text/plain: los módulos ES no cargarían.
    for ext, mime in ((".js", "text/javascript"), (".mjs", "text/javascript"), (".json", "application/json"),
                      (".svg", "image/svg+xml"), (".css", "text/css")):
        mimetypes.add_type(mime, ext)

    import webview  # import tardío: la segunda instancia no necesita cargar WebView2

    from .bridge import Api
    from .services import Services

    services = Services()
    api = Api(services)
    window = webview.create_window(
        APP_NAME, url=str(paths.ui_dir() / "index.html"), js_api=api, width=1000, height=680,
        min_size=(760, 540), background_color="#101218", text_select=False,
    )
    services.window = window
    services.pump.attach(window)

    def on_closing():
        active = services.downloads.active_count()
        if services.force_quit or active == 0:
            return True
        # No se llama a JS desde aquí (hilo de la UI): el aviso sale por el hilo del pump.
        services.pump.emit({"type": "confirm_close", "active": active}, "confirm_close")
        return False

    def on_shown():
        try:
            services.hwnd = int(window.native.Handle.ToInt64())
        except Exception:  # noqa: BLE001
            services.hwnd = 0

    def on_message(msg: dict) -> None:
        url = url_from_argv([str(a) for a in msg.get("argv") or []])
        if url:
            services.pump.emit({"type": "incoming_url", "url": url}, "incoming_url")
        _bring_to_front(window)

    window.events.closing += on_closing
    window.events.shown += on_shown
    guard.serve(on_message)

    incoming = url_from_argv(argv)
    if incoming:
        services.pump.emit({"type": "incoming_url", "url": incoming}, "incoming_url")

    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
    webview.settings["ALLOW_DOWNLOADS"] = False
    webview.settings["SHOW_DEFAULT_MENUS"] = debug  # sin el menú "Atrás/Recargar/Inspeccionar" en producción
    port = os.environ.get("KD_REMOTE_DEBUG_PORT")
    if port and port.isdigit():  # solo para pruebas e2e (Playwright/CDP)
        webview.settings["REMOTE_DEBUGGING_PORT"] = int(port)
    try:
        webview.start(services.start_background, gui="edgechromium", debug=debug, private_mode=True,
                      http_server=True, icon=_icon_path())
    finally:
        services.shutdown()
        guard.close()
        log.info("K-Snag cerrado")
    return 0


def selftest() -> int:
    """Comprobación rápida para el instalador y la CI: dependencias, UI y motor importables."""
    problems: list[str] = []
    winintegration.suppress_error_dialogs()
    try:
        import webview  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        problems.append(f"pywebview: {exc!r}")
    if sys.platform == "win32":
        try:
            import clr_loader  # noqa: F401
        except Exception as exc:  # noqa: BLE001
            problems.append(f"pythonnet: {exc!r}")
    index = paths.ui_dir() / "index.html"
    if not index.exists():
        problems.append(f"Falta la interfaz: {index}")
    try:
        from .engine import errors, presets, ytdlp  # noqa: F401

        errors.catalog()
        presets.build_args(presets.Choice("recommended"), is_youtube=True)
    except Exception as exc:  # noqa: BLE001
        problems.append(f"motor: {exc!r}")
    out = sys.stdout
    if out is not None:
        if problems:
            out.write("SELFTEST FAIL\n" + "\n".join(problems) + "\n")
        else:
            out.write(f"SELFTEST OK K-Snag {__version__} · Python {sys.version.split()[0]} · "
                      f"{Path(sys.executable).name}\n")
    return 1 if problems else 0
