"""Integración con Windows al instalar y desinstalar K-Snag (biblioteca estándar + comtypes opcional).

Lo llama launch.pyw:

    runtime\\python.exe launch.pyw --install-finish --version V [--desktop] [--no-registry]
    runtime\\python.exe launch.pyw --uninstall [--keep-data | --delete-data] [--quiet] [--no-registry]

--install-finish: escribe "current", crea los accesos directos (menú Inicio y, si se pide, escritorio)
con ícono y AppUserModelID, registra el protocolo kdownloader:// y la entrada de "Aplicaciones" en
HKCU, y deja solo las dos versiones más nuevas de app\\<versión>.

--uninstall: cierra la app si está abierta, quita accesos directos y claves del registro, pregunta si
borrar los datos (%LOCALAPPDATA%\\K-Downloader) y programa el borrado de la carpeta de instalación
para cuando este proceso termine (el runtime está en uso mientras tanto).

Opciones para pruebas: --root (otra carpeta de instalación), --no-registry (no toca el registro ni
crea o borra accesos directos fuera de --shortcut-dir/--desktop-dir), --shortcut-dir, --desktop-dir,
--no-schedule (no programa el borrado final). Los datos se pueden redirigir con KD_DATA_DIR.

Seguridad: nunca borra una carpeta que no termine en "K-Snag" (salvo un --root explícito) o que no
contenga launch.pyw; nunca sigue enlaces ni uniones (junctions) y nunca borra fuera de la raíz indicada.
"""

from __future__ import annotations

import argparse
import contextlib
import ctypes
import os
import re
import stat
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass
from pathlib import Path

from .constants import APP_ID, APP_NAME, DIR_NAME, PROTOCOL, WEB_BASE

if sys.platform == "win32":
    import winreg
    from ctypes import wintypes
else:  # pragma: no cover - el instalador solo existe en Windows
    winreg = None  # type: ignore[assignment]
    wintypes = None  # type: ignore[assignment]

PUBLISHER = "Kasaco223"
SHORTCUT_FILE = f"{APP_NAME}.lnk"
LEGACY_SHORTCUT_FILE = f"{DIR_NAME}.lnk"  # el acceso directo de antes del cambio de nombre (2.0.2 y anteriores)
ICON_FILE = f"{DIR_NAME}.ico"
DESCRIPTION = "Descarga tus videos favoritos en la mejor calidad"
UNINSTALL_KEY = rf"Software\Microsoft\Windows\CurrentVersion\Uninstall\{DIR_NAME}"
PROTOCOL_KEY = rf"Software\Classes\{PROTOCOL}"
KEEP_APP_VERSIONS = 2  # la versión activa y la anterior (para volver atrás)
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$")

FOLDERID_PROGRAMS = "{A77F5D77-2E2B-44C3-A6A2-ABA601054A51}"
FOLDERID_DESKTOP = "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"
PKEY_APPUSERMODEL_ID = ("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}", 5)

FILE_ATTRIBUTE_REPARSE_POINT = 0x400
FILE_ATTRIBUTE_DIRECTORY = 0x10
CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_BREAKAWAY_FROM_JOB = 0x01000000

MB_YESNO = 0x4
MB_ICONERROR = 0x10
MB_ICONQUESTION = 0x20
MB_ICONWARNING = 0x30
MB_ICONINFORMATION = 0x40
MB_DEFBUTTON2 = 0x100
MB_SETFOREGROUND = 0x10000
MB_TOPMOST = 0x40000
IDYES = 6

UNINSTALL_TITLE = f"Desinstalar {APP_NAME}"


class InstallerError(Exception):
    """Error con un mensaje listo para mostrar (en español)."""


# ---------------------------------------------------------------------------------------------
# Salida y diálogos


def say(text: str) -> None:
    stream = sys.stdout
    if stream is None:
        return
    try:
        stream.write(text + "\n")
        stream.flush()
    except (OSError, ValueError, UnicodeEncodeError):
        with contextlib.suppress(Exception):
            stream.write(text.encode("ascii", "replace").decode("ascii") + "\n")


def message_box(text: str, title: str = APP_NAME, flags: int = MB_ICONINFORMATION) -> int:
    try:
        return int(ctypes.windll.user32.MessageBoxW(None, text, title, flags | MB_SETFOREGROUND | MB_TOPMOST))
    except Exception:  # noqa: BLE001 - nunca romper el instalador por esto
        return 0


def ask_yes_no(text: str, title: str = UNINSTALL_TITLE, default_no: bool = True) -> bool:
    flags = MB_YESNO | MB_ICONQUESTION | (MB_DEFBUTTON2 if default_no else 0)
    return message_box(text, title, flags) == IDYES


# ---------------------------------------------------------------------------------------------
# Carpetas conocidas


