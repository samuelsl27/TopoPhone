"""Vectores de contraste del catálogo de SC de S034: tres puntos por SC proyectado, calculados con pyproj/PROJ (constitución nº 2).

Uso: uv run --project tools tools/geodesia/catalogo_vectores.py   (escribe `fixtures/geodesia/catalogo_<método>.csv`)

Cada fila: SC de EPSG, número de punto, latitud y longitud (grados, longitud de Greenwich, en el datum del SC) y Este y Norte en metros. La
referencia es el `Transformer` de PROJ desde el CRS geográfico del propio SC hasta el SC, con ejes Este/Norte (`always_xy`) y pasando a metros
con la unidad del eje. Los tres puntos caen dentro del ámbito de uso del SC, en las fracciones (0,5; 0,5), (0,25; 0,35) y (0,75; 0,65) de su
recuadro, de modo que son los mismos al regenerar. Los SC geográficos no tienen vectores: no proyectan nada.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import pyproj
from pyproj import CRS, Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalogo as c  # noqa: E402

OUT = c.ROOT / "fixtures" / "geodesia"
FRACCIONES = [(0.5, 0.5), (0.25, 0.35), (0.75, 0.65)]  # (de sur a norte, de oeste a este)
MAX_COORDENADA_M = 1e9


def puntos_de_prueba(ambito: str) -> list[tuple[float, float]]:
    """(latitud, longitud) de los tres puntos dentro del recuadro S,W,N,E; si cruza el antimeridiano (W > E) se cuenta hacia el este."""
    sur, oeste, norte, este = (float(v) for v in ambito.split(","))
    if este < oeste:
        este += 360.0
    puntos = []
    for fraccion_lat, fraccion_lon in FRACCIONES:
        longitud = oeste + (este - oeste) * fraccion_lon
        longitud = (longitud + 180.0) % 360.0 - 180.0
        puntos.append((round(sur + (norte - sur) * fraccion_lat, 6), round(longitud, 6)))
    return puntos


def _vectores_de(fila: list[str]) -> list[list[str]]:
    epsg = int(fila[c.CRS_EPSG])
    crs = CRS.from_epsg(epsg)
    geografico = crs.geodetic_crs
    factor_angulo = geografico.axis_info[0].unit_conversion_factor  # radianes por unidad del eje
    meridiano = c._grados(
        geografico.prime_meridian.longitude, geografico.prime_meridian.unit_name, geografico.prime_meridian.unit_conversion_factor or 1.0
    )
    transformador = Transformer.from_crs(geografico, crs, always_xy=True)
    unidad_m = float(fila[c.CRS_UNIDAD_M])
    filas = []
    for numero, (latitud, longitud) in enumerate(puntos_de_prueba(fila[c.CRS_AMBITO]), start=1):
        x = math.radians(longitud - meridiano) / factor_angulo
        y = math.radians(latitud) / factor_angulo
        este, norte = transformador.transform(x, y)
        if not (math.isfinite(este) and math.isfinite(norte)) or max(abs(este), abs(norte)) > MAX_COORDENADA_M:
            continue
        filas.append([str(epsg), str(numero), c.num(latitud), c.num(longitud), c.num(este * unidad_m), c.num(norte * unidad_m)])
    return filas


def generar_vectores(secciones: dict[str, list[list[str]]]) -> dict[str, list[list[str]]]:
    """Método del catálogo → filas de vectores de sus SC proyectados."""
    por_metodo: dict[str, list[list[str]]] = {}
    for fila in secciones["crs"]:
        if fila[c.CRS_TIPO] != "P" or not fila[c.CRS_AMBITO]:
            continue
        por_metodo.setdefault(fila[c.CRS_METODO], []).extend(_vectores_de(fila))
    return por_metodo


COLUMNAS_CSV = ["epsg", "punto", "latitud", "longitud", "este_m", "norte_m"]


def a_csv(metodo: str, filas: list[list[str]]) -> str:
    cabecera = [
        f"# Vectores del catálogo de SC de S034, método {metodo}. Generado por tools/geodesia/catalogo_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        ",".join(COLUMNAS_CSV),
    ]
    return "\n".join(cabecera + [",".join(f) for f in filas]) + "\n"


def escribir(vectores: dict[str, list[list[str]]], carpeta: Path = OUT) -> list[Path]:
    carpeta.mkdir(parents=True, exist_ok=True)
    rutas = []
    for metodo, filas in sorted(vectores.items()):
        ruta = carpeta / f"catalogo_{metodo}.csv"
        ruta.write_text(a_csv(metodo, filas), encoding="utf-8", newline="\n")
        rutas.append(ruta)
    return rutas


def main() -> int:
    secciones = c.generar()
    c.escribir(secciones)
    rutas = escribir(generar_vectores(secciones))
    print(f"{len(rutas)} ficheros de vectores")
    return 0


if __name__ == "__main__":
    sys.exit(main())
