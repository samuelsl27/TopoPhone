"""Definiciones WKT1 de los SC del catálogo de S034 para exportar (S057/RF-22, RF-23, RF-26), generadas con pyproj/PROJ (ADR-3).

Uso: uv run --project tools tools/geodesia/catalogo_wkt.py   (escribe `core/geodesy/src/main/resources/catalog/crs_wkt.tsv.gz`)

Sale del catálogo ya generado (`crs.tsv.gz`): una línea por SC, ordenada por EPSG y sin marcas de tiempo (mismo fichero al regenerarlo):
`epsg<TAB>wkt1_gdal<TAB>wkt1_esri`. La primera es la de `gpkg_spatial_ref_sys` y la segunda la del `.prj`. Si PROJ no puede expresar el SC en
WKT1, esa columna va vacía y la exportación avisa (S057/RF-26). No necesita red.
"""

from __future__ import annotations

import gzip
import sys
import warnings
from pathlib import Path

from pyproj import CRS
from pyproj.enums import WktVersion

ROOT = Path(__file__).resolve().parents[2]
CATALOGO = ROOT / "core" / "geodesy" / "src" / "main" / "resources" / "catalog" / "crs.tsv.gz"
SALIDA = ROOT / "core" / "geodesy" / "src" / "main" / "resources" / "catalog" / "crs_wkt.tsv.gz"
TAMANO_MAXIMO = 1_000_000  # S057/RNF: las definiciones añaden como mucho 1 MB al APK


def epsg_del_catalogo(ruta: Path = CATALOGO) -> list[int]:
    """Los códigos EPSG de la sección `#crs` del catálogo, ordenados."""
    codigos: list[int] = []
    seccion = ""
    with gzip.open(ruta, "rt", encoding="utf-8") as f:
        for linea in f:
            linea = linea.rstrip("\n")
            if linea.startswith("#") and not linea.startswith("##"):
                seccion = linea[1:]
            elif seccion == "crs" and linea:
                codigos.append(int(linea.split("\t", 1)[0]))
    return sorted(codigos)


def _wkt(crs: CRS, version: WktVersion) -> str:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            texto = crs.to_wkt(version) or ""
        except Exception:  # noqa: BLE001 - PROJ lanza ProjError o similares si no puede expresarlo
            return ""
    return " ".join(texto.split()) if "\t" in texto or "\n" in texto else texto


def definiciones(codigos: list[int]) -> list[list[str]]:
    filas = []
    for codigo in codigos:
        crs = CRS.from_epsg(codigo)
        filas.append([str(codigo), _wkt(crs, WktVersion.WKT1_GDAL), _wkt(crs, WktVersion.WKT1_ESRI)])
    return filas


def a_texto(filas: list[list[str]]) -> str:
    return "\n".join("\t".join(f) for f in filas) + "\n"


def escribir(filas: list[list[str]], destino: Path = SALIDA) -> int:
    destino.parent.mkdir(parents=True, exist_ok=True)
    with destino.open("wb") as bruto, gzip.GzipFile(filename="", mode="wb", fileobj=bruto, compresslevel=9, mtime=0) as comprimido:
        comprimido.write(a_texto(filas).encode("utf-8"))
    return destino.stat().st_size


def main() -> int:
    filas = definiciones(epsg_del_catalogo())
    tamano = escribir(filas)
    vacias = sum(1 for f in filas if not f[1])
    print(f"{len(filas)} SC, {vacias} sin WKT1 de GeoPackage: {tamano / 1e6:.2f} MB")
    return 0 if tamano <= TAMANO_MAXIMO else 1


if __name__ == "__main__":
    sys.exit(main())
