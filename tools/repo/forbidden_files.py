#!/usr/bin/env python3
"""Ficheros que nunca pueden entrar en el repositorio público de TopoPhone (S001/RF-29, RNF-T14).

Revisa los ficheros versionados (`git ls-files`, incluidos los añadidos al índice) y falla si hay:
- documentos PDF (manuales y normas de terceros: solo en local),
- almacenes de claves o claves privadas por extensión,
- ficheros de contraseñas o de configuración local por nombre,
- cualquier fichero de texto que contenga una clave privada en formato PEM.

Uso: uv run --project tools tools/repo/forbidden_files.py [--root RUTA]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

FORBIDDEN_EXTENSIONS = {".pdf", ".jks", ".keystore", ".bks", ".p12", ".pfx", ".pem", ".key"}
FORBIDDEN_NAMES = {"keystore.properties", "local.properties", "google-services.json", ".env"}
PRIVATE_KEY = re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")
MAX_SCANNED_BYTES = 5 * 1024 * 1024


def tracked_files(root: Path) -> list[str]:
    output = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name for name in output.decode("utf-8").split("\0") if name)


def reason_for(root: Path, relative: str) -> str | None:
    path = PurePosixPath(relative)
    extension = path.suffix.lower()
    if extension in FORBIDDEN_EXTENSIONS:
        return f"extensión prohibida ({extension})"
    if path.name.lower() in FORBIDDEN_NAMES:
        return "fichero de claves o configuración local"
    file = root / relative
    if file.is_file() and file.stat().st_size <= MAX_SCANNED_BYTES and PRIVATE_KEY.search(file.read_bytes()):
        return "contiene una clave privada"
    return None


def find_forbidden(root: Path) -> list[tuple[str, str]]:
    findings = []
    for relative in tracked_files(root):
        reason = reason_for(root, relative)
        if reason:
            findings.append((relative, reason))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Comprueba que el repositorio no contiene ficheros prohibidos.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2], help="raíz del repositorio")
    args = parser.parse_args(argv)

    try:
        sys.stdout.reconfigure(encoding="utf-8")  # consola de Windows
    except (AttributeError, ValueError):
        pass

    findings = find_forbidden(args.root.resolve())
    if not findings:
        print("Sin ficheros prohibidos.")
        return 0
    print("Ficheros prohibidos en el repositorio (S001/RF-29):")
    for relative, reason in findings:
        print(f"  - {relative}: {reason}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
