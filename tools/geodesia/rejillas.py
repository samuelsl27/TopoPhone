"""Rejillas geodésicas que TopoPhone incluye en la app (ADR-4), con su origen y su huella SHA-256.

Uso: uv run --project tools tools/geodesia/rejillas.py [--comprobar]
Sin opciones descarga las que falten del CDN de PROJ; con --comprobar solo verifica las presentes.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "core" / "geodesy" / "src" / "main" / "resources"


@dataclass(frozen=True)
class Grid:
    name: str
    sha256: str
    attribution: str
    folder: str = "geoid"  # "geoid" (modelos de geoide) o "shift" (desplazamientos horizontales)

    @property
    def url(self) -> str:
        return f"https://cdn.proj.org/{self.name}"

    @property
    def path(self) -> Path:
        return RESOURCES / self.folder / self.name


GRIDS = [
    Grid(
        name="es_ign_egm08-rednap.tif",
        sha256="d1758a580c2acc7cddcb814c2aebe1e5ab83f74dfaed8c1a885355bb3f85df58",
        attribution="EGM08-REDNAP (Península y Baleares) © Instituto Geográfico Nacional, CC-BY 4.0",
    ),
    Grid(
        name="es_ign_egm08-rednap-canarias.tif",
        sha256="ef545116102c73860fe364061b75f17caf15431a992dc9b3ed2fbe250619373f",
        attribution="EGM08-REDNAP (Canarias) © Instituto Geográfico Nacional, CC-BY 4.0",
    ),
    Grid(
        name="es_ign_SPED2ETV2.tif",
        sha256="61896f5d74bdc7c1d5850839ae743b08e19f9a627e8febb4ac93353ded835961",
        attribution="ED50 a ETRS89 (SPED2ETV2, Península y Baleares) © Instituto Geográfico Nacional, CC-BY 4.0",
        folder="shift",
    ),
]

GEOID_PENINSULA, GEOID_CANARIAS, ED50_SHIFT = GRIDS


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure(grid: Grid, download: bool) -> None:
    if not grid.path.exists():
        if not download:
            raise SystemExit(f"Falta {grid.path}")
        grid.path.parent.mkdir(parents=True, exist_ok=True)
        print(f"Descargando {grid.url}")
        with urllib.request.urlopen(grid.url, timeout=60) as response:
            grid.path.write_bytes(response.read())
    actual = sha256_of(grid.path)
    if actual != grid.sha256:
        raise SystemExit(f"SHA-256 de {grid.name} incorrecto: {actual} (esperado {grid.sha256})")
    print(f"{grid.name}: SHA-256 correcto")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--comprobar", action="store_true", help="solo verificar, sin descargar")
    args = parser.parse_args(argv)
    for grid in GRIDS:
        ensure(grid, download=not args.comprobar)
    return 0


if __name__ == "__main__":
    sys.exit(main())
