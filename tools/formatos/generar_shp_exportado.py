"""Referencias de la exportación a Shapefile calculadas aparte de la app (S055).

Genera en fixtures/formatos/shp-exportado/:

- ejemplo-puntos|lineas|poligonos.shp, .shx, .dbf y .cpg: los ficheros que debe escribir la app para un proyecto de ejemplo (puntos con y
  sin altura, uno sin posición, textos con acentos y uno de más de 254 bytes; líneas; y polígonos, uno en sentido antihorario). Se escriben
  con `struct` de la biblioteca estándar siguiendo la especificación de ESRI y de dBase III, sin pasar por ningún código de la app: los
  tests de `core:formats` comparan lo que escribe el móvil con ellos byte a byte.
- entrada-puntos|lineas|poligonos.csv: lo que se le da a cada escritor para obtener esos ficheros. Primera columna `geom` (vértices separados
  por `;`, cada uno `x y z`; vacía = objeto sin geometría) y después los atributos, con su tipo en la cabecera (`NOMBRE:C`, `SATELITES:I`,
  `COTA:D` = texto, entero y decimal). Ningún valor lleva comas, comillas ni saltos de línea.
- prj/<epsg>.prj: el `.prj` de cada sistema de los proyectos de S008 y de las geográficas de cada datum, en WKT1 de ESRI con pyproj. El texto
  cambia con la versión de PROJ: el test lo compara por el sistema que define, no por el texto.
- esperado-puntos.csv: los mismos puntos de la Península, Baleares y Canarias que usa S053, con su latitud y longitud y su Este y Norte en cada
  sistema proyectado, con pyproj (constitución nº 2).
- `--kotlin <ruta>` escribe el ShpPrjDefinitionsData.kt de `core:formats`, con los WKT como constantes para que el móvil no necesite PROJ.

Y lo que usa el test de `tools`: lectores de `.shp`, `.shx` y `.dbf` independientes del móvil (`read_shp`, `read_shx`, `read_dbf`).

Reglas de este conjunto que la spec no fija y que quedan escritas aquí (plan, A2 y A3):
- `.dbf` versión 0x03, motor de idioma 0x00 y `.cpg` con `UTF-8`; texto `C` del ancho del mayor valor en bytes (1 a 254), enteros `N(w,0)` del
  ancho del mayor valor y decimales `N(20,9)` redondeados a 9 decimales con el valor exacto del `double` (mitades al par), sin cero negativo.
- Un texto de más de 254 bytes se recorta sin partir un carácter UTF-8. Un decimal que no cabe en 20 caracteres pierde decimales hasta caber.
- `.shp`: PointZ (11) con M = -1e38, PolyLineZ (13) y PolygonZ (15) sin M; el contorno de un polígono en sentido horario (se conserva el
  primer vértice) con el primer vértice repetido al final; un objeto sin geometría es un registro nulo; la envolvente son los valores escritos.

Uso: uv run --project tools tools/formatos/generar_shp_exportado.py
"""

from __future__ import annotations

import struct
import sys
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal, localcontext
from pathlib import Path

import pyproj
from pyproj import CRS
from pyproj.enums import WktVersion

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "shp-exportado"
DBF_DATE = (2026, 11, 12)
MAX_TEXT_BYTES = 254
DECIMAL_WIDTH = 20
DECIMAL_SCALE = 9
MAX_INTEGER_WIDTH = 20
NO_DATA = -1e38
CPG = b"UTF-8"

POINT, LINE, POLYGON = "point", "line", "polygon"
SHAPE_TYPE = {POINT: 11, LINE: 13, POLYGON: 15}
LAYERS = {POINT: "puntos", LINE: "lineas", POLYGON: "poligonos"}

# Los sistemas de S008 (ProjectCrs) y las geográficas de cada datum (las de los proyectos ED50 son las de ETRS89).
PRJ_SYSTEMS = (
    (25829, "ETRS89 / UTM 29"),
    (25830, "ETRS89 / UTM 30"),
    (25831, "ETRS89 / UTM 31"),
    (4258, "ETRS89 geográficas"),
    (4082, "REGCAN95 / UTM 27"),
    (4083, "REGCAN95 / UTM 28"),
    (4081, "REGCAN95 geográficas"),
    (23029, "ED50 / UTM 29"),
    (23030, "ED50 / UTM 30"),
    (23031, "ED50 / UTM 31"),
)


