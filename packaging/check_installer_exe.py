"""Comprueba que Instalar-K-Snag.exe y Instalar-K-Snag.cmd instalen exactamente el mismo bundle.

    python packaging/check_installer_exe.py [dist]

Los dos llevan incrustados la versión, la URL, el SHA-256, el nombre y el tamaño de kd-bundle-<v>.zip: el .cmd
en su bloque $KD = @{...} y el .exe como AssemblyMetadata (packaging/installer/exe/Instalar-K-Snag.csproj).
También compara el SHA-256 con el kd-bundle-<v>.zip de dist/. Solo biblioteca estándar.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

KEYS = {
    "KdVersion": "Version",
    "KdBundleUrl": "Url",
    "KdBundleSha256": "Sha256",
    "KdBundleName": "Name",
    "KdBundleSize": "Size",
}


def _compressed_length(blob: bytes, pos: int) -> tuple[int, int]:
    """Longitud de un SerString de ECMA-335 (II.23.2): devuelve (longitud, bytes que ocupa)."""
    first = blob[pos]
    if first & 0x80 == 0:
        return first, 1
    if first & 0xC0 == 0x80:
        return ((first & 0x3F) << 8) | blob[pos + 1], 2
    return ((first & 0x1F) << 24) | (blob[pos + 1] << 16) | (blob[pos + 2] << 8) | blob[pos + 3], 4


def exe_metadata(blob: bytes) -> dict[str, str]:
    """Los AssemblyMetadata Kd* del .exe: cada uno es SerString(clave) seguido de SerString(valor)."""
    found: dict[str, str] = {}
    for key in KEYS:
        raw = key.encode("utf-8")
        pattern = re.escape(bytes([len(raw)]) + raw)
        values = set()
        for match in re.finditer(pattern, blob):
            pos = match.end()
            if pos >= len(blob):
                continue
            length, used = _compressed_length(blob, pos)
            start = pos + used
            value = blob[start:start + length]
            if len(value) == length:
                values.add(value.decode("utf-8", errors="replace"))
        if len(values) == 1:
            found[key] = values.pop()
        elif values:
            raise ValueError(f"{key} aparece con varios valores: {sorted(values)}")
    return found


def cmd_metadata(text: str) -> dict[str, str]:
    """Los valores del bloque $KD = @{ ... } del .cmd generado."""
    block = re.search(r"\$KD = @\{(.*?)\n\}", text, re.DOTALL)
    if not block:
        raise ValueError("No se encontró el bloque $KD en el .cmd")
    values = dict(re.findall(r"^\s*(\w+)\s*=\s*'([^']*)'", block.group(1), re.MULTILINE))
    return {key: values.get(name, "") for key, name in KEYS.items()}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(dist: Path) -> list[str]:
    exe_path = dist / "Instalar-K-Snag.exe"
    cmd_path = dist / "Instalar-K-Snag.cmd"
    problems = [f"falta {p}" for p in (exe_path, cmd_path) if not p.is_file()]
    if problems:
        return problems
    exe = exe_metadata(exe_path.read_bytes())
    cmd = cmd_metadata(cmd_path.read_text(encoding="ascii"))
    for key in KEYS:
        if key not in exe:
            problems.append(f"el .exe no tiene {key}")
        elif "{{" in exe[key]:
            problems.append(f"el .exe quedó sin procesar: {key} = {exe[key]!r}")
        elif exe[key] != cmd[key]:
            problems.append(f"{key}: .exe {exe[key]!r} y .cmd {cmd[key]!r} no coinciden")
    bundle = dist / exe.get("KdBundleName", "-")
    if not bundle.is_file():
        problems.append(f"falta el bundle {bundle}")
    else:
        if sha256(bundle) != exe.get("KdBundleSha256", "").lower():
            problems.append(f"el SHA-256 del .exe no es el de {bundle.name}")
        if str(bundle.stat().st_size) != exe.get("KdBundleSize"):
            problems.append(f"el tamaño del .exe no es el de {bundle.name}")
    return problems


def main(argv: list[str]) -> int:
    dist = Path(argv[1]) if len(argv) > 1 else Path("dist")
    problems = check(dist)
    if problems:
        print("Instalar-K-Snag.exe no coincide con el .cmd:", *problems, sep="\n  - ")
        return 1
    print("Instalar-K-Snag.exe y Instalar-K-Snag.cmd instalan el mismo bundle.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