def _known_folder(guid: str) -> Path:
    class GUID(ctypes.Structure):
        _fields_ = [
            ("Data1", wintypes.DWORD),
            ("Data2", wintypes.WORD),
            ("Data3", wintypes.WORD),
            ("Data4", wintypes.BYTE * 8),
        ]

    g = GUID()
    if ctypes.windll.ole32.CLSIDFromString(ctypes.c_wchar_p(guid), ctypes.byref(g)) != 0:
        raise OSError(f"GUID inválido: {guid}")
    out = ctypes.c_wchar_p()
    shell32 = ctypes.windll.shell32
    shell32.SHGetKnownFolderPath.argtypes = [ctypes.c_void_p, wintypes.DWORD, wintypes.HANDLE, ctypes.c_void_p]
    hr = shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(out))
    if hr != 0:
        raise OSError(f"SHGetKnownFolderPath falló ({hr & 0xFFFFFFFF:#x})")
    try:
        return Path(out.value or "")
    finally:
        ctypes.windll.ole32.CoTaskMemFree(out)


def start_menu_dir() -> Path:
    try:
        return _known_folder(FOLDERID_PROGRAMS)
    except OSError:
        appdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs"


def desktop_dir() -> Path:
    # Puede estar redirigido (OneDrive): por eso se pregunta a Windows y no se arma la ruta a mano.
    try:
        return _known_folder(FOLDERID_DESKTOP)
    except OSError:
        return Path(os.environ.get("USERPROFILE") or Path.home()) / "Desktop"


def data_dir() -> Path:
    """Carpeta de datos del usuario, sin crearla (a diferencia de paths.data_dir)."""
    override = os.environ.get("KD_DATA_DIR")
    if override:
        return Path(override)
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    return Path(base) / DIR_NAME


# ---------------------------------------------------------------------------------------------
# Rutas y seguridad


def _norm(path: Path | str) -> str:
    return os.path.normcase(os.path.abspath(str(path))).rstrip("\\/")


def is_within(child: Path | str, parent: Path | str) -> bool:
    c, p = _norm(child), _norm(parent)
    return c == p or c.startswith(p + os.sep)


def is_reparse_point(path: Path | str) -> bool:
    try:
        st = os.lstat(path)
    except OSError:
        return False
    return bool(getattr(st, "st_file_attributes", 0) & FILE_ATTRIBUTE_REPARSE_POINT) or stat.S_ISLNK(st.st_mode)


def _protected_dirs() -> list[str]:
    candidates = [
        Path.home(),
        os.environ.get("LOCALAPPDATA"),
        os.environ.get("APPDATA"),
        os.environ.get("USERPROFILE"),
        os.environ.get("SYSTEMROOT"),
        os.environ.get("PROGRAMFILES"),
        os.environ.get("PROGRAMFILES(X86)"),
        os.environ.get("TEMP"),
        os.environ.get("TMP"),
    ]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(Path(local) / "Programs")
    return [_norm(c) for c in candidates if c]


def check_install_root(root: Path, explicit: bool) -> Path:
    """Valida que root sea una instalación de K-Snag que se puede borrar. Devuelve la ruta absoluta."""
    root = Path(os.path.abspath(root))
    if not root.is_dir():
        raise InstallerError(f"No existe la carpeta de instalación: {root}")
    if root.parent == root or len(root.parts) < 3:
        raise InstallerError(f"Por seguridad no se borra una carpeta tan general: {root}")
    if _norm(root) in _protected_dirs():
        raise InstallerError(f"Por seguridad no se borra esta carpeta: {root}")
    if is_reparse_point(root):
        raise InstallerError(f"La carpeta de instalación es un enlace; por seguridad no se borra: {root}")
    if not explicit and root.name.lower() != DIR_NAME.lower():
        raise InstallerError(f"La carpeta no parece una instalación de {APP_NAME}: {root}")
    if not (root / "launch.pyw").is_file():
        raise InstallerError(f"La carpeta no contiene launch.pyw; no parece una instalación de {APP_NAME}: {root}")
    return root


def check_data_dir(path: Path, install_root: Path | None) -> Path | None:
    """Devuelve la carpeta de datos si es seguro borrarla; None si no."""
    path = Path(os.path.abspath(path))
    if not path.is_dir():
        return None
    if path.parent == path or len(path.parts) < 3 or _norm(path) in _protected_dirs():
        return None
    if is_reparse_point(path):
        return None
    if path.name.lower() != DIR_NAME.lower() and not os.environ.get("KD_DATA_DIR"):
        return None
    if install_root is not None and (is_within(install_root, path) or is_within(path, install_root)):
        return None
    return path


