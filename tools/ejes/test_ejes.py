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


def test_el_eje_por_vertices_se_encadena_y_acaba_en_el_ultimo_vertice():
    rows = rows_of("esperado-pi.csv")
    for a, b in zip(rows, rows[1:]):
        assert math.hypot(float(a["este_fin"]) - float(b["este_inicio"]), float(a["norte_fin"]) - float(b["norte_inicio"])) < 1e-6
        assert float(a["pk_inicio"]) + float(a["longitud"]) == pytest.approx(float(b["pk_inicio"]), abs=1e-9)
    last_vertex = rows_of("pi-vertices.csv")[-1]
    assert float(rows[-1]["este_fin"]) == pytest.approx(float(last_vertex["este"]), abs=1e-6)
    assert float(rows[-1]["norte_fin"]) == pytest.approx(float(last_vertex["norte"]), abs=1e-6)


# ---------------------------------------------------------------------------------------------------- replanteo por eje (S054)
def series_of(case: str) -> list[float]:
    return [float(r["pk"]) for r in rows_of("replanteo/esperado-series.csv") if r["caso"] == case]


def test_los_puntos_singulares_son_el_cambio_de_clase_de_tramo_y_los_vertices_de_la_rasante():
    sing = {(r["eje"], float(r["pk"])): r["motivo"] for r in rows_of("replanteo/esperado-singulares.csv")}
    assert [pk for (eje, pk) in sing if eje == "Eje A"] == [0.0, 120.0, 150.0, 180.0, 280.0, 300.0, 340.0, 420.0]
    assert sing[("Eje A", 120.0)] == "tramo" and sing[("Eje A", 150.0)] == "rasante" and sing[("Eje A", 0.0)] == "rasante"
    # en el eje B la unión de dos clotoides no cambia la clase de tramo
    assert 1180.5 not in [pk for (eje, pk) in sing if eje == "Eje B"]
    assert not [1 for (eje, _) in sing if eje == "Eje C"]


def test_las_series_de_pk_siguen_las_reglas_de_la_spec():
    assert series_of("A-solo-inicial") == [100.0]
    assert series_of("A-intervalo") == [0.0, 100.0, 200.0, 300.0, 400.0, 420.0]
    assert series_of("A-intervalo-singulares") == [0.0, 100.0, 120.0, 150.0, 180.0, 200.0, 280.0, 300.0, 340.0, 400.0, 420.0]
    assert series_of("A-intervalo-mayor-que-el-tramo") == [100.0, 120.0, 150.0, 180.0, 280.0, 300.0]
    assert series_of("A-hacia-atras") == [420.0, 270.0, 120.0, 0.0]
    assert series_of("A-coinciden-con-singulares") == [0.0, 120.0, 150.0, 180.0, 280.0, 300.0, 340.0, 420.0]
    assert series_of("A-hacia-atras-singulares") == [420.0, 340.0, 300.0, 280.0, 270.0, 180.0, 150.0, 120.0, 0.0]
    assert series_of("A-sin-final") == [300.0, 360.0, 420.0]
    assert series_of("A-singulares-sin-intervalo") == [100.0, 120.0, 150.0, 180.0, 280.0, 300.0, 340.0, 420.0]
    assert series_of("C-sin-rasante") == [0.0, 80.0, 160.0, 200.0]


def test_la_cota_de_diseno_es_la_rasante_mas_el_espesor_y_vacia_sin_rasante():
    for r in rows_of("replanteo/esperado-objetivos.csv"):
        axis = next(a for a in g.AXES if a.name == r["eje"])
        if not axis.profile:
            assert r["cota_diseno"] == ""
        else:
            z, _ = g.profile_at(axis.profile, float(r["pk"]))
            assert float(r["cota_diseno"]) == pytest.approx(z + float(r["espesor"]), abs=1e-12)


def test_la_punta_de_la_guia_esta_donde_la_construccion_dice():
    rows = rows_of("replanteo/esperado-guia.csv")
    assert len(rows) > 100
    for r in rows:
        axis = next(a for a in g.AXES if a.name == r["eje"])
        st, _ = g.point_at(axis, float(r["pk"]))
        de, dn = float(r["diseno_este"]) - float(r["punta_este"]), float(r["diseno_norte"]) - float(r["punta_norte"])
        tx, ty = math.sin(st.azimuth), math.cos(st.azimuth)
        assert de * tx + dn * ty == pytest.approx(float(r["adelante"]), abs=1e-9)
        assert de * math.cos(st.azimuth) - dn * math.sin(st.azimuth) == pytest.approx(float(r["derecha"]), abs=1e-9)


def test_dentro_y_fuera_dependen_del_lado_del_diseno_respecto_al_eje():
    sides = {(float(r["desplazamiento"]), float(r["derecha"])): r["sentido"] for r in rows_of("replanteo/esperado-guia.csv")}
    assert sides[(3.5, -0.4)] == "dentro" and sides[(3.5, 0.75)] == "fuera"
    assert sides[(-3.5, -0.4)] == "fuera" and sides[(-3.5, 0.75)] == "dentro"
    assert sides[(0.0, -0.4)] == "izquierda" and sides[(0.0, 0.75)] == "derecha"


def test_la_reproduccion_recorre_el_eje_a_y_la_punta_vuelve_a_su_pk_y_desplazamiento():
    rows = rows_of("replanteo/reproduccion-eje-a.csv")
    assert [float(r["pk"]) for r in rows][:3] == [0.0, 10.0, 20.0] and float(rows[-1]["pk"]) == 420.0
    for r in rows:
        st, _ = g.point_at(g.AXIS_A, float(r["pk"]))
        e, n = g.offset_point(st, float(r["desplazamiento"]))
        # la punta redondeada (latitud y longitud a 11 decimales, plano a 5) dista de la construida menos de 1 mm
        assert math.hypot(float(r["punta_este"]) - e, float(r["punta_norte"]) - n) < 1e-3
        assert math.hypot(float(r["adelante"]), float(r["derecha"])) == pytest.approx(float(r["distancia"]), abs=1e-9)
    # en el PK del objetivo (150) la guia no manda nada adelante ni atras
    at_target = next(r for r in rows if float(r["pk"]) == 150.0)
    assert abs(float(at_target["adelante"])) < 1e-3