# ---------------------------------------------------------------------------------------------------------------------- el ejemplo
@dataclass(frozen=True)
class Column:
    name: str
    kind: str  # C texto, I entero, D decimal


@dataclass(frozen=True)
class Sample:
    kind: str
    columns: tuple[Column, ...]
    geoms: tuple[tuple[tuple[float, float, float], ...] | None, ...]  # un objeto = sus vértices; None = sin geometría
    rows: tuple[tuple[object, ...], ...]


LONG_TEXT = "x" * 253 + "éé"  # 255 bytes: al recortar a 254 se pierde la «é» entera y quedan 253

SAMPLES = {
    POINT: Sample(
        POINT,
        (
            Column("NOMBRE", "C"),
            Column("CODIGO", "C"),
            Column("ESTE", "D"),
            Column("NORTE", "D"),
            Column("COTA", "D"),
            Column("SATELITES", "I"),
            Column("REPRODUC", "I"),
            Column("MED_INICIO", "C"),
            Column("NOTAS", "C"),
        ),
        (
            ((440000.123456789, 4474000.987654321, 648.25),),
            ((440100.5, 4474100.25, 0.0),),
            None,
            ((-3.7038, 40.4168, -12.5),),
        ),
        (
            ("P1", "ARBOL", 440000.123456789, 4474000.987654321, 648.25, 24, 0, "2026-10-28T09:15:30.250Z", "Árbol ñandú €"),
            ("P2", None, 440100.5, 4474100.25, None, 18, 1, None, None),
            ("P3", "VALLA", None, None, 12.0, None, None, None, "sin posición"),
            ("P4", "POSTE", -3.7038, 40.4168, -12.5, 1234567, 0, None, LONG_TEXT),
        ),
    ),
    LINE: Sample(
        LINE,
        (Column("NOMBRE", "C"), Column("CODIGO", "C"), Column("VERTICES", "I"), Column("ANCHO", "D")),
        (
            ((440000.0, 4474000.0, 648.0), (440010.5, 4474000.25, 648.5), (440020.0, 4474010.75, 0.0)),
            ((440100.0, 4474100.0, 650.0), (440100.0, 4474150.0, 651.25)),
        ),
        (("L1", "MURO", 3, 0.3), ("L2", None, 2, None)),
    ),
    POLYGON: Sample(
        POLYGON,
        (Column("NOMBRE", "C"), Column("CODIGO", "C"), Column("VERTICES", "I")),
        (
            # antihorario: el escritor lo invierte y conserva el primer vértice
            ((440000.0, 4474000.0, 648.0), (440010.0, 4474000.0, 648.5), (440010.0, 4474010.0, 649.0), (440000.0, 4474010.0, 648.75)),
            # ya horario
            ((440100.0, 4474100.0, 650.0), (440100.0, 4474120.0, 650.5), (440130.0, 4474100.0, 651.0)),
        ),
        (("PA1", "PARCELA", 4), ("PA2", "SOLAR", 3)),
    ),
}


# ----------------------------------------------------------------------------------------------------------------------- el .dbf
def truncate_utf8(text: str) -> tuple[bytes, bool]:
    """El texto en UTF-8 recortado a 254 bytes sin partir un carácter, y si se ha recortado."""
    data = text.encode("utf-8")
    if len(data) <= MAX_TEXT_BYTES:
        return data, False
    cut = MAX_TEXT_BYTES
    while cut > 0 and (data[cut] & 0xC0) == 0x80:  # el byte que queda fuera es de continuación: el carácter se partiría
        cut -= 1
    return data[:cut], True


def decimal_text(value: float) -> str | None:
    """`value` con 9 decimales (o los que quepan en 20 caracteres), con el valor exacto del double; None si no cabe ni sin decimales."""
    with localcontext() as ctx:
        ctx.prec = 100
        exact = Decimal(value)
        for scale in range(DECIMAL_SCALE, -1, -1):
            text = format(exact.quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_EVEN), "f")
            if text.startswith("-") and not any(c in "123456789" for c in text):
                text = text[1:]  # sin cero negativo
            if len(text) <= DECIMAL_WIDTH:
                return text
    return None


