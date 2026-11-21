"""Vectores de contraste de la reproyección de S057: de un SC de la biblioteca (otro país) al SC del proyecto, con cambio de datum, calculados con
pyproj/PROJ (constitución nº 2).

Uso: uv run --project tools tools/geodesia/reproyeccion_vectores.py   (escribe `fixtures/geodesia/reproyeccion.csv`)

Cada fila: SC de origen, SC del proyecto, punto, latitud y longitud (grados, en el datum de origen; solo de referencia), X e Y del origen en metros,
Este y Norte del proyecto en metros y la tubería que usó PROJ. La referencia es el `Transformer` de PROJ del SC de origen al del proyecto, con la
operación más precisa que cubre el punto (`area_of_interest`), ejes Este/Norte. Los casos cubren tres tipos de paso: nulo (RGF93 = ETRS89),
parámetros (Helmert: GGRS87 y Pulkovo 1942(58)) y rejilla (ED50 con la rejilla del IGN que ya va en la app).
"""

from __future__ import annotations

import math
import sys
import warnings
from pathlib import Path

import pyproj
import pyproj.aoi
import pyproj.network
from pyproj import CRS, Transformer

ROOT = Path(__file__).resolve().parents[2]
SALIDA = ROOT / "fixtures" / "geodesia" / "reproyeccion.csv"

# (SC de origen, SC del proyecto, tipo de paso, [(latitud, longitud)]): puntos dentro del ámbito de ambos SC.
CASOS = [
    (2154, 25830, "nulo", [(48.10, -3.00), (47.60, -2.20), (48.60, -1.80)]),  # Lambert-93 (Francia) → ETRS89 / UTM 30N
    (2100, 32634, "parametros", [(38.00, 23.50), (37.20, 22.40), (40.60, 22.90)]),  # GGRS87 / Greek Grid → WGS 84 / UTM 34N
    (3844, 25834, "parametros", [(44.43, 26.10), (45.75, 21.23), (46.77, 23.60)]),  # Pulkovo 1942(58) / Stereo 70 → ETRS89 / UTM 34N
    (23031, 25831, "rejilla", [(41.50, 2.00), (41.00, 0.80), (42.30, 3.10)]),  # ED50 / UTM 31N → ETRS89 / UTM 31N (rejilla del IGN)
]
COLUMNAS = ["origen", "proyecto", "paso", "punto", "latitud", "longitud", "x_m", "y_m", "este_m", "norte_m"]


def num(valor: float) -> str:
    return repr(float(valor))


def generar() -> list[list[str]]:
    pyproj.network.set_network_enabled(True)
    filas: list[list[str]] = []
    for origen, proyecto, paso, puntos in CASOS:
        crs_origen, crs_proyecto = CRS.from_epsg(origen), CRS.from_epsg(proyecto)
        a_origen = Transformer.from_crs(crs_origen.geodetic_crs, crs_origen, always_xy=True)
        for numero, (latitud, longitud) in enumerate(puntos, start=1):
            x, y = a_origen.transform(longitud, latitud)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                aoi = pyproj.aoi.AreaOfInterest(longitud, latitud, longitud, latitud)
                t = Transformer.from_crs(crs_origen, crs_proyecto, always_xy=True, area_of_interest=aoi)
                este, norte = t.transform(x, y)
            if not (math.isfinite(este) and math.isfinite(norte)):
                raise SystemExit(f"Sin resultado de PROJ para {origen} → {proyecto} en ({latitud}, {longitud})")
            filas.append([str(origen), str(proyecto), paso, str(numero), num(latitud), num(longitud), num(x), num(y), num(este), num(norte)])
    return filas


def a_csv(filas: list[list[str]]) -> str:
    cabecera = [
        "# Vectores de la reproyección de S057 (SC de la biblioteca → SC del proyecto). Generado por tools/geodesia/reproyeccion_vectores.py; no editar a mano.",
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
