"""Vectores de S039 (`generar_localizacion.py`): la localizacion calculada con otro algoritmo, reproducible."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_localizacion as g  # noqa: E402


def _leer(ruta: Path, columnas: list[str]) -> list[dict[str, str]]:
    lineas = [x for x in ruta.read_text(encoding="utf-8").splitlines() if not x.startswith("#")]
    assert lineas[0].split(",") == columnas
    return [dict(zip(columnas, x.split(","))) for x in lineas[1:]]


def test_s039_rf_7_los_casos_son_cinco_y_el_sin_ruido_recupera_la_transformacion() -> None:
    parametros = _leer(g.PARAMETROS, g.COLUMNAS_PARAMETROS)
    assert len(parametros) == 5
    c1 = next(p for p in parametros if p["caso"] == "c1_utm30_sin_ruido")
    assert math.isclose(float(c1["giro_rad"]), g.GIRO, abs_tol=1e-9)
    assert math.isclose(float(c1["escala"]), g.ESCALA, abs_tol=1e-9)
    assert float(c1["rms_h"]) < 1e-4 and float(c1["rms_v"]) < 1e-4


def test_s039_rf_8_con_la_escala_fija_la_escala_es_exactamente_uno() -> None:
    c3 = next(p for p in _leer(g.PARAMETROS, g.COLUMNAS_PARAMETROS) if p["caso"] == "c3_lambert93_rigido")
    assert c3["escala_fija"] == "1" and float(c3["escala"]) == 1.0


def test_s039_rf_9_el_tipo_vertical_sigue_el_numero_de_pares() -> None:
    tipos = {p["caso"]: p["tipo_vertical"] for p in _leer(g.PARAMETROS, g.COLUMNAS_PARAMETROS)}
    assert tipos["c1_utm30_sin_ruido"] == "PLANE"
    assert tipos["c3_lambert93_rigido"] == "OFFSET"
    assert tipos["c5_utm30_dos_pares_desplazamiento"] == "OFFSET"


def test_s039_rf_7_con_un_par_hay_traslacion_y_el_residuo_es_cero() -> None:
    filas = [p for p in _leer(g.PARES, g.COLUMNAS_PARES) if p["caso"] == "c4_utm30_un_par"]
    assert len(filas) == 1 and abs(float(filas[0]["res_e"])) < 1e-9 and abs(float(filas[0]["res_n"])) < 1e-9


def test_s039_rf_7_regenerarlo_da_los_mismos_ficheros() -> None:
    pares, parametros = g.generar()
    assert g.a_csv(g.COLUMNAS_PARES, pares, "los pares y sus residuos") == g.PARES.read_text(encoding="utf-8")
    assert g.a_csv(g.COLUMNAS_PARAMETROS, parametros, "los parametros de cada localizacion") == g.PARAMETROS.read_text(encoding="utf-8")