def dbf_bytes(columns: tuple[Column, ...], rows: tuple[tuple[object, ...], ...], date: tuple[int, int, int] = DBF_DATE) -> tuple[bytes, int]:
    """El `.dbf` de `rows` y cuántos textos se han recortado."""
    truncated = 0
    cells: list[list[bytes | None]] = []
    for row in rows:
        out: list[bytes | None] = []
        for column, value in zip(columns, row):
            if value is None:
                out.append(None)
            elif column.kind == "C":
                data, cut = truncate_utf8(str(value))
                truncated += cut
                out.append(data)
            elif column.kind == "I":
                out.append(str(int(value)).encode("ascii"))
            else:
                text = decimal_text(float(value))
                out.append(None if text is None else text.encode("ascii"))
        cells.append(out)
    widths = []
    for index, column in enumerate(columns):
        if column.kind == "D":
            widths.append(DECIMAL_WIDTH)
        else:
            longest = max((len(r[index]) for r in cells if r[index] is not None), default=1)
            widths.append(min(max(longest, 1), MAX_TEXT_BYTES if column.kind == "C" else MAX_INTEGER_WIDTH))
    header_length = 32 + 32 * len(columns) + 1
    record_length = 1 + sum(widths)
    head = bytearray(32)
    head[0] = 0x03
    head[1:4] = bytes((date[0] - 1900, date[1], date[2]))
    head[4:8] = struct.pack("<I", len(rows))
    head[8:10] = struct.pack("<H", header_length)
    head[10:12] = struct.pack("<H", record_length)
    out_bytes = bytearray(head)
    for column, width in zip(columns, widths):
        descriptor = bytearray(32)
        descriptor[0 : len(column.name)] = column.name.encode("ascii")
        descriptor[11] = ord("C" if column.kind == "C" else "N")
        descriptor[16] = width
        descriptor[17] = DECIMAL_SCALE if column.kind == "D" else 0
        out_bytes += descriptor
    out_bytes.append(0x0D)
    for row in cells:
        out_bytes.append(0x20)
        for column, width, data in zip(columns, widths, row):
            if data is None:
                out_bytes += b" " * width
            elif column.kind == "C":
                out_bytes += data + b" " * (width - len(data))
            else:
                out_bytes += b" " * (width - len(data)) + data
    out_bytes.append(0x1A)
    return bytes(out_bytes), truncated


# ------------------------------------------------------------------------------------------------------------------- .shp y .shx
def signed_area(vertices: tuple[tuple[float, float, float], ...]) -> float:
    """La mitad de la suma de productos cruzados, con el primer vértice como origen: positiva si el contorno es antihorario."""
    x0, y0, _ = vertices[0]
    total = 0.0
    for (xa, ya, _a), (xb, yb, _b) in zip(vertices, vertices[1:] + vertices[:1]):
        total += (xa - x0) * (yb - y0) - (xb - x0) * (ya - y0)
    return total / 2.0


def clockwise_closed(vertices: tuple[tuple[float, float, float], ...]) -> list[tuple[float, float, float]]:
    """El contorno en sentido horario, conservando el primer vértice, y con el primer vértice repetido al final."""
    ring = list(vertices)
    if signed_area(vertices) > 0:
        ring = [vertices[0]] + list(reversed(vertices[1:]))
    return ring + [ring[0]]


def _header(shape_type: int, words: int, extent: list[float]) -> bytes:
    x0, y0, x1, y1, z0, z1 = extent
    return (
        struct.pack(">i5ii", 9994, 0, 0, 0, 0, 0, words)
        + struct.pack("<ii", 1000, shape_type)
        + struct.pack("<8d", x0, y0, x1, y1, z0, z1, 0.0, 0.0)
    )