def remove_tree(top: Path, keep: tuple[Path, ...] = ()) -> list[str]:
    """Borra el contenido de top y top mismo sin seguir enlaces ni uniones.

    Los enlaces se quitan (solo el enlace, nunca su destino), incluso dentro de las rutas de keep.
    Lo que está en keep (y sus carpetas padre) se conserva. Devuelve las rutas que no se pudieron borrar.
    """
    failures: list[str] = []
    top = Path(os.path.abspath(top))
    keep_norm = [_norm(k) for k in keep]

    def kept(p: str) -> bool:
        n = _norm(p)
        return any(n == k or n.startswith(k + os.sep) for k in keep_norm)

    def holds_kept(p: str) -> bool:
        n = _norm(p)
        return any(k.startswith(n + os.sep) for k in keep_norm)

    def unlink(p: str, is_dir: bool) -> None:
        try:
            (os.rmdir if is_dir else os.unlink)(p)
        except PermissionError:
            try:
                os.chmod(p, stat.S_IWRITE)
                (os.rmdir if is_dir else os.unlink)(p)
            except OSError:
                failures.append(p)
        except FileNotFoundError:
            pass
        except OSError:
            failures.append(p)

    def drop_link(p: str, st: os.stat_result) -> None:
        attrs = getattr(st, "st_file_attributes", 0)
        try:
            if attrs & FILE_ATTRIBUTE_DIRECTORY:
                os.rmdir(p)  # quita la unión o el enlace, no su destino
            else:
                os.unlink(p)
        except OSError:
            try:
                os.unlink(p)
            except OSError:
                failures.append(p)

    def walk(d: str, keep_files: bool) -> None:
        try:
            with os.scandir(d) as it:
                entries = list(it)
        except OSError:
            failures.append(d)
            return
        for entry in entries:
            p = entry.path
            if not is_within(p, top):  # nunca debería pasar; por las dudas
                failures.append(p)
                continue
            try:
                st = entry.stat(follow_symlinks=False)
            except OSError:
                failures.append(p)
                continue
            if getattr(st, "st_file_attributes", 0) & FILE_ATTRIBUTE_REPARSE_POINT or stat.S_ISLNK(st.st_mode):
                drop_link(p, st)
                continue
            inside_keep = keep_files or kept(p)
            if stat.S_ISDIR(st.st_mode):
                walk(p, inside_keep)
                if not inside_keep and not holds_kept(p):
                    unlink(p, True)
            elif not inside_keep:
                unlink(p, False)

    if is_reparse_point(top):
        drop_link(str(top), os.lstat(top))
        return failures
    if not top.exists():
        return failures
    walk(str(top), kept(str(top)))
    if not keep_norm:
        unlink(str(top), True)
    return failures


def dir_size(top: Path) -> int:
    total = 0
    for dirpath, dirnames, filenames in os.walk(top, followlinks=False):
        dirnames[:] = [d for d in dirnames if not is_reparse_point(os.path.join(dirpath, d))]
        for name in filenames:
            with contextlib.suppress(OSError):
                total += os.lstat(os.path.join(dirpath, name)).st_size
    return total


def _version_key(name: str) -> tuple:
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[.+_-]", name))


def write_current(root: Path, version: str) -> None:
    tmp = root / f"current.{os.getpid()}.tmp"
    tmp.write_text(version, encoding="ascii")
    os.replace(tmp, root / "current")


def prune_app_versions(root: Path, active: str, keep: int = KEEP_APP_VERSIONS) -> list[str]:
    """Deja app\\<active> y las (keep - 1) versiones más nuevas; borra el resto. Devuelve lo borrado."""
    apps = root / "app"
    if not apps.is_dir() or is_reparse_point(apps):
        return []
    others = [
        e for e in apps.iterdir()
        if e.name != active and VERSION_RE.match(e.name) and e.is_dir() and not is_reparse_point(e)
    ]
    others.sort(key=lambda p: _version_key(p.name), reverse=True)
    removed = []
    for old in others[max(0, keep - 1):]:
        if not remove_tree(old):
            removed.append(old.name)
    return removed


# ---------------------------------------------------------------------------------------------
# Accesos directos


@dataclass
class Shortcut:
    path: Path
    target: Path
    arguments: str
    icon: Path
    workdir: Path
    app_id: str = APP_ID
    description: str = DESCRIPTION


def _com_types():
    """Define (una sola vez) las estructuras e interfaces COM que comtypes no trae."""
    global _COM
    if _COM is not None:
        return _COM
    import comtypes
    from comtypes import COMMETHOD, GUID, HRESULT, IUnknown

    class PROPERTYKEY(ctypes.Structure):
        _fields_ = [("fmtid", GUID), ("pid", wintypes.DWORD)]

    class _PVUnion(ctypes.Union):
        _fields_ = [("pwszVal", ctypes.c_void_p), ("ulVal", wintypes.ULONG), ("raw", ctypes.c_byte * 16)]

    class PROPVARIANT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = [
            ("vt", ctypes.c_ushort),
            ("wReserved1", wintypes.WORD),
            ("wReserved2", wintypes.WORD),
            ("wReserved3", wintypes.WORD),
            ("u", _PVUnion),
        ]

    class IPropertyStore(IUnknown):
        _iid_ = GUID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}")
        _methods_ = [  # noqa: RUF012 - así lo pide comtypes
            COMMETHOD([], HRESULT, "GetCount", (["out"], ctypes.POINTER(wintypes.DWORD), "cProps")),
            COMMETHOD(
                [], HRESULT, "GetAt", (["in"], wintypes.DWORD, "iProp"), (["out"], ctypes.POINTER(PROPERTYKEY), "pkey")
            ),
            COMMETHOD(
                [], HRESULT, "GetValue",
                (["in"], ctypes.POINTER(PROPERTYKEY), "key"), (["in"], ctypes.POINTER(PROPVARIANT), "pv"),
            ),
            COMMETHOD(
                [], HRESULT, "SetValue",
                (["in"], ctypes.POINTER(PROPERTYKEY), "key"), (["in"], ctypes.POINTER(PROPVARIANT), "propvar"),
            ),
            COMMETHOD([], HRESULT, "Commit"),
        ]

    key = PROPERTYKEY(GUID(PKEY_APPUSERMODEL_ID[0]), PKEY_APPUSERMODEL_ID[1])
    _COM = {"comtypes": comtypes, "PROPVARIANT": PROPVARIANT, "IPropertyStore": IPropertyStore, "PKEY_AUMID": key}
    return _COM


