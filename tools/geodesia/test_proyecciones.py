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


def test_s032_rf_4_mercator_cubre_a_y_b_con_los_casos_de_epsg_y_los_limite() -> None:
    comentarios, filas = leer("mercator.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea for linea in comentarios)
    assert len(filas) > 40
    assert {fila["metodo"] for fila in filas} == {"A", "B"}
    casos = {fila["caso"] for fila in filas}
    assert {"EPSG:3002", "EPSG:3388", "EPSG:3395"} <= casos
    assert {"esfera-a", "esfera-b", "sur-a", "sur-b", "b-ecuador"} <= casos


def test_s032_rf_4_mercator_tiene_las_columnas_de_ida_vuelta_factor_y_convergencia() -> None:
    _, filas = leer("mercator.csv")

    assert set(filas[0]) == {
        "caso", "metodo", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m", "lon_origen", "k0", "lat_estandar",
        "falso_este", "falso_norte", "latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados",
    }  # fmt: skip
    # La convergencia de una Mercator es siempre nula (S032/RF-5).
    assert all(float(fila["convergencia_grados"]) == 0.0 for fila in filas)


def test_s032_rf_4_cada_caso_de_epsg_de_mercator_usa_la_variante_que_se_espera() -> None:
    for codigo, esperado in p.EPSG_MERCATOR.items():
        assert esperado in pyproj.CRS.from_epsg(codigo).coordinate_operation.method_name, codigo


def test_s032_rf_4_los_vectores_de_mercator_se_regeneran_igual() -> None:
    regenerado = "\n".join(p.mercator_vectors(random.Random(p.SEMILLA_MERCATOR))) + "\n"

    assert regenerado == (FIXTURES / "mercator.csv").read_text(encoding="utf-8")


def test_s032_rf_7_omerc_cubre_a_y_b_con_los_casos_de_epsg_y_los_limite() -> None:
    comentarios, filas = leer("omerc.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea for linea in comentarios)
    assert len(filas) > 100
    assert {fila["metodo"] for fila in filas} == {"A", "B"}
    casos = {fila["caso"] for fila in filas}
    assert {"EPSG:26931", "EPSG:3375", "EPSG:29873"} <= casos
    # Azimut de 0°, 90°, 180° y 270°, ángulo de rectificación distinto del azimut, centro en el ecuador, esfera y hemisferio sur.
    assert {"a-azimut-0", "b-azimut-90", "a-azimut-120", "b-azimut-180", "b-azimut-270", "b-gamma-distinto", "b-ecuador"} <= casos
    assert {"esfera-a", "esfera-b", "sur-a", "sur-b"} <= casos


def test_s032_rf_7_omerc_tiene_las_columnas_de_ida_vuelta_factor_y_convergencia() -> None:
    _, filas = leer("omerc.csv")

    assert set(filas[0]) == {
        "caso", "metodo", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m", "lat_centro", "lon_centro", "azimut",
        "angulo_rectificacion", "k0", "falso_este", "falso_norte", "latitud", "longitud", "este_m", "norte_m", "factor_escala",
        "convergencia_grados",
    }  # fmt: skip
    # El caso de EPSG con ángulo de rectificación distinto del azimut lo conserva.
    gamma = {(fila["azimut"], fila["angulo_rectificacion"]) for fila in filas if fila["caso"] == "EPSG:29873"}
    assert len(gamma) == 1 and next(iter(gamma))[0] != next(iter(gamma))[1]


def test_s032_rf_7_cada_caso_de_epsg_de_omerc_usa_la_variante_que_se_espera() -> None:
    for codigo, esperado in p.EPSG_OMERC.items():
        assert esperado in pyproj.CRS.from_epsg(codigo).coordinate_operation.method_name, codigo


def test_s032_rf_7_los_vectores_de_omerc_se_regeneran_igual() -> None:
    regenerado = "\n".join(p.omerc_vectors(random.Random(p.SEMILLA_OMERC))) + "\n"

    assert regenerado == (FIXTURES / "omerc.csv").read_text(encoding="utf-8")


def test_s032_rf_10_stereo_oblicua_cubre_rd_nueva_y_los_casos_limite() -> None:
    comentarios, filas = leer("stereo_oblicua.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea for linea in comentarios)
    assert len(filas) > 60
    casos = {fila["caso"] for fila in filas}
    assert {"EPSG:28992", "origen-ecuador", "cerca-del-polo", "esfera", "sur"} <= casos
    assert set(filas[0]) == {
        "caso", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m", "lat_origen", "lon_origen", "k0", "falso_este",
        "falso_norte", "latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados",
    }  # fmt: skip


def test_s032_rf_10_el_punto_publicado_de_gn_7_2_esta_en_los_vectores_de_rd_nueva() -> None:
    _, filas = leer("stereo_oblicua.csv")

    publicado = [f for f in filas if f["caso"] == "EPSG:28992" and float(f["latitud"]) == 53.0 and float(f["longitud"]) == 6.0]

    assert len(publicado) == 1
    assert float(publicado[0]["este_m"]) == pytest.approx(196105.283, abs=0.002)
    assert float(publicado[0]["norte_m"]) == pytest.approx(557057.739, abs=0.002)


def test_s032_rf_10_el_caso_de_epsg_es_la_estereografica_oblicua() -> None:
    assert p.EPSG_ESTEREO_OBLICUA == {28992}
    for codigo in p.EPSG_ESTEREO_OBLICUA:
        assert "Oblique Stereographic" in pyproj.CRS.from_epsg(codigo).coordinate_operation.method_name


def test_s032_rf_10_los_vectores_de_stereo_oblicua_se_regeneran_igual() -> None:
    regenerado = "\n".join(p.stereo_oblicua_vectors(random.Random(p.SEMILLA_STEREO_OBLICUA))) + "\n"

    assert regenerado == (FIXTURES / "stereo_oblicua.csv").read_text(encoding="utf-8")


def test_s032_rf_13_stereo_polar_cubre_a_b_y_c_en_los_dos_polos() -> None:
    comentarios, filas = leer("stereo_polar.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea for linea in comentarios)
    assert len(filas) > 150
    assert {fila["metodo"] for fila in filas} == {"A", "B", "C"}
    casos = {fila["caso"] for fila in filas}
    assert {"EPSG:5041", "EPSG:5042", "EPSG:3031", "EPSG:3413", "EPSG:2985"} <= casos
    assert {"b-polo-norte", "b-polo-sur", "esfera-a", "esfera-b"} <= casos
    # Los dos polos, en cada variante (el signo del parámetro de latitud dice el polo).
    for metodo in ("A", "B", "C"):
        signos = {float(f["lat_param"]) > 0 for f in filas if f["metodo"] == metodo}
        assert signos == {True, False}, metodo


def test_s032_rf_13_stereo_polar_tiene_las_columnas_de_ida_vuelta_factor_y_convergencia() -> None:
    _, filas = leer("stereo_polar.csv")

    assert set(filas[0]) == {
        "caso", "metodo", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m", "lat_param", "lon_origen", "k0",
        "falso_este", "falso_norte", "latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados",
    }  # fmt: skip
    # Solo la A lleva factor de escala en el origen.
    assert all((fila["k0"] != "") == (fila["metodo"] == "A") for fila in filas)


def test_s032_rf_13_cada_caso_de_epsg_polar_usa_la_variante_que_se_espera() -> None:
    for codigo, esperado in p.EPSG_POLAR.items():
        assert esperado in pyproj.CRS.from_epsg(codigo).coordinate_operation.method_name, codigo


def test_s032_rf_13_los_vectores_de_stereo_polar_se_regeneran_igual() -> None:
    regenerado = "\n".join(p.stereo_polar_vectors(random.Random(p.SEMILLA_STEREO_POLAR))) + "\n"

    assert regenerado == (FIXTURES / "stereo_polar.csv").read_text(encoding="utf-8")


def test_s032_rf_17_meridiano_origen_cubre_los_diez_metodos_y_tres_meridianos() -> None:
    comentarios, filas = leer("meridiano_origen.csv")

    assert any(f"pyproj={pyproj.__version__}" in linea for linea in comentarios)
    metodos = {fila["metodo"] for fila in filas}
    assert metodos == {
        "LAMBERT_CONIC_1SP", "LAMBERT_CONIC_2SP", "MERCATOR_A", "MERCATOR_B", "OBLIQUE_MERCATOR_A", "OBLIQUE_MERCATOR_B",
        "OBLIQUE_STEREOGRAPHIC", "POLAR_STEREOGRAPHIC_A", "POLAR_STEREOGRAPHIC_B", "POLAR_STEREOGRAPHIC_C",
    }  # fmt: skip
    meridianos = {round(float(fila["meridiano_origen_deg"]), 6) for fila in filas}
    assert meridianos >= {round(p.MADRID_DEG, 6), round(p.PARIS_DEG, 6), round(p.LISBOA_DEG, 6)}


def test_s032_rf_17_meridiano_origen_tiene_un_caso_que_cruza_el_antimeridiano() -> None:
    _, filas = leer("meridiano_origen.csv")

    cruza = [fila for fila in filas if fila["caso"].startswith("cruza-antimeridiano")]

    assert len(cruza) > 5
    assert any(float(fila["longitud"]) < -170.0 for fila in cruza) and any(float(fila["longitud"]) > 170.0 for fila in cruza)
    assert all(-180.0 < float(fila["longitud"]) <= 180.0 for fila in filas)


def test_s032_rf_17_meridiano_origen_tiene_las_columnas_genericas() -> None:
    _, filas = leer("meridiano_origen.csv")

    assert set(filas[0]) == {
        "caso", "metodo", "semieje", "achatamiento_inverso", "meridiano_origen_deg", "unidad_m",
        "p1", "p2", "p3", "p4", "p5", "p6", "p7", "latitud", "longitud", "este_m", "norte_m", "factor_escala", "convergencia_grados",
    }  # fmt: skip


def test_s032_rf_17_los_vectores_de_meridiano_origen_se_regeneran_igual() -> None:
    regenerado = "\n".join(p.meridiano_origen_vectors(random.Random(p.SEMILLA_MERIDIANO_ORIGEN))) + "\n"

    assert regenerado == (FIXTURES / "meridiano_origen.csv").read_text(encoding="utf-8")
