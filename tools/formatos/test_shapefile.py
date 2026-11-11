"""Los Shapefiles de ejemplo están al día, se leen con otro lector y lo esperado sale de otro cálculo (S030)."""

from __future__ import annotations

import csv
import io
import math
import re
import struct
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

import pyproj
import pytest
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_shapefile as s  # noqa: E402

DATOS = s.OUT
ALL = {**s.scenarios(), **s.name_scenarios()}
DESCRIBED = s.scenarios()


def rows_of(name: str) -> list[dict[str, str]]:
    lines = [line for line in (DATOS / name).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    return list(csv.DictReader(io.StringIO("\n".join(lines), newline="")))


def files_of(folder: str, layer: s.Layer) -> dict[str, bytes]:
    return {path.name: path.read_bytes() for path in (DATOS / folder).iterdir() if path.stem.lower() == layer.name.lower()}


def find(files: dict[str, bytes], extension: str) -> bytes | None:
    for name, data in files.items():
        if name.lower().endswith("." + extension):
            return data
    return None


# ---------- un lector aparte del formato (no usa nada del generador salvo los datos de entrada) ----------


def read_shp(data: bytes) -> tuple[int, list[dict]]:
    """(tipo de la cabecera, registros): número, tipo, partes, vértices con z y m, y el desplazamiento de cada registro."""
    code, = struct.unpack(">i", data[:4])
    length_words, = struct.unpack(">i", data[24:28])
    version, stype = struct.unpack("<ii", data[28:36])
    assert code == 9994 and version == 1000
    assert length_words * 2 == len(data)
    records, pos = [], 100
    while pos < len(data):
        number, words = struct.unpack(">ii", data[pos : pos + 8])
        content = data[pos + 8 : pos + 8 + words * 2]
        records.append({"number": number, "offset": pos // 2, "words": words, **parse_content(content)})
        pos += 8 + words * 2
    return stype, records


def parse_content(content: bytes) -> dict:
    kind, = struct.unpack("<i", content[:4])
    if kind == 0:
        return {"type": 0, "parts": [], "x": [], "y": [], "z": None, "m": None}
    if kind in (1, 11, 21):
        x, y = struct.unpack("<2d", content[4:20])
        z = m = None
        rest = content[20:]
        if kind == 11:
            z = [struct.unpack("<d", rest[:8])[0]]
            m = [struct.unpack("<d", rest[8:16])[0]] if len(rest) >= 16 else None
        elif kind == 21:
            m = [struct.unpack("<d", rest[:8])[0]]
        return {"type": kind, "parts": [0], "x": [x], "y": [y], "z": z, "m": m}
    if kind in (8, 18, 28):
        n, = struct.unpack("<i", content[36:40])
        pos, parts = 40, [0]
    else:
        parts_n, n = struct.unpack("<2i", content[36:44])
        parts = list(struct.unpack(f"<{parts_n}i", content[44 : 44 + 4 * parts_n]))
        pos = 44 + 4 * parts_n
        if kind == 31:
            pos += 4 * parts_n  # los tipos de parte
    xy = struct.unpack(f"<{2 * n}d", content[pos : pos + 16 * n])
    pos += 16 * n
    z = m = None
    has_z = kind in (13, 15, 18, 31)
    has_m = kind in (23, 25, 28)
    if has_z:
        z = list(struct.unpack(f"<{n}d", content[pos + 16 : pos + 16 + 8 * n]))
        pos += 16 + 8 * n
        if len(content) >= pos + 16 + 8 * n:
            m = list(struct.unpack(f"<{n}d", content[pos + 16 : pos + 16 + 8 * n]))
    elif has_m:
        m = list(struct.unpack(f"<{n}d", content[pos + 16 : pos + 16 + 8 * n]))
    return {"type": kind, "parts": parts, "x": list(xy[0::2]), "y": list(xy[1::2]), "z": z, "m": m}


def split_parts(record: dict) -> list[list[int]]:
    starts = record["parts"] + [len(record["x"])]
    return [list(range(a, b)) for a, b in zip(starts, starts[1:])]


def read_dbf(data: bytes, encoding: str) -> tuple[list[tuple[str, str, int, int]], list[tuple[bool, list[str]]]]:
    """(campos, registros): cada registro con su marca de borrado y los textos de sus campos, sin recortar."""
    count, header_length, record_length = struct.unpack("<IHH", data[4:12])
    fields, pos = [], 32
    while data[pos] != 0x0D:
        name = data[pos : pos + 11].split(b"\0")[0].decode("ascii")
        fields.append((name, chr(data[pos + 11]), data[pos + 16], data[pos + 17]))
        pos += 32
    assert pos + 1 == header_length
    rows = []
    for i in range(count):
        record = data[header_length + i * record_length : header_length + (i + 1) * record_length]
        offset, values = 1, []
        for _, _, length, _ in fields:
            values.append(record[offset : offset + length].decode(encoding))
            offset += length
        rows.append((record[0:1] == b"*", values))
    assert data[-1:] == b"\x1a"
    return fields, rows


# ---------- los ficheros ----------


def test_s030_rf2_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    """S030/RF-2: lo guardado en fixtures/formatos/shp/ es lo que escribe el script, fichero a fichero."""
    expected = s.generate()
    for name, content in expected.items():
        path = DATOS / name
        assert path.exists(), name
        if name == "zip/bomba.zip":
            with zipfile.ZipFile(path) as stored, zipfile.ZipFile(io.BytesIO(content)) as fresh:
                assert stored.namelist() == fresh.namelist()
                assert [i.file_size for i in stored.infolist()] == [i.file_size for i in fresh.infolist()]
        elif isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert path.read_text(encoding="utf-8") == content, name
    stored = {p.relative_to(DATOS).as_posix() for p in DATOS.rglob("*") if p.is_file() and not p.relative_to(DATOS).as_posix().startswith("qgis/")}
    assert stored == set(expected)


def test_s030_rf2_el_shp_se_reconoce_por_su_cabecera_y_no_por_su_extension() -> None:
    """S030/RF-2: un .shp guardado como .dat tiene la cabecera de Shapefile y su .dbf y su .prj tienen su mismo nombre."""
    data = (DATOS / "otra-extension" / "vertices.dat").read_bytes()
    assert struct.unpack(">i", data[:4])[0] == 9994 and struct.unpack("<i", data[28:32])[0] == 1000
    assert {p.name for p in (DATOS / "otra-extension").iterdir()} >= {"vertices.dat", "vertices.dbf", "vertices.prj", "vertices.cpg"}
    mixed = {p.name for p in (DATOS / "mayusculas").iterdir()}
    assert "PARCELAS.SHP" in mixed and "PARCELAS.Dbf" in mixed and "PARCELAS.PRJ" in mixed


def test_s030_rf6_ningun_fichero_de_ejemplo_se_acerca_al_limite_de_100_mb() -> None:
    """S030/RF-6: los ejemplos son pequeños; el límite de 100 MB se prueba con un fichero sintético en los tests de la app."""
    assert max(p.stat().st_size for p in DATOS.rglob("*") if p.is_file()) < 100_000


def test_s030_rf15_los_vertices_leidos_por_otro_lector_son_los_esperados() -> None:
    """S030/RF-15: el .shp leído con otro lector da los vértices de lo esperado, de cada elemento."""
    by_object: dict[tuple, list] = defaultdict(list)
    for row in rows_of("esperado.csv"):
        by_object[(row["escenario"], row["capa"], int(row["registro"]))].append(row)
    checked = 0
    for folder, layers in DESCRIBED.items():
        for layer in layers:
            files = files_of(folder, layer)
            stype, records = read_shp(find(files, "shp"))
            assert stype == layer.type_code
            assert len(records) == len(layer.feats)
            for record in records:
                rows = by_object.get((folder, layer.name, record["number"]))
                if not rows or rows[0]["estado"] != "OK":
                    continue
                dims = "Z" if record["z"] is not None else ""
                expected = [r["vertices_fuente"].split(";") for r in rows]
                got = []
                for part in split_parts(record) if record["type"] not in (8, 18, 28, 1, 11, 21) else [[i] for i in range(len(record["x"]))]:
                    got.append([f"{s.g.num(record['x'][i])} {s.g.num(record['y'][i])} {'_' if not dims else s.g.num(record['z'][i])}" for i in part])
                assert got == expected, (folder, layer.name, record["number"])
                checked += 1
    assert checked > 40


def test_s030_rf15_los_poligonos_estan_orientados_como_exige_el_formato() -> None:
    """S030/RF-15: el exterior de un polígono va en sentido horario y sus huecos en antihorario, y todos los anillos están cerrados."""
    layer = next(l for l in DESCRIBED["poligonos-25830"] if l.name == "parcelas")
    _, records = read_shp(find(files_of("poligonos-25830", layer), "shp"))
    holes = 0
    for record, feat in zip(records, layer.feats):
        for part, exterior in zip(split_parts(record), feat.shape.exterior):
            ring = [(record["x"][i], record["y"][i]) for i in part]
            assert ring[0] == ring[-1]
            signed = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:])) / 2
            assert (signed < 0) == exterior
            holes += not exterior
    assert holes == 2


