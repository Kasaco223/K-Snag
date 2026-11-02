"""Valores fijos de la aplicación. Las URLs se pueden sobrescribir con variables de entorno para pruebas."""

import os

from . import __version__

APP_NAME = "K-Downloader"
APP_ID = "Kasaco223.KDownloader"  # AppUserModelID: agrupa la ventana con su acceso directo
PROTOCOL = "kdownloader"
REPO = "Kasaco223/K-YoutubeVideoDownloaderHD"
USER_AGENT = f"K-Downloader/{__version__} (+https://github.com/{REPO})"

WEB_BASE = os.environ.get("KD_WEB_BASE", "https://kdownloader.vercel.app").rstrip("/")
RELEASES_LATEST = f"https://github.com/{REPO}/releases/latest/download"
LATEST_JSON_URL = os.environ.get("KD_UPDATE_URL", f"{RELEASES_LATEST}/latest.json")
ENGINE_HINTS_URL = os.environ.get("KD_HINTS_URL", f"https://raw.githubusercontent.com/{REPO}/master/engine.json")

# Páginas que la UI puede abrir; JS solo envía la clave, nunca una URL.
WEB_PAGES = {
    "home": WEB_BASE,
    "help": f"{WEB_BASE}/ayuda",
    "responsible": f"{WEB_BASE}/uso-responsable",
    "donate": f"{WEB_BASE}/donar",
    "download": f"{WEB_BASE}/descargar",
    "github": f"https://github.com/{REPO}",
    "releases": f"https://github.com/{REPO}/releases",
    "issues": f"https://github.com/{REPO}/issues",
}

MUTEX_NAME = "Local\\KDownloader.SingleInstance"
PIPE_PREFIX = r"\\.\pipe\KDownloader-"
