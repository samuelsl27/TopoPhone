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


# --- Parte 2: transformaciones de cada datum hasta ETRS89 y WGS 84 ---


@pytest.fixture(scope="session")
def completo() -> dict[str, list[list[str]]]:
    return c.generar()


def opciones_de(completo, datum: str, destino: str) -> list[list[str]]:
    return [f for f in completo["options"] if f[c.OPCION_DATUM] == datum and f[c.OPCION_DESTINO] == destino]


def test_s034_rf_4_todo_sc_tiene_al_menos_una_transformacion(completo) -> None:
    con_opcion = {f[c.OPCION_DATUM] for f in completo["options"]}

    assert all(f[c.CRS_DATUM] in con_opcion for f in completo["crs"])
    assert {f[c.DATUM_CODIGO] for f in completo["datums"]} == {f[c.CRS_DATUM] for f in completo["crs"]}
    assert len(completo["crs"]) > 4500


def test_s034_rf_4_los_pasos_solo_usan_lo_que_calcula_s033(completo) -> None:
    for fila in completo["options"]:
        assert len(fila) == c.COLUMNAS["options"]
        for paso in fila[c.OPCION_PASOS].split("|"):
            assert paso == "null" or paso.startswith(("H;A=", "G;grid=")), paso
        if "null" in fila[c.OPCION_PASOS]:
            assert fila[c.OPCION_PASOS] == "null"


def test_s034_rf_10_ed50_a_etrs89_ofrece_la_rejilla_del_ign_y_los_helmert_de_epsg(completo) -> None:
    opciones = opciones_de(completo, "4230", "E")
    pasos = [f[c.OPCION_PASOS] for f in opciones]

    assert any(p == "G;grid=es_ign_SPED2ETV2.tif;inv=0" for p in pasos)
    assert any(p.startswith("H;") for p in pasos)
    assert [f[c.OPCION_PRECISION_M] for f in opciones if f[c.OPCION_PRECISION_M]] == sorted(
        (f[c.OPCION_PRECISION_M] for f in opciones if f[c.OPCION_PRECISION_M]), key=float
    )


def test_s034_rf_9_cada_opcion_lleva_su_nombre_precision_y_ambito(completo) -> None:
    ign = next(f for f in opciones_de(completo, "4230", "E") if "SPED2ETV2" in f[c.OPCION_PASOS])

    assert ign[c.OPCION_NOMBRE].startswith("ED50 to ETRS89")
    assert float(ign[c.OPCION_PRECISION_M]) == pytest.approx(0.2)
    sur, oeste, norte, este = (float(v) for v in ign[c.OPCION_AMBITO].split(","))
    assert sur < 36.0 < 43.0 < norte and oeste < -9.0 and este > 3.0


def test_s034_rf_4_etrs89_y_regcan95_no_cambian_nada_hasta_etrs89(completo) -> None:
    assert [f[c.OPCION_PASOS] for f in opciones_de(completo, "4258", "E")] == ["null"]
    regcan = opciones_de(completo, "4081", "E")

    assert [f[c.OPCION_PASOS] for f in regcan] == ["null"]
    assert "1071/2007" in regcan[0][c.OPCION_NOMBRE]
    assert opciones_de(completo, "4081", "W")


def test_s034_rf_4_la_ntf_de_paris_usa_solo_las_opciones_con_su_meridiano(completo) -> None:
    opciones = opciones_de(completo, "4807", "E")

    assert opciones
    assert all(f[c.OPCION_PASOS].startswith(("H;", "G;")) for f in opciones)


def test_s034_rf_4_un_paso_de_helmert_lleva_elipsoides_convencion_y_parametros(completo) -> None:
    paso = next(f[c.OPCION_PASOS] for f in opciones_de(completo, "4230", "E") if f[c.OPCION_NOMBRE] == "ED50 to ETRS89 (7)")
    campos = dict(parte.split("=", 1) for parte in paso.split(";")[1:])

    assert campos["A"] == "6378388.0,297.0"
    assert campos["B"] == "6378137.0,298.257222101"
    assert campos["inv"] == "0" and campos["conv"] == "PV"
    assert [float(v) for v in campos["p"].split(",")] == [-131.0, -100.3, -163.4, -1.244, -0.02, -1.144, 9.39]


def test_s034_rf_4_la_generacion_completa_es_determinista(completo) -> None:
    assert c.a_texto(completo) == c.a_texto(c.generar())