def test_s030_rf15_los_multianillos_y_los_multipuntos_estan_en_los_ejemplos() -> None:
    """S030/RF-15: hay polígonos con varios anillos exteriores, multipuntos y líneas de varias partes."""
    _, manzanas = read_shp(find(files_of("poligonos-25830", DESCRIBED["poligonos-25830"][1]), "shp"))
    assert [len(split_parts(r)) for r in manzanas] == [3, 1]
    _, multi = read_shp(find(files_of("multipuntos-25830", DESCRIBED["multipuntos-25830"][0]), "shp"))
    assert [len(r["x"]) for r in multi] == [3, 1]
    _, ejes = read_shp(find(files_of("lineas-4258", DESCRIBED["lineas-4258"][0]), "shp"))
    assert [len(split_parts(r)) for r in ejes] == [1, 1, 2]


def test_s030_rf16_nulos_borrados_y_multipatch_se_ignoran_con_su_motivo() -> None:
    """S030/RF-16: lo esperado marca la forma nula, el MultiPatch y el registro borrado del .dbf como ignorados."""
    states = {(r["capa"], r["registro"]): r["estado"] for r in rows_of("esperado.csv") if r["escenario"] == "borrados-y-nulos"}
    assert states[("nulos", "2")] == "ignorado_vacia" and states[("nulos", "4")] == "ignorado_vacia"
    assert states[("nulos", "1")] == "OK"
    assert states[("multipatch", "1")] == "ignorado_multipatch" and states[("multipatch", "2")] == "ignorado_multipatch"
    assert states[("borrados", "2")] == "ignorado_borrado" and states[("borrados", "4")] == "ignorado_borrado"
    assert states[("borrados", "1")] == "OK"
    layer = next(l for l in DESCRIBED["borrados-y-nulos"] if l.name == "nulos")
    _, records = read_shp(find(files_of("borrados-y-nulos", layer), "shp"))
    assert [r["type"] for r in records] == [3, 0, 3, 0]
    _, rows = read_dbf(find(files_of("borrados-y-nulos", next(l for l in DESCRIBED["borrados-y-nulos"] if l.name == "borrados")), "dbf"), "utf-8")
    assert [deleted for deleted, _ in rows] == [False, True, False, True, False]
    patch = next(l for l in DESCRIBED["borrados-y-nulos"] if l.name == "multipatch")
    stype, _ = read_shp(find(files_of("borrados-y-nulos", patch), "shp"))
    assert stype == 31


