"""Los documentos de capacidades de referencia están al día, el lector de referencia lee lo que dicen las reglas y las cajas salen de pyproj (S036)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

import pytest
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_wms as g  # noqa: E402

R = 6378137.0


def read(name: str) -> dict:
    return g.read_capabilities((g.OUT / name).read_bytes())


def by_name(data: dict) -> dict[str, dict]:
    return {layer["name"]: layer for layer in data["layers"]}


def test_los_ficheros_guardados_son_los_que_genera_el_script():
    for name, data in g.generate_all().items():
        assert (g.OUT / name).read_bytes() == data, name


def test_cada_documento_tiene_su_linea_de_servicio_en_lo_esperado():
    lines = (g.OUT / g.EXPECTED).read_text(encoding="utf-8").splitlines()
    services = {line.split("|")[1] for line in lines if line.startswith("service|")}
    docs = {p.name for p in g.OUT.iterdir() if p.is_file() and p.name.startswith(("caps-", "error-"))}
    assert services == docs


def test_1_3_0_basico_lleva_version_titulo_atribucion_formatos_y_capas():
    data = read("caps-1.3.0-basico.xml")
    assert data["state"] == "ok" and data["version"] == "1.3.0"
    assert data["title"] == "Servicio de ejemplo"
    assert data["attribution"] == "© Servicio de ejemplo"
    assert data["map_formats"] == ["image/png", "image/jpeg"]
    assert data["info_formats"] == ["text/plain", "text/html", "application/vnd.ogc.gml"]
    layers = by_name(data)
    assert [layer["name"] for layer in data["layers"]] == ["raiz", "rgb", "irg"]
    assert [layer["depth"] for layer in data["layers"]] == [0, 1, 1]
    # consultable y 3857 se heredan; `opaque="1"` quita la transparencia de la que lo declara
    assert layers["rgb"]["queryable"] and layers["rgb"]["code"] == "EPSG:3857" and layers["rgb"]["transparent"]
    assert not layers["irg"]["queryable"] and not layers["irg"]["transparent"]
    assert layers["raiz"]["abstract"] == "Resumen de la raíz"


def test_1_1_1_con_srs_separados_por_espacios_y_herencia():
    data = read("caps-1.1.1-basico.xml")
    assert data["version"] == "1.1.1"
    layers = by_name(data)
    assert layers["raiz"]["code"] == "EPSG:3857"
    assert layers["capa-a"]["queryable"] and layers["capa-a"]["code"] == "EPSG:3857"
    assert layers["capa-b"]["code"] == "EPSG:3857"  # los CRS se suman: sus 25830 no quitan los heredados
    assert data["attribution"] == ""


def test_el_doctype_con_subconjunto_interno_y_el_iso_8859_1_se_leen():
    raw = (g.OUT / "caps-1.1.1-doctype-interno.xml").read_bytes()
    assert b"iso-8859-1" in raw[:80] and b"<!ELEMENT VendorSpecificCapabilities EMPTY>" in raw
    assert "Cartografía".encode("iso-8859-1") in raw and "Cartografía".encode("utf-8") not in raw
    data = read("caps-1.1.1-doctype-interno.xml")
    assert data["title"] == "Cartografía catastral"
    assert data["info_formats"] == ["text/html", "text/xml"]
    assert data["layers"][0]["name"] == "Catastro" and data["layers"][0]["queryable"]


def test_un_prefijo_sin_declarar_no_impide_leer_el_documento():
    raw = (g.OUT / "caps-1.1.1-prefijo-sin-declarar.xml").read_bytes()
    assert b"xsi:type" in raw and b"xmlns:xsi" not in raw
    data = read("caps-1.1.1-prefijo-sin-declarar.xml")
    assert data["state"] == "ok" and data["layers"][0]["code"] == "EPSG:3857"


def test_diez_niveles_se_leen_todos_y_los_de_mas_de_diez_se_ignoran():
    assert [layer["depth"] for layer in read("caps-1.3.0-anidadas-10.xml")["layers"]] == list(range(10))
    deep = read("caps-1.3.0-anidadas-12.xml")["layers"]
    assert [layer["depth"] for layer in deep] == list(range(10))
    assert all(layer["code"] == "EPSG:3857" for layer in deep)  # el 3857 baja desde el primer nivel


def test_los_alias_de_web_mercator_y_el_3857_preferido():
    layers = by_name(read("caps-1.3.0-alias.xml"))
    assert layers["a-3785"]["code"] == "EPSG:3785"
    assert layers["a-102113"]["code"] == "EPSG:102113"
    assert layers["a-102100"]["code"] == "EPSG:900913"  # el primero que vale, en el orden del documento
    assert layers["a-900913"]["code"] == "EPSG:900913"
    assert layers["a-ambos"]["code"] == "EPSG:3857"
    assert layers["a-minus"]["code"] == "EPSG:3857"
    assert layers["a-25830"]["code"] == ""


def test_un_servicio_sin_3857_se_lee_pero_ninguna_capa_lo_tiene():
    data = read("caps-1.3.0-sin-3857.xml")
    assert data["state"] == "ok" and all(layer["code"] == "" for layer in data["layers"])


def test_las_capas_que_solo_agrupan_y_lo_que_heredan_sus_hijas():
    data = read("caps-1.3.0-sin-nombre.xml")
    assert [layer["name"] for layer in data["layers"]] == ["", "hija-1", "", "nieta"]
    assert [layer["code"] for layer in data["layers"]] == ["EPSG:3857"] * 4
    assert [layer["queryable"] for layer in data["layers"]] == [True, True, True, False]


def test_nombres_con_espacios_barras_verticales_numeros_repetidos_y_entidades():
    data = read("caps-1.3.0-nombres-raros.xml")
    names = [layer["name"] for layer in data["layers"]]
    assert names == [
        "OWS_0101_BCV05",
        "Usos del suelo | Usos del sòl | Land use",
        "0",
        "repetida",
        "repetida",
        "con & ampersand",
    ]
    last = data["layers"][-1]
    assert last["title"] == "Título con <etiqueta> y & más"


def test_sin_atribucion_y_sin_formatos_de_consulta():
    data = read("caps-1.3.0-sin-atribucion.xml")
    assert data["attribution"] == "" and data["info_formats"] == []


@pytest.mark.parametrize(
    ("name", "state"),
    [
        ("error-excepcion-1.3.0.xml", "SERVICE_EXCEPTION"),
        ("error-excepcion-1.1.1.xml", "SERVICE_EXCEPTION"),
        ("error-html-200.html", "MALFORMED"),
        ("error-xml-danado.xml", "MALFORMED"),
        ("error-vacio.xml", "MALFORMED"),
        ("error-otro-xml.xml", "NOT_CAPABILITIES"),
    ],
)
def test_lo_que_no_son_capacidades_se_clasifica(name, state):
    assert read(name)["state"] == state


def test_el_mensaje_de_una_excepcion_de_servicio_se_conserva():
    assert read("error-excepcion-1.3.0.xml")["message"] == "Version 1.3.0 not supported"
    assert read("error-excepcion-1.1.1.xml")["message"] == "Parcela no encontrada"


def test_las_cajas_de_pyproj_vuelven_a_los_grados_a_menos_de_un_nanogrado():
    back = Transformer.from_crs("EPSG:3857", "EPSG:4326", always_xy=True)
    for south, west, north, east, x0, y0, x1, y1 in g.boxes():
        lon0, lat0 = back.transform(x0, y0)
        lon1, lat1 = back.transform(x1, y1)
        assert abs(lat0 - south) < 1e-9 and abs(lon0 - west) < 1e-9
        assert abs(lat1 - north) < 1e-9 and abs(lon1 - east) < 1e-9


def test_las_cajas_coinciden_con_la_formula_de_mercator_esferico_a_menos_de_un_milimetro():
    for south, west, _north, east, x0, y0, x1, _y1 in g.boxes():
        assert abs(x0 - R * math.radians(west)) < 1e-3
        assert abs(x1 - R * math.radians(east)) < 1e-3
        assert abs(y0 - R * math.log(math.tan(math.pi / 4 + math.radians(south) / 2))) < 1e-3


def test_cada_caja_de_lo_esperado_tiene_ocho_numeros_y_una_zona_valida():
    lines = (g.OUT / g.BOXES).read_text(encoding="utf-8").splitlines()
    assert len(lines) == len(g.BOXES_DEG)
    for line in lines:
        parts = line.split("|")
        assert parts[0] == "caja" and len(parts) == 9
        south, west, north, east, x0, y0, x1, y1 = (float(p) for p in parts[1:])
        assert south < north and west < east and x0 < x1 and y0 < y1
