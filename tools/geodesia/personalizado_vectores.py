"""Vectores de contraste de S035: un SC personalizado definido con sus parametros sueltos (elipsoide, proyeccion, transformacion de datum), calculado con
pyproj/PROJ (constitucion n 2). No pasa por el catalogo: cada fila lleva los numeros que escribiria el usuario en el editor.

Uso: uv run --project tools tools/geodesia/personalizado_vectores.py   (escribe `fixtures/geodesia/personalizado.csv`)

Cada fila: caso, metodo (`tm`, `lcc1`, `lcc2`, `stereo`), parametros de la proyeccion (`nombre=valor;...`, grados y metros), semieje y inverso del
aplanamiento del elipsoide del SC, tipo de transformacion (`nula`, `helmert3`, `helmert7pv`, `helmert7cf`), sus siete parametros (tx, ty, tz en m;
rx, ry, rz en segundos de arco; escala en ppm), Este y Norte (m) de un punto, y la longitud y la latitud (grados) en GRS80 (ETRS89) que da PROJ.
Hay tres puntos por caso.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pyproj
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
SALIDA = ROOT / "fixtures" / "geodesia" / "personalizado.csv"

GRS80 = "+a=6378137 +rf=298.257222101"
GRS80_ELIPSOIDE = (6378137.0, 298.257222101)
INTERNACIONAL = (6378388.0, 297.0)
BESSEL = (6377397.155, 299.1528128)
KRASSOWSKY = (6378245.0, 298.3)

TM_SIN_CAMBIO = {
    "latitudeOfOriginDeg": 0.0,
    "longitudeOfOriginDeg": -3.0,
    "scaleFactor": 0.9996,
    "falseEasting": 500000.0,
    "falseNorthing": 0.0,
}
LCC2 = {
    "latitudeOfFalseOriginDeg": 46.5,
    "longitudeOfFalseOriginDeg": 3.0,
    "standardParallel1Deg": 49.0,
    "standardParallel2Deg": 44.0,
    "eastingAtFalseOrigin": 700000.0,
    "northingAtFalseOrigin": 6600000.0,
}
LCC1 = {
    "latitudeOfOriginDeg": 34.0,
    "longitudeOfOriginDeg": 37.0,
    "scaleFactor": 0.9996256,
    "falseEasting": 300000.0,
    "falseNorthing": 300000.0,
}
TM24 = {
    "latitudeOfOriginDeg": 0.0,
    "longitudeOfOriginDeg": 24.0,
    "scaleFactor": 0.9996,
    "falseEasting": 500000.0,
    "falseNorthing": 0.0,
}
TM9 = dict(TM24, longitudeOfOriginDeg=9.0)
STERE = {
    "latitudeOfOriginDeg": 46.0,
    "longitudeOfOriginDeg": 25.0,
    "scaleFactor": 0.99975,
    "falseEasting": 500000.0,
    "falseNorthing": 500000.0,
}
SIN_HELMERT = (0, 0, 0, 0, 0, 0, 0)

# (caso, metodo, parametros, elipsoide (a, 1/f), transformacion, siete parametros, [(E, N)])
CASOS = [
    ("tm_sin_cambio", "tm", TM_SIN_CAMBIO, GRS80_ELIPSOIDE, "nula", SIN_HELMERT, [(440000.0, 4474000.0), (350000.0, 4150000.0), (700000.0, 4800000.0)]),
    ("lcc2_sin_cambio", "lcc2", LCC2, GRS80_ELIPSOIDE, "nula", SIN_HELMERT, [(700000.0, 6600000.0), (500000.0, 6200000.0), (900000.0, 6900000.0)]),
    ("lcc1_helmert3", "lcc1", LCC1, INTERNACIONAL, "helmert3", (-190.421, 8.532, 238.69, 0, 0, 0, 0), [(300000.0, 300000.0), (350000.0, 250000.0), (250000.0, 360000.0)]),
    ("tm_helmert7_pv", "tm", TM24, BESSEL, "helmert7pv", (598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7), [(500000.0, 5100000.0), (450000.0, 4900000.0), (560000.0, 5300000.0)]),
    ("tm_helmert7_cf", "tm", TM9, KRASSOWSKY, "helmert7cf", (24.0, -123.0, -94.0, 0.02, -0.25, -0.13, 1.1), [(500000.0, 5500000.0), (450000.0, 5600000.0), (560000.0, 5300000.0)]),
    ("stere_helmert7_pv", "stereo", STERE, KRASSOWSKY, "helmert7pv", (2.329, -147.042, -92.08, 0.309, -0.325, -0.497, 5.69), [(500000.0, 500000.0), (600000.0, 450000.0), (450000.0, 600000.0)]),
]
COLUMNAS = ["caso", "metodo", "parametros", "a_m", "inv_f", "transformacion", "tx", "ty", "tz", "rx", "ry", "rz", "s_ppm", "punto", "este_m", "norte_m", "longitud", "latitud"]


def num(valor: float) -> str:
    return repr(float(valor))


def _proyeccion(metodo: str, p: dict[str, float], a: float, rf: float) -> str:
    elipsoide = f"+a={a!r} +rf={rf!r}"
    if metodo == "lcc2":
        paralelos = f"+lat_1={p['standardParallel1Deg']!r} +lat_2={p['standardParallel2Deg']!r}"
        origen = f"+lat_0={p['latitudeOfFalseOriginDeg']!r} +lon_0={p['longitudeOfFalseOriginDeg']!r}"
        return f"+proj=lcc {paralelos} {origen} +x_0={p['eastingAtFalseOrigin']!r} +y_0={p['northingAtFalseOrigin']!r} {elipsoide}"
    falsos = f"+x_0={p['falseEasting']!r} +y_0={p['falseNorthing']!r}"
    lat0, lon0 = p["latitudeOfOriginDeg"], p["longitudeOfOriginDeg"]
    if metodo == "tm":
        return f"+proj=tmerc +lat_0={lat0!r} +lon_0={lon0!r} +k_0={p['scaleFactor']!r} {falsos} {elipsoide}"
    if metodo == "lcc1":
        return f"+proj=lcc +lat_1={lat0!r} +lat_0={lat0!r} +lon_0={lon0!r} +k_0={p['scaleFactor']!r} {falsos} {elipsoide}"
    if metodo == "stereo":
        return f"+proj=sterea +lat_0={lat0!r} +lon_0={lon0!r} +k_0={p['scaleFactor']!r} {falsos} {elipsoide}"
    raise ValueError(metodo)


def _transformador(metodo: str, p: dict[str, float], elipsoide: tuple[float, float], tipo: str, h: tuple[float, ...]) -> Transformer:
    a, rf = elipsoide
    pasos = [f"+step +inv {_proyeccion(metodo, p, a, rf)}", f"+step +proj=cart +a={a!r} +rf={rf!r}"]
    if tipo != "nula":
        convencion = "coordinate_frame" if tipo == "helmert7cf" else "position_vector"
        rotaciones = f"+rx={h[3]!r} +ry={h[4]!r} +rz={h[5]!r} +s={h[6]!r} " if tipo != "helmert3" else ""
        pasos.append(f"+step +proj=helmert +x={h[0]!r} +y={h[1]!r} +z={h[2]!r} {rotaciones}+convention={convencion}")
    pasos += [f"+step +inv +proj=cart {GRS80}", "+step +proj=unitconvert +xy_in=rad +xy_out=deg"]
    return Transformer.from_pipeline("+proj=pipeline " + " ".join(pasos))


def generar() -> list[list[str]]:
    filas: list[list[str]] = []
    for caso, metodo, p, elipsoide, tipo, h, puntos in CASOS:
        t = _transformador(metodo, p, elipsoide, tipo, h)
        for numero, (e, n) in enumerate(puntos, start=1):
            lon, lat, _ = t.transform(e, n, 0.0)
            parametros = ";".join(f"{k}={num(v)}" for k, v in p.items())
            filas.append([caso, metodo, parametros, num(elipsoide[0]), num(elipsoide[1]), tipo, *[num(x) for x in h], str(numero), num(e), num(n), num(lon), num(lat)])
    return filas


def a_csv(filas: list[list[str]]) -> str:
    cabecera = [
        "# Vectores de S035: SC personalizado con parametros sueltos. Generado por tools/geodesia/personalizado_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        ",".join(COLUMNAS),
    ]
    return "\n".join(cabecera + [",".join(f) for f in filas]) + "\n"


def main() -> int:
    SALIDA.write_text(a_csv(generar()), encoding="utf-8", newline="\n")
    print(f"{len(CASOS)} casos escritos en {SALIDA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
