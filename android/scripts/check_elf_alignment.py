# SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
# SPDX-License-Identifier: GPL-3.0-or-later
"""Revisa que las bibliotecas nativas de 64 bits carguen con páginas de 16 KB (Android 15+).

Mira los .so de lib/<abi>/ del APK (o jni/<abi>/ de un AAR) y también los ELF que van DENTRO de los
paquetes zip del motor (lib*.zip.so de youtubedl-android), que las herramientas de Google no revisan.
Un APK sin bibliotecas nativas pasa sin más. Las versiones de prueba sin motor solo traen la de Compose
(libandroidx.graphics.path.so, de androidx.graphics:graphics-path), que ya está alineada a 16 KB.

    python3 android/scripts/check_elf_alignment.py [--fail-on-pending] [--as-one] ARCHIVO...

Todo ELF de 64 bits con un PT_LOAD alineado a menos de 16 KB es un error, salvo que esté en
elf_alignment_allowlist.txt (llega con el motor) con una de estas disposiciones, que el script COMPRUEBA:
  unused                  nadie lo carga: no puede aparecer en el cierre de DT_NEEDED de los ejecutables
                          (libffmpeg.so, libffprobe.so, libpython.so, libqjs.so) con el LD_LIBRARY_PATH
                          del motor (python y luego ffmpeg).
  replaced_by=lib/<abi>/X el APK trae un reemplazo propio en lib/<abi>/ (libkd_*.so) que el motor pone
                          primero en LD_LIBRARY_PATH: debe ser ELF64, estar alineado a 16 KB, tener el
                          mismo DT_SONAME y exportar todos los símbolos que sus usuarios toman del original.
  pending=<hito>          conocido y sin arreglar todavía: aviso; error con --fail-on-pending (versiones
                          estables).
--as-one trata todos los archivos como un solo paquete (p. ej. los AAR library + ffmpeg en las pruebas);
--allowlist=RUTA usa otra lista (pruebas).
Solo biblioteca estándar. Lee los archivos como datos: no ejecuta nada.
"""

from __future__ import annotations

import io
import posixpath
import stat
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

PAGE_16K = 16384
ABIS_64 = ("arm64-v8a", "x86_64")
ROOTS = ("libffmpeg.so", "libffprobe.so", "libpython.so", "libqjs.so")
SEARCH = ("libpython.zip.so", "libffmpeg.zip.so", "libaria2c.zip.so")  # orden de LD_LIBRARY_PATH (YoutubeDL.kt)
ALLOWLIST = Path(__file__).with_name("elf_alignment_allowlist.txt")


@dataclass
class Elf:
    bits: int
    min_align: int
    needed: list[str] = field(default_factory=list)
    soname: str | None = None
    defined: set[str] = field(default_factory=set)
    undefined: set[str] = field(default_factory=set)
    has_symbols: bool = False


