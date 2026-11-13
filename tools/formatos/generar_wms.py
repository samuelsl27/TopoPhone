"""Referencias de la lectura de GetCapabilities de un servicio WMS y de los cuadros envolventes en EPSG:3857 (S036/RF-2, RF-3, RF-9, RF-11, RF-12, RF-29).

Genera en fixtures/formatos/wms/:

- Los documentos de capacidades sintéticos de los casos límite (`caps-*.xml`) y los que no son capacidades (`error-*`): 1.1.1 y 1.3.0, capas
  anidadas, `DOCTYPE` con subconjunto interno en ISO-8859-1 como el del Catastro, un prefijo sin declarar como el del 1.1.1 de Andalucía, CRS
  heredados, alias de Web Mercator, nombres con espacios y barras verticales, excepciones de servicio, una página HTML con HTTP 200, XML dañado,
  vacío y otro XML.
- `esperado-wms.txt`: lo que debe leer `WmsCapabilitiesReader` de cada uno. Lo calcula `read_capabilities`, un lector de referencia con
  `xml.parsers.expat` sin procesar espacios de nombres (como el de Kotlin, que ignora los prefijos), distinto del lector de la app.
- `esperado-cajas.txt`: unas cajas en grados con su cuadro envolvente en EPSG:3857, calculado con pyproj (constitución nº 2; ≤ 1 cm).

Formato de `esperado-wms.txt`, una línea por elemento, campos separados por `|`, con `\\p` por un `|` y `\\n` por un salto de línea en un texto:
    service|<archivo>|<ok|MALFORMED|NOT_CAPABILITIES|SERVICE_EXCEPTION>|<versión>|<título>|<atribución>|<formatos GetMap, con ;>|<formatos
    GetFeatureInfo, con ;>|<mensaje de la excepción>
    layer|<archivo>|<ordinal>|<profundidad>|<nombre o vacío>|<título>|<resumen>|<transparente 0|1>|<consultable 0|1>|<código de Web Mercator o vacío>

Reglas que la spec no fija y que quedan escritas aquí:
- Las capas se numeran en el orden del documento y solo se leen las de los 10 primeros niveles (profundidad 0 a 9); las más profundas se ignoran.
- `queryable` y `opaque` (de ahí «transparente») se heredan del ascendiente si la capa no los declara; por defecto, no consultable y transparente.
- Los CRS de una capa son los suyos más los de sus ascendientes, en ese orden de aparición; de ellos vale `EPSG:3857` si está y si no el primer alias
  de Web Mercator (`EPSG:900913`, `EPSG:102100`, `EPSG:102113`, `EPSG:3785`). En 1.1.1 un `SRS` puede llevar varios códigos separados por espacios o comas.
- La atribución del servicio es el primer `Attribution/Title` del documento, o vacía.

Uso: uv run --project tools tools/formatos/generar_wms.py
"""

from __future__ import annotations

import math
import sys
import xml.parsers.expat
from dataclasses import dataclass, field
from pathlib import Path

import pyproj

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "wms"
MAX_DEPTH = 10
ALIASES = ("EPSG:900913", "EPSG:102100", "EPSG:102113", "EPSG:3785")
EXPECTED = "esperado-wms.txt"
BOXES = "esperado-cajas.txt"

# --- el lector de referencia -------------------------------------------------------------------------------------------


@dataclass
class Node:
    name: str
    attrs: dict[str, str]
    children: list[Node] = field(default_factory=list)
    text: str = ""


def parse_tree(data: bytes) -> Node:
    """Un árbol de elementos con nombres locales, sin procesar espacios de nombres (un prefijo sin declarar no es un error)."""
    parser = xml.parsers.expat.ParserCreate()
    stack: list[Node] = [Node("#doc", {})]

    def start(name: str, attrs: dict[str, str]) -> None:
        node = Node(name.split(":")[-1], {k.split(":")[-1]: v for k, v in attrs.items()})
        stack[-1].children.append(node)
        stack.append(node)

    def end(name: str) -> None:
        stack.pop()

    def chars(data_: str) -> None:
        stack[-1].text += data_

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = chars
    parser.Parse(data, True)
    return stack[0].children[0]


def child(node: Node, name: str) -> Node | None:
    return next((c for c in node.children if c.name == name), None)


