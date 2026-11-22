"""Vectores de contraste de S035/RF-23: el WKT1 de un SC personalizado, escrito aqui con la plantilla que debe reproducir la app, y comprobado contra pyproj.

Uso: uv run --project tools tools/geodesia/personalizado_wkt.py   (escribe `fixtures/geodesia/personalizado_wkt.csv`)

Cada fila: caso, el WKT1 que escribe la app (una sola linea) y, de cada punto de `personalizado.csv`, su Este y Norte, y la longitud y la latitud (grados)
que da PROJ **leyendo ese WKT** y llevando el punto a WGS 84. La app comprueba que su WKT es identico al de aqui (`CustomCrsWktTest`) y `test_personalizado_wkt.py`
comprueba que PROJ, leyendolo, da lo mismo que el SC definido por parametros sueltos de `personalizado.csv` (hasta 1 mm).

Plantilla (la misma que `CustomCrsWkt.kt`): PROJCS[nombre, GEOGCS[nombre, DATUM[nombre, SPHEROID[nombre, a, 1/f], TOWGS84[...]], PRIMEM, UNIT["degree"]], PROJECTION,
PARAMETER..., UNIT]; TOWGS84 en posicion vectorial (un Helmert de marco de coordenadas lleva las rotaciones con el signo cambiado).
"""

from __future__ import annotations

import sys
from pathlib import Path

import pyproj
from pyproj import CRS, Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import personalizado_vectores as v  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SALIDA = ROOT / "fixtures" / "geodesia" / "personalizado_wkt.csv"
NOMBRE_DATUM = "Personalizado"
GRADO = 0.0174532925199433

# metodo de la app -> (PROJECTION de WKT1, [(PARAMETER de WKT1, clave de la app)])
PROYECCIONES = {
    "tm": ("Transverse_Mercator", [("latitude_of_origin", "latitudeOfOriginDeg"), ("central_meridian", "longitudeOfOriginDeg"), ("scale_factor", "scaleFactor"), ("false_easting", "falseEasting"), ("false_northing", "falseNorthing")]),
    "lcc1": ("Lambert_Conformal_Conic_1SP", [("latitude_of_origin", "latitudeOfOriginDeg"), ("central_meridian", "longitudeOfOriginDeg"), ("scale_factor", "scaleFactor"), ("false_easting", "falseEasting"), ("false_northing", "falseNorthing")]),
    "lcc2": ("Lambert_Conformal_Conic_2SP", [("standard_parallel_1", "standardParallel1Deg"), ("standard_parallel_2", "standardParallel2Deg"), ("latitude_of_origin", "latitudeOfFalseOriginDeg"), ("central_meridian", "longitudeOfFalseOriginDeg"), ("false_easting", "eastingAtFalseOrigin"), ("false_northing", "northingAtFalseOrigin")]),
    "stereo": ("Oblique_Stereographic", [("latitude_of_origin", "latitudeOfOriginDeg"), ("central_meridian", "longitudeOfOriginDeg"), ("scale_factor", "scaleFactor"), ("false_easting", "falseEasting"), ("false_northing", "falseNorthing")]),
    "omerca": ("Hotine_Oblique_Mercator", [("latitude_of_center", "latitudeOfCentreDeg"), ("longitude_of_center", "longitudeOfCentreDeg"), ("azimuth", "azimuthDeg"), ("rectified_grid_angle", "rectifiedGridAngleDeg"), ("scale_factor", "scaleFactor"), ("false_easting", "falseEasting"), ("false_northing", "falseNorthing")]),
    "omercb": ("Hotine_Oblique_Mercator_Azimuth_Center", [("latitude_of_center", "latitudeOfCentreDeg"), ("longitude_of_center", "longitudeOfCentreDeg"), ("azimuth", "azimuthDeg"), ("rectified_grid_angle", "rectifiedGridAngleDeg"), ("scale_factor", "scaleFactor"), ("false_easting", "eastingAtCentre"), ("false_northing", "northingAtCentre")]),
}
COLUMNAS = ["caso", "wkt", "punto", "este_m", "norte_m", "longitud", "latitud"]


def num(valor: float) -> str:
    return repr(float(valor))


def wkt(caso: str, metodo: str, parametros: dict[str, float], elipsoide: tuple[float, float], tipo: str, h: tuple[float, ...]) -> str:
    """El WKT1 de un SC personalizado con la plantilla de la app."""
    a, rf = elipsoide
    towgs84 = ""
    if tipo != "nula":
        rotaciones = [0.0 - x for x in h[3:6]] if tipo == "helmert7cf" else list(h[3:6])
        escala = h[6]
        if tipo == "helmert3":
            rotaciones, escala = [0.0, 0.0, 0.0], 0.0
        towgs84 = ",TOWGS84[" + ",".join(num(x) for x in [h[0], h[1], h[2], *rotaciones, escala]) + "]"
    else:
        towgs84 = ",TOWGS84[" + ",".join(num(x) for x in [0, 0, 0, 0, 0, 0, 0]) + "]"
    geogcs = (
        f'GEOGCS["{NOMBRE_DATUM}",DATUM["{NOMBRE_DATUM}",SPHEROID["{NOMBRE_DATUM}",{num(a)},{num(rf)}]{towgs84}],'
        f'PRIMEM["Greenwich",0.0],UNIT["degree",{num(GRADO)}]]'
    )
    nombre_proyeccion, parametros_wkt = PROYECCIONES[metodo]
    partes = ",".join(f'PARAMETER["{nombre}",{num(parametros[clave])}]' for nombre, clave in parametros_wkt)
    return f'PROJCS["{caso}",{geogcs},PROJECTION["{nombre_proyeccion}"],{partes},UNIT["metre",1.0]]'


def generar() -> list[list[str]]:
    filas: list[list[str]] = []
    for caso, metodo, p, elipsoide, tipo, h, puntos in v.CASOS:
        texto = wkt(caso, metodo, p, elipsoide, tipo, h)
        t = Transformer.from_crs(CRS.from_wkt(texto), CRS.from_epsg(4326), always_xy=True)
        for numero, (e, n) in enumerate(puntos, start=1):
            lon, lat = t.transform(e, n)
            filas.append([caso, texto, str(numero), num(e), num(n), num(lon), num(lat)])
    return filas


def a_csv(filas: list[list[str]]) -> str:
    cabecera = [
        "# WKT1 de SC personalizados de S035/RF-23 y lo que da PROJ leyendolos. Generado por tools/geodesia/personalizado_wkt.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        "\t".join(COLUMNAS),
    ]
    return "\n".join(cabecera + ["\t".join(f) for f in filas]) + "\n"


def main() -> int:
    SALIDA.write_text(a_csv(generar()), encoding="utf-8", newline="\n")
    print(f"{len(v.CASOS)} casos escritos en {SALIDA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