def parse_elf(data: bytes) -> Elf | None:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    e = "<" if data[5] == 1 else ">"
    try:
        if is64:
            phoff, shoff = struct.unpack_from(e + "QQ", data, 0x20)
            phentsize, phnum, shentsize, shnum = struct.unpack_from(e + "HHHH", data, 0x36)
            ph_fmt, dyn_fmt, dyn_size, sym_size = e + "IIQQQQQQ", e + "qQ", 16, 24
        else:
            phoff, shoff = struct.unpack_from(e + "II", data, 0x1C)
            phentsize, phnum, shentsize, shnum = struct.unpack_from(e + "HHHH", data, 0x2A)
            ph_fmt, dyn_fmt, dyn_size, sym_size = e + "IIIIIIII", e + "iI", 8, 16
        phdrs = []
        for i in range(phnum):
            f = struct.unpack_from(ph_fmt, data, phoff + i * phentsize)
            # ELF64: type flags offset vaddr paddr filesz memsz align
            # ELF32: type offset vaddr paddr filesz memsz flags align
            p = dict(type=f[0], offset=f[2], vaddr=f[3], filesz=f[5], align=f[7]) if is64 else \
                dict(type=f[0], offset=f[1], vaddr=f[2], filesz=f[4], align=f[7])
            phdrs.append(p)
        loads = [p for p in phdrs if p["type"] == 1]
        elf = Elf(bits=64 if is64 else 32, min_align=min((p["align"] for p in loads), default=0))

        def v2o(vaddr: int) -> int | None:
            for p in loads:
                if p["vaddr"] <= vaddr < p["vaddr"] + p["filesz"]:
                    return vaddr - p["vaddr"] + p["offset"]
            return None

        def cstr(off: int) -> str:
            return data[off:data.index(b"\x00", off)].decode("latin1")

        for d in (p for p in phdrs if p["type"] == 2):  # PT_DYNAMIC
            entries = []
            for j in range(0, d["filesz"], dyn_size):
                tag, val = struct.unpack_from(dyn_fmt, data, d["offset"] + j)
                if tag == 0:
                    break
                entries.append((tag, val))
            strtab = next((v for t, v in entries if t == 5), None)
            base = v2o(strtab) if strtab is not None else None
            if base is not None:
                elf.needed = [cstr(base + v) for t, v in entries if t == 1]
                elf.soname = next((cstr(base + v) for t, v in entries if t == 14), None)
        # Símbolos dinámicos: sección SHT_DYNSYM (11) y su tabla de nombres (sh_link).
        sections = [struct.unpack_from(e + ("IIQQQQIIQQ" if is64 else "IIIIIIIIII"), data, shoff + i * shentsize)
                    for i in range(shnum)] if shoff and shnum else []
        for sh in sections:
            if sh[1] != 11:
                continue
            sym_off, sym_sz, link = sh[4], sh[5], sh[6]
            str_off = sections[link][4]
            for k in range(1, sym_sz // sym_size):
                o = sym_off + k * sym_size
                if is64:
                    st_name, st_info, _, st_shndx = struct.unpack_from(e + "IBBH", data, o)
                else:
                    st_name, = struct.unpack_from(e + "I", data, o)
                    st_info, _, st_shndx = struct.unpack_from(e + "BBH", data, o + 12)
                name = cstr(str_off + st_name) if st_name else ""
                bind = st_info >> 4
                if not name or bind not in (1, 2):  # solo GLOBAL y WEAK
                    continue
                (elf.undefined if st_shndx == 0 else elf.defined).add(name)
            elf.has_symbols = True
        return elf
    except (struct.error, ValueError, IndexError):
        return None


@dataclass
class Package:
    name: str
    top: dict[str, dict[str, bytes]] = field(default_factory=dict)        # abi -> nombre -> bytes
    payload: dict[str, dict[str, bytes]] = field(default_factory=dict)    # "abi:libX.zip.so" -> miembro -> bytes
    links: dict[str, dict[str, str]] = field(default_factory=dict)        # "abi:libX.zip.so" -> miembro -> destino


def load(paths: list[Path], name: str) -> Package:
    pkg = Package(name)
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                parts = info.filename.split("/")
                if len(parts) != 3 or parts[0] not in ("lib", "jni") or parts[1] not in ABIS_64 or not parts[2]:
                    continue
                abi, fname = parts[1], parts[2]
                data = archive.read(info)
                pkg.top.setdefault(abi, {})[fname] = data
                if data[:4] == b"PK\x03\x04":
                    key = f"{abi}:{fname}"
                    members, links = pkg.payload.setdefault(key, {}), pkg.links.setdefault(key, {})
                    with zipfile.ZipFile(io.BytesIO(data)) as inner:
                        for m in inner.infolist():
                            if m.is_dir():
                                continue
                            if stat.S_ISLNK(m.external_attr >> 16):
                                links[m.filename] = inner.read(m).decode("utf-8", "replace")
                            else:
                                members[m.filename] = inner.read(m)
    return pkg


def resolve(pkg: Package, abi: str, needed: str) -> str | None:
    """Clave "abi:libX.zip.so!usr/lib/<nombre>" del archivo que cargaría el linker, o None (biblioteca del sistema)."""
    for payload in SEARCH:
        key = f"{abi}:{payload}"
        if key not in pkg.payload:
            continue
        member = f"usr/lib/{needed}"
        for _ in range(10):  # seguir enlaces simbólicos dentro del paquete
            if member in pkg.payload[key]:
                return f"{key}!{member}"
            target = pkg.links[key].get(member)
            if target is None:
                break
            member = posixpath.normpath(posixpath.join(posixpath.dirname(member), target))
    return None


def blob(pkg: Package, key: str) -> bytes | None:
    abi_file, _, member = key.partition("!")
    abi, _, fname = abi_file.partition(":")
    return pkg.payload.get(abi_file, {}).get(member) if member else pkg.top.get(abi, {}).get(fname)


def closure(pkg: Package, abi: str) -> dict[str, Elf]:
    seen: dict[str, Elf] = {}
    todo = [f"{abi}:{r}" for r in ROOTS if r in pkg.top.get(abi, {})]
    while todo:
        key = todo.pop()
        if key in seen:
            continue
        data = blob(pkg, key)
        elf = parse_elf(data) if data else None
        if elf is None:
            continue
        seen[key] = elf
        todo += [k for k in (resolve(pkg, abi, n) for n in elf.needed) if k and k not in seen]
    return seen


def findings(pkg: Package) -> dict[str, int]:
    bad = {}
    for abi, files in pkg.top.items():
        for fname, data in files.items():
            elf = parse_elf(data)
            if elf and elf.bits == 64 and elf.min_align < PAGE_16K:
                bad[f"{abi}:{fname}"] = elf.min_align
    for key, members in pkg.payload.items():
        for member, data in members.items():
            elf = parse_elf(data)
            if elf and elf.bits == 64 and elf.min_align < PAGE_16K:
                bad[f"{key}!{member}"] = elf.min_align
    return bad


def read_allowlist(path: Path) -> dict[str, str]:
    rules = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.split("#", 1)[0].strip()
            if line:
                key, _, disposition = line.partition(" ")
                rules[key] = disposition.strip()
    return rules


def check(pkg: Package, rules: dict[str, str], fail_on_pending: bool) -> int:
    status = 0

    def error(msg: str) -> None:
        nonlocal status
        print(f"::error file={pkg.name}::{msg}")
        status = 1

    bad = findings(pkg)
    reach = {abi: closure(pkg, abi) for abi in pkg.top}
    for key, align in sorted(bad.items()):
        abi = key.split(":", 1)[0]
        disposition = rules.get(key)
        if disposition is None:
            error(f"{key} alineado a {align} B: no carga con páginas de 16 KB (no está en {ALLOWLIST.name})")
        elif disposition == "unused":
            if key in reach[abi]:
                error(f"{key} está marcado 'unused' pero ahora SÍ se carga (cierre de DT_NEEDED): hay que reemplazarlo")
            else:
                print(f"{key}: {align} B, sin uso (nadie lo carga): OK")
        elif disposition.startswith("pending="):
            msg = f"{key} alineado a {align} B, pendiente ({disposition[8:]}): no carga con páginas de 16 KB"
            if fail_on_pending:
                error(msg + ". Las versiones estables no salen con pendientes (docs/release-android.md).")
            else:
                print(f"::warning file={pkg.name}::{msg}")
        elif disposition.startswith("replaced_by="):
            check_replacement(pkg, key, disposition[12:], reach[abi], error)
        else:
            error(f"{key}: disposición desconocida {disposition!r} en {ALLOWLIST.name}")
    for key in sorted(set(rules) - set(bad)):
        if key.split(":", 1)[0] in pkg.top:
            print(f"::notice file={pkg.name}::{key} ya está alineado o no existe: borra su línea de {ALLOWLIST.name}")
    if not pkg.top:
        print(f"{pkg.name}: sin bibliotecas nativas de 64 bits: OK")
    else:
        print(f"{pkg.name}: {len(bad)} ELF de 64 bits por debajo de 16 KB, "
              + ", ".join(f"{abi}: {len(r)} en el cierre" for abi, r in sorted(reach.items())))
    return status


def check_replacement(pkg: Package, key: str, path: str, reach: dict[str, Elf], error) -> None:
    """Valida un reemplazo replaced_by=lib/<abi>/X; los problemas se informan con error()."""
    parts = path.split("/")
    original = parse_elf(blob(pkg, key) or b"")
    data = pkg.top.get(parts[1], {}).get(parts[2]) if len(parts) == 3 and parts[0] == "lib" else None
    repl = parse_elf(data) if data else None
    if repl is None or original is None:
        error(f"{key}: falta el reemplazo {path} en el APK (contrato M5)")
        return
    problems = []
    if repl.bits != 64 or repl.min_align < PAGE_16K:
        problems.append(f"alineado a {repl.min_align} B")
    if repl.soname != original.soname:
        problems.append(f"DT_SONAME {repl.soname!r}, el original es {original.soname!r}")
    if not repl.has_symbols or not original.has_symbols:
        problems.append("sin tabla de símbolos dinámicos para comparar")
    else:
        users = [k for k, elf in reach.items() if original.soname in elf.needed]
        wanted = set().union(*(reach[k].undefined for k in users)) & original.defined if users else set()
        missing = sorted(wanted - repl.defined)
        if missing:
            used_by = ", ".join(u.split("!")[-1] for u in users)
            problems.append(f"no exporta {len(missing)} símbolo(s) que usan {used_by}: "
                            + ", ".join(missing[:8]) + (" …" if len(missing) > 8 else ""))
    if problems:
        error(f"{key}: el reemplazo {path} no sirve: " + "; ".join(problems))
    else:
        print(f"{key}: reemplazado por {path} (16 KB, mismo SONAME, símbolos completos): OK")


def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # consola de Windows (cp1252)
    fail_on_pending = "--fail-on-pending" in argv
    as_one = "--as-one" in argv
    paths = [Path(a) for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__)
        return 2
    missing = [p for p in paths if not p.is_file()]
    if missing:
        for p in missing:
            print(f"::error::No existe {p} (¿se compiló el APK?)")
        return 2
    custom = next((a.split("=", 1)[1] for a in argv if a.startswith("--allowlist=")), None)
    rules = read_allowlist(Path(custom) if custom else ALLOWLIST)
    groups = [(paths, "+".join(p.name for p in paths))] if as_one else [([p], p.name) for p in paths]
    status = 0
    for group, name in groups:
        status |= check(load(group, name), rules, fail_on_pending)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
