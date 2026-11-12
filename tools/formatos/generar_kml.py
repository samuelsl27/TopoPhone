"""KML y KMZ de ejemplo de la importación y lo que debe salir de cada uno (S031).

Genera en fixtures/formatos/kml/ los ficheros de ejemplo (`.kml` y `.kmz`), escritos a mano con la biblioteca estándar, y estos ficheros con lo
esperado, que salen de un lector de referencia (`xml.etree`, no el de la app) y de pyproj:

- esperado-archivos.csv: una fila por fichero: formato, resultado de abrirlo (OK, EMPTY, NOT_KML, NO_PLACEMARKS o DAMAGED), el KML principal
  de un KMZ, los objetos válidos, si trae altitudes absolutas (la pregunta de RF-13) y los elementos ignorados por fichero (RF-9).
- esperado-capas.csv: una fila por capa: archivo, nombre de la capa, tipo (POINTS o LINES), objetos (los vacíos y los de fuera del ámbito
  cuentan) y las columnas de atributos en su orden.
- esperado.csv: una fila por parte de cada objeto, con el sistema de la app ETRS89 UTM 30: archivo, capa, fid, tipo, parte, si es contorno
  de polígono, vértices tal como están en el KML ("lon lat alt", `_` si el objeto no lleva Z), vértices en ETRS89 UTM 30 ("este norte alt"),
  color (#RRGGBB) y estado (OK, ignorado_vacia o fuera_de_ambito).
- esperado-regcan95.csv: lo mismo para canarias.kml con el sistema de la app REGCAN95 UTM 28.
- esperado-atributos.csv: una fila por valor no nulo: archivo, capa, fid, columna y valor.

Reglas de este conjunto que la spec no fija y que quedan escritas en lo esperado (se confirman al hacer cada tarea):
- Una carpeta es un `Folder` o un `Document` que no es el `Document` raíz (el hijo directo de `<kml>`). Una carpeta sin nombre no añade tramo
  a la ruta. La ruta de la capa une los nombres con « / ». Las marcas fuera de toda carpeta van a las capas con el nombre del fichero sin
  extensión. Dos carpetas con la misma ruta comparten capa.
- Una capa es una pareja (ruta, tipo): puntos o líneas (los polígonos van en líneas). Si una ruta tiene los dos, se llaman «ruta (puntos)» y
  «ruta (líneas)»; con uno solo, la ruta tal cual.
- Una marca da un objeto por tipo: sus puntos (también los de un `MultiGeometry`) son las partes del objeto de puntos, y sus líneas, anillos
  y polígonos las del objeto de líneas. Un polígono da su contorno exterior y uno por cada hueco, todos con la marca de contorno; un
  `LinearRing` suelto es una línea cerrada y un `LineString`, una línea, sin marca. El `fid` es el número del objeto en su capa, desde 1, por
  orden de aparición. Una geometría vacía es un objeto ignorado (`ignorado_vacia`) que consume su `fid`, como un registro nulo de un Shapefile.
- Vacías: un punto sin coordenadas legibles, una línea con menos de 2 vértices, un anillo con menos de 4 posiciones o un polígono sin
  contorno exterior válido; un hueco inválido se descarta sin más. Una marca sin geometría no da objeto ni se cuenta.
- La altitud solo se guarda si el `altitudeMode` de la geometría es `absolute` y todos sus vértices la traen; un objeto lleva Z solo si todas
  sus partes la llevan. `gx:altitudeMode` no cuenta. Sin modo es `clampToGround`.
- Color: el de `IconStyle` para los puntos y el de `LineStyle` para las líneas y los contornos, de `aabbggrr` a `#RRGGBB` (la transparencia se
  ignora; un color que no son 8 cifras hexadecimales no vale). Un estilo en la marca gana al `styleUrl`; un `StyleMap` da su estilo
  `normal`. Los estilos se resuelven al terminar la marca con los declarados hasta entonces: uno declarado después no se aplica, ni uno de otro
  fichero (`otro.kml#id`).
- Atributos: `name` y `description` (sin etiquetas HTML: `<br>`, `</p>`, `</div>`, `</li>` y `</tr>` son saltos de línea, las demás etiquetas
  se quitan, se resuelven las entidades y se quitan las líneas vacías y los espacios de los extremos de cada línea) y una columna por cada
  `Data` o `SimpleData`, en orden de aparición en la capa; un nombre que ya existe como columna (incluidos `name` y `description`) lleva `_2`,
  `_3`… Un valor vacío es nulo.
- Vínculos internos (solo en un KMZ): un `NetworkLink` (con `Link` o `Url`) cuyo `href` es relativo, sin esquema ni `..` que salga del KMZ, y
  apunta a un `.kml` del KMZ (exacto o sin distinguir mayúsculas) se lee como una carpeta con el nombre del vínculo o, sin nombre, el del
  fichero sin extensión: su ruta empieza por la del vínculo y las marcas sueltas del fichero enlazado van a la capa con esa ruta. Hasta 3
  niveles de vínculos y cada fichero una sola vez; el resto de los vínculos se cuentan como ignorados.
- Ignorados por fichero: `NetworkLink` (los que no son vínculos internos), `GroundOverlay`, `ScreenOverlay` y `PhotoOverlay` (`NETWORK_LINK` y `OVERLAY`), `Model` (`MODEL`),
  `gx:Track` y `gx:MultiTrack` (`TRACK`, una vez por `MultiTrack`) y `gx:Tour` (`TOUR`).
- Un fichero sin ningún objeto válido es `NO_PLACEMARKS`. Un KML es XML con raíz `kml` y sin espacio de nombres o con uno `kml/2.x`; un XML
  cortado que empieza como KML es `DAMAGED`; texto o XML de otra raíz, `NOT_KML`; vacío o solo espacios, `EMPTY`. Un KMZ usa el `doc.kml` de
  la raíz y, si no lo hay, el primer `.kml` de la raíz por orden alfabético, sin distinguir mayúsculas; sin `.kml` en la raíz es `NOT_KML`.
- Las coordenadas se escriben a su precisión completa (repr de Python).

Uso: uv run --project tools tools/formatos/generar_kml.py
"""

