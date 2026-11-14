"""Las teselas, las esquinas y las cajas de ejemplo están al día y salen de otro cálculo (S037)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_teselas as g  # noqa: E402

SOURCES = {s.name: s for s in g.SOURCES}


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        assert (g.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def brute_force_count(south: float, west: float, north: float, east: float, level: int) -> int:
    """Otra vía: se prueba cada tesela cercana con su caja en EPSG:3857 (pyproj) contra la caja, sin la fórmula de `floor`."""
    x0, y0 = g.TO_3857.transform(west, south)
    x1, y1 = g.TO_3857.transform(east, north)
    size = g.WORLD_M / 2**level
    first_x = math.floor((x0 + g.WORLD_M / 2) / size) - 1
    first_y = math.floor((g.WORLD_M / 2 - y1) / size) - 1
    found = 0
    last = 2**level - 1
    for x in range(max(0, first_x), min(last, first_x + 400) + 1):
        for y in range(max(0, first_y), min(last, first_y + 400) + 1):
            min_x, min_y, max_x, max_y = g.tile_box_3857(level, x, y)
            if min_x < x1 and max_x > x0 and min_y < y1 and max_y > y0:
                found += 1
    return found


@pytest.mark.parametrize("place", list(g.PLACES))
def test_las_teselas_de_la_caja_son_las_que_la_cortan_en_epsg_3857(place):
    south, west, north, east = g.boxes_of(place)["encuadre"]
    for level in (5, 12, 16, 18, 19):
        x0, x1, y0, y1 = g.cover(south, west, north, east, level)
        assert (x1 - x0 + 1) * (y1 - y0 + 1) == brute_force_count(south, west, north, east, level), (place, level)


def test_lo_que_conto_maplibre_en_el_emulador_sale_del_calculo():
    rows = rows_of("medidos.csv")
    assert len(rows) == len(g.MEASURED)
    for row in rows:
        source = SOURCES[row["source"]]
        mine = g.count(
            float(row["south"]), float(row["west"]), float(row["north"]), float(row["east"]), float(row["map_zoom_min"]), float(row["map_zoom_max"]), source
        )
        assert mine == int(row["tiles"]), row["case"]


def test_los_niveles_de_tesela_de_cada_tipo_de_fuente():
    pnoa, ign, relieve = SOURCES["pnoa"], SOURCES["ign-vector"], SOURCES["ign-relieve"]
    assert g.tile_levels(15, 17, pnoa) == [16, 17, 18]  # una ráster de 256 px pide un nivel más que el zoom del mapa
    assert g.tile_levels(15, 17, ign) == [15, 16, 17]  # una vectorial de 512 px, el mismo
    assert g.tile_levels(15, 17, relieve) == [15]  # acotada a 5..15
    assert g.tile_levels(16.2, 18.5, pnoa) == [17, 18, 19]  # 17,2 → 17 y 19,5 → 20, acotado a 19
    assert g.tile_levels(16.2, 17.49, ign) == [16, 17]
    assert g.tile_levels(20, 22, SOURCES["ofm"]) == []  # por encima del máximo de la fuente: ya no hay nada que bajar
    assert g.covering_level(17.5, pnoa) == 19  # la mitad sube, como el ::round de C++


def test_el_numero_de_teselas_solo_crece_con_el_rango_de_zoom_y_con_la_caja():
    for place in g.PLACES:
        boxes = g.boxes_of(place)
        for source in g.SOURCES:
            small = g.count(*boxes["radio500"], 14, 16, source)
            large = g.count(*boxes["radio5000"], 14, 16, source)
            deeper = g.count(*boxes["radio500"], 14, 18, source)
            assert large >= small and deeper >= small, (place, source.name)


def test_las_esquinas_de_las_teselas_coinciden_con_pyproj_a_menos_de_1_mm():
    rows = rows_of("esquinas.csv")
    assert len(rows) >= 50
    for row in rows:
        level, x, y = int(row["tile_zoom"]), int(row["x"]), int(row["y"])
        saved = [float(row[k]) for k in ("min_x", "min_y", "max_x", "max_y")]
        for a, b in zip(saved, g.tile_box_pyproj(level, x, y)):
            assert abs(a - b) < 0.001, (level, x, y)


def test_las_esquinas_de_la_tesela_0_son_el_mundo_entero():
    min_x, min_y, max_x, max_y = g.tile_box_3857(0, 0, 0)
    assert (min_x, max_x) == (-g.WORLD_M / 2, g.WORLD_M / 2)
    assert (min_y, max_y) == (-g.WORLD_M / 2, g.WORLD_M / 2)


def test_la_caja_exacta_de_cada_circulo_contiene_su_circunferencia_y_la_toca():
    rows = [r for r in rows_of("cajas.csv") if r["kind"] == "radius"]
    assert len(rows) == 12
    for row in rows:
        lat, lon, radius = float(row["lat"]), float(row["lon"]), float(row["radius_m"])
        south, west, north, east = (float(row[k]) for k in ("south", "west", "north", "east"))
        points = g.circle_points(lat, lon, radius)
        assert all(south <= p[0] <= north and west <= p[1] <= east for p in points), row["case"]
        assert min(p[0] for p in points) == south and max(p[1] for p in points) == east
        # Los extremos están a la distancia del radio, a menos de un 0,1 %.
        _, _, dist_north = g.GEOD.inv(lon, lat, lon, north)
        assert abs(dist_north - radius) / radius < 1e-3, row["case"]


def test_la_caja_de_un_rectangulo_con_margen_contiene_las_circunferencias_de_sus_esquinas():
    rows = [r for r in rows_of("cajas.csv") if r["kind"] == "project"]
    assert len(rows) == 12
    for row in rows:
        base = [float(row[k]) for k in ("base_south", "base_west", "base_north", "base_east")]
        margin = float(row["margin_m"])
        south, west, north, east = (float(row[k]) for k in ("south", "west", "north", "east"))
        assert south < base[0] and west < base[1] and north >= base[2] and east >= base[3], row["case"]
        for lat in (base[0], base[2]):
            for lon in (base[1], base[3]):
                for p_lat, p_lon in g.circle_points(lat, lon, margin):
                    assert south <= p_lat <= north and west <= p_lon <= east, row["case"]


def test_ningun_caso_cae_en_el_borde_exacto_de_una_tesela():
    for row in rows_of("teselas.csv"):
        level = int(row["tile_zoom"])
        s, w, n, e = (float(row[k]) for k in ("south", "west", "north", "east"))
        assert all(g.not_on_boundary(v) for v in (g.tile_x(w, level), g.tile_x(e, level), g.tile_y(n, level), g.tile_y(s, level))), row["case"]