def children(node: Node, name: str) -> list[Node]:
    return [c for c in node.children if c.name == name]


def text_of(node: Node | None) -> str:
    return node.text.strip() if node is not None else ""


def find_all(node: Node, name: str) -> list[Node]:
    out = [node] if node.name == name else []
    for c in node.children:
        out += find_all(c, name)
    return out


def codes_of(layer: Node) -> list[str]:
    out: list[str] = []
    for tag in ("CRS", "SRS"):
        for c in children(layer, tag):
            out += [t for t in c.text.replace(",", " ").split() if t]
    return out


def preferred(codes: list[str]) -> str:
    upper = [c.strip().upper() for c in codes]
    if "EPSG:3857" in upper:
        return "EPSG:3857"
    return next((c for c in upper if c in ALIASES), "")


def flag(node: Node, name: str, inherited: bool) -> bool:
    value = node.attrs.get(name)
    return inherited if value is None else value.strip().lower() in ("1", "true")


def read_layers(layer: Node, depth: int, inherited_codes: list[str], queryable: bool, opaque: bool, out: list[dict]) -> None:
    if depth >= MAX_DEPTH:
        return
    codes = inherited_codes + codes_of(layer)
    queryable = flag(layer, "queryable", queryable)
    opaque = flag(layer, "opaque", opaque)
    name = child(layer, "Name")
    out.append(
        {
            "ordinal": len(out),
            "depth": depth,
            "name": text_of(name) if name is not None else "",
            "title": text_of(child(layer, "Title")),
            "abstract": text_of(child(layer, "Abstract")),
            "transparent": not opaque,
            "queryable": queryable,
            "code": preferred(codes),
        }
    )
    for sub in children(layer, "Layer"):
        read_layers(sub, depth + 1, codes, queryable, opaque, out)


def read_capabilities(data: bytes) -> dict:
    """Lo que debe leer la app de un documento: su estado y, si es de capacidades, el servicio y sus capas."""
    try:
        root = parse_tree(data)
    except (xml.parsers.expat.ExpatError, IndexError):
        return {"state": "MALFORMED"}
    if root.name == "ServiceExceptionReport":
        messages = [c.text.strip() for c in find_all(root, "ServiceException")]
        return {"state": "SERVICE_EXCEPTION", "message": " ".join(m for m in messages if m)}
    if root.name not in ("WMS_Capabilities", "WMT_MS_Capabilities"):
        return {"state": "NOT_CAPABILITIES"}
    capability = child(root, "Capability")
    request = child(capability, "Request") if capability is not None else None

    def formats(request_name: str) -> list[str]:
        node = child(request, request_name) if request is not None else None
        return [c.text.strip() for c in children(node, "Format")] if node is not None else []

    layers: list[dict] = []
    if capability is not None:
        for top in children(capability, "Layer"):
            read_layers(top, 0, [], False, False, layers)
    attribution = next((text_of(child(a, "Title")) for a in find_all(root, "Attribution")), "")
    return {
        "state": "ok",
        "version": root.attrs.get("version", ""),
        "title": text_of(child(child(root, "Service"), "Title")) if child(root, "Service") is not None else "",
        "attribution": attribution,
        "map_formats": formats("GetMap"),
        "info_formats": formats("GetFeatureInfo"),
        "layers": layers,
    }


# --- los documentos sintéticos --------------------------------------------------------------------------------------------

NS13 = 'xmlns="http://www.opengis.net/wms" xmlns:xlink="http://www.w3.org/1999/xlink"'
FORMATS = ("image/png", "image/jpeg")
INFO = ("text/plain", "text/html", "application/vnd.ogc.gml")


def layer13(name: str | None, title: str, crs: list[str], attrs: str = "", inner: str = "", abstract: str = "", extra: str = "") -> str:
    name_xml = f"<Name>{name}</Name>" if name is not None else ""
    abstract_xml = f"<Abstract>{abstract}</Abstract>" if abstract else ""
    crs_xml = "".join(f"<CRS>{c}</CRS>" for c in crs)
    return f"<Layer{attrs}>{name_xml}<Title>{title}</Title>{abstract_xml}{crs_xml}{extra}{inner}</Layer>"


