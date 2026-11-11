"""Shapefiles de ejemplo de la importación y lo que debe salir de cada uno (S030).

Genera en fixtures/formatos/shp/ una carpeta por escenario con los ficheros sueltos de cada capa (`.shp`, `.shx`, `.dbf`, `.prj`, `.cpg`),
escritos con `struct` de la biblioteca estándar, sin librerías de Shapefile, y los ficheros de lo esperado:

- esperado.csv: una fila por elemento que debe salir de cada objeto: escenario, capa, número de registro (desde 1), capa de diseño (POINTS o
  LINES), número de elemento, anillo (la posición del anillo dentro del polígono), si es contorno de polígono, vértices tal como están en el
  `.shp` ("x y z", con `_` si falta la altura; la M se descarta) y en ETRS89 UTM 30 (el SC del proyecto), y el estado del objeto (OK,
  ignorado_vacia, ignorado_multipatch, ignorado_borrado o fuera_de_ambito).
- esperado-capas.csv: una fila por capa: tipo de geometría, tipo del `.shp`, objetos del `.shp` y registros del `.dbf`, sistema de la app y
  unidad según su `.prj`, codificación que debe usarse para el `.dbf`, si el `.cpg` es de una codificación que no se conoce y las columnas.
- esperado-atributos.csv: una fila por valor del `.dbf` que se ve en la ficha: escenario, capa, registro, columna, tipo y valor.
- esperado-prj.csv: una fila por `.prj` de prj/ (cada sistema de la app y otros que no lo son, en tres estilos de WKT): el sistema de la app, su
  estado (DISPONIBLE, WGS84, NO_DISPONIBLE o SIN_SISTEMA) y el nombre que trae el WKT.

Reglas de este conjunto que la spec no fija y que quedan escritas en lo esperado (se confirman al hacer cada tarea):
- Un anillo de polígono es una línea cerrada con la marca de contorno: no se distingue el exterior de un hueco (su `anillo` es la posición
  del anillo en el registro). Los anillos se escriben como pide el formato: exterior en sentido horario y huecos en antihorario.
- Las coordenadas de proyecto se calculan como las calcula la app (generar_geopackage.py): ED50 con la rejilla y todo hacia ETRS89 UTM 30.
  EPSG:4326 se trata como ETRS89; un objeto con un vértice fuera del ámbito del proyecto se descarta entero.
- Z se conserva sin convertir y M se ignora. Los tipos con Z se escriben sin M salvo en las capas «zm», que la llevan.
- Un campo de texto en blanco, o un número en blanco, es un valor nulo (columna vacía en lo esperado).
- Sin `.cpg`, el `.dbf` es UTF-8 si todo el texto lo es y Windows-1252 si no; un `.cpg` que no se conoce se lee como Windows-1252.
- Las coordenadas se escriben a su precisión completa (repr de Python).

Uso: uv run --project tools tools/formatos/generar_shapefile.py
"""

from __future__ import annotations

import math
import re
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path

import pyproj
from pyproj import Transformer
from pyproj.enums import WktVersion

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_geopackage as g  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "shp"

KIND_BASE = {"POINT": 1, "POLYLINE": 3, "POLYGON": 5, "MULTIPOINT": 8}
TYPE_NAME = {"POINT": "POINT", "MULTIPOINT": "MULTIPOINT", "POLYLINE": "LINESTRING", "POLYGON": "POLYGON", "MULTIPATCH": "POLYGON"}
NO_DATA = -1e38


# ---------- sistemas de coordenadas ----------

EXTRA_TRANSFORMERS = {
    srs: Transformer.from_crs("EPSG:4258", f"EPSG:{srs}", always_xy=True) for srs in (25828, 2263)
}


def shp_xy(srs: int, lon: float, lat: float) -> tuple[float, float]:
    """Las coordenadas que se escriben en una capa del SRS dado para el punto (lon, lat)."""
    if srs in EXTRA_TRANSFORMERS:
        return EXTRA_TRANSFORMERS[srs].transform(lon, lat)
    return g.layer_xy(srs, lon, lat)


def crs_wkt(srs: int, style: str) -> str:
    """El WKT de un .prj en uno de los tres estilos que se ven: ESRI (QGIS y ArcGIS), OGC con AUTHORITY y OGC sin AUTHORITY."""
    crs = pyproj.CRS.from_epsg(srs)
    if style == "esri":
        return crs.to_wkt(WktVersion.WKT1_ESRI)
    wkt = crs.to_wkt(WktVersion.WKT1_GDAL)
    if style == "gdal":
        return wkt
    if style == "gdal-sin-autoridad":
        return re.sub(r',\s*AUTHORITY\["[^"]*","[^"]*"\]', "", wkt)
    raise ValueError(style)