_COM: dict | None = None
VT_LPWSTR = 31


def _new_shell_link():
    com = _com_types()
    comtypes = com["comtypes"]
    from comtypes.shelllink import IShellLinkW, ShellLink

    with contextlib.suppress(OSError):
        comtypes.CoInitialize()
    return comtypes.CoCreateInstance(ShellLink._reg_clsid_, IShellLinkW, comtypes.CLSCTX_INPROC_SERVER)


def _create_shortcut_comtypes(sc: Shortcut) -> None:
    com = _com_types()
    from comtypes.persist import IPersistFile

    link = _new_shell_link()
    link.SetPath(str(sc.target))
    link.SetArguments(sc.arguments)
    link.SetWorkingDirectory(str(sc.workdir))
    link.SetIconLocation(str(sc.icon), 0)
    link.SetDescription(sc.description)

    store = link.QueryInterface(com["IPropertyStore"])
    buf = ctypes.create_unicode_buffer(sc.app_id)
    pv = com["PROPVARIANT"]()
    pv.vt = VT_LPWSTR
    pv.pwszVal = ctypes.cast(buf, ctypes.c_void_p).value
    store.SetValue(ctypes.byref(com["PKEY_AUMID"]), ctypes.byref(pv))  # SetValue copia el valor
    store.Commit()

    sc.path.parent.mkdir(parents=True, exist_ok=True)
    link.QueryInterface(IPersistFile).Save(str(sc.path), True)


