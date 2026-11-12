"""La referencia de la exportación a Shapefile está al día, sale de la especificación y se lee con otro lector (S055)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

import pytest
from pyproj import CRS, Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_shp_exportado as g  # noqa: E402

MILLIMETRE_M = 1e-3


def text_of(name: str) -> str:
    return (g.OUT / name).read_text(encoding="utf-8")


def bytes_of(name: str) -> bytes:
    return (g.OUT / name).read_bytes()


# ---------------------------------------------------------------------------------------------------------------- al día
def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        if name.endswith(".prj"):
            continue  # el texto del WKT cambia con PROJ: se comprueba por el sistema que define
        assert bytes_of(name) == (tmp_path / name).read_bytes(), name


def test_hay_un_prj_por_cada_sistema_de_la_app():
    assert sorted(p.stem for p in (g.OUT / "prj").glob("*.prj")) == sorted(str(code) for code, _ in g.PRJ_SYSTEMS)


# ---------------------------------------------------------------------------------------------------------------- el .dbf
def test_texto_recortado_a_254_bytes_sin_partir_un_caracter():
    exact = "a" * 254
    assert g.truncate_utf8(exact) == (exact.encode(), False)
    assert g.truncate_utf8(exact + "b") == (exact.encode(), True)
    boundary, cut = g.truncate_utf8("a" * 253 + "é")  # la «é» ocuparía los bytes 254 y 255
    assert (len(boundary), cut) == (253, True)
    assert boundary.decode("utf-8") == "a" * 253
    three, cut = g.truncate_utf8("a" * 252 + "€")  # «€» son 3 bytes
    assert (len(three), cut) == (252, True)
    emoji, cut = g.truncate_utf8("a" * 252 + "😀")  # 4 bytes
    assert (len(emoji), cut) == (252, True)


def test_decimales_con_nueve_decimales_del_valor_exacto_sin_cero_negativo():
    assert g.decimal_text(440000.123456789) == "440000.123456789"
    assert g.decimal_text(0.5) == "0.500000000"
    assert g.decimal_text(-3.7038) == "-3.703800000"
    assert g.decimal_text(-1e-12) == "0.000000000"
    assert g.decimal_text(0.0) == "0.000000000"
    # 0.1 + 0.2 = 0.30000000000000004: el valor exacto manda, no la forma corta del número
    assert g.decimal_text(0.1 + 0.2) == "0.300000000"
    # una mitad exacta en binario (2^-10 = 0.0009765625) se redondea al par: 0.000976562 y no 0.000976563
    assert g.decimal_text(2**-10) == "0.000976562"
    assert g.decimal_text(3 * 2**-10) == "0.002929688"  # 0.0029296875 -> el 7 es impar: sube al 8


def test_un_decimal_que_no_cabe_en_20_caracteres_pierde_decimales():
    assert g.decimal_text(123456789012.5) == "123456789012.5000000"  # 12 cifras enteras: caben 7 decimales
    text = g.decimal_text(1e12)
    assert text is not None and len(text) == 20 and text.startswith("1000000000000.")
    assert g.decimal_text(1e25) is None
    assert g.decimal_text(-9999999999.5) == "-9999999999.50000000"  # el signo cuenta: 8 decimales


@pytest.mark.parametrize("kind", [g.POINT, g.LINE, g.POLYGON])
def test_el_dbf_se_lee_con_otro_lector_y_dice_lo_que_se_escribio(kind):
    sample = g.SAMPLES[kind]
    dbf = g.read_dbf(bytes_of(f"ejemplo-{g.LAYERS[kind]}.dbf"))
    assert dbf.date == g.DBF_DATE
    assert dbf.language == 0
    assert [c[0] for c in dbf.columns] == [c.name for c in sample.columns]
    assert all(len(c[0]) <= 10 and c[0] == c[0].upper() and c[0].isascii() for c in dbf.columns)
    assert len(dbf.rows) == len(sample.rows)
    for (_, kind_code, _, decimals), column in zip(dbf.columns, sample.columns):
        assert kind_code == ("C" if column.kind == "C" else "N")
        assert decimals == (9 if column.kind == "D" else 0)


def test_los_valores_del_dbf_de_puntos_vuelven_tal_cual():
    dbf = g.read_dbf(bytes_of("ejemplo-puntos.dbf"))
    widths = {name: width for name, _, width, _ in dbf.columns}
    assert widths["NOMBRE"] == 2  # el ancho es el del mayor valor
    assert widths["SATELITES"] == 7
    assert widths["REPRODUC"] == 1
    assert widths["ESTE"] == 20
    assert widths["MED_INICIO"] == 24
    assert widths["NOTAS"] == 253  # el texto de 255 bytes se recortó sin partir la «é»
    by_name = [dict(zip((c[0] for c in dbf.columns), row)) for row in dbf.rows]
    first = by_name[0]
    assert first["NOTAS"] == "Árbol ñandú €"  # ancho en bytes, no en caracteres
    assert float(first["ESTE"]) == 440000.123456789 and float(first["NORTE"]) == 4474000.987654321
    assert first["MED_INICIO"] == "2026-10-28T09:15:30.250Z"
    assert by_name[1]["CODIGO"] == "" and by_name[1]["COTA"] == "" and by_name[1]["MED_INICIO"] == ""  # vacío = espacios
    assert by_name[2]["ESTE"] == "" and by_name[2]["NORTE"] == "" and by_name[2]["SATELITES"] == ""
    assert by_name[3]["SATELITES"] == "1234567" and by_name[3]["COTA"] == "-12.500000000"
    assert by_name[3]["NOTAS"] == "x" * 253


def test_el_recorte_se_cuenta():
    sample = g.SAMPLES[g.POINT]
    assert g.dbf_bytes(sample.columns, sample.rows)[1] == 1
    assert g.dbf_bytes(g.SAMPLES[g.LINE].columns, g.SAMPLES[g.LINE].rows)[1] == 0


def test_el_cpg_declara_utf8():
    for layer in g.LAYERS.values():
        assert bytes_of(f"ejemplo-{layer}.cpg") == b"UTF-8"


# ------------------------------------------------------------------------------------------------------------------ el .shp
@pytest.mark.parametrize("kind", [g.POINT, g.LINE, g.POLYGON])
def test_el_shp_y_el_shx_se_leen_con_otro_lector_y_coinciden(kind):
    name = f"ejemplo-{g.LAYERS[kind]}"
    shp = g.read_shp(bytes_of(f"{name}.shp"))
    shx_type, entries = g.read_shx(bytes_of(f"{name}.shx"))
    sample = g.SAMPLES[kind]
    assert shp.shape_type == shx_type == g.SHAPE_TYPE[kind]
    assert [number for number, _, _ in shp.records] == list(range(1, len(sample.geoms) + 1))
    assert len(entries) == len(shp.records)
    data = bytes_of(f"{name}.shp")
    for (offset, length), (number, _, _) in zip(entries, shp.records):
        assert int.from_bytes(data[offset : offset + 4], "big") == number  # el .shx apunta al registro
        assert int.from_bytes(data[offset + 4 : offset + 8], "big") * 2 == length


def test_los_puntos_son_pointz_con_m_sin_dato_y_el_registro_nulo_donde_no_hay_posicion():
    shp = g.read_shp(bytes_of("ejemplo-puntos.shp"))
    kinds = [kind for _, kind, _ in shp.records]
    assert kinds == [11, 11, 0, 11]
    first = shp.records[0][2]
    assert first == (440000.123456789, 4474000.987654321, 648.25, g.NO_DATA)
    assert shp.records[1][2][2] == 0.0  # sin altura: Z = 0
    assert shp.records[2][2] is None
    # la envolvente son los valores escritos (con el 0 del punto sin altura) y no incluye el registro nulo
    x0, y0, x1, y1, z0, z1, m0, m1 = shp.extent
    assert (x0, x1) == (-3.7038, 440100.5) and (y0, y1) == (40.4168, 4474100.25)
    assert (z0, z1) == (-12.5, 648.25) and (m0, m1) == (0.0, 0.0)


def test_las_lineas_son_polylinez_de_una_parte_sin_m_y_con_la_z_del_vertice():
    shp = g.read_shp(bytes_of("ejemplo-lineas.shp"))
    assert [kind for _, kind, _ in shp.records] == [13, 13]
    parts = shp.records[0][2]
    assert len(parts) == 1 and parts[0] == list(g.SAMPLES[g.LINE].geoms[0])
    assert parts[0][2][2] == 0.0
    assert shp.extent[4:6] == (0.0, 651.25)


def test_los_poligonos_van_en_sentido_horario_con_el_primer_vertice_repetido():
    shp = g.read_shp(bytes_of("ejemplo-poligonos.shp"))
    for (_, kind, parts), original in zip(shp.records, g.SAMPLES[g.POLYGON].geoms):
        assert kind == 15 and len(parts) == 1
        ring = parts[0]
        assert ring[0] == ring[-1] == original[0]  # se conserva el primer vértice y se repite al final
        assert len(ring) == len(original) + 1
        assert g.signed_area(tuple(ring[:-1])) < 0  # horario: la fórmula del zapatero da área negativa
        assert sorted(ring[:-1]) == sorted(original)  # los mismos vértices
    # el primero estaba en sentido antihorario: se ha invertido sin perder el primer vértice
    assert g.signed_area(g.SAMPLES[g.POLYGON].geoms[0]) > 0
    assert shp.records[0][2][0][1] == g.SAMPLES[g.POLYGON].geoms[0][-1]
    # el segundo ya era horario y queda como estaba
    assert shp.records[1][2][0][:-1] == list(g.SAMPLES[g.POLYGON].geoms[1])


def test_el_area_con_signo_no_se_pierde_con_coordenadas_grandes():
    # un cuadrado de 1 cm de lado a 4,4 millones de metros: el origen en el primer vértice evita perder el signo
    x, y = 440000.0, 4474000.0
    ccw = ((x, y, 0.0), (x + 0.01, y, 0.0), (x + 0.01, y + 0.01, 0.0), (x, y + 0.01, 0.0))
    assert g.signed_area(ccw) > 0
    assert g.signed_area(tuple(reversed(ccw))) < 0


def test_un_shapefile_sin_ninguna_posicion_lleva_la_envolvente_a_cero():
    shp_bytes, shx_bytes = g.shp_bytes(g.POINT, (None, None))
    shp = g.read_shp(shp_bytes)
    assert shp.extent == (0.0,) * 8 and [k for _, k, _ in shp.records] == [0, 0]
    assert g.read_shx(shx_bytes)[1] == [(100, 4), (112, 4)]


# ---------------------------------------------------------------------------------------------------------------- los .prj
def _crs(epsg: int) -> CRS:
    return CRS.from_wkt((g.OUT / "prj" / f"{epsg}.prj").read_text(encoding="ascii"))


@pytest.mark.parametrize("epsg", [code for code, _ in g.PRJ_SYSTEMS])
def test_cada_prj_es_el_sistema_de_su_proyecto_leido_con_pyproj(epsg):
    projected = epsg not in (4258, 4081)
    defined = _crs(epsg)
    assert defined.is_projected == projected
    reference = CRS.from_epsg(epsg)
    # El WKT de ESRI no lleva el código ni los ejes: se comprueba que define las mismas coordenadas para un punto de cada zona.
    points = {25829: (-8.7207, 42.2406), 25830: (-3.7038, 40.4168), 25831: (2.1686, 41.3874), 4082: (-18.16, 27.7), 4083: (-16.2518, 28.4636)}
    lon, lat = points.get(epsg, (-3.7038, 40.4168))
    if projected:
        geographic = reference.geodetic_crs
        east, north = Transformer.from_crs(geographic, reference, always_xy=True).transform(lon, lat)
        back_east, back_north = Transformer.from_crs(reference, defined, always_xy=True).transform(east, north)
        assert math.hypot(back_east - east, back_north - north) < MILLIMETRE_M
    else:
        back_lon, back_lat = Transformer.from_crs(reference, defined, always_xy=True).transform(lon, lat)
        assert abs(back_lat - lat) < 9e-9 and abs(back_lon - lon) < 1.2e-8  # 1 mm de arco de meridiano


def test_el_prj_proyectado_y_el_geografico_de_su_datum_dan_la_misma_latitud_y_longitud_que_pyproj_a_menos_de_un_milimetro():
    geographic_of = {25829: 4258, 25830: 4258, 25831: 4258, 4082: 4081, 4083: 4081}
    rows = list(csv.DictReader(io.StringIO(text_of("esperado-puntos.csv"), newline="")))
    assert rows
    for row in rows:
        epsg = int(row["epsg"])
        to_geographic = Transformer.from_crs(_crs(epsg), _crs(geographic_of[epsg]), always_xy=True)
        lon, lat = to_geographic.transform(float(row["este"]), float(row["norte"]))
        assert abs(lat - float(row["latitud"])) < 9e-9 and abs(lon - float(row["longitud"])) < 1.2e-8, row["punto"]


def test_los_proyectos_ed50_exportan_las_geograficas_de_etrs89():
    # S053/D3: el móvil calcula las geográficas en ETRS89 también en un proyecto ED50; el .prj geográfico es el de 4258
    assert "ETRS_1989" in (g.OUT / "prj" / "4258.prj").read_text(encoding="ascii")


def test_los_prj_son_wkt1_de_esri_y_ascii():
    for epsg, _ in g.PRJ_SYSTEMS:
        text = (g.OUT / "prj" / f"{epsg}.prj").read_text(encoding="ascii")
        assert text.startswith(("PROJCS[", "GEOGCS["))
        assert "AUTHORITY" not in text and "AXIS" not in text and "\n" not in text  # una línea, sin AUTHORITY: es el WKT de ESRI


# ------------------------------------------------------------------------------------------------------------------ la entrada
def test_el_csv_de_entrada_describe_lo_que_se_escribe():
    for kind, sample in g.SAMPLES.items():
        rows = list(csv.reader(io.StringIO(text_of(f"entrada-{g.LAYERS[kind]}.csv"), newline="")))
        assert rows[0] == ["geom"] + [f"{c.name}:{c.kind}" for c in sample.columns]
        assert len(rows) == len(sample.rows) + 1
        for row in rows[1:]:
            assert len(row) == len(sample.columns) + 1