from __future__ import annotations

import csv
import html
import io
import posixpath
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_geopackage as g  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "kml"

NS22 = "http://www.opengis.net/kml/2.2"
NS_EARTH22 = "http://earth.google.com/kml/2.2"
NS_EARTH20 = "http://earth.google.com/kml/2.0"
GX = "http://www.google.com/kml/ext/2.2"
BOM = b"\xef\xbb\xbf"
ZIP_DATE = (2026, 11, 12, 0, 0, 0)

MAX_LINK_DEPTH = 3
POINT_TAGS = {"Point"}
LINE_TAGS = {"LineString", "LinearRing", "Polygon"}
IGNORED_TAGS = {
    "NetworkLink": "NETWORK_LINK",
    "GroundOverlay": "OVERLAY",
    "ScreenOverlay": "OVERLAY",
    "PhotoOverlay": "OVERLAY",
    "Model": "MODEL",
    "Track": "TRACK",
    "MultiTrack": "TRACK",
    "Tour": "TOUR",
}


# ---------- construcción de los ficheros ----------


def kml(body: str, ns: str | None = NS_EARTH22, *, gx: bool = False, bom: bool = False) -> bytes:
    attrs = (f' xmlns="{ns}"' if ns else "") + (f' xmlns:gx="{GX}"' if gx else "")
    text = f'<?xml version="1.0" encoding="UTF-8"?>\n<kml{attrs}>\n{body}\n</kml>\n'
    return (BOM if bom else b"") + text.encode("utf-8")


def num(x: float) -> str:
    return repr(float(x))


def coords(*points: tuple) -> str:
    """`<coordinates>` con una tupla por punto: (lon, lat) o (lon, lat, alt)."""
    return "<coordinates>" + " ".join(",".join(num(v) for v in p) for p in points) + "</coordinates>"


def pm(name: str | None, geometry: str = "", extra: str = "", style: str = "", description: str | None = None) -> str:
    parts = ["<Placemark>"]
    if name is not None:
        parts.append(f"<name>{html.escape(name, quote=False)}</name>")
    if description is not None:
        parts.append(f"<description>{description}</description>")
    if style:
        parts.append(style)
    if extra:
        parts.append(extra)
    parts.append(geometry)
    parts.append("</Placemark>")
    return "".join(parts)


def point(lon: float, lat: float, alt: float | None = None, mode: str = "") -> str:
    p = (lon, lat) if alt is None else (lon, lat, alt)
    return f"<Point>{mode}{coords(p)}</Point>"


def line(*points: tuple, mode: str = "") -> str:
    return f"<LineString>{mode}{coords(*points)}</LineString>"


def ring(*points: tuple) -> str:
    return f"<LinearRing>{coords(*points)}</LinearRing>"


def polygon(outer: tuple, *holes: tuple, mode: str = "") -> str:
    inner = "".join(f"<innerBoundaryIs>{ring(*h)}</innerBoundaryIs>" for h in holes)
    return f"<Polygon>{mode}<outerBoundaryIs>{ring(*outer)}</outerBoundaryIs>{inner}</Polygon>"


def multi(*geometries: str) -> str:
    return "<MultiGeometry>" + "".join(geometries) + "</MultiGeometry>"


def folder(name: str | None, *children: str, tag: str = "Folder") -> str:
    head = f"<name>{html.escape(name, quote=False)}</name>" if name is not None else ""
    return f"<{tag}>{head}{''.join(children)}</{tag}>"


def document(*children: str, name: str = "Documento") -> str:
    return f"<Document><name>{name}</name>{''.join(children)}</Document>"


ABS = "<altitudeMode>absolute</altitudeMode>"
CLAMP = "<altitudeMode>clampToGround</altitudeMode>"
RELATIVE = "<altitudeMode>relativeToGround</altitudeMode>"

# Un cuadrado de unos 60 m en Madrid (lon, lat) y su hueco; todo dentro del ámbito de ETRS89 UTM 30.
LON0, LAT0 = -3.7038, 40.4168


def at(dx: float, dy: float, alt: float | None = None) -> tuple:
    """Un vértice a (dx, dy) milésimas de grado del origen."""
    lon, lat = round(LON0 + dx * 0.001, 9), round(LAT0 + dy * 0.001, 9)
    return (lon, lat) if alt is None else (lon, lat, alt)


def square(x: float, y: float, size: float, alt: float | None = None) -> tuple:
    return (at(x, y, alt), at(x + size, y, alt), at(x + size, y + size, alt), at(x, y + size, alt), at(x, y, alt))


