"""Los ficheros de importación están al día, se leen con otra herramienta y lo esperado sale de otro cálculo (S028)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_importacion as r  # noqa: E402
from pyproj import Transformer  # noqa: E402

BOM = b"\xef\xbb\xbf"
DATOS = r.OUT
# Fichero → (codificación, separador, decimal, con cabecera, columnas: posición del nombre, primera, segunda, altura y descripción)
FORMATOS = {
    "etrs89-utm30-utf8-bom-coma.csv": ("utf-8-sig", ",", ".", True, (0, 1, 2, 3, 4)),
    "etrs89-utm30-windows1252-puntoycoma.txt": ("cp1252", ";", ",", True, (0, 1, 2, 3, 4)),
    "etrs89-utm30-tabulador-sin-cabecera.txt": ("utf-8", "\t", ".", False, (0, 1, 2, 3, 4)),
    "etrs89-utm30-espacios-pnezd.txt": ("utf-8", " ", ".", False, (0, 2, 1, 3, 4)),
    "etrs89-utm30-comillas.csv": ("utf-8", ",", ".", True, (0, 1, 2, 3, 4)),
    "etrs89-geograficas.csv": ("utf-8", ",", ".", True, (0, 1, 2, 3, 4)),
    "regcan95-utm28.csv": ("utf-8", ",", ".", True, (0, 1, 2, 3, 4)),
    "ed50-utm30.csv": ("utf-8", ",", ".", True, (0, 1, 2, 3, 4)),
    "etrs89-utm30-pies.csv": ("utf-8", ",", ".", True, (0, 1, 2, 3, 4)),
    "etrs89-utm30-errores.csv": ("utf-8", ";", ",", True, (0, 1, 2, 3, 4)),
}
UTM = {
    "ETRS89": Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True),
    "REGCAN95": Transformer.from_crs("EPSG:4083", "EPSG:4081", always_xy=True),
}


def expected() -> list[dict[str, str]]:
    lines = [line for line in (DATOS / r.EXPECTED).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    reader = csv.DictReader(io.StringIO("\n".join(lines), newline=""))
    return list(reader)


def rows_of(file: str) -> list[dict[str, str]]:
    return [row for row in expected() if row["archivo"] == file]


def parsed(file: str) -> list[list[str]]:
    """El fichero leído con el módulo csv de Python, según su formato, sin las funciones del script."""
    encoding, separator, _, header, _ = FORMATOS[file]
    text = (DATOS / file).read_bytes().decode(encoding)
    if separator == " ":
        return [line.split() for line in text.splitlines() if line.strip()]
    rows = list(csv.reader(io.StringIO(text, newline=""), delimiter=separator))
    return rows[1:] if header else rows


def decimal_number(text: str, decimal: str) -> float | None:
    return None if text == "" else float(text.replace(decimal, ".") if decimal != "." else text)


def test_s028_rf4_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = DATOS / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert path.read_text(encoding="utf-8") == content, name


def test_s028_rf4_cada_fichero_tiene_la_codificacion_que_promete() -> None:
    for name in FORMATOS:
        data = (DATOS / name).read_bytes()
        assert data.startswith(BOM) == (name == "etrs89-utm30-utf8-bom-coma.csv"), name
        if name == "etrs89-utm30-windows1252-puntoycoma.txt":
            data.decode("cp1252")
            try:
                data.decode("utf-8")
            except UnicodeDecodeError:
                pass
            else:
                raise AssertionError("el fichero de Windows-1252 también es UTF-8 válido: no sirve para probar la detección")
            assert b"\xc1" in data and b"\xf1" in data and b"\xf3" in data  # Á, ñ y ó, cada una en un solo byte
        else:
            data.decode("utf-8")


def test_s028_rf5_rf7_los_separadores_y_los_decimales_prometidos() -> None:
    seen = {(sep, dec) for _, sep, dec, _, _ in FORMATOS.values()}
    assert seen == {(",", "."), (";", ","), ("\t", "."), (" ", ".")}


def test_s028_rf9_con_y_sin_cabecera() -> None:
    headers = {name: header for name, (_, _, _, header, _) in FORMATOS.items()}
    assert headers["etrs89-utm30-tabulador-sin-cabecera.txt"] is False
    assert headers["etrs89-utm30-espacios-pnezd.txt"] is False
    assert sum(headers.values()) == 8


def test_s028_rf6_las_comillas_con_el_separador_las_comillas_dobles_y_el_salto_de_linea_se_leen_con_el_modulo_csv() -> None:
    rows = parsed("etrs89-utm30-comillas.csv")
    assert [row[0] for row in rows] == ["1", "2", "3", "4", "5", "6"]
    assert rows[1][4] == "Pozo,\nregistro"
    assert rows[4][4] == 'Línea "eje"'


def test_s028_rf10_las_filas_leidas_con_csv_son_las_esperadas() -> None:
    """Nombre, descripción y números de cada fila, desde el fichero, contra esperado.csv."""
    for name, (_, _, decimal, _, order) in FORMATOS.items():
        if name == "etrs89-utm30-errores.csv":
            continue
        unit = 0.3048 if name == "etrs89-utm30-pies.csv" else 1.0
        file_rows = parsed(name)
        want = rows_of(name)
        assert len(file_rows) == len(want) == 6, name
        for cells, row in zip(file_rows, want):
            assert cells[order[0]] == row["nombre"], name
            assert cells[order[4]] == row["descripcion"], (name, cells)
            first = decimal_number(cells[order[1]], decimal)
            second = decimal_number(cells[order[2]], decimal)
            assert abs(first * unit - float(row["primera_m"])) < 1e-6, (name, cells)
            assert abs(second * unit - float(row["segunda_m"])) < 1e-6, (name, cells)
            height = decimal_number(cells[order[3]], decimal) if len(cells) > order[3] else None
            if row["altura_m"] == "":
                assert height is None, (name, cells)
            else:
                assert abs(height * unit - float(row["altura_m"])) < 1e-6, (name, cells)


def test_s028_rf12_las_geograficas_proyectadas_son_las_de_pyproj_de_lo_escrito() -> None:
    """Independiente: la latitud y la longitud esperadas desde Este y Norte con pyproj, y el Este y el Norte desde ellas."""
    for row in expected():
        if row["estado"] != "OK" or row["forma"] != "PROJECT_CRS" or row["datum"] not in UTM or row["huso"] not in ("30", "28"):
            continue
        lon, lat = UTM[row["datum"]].transform(float(row["primera_m"]), float(row["segunda_m"]))
        assert abs(lat - float(row["latitud"])) < 1e-9, row["archivo"]
        assert abs(lon - float(row["longitud"])) < 1e-9, row["archivo"]
        assert abs(float(row["este"]) - float(row["primera_m"])) < 1e-9


def test_s028_rf13_el_fichero_en_geograficas_lleva_la_latitud_y_la_longitud_escritas_y_la_altura_elipsoidal() -> None:
    for row in rows_of("etrs89-geograficas.csv"):
        assert row["forma"] == "GEOGRAPHIC" and row["tipo_altura"] == "ELLIPSOIDAL"
        assert abs(float(row["latitud"]) - float(row["primera_m"])) < 1e-9
        assert abs(float(row["longitud"]) - float(row["segunda_m"])) < 1e-9
        if row["altura_m"]:
            assert abs(float(row["altura_elipsoidal"]) - float(row["altura_m"])) < 1e-9


def test_s028_rf14_el_fichero_en_pies_da_los_mismos_metros_que_el_de_metros() -> None:
    meters, feet = rows_of("etrs89-utm30-utf8-bom-coma.csv"), rows_of("etrs89-utm30-pies.csv")
    assert all(row["unidad"] == "ft" for row in feet)
    for a, b in zip(meters, feet):
        assert abs(float(a["primera_m"]) - float(b["primera_m"])) < 6e-4, a["nombre"]  # el de metros va a 1 mm y el de pies a 0,1 mm de pie
        assert abs(float(a["segunda_m"]) - float(b["segunda_m"])) < 6e-4, a["nombre"]


def test_s028_rf15_rf16_hay_un_punto_sin_altura_y_otro_a_cero_en_cada_sistema() -> None:
    for name in ("etrs89-utm30-utf8-bom-coma.csv", "regcan95-utm28.csv", "ed50-utm30.csv"):
        heights = [row["altura_m"] for row in rows_of(name)]
        assert heights.count("") == 1 and heights.count("0.0") == 1, name


def test_s028_rf11_rf23_el_fichero_de_errores_tiene_un_error_de_cada_clase_con_su_linea() -> None:
    errors = {row["estado"]: int(row["linea"]) for row in rows_of("etrs89-utm30-errores.csv") if row["estado"] != "OK"}
    assert errors == {
        "MISSING_NAME": 3,
        "NOT_A_NUMBER": 10,  # la última: "Miles con punto" y también la de la línea 4 con letras
        "MISSING_COLUMNS": 5,
        "OUTSIDE_CRS_AREA": 6,
        "DUPLICATE_IN_FILE": 7,
    }
    assert [int(row["linea"]) for row in rows_of("etrs89-utm30-errores.csv") if row["estado"] == "NOT_A_NUMBER"] == [4, 10]


def test_s028_rf23_las_lineas_del_fichero_son_las_fisicas_aunque_una_descripcion_lleve_un_salto() -> None:
    text = (DATOS / "etrs89-utm30-comillas.csv").read_bytes().decode("utf-8")
    physical = text.splitlines()  # cada salto de línea cuenta, también el de dentro de unas comillas
    lines = {row["nombre"]: int(row["linea"]) for row in rows_of("etrs89-utm30-comillas.csv")}
    assert lines == {"1": 2, "2": 3, "3": 5, "4": 6, "5": 7, "6": 8}
    assert physical[lines["3"] - 1].startswith('"3"')
    assert physical[lines["6"] - 1].startswith('"6"')


def test_s028_rf27_los_cuatro_sistemas_y_el_rechazo_geodesico_salen_de_pyproj() -> None:
    systems = {(row["datum"], row["huso"]) for row in expected() if row["estado"] == "OK"}
    assert systems == {("ETRS89", "30"), ("REGCAN95", "28"), ("ED50", "30")}
    assert all(math.isfinite(float(row["latitud"])) for row in expected() if row["estado"] == "OK")
    assert any(row["estado"] == "OUTSIDE_CRS_AREA" for row in expected())
