"""La sesión sintética de fixtures/nmea/sintetico/ está al día y es la que describe su generador."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_sintetico as g  # noqa: E402


def test_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    nmea, expected = g.generate()

    assert (g.OUT / f"{g.NAME}.nmea").read_bytes() == nmea
    assert g.same_csv((g.OUT / f"{g.NAME}.esperado.csv").read_text(encoding="utf-8"), expected)


def test_la_comparacion_admite_solo_diferencias_de_la_ultima_cifra() -> None:
    assert g.same_value("4747389.743750659", "4747389.74375066")
    assert not g.same_value("4747389.743750659", "4747389.743752")
    assert not g.same_value("FIX", "FLOAT")
    assert g.same_csv("# a\nx,1.0000000001\n", "# a\nx,1.0\n")
    assert not g.same_csv("# a\nx,1.0\n", "# b\nx,1.0\n")


def test_todas_las_frases_completas_tienen_suma_correcta_salvo_la_corrupta() -> None:
    nmea, _ = g.generate()
    lines = [line for line in nmea.split(b"\r\n") if b"$" in line and b"*" in line]
    bad = []
    for line in lines:
        text = line[line.rindex(b"$") :].decode("ascii")
        body, cs = text[1:].split("*")
        if g.checksum(body) != cs:
            bad.append(body.split(",")[1])
    assert bad == [g.CORRUPT_UTC]


def test_la_suma_de_control_coincide_con_un_ejemplo_conocido() -> None:
    # Ejemplo clásico de la norma NMEA 0183.
    assert g.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,") == "47"


def test_las_coordenadas_se_escriben_y_se_leen_como_el_receptor() -> None:
    text, hemisphere = g.nmea_coordinate(-3.7038, 3, "E", "W")

    assert (text, hemisphere) == ("00342.2280000", "W")
    assert abs(g.parsed_coordinate(text, hemisphere, 3) - -3.7038) < 1e-12


def test_hay_puntos_en_cada_huso_fuera_de_los_husos_y_sin_solucion() -> None:
    _, expected = g.generate()
    rows = [line.split(",") for line in expected.splitlines() if line and not line.startswith(("#", "frase"))]

    assert {row[11] for row in rows} == {"29", "30", "31", ""}
    assert any(row[2] == "NONE" and row[8] == "" for row in rows)
    assert any(row[11] == "" and row[8] != "" and row[14] == "" for row in rows)  # Canarias: sin UTM ni H
    assert any(row[0].startswith("GP") for row in rows)
