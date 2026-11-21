"""Lo que debe salir de importar en un proyecto la capa de un GeoPackage con un SC de la biblioteca, calculado con pyproj (S057/RF-16).

Genera dos ficheros, cada uno con una fila por punto de la capa de ejemplo: su X e Y en el SC de la capa y su Este y Norte en ETRS89 / UTM 30N
(EPSG:25830) según PROJ, con la operación más precisa para la zona.

- `fixtures/formatos/biblioteca/esperado-gpkg-3857.csv`: `no-disponible-3857.gpkg`, tres puntos de Madrid en EPSG:3857 (Web Mercator).
- `fixtures/formatos/biblioteca/esperado-shp-utm33.csv`: `sistemas/utm33.shp`, tres puntos en ETRS89 / UTM 33N (EPSG:25833).

Uso: uv run --project tools tools/formatos/generar_biblioteca.py
"""

from __future__ import annotations

import sqlite3
import struct
import sys
from pathlib import Path

import pyproj
import pyproj.aoi
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
CARPETA = ROOT / "fixtures" / "formatos" / "gpkg"
FICHERO = CARPETA / "no-disponible-3857.gpkg"
SALIDA = ROOT / "fixtures" / "formatos" / "biblioteca" / "esperado-gpkg-3857.csv"
SHP = ROOT / "fixtures" / "formatos" / "shp" / "sistemas" / "utm33.shp"
SALIDA_SHP = ROOT / "fixtures" / "formatos" / "biblioteca" / "esperado-shp-utm33.csv"
EPSG_CAPA = 3857
EPSG_CAPA_SHP = 25833
EPSG_PROYECTO = 25830
COLUMNAS = ["epsg_capa", "id", "nombre", "x_m", "y_m", "epsg_proyecto", "este_m", "norte_m"]


def num(valor: float) -> str:
    return repr(float(valor))


def puntos() -> list[tuple[int, str, float, float]]:
    """Id, nombre y (X, Y) de cada punto de la tabla `puntos`; el blob de GeoPackage termina con las dos coordenadas del punto."""
    with sqlite3.connect(FICHERO) as conexion:
        filas = conexion.execute("SELECT fid, geom, nombre FROM puntos ORDER BY fid").fetchall()
    return [(fid, nombre, *struct.unpack("<dd", blob[-16:])) for fid, blob, nombre in filas]


def puntos_shp() -> list[tuple[int, str, float, float]]:
    """Número, nombre y (X, Y) de cada punto del Shapefile: registros de tipo 1 tras la cabecera de 100 bytes (8 de cabecera y 20 de contenido)."""
    datos = SHP.read_bytes()
    filas = []
    posicion = 100
    while posicion < len(datos):
        numero, palabras = struct.unpack(">ii", datos[posicion : posicion + 8])
        x, y = struct.unpack("<dd", datos[posicion + 12 : posicion + 28])
        filas.append((numero, f"punto{numero}", x, y))
        posicion += 8 + palabras * 2
    return filas


def convertir(epsg_capa: int, puntos_capa: list[tuple[int, str, float, float]]) -> list[list[str]]:
    a_geograficas = Transformer.from_crs(epsg_capa, 4326, always_xy=True)
    filas = []
    for fid, nombre, x, y in puntos_capa:
        longitud, latitud = a_geograficas.transform(x, y)
        aoi = pyproj.aoi.AreaOfInterest(longitud, latitud, longitud, latitud)
        t = Transformer.from_crs(epsg_capa, EPSG_PROYECTO, always_xy=True, area_of_interest=aoi)
        este, norte = t.transform(x, y)
        filas.append([str(epsg_capa), str(fid), nombre, num(x), num(y), str(EPSG_PROYECTO), num(este), num(norte)])
    return filas


def generar() -> list[list[str]]:
    return convertir(EPSG_CAPA, puntos())


def generar_shp() -> list[list[str]]:
    return convertir(EPSG_CAPA_SHP, puntos_shp())


def a_csv(filas: list[list[str]], fichero: str = "no-disponible-3857.gpkg", epsg: int = EPSG_CAPA) -> str:
    cabecera = [
        f"# Importar en un proyecto en ETRS89 / UTM 30N la capa en EPSG:{epsg} de {fichero} (S057/RF-16). Generado por "
        "tools/formatos/generar_biblioteca.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        ",".join(COLUMNAS),
    ]
    return "\n".join(cabecera + [",".join(f) for f in filas]) + "\n"


def a_csv_shp(filas: list[list[str]]) -> str:
    return a_csv(filas, "sistemas/utm33.shp", EPSG_CAPA_SHP)


def main() -> int:
    SALIDA.write_text(a_csv(generar()), encoding="utf-8", newline="\n")
    SALIDA_SHP.write_text(a_csv_shp(generar_shp()), encoding="utf-8", newline="\n")
    print(f"escritos {SALIDA} y {SALIDA_SHP}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
