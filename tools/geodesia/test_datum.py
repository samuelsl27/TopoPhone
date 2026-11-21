"""Coherencia de los vectores de transformación de datum de S033 (`datum.py`): columnas, recuentos, métodos de EPSG y que se regeneran igual."""

from __future__ import annotations

import random
import sys
from pathlib import Path

import pyproj
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import datum as d  # noqa: E402

FIXTURES = d.OUT


def leer(nombre: str) -> tuple[list[str], list[dict[str, str]]]:
    """(líneas de comentario, filas con sus columnas por nombre) de un CSV de `fixtures/geodesia/`."""
    lineas = (FIXTURES / nombre).read_text(encoding="utf-8").splitlines()
    comentarios = [linea for linea in lineas if linea.startswith("#")]
    datos = [linea for linea in lineas if linea and not linea.startswith("#")]
    columnas = datos[0].split(",")
    return comentarios, [dict(zip(columnas, linea.split(","), strict=True)) for linea in datos[1:]]


def test_s033_rf_1_la_cabecera_lleva_las_versiones_de_pyproj_y_proj() -> None:
    comentarios, _ = leer("helmert.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea and f"PROJ={pyproj.proj_version_str}" in linea for linea in comentarios)


def test_s033_rf_1_helmert_tiene_las_columnas_y_los_casos_esperados() -> None:
    _, filas = leer("helmert.csv")

    assert len(filas) > 600
    assert {f["n_parametros"] for f in filas} == {"3", "4", "6", "7"}
    assert {f["convencion"] for f in filas} == {"PV", "CF"}
    assert {f["caso"] for f in filas} >= {"EPSG:10085", "EPSG:1112", "EPSG:1024", "cero_7p", "grande_7p_pv", "grande_7p_cf"}
    for fila in filas:
        for columna in ("latitud_dir", "longitud_dir", "altura_dir", "latitud_inv", "longitud_inv", "altura_inv"):
            assert float(fila[columna]) == float(fila[columna])  # no es NaN


def test_s033_rf_2_cada_caso_de_epsg_usa_el_metodo_que_se_espera() -> None:
    for codigo, metodo, _, _ in d.OPERACIONES_HELMERT:
        real = Transformer.from_pipeline(f"urn:ogc:def:coordinateOperation:EPSG::{codigo}").to_json_dict()["method"]["name"]
        assert real == metodo, codigo


def test_s033_rf_2_los_casos_propios_cubren_pv_y_cf_con_rotaciones_y_escala_grandes() -> None:
    grandes = [c for c in d.CASOS_PROPIOS if max(abs(v) for v in c.r) >= 1.0 and abs(c.escala) >= 10.0]

    assert {c.convencion for c in grandes} == {"PV", "CF"}


def test_s033_rf_1_helmert_se_regenera_igual() -> None:
    generado = d.helmert_vectors(random.Random(d.SEMILLA_HELMERT))
    guardado = (FIXTURES / "helmert.csv").read_text(encoding="utf-8").splitlines()

    assert generado == guardado
