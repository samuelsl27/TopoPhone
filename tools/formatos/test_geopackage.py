"""Los GeoPackage de ejemplo están al día, se leen con otro lector y lo esperado sale de otro cálculo (S048)."""

from __future__ import annotations

import csv
import io
import math
import re
import sqlite3
import struct
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_geopackage as g  # noqa: E402
import pyproj  # noqa: E402
from pyproj import Transformer  # noqa: E402

DATOS = g.OUT
GPKG = [name for name in g.files_spec()]
FEATURE_FILES = [name for name, (layers, _, _) in g.files_spec().items() if layers and name.endswith(".gpkg")]
SRS_OF = {(name, layer.table): layer.srs for name, (layers, _, _) in g.files_spec().items() for layer in layers}
APP_ID = 0x47504B47


def rows_of(name: str) -> list[dict[str, str]]:
    lines = [line for line in (DATOS / name).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    return list(csv.DictReader(io.StringIO("\n".join(lines), newline="")))


def connect(data: bytes) -> sqlite3.Connection:
    con = sqlite3.connect(":memory:")
    con.deserialize(data)
    return con


def logical(data: bytes) -> list:
    """El contenido de un .gpkg sin la cabecera de SQLite y sin las definiciones WKT, que cambian con PROJ."""
    con = connect(data)
    out = [("application_id", con.execute("PRAGMA application_id").fetchone()[0]), ("user_version", con.execute("PRAGMA user_version").fetchone()[0])]
    for name, sql in con.execute("SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name").fetchall():
        out.append((name, sql))
        if sql is None or name.startswith("sqlite_"):
            continue
        columns = [r[1] for r in con.execute(f'PRAGMA table_info("{name}")') if not (name == "gpkg_spatial_ref_sys" and r[1] == "definition")]
        if columns:
            out.append(con.execute(f'SELECT {",".join(columns)} FROM "{name}" ORDER BY 1').fetchall())
    return out


def stored_layers(file: str) -> list[dict]:
    """Las capas con geometría de un .gpkg leídas con SQL, sin pasar por el escritor del script."""
    con = connect((DATOS / file).read_bytes())
    out = []
    query = "SELECT c.table_name, c.data_type, k.column_name, k.geometry_type_name, k.srs_id, k.z FROM gpkg_contents c " \
            "JOIN gpkg_geometry_columns k ON k.table_name = c.table_name WHERE c.data_type = 'features' ORDER BY c.rowid"
    for table, _, column, gtype, srs, z in con.execute(query).fetchall():
        out.append({"con": con, "table": table, "column": column, "type": gtype, "srs": srs, "z": z})
    return out


# ---------- un lector de WKB independiente del escritor ----------


class Wkb:
    def __init__(self, data: bytes) -> None:
        self.data, self.pos = data, 0

    def take(self, fmt: str, order: str):
        values = struct.unpack_from(order + fmt, self.data, self.pos)
        self.pos += struct.calcsize(order + fmt)
        return values

    def geometry(self) -> tuple[str, str, object]:
        order = ">" if self.take("B", "<")[0] == 0 else "<"
        (code,) = self.take("I", order)
        ewkb_z, ewkb_m, srid = bool(code & 0x80000000), bool(code & 0x40000000), bool(code & 0x20000000)
        base = code & 0x0FFFFFFF
        if srid:
            self.take("I", order)
        dims = "XY"
        if code & 0xE0000000:
            dims = "XY" + ("Z" if ewkb_z else "") + ("M" if ewkb_m else "")
        else:
            dims = ["XY", "XYZ", "XYM", "XYZM"][base // 1000]
            base %= 1000
        kind = {v: k for k, v in g.TYPE.items()}[base]
        n = len(dims)

        def points() -> list[tuple]:
            (count,) = self.take("I", order)
            return [self.take("d" * n, order) for _ in range(count)]

        if kind == "POINT":
            return kind, dims, self.take("d" * n, order)
        if kind in ("LINESTRING", "CIRCULARSTRING"):
            return kind, dims, points()
        if kind == "POLYGON":
            (rings,) = self.take("I", order)
            return kind, dims, [points() for _ in range(rings)]
        (count,) = self.take("I", order)
        return kind, dims, [self.geometry() for _ in range(count)]


def decode(blob: bytes) -> dict:
    assert blob[:2] == b"GP" and blob[2] == 0, "cabecera de GeoPackage"
    flags = blob[3]
    order = "<" if flags & 1 else ">"
    envelope = (flags >> 1) & 7
    empty = bool(flags & 16)
    size = {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[envelope]
    (srs,) = struct.unpack_from(order + "i", blob, 4)
    env = struct.unpack_from(order + "d" * (size // 8), blob, 8) if size else ()
    kind, dims, data = Wkb(blob[8 + size:]).geometry()
    return {"srs": srs, "envelope": env, "empty": empty, "kind": kind, "dims": dims, "data": data, "big_header": order == ">"}


def elements(kind: str, dims: str, data) -> list[tuple]:
    """(capa, anillo, contorno, vértices, dimensiones) de cada elemento, como hace la app con un objeto (RF-17)."""
    if kind == "POINT":
        return [("POINTS", None, False, [data], dims)]
    if kind == "LINESTRING":
        return [("LINES", None, False, data, dims)]
    if kind == "POLYGON":
        return [("LINES", i, True, ring, dims) for i, ring in enumerate(data)]
    out = []
    for child_kind, child_dims, child in data:
        out += elements(child_kind, child_dims, child)
    return out


def source_text(points: list[tuple], dims: str) -> str:
    return ";".join(f"{p[0]!r} {p[1]!r} {p[2]!r}" if "Z" in dims else f"{p[0]!r} {p[1]!r} _" for p in points)


# ---------- tests ----------


def test_s048_rf2_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    generated = g.generate()
    for name, content in generated.items():
        path = DATOS / name
        if name == "esperado.csv" or name.startswith("esperado-"):
            assert path.read_text(encoding="utf-8") == content, name
        elif name in g.NOT_GPKG:
            assert path.read_bytes() == content, name
        elif name == "danado.gpkg":
            assert path.read_bytes()[:16] == content[:16], name  # la versión de SQLite cambia en la cabecera, no el principio
            assert len(path.read_bytes()) == len(content) == 2 * g.PAGE
        else:
            assert logical(path.read_bytes()) == logical(content), name
    assert set(generated) == {p.name for p in DATOS.iterdir()}


def test_s048_rf2_la_cabecera_de_sqlite_y_el_application_id_se_leen_sin_la_extension() -> None:
    for name in GPKG:
        raw = (DATOS / name).read_bytes()
        assert raw[:16] == b"SQLite format 3\x00", name
        con = connect(raw)
        assert con.execute("PRAGMA application_id").fetchone()[0] == APP_ID, name
        assert con.execute("PRAGMA user_version").fetchone()[0] == g.USER_VERSION, name
    assert (DATOS / "lineas-con-otra-extension.dat").suffix == ".dat"


def test_s048_rf3_lo_que_no_es_un_geopackage_o_esta_danado_se_distingue() -> None:
    assert (DATOS / "texto.gpkg").read_bytes()[:16] != b"SQLite format 3\x00"
    assert (DATOS / "vacio.gpkg").read_bytes() == b""

    common = connect((DATOS / "sqlite-comun.gpkg").read_bytes())
    assert common.execute("PRAGMA application_id").fetchone()[0] != APP_ID
    assert common.execute("SELECT count(*) FROM sqlite_master WHERE name = 'gpkg_contents'").fetchone()[0] == 0

    broken = (DATOS / "danado.gpkg").read_bytes()
    assert broken[:16] == b"SQLite format 3\x00"
    with pytest.raises(sqlite3.DatabaseError):
        connect(broken).execute("SELECT count(*) FROM gpkg_contents").fetchall()

    nolayers = connect((DATOS / "sin-capas.gpkg").read_bytes())
    assert nolayers.execute("SELECT count(*) FROM gpkg_geometry_columns").fetchone()[0] == 0


def test_s048_rf4_ningun_fichero_de_ejemplo_se_acerca_al_limite_de_100_mb() -> None:
    assert max(p.stat().st_size for p in DATOS.iterdir()) < 1_000_000


def test_s048_rf5_las_capas_con_geometria_y_sus_datos_son_los_esperados() -> None:
    expected = rows_of("esperado-capas.csv")
    for file in FEATURE_FILES:
        wanted = [r for r in expected if r["archivo"] == file]
        found = stored_layers(file)
        assert [r["tabla"] for r in wanted] == [layer["table"] for layer in found], file
        for row, layer in zip(wanted, found):
            con, table = layer["con"], layer["table"]
            assert layer["type"] == row["tipo_geometria"], (file, table)
            assert layer["srs"] == int(row["srs_id"]) and layer["z"] == int(row["z"]), (file, table)
            assert con.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0] == int(row["objetos"]), (file, table)
            has_rtree = con.execute("SELECT count(*) FROM sqlite_master WHERE name = ?", (f"rtree_{table}_geom",)).fetchone()[0] == 1
            assert has_rtree == (row["rtree"] == "SI"), (file, table)
            columns = [f"{c[1]}:{c[2]}" for c in con.execute(f'PRAGMA table_info("{table}")') if c[1] not in ("fid", "geom")]
            assert ";".join(columns) == row["columnas"], (file, table)


def test_s048_rf6_las_tablas_sin_geometria_o_de_teselas_estan_y_no_son_capas() -> None:
    con = connect((DATOS / "con-ajenas-25830.gpkg").read_bytes())
    types = dict(con.execute("SELECT table_name, data_type FROM gpkg_contents").fetchall())
    assert types == {"puntos": "features", "censo": "attributes", "teselas": "tiles"}
    assert con.execute("SELECT count(*) FROM notas").fetchone()[0] == 1  # no está en gpkg_contents
    summary = {r["clave"]: r["valor"] for r in rows_of("esperado-resumen.csv") if r["archivo"] == "con-ajenas-25830.gpkg"}
    assert summary["tablas_ajenas"] == "censo;teselas;notas" and summary["capas_con_geometria"] == "1"


def test_s048_rf17_el_wkb_leido_por_otro_lector_da_los_vertices_esperados() -> None:
    expected = rows_of("esperado.csv")
    checked = 0
    for file in FEATURE_FILES:
        for layer in stored_layers(file):
            con, table = layer["con"], layer["table"]
            for fid, blob in con.execute(f'SELECT fid, geom FROM "{table}" ORDER BY fid'):
                rows = [r for r in expected if (r["archivo"], r["tabla"], int(r["fid"])) == (file, table, fid)]
                if not rows:
                    assert not g.srs_available(layer["srs"]), (file, table, fid)
                    continue
                if blob is None:
                    assert rows[0]["estado"] == "ignorado_vacia"
                    continue
                geom = decode(blob)
                assert geom["srs"] == layer["srs"], (file, table, fid)
                if rows[0]["estado"] == "ignorado_vacia":
                    continue
                if rows[0]["estado"] == "ignorado_curva":
                    assert geom["kind"] in g.CURVES
                    continue
                if rows[0]["estado"] == "fuera_de_ambito":
                    continue
                parts = elements(geom["kind"], geom["dims"], geom["data"])
                assert len(parts) == len(rows), (file, table, fid)
                for part, row in zip(parts, rows):
                    layer_kind, ring, contour, verts, dims = part
                    assert row["capa_diseno"] == layer_kind
                    assert row["anillo"] == ("" if ring is None else str(ring))
                    assert row["contorno"] == ("SI" if contour else "NO")
                    assert row["vertices_fuente"] == source_text(verts, dims), (file, table, fid)
                    checked += 1
    assert checked > 60


def test_s048_rf17_los_poligonos_con_huecos_y_los_multiples_estan_en_los_ejemplos() -> None:
    expected = rows_of("esperado.csv")
    parcela = [r for r in expected if r["tabla"] == "parcelas" and r["fid"] == "2"]
    assert [r["anillo"] for r in parcela] == ["0", "1", "2"] and all(r["contorno"] == "SI" for r in parcela)
    manzana = [r for r in expected if r["tabla"] == "manzanas" and r["fid"] == "1"]
    assert [r["anillo"] for r in manzana] == ["0", "0", "1"]
    mixta = [r for r in expected if r["tabla"] == "mixta" and r["fid"] == "3"]
    assert {r["capa_diseno"] for r in mixta} == {"POINTS", "LINES"} and len(mixta) == 4


def test_s048_rf18_los_vacios_y_las_curvas_se_ignoran_con_su_motivo() -> None:
    states = {int(r["fid"]): r["estado"] for r in rows_of("esperado.csv") if r["tabla"] == "raros"}
    assert states == {1: "OK", 2: "ignorado_vacia", 3: "ignorado_vacia", 4: "ignorado_vacia", 5: "ignorado_curva", 6: "ignorado_curva",
                      7: "ignorado_curva", 8: "OK"}
    con = connect((DATOS / "curvas-y-vacios-25830.gpkg").read_bytes())
    assert con.execute("SELECT geom FROM raros WHERE fid = 4").fetchone()[0] is None
    empty_point = decode(con.execute("SELECT geom FROM raros WHERE fid = 2").fetchone()[0])
    assert empty_point["empty"] and all(math.isnan(c) for c in empty_point["data"])
    assert decode(con.execute("SELECT geom FROM raros WHERE fid = 3").fetchone()[0])["empty"]


def test_s048_rf17_las_codificaciones_de_wkb_y_de_cabecera_se_leen_igual() -> None:
    con = connect((DATOS / "codificaciones-25830.gpkg").read_bytes())
    big = decode(con.execute("SELECT geom FROM wkb_big_endian WHERE fid = 1").fetchone()[0])
    ewkb = decode(con.execute("SELECT geom FROM ewkb_z WHERE fid = 1").fetchone()[0])
    header = decode(con.execute("SELECT geom FROM cabecera_big_endian WHERE fid = 1").fetchone()[0])
    assert not big["big_header"] and header["big_header"]
    assert ewkb["dims"] == "XYZ" and ewkb["data"][2] == 50.0
    assert big["data"] == header["data"]
    raw = con.execute("SELECT geom FROM wkb_big_endian WHERE fid = 1").fetchone()[0]
    assert raw[8 + 32] == 0  # el WKB empieza por el byte de orden: 0 = big endian
    ewkb_raw = con.execute("SELECT geom FROM ewkb_z WHERE fid = 1").fetchone()[0]
    assert struct.unpack_from("<I", ewkb_raw, 8 + 48 + 1)[0] & 0xE0000000 == 0xA0000000  # Z y SRID en las marcas de EWKB


def test_s048_rf19_las_alturas_se_conservan_y_m_no_cuenta() -> None:
    rows = rows_of("esperado.csv")
    z = [r for r in rows if r["tabla"] == "puntos_z"]
    assert z[0]["vertices_fuente"].endswith(" 646.377") and z[0]["vertices_proyecto"].endswith(" 646.377")
    assert all(not r["vertices_proyecto"].endswith(" _") for r in rows if r["tabla"] in ("lineas_z", "lineas_zm", "poligonos_z"))
    assert all(" _" in r["vertices_proyecto"] for r in rows if r["tabla"] in ("lineas_m", "vertices"))
    zm = decode(connect((DATOS / "altura-25830.gpkg").read_bytes()).execute("SELECT geom FROM lineas_zm").fetchone()[0])
    assert zm["dims"] == "XYZM" and len(zm["data"][0]) == 4


def test_s048_rf8_las_coordenadas_de_proyecto_coinciden_con_pyproj() -> None:
    to_project = {
        srs: Transformer.from_crs(f"EPSG:{src}", "EPSG:25830", always_xy=True)
        for srs, src in ((25829, 25829), (25830, 25830), (25831, 25831), (4258, 4258), (4326, 4258))
    }
    checked = 0
    for row in rows_of("esperado.csv"):
        if row["estado"] != "OK":
            continue
        srs = SRS_OF[(row["archivo"], row["tabla"])]
        if srs not in to_project:
            continue
        for source, project in zip(row["vertices_fuente"].split(";"), row["vertices_proyecto"].split(";")):
            x, y = (float(c) for c in source.split()[:2])
            e, n = to_project[srs].transform(x, y)
            pe, pn = (float(c) for c in project.split()[:2])
            assert abs(e - pe) < 1e-6 and abs(n - pn) < 1e-6, row
            checked += 1
    assert checked > 100


def test_s048_rf8_las_capas_en_ed50_y_en_canarias_se_calculan_con_la_rejilla_y_el_ambito() -> None:
    ed50 = [r for r in rows_of("esperado.csv") if r["tabla"] == "ed50_utm30"]
    assert len(ed50) == 3 and all(r["estado"] == "OK" for r in ed50)
    for row in ed50:
        sx, sy = (float(c) for c in row["vertices_fuente"].split()[:2])
        px, py = (float(c) for c in row["vertices_proyecto"].split()[:2])
        assert 200 < math.hypot(sx - px, sy - py) < 270  # ED50 → ETRS89 en Madrid desplaza unos 110 m al este y 210 m al norte
    assert {r["estado"] for r in rows_of("esperado.csv") if r["tabla"] == "canarias_utm28"} == {"fuera_de_ambito"}


def test_s048_rf9_las_capas_sin_sistema_disponible_no_tienen_elementos_esperados() -> None:
    systems = {r["tabla"]: r["sistema_app"] for r in rows_of("esperado-capas.csv")}
    assert systems["puntos"] in ("NO_DISPONIBLE", "ETRS89-UTM30", "ETRS89-GEOGRAFICAS")
    assert systems["indefinido_cartesiano"] == "SIN_SISTEMA" and systems["indefinido_geografico"] == "SIN_SISTEMA"
    assert systems["srs_inexistente"] == "NO_DECLARADO" and systems["utm33"] == "NO_DISPONIBLE"
    tables = {r["tabla"] for r in rows_of("esperado.csv")}
    assert not tables & {"indefinido_cartesiano", "indefinido_geografico", "srs_inexistente", "utm33"}
    con = connect((DATOS / "sin-sistema.gpkg").read_bytes())
    assert con.execute("SELECT count(*) FROM gpkg_spatial_ref_sys WHERE srs_id = 99999").fetchone()[0] == 0
    assert con.execute("SELECT count(*) FROM gpkg_geometry_columns WHERE srs_id = 99999").fetchone()[0] == 1


def test_s048_rf8_la_lista_de_sistemas_coincide_con_pyproj() -> None:
    for row in rows_of("esperado-crs.csv"):
        epsg = int(row["epsg"])
        if epsg <= 0:
            assert row["estado"] == "SIN_SISTEMA"
            continue
        crs = pyproj.CRS.from_epsg(epsg)
        assert row["unidad"] == {"metre": "m", "degree": "deg"}[crs.axis_info[0].unit_name], epsg
        assert (row["tipo"] == "GEOGRAFICAS") == crs.is_geographic, epsg
        zone = re.search(r"UTM zone (\d+)N", crs.name)
        if row["estado"] == "DISPONIBLE" and row["tipo"] == "UTM" and row["datum"] != "ED50":
            assert zone is None or int(zone.group(1)) == int(row["huso"]), epsg
        if row["datum"] == "ED50" and row["tipo"] == "UTM":
            assert int(zone.group(1)) == int(row["huso"])
        if row["estado"] == "DISPONIBLE":
            assert row["sistema_app"], epsg
    available = {int(r["epsg"]) for r in rows_of("esperado-crs.csv") if r["estado"] in ("DISPONIBLE", "WGS84")}
    assert {25829, 25830, 25831, 4258, 4082, 4083, 4081, 23029, 23030, 23031, 4326} == available


def test_s048_rf13_el_rtree_y_la_envolvente_coinciden_con_las_geometrias() -> None:
    checked = 0
    for file in FEATURE_FILES:
        for layer in stored_layers(file):
            con, table = layer["con"], layer["table"]
            index = {r[0]: r[1:] for r in con.execute(f'SELECT id, minx, maxx, miny, maxy FROM "rtree_{table}_geom"')} \
                if con.execute("SELECT count(*) FROM sqlite_master WHERE name = ?", (f"rtree_{table}_geom",)).fetchone()[0] else None
            has_envelope = {r["tabla"]: r["envolvente"] for r in rows_of("esperado-capas.csv") if r["archivo"] == file}[table] == "SI"
            for fid, blob in con.execute(f'SELECT fid, geom FROM "{table}"'):
                geom = decode(blob) if blob is not None else None
                if geom is None or geom["empty"]:
                    assert index is None or fid not in index
                    continue
                xs, ys = [], []

                def walk(kind, data):
                    if kind == "POINT":
                        xs.append(data[0]), ys.append(data[1])
                    elif kind in ("LINESTRING", "CIRCULARSTRING"):
                        for p in data:
                            xs.append(p[0]), ys.append(p[1])
                    elif kind == "POLYGON":
                        for ring in data:
                            for p in ring:
                                xs.append(p[0]), ys.append(p[1])
                    else:
                        for child_kind, _, child in data:
                            walk(child_kind, child)

                walk(geom["kind"], geom["data"])
                box = (min(xs), max(xs), min(ys), max(ys))
                if has_envelope:
                    assert tuple(geom["envelope"][:4]) == box, (file, table, fid)
                else:
                    assert geom["envelope"] == (), (file, table, fid)
                if index is not None:
                    got = index[fid]
                    assert all(abs(a - b) <= 1e-6 * max(1.0, abs(b)) for a, b in zip(got, box)), (file, table, fid)  # el R-tree guarda float32
                checked += 1
    assert checked > 60
