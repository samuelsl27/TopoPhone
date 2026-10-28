"""La referencia de la exportación a GeoPackage está al día, sale de pyproj y se lee con otro lector (S053)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

import pytest
from pyproj import CRS, Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_gpkg_exportado as g  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def test_los_puntos_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    assert (g.OUT / "esperado-puntos.csv").read_bytes() == (tmp_path / "esperado-puntos.csv").read_bytes()


def test_las_definiciones_de_los_sistemas_son_las_de_pyproj_aunque_el_texto_cambie_con_proj():
    rows = rows_of("esperado-srs.csv")
    assert [int(r["epsg"]) for r in rows] == [code for code, _ in g.SRS]
    for row in rows:
        code = int(row["epsg"])
        defined = CRS.from_wkt(row["definicion"])
        assert defined.to_epsg() == code or defined.equals(CRS.from_epsg(code), ignore_axis_order=True), code


def test_cada_sistema_de_la_app_es_proyectado_o_geografico_como_dice_su_codigo():
    projected = {25829, 25830, 25831, 4082, 4083, 23029, 23030, 23031}
    for code, _ in g.SRS:
        assert CRS.from_epsg(code).is_projected == (code in projected), code


def test_este_y_norte_vuelven_a_la_latitud_y_longitud_a_menos_de_un_milimetro():
    back = {code: Transformer.from_crs(f"EPSG:{code}", f"EPSG:{g.GEOGRAPHIC_OF[code]}", always_xy=True) for code in g.SAMPLES}
    rows = rows_of("esperado-puntos.csv")
    assert len(rows) == sum(len(s) for s in g.SAMPLES.values())
    for r in rows:
        lon, lat = back[int(r["epsg"])].transform(float(r["este"]), float(r["norte"]))
        # 1 mm de arco de meridiano son 9e-9 grados
        assert abs(lat - float(r["latitud"])) < 9e-9 and abs(lon - float(r["longitud"])) < 1.2e-8, r["punto"]


ROWS = [
    ("A", 440000.123456789, 4474000.987654321, 648.25, 40.4168, -3.7038, 24, 0),
    ("B", 440100.5, 4474100.25, None, 40.4169, -3.7037, 18, 1),
    ("C", None, None, 12.0, 40.0, -3.0, None, None),
]


@pytest.fixture
def reference(tmp_path):
    path = tmp_path / "referencia.gpkg"
    g.build_reference(path, 25830, ROWS)
    return g.read_gpkg(path)


def test_la_referencia_declara_geopackage_y_los_sistemas_obligatorios_mas_el_de_la_capa(reference):
    assert reference.application_id == g.APPLICATION_ID
    assert reference.user_version == g.USER_VERSION
    assert {"gpkg_spatial_ref_sys", "gpkg_contents", "gpkg_geometry_columns", g.LAYER} <= reference.tables
    assert set(reference.srs) == {-1, 0, 4326, 25830}
    organization, code, definition = reference.srs[25830]
    assert (organization, code) == ("EPSG", 25830)
    assert CRS.from_wkt(definition).to_epsg() == 25830


def test_la_referencia_declara_la_capa_de_puntos_con_su_sistema_y_la_z_opcional(reference):
    assert [c[0] for c in reference.contents] == [g.LAYER]
    name, kind, identifier, min_x, min_y, max_x, max_y, srs_id = reference.contents[0]
    assert (kind, identifier, srs_id) == ("features", "points", 25830)
    assert (min_x, min_y, max_x, max_y) == (440000.123456789, 4474000.987654321, 440100.5, 4474100.25)
    assert reference.geometry_columns == [("points", "geom", "POINT", 25830, 2, 0)]


def test_los_atributos_llevan_su_tipo_y_el_nombre_de_la_columna(reference):
    declared = dict(reference.columns)
    assert declared["Name"] == "TEXT" and declared["Satellites"] == "INTEGER" and declared["Easting"] == "REAL"
    assert declared["Replay"] == "BOOLEAN" and declared["fid"] == "INTEGER" and declared["geom"] == "POINT"


def test_la_geometria_lleva_la_z_si_el_punto_tiene_altura_y_esta_vacia_si_falta_el_este_o_el_norte(reference):
    a, b, c = (row["geom"] for row in reference.rows)
    assert (a.srs_id, a.kind, a.coords) == (25830, 1001, (440000.123456789, 4474000.987654321, 648.25))
    assert (b.kind, b.coords) == (1, (440100.5, 4474100.25))
    assert c.empty and all(math.isnan(v) for v in (c.coords or (math.nan,)))


def test_los_vacios_se_leen_como_nulos_y_los_valores_no_se_redondean(reference):
    a, b, c = reference.rows
    assert a["Easting"] == 440000.123456789 and a["Elevation"] == 648.25
    assert b["Elevation"] is None and b["Replay"] == 1
    assert c["Satellites"] is None and c["Replay"] is None


# ---------------------------------------------------------------------------------------- los GeoPackage dorados de la app
# (fichero, EPSG de los puntos de esperado-puntos.csv, EPSG de la capa)
GOLDEN = (
    ("exportado-25829.gpkg", 25829, 25829),
    ("exportado-25830.gpkg", 25830, 25830),
    ("exportado-25831.gpkg", 25831, 25831),
    ("exportado-4082.gpkg", 4082, 4082),
    ("exportado-4083.gpkg", 4083, 4083),
    ("exportado-25830-geograficas.gpkg", 25830, 4258),
    ("exportado-4083-geograficas.gpkg", 4083, 4081),
)


def samples_of(epsg: int) -> list[dict[str, str]]:
    return [r for r in rows_of("esperado-puntos.csv") if int(r["epsg"]) == epsg]


@pytest.mark.parametrize(("name", "points_epsg", "layer_epsg"), GOLDEN)
def test_el_dorado_se_declara_geopackage_con_el_sistema_de_la_capa_y_su_definicion(name, points_epsg, layer_epsg):
    info = g.read_gpkg(g.OUT / name)

    assert info.application_id == g.APPLICATION_ID and info.user_version == g.USER_VERSION
    assert {"gpkg_spatial_ref_sys", "gpkg_contents", "gpkg_geometry_columns", g.LAYER} <= info.tables
    assert set(info.srs) == {-1, 0, 4326, layer_epsg}
    organization, code, definition = info.srs[layer_epsg]
    assert (organization, code) == ("EPSG", layer_epsg)
    assert CRS.from_wkt(definition).to_epsg() == layer_epsg or CRS.from_wkt(definition).equals(CRS.from_epsg(layer_epsg), ignore_axis_order=True)
    assert [c[0] for c in info.contents] == [g.LAYER] and info.contents[0][7] == layer_epsg
    assert info.geometry_columns == [("points", "geom", "POINT", layer_epsg, 2, 0)]


@pytest.mark.parametrize(("name", "points_epsg", "layer_epsg"), GOLDEN)
def test_el_dorado_tiene_los_mismos_puntos_con_las_coordenadas_de_pyproj_a_menos_de_1_mm(name, points_epsg, layer_epsg):
    info = g.read_gpkg(g.OUT / name)
    expected = samples_of(points_epsg)
    to_projected = Transformer.from_crs(f"EPSG:{g.GEOGRAPHIC_OF[points_epsg]}", f"EPSG:{points_epsg}", always_xy=True)
    assert [r["Name"] for r in info.rows] == [r["punto"] for r in expected]
    geographic = layer_epsg != points_epsg
    for row, sample in zip(info.rows, expected):
        lat, lon = float(sample["latitud"]), float(sample["longitud"])
        east, north = to_projected.transform(lon, lat)
        # lo que guarda la app son las coordenadas del punto, sin redondear
        assert row["Easting"] == float(sample["este"]) and row["Northing"] == float(sample["norte"])
        assert row["Latitude"] == lat and row["Longitude"] == lon
        # y cada una coincide con la de pyproj
        assert abs(row["Easting"] - east) < 1e-3 and abs(row["Northing"] - north) < 1e-3
        geom = row["geom"]
        assert not geom.empty and geom.srs_id == layer_epsg
        x, y = geom.coords[0], geom.coords[1]
        assert (x, y) == ((lon, lat) if geographic else (row["Easting"], row["Northing"]))


@pytest.mark.parametrize(("name", "points_epsg", "layer_epsg"), GOLDEN)
def test_el_dorado_lleva_la_altura_como_tercera_coordenada_solo_en_los_puntos_que_la_tienen(name, points_epsg, layer_epsg):
    info = g.read_gpkg(g.OUT / name)
    for index, row in enumerate(info.rows):
        geom = row["geom"]
        if index % 2 == 0:
            assert geom.kind == 1001 and geom.coords[2] == row["Elevation"] == 500.5 + index
        else:
            assert geom.kind == 1 and row["Elevation"] is None


def test_el_dorado_declara_el_tipo_de_cada_atributo_y_deja_vacio_lo_que_el_punto_no_tiene():
    info = g.read_gpkg(g.OUT / "exportado-25830.gpkg")
    declared = dict(info.columns)

    assert declared["Name"] == "TEXT" and declared["Easting"] == "REAL" and declared["Satellites"] == "INTEGER"
    assert declared["Replay"] == "BOOLEAN" and declared["Ellipsoidal height"] == "REAL" and declared["geom"] == "POINT"
    first = info.rows[0]
    assert first["Origin"] == "Manual" and first["CRS"] == "EPSG:25830" and first["Edited"] == 0
    assert first["Satellites"] is None and first["Solution"] is None and first["Replay"] is None


def test_la_extension_de_la_capa_es_la_de_los_puntos():
    info = g.read_gpkg(g.OUT / "exportado-25830.gpkg")
    xs = [r["geom"].coords[0] for r in info.rows]
    ys = [r["geom"].coords[1] for r in info.rows]
    assert (info.contents[0][3], info.contents[0][4], info.contents[0][5], info.contents[0][6]) == (min(xs), min(ys), max(xs), max(ys))