def wkt_name(wkt: str) -> str:
    return re.match(r'\w+\["([^"]*)"', wkt).group(1)


def sistema_app(srs: int) -> str:
    row = g.CRS_BY_EPSG.get(srs)
    return row[1] if row is not None and row[2] in ("DISPONIBLE", "WGS84") else "NO_DISPONIBLE"


def srs_available(srs: int) -> bool:
    return sistema_app(srs) != "NO_DISPONIBLE"


def unit_of(srs: int) -> str:
    unit = pyproj.CRS.from_epsg(srs).axis_info[0].unit_name
    return {"metre": "m", "degree": "deg"}.get(unit, unit.replace(" ", "_"))


# ---------- formas ----------


@dataclass
class Shape:
    """Una forma en coordenadas base (lon, lat, z, m).

    kind: POINT, MULTIPOINT, POLYLINE, POLYGON o MULTIPATCH. parts: un vértice (POINT), una lista de vértices (MULTIPOINT) o una lista de
    partes (POLYLINE, POLYGON y MULTIPATCH). dims: XY, XYZ, XYM o XYZM.
    """

    kind: str
    parts: object
    dims: str = "XY"
    exterior: list[bool] | None = None  # de un polígono: por anillo, si es exterior (True) o hueco (False)


@dataclass
class Feat:
    shape: Shape | None  # None es una forma nula
    attrs: dict = field(default_factory=dict)
    deleted: bool = False  # marcado como borrado en el .dbf


@dataclass
class Field:
    name: str
    kind: str  # C, N, F, L, D
    length: int
    decimals: int = 0

    @property
    def declared(self) -> str:
        if self.kind == "C":
            return "TEXT"
        if self.kind in ("N", "F"):
            return "REAL" if self.decimals else "INTEGER"
        return {"D": "DATE", "L": "BOOLEAN"}[self.kind]


@dataclass
class Layer:
    name: str
    kind: str
    srs: int
    feats: list[Feat]
    fields: list[Field] = field(default_factory=lambda: [Field("nombre", "C", 30)])
    dims: str = "XY"
    prj: str | None = "esri"  # estilo del .prj; None no lo escribe; "ilegible" escribe un texto que no es WKT
    cpg: str | None = "UTF-8"
    encoding: str = "utf-8"  # en el que se escriben los textos del .dbf
    shx: str = "bien"  # bien, falta o roto
    dbf: str = "bien"  # bien, falta, corto o largo
    ldid: int = 0
    extension: str = "shp"  # la extensión con la que se guarda el .shp (para el reconocimiento por contenido)
    case: str = "minusculas"  # minusculas o mixto (los nombres de los ficheros)
    shape_type: int | None = None  # el tipo de la cabecera si no sale de kind y dims (MultiPatch)

    @property
    def type_code(self) -> int:
        if self.shape_type is not None:
            return self.shape_type
        base = KIND_BASE[self.kind]
        return base + (10 if "Z" in self.dims else 20 if "M" in self.dims else 0)


# ---------- coordenadas de las formas ----------


def written(layer: Layer, shape: Shape | None) -> Shape | None:
    """La forma con cada vértice pasado a las coordenadas del SRS de la capa."""
    if shape is None:
        return None

    def fn(p: tuple) -> tuple:
        return shp_xy(layer.srs, p[0], p[1]) + tuple(p[2:])

    if shape.kind == "POINT":
        return Shape(shape.kind, fn(shape.parts), shape.dims)
    if shape.kind == "MULTIPOINT":
        return Shape(shape.kind, [fn(p) for p in shape.parts], shape.dims)
    return Shape(shape.kind, [[fn(p) for p in part] for part in shape.parts], shape.dims, shape.exterior)


def area(ring: list[tuple]) -> float:
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:] + ring[:1])) / 2


def oriented(shape: Shape) -> Shape:
    """Exterior en sentido horario y huecos en antihorario, como exige el formato."""
    out = []
    exterior = shape.exterior or [True] * len(shape.parts)
    for ring, is_exterior in zip(shape.parts, exterior):
        clockwise = area(ring) < 0
        out.append(ring if clockwise == is_exterior else ring[::-1])
    return Shape(shape.kind, out, shape.dims, shape.exterior)


def vertices_of(shape: Shape) -> list[tuple]:
    if shape.kind == "POINT":
        return [tuple(shape.parts)]
    if shape.kind == "MULTIPOINT":
        return [tuple(p) for p in shape.parts]
    return [tuple(p) for part in shape.parts for p in part]


def z_index(dims: str) -> int | None:
    return 2 if "Z" in dims else None


def m_index(dims: str) -> int | None:
    return 3 if dims == "XYZM" else 2 if dims == "XYM" else None


# ---------- el .shp y el .shx ----------


