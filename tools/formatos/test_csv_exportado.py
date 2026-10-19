"""El CSV que escribe TopoPhone, leído con otra herramienta: el módulo csv de Python (S012, constitución nº 2).

Los ficheros de fixtures/formatos/csv los escribe CsvExport (CsvExportGoldenTest los compara byte a byte) a partir de
los puntos de fuente.json, con los valores sin redondear. Aquí se comprueba que cualquier programa que siga el
RFC 4180 lee lo mismo que se guardó.
"""

from __future__ import annotations

import csv
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / "fixtures" / "formatos" / "csv"
SOURCE = json.loads((FOLDER / "fuente.json").read_text(encoding="utf-8"))

# Fichero → (separador, signo decimal)
FILES = {
    "todas_coma_punto.csv": (",", "."),
    "todas_puntoycoma_coma.csv": (";", ","),
    "todas_tabulador_punto.csv": ("\t", "."),
    "penzd_puntoycoma_coma.csv": (";", ","),
    "pnezd_coma_punto.csv": (",", "."),
}

LENGTHS = {
    "Easting": "easting",
    "Northing": "northing",
    "Elevation": "height",
    "Ellipsoidal height": "ellipsoidal_height",
    "Pole height": "pole_height",
    "Horizontal accuracy": "horizontal_accuracy",
    "Vertical accuracy": "vertical_accuracy",
}
DEGREES = {"Latitude": "latitude", "Longitude": "longitude"}


def read(name: str) -> list[dict[str, str]]:
    separator, _ = FILES[name]
    data = (FOLDER / name).read_bytes()
    text = data.decode("utf-8")
    return list(csv.DictReader(io.StringIO(text, newline=""), delimiter=separator))


def number(text: str, decimal: str) -> float | None:
    return None if text == "" else float(text.replace(decimal, ".")) if decimal != "." else float(text)


def test_s012_rf_16_utf8_sin_bom_y_crlf() -> None:
    for name in FILES:
        data = (FOLDER / name).read_bytes()
        assert not data.startswith(b"\xef\xbb\xbf"), name
        assert data.endswith(b"\r\n"), name
        # Fuera de los campos entre comillas, cada salto de línea es CRLF.
        assert data.count(b"\r\n") == len(SOURCE) + 1, name


def test_s012_rf_11_rf_12_los_valores_leidos_difieren_de_los_guardados_menos_de_0_05_mm() -> None:
    for name, (_, decimal) in FILES.items():
        rows = read(name)
        assert [row["Name"] for row in rows] == [point["name"] for point in SOURCE], name
        for row, point in zip(rows, SOURCE):
            for column, key in LENGTHS.items():
                if column not in row:
                    continue
                value = number(row[column], decimal)
                expected = point[key]
                if expected is None:
                    assert value is None, (name, column, row[column])
                else:
                    assert value is not None and abs(value - expected) <= 0.00005 + 1e-12, (name, column, value, expected)
            for column, key in DEGREES.items():
                if column in row:
                    assert abs(number(row[column], decimal) - point[key]) <= 5e-10 + 1e-15, (name, column)


def test_s012_rf_15_los_textos_con_separador_comillas_o_saltos_se_leen_tal_cual() -> None:
    for name in FILES:
        rows = read(name)
        assert [row["Description"] for row in rows] == [point["description"] for point in SOURCE], name


def test_s012_rf_9_todas_las_columnas_y_penzd_pnezd_con_su_cabecera() -> None:
    headers = {name: list(read(name)[0].keys()) for name in FILES}
    assert headers["todas_coma_punto.csv"] == headers["todas_puntoycoma_coma.csv"] == headers["todas_tabulador_punto.csv"]
    assert len(headers["todas_coma_punto.csv"]) == 28
    assert headers["penzd_puntoycoma_coma.csv"] == ["Name", "Easting", "Northing", "Elevation", "Description"]
    assert headers["pnezd_coma_punto.csv"] == ["Name", "Northing", "Easting", "Elevation", "Description"]


def test_s012_rf_13_los_tres_ficheros_completos_dicen_lo_mismo() -> None:
    reference = read("todas_coma_punto.csv")
    for name in ("todas_puntoycoma_coma.csv", "todas_tabulador_punto.csv"):
        _, decimal = FILES[name]
        for expected, row in zip(reference, read(name)):
            for column, value in row.items():
                if column in LENGTHS or column in DEGREES or column in ("HDOP", "Correction age"):
                    assert number(value, decimal) == number(expected[column], "."), (name, column)
                else:
                    assert value == expected[column], (name, column)
