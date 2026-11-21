"""Vectores de la reproyección de S057 (`reproyeccion_vectores.py`): tres SC de otros países y dos cambios de datum, reproducibles."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reproyeccion_vectores as r  # noqa: E402


def _leer() -> list[list[str]]:
    lineas = [l for l in r.SALIDA.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]
    assert lineas[0].split(",") == r.COLUMNAS
    return [l.split(",") for l in lineas[1:]]


def test_s057_rnf_hay_al_menos_tres_sc_de_otros_paises_y_un_cambio_con_parametros_y_otro_con_rejilla() -> None:
    filas = _leer()

    assert len({f[0] for f in filas}) >= 3
    assert {"parametros", "rejilla"} <= {f[2] for f in filas}
    assert all(len([f for f in filas if f[0] == origen]) == 3 for origen in {f[0] for f in filas})


def test_s057_rf_8_el_cambio_de_datum_se_nota_donde_toca() -> None:
    por_origen: dict[str, list[list[str]]] = {}
    for f in _leer():
        por_origen.setdefault(f[0], []).append(f)

    # ED50 → ETRS89 con la rejilla mueve el punto unos 100-200 m; RGF93 = ETRS89 no lo mueve, solo cambia de proyección.
    desplazamientos = {o: abs(float(fs[0][8]) - float(fs[0][6])) for o, fs in por_origen.items()}
    assert 50 < desplazamientos["23030"] < 300


def test_s057_rf_7_regenerarlo_da_el_mismo_fichero() -> None:
    assert r.a_csv(r.generar()) == r.SALIDA.read_text(encoding="utf-8")
