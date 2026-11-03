"""Las tomas lineales de ejemplo están al día y salen de otro cálculo (S052)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_toma_lineal as t  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((t.OUT / name).read_text(encoding="utf-8"), newline="")))


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    t.main(tmp_path)
    for name in t.all_files():
        assert (t.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def test_el_eje_de_rectas_une_los_puntos_y_mide_lo_que_la_poligonal():
    rows = [r for r in rows_of("esperado-eje.csv") if r["caso"] == "rectas"]
    assert [r["tipo"] for r in rows] == ["line"] * 4
    total = sum(float(r["longitud"]) for r in rows)
    assert total == pytest.approx(float(rows_of("esperado-longitud.csv")[-1]["longitud"]), abs=1e-9)


def test_el_eje_con_radios_alterna_rectas_y_arcos_y_cada_tramo_empieza_donde_acaba_el_anterior():
    rows = [r for r in rows_of("esperado-eje.csv") if r["caso"] == "radios"]
    assert [r["tipo"] for r in rows] == ["line", "arc", "line", "arc", "line", "arc", "line"]
    assert float(rows[0]["pk_inicio"]) == 250.0
    for a, b in zip(rows, rows[1:]):
        assert float(b["este_inicio"]) == pytest.approx(float(a["este_fin"]), abs=1e-6)
        assert float(b["norte_inicio"]) == pytest.approx(float(a["norte_fin"]), abs=1e-6)
        assert float(b["pk_inicio"]) == pytest.approx(float(a["pk_inicio"]) + float(a["longitud"]), abs=1e-9)


def test_los_arcos_tienen_la_curvatura_del_radio_dado_y_el_signo_del_giro():
    arcs = [r for r in rows_of("esperado-eje.csv") if r["caso"] == "radios" and r["tipo"] == "arc"]
    assert [abs(float(r["k_inicio"])) for r in arcs] == pytest.approx([1 / 150, 1 / 200, 1 / 100])
    assert [math.copysign(1, float(r["k_inicio"])) for r in arcs] == [-1.0, 1.0, 1.0]


def test_el_eje_con_radios_acaba_en_el_ultimo_punto():
    last = [r for r in rows_of("esperado-eje.csv") if r["caso"] == "radios"][-1]
    assert (float(last["este_fin"]), float(last["norte_fin"])) == pytest.approx((441200.0, 4474300.0), abs=1e-6)


def test_el_pk_de_los_vertices_de_la_rasante_crece_y_el_primero_es_el_inicial():
    pks = [float(r["pk"]) for r in rows_of("esperado-rasante.csv")]
    assert pks[0] == 250.0
    assert pks == sorted(pks)
    assert len(pks) == 5


def test_cada_rechazo_tiene_su_motivo_y_su_vertice():
    expected = {r["caso"]: r for r in rows_of("esperado-rechazos.csv")}
    assert {k: (v["motivo"], v["vertice"]) for k, v in expected.items()} == {
        "pocos_puntos": ("pocos_puntos", ""),
        "duplicado": ("duplicado", "2"),
        "radio_no_cabe": ("radio_no_cabe", "2"),
    }


def test_el_longitudinal_marca_el_fuera_del_eje_y_lo_que_no_tiene_proyeccion():
    marks = {r["nombre"]: r["marca"] for r in rows_of("esperado-longitudinal.csv")}
    assert marks["L8"] == "fuera_del_eje"
    assert marks["L6"] == "ninguna"  # 0,45 m: dentro de la tolerancia de 0,50 m
    assert marks["L11"] == marks["L12"] == "sin_proyeccion"
    assert sum(1 for m in marks.values() if m == "ninguna") == 9


def test_la_diferencia_de_cota_del_longitudinal_es_la_cota_menos_la_rasante():
    for r in rows_of("esperado-longitudinal.csv"):
        if r["d_cota"]:
            cota = {p["nombre"]: float(p["cota"]) for p in rows_of("longitudinal-puntos.csv")}[r["nombre"]]
            assert float(r["d_cota"]) == pytest.approx(cota - float(r["cota_rasante"]), abs=1e-12)


def test_el_transversal_deja_fuera_de_seccion_lo_que_pasa_de_10_cm_y_ordena_el_resto_por_desplazamiento():
    rows = {r["nombre"]: r for r in rows_of("esperado-transversal.csv")}
    assert {n for n, r in rows.items() if r["marca"] == "fuera_de_seccion"} == {"X4", "X8"}
    ordered = sorted((r for r in rows.values() if r["orden"]), key=lambda r: int(r["orden"]))
    assert [r["nombre"] for r in ordered] == ["X7", "X5", "X3", "X6", "X2", "X1"]
    assert [r["codigo"] for r in ordered] == ["P1", "P2", "P3", "P4", "P5", "P6"]


def test_la_diferencia_con_la_seccion_de_diseno_se_interpola_y_queda_vacia_fuera_de_ella():
    rows = {r["nombre"]: r for r in rows_of("esperado-transversal.csv")}
    assert float(rows["X1"]["d_cota"]) == pytest.approx(99.40 - (100.0 - 1.0 * (8.0 - 3.5) / 6.5), abs=1e-12)
    assert float(rows["X6"]["d_cota"]) == pytest.approx(-0.05, abs=1e-12)
    assert rows["X7"]["d_cota"] == ""


def test_la_reproduccion_longitudinal_tiene_42_pk_en_el_eje_3_epocas_a_0_75_m_y_4_mas_alla_del_final():
    rows = rows_of("reproduccion-longitudinal.csv")
    assert len(rows) == t.LONG_EPOCHS == 46
    assert [r["marca"] for r in rows].count("fuera_del_eje") == 3
    assert [r["marca"] for r in rows].count("sin_proyeccion") == 4
    assert sum(1 for r in rows if r["marca"] == "ninguna") == 39
    assert all(r["pk"] == "" for r in rows if r["marca"] == "sin_proyeccion")


def test_la_diferencia_de_cota_del_recorrido_es_la_altura_menos_la_rasante():
    for r in rows_of("reproduccion-longitudinal.csv"):
        if r["d_cota"]:
            assert float(r["d_cota"]) == pytest.approx(float(r["altura"]) - float(r["cota_rasante"]), abs=1e-9)


def test_la_reproduccion_transversal_cruza_la_seccion_de_menos_9_5_a_9_5_m_con_tres_epocas_fuera_de_seccion():
    rows = rows_of("reproduccion-transversal.csv")
    assert [float(r["desplazamiento"]) for r in rows] == [-9.5 + i for i in range(20)]
    assert [r["marca"] for r in rows].count("fuera_de_seccion") == 3
    assert all(r["cota_diseno"] != "" for r in rows)  # la seccion de diseno llega de -10 a 10
    assert [r["marca"] for r in rows].index("fuera_de_seccion") == 3


def test_cada_reproduccion_escribe_una_gga_y_una_gst_por_epoca_con_cr_lf():
    for name, count in (("reproduccion-longitudinal.nmea", 46), ("reproduccion-transversal.nmea", 20)):
        data = (t.OUT / name).read_bytes()
        assert data.count(b"\r\n") == 2 * count
        assert data.count(b"$GNGGA") == data.count(b"$GNGST") == count
