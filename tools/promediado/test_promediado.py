"""Los promediados simulados están al día y lo esperado sale de otro cálculo (S017)."""

from __future__ import annotations

import math
import statistics
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_promediado as r  # noqa: E402
from pyproj import Transformer  # noqa: E402


def data_rows(name: str) -> list[list[str]]:
    text = (r.OUT / f"{name}.csv").read_text(encoding="utf-8")
    return [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "n,"))]


def expected_rows(name: str) -> list[dict[str, str]]:
    lines = [line for line in (r.OUT / f"{name}.esperado.csv").read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:]]


def test_s017_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        assert r.g.same_csv((r.OUT / name).read_text(encoding="utf-8"), content), name


def test_s017_cada_escenario_tiene_sus_epocas_seguidas() -> None:
    for scenario in r.SCENARIOS:
        rows = data_rows(scenario.name)
        assert len(rows) == scenario.epochs, scenario.name
        assert [int(row[0]) for row in rows] == list(range(1, scenario.epochs + 1)), scenario.name


def test_s017_lo_esperado_sale_de_otro_calculo() -> None:
    # `statistics` (media y desviación muestral) en lugar de las dos pasadas con `math.fsum` del generador.
    for scenario in r.SCENARIOS:
        rows = data_rows(scenario.name)
        east, north, height = ([float(row[col]) for row in rows] for col in (4, 5, 3))
        for expected in expected_rows(scenario.name):
            n = int(expected["n"])
            assert math.isclose(float(expected["media_e"]), statistics.fmean(east[:n]), abs_tol=1e-9), (scenario.name, n)
            assert math.isclose(float(expected["media_h"]), statistics.fmean(height[:n]), abs_tol=1e-9), (scenario.name, n)
            if n >= 2:
                dev_h = math.hypot(statistics.stdev(east[:n]), statistics.stdev(north[:n]))
                assert math.isclose(float(expected["dev_h"]), dev_h, abs_tol=1e-9), (scenario.name, n)
                assert math.isclose(float(expected["dev_v"]), statistics.stdev(height[:n]), abs_tol=1e-9), (scenario.name, n)
            if n > r.WINDOW_EPOCHS:
                before = n - r.WINDOW_EPOCHS
                shift_e = statistics.fmean(east[:n]) - statistics.fmean(east[:before])
                shift_n = statistics.fmean(north[:n]) - statistics.fmean(north[:before])
                assert math.isclose(float(expected["estab_h"]), math.hypot(shift_e, shift_n), abs_tol=1e-9), (scenario.name, n)
            else:
                assert expected["estab_h"] == "" and expected["estab_v"] == "", (scenario.name, n)
            assert (expected["dev_h"] == "") == (n < 2), (scenario.name, n)


def test_s017_este_y_norte_son_de_pyproj() -> None:
    for scenario in r.SCENARIOS:
        rows = data_rows(scenario.name)
        projected = Transformer.from_crs(scenario.geographic, scenario.utm, always_xy=True)
        for row in rows[:: scenario.epochs // 40]:
            east, north = projected.transform(float(row[2]), float(row[1]))
            assert abs(east - float(row[4])) < 6e-6 and abs(north - float(row[5])) < 6e-6, (scenario.name, row[0])


def test_s017_el_ruido_y_la_deriva_son_los_pedidos() -> None:
    rows = data_rows("promediado-60min")
    east = [float(row[4]) for row in rows]
    # La deriva de 6 cm en 60 min (1,7 cm de desviación) domina sobre el ruido de 4 mm.
    assert 0.014 < statistics.stdev(east) < 0.022
    assert statistics.fmean(east[-600:]) - statistics.fmean(east[:600]) == pytest.approx(0.001 * 59, abs=0.002)
