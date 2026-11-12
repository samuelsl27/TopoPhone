"""Los KML y KMZ de ejemplo están al día, se leen con otro lector y lo esperado sale de otro cálculo (S031)."""

from __future__ import annotations

import csv
import io
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path
from xml.dom import minidom

import pytest
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_kml as k  # noqa: E402

DATOS = k.OUT
FILES = k.all_files()
KML_TO_UTM30 = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
KML_TO_UTM28 = Transformer.from_crs("EPSG:4081", "EPSG:4083", always_xy=True)


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((DATOS / name).read_text(encoding="utf-8"), newline="")))


def test_los_ficheros_estan_al_dia() -> None:
    produced = k.generate_all()
    on_disk = {p.name: p.read_bytes() for p in DATOS.iterdir() if p.is_file()}
    assert sorted(on_disk) == sorted(produced)
    for name, data in produced.items():
        assert on_disk[name] == data, f"{name} no coincide con lo que genera generar_kml.py"


def test_hay_una_fila_por_fichero() -> None:
    assert sorted(r["archivo"] for r in rows_of("esperado-archivos.csv")) == sorted(FILES)


def test_resultados_de_abrir_cada_fichero() -> None:
    result = {r["archivo"]: r["resultado"] for r in rows_of("esperado-archivos.csv")}
    assert result["vacio.kml"] == "EMPTY" and result["espacios.kml"] == "EMPTY"
    assert result["texto.kml"] == "NOT_KML" and result["no-es-kml.kml"] == "NOT_KML" and result["ns30.kml"] == "NOT_KML"
    assert result["danado.kml"] == "DAMAGED" and result["danado.kmz"] == "DAMAGED"
    assert result["enlace-danado.kmz"] == "DAMAGED"  # un KML enlazado y cortado deja dañado todo el KMZ
    assert result["sin-marcas.kml"] == "NO_PLACEMARKS" and result["solo-ignorados.kml"] == "NO_PLACEMARKS"
    assert result["sin-kml.kmz"] == "NOT_KML" and result["kml-en-subcarpeta.kmz"] == "NOT_KML" and result["kmz-sin-entradas.kmz"] == "NOT_KML"
    ok = {n for n, r in result.items() if r == "OK"}
    assert {"con-doc.kmz", "sin-doc.kmz", "varios-kml.kmz", "mayusculas.kmz", "ns20-bom.kml", "ns22-ogc.kml", "sin-ns.kml"} <= ok


def test_el_kml_principal_de_un_kmz() -> None:
    principal = {r["archivo"]: r["principal"] for r in rows_of("esperado-archivos.csv")}
    assert principal["con-doc.kmz"] == "doc.kml"
    assert principal["varios-kml.kmz"] == "doc.kml"
    assert principal["sin-doc.kmz"] == "a.kml"  # el primero por orden alfabético, no el que se escribió antes ni el de la subcarpeta
    assert principal["mayusculas.kmz"] == "DOC.KML"


def test_los_kmz_se_abren_con_otro_lector() -> None:
    for name, data in FILES.items():
        if not name.endswith(".kmz") or name == "danado.kmz":
            continue
        z = zipfile.ZipFile(io.BytesIO(data))
        assert z.testzip() is None
    assert "files/foto.jpg" in zipfile.ZipFile(io.BytesIO(FILES["con-doc.kmz"])).namelist()
    with pytest.raises(zipfile.BadZipFile):
        zipfile.ZipFile(io.BytesIO(FILES["danado.kmz"]))


def test_los_kml_son_xml_bien_formado_con_otro_lector() -> None:
    cut = {"danado.kml", "texto.kml", "vacio.kml", "espacios.kml"}
    for name, data in FILES.items():
        if name.endswith(".kml") and name not in cut:
            minidom.parseString(data)
    with pytest.raises(Exception):  # noqa: B017, PT011
        minidom.parseString(FILES["danado.kml"])


def test_ignorados_por_fichero() -> None:
    ignored = {r["archivo"]: r["ignorados"] for r in rows_of("esperado-archivos.csv")}
    assert ignored["ignorados.kml"] == "MODEL=2;NETWORK_LINK=2;OVERLAY=3;TOUR=1;TRACK=2"
    assert ignored["solo-ignorados.kml"] == "NETWORK_LINK=1"
    assert ignored["geometrias.kml"] == ""