def nested_folders() -> bytes:
    return kml(document(
        pm("Suelto A", point(*at(0, 0))),
        folder("Obra",
               pm("Eje", line(at(1, 1), at(2, 2), at(3, 1))),
               pm("Hito", point(*at(1.5, 1))),
               folder("Parcelas",
                      folder("Norte", pm("P1", point(*at(4, 4))), pm("P2", point(*at(4.5, 4.2))), pm("L1", line(at(4, 4), at(5, 5)))),
                      folder("Sur", pm("S1", point(*at(4, -4))))),
               folder(None, pm("En carpeta sin nombre", point(*at(2, 2)))),
               folder("Anexo", pm("En documento anidado", point(*at(2.5, 2.5))), tag="Document")),
        pm("Suelto B", line(at(0, 0), at(0, 1))),
    ))


def geometries() -> bytes:
    outer = square(0, 0, 0.4)
    hole = square(0.1, 0.1, 0.1)
    hole2 = square(0.25, 0.25, 0.1)
    return kml(document(folder("Geometrias",
        pm("Punto", point(*at(0, 0))),
        pm("Linea", line(at(0, 0), at(1, 0), at(1, 1))),
        pm("Anillo", ring(*square(2, 0, 0.3))),
        pm("Parcela", polygon(outer, hole)),
        pm("Parcela con dos huecos", polygon(square(3, 0, 0.5), square(3.05, 0.05, 0.1), square(3.3, 0.3, 0.1))),
        pm("Dos puntos", multi(point(*at(5, 5)), point(*at(5.1, 5.1)))),
        pm("Mixta", multi(point(*at(6, 6)), polygon(square(6, 6, 0.2)))),
        pm("Dos lineas", multi(line(at(7, 0), at(7, 1)), line(at(7.5, 0), at(7.5, 1)))),
        pm(None, point(*at(8, 8))),
        pm("Anidada", multi(multi(point(*at(9, 9)), line(at(9, 9), at(9.5, 9.5))), point(*at(9.8, 9.8)))),
    )))


def attributes() -> bytes:
    schema = '<ExtendedData><SchemaData schemaUrl="#s"><SimpleData name="diametro">0.60</SimpleData><SimpleData name="altura">1</SimpleData></SchemaData></ExtendedData>'
    data = ('<ExtendedData><Data name="altura"><value>12.5</value></Data><Data name="material"><value>hormigón</value></Data>'
            '<Data name="name"><value>dup</value></Data></ExtendedData>')
    escapes = ('<ExtendedData><Data name="vacio"><value></value></Data><Data name="mezcla"><value>a &amp; b</value></Data>'
               '<Data name="html"><value>&lt;b&gt;negrita&lt;/b&gt;</value></Data></ExtendedData>')
    return kml(document(folder("Atributos",
        pm("Poste", point(*at(0, 0)), data,
           description="<![CDATA[<b>Poste</b> de <i>hormigón</i><br/>Línea 2<br>Línea 3 &amp; fin<img src=\"foto.jpg\"/>]]>"),
        pm("Arqueta", point(*at(1, 0)), schema, description="Tapa rota"),
        pm("Sin datos", point(*at(2, 0))),
        pm("Escapes", point(*at(3, 0)), escapes, description="&lt;p&gt;Uno&lt;/p&gt;&lt;p&gt;Dos&lt;/p&gt;"),
    )))


def styles() -> bytes:
    def style(sid: str, inner: str) -> str:
        return f'<Style id="{sid}">{inner}</Style>'

    def icon(color: str) -> str:
        return f"<IconStyle><color>{color}</color></IconStyle>"

    def linest(color: str) -> str:
        return f"<LineStyle><color>{color}</color></LineStyle>"

    def u(ref: str) -> str:
        return f"<styleUrl>{ref}</styleUrl>"

    head = (
        style("ptRojo", icon("ff0000ff"))
        + style("lnAzul", linest("ffff0000"))
        + style("ambos", icon("ff00ff00") + linest("ff00ffff"))
        + style("sinColor", "<LabelStyle><scale>1.2</scale></LabelStyle>")
        + style("alfa", icon("80112233"))
        + style("malo", icon("rojo"))
        + '<StyleMap id="mapa"><Pair><key>normal</key><styleUrl>#ptRojo</styleUrl></Pair>'
        + "<Pair><key>highlight</key><styleUrl>#lnAzul</styleUrl></Pair></StyleMap>"
        + f'<StyleMap id="mapaInline"><Pair><key>normal</key><Style>{icon("ff00ff00")}</Style></Pair></StyleMap>'
    )
    inline = f"<Style>{icon('ff112233')}</Style>"
    marks = [
        pm("Rojo", point(*at(0, 0)), style=u("#ptRojo")),
        pm("Punto con estilo de linea", point(*at(1, 0)), style=u("#lnAzul")),
        pm("Linea azul", line(at(2, 0), at(2, 1)), style=u("#lnAzul")),
        pm("Poligono azul", polygon(square(3, 0, 0.3)), style=u("#lnAzul")),
        pm("Con StyleMap", point(*at(4, 0)), style=u("#mapa")),
        pm("StyleMap en linea", point(*at(5, 0)), style=u("#mapaInline")),
        pm("Estilo en la marca", point(*at(6, 0)), style=inline + u("#ptRojo")),
        pm("Punto y linea", multi(point(*at(7, 0)), line(at(7, 0), at(7, 1))), style=u("#ambos")),
        pm("Sin estilo", point(*at(8, 0))),
        pm("Estilo sin color", point(*at(9, 0)), style=u("#sinColor")),
        pm("Con alfa", point(*at(10, 0)), style=u("#alfa")),
        pm("Estilo declarado despues", point(*at(11, 0)), style=u("#tarde")),
        pm("Estilo de otro fichero", point(*at(12, 0)), style=u("otro.kml#externo")),
        pm("Estilo que no existe", point(*at(13, 0)), style=u("#noExiste")),
        pm("Color que no vale", point(*at(14, 0)), style=u("#malo")),
    ]
    tail = style("tarde", icon("ff0000ff"))
    return kml(document(head, folder("Estilos", *marks), tail))


