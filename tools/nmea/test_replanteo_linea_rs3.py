"""La reproducción sintética del replanteo de una línea está al día y lo esperado sale de otro cálculo (S041)."""

from __future__ import annotations

import math
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replanteo_linea_rs3 as r  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
NAME = "rs3-linea.nmea"
CSV = "rs3-linea.guia.csv"


def lines() -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / NAME).read_bytes().split(b"\r\n") if line]


def epochs() -> list[tuple[str, str, float, float]]:
    """Por cada GGA: hora, estado y el suelo (E, N) calculado aquí aparte, desde la frase tal como la lee la app."""
    out = []
    for line in lines():
        f = line.split("*")[0].split(",")
        if f[0] != "$GNGGA":
            continue
        lat, lon = g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)
        east, north = UTM.transform(lon, lat)
        out.append((f[1], f[6], east, north))
    return out


def rows() -> list[dict[str, str]]:
    text = (g.OUT / CSV).read_text(encoding="utf-8").splitlines()
    data = [line for line in text if line and not line.startswith("#")]
    header = data[0].split(",")
    return [dict(zip(header, line.split(","))) for line in data[1:]]


def vertices() -> list[tuple[float, float]]:
    text = (g.OUT / CSV).read_text(encoding="utf-8").splitlines()
    line = next(x for x in text if x.startswith("# linea:")).removeprefix("# linea: ")
    return [tuple(float(v) for v in pair.split(",")) for pair in line.split()]  # type: ignore[misc]


def test_s041_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s041_las_frases_tienen_suma_correcta() -> None:
    sentences = lines()
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences)
    assert {line[3:6] for line in sentences} == {"GGA", "GST", "RMC", "ZDA"}


def test_s041_rf_26_son_360_epocas_a_5_hz_con_4_s_sin_fix() -> None:
    got = epochs()
    assert len(got) == r.EPOCHS == 360
    times = [int(u[0:2]) * 3600 + int(u[2:4]) * 60 + float(u[4:]) for u, *_ in got]
    assert {round(b - a, 2) for a, b in zip(times, times[1:])} == {0.2}
    statuses = [status for _, status, *_ in got]
    assert statuses.count("5") == 20 and statuses[60:80] == ["5"] * 20
    assert [row["guia"] for row in rows()].count("NO_FIX") == 20


def test_s041_rf_10_rf_15_el_pk_el_lado_y_el_error_lateral_salen_de_la_gga_aparte() -> None:
    """Otra forma de calcularlos: la proyección sobre cada tramo con el parámetro recortado, sin las funciones del script."""
    line = vertices()
    segments = list(zip(line, line[1:]))
    cumulative = [0.0]
    for a, b in segments:
        cumulative.append(cumulative[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    for (_, status, east, north), row in zip(epochs(), rows()):
        if status != "4":
            assert row["guia"] == "NO_FIX"
            continue
        best = None
        for k, (a, b) in enumerate(segments):
            ab = (b[0] - a[0], b[1] - a[1])
            length = math.hypot(*ab)
            along = ((east - a[0]) * ab[0] + (north - a[1]) * ab[1]) / length
            foot = min(max(along, 0.0), length)
            distance = math.hypot(east - (a[0] + ab[0] * foot / length), north - (a[1] + ab[1] * foot / length))
            if best is None or distance < best[0] - 1e-9:
                cross = ab[0] * (north - a[1]) - ab[1] * (east - a[0])
                best = (distance, k, along, length, -cross / length)
        distance, k, along, length, signed = best
        if k == 0 and along < 0:
            assert row["posicion"] == "ANTES_INICIO", row["hora"]
        elif k == len(segments) - 1 and along > length:
            assert row["posicion"] == "DESPUES_FINAL", row["hora"]
            assert row["lateral_m"] == "" and row["error_lateral_m"] == ""
        else:
            assert row["posicion"] == "EN_LINEA", row["hora"]
            assert math.isclose(float(row["pk_m"]), cumulative[k] + min(max(along, 0.0), length), abs_tol=1e-6)
            assert math.isclose(abs(float(row["lateral_m"])), distance, abs_tol=1e-6)
            assert (float(row["lateral_m"]) >= 0) == (signed >= 0)
            assert math.isclose(float(row["error_lateral_m"]), float(row["lateral_m"]) - float(row["desplazamiento_m"]), abs_tol=1e-9)


def test_s041_rf_11_el_desplazamiento_pedido_cambia_en_la_epoca_150() -> None:
    offsets = [float(row["desplazamiento_m"]) for row in rows()]
    assert offsets[:150] == [1.5] * 150 and offsets[150:] == [-2.0] * 210


def test_s041_rf_11_el_recorrido_va_paralelo_a_la_linea_a_la_distancia_pedida() -> None:
    """Salvo en las esquinas y los extremos, el error lateral queda en el ruido de 1 cm de la reproducción."""
    errors = [abs(float(row["error_lateral_m"])) for row in rows() if row["error_lateral_m"] and row["pk_m"]]
    assert statistics.median(errors) < 0.03
    assert sum(1 for e in errors if e < 0.05) > 0.8 * len(errors)


def test_s041_rf_14_hay_epocas_dentro_de_la_linea_y_pasado_el_final() -> None:
    positions = [row["posicion"] for row in rows() if row["guia"] == "SI"]
    assert positions.count("DESPUES_FINAL") > 50 and positions.count("EN_LINEA") > 250
    assert "ANTES_INICIO" not in positions


def test_s041_rf_13_hay_zonas_y_avisos_de_llegada() -> None:
    got = rows()
    zones = {row["zona"] for row in got if row["guia"] == "SI"}
    assert zones == {"FAR", "AID", "INSIDE"}
    alerts = [row["aviso"] for row in got if row["aviso"]]
    assert "PERDIDO" in alerts and "ARRIVED" in alerts and "LEFT" in alerts