def test_s030_rf16_un_vertice_fuera_del_ambito_descarta_el_objeto_entero() -> None:
    """S030/RF-16: en el ejemplo de ámbito, el punto de Italia queda fuera del ámbito del proyecto y los de Madrid entran."""
    states = {r["registro"]: r["estado"] for r in rows_of("esperado.csv") if r["escenario"] == "ambito"}
    assert states == {"1": "OK", "2": "fuera_de_ambito", "3": "OK"}


def test_s030_rf17_las_alturas_se_conservan_y_m_no_cuenta() -> None:
    """S030/RF-17: Z se conserva sin convertir y M se ignora; los tipos con Z se escriben con y sin M."""
    rows = [r for r in rows_of("esperado.csv") if r["escenario"] == "altura-25830"]
    by_layer = defaultdict(list)
    for row in rows:
        by_layer[row["capa"]].append(row)
    assert all("_" not in r["vertices_fuente"].replace("_ ", "") for r in by_layer["lineas_z"])
    assert all(v.endswith(" _") for r in by_layer["lineas_m"] for v in r["vertices_fuente"].split(";"))
    assert by_layer["puntos_z"][0]["vertices_fuente"].split()[2] == "646.377"
    assert by_layer["puntos_z"][1]["vertices_fuente"].split()[2] == "0.0"
    layers = {l.name: l for l in DESCRIBED["altura-25830"]}
    _, zonly = read_shp(find(files_of("altura-25830", layers["puntos_z"]), "shp"))
    _, zm = read_shp(find(files_of("altura-25830", layers["puntos_zm"]), "shp"))
    assert zonly[0]["m"] is None and zm[0]["m"] == [3.0]
    assert {l.type_code for l in layers.values()} == {11, 21, 13, 23, 15, 25, 18, 28}


