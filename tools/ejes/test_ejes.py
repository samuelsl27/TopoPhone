"""Los ejes y rasantes de ejemplo están al día y su geometría sale de otro cálculo (S049)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path
from xml.etree import ElementTree

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_ejes as g  # noqa: E402

NS = {"l": g.NS}


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((g.OUT / name).read_text(encoding="utf-8"), newline="")))


def test_los_ficheros_guardados_son_los_que_genera_el_script(tmp_path):
    g.main(tmp_path)
    for name in g.all_files():
        assert (g.OUT / name).read_bytes() == (tmp_path / name).read_bytes(), name


def integrate(axis: g.Axis, pk: float) -> tuple[float, float]:
    """Otra vía: Runge-Kutta de cuarto orden sobre la curvatura, con pasos de 1 cm."""
    states = g.boundaries(axis)
    x = y = 0.0  # desde cero: sumar pasos de 1 cm a un Norte de 4,4e6 pierde micras por redondeo
    h = math.pi / 2 - math.radians(axis.azimuth_deg)
    for e, st in zip(axis.elements, states):
        end = min(pk, st.pk + e.length)
        s = st.pk
        while s < end - 1e-12:
            step = min(0.01, end - s)

            def k(at: float) -> float:
                return g.curvature(e, min(max(at - st.pk, 0.0), e.length))

            def f(at: float, hh: float) -> tuple[float, float, float]:
                return math.cos(hh), math.sin(hh), k(at)

            a1 = f(s, h)
            a2 = f(s + step / 2, h + step / 2 * a1[2])
            a3 = f(s + step / 2, h + step / 2 * a2[2])
            a4 = f(s + step, h + step * a3[2])
            x += step / 6 * (a1[0] + 2 * a2[0] + 2 * a3[0] + a4[0])
            y += step / 6 * (a1[1] + 2 * a2[1] + 2 * a3[1] + a4[1])
            h += step / 6 * (a1[2] + 2 * a2[2] + 2 * a3[2] + a4[2])
            s += step
        if pk <= st.pk + e.length:
            break
    return axis.east + x, axis.north + y


@pytest.mark.parametrize("axis", [g.AXIS_A, g.AXIS_B])
def test_la_clotoide_en_serie_coincide_con_la_integracion_numerica(axis):
    for pk in g.sample_pks(axis):
        state, _ = g.point_at(axis, pk)
        x, y = integrate(axis, pk)
        assert math.hypot(state.east - x, state.north - y) < 1e-7, (axis.name, pk)


@pytest.mark.parametrize("axis", g.AXES)
def test_el_azimut_y_el_desplazamiento_son_horarios_y_a_la_derecha(axis):
    for pk in g.sample_pks(axis):
        state, _ = g.point_at(axis, pk)
        e, n = g.offset_point(state, 3.5)
        # a la derecha del rumbo: producto vectorial (rumbo x desplazamiento) negativo en ejes Este-Norte
        tx, ty = math.sin(state.azimuth), math.cos(state.azimuth)
        cross = tx * (n - state.north) - ty * (e - state.east)
        assert cross < 0
        assert math.hypot(e - state.east, n - state.north) == pytest.approx(3.5, abs=1e-9)


def test_los_tramos_son_continuos_y_el_eje_a_empieza_donde_dice():
    for axis in g.AXES:
        states = g.boundaries(axis)
        assert states[0].pk == axis.start_pk
        assert states[-1].pk == pytest.approx(axis.start_pk + g.length_of(axis))
    rows = [r for r in rows_of("esperado-tramos.csv") if r["eje"] == "Eje A"]
    for a, b in zip(rows, rows[1:]):
        assert (a["este_fin"], a["norte_fin"]) == (b["este_inicio"], b["norte_inicio"])


@pytest.mark.parametrize("axis", [g.AXIS_A, g.AXIS_B])
def test_la_rasante_es_continua_y_su_pendiente_tambien(axis):
    vs = axis.profile
    for i in range(1, len(vs) - 1):
        x1, x2, _ = g._curve_span(vs, i)
        eps = 1e-9
        z_a, g_a = g.profile_at(vs, x1 - eps)
        z_b, g_b = g.profile_at(vs, x1 + eps)
        assert z_a == pytest.approx(z_b, abs=1e-6)
        assert g_a == pytest.approx(g_b, abs=1e-6)
        z_a, g_a = g.profile_at(vs, x2 - eps)
        z_b, g_b = g.profile_at(vs, x2 + eps)
        assert z_a == pytest.approx(z_b, abs=1e-6)
        assert g_a == pytest.approx(g_b, abs=1e-6)


def test_el_acuerdo_circular_es_una_circunferencia_tangente():
    vs = g.AXIS_A.profile
    x1, x2, d = g._curve_span(vs, 2)
    for x in (x1, (x1 + x2) / 2, x2):
        z, _ = g.profile_at(vs, x)
        assert math.hypot(x - d["xc"], z - d["zc"]) == pytest.approx(d["r"], abs=1e-9)
    # la longitud del arco que va en el LandXML es R·|Δα|
    a1, a2 = math.atan((101.0 - 103.0) / 150.0), math.atan((102.0 - 101.0) / 120.0)
    assert d["length"] == pytest.approx(2000.0 * abs(a2 - a1))


def landxml_alignments(name: str) -> dict[str, ElementTree.Element]:
    root = ElementTree.fromstring((g.OUT / name).read_text(encoding="utf-8"))
    return {a.get("name"): a for a in root.findall(".//l:Alignment", NS)}


def test_el_landxml_declara_los_extremos_de_cada_tramo_en_norte_este():
    al = landxml_alignments("ejes.xml")["Eje A"]
    tramos = [r for r in rows_of("esperado-tramos.csv") if r["eje"] == "Eje A"]
    elements = list(al.find("l:CoordGeom", NS))
    assert len(elements) == len(tramos)
    for el, row in zip(elements, tramos):
        n0, e0 = (float(v) for v in el.find("l:Start", NS).text.split())
        n1, e1 = (float(v) for v in el.find("l:End", NS).text.split())
        assert (e0, n0, e1, n1) == (float(row["este_inicio"]), float(row["norte_inicio"]), float(row["este_fin"]), float(row["norte_fin"]))


def test_el_landxml_en_pies_es_el_mismo_eje():
    meters = landxml_alignments("ejes.xml")["Eje A"]
    feet = landxml_alignments("eje-a-pies.xml")["Eje A"]
    assert float(feet.get("length")) * g.FOOT == pytest.approx(float(meters.get("length")), abs=1e-9)
    n_m, e_m = (float(v) for v in meters.find("l:CoordGeom/l:Line/l:Start", NS).text.split())
    n_f, e_f = (float(v) for v in feet.find("l:CoordGeom/l:Line/l:Start", NS).text.split())
    assert (n_f * g.FOOT, e_f * g.FOOT) == (pytest.approx(n_m, abs=1e-6), pytest.approx(e_m, abs=1e-6))


def test_el_resumen_cubre_cada_fichero_con_alineaciones():
    files = {r["archivo"] for r in rows_of("esperado-resumen.csv")}
    assert files == {name for name in g.files() if name.startswith("eje")}


def test_los_casos_sin_alineaciones_y_mal_formados():
    assert landxml_alignments("sin-alineaciones.xml") == {}
    with pytest.raises(ElementTree.ParseError):
        ElementTree.fromstring((g.OUT / "mal-formado.xml").read_text(encoding="utf-8"))


def test_el_csv_de_rasante_lleva_la_coma_decimal_y_el_kv_solo_en_el_vertice_con_acuerdo():
    lines = (g.OUT / "rasante-b.csv").read_text(encoding="utf-8").splitlines()
    assert lines[0] == "PK;Cota;Kv"
    assert lines[1] == "1000,5;50,0;"
    assert lines[2] == "1200,0;52,0;2000,0"
