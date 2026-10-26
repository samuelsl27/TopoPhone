"""Los DXF de ejemplo están al día, se leen con otra herramienta y lo esperado sale de otro cálculo (S040)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_dxf as g  # noqa: E402
from pyproj import Transformer  # noqa: E402

DATOS = g.OUT
FOOT = 0.3048
US_FOOT = 1200 / 3937


def rows_of(name: str) -> list[dict[str, str]]:
    lines = [line for line in (DATOS / name).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    return list(csv.DictReader(io.StringIO("\n".join(lines), newline="")))


def elements(file: str) -> list[dict[str, str]]:
    return [row for row in rows_of("esperado.csv") if row["archivo"] == file]


def summary(file: str) -> dict[str, str]:
    return {row["clave"]: row["valor"] for row in rows_of("esperado-resumen.csv") if row["archivo"] == file}


def pairs(file: str) -> list[tuple[int, str]]:
    """El DXF leído como pares código/valor sin usar el escritor del script."""
    data = (DATOS / file).read_bytes()
    text = data.decode("utf-8" if file in ("r2010.dxf", "r2018.dxf", "capas.dxf") else "cp1252")
    lines = text.splitlines()
    return [(int(lines[i].strip()), lines[i + 1]) for i in range(0, len(lines) - 1, 2)]


def entity_types(file: str) -> list[str]:
    """Los tipos de la sección ENTITIES, en orden."""
    out, section = [], None
    items = pairs(file)
    for i, (code, value) in enumerate(items):
        if code == 0 and value == "SECTION":
            section = items[i + 1][1]
        elif code == 0 and value == "ENDSEC":
            section = None
        elif code == 0 and section == "ENTITIES":
            out.append(value)
    return out


def vertices(text: str) -> list[list[float | None]]:
    return [[None if c == "_" else float(c) for c in v.split()] for v in text.split(";") if v]


def test_s040_rf2_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in g.generate().items():
        path = DATOS / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert path.read_text(encoding="utf-8") == content, name


def test_s040_rf2_las_cuatro_versiones_y_sus_codificaciones() -> None:
    versions = {"r12.dxf": "AC1009", "r2000.dxf": "AC1015", "r2010.dxf": "AC1024", "r2018.dxf": "AC1032"}
    for file, version in versions.items():
        items = pairs(file)
        assert (9, "$ACADVER") in items and (1, version) in items, file
    r12, r2000, r2010, r2018 = (pairs(f) for f in versions)
    for banned in ("LWPOLYLINE", "SPLINE"):
        assert (0, banned) not in r12
        assert (0, banned) in r2000 and (0, banned) in r2018
    assert not any(code == 100 for code, _ in r12)  # el R12 no tiene marcas de subclase ni de manejadores
    assert not any(code == 5 for code, _ in r12)
    assert any(code == 100 for code, _ in r2000) and any(code == 5 for code, _ in r2000)
    raw = {f: (DATOS / f).read_bytes() for f in versions}
    assert raw["r12.dxf"].count(b"\r\n") > 50 and raw["r2010.dxf"].count(b"\r") == 0
    assert raw["r2018.dxf"].startswith(b"999\n")  # un comentario antes de la primera sección
    # La ñ de la capa: un solo byte en Windows-1252 (R12 y R2000) y dos en UTF-8 (R2007 en adelante).
    assert b"Acera \xf1and\xfa" in raw["r12.dxf"] and b"Acera \xf1and\xfa" in raw["r2000.dxf"]
    assert "Acera ñandú".encode() in raw["r2010.dxf"] and "Acera ñandú".encode() in raw["r2018.dxf"]
    for f in ("r12.dxf", "r2000.dxf"):
        try:
            raw[f].decode("utf-8")
        except UnicodeDecodeError:
            pass
        else:
            raise AssertionError(f"{f} también es UTF-8 válido: no sirve para probar la página de códigos")


def test_s040_rf7_la_unidad_declarada_en_la_cabecera() -> None:
    assert (9, "$INSUNITS") not in pairs("r12.dxf")
    for file, code in (("r2000.dxf", "6"), ("origen-etrs89-utm30-pies.dxf", "2"), ("origen-etrs89-utm30-pies-us.dxf", "21")):
        items = pairs(file)
        assert items[items.index((9, "$INSUNITS")) + 1] == (70, code), file


def test_s040_rf3_rf4_los_ficheros_que_no_son_dxf() -> None:
    dwg = (DATOS / "dibujo.dwg").read_bytes()
    assert dwg[:4] == b"AC10" and b"\x00" in dwg[:64] and (DATOS / "dwg-como-dxf.dxf").read_bytes()[:4] == b"AC10"
    assert (DATOS / "binario.dxf").read_bytes().startswith(b"AutoCAD Binary DXF\r\n\x1a\x00")
    assert (DATOS / "vacio.dxf").stat().st_size == 0
    assert b"SECTION" not in (DATOS / "texto.dxf").read_bytes()
    kinds = {row["archivo"]: row["valor"] for row in rows_of("esperado-resumen.csv") if row["clave"] == "tipo_fichero"}
    assert {kinds[f] for f in ("dibujo.dwg", "dwg-como-dxf.dxf")} == {"DWG"}
    assert (kinds["binario.dxf"], kinds["texto.dxf"], kinds["vacio.dxf"]) == ("BINARIO", "NO_DXF", "VACIO")


def test_s040_rf14_los_tipos_de_entidad_de_cada_version() -> None:
    assert sorted(entity_types("r12.dxf")) == sorted(
        ["POINT", "POINT", "LINE", "LINE", "POLYLINE", "SEQEND", "POLYLINE", "SEQEND", "ARC", "CIRCLE", "INSERT"]
        + ["VERTEX"] * 7
    )
    for file in ("r2000.dxf", "r2010.dxf", "r2018.dxf"):
        types = entity_types(file)
        assert types.count("LWPOLYLINE") == 2 and types.count("SPLINE") == 2 and types.count("CIRCLE") == 2, file
        assert types.count("ARC") == 1 and types.count("INSERT") == 1 and types.count("POLYLINE") == 1, file
    # Mismo contenido en las tres versiones modernas: los mismos elementos esperados.
    assert len(elements("r2000.dxf")) == len(elements("r2010.dxf")) == len(elements("r2018.dxf")) == 15


def test_s040_rf14_los_bloques_desplegados_dan_lo_que_se_calcula_a_mano() -> None:
    rows = elements("bloques.dxf")
    lines = [vertices(r["vertices"]) for r in rows if r["tipo_elemento"] == "LINE"]
    # CONJUNTO (escala 2, giro 90°): el lado (0,0)-(1,0) pasa a (0,0)-(0,2) y el (1,0)-(1,1) a (0,2)-(-2,2).
    assert [[440000.0, 4474000.0, None, None], [440000.0, 4474002.0, None, None]] in lines
    assert [[440000.0, 4474002.0, None, None], [439998.0, 4474002.0, None, None]] in lines
    # UNIDAD con escala X negativa en (440050, 4474000): (0,0)-(3,1) pasa a (0,0)-(-3,1).
    assert [[440050.0, 4474000.0, None, None], [440047.0, 4474001.0, None, None]] in lines
    # MINSERT de 2 columnas y 3 filas, a 30°: seis puntos de inserción, el segundo a (5, 0) girado 30°.
    grid = [vertices(r["vertices"])[0] for r in rows if r["tipo_elemento"] == "BLOCK_INSERT" and r["con_z"] == "SI"]
    assert len(grid) == 6
    assert abs(grid[1][0] - (440100 + 5 * math.cos(math.radians(30)))) < 1e-9
    assert abs(grid[1][1] - (4474000 + 5 * math.sin(math.radians(30)))) < 1e-9
    assert all(p[2] == 10.0 for p in grid)
    # El ciclo CICLO_A → CICLO_B → CICLO_A corta en el primer ciclo.
    assert summary("bloques.dxf")["descartados.ciclo_de_bloques"] == "1"


def test_s040_rf24_rf25_rf33_colores_y_estado_de_las_capas() -> None:
    layers = {(r["capa"], r["tipo_capa"]): r for r in rows_of("esperado-capas.csv") if r["archivo"] == "capas.dxf"}
    assert layers[("Apagada", "LINES")]["visible"] == "NO" and layers[("Congelada", "LINES")]["visible"] == "NO"
    assert layers[("Roja", "LINES")]["visible"] == "SI"
    assert layers[("Blanca", "LINES")]["color"] == "AUTO"
    assert layers[("Verdadera", "LINES")]["color"] == "FF336699"
    assert layers[("Naranja", "LINES")]["color"] == "FFFF7F00"
    items = pairs("capas.dxf")
    assert items[items.index((2, "Apagada")) + 2] == (62, "-3")  # apagada: color negativo
    assert items[items.index((2, "Congelada")) + 1] == (70, "1")  # congelada: bit 1
    colors = [r["color"] for r in elements("capas.dxf") if r["tipo_elemento"] == "LINE"]
    assert "FFFFFF00" in colors  # color 2 propio sobre la capa roja
    assert "FF00AA55" in colors  # color verdadero propio
    assert "FF333333" in colors  # ACI 250
    assert colors.count("AUTO") == 2  # la capa blanca y la línea con color verdadero FFFFFF


def test_s040_rf14_rf24_la_capa_cero_y_byblock_en_un_bloque() -> None:
    rows = elements("bloques.dxf")
    first = [r for r in rows if r["tipo_elemento"] == "LINE"][:2]
    assert (first[0]["capa"], first[0]["color"]) == ("Mobiliario", "FFFF0000")  # BYBLOCK: el color del INSERT
    assert (first[1]["capa"], first[1]["color"]) == ("Mobiliario", "FFFFFF00")  # BYLAYER sobre la capa 0: la capa del INSERT


def test_s040_rf21_rf22_rf23_cotas() -> None:
    rows = elements("cotas.dxf")
    assert [r["con_z"] for r in rows] == ["SI", "NO", "NO", "SI", "SI", "NO", "SI"]
    lines = [(code, value) for code, value in pairs("cotas.dxf")]
    # Los LINE y POINT sin Z no llevan el código 30 ni el 31.
    assert lines.count((0, "LINE")) == 2 and lines.count((31, "102.0")) == 1
    assert summary("cotas.dxf")["todo_z_cero"] == "NO"
    zeros = summary("todo-z-cero.dxf")
    assert zeros["todo_z_cero"] == "SI" and all(r["con_z"] == "SI" for r in elements("todo-z-cero.dxf"))
    assert all(summary(f)["todo_z_cero"] == "NO" for f in ("r2000.dxf", "bloques.dxf", "capas.dxf"))


def test_s040_rf16_los_ignorados_son_los_de_la_cabecera_del_fichero() -> None:
    types = entity_types("ignorados.dxf")
    ignored = {k.removeprefix("ignorados."): int(v) for k, v in summary("ignorados.dxf").items() if k.startswith("ignorados.")}
    for name, count in ignored.items():
        assert types.count(name) == count, name
    assert sum(ignored.values()) == len(types) - types.count("LINE") - types.count("POINT")
    assert summary("ignorados.dxf")["elementos.LINE"] == "1" and summary("ignorados.dxf")["elementos.POINT"] == "1"


def test_s040_rf13_el_fichero_cortado_y_el_elemento_roto() -> None:
    cut = (DATOS / "cortado.dxf").read_bytes()
    assert not cut.endswith(b"\n") and b"EOF" not in cut and cut.count(b"LINE") == 4  # tres enteros y el cuarto a medias
    assert len(elements("cortado.dxf")) == 3 and summary("cortado.dxf")["completo"] == "NO"
    broken = (DATOS / "elemento-roto.dxf").read_text(encoding="cp1252")
    assert broken.count("\nabc\n") == 1 and broken.rstrip().endswith("EOF")
    assert len(elements("elemento-roto.dxf")) == 4 and summary("elemento-roto.dxf")["descartados.valor_no_numerico"] == "1"
    assert elements("sin-elementos.dxf") == []


def test_s040_rf17_el_vertice_fuera_de_ambito_no_cae_en_ninguna_zona_valida() -> None:
    lat = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True).transform(100.0, 100.0)[1]
    assert not 35.0 <= lat <= 44.5
    assert summary("fuera-de-ambito.dxf")["descartados.fuera_de_ambito"] == "2"
    assert len(elements("fuera-de-ambito.dxf")) == 3


def test_s040_rf6_otro_huso_es_lo_que_da_pyproj_de_lo_escrito() -> None:
    to30 = Transformer.from_crs("EPSG:25829", "EPSG:25830", always_xy=True)
    rows = [r for r in rows_of("esperado-conversion.csv") if r["archivo"] == "origen-etrs89-utm29.dxf"]
    assert len(rows) == 4
    for r in rows:
        e, n = to30.transform(float(r["fuente_x"]), float(r["fuente_y"]))
        assert abs(e - float(r["proyecto_e"])) < 1e-6 and abs(n - float(r["proyecto_n"])) < 1e-6, r


def test_s040_rf6_ed50_se_desplaza_unos_cientos_de_metros_y_cuadra_con_la_transformacion_aproximada_de_epsg() -> None:
    approx = Transformer.from_crs("EPSG:23030", "EPSG:25830", always_xy=True)
    for r in (r for r in rows_of("esperado-conversion.csv") if r["archivo"] == "origen-ed50-utm30.dxf"):
        e, n = approx.transform(float(r["fuente_x"]), float(r["fuente_y"]))
        shift = math.hypot(float(r["proyecto_e"]) - float(r["fuente_x"]), float(r["proyecto_n"]) - float(r["fuente_y"]))
        assert 50 < shift < 300, r
        assert math.hypot(e - float(r["proyecto_e"]), n - float(r["proyecto_n"])) < 5.0, r  # la rejilla mejora a Helmert, no la contradice


def test_s040_rf7_los_pies_dan_los_mismos_metros_que_los_metros() -> None:
    meters = [r for r in rows_of("esperado-conversion.csv") if r["archivo"] == "origen-etrs89-utm30-pies.dxf"]
    base = {(r["vertice"]): r for r in rows_of("esperado-conversion.csv") if r["archivo"] == "origen-ed50-utm30.dxf"}
    assert len(meters) == 4 and base
    for file, factor in (("origen-etrs89-utm30-pies.dxf", FOOT), ("origen-etrs89-utm30-pies-us.dxf", US_FOOT)):
        for r in (r for r in rows_of("esperado-conversion.csv") if r["archivo"] == file):
            assert abs(float(r["fuente_x"]) * factor - float(r["proyecto_e"])) < 2e-3, r  # el fichero va a 0,1 mm de pie
            assert abs(float(r["fuente_y"]) * factor - float(r["proyecto_n"])) < 2e-3, r
            assert abs(float(r["fuente_z"]) * factor - float(r["proyecto_z"])) < 1e-9, r
    assert US_FOOT != FOOT and abs(US_FOOT - FOOT) > 5e-7  # no es el mismo pie


def test_s040_rf15_las_curvas_exactas() -> None:
    arc = next(r for r in elements("r2000.dxf") if r["tipo_elemento"] == "ARC")
    parts = arc["curva"].split()
    assert parts[0] == "ARC" and float(parts[4]) == 120.0 and (float(parts[5]), float(parts[6])) == (20.0, 140.0)
    tiny = [r for r in elements("r2000.dxf") if r["tipo_elemento"] == "CIRCLE" and r["curva"].split()[4] == "0.005"]
    assert len(tiny) == 1  # un círculo de radio menor que la tolerancia de 1 cm
    # La spline: empieza y acaba en los extremos de su polígono de control y no sale de él.
    splines = [r for r in elements("r2000.dxf") if r["tipo_elemento"] == "SPLINE"]
    assert len(splines) == 2
    for s in splines:
        dense = [tuple(map(float, p.split())) for p in s["densa"].split(";")]
        ctrl = vertices(s["vertices"])
        assert len(dense) == 401
        assert math.hypot(dense[0][0] - ctrl[0][0], dense[0][1] - ctrl[0][1]) < 1e-9
        assert math.hypot(dense[-1][0] - ctrl[-1][0], dense[-1][1] - ctrl[-1][1]) < 1e-6
        assert min(p[0] for p in dense) >= min(c[0] for c in ctrl) - 1e-9 and max(p[1] for p in dense) <= max(c[1] for c in ctrl) + 1e-9
    assert splines[0]["curva"].endswith("polynomial") and splines[1]["curva"].endswith("rational")


def test_s040_rf15_de_boor_coincide_con_una_curva_de_bezier_calculada_a_mano() -> None:
    ctrl = [(0.0, 0.0, 0.0), (10.0, 30.0, 0.0), (30.0, 30.0, 0.0), (40.0, 0.0, 0.0)]
    points = g.spline_points(ctrl, [0, 0, 0, 0, 1, 1, 1, 1], 3, None, samples=20)
    for i, (x, y, _) in enumerate(points):
        t = i / 20
        b = [(1 - t) ** 3, 3 * t * (1 - t) ** 2, 3 * t * t * (1 - t), t**3]
        assert abs(x - sum(w * c[0] for w, c in zip(b, ctrl))) < 1e-9
        assert abs(y - sum(w * c[1] for w, c in zip(b, ctrl))) < 1e-9
    # Con pesos, un arco de círculo de un cuarto: la NURBS racional da puntos a distancia 1 del centro.
    w = math.sqrt(2) / 2
    quarter = g.spline_points([(1.0, 0.0, 0.0), (1.0, 1.0, 0.0), (0.0, 1.0, 0.0)], [0, 0, 0, 1, 1, 1], 2, [1.0, w, 1.0], samples=20)
    assert all(abs(math.hypot(x, y) - 1.0) < 1e-12 for x, y, _ in quarter)


def test_s040_rf14_la_extrusion_invierte_el_este() -> None:
    mirrored = elements("extrusion-espejo.dxf")
    arc = next(r for r in mirrored if r["tipo_elemento"] == "ARC")
    assert arc["curva"].split()[1:3] == ["440020.0", "4474020.0"]  # el centro escrito era -440020
    assert "-440020.0" in (DATOS / "extrusion-espejo.dxf").read_text(encoding="cp1252")
    poly = next(r for r in mirrored if r["tipo_elemento"] == "POLYLINE")
    assert vertices(poly["vertices"])[0][:2] == [440010.0, 4474000.0]
    assert g.ocs_to_wcs((1.0, 2.0, 3.0), (0.0, 0.0, -1.0)) == (-1.0, 2.0, -3.0)
    assert g.ocs_to_wcs((1.0, 2.0, 3.0), (0.0, 0.0, 1.0)) == (1.0, 2.0, 3.0)
