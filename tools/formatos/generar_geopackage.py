"""Ficheros GeoPackage de ejemplo de la importación de capas y lo que debe salir de cada uno (S048).

Genera en fixtures/formatos/gpkg/ los `.gpkg` (con sqlite3 de la biblioteca estándar, sin librerías de GeoPackage), otros ficheros que no
lo son (texto, vacío, SQLite común, dañado) y cuatro ficheros de lo esperado:

- esperado.csv: una fila por elemento que debe salir de cada objeto (polígonos ya separados en contorno y huecos): tabla, fid, capa de diseño
  (POINTS o LINES), número de elemento, anillo, si es contorno de polígono, vértices en el sistema de la capa ("x y z", con `_` si falta la
  altura) y en ETRS89 UTM 30 (el SC del proyecto), y el estado del objeto (OK, ignorado_vacia, ignorado_curva o fuera_de_ambito).
- esperado-capas.csv: una fila por capa con geometría: tipo declarado, SRS, objetos, sistema de la app, unidad, si tiene R-tree, si las
  geometrías traen envolvente, columnas.
- esperado-resumen.csv: clave/valor por fichero: tipo de fichero, capas con geometría y tablas que se dejan fuera.
- esperado-crs.csv: EPSG → sistema de la app (S008), unidad y estado, comprobado contra pyproj en el test.

Reglas de este conjunto que la spec no fija y que quedan escritas en lo esperado (se confirman al hacer cada tarea):
- Las coordenadas de proyecto se calculan como las calcula la app: del sistema de la capa al datum base con `ManualCrs` de
  tools/geodesia/generar_vectores.py (pyproj, con la rejilla SPED2ETV2 en ED50) y de ahí a ETRS89 UTM 30.
- EPSG:4326 se trata como ETRS89 (decisión D3 del plan); un objeto con un vértice fuera del ámbito del proyecto se descarta entero.
- Una geometría con el indicador de vacía, un punto con NaN, una línea sin vértices o una geometría NULL son «vacía»; CIRCULARSTRING,
  COMPOUNDCURVE y CURVEPOLYGON son «curva».
- M se ignora; Z se conserva sin convertir.
- Las coordenadas se escriben a su precisión completa (repr de Python).

Los `.gpkg` no son idénticos byte a byte entre versiones de SQLite (la cabecera guarda la versión): el test compara su contenido.

Uso: uv run --project tools tools/formatos/generar_geopackage.py
"""

from __future__ import annotations

import math
import sqlite3
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path

import pyproj
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores as v  # noqa: E402

OUT = ROOT / "fixtures" / "formatos" / "gpkg"
E0, N0 = 440000.0, 4474000.0  # esquina de los dibujos, en ETRS89 UTM 30 (Madrid)
APPLICATION_ID = 0x47504B47  # "GPKG"
USER_VERSION = 10300  # GeoPackage 1.3.0
LAST_CHANGE = "2026-10-27T00:00:00.000Z"
PAGE = 4096

TYPE = {"POINT": 1, "LINESTRING": 2, "POLYGON": 3, "MULTIPOINT": 4, "MULTILINESTRING": 5, "MULTIPOLYGON": 6, "GEOMETRYCOLLECTION": 7,
        "CIRCULARSTRING": 8, "COMPOUNDCURVE": 9, "CURVEPOLYGON": 10}
DIM = {"XY": 0, "XYZ": 1000, "XYM": 2000, "XYZM": 3000}
CURVES = {"CIRCULARSTRING", "COMPOUNDCURVE", "CURVEPOLYGON"}

PROJECT = v.ManualCrs("ETRS89", 30, "EPSG:4258", "EPSG:25830")
MANUAL = {
    25829: v.ManualCrs("ETRS89", 29, "EPSG:4258", "EPSG:25829"),
    25830: PROJECT,
    25831: v.ManualCrs("ETRS89", 31, "EPSG:4258", "EPSG:25831"),
    4083: v.ManualCrs("REGCAN95", 28, "EPSG:4081", "EPSG:4083"),
    23030: v.ManualCrs("ED50", 30, None, None),
}
TO_GEOGRAPHIC = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)
TO_3857 = Transformer.from_crs("EPSG:4258", "EPSG:3857", always_xy=True)
TO_25833 = Transformer.from_crs("EPSG:4258", "EPSG:25833", always_xy=True)

