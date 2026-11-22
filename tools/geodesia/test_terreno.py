"""Vectores de S058 (`terreno_vectores.py`): del suelo a coordenadas de terreno, reproducibles."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import terreno_vectores as t  # noqa: E402


def _leer() -> list[list[str]]:
    lineas = [l for l in t.SALIDA.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]
    assert lineas[0].split(",") == t.COLUMNAS
    return [l.split(",") for l in lineas[1:]]


def test_s058_rf_3_hay_tres_puntos_por_caso() -> None:
    filas = _leer()

    assert len({f[0] for f in filas}) == 3
    assert all(len([f for f in filas if f[0] == c]) == 3 for c in {f[0] for f in filas})


def test_s058_rf_7_el_terreno_es_una_homotecia_respecto_al_origen() -> None:
    for f in _leer():
        k, e0, n0 = float(f[4]), float(f[5]), float(f[6])
        e, n, et, nt = float(f[8]), float(f[9]), float(f[10]), float(f[11])
        assert math.isclose(math.hypot(et - e0, nt - n0), k * math.hypot(e - e0, n - n0), rel_tol=1e-12, abs_tol=1e-8)


def test_s058_rf_3_el_factor_combinado_es_casi_uno_y_menor_con_altura_en_el_meridiano_central() -> None:
    # En UTM 30N, a unos 3.7 grados del meridiano central (-3), la escala de la proyeccion es algo mayor que 0,9996 y la altura de 650 m la baja ~1e-4
    madrid = next(f for f in _leer() if f[0] == "25830")
    assert 0.9995 < float(madrid[4]) < 1.0005


def test_s058_rf_7_regenerarlo_da_el_mismo_fichero() -> None:
    assert t.a_csv(t.generar()) == t.SALIDA.read_text(encoding="utf-8")