def test_s030_rf19_el_shx_coincide_con_el_shp_salvo_el_roto_y_el_que_falta() -> None:
    """S030/RF-19: el .shx da la posición de cada registro; el de shx_roto no cuadra y sin_shx no existe."""
    layers = {l.name: l for l in DESCRIBED["incompletos"]}
    for name, layer in layers.items():
        files = files_of("incompletos", layer)
        shx = find(files, "shx")
        if name in ("sin_shx", "sin_shx_ni_dbf"):
            assert shx is None
            continue
        _, records = read_shp(find(files, "shp"))
        entries = [struct.unpack(">ii", shx[100 + 8 * i : 108 + 8 * i]) for i in range((len(shx) - 100) // 8)]
        matches = entries == [(r["offset"], r["words"]) for r in records]
        assert matches is (name != "shx_roto"), name


def test_s030_rf18_el_dbf_se_lee_en_la_codificacion_esperada() -> None:
    """S030/RF-18: cada .dbf se lee con la codificación que dice lo esperado y, sin .cpg, la que decide el contenido."""
    wanted = {r["capa"]: r for r in rows_of("esperado-capas.csv") if r["escenario"] == "codificaciones"}
    expected_text = {(r["capa"], r["registro"]): r["valor"] for r in rows_of("esperado-atributos.csv") if r["escenario"] == "codificaciones"}
    for layer in DESCRIBED["codificaciones"]:
        files = files_of("codificaciones", layer)
        row = wanted[layer.name]
        encoding = {"UTF-8": "utf-8", "WINDOWS-1252": "cp1252"}[row["codificacion"]]
        _, rows = read_dbf(find(files, "dbf"), encoding)
        for number, (_, values) in enumerate(rows, 1):
            assert values[0].rstrip() == expected_text[(layer.name, str(number))], layer.name
        cpg = find(files, "cpg")
        assert (cpg is None) == (layer.cpg is None)
    raw = find(files_of("codificaciones", next(l for l in DESCRIBED["codificaciones"] if l.name == "w1252_sin_cpg")), "dbf")
    with pytest.raises(UnicodeDecodeError):
        raw.decode("utf-8")
    assert wanted["cpg_desconocido"]["cpg_desconocido"] == "SI" and wanted["w1252_cpg"]["cpg_desconocido"] == "NO"


def test_s030_rf20_el_dbf_corto_tiene_menos_registros_que_el_shp() -> None:
    """S030/RF-20: el .dbf de dbf_corto tiene 3 registros para 5 objetos y el de dbf_largo 7."""
    capas = {r["capa"]: r for r in rows_of("esperado-capas.csv") if r["escenario"] == "incompletos"}
    assert (capas["dbf_corto"]["objetos"], capas["dbf_corto"]["registros_dbf"]) == ("5", "3")
    assert (capas["dbf_largo"]["objetos"], capas["dbf_largo"]["registros_dbf"]) == ("5", "7")
    assert capas["sin_dbf"]["registros_dbf"] == "" and capas["sin_dbf"]["columnas"] == ""
    layers = {l.name: l for l in DESCRIBED["incompletos"]}
    assert len(read_dbf(find(files_of("incompletos", layers["dbf_corto"]), "dbf"), "utf-8")[1]) == 3
    assert len(read_dbf(find(files_of("incompletos", layers["dbf_largo"]), "dbf"), "utf-8")[1]) == 7


def test_s030_rf21_los_atributos_leidos_por_otro_lector_son_los_esperados() -> None:
    """S030/RF-21: los valores del .dbf leídos con otro lector son los de esperado-atributos.csv, con su tipo."""
    expected = {(r["escenario"], r["capa"], int(r["registro"]), r["columna"]): r for r in rows_of("esperado-atributos.csv")}
    checked = 0
    for folder, layers in DESCRIBED.items():
        for layer in layers:
            data = find(files_of(folder, layer), "dbf")
            if data is None:
                continue
            encoding = "cp1252" if layer.encoding == "cp1252" else "utf-8"
            fields, rows = read_dbf(data, encoding)
            assert [(f[0], f[1]) for f in fields] == [(f.name, f.kind) for f in layer.fields]
            for number, (deleted, values) in enumerate(rows, 1):
                if deleted:
                    continue
                for (name, kind, length, decimals), text in zip(fields, values):
                    row = expected.get((folder, layer.name, number, name))
                    if row is None:
                        continue
                    text = text.strip()
                    if kind in ("N", "F"):
                        value = "" if not text else (s.g.num(float(text)) if decimals else str(int(text)))
                    elif kind == "D":
                        value = "" if not text else f"{text[:4]}-{text[4:6]}-{text[6:]}"
                    elif kind == "L":
                        value = {"T": "true", "F": "false"}.get(text, "")
                    else:
                        value = text
                    assert value == row["valor"], (folder, layer.name, number, name)
                    checked += 1
    assert checked > 100


def test_s030_rf8_las_coordenadas_de_proyecto_coinciden_con_pyproj() -> None:
    """S030/RF-8: las coordenadas en ETRS89 UTM 30 de lo esperado coinciden con pyproj a menos de 1 mm, salvo ED50 (con la rejilla)."""
    to_project = {
        srs: Transformer.from_crs(f"EPSG:{srs}", "EPSG:25830", always_xy=True)
        for srs in (25829, 25830, 25831, 4258, 4326, 4083)
    }
    srs_of = {(folder, layer.name): layer.srs for folder, layers in DESCRIBED.items() for layer in layers}
    compared = 0
    for row in rows_of("esperado.csv"):
        srs = srs_of[(row["escenario"], row["capa"])]
        if row["estado"] != "OK" or srs not in to_project:
            continue
        for source, project in zip(row["vertices_fuente"].split(";"), row["vertices_proyecto"].split(";")):
            x, y = (float(v) for v in source.split()[:2])
            e, n = (float(v) for v in project.split()[:2])
            ex, ny = to_project[srs].transform(x, y)
            assert math.hypot(e - ex, n - ny) < 1e-3, row
            compared += 1
    assert compared > 50


def test_s030_rf8_las_capas_en_ed50_se_calculan_con_la_rejilla() -> None:
    """S030/RF-8: ED50 UTM 30 en Madrid se desplaza unos 110 m al este y 210 m al norte respecto a ETRS89."""
    rows = [r for r in rows_of("esperado.csv") if r["capa"] == "ed50_utm30" and r["estado"] == "OK"]
    assert rows
    for row in rows:
        sx, sy = (float(v) for v in row["vertices_fuente"].split()[:2])
        px, py = (float(v) for v in row["vertices_proyecto"].split()[:2])
        assert 200 < math.hypot(sx - px, sy - py) < 270


def test_s030_rf9_las_capas_sin_sistema_disponible_no_tienen_elementos_esperados() -> None:
    """S030/RF-9: sin .prj, con un .prj ilegible o con un sistema que la app no ofrece, la capa no se importa hasta elegir uno."""
    capas = {(r["escenario"], r["capa"]): r for r in rows_of("esperado-capas.csv")}
    withs = {(r["escenario"], r["capa"]) for r in rows_of("esperado.csv")}
    for name in ("sin_prj", "prj_ilegible"):
        assert capas[("sistemas", name)]["sistema_app"] == "SIN_SISTEMA"
        assert ("sistemas", name) not in withs
    for name in ("utm33", "utm28_etrs89", "pies_2263"):
        assert capas[("sistemas", name)]["sistema_app"] == "NO_DISPONIBLE"
        assert ("sistemas", name) not in withs
    assert capas[("sistemas", "pies_2263")]["unidad"] == "US_survey_foot"
    for name in ("utm29", "utm31", "ed50_utm30", "canarias_utm28", "geograficas_4258", "wgs84_4326"):
        assert ("sistemas", name) in withs
    assert not (DATOS / "sistemas" / "sin_prj.prj").exists()
    assert not (DATOS / "sistemas" / "prj_ilegible.prj").read_text().startswith(("PROJCS", "GEOGCS"))


def test_s030_rf8_los_prj_se_leen_con_pyproj_como_el_sistema_esperado() -> None:
    """S030/RF-8: cada .prj de prj/ se lee con pyproj como el sistema de su EPSG, en los tres estilos de WKT."""
    rows = rows_of("esperado-prj.csv")
    assert {r["estilo"] for r in rows} == {"esri", "gdal", "gdal-sin-autoridad"}
    for row in rows:
        text = (DATOS / row["archivo"]).read_text(encoding="ascii")
        crs = pyproj.CRS.from_wkt(text)
        reference = pyproj.CRS.from_epsg(int(row["epsg"]))
        assert crs.is_projected == reference.is_projected, row["archivo"]
        assert math.isclose(crs.axis_info[0].unit_conversion_factor, reference.axis_info[0].unit_conversion_factor, rel_tol=1e-9), row["archivo"]
        assert text.count("[") == text.count("]")
        if row["estilo"] == "gdal":
            assert f'AUTHORITY["EPSG","{row["epsg"]}"]' in text.replace(" ", "")
        if row["estilo"] == "gdal-sin-autoridad":
            assert "AUTHORITY" not in text
        if row["estilo"] == "esri":
            assert text.startswith(("PROJCS", "GEOGCS"))
            assert row["nombre_en_el_prj"] == re.match(r'\w+\["([^"]*)"', text).group(1)


def test_s030_rf8_la_lista_de_sistemas_coincide_con_pyproj() -> None:
    """S030/RF-8: los sistemas de la app salen de EPSG, con su huso y su unidad, y los demás no están disponibles."""
    rows = rows_of("esperado-prj.csv")
    available = {int(r["epsg"]) for r in rows if r["estado"] in ("DISPONIBLE", "WGS84")}
    assert available == {25829, 25830, 25831, 4258, 4082, 4083, 4081, 23029, 23030, 23031, 4326}
    for row in rows:
        epsg = int(row["epsg"])
        crs = pyproj.CRS.from_epsg(epsg)
        if row["estado"] == "DISPONIBLE" and crs.is_projected and epsg not in (23029, 23030, 23031):
            zone = int(re.search(r"zone (\d+)N", crs.name).group(1))
            assert row["sistema_app"].endswith(f"UTM{zone}"), row
        assert row["unidad"] == ("deg" if crs.is_geographic else "m" if "metre" in crs.axis_info[0].unit_name else row["unidad"])


def test_s030_rf7_las_capas_del_escenario_tienen_nombre_tipo_y_objetos() -> None:
    """S030/RF-7: lo esperado lista cada capa con su tipo, su número de objetos y su sistema."""
    capas = {(r["escenario"], r["capa"]): r for r in rows_of("esperado-capas.csv")}
    assert capas[("puntos-25830", "vertices")]["tipo_geometria"] == "POINT" and capas[("puntos-25830", "vertices")]["objetos"] == "5"
    assert capas[("lineas-4258", "ejes")]["tipo_geometria"] == "LINESTRING"
    assert capas[("poligonos-25830", "parcelas")]["tipo_geometria"] == "POLYGON"
    assert capas[("multipuntos-25830", "multipuntos")]["tipo_geometria"] == "MULTIPOINT"
    assert capas[("puntos-25830", "vertices")]["sistema_app"] == "ETRS89-UTM30"
    assert capas[("lineas-4258", "ejes")]["unidad"] == "deg"
    assert capas[("puntos-25830", "vertices")]["columnas"] == "nombre:TEXT;cota:REAL;codigo:INTEGER;fecha:DATE;activo:BOOLEAN"


# ---------- ZIP y selecciones ----------


def classify(files: dict[str, bytes]) -> tuple[list[str], list[str], list[str]]:
    """(capas, huérfanos, descartados) de un conjunto de ficheros: lo que debe salir al abrirlos, calculado aparte de la app."""
    dropped = [n for n in files if n.startswith("/") or ".." in n.split("/")]
    kept = {n: d for n, d in files.items() if n not in dropped}

    def is_shp(data: bytes) -> bool:
        return len(data) >= 100 and struct.unpack(">i", data[:4])[0] == 9994 and struct.unpack("<i", data[28:32])[0] == 1000

    # El .shx tiene la misma cabecera que el .shp: por contenido se reconoce el .shp, pero la extensión .shx es la de su índice.
    stems = {n.rsplit(".", 1)[0] for n, d in kept.items() if is_shp(d) and not n.lower().endswith(".shx")}
    lowered = {stem.lower() for stem in stems}
    names = sorted(stems)
    short = [stem.rsplit("/", 1)[-1] for stem in names]
    layers = [stem if short.count(stem.rsplit("/", 1)[-1]) > 1 else stem.rsplit("/", 1)[-1] for stem in names]
    side = ("shx", "dbf", "prj", "cpg")
    orphans = [n.rsplit("/", 1)[-1] for n in kept if n.rsplit(".", 1)[-1].lower() in side and n.rsplit(".", 1)[0].lower() not in lowered]
    return sorted(layers), sorted(orphans), sorted(dropped)


def classify_zip(path: Path) -> tuple[str, list[str], list[str], list[str]]:
    data = path.read_bytes()
    if not data.startswith(b"PK"):
        return "NO_ES_SHAPEFILE", [], [], []
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            files = {i.filename: archive.read(i) for i in archive.infolist() if not i.is_dir()}
    except (zipfile.BadZipFile, EOFError):
        return "DANADO", [], [], []
    if not files:
        return "VACIO", [], [], []
    layers, orphans, dropped = classify(files)
    return ("OK" if layers else "SIN_SHP"), layers, (orphans if layers else []), dropped  # sin .shp es un error: no se listan los huérfanos


def test_s030_rf3_los_zip_se_abren_o_fallan_como_dice_lo_esperado() -> None:
    """S030/RF-3: cada ZIP de zip/ da el resultado, las capas, los huérfanos y los nombres descartados de esperado-zip.csv."""
    expected = {r["archivo"]: r for r in rows_of("esperado-zip.csv")}
    assert set(expected) == {f"zip/{p.name}" for p in (DATOS / "zip").iterdir()}
    for name, row in expected.items():
        result, layers, orphans, dropped = classify_zip(DATOS / name)
        if row["resultado"] == "TOO_LARGE":
            assert result == "SIN_SHP"  # el ZIP es legible: lo que lo rechaza es el límite de descompresión
            continue
        assert result == row["resultado"], name
        assert layers == [c for c in row["capas"].split(";") if c], name
        assert orphans == [c for c in row["huerfanos"].split(";") if c], name
        assert dropped == [c for c in row["descartados"].split(";") if c], name


def test_s030_rf6_el_zip_bomba_es_pequeno_y_descomprime_mucho() -> None:
    """S030/RF-6: bomba.zip pesa unos 20 kB y descomprime 20 MB, así que el límite se mide con los bytes leídos."""
    path = DATOS / "zip" / "bomba.zip"
    assert path.stat().st_size < 100_000
    with zipfile.ZipFile(path) as archive:
        assert sum(i.file_size for i in archive.infolist()) == s.BOMB_BYTES


def test_s030_rf5_los_ficheros_con_su_shp_y_los_huerfanos_se_distinguen() -> None:
    """S030/RF-5: en huerfanos.zip el .dbf, el .prj y el .shx sin .shp quedan fuera y vertices se asocia con sus cinco ficheros."""
    with zipfile.ZipFile(DATOS / "zip" / "huerfanos.zip") as archive:
        names = sorted(archive.namelist())
    assert {"vertices.shp", "vertices.shx", "vertices.dbf", "vertices.prj", "vertices.cpg", "solo.dbf", "solo.prj", "otro.shx"} == set(names)


def test_s030_rf4_las_selecciones_dan_lo_esperado() -> None:
    """S030/RF-4: un ZIP, ficheros sueltos o una mezcla dan el resultado de esperado-seleccion.csv."""
    for row in rows_of("esperado-seleccion.csv"):
        chosen = row["ficheros"].split(";")
        zips = [f for f in chosen if (DATOS / f).read_bytes().startswith(b"PK")]
        if len(zips) > 1 or (zips and len(chosen) > 1):
            result, layers, orphans = "MEZCLA", [], []
        elif zips:
            result, layers, orphans, _ = classify_zip(DATOS / zips[0])
        elif len(chosen) == 1 and not (DATOS / chosen[0]).read_bytes()[:4] == bytes.fromhex("0000270a"):
            result, layers, orphans = "NO_ES_SHAPEFILE", [], []
        else:
            files = {Path(f).name: (DATOS / f).read_bytes() for f in chosen}
            layers, orphans, _ = classify(files)
            result = "OK" if layers else "SIN_SHP"
            orphans = orphans if layers else []  # sin .shp es un error: no se listan los demás ficheros
        assert result == row["resultado"], row["id"]
        assert layers == [c for c in row["capas"].split(";") if c], row["id"]
        assert orphans == [c for c in row["huerfanos"].split(";") if c], row["id"]


# ---------- los Shapefiles escritos por QGIS ----------

QGIS = DATOS / "qgis"


def qgis_rows(name: str) -> list[dict[str, str]]:
    lines = [line for line in (QGIS / name).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    return list(csv.DictReader(io.StringIO("\n".join(lines), newline="")))


def test_s030_rf15_los_vertices_que_lee_otro_lector_son_los_que_lee_qgis() -> None:
    """S030/RF-15: los vértices del .shp escrito por QGIS, leídos con otro lector, son los que QGIS lee del mismo fichero."""
    expected: dict[tuple, dict[str, list[str]]] = defaultdict(dict)
    for row in qgis_rows("esperado-qgis-geometria.csv"):
        expected[(row["escenario"], row["capa"], int(row["registro"]))][row["parte"]] = row["vertices"].split(";")
    checked = 0
    for layer in qgis_rows("esperado-qgis-capas.csv"):
        _, records = read_shp((QGIS / layer["escenario"] / f"{layer['capa']}.shp").read_bytes())
        assert len(records) == int(layer["objetos"])
        for record in records:
            if record["type"] in (1, 11, 21, 8, 18, 28):
                parts = [[i] for i in range(len(record["x"]))]
            else:
                parts = split_parts(record)
            got = {
                str(index): [f"{s.g.num(record['x'][i])} {s.g.num(record['y'][i])} {s.g.num(record['z'][i]) if record['z'] else '_'}" for i in part]
                for index, part in enumerate(parts)
            }
            assert got == expected[(layer["escenario"], layer["capa"], record["number"])], layer
            checked += 1
    assert checked == 17


def test_s030_rf8_los_prj_de_qgis_se_leen_con_pyproj_como_el_sistema_que_reconoce_qgis() -> None:
    """S030/RF-8: cada .prj de QGIS es el sistema que QGIS reconoce, comprobado con pyproj (huso, unidad y datum)."""
    for layer in qgis_rows("esperado-qgis-capas.csv"):
        text = (QGIS / layer["escenario"] / f"{layer['capa']}.prj").read_text(encoding="ascii")
        assert text.startswith(("PROJCS", "GEOGCS")), layer
        assert "AUTHORITY" not in text  # QGIS escribe el .prj sin autoridad: el sistema se reconoce por sus parámetros
        crs = pyproj.CRS.from_wkt(text)
        reference = pyproj.CRS.from_user_input(layer["sistema_qgis"])
        assert crs.is_projected == reference.is_projected
        assert math.isclose(crs.axis_info[0].unit_conversion_factor, reference.axis_info[0].unit_conversion_factor, rel_tol=1e-9)
        assert math.isclose(crs.ellipsoid.semi_major_metre, reference.ellipsoid.semi_major_metre)
        assert math.isclose(crs.ellipsoid.inverse_flattening, reference.ellipsoid.inverse_flattening, rel_tol=1e-6)
        first = next(r for r in qgis_rows("esperado-qgis-geometria.csv") if (r["escenario"], r["capa"]) == (layer["escenario"], layer["capa"]))
        x, y = (float(v) for v in first["vertices"].split(";")[0].split()[:2])
        ours = Transformer.from_crs(crs, crs.geodetic_crs, always_xy=True).transform(x, y)
        theirs = Transformer.from_crs(reference, reference.geodetic_crs, always_xy=True).transform(x, y)
        assert math.hypot(ours[0] - theirs[0], ours[1] - theirs[1]) < 1e-9, layer  # la misma proyección: mismos grados


def test_s030_rf18_el_cpg_de_qgis_es_utf_8_o_1252_y_el_dbf_se_lee_con_el_que_dice() -> None:
    """S030/RF-18: QGIS escribe `UTF-8` o `1252` en el .cpg y el .dbf se lee con esa codificación, con las tildes y eñes bien."""
    capas = {(r["escenario"], r["capa"]): r for r in qgis_rows("esperado-qgis-capas.csv")}
    assert {r["cpg"] for r in capas.values()} == {"UTF-8", "1252"}
    attributes = {(r["escenario"], r["capa"], int(r["registro"]), r["columna"]): r["valor"] for r in qgis_rows("esperado-qgis-atributos.csv")}
    for (scenario, layer), row in capas.items():
        encoding = "utf-8" if row["cpg"] == "UTF-8" else "cp1252"
        _, rows = read_dbf((QGIS / scenario / f"{layer}.dbf").read_bytes(), encoding)
        fields, _ = read_dbf((QGIS / scenario / f"{layer}.dbf").read_bytes(), encoding)
        for number, (_, values) in enumerate(rows, 1):
            for (name, kind, length, decimals), text in zip(fields, values):
                if kind == "C":
                    assert text.rstrip() == attributes[(scenario, layer, number, name)], (scenario, layer, number, name)
    assert attributes[("windows-1252", "nombres", 1, "nombre")] == "Árbol ñandú"


def test_s030_rf21_los_tipos_de_campo_que_escribe_qgis_son_los_esperados() -> None:
    """S030/RF-21: QGIS escribe texto (C), número (N), fecha (D) y lógico (L), y el .dbf se lee con otro lector sin error."""
    kinds = set()
    for layer in qgis_rows("esperado-qgis-capas.csv"):
        fields, rows = read_dbf((QGIS / layer["escenario"] / f"{layer['capa']}.dbf").read_bytes(), "utf-8" if layer["cpg"] == "UTF-8" else "cp1252")
        kinds |= {kind for _, kind, _, _ in fields}
        assert len(rows) == int(layer["objetos"])
    assert kinds == {"C", "N", "D", "L"}


def test_s030_rf17_qgis_escribe_la_z_de_los_puntos_y_de_las_lineas() -> None:
    """S030/RF-17: los mojones (PointZ) y los ejes (PolyLineZ) de QGIS traen Z, y la M, si la hay, no cuenta."""
    capas = {(r["escenario"], r["capa"]): r for r in qgis_rows("esperado-qgis-capas.csv")}
    assert capas[("catastro-25830", "mojones")]["tipo_wkb"] == "PointZ" and capas[("geograficas-4258", "ejes")]["tipo_wkb"] == "MultiLineStringZ"
    _, mojones = read_shp((QGIS / "catastro-25830" / "mojones.shp").read_bytes())
    assert [r["z"] for r in mojones] == [[646.377], [647.125]]
    _, ejes = read_shp((QGIS / "geograficas-4258" / "ejes.shp").read_bytes())
    assert ejes[0]["z"] == [650.0, 651.0, 652.0]
