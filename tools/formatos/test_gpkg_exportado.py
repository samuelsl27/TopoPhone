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