def shp_bytes(kind: str, geoms: tuple[tuple[tuple[float, float, float], ...] | None, ...]) -> tuple[bytes, bytes]:
    """El `.shp` y el `.shx` de [geoms]: PointZ, PolyLineZ o PolygonZ y, para cada objeto sin geometría, un registro nulo."""
    shape_type = SHAPE_TYPE[kind]
    contents: list[bytes] = []
    written: list[tuple[float, float, float]] = []
    for geom in geoms:
        if geom is None:
            contents.append(struct.pack("<i", 0))
            continue
        if kind == POINT:
            (x, y, z), = geom
            contents.append(struct.pack("<i4d", shape_type, x, y, z, NO_DATA))
            written.append((x, y, z))
            continue
        vertices = clockwise_closed(geom) if kind == POLYGON else list(geom)
        xs = [v[0] for v in vertices]
        ys = [v[1] for v in vertices]
        zs = [v[2] for v in vertices]
        body = struct.pack("<i4dii", shape_type, min(xs), min(ys), max(xs), max(ys), 1, len(vertices)) + struct.pack("<i", 0)
        body += b"".join(struct.pack("<2d", x, y) for x, y, _ in vertices)
        body += struct.pack("<2d", min(zs), max(zs)) + b"".join(struct.pack("<d", z) for z in zs)
        contents.append(body)
        written += vertices
    extent = (
        [min(v[0] for v in written), min(v[1] for v in written), max(v[0] for v in written), max(v[1] for v in written),
         min(v[2] for v in written), max(v[2] for v in written)]
        if written
        else [0.0] * 6
    )
    shp = bytearray()
    shx = bytearray()
    offset = 100
    for number, content in enumerate(contents, start=1):
        shp += struct.pack(">ii", number, len(content) // 2) + content
        shx += struct.pack(">ii", offset // 2, len(content) // 2)
        offset += 8 + len(content)
    return _header(shape_type, offset // 2, extent) + bytes(shp), _header(shape_type, (100 + len(shx)) // 2, extent) + bytes(shx)


# ------------------------------------------------------------------------------------------------------------------------- lectores
@dataclass
class ShpFile:
    shape_type: int
    length_words: int
    extent: tuple[float, ...]  # x0, y0, x1, y1, z0, z1, m0, m1
    records: list[tuple[int, int, list[list[tuple[float, ...]]] | tuple[float, ...] | None]]  # (número, tipo, partes | punto | None)


def read_shp(data: bytes) -> ShpFile:
    """Un lector de `.shp` por la especificación de ESRI (PointZ, PolyLineZ y PolygonZ con o sin M); no es el de la app."""
    assert struct.unpack(">i", data[:4])[0] == 9994 and struct.unpack("<i", data[28:32])[0] == 1000
    length_words = struct.unpack(">i", data[24:28])[0]
    assert length_words * 2 == len(data), (length_words * 2, len(data))
    shape_type = struct.unpack("<i", data[32:36])[0]
    extent = struct.unpack("<8d", data[36:100])
    records = []
    pos = 100
    while pos < len(data):
        number, words = struct.unpack(">ii", data[pos : pos + 8])
        body = data[pos + 8 : pos + 8 + words * 2]
        assert len(body) == words * 2
        kind = struct.unpack("<i", body[:4])[0]
        if kind == 0:
            records.append((number, 0, None))
        elif kind == 11:
            records.append((number, 11, struct.unpack("<4d", body[4:36])))
        else:
            n_parts, n_points = struct.unpack("<ii", body[36:44])
            parts = list(struct.unpack(f"<{n_parts}i", body[44 : 44 + 4 * n_parts]))
            at = 44 + 4 * n_parts
            xy = [struct.unpack("<2d", body[at + 16 * i : at + 16 * i + 16]) for i in range(n_points)]
            at += 16 * n_points + 16
            z = list(struct.unpack(f"<{n_points}d", body[at : at + 8 * n_points]))
            assert len(body) == at + 8 * n_points, "sin M: el registro acaba con las Z"
            bounds = [*struct.unpack("<4d", body[4:36])]
            assert bounds == [min(p[0] for p in xy), min(p[1] for p in xy), max(p[0] for p in xy), max(p[1] for p in xy)]
            ends = parts[1:] + [n_points]
            records.append((number, kind, [[(xy[i][0], xy[i][1], z[i]) for i in range(a, b)] for a, b in zip(parts, ends)]))
        pos += 8 + words * 2
    return ShpFile(shape_type, length_words, extent, records)


def read_shx(data: bytes) -> tuple[int, list[tuple[int, int]]]:
    """El tipo de forma y los (posición, longitud) en bytes de cada registro del `.shx`."""
    assert struct.unpack(">i", data[:4])[0] == 9994 and struct.unpack(">i", data[24:28])[0] * 2 == len(data)
    count = (len(data) - 100) // 8
    entries = [struct.unpack(">ii", data[100 + 8 * i : 108 + 8 * i]) for i in range(count)]
    return struct.unpack("<i", data[32:36])[0], [(o * 2, c * 2) for o, c in entries]


@dataclass
class DbfFile:
    date: tuple[int, int, int]
    columns: list[tuple[str, str, int, int]]  # nombre, tipo, ancho, decimales
    rows: list[list[str]]  # el texto de cada campo sin los espacios de relleno ("" = vacío)
    language: int


def read_dbf(data: bytes) -> DbfFile:
    """Un lector de dBase III: cabecera, descriptores y registros, con el texto decodificado como UTF-8."""
    assert data[0] == 0x03 and data[-1] == 0x1A
    count, header_length, record_length = struct.unpack("<IHH", data[4:12])
    columns = []
    at = 32
    while data[at] != 0x0D:
        name = data[at : at + 11].split(b"\x00")[0].decode("ascii")
        columns.append((name, chr(data[at + 11]), data[at + 16], data[at + 17]))
        at += 32
    assert at + 1 == header_length
    assert record_length == 1 + sum(c[2] for c in columns)
    assert len(data) == header_length + count * record_length + 1
    rows = []
    for i in range(count):
        record = data[header_length + i * record_length : header_length + (i + 1) * record_length]
        assert record[0] == 0x20
        field = 1
        row = []
        for _name, _kind, width, _dec in columns:
            row.append(record[field : field + width].decode("utf-8").strip(" "))
            field += width
        rows.append(row)
    return DbfFile((data[1] + 1900, data[2], data[3]), columns, rows, data[29])


# ------------------------------------------------------------------------------------------------------------------------ las salidas
def num(x: float) -> str:
    return repr(float(x))


def entrada_csv(sample: Sample) -> str:
    """El `entrada-*.csv` del ejemplo: lo que hay que darle al escritor para obtener sus ficheros de referencia."""
    lines = [",".join(["geom"] + [f"{c.name}:{c.kind}" for c in sample.columns])]
    for geom, row in zip(sample.geoms, sample.rows):
        shape = "" if geom is None else ";".join(" ".join(num(v) for v in vertex) for vertex in geom)
        cells = ["" if v is None else (num(v) if c.kind == "D" else str(v)) for c, v in zip(sample.columns, row)]
        lines.append(",".join([shape] + cells))
    return "\n".join(lines) + "\n"


def esri_wkt(epsg: int) -> str:
    return CRS.from_epsg(epsg).to_wkt(WktVersion.WKT1_ESRI)


def points_csv() -> str:
    """Los puntos de S053 con su latitud, longitud, Este y Norte (pyproj): sirven para comprobar las coordenadas del Shapefile."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import generar_gpkg_exportado as gpkg

    return gpkg.points_csv()


def all_files() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for kind, sample in SAMPLES.items():
        name = f"ejemplo-{LAYERS[kind]}"
        shp, shx = shp_bytes(kind, sample.geoms)
        dbf, _ = dbf_bytes(sample.columns, sample.rows)
        files[f"{name}.shp"] = shp
        files[f"{name}.shx"] = shx
        files[f"{name}.dbf"] = dbf
        files[f"{name}.cpg"] = CPG
        files[f"entrada-{LAYERS[kind]}.csv"] = entrada_csv(sample).encode("utf-8")
    for epsg, _ in PRJ_SYSTEMS:
        files[f"prj/{epsg}.prj"] = esri_wkt(epsg).encode("ascii")
    files["esperado-puntos.csv"] = points_csv().encode("utf-8")
    return files


def kotlin_source() -> str:
    """El `ShpPrjDefinitionsData.kt` de `core:formats`: el WKT1 de ESRI de pyproj de cada sistema, como constantes."""
    lines = [
        "package es.topophone.core.formats.shp.export",
        "",
        "// Generado por tools/formatos/generar_shp_exportado.py --kotlin con pyproj " + pyproj.__version__ + " (PROJ " + pyproj.proj_version_str + "); no editar a mano.",
        "internal object ShpPrjDefinitionsData {",
        "    /** El código EPSG, el nombre y el `.prj` (WKT1 de ESRI) de cada sistema de la exportación. */",
        "    val ALL: List<Triple<Int, String, String>> = listOf(",
    ]
    for epsg, name in PRJ_SYSTEMS:
        text = esri_wkt(epsg)
        chunks = [text[i : i + 100].replace("\\", "\\\\").replace('"', '\\"') for i in range(0, len(text), 100)]
        lines.append("        Triple(")
        lines.append(f"            {epsg},")
        lines.append(f'            "{name}",')
        lines.append("            " + " +\n                ".join(f'"{chunk}"' for chunk in chunks) + ",")
        lines.append("        ),")
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def main(out: Path = OUT) -> int:
    if len(sys.argv) > 2 and sys.argv[1] == "--kotlin":
        Path(sys.argv[2]).write_text(kotlin_source(), encoding="utf-8", newline="\n")
        print(f"Kotlin en {sys.argv[2]}")
        return 0
    files = all_files()
    for name, data in files.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f"{len(files)} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