def _create_shortcut_powershell(sc: Shortcut) -> None:
    # Respaldo sin comtypes: WScript.Shell no puede fijar el AppUserModelID.
    script = (
        "$ErrorActionPreference='Stop';"
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:KD_LNK);"
        "$s.TargetPath=$env:KD_TARGET;$s.Arguments=$env:KD_ARGS;$s.WorkingDirectory=$env:KD_WORKDIR;"
        "$s.IconLocation=$env:KD_ICON+',0';$s.Description=$env:KD_DESC;$s.Save()"
    )
    env = dict(
        os.environ,
        KD_LNK=str(sc.path), KD_TARGET=str(sc.target), KD_ARGS=sc.arguments,
        KD_WORKDIR=str(sc.workdir), KD_ICON=str(sc.icon), KD_DESC=sc.description,
    )
    sc.path.parent.mkdir(parents=True, exist_ok=True)
    system32 = Path(os.environ.get("SYSTEMROOT", r"C:\Windows")) / "System32"
    powershell = system32 / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    result = subprocess.run(
        [str(powershell), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
        env=env, capture_output=True, text=True, timeout=120, creationflags=CREATE_NO_WINDOW,
    )
    if result.returncode != 0 or not sc.path.is_file():
        raise InstallerError(f"No se pudo crear el acceso directo {sc.path}: {result.stderr.strip()}")


def create_shortcut(sc: Shortcut) -> str:
    """Crea el acceso directo. Devuelve el método usado: "comtypes" o "powershell" (sin AUMID)."""
    try:
        _create_shortcut_comtypes(sc)
        return "comtypes"
    except Exception as exc:  # noqa: BLE001
        say(f"  comtypes no pudo crear el acceso directo ({type(exc).__name__}: {exc}); se usa PowerShell.")
        _create_shortcut_powershell(sc)
        return "powershell"


def read_shortcut(path: Path) -> dict:
    """Lee un .lnk (para pruebas y diagnóstico): destino, argumentos, carpeta, ícono y AppUserModelID."""
    com = _com_types()
    from comtypes.persist import IPersistFile
    from comtypes.shelllink import SLGP_RAWPATH

    link = _new_shell_link()
    link.QueryInterface(IPersistFile).Load(str(path), 0)
    icon, index = link.GetIconLocation()
    info = {
        "target": link.GetPath(SLGP_RAWPATH),
        "arguments": link.GetArguments(),
        "workdir": link.GetWorkingDirectory(),
        "icon": icon,
        "icon_index": index,
        "description": link.GetDescription(),
        "app_id": None,
    }
    store = link.QueryInterface(com["IPropertyStore"])
    pv = com["PROPVARIANT"]()
    store.GetValue(ctypes.byref(com["PKEY_AUMID"]), ctypes.byref(pv))
    try:
        if pv.vt == VT_LPWSTR and pv.pwszVal:
            info["app_id"] = ctypes.wstring_at(pv.pwszVal)
    finally:
        ctypes.windll.ole32.PropVariantClear(ctypes.byref(pv))
    return info


def remove_legacy_shortcut(folder: Path) -> bool:
    """Quita el «K-Downloader.lnk» de antes del cambio de nombre, si existe. True si lo quitó."""
    lnk = folder / LEGACY_SHORTCUT_FILE
    if not lnk.is_file() or is_reparse_point(lnk):
        return False
    try:
        lnk.unlink()
        say(f"Acceso directo anterior quitado: {lnk}")
        return True
    except OSError as exc:
        say(f"  No se pudo quitar {lnk}: {exc}")
        return False


def migrate_name(root: Path, version: str) -> list[str]:
    """Cambio de nombre a K-Snag en una instalación que se actualizó sola (el zip de la app no pasa por el
    instalador): cambia «K-Downloader.lnk» por «K-Snag.lnk» en el menú Inicio y el escritorio, y renueva el
    nombre de la entrada de «Desinstalar» y del protocolo. Solo toca lo que ya existía. Devuelve lo que cambió."""
    done: list[str] = []
    for folder in (start_menu_dir(), desktop_dir()):
        if folder is None or not (folder / LEGACY_SHORTCUT_FILE).is_file():
            continue
        sc = app_shortcut(root, folder)
        create_shortcut(sc)
        if remove_legacy_shortcut(folder):
            done.append(str(sc.path))
    if _read_value(UNINSTALL_KEY, "DisplayName") not in (None, APP_NAME):
        register_uninstall_entry(root, version)
        done.append(f"HKCU\\{UNINSTALL_KEY}")
    friendly = _read_value(PROTOCOL_KEY + r"\shell\open", "FriendlyAppName")
    if _key_exists(PROTOCOL_KEY) and friendly not in (None, APP_NAME):
        register_protocol(root)
        done.append(f"HKCU\\{PROTOCOL_KEY}")
    if done:
        notify_shell()
    return done


def app_shortcut(root: Path, folder: Path) -> Shortcut:
    return Shortcut(
        path=folder / SHORTCUT_FILE,
        target=root / "runtime" / "pythonw.exe",
        arguments=f'"{root / "launch.pyw"}"',
        icon=root / ICON_FILE,
        workdir=root,
    )


# ---------------------------------------------------------------------------------------------
# Registro (HKCU)


def protocol_command(root: Path) -> str:
    return f'"{root / "runtime" / "pythonw.exe"}" "{root / "launch.pyw"}" --protocol "%1"'


def uninstall_string(root: Path, quiet: bool = False) -> str:
    extra = " --quiet" if quiet else ""
    return f'"%SystemRoot%\\System32\\cmd.exe" /c ""{root / "uninstall.cmd"}"{extra}"'


def _set_values(subkey: str, values: dict[str, tuple[int, object]]) -> None:
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, subkey, 0, winreg.KEY_WRITE) as key:
        for name, (kind, value) in values.items():
            winreg.SetValueEx(key, name, 0, kind, value)


def _read_value(subkey: str, name: str) -> object | None:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, subkey, 0, winreg.KEY_READ) as key:
            return winreg.QueryValueEx(key, name)[0]
    except OSError:
        return None


def _key_exists(subkey: str) -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, subkey, 0, winreg.KEY_READ):
            return True
    except OSError:
        return False


def _delete_tree(subkey: str) -> None:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, subkey, 0, winreg.KEY_READ) as key:
            children = []
            i = 0
            while True:
                try:
                    children.append(winreg.EnumKey(key, i))
                except OSError:
                    break
                i += 1
    except FileNotFoundError:
        return
    for child in children:
        _delete_tree(f"{subkey}\\{child}")
    with contextlib.suppress(FileNotFoundError):
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, subkey)


def register_protocol(root: Path) -> None:
    sz = winreg.REG_SZ
    _set_values(PROTOCOL_KEY, {"": (sz, f"URL:{APP_NAME}"), "URL Protocol": (sz, "")})
    _set_values(PROTOCOL_KEY + r"\DefaultIcon", {"": (sz, f"{root / ICON_FILE},0")})
    _set_values(PROTOCOL_KEY + r"\Application", {
        "ApplicationName": (sz, APP_NAME),
        "ApplicationCompany": (sz, PUBLISHER),
        "ApplicationIcon": (sz, f"{root / ICON_FILE},0"),
    })
    # FriendlyAppName: así el navegador pregunta "¿Abrir K-Snag?" y no "¿Abrir pythonw?".
    _set_values(PROTOCOL_KEY + r"\shell\open", {"FriendlyAppName": (sz, APP_NAME)})
    _set_values(PROTOCOL_KEY + r"\shell\open\command", {"": (sz, protocol_command(root))})