def caps13(title: str, root_layer: str, formats=FORMATS, info=INFO) -> str:
    request = "".join(f"<Format>{f}</Format>" for f in formats)
    info_xml = "".join(f"<Format>{f}</Format>" for f in info)
    return (
        f'<?xml version="1.0" encoding="UTF-8"?>\n<WMS_Capabilities version="1.3.0" {NS13}>\n'
        f"<Service><Name>WMS</Name><Title>{title}</Title></Service>\n"
        f"<Capability><Request><GetCapabilities><Format>text/xml</Format></GetCapabilities>"
        f"<GetMap>{request}</GetMap><GetFeatureInfo>{info_xml}</GetFeatureInfo></Request>\n"
        f"{root_layer}\n</Capability></WMS_Capabilities>\n"
    )


def caps111(title: str, root_layer: str, doctype: str, encoding: str = "UTF-8", formats=FORMATS, info=("text/plain", "text/html")) -> bytes:
    request = "".join(f"<Format>{f}</Format>" for f in formats)
    info_xml = "".join(f"<Format>{f}</Format>" for f in info)
    text = (
        f'<?xml version="1.0" encoding="{encoding}" standalone="no" ?>\n{doctype}\n<WMT_MS_Capabilities version="1.1.1">\n'
        f"<Service><Name>OGC:WMS</Name><Title>{title}</Title></Service>\n"
        f"<Capability><Request><GetCapabilities><Format>application/vnd.ogc.wms_xml</Format></GetCapabilities>"
        f"<GetMap>{request}</GetMap><GetFeatureInfo>{info_xml}</GetFeatureInfo></Request>\n"
        f"{root_layer}\n</Capability></WMT_MS_Capabilities>\n"
    )
    return text.encode(encoding)


DOCTYPE_EXTERNAL = '<!DOCTYPE WMT_MS_Capabilities SYSTEM "http://schemas.opengis.net/wms/1.1.1/WMS_MS_Capabilities.dtd">'
DOCTYPE_INTERNAL = (
    '<!DOCTYPE WMT_MS_Capabilities SYSTEM "https://ovc.catastro.meh.es/Cartografia/WMS/DTD/capabilities_1_1_1.dtd" [\n'
    "<!ELEMENT VendorSpecificCapabilities EMPTY>\n]>"
)


def layer111(name: str | None, title: str, srs: list[str], attrs: str = "", inner: str = "") -> str:
    name_xml = f"<Name>{name}</Name>" if name is not None else ""
    srs_xml = "".join(f"<SRS>{s}</SRS>" for s in srs)
    return f"<Layer{attrs}>{name_xml}<Title>{title}</Title>{srs_xml}{inner}</Layer>"


def nested(levels: int, crs_at_root: bool = True) -> str:
    """Capas anidadas `levels` niveles (una por nivel): Nivel 1 (grupo) contiene Nivel 2, y así hasta la hoja."""
    inner = ""
    for level in range(levels, 0, -1):
        name = f"nivel-{level}" if level % 2 == 0 else None  # alterna capas que solo agrupan y capas con nombre
        crs = ["EPSG:4326", "EPSG:3857"] if level == 1 and crs_at_root else []
        inner = layer13(name, f"Nivel {level}", crs, inner=inner)
    return inner


