"""Instalar-K-Snag.exe tiene que seguir siendo el mismo instalador que Instalar-K-Snag.cmd.

El .exe (packaging/installer/exe, C#) es la versión que se puede firmar. Estas pruebas comparan su código con la
plantilla del .cmd: carpeta de instalación, opciones, marcadores sin procesar y los datos incrustados.
"""

import importlib.util
import re
from pathlib import Path

from kdownloader.constants import DIR_NAME

ROOT = Path(__file__).resolve().parents[1]
EXE_DIR = ROOT / "packaging" / "installer" / "exe"
TEMPLATE = ROOT / "packaging" / "installer" / "Instalar-K-Snag.cmd.template"

spec = importlib.util.spec_from_file_location("check_installer_exe", ROOT / "packaging" / "check_installer_exe.py")
check_installer_exe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_installer_exe)


def _cs() -> str:
    return (EXE_DIR / "Installer.cs").read_text(encoding="utf-8")


def _options(text: str) -> set[str]:
    return set(re.findall(r"(--[a-z][a-z0-9-]*)", text))


def test_exe_uses_the_same_install_folder():
    assert re.findall(r'DirName = "([^"]+)"', _cs()) == [DIR_NAME]
    assert re.findall(r'DefaultRootRelative = @"Programs\\([^"]+)"', _cs()) == [DIR_NAME]


def test_exe_accepts_the_same_options_as_the_cmd():
    template = TEMPLATE.read_text(encoding="ascii")
    cmd_parse = template[template.index("function Read-KdOptions"):template.index("function Show-KdHelp")]
    cmd_help = template[template.index("function Show-KdHelp"):template.index("# --- Utilidades")]
    cs = _cs()
    exe_parse = cs[cs.index("public static Options Parse"):cs.index("private static string FullPath")]
    exe_help = cs[cs.index("public static string HelpText"):cs.index("internal sealed class Step")]
    assert _options(exe_parse) == _options(cmd_parse)
    assert _options(exe_help) == _options(cmd_help)


def test_exe_has_the_same_steps_as_the_cmd():
    template = TEMPLATE.read_text(encoding="ascii")
    cmd_steps = re.findall(r"@\{ Text = '([^']+)'; Run = \{ Invoke-Step(\w+) \}; Weight = (\d+)", template)
    exe_steps = re.findall(
        r'new Step \{ Text = "([^"]+)", Weight = (\d+),(?: Critical = true,)? Run = Step(\w+) \}', _cs())
    assert [(name, weight) for _, name, weight in cmd_steps] == [(name, weight) for _, weight, name in exe_steps]


def test_csproj_placeholders_mark_an_unprocessed_build():
    csproj = (EXE_DIR / "Instalar-K-Snag.csproj").read_text(encoding="utf-8")
    for prop, marker in [("KdVersion", "{{VERSION}}"), ("KdBundleUrl", "{{BUNDLE_URL}}"),
                         ("KdBundleSha256", "{{BUNDLE_SHA256}}"), ("KdBundleName", "{{BUNDLE_NAME}}")]:
        assert f"<{prop} Condition=\"'$({prop})' == ''\">{marker}</{prop}>" in csproj
        assert f'<AssemblyMetadata Include="{prop}" Value="$({prop})" />' in csproj


def test_manifest_never_asks_for_admin():
    manifest = (EXE_DIR / "app.manifest").read_text(encoding="utf-8")
    assert 'level="asInvoker"' in manifest
    assert "requireAdministrator" not in manifest


def test_build_script_passes_the_bundle_data_to_the_exe():
    build = (ROOT / "packaging" / "build.ps1").read_text(encoding="utf-8-sig")
    for prop, var in [("KdVersion", "$Version"), ("KdBundleUrl", "$bundleUrl"), ("KdBundleSha256", "$bundleSha"),
                      ("KdBundleName", "$bundleName"), ("KdBundleSize", "$bundleSize")]:
        assert f"('-p:{prop}=' + {var})" in build


def _ser(text: str) -> bytes:
    raw = text.encode("utf-8")
    assert len(raw) < 0x4000
    prefix = bytes([len(raw)]) if len(raw) < 0x80 else bytes([0x80 | (len(raw) >> 8), len(raw) & 0xFF])
    return prefix + raw


def test_metadata_reader_matches_cmd_block():
    values = {
        "KdVersion": "9.8.7",
        "KdBundleUrl": "https://github.com/Kasaco223/K-Snag/releases/download/v9.8.7/kd-bundle-9.8.7.zip" + "x" * 80,
        "KdBundleSha256": "a" * 64,
        "KdBundleName": "kd-bundle-9.8.7.zip",
        "KdBundleSize": "123",
    }
    blob = b"MZ\x00\x00" + b"".join(b"\x01\x00\x02\x00" + _ser(k) + _ser(v) + b"\x00\x00" for k, v in values.items())
    assert check_installer_exe.exe_metadata(blob) == values
    cmd = "$KD = @{\n" + "".join(
        f"    {name} = '{values[key]}'\n" for key, name in check_installer_exe.KEYS.items()) + "}\n"
    assert check_installer_exe.cmd_metadata(cmd) == values