def register_uninstall_entry(root: Path, version: str) -> None:
    sz, esz, dword = winreg.REG_SZ, winreg.REG_EXPAND_SZ, winreg.REG_DWORD
    size_kb = min(dir_size(root) // 1024, 0xFFFFFFFF)
    _set_values(UNINSTALL_KEY, {
        "DisplayName": (sz, APP_NAME),
        "DisplayVersion": (sz, version),
        "Publisher": (sz, PUBLISHER),
        "DisplayIcon": (sz, str(root / ICON_FILE)),
        "InstallLocation": (sz, str(root)),
        "UninstallString": (esz, uninstall_string(root)),
        "QuietUninstallString": (esz, uninstall_string(root, quiet=True)),
        "NoModify": (dword, 1),
        "NoRepair": (dword, 1),
        "EstimatedSize": (dword, int(size_kb)),
        "InstallDate": (sz, time.strftime("%Y%m%d")),
        "URLInfoAbout": (sz, "https://ksnagapp.vercel.app"),
        "HelpLink": (sz, f"{WEB_BASE}/ayuda"),
    })


def unregister(root: Path) -> list[str]:
    """Quita las claves de HKCU, solo si apuntan a esta instalación. Devuelve lo que se quitó."""
    removed = []
    command = _read_value(PROTOCOL_KEY + r"\shell\open\command", "")
    if _key_exists(PROTOCOL_KEY):
        if command is None or _norm(root) in os.path.normcase(str(command)):
            _delete_tree(PROTOCOL_KEY)
            removed.append(f"HKCU\\{PROTOCOL_KEY}")
        else:
            say(f"  El protocolo {PROTOCOL}:// apunta a otra instalación; se deja como está.")
    location = _read_value(UNINSTALL_KEY, "InstallLocation")
    if _key_exists(UNINSTALL_KEY):
        if location is None or _norm(str(location)) == _norm(root):
            _delete_tree(UNINSTALL_KEY)
            removed.append(f"HKCU\\{UNINSTALL_KEY}")
        else:
            say("  La entrada de Aplicaciones apunta a otra instalación; se deja como está.")
    return removed


def notify_shell() -> None:
    with contextlib.suppress(Exception):
        ctypes.windll.shell32.SHChangeNotify(0x08000000, 0, None, None)  # SHCNE_ASSOCCHANGED


# ---------------------------------------------------------------------------------------------
# Procesos de la app


def running_processes(root: Path) -> list[tuple[int, str]]:
    """Procesos (pid, ruta) cuyo ejecutable está dentro de root, sin contar este proceso."""
    TH32CS_SNAPPROCESS = 0x2
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [
            ("dwSize", wintypes.DWORD),
            ("cntUsage", wintypes.DWORD),
            ("th32ProcessID", wintypes.DWORD),
            ("th32DefaultHeapID", ctypes.c_size_t),
            ("th32ModuleID", wintypes.DWORD),
            ("cntThreads", wintypes.DWORD),
            ("th32ParentProcessID", wintypes.DWORD),
            ("pcPriClassBase", ctypes.c_long),
            ("dwFlags", wintypes.DWORD),
            ("szExeFile", ctypes.c_wchar * 260),
        ]

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    k32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
    k32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.OpenProcess.restype = wintypes.HANDLE
    k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)
    ]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]

    snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if not snap or snap == wintypes.HANDLE(-1).value:
        return []
    found: list[tuple[int, str]] = []
    me = os.getpid()
    try:
        entry = PROCESSENTRY32W()
        entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        ok = k32.Process32FirstW(snap, ctypes.byref(entry))
        while ok:
            pid = entry.th32ProcessID
            if pid != me and entry.szExeFile.lower() in ("python.exe", "pythonw.exe"):
                handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
                if handle:
                    try:
                        buf = ctypes.create_unicode_buffer(32768)
                        size = wintypes.DWORD(len(buf))
                        queried = k32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size))
                        if queried and is_within(buf.value, root):
                            found.append((pid, buf.value))
                    finally:
                        k32.CloseHandle(handle)
            ok = k32.Process32NextW(snap, ctypes.byref(entry))
    finally:
        k32.CloseHandle(snap)
    return found


def stop_processes(pids: list[int], timeout: float = 10.0) -> bool:
    PROCESS_TERMINATE = 0x1
    SYNCHRONIZE = 0x100000
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.OpenProcess.restype = wintypes.HANDLE
    k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    k32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]
    handles = []
    for pid in pids:
        h = k32.OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, False, pid)
        if h:
            k32.TerminateProcess(h, 1)
            handles.append(h)
    deadline = time.monotonic() + timeout
    all_done = True
    for h in handles:
        remaining = max(0, int((deadline - time.monotonic()) * 1000))
        if k32.WaitForSingleObject(h, remaining) != 0:
            all_done = False
        k32.CloseHandle(h)
    return all_done


# ---------------------------------------------------------------------------------------------
# Comandos


