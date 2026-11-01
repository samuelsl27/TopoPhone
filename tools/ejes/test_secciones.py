"""Las secciones y los peraltes de ejemplo están al día y salen de otro cálculo (S050)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path
from xml.etree import ElementTree

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_secciones as s  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((s.OUT / name).read_text(encoding="utf-8"), newline="")))


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    s.main(tmp_path)
    for name in s.all_files():
        assert (s.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def dz_of(eje: str, pk: float, code: str) -> float:
    return next(float(r["dz"]) for r in rows_of("esperado-tipo.csv") if r["eje"] == eje and float(r["pk"]) == pk and r["codigo"] == code)


def test_el_bombeo_baja_hacia_los_dos_lados():
    # PK 0 del eje A: 2 % en los dos bordes de 3,5 m.
    assert dz_of("Eje A", 0.0, "BD") == pytest.approx(-0.07, abs=1e-12)
    assert dz_of("Eje A", 0.0, "BI") == pytest.approx(-0.07, abs=1e-12)


def test_el_peralte_completo_sube_un_lado_y_baja_el_otro():
    # PK 230 (entre 180 y 280): -6 % a la izquierda y +6 % a la derecha.
    assert dz_of("Eje A", 230.0, "BI") == pytest.approx(3.5 * 0.06, abs=1e-12)
    assert dz_of("Eje A", 230.0, "BD") == pytest.approx(-3.5 * 0.06, abs=1e-12)


def test_el_peralte_varia_linealmente_entre_filas():
    # PK 150 es el punto medio entre 120 (2 %) y 180 (-6 % / 6 %): pendientes 2 % - 4 % = -2 % a la izquierda y +2 % -> 4 % a la derecha.
    assert dz_of("Eje A", 150.0, "BD") == pytest.approx(-3.5 * 0.04, abs=1e-12)
    assert dz_of("Eje A", 150.0, "BI") == pytest.approx(3.5 * 0.02, abs=1e-12)


def test_con_dos_o_mas_filas_fuera_de_la_tabla_vale_la_pendiente_propia():
    # PK 400 está después de la última fila (380) y el tramo vuelve a su 2 % propio.
    assert dz_of("Eje A", 400.0, "BD") == pytest.approx(-0.07, abs=1e-12)


def test_con_una_fila_se_mantiene_en_todo_el_eje():
    # Eje B, una fila (PK 1100, -4 % / 4 %): se mantiene antes y después. El giro es en BD, que queda en 0.
    for pk in (1000.5, 1050.0, 1100.0, 1300.5, 1380.5):
        assert dz_of("Eje B", pk, "BD") == 0.0
        assert dz_of("Eje B", pk, "BI") == pytest.approx(0.28, abs=1e-12)


def test_el_punto_de_rasante_queda_a_la_cota_de_la_rasante():
    for r in rows_of("esperado-puntos.csv"):
        if r["eje"] == "Eje B" and r["codigo"] == "BD":
            import generar_ejes as g

            assert float(r["cota"]) == pytest.approx(g.profile_at(g.AXIS_B.profile, float(r["pk"]))[0], abs=1e-9)


def test_la_planta_es_perpendicular_al_eje():
    rows = rows_of("esperado-puntos.csv")
    for r in rows:
        if r["eje"] == "Eje A" and r["codigo"] in ("BD", "BI"):
            axis = next(a for a in rows if a["eje"] == "Eje A" and a["pk"] == r["pk"] and a["codigo"] == "EJE")
            d = math.hypot(float(r["este"]) - float(axis["este"]), float(r["norte"]) - float(axis["norte"]))
            assert d == pytest.approx(abs(float(r["desplazamiento"])), abs=1e-9)


def test_la_interpolacion_entre_secciones_explicitas_es_lineal():
    # Eje C, PK 25 entre 0 (100,30) y 100 (101,70) en el eje, y 6 -> 7 en el borde derecho.
    pts = {r["codigo"]: r for r in rows_of("esperado-puntos.csv") if r["eje"] == "Eje C" and float(r["pk"]) == 25.0}
    assert float(pts["EJE"]["cota"]) == pytest.approx(100.65, abs=1e-12)
    assert float(pts["BD"]["desplazamiento"]) == pytest.approx(6.25, abs=1e-12)


def test_resolucion_con_codigos_que_faltan_y_fuera_de_rango():
    res = {(r["eje"], float(r["pk"])): r for r in rows_of("esperado-resolucion.csv")}
    assert res[("Eje C", 125.0)]["estado"] == "faltan"
    assert res[("Eje C", 125.0)]["faltan"] == "CD;CI"
    assert res[("Eje C", 195.0)]["estado"] == "fuera"
    assert res[("Eje C", 175.0)]["estado"] == "ok"


def test_mas_alla_de_los_puntos_exteriores_esta_fuera_de_la_seccion():
    rows = [r for r in rows_of("esperado-desplazamientos.csv") if r["eje"] == "Eje A" and float(r["pk"]) == 0.0]
    by = {float(r["desplazamiento"]): r["cota"] for r in rows}
    assert by[12.0] == "fuera" and by[-12.0] == "fuera"
    assert by[0.0] == "100.0"


def test_la_guia_de_la_reproduccion_cambia_la_cota_de_diseno_por_la_diferencia_de_la_seccion():
    guide = rows_of("esperado-guia-seccion.csv")
    assert len(guide) == 43
    design = float(guide[0]["cota_diseno"])
    # rasante en el PK 150 (102,5833...) menos 3,5 x 4 % del peralte de ese PK, más 10 cm de espesor
    assert design == pytest.approx(102.58333333333333 - 0.14 + 0.1, abs=1e-12)
    for row in guide:
        assert float(row["d_cota"]) == pytest.approx(float(row["altura"]) - design, abs=1e-12)


def test_el_landxml_sintetico_tiene_diseno_terreno_y_puntos_sin_nombre():
    root = ElementTree.fromstring((s.OUT / "secciones-eje-c.xml").read_bytes())
    ns = {"l": s.g.NS}
    sections = root.findall(".//l:CrossSect", ns)
    assert len(sections) == 5
    assert root.findall(".//l:DesignCrossSectSurf", ns)
    assert any(x.get("state") == "existing" for x in root.findall(".//l:CrossSectSurf", ns))
    unnamed = [p for p in root.findall(".//l:CrossSectPnt", ns) if p.get("code") is None]
    assert len(unnamed) == 3
