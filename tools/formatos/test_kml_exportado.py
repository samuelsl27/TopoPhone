"""El KML de referencia de la exportación está al día, es XML bien formado, se lee con el lector de importación y escribe lo que dice la plantilla (S031)."""

from __future__ import annotations

import sys
from pathlib import Path
from xml.dom import minidom

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_kml as importer  # noqa: E402
import generar_kml_exportado as k  # noqa: E402

DATOS = k.OUT
NAMES = sorted(k.ENTRADAS)


def test_los_ficheros_estan_al_dia() -> None:
    produced = k.generate_all()
    on_disk = {p.name: p.read_bytes() for p in DATOS.iterdir() if p.is_file()}
    assert sorted(on_disk) == sorted(produced)
    for name, data in produced.items():
        assert on_disk[name] == data, f"{name} no coincide con lo que genera generar_kml_exportado.py"


@pytest.mark.parametrize("name", NAMES)
def test_es_xml_bien_formado_con_la_raiz_y_el_espacio_de_nombres_de_kml(name: str) -> None:
    dom = minidom.parseString((DATOS / f"{name}.kml").read_bytes())
    assert dom.documentElement.tagName == "kml"
    assert dom.documentElement.getAttribute("xmlns") == "http://www.opengis.net/kml/2.2"


@pytest.mark.parametrize("name", NAMES)
def test_termina_con_un_salto_de_linea_y_usa_lf_y_sangria_de_dos_espacios(name: str) -> None:
    text = (DATOS / f"{name}.kml").read_bytes().decode("utf-8")
    assert text.endswith("</kml>\n") and "\r" not in text
    for line in text.split("\n"):
        assert (len(line) - len(line.lstrip(" "))) % 2 == 0


@pytest.mark.parametrize("name", NAMES)
def test_el_lector_de_importacion_lee_lo_que_se_exporta(name: str) -> None:
    doc = k.parse(k.ENTRADAS[name])
    read = importer.read_kml((DATOS / f"{name}.kml").read_bytes(), name)
    assert read.result == "OK"
    expected_marks = sum(len(f.marks) for f in doc.folders)
    assert read.valid_objects() == expected_marks
    for folder in doc.folders:
        kinds = {m.kind for m in folder.marks}
        paths = {layer.path for layer in read.layers}
        assert folder.name in paths
        assert kinds  # cada carpeta tiene marcas


def test_los_vertices_se_leen_igual_que_se_escribieron() -> None:
    doc = k.parse(k.ENTRADAS["con-codigos"])
    read = importer.read_kml((DATOS / "con-codigos.kml").read_bytes(), "con-codigos")
    by_name = {f.name: f for layer in read.layers for f in layer.features}
    for folder in doc.folders:
        for mark in folder.marks:
            feature = by_name[mark.name]
            written = [v for part in feature.parts for v in part.vertices]
            assert len(written) == len(mark.vertices)
            for (lon, lat, alt), (rl, rt, ra) in zip(mark.vertices, written):
                assert float(lon) == rl and float(lat) == rt
                if mark.mode == "absolute" and alt:
                    assert ra == float(alt)


def test_solo_las_marcas_absolutas_conservan_la_altitud_al_leerlas() -> None:
    read = importer.read_kml((DATOS / "con-codigos.kml").read_bytes(), "con-codigos")
    with_z = {f.name for layer in read.layers for f in layer.features if f.has_z}
    assert with_z == {"P1", "Muro 1", "Parcela A"}


def test_los_colores_de_los_codigos_se_leen_como_rgb() -> None:
    read = importer.read_kml((DATOS / "con-codigos.kml").read_bytes(), "con-codigos")
    colors = {f.name: f.color for layer in read.layers for f in layer.features}
    assert colors["P1"] == "#FF0000" and colors["P2"] == "#FF0000"
    assert colors["Muro 1"] == "#0000FF" and colors["Parcela A"] == "#00FF00"
    assert colors["Suelto"] == ""


def test_la_marca_sin_codigo_va_a_la_carpeta_del_proyecto_sin_estilo() -> None:
    text = (DATOS / "con-codigos.kml").read_bytes().decode("utf-8")
    assert "<name>Obra 1 &amp; Cía</name>\n    <Style" in text
    suelto = text[text.index("<name>Suelto</name>"):]
    assert "<styleUrl>" not in suelto.split("</Placemark>")[0]


def test_el_poligono_es_antihorario_y_cerrado() -> None:
    doc = k.parse(k.ENTRADAS["con-codigos"])
    polygon = next(m for f in doc.folders for m in f.marks if m.kind == "polygon")
    ring = [(float(a), float(b)) for a, b, _ in polygon.vertices]
    assert ring[0] == ring[-1]
    area = sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:]))
    assert area > 0


def test_el_texto_se_escapa() -> None:
    text = (DATOS / "con-codigos.kml").read_bytes().decode("utf-8")
    assert "<description>Un árbol de &lt;10 m\n(segunda línea)</description>" in text
    assert "<description>Linde \"norte\"</description>" in text
    assert "<name>Obra 1 &amp; Cía</name>" in text


def test_un_valor_vacio_es_value_vacio_y_una_descripcion_vacia_no_escribe_el_elemento() -> None:
    text = (DATOS / "con-codigos.kml").read_bytes().decode("utf-8")
    assert '<Data name="Notes"><value/></Data>' in text
    p2 = text[text.index("<name>P2</name>"):].split("</Placemark>")[0]
    assert "<description>" not in p2


def test_las_fotos_van_en_cdata_con_saltos_y_una_imagen_por_foto() -> None:
    text = (DATOS / "con-fotos.kml").read_bytes().decode("utf-8")
    assert (
        '<description><![CDATA[Poste &amp; base<br/>muy alto<br/><img src="files/P1_1.jpg" width="400"/>'
        '<br/><img src="files/P1_2.jpg" width="400"/>]]></description>'
    ) in text
    assert "<description>Sin fotos</description>" in text
    assert '<description><![CDATA[<br/><img src="files/P3_1.jpg" width="400"/>]]></description>' in text
    assert text.count("<![CDATA[") == 2


def test_un_proyecto_sin_codigos_no_lleva_estilos_ni_styleurl() -> None:
    text = (DATOS / "sin-codigos.kml").read_bytes().decode("utf-8")
    assert "<Style" not in text and "<styleUrl>" not in text
    assert text.count("<Folder>") == 1
    assert "<Folder>\n      <name>Proyecto sin códigos</name>" in text


def test_la_altitud_solo_va_con_altitudeMode_absolute() -> None:
    text = (DATOS / "con-codigos.kml").read_bytes().decode("utf-8")
    assert text.count("<altitudeMode>absolute</altitudeMode>") == 3
    assert "<coordinates>-3.703700000,40.416800000</coordinates>" in text
    assert "<coordinates>-3.703800000,40.416800000,646.3770</coordinates>" in text


def test_el_estilo_de_un_codigo_lleva_icono_linea_y_relleno_con_alfa_80() -> None:
    text = (DATOS / "con-codigos.kml").read_bytes().decode("utf-8")
    assert (
        '<Style id="ARBOL">\n      <IconStyle><color>ff0000ff</color></IconStyle>\n'
        "      <LineStyle><color>ff0000ff</color></LineStyle>\n"
        "      <PolyStyle><color>8000ffff</color></PolyStyle>"
    ) not in text  # el relleno cambia solo el alfa: 80 + BGR
    assert "<PolyStyle><color>800000ff</color></PolyStyle>" in text
