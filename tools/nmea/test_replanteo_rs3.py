"""La reproducción sintética del replanteo está al día y tiene lo que promete su generador (S018)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replanteo_rs3 as r  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
NAME = "rs3-replanteo.nmea"
CSV = "rs3-replanteo.guia.csv"


def lines() -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / NAME).read_bytes().split(b"\r\n") if line]


def epochs() -> list[tuple[str, str, float, float, float]]:
    """Por cada GGA: hora, estado y el suelo (E, N, h) calculado aquí aparte, desde la frase tal como la lee la app."""
    out = []
    for line in lines():
        f = line.split("*")[0].split(",")
        if f[0] != "$GNGGA":
            continue
        lat, lon = g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)
        east, north = UTM.transform(lon, lat)
        out.append((f[1], f[6], east, north, (float(f[9]) + float(f[11])) - ANTENNA_HEIGHT))
    return out


def rows() -> list[list[str]]:
    text = (g.OUT / CSV).read_text(encoding="utf-8")
    return [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "hora,"))]


def target() -> tuple[float, float, float]:
    header = next(line for line in (g.OUT / CSV).read_text(encoding="utf-8").splitlines() if line.startswith("# diseno:"))
    values = dict(part.split("=") for part in header.removeprefix("# diseno: ").split(";")[0].split())
    return float(values["este"]), float(values["norte"]), float(values["h"])


def test_s018_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s018_las_frases_tienen_suma_correcta() -> None:
    sentences = lines()
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences)
    assert {line[3:6] for line in sentences} == {"GGA", "GST", "RMC", "ZDA"}


def test_s018_rf_22_son_247_epocas_a_5_hz_con_10_s_sin_fix() -> None:
    got = epochs()
    assert len(got) == r.EPOCHS == 247
    times = [int(u[0:2]) * 3600 + int(u[2:4]) * 60 + float(u[4:]) for u, *_ in got]
    assert {round(b - a, 2) for a, b in zip(times, times[1:])} == {0.2}
    statuses = [status for _, status, *_ in got]
    assert statuses.count("5") == 50 and statuses[60:110] == ["5"] * 50
    assert [row[5] for row in rows()].count("NO_FIX") == 50
    assert [row[1] for row in rows()] == ["FLOAT" if s == "5" else "FIX" for s in statuses]


def test_s018_rf_6_el_diseno_esta_a_40_m_al_empezar_y_la_distancia_sale_de_la_gga_aparte() -> None:
    east, north, height = target()
    got, expected = epochs(), rows()
    assert math.isclose(math.hypot(east - got[0][2], north - got[0][3]), 40.0, abs_tol=0.1)
    for (_, status, e, n, h), row in zip(got, expected):
        if status != "4":
            assert row[6:10] == ["", "", "", ""]
            continue
        assert abs(float(row[6]) - math.hypot(east - e, north - n)) < 1e-6
        assert abs(float(row[7]) - (east - e)) < 1e-6
        assert abs(float(row[8]) - (north - n)) < 1e-6
        assert abs(float(row[9]) - (h - height)) < 1e-6


def test_s018_rf_20_rf_21_rf_22_los_avisos_son_aproximacion_llegada_salida_y_fix_perdido_en_ese_orden() -> None:
    alerts = [(i, row[11]) for i, row in enumerate(rows()) if row[11]]
    assert [a for _, a in alerts] == ["PERDIDO", "APPROACH", "ARRIVED", "LEFT"]
    assert 60 <= alerts[0][0] <= 62  # el primer FLOAT
    assert alerts[2][0] < r.WALK_EPOCHS + 10 and alerts[3][0] == r.WALK_EPOCHS + r.STOP_EPOCHS
    zones = [row[10] for row in rows() if row[5] == "SI"]
    assert zones[0] == "FAR" and zones[-1] == "AID" and "INSIDE" in zones


def test_s018_rf_20_la_zona_dentro_no_vuelve_a_avisar_en_la_parada() -> None:
    stop = rows()[r.WALK_EPOCHS : r.WALK_EPOCHS + r.STOP_EPOCHS]
    assert {row[10] for row in stop} == {"INSIDE"}
    assert stop[0][11] == "ARRIVED"  # la parada empieza dentro, y el aviso es el de llegar
    assert [row[11] for row in stop[1:]] == [""] * (r.STOP_EPOCHS - 1)
    assert all(float(row[6]) <= r.TOLERANCE_M for row in stop)


def test_s018_rf_9_el_rumbo_de_marcha_es_el_del_avance_se_conserva_10_s_y_despues_no_hay() -> None:
    data = rows()
    walking = [row for row in data[: r.WALK_EPOCHS] if row[5] == "SI" and row[12]]
    assert walking and all(abs(float(row[12]) - r.WALK_AZIMUTH) < 3.0 for row in walking)
    assert all(row[13] == "0.000000000" for row in walking[10:12])
    held = [float(row[13]) for row in data if row[12] and float(row[13]) > 0]
    assert held and max(held) <= r.HOLD_S
    assert data[-1][12] == "" and data[-1][13] == ""  # tras 12 s de parada y 4 s apartándose, ya no hay rumbo