def altitudes() -> bytes:
    gx_mode = "<gx:altitudeMode>relativeToSeaFloor</gx:altitudeMode>"
    return kml(document(folder("Altitudes",
        pm("Abs punto", point(*at(0, 0, 646.377), mode=ABS)),
        pm("Abs linea", line(at(1, 0, 10.5), at(1, 1, 11.5), at(1, 2, 12.5), mode=ABS)),
        pm("Abs parcial", line(at(2, 0, 10.5), at(2, 1, 11.5), at(2, 2), mode=ABS)),
        pm("Clamp con z", point(*at(3, 0, 10.0), mode=CLAMP)),
        pm("Relativa", point(*at(4, 0, 5.0), mode=RELATIVE)),
        pm("Sin modo con z", point(*at(5, 0, 7.0))),
        pm("Fondo marino", f"<Point>{gx_mode}{coords(at(6, 0, 3.0))}</Point>"),
        pm("Abs sin z", point(*at(7, 0), mode=ABS)),
        pm("Abs poligono", polygon(square(8, 0, 0.3, 100.0), square(8.05, 0.05, 0.1, 100.0), mode=ABS)),
        pm("Mixta", multi(point(*at(9, 0, 50.0), mode=ABS), point(*at(9.5, 0, 50.0), mode=CLAMP))),
    )), gx=True)


def ignored() -> bytes:
    link = '<NetworkLink><name>Vinculo</name><Link><href>http://example.org/otro.kml</href></Link></NetworkLink>'
    overlays = (
        '<GroundOverlay><name>Plano</name><Icon><href>plano.png</href></Icon></GroundOverlay>'
        '<ScreenOverlay><name>Leyenda</name><Icon><href>leyenda.png</href></Icon></ScreenOverlay>'
        '<PhotoOverlay><name>Foto</name><Icon><href>foto.jpg</href></Icon></PhotoOverlay>'
    )
    model = '<Model><Location><longitude>-3.7</longitude><latitude>40.4</latitude></Location><Link><href>m.dae</href></Link></Model>'
    track = ('<gx:Track><when>2026-11-12T10:00:00Z</when><when>2026-11-12T10:01:00Z</when>'
             '<gx:coord>-3.70 40.41 0</gx:coord><gx:coord>-3.71 40.42 0</gx:coord></gx:Track>')
    multitrack = f"<gx:MultiTrack>{track}{track}</gx:MultiTrack>"
    tour = '<gx:Tour><name>Recorrido</name><gx:Playlist><gx:FlyTo><gx:duration>5</gx:duration></gx:FlyTo></gx:Playlist></gx:Tour>'
    return kml(document(
        link, overlays, tour,
        folder("Mezcla",
               link,
               pm("Valido", point(*at(0, 0))),
               pm("Modelo", model),
               pm("Pista", track),
               pm("Pistas", multitrack),
               pm("Modelo y punto", multi(model, point(*at(1, 0)))),
               pm("Punto vacio", "<Point><coordinates></coordinates></Point>"),
               pm("Linea de un vertice", line(at(2, 0))),
               pm("Poligono roto", polygon((at(3, 0), at(3.1, 0), at(3, 0)))),
               pm("Anillo roto", ring(at(4, 0), at(4.1, 0), at(4, 0))),
               pm("Coordenadas ilegibles", "<Point><coordinates>abc,def</coordinates></Point>"),
               pm("Solo una nota", ""),
               ),
    ), gx=True)


def outside_area() -> bytes:
    return kml(document(folder("Ambito",
        pm("Dentro", point(*at(0, 0))),
        pm("Fuera", point(10.0, 50.0)),
        pm("Linea con un vertice fuera", line(at(0, 0), at(1, 1), (10.0, 50.0))),
        pm("Parcela dentro", polygon(square(2, 2, 0.2))),
    )))


def canarias() -> bytes:
    def c(dx: float, dy: float) -> tuple:
        return (round(-16.25 + dx * 0.001, 9), round(28.45 + dy * 0.001, 9))

    return kml(document(folder("Tenerife",
        pm("Faro", point(*c(0, 0))),
        pm("Muelle", point(*c(1, 1))),
        pm("Camino", line(c(0, 0), c(1, 1), c(2, 1))),
        pm("Parcela", polygon((c(3, 3), c(3.2, 3), c(3.2, 3.2), c(3, 3.2), c(3, 3)))),
    )))


def namespaces() -> dict[str, bytes]:
    body = document(pm("Un punto", point(*at(0, 0))))
    return {
        "ns20-bom.kml": kml(body, NS_EARTH20, bom=True),
        "ns22-ogc.kml": kml(body, NS22),
        "sin-ns.kml": kml(body, None),
        "ns30.kml": kml(body, "http://www.opengis.net/kml/3.0"),
    }


