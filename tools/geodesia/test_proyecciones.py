"""Coherencia de los vectores de proyección de S032 (`proyecciones.py`): columnas, recuentos, métodos de EPSG y que se regeneran igual."""

from __future__ import annotations

import random
import sys
from pathlib import Path

import pyproj
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import proyecciones as p  # noqa: E402

FIXTURES = p.OUT


def leer(nombre: str) -> tuple[list[str], list[dict[str, str]]]:
    """(líneas de comentario, filas con sus columnas por nombre) de un CSV de `fixtures/geodesia/`."""
    lineas = (FIXTURES / nombre).read_text(encoding="utf-8").splitlines()
    comentarios = [linea for linea in lineas if linea.startswith("#")]
    datos = [linea for linea in lineas if linea and not linea.startswith("#")]
    columnas = datos[0].split(",")
    return comentarios, [dict(zip(columnas, linea.split(","), strict=True)) for linea in datos[1:]]


def test_s032_rf_1_la_cabecera_lleva_las_versiones_de_pyproj_y_proj() -> None:
    comentarios, _ = leer("lcc.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea and f"PROJ={pyproj.proj_version_str}" in linea for linea in comentarios)
    assert any("tools/geodesia/proyecciones.py" in linea for linea in comentarios)


def test_s032_rf_1_lcc_tiene_las_columnas_de_ida_vuelta_factor_y_convergencia() -> None:
    _, filas = leer("lcc.csv")

    assert set(filas[0]) == {
        "caso", "metodo", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m",
        "lat_origen", "lon_origen", "lat1", "lat2", "k0", "falso_este", "falso_norte",
        "latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados",
    }  # fmt: skip


def test_s032_rf_1_lcc_cubre_1sp_2sp_y_mas_de_cuarenta_vectores() -> None:
    _, filas = leer("lcc.csv")

    assert len(filas) > 40
    assert {fila["metodo"] for fila in filas} == {"1SP", "2SP"}


def test_s032_rf_1_lcc_incluye_los_casos_de_epsg_y_los_casos_limite() -> None:
    _, filas = leer("lcc.csv")
    casos = {fila["caso"] for fila in filas}

    assert {"EPSG:2154", "EPSG:3034", "EPSG:32040", "EPSG:27571", "EPSG:27572"} <= casos
    assert "esfera-2sp" in casos  # aplanamiento 0
    assert "sur-2sp" in casos  # hemisferio sur
    assert "paralelos-iguales-2sp" in casos  # equivale a 1SP
    # El caso en pies topográficos lleva su unidad y sus falsos tal como los define EPSG.
    pies = [fila for fila in filas if fila["caso"] == "EPSG:32040"]
    assert abs(float(pies[0]["unidad_m"]) - 1200 / 3937) < 1e-9
    # Los casos con meridiano de París lo llevan en grados respecto a Greenwich.
    paris = {fila["meridiano_origen_deg"] for fila in filas if fila["caso"] in {"EPSG:27571", "EPSG:27572"}}
    assert len(paris) == 1 and all(abs(float(valor) - 2.33722917) < 1e-6 for valor in paris)


def test_s032_rf_1_cada_caso_de_epsg_usa_el_metodo_que_se_espera_en_la_base_de_proj() -> None:
    for codigo, esperado in p.EPSG_LCC.items():
        crs = pyproj.CRS.from_epsg(codigo)
        assert esperado in crs.coordinate_operation.method_name, codigo


def test_s032_rf_1_los_vectores_de_lcc_se_regeneran_igual() -> None:
    """Determinista: la misma versión de pyproj/PROJ y la misma semilla dan el mismo fichero."""
    regenerado = "\n".join(p.lcc_vectors(random.Random(p.SEMILLA_LCC))) + "\n"

    assert regenerado == (FIXTURES / "lcc.csv").read_text(encoding="utf-8")


def test_s032_rf_1_los_vectores_son_numeros_finitos() -> None:
    _, filas = leer("lcc.csv")

    for fila in filas:
        for columna in ("latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados"):
            assert float(fila[columna]) == pytest.approx(float(fila[columna]))
            assert abs(float(fila[columna])) < 1e12, (fila["caso"], columna)