def test_altitudes_absolutas_de_cada_fichero() -> None:
    flag = {r["archivo"]: r["altitudes_absolutas"] for r in rows_of("esperado-archivos.csv")}
    assert flag["altitudes.kml"] == "SI"
    assert all(v == "NO" for n, v in flag.items() if n != "altitudes.kml")


def test_solo_las_marcas_absolutas_con_altitud_completa_llevan_z() -> None:
    z_of = {}
    for r in rows_of("esperado.csv"):
        if r["archivo"] == "altitudes.kml" and r["estado"] == "OK":
            z_of[(r["capa"], int(r["fid"]))] = "_" not in r["vertices_kml"].split(";")[0].split()[2:3]
    names = {(r["capa"], int(r["fid"])): r for r in rows_of("esperado-atributos.csv") if r["columna"] == "name" and r["archivo"] == "altitudes.kml"}
    with_z = {names[key]["valor"] for key, has in z_of.items() if has}
    assert with_z == {"Abs punto", "Abs linea", "Abs poligono"}


def test_los_vertices_esperados_estan_en_el_fichero() -> None:
    for r in rows_of("esperado.csv"):
        if r["estado"] != "OK":
            continue
        text = FILES[r["archivo"]].decode("utf-8", "ignore")
        if r["archivo"].endswith(".kmz"):
            z = zipfile.ZipFile(io.BytesIO(FILES[r["archivo"]]))
            text = z.read(next(n for n in z.namelist() if n.lower().endswith(".kml"))).decode("utf-8")
            text = text + "".join(z.read(n).decode("utf-8", "ignore") for n in z.namelist() if n.lower().endswith(".kml"))
        for vertex in r["vertices_kml"].split(";"):
            lon, lat, *alt = vertex.split()
            assert f"{lon},{lat}" in text, f"{r['archivo']}: {lon},{lat}"


def test_coordenadas_de_proyecto_contra_pyproj() -> None:
    worst = 0.0
    count = 0
    for r in rows_of("esperado.csv"):
        if r["estado"] != "OK":
            continue
        for source, target in zip(r["vertices_kml"].split(";"), r["vertices_proyecto"].split(";")):
            lon, lat, *_ = source.split()
            e, n, *_ = target.split()
            ee, nn = KML_TO_UTM30.transform(float(lon), float(lat))
            worst = max(worst, abs(ee - float(e)), abs(nn - float(n)))
            count += 1
    assert count > 100
    assert worst < 1e-6, f"diferencia de {worst} m frente a pyproj"


def test_coordenadas_de_regcan95_contra_pyproj() -> None:
    rows = [r for r in rows_of("esperado-regcan95.csv") if r["estado"] == "OK"]
    assert len(rows) == 4
    for r in rows:
        for source, target in zip(r["vertices_kml"].split(";"), r["vertices_proyecto"].split(";")):
            lon, lat, *_ = source.split()
            e, n, *_ = target.split()
            ee, nn = KML_TO_UTM28.transform(float(lon), float(lat))
            assert abs(ee - float(e)) < 1e-6 and abs(nn - float(n)) < 1e-6


def test_canarias_cae_fuera_del_ambito_de_utm30_y_dentro_del_de_utm28() -> None:
    utm30 = {r["estado"] for r in rows_of("esperado.csv") if r["archivo"] == "canarias.kml"}
    assert utm30 == {"fuera_de_ambito"}
    assert {r["estado"] for r in rows_of("esperado-regcan95.csv")} == {"OK"}


def test_fuera_de_ambito_descarta_el_objeto_entero() -> None:
    estados = {(r["capa"], int(r["fid"])): r["estado"] for r in rows_of("esperado.csv") if r["archivo"] == "fuera-de-ambito.kml"}
    assert estados == {("Ambito (puntos)", 1): "OK", ("Ambito (puntos)", 2): "fuera_de_ambito",
                       ("Ambito (líneas)", 1): "fuera_de_ambito", ("Ambito (líneas)", 2): "OK"}


