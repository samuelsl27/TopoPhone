"""Las referencias de la información geométrica están al día y cumplen lo que se sabe de su construcción (S026)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_referencias as g  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def find(name: str, case: str, mode: str) -> dict[str, str]:
    return next(r for r in rows_of(name) if r["caso"] == case and r["modo"] == mode)


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        assert (g.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def test_el_area_del_poligono_concavo_en_cuadricula_es_la_del_dibujo():
    # 200 x 50 más 100 x 100 del lado izquierdo = 20.000 m².
    assert math.isclose(float(find("totales.csv", "concavo", "grid")["area"]), 20000.0, abs_tol=1e-6)
    assert find("totales.csv", "concavo", "grid")["cruces"] == "0"


def test_el_area_de_terreno_es_mayor_que_la_de_cuadricula_a_700_m_de_altura():
    grid = float(find("totales.csv", "parcela", "grid")["area"])
    ground = float(find("totales.csv", "parcela", "ground")["area"])
    assert ground > grid


def test_el_lazo_tiene_cruces_y_la_parcela_no():
    assert find("totales.csv", "cruces", "grid")["cruces"] == "1"
    assert find("totales.csv", "parcela", "grid")["cruces"] == "0"


def test_el_angulo_recto_mide_pi_medios_y_los_azimuts_son_norte_y_este():
    row = find("angulos.csv", "angulo_recto", "grid")
    assert math.isclose(float(row["angulo_rad"]), math.pi / 2, abs_tol=1e-9)
    assert math.isclose(float(row["azimut_ba_rad"]), 0.0, abs_tol=1e-9)
    assert math.isclose(float(row["azimut_bc_rad"]), math.pi / 2, abs_tol=1e-9)


def test_el_segmento_vertical_no_tiene_pendiente_y_el_que_no_tiene_cota_tampoco_longitud_3d():
    vertical = [r for r in rows_of("segmentos.csv") if r["caso"] == "vertical" and r["modo"] == "grid"]
    assert vertical[1]["pendiente_pct"] == "" and float(vertical[1]["desnivel"]) == 10.0
    sin_cota = [r for r in rows_of("segmentos.csv") if r["caso"] == "sin_cota" and r["modo"] == "grid"]
    assert sin_cota[0]["longitud_3d"] == "" and sin_cota[1]["longitud_3d"] == "" and sin_cota[2]["longitud_3d"] != ""
    assert find("totales.csv", "sin_cota", "grid")["longitud_3d"] == ""


def test_el_terreno_de_una_recta_a_700_m_es_mayor_que_la_cuadricula_en_el_factor_combinado():
    grid = float(find("totales.csv", "recta", "grid")["longitud_2d"])
    ground = float(find("totales.csv", "recta", "ground")["longitud_2d"])
    assert math.isclose(grid, 300.0, abs_tol=1e-4)
    assert 1.0003 < ground / grid < 1.0006  # k ≈ 0,9996 y h ≈ 690 m: 1 / (0,9996 · 0,99989)


def test_en_geografico_el_terreno_coincide_con_la_cuadricula():
    assert find("totales.csv", "geografico", "grid")["longitud_2d"] == find("totales.csv", "geografico", "ground")["longitud_2d"]