def record_content(layer: Layer, shape: Shape | None) -> bytes:
    if shape is None:
        return struct.pack("<i", 0)
    stype = layer.type_code
    zi, mi = z_index(layer.dims), m_index(layer.dims)
    verts = vertices_of(shape)
    xs, ys = [p[0] for p in verts], [p[1] for p in verts]
    box = struct.pack("<4d", min(xs), min(ys), max(xs), max(ys))

    def pts(items: list[tuple]) -> bytes:
        return b"".join(struct.pack("<2d", p[0], p[1]) for p in items)

    def extra(items: list[tuple], index: int | None) -> bytes:
        values = [p[index] if index is not None else 0.0 for p in items]
        return struct.pack("<2d", min(values), max(values)) + b"".join(struct.pack("<d", v) for v in values)

    out = struct.pack("<i", stype)
    if layer.kind == "POINT" and stype < 31:
        p = verts[0]
        out += struct.pack("<2d", p[0], p[1])
        if zi is not None:
            out += struct.pack("<d", p[zi])
        if mi is not None:
            out += struct.pack("<d", p[mi])
        return out
    if stype == 31:  # MultiPatch: una parte de tipo anillo exterior
        part = shape.parts[0]
        out += box + struct.pack("<2i", 1, len(part)) + struct.pack("<i", 0) + struct.pack("<i", 5) + pts(part)
        return out + extra(part, 2)
    if layer.kind == "MULTIPOINT":
        out += box + struct.pack("<i", len(verts)) + pts(verts)
    else:
        starts, total = [], 0
        for part in shape.parts:
            starts.append(total)
            total += len(part)
        out += box + struct.pack("<2i", len(shape.parts), total) + b"".join(struct.pack("<i", s) for s in starts) + pts(verts)
    if zi is not None:
        out += extra(verts, zi)
    if mi is not None:
        out += extra(verts, mi)
    return out


def header(layer: Layer, words: int, shapes: list[Shape | None]) -> bytes:
    real = [s for s in shapes if s is not None]
    verts = [p for s in real for p in vertices_of(s)]
    zi, mi = z_index(layer.dims), m_index(layer.dims)
    if verts:
        xs, ys = [p[0] for p in verts], [p[1] for p in verts]
        box = (min(xs), min(ys), max(xs), max(ys))
    else:
        box = (0.0, 0.0, 0.0, 0.0)
    zs = [p[2] for p in verts] if layer.type_code == 31 else ([p[zi] for p in verts] if zi is not None else [])
    ms = [p[mi] for p in verts] if mi is not None else []
    z0, z1 = (min(zs), max(zs)) if zs else (0.0, 0.0)
    m0, m1 = (min(ms), max(ms)) if ms else (0.0, 0.0)
    return struct.pack(">i5ii", 9994, 0, 0, 0, 0, 0, words) + struct.pack("<ii", 1000, layer.type_code) + struct.pack("<8d", *box, z0, z1, m0, m1)


def build_shp(layer: Layer, shapes: list[Shape | None]) -> tuple[bytes, bytes]:
    contents = [record_content(layer, s) for s in shapes]
    body, index, offset = b"", b"", 50
    for number, content in enumerate(contents, 1):
        words = len(content) // 2
        body += struct.pack(">ii", number, words) + content
        index += struct.pack(">ii", offset, words)
        offset += 4 + words
    shp = header(layer, offset, shapes) + body
    shx = header(layer, 50 + 4 * len(contents), shapes) + index
    return shp, shx