def test_fid_seguidos_y_capas_unicas_por_fichero() -> None:
    fids: dict[tuple[str, str], list[int]] = defaultdict(list)
    for r in rows_of("esperado.csv"):
        key = (r["archivo"], r["capa"])
        n = int(r["fid"])
        if not fids[key] or fids[key][-1] != n:
            fids[key].append(n)
    for key, values in fids.items():
        assert values == list(range(1, len(values) + 1)), key
    capas = rows_of("esperado-capas.csv")
    assert len({(r["archivo"], r["capa"]) for r in capas}) == len(capas)
    for r in capas:
        assert len(fids[(r["archivo"], r["capa"])]) == int(r["objetos"]), r


def test_nombres_de_capa_con_ruta() -> None:
    capas = {r["capa"] for r in rows_of("esperado-capas.csv") if r["archivo"] == "carpetas-anidadas.kml"}
    assert capas == {
        "carpetas-anidadas (puntos)", "carpetas-anidadas (líneas)",
        "Obra (puntos)", "Obra (líneas)",
        "Obra / Parcelas / Norte (puntos)", "Obra / Parcelas / Norte (líneas)",
        "Obra / Parcelas / Sur", "Obra / Anexo",
    }


def test_poligonos_dan_contorno_y_huecos() -> None:
    rows = [r for r in rows_of("esperado.csv") if r["archivo"] == "geometrias.kml" and r["estado"] == "OK"]
    by = defaultdict(list)
    for r in rows:
        by[(r["capa"], r["fid"])].append(r)
    names = {(r["capa"], r["fid"]): r["valor"] for r in rows_of("esperado-atributos.csv") if r["archivo"] == "geometrias.kml" and r["columna"] == "name"}
    parcela = by[next(key for key, v in names.items() if v == "Parcela")]
    assert [r["contorno"] for r in parcela] == ["SI", "SI"]
    dos_huecos = by[next(key for key, v in names.items() if v == "Parcela con dos huecos")]
    assert len(dos_huecos) == 3
    anillo = by[next(key for key, v in names.items() if v == "Anillo")]
    assert [r["contorno"] for r in anillo] == ["NO"]
    dos_puntos = by[next(key for key, v in names.items() if v == "Dos puntos")]
    assert len(dos_puntos) == 2


def test_marca_sin_nombre_no_tiene_valor_de_nombre() -> None:
    nombres = {(r["capa"], int(r["fid"])) for r in rows_of("esperado-atributos.csv") if r["archivo"] == "geometrias.kml" and r["columna"] == "name"}
    puntos = [r for r in rows_of("esperado.csv") if r["archivo"] == "geometrias.kml" and r["tipo"] == "POINTS"]
    sin_nombre = {(r["capa"], int(r["fid"])) for r in puntos} - nombres
    assert len(sin_nombre) == 1


def test_atributos_y_columnas() -> None:
    capa = next(r for r in rows_of("esperado-capas.csv") if r["archivo"] == "atributos.kml")
    assert capa["columnas"] == "name;description;altura;material;name_2;diametro;vacio;mezcla;html"
    valores = {(int(r["fid"]), r["columna"]): r["valor"] for r in rows_of("esperado-atributos.csv") if r["archivo"] == "atributos.kml"}
    assert valores[(1, "description")] == "Poste de hormigón\nLínea 2\nLínea 3 & fin"
    assert valores[(1, "name_2")] == "dup"
    assert valores[(2, "altura")] == "1" and valores[(2, "diametro")] == "0.60"
    assert valores[(4, "description")] == "Uno\nDos"
    assert (4, "vacio") not in valores  # un valor vacío es nulo
    assert valores[(4, "html")] == "<b>negrita</b>"  # los datos extendidos no se limpian de HTML


def test_colores() -> None:
    colores = {}
    nombres = {(r["capa"], int(r["fid"])): r["valor"] for r in rows_of("esperado-atributos.csv") if r["archivo"] == "estilos.kml" and r["columna"] == "name"}
    for r in rows_of("esperado.csv"):
        if r["archivo"] == "estilos.kml" and r["estado"] == "OK":
            colores[(nombres[(r["capa"], int(r["fid"]))], r["tipo"])] = r["color"]
    assert colores[("Rojo", "POINTS")] == "#FF0000"
    assert colores[("Punto con estilo de linea", "POINTS")] == ""
    assert colores[("Linea azul", "LINES")] == "#0000FF"
    assert colores[("Poligono azul", "LINES")] == "#0000FF"
    assert colores[("Con StyleMap", "POINTS")] == "#FF0000"
    assert colores[("StyleMap en linea", "POINTS")] == "#00FF00"
    assert colores[("Estilo en la marca", "POINTS")] == "#332211"
    assert colores[("Punto y linea", "POINTS")] == "#00FF00" and colores[("Punto y linea", "LINES")] == "#FFFF00"
    assert colores[("Con alfa", "POINTS")] == "#332211"
    for sin_color in ("Sin estilo", "Estilo sin color", "Estilo declarado despues", "Estilo de otro fichero", "Estilo que no existe", "Color que no vale"):
        assert colores[(sin_color, "POINTS")] == "", sin_color


