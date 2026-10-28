"""Referencias de la exportación a GeoPackage calculadas con pyproj y un lector de GeoPackage con `sqlite3` (S053).

Genera en fixtures/formatos/gpkg-exportado/:

- esperado-srs.csv: por sistema de la app (S008) y por geográficas de cada datum, su código EPSG, su nombre y su definición WKT1 (la de
  `gpkg_spatial_ref_sys`), calculados con pyproj. El WKT cambia con la versión de PROJ: el test lo compara por el sistema que define, no
  por el texto.
- esperado-puntos.csv: unos puntos de la Península, Baleares y Canarias con su latitud y longitud y su Este y Norte en cada sistema
  proyectado, con pyproj (constitución nº 2). El móvil exporta las coordenadas del punto sin redondear, así que el fichero exportado tiene
  que dar estos valores a menos de 1 mm.

Y lo que usa el test de `tools` de la exportación:

- `read_gpkg`: un lector de `.gpkg` con `sqlite3` de la biblioteca estándar y la decodificación del blob de geometría (cabecera `GP`, WKB
  de punto con o sin Z). No es el lector del móvil, así que comprobar con él un fichero exportado no es circular.
- `build_reference`: un fichero de referencia escrito con `sqlite3` con la estructura que debe escribir la app (S053, plan «Modelo de datos del
  fichero»): `application_id` `GPKG`, `user_version` 10300, las tres filas obligatorias de sistemas más la del sistema de la capa, la capa
  `points` con una columna por atributo y su geometría con Z opcional.

Reglas de este conjunto que la spec no fija y que quedan escritas aquí:
- Los atributos se escriben en metros y grados, sin redondear (D1 del plan).
- Un proyecto ED50 exporta las geográficas como EPSG:4258 (D3).

Los `.gpkg` no son idénticos byte a byte entre versiones de SQLite (la cabecera guarda la versión): los tests comparan su contenido.

Uso: uv run --project tools tools/formatos/generar_gpkg_exportado.py
"""

from __future__ import annotations

import math
import sqlite3
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import pyproj
from pyproj import CRS, Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "gpkg-exportado"
APPLICATION_ID = 0x47504B47  # "GPKG"
USER_VERSION = 10300  # GeoPackage 1.3.0
LAST_CHANGE = "2026-10-28T00:00:00.000Z"
LAYER = "points"
MANDATORY_SRS = (-1, 0, 4326)

