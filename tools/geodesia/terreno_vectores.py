"""Vectores de contraste de S058: el paso de cuadricula a coordenadas de terreno (una homotecia respecto a un origen con un factor), con el factor combinado en el
origen calculado con pyproj/PROJ (constitucion n 2).

Uso: uv run --project tools tools/geodesia/terreno_vectores.py   (escribe `fixtures/geodesia/terreno.csv`)

Cada fila: SC (EPSG), latitud y longitud del origen (grados, ETRS89), altura elipsoidal del origen (m), el factor combinado en el origen, el Este y el Norte
del origen en cuadricula, el numero del punto, su Este y Norte de cuadricula y su Este y Norte de terreno. El factor combinado es el factor de escala de la
proyeccion en el origen (`Proj.get_factors` de PROJ) por R / (R + h), con R el radio medio de Gauss del elipsoide en la latitud del origen (S008/RF-15).
El terreno es: E0 + k * (E - E0), N0 + k * (N - N0).
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import pyproj
from pyproj import Proj, Transformer

ROOT = Path(__file__).resolve().parents[2]
SALIDA = ROOT / "fixtures" / "geodesia" / "terreno.csv"

# GRS 1980
A = 6378137.0
RF = 298.257222101
E2 = 1 - (1 - 1 / RF) ** 2

# (EPSG, latitud, longitud, altura elipsoidal del origen, [(dE, dN) de los puntos respecto al origen en cuadricula])
CASOS = [
    (25830, 40.4, -3.7, 650.0, [(500.0, 800.0), (-1200.0, 300.0), (3000.0, -2500.0)]),
    (2154, 48.85, 2.35, 60.0, [(500.0, 800.0), (-1200.0, 300.0), (3000.0, -2500.0)]),
    (25829, 38.0, -8.0, 0.0, [(100.0, 100.0), (-2500.0, 4000.0), (10000.0, -10000.0)]),
]
COLUMNAS = ["epsg", "latitud", "longitud", "altura_m", "factor", "este0_m", "norte0_m", "punto", "este_m", "norte_m", "este_terreno_m", "norte_terreno_m"]


def num(valor: float) -> str:
    return repr(float(valor))


def radio_gauss(latitud: float) -> float:
    """Radio medio de Gauss sqrt(M * N) en la latitud dada, en el elipsoide GRS 1980."""
    seno = math.sin(math.radians(latitud))
    return A * math.sqrt(1 - E2) / (1 - E2 * seno * seno)


def generar() -> list[list[str]]:
    filas: list[list[str]] = []
    for epsg, latitud, longitud, altura, puntos in CASOS:
        a_cuadricula = Transformer.from_crs(4258, epsg, always_xy=True)
        este0, norte0 = a_cuadricula.transform(longitud, latitud)
        escala = Proj(epsg).get_factors(longitud, latitud).parallel_scale
        radio = radio_gauss(latitud)
        factor = escala * radio / (radio + altura)
        for numero, (de, dn) in enumerate(puntos, start=1):
            e, n = este0 + de, norte0 + dn
            filas.append([str(epsg), num(latitud), num(longitud), num(altura), num(factor), num(este0), num(norte0), str(numero), num(e), num(n),
                          num(este0 + factor * (e - este0)), num(norte0 + factor * (n - norte0))])
    return filas


def a_csv(filas: list[list[str]]) -> str:
    cabecera = [
        "# Vectores de S058: del suelo a coordenadas de terreno. Generado por tools/geodesia/terreno_vectores.py; no editar a mano.",
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