def broken() -> dict[str, bytes]:
    good = kml(document(pm("Un punto", point(*at(0, 0))), pm("Otro", point(*at(1, 1)))))
    return {
        "vacio.kml": b"",
        "espacios.kml": b"  \n\t \n",
        "texto.kml": "Esto no es un KML.\n".encode(),
        "no-es-kml.kml": '<?xml version="1.0"?><gpx version="1.1"><wpt lat="40" lon="-3"/></gpx>'.encode(),
        "danado.kml": good[: len(good) // 2],
        "sin-marcas.kml": kml(document(folder("Vacia"), folder("Notas", pm("Solo una nota", "", description="sin geometria")))),
        "solo-ignorados.kml": kml(document('<NetworkLink><name>V</name><Link><href>http://example.org/a.kml</href></Link></NetworkLink>')),
    }


def kmz(entries: list[tuple[str, bytes]]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return buffer.getvalue()


def kmz_files() -> dict[str, bytes]:
    def one(label: str, dx: float) -> bytes:
        return kml(document(pm(label, point(*at(dx, 0)))))

    fake_jpg = b"\xff\xd8\xff\xe0JFIF" + bytes(range(64))
    main = kml(document(pm("Punto del KMZ", point(*at(0, 0))), pm("Linea del KMZ", line(at(0, 0), at(1, 1)))))
    con_doc = kmz([("doc.kml", main), ("files/foto.jpg", fake_jpg), ("icono.png", b"\x89PNG\r\n\x1a\n" + bytes(32))])
    return {
        "con-doc.kmz": con_doc,
        "sin-doc.kmz": kmz([("b.kml", one("B", 1)), ("a.kml", one("A", 0)), ("sub/z.kml", one("Z", 2))]),
        "varios-kml.kmz": kmz([("otro.kml", one("Otro", 1)), ("doc.kml", one("Principal", 0))]),
        "mayusculas.kmz": kmz([("DOC.KML", one("En mayusculas", 0))]),
        "enlaces-internos.kmz": linked_kmz(),
        "sin-kml.kmz": kmz([("imagen.jpg", fake_jpg)]),
        "kml-en-subcarpeta.kmz": kmz([("sub/doc.kml", one("Escondido", 0))]),
        "kmz-sin-entradas.kmz": kmz([]),
        "danado.kmz": con_doc[: len(con_doc) // 2],
    }


def linked_kmz() -> bytes:
    def link(name: str | None, href: str, tag: str = "Link") -> str:
        head = f"<name>{name}</name>" if name is not None else ""
        return f"<NetworkLink>{head}<{tag}><href>{href}</href></{tag}></NetworkLink>"

    def doc(*children: str) -> bytes:
        return kml(document(*children))

    root = doc(
        link("Capa A", "layers/a.kml"),
        link(None, "layers/b.kml"),
        link("Con Url", "layers/u.kml", tag="Url"),
        folder("Carpeta", link("Anidado", "sub/c.kml"), pm("Marca de doc", point(*at(0, 0)))),
        link("Web", "http://example.org/otro.kml"),
        link("Falta", "layers/falta.kml"),
        link("Sale", "../fuera.kml"),
        link("Absoluto", "/layers/a.kml"),
        link("Al doc", "doc.kml"),
        link("Mayusculas", "LAYERS/A.KML"),
        link("Rota", "layers/rota.kml"),
    )
    a = doc(folder("Zona", pm("A1", point(*at(1, 0))), pm("A2", point(*at(2, 0)))), pm("Suelta de A", point(*at(3, 0))))
    b = doc(pm("B1", line(at(4, 0), at(5, 0))))
    u = doc(pm("U1", point(*at(6, 0))))
    c = doc(pm("C1", point(*at(7, 0))), link("Hondo", "d.kml"), link("Ciclo", "c.kml"))
    d = doc(pm("D1", point(*at(8, 0))), link("Mas hondo", "e.kml"))
    e = doc(pm("E1", point(*at(9, 0))), link("Demasiado hondo", "f.kml"))
    f = doc(pm("F1", point(*at(10, 0))))
    rota = b"<kml><Document>"
    return kmz([
        ("doc.kml", root), ("layers/a.kml", a), ("layers/b.kml", b), ("layers/u.kml", u), ("sub/c.kml", c), ("sub/d.kml", d),
        ("sub/e.kml", e), ("sub/f.kml", f), ("layers/rota.kml", rota),
    ])


def all_files() -> dict[str, bytes]:
    files: dict[str, bytes] = {
        "carpetas-anidadas.kml": nested_folders(),
        "geometrias.kml": geometries(),
        "atributos.kml": attributes(),
        "estilos.kml": styles(),
        "altitudes.kml": altitudes(),
        "ignorados.kml": ignored(),
        "fuera-de-ambito.kml": outside_area(),
        "canarias.kml": canarias(),
    }
    files.update(namespaces())
    files.update(broken())
    files.update(kmz_files())
    return dict(sorted(files.items()))


# ---------- lector de referencia ----------


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def kids(element: ET.Element, name: str) -> list[ET.Element]:
    return [c for c in element if local(c.tag) == name]


def first(element: ET.Element, name: str) -> ET.Element | None:
    found = kids(element, name)
    return found[0] if found else None


def text_of(element: ET.Element | None) -> str:
    return "" if element is None or element.text is None else element.text.strip()


def strip_html(raw: str) -> str:
    text = re.sub(r"<\s*br\s*/?\s*>|<\s*/\s*(p|div|li|tr)\s*>", "\n", raw, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]*>", "", text)
    text = html.unescape(text).replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(line.strip() for line in text.split("\n") if line.strip())


def parse_coordinates(element: ET.Element | None) -> list[tuple[float, float, float | None]] | None:
    """Los vértices de un `<coordinates>`, o None si alguna tupla no se entiende."""
    if element is None:
        return None
    out = []
    for token in (element.text or "").split():
        pieces = token.split(",")
        try:
            values = [float(p) for p in pieces]
        except ValueError:
            return None
        if len(values) < 2:
            return None
        out.append((values[0], values[1], values[2] if len(values) >= 3 else None))
    return out


@dataclass
class Part:
    vertices: list[tuple[float, float, float | None]]
    contour: bool = False
    absolute: bool = False

    @property
    def has_z(self) -> bool:
        return self.absolute and all(v[2] is not None for v in self.vertices)


@dataclass
class Feature:
    kind: str  # POINTS | LINES
    parts: list[Part] = field(default_factory=list)
    empty: bool = False
    name: str = ""
    description: str = ""
    data: dict[str, str] = field(default_factory=dict)
    color: str = ""

    @property
    def has_z(self) -> bool:
        return bool(self.parts) and all(p.has_z for p in self.parts)


@dataclass
class Layer:
    path: str
    kind: str
    features: list[Feature] = field(default_factory=list)
    columns: dict[str, str] = field(default_factory=dict)  # nombre crudo -> columna

    def column_names(self) -> list[str]:
        return ["name", "description", *self.columns.values()]


@dataclass
class Doc:
    result: str = "OK"
    principal: str = ""
    layers: list[Layer] = field(default_factory=list)
    ignored: dict[str, int] = field(default_factory=dict)

    def valid_objects(self) -> int:
        return sum(1 for layer in self.layers for f in layer.features if not f.empty)

    def has_elevations(self) -> bool:
        return any(f.has_z for layer in self.layers for f in layer.features)

    def layer_names(self) -> dict[tuple[str, str], str]:
        kinds: dict[str, set[str]] = {}
        for layer in self.layers:
            kinds.setdefault(layer.path, set()).add(layer.kind)
        suffix = {"POINTS": " (puntos)", "LINES": " (líneas)"}
        return {(l.path, l.kind): l.path + (suffix[l.kind] if len(kinds[l.path]) > 1 else "") for l in self.layers}


def hex_color(text: str) -> str:
    text = text.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{8}", text):
        return ""
    return "#" + (text[6:8] + text[4:6] + text[2:4]).upper()


def resolve_href(current: str, href: str, files: dict[str, bytes]) -> str | None:
    """El `.kml` del KMZ al que apunta un vínculo de red de `current`, o None si no es un vínculo interno."""
    href = href.strip().split("#", 1)[0]
    if not href or href.startswith(("/", "\\")) or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", href):
        return None
    target = posixpath.normpath(posixpath.join(posixpath.dirname(current), href))
    if target.startswith("..") or not target.lower().endswith(".kml"):
        return None
    if target in files:
        return target
    return next((name for name in files if name.lower() == target.lower()), None)


class Reader:
    def __init__(self, stem: str, files: dict[str, bytes] | None = None, current: str = "") -> None:
        self.stem = stem
        self.doc = Doc()
        self.layers: dict[tuple[str, str], Layer] = {}
        self.styles: dict[str, ET.Element] = {}
        self.files = files  # los ficheros de un KMZ; None en un KML suelto
        self.current = current  # el `.kml` del KMZ que se lee
        self.depth = 0
        self.visited: set[str] = {current}

    # estilos
    def resolve(self, ref: str, depth: int = 0) -> ET.Element | None:
        if not ref.startswith("#") or depth > 4:
            return None
        element = self.styles.get(ref[1:])
        if element is None:
            return None
        if local(element.tag) == "StyleMap":
            for pair in kids(element, "Pair"):
                if text_of(first(pair, "key")) == "normal":
                    inline = first(pair, "Style")
                    return inline if inline is not None else self.resolve(text_of(first(pair, "styleUrl")), depth + 1)
            return None
        return element

    def color_of(self, placemark: ET.Element, kind: str) -> str:
        style = first(placemark, "Style")
        if style is None:
            style = self.resolve(text_of(first(placemark, "styleUrl")))
        if style is None:
            return ""
        sub = first(style, "IconStyle" if kind == "POINTS" else "LineStyle")
        return hex_color(text_of(first(sub, "color"))) if sub is not None else ""

    # geometrías
    def flatten(self, element: ET.Element, out: list[ET.Element]) -> None:
        for child in element:
            name = local(child.tag)
            if name == "MultiGeometry":
                self.flatten(child, out)
            elif name in POINT_TAGS | LINE_TAGS:
                out.append(child)
            elif name in IGNORED_TAGS:
                self.count_ignored(name)

    def count_ignored(self, name: str) -> None:
        reason = IGNORED_TAGS[name]
        self.doc.ignored[reason] = self.doc.ignored.get(reason, 0) + 1

    def parts_of(self, geometry: ET.Element) -> list[Part] | None:
        """Las partes de una geometría, o None si está vacía."""
        name = local(geometry.tag)
        absolute = text_of(first(geometry, "altitudeMode")) == "absolute"
        if name == "Point":
            vertices = parse_coordinates(first(geometry, "coordinates"))
            return [Part(vertices[:1], False, absolute)] if vertices else None
        if name == "LineString":
            vertices = parse_coordinates(first(geometry, "coordinates"))
            return [Part(vertices, False, absolute)] if vertices and len(vertices) >= 2 else None
        if name == "LinearRing":
            vertices = parse_coordinates(first(geometry, "coordinates"))
            return [Part(vertices, False, absolute)] if vertices and len(vertices) >= 4 else None
        outer_holder = first(geometry, "outerBoundaryIs")
        outer_ring = first(outer_holder, "LinearRing") if outer_holder is not None else None
        outer = parse_coordinates(first(outer_ring, "coordinates")) if outer_ring is not None else None
        if not outer or len(outer) < 4:
            return None
        parts = [Part(outer, True, absolute)]
        for holder in kids(geometry, "innerBoundaryIs"):
            hole_ring = first(holder, "LinearRing")
            hole = parse_coordinates(first(hole_ring, "coordinates")) if hole_ring is not None else None
            if hole and len(hole) >= 4:
                parts.append(Part(hole, True, absolute))
        return parts

    # atributos
    def attributes(self, placemark: ET.Element) -> dict[str, str]:
        data: dict[str, str] = {}
        extended = first(placemark, "ExtendedData")
        if extended is not None:
            for item in kids(extended, "Data"):
                data[item.get("name", "")] = text_of(first(item, "value"))
            for schema in kids(extended, "SchemaData"):
                for item in kids(schema, "SimpleData"):
                    data[item.get("name", "")] = text_of(item)
        return {k: v for k, v in data.items() if k}

    def add(self, path: str, kind: str, feature: Feature, data: dict[str, str]) -> None:
        layer = self.layers.get((path, kind))
        if layer is None:
            layer = self.layers[(path, kind)] = Layer(path, kind)
            self.doc.layers.append(layer)
        taken = {"name", "description", *layer.columns.values()}
        for raw in data:
            if raw in layer.columns:
                continue
            column, n = raw, 2
            while column in taken:
                column, n = f"{raw}_{n}", n + 1
            layer.columns[raw] = column
            taken.add(column)
        feature.data = {layer.columns[raw]: value for raw, value in data.items() if value != ""}
        layer.features.append(feature)

    def placemark(self, element: ET.Element, path: str) -> None:
        geometries: list[ET.Element] = []
        self.flatten(element, geometries)
        if not geometries:
            return
        name = text_of(first(element, "name"))
        description = strip_html(first(element, "description").text or "") if first(element, "description") is not None else ""
        data = self.attributes(element)
        for kind, tags in (("POINTS", POINT_TAGS), ("LINES", LINE_TAGS)):
            valid: list[Part] = []
            empties = 0
            for geometry in geometries:
                if local(geometry.tag) not in tags:
                    continue
                parts = self.parts_of(geometry)
                if parts is None:
                    empties += 1
                else:
                    valid += parts
            color = self.color_of(element, kind)
            if valid:
                self.add(path or self.stem, kind, Feature(kind, valid, False, name, description, color=color), data)
            for _ in range(empties):
                self.add(path or self.stem, kind, Feature(kind, [], True, name, description, color=color), data)

    # vínculos internos
    def follow(self, link: ET.Element, segments: list[str]) -> bool:
        if self.files is None or self.depth >= MAX_LINK_DEPTH:
            return False
        node = first(link, "Link")
        if node is None:
            node = first(link, "Url")
        href = text_of(first(node, "href")) if node is not None else ""
        target = resolve_href(self.current, href, self.files)
        if target is None or target in self.visited:
            return False
        try:
            root = ET.fromstring(self.files[target])
        except ET.ParseError:
            return False
        if not is_kml_root(root):
            return False
        self.visited.add(target)
        label = text_of(first(link, "name")) or posixpath.basename(target).rsplit(".", 1)[0]
        parent = (self.current, self.depth, self.styles)
        self.current, self.depth, self.styles = target, self.depth + 1, {}
        self.run(root, segments + [label])
        self.current, self.depth, self.styles = parent
        return True

    # recorrido
    def walk(self, element: ET.Element, segments: list[str]) -> None:
        for child in element:
            name = local(child.tag)
            if name in ("Style", "StyleMap"):
                if child.get("id"):
                    self.styles[child.get("id", "")] = child
            elif name in ("Folder", "Document"):
                label = text_of(first(child, "name"))
                self.walk(child, segments + ([label] if label else []))
            elif name == "Placemark":
                self.placemark(child, " / ".join(segments))
            elif name == "NetworkLink":
                if not self.follow(child, segments):
                    self.count_ignored(name)
            elif name in IGNORED_TAGS:
                self.count_ignored(name)

    def run(self, root: ET.Element, base: list[str] | None = None) -> Doc:
        base = base or []
        for child in root:
            name = local(child.tag)
            if name == "Document":
                self.walk(child, base)
            elif name in ("Style", "StyleMap"):
                if child.get("id"):
                    self.styles[child.get("id", "")] = child
            elif name == "Folder":
                self.walk(_wrap(child), base)
            elif name == "Placemark":
                self.placemark(child, " / ".join(base))
            elif name == "NetworkLink":
                if not self.follow(child, base):
                    self.count_ignored(name)
            elif name in IGNORED_TAGS:
                self.count_ignored(name)
        return self.doc


def _wrap(folder_element: ET.Element) -> ET.Element:
    holder = ET.Element("holder")
    holder.append(folder_element)
    return holder


def is_kml_root(root: ET.Element) -> bool:
    namespace = root.tag[1:].split("}")[0] if root.tag.startswith("{") else ""
    return local(root.tag) == "kml" and (not namespace or "/kml/2." in namespace)


def read_kml(data: bytes, stem: str, files: dict[str, bytes] | None = None, current: str = "") -> Doc:
    """Lee un KML (sus bytes) como lo especifican las reglas de este conjunto; `files` son los de su KMZ, si lo es."""
    if not data.strip():
        return Doc("EMPTY")
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        head = data.lstrip(BOM).lstrip()[:200]
        return Doc("DAMAGED" if re.match(rb"(<\?xml[^>]*\?>\s*)?<kml[\s>]", head) else "NOT_KML")
    if not is_kml_root(root):
        return Doc("NOT_KML")
    doc = Reader(stem, files, current).run(root)
    if doc.valid_objects() == 0:
        doc.result = "NO_PLACEMARKS"
    return doc


def read_file(name: str, data: bytes) -> Doc:
    stem = name.rsplit(".", 1)[0]
    if not name.endswith(".kmz"):
        return read_kml(data, stem)
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
        names = [n for n in z.namelist() if "/" not in n and n.lower().endswith(".kml")]
    except zipfile.BadZipFile:
        return Doc("DAMAGED")
    if not names:
        return Doc("NOT_KML")
    main = next((n for n in names if n.lower() == "doc.kml"), None) or sorted(names, key=str.lower)[0]
    files = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
    doc = read_kml(files[main], stem, files, main)
    doc.principal = main
    return doc


# ---------- lo esperado ----------


def regcan95(lat: float, lon: float) -> tuple[float, float] | None:
    crs = g.MANUAL[4083]
    return crs.typed_from_base(lat, lon, "PROJECT_CRS") if crs.inside(lat, lon) else None


def vertex_text(v: tuple, z: bool) -> str:
    return f"{num(v[0])} {num(v[1])} {num(v[2]) if z else '_'}"


def project_text(p: tuple[float, float], v: tuple, z: bool) -> str:
    return f"{num(p[0])} {num(p[1])} {num(v[2]) if z else '_'}"


def describe(name: str, doc: Doc, projector, out: list[list]) -> None:
    names = doc.layer_names()
    for layer in doc.layers:
        layer_name = names[(layer.path, layer.kind)]
        for fid, f in enumerate(layer.features, 1):
            key = [name, layer_name, fid, layer.kind]
            if f.empty:
                out.append(key + ["", "", "", "", f.color, "ignorado_vacia"])
                continue
            converted = [[projector(v[1], v[0]) for v in p.vertices] for p in f.parts]
            if any(c is None for part in converted for c in part):
                out.append(key + ["", "", "", "", f.color, "fuera_de_ambito"])
                continue
            z = f.has_z
            for index, (part, proj) in enumerate(zip(f.parts, converted), 1):
                source = ";".join(vertex_text(v, z) for v in part.vertices)
                target = ";".join(project_text(c, v, z) for c, v in zip(proj, part.vertices))
                out.append(key + [index, "SI" if part.contour else "NO", source, target, f.color, "OK"])


def etrs89_utm30(lat: float, lon: float) -> tuple[float, float] | None:
    return g.project_of(4326, lon, lat)


def expected(files: dict[str, bytes]) -> dict[str, bytes]:
    docs = {name: read_file(name, data) for name, data in files.items()}
    archivos, capas, objetos, regcan, atributos = [], [], [], [], []
    for name, doc in docs.items():
        archivos.append([
            name, "KMZ" if name.endswith(".kmz") else "KML", doc.result, doc.principal,
            doc.valid_objects() if doc.result == "OK" else 0,
            "SI" if doc.has_elevations() else "NO",
            ";".join(f"{k}={v}" for k, v in sorted(doc.ignored.items())),
        ])
        if doc.result != "OK":
            continue
        names = doc.layer_names()
        for layer in doc.layers:
            layer_name = names[(layer.path, layer.kind)]
            capas.append([name, layer_name, layer.kind, len(layer.features), ";".join(layer.column_names())])
            for fid, f in enumerate(layer.features, 1):
                if f.empty:
                    continue
                values = {"name": f.name, "description": f.description, **f.data}
                for column, value in values.items():
                    if value != "":
                        atributos.append([name, layer_name, fid, column, value])
        describe(name, doc, etrs89_utm30, objetos)
        if name == "canarias.kml":
            describe(name, doc, regcan95, regcan)
    header = ["archivo", "capa", "fid", "tipo", "parte", "contorno", "vertices_kml", "vertices_proyecto", "color", "estado"]
    result: dict[str, bytes] = {}
    for file, head, rows in (
        ("esperado-archivos.csv", ["archivo", "formato", "resultado", "principal", "objetos", "altitudes_absolutas", "ignorados"], archivos),
        ("esperado-capas.csv", ["archivo", "capa", "tipo", "objetos", "columnas"], capas),
        ("esperado.csv", header, objetos),
        ("esperado-regcan95.csv", header, regcan),
        ("esperado-atributos.csv", ["archivo", "capa", "fid", "columna", "valor"], atributos),
    ):
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="\n")
        writer.writerow(head)
        writer.writerows(rows)
        result[file] = buffer.getvalue().encode("utf-8")
    return result


def generate_all() -> dict[str, bytes]:
    files = all_files()
    return {**files, **expected(files)}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    produced = generate_all()
    for name, data in produced.items():
        (OUT / name).write_bytes(data)
    stale = [p for p in OUT.iterdir() if p.is_file() and p.name not in produced]
    for p in stale:
        p.unlink()
    print(f"{len(produced)} ficheros en {OUT}")


if __name__ == "__main__":
    main()
