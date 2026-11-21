"""Coherencia del catálogo de SC de S034 (`catalogo.py`): contenido, descartes, determinismo y fichero generado."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalogo as c  # noqa: E402
import paises  # noqa: E402

LINEAS_ESPERADAS = {"ellipsoids", "datums", "crs", "countries"}


@pytest.fixture(scope="session")
def secciones() -> dict[str, list[list[str]]]:
    return c.generar_crs()


def crs_por_epsg(secciones: dict[str, list[list[str]]]) -> dict[int, list[str]]:
    return {int(fila[0]): fila for fila in secciones["crs"]}


def test_s034_rf_4_el_catalogo_tiene_las_secciones_de_crs_y_no_es_pequeno(secciones) -> None:
    assert LINEAS_ESPERADAS <= set(secciones)
    assert len(secciones["crs"]) > 4500
    assert len(secciones["datums"]) > 100


def test_s034_rf_4_cada_fila_tiene_las_columnas_de_su_seccion(secciones) -> None:
    for nombre, columnas in c.COLUMNAS.items():
        if nombre in secciones:
            assert all(len(fila) == columnas for fila in secciones[nombre]), nombre


def test_s034_rf_4_solo_hay_metodos_que_se_calculan(secciones) -> None:
    metodos = {fila[c.CRS_METODO] for fila in secciones["crs"]}

    assert metodos <= c.METODOS_CALCULABLES
    assert {"tm", "lcc2", "lcc1", "webmerc", "geo"} <= metodos


def test_s034_rf_4_no_hay_ejes_al_oeste_ni_al_sur_ni_unidades_raras(secciones) -> None:
    unidades = {fila[c.CRS_UNIDAD_M] for fila in secciones["crs"]}

    assert unidades <= {repr(1.0), repr(0.3048), repr(1200.0 / 3937.0)}
    # EPSG:2000 (Anguilla 1957 / British West Indies Grid) es de ejes E-N; EPSG:22275 (Cape / Lo15) está orientado al oeste y no entra.
    por_epsg = crs_por_epsg(secciones)
    assert 25830 in por_epsg
    assert 22275 not in por_epsg


def test_s034_rf_4_no_hay_sc_obsoletos(secciones) -> None:
    por_epsg = crs_por_epsg(secciones)

    assert 4230 in por_epsg  # ED50, vigente
    assert 25832 in por_epsg
    assert 3035 not in por_epsg  # ETRS89-extended / LAEA Europe usa Lambert Azimuthal Equal Area: no se calcula
    assert 4326 in por_epsg and 3857 in por_epsg


def test_s034_rf_4_utm_30_de_etrs89_trae_sus_parametros_y_su_pais(secciones) -> None:
    fila = crs_por_epsg(secciones)[25830]

    assert fila[c.CRS_METODO] == "tm"
    assert c.parametros(fila[c.CRS_PARAMETROS]) == {
        "latitudeOfOriginDeg": 0.0,
        "longitudeOfOriginDeg": -3.0,
        "scaleFactor": 0.9996,
        "falseEasting": 500000.0,
        "falseNorthing": 0.0,
    }
    assert fila[c.CRS_DATUM] == "4258"
    assert "ES" in fila[c.CRS_PAISES].split(",")


def test_s034_rf_4_lambert_93_es_lcc_2sp_con_sus_paralelos(secciones) -> None:
    fila = crs_por_epsg(secciones)[2154]

    assert fila[c.CRS_METODO] == "lcc2"
    parametros = c.parametros(fila[c.CRS_PARAMETROS])
    assert parametros["standardParallel1Deg"] == pytest.approx(49.0)
    assert parametros["standardParallel2Deg"] == pytest.approx(44.0)
    assert parametros["eastingAtFalseOrigin"] == 700000.0
    assert "FR" in fila[c.CRS_PAISES].split(",")


def test_s034_rf_4_los_falsos_van_en_la_unidad_del_sc(secciones) -> None:
    fila = crs_por_epsg(secciones)[32040]  # NAD27 / Texas South Central, en pies de EE. UU.

    assert float(fila[c.CRS_UNIDAD_M]) == pytest.approx(1200.0 / 3937.0, rel=1e-15)
    assert c.parametros(fila[c.CRS_PARAMETROS])["eastingAtFalseOrigin"] == pytest.approx(2000000.0 / 1.0)


def test_s034_rf_4_los_geograficos_llevan_su_meridiano_de_origen(secciones) -> None:
    fila = crs_por_epsg(secciones)[4807]  # NTF (Paris)

    assert fila[c.CRS_METODO] == "geo"
    datum = {f[0]: f for f in secciones["datums"]}[fila[c.CRS_DATUM]]
    assert float(datum[c.DATUM_MERIDIANO]) == pytest.approx(2.5969213 * 0.9, rel=1e-6)


def test_s034_rf_2_los_paises_salen_del_nombre_del_ambito() -> None:
    assert paises.paises_del_ambito("France - mainland onshore") == ["FR"]
    assert paises.paises_del_ambito("Spain - Canary Islands onshore") == ["ES"]
    assert paises.paises_del_ambito("Europe - onshore and offshore: Albania; Andorra; Austria") == ["AD", "AL", "AT"]
    assert paises.paises_del_ambito("Papua New Guinea - onshore") == ["PG"]
    assert paises.paises_del_ambito("World") == []


def test_s034_rf_4_la_generacion_es_determinista(secciones) -> None:
    assert c.a_texto(secciones) == c.a_texto(c.generar_crs())