# (EPSG, sistema de la app, estado, tipo, datum, huso). Estados: DISPONIBLE, WGS84 (se trata como ETRS89), NO_DISPONIBLE.
CRS_ROWS = (
    (25829, "ETRS89-UTM29", "DISPONIBLE", "UTM", "ETRS89", 29),
    (25830, "ETRS89-UTM30", "DISPONIBLE", "UTM", "ETRS89", 30),
    (25831, "ETRS89-UTM31", "DISPONIBLE", "UTM", "ETRS89", 31),
    (4258, "ETRS89-GEOGRAFICAS", "DISPONIBLE", "GEOGRAFICAS", "ETRS89", None),
    (4082, "REGCAN95-UTM27", "DISPONIBLE", "UTM", "REGCAN95", 27),
    (4083, "REGCAN95-UTM28", "DISPONIBLE", "UTM", "REGCAN95", 28),
    (4081, "REGCAN95-GEOGRAFICAS", "DISPONIBLE", "GEOGRAFICAS", "REGCAN95", None),
    (23029, "ED50-UTM29", "DISPONIBLE", "UTM", "ED50", 29),
    (23030, "ED50-UTM30", "DISPONIBLE", "UTM", "ED50", 30),
    (23031, "ED50-UTM31", "DISPONIBLE", "UTM", "ED50", 31),
    (4326, "ETRS89-GEOGRAFICAS", "WGS84", "GEOGRAFICAS", "WGS84", None),
    (3857, "", "NO_DISPONIBLE", "UTM", "", None),
    (25833, "", "NO_DISPONIBLE", "UTM", "", None),
    (25828, "", "NO_DISPONIBLE", "UTM", "", None),
    (23028, "", "NO_DISPONIBLE", "UTM", "", None),
    (4230, "", "NO_DISPONIBLE", "GEOGRAFICAS", "", None),
    (32630, "", "NO_DISPONIBLE", "UTM", "", None),
    (2062, "", "NO_DISPONIBLE", "UTM", "", None),
)
CRS_BY_EPSG = {row[0]: row for row in CRS_ROWS}


def num(x: float | None) -> str:
    return "" if x is None else repr(float(x))


# ---------- geometrías ----------


@dataclass
class G:
    """Una geometría en coordenadas base (lon, lat, z, m). data: tupla (punto), lista de tuplas, lista de anillos o lista de G."""

    kind: str
    data: object = None
    dims: str = "XY"
    big: bool = False
    ewkb: bool = False
    srid: int | None = None
    empty: bool = False  # indicador de vacía en la cabecera del blob


def sub(parent: G, kind: str, data: object) -> G:
    return G(kind, data, parent.dims, parent.big, parent.ewkb)


def vertices(g: G) -> list[tuple]:
    if g.kind == "POINT":
        return [] if g.data is None else [tuple(g.data)]
    if g.kind in ("LINESTRING", "CIRCULARSTRING"):
        return [tuple(p) for p in g.data]
    if g.kind == "POLYGON":
        return [tuple(p) for ring in g.data for p in ring]
    return [p for child in g.data for p in vertices(child)]


def remap(g: G, fn) -> G:
    """Los mismos datos con cada vértice pasado por fn."""
    if g.kind == "POINT":
        data = None if g.data is None else fn(tuple(g.data))
    elif g.kind in ("LINESTRING", "CIRCULARSTRING"):
        data = [fn(tuple(p)) for p in g.data]
    elif g.kind == "POLYGON":
        data = [[fn(tuple(p)) for p in ring] for ring in g.data]
    else:
        data = [remap(c, fn) for c in g.data]
    return G(g.kind, data, g.dims, g.big, g.ewkb, g.srid, g.empty)


def wkb(g: G) -> bytes:
    e = ">" if g.big else "<"
    code = TYPE[g.kind]
    if g.ewkb:
        code |= (0x80000000 if "Z" in g.dims else 0) | (0x40000000 if "M" in g.dims else 0) | (0x20000000 if g.srid is not None else 0)
    else:
        code += DIM[g.dims]
    out = (b"\x00" if g.big else b"\x01") + struct.pack(e + "I", code)
    if g.ewkb and g.srid is not None:
        out += struct.pack(e + "I", g.srid)
    n = len(g.dims)

    def pts(items: list) -> bytes:
        return struct.pack(e + "I", len(items)) + b"".join(struct.pack(e + "d" * n, *p) for p in items)

    if g.kind == "POINT":
        out += struct.pack(e + "d" * n, *(g.data if g.data is not None else (math.nan,) * n))
    elif g.kind in ("LINESTRING", "CIRCULARSTRING"):
        out += pts(g.data)
    elif g.kind == "POLYGON":
        out += struct.pack(e + "I", len(g.data)) + b"".join(pts(ring) for ring in g.data)
    else:
        out += struct.pack(e + "I", len(g.data)) + b"".join(wkb(c) for c in g.data)
    return out


def gpkg_blob(g: G | None, srs: int, env: int, big_header: bool) -> bytes | None:
    """Cabecera del blob (GP, versión, indicadores, SRS, envolvente) y WKB. env: 0 sin envolvente, 1 XY, 2 XYZ."""
    if g is None:
        return None
    verts = vertices(g)
    empty = g.empty or not verts
    indicator = 0 if empty or env == 0 else env
    e = ">" if big_header else "<"
    out = b"GP" + bytes([0, (0 if big_header else 1) | (indicator << 1) | (16 if empty else 0)]) + struct.pack(e + "i", srs)
    if indicator:
        xs, ys = [p[0] for p in verts], [p[1] for p in verts]
        out += struct.pack(e + "4d", min(xs), max(xs), min(ys), max(ys))
        if indicator == 2:
            zs = [p[2] for p in verts]
            out += struct.pack(e + "2d", min(zs), max(zs))
    return out + wkb(g)