def install_finish(
    root: Path,
    version: str,
    desktop: bool,
    register: bool = True,
    shortcut_dir: Path | None = None,
    desktop_dir_override: Path | None = None,
) -> dict:
    """Termina la instalación. Devuelve un resumen (para pruebas)."""
    root = Path(os.path.abspath(root))
    if not VERSION_RE.match(version or ""):
        raise InstallerError(f"Versión inválida: {version!r}")
    for required in (root / "launch.pyw", root / "runtime" / "pythonw.exe", root / "app" / version / "kdownloader"):
        if not required.exists():
            raise InstallerError(f"Falta {required}. Vuelve a ejecutar el instalador.")
    summary: dict = {"root": str(root), "version": version, "shortcuts": {}, "registry": [], "pruned": []}

    write_current(root, version)

    # Sin registro (pruebas) no se tocan los accesos directos reales: solo los de las carpetas indicadas.
    start_dir = shortcut_dir if shortcut_dir is not None else (start_menu_dir() if register else None)
    desk_dir = desktop_dir_override if desktop_dir_override is not None else (desktop_dir() if register else None)
    if start_dir is not None:
        sc = app_shortcut(root, start_dir)
        summary["shortcuts"][str(sc.path)] = create_shortcut(sc)
        say(f"Acceso directo: {sc.path}")
        remove_legacy_shortcut(start_dir)
    if desktop and desk_dir is not None:
        sc = app_shortcut(root, desk_dir)
        summary["shortcuts"][str(sc.path)] = create_shortcut(sc)
        say(f"Acceso directo en el escritorio: {sc.path}")
    if desk_dir is not None:
        remove_legacy_shortcut(desk_dir)

    if register:
        register_protocol(root)
        register_uninstall_entry(root, version)
        notify_shell()
        summary["registry"] = [f"HKCU\\{PROTOCOL_KEY}", f"HKCU\\{UNINSTALL_KEY}"]
        say(f"Registrado: {PROTOCOL}:// y la entrada en Aplicaciones")

    summary["pruned"] = prune_app_versions(root, version)
    if summary["pruned"]:
        say(f"Versiones anteriores quitadas: {', '.join(summary['pruned'])}")
    say(f"{APP_NAME} {version} quedó instalado en {root}")
    return summary


def schedule_root_removal(root: Path) -> None:
    """Borra root cuando este proceso termine (el runtime en uso no se puede borrar antes)."""
    system32 = Path(os.environ.get("SYSTEMROOT", r"C:\Windows")) / "System32"
    comspec = os.environ.get("COMSPEC") or str(system32 / "cmd.exe")
    rm = 'rmdir /s /q "%KD_RM_ROOT%" 2>nul'
    again = f'if exist "%KD_RM_ROOT%\\" (ping 127.0.0.1 -n 3 >nul & {rm})'
    command = f'"{comspec}" /d /c "ping 127.0.0.1 -n 3 >nul & {rm} & {again} & {again}"'
    env = dict(os.environ, KD_RM_ROOT=str(root))
    cwd = os.environ.get("TEMP") or str(root.parent)
    flags = CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP
    kwargs = dict(env=env, cwd=cwd, close_fds=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                  stderr=subprocess.DEVNULL)
    try:
        subprocess.Popen(command, creationflags=flags | CREATE_BREAKAWAY_FROM_JOB, **kwargs)
    except OSError:  # el Job actual no permite salir de él
        subprocess.Popen(command, creationflags=flags, **kwargs)