def test_geometrias_vacias_son_objetos_ignorados() -> None:
    estados = [r["estado"] for r in rows_of("esperado.csv") if r["archivo"] == "ignorados.kml"]
    assert estados.count("OK") == 2 and estados.count("ignorado_vacia") == 5


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("<b>Hola</b> mundo", "Hola mundo"),
        ("Uno<br>Dos<br/>Tres<BR />Cuatro", "Uno\nDos\nTres\nCuatro"),
        ("<p>Uno</p><p>Dos</p>", "Uno\nDos"),
        ("a &amp; b &lt; c", "a & b < c"),
        ("  hueco  \n\n  otra  ", "hueco\notra"),
        ('<a href="http://x/y">enlace</a><img src="f.jpg"/>', "enlace"),
        ("a < b y c > d", "a < b y c > d"),
        ("", ""),
    ],
)
def test_html_de_la_descripcion(raw: str, expected: str) -> None:
    assert k.strip_html(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("ff0000ff", "#FF0000"), ("ffff0000", "#0000FF"), ("80112233", "#332211"), ("FF00FF00", "#00FF00"), ("rojo", ""), ("ff0000", ""), ("", "")],
)
def test_colores_aabbggrr(raw: str, expected: str) -> None:
    assert k.hex_color(raw) == expected


def test_coordenadas_ilegibles() -> None:
    from xml.etree import ElementTree as ET

    def parse(text: str):
        return k.parse_coordinates(ET.fromstring(f"<coordinates>{text}</coordinates>"))

    assert parse("1,2 3,4,5") == [(1.0, 2.0, None), (3.0, 4.0, 5.0)]
    assert parse("1,2\n3,4") == [(1.0, 2.0, None), (3.0, 4.0, None)]
    assert parse("abc,def") is None
    assert parse("1") is None
    assert parse("") == []
    assert re.fullmatch(r"-?\d+\.\d+", k.num(-3.7038)) is not None


def test_enlaces_internos_de_un_kmz() -> None:
    capas = {r["capa"]: r for r in rows_of("esperado-capas.csv") if r["archivo"] == "enlaces-internos.kmz"}
    assert set(capas) == {
        "Capa A / Zona", "Capa A", "b", "Con Url", "Carpeta / Anidado", "Carpeta / Anidado / Hondo",
        "Carpeta / Anidado / Hondo / Mas hondo", "Carpeta",
    }
    assert capas["b"]["tipo"] == "LINES"  # un vínculo sin nombre toma el del fichero
    fila = next(r for r in rows_of("esperado-archivos.csv") if r["archivo"] == "enlaces-internos.kmz")
    # http, fichero que falta, que sale del KMZ, ruta absoluta, el propio doc.kml, el repetido sin distinguir mayúsculas,
    # un KML roto, el ciclo c.kml y el que pasa de 3 niveles
    assert fila["ignorados"] == "NETWORK_LINK=9"
    assert fila["objetos"] == "9"


def test_un_vinculo_en_un_kml_suelto_se_ignora() -> None:
    doc = k.read_file("suelto.kml", k.kml(k.document(k.pm("Un punto", k.point(*k.at(0, 0))), "<NetworkLink><Link><href>a.kml</href></Link></NetworkLink>")))
    assert doc.result == "OK" and doc.ignored == {"NETWORK_LINK": 1}


QGIS = DATOS / "qgis"


def qgis_rows(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((QGIS / name).read_text(encoding="utf-8"), newline="")))


QGIS_FILES = ["qgis-kml.kml", "qgis-libkml.kml", "qgis-libkml.kmz", "qgis-libkml-multicapa.kmz"]