# ---------- capas ----------


@dataclass
class Feat:
    geom: G | None
    props: dict = field(default_factory=dict)


@dataclass
class Layer:
    table: str
    gtype: str  # tipo declarado en gpkg_geometry_columns
    srs: int
    feats: list[Feat]
    cols: list[tuple[str, str]] = field(default_factory=lambda: [("nombre", "TEXT")])
    rtree: bool = True
    z: int = 0  # 0 prohibida, 1 obligatoria, 2 opcional
    env: int = 1
    big_header: bool = False


def ll(e: float, n: float, z: float | None = None, m: float | None = None) -> tuple:
    """Un vértice a (e, n) metros de la esquina del dibujo, en lon/lat (el origen común de todas las capas)."""
    lon, lat = TO_GEOGRAPHIC.transform(E0 + e, N0 + n)
    return (lon, lat) + tuple(x for x in (z, m) if x is not None)


def layer_xy(srs: int, lon: float, lat: float) -> tuple[float, float]:
    """Las coordenadas que se escriben en una capa del SRS dado para el punto (lon, lat)."""
    if srs in (4258, 4326, 4081, 0):
        return lon, lat
    if srs == 3857:
        return TO_3857.transform(lon, lat)
    if srs == 25833:
        return TO_25833.transform(lon, lat)
    if srs in (-1, 99999):
        return TO_25833.transform(lon, lat)
    return MANUAL[srs].typed_from_base(lat, lon, "PROJECT_CRS")


def written(layer: Layer, g: G | None) -> G | None:
    if g is None:
        return None
    return remap(g, lambda p: layer_xy(layer.srs, p[0], p[1]) + tuple(p[2:]))


def project_of(srs: int, x: float, y: float) -> tuple[float, float] | None:
    """Lo que la app obtiene en ETRS89 UTM 30 para (x, y) del SRS; None si cae fuera del ámbito del proyecto."""
    if srs in (4258, 4326):
        lat, lon = y, x
    else:
        base = MANUAL[srs].base_from_typed("PROJECT_CRS", x, y)
        if base is None:
            return None
        lat, lon = base
    if not PROJECT.inside(lat, lon):
        return None
    return PROJECT.typed_from_base(lat, lon, "PROJECT_CRS")


def srs_available(srs: int) -> bool:
    row = CRS_BY_EPSG.get(srs)
    return row is not None and row[2] in ("DISPONIBLE", "WGS84")


# ---------- construcción del GeoPackage ----------

SQL_TABLES = (
    "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL,"
    " organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)",
    "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, description TEXT"
    " DEFAULT '', last_change DATETIME NOT NULL, min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER)",
    "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL,"
    " srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name))",
    "CREATE TABLE gpkg_extensions (table_name TEXT, column_name TEXT, extension_name TEXT NOT NULL, definition TEXT NOT NULL,"
    " scope TEXT NOT NULL, UNIQUE (table_name, column_name, extension_name))",
)
SQL_TILES = (
    "CREATE TABLE gpkg_tile_matrix_set (table_name TEXT NOT NULL PRIMARY KEY, srs_id INTEGER NOT NULL, min_x DOUBLE NOT NULL,"
    " min_y DOUBLE NOT NULL, max_x DOUBLE NOT NULL, max_y DOUBLE NOT NULL)",
    "CREATE TABLE gpkg_tile_matrix (table_name TEXT NOT NULL, zoom_level INTEGER NOT NULL, matrix_width INTEGER NOT NULL,"
    " matrix_height INTEGER NOT NULL, tile_width INTEGER NOT NULL, tile_height INTEGER NOT NULL, pixel_x_size DOUBLE NOT NULL,"
    " pixel_y_size DOUBLE NOT NULL, CONSTRAINT pk_ttm PRIMARY KEY (table_name, zoom_level))",
)


def srs_definition(epsg: int) -> tuple[str, str]:
    crs = pyproj.CRS.from_epsg(epsg)
    return crs.name, crs.to_wkt(pyproj.enums.WktVersion.WKT1_GDAL)


@dataclass
class Extras:
    attributes: bool = False  # tabla `censo` registrada como atributos
    tiles: bool = False  # tabla `teselas` registrada como teselas
    foreign: bool = False  # tabla `notas` sin registrar


