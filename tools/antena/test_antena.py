"""Los vectores de la antena y del jalón de S046 se pueden reproducir y cumplen lo que dice la spec."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_antena as ga  # noqa: E402
from pyproj import Geod  # noqa: E402

FIXTURE = ga.OUT / ga.NAME


def table() -> list[dict[str, str]]:
    lines = [line for line in FIXTURE.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    names = lines[0].split(",")
    return [dict(zip(names, line.split(","))) for line in lines[1:]]


def test_s046_el_fixture_esta_actualizado() -> None:
    assert FIXTURE.read_text(encoding="utf-8") == "\n".join(ga.rows()) + "\n"


def test_s046_rf_1_los_perfiles_son_los_del_catalogo() -> None:
    offsets = {row["perfil"]: float(row["desplazamiento"]) for row in table()}
    assert offsets["Reach RS3"] == 0.134 and offsets["Reach RS2"] == 0.134
    assert offsets["Reach RS"] == 0.065 and offsets["Reach RS4"] == 0.095 and offsets["Reach RX"] == 0.1471


def test_s046_rf_17_rf_18_la_vertical_es_la_raiz_o_no_es_valida() -> None:
    states = set()
    for row in table():
        states.add(row["estado"])
        entered = float(row["introducida"])
        if row["tipo"] == "VERTICAL":
            assert row["estado"] == "OK" and float(row["vertical"]) == entered
        elif row["radio"] == "":
            assert row["estado"] == "NO_RADIUS" and row["vertical"] == "" and row["latitud_punta"] == ""
        elif entered < float(row["radio"]):
            assert row["estado"] == "SLANT_SHORTER_THAN_RADIUS" and row["vertical"] == ""
        else:
            radius = float(row["radio"])
            assert abs(float(row["vertical"]) - math.sqrt(entered**2 - radius**2)) < 1e-4 / 1000
    assert states == {"OK", "NO_RADIUS", "SLANT_SHORTER_THAN_RADIUS"}


def test_s046_rf_20_la_antena_es_la_vertical_mas_el_desplazamiento() -> None:
    valid = [row for row in table() if row["estado"] == "OK"]
    assert valid
    for row in valid:
        assert abs(float(row["altura_antena"]) - (float(row["vertical"]) + float(row["desplazamiento"]))) < 1e-12


def test_s046_rf_21_el_rs3_con_1_8_m_vertical_da_la_antena_de_antes() -> None:
    rows = [r for r in table() if r["perfil"] == "Reach RS3" and r["tipo"] == "VERTICAL" and r["introducida"] == "1.8"]
    assert rows and all(abs(float(r["altura_antena"]) - 1.934) < 1e-12 for r in rows)


def test_s046_rf_20_la_punta_queda_a_la_altura_de_antena_por_sen_inclinacion() -> None:
    """Comprobación independiente de la tubería topocéntrica: distancia con la geodésica de GRS80."""
    geod = Geod(ellps="GRS80")
    checked = 0
    for row in table():
        if row["estado"] != "OK":
            continue
        antenna, tilt = float(row["altura_antena"]), math.radians(float(row["inclinacion"]))
        lat, lon = float(row["latitud"]), float(row["longitud"])
        lat_t, lon_t = float(row["latitud_punta"]), float(row["longitud_punta"])
        if tilt == 0.0:
            assert abs(lat_t - lat) < 1e-12 and abs(lon_t - lon) < 1e-12
        else:
            _, _, distance = geod.inv(lon, lat, lon_t, lat_t)
            assert abs(distance - antenna * math.sin(tilt)) < 0.005
        checked += 1
    assert checked > 100
