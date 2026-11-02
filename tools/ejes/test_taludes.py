"""Los puntos de talud de ejemplo están al día y salen de otro cálculo (S051)."""

from __future__ import annotations

import csv
import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_taludes as t  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((t.OUT / name).read_text(encoding="utf-8"), newline="")))


def cases() -> dict[str, dict[str, str]]:
    return {r["caso"]: r for r in rows_of("casos.csv")}


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    t.main(tmp_path)
    for name in t.all_files():
        assert (t.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def test_cada_estado_esta_representado():
    assert {r["estado"] for r in cases().values()} == {"found", "edge", "sin_interseccion", "sin_terreno"}


def test_el_desmonte_de_la_derecha_corta_a_1_5_m_con_una_altura_de_1_m():
    c = cases()["desmonte-derecha"]
    assert (c["tipo"], float(c["t"]), float(c["altura"])) == ("CUT", 1.5, 1.0)
    assert float(c["cota_talud"]) == pytest.approx(100.0 + 1.5 / 1.5, abs=1e-12)


def test_la_izquierda_es_la_imagen_de_la_derecha_en_planta():
    right, left = cases()["desmonte-derecha"], cases()["desmonte-izquierda"]
    assert float(left["t"]) == float(right["t"])
    assert float(left["desp_talud"]) == -float(right["desp_talud"])
    # Este eje va al Este: la derecha está al Sur y la izquierda al Norte, a la misma distancia del eje.
    assert float(right["norte"]) - 4474000.0 == pytest.approx(-(float(left["norte"]) - 4474000.0), abs=1e-9)
    assert float(right["este"]) == float(left["este"])


def test_en_cada_caso_con_punto_la_altura_es_la_distancia_entre_la_relacion_y_el_punto_esta_en_el_talud():
    for c in cases().values():
        if c["estado"] != "found":
            continue
        ratio = float(c["razon_desmonte"] if c["tipo"] == "CUT" else c["razon_terraplen"])
        t_dist, edge_z = float(c["t"]), float(c["borde_cota"])
        sign = 1.0 if c["tipo"] == "CUT" else -1.0
        assert float(c["cota_talud"]) == pytest.approx(edge_z + sign * t_dist / ratio, abs=1e-9), c["caso"]
        assert float(c["altura"]) == pytest.approx(t_dist / ratio, abs=1e-9), c["caso"]


def test_el_punto_de_un_perfil_esta_sobre_el_terreno_interpolado_entre_sus_puntos():
    profiles: dict[str, list[tuple[float, float]]] = {}
    for r in rows_of("perfiles.csv"):
        profiles.setdefault(r["perfil"], []).append((float(r["desplazamiento"]), float(r["cota"])))
    checked = 0
    for c in cases().values():
        if c["estado"] != "found" or c["terreno"] == "TIN":
            continue
        d, z = float(c["desp_talud"]), float(c["cota_talud"])
        pts = sorted(profiles[c["terreno"]])
        for (d0, z0), (d1, z1) in zip(pts, pts[1:]):
            if d0 <= d <= d1:
                assert z == pytest.approx(z0 + (z1 - z0) * (d - d0) / (d1 - d0), abs=1e-9), c["caso"]
                checked += 1
                break
    assert checked == 3


def test_el_punto_del_tin_esta_sobre_el_plano_de_uno_de_sus_triangulos():
    triangles = [[float(v) for v in r.values()] for r in rows_of("tin.csv")]

    def height(x: float, y: float) -> float | None:
        for x1, y1, z1, x2, y2, z2, x3, y3, z3 in triangles:
            den = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
            a = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / den
            b = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / den
            c = 1 - a - b
            if min(a, b, c) >= -1e-12:
                return a * z1 + b * z2 + c * z3
        return None

    checked = 0
    for c in cases().values():
        if c["estado"] != "found" or c["terreno"] != "TIN":
            continue
        z = height(float(c["este"]), float(c["norte"]))
        assert z == pytest.approx(float(c["cota_talud"]), abs=1e-9), c["caso"]
        checked += 1
    assert checked == 2


def replay() -> list[dict[str, str]]:
    return rows_of("reproduccion-talud.csv")


def test_la_reproduccion_tiene_treinta_epocas_y_su_nmea_dos_frases_por_epoca():
    assert len(replay()) == 30
    assert (t.OUT / "reproduccion-talud.nmea").read_bytes().count(b"\r\n") == 60


def test_la_reproduccion_sube_por_la_seccion_y_cruza_el_talud_de_diseno():
    dz = [float(r["d_cota"]) for r in replay()]
    assert dz[0] < 0 < dz[29]
    assert float(replay()[29]["desplazamiento"]) > float(replay()[0]["desplazamiento"])


def test_la_distancia_a_moverse_es_la_relacion_por_la_diferencia_de_cota():
    for r in replay():
        assert float(r["mover"]) == pytest.approx(1.5 * float(r["d_cota"]), abs=1e-12)


def test_solo_se_encuentra_el_punto_en_cota_y_en_la_linea_de_la_seccion():
    found = [int(r["epoca"]) for r in replay() if r["encontrado"] == "true"]
    assert found == [15, 16, 17]  # las épocas 21 a 23 están en cota pero fuera de la línea
    assert all(abs(float(r["d_pk"])) > 0.025 for r in replay() if r["en_linea"] == "false")
    off_line_in_height = [r for r in replay() if r["en_linea"] == "false" and abs(float(r["d_cota"])) <= 0.03]
    assert len(off_line_in_height) >= 3
