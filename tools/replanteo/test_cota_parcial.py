"""Las referencias de cota parcial están al día y cumplen lo que se sabe de su construcción (S027/RF-8, RF-9)."""

from __future__ import annotations

import csv
import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_cota_parcial as g  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def height(case: str, pk: str) -> str:
    return next(r["cota"] for r in rows_of("esperado.csv") if r["caso"] == case and float(r["pk"]) == float(pk))


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        assert (g.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def test_sin_cota_antes_del_primer_vertice_con_altura_y_despues_del_ultimo():
    assert height("abierta_extremos", "0") == ""
    assert height("abierta_extremos", "25") == ""
    assert height("abierta_extremos", "175") == ""
    assert height("abierta_extremos", "200") == ""


def test_se_interpola_saltando_los_vertices_sin_altura():
    # De la cota 10 en el PK 50 a la 20 en el PK 150: 0,1 m por metro.
    assert float(height("abierta_extremos", "75")) == 12.5
    assert float(height("abierta_extremos", "100")) == 15.0
    assert float(height("abierta_extremos", "150")) == 20.0


def test_con_todas_las_alturas_es_la_interpolacion_de_siempre():
    assert float(height("abierta_completa", "25")) == 6.0
    assert float(height("abierta_completa", "75")) == 6.5


def test_en_un_poligono_el_cierre_tambien_interpola():
    # Del PK 70 (cota 20) al 140 = 0 (cota 10).
    assert abs(float(height("poligono_parcial", "100")) - (20 - 10 * 30 / 70)) < 1e-9
    assert float(height("poligono_parcial", "0")) == 10.0
    assert float(height("poligono_parcial", "140")) == 10.0
    assert height("poligono_parcial", "15") != ""


def test_un_solo_vertice_con_altura_solo_da_cota_en_su_pk():
    assert float(height("un_solo_vertice", "50")) == 8.0
    assert height("un_solo_vertice", "25") == ""
    assert height("un_solo_vertice", "75") == ""


def test_sin_alturas_no_hay_cota_en_ningun_pk():
    assert all(r["cota"] == "" for r in rows_of("esperado.csv") if r["caso"] == "sin_alturas")