def documents() -> dict[str, bytes]:
    docs: dict[str, bytes] = {}

    def put(name: str, text: str, encoding: str = "utf-8") -> None:
        docs[name] = text.encode(encoding)

    # 1.3.0 básico: atribución en la capa raíz, formatos y consulta heredada
    root = layer13(
        "raiz", "Ortofoto de ejemplo", ["EPSG:4326", "EPSG:3857"], attrs=' queryable="1"', abstract="Resumen de la raíz",
        extra="<Attribution><Title>© Servicio de ejemplo</Title><OnlineResource xlink:href=\"https://ejemplo.es\"/></Attribution>",
        inner=layer13("rgb", "Color", [], abstract="Imagen en color") + layer13("irg", "Infrarrojo", [], attrs=' opaque="1" queryable="0"'),
    )
    put("caps-1.3.0-basico.xml", caps13("Servicio de ejemplo", root))

    # 1.1.1 con DOCTYPE externo
    docs["caps-1.1.1-basico.xml"] = caps111(
        "Servicio de ejemplo 1.1.1",
        layer111("raiz", "Raíz", ["EPSG:4326 EPSG:3857"], inner=layer111("capa-a", "Capa A", [], attrs=' queryable="1"') + layer111("capa-b", "Capa B", ["EPSG:25830"])),
        DOCTYPE_EXTERNAL,
    )

    # 1.1.1 como el del Catastro: ISO-8859-1 y DOCTYPE con subconjunto interno
    docs["caps-1.1.1-doctype-interno.xml"] = caps111(
        "Cartografía catastral",
        layer111("Catastro", "Cartografía catastral: parcelas y edificios", ["EPSG:4326", "EPSG:3857", "EPSG:25830"], attrs=' queryable="1"'),
        DOCTYPE_INTERNAL,
        encoding="iso-8859-1",
        info=("text/html", "text/xml"),
    )

    # 1.1.1 con un prefijo sin declarar (Andalucía): no es XML con espacios de nombres válido, pero sí bien formado sin ellos
    sin_prefijo = caps111(
        "Ráster 1:10.000",
        layer111("mta10r", "Mapa topográfico", ["EPSG:23030", "EPSG:3857"], inner='<Extra xsi:type="inspire:Algo">dato</Extra>'),
        DOCTYPE_EXTERNAL,
    )
    docs["caps-1.1.1-prefijo-sin-declarar.xml"] = sin_prefijo

    # anidadas: 10 niveles (se leen todos) y 12 (se ignoran los dos últimos)
    put("caps-1.3.0-anidadas-10.xml", caps13("Anidadas 10", nested(10)))
    put("caps-1.3.0-anidadas-12.xml", caps13("Anidadas 12", nested(12)))

    # alias de Web Mercator y capas sin 3857
    alias_layers = (
        layer13("a-3785", "Solo 3785", ["EPSG:4326", "EPSG:3785"])
        + layer13("a-102113", "Solo 102113", ["EPSG:102113"])
        + layer13("a-102100", "102100 y 900913", ["EPSG:900913", "EPSG:102100"])
        + layer13("a-900913", "Solo 900913", ["EPSG:900913"])
        + layer13("a-ambos", "3857 y alias", ["EPSG:3785", "EPSG:3857"])
        + layer13("a-minus", "En minúsculas", ["epsg:3857"])
        + layer13("a-25830", "Sin 3857", ["EPSG:25830", "EPSG:4326"])
    )
    put("caps-1.3.0-alias.xml", caps13("Alias", layer13(None, "Raíz de alias", [], inner=alias_layers)))
    put("caps-1.3.0-sin-3857.xml", caps13("Sin 3857", layer13("sola", "Solo UTM", ["EPSG:25830", "EPSG:4326"])))

    # capas que solo agrupan, con CRS y atributos heredados
    grupos = layer13(
        None, "Grupo sin nombre", ["EPSG:3857"], attrs=' queryable="1"',
        inner=layer13("hija-1", "Hija 1", []) + layer13(None, "Subgrupo", [], inner=layer13("nieta", "Nieta", [], attrs=' queryable="0"')),
    )
    put("caps-1.3.0-sin-nombre.xml", caps13("Con grupos", grupos))

    # nombres raros: espacios, barras verticales, solo números, repetidos entre grupo e hijo, títulos trilingües
    raros = layer13(
        "OWS_0101_BCV05", "Cartografía 1:5.000", ["EPSG:3857"],
        inner=(
            layer13("Usos del suelo | Usos del sòl | Land use", "Usos del suelo | Usos del sòl | Land use", [])
            + layer13("0", "Capa numérica", [])
            + layer13("repetida", "Grupo repetido", [], inner=layer13("repetida", "Hija con el mismo nombre", []))
            + layer13("con &amp; ampersand", "Título con &lt;etiqueta&gt; y &amp; más", [], abstract="Línea 1\nLínea 2")
        ),
    )
    put("caps-1.3.0-nombres-raros.xml", caps13("Nombres raros", raros))

    # sin atribución y sin formatos de consulta
    put(
        "caps-1.3.0-sin-atribucion.xml",
        caps13("Sin atribución", layer13("sola", "Capa sola", ["EPSG:3857"]), info=()),
    )

    # errores
    put(
        "error-excepcion-1.3.0.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n<ServiceExceptionReport version="1.3.0" xmlns="http://www.opengis.net/ogc">\n'
        '<ServiceException code="InvalidParameterValue">Version 1.3.0 not supported</ServiceException>\n</ServiceExceptionReport>\n',
    )
    docs["error-excepcion-1.1.1.xml"] = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no" ?>\n'
        '<!DOCTYPE ServiceExceptionReport SYSTEM "http://schemas.opengis.net/wms/1.1.1/WMS_exception_1_1_1.dtd">\n'
        '<ServiceExceptionReport version="1.1.1"><ServiceException code="NoApplicableCode">Parcela no encontrada</ServiceException>'
        "</ServiceExceptionReport>\n"
    ).encode()
    put("error-html-200.html", '<html><head><title>Servei</title></head><body><h1>SERVEI NO DISPONIBLE PER MANTENIMENT</h1><br></body></html>\n')
    docs["error-xml-danado.xml"] = caps13("Cortado", layer13("a", "A", ["EPSG:3857"]))[:400].encode()
    docs["error-vacio.xml"] = b""
    put("error-otro-xml.xml", '<?xml version="1.0"?>\n<respuesta><estado>ok</estado></respuesta>\n')
    return docs


