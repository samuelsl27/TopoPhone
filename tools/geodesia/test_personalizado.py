"""Vectores de S035 (`personalizado_vectores.py`): SC personalizados con parametros sueltos, reproducibles."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import personalizado_vectores as p  # noqa: E402


def _leer() -> list[list[str]]:
    lineas = [l for l in p.SALIDA.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]
    assert lineas[0].split(",") == p.COLUMNAS
    return [l.split(",") for l in lineas[1:]]


def test_s035_rnf_hay_tres_puntos_por_caso_y_todos_los_tipos_de_transformacion() -> None:
    filas = _leer()

    assert all(len([f for f in filas if f[0] == c]) == 3 for c in {f[0] for f in filas})
    assert {"nula", "helmert3", "helmert7pv", "helmert7cf"} <= {f[5] for f in filas}
    assert {"tm", "lcc1", "lcc2", "stereo", "omerca", "omercb"} <= {f[1] for f in filas}


def test_s035_rf_3_un_utm_sin_cambio_de_datum_cae_donde_toca() -> None:
    primero = next(f for f in _leer() if f[0] == "tm_sin_cambio")

    assert abs(float(primero[17]) - 40.4) < 0.1
    assert abs(float(primero[16]) + 3.7) < 0.2


def test_s035_rf_3_regenerarlo_da_el_mismo_fichero() -> None:
    assert p.a_csv(p.generar()) == p.SALIDA.read_text(encoding="utf-8")
