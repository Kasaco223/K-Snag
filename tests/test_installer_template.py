"""El instalador .cmd y la app tienen que coincidir en la carpeta de instalación.

La CI instala con --root explícito, que se salta la comprobación del nombre de la carpeta; por eso la 2.0.3
salió rechazando su propia carpeta (la comprobación decía 'K-Snag' y la carpeta es 'K-Downloader').
"""

import re
from pathlib import Path

from kdownloader.constants import DIR_NAME

TEMPLATE = Path(__file__).resolve().parents[1] / "packaging" / "installer" / "Instalar-K-Snag.cmd.template"


def _text() -> str:
    return TEMPLATE.read_text(encoding="ascii")


def test_default_root_uses_dir_name():
    roots = re.findall(r"'Programs\\([^']+)'", _text())
    assert roots == [DIR_NAME]


def test_folder_name_check_matches_default_root():
    checks = re.findall(r"Split-Path -Leaf \$root\) -ine '([^']+)'", _text())
    assert checks == [DIR_NAME]
