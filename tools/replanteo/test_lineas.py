"""Las referencias del replanteo de líneas están al día y salen de otro cálculo (S041)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_lineas as gl  # noqa: E402
from pyproj import Transformer  # noqa: E402


def rows_of(name: str) -> list[dict[str, str]]:
    text = (gl.OUT / name).read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if line and not line.startswith("#")]
    return list(csv.DictReader(io.StringIO("\n".join(lines), newline="")))


def line_of(name: str) -> list[tuple[float, float, float | None]]:
    points = [r for r in rows_of(gl.NAME_LINEAS) if r["linea"] == name]
    return [(float(r["este"]), float(r["norte"]), float(r["cota"]) if r["cota"] else None) for r in points]


def closed(name: str) -> bool:
    return next(r for r in rows_of(gl.NAME_LINEAS) if r["linea"] == name)["cerrada"] == "1"


def segments(name: str) -> list[tuple[tuple[float, float], tuple[float, float]]]:
    pts = [(x, y) for x, y, _ in line_of(name)]
    pairs = list(zip(pts, pts[1:]))
    if closed(name):
        pairs.append((pts[-1], pts[0]))
    return pairs


def distance_to_segment(p: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
    """Otra forma de la distancia: la altura del triángulo si el pie cae dentro y, si no, la distancia al extremo más cercano."""
    ab = (b[0] - a[0], b[1] - a[1])
    ap = (p[0] - a[0], p[1] - a[1])
    length = math.hypot(*ab)
    along = (ap[0] * ab[0] + ap[1] * ab[1]) / length
    if along <= 0:
        return math.hypot(*ap)
    if along >= length:
        return math.hypot(p[0] - b[0], p[1] - b[1])
    return abs(ab[0] * ap[1] - ab[1] * ap[0]) / length


def test_s041_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, build in gl.FILES.items():
        assert gl.g.same_csv((gl.OUT / name).read_text(encoding="utf-8"), build()), name


def test_s041_las_lineas_salen_de_coordenadas_geograficas_con_pyproj() -> None:
    """Cada vértice es el de EPSG:4258 → 25830 del punto a (dx, dy) metros; se comprueba yendo y volviendo."""
    inverse = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)
    forward = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
    for r in rows_of(gl.NAME_LINEAS):
        lon, lat = inverse.transform(float(r["este"]), float(r["norte"]))
        x, y = forward.transform(lon, lat)
        assert math.isclose(x, float(r["este"]), abs_tol=1e-4)
        assert math.isclose(y, float(r["norte"]), abs_tol=1e-4)
        assert 40.0 < lat < 41.0 and -4.0 < lon < -3.0


def test_s041_rf_15_el_pk_acumulado_y_la_longitud_salen_de_las_coordenadas() -> None:
    for name in {r["linea"] for r in rows_of(gl.NAME_LINEAS)}:
        total = 0.0
        pks = [float(r["pk"]) for r in rows_of(gl.NAME_LINEAS) if r["linea"] == name]
        length = float(next(r for r in rows_of(gl.NAME_LINEAS) if r["linea"] == name)["longitud"])
        for (a, b), pk in zip(segments(name), pks[1:] + [length]):
            total += math.hypot(b[0] - a[0], b[1] - a[1])
            assert math.isclose(total, pk, abs_tol=1e-8)
        assert math.isclose(total, length, abs_tol=1e-8)


def test_s041_rf_10_rf_15_el_cercano_esta_en_la_linea_y_ninguna_otra_parte_esta_mas_cerca() -> None:
    cases = rows_of(gl.NAME_CONSULTAS)
    assert {r["linea"] for r in cases} == {r["linea"] for r in rows_of(gl.NAME_LINEAS)}
    for r in cases:
        tip = (float(r["punta_e"]), float(r["punta_n"]))
        near = (float(r["cercano_e"]), float(r["cercano_n"]))
        segs = segments(r["linea"])
        assert min(distance_to_segment(near, a, b) for a, b in segs) < 1e-6  # está en la línea
        best = min(distance_to_segment(tip, a, b) for a, b in segs)
        assert math.isclose(float(r["distancia"]), best, abs_tol=1e-6)
        assert math.isclose(math.hypot(tip[0] - near[0], tip[1] - near[1]), best, abs_tol=1e-6)


def test_s041_rf_10_la_distancia_lateral_tiene_signo_positivo_a_la_derecha() -> None:
    right = left = before = after = 0
    for r in rows_of(gl.NAME_CONSULTAS):
        lateral = float(r["lateral"])
        if r["posicion"] != "EN_LINEA":
            assert lateral == 0.0  # sin lado
            before += r["posicion"] == "ANTES_INICIO"
            after += r["posicion"] == "DESPUES_FINAL"
            continue
        assert math.isclose(abs(lateral), float(r["distancia"]), abs_tol=1e-6)
        right += lateral > 1e-6
        left += lateral < -1e-6
    assert right > 20 and left > 20 and before > 5 and after > 5


def test_s041_rf_10_en_una_recta_hacia_el_este_la_derecha_es_el_sur() -> None:
    """Línea de (0,0) a (100,0): una punta al sur está a la derecha (positiva) y una al norte a la izquierda."""
    line = gl.Line("sintetica", False, (0.0, 100.0), (0.0, 0.0), None)
    south = gl.locate(line, 40.0, -3.0)
    north = gl.locate(line, 40.0, 2.0)
    assert math.isclose(south["lateral"], 3.0) and math.isclose(north["lateral"], -2.0)
    assert math.isclose(south["pk"], 40.0)
    assert gl.locate(line, -5.0, 1.0)["position"] == "ANTES_INICIO"
    assert gl.locate(line, 105.0, 1.0)["position"] == "DESPUES_FINAL"


def test_s041_rf_14_una_linea_cerrada_no_tiene_antes_ni_despues() -> None:
    assert not any(r["posicion"] != "EN_LINEA" for r in rows_of(gl.NAME_CONSULTAS) if closed(r["linea"]))


def test_s041_rf_11_el_objetivo_esta_a_la_distancia_del_desplazamiento_y_el_error_lateral_es_la_resta() -> None:
    cases = rows_of(gl.NAME_OBJETIVOS)
    assert {float(r["desplazamiento"]) for r in cases} == set(gl.OFFSETS)
    for r in cases:
        d = float(r["desplazamiento"])
        target = (float(r["objetivo_e"]), float(r["objetivo_n"]))
        line = next(x for x in gl.LINES if x.name == r["linea"])
        near = gl.locate(line, float(r["punta_e"]), float(r["punta_n"]))
        assert math.isclose(math.hypot(target[0] - near["px"], target[1] - near["py"]), abs(d), abs_tol=1e-6)
        assert math.isclose(float(r["error_lateral"]), near["lateral"] - d, abs_tol=1e-6)


def test_s041_rf_23_la_cota_se_interpola_linealmente_en_el_pk() -> None:
    pts = line_of("polilinea-cota")
    cumulative = [float(r["pk"]) for r in rows_of(gl.NAME_LINEAS) if r["linea"] == "polilinea-cota"]
    for r in rows_of(gl.NAME_CONSULTAS):
        if r["linea"] != "polilinea-cota":
            continue
        pk = float(r["pk"])
        k = max(i for i in range(len(pts) - 1) if cumulative[i] <= pk + 1e-12)
        t = (pk - cumulative[k]) / (cumulative[k + 1] - cumulative[k])
        expected = pts[k][2] + (pts[k + 1][2] - pts[k][2]) * t
        assert math.isclose(float(r["cota_pk"]), expected, abs_tol=1e-8)
    assert all(not r["cota_pk"] for r in rows_of(gl.NAME_CONSULTAS) if r["linea"] == "recta")


def test_s041_rf_18_rf_19_las_series_incluyen_b_y_dan_solo_a_y_b_con_un_intervalo_mayor() -> None:
    by_case: dict[tuple[str, str, str, str, str], list[dict[str, str]]] = {}
    for r in rows_of(gl.NAME_SERIES):
        by_case.setdefault((r["linea"], r["pk_a"], r["pk_b"], r["intervalo"], r["desplazamiento"]), []).append(r)
    assert any(float(k[3]) >= abs(float(k[2]) - float(k[1])) for k in by_case)
    for (_, a, b, step, _), rows in by_case.items():
        a, b, step = float(a), float(b), float(step)
        pks = [float(r["pk"]) for r in rows]
        assert pks[0] == a and math.isclose(pks[-1], b, abs_tol=1e-9)
        if step >= abs(b - a):
            assert len(pks) == 2
        else:
            assert all(math.isclose(abs(p - a), n * step, abs_tol=1e-9) for n, p in enumerate(pks[:-1]))
            assert 0 < abs(pks[-1] - pks[-2]) <= step + 1e-9
    assert any(float(k[1]) > float(k[2]) for k in by_case)  # tramos invertidos
    assert any(float(k[4]) > 0 for k in by_case) and any(float(k[4]) < 0 for k in by_case)
    assert any(abs(float(k[2]) - float(k[1])) % float(k[3]) > 1e-6 for k in by_case)  # B no es múltiplo del intervalo


def test_s041_rf_18_los_puntos_de_una_serie_estan_a_la_distancia_del_desplazamiento_de_la_linea() -> None:
    for r in rows_of(gl.NAME_SERIES):
        d = abs(float(r["desplazamiento"]))
        point = (float(r["este"]), float(r["norte"]))
        best = min(distance_to_segment(point, a, b) for a, b in segments(r["linea"]))
        assert best <= d + 1e-6  # en las esquinas la paralela no se recorta: queda a menos de d de la línea, nunca a más
        if d == 0:
            assert best < 1e-6
