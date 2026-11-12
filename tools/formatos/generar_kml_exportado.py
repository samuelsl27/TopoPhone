"""KML de referencia de la exportación a KML y KMZ y lo que debe escribir el escritor de la app (S031/RF-21 a RF-29).

Genera en fixtures/formatos/kml-exportado/ el documento de entrada de cada ejemplo (`entrada-<ejemplo>.txt`) y el KML que debe salir de él
(`<ejemplo>.kml`), byte a byte. Lo escribe esta plantilla, que es independiente del escritor de Kotlin: los tests de `core:formats` leen la misma
entrada, escriben con `KmlWriter` y comparan con lo que sale de aquí.

Formato de la entrada, una línea por elemento, campos separados por `|` y `\\n` por un salto de línea y `\\p` por un `|` dentro de un texto:
    doc|<nombre del documento>
    style|<id>|<color aabbggrr>              un estilo de código: IconStyle y LineStyle con ese color y PolyStyle con él y alfa 80
    folder|<nombre de la carpeta>
    mark|<point|line|polygon>|<nombre>|<descripcion>|<id de estilo o vacío>|<absolute|clamp>|<fotos separadas por ;>
    data|<nombre>|<valor>                    un dato extendido de la marca anterior (valor vacío: `<value/>`)
    vertex|<lon>|<lat>|<alt o vacío>         un vértice de la marca anterior, ya con su texto final

Reglas de la plantilla:
- Sangría de 2 espacios, fin de línea LF y salto de línea final. `<altitudeMode>absolute</altitudeMode>` solo con `absolute`.
- Las coordenadas van como `lon,lat[,alt]` separadas por un espacio, con el texto de cada vértice tal cual está en la entrada; la altura solo
  se escribe en las marcas `absolute`.
- El texto de nombres, descripciones y valores escapa `&`, `<` y `>`; un atributo escapa además `"`.
- Una marca con fotos lleva la descripción en CDATA: el texto con `&`, `<` y `>` escapados como HTML y sus saltos de línea como `<br/>`, y
  después un `<br/>` y un `<img src="files/<foto>" width="400"/>` por foto.
- Una descripción vacía no escribe el elemento.

Uso: uv run --project tools tools/formatos/generar_kml_exportado.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "kml-exportado"
IMG_WIDTH = 400


@dataclass
class Mark:
    kind: str
    name: str
    description: str
    style: str
    mode: str
    photos: list[str]
    data: list[tuple[str, str]] = field(default_factory=list)
    vertices: list[tuple[str, str, str]] = field(default_factory=list)


@dataclass
class Folder:
    name: str
    marks: list[Mark] = field(default_factory=list)


@dataclass
class Document:
    name: str = ""
    styles: list[tuple[str, str]] = field(default_factory=list)
    folders: list[Folder] = field(default_factory=list)


def unescape_field(text: str) -> str:
    return text.replace("\\n", "\n").replace("\\p", "|")


def parse(text: str) -> Document:
    doc = Document()
    for raw in text.split("\n"):
        if not raw.strip():
            continue
        parts = raw.split("|")
        tag = parts[0]
        if tag == "doc":
            doc.name = unescape_field(parts[1])
        elif tag == "style":
            doc.styles.append((parts[1], parts[2]))
        elif tag == "folder":
            doc.folders.append(Folder(unescape_field(parts[1])))
        elif tag == "mark":
            photos = [p for p in parts[6].split(";") if p] if len(parts) > 6 else []
            doc.folders[-1].marks.append(
                Mark(parts[1], unescape_field(parts[2]), unescape_field(parts[3]), parts[4], parts[5], photos)
            )
        elif tag == "data":
            doc.folders[-1].marks[-1].data.append((unescape_field(parts[1]), unescape_field(parts[2])))
        elif tag == "vertex":
            doc.folders[-1].marks[-1].vertices.append((parts[1], parts[2], parts[3]))
        else:
            raise ValueError(f"Línea desconocida: {raw}")
    return doc


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def esc_attr(text: str) -> str:
    return esc(text).replace('"', "&quot;")


def coordinates(mark: Mark) -> str:
    """Los vértices de la marca: la altura solo va si la marca es `absolute`; una pegada al suelo no la escribe aunque la entrada la traiga."""
    keep = mark.mode == "absolute"
    return " ".join(",".join(c for c in (v[0], v[1], v[2] if keep else "") if c != "") for v in mark.vertices)


def description(mark: Mark) -> str | None:
    if mark.photos:
        html = esc(mark.description).replace("\n", "<br/>")
        imgs = "".join(f'<br/><img src="files/{esc_attr(p)}" width="{IMG_WIDTH}"/>' for p in mark.photos)
        return f"<![CDATA[{html}{imgs}]]>"
    return esc(mark.description) if mark.description else None


def render_mark(mark: Mark, indent: str) -> list[str]:
    i = indent
    out = [f"{i}<Placemark>", f"{i}  <name>{esc(mark.name)}</name>"]
    text = description(mark)
    if text is not None:
        out.append(f"{i}  <description>{text}</description>")
    if mark.style:
        out.append(f"{i}  <styleUrl>#{esc(mark.style)}</styleUrl>")
    if mark.data:
        out.append(f"{i}  <ExtendedData>")
        for name, value in mark.data:
            body = f"<value>{esc(value)}</value>" if value else "<value/>"
            out.append(f'{i}    <Data name="{esc_attr(name)}">{body}</Data>')
        out.append(f"{i}  </ExtendedData>")
    mode = [f"{i}    <altitudeMode>absolute</altitudeMode>"] if mark.mode == "absolute" else []
    coords = f"<coordinates>{coordinates(mark)}</coordinates>"
    if mark.kind == "point":
        out += [f"{i}  <Point>", *mode, f"{i}    {coords}", f"{i}  </Point>"]
    elif mark.kind == "line":
        out += [f"{i}  <LineString>", *mode, f"{i}    {coords}", f"{i}  </LineString>"]
    else:
        out += [
            f"{i}  <Polygon>",
            *mode,
            f"{i}    <outerBoundaryIs>",
            f"{i}      <LinearRing>",
            f"{i}        {coords}",
            f"{i}      </LinearRing>",
            f"{i}    </outerBoundaryIs>",
            f"{i}  </Polygon>",
        ]
    out.append(f"{i}</Placemark>")
    return out


def render(doc: Document) -> str:
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<kml xmlns="http://www.opengis.net/kml/2.2">', "  <Document>"]
    out.append(f"    <name>{esc(doc.name)}</name>")
    for style_id, color in doc.styles:
        alpha = "80" + color[2:]
        out += [
            f'    <Style id="{esc_attr(style_id)}">',
            f"      <IconStyle><color>{color}</color></IconStyle>",
            f"      <LineStyle><color>{color}</color></LineStyle>",
            f"      <PolyStyle><color>{alpha}</color></PolyStyle>",
            "    </Style>",
        ]
    for folder in doc.folders:
        out += ["    <Folder>", f"      <name>{esc(folder.name)}</name>"]
        for mark in folder.marks:
            out += render_mark(mark, "      ")
        out.append("    </Folder>")
    out += ["  </Document>", "</kml>"]
    return "\n".join(out) + "\n"


# ---------- los ejemplos ----------

ENTRADAS: dict[str, str] = {}

ENTRADAS["con-codigos"] = """doc|Obra 1 & Cía
style|ARBOL|ff0000ff
style|MURO|ffff0000
style|PARC|ff00ff00
folder|ARBOL
mark|point|P1|Un árbol de <10 m\\n(segunda línea)|ARBOL|absolute|
data|Name|P1
data|Code|ARBOL
data|Easting|440290.4580539461
data|Elevation|646.377
data|Notes|
vertex|-3.703800000|40.416800000|646.3770
mark|point|P2||ARBOL|clamp|
data|Name|P2
data|Easting|440300.5
data|Elevation|
vertex|-3.703700000|40.416800000|
folder|MURO
mark|line|Muro 1|Tapia|MURO|absolute|
data|Name|Muro 1
data|Vertices|3
vertex|-3.703800000|40.417800000|10.5000
vertex|-3.702800000|40.417800000|11.5000
vertex|-3.702800000|40.418800000|12.5000
mark|line|Muro 2||MURO|clamp|
data|Name|Muro 2
vertex|-3.703800000|40.419800000|
vertex|-3.702800000|40.419800000|12.0000
folder|PARC
mark|polygon|Parcela A|Linde "norte"|PARC|absolute|
data|Name|Parcela A
data|Vertices|4
vertex|-3.703800000|40.420000000|100.0000
vertex|-3.703400000|40.420000000|100.0000
vertex|-3.703400000|40.420400000|100.0000
vertex|-3.703800000|40.420400000|100.0000
vertex|-3.703800000|40.420000000|100.0000
folder|Obra 1 & Cía
mark|point|Suelto|Sin código||clamp|
data|Name|Suelto
vertex|-3.704000000|40.416000000|
"""

ENTRADAS["sin-codigos"] = """doc|Proyecto sin códigos
folder|Proyecto sin códigos
mark|point|1|||clamp|
data|Name|1
vertex|-3.703800000|40.416800000|
mark|point|2|Con descripción||clamp|
data|Name|2
vertex|-3.703700000|40.416900000|
"""

ENTRADAS["con-fotos"] = """doc|Con fotos
style|FOTO|ff0000ff
folder|FOTO
mark|point|P1|Poste & base\\nmuy alto|FOTO|clamp|P1_1.jpg;P1_2.jpg
data|Name|P1
vertex|-3.703800000|40.416800000|
mark|point|P2|Sin fotos|FOTO|clamp|
data|Name|P2
vertex|-3.703700000|40.416800000|
mark|point|P3||FOTO|clamp|P3_1.jpg
data|Name|P3
vertex|-3.703600000|40.416800000|
"""


def generate_all() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for name, text in ENTRADAS.items():
        files[f"entrada-{name}.txt"] = text.encode("utf-8")
        files[f"{name}.kml"] = render(parse(text)).encode("utf-8")
    return dict(sorted(files.items()))


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
