"""Ficheros DXF de ejemplo de la capa de diseño y lo que debe salir de cada uno (S040).

Genera en fixtures/formatos/dxf/ los DXF, un DWG, un DXF binario y otros ficheros que no son DXF, y cuatro ficheros de lo esperado:

- esperado.csv: una fila por elemento que debe salir del fichero (bloques ya desplegados, curvas sin convertir): capa, tipo de capa,
  tipo de elemento, si trae Z, color, vértices originales ("x y z bulge", con `_` si falta) y la curva exacta; en las splines, 400 puntos
  de la curva evaluados con de Boor.
- esperado-capas.csv: una fila por capa de diseño (nombre y tipo), con su color, si llega visible y cuántos elementos tiene.
- esperado-resumen.csv: clave/valor por fichero: tipo de fichero, unidad declarada, recuento por tipo de elemento, ignorados por tipo,
  descartados por motivo, si el fichero está completo y si todas las Z valen 0.
- esperado-conversion.csv: los vértices de los ficheros `origen-*.dxf` en el sistema de origen y en ETRS89 UTM 30 (el SC del proyecto),
  calculados con `ManualCrs` de tools/geodesia/generar_vectores.py (pyproj, con la rejilla SPED2ETV2 en ED50).

Reglas de este conjunto que la spec no fija y que quedan escritas en lo esperado (se confirman al hacer cada tarea):
- Una entidad trae Z si el DXF escribe su código 30 (o 31): LINE y POINT sin él no la traen; ARC, CIRCLE, SPLINE y POLYLINE 3D siempre.
  Una LWPOLYLINE la trae si tiene elevación (38), y una POLYLINE 2D solo si su elevación no es 0.
- Los bloques se despliegan elemento a elemento y el punto de inserción de cada INSERT es un elemento BLOCK_INSERT en la capa del INSERT.
  Un INSERT que cierra un ciclo de bloques no da nada, ni siquiera su punto.
- La capa `0` dentro de un bloque toma la capa del INSERT y BYBLOCK (color 0) su color.
- El blanco y el negro del DXF (ACI 7, color verdadero FFFFFF o 000000) se escriben como `AUTO`.
- Una capa está oculta si su color es negativo (apagada) o si tiene el bit 1 de las marcas (congelada).
- Las coordenadas se escriben a su precisión completa (repr de Python).

Uso: uv run --project tools tools/formatos/generar_dxf.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field, replace
from pathlib import Path

import pyproj

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores as v  # noqa: E402

OUT = ROOT / "fixtures" / "formatos" / "dxf"
FOOT = 0.3048
US_FOOT = 1200 / 3937
E0, N0 = 440000.0, 4474000.0  # esquina de los dibujos, en ETRS89 UTM 30 (Madrid)

# Colores ACI que usan los ejemplos (la tabla completa es de la app: AciPalette).
ACI = {1: "FFFF0000", 2: "FFFFFF00", 3: "FF00FF00", 4: "FF00FFFF", 5: "FF0000FF", 6: "FFFF00FF", 30: "FFFF7F00", 250: "FF333333"}
INSUNITS = {"m": 6, "ft": 2, "usft": 21}


def P(x: float, y: float, z: float | None = None, b: float | None = None) -> tuple:
    return (x, y, z, b)


@dataclass
class Ent:
    """Una entidad lógica. kind: POINT, LINE, POLY2D, POLY3D, ARC, CIRCLE, SPLINE, INSERT o RAW."""

    kind: str
    layer: str
    pts: list[tuple] = field(default_factory=list)
    color: int | None = None  # código 62; 0 = BYBLOCK, 256 = BYLAYER, None = no se escribe
    true_color: int | None = None  # código 420
    closed: bool = False
    extra: dict = field(default_factory=dict)


@dataclass
class Block:
    name: str
    ents: list[Ent]


@dataclass
class Layer:
    name: str
    color: int = 7
    flags: int = 0
    true_color: int | None = None


@dataclass
class Element:
    layer: str
    layer_kind: str  # POINTS o LINES
    kind: str
    has_z: bool
    color: str
    verts: list[tuple]
    curve: str = ""
    dense: str = ""
    center_z: float | None = None  # la Z del centro de un arco o un círculo

    def zs(self) -> list[float]:
        return [p[2] for p in self.verts if p[2] is not None] + ([] if self.center_z is None else [self.center_z])


@dataclass
class Scene:
    """Un DXF: lo que se escribe y las claves de lo esperado."""

    file: str
    version: str  # AC1009, AC1015, AC1024 o AC1032
    layers: list[Layer]
    ents: list[Ent]
    blocks: list[Block] = field(default_factory=list)
    units: str | None = "m"  # None: sin $INSUNITS
    newline: str = "\n"
    comment: bool = False
    truncate_after: int | None = None  # entidades completas antes de cortar el fichero a mitad de la siguiente
    broken: int | None = None  # índice de la entidad cuyo primer número se escribe como "abc"

    @property
    def modern(self) -> bool:
        return self.version != "AC1009"

    @property
    def encoding(self) -> str:
        return "utf-8" if self.version >= "AC1021" else "cp1252"


def num(x: float) -> str:
    return repr(float(x))


# ───────────────────────────── escritura del DXF ─────────────────────────────


class Writer:
    def __init__(self, scene: Scene) -> None:
        self.s = scene
        self.handle = 0x100
        self.out: list[str] = []

    def h(self) -> str:
        self.handle += 1
        return f"{self.handle:X}"

    def pair(self, code: int, value) -> str:
        return f"{code:>3}\n{value}\n"

    def pairs(self, items: list[tuple]) -> str:
        return "".join(self.pair(c, x) for c, x in items)

    def common(self, name: str, layer: str) -> list[tuple]:
        items: list[tuple] = [(0, name)]
        if self.s.modern:
            items += [(5, self.h()), (100, "AcDbEntity")]
        items.append((8, layer))
        return items

    def header(self) -> str:
        s = self.s
        items: list[tuple] = []
        if s.comment:
            items.append((999, "Generado por tools/formatos/generar_dxf.py"))
        items += [(0, "SECTION"), (2, "HEADER"), (9, "$ACADVER"), (1, s.version)]
        if s.units is not None and s.modern:
            items += [(9, "$INSUNITS"), (70, INSUNITS[s.units])]
        items += [(9, "$DWGCODEPAGE"), (3, "ANSI_1252")]
        # Extensión falsa: la app no debe fiarse de ella.
        items += [(9, "$EXTMIN"), (10, "0.0"), (20, "0.0"), (30, "0.0"), (9, "$EXTMAX"), (10, "1.0"), (20, "1.0"), (30, "0.0")]
        items.append((0, "ENDSEC"))
        return self.pairs(items)

    def tables(self) -> str:
        s = self.s
        items: list[tuple] = [(0, "SECTION"), (2, "TABLES"), (0, "TABLE"), (2, "LAYER")]
        if s.modern:
            items += [(5, self.h()), (100, "AcDbSymbolTable")]
        items.append((70, len(s.layers)))
        for layer in s.layers:
            items.append((0, "LAYER"))
            if s.modern:
                items += [(5, self.h()), (100, "AcDbSymbolTableRecord"), (100, "AcDbLayerTableRecord")]
            items += [(2, layer.name), (70, layer.flags), (62, layer.color)]
            if layer.true_color is not None:
                items.append((420, layer.true_color))
            items.append((6, "CONTINUOUS"))
        items += [(0, "ENDTAB"), (0, "ENDSEC")]
        return self.pairs(items)

    def blocks(self) -> str:
        s = self.s
        out = self.pairs([(0, "SECTION"), (2, "BLOCKS")])
        names = [Block("*Model_Space", []), Block("*Paper_Space", [])] if s.modern else []
        for block in names + s.blocks:
            head: list[tuple] = [(0, "BLOCK")]
            if s.modern:
                head += [(5, self.h()), (100, "AcDbEntity")]
            head.append((8, "0"))
            if s.modern:
                head.append((100, "AcDbBlockBegin"))
            head += [(2, block.name), (70, 0 if not block.name.startswith("*") else 1), (10, "0.0"), (20, "0.0"), (30, "0.0"), (3, block.name), (1, "")]
            out += self.pairs(head)
            for e in block.ents:
                out += self.entity(e)
            tail: list[tuple] = [(0, "ENDBLK")]
            if s.modern:
                tail += [(5, self.h()), (100, "AcDbEntity")]
            tail.append((8, "0"))
            if s.modern:
                tail.append((100, "AcDbBlockEnd"))
            out += self.pairs(tail)
        return out + self.pairs([(0, "ENDSEC")])

    def xyz(self, base: int, x: float, y: float, z: float | None) -> list[tuple]:
        items = [(base, num(x)), (base + 10, num(y))]
        if z is not None:
            items.append((base + 20, num(z)))
        return items

    def entity(self, e: Ent) -> str:
        s, m = self.s, self.s.modern
        k = e.kind

        def colors() -> list[tuple]:
            out = []
            if e.color is not None:
                out.append((62, e.color))
            if e.true_color is not None:
                out.append((420, e.true_color))
            return out

        if k == "POINT":
            x, y, z, _ = e.pts[0]
            return self.pairs(self.common("POINT", e.layer) + colors() + ([(100, "AcDbPoint")] if m else []) + self.xyz(10, x, y, z))
        if k == "LINE":
            (x1, y1, z1, _), (x2, y2, z2, _) = e.pts
            return self.pairs(self.common("LINE", e.layer) + colors() + ([(100, "AcDbLine")] if m else [])
                              + self.xyz(10, x1, y1, z1) + self.xyz(11, x2, y2, z2))
        if k == "POLY2D" and m:
            items = self.common("LWPOLYLINE", e.layer) + colors() + [(100, "AcDbPolyline"), (90, len(e.pts)), (70, 1 if e.closed else 0)]
            if e.extra.get("elevation") is not None:
                items.append((38, num(e.extra["elevation"])))
            for x, y, _, b in e.pts:
                items += [(10, num(x)), (20, num(y))]
                if b:
                    items.append((42, num(b)))
            return self.pairs(items + self.normal(e))
        if k in ("POLY2D", "POLY3D"):
            three = k == "POLY3D"
            head = self.common("POLYLINE", e.layer) + colors()
            if m:
                head.append((100, "AcDb3dPolyline" if three else "AcDb2dPolyline"))
            elevation = e.extra.get("elevation") or 0.0
            head += [(66, 1), (10, "0.0"), (20, "0.0"), (30, num(elevation)), (70, (1 if e.closed else 0) | (8 if three else 0))]
            out = self.pairs(head + self.normal(e))
            for x, y, z, b in e.pts:
                vertex = self.common("VERTEX", e.layer)
                if m:
                    vertex += [(100, "AcDbVertex"), (100, "AcDb3dPolylineVertex" if three else "AcDb2dVertex")]
                vertex += [(10, num(x)), (20, num(y)), (30, num(z if z is not None else 0.0))]
                if b:
                    vertex.append((42, num(b)))
                if three:
                    vertex.append((70, 32))
                out += self.pairs(vertex)
            return out + self.pairs(self.common("SEQEND", e.layer))
        if k in ("ARC", "CIRCLE"):
            cx, cy, cz = e.extra["center"]
            items = self.common(k, e.layer) + colors() + ([(100, "AcDbCircle")] if m else [])
            items += [(10, num(cx)), (20, num(cy)), (30, num(cz)), (40, num(e.extra["radius"]))] + self.normal(e)
            if k == "ARC":
                if m:
                    items.append((100, "AcDbArc"))
                items += [(50, num(e.extra["a0"])), (51, num(e.extra["a1"]))]
            return self.pairs(items)
        if k == "SPLINE":
            ctrl, knots, degree = e.extra["ctrl"], e.extra["knots"], e.extra["degree"]
            weights = e.extra.get("weights")
            items = self.common("SPLINE", e.layer) + colors() + [(100, "AcDbSpline"), (210, "0.0"), (220, "0.0"), (230, "1.0")]
            items += [(70, 8 | (4 if weights else 0)), (71, degree), (72, len(knots)), (73, len(ctrl)), (74, 0)]
            items += [(40, num(t)) for t in knots]
            if weights:
                items += [(41, num(w)) for w in weights]
            for x, y, z in ctrl:
                items += [(10, num(x)), (20, num(y)), (30, num(z))]
            return self.pairs(items)
        if k == "INSERT":
            x, y, z, _ = e.pts[0]
            items = self.common("INSERT", e.layer) + colors() + ([(100, "AcDbBlockReference")] if m else [])
            items.append((2, e.extra["block"]))
            items += self.xyz(10, x, y, z)
            sx, sy, sz = e.extra.get("scale", (1.0, 1.0, 1.0))
            if (sx, sy, sz) != (1.0, 1.0, 1.0):
                items += [(41, num(sx)), (42, num(sy)), (43, num(sz))]
            if e.extra.get("rot"):
                items.append((50, num(e.extra["rot"])))
            if "grid" in e.extra:
                cols, rows, dx, dy = e.extra["grid"]
                items += [(70, cols), (71, rows), (44, num(dx)), (45, num(dy))]
            return self.pairs(items + self.normal(e))
        if k == "RAW":
            items = self.common(e.extra["type"], e.layer) + colors()
            return self.pairs(items + e.extra["pairs"])
        raise ValueError(k)

    def normal(self, e: Ent) -> list[tuple]:
        n = e.extra.get("normal")
        return [] if n is None else [(210, num(n[0])), (220, num(n[1])), (230, num(n[2]))]

    def text(self) -> str:
        s = self.s
        body = self.header() + self.tables()
        if s.modern or s.blocks:
            body += self.blocks()
        body += self.pairs([(0, "SECTION"), (2, "ENTITIES")])
        texts = [self.entity(e) for e in s.ents]
        if s.broken is not None:
            first = num(s.ents[s.broken].pts[0][0])
            texts[s.broken] = texts[s.broken].replace(f"\n{first}\n", "\nabc\n", 1)
        if s.truncate_after is not None:
            cut = texts[s.truncate_after]
            body += "".join(texts[: s.truncate_after]) + cut[: cut.index("\n 10\n") + 5] + "44"  # el fichero acaba a mitad de un valor
        else:
            body += "".join(texts) + self.pairs([(0, "ENDSEC"), (0, "EOF")])
        return body.replace("\n", s.newline)


# ─────────────────────────────── geometría exacta ───────────────────────────────


@dataclass
class Xf:
    m: tuple  # 3 filas de 3
    t: tuple

    def apply(self, p: tuple) -> tuple:
        x, y, z, b = p
        zz = 0.0 if z is None else z
        r = [self.m[i][0] * x + self.m[i][1] * y + self.m[i][2] * zz + self.t[i] for i in range(3)]
        return (r[0], r[1], None if z is None else r[2], b)

    def then(self, outer: "Xf") -> "Xf":
        """Primero esta y luego `outer`."""
        m = tuple(tuple(sum(outer.m[i][k] * self.m[k][j] for k in range(3)) for j in range(3)) for i in range(3))
        t = tuple(sum(outer.m[i][k] * self.t[k] for k in range(3)) + outer.t[i] for i in range(3))
        return Xf(m, t)


IDENTITY = Xf(((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)), (0.0, 0.0, 0.0))


def insert_xf(e: Ent, offset: tuple[float, float] = (0.0, 0.0)) -> Xf:
    sx, sy, sz = e.extra.get("scale", (1.0, 1.0, 1.0))
    a = math.radians(e.extra.get("rot", 0.0))
    c, s = math.cos(a), math.sin(a)
    m = ((c * sx, -s * sy, 0.0), (s * sx, c * sy, 0.0), (0.0, 0.0, sz))
    x, y, z, _ = e.pts[0]
    ox, oy = offset
    return Xf(m, (x + c * ox - s * oy, y + s * ox + c * oy, z or 0.0))


def arbitrary_axis(n: tuple) -> tuple:
    """Los ejes del OCS en el sistema global (algoritmo del eje arbitrario de AutoCAD)."""
    nx, ny, nz = n
    ax = (0.0, 1.0, 0.0) if abs(nx) < 1 / 64 and abs(ny) < 1 / 64 else (0.0, 0.0, 1.0)
    # Ax = Wy × N  (o Wz × N)
    ax = (ax[1] * nz - ax[2] * ny, ax[2] * nx - ax[0] * nz, ax[0] * ny - ax[1] * nx)
    length = math.sqrt(sum(c * c for c in ax))
    ax = tuple(c / length for c in ax)
    ay = (ny * ax[2] - nz * ax[1], nz * ax[0] - nx * ax[2], nx * ax[1] - ny * ax[0])
    return ax, ay, (nx, ny, nz)


def ocs_to_wcs(p: tuple, n: tuple) -> tuple:
    ax, ay, az = arbitrary_axis(n)
    return tuple(ax[i] * p[0] + ay[i] * p[1] + az[i] * p[2] + 0.0 for i in range(3))  # + 0.0: que no salga -0.0


def de_boor(k: int, t: float, knots: list[float], ctrl: list[list[float]], degree: int) -> list[float]:
    d = [list(ctrl[j + k - degree]) for j in range(degree + 1)]
    for r in range(1, degree + 1):
        for j in range(degree, r - 1, -1):
            alpha = (t - knots[j + k - degree]) / (knots[j + 1 + k - r] - knots[j + k - degree])
            d[j] = [(1 - alpha) * a + alpha * b for a, b in zip(d[j - 1], d[j])]
    return d[degree]


def spline_points(ctrl: list[tuple], knots: list[float], degree: int, weights: list[float] | None, samples: int = 400) -> list[tuple]:
    w = weights or [1.0] * len(ctrl)
    homogeneous = [[x * wi, y * wi, z * wi, wi] for (x, y, z), wi in zip(ctrl, w)]
    t0, t1 = knots[degree], knots[len(ctrl)]
    out = []
    for i in range(samples + 1):
        t = t0 + (t1 - t0) * i / samples
        k = degree
        while k < len(ctrl) - 1 and knots[k + 1] <= t:
            k += 1
        h = de_boor(k, t, knots, homogeneous, degree)
        out.append((h[0] / h[3], h[1] / h[3], h[2] / h[3]))
    return out


# ───────────────────────── de la escena a lo esperado ─────────────────────────


class Expander:
    def __init__(self, scene: Scene) -> None:
        self.s = scene
        self.layers = {layer.name: layer for layer in scene.layers}
        self.blocks = {b.name: b for b in scene.blocks}
        self.elements: list[Element] = []
        self.discarded: dict[str, int] = {}
        self.ignored: dict[str, int] = {}

    def layer_color(self, name: str) -> str:
        layer = self.layers.get(name)
        if layer is None:
            return "AUTO"
        if layer.true_color is not None:
            return rgb(layer.true_color)
        return aci(abs(layer.color))

    def color_of(self, e: Ent, layer: str, parent: str | None) -> str:
        if e.true_color is not None:
            return rgb(e.true_color)
        if e.color == 0 and parent is not None:
            return parent
        if e.color not in (None, 0, 256):
            return aci(e.color)
        return self.layer_color(layer)

    def run(self) -> None:
        for e in self.s.ents:
            self.expand(e, IDENTITY, [], None, None)

    def expand(self, e: Ent, xf: Xf, stack: list[str], layer_in: str | None, color_in: str | None) -> None:
        layer = layer_in if (layer_in is not None and e.layer == "0") else e.layer
        color = self.color_of(e, layer, color_in)
        if e.kind == "RAW":
            self.ignored[e.extra["type"]] = self.ignored.get(e.extra["type"], 0) + 1
            return
        if e.kind == "INSERT":
            name = e.extra["block"]
            if name in stack:
                self.discarded["ciclo_de_bloques"] = self.discarded.get("ciclo_de_bloques", 0) + 1
                return
            cols, rows, dx, dy = e.extra.get("grid", (1, 1, 0.0, 0.0))
            z = e.pts[0][2]
            for r in range(rows):
                for c in range(cols):
                    local = insert_xf(e, (c * dx, r * dy))
                    placed = xf.apply((local.t[0], local.t[1], None if z is None else local.t[2], None))
                    self.elements.append(Element(layer, "POINTS", "BLOCK_INSERT", z is not None, color, [placed]))
                    for inner_e in self.blocks[name].ents:
                        self.expand(inner_e, local.then(xf), stack + [name], layer, color)
            return
        self.add(e, xf, layer, color)

    def add(self, e: Ent, xf: Xf, layer: str, color: str) -> None:
        k = e.kind
        if k == "POINT":
            self.elements.append(Element(layer, "POINTS", "POINT", e.pts[0][2] is not None, color, [xf.apply(e.pts[0])]))
        elif k == "LINE":
            has_z = e.pts[0][2] is not None
            self.elements.append(Element(layer, "LINES", "LINE", has_z, color, [xf.apply(p) for p in e.pts]))
        elif k == "POLY2D":
            elevation = e.extra.get("elevation")
            has_z = elevation is not None and (self.s.modern or elevation != 0)
            pts = [(x, y, elevation if has_z else None, b) for x, y, _, b in e.pts]
            n = e.extra.get("normal")
            if n is not None:  # los vértices de una polilínea 2D están en el OCS
                pts = [ocs_to_wcs((x, y, elevation or 0.0), n) + (b,) for x, y, _, b in pts]
                pts = [(x, y, z if has_z else None, b) for x, y, z, b in pts]
            self.elements.append(Element(layer, "LINES", "POLYLINE", has_z, color, [xf.apply(p) for p in self.closed(e, pts)]))
        elif k == "POLY3D":
            self.elements.append(Element(layer, "LINES", "POLYLINE", True, color, [xf.apply(p) for p in self.closed(e, e.pts)]))
        elif k in ("ARC", "CIRCLE"):
            n = e.extra.get("normal", (0.0, 0.0, 1.0))
            cx, cy, cz = e.extra["center"]
            center = ocs_to_wcs((cx, cy, cz), n)
            center = xf.apply((center[0], center[1], center[2], None))
            r = e.extra["radius"]
            if k == "ARC":
                a0, a1 = e.extra["a0"], e.extra["a1"]
                curve = f"ARC {num(center[0])} {num(center[1])} {num(center[2])} {num(r)} {num(a0)} {num(a1)} {' '.join(num(c) for c in n)}"
            else:
                curve = f"CIRCLE {num(center[0])} {num(center[1])} {num(center[2])} {num(r)} {' '.join(num(c) for c in n)}"
            self.elements.append(Element(layer, "LINES", k, True, color, [], curve, center_z=center[2]))
        elif k == "SPLINE":
            dense = spline_points(e.extra["ctrl"], e.extra["knots"], e.extra["degree"], e.extra.get("weights"))
            ctrl = [(x, y, z, None) for x, y, z in e.extra["ctrl"]]
            curve = f"SPLINE {e.extra['degree']} {'rational' if e.extra.get('weights') else 'polynomial'}"
            self.elements.append(Element(
                layer, "LINES", "SPLINE", True, color, ctrl, curve, ";".join(f"{num(x)} {num(y)}" for x, y, _ in dense),
            ))

    def closed(self, e: Ent, pts: list[tuple]) -> list[tuple]:
        return pts + [pts[0][:3] + (None,)] if e.closed else pts


def rgb(value: int) -> str:
    return "AUTO" if value in (0xFFFFFF, 0x000000) else f"FF{value:06X}"


def aci(code: int) -> str:
    return "AUTO" if code == 7 else ACI[code]


def vertex_text(p: tuple) -> str:
    x, y, z, b = p
    return " ".join("_" if c is None else num(c) for c in (x, y, z, b))


# ─────────────────────────────────── escenas ───────────────────────────────────


def basic(file: str, version: str) -> Scene:
    modern = version != "AC1009"
    zanja, acera, pozos, curvas = "Eje-Zanja", "Acera ñandú", "Pozos", "Curvas"
    ents = [
        Ent("POINT", pozos, [P(E0 + 10, N0 + 10, 646.5)]),
        Ent("POINT", pozos, [P(E0 + 20, N0 + 15)]),
        Ent("LINE", zanja, [P(E0, N0), P(E0 + 100, N0 + 50)]),
        Ent("LINE", zanja, [P(E0 + 100, N0 + 50, 650.0), P(E0 + 150, N0 + 60, 652.5)]),
        Ent("POLY2D", acera, [P(E0 + 30, N0, b=0.5), P(E0 + 60, N0), P(E0 + 60, N0 + 20, b=-0.25), P(E0 + 30, N0 + 20)], closed=True),
        Ent("POLY3D", acera, [P(E0, N0 + 80, 640.0), P(E0 + 40, N0 + 90, 641.25), P(E0 + 80, N0 + 85, 643.5)]),
        Ent("ARC", curvas, extra={"center": (E0 + 50, N0 + 120, 0.0), "radius": 120.0, "a0": 20.0, "a1": 140.0}),
        Ent("CIRCLE", curvas, extra={"center": (E0 + 100, N0 + 100, 0.0), "radius": 25.0}),
        Ent("INSERT", pozos, [P(E0 + 70, N0 + 70)], color=3, extra={"block": "POZO"}),
    ]
    if modern:
        ents += [
            Ent("POLY2D", acera, [P(E0 + 10, N0 + 130, b=0.3), P(E0 + 50, N0 + 130), P(E0 + 70, N0 + 150)], extra={"elevation": 645.0}),
            Ent("SPLINE", curvas, extra={
                "degree": 3, "knots": [0, 0, 0, 0, 1, 2, 3, 3, 3, 3],
                "ctrl": [(E0, N0 + 150, 0.0), (E0 + 20, N0 + 190, 0.0), (E0 + 50, N0 + 170, 0.0),
                         (E0 + 80, N0 + 200, 0.0), (E0 + 110, N0 + 175, 0.0), (E0 + 140, N0 + 190, 0.0)],
            }),
            Ent("SPLINE", curvas, extra={
                "degree": 3, "knots": [0, 0, 0, 0, 1, 2, 3, 3, 3, 3],
                "ctrl": [(E0, N0 + 210, 0.0), (E0 + 20, N0 + 250, 0.0), (E0 + 50, N0 + 230, 0.0),
                         (E0 + 80, N0 + 260, 0.0), (E0 + 110, N0 + 235, 0.0), (E0 + 140, N0 + 250, 0.0)],
                "weights": [1.0, 2.0, 1.0, 0.5, 1.0, 1.0],
            }),
            Ent("CIRCLE", curvas, extra={"center": (E0 + 200, N0 + 10, 0.0), "radius": 0.005}),
        ]
    return Scene(
        file, version,
        [Layer("0", 7), Layer(zanja, 1), Layer(acera, 5), Layer(pozos, 3), Layer(curvas, 6)],
        ents, [Block("POZO", [
            Ent("LINE", "0", [P(0.0, 0.0), P(2.0, 0.0)], color=0),
            Ent("POINT", "0", [P(0.0, 0.0)]),
        ])],
        units="m" if modern else None, newline="\r\n" if version in ("AC1009", "AC1015") else "\n", comment=version == "AC1032",
    )


def blocks_scene() -> Scene:
    sq = [Ent("LINE", "0", [P(0.0, 0.0), P(1.0, 0.0)], color=0), Ent("LINE", "0", [P(1.0, 0.0), P(1.0, 1.0)])]
    return Scene(
        "bloques.dxf", "AC1015", [Layer("0", 7), Layer("Mobiliario", 2), Layer("Redes", 4)],
        [
            Ent("INSERT", "Mobiliario", [P(E0, N0)], color=1, extra={"block": "CONJUNTO", "scale": (2.0, 2.0, 1.0), "rot": 90.0}),
            Ent("INSERT", "Redes", [P(E0 + 50, N0)], extra={"block": "UNIDAD", "scale": (-1.0, 1.0, 1.0)}),
            Ent("INSERT", "Redes", [P(E0 + 100, N0, 10.0)], extra={"block": "UNIDAD", "grid": (2, 3, 5.0, 4.0), "rot": 30.0}),
            Ent("INSERT", "Redes", [P(E0 + 150, N0)], extra={"block": "CICLO_A"}),
        ],
        [
            Block("UNIDAD", [Ent("LINE", "0", [P(0.0, 0.0), P(3.0, 1.0)]), Ent("POINT", "Redes", [P(1.0, 1.0)])]),
            Block("CONJUNTO", sq + [Ent("INSERT", "0", [P(1.0, 1.0)], extra={"block": "UNIDAD", "rot": 90.0, "scale": (1.0, 0.5, 1.0)})]),
            Block("CICLO_A", [Ent("LINE", "0", [P(0.0, 0.0), P(1.0, 1.0)]), Ent("INSERT", "0", [P(2.0, 0.0)], extra={"block": "CICLO_B"})]),
            Block("CICLO_B", [Ent("INSERT", "0", [P(1.0, 1.0)], extra={"block": "CICLO_A"})]),
        ],
    )


def layers_scene() -> Scene:
    layers = [
        Layer("Roja", 1), Layer("Apagada", -3), Layer("Congelada", 5, flags=1), Layer("Blanca", 7),
        Layer("Verdadera", 5, true_color=0x336699), Layer("Naranja", 30),
    ]
    line = lambda i, name, **kw: Ent("LINE", name, [P(E0, N0 + 10 * i), P(E0 + 50, N0 + 10 * i)], **kw)  # noqa: E731
    ents = [line(i, layer.name) for i, layer in enumerate(layers)]
    ents += [
        line(10, "Roja", color=2),
        line(11, "Roja", true_color=0x00AA55),
        line(12, "Blanca", true_color=0xFFFFFF),
        line(13, "Naranja", color=250),
        Ent("POINT", "Apagada", [P(E0 + 5, N0 + 5)]),
        Ent("POINT", "Roja", [P(E0 + 6, N0 + 6)]),
    ]
    return Scene("capas.dxf", "AC1032", layers, ents)


def curves_ocs_scene() -> Scene:
    mirror = (0.0, 0.0, -1.0)
    return Scene(
        "extrusion-espejo.dxf", "AC1015", [Layer("0", 7), Layer("Espejo", 4)],
        [
            Ent("ARC", "Espejo", extra={"center": (-(E0 + 20), N0 + 20, 0.0), "radius": 15.0, "a0": 10.0, "a1": 100.0, "normal": mirror}),
            Ent("CIRCLE", "Espejo", extra={"center": (-(E0 + 60), N0 + 20, 0.0), "radius": 8.0, "normal": mirror}),
            Ent("POLY2D", "Espejo", [P(-(E0 + 10), N0), P(-(E0 + 40), N0), P(-(E0 + 40), N0 + 30)], extra={"normal": mirror}),
        ],
    )


def z_scenes() -> list[Scene]:
    mixed = Scene(
        "cotas.dxf", "AC1015", [Layer("0", 7), Layer("Cotas", 2)],
        [
            Ent("POINT", "Cotas", [P(E0, N0, 100.0)]),
            Ent("POINT", "Cotas", [P(E0 + 1, N0)]),
            Ent("LINE", "Cotas", [P(E0, N0), P(E0 + 10, N0)]),
            Ent("LINE", "Cotas", [P(E0, N0 + 5, 101.0), P(E0 + 10, N0 + 5, 102.0)]),
            Ent("POLY3D", "Cotas", [P(E0, N0 + 9, 100.0), P(E0 + 5, N0 + 9, 100.5)]),
            Ent("POLY2D", "Cotas", [P(E0, N0 + 12), P(E0 + 5, N0 + 12)]),
            Ent("POLY2D", "Cotas", [P(E0, N0 + 14), P(E0 + 5, N0 + 14)], extra={"elevation": 99.0}),
        ],
    )
    zeros = Scene(
        "todo-z-cero.dxf", "AC1015", [Layer("0", 7), Layer("Plano", 2)],
        [
            Ent("POINT", "Plano", [P(E0, N0, 0.0)]),
            Ent("LINE", "Plano", [P(E0, N0, 0.0), P(E0 + 10, N0, 0.0)]),
            Ent("POLY3D", "Plano", [P(E0, N0 + 5, 0.0), P(E0 + 5, N0 + 5, 0.0)]),
            Ent("ARC", "Plano", extra={"center": (E0 + 20, N0 + 5, 0.0), "radius": 5.0, "a0": 0.0, "a1": 90.0}),
        ],
    )
    return [mixed, zeros]


RAW_IGNORED = (
    ("TEXT", [(100, "AcDbText"), (10, "440005.0"), (20, "4474005.0"), (30, "0.0"), (40, "2.5"), (1, "P-1"), (100, "AcDbText")]),
    ("MTEXT", [(100, "AcDbMText"), (10, "440006.0"), (20, "4474006.0"), (30, "0.0"), (40, "2.5"), (1, "Cota\\P12,5")]),
    ("DIMENSION", [(100, "AcDbDimension"), (10, "440010.0"), (20, "4474010.0"), (30, "0.0"), (13, "440000.0"), (23, "4474000.0"), (33, "0.0"),
                   (14, "440020.0"), (24, "4474000.0"), (34, "0.0")]),
    ("HATCH", [(100, "AcDbHatch"), (10, "0.0"), (20, "0.0"), (30, "0.0"), (210, "0.0"), (220, "0.0"), (230, "1.0"), (2, "SOLID"), (91, 1),
               (92, 1), (93, 2), (72, 1), (10, "440000.0"), (20, "4474000.0"), (11, "440010.0"), (21, "4474000.0")]),
    ("ELLIPSE", [(100, "AcDbEllipse"), (10, "440050.0"), (20, "4474050.0"), (30, "0.0"), (11, "20.0"), (21, "0.0"), (31, "0.0"),
                 (210, "0.0"), (220, "0.0"), (230, "1.0"), (40, "0.5"), (41, "0.0"), (42, "6.283185307")]),
    ("SOLID", [(100, "AcDbTrace"), (10, "440000.0"), (20, "4474000.0"), (30, "0.0"), (11, "440001.0"), (21, "4474000.0"), (31, "0.0"),
               (12, "440000.0"), (22, "4474001.0"), (32, "0.0"), (13, "440001.0"), (23, "4474001.0"), (33, "0.0")]),
    ("3DFACE", [(100, "AcDbFace"), (10, "440000.0"), (20, "4474000.0"), (30, "5.0"), (11, "440001.0"), (21, "4474000.0"), (31, "5.0"),
                (12, "440000.0"), (22, "4474001.0"), (32, "5.0"), (13, "440000.0"), (23, "4474001.0"), (33, "5.0")]),
    ("LEADER", [(100, "AcDbLeader"), (3, "Standard"), (71, 1), (72, 0), (76, 2), (10, "440000.0"), (20, "4474000.0"), (30, "0.0"),
                (10, "440010.0"), (20, "4474010.0"), (30, "0.0")]),
)


def ignored_scene() -> Scene:
    ents = [Ent("LINE", "Ejes", [P(E0, N0), P(E0 + 10, N0)])]
    ents += [Ent("RAW", "Ejes", extra={"type": t, "pairs": p}) for t, p in RAW_IGNORED]
    ents += [Ent("RAW", "Ejes", extra={"type": "TEXT", "pairs": RAW_IGNORED[0][1]}), Ent("POINT", "Ejes", [P(E0 + 3, N0 + 3)])]
    return Scene("ignorados.dxf", "AC1015", [Layer("0", 7), Layer("Ejes", 1)], ents)


def damaged_scenes() -> list[Scene]:
    def lines(count: int) -> list[Ent]:
        return [Ent("LINE", "Capa", [P(E0, N0 + 10 * i), P(E0 + 10, N0 + 10 * i)]) for i in range(count)]

    layers = [Layer("0", 7), Layer("Capa", 1)]
    cut = Scene("cortado.dxf", "AC1015", layers, lines(6), truncate_after=3)
    broken = Scene("elemento-roto.dxf", "AC1015", layers, lines(5), broken=2)
    outside = Scene("fuera-de-ambito.dxf", "AC1015", layers, lines(3) + [
        Ent("LINE", "Capa", [P(E0, N0 + 100), P(100.0, 100.0)]),  # un vértice en el golfo de Guinea
        Ent("POLY3D", "Capa", [P(E0, N0 + 110, 1.0), P(E0 + 5, N0 + 110, 1.0), P(100.0, 100.0, 1.0)]),
    ])
    empty = Scene("sin-elementos.dxf", "AC1015", layers, [])
    return [cut, broken, outside, empty]


# Los 4 puntos de los ficheros de conversión: nombre, latitud, longitud, altura.
SOURCE_POINTS = (
    ("Madrid", 40.41668, -3.70371, 646.377),
    ("Zaragoza", 41.6488, -0.8891, 199.25),
    ("Salamanca", 40.9701, -5.6635, 802.125),
    ("Sevilla", 37.3891, -5.9845, 11.5),
)
PROJECT = ("ETRS89", 30, "EPSG:4258", "EPSG:25830")


def origin_scenes() -> list[tuple[Scene, str, float, tuple]]:
    """(escena, nombre del sistema de origen, unidad en metros, entrada de MANUAL_CRS)."""
    out = []
    specs = (
        ("origen-ed50-utm30.dxf", "ED50-UTM30", "m", ("ED50", 30, None, None)),
        ("origen-etrs89-utm29.dxf", "ETRS89-UTM29", "m", ("ETRS89", 29, "EPSG:4258", "EPSG:25829")),
        ("origen-etrs89-utm30-pies.dxf", "ETRS89-UTM30", "ft", PROJECT),
        ("origen-etrs89-utm30-pies-us.dxf", "ETRS89-UTM30", "usft", PROJECT),
    )
    for file, name, unit, crs_row in specs:
        crs = v.ManualCrs(*crs_row)
        factor = {"m": 1.0, "ft": FOOT, "usft": US_FOOT}[unit]
        pts = []
        for _, lat, lon, height in SOURCE_POINTS:
            e, n = crs.typed_from_base(lat, lon, "PROJECT_CRS")
            pts.append(P(round(e / factor, 4), round(n / factor, 4), round(height / factor, 4)))
        ents = [Ent("POINT", "Origen", [p]) for p in pts] + [Ent("LINE", "Origen", [pts[0], pts[1]])]
        out.append((Scene(file, "AC1015", [Layer("0", 7), Layer("Origen", 3)], ents, units=unit), name, factor, crs_row))
    return out


NOT_DXF = {
    "dibujo.dwg": b"AC1032\x00\x00\x00\x00\x00\x01\x0f" + bytes(range(256)) * 4,
    "dwg-como-dxf.dxf": b"AC1027\x00\x00\x00\x00\x00\x01\x0f" + bytes(range(256)) * 4,
    "binario.dxf": b"AutoCAD Binary DXF\r\n\x1a\x00" + b"\x00\x08Eje\x00\x01\x00\x02\x00" * 40,
    "texto.dxf": b"Esto no es un DXF.\r\nSolo es un texto cualquiera.\r\n",
    "vacio.dxf": b"",
}
NOT_DXF_KIND = {"dibujo.dwg": "DWG", "dwg-como-dxf.dxf": "DWG", "binario.dxf": "BINARIO", "texto.dxf": "NO_DXF", "vacio.dxf": "VACIO"}


# ─────────────────────────────────── generación ───────────────────────────────────


def scenes() -> list[Scene]:
    return [
        basic("r12.dxf", "AC1009"), basic("r2000.dxf", "AC1015"), basic("r2010.dxf", "AC1024"), basic("r2018.dxf", "AC1032"),
        blocks_scene(), layers_scene(), curves_ocs_scene(), *z_scenes(), ignored_scene(), *damaged_scenes(),
    ]


def csv_cell(text) -> str:
    text = str(text)
    return '"' + text.replace('"', '""') + '"' if any(c in text for c in (",", '"', "\n")) else text


def csv_rows(rows: list[list]) -> list[str]:
    return [",".join(csv_cell(c) for c in row) for row in rows]


def banner(what: str) -> list[str]:
    return [
        f"# {what} (S040). Generado por tools/formatos/generar_dxf.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla=es_ign_SPED2ETV2.tif",
    ]


def generate() -> dict[str, bytes | str]:
    files: dict[str, bytes | str] = {}
    elements, layers, summary, conversion = [], [], [], []

    for scene in scenes():
        files[scene.file] = Writer(scene).text().encode(scene.encoding)
        readable, discarded = readable_part(scene)
        expander = Expander(readable)
        expander.run()
        expander.discarded.update(discarded)
        describe(scene, expander, elements, layers, summary)

    for scene, name, factor, crs_row in origin_scenes():
        files[scene.file] = Writer(scene).text().encode(scene.encoding)
        crs, project = v.ManualCrs(*crs_row), v.ManualCrs(*PROJECT)
        expander = Expander(scene)
        expander.run()
        describe(scene, expander, elements, layers, summary)
        vertices = [p for el in expander.elements if el.kind == "POINT" for p in el.verts]
        for i, (x, y, z, _) in enumerate(vertices, 1):
            base = crs.base_from_typed("PROJECT_CRS", x * factor, y * factor)
            e, n = project.typed_from_base(base[0], base[1], "PROJECT_CRS")
            conversion.append([scene.file, i, name, {1.0: "m", FOOT: "ft", US_FOOT: "usft"}[factor], num(x), num(y), num(z),
                               num(e), num(n), num(z * factor)])

    for file, data in NOT_DXF.items():
        files[file] = data
        summary.append([file, "tipo_fichero", NOT_DXF_KIND[file]])

    files["esperado.csv"] = "\n".join(banner("Elementos esperados de cada DXF") + [
        "# vertices: 'x y z bulge' separados por ';' (_ si falta). curva: la curva exacta de arcos, círculos y splines. densa: 400 puntos de la spline.",
        "archivo,capa,tipo_capa,tipo_elemento,con_z,color,vertices,curva,densa",
    ] + csv_rows(elements)) + "\n"
    files["esperado-capas.csv"] = "\n".join(banner("Capas de diseño esperadas de cada DXF") + [
        "archivo,capa,tipo_capa,color,visible,elementos",
    ] + csv_rows(layers)) + "\n"
    files["esperado-resumen.csv"] = "\n".join(banner("Resumen esperado de cada fichero") + ["archivo,clave,valor"] + csv_rows(summary)) + "\n"
    files["esperado-conversion.csv"] = "\n".join(banner("Conversión al SC del proyecto (ETRS89 UTM 30)") + [
        "# fuente_*: lo escrito en el DXF en la unidad del fichero. proyecto_*: Este, Norte y altura en metros en ETRS89 UTM 30.",
        "archivo,vertice,sistema_origen,unidad,fuente_x,fuente_y,fuente_z,proyecto_e,proyecto_n,proyecto_z",
    ] + csv_rows(conversion)) + "\n"
    return files


def readable_part(scene: Scene) -> tuple[Scene, dict[str, int]]:
    """Lo que la app debe poder leer de un fichero cortado, con un elemento roto o con vértices fuera del ámbito."""
    ents = scene.ents
    discarded: dict[str, int] = {}
    if scene.truncate_after is not None:
        ents = ents[: scene.truncate_after]
    if scene.broken is not None:
        ents = [e for i, e in enumerate(ents) if i != scene.broken]
        discarded["valor_no_numerico"] = 1
    if scene.file == "fuera-de-ambito.dxf":
        ents = ents[:-2]
        discarded["fuera_de_ambito"] = 2
    return replace(scene, ents=ents), discarded


def describe(scene: Scene, ex: Expander, elements: list, layers: list, summary: list) -> None:
    for el in ex.elements:
        elements.append([scene.file, el.layer, el.layer_kind, el.kind, "SI" if el.has_z else "NO", el.color,
                         ";".join(vertex_text(p) for p in el.verts), el.curve, el.dense])
    seen: dict[tuple[str, str], list] = {}
    table = {layer.name: layer for layer in scene.layers}
    for el in ex.elements:
        key = (el.layer, el.layer_kind)
        if key not in seen:
            layer = table.get(el.layer, Layer(el.layer))
            visible = layer.color >= 0 and not layer.flags & 1
            seen[key] = [scene.file, el.layer, el.layer_kind, ex.layer_color(el.layer), "SI" if visible else "NO", 0]
        seen[key][5] += 1
    layers.extend(seen.values())

    kinds = {"tipo_fichero": "DXF", "version": scene.version, "unidad": scene.units or "", "completo": "NO" if scene.truncate_after is not None else "SI"}
    for key, value in kinds.items():
        summary.append([scene.file, key, value])
    totals: dict[str, int] = {}
    for el in ex.elements:
        totals[el.kind] = totals.get(el.kind, 0) + 1
    summary += [[scene.file, f"elementos.{k}", n] for k, n in sorted(totals.items())]
    summary += [[scene.file, f"ignorados.{k}", n] for k, n in sorted(ex.ignored.items())]
    summary += [[scene.file, f"descartados.{k}", n] for k, n in sorted(ex.discarded.items())]
    zs = [z for el in ex.elements for z in el.zs()]
    summary.append([scene.file, "todo_z_cero", "SI" if zs and all(z == 0 for z in zs) else "NO"])


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        path = OUT / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} {'bytes' if isinstance(content, bytes) else 'caracteres'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
