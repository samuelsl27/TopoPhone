"""La referencia de la exportación a DXF está al día, sale de pyproj y se lee con otro lector (S029)."""

from __future__ import annotations

import csv
import io
import sys
from pathlib import Path

from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_dxf_exportado as g  # noqa: E402


def stored(name: str) -> bytes:
    return (g.OUT / name).read_bytes()


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.expected():
        assert stored(name) == (tmp_path / name).read_bytes(), name


def test_s029_rf6_la_tabla_aci_tiene_255_colores_y_los_conocidos_son_los_de_la_tabla_publicada():
    rows = list(csv.DictReader(io.StringIO(stored("aci.csv").decode("utf-8"), newline="")))
    assert [int(r["indice"]) for r in rows] == list(range(1, 256))
    known = {
        1: (255, 0, 0),
        2: (255, 255, 0),
        3: (0, 255, 0),
        7: (255, 255, 255),
        10: (255, 0, 0),
        11: (255, 127, 127),
        12: (204, 0, 0),
        20: (255, 63, 0),
        21: (255, 159, 127),
        22: (204, 51, 0),
        130: (0, 255, 255),
        150: (0, 127, 255),
        170: (0, 0, 255),
        250: (51, 51, 51),
        255: (255, 255, 255),
    }
    for index, rgb in known.items():
        row = rows[index - 1]
        assert (int(row["r"]), int(row["g"]), int(row["b"])) == rgb, index


def test_s029_rf6_el_color_mas_cercano_es_el_exacto_cuando_existe_y_el_menor_indice_en_un_empate():
    assert g.nearest_aci(0xFF0000) == 1  # rojo: el 1 gana al 10 (mismo color) por ser el menor
    assert g.nearest_aci(0xFFFFFF) == 7
    assert g.nearest_aci(0x333333) == 250
    assert g.nearest_aci(0x000000) == 18  # el negro no está: (76,0,0) y (0,0,76) empatan y gana el menor


def test_el_dxf_se_parte_en_pares_y_acaba_en_eof():
    for name in ("referencia-locales.dxf", "referencia-globales.dxf"):
        pairs = g.read_pairs(stored(name).decode("utf-8"))
        assert pairs[0] == (0, "SECTION") and pairs[-1] == (0, "EOF"), name
        assert (9, "$ACADVER") in pairs and (1, "AC1009") in pairs


def points_of(name: str) -> list[tuple[str, float, float, float]]:
    pairs = g.read_pairs(stored(name).decode("utf-8"))
    found = []
    for i, (code, value) in enumerate(pairs):
        if (code, value) == (0, "POINT"):
            fields = dict(pairs[i + 1 : i + 5])
            found.append((fields[8], float(fields[10]), float(fields[20]), float(fields[30])))
    return found


def test_s029_rf11_las_coordenadas_locales_son_el_este_y_el_norte_a_menos_de_0_05_mm():
    found = points_of("referencia-locales.dxf")
    assert len(found) == len(g.POINTS)
    for (layer, x, y, z), p in zip(found, g.POINTS):
        assert layer == (p.code or "SIN_CODIGO")
        assert abs(x - p.east) < 5e-5 and abs(y - p.north) < 5e-5 and abs(z - (p.height or 0.0)) < 5e-5


def test_s029_rf12_las_globales_son_longitud_latitud_y_altura_elipsoidal_de_pyproj_a_menos_de_1_mm():
    back = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
    for (layer, lon, lat, z), p in zip(points_of("referencia-globales.dxf"), g.POINTS):
        east, north = back.transform(lon, lat)
        assert abs(east - p.east) < 1e-3 and abs(north - p.north) < 1e-3, p.name
        assert abs(z - (p.ellipsoidal or 0.0)) < 5e-4, p.name


def test_s029_rf9_un_punto_sin_altura_va_con_z_cero_y_sin_etiqueta_de_cota():
    pairs = g.read_pairs(stored("referencia-locales.dxf").decode("utf-8"))
    p2 = [v for v in g.POINTS if v.height is None][0]
    zs = [float(pairs[i + 4][1]) for i, kv in enumerate(pairs) if kv == (0, "POINT") and pairs[i + 3][1] == g.number(p2.north, 4)]
    assert zs == [0.0]
    cotas = [pairs[i + 5][1] for i, kv in enumerate(pairs) if kv == (8, "COTAS")]
    assert cotas == ["650.124", "651.000"]


def test_s029_rf3_la_polilinea_abierta_y_el_poligono_llevan_los_indicadores_de_r12():
    pairs = g.read_pairs(stored("referencia-locales.dxf").decode("utf-8"))
    flags = [pairs[i + 3][1] for i, kv in enumerate(pairs) if kv == (0, "POLYLINE")]
    assert flags == ["8", "9"]
    assert sum(1 for kv in pairs if kv == (0, "VERTEX")) == 6 and sum(1 for kv in pairs if kv == (0, "SEQEND")) == 2


def test_s029_rf6_cada_capa_de_codigo_lleva_el_color_aci_mas_cercano():
    pairs = g.read_pairs(stored("referencia-locales.dxf").decode("utf-8"))
    colors = {}
    for i, kv in enumerate(pairs):
        if kv == (0, "LAYER"):
            colors[pairs[i + 1][1]] = int(pairs[i + 3][1])
    assert colors["ARBOL"] == g.nearest_aci(0x008000) and colors["BORDE_1"] == g.nearest_aci(0xFF0000)
    assert colors["SIN_CODIGO"] == colors["NOMBRES"] == 7
