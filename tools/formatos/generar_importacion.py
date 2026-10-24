"""Ficheros de ejemplo de la importación de puntos y lo que debe salir de cada uno, calculado con pyproj (S028).

Genera en fixtures/formatos/csv-importacion/ diez ficheros con los mismos seis puntos escritos de formas distintas y un
`esperado.csv` con una fila por cada fila de datos de cada fichero:

- etrs89-utm30-utf8-bom-coma.csv: UTF-8 con BOM, coma de separador, punto decimal, con cabecera y una descripción con coma.
- etrs89-utm30-windows1252-puntoycoma.txt: Windows-1252, punto y coma, coma decimal, cabecera en español y acentos.
- etrs89-utm30-tabulador-sin-cabecera.txt: tabulador, sin cabecera, PENZD, saltos LF.
- etrs89-utm30-espacios-pnezd.txt: columnas alineadas con espacios, sin cabecera, en orden nombre, Norte, Este, altura, descripción.
- etrs89-utm30-comillas.csv: todo entre comillas, con una comilla duplicada y un salto de línea dentro de una descripción.
- etrs89-geograficas.csv: latitud, longitud y altura elipsoidal, con la cabecera de S012.
- regcan95-utm28.csv, ed50-utm30.csv: los mismos formatos en REGCAN95 UTM 28 y ED50 UTM 30.
- etrs89-utm30-pies.csv: Este, Norte y altura en pies internacionales.
- etrs89-utm30-errores.csv: una fila válida de cada clase y una con cada error (punto y coma, coma decimal).

- Lo escrito en cada fichero se redondea antes de calcular lo esperado (a 1 mm; a 1e-9° en geográficas; 1e-4 ft en pies), así que la
  app y este script parten de los mismos números.
- Lo esperado sale de `ManualCrs` y `manual_row` de tools/geodesia/generar_vectores.py: lo que la app hace con un punto manual
  (S011/RF-17 a RF-20), calculado con pyproj, con la rejilla SPED2ETV2 en ED50 y el geoide EGM08-REDNAP.
- La línea de cada fila es la del fichero, empezando en 1 y contando la cabecera, y es la de la primera línea de la fila si
  una descripción lleva un salto de línea.

Uso: uv run --project tools tools/formatos/generar_importacion.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass

import pyproj
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores as v  # noqa: E402

OUT = ROOT / "fixtures" / "formatos" / "csv-importacion"
EXPECTED = "esperado.csv"
FOOT = 0.3048
BOM = b"\xef\xbb\xbf"

# Nombre, descripción, latitud, longitud y altura del proyecto (None: sin altura) de los seis puntos de cada SC.
SPAIN = (
    ("1", "Esquina norte", 40.41668, -3.70371, 646.377),
    ("2", "Pozo, registro", 39.4699, -0.3763, 15.25),
    ("3", "Árbol ñandú", 41.6488, -0.8891, 0.0),
    ("4", "Sin cota", 37.3891, -5.9845, None),
    ("5", 'Línea "eje"', 40.9701, -5.6635, 802.125),
    ("6", "Borde", 42.8467, -2.6716, 512.5),
)
CANARIAS = (
    ("1", "Esquina norte", 28.4636, -16.2518, 120.5),
    ("2", "Pozo, registro", 28.1, -15.4, 35.0),
    ("3", "Árbol ñandú", 28.96, -13.55, 0.0),
    ("4", "Sin cota", 28.68, -17.86, None),
    ("5", 'Línea "eje"', 28.05, -16.5, 610.25),
    ("6", "Borde", 28.3, -16.7, 880.0),
)

COLUMNS = (
    "archivo,linea,estado,nombre,descripcion,forma,unidad,datum,huso,sistema_alturas,tipo_altura,"
    "primera_m,segunda_m,altura_m,latitud,longitud,altura_elipsoidal,este,norte,altura_proyecto"
)


@dataclass(frozen=True)
class Row:
    """Una fila de datos de un fichero: lo escrito (en la unidad del fichero) y lo esperado."""

    name: str
    description: str
    first: float | str  # Este o latitud; un texto si es lo que hay que escribir tal cual (un error)
    second: float | str
    height: float | None
    state: str = "OK"  # OK o el motivo del error
    raw: tuple[str, ...] | None = None  # las celdas tal cual, ya en el orden del fichero (una fila corta)


@dataclass
class Sample:
    file: str
    rows: list[Row]
    crs: tuple  # entrada de MANUAL_CRS
    form: str = "PROJECT_CRS"
    height_kind: str = "PROJECT"
    unit_m: float = 1.0
    decimals: int = 3
    encoding: str = "utf-8"
    bom: bool = False
    separator: str = ","
    decimal: str = "."
    header: list[str] | None = None
    order: tuple[int, ...] = (0, 1, 2, 3, 4)  # nombre, primera, segunda, altura, descripción → posición en la fila
    quote_all: bool = False
    newline: str = "\r\n"
    align: bool = False


def number(value: float | str, decimals: int, decimal: str) -> str:
    if isinstance(value, str):
        return value
    return f"{value:.{decimals}f}".replace(".", decimal)


def quote(text: str, separator: str, force: bool) -> str:
    if force or any(c in text for c in (separator, '"', "\n", "\r")):
        return '"' + text.replace('"', '""') + '"'
    return text


def typed_rows(points, crs_row: tuple, form: str, height_kind: str, unit_m: float, decimals: int) -> list[Row]:
    """Lo que el usuario escribiría para cada punto, redondeado a la precisión del fichero."""
    crs = v.ManualCrs(*crs_row)
    rows = []
    for name, description, lat, lon, height in points:
        first, second = crs.typed_from_base(lat, lon, form)
        if form == "GEOGRAPHIC":
            first, second = round(first, 9), round(second, 9)
        else:
            first, second = round(first / unit_m, decimals), round(second / unit_m, decimals)
        value = height
        if value is not None and height_kind == "ELLIPSOIDAL":
            value = round(height + crs.undulation(lat, lon), 3)
        elif value is not None:
            value = round(value / unit_m, decimals)
        rows.append(Row(name, description, first, second, value))
    return rows


def write(sample: Sample) -> tuple[bytes, list[int]]:
    """El fichero en bytes y la línea (1 = primera) donde empieza cada fila de datos."""
    lines: list[str] = []
    start_lines: list[int] = []
    current = 1
    sep = " " if sample.align else sample.separator
    if sample.header is not None:
        lines.append(sep.join(quote(h, sep, sample.quote_all) for h in sample.header))
        current += 1
    for row in sample.rows:
        cells = [
            row.name,
            number(row.first, sample.decimals, sample.decimal),
            number(row.second, sample.decimals, sample.decimal),
            "" if row.height is None else number(row.height, sample.decimals, sample.decimal),
            row.description,
        ]
        ordered = list(row.raw) if row.raw is not None else [cells[i] for i in sample.order]
        if sample.align:
            text = "  ".join(f"{c:<14}" for c in ordered).rstrip()
        else:
            text = sep.join(quote(c, sep, sample.quote_all) for c in ordered)
        start_lines.append(current)
        lines.append(text)
        current += text.count("\n") + 1
    body = sample.newline.join(lines) + sample.newline
    data = body.encode(sample.encoding)
    return (BOM + data if sample.bom else data), start_lines


def expected_row(sample: Sample, row: Row, line: int) -> list[str]:
    """Una fila de esperado.csv: lo escrito en metros y lo que sale con pyproj, o el motivo de error."""
    datum, zone = sample.crs[0], sample.crs[1]
    crs = v.ManualCrs(*sample.crs)
    head = [sample.file, str(line), row.state, row.name, row.description, sample.form, unit_text(sample.unit_m), datum, str(zone or "")]
    if row.state != "OK":
        if row.state.startswith("OUTSIDE_"):
            # El rechazo geodésico lo confirma pyproj, no lo dice el script porque sí.
            result = v.manual_row(crs, "ORTHOMETRIC", sample.form, row.first * sample.unit_m, row.second * sample.unit_m, row.height, sample.height_kind)
            if result[14] != row.state:
                raise SystemExit(f"{sample.file} línea {line}: pyproj da {result[14] or 'un punto válido'}, no {row.state}")
        return head + ["ORTHOMETRIC", sample.height_kind] + [""] * 9
    first = row.first * sample.unit_m if sample.form == "PROJECT_CRS" else row.first
    second = row.second * sample.unit_m if sample.form == "PROJECT_CRS" else row.second
    height = None if row.height is None else row.height * (sample.unit_m if sample.height_kind == "PROJECT" else 1.0)
    result = v.manual_row(crs, "ORTHOMETRIC", sample.form, first, second, height, sample.height_kind)
    # manual_row: datum, huso, alturas, forma, primera, segunda, altura, tipo, latitud, longitud, elipsoidal, este, norte, proyecto, motivo
    ground = result[8:14]
    reason = result[14]
    typed = [v.fmt(first), v.fmt(second), "" if height is None else v.fmt(height)]
    state = reason if reason else "OK"
    return head[:2] + [state] + head[3:] + ["ORTHOMETRIC", sample.height_kind] + typed + ground


def unit_text(unit_m: float) -> str:
    return {1.0: "m", FOOT: "ft"}[unit_m]


def samples() -> list[Sample]:
    etrs30 = ("ETRS89", 30, "EPSG:4258", "EPSG:25830")
    regcan28 = ("REGCAN95", 28, "EPSG:4081", "EPSG:4083")
    ed50_30 = ("ED50", 30, None, None)
    header_en = ["Name", "Easting", "Northing", "Elevation", "Description"]
    out: list[Sample] = []

    out.append(Sample(
        "etrs89-utm30-utf8-bom-coma.csv", typed_rows(SPAIN, etrs30, "PROJECT_CRS", "PROJECT", 1.0, 3), etrs30,
        bom=True, header=header_en,
    ))
    out.append(Sample(
        "etrs89-utm30-windows1252-puntoycoma.txt", typed_rows(SPAIN, etrs30, "PROJECT_CRS", "PROJECT", 1.0, 3), etrs30,
        encoding="cp1252", separator=";", decimal=",", header=["Punto", "Este", "Norte", "Altura", "Descripción"],
    ))
    out.append(Sample(
        "etrs89-utm30-tabulador-sin-cabecera.txt",
        typed_rows([(n, d.replace('"', ""), la, lo, h) for n, d, la, lo, h in SPAIN], etrs30, "PROJECT_CRS", "PROJECT", 1.0, 3),
        etrs30, separator="\t", newline="\n",
    ))
    out.append(Sample(
        "etrs89-utm30-espacios-pnezd.txt",
        # Sin espacios en la descripción y con altura en todos: con espacios de separador, una celda vacía correría las columnas.
        typed_rows([(n, d.split()[0].replace('"', "").replace(",", ""), la, lo, 99.0 if h is None else h) for n, d, la, lo, h in SPAIN],
                   etrs30, "PROJECT_CRS", "PROJECT", 1.0, 3),
        etrs30, align=True, newline="\n", order=(0, 2, 1, 3, 4),
    ))
    out.append(Sample(
        "etrs89-utm30-comillas.csv",
        typed_rows([(n, d if n != "2" else "Pozo,\nregistro", la, lo, h) for n, d, la, lo, h in SPAIN],
                   etrs30, "PROJECT_CRS", "PROJECT", 1.0, 3),
        etrs30, header=header_en, quote_all=True,
    ))
    out.append(Sample(
        "etrs89-geograficas.csv", typed_rows(SPAIN, etrs30, "GEOGRAPHIC", "ELLIPSOIDAL", 1.0, 3), etrs30,
        form="GEOGRAPHIC", height_kind="ELLIPSOIDAL", decimals=9, header=["Name", "Latitude", "Longitude", "Ellipsoidal height", "Description"],
    ))
    out.append(Sample(
        "regcan95-utm28.csv", typed_rows(CANARIAS, regcan28, "PROJECT_CRS", "PROJECT", 1.0, 3), regcan28, header=header_en,
    ))
    out.append(Sample(
        "ed50-utm30.csv", typed_rows(SPAIN, ed50_30, "PROJECT_CRS", "PROJECT", 1.0, 3), ed50_30, header=header_en,
    ))
    out.append(Sample(
        "etrs89-utm30-pies.csv", typed_rows(SPAIN, etrs30, "PROJECT_CRS", "PROJECT", FOOT, 4), etrs30,
        unit_m=FOOT, decimals=4, header=header_en,
    ))
    out.append(errors_sample(etrs30))
    return out


def errors_sample(crs_row: tuple) -> Sample:
    good = typed_rows(SPAIN, crs_row, "PROJECT_CRS", "PROJECT", 1.0, 3)
    a, b, c, d = good[0], good[1], good[2], good[3]
    rows = [
        a,  # línea 2: válida
        Row("", "Sin nombre", b.first, b.second, b.height, "MISSING_NAME"),  # línea 3
        Row("E1", "Este con letras", "abc", b.second, b.height, "NOT_A_NUMBER"),  # línea 4
        Row("E2", "Fila corta", b.first, "", None, "MISSING_COLUMNS", raw=("E2", "440290,458")),  # línea 5: solo dos columnas
        Row("E3", "Fuera del huso", 100.0, 100.0, 5.0, "OUTSIDE_CRS_AREA"),  # línea 6
        Row(a.name, "Nombre repetido", b.first, b.second, b.height, "DUPLICATE_IN_FILE"),  # línea 7
        d,  # línea 8: altura vacía
        c,  # línea 9: altura 0
        Row("E4", "Miles con punto", "440.290,458", b.second, b.height, "NOT_A_NUMBER"),  # línea 10
    ]
    return Sample("etrs89-utm30-errores.csv", rows, crs_row, separator=";", decimal=",", header=["Nombre", "Este", "Norte", "Altura", "Descripción"])


def generate() -> dict[str, bytes | str]:
    files: dict[str, bytes | str] = {}
    lines = [
        "# Lo esperado de los ficheros de importación (S028). Generado por tools/formatos/generar_importacion.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla=es_ign_SPED2ETV2.tif; geoide EGM08-REDNAP",
        "# primera_m, segunda_m, altura_m: lo escrito en metros (grados en geográficas). estado: OK o el motivo de error de la fila.",
        "# latitud ... altura_proyecto: lo que sale con pyproj para las filas OK, en el SC de la columna datum y huso, con alturas ortométricas.",
        COLUMNS,
    ]
    for sample in samples():
        data, start_lines = write(sample)
        files[sample.file] = data
        for row, line in zip(sample.rows, start_lines):
            lines.append(",".join(csv_cell(c) for c in expected_row(sample, row, line)))
    files[EXPECTED] = "\n".join(lines) + "\n"
    return files


def csv_cell(text: str) -> str:
    return '"' + text.replace('"', '""') + '"' if any(c in text for c in (',', '"', "\n")) else text


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        path = OUT / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} {'bytes' if isinstance(content, bytes) else 'caracteres'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