def build(layers: list[Layer], extras: Extras | None = None, undeclared: tuple[int, ...] = ()) -> bytes:
    """Un GeoPackage completo en memoria. undeclared: SRS que las capas usan pero que gpkg_spatial_ref_sys no define."""
    extras = extras or Extras()
    con = sqlite3.connect(":memory:")
    con.execute(f"PRAGMA application_id={APPLICATION_ID}")
    con.execute(f"PRAGMA user_version={USER_VERSION}")
    for sql in SQL_TABLES + (SQL_TILES if extras.tiles else ()):
        con.execute(sql)
    con.executemany("INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)", [
        ("Undefined Cartesian SRS", -1, "NONE", -1, "undefined", "undefined Cartesian coordinate reference system"),
        ("Undefined geographic SRS", 0, "NONE", 0, "undefined", "undefined geographic coordinate reference system"),
        ("WGS 84 geodetic", 4326, "EPSG", 4326, srs_definition(4326)[1], "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"),
    ])
    used = sorted({layer.srs for layer in layers if layer.srs in CRS_BY_EPSG and layer.srs != 4326 and layer.srs not in undeclared})
    if extras.tiles:
        used = sorted(set(used) | {3857})
    for epsg in used:
        name, wkt = srs_definition(epsg)
        con.execute("INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,?)", (name, epsg, "EPSG", epsg, wkt, None))

    for layer in layers:
        cols = "".join(f", {name} {kind}" for name, kind in layer.cols)
        con.execute(f"CREATE TABLE {layer.table} (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom BLOB{cols})")
        names = [name for name, _ in layer.cols]
        envelopes = []
        for feat in layer.feats:
            g = written(layer, feat.geom)
            blob = gpkg_blob(g, layer.srs, layer.env, layer.big_header)
            cur = con.execute(f"INSERT INTO {layer.table} (geom{''.join(', ' + n for n in names)}) VALUES (?{', ?' * len(names)})",
                              [blob] + [feat.props.get(n) for n in names])
            verts = vertices(g) if g is not None and not g.empty else []
            if verts:
                xs, ys = [p[0] for p in verts], [p[1] for p in verts]
                envelopes.append((cur.lastrowid, min(xs), max(xs), min(ys), max(ys)))
        extent = (min(e[1] for e in envelopes), min(e[3] for e in envelopes), max(e[2] for e in envelopes), max(e[4] for e in envelopes)) \
            if envelopes else (None, None, None, None)
        con.execute("INSERT INTO gpkg_contents VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (layer.table, "features", layer.table, "", LAST_CHANGE, extent[0], extent[1], extent[2], extent[3], layer.srs))
        con.execute("INSERT INTO gpkg_geometry_columns VALUES (?,?,?,?,?,?)", (layer.table, "geom", layer.gtype, layer.srs, layer.z, 0))
        if layer.rtree:
            rtree = f"rtree_{layer.table}_geom"
            con.execute(f"CREATE VIRTUAL TABLE {rtree} USING rtree(id, minx, maxx, miny, maxy)")
            con.executemany(f"INSERT INTO {rtree} VALUES (?,?,?,?,?)", envelopes)
            con.execute("INSERT INTO gpkg_extensions VALUES (?,?,?,?,?)",
                        (layer.table, "geom", "gpkg_rtree_index", "http://www.geopackage.org/spec120/#extension_rtree", "write-only"))

    if extras.attributes:
        con.execute("CREATE TABLE censo (id INTEGER PRIMARY KEY AUTOINCREMENT, barrio TEXT, habitantes INTEGER)")
        con.executemany("INSERT INTO censo (barrio, habitantes) VALUES (?,?)", [("Centro", 1200), ("Ensanche", 3400)])
        con.execute("INSERT INTO gpkg_contents VALUES ('censo','attributes','censo','',?,NULL,NULL,NULL,NULL,NULL)", (LAST_CHANGE,))
    if extras.tiles:
        con.execute("CREATE TABLE teselas (id INTEGER PRIMARY KEY AUTOINCREMENT, zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,"
                    " tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL, UNIQUE (zoom_level, tile_column, tile_row))")
        con.execute("INSERT INTO teselas (zoom_level, tile_column, tile_row, tile_data) VALUES (0,0,0,?)", (bytes(range(16)),))
        con.execute("INSERT INTO gpkg_contents VALUES ('teselas','tiles','teselas','',?,-20037508.34,-20037508.34,20037508.34,20037508.34,3857)",
                    (LAST_CHANGE,))
        con.execute("INSERT INTO gpkg_tile_matrix_set VALUES ('teselas',3857,-20037508.34,-20037508.34,20037508.34,20037508.34)")
        con.execute("INSERT INTO gpkg_tile_matrix VALUES ('teselas',0,1,1,256,256,156543.03,156543.03)")
    if extras.foreign:
        con.execute("CREATE TABLE notas (id INTEGER PRIMARY KEY, texto TEXT)")
        con.execute("INSERT INTO notas VALUES (1, 'Una tabla que no está en gpkg_contents')")
    con.commit()
    data = con.serialize()
    con.close()
    return bytes(data)


# ---------- los ficheros de ejemplo ----------


def pt(e: float, n: float, z: float | None = None, dims: str = "XY", **kw) -> G:
    return G("POINT", ll(e, n, z), dims, **kw)


def line(points: list[tuple], dims: str = "XY", **kw) -> G:
    return G("LINESTRING", [ll(*p) for p in points], dims, **kw)


def square(e: float, n: float, size: float, hole: float = 0.0, dims: str = "XY") -> list[list[tuple]]:
    """Anillo exterior en sentido antihorario, y un hueco cuadrado en sentido horario si hole > 0."""
    ring = [ll(e, n), ll(e + size, n), ll(e + size, n + size), ll(e, n + size), ll(e, n)]
    if not hole:
        return [ring]
    inner = e + (size - hole) / 2, n + (size - hole) / 2
    return [ring, [ll(inner[0], inner[1]), ll(inner[0], inner[1] + hole), ll(inner[0] + hole, inner[1] + hole),
                   ll(inner[0] + hole, inner[1]), ll(inner[0], inner[1])]]


def named(*names: str | None) -> list[dict]:
    return [{"nombre": n} for n in names]


def simple_layers(srs: int, table: str, gtype: str = "POINT") -> Layer:
    """Tres elementos del tipo dado, para los ficheros que prueban un sistema de coordenadas."""
    if gtype == "POINT":
        geoms = [pt(100, 100), pt(220, 160), pt(340, 90)]
    else:
        geoms = [line([(0, 0), (100, 40), (200, 0)]), line([(0, 120), (150, 180)]), line([(30, 250), (120, 300), (220, 260)])]
    return Layer(table, gtype, srs, [Feat(g, p) for g, p in zip(geoms, named("Uno", "Dos", "Tres"))])


def puntos() -> Layer:
    cols = [("nombre", "TEXT"), ("cota", "REAL"), ("codigo", "INTEGER"), ("fecha", "DATE"), ("activo", "BOOLEAN"), ("foto", "BLOB")]
    rows = [
        (pt(100, 100), {"nombre": "Esquina norte", "cota": 646.377, "codigo": 1, "fecha": "2026-10-01", "activo": 1}),
        (pt(150, 120), {"nombre": "Árbol ñandú", "cota": 647.125, "codigo": 2, "fecha": "2026-10-02", "activo": 0}),
        (pt(200, 140), {"nombre": None, "cota": 648.0, "codigo": 3}),
        (pt(250, 160), {"nombre": "", "cota": None, "codigo": 4}),
        (pt(300, 180), {"nombre": "Farola", "cota": 649.5, "codigo": 5, "foto": bytes(70000)}),
    ]
    return Layer("vertices", "POINT", 25830, [Feat(g, p) for g, p in rows], cols)


def lineas() -> Layer:
    cols = [("nombre", "TEXT"), ("ancho", "REAL")]
    rows = [
        (line([(0, 0), (100, 50), (200, 0)]), {"nombre": "Calle Mayor", "ancho": 12.5}),
        (line([(0, 200), (300, 250)]), {"nombre": "Calle Alta", "ancho": 8.0}),
        (line([(50, 300), (120, 320), (200, 380), (260, 300)]), {"nombre": "Camino", "ancho": None}),
    ]
    return Layer("ejes", "LINESTRING", 4258, [Feat(g, p) for g, p in rows], cols)


def hole_ring(e: float, n: float, size: float) -> list[tuple]:
    """Un hueco cuadrado cerrado, en sentido horario."""
    return [ll(e, n), ll(e, n + size), ll(e + size, n + size), ll(e + size, n), ll(e, n)]


def poligonos() -> list[Layer]:
    parcelas = [
        Feat(G("POLYGON", square(0, 0, 100)), {"nombre": "Parcela 1"}),
        Feat(G("POLYGON", [square(150, 0, 200)[0], hole_ring(170, 20, 40), hole_ring(250, 100, 60)]), {"nombre": "Parcela 2"}),
    ]
    manzanas = [
        Feat(G("MULTIPOLYGON", [G("POLYGON", square(0, 400, 100)), G("POLYGON", square(150, 400, 150, 50))]), {"nombre": "Manzana A"}),
        Feat(G("MULTIPOLYGON", [G("POLYGON", square(400, 400, 80))]), {"nombre": "Manzana B"}),
    ]
    return [Layer("parcelas", "POLYGON", 25830, parcelas), Layer("manzanas", "MULTIPOLYGON", 25830, manzanas)]


def multiples() -> list[Layer]:
    multipoint = G("MULTIPOINT", [])
    multipoint.data = [sub(multipoint, "POINT", ll(*p)) for p in ((50, 50), (90, 70), (130, 40))]
    multiline = G("MULTILINESTRING", [])
    multiline.data = [sub(multiline, "LINESTRING", [ll(*p) for p in pts]) for pts in (((0, 0), (80, 20)), ((0, 60), (60, 90), (120, 70)))]
    collection = G("GEOMETRYCOLLECTION", [])
    collection.data = [
        sub(collection, "POINT", ll(400, 400)),
        sub(collection, "LINESTRING", [ll(410, 400), ll(480, 430)]),
        sub(collection, "POLYGON", square(500, 400, 60, 20)),
    ]
    return [
        Layer("multipuntos", "MULTIPOINT", 25830, [Feat(multipoint, {"nombre": "Grupo"})]),
        Layer("multilineas", "MULTILINESTRING", 25830, [Feat(multiline, {"nombre": "Red"})]),
        Layer("mixta", "GEOMETRY", 25830, [Feat(pt(300, 300), {"nombre": "Punto"}), Feat(line([(300, 320), (360, 380)]), {"nombre": "Línea"}),
                                           Feat(collection, {"nombre": "Colección"})]),
    ]


def altura() -> list[Layer]:
    def zline(dims: str) -> G:
        extra = {"XYZ": (lambda i: (100.0 + i,)), "XYM": (lambda i: (7.5 + i,)), "XYZM": (lambda i: (100.0 + i, 7.5 + i))}[dims]
        return G("LINESTRING", [ll(e, n) + extra(i) for i, (e, n) in enumerate(((0, 0), (80, 40), (160, 0)))], dims)

    zring = square(300, 0, 100, 30)
    zpoly = G("POLYGON", [[p + (110.0 + i,) for i, p in enumerate(ring)] for ring in zring], "XYZ")
    return [
        Layer("puntos_z", "POINT", 25830, [Feat(pt(10, 10, 646.377, "XYZ"), {"nombre": "A"}), Feat(pt(60, 20, 0.0, "XYZ"), {"nombre": "B"})],
              z=1, env=2),
        Layer("lineas_z", "LINESTRING", 25830, [Feat(zline("XYZ"), {"nombre": "Eje"})], z=1, env=2),
        Layer("lineas_m", "LINESTRING", 25830, [Feat(zline("XYM"), {"nombre": "Eje"})]),
        Layer("lineas_zm", "LINESTRING", 25830, [Feat(zline("XYZM"), {"nombre": "Eje"})], z=2, env=2),
        Layer("poligonos_z", "POLYGON", 25830, [Feat(zpoly, {"nombre": "Parcela"})], z=1, env=2),
    ]


def codificaciones() -> list[Layer]:
    def feats(**kw) -> list[Feat]:
        dims = kw.get("dims", "XY")
        z = 50.0 if dims == "XYZ" else None
        ring = [p + (50.0 + i,) if dims == "XYZ" else p for i, p in enumerate(square(0, 100, 60, 20)[0])]
        hole = [p + (50.0,) if dims == "XYZ" else p for p in square(0, 100, 60, 20)[1]]
        return [
            Feat(G("POINT", ll(20, 20, z), **kw), {"nombre": "Punto"}),
            Feat(G("LINESTRING", [ll(0, 0, z), ll(80, 30, z)], **kw), {"nombre": "Línea"}),
            Feat(G("POLYGON", [ring, hole], **kw), {"nombre": "Polígono"}),
        ]

    return [
        Layer("wkb_big_endian", "GEOMETRY", 25830, feats(big=True)),
        Layer("ewkb_z", "GEOMETRY", 25830, feats(dims="XYZ", ewkb=True, srid=25830), z=2, env=2),
        Layer("cabecera_big_endian", "GEOMETRY", 25830, feats(big=True), big_header=True),
    ]


def curvas_y_vacios() -> list[Layer]:
    arc = G("CIRCULARSTRING", [ll(0, 0), ll(30, 30), ll(60, 0)])
    compound = G("COMPOUNDCURVE", [])
    compound.data = [sub(compound, "LINESTRING", [ll(100, 0), ll(140, 0)]), sub(compound, "CIRCULARSTRING", [ll(140, 0), ll(160, 20), ll(180, 0)])]
    curved = G("CURVEPOLYGON", [])
    curved.data = [sub(curved, "CIRCULARSTRING", [ll(200, 0), ll(240, 40), ll(280, 0), ll(240, -40), ll(200, 0)])]
    rows = [
        (pt(20, 20), "Punto normal"),
        (G("POINT", None, empty=True), "Punto vacío"),
        (G("LINESTRING", [], empty=True), "Línea vacía"),
        (None, "Sin geometría"),
        (arc, "Arco"),
        (compound, "Curva compuesta"),
        (curved, "Polígono curvo"),
        (line([(0, 100), (90, 140)]), "Línea normal"),
    ]
    return [Layer("raros", "GEOMETRY", 25830, [Feat(g, {"nombre": n}) for g, n in rows])]


def sistemas_mixtos() -> list[Layer]:
    canarias = [Feat(G("POINT", (-15.4 + 0.001 * i, 28.1 + 0.001 * i)), {"nombre": f"Gran Canaria {i}"}) for i in range(3)]
    return [
        simple_layers(25829, "utm29"),
        simple_layers(25831, "utm31"),
        simple_layers(23030, "ed50_utm30"),
        Layer("canarias_utm28", "POINT", 4083, canarias),
        simple_layers(25833, "utm33"),
        simple_layers(4258, "geograficas_4258"),
    ]


def sin_sistema() -> list[Layer]:
    return [simple_layers(-1, "indefinido_cartesiano"), simple_layers(0, "indefinido_geografico"), simple_layers(99999, "srs_inexistente")]


def sin_rtree() -> list[Layer]:
    return [
        Layer("con_rtree", "POINT", 25830, simple_layers(25830, "x").feats),
        Layer("sin_rtree", "LINESTRING", 25830, simple_layers(25830, "x", "LINESTRING").feats, rtree=False),
        Layer("sin_envolvente", "POLYGON", 25830, [Feat(G("POLYGON", square(0, 400, 100, 30)), {"nombre": "A"}),
                                                    Feat(G("POLYGON", square(200, 400, 100)), {"nombre": "B"})], rtree=False, env=0),
    ]


def sqlite_comun() -> bytes:
    con = sqlite3.connect(":memory:")
    con.execute("CREATE TABLE datos (id INTEGER PRIMARY KEY, valor TEXT)")
    con.execute("INSERT INTO datos VALUES (1, 'No es un GeoPackage')")
    con.commit()
    data = bytes(con.serialize())
    con.close()
    return data


def files_spec() -> dict[str, tuple[list[Layer], Extras, tuple[int, ...]]]:
    plain = Extras()
    return {
        "puntos-25830.gpkg": ([puntos()], plain, ()),
        "lineas-4258.gpkg": ([lineas()], plain, ()),
        "poligonos-25830.gpkg": (poligonos(), plain, ()),
        "multiples-25830.gpkg": (multiples(), plain, ()),
        "altura-25830.gpkg": (altura(), plain, ()),
        "codificaciones-25830.gpkg": (codificaciones(), plain, ()),
        "curvas-y-vacios-25830.gpkg": (curvas_y_vacios(), plain, ()),
        "wgs84-4326.gpkg": ([simple_layers(4326, "puntos")], plain, ()),
        "no-disponible-3857.gpkg": ([simple_layers(3857, "puntos")], plain, ()),
        "sin-sistema.gpkg": (sin_sistema(), plain, (99999,)),
        "sistemas-mixtos.gpkg": (sistemas_mixtos(), plain, ()),
        "sin-rtree-25830.gpkg": (sin_rtree(), plain, ()),
        "con-ajenas-25830.gpkg": ([simple_layers(25830, "puntos")], Extras(attributes=True, tiles=True, foreign=True), ()),
        "sin-capas.gpkg": ([], Extras(attributes=True), ()),
        # El mismo contenido que lineas-4258.gpkg con otra extensión: se reconoce por su contenido (RF-2).
        "lineas-con-otra-extension.dat": ([lineas()], plain, ()),
    }


NOT_GPKG = {
    "texto.gpkg": b"Esto no es un GeoPackage.\r\nSolo es un texto cualquiera.\r\n",
    "vacio.gpkg": b"",
}
NOT_GPKG_KIND = {"texto.gpkg": "NO_GEOPACKAGE", "vacio.gpkg": "VACIO", "sqlite-comun.gpkg": "NO_GEOPACKAGE", "danado.gpkg": "DANADO"}


# ---------- lo esperado ----------


def flatten(g: G):
    """(capa de diseño, anillo, contorno, vértices con su dimensión) de cada elemento que sale de la geometría (RF-17)."""
    if g.kind == "POINT":
        yield "POINTS", None, False, [tuple(g.data)], g.dims
    elif g.kind == "LINESTRING":
        yield "LINES", None, False, [tuple(p) for p in g.data], g.dims
    elif g.kind == "POLYGON":
        for i, ring in enumerate(g.data):
            yield "LINES", i, True, [tuple(p) for p in ring], g.dims
    else:
        for child in g.data:
            yield from flatten(child)


def vertex_text(p: tuple, dims: str) -> str:
    z = p[2] if "Z" in dims else None
    return f"{num(p[0])} {num(p[1])} {'_' if z is None else num(z)}"


def describe(file: str, layer: Layer, out: list[list]) -> None:
    if not srs_available(layer.srs):
        return  # la capa no se importa hasta que el usuario elija un sistema (RF-9)
    for fid, feat in enumerate(layer.feats, 1):
        g = written(layer, feat.geom)
        if g is None or g.empty or not vertices(g):
            out.append([file, layer.table, fid, "", "", "", "", "", "", "ignorado_vacia"])
            continue
        if g.kind in CURVES:
            out.append([file, layer.table, fid, "", "", "", "", "", "", "ignorado_curva"])
            continue
        elements = list(flatten(g))
        converted = [[project_of(layer.srs, p[0], p[1]) for p in verts] for *_, verts, _ in elements]
        if any(c is None for part in converted for c in part):
            out.append([file, layer.table, fid, "", "", "", "", "", "", "fuera_de_ambito"])
            continue
        for index, ((kind, ring, contour, verts, dims), proj) in enumerate(zip(elements, converted), 1):
            source = ";".join(vertex_text(p, dims) for p in verts)
            project = ";".join(f"{num(c[0])} {num(c[1])} {'_' if 'Z' not in dims else num(p[2])}" for c, p in zip(proj, verts))
            out.append([file, layer.table, fid, kind, index, "" if ring is None else ring, "SI" if contour else "NO", source, project, "OK"])


def layer_row(file: str, layer: Layer) -> list:
    row = CRS_BY_EPSG.get(layer.srs)
    if layer.srs in (-1, 0):
        system, unit = "SIN_SISTEMA", ""
    elif layer.srs == 99999:
        system, unit = "NO_DECLARADO", ""
    elif srs_available(layer.srs):
        system, unit = row[1], crs_unit(layer.srs)
    else:
        system, unit = "NO_DISPONIBLE", crs_unit(layer.srs)
    return [file, layer.table, layer.gtype, layer.srs, len(layer.feats), system, unit, "SI" if layer.rtree else "NO",
            "NO" if layer.env == 0 else "SI", layer.z, ";".join(f"{n}:{t}" for n, t in layer.cols)]


def crs_unit(epsg: int) -> str:
    unit = pyproj.CRS.from_epsg(epsg).axis_info[0].unit_name
    return {"metre": "m", "degree": "deg"}[unit]


def crs_rows() -> list[list]:
    rows = []
    for epsg, system, state, kind, datum, zone in CRS_ROWS:
        rows.append([epsg, system, state, kind, datum, "" if zone is None else zone, crs_unit(epsg)])
    rows.append([-1, "", "SIN_SISTEMA", "", "", "", ""])
    rows.append([0, "", "SIN_SISTEMA", "", "", "", ""])
    return rows


def csv_rows(rows: list[list]) -> list[str]:
    return [",".join(str(x) for x in row) for row in rows]


def banner(what: str) -> list[str]:
    return [
        f"# {what} (S048). Generado por tools/formatos/generar_geopackage.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla=es_ign_SPED2ETV2.tif",
    ]


def generate() -> dict[str, bytes | str]:
    files: dict[str, bytes | str] = {}
    elements, layer_rows, summary = [], [], []
    for file, (layers, extras, undeclared) in files_spec().items():
        files[file] = build(layers, extras, undeclared)
        if file.endswith(".dat"):
            summary.append([file, "tipo_fichero", "GEOPACKAGE"])
            summary.append([file, "capas_con_geometria", len(layers)])
            continue
        for layer in layers:
            describe(file, layer, elements)
            layer_rows.append(layer_row(file, layer))
        summary.append([file, "tipo_fichero", "GEOPACKAGE"])
        summary.append([file, "capas_con_geometria", len(layers)])
        foreign = [name for name, on in (("censo", extras.attributes), ("teselas", extras.tiles), ("notas", extras.foreign)) if on]
        summary.append([file, "tablas_ajenas", ";".join(foreign)])

    files["sqlite-comun.gpkg"] = sqlite_comun()
    full = files["puntos-25830.gpkg"]
    files["danado.gpkg"] = full[: 2 * PAGE]
    for file, data in NOT_GPKG.items():
        files[file] = data
    for file, kind in NOT_GPKG_KIND.items():
        summary.append([file, "tipo_fichero", kind])

    files["esperado.csv"] = "\n".join(banner("Elementos esperados de cada objeto") + [
        "# vertices_fuente: 'x y z' en el sistema de la capa; vertices_proyecto: 'e n z' en ETRS89 UTM 30; _ si no hay altura.",
        "# anillo: 0 el exterior, 1.. los huecos. Solo hay filas de las capas con sistema disponible.",
        "archivo,tabla,fid,capa_diseno,elemento,anillo,contorno,vertices_fuente,vertices_proyecto,estado",
    ] + csv_rows(elements)) + "\n"
    files["esperado-capas.csv"] = "\n".join(banner("Capas con geometría esperadas de cada GeoPackage") + [
        "archivo,tabla,tipo_geometria,srs_id,objetos,sistema_app,unidad,rtree,envolvente,z,columnas",
    ] + csv_rows(layer_rows)) + "\n"
    files["esperado-resumen.csv"] = "\n".join(banner("Resumen esperado de cada fichero") + ["archivo,clave,valor"] + csv_rows(summary)) + "\n"
    files["esperado-crs.csv"] = "\n".join(banner("Sistemas de coordenadas: EPSG de la capa → sistema de la app") + [
        "epsg,sistema_app,estado,tipo,datum,huso,unidad",
    ] + csv_rows(crs_rows())) + "\n"
    return files


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