def uninstall(
    root: Path,
    keep_data: bool | None = None,
    quiet: bool = False,
    register: bool = True,
    explicit_root: bool = False,
    shortcut_dir: Path | None = None,
    desktop_dir_override: Path | None = None,
    schedule: bool = True,
) -> dict:
    """Desinstala. keep_data: None = preguntar (o conservar si quiet), True = conservar, False = borrar."""
    root = check_install_root(root, explicit_root)
    summary: dict = {"root": str(root), "shortcuts": [], "registry": [], "data_removed": False, "leftovers": []}

    procs = running_processes(root)
    if procs:
        if not quiet and not ask_yes_no(
            f"{APP_NAME} está abierto.\n\nPara desinstalarlo hay que cerrarlo. "
            "Las descargas en curso se detendrán.\n\n¿Cerrarlo ahora?",
            default_no=False,
        ):
            raise InstallerError("No se desinstaló nada: K-Snag sigue abierto.")
        say(f"Cerrando {APP_NAME} ({len(procs)} proceso(s))…")
        if not stop_processes([pid for pid, _ in procs]):
            raise InstallerError("No se pudo cerrar K-Snag. Ciérralo y vuelve a intentarlo.")

    start_dir = shortcut_dir if shortcut_dir is not None else (start_menu_dir() if register else None)
    desk_dir = desktop_dir_override if desktop_dir_override is not None else (desktop_dir() if register else None)
    for folder in (start_dir, desk_dir):
        if folder is None:
            continue
        for lnk in (folder / SHORTCUT_FILE, folder / LEGACY_SHORTCUT_FILE):
            if lnk.is_file() and not is_reparse_point(lnk):
                try:
                    lnk.unlink()
                    summary["shortcuts"].append(str(lnk))
                    say(f"Acceso directo quitado: {lnk}")
                except OSError as exc:
                    say(f"  No se pudo quitar {lnk}: {exc}")

    if register:
        summary["registry"] = unregister(root)
        notify_shell()
        for item in summary["registry"]:
            say(f"Registro quitado: {item}")

    data = check_data_dir(data_dir(), root)
    if data is not None:
        if keep_data is None:
            keep_data = True if quiet else not ask_yes_no(
                f"¿Quieres borrar también tus datos de {APP_NAME}?\n\n"
                "Son tus ajustes, el historial y los componentes descargados (yt-dlp, FFmpeg y Deno), en:\n"
                f"{data}\n\nTus videos descargados no se borran.",
            )
        if not keep_data:
            failures = remove_tree(data)
            summary["data_removed"] = not failures
            if failures:
                say(f"  No se pudieron borrar {len(failures)} archivo(s) de {data}")
            else:
                say(f"Datos borrados: {data}")

    # Se borra todo lo posible ahora (sin seguir uniones) y el resto —el runtime en uso y uninstall.cmd—
    # lo borra un cmd separado cuando este proceso termine.
    summary["leftovers"] = remove_tree(root, keep=(root / "runtime", root / "uninstall.cmd"))
    if schedule:
        os.chdir(os.environ.get("TEMP") or str(root.parent))
        schedule_root_removal(root)
    say(f"{APP_NAME} se desinstaló de {root}")
    if not quiet:
        kept = ""
        if data is not None and not summary["data_removed"]:
            kept = f"\n\nTus datos se conservaron en {data} por si vuelves a instalarlo."
        message_box(f"{APP_NAME} se desinstaló de tu equipo.{kept}", UNINSTALL_TITLE, MB_ICONINFORMATION)
    return summary


def _default_root() -> Path | None:
    env = os.environ.get("KD_INSTALL_ROOT")
    if env:
        return Path(env)
    exe = Path(sys.executable).resolve()
    if exe.parent.name.lower() == "runtime" and (exe.parent.parent / "launch.pyw").is_file():
        return exe.parent.parent
    return None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="launch.pyw", description=f"Instalación de {APP_NAME}")
    cmd = parser.add_mutually_exclusive_group(required=True)
    cmd.add_argument("--install-finish", action="store_true", help="termina la instalación")
    cmd.add_argument("--uninstall", action="store_true", help="desinstala")
    parser.add_argument("--version", help="versión instalada (con --install-finish)")
    parser.add_argument("--desktop", action="store_true", help="crea también el acceso directo en el escritorio")
    parser.add_argument("--no-registry", action="store_true",
                        help="no toca el registro ni accesos directos fuera de --shortcut-dir/--desktop-dir")
    data = parser.add_mutually_exclusive_group()
    data.add_argument("--keep-data", dest="keep_data", action="store_const", const=True, default=None)
    data.add_argument("--delete-data", dest="keep_data", action="store_const", const=False)
    parser.add_argument("--quiet", action="store_true", help="sin ventanas ni preguntas")
    parser.add_argument("--root", type=Path, help="carpeta de instalación (por defecto, la de launch.pyw)")
    parser.add_argument("--shortcut-dir", type=Path, help="carpeta para el acceso directo del menú Inicio")
    parser.add_argument("--desktop-dir", type=Path, help="carpeta para el acceso directo del escritorio")
    parser.add_argument("--no-schedule", action="store_true", help="no programa el borrado final de la carpeta")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(sys.argv[1:] if argv is None else argv)
    root = args.root or _default_root()
    if root is None:
        say("No se pudo determinar la carpeta de instalación (usa --root).")
        return 1
    try:
        if args.install_finish:
            if not args.version:
                say("Falta --version.")
                return 2
            install_finish(
                root, args.version, args.desktop, register=not args.no_registry,
                shortcut_dir=args.shortcut_dir, desktop_dir_override=args.desktop_dir,
            )
        else:
            uninstall(
                root, keep_data=args.keep_data, quiet=args.quiet, register=not args.no_registry,
                explicit_root=args.root is not None, shortcut_dir=args.shortcut_dir,
                desktop_dir_override=args.desktop_dir, schedule=not args.no_schedule,
            )
    except InstallerError as exc:
        say(f"Error: {exc}")
        if args.uninstall and not args.quiet:
            message_box(str(exc), UNINSTALL_TITLE, MB_ICONWARNING)
        return 1
    except Exception as exc:  # noqa: BLE001 - inesperado: que se vea en la consola del instalador
        say(f"Error inesperado: {type(exc).__name__}: {exc}")
        say(traceback.format_exc().rstrip())
        if args.uninstall and not args.quiet:
            message_box(
                f"No se pudo desinstalar {APP_NAME}.\n\n{type(exc).__name__}: {exc}", UNINSTALL_TITLE, MB_ICONERROR
            )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