def test_los_ficheros_de_qgis_estan_y_se_leen() -> None:
    assert (QGIS / "LEEME.txt").read_text(encoding="utf-8").startswith("Ficheros escritos por QGIS")
    for name in QGIS_FILES:
        doc = k.read_file(name, (QGIS / name).read_bytes())
        assert doc.result == "OK", name
        assert {layer.path for layer in doc.layers} <= {"puntos", "lineas", "poligonos"}
        if name == "qgis-libkml.kmz":
            assert [layer.path for layer in doc.layers] == ["puntos"]  # el KMZ de una sola capa
        else:
            assert sorted(layer.path for layer in doc.layers) == ["lineas", "poligonos", "puntos"]


def test_la_lectura_de_referencia_coincide_con_la_de_qgis() -> None:
    qgis = defaultdict(list)
    for r in qgis_rows("esperado-qgis-geometria.csv"):
        qgis[(r["archivo"], r["capa"], int(r["objeto"]))].append(r["vertices"].split(";"))
    checked = 0
    for name in QGIS_FILES:
        doc = k.read_file(name, (QGIS / name).read_bytes())
        for layer in doc.layers:
            assert len(layer.features) == qgis_count(name, layer.path), (name, layer.path)
            for number, feature in enumerate(layer.features, 1):
                theirs = qgis[(name, layer.path, number)]
                assert len(feature.parts) == len(theirs), (name, layer.path, number)
                for part, vertices in zip(feature.parts, theirs):
                    assert len(part.vertices) == len(vertices)
                    for ours, text in zip(part.vertices, vertices):
                        x, y, *_ = text.split()
                        assert abs(ours[0] - float(x)) < 1e-12 and abs(ours[1] - float(y)) < 1e-12
                        checked += 1
    assert checked > 50


def qgis_count(name: str, layer: str) -> int:
    return int(next(r for r in qgis_rows("esperado-qgis-capas.csv") if r["archivo"] == name and r["capa"] == layer)["objetos"])


def test_los_atributos_de_referencia_coinciden_con_los_de_qgis() -> None:
    theirs = defaultdict(dict)
    for r in qgis_rows("esperado-qgis-atributos.csv"):
        theirs[(r["archivo"], r["capa"], int(r["objeto"]))][r["columna"]] = r["valor"]
    for name in QGIS_FILES:
        doc = k.read_file(name, (QGIS / name).read_bytes())
        for layer in doc.layers:
            for number, feature in enumerate(layer.features, 1):
                expected = theirs[(name, layer.path, number)]
                assert feature.name == expected["Name"], (name, layer.path, number)
                assert feature.description == expected.get("description", ""), (name, layer.path, number)
                for column in ("codigo",):
                    assert feature.data.get(column, "") == expected.get(column, "")
                if "altura" in expected:
                    assert float(feature.data["altura"]) == pytest.approx(float(expected["altura"]))
                assert int(feature.data["n"]) == int(expected["n"])


def test_los_kml_de_qgis_no_declaran_modo_de_altitud_asi_que_no_llevan_z() -> None:
    # QGIS escribe `0` o la altura en cada vértice sin `altitudeMode`: por RF-14 se importa sin cota
    for name in QGIS_FILES:
        doc = k.read_file(name, (QGIS / name).read_bytes())
        assert not doc.has_elevations(), name


def test_las_coordenadas_de_proyecto_de_qgis_contra_pyproj() -> None:
    worst = 0.0
    count = 0
    geometry = {(r["archivo"], r["capa"], r["objeto"], r["parte"]): r["vertices"] for r in qgis_rows("esperado-qgis-geometria.csv")}
    for r in qgis_rows("esperado-qgis-proyecto.csv"):
        source = geometry[(r["archivo"], r["capa"], r["objeto"], r["parte"])].split(";")
        for text, projected in zip(source, r["vertices_utm30"].split(";")):
            lon, lat, *_ = text.split()
            e, n, *_ = projected.split()
            ee, nn = KML_TO_UTM30.transform(float(lon), float(lat))
            worst = max(worst, abs(ee - float(e)), abs(nn - float(n)))
            count += 1
    assert count > 50
    assert worst < 1e-6, f"QGIS y pyproj difieren {worst} m"
