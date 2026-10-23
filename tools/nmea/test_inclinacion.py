"""Las reproducciones sintéticas con la inclinación están al día y tienen lo que promete su generador (S016)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_inclinacion as r  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Geod  # noqa: E402

GEOD = Geod(ellps="GRS80")


def lines(name: str) -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / name).read_bytes().split(b"\r\n") if line]


def expected_rows() -> list[list[str]]:
    text = (g.OUT / "rs3-inclinacion.esperado.csv").read_text(encoding="utf-8")
    return [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "hora"))]


def gga_positions(name: str) -> list[tuple[float, float]]:
    out = []
    for line in lines(name):
        f = line.split("*")[0].split(",")
        if f[0] == "$GNGGA":
            out.append((g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)))
    return out


def test_s016_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s016_las_frases_tienen_suma_correcta_y_van_a_5_hz() -> None:
    for name in ("rs3-inclinacion.nmea", "rs3-asistente-app.nmea", "rs3-asistente-receptor.nmea"):
        sentences = lines(name)
        assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences), name
        assert {line[3:6] for line in sentences} == {"GGA", "ETC", "GST", "RMC", "ZDA"}, name
        times = [line.split(",")[1] for line in sentences if line.startswith("$GNGGA")]
        assert times[1] == "100000.20", name


def test_s016_rf_19_la_inclinacion_pasa_por_configuracion_alineacion_y_un_aviso() -> None:
    rows = expected_rows()
    assert [row[1] for row in rows[:1]] == ["SETUP"]
    assert {row[1] for row in rows} == {"SETUP", "ALIGNMENT", "COMPENSATING"}
    assert sum(1 for row in rows if row[2] == "FAST_MOTION") == 5
    tilts = [float(row[3]) for row in rows if row[1] == "COMPENSATING"]
    assert min(tilts) < 1.0 and max(tilts) > 39.0


def test_s016_rf_13_la_punta_esperada_queda_en_el_punto_fijo() -> None:
    """Comprobación independiente: desde la GGA redondeada como la lee la app, la punta vuelve al punto (< 1 mm)."""
    lat0, lon0, h0 = r.TIP
    for row in expected_rows():
        if row[1] != "COMPENSATING":
            assert row[5] == "", row
            continue
        _, _, distance = GEOD.inv(lon0, lat0, float(row[6]), float(row[5]))
        assert distance < 0.001, row
        assert abs(float(row[7]) - h0) < 0.001, row


def test_s016_rf_7_rf_8_la_gga_sigue_a_la_antena_o_queda_en_la_punta() -> None:
    """Sin compensar, la antena se mueve H·(sen 25° − sen 1°) ≈ 0,78 m; compensando, la GGA no se mueve más de 1 cm."""
    for name, (low, high) in (("rs3-asistente-app.nmea", (0.7, 0.85)), ("rs3-asistente-receptor.nmea", (0.0, 0.015))):
        positions = gga_positions(name)
        (lat_a, lon_a), (lat_b, lon_b) = positions[0], positions[-1]
        _, _, distance = GEOD.inv(lon_a, lat_a, lon_b, lat_b)
        assert low <= distance <= high, (name, distance)
    expected = r.ANTENNA_HEIGHT * (math.sin(math.radians(25.0)) - math.sin(math.radians(1.0)))
    assert 0.7 < expected < 0.85
