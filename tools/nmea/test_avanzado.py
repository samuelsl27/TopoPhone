"""Las reproducciones sintéticas del recolector avanzado están al día y tienen lo que promete su generador (S017)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_avanzado as r  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M


def lines(name: str) -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / name).read_bytes().split(b"\r\n") if line]


def csv_rows(name: str) -> list[list[str]]:
    text = (g.OUT / name).read_text(encoding="utf-8")
    return [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "n,", "hora,", "segmento,"))]


def seconds(utc: str) -> float:
    return int(utc[0:2]) * 3600 + int(utc[2:4]) * 60 + float(utc[4:])


def ground(name: str) -> list[tuple[str, str, float, float, float]]:
    """Por cada GGA: hora, estado, y su punto del suelo (E, N, h) calculado aquí aparte, desde la frase tal como la lee la app."""
    out = []
    for line in lines(name):
        f = line.split("*")[0].split(",")
        if f[0] != "$GNGGA":
            continue
        lat, lon = g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)
        east, north = UTM.transform(lon, lat)
        out.append((f[1], f[6], east, north, (float(f[9]) + float(f[11])) - ANTENNA_HEIGHT))
    return out


def test_s017_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s017_las_frases_tienen_suma_correcta() -> None:
    for name in ("rs3-promediado-largo.nmea", "rs3-corte.nmea", "rs3-traza.nmea"):
        sentences = lines(name)
        assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences), name
        assert {line[3:6] for line in sentences} == {"GGA", "GST", "RMC", "ZDA"}, name


def test_s017_rf_12_el_promediado_largo_son_10_min_a_10_hz_con_un_corte_de_6_s() -> None:
    times = [seconds(utc) for utc, *_ in ground("rs3-promediado-largo.nmea")]
    steps = [round(b - a, 2) for a, b in zip(times, times[1:])]
    assert len(times) == r.LEAD_IN_EPOCHS + 6000 - 60
    assert steps.count(0.1) == len(steps) - 1
    assert steps.count(6.1) == 1
    assert math.isclose(times[-1] - times[0], 599.9 + r.LEAD_IN_EPOCHS * 0.1, abs_tol=1e-6)  # 10 min de reloj con el corte dentro


def test_s017_rf_12_lo_esperado_del_promediado_sale_de_la_gga_aparte() -> None:
    """Media y desviaciones típicas muestrales (n − 1) del suelo, recalculadas aquí sin las funciones del generador."""
    epochs = ground("rs3-promediado-largo.nmea")[r.LEAD_IN_EPOCHS :]  # sin las de calentamiento
    for row in csv_rows("rs3-promediado-largo.medias.csv"):
        n = int(row[0])
        used = epochs[:n]
        assert math.isclose(float(row[1]), n * 0.1, abs_tol=1e-9)
        for column, axis in ((2, 2), (3, 3), (4, 4)):
            values = [e[axis] for e in used]
            assert abs(float(row[column]) - math.fsum(values) / n) < 1e-6, (n, column)
        if n >= 2:
            def sd(axis: int) -> float:
                values = [e[axis] for e in used]
                m = math.fsum(values) / n
                return math.sqrt(math.fsum((x - m) ** 2 for x in values) / (n - 1))

            assert abs(float(row[5]) - math.hypot(sd(2), sd(3))) < 1e-6, n
            assert abs(float(row[6]) - sd(4)) < 1e-6, n
        else:
            assert row[5] == row[6] == "", row


def test_s017_rf_12_el_esperado_incluye_las_epocas_de_antes_y_de_despues_del_corte() -> None:
    ns = [int(row[0]) for row in csv_rows("rs3-promediado-largo.medias.csv")]
    assert 1 in ns and 2 in ns and ns[-1] == 5940
    assert any(n < r.CUT_AT_EPOCH for n in ns) and any(n > r.CUT_AT_EPOCH for n in ns)


def test_s017_rf_19_el_corte_corto_y_el_largo_del_fichero_de_cortes() -> None:
    times = [seconds(utc) for utc, *_ in ground("rs3-corte.nmea")]
    gaps = sorted({round(b - a, 2) for a, b in zip(times, times[1:])})
    assert gaps == [0.2, 4.2, 360.2]
    rows = csv_rows("rs3-corte.tramos.csv")
    assert [row[5] for row in rows] == ["inicio", "pausa-y-reanuda", "pausa-larga"]
    assert [int(row[3]) for row in rows] == [100, 100, 50]
    assert sum(int(row[3]) for row in rows) == len(times)


def test_s017_rf_20_rf_21_la_traza_son_2_min_andando_a_5_hz_con_10_s_sin_fix() -> None:
    epochs = ground("rs3-traza.nmea")
    assert len(epochs) == 600
    statuses = [status for _, status, *_ in epochs]
    assert statuses.count("5") == 50 and statuses.count("4") == 550
    assert statuses[250:300] == ["5"] * 50
    length = sum(math.hypot(b[2] - a[2], b[3] - a[3]) for a, b in zip(epochs, epochs[1:]))
    assert 143.0 < math.hypot(epochs[-1][2] - epochs[0][2], epochs[-1][3] - epochs[0][3]) < 146.0  # 1,2 m/s durante 120 s
    assert length >= 144.0


def test_s017_rf_20_rf_21_los_disparos_esperados_salen_de_las_posiciones_aparte() -> None:
    epochs = ground("rs3-traza.nmea")
    rows = csv_rows("rs3-traza.disparos.csv")
    assert len(rows) == len(epochs)
    last = 0
    by_distance = []
    for i, (_, _, east, north, _) in enumerate(epochs):
        if i == 0 or math.hypot(east - epochs[last][2], north - epochs[last][3]) >= r.TRIGGER_DISTANCE_M:
            by_distance.append(i)
            last = i
    assert [i for i, row in enumerate(rows) if row[6] == "1"] == by_distance
    assert [i for i, row in enumerate(rows) if row[7] == "1"] == [0, 150, 300, 450]
    assert 10 <= len(by_distance) <= 16
