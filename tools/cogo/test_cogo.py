"""Las referencias de COGO están al día y cumplen lo que se sabe de su construcción (S038)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_cogo as g  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def find(name: str, **keys: str) -> list[dict[str, str]]:
    return [r for r in rows_of(name) if all(r[k] == v for k, v in keys.items())]


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        assert (g.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def test_hay_puntos_en_los_tres_sistemas_y_p7_no_tiene_altura():
    crs = {r["crs"] for r in rows_of("puntos.csv")}
    assert crs == {"ETRS89_UTM30", "REGCAN95_UTM28", "ED50_UTM30"}
    p7 = find("puntos.csv", crs="ETRS89_UTM30", id="P7")[0]
    assert p7["cota"] == "" and p7["altura_elipsoidal"] == ""


def test_el_factor_combinado_de_un_punto_a_650_m_es_algo_menor_que_uno_y_el_terreno_mayor_que_la_cuadricula():
    row = find("inversas.csv", caso="larga", crs="ETRS89_UTM30", modo="grid")[0]
    assert 0.9995 < float(row["factor"]) < 1.0
    assert float(row["dist_terreno"]) > float(row["dist_cuadricula"])


def test_la_inversa_de_puntos_coincidentes_tiene_distancia_cero_y_azimut_vacio():
    row = find("inversas.csv", caso="coincidentes", crs="ETRS89_UTM30", modo="grid")[0]
    assert float(row["dist_cuadricula"]) == 0.0 and row["azimut_directo_rad"] == "" and row["pendiente_pct"] == ""


def test_los_azimuts_directo_e_inverso_difieren_en_pi():
    for row in rows_of("inversas.csv"):
        if row["azimut_directo_rad"]:
            diff = (float(row["azimut_inverso_rad"]) - float(row["azimut_directo_rad"])) % (2 * math.pi)
            assert math.isclose(diff, math.pi, abs_tol=1e-12)


def test_la_inversa_sin_altura_no_da_inclinada_ni_pendiente():
    row = find("inversas.csv", caso="sin_altura_a", crs="ETRS89_UTM30", modo="grid")[0]
    assert row["d_altura"] == "" and row["dist_inclinada"] == "" and row["pendiente_pct"] == ""


def test_dos_circunferencias_que_se_cortan_dan_una_solucion_a_cada_lado():
    rows = find("intersecciones.csv", caso="dd_general", crs="ETRS89_UTM30", modo="grid")
    assert [r["etiqueta"] for r in rows] == ["LEFT", "RIGHT"]
    assert all(r["n_soluciones"] == "2" for r in rows)
    # Las dos soluciones equidistan de A y de B.
    for r in rows:
        assert math.isclose(float(r["t1"]), float(rows[0]["t1"]), abs_tol=1e-9)
        assert math.isclose(float(r["t2"]), float(rows[0]["t2"]), abs_tol=1e-9)


def test_las_circunferencias_tangentes_dan_una_y_las_que_no_se_cortan_ninguna_con_su_motivo():
    assert len(find("intersecciones.csv", caso="dd_tangente", crs="ED50_UTM30", modo="grid")) == 1
    for caso, motivo in (("dd_lejos", "TOO_FAR"), ("dd_dentro", "TOO_CLOSE"), ("dd_concentricos", "CONCENTRIC")):
        rows = find("intersecciones.csv", caso=caso, crs="ED50_UTM30", modo="grid")
        assert len(rows) == 1 and rows[0]["n_soluciones"] == "0" and rows[0]["motivo"] == motivo, caso


def test_la_interseccion_detras_de_un_punto_tiene_distancia_negativa_y_las_paralelas_no_tienen_solucion():
    row = find("intersecciones.csv", caso="bb_detras", crs="ETRS89_UTM30", modo="grid")[0]
    assert float(row["t1"]) < 0 < float(row["t2"])
    for caso in ("bb_paralelas", "bb_coincidentes"):
        row = find("intersecciones.csv", caso=caso, crs="ETRS89_UTM30", modo="grid")[0]
        assert row["n_soluciones"] == "0" and row["motivo"] == "PARALLEL", caso


def test_distancia_direccion_da_dos_soluciones_una_en_el_punto_conocido_y_ninguna_si_la_recta_pasa_lejos():
    rows = find("intersecciones.csv", caso="db_general", crs="ETRS89_UTM30", modo="grid")
    assert [r["etiqueta"] for r in rows] == ["FIRST", "SECOND"]
    assert float(rows[0]["t2"]) < float(rows[1]["t2"])
    miss = find("intersecciones.csv", caso="db_sin_solucion", crs="ETRS89_UTM30", modo="grid")
    assert len(miss) == 1 and miss[0]["motivo"] == "LINE_MISSES"
    assert len(find("intersecciones.csv", caso="db_tangente", crs="ETRS89_UTM30", modo="grid")) == 1


def test_en_terreno_se_escribe_la_distancia_de_terreno_y_la_solucion_es_la_misma():
    grid = find("intersecciones.csv", caso="dd_general", crs="ETRS89_UTM30", modo="grid")[0]
    ground = find("intersecciones.csv", caso="dd_general", crs="ETRS89_UTM30", modo="ground")[0]
    assert float(ground["valor_1"]) > float(grid["valor_1"])  # entre el factor del primer punto, algo menor que 1
    assert ground["este"] == grid["este"] and ground["norte"] == grid["norte"]
