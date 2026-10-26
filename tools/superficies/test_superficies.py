"""Las referencias de las superficies LandXML están al día y salen de otro cálculo (S042)."""

from __future__ import annotations

import csv
import io
import math
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_superficies as gs  # noqa: E402
from pyproj import Transformer  # noqa: E402


def data_lines(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if line and not line.startswith("#"))


def rows_of(name: str) -> list[dict[str, str]]:
    text = (gs.OUT / name).read_text(encoding="utf-8")
    return list(csv.DictReader(io.StringIO(data_lines(text), newline="")))


def parse_surfaces(name: str) -> dict[str, tuple[dict[str, tuple[float, float, float]], list[tuple[str, str, str, bool]]]]:
    root = ElementTree.fromstring((gs.OUT / name).read_text(encoding="utf-8"))
    ns = {"l": "http://www.landxml.org/schema/LandXML-1.2"}
    out = {}
    for surface in root.findall(".//l:Surface", ns):
        points = {}
        for p in surface.findall(".//l:Pnts/l:P", ns):
            n, e, z = (float(v) for v in p.text.split())
            points[p.get("id")] = (e, n, z)
        faces = [(*f.text.split(), f.get("i") == "1") for f in surface.findall(".//l:Faces/l:F", ns)]
        out[surface.get("name")] = (points, faces)
    return out


def test_s042_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, build in gs.FILES.items():
        saved = (gs.OUT / name).read_text(encoding="utf-8")
        built = build()
        if name.endswith(".csv"):
            assert data_lines(saved) == data_lines(built), name
        else:
            assert saved == built, name


def test_s042_cada_punto_va_en_el_orden_norte_este_cota() -> None:
    points, _ = parse_surfaces("dos-superficies.xml")["terreno"]
    vertices, _ = gs.terrain()
    first = vertices[0]
    n, e, z = (float(v) for v in re.search(r'<P id="101">([^<]+)</P>', (gs.OUT / "dos-superficies.xml").read_text(encoding="utf-8")).group(1).split())
    assert abs(n - first.n) < 1e-5 and abs(e - first.e) < 1e-5 and abs(z - first.z) < 1e-5
    assert points["101"][0] > 400_000 and points["101"][1] > 4_000_000  # Este (6 cifras) y Norte (7 cifras) no se confunden


def test_s042_los_identificadores_no_son_consecutivos_y_hay_dos_triangulos_invisibles() -> None:
    surfaces = parse_surfaces("dos-superficies.xml")
    points, faces = surfaces["terreno"]
    ids = sorted(int(i) for i in points)
    assert ids[1] - ids[0] == gs.ID_STEP
    assert len(faces) == 2 * gs.NX * gs.NY
    assert sum(1 for f in faces if f[3]) == 2
    assert set(surfaces) == {"terreno", "rasante"}


def test_s042_la_cota_esperada_coincide_con_la_interpolacion_baricentrica() -> None:
    """Otro cálculo: coordenadas baricéntricas con los vértices leídos del propio XML."""
    points, faces = parse_surfaces("dos-superficies.xml")["terreno"]
    for row in rows_of("consultas.csv"):
        p = (float(row["este"]), float(row["norte"]))
        found = None
        for a, b, c, hidden in faces:
            if hidden:
                continue
            (ea, na, za), (eb, nb, zb), (ec, nc, zc) = points[a], points[b], points[c]
            det = (nb - nc) * (ea - ec) + (ec - eb) * (na - nc)
            w1 = ((nb - nc) * (p[0] - ec) + (ec - eb) * (p[1] - nc)) / det
            w2 = ((nc - na) * (p[0] - ec) + (ea - ec) * (p[1] - nc)) / det
            w3 = 1 - w1 - w2
            if min(w1, w2, w3) >= -1e-9:
                found = w1 * za + w2 * zb + w3 * zc
                break
        expected = row["cota"]
        if row["tipo"] in ("hueco", "fuera"):
            assert expected == "" and found is None, row
        else:
            assert expected != "" and found is not None, row
            assert abs(found - float(expected)) < 1e-4, row  # el XML guarda las cotas con 5 decimales
    kinds = {r["tipo"] for r in rows_of("consultas.csv")}
    assert kinds == {"dentro", "arista", "vertice", "hueco", "fuera"}


def test_s042_el_terreno_no_es_un_plano() -> None:
    zs = [float(r["cota"]) for r in rows_of("consultas.csv") if r["cota"]]
    assert max(zs) - min(zs) > 5.0
    v, _ = gs.terrain()
    a, b, c = v[0], v[1], v[gs.NX + 2]
    assert abs(gs.plane_height((v[10].e, v[10].n), a, b, c) - v[10].z) > 0.01


def test_s042_en_pies_es_el_mismo_terreno_en_pies_internacionales() -> None:
    metric, _ = parse_surfaces("dos-superficies.xml")["terreno"]
    feet, _ = parse_surfaces("en-pies.xml")["terreno"]
    assert set(metric) == set(feet)
    for key, (e, n, z) in metric.items():
        fe, fn, fz = feet[key]
        assert abs(fe * gs.FOOT_M - e) < 1e-5 and abs(fn * gs.FOOT_M - n) < 1e-5 and abs(fz * gs.FOOT_M - z) < 1e-5
    assert 'linearUnit="foot"' in (gs.OUT / "en-pies.xml").read_text(encoding="utf-8")


def test_s042_la_conversion_utm29_a_utm30_es_la_de_pyproj() -> None:
    forward = Transformer.from_crs("EPSG:25829", "EPSG:25830", always_xy=True)
    back = Transformer.from_crs("EPSG:25830", "EPSG:25829", always_xy=True)
    utm29, _ = parse_surfaces("en-utm29.xml")["terreno"]
    for row in rows_of("conversion.csv"):
        e30, n30 = forward.transform(float(row["este29"]), float(row["norte29"]))
        assert abs(e30 - float(row["este30"])) < 1e-6 and abs(n30 - float(row["norte30"])) < 1e-6
        e29, n29 = back.transform(float(row["este30"]), float(row["norte30"]))
        assert math.hypot(e29 - float(row["este29"]), n29 - float(row["norte29"])) < 1e-4
        file_e, file_n, _ = utm29[row["id"]]
        assert abs(file_e - float(row["este29"])) < 1e-5 and abs(file_n - float(row["norte29"])) < 1e-5


def test_s042_los_ficheros_que_no_se_importan() -> None:
    assert "sin-superficies" and "<Surface " not in (gs.OUT / "sin-superficies.xml").read_text(encoding="utf-8")
    assert 'surfType="grid"' in (gs.OUT / "malla.xml").read_text(encoding="utf-8")
    try:
        ElementTree.fromstring((gs.OUT / "mal-formado.xml").read_text(encoding="utf-8"))
    except ElementTree.ParseError:
        pass
    else:  # pragma: no cover
        raise AssertionError("mal-formado.xml debería no ser XML válido")
    texto, _ = parse_surfaces("ids-texto.xml")["texto"]
    assert set(texto) == {"A1", "B2", "C3", "D4"}