# Los sistemas de S008 (ProjectCrs): EPSG y su nombre en la app.
SRS = (
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

# (id, latitud, longitud) en el datum del sistema; las geográficas de cada punto son las de su datum.
SAMPLES = {
    25829: (("Vigo", 42.2406, -8.7207), ("Coruna", 43.3623, -8.4115), ("Lisboa", 38.7223, -9.1393)),
    25830: (("Madrid", 40.4168, -3.7038), ("Valencia", 39.4699, -0.3763), ("Sevilla", 37.3891, -5.9845)),
    25831: (("Barcelona", 41.3874, 2.1686), ("Palma", 39.5696, 2.6502), ("Mahon", 39.8885, 4.2658)),
    4082: (("Hierro", 27.7000, -18.1600), ("Orchilla", 27.7461, -18.1540)),
    4083: (("Tenerife", 28.4636, -16.2518), ("LasPalmas", 28.1235, -15.4363), ("Lanzarote", 28.9630, -13.5477)),
}
GEOGRAPHIC_OF = {25829: 4258, 25830: 4258, 25831: 4258, 4082: 4081, 4083: 4081}


def num(x: float | None) -> str:
    return "" if x is None else repr(float(x))


def csv_cell(text: str) -> str:
    return '"' + text.replace('"', '""') + '"' if any(c in text for c in ',"\n') else text


def wkt(epsg: int) -> str:
    return CRS.from_epsg(epsg).to_wkt(pyproj.enums.WktVersion.WKT1_GDAL)


# ---------------------------------------------------------------------------------------------------------------------- esperado
def srs_csv() -> str:
    rows = ["epsg,nombre,definicion"]
    for code, name in SRS:
        rows.append(",".join([str(code), csv_cell(name), csv_cell(wkt(code))]))
    return "\n".join(rows) + "\n"


def points_csv() -> str:
    rows = ["epsg,punto,latitud,longitud,este,norte"]
    for code, samples in SAMPLES.items():
        transformer = Transformer.from_crs(f"EPSG:{GEOGRAPHIC_OF[code]}", f"EPSG:{code}", always_xy=True)
        for name, lat, lon in samples:
            east, north = transformer.transform(lon, lat)
            rows.append(",".join([str(code), name, num(lat), num(lon), num(east), num(north)]))
    return "\n".join(rows) + "\n"


def expected() -> dict[str, str]:
    return {"esperado-srs.csv": srs_csv(), "esperado-puntos.csv": points_csv()}


# ------------------------------------------------------------------------------------------------------------------ el lector
@dataclass(frozen=True)
class Geometry:
    srs_id: int
    empty: bool
    kind: int  # 1 Point, 1001 Point Z
    coords: tuple[float, ...]


def decode_geometry(blob: bytes | None) -> Geometry | None:
    """La cabecera de un blob de GeoPackage y su WKB de punto; None si el blob es NULL."""
    if blob is None:
        return None
    assert blob[:2] == b"GP", "falta la marca GP"
    assert blob[2] == 0, "versión de GeoPackage distinta de 0"
    flags = blob[3]
    little = bool(flags & 1)
    empty = bool(flags & 0x10)
    envelope = (flags >> 1) & 7
    envelope_bytes = {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[envelope]
    srs_id = struct.unpack("<i" if little else ">i", blob[4:8])[0]
    wkb = blob[8 + envelope_bytes :]
    order = "<" if wkb[0] == 1 else ">"
    kind = struct.unpack(order + "I", wkb[1:5])[0]
    if empty:
        return Geometry(srs_id, True, kind, ())
    dims = 3 if kind == 1001 else 2
    coords = struct.unpack(order + "d" * dims, wkb[5 : 5 + 8 * dims])
    return Geometry(srs_id, False, kind, coords)


@dataclass
class GpkgInfo:
    application_id: int
    user_version: int
    tables: set[str]
    srs: dict[int, tuple[str, int, str]]  # srs_id → (organización, código, definición)
    contents: list[tuple]
    geometry_columns: list[tuple]
    columns: list[tuple[str, str]]  # (nombre, tipo declarado) de la capa de puntos
    rows: list[dict]  # un diccionario por punto: sus atributos y 'geom' decodificada


def read_gpkg(path: Path) -> GpkgInfo:
    """Lee un `.gpkg` con sqlite3 de Python: sus tablas obligatorias, la capa `points` y sus filas con la geometría decodificada."""
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        application_id = con.execute("PRAGMA application_id").fetchone()[0]
        user_version = con.execute("PRAGMA user_version").fetchone()[0]
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        srs = {
            r[1]: (r[2], r[3], r[4])
            for r in con.execute(
                "SELECT srs_name, srs_id, organization, organization_coordsys_id, definition FROM gpkg_spatial_ref_sys"
            )
        }
        contents = list(
            con.execute(
                "SELECT table_name, data_type, identifier, min_x, min_y, max_x, max_y, srs_id FROM gpkg_contents ORDER BY table_name"
            )
        )
        geometry_columns = list(con.execute("SELECT * FROM gpkg_geometry_columns ORDER BY table_name"))
        info = list(con.execute(f'PRAGMA table_info("{LAYER}")'))
        columns = [(r[1], r[2]) for r in info]
        names = [c[0] for c in columns]
        rows = []
        for record in con.execute(f'SELECT * FROM "{LAYER}" ORDER BY fid'):
            row = dict(zip(names, record))
            row["geom"] = decode_geometry(row["geom"])
            rows.append(row)
        return GpkgInfo(application_id, user_version, tables, srs, contents, geometry_columns, columns, rows)
    finally:
        con.close()


# ---------------------------------------------------------------------------------------------------------------- la referencia
def geometry_blob(srs_id: int, east: float | None, north: float | None, height: float | None) -> bytes:
    """El blob de un punto: cabecera GP little-endian sin envolvente y WKB `Point` o `Point Z`; vacía si falta una coordenada."""
    if east is None or north is None:
        flags = 0x01 | 0x10
        return b"GP" + bytes([0, flags]) + struct.pack("<i", srs_id) + struct.pack("<BIdd", 1, 1, math.nan, math.nan)
    if height is None:
        return b"GP" + bytes([0, 0x01]) + struct.pack("<i", srs_id) + struct.pack("<BIdd", 1, 1, east, north)
    return b"GP" + bytes([0, 0x01]) + struct.pack("<i", srs_id) + struct.pack("<BIddd", 1, 1001, east, north, height)


ATTRIBUTES = (
    ("Name", "TEXT"),
    ("Easting", "REAL"),
    ("Northing", "REAL"),
    ("Elevation", "REAL"),
    ("Latitude", "REAL"),
    ("Longitude", "REAL"),
    ("Satellites", "INTEGER"),
    ("Replay", "BOOLEAN"),
)


def build_reference(path: Path, epsg: int, rows: list[tuple]) -> None:
    """Un `.gpkg` con la estructura que debe escribir la app. Cada fila: (nombre, este, norte, altura, latitud, longitud, satélites, replay)."""
    path.unlink(missing_ok=True)
    con = sqlite3.connect(path)
    con.execute(f"PRAGMA application_id = {APPLICATION_ID}")
    con.execute(f"PRAGMA user_version = {USER_VERSION}")
    con.execute(
        "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL, "
        "organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)"
    )
    con.executemany(
        "INSERT INTO gpkg_spatial_ref_sys VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("Undefined Cartesian SRS", -1, "NONE", -1, "undefined", "undefined Cartesian coordinate reference system"),
            ("Undefined geographic SRS", 0, "NONE", 0, "undefined", "undefined geographic coordinate reference system"),
            ("WGS 84", 4326, "EPSG", 4326, wkt(4326), "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"),
        ],
    )
    if epsg != 4326:
        con.execute("INSERT INTO gpkg_spatial_ref_sys VALUES (?, ?, 'EPSG', ?, ?, NULL)", (f"EPSG:{epsg}", epsg, epsg, wkt(epsg)))
    con.execute(
        "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, "
        "description TEXT DEFAULT '', last_change DATETIME NOT NULL, min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER)"
    )
    con.execute(
        "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, "
        "srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, PRIMARY KEY (table_name, column_name))"
    )
    columns = ", ".join(f'"{name}" {kind}' for name, kind in ATTRIBUTES)
    con.execute(f'CREATE TABLE "{LAYER}" (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, {columns})')
    located = [(r[1], r[2]) for r in rows if r[1] is not None and r[2] is not None]
    extent = (min(x for x, _ in located), min(y for _, y in located), max(x for x, _ in located), max(y for _, y in located)) if located else (None,) * 4
    con.execute("INSERT INTO gpkg_contents VALUES (?, 'features', ?, '', ?, ?, ?, ?, ?, ?)", (LAYER, LAYER, LAST_CHANGE, *extent, epsg))
    con.execute("INSERT INTO gpkg_geometry_columns VALUES (?, 'geom', 'POINT', ?, 2, 0)", (LAYER, epsg))
    for name, east, north, height, lat, lon, satellites, replay in rows:
        con.execute(
            f'INSERT INTO "{LAYER}" (geom, {", ".join(chr(34) + n + chr(34) for n, _ in ATTRIBUTES)}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (geometry_blob(epsg, east, north, height), name, east, north, height, lat, lon, satellites, replay),
        )
    con.commit()
    con.close()


def all_files() -> dict[str, str]:
    return expected()


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(all_files())} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