def broken(shx: bytes) -> bytes:
    """Un .shx que dice otra cosa que el .shp: las posiciones corridas dos palabras y un registro de menos."""
    head, rows = bytearray(shx[:100]), shx[100:]
    out = bytearray()
    for i in range(0, len(rows) - 8, 8):
        offset, words = struct.unpack(">ii", rows[i : i + 8])
        out += struct.pack(">ii", offset + 2, words)
    struct.pack_into(">i", head, 24, 50 + len(out) // 2)
    return bytes(head) + bytes(out)


# ---------- el .dbf ----------


def encode_value(field_: Field, value, encoding: str) -> bytes:
    if field_.kind == "C":
        raw = ("" if value is None else str(value)).encode(encoding)
        return raw[: field_.length].ljust(field_.length, b" ")
    if field_.kind in ("N", "F"):
        if value is None:
            return b" " * field_.length
        text = f"{value:.{field_.decimals}f}" if field_.decimals else str(int(value))
        return text.rjust(field_.length).encode("ascii")
    if field_.kind == "D":
        return b" " * 8 if value is None else value.replace("-", "").encode("ascii")
    if field_.kind == "L":
        return b"?" if value is None else (b"T" if value else b"F")
    raise ValueError(field_.kind)


def build_dbf(layer: Layer, feats: list[Feat]) -> bytes:
    fields = layer.fields
    rows = feats
    if layer.dbf == "corto":
        rows = feats[: max(1, len(feats) - 2)]
    elif layer.dbf == "largo":
        rows = feats + [Feat(None, {"nombre": "Sobrante"}), Feat(None, {"nombre": "Sobrante 2"})]
    record_length = 1 + sum(f.length for f in fields)
    header_length = 32 + 32 * len(fields) + 1
    out = struct.pack("<BBBBIHH", 3, 126, 10, 27, len(rows), header_length, record_length) + bytes(17) + bytes([layer.ldid]) + bytes(2)
    for f in fields:
        out += f.name.encode("ascii").ljust(11, b"\0") + f.kind.encode("ascii") + bytes(4) + bytes([f.length, f.decimals]) + bytes(14)
    out += b"\r"
    for feat in rows:
        out += b"*" if feat.deleted else b" "
        for f in fields:
            out += encode_value(f, feat.attrs.get(f.name), layer.encoding)
    return out + b"\x1a"


# ---------- los ficheros de una capa ----------


def layer_files(layer: Layer) -> dict[str, bytes]:
    shapes = [written(layer, f.shape) for f in layer.feats]
    if layer.kind == "POLYGON":
        shapes = [None if s is None else oriented(s) for s in shapes]
    shp, shx = build_shp(layer, shapes)
    stem = layer.name if layer.case == "minusculas" else layer.name.upper()
    ext = (lambda e: e) if layer.case == "minusculas" else (lambda e: e.capitalize() if e in ("dbf", "cpg") else e.upper())
    files = {f"{stem}.{layer.extension if layer.case == 'minusculas' else ext(layer.extension)}": shp}
    if layer.shx == "bien":
        files[f"{stem}.{ext('shx')}"] = shx
    elif layer.shx == "roto":
        files[f"{stem}.{ext('shx')}"] = broken(shx)
    if layer.dbf != "falta":
        files[f"{stem}.{ext('dbf')}"] = build_dbf(layer, layer.feats)
    if layer.prj == "ilegible":
        files[f"{stem}.{ext('prj')}"] = b"Esto no es un WKT\r\n"
    elif layer.prj is not None:
        files[f"{stem}.{ext('prj')}"] = crs_wkt(layer.srs, layer.prj).encode("ascii")
    if layer.cpg is not None:
        files[f"{stem}.{ext('cpg')}"] = layer.cpg.encode("ascii")
    return files


def polygon(*rings_spec: tuple[list[tuple], bool], dims: str = "XY") -> Shape:
    """Un polígono: cada anillo con si es exterior (True) o hueco (False). La orientación se arregla al escribir."""
    return Shape("POLYGON", [r for r, _ in rings_spec], dims, [e for _, e in rings_spec])


# ---------- escenarios ----------


def pt(e: float, n: float, z: float | None = None, m: float | None = None) -> tuple:
    return g.ll(e, n, z, m)


def ring(e: float, n: float, size: float) -> list[tuple]:
    return [pt(e, n), pt(e, n + size), pt(e + size, n + size), pt(e + size, n), pt(e, n)]


def named(*names: str | None) -> list[dict]:
    return [{"nombre": n} for n in names]


def simple(name: str, srs: int, prj: str | None = "esri", cpg: str | None = "UTF-8", kind: str = "POINT") -> Layer:
    """Tres elementos del tipo dado, para los ficheros que prueban un sistema de coordenadas."""
    if kind == "POINT":
        shapes = [Shape("POINT", pt(100, 100)), Shape("POINT", pt(220, 160)), Shape("POINT", pt(340, 90))]
    else:
        shapes = [Shape("POLYLINE", [[pt(0, 0), pt(100, 40), pt(200, 0)]]), Shape("POLYLINE", [[pt(0, 120), pt(150, 180)]]),
                  Shape("POLYLINE", [[pt(30, 250), pt(120, 300), pt(220, 260)]])]
    return Layer(name, kind, srs, [Feat(s, a) for s, a in zip(shapes, named("Uno", "Dos", "Tres"))], prj=prj, cpg=cpg)


def puntos() -> Layer:
    fields = [Field("nombre", "C", 30), Field("cota", "N", 12, 3), Field("codigo", "N", 5), Field("fecha", "D", 8), Field("activo", "L", 1)]
    rows = [
        (pt(100, 100), {"nombre": "Esquina norte", "cota": 646.377, "codigo": 1, "fecha": "2026-10-01", "activo": True}),
        (pt(150, 120), {"nombre": "Árbol ñandú", "cota": 647.125, "codigo": 2, "fecha": "2026-10-02", "activo": False}),
        (pt(200, 140), {"nombre": None, "cota": 648.0, "codigo": 3}),
        (pt(250, 160), {"nombre": "", "cota": None, "codigo": 4}),
        (pt(300, 180), {"nombre": "Farola", "cota": 649.5, "codigo": 5}),
    ]
    return Layer("vertices", "POINT", 25830, [Feat(Shape("POINT", p), a) for p, a in rows], fields)


def lineas() -> Layer:
    fields = [Field("nombre", "C", 30), Field("ancho", "N", 8, 2)]
    rows = [
        ([[pt(0, 0), pt(100, 50), pt(200, 0)]], {"nombre": "Calle Mayor", "ancho": 12.5}),
        ([[pt(0, 200), pt(300, 250)]], {"nombre": "Calle Alta", "ancho": 8.0}),
        ([[pt(50, 300), pt(120, 320), pt(200, 380), pt(260, 300)], [pt(300, 300), pt(340, 330)]], {"nombre": "Camino", "ancho": None}),
    ]
    return Layer("ejes", "POLYLINE", 4258, [Feat(Shape("POLYLINE", p), a) for p, a in rows], fields)


def poligonos() -> list[Layer]:
    parcelas = [
        Feat(polygon((ring(0, 0, 100), True)), {"nombre": "Parcela 1"}),
        Feat(polygon((ring(150, 0, 200), True), (ring(170, 20, 40), False), (ring(250, 100, 60), False)), {"nombre": "Parcela 2"}),
    ]
    manzanas = [
        Feat(polygon((ring(0, 400, 100), True), (ring(150, 400, 150), True), (ring(175, 425, 50), False)), {"nombre": "Manzana A"}),
        Feat(polygon((ring(400, 400, 80), True)), {"nombre": "Manzana B"}),
    ]
    return [Layer("parcelas", "POLYGON", 25830, parcelas), Layer("manzanas", "POLYGON", 25830, manzanas)]


def multipuntos() -> Layer:
    feats = [Feat(Shape("MULTIPOINT", [pt(50, 50), pt(90, 70), pt(130, 40)]), {"nombre": "Grupo"}),
             Feat(Shape("MULTIPOINT", [pt(300, 300)]), {"nombre": "Uno solo"})]
    return Layer("multipuntos", "MULTIPOINT", 25830, feats)


def altura() -> list[Layer]:
    def zline(dims: str, base: float) -> Shape:
        def v(i: int, e: float, n: float) -> tuple:
            return pt(e, n, *({"XYZ": (base + i, None), "XYM": (None, 7.5 + i), "XYZM": (base + i, 7.5 + i)}[dims]))

        return Shape("POLYLINE", [[v(i, e, n) for i, (e, n) in enumerate(((0, 0), (80, 40), (160, 0)))]], dims)

    def zpoly(dims: str) -> Shape:
        def with_extra(p: tuple, i: int) -> tuple:
            return p[:2] + {"XYZ": (110.0 + i,), "XYM": (7.5 + i,), "XYZM": (110.0 + i, 7.5 + i)}[dims]

        s = polygon((ring(300, 0, 100), True), (ring(320, 20, 30), False), dims=dims)
        return Shape("POLYGON", [[with_extra(p, i) for i, p in enumerate(r)] for r in s.parts], dims, s.exterior)

    def point(dims: str) -> Shape:
        return Shape("POINT", pt(10, 10, *({"XYZ": (646.377, None), "XYM": (None, 3.0), "XYZM": (646.377, 3.0)}[dims])), dims)

    def multi(dims: str) -> Shape:
        extra = {"XYZ": lambda i: (100.0 + i, None), "XYM": lambda i: (None, 1.5 + i), "XYZM": lambda i: (100.0 + i, 1.5 + i)}[dims]
        return Shape("MULTIPOINT", [pt(e, n, *extra(i)) for i, (e, n) in enumerate(((0, 0), (40, 40), (80, 0)))], dims)

    def one(name: str, kind: str, dims: str, make) -> Layer:
        return Layer(name, kind, 25830, [Feat(make(dims), {"nombre": "A"})], dims=dims)

    layers = [
        Layer("puntos_z", "POINT", 25830, [Feat(point("XYZ"), {"nombre": "A"}), Feat(Shape("POINT", pt(60, 20, 0.0), "XYZ"), {"nombre": "B"})], dims="XYZ"),
        one("puntos_m", "POINT", "XYM", point),
        one("puntos_zm", "POINT", "XYZM", point),
        one("lineas_z", "POLYLINE", "XYZ", lambda d: zline(d, 100.0)),
        one("lineas_m", "POLYLINE", "XYM", lambda d: zline(d, 100.0)),
        one("lineas_zm", "POLYLINE", "XYZM", lambda d: zline(d, 100.0)),
        one("poligonos_z", "POLYGON", "XYZ", zpoly),
        one("poligonos_m", "POLYGON", "XYM", zpoly),
        one("multipuntos_z", "MULTIPOINT", "XYZ", multi),
        one("multipuntos_m", "MULTIPOINT", "XYM", multi),
    ]
    return layers


def sistemas() -> list[Layer]:
    canarias = [Feat(Shape("POINT", (-15.4 + 0.001 * i, 28.1 + 0.001 * i)), {"nombre": f"Gran Canaria {i}"}) for i in range(3)]
    return [
        simple("utm29", 25829, "esri"),
        simple("utm31", 25831, "gdal"),
        simple("ed50_utm30", 23030, "gdal-sin-autoridad"),
        Layer("canarias_utm28", "POINT", 4083, canarias, prj="esri"),
        simple("geograficas_4258", 4258, "esri"),
        simple("wgs84_4326", 4326, "gdal-sin-autoridad"),
        simple("utm33", 25833, "esri"),
        simple("utm28_etrs89", 25828, "gdal"),
        simple("pies_2263", 2263, "esri"),
        simple("sin_prj", 25830, None),
        simple("prj_ilegible", 25830, "ilegible"),
        simple("sin_cpg", 25830, "esri", None),
    ]


def ambito() -> list[Layer]:
    feats = [Feat(Shape("POINT", (-3.7, 40.4)), {"nombre": "Madrid"}), Feat(Shape("POINT", (10.0, 45.0)), {"nombre": "Lejos"}),
             Feat(Shape("POINT", (-3.71, 40.41)), {"nombre": "Madrid 2"})]
    return [Layer("fuera", "POINT", 4258, feats)]


def codificaciones() -> list[Layer]:
    texts = ["Árbol ñandú", "Camión", "Plaza — Mayor", "Sin tildes"]

    def layer(name: str, encoding: str, cpg: str | None, text: list[str] = texts) -> Layer:
        feats = [Feat(Shape("POINT", pt(20 * i, 20 * i)), {"nombre": t}) for i, t in enumerate(text, 1)]
        return Layer(name, "POINT", 25830, feats, encoding=encoding, cpg=cpg)

    latin = ["Árbol ñandú", "Camión", "Plaza Mayor", "Sin tildes"]
    return [
        layer("utf8_cpg", "utf-8", "UTF-8"),
        layer("utf8_cpg_65001", "utf-8", "65001"),
        layer("w1252_cpg", "cp1252", "1252", latin),
        layer("w1252_cpg_ansi", "cp1252", "ANSI 1252", latin),
        layer("w1252_cpg_windows", "cp1252", "windows-1252", latin),
        layer("w1252_sin_cpg", "cp1252", None, latin),
        layer("utf8_sin_cpg", "utf-8", None),
        layer("ascii_sin_cpg", "utf-8", None, ["Uno", "Dos", "Tres", "Cuatro"]),
        layer("cpg_desconocido", "cp1252", "ISO-8859-5", latin),
    ]


def incompletos() -> list[Layer]:
    base = puntos()

    def variant(name: str, **kw) -> Layer:
        return Layer(name, base.kind, base.srs, base.feats, base.fields, **kw)

    return [variant("sin_shx", shx="falta"), variant("sin_dbf", dbf="falta"), variant("dbf_corto", dbf="corto"),
            variant("dbf_largo", dbf="largo"), variant("shx_roto", shx="roto"), variant("sin_shx_ni_dbf", shx="falta", dbf="falta")]


def borrados_y_nulos() -> list[Layer]:
    base = puntos()
    deleted = [Feat(f.shape, f.attrs, deleted=i in (1, 3)) for i, f in enumerate(base.feats)]
    nulls = [
        Feat(Shape("POLYLINE", [[pt(0, 0), pt(100, 50)]]), {"nombre": "Uno"}),
        Feat(None, {"nombre": "Nulo"}),
        Feat(Shape("POLYLINE", [[pt(0, 100), pt(100, 150)]]), {"nombre": "Tres"}),
        Feat(None, {"nombre": "Nulo 2"}),
    ]
    patch = [Feat(Shape("MULTIPATCH", [[pt(0, 0, 100.0), pt(0, 50, 101.0), pt(50, 50, 102.0), pt(0, 0, 100.0)]], "XYZ"), {"nombre": "Parche"}),
             Feat(Shape("MULTIPATCH", [[pt(100, 0, 110.0), pt(100, 50, 111.0), pt(150, 50, 112.0), pt(100, 0, 110.0)]], "XYZ"), {"nombre": "Parche 2"})]
    return [
        Layer("borrados", "POINT", 25830, deleted, base.fields),
        Layer("nulos", "POLYLINE", 25830, nulls),
        Layer("multipatch", "POLYGON", 25830, patch, dims="XYZ", shape_type=31),
    ]


def scenarios() -> dict[str, list[Layer]]:
    return {
        "puntos-25830": [puntos()],
        "lineas-4258": [lineas()],
        "poligonos-25830": poligonos(),
        "multipuntos-25830": [multipuntos()],
        "altura-25830": altura(),
        "sistemas": sistemas(),
        "ambito": ambito(),
        "codificaciones": codificaciones(),
        "incompletos": incompletos(),
        "borrados-y-nulos": borrados_y_nulos(),
    }


def name_scenarios() -> dict[str, list[Layer]]:
    """Cada uno en su carpeta porque se prueba con los ficheros de la carpeta como una selección."""
    base = puntos()
    return {
        "mayusculas": [Layer("PARCELAS", "POINT", 25830, base.feats, base.fields, case="mixto")],
        "otra-extension": [Layer("vertices", "POINT", 25830, base.feats, base.fields, extension="dat")],
    }


# ---------- lo esperado ----------


def reread(layer: Layer, feat: Feat) -> Shape | None:
    """La forma tal como queda en el .shp: en las coordenadas de la capa y con los anillos orientados."""
    shape = written(layer, feat.shape)
    return oriented(shape) if shape is not None and layer.kind == "POLYGON" else shape


def vertex_text(p: tuple, dims: str) -> str:
    zi = z_index(dims)
    return f"{g.num(p[0])} {g.num(p[1])} {'_' if zi is None else g.num(p[zi])}"


def elements_of(shape: Shape):
    """(capa de diseño, anillo, contorno, vértices) de cada elemento que sale de una forma (RF-15)."""
    if shape.kind == "POINT":
        yield "POINTS", None, False, [tuple(shape.parts)]
    elif shape.kind == "MULTIPOINT":
        for p in shape.parts:
            yield "POINTS", None, False, [tuple(p)]
    elif shape.kind == "POLYLINE":
        for part in shape.parts:
            yield "LINES", None, False, [tuple(p) for p in part]
    else:
        for i, part in enumerate(shape.parts):
            yield "LINES", i, True, [tuple(p) for p in part]


def describe(folder: str, layer: Layer, out: list[list]) -> None:
    if layer.prj is None or layer.prj == "ilegible" or not srs_available(layer.srs):
        return  # la capa no se importa hasta que el usuario elija un sistema (RF-9)
    for number, feat in enumerate(layer.feats, 1):
        key = [folder, layer.name, number]
        if feat.deleted:
            out.append(key + ["", "", "", "", "", "", "ignorado_borrado"])
            continue
        if feat.shape is None:
            out.append(key + ["", "", "", "", "", "", "ignorado_vacia"])
            continue
        if layer.type_code == 31:
            out.append(key + ["", "", "", "", "", "", "ignorado_multipatch"])
            continue
        shape = reread(layer, feat)
        elements = list(elements_of(shape))
        converted = [[g.project_of(layer.srs, p[0], p[1]) for p in verts] for *_, verts in elements]
        if any(c is None for part in converted for c in part):
            out.append(key + ["", "", "", "", "", "", "fuera_de_ambito"])
            continue
        for index, ((kind, ring_, contour, verts), proj) in enumerate(zip(elements, converted), 1):
            source = ";".join(vertex_text(p, shape.dims) for p in verts)
            zi = z_index(shape.dims)
            project = ";".join(f"{g.num(c[0])} {g.num(c[1])} {'_' if zi is None else g.num(p[zi])}" for c, p in zip(proj, verts))
            out.append(key + [kind, index, "" if ring_ is None else ring_, "SI" if contour else "NO", source, project, "OK"])


def detected_encoding(layer: Layer) -> tuple[str, str]:
    """(codificación con la que se lee el .dbf, si el .cpg es de una que no se conoce)."""
    if layer.cpg is not None:
        text = layer.cpg.strip().upper()
        if text in ("UTF-8", "UTF8", "65001"):
            return "UTF-8", "NO"
        if text in ("1252", "ANSI 1252", "WINDOWS-1252", "CP1252"):
            return "WINDOWS-1252", "NO"
        return "WINDOWS-1252", "SI"
    texts = [str(v) for f in layer.feats for k, v in f.attrs.items() if isinstance(v, str)]
    raw = "".join(texts).encode(layer.encoding)
    try:
        raw.decode("utf-8")
        return "UTF-8", "NO"
    except UnicodeDecodeError:
        return "WINDOWS-1252", "NO"


def layer_row(folder: str, layer: Layer) -> list:
    if layer.prj is None:
        system, unit = "SIN_SISTEMA", ""
    elif layer.prj == "ilegible":
        system, unit = "SIN_SISTEMA", ""
    else:
        system, unit = sistema_app(layer.srs), unit_of(layer.srs)
    encoding, unknown = detected_encoding(layer) if layer.dbf != "falta" else ("", "")
    records_dbf = "" if layer.dbf == "falta" else {"bien": len(layer.feats), "corto": max(1, len(layer.feats) - 2), "largo": len(layer.feats) + 2}[layer.dbf]
    columns = "" if layer.dbf == "falta" else ";".join(f"{f.name}:{f.declared}" for f in layer.fields)
    return [folder, layer.name, TYPE_NAME[layer.kind], layer.type_code, len(layer.feats), records_dbf, system, unit, encoding, unknown, columns,
            "SI" if layer.shx == "bien" else ("NO" if layer.shx == "falta" else "ROTO"), "NO" if layer.prj is None else "SI",
            "NO" if layer.cpg is None else "SI"]


def parse_value(f: Field, value) -> str:
    if value is None:
        return ""
    if f.kind == "C":
        return str(value).rstrip(" ")
    if f.kind in ("N", "F"):
        return g.num(round(float(value), f.decimals)) if f.decimals else str(int(value))
    if f.kind == "D":
        return str(value)
    return "true" if value else "false"


def attribute_rows(folder: str, layer: Layer) -> list[list]:
    if layer.dbf == "falta":
        return []
    rows = []
    feats = layer.feats
    if layer.dbf == "corto":
        feats = feats[: max(1, len(feats) - 2)]
    for number, feat in enumerate(feats, 1):
        if feat.deleted:
            continue  # un objeto borrado no se importa: su ficha no existe
        for f in layer.fields:
            rows.append([folder, layer.name, number, f.name, f.declared, parse_value(f, feat.attrs.get(f.name))])
    return rows


def prj_files() -> dict[str, str]:
    files = {}
    for epsg in [row[0] for row in g.CRS_ROWS] + [2263]:
        for style in ("esri", "gdal", "gdal-sin-autoridad"):
            files[f"prj/{style}-{epsg}.prj"] = crs_wkt(epsg, style)
    return files


def prj_rows() -> list[list]:
    rows = []
    for epsg in [row[0] for row in g.CRS_ROWS] + [2263]:
        for style in ("esri", "gdal", "gdal-sin-autoridad"):
            wkt = crs_wkt(epsg, style)
            state = "DISPONIBLE" if srs_available(epsg) else "NO_DISPONIBLE"
            if epsg == 4326:
                state = "WGS84"
            system = sistema_app(epsg) if srs_available(epsg) else ""
            rows.append([f"prj/{style}-{epsg}.prj", epsg, style, system, state, unit_of(epsg), wkt_name(wkt)])
    return rows


def banner(what: str) -> list[str]:
    return [
        f"# {what} (S030). Generado por tools/formatos/generar_shapefile.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla=es_ign_SPED2ETV2.tif",
    ]


def csv_text(rows: list[list]) -> list[str]:
    return [",".join(str(x) for x in row) for row in rows]


def generate() -> dict[str, bytes | str]:
    files: dict[str, bytes | str] = {}
    elements, layer_rows, attributes = [], [], []
    only_files = name_scenarios()
    for folder, layers in {**scenarios(), **only_files}.items():
        for layer in layers:
            for name, data in layer_files(layer).items():
                files[f"{folder}/{name}"] = data
            if folder in only_files:
                continue  # los nombres se prueban como selección, no se describen capa a capa
            describe(folder, layer, elements)
            layer_rows.append(layer_row(folder, layer))
            attributes.extend(attribute_rows(folder, layer))
    for name, text in prj_files().items():
        files[name] = text.encode("ascii")

    files["esperado.csv"] = "\n".join(banner("Elementos esperados de cada objeto") + [
        "# vertices_fuente: 'x y z' en el sistema de la capa; vertices_proyecto: 'e n z' en ETRS89 UTM 30; _ si no hay altura.",
        "# anillo: la posición del anillo dentro del polígono. Solo hay filas de las capas con sistema disponible.",
        "escenario,capa,registro,capa_diseno,elemento,anillo,contorno,vertices_fuente,vertices_proyecto,estado",
    ] + csv_text(elements)) + "\n"
    files["esperado-capas.csv"] = "\n".join(banner("Capas esperadas de cada escenario") + [
        "escenario,capa,tipo_geometria,tipo_shp,objetos,registros_dbf,sistema_app,unidad,codificacion,cpg_desconocido,columnas,shx,prj,cpg",
    ] + csv_text(layer_rows)) + "\n"
    files["esperado-atributos.csv"] = "\n".join(banner("Valores del .dbf que se ven en la ficha") + [
        "escenario,capa,registro,columna,tipo,valor",
    ] + csv_text(attributes)) + "\n"
    files["esperado-prj.csv"] = "\n".join(banner("Sistema de la app para cada .prj de prj/") + [
        "archivo,epsg,estilo,sistema_app,estado,unidad,nombre_en_el_prj",
    ] + csv_text(prj_rows())) + "\n"
    return files


def main() -> int:
    for name, content in generate().items():
        path = OUT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
    print(f"{len(generate())} ficheros en {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
