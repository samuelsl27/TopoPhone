"""La sesión sintética completa del RS3 está al día y tiene lo que promete su generador."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_rs3_completo as r  # noqa: E402
import generar_sintetico as g  # noqa: E402


def rows() -> list[list[str]]:
    _, expected = r.generate()
    return [line.split(",") for line in expected.splitlines() if line and not line.startswith(("#", "hora"))]


def test_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    nmea, expected = r.generate()

    assert (g.OUT / f"{r.NAME}.nmea").read_bytes() == nmea
    assert g.same_csv((g.OUT / f"{r.NAME}.epocas.csv").read_text(encoding="utf-8"), expected)


def test_tiene_todas_las_frases_del_rs3_con_suma_correcta() -> None:
    nmea, _ = r.generate()
    lines = [line.decode("ascii") for line in nmea.split(b"\r\n") if line]
    types = {line[3:6] for line in lines}
    talkers = {line[1:3] for line in lines if line[3:6] in ("GSA", "GSV")}

    assert types == {"GGA", "ETC", "GSA", "GSV", "GST", "RMC", "VTG", "ZDA", "EBP"}
    assert talkers == {"GP", "GL", "GA", "GB"}
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in lines)


def test_cruza_la_medianoche_y_tiene_un_hueco_de_15_s() -> None:
    hours = [row[0] for row in rows()]
    dates = {row[1] for row in rows()}

    assert "000000.00" in hours
    assert dates == {"2026-10-15", "2026-10-16"}
    assert "000002.80" in hours and "000017.80" in hours and "000003.00" not in hours


def test_satelites_e_inclinacion_por_epoca() -> None:
    by_hour = {row[0]: row for row in rows()}

    assert by_hour["235957.00"][2:5] == ["23", "31", "ALIGNMENT"]
    assert by_hour["235957.20"][2:5] == ["", "", "ALIGNMENT"]
    assert by_hour["235959.00"][4] == "COMPENSATING"
    assert by_hour["000017.80"][4] == "SETUP"