# --- lo esperado ------------------------------------------------------------------------------------------------------


def field_text(text: str) -> str:
    return text.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")


def expected_lines(docs: dict[str, bytes]) -> list[str]:
    lines: list[str] = []
    for name in sorted(docs):
        data = read_capabilities(docs[name])
        if data["state"] != "ok":
            lines.append(f"service|{name}|{data['state']}||||||{field_text(data.get('message', ''))}")
            continue
        lines.append(
            "service|%s|ok|%s|%s|%s|%s|%s|"
            % (
                name,
                data["version"],
                field_text(data["title"]),
                field_text(data["attribution"]),
                ";".join(data["map_formats"]),
                ";".join(data["info_formats"]),
            )
        )
        for layer in data["layers"]:
            lines.append(
                "layer|%s|%d|%d|%s|%s|%s|%d|%d|%s"
                % (
                    name,
                    layer["ordinal"],
                    layer["depth"],
                    field_text(layer["name"]),
                    field_text(layer["title"]),
                    field_text(layer["abstract"]),
                    int(layer["transparent"]),
                    int(layer["queryable"]),
                    layer["code"],
                )
            )
    return lines


# Cajas en grados (sur, oeste, norte, este): la Península, Baleares, Canarias, el cinturón de Castellón y casos de los extremos.
BOXES_DEG = [
    (36.0, -9.5, 43.8, 3.4),
    (39.9, 1.2, 40.1, 4.4),
    (27.6, -18.2, 29.5, -13.3),
    (39.9800, -0.0600, 39.9900, -0.0500),
    (39.98640000, -0.05130000, 39.98650000, -0.05120000),
    (-1.0, -1.0, 1.0, 1.0),
    (-60.0, -179.0, -59.0, -178.0),
    (84.0, 170.0, 85.0, 179.9),
    (41.3, 2.1, 41.5, 2.3),
    (42.8, -2.7, 42.9, -2.6),
    (37.3, -6.0, 37.5, -5.9),
    (28.4, -16.3, 28.5, -16.2),
]


def boxes() -> list[tuple]:
    t = pyproj.Transformer.from_crs("EPSG:4326", "EPSG:3857", always_xy=True)
    out = []
    for south, west, north, east in BOXES_DEG:
        x0, y0 = t.transform(west, south)
        x1, y1 = t.transform(east, north)
        out.append((south, west, north, east, x0, y0, x1, y1))
    return out


def box_lines() -> list[str]:
    return ["caja|" + "|".join(repr(v) for v in b) for b in boxes()]


def generate_all() -> dict[str, bytes]:
    docs = documents()
    out = dict(docs)
    out[EXPECTED] = ("\n".join(expected_lines(docs)) + "\n").encode("utf-8")
    out[BOXES] = ("\n".join(box_lines()) + "\n").encode("utf-8")
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    produced = generate_all()
    for name, data in produced.items():
        (OUT / name).write_bytes(data)
    for stale in [p for p in OUT.iterdir() if p.is_file() and p.name not in produced]:
        stale.unlink()
    print(f"{len(produced)} ficheros en {OUT}")


if __name__ == "__main__":
    sys.exit(main())
