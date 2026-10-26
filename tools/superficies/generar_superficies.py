"""Referencias de las superficies LandXML calculadas aparte: la cota de una superficie en un punto y la conversión de sus vértices (S042).

Genera en fixtures/superficies/:

- dos-superficies.xml: un LandXML 1.2 en metros con "terreno" (una malla de 6 × 5 celdas de 20 m con una cota que no es un plano,
  con identificadores de punto que no son consecutivos, el orden Norte, Este, cota de LandXML y una celda de dos triángulos invisibles
  que es un hueco) y "rasante" (dos triángulos a cota constante, aparte).
- en-pies.xml: el mismo "terreno" con las coordenadas y las cotas en pies internacionales (`<Imperial linearUnit="foot">`).
- en-utm29.xml: el mismo "terreno" con los vértices en ETRS89 / UTM 29 (EPSG:25829), pasados con pyproj desde las coordenadas de UTM 30.
- ids-texto.xml: dos triángulos con identificadores de punto que son texto (`A1`, `B2`…).
- malla.xml: una superficie definida por malla regular (`surfType="grid"`), que la app no importa.
- sin-superficies.xml: un LandXML sin superficies.
- mal-formado.xml: un XML cortado a la mitad.
- consultas.csv: para cada punto de consulta (dentro de un triángulo, sobre una arista, sobre un vértice, fuera del contorno y en el
  hueco) la cota esperada del "terreno" en UTM 30, en metros; vacía si no hay superficie.
- conversion.csv: cada vértice del "terreno" en UTM 29 (como está en en-utm29.xml) y en UTM 30 con pyproj (EPSG:25829 → 25830).

La cota esperada sale de otro cálculo que el de la app: aquí se resuelve el plano de cada triángulo por tres puntos con una
regla de Cramer y se comprueba que el punto cae dentro con tres productos vectoriales, sin coordenadas baricéntricas.

Uso: uv run --project tools tools/superficies/generar_superficies.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

from pyproj import Transformer
import pyproj

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "superficies"
FOOT_M = 0.3048
LAT0, LON0 = 40.4168, -3.7038
STEP_M = 20.0
NX, NY = 6, 5
HOLE_CELL = (2, 2)
ID_START, ID_STEP = 101, 3
TO_UTM30 = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
TO_UTM29 = Transformer.from_crs("EPSG:4258", "EPSG:25829", always_xy=True)
UTM29_TO_30 = Transformer.from_crs("EPSG:25829", "EPSG:25830", always_xy=True)
EPS = 1e-9


@dataclass(frozen=True)
class Vertex:
    id: int
    e: float
    n: float
    z: float


Face = tuple[int, int, int, bool]  # índices de vértice y si es invisible


def geographic(dx: float, dy: float) -> tuple[float, float]:
    return LON0 + dx / (111_000.0 * math.cos(math.radians(LAT0))), LAT0 + dy / 111_000.0


def height(dx: float, dy: float) -> float:
    """La cota del terreno: una pendiente con una ondulación, para que no sea un plano."""
    return round(600.0 + 0.05 * dx + 0.03 * dy + 1.5 * math.sin(dx / 15.0) * math.cos(dy / 12.0), 3)


def terrain() -> tuple[list[Vertex], list[Face]]:
    """El "terreno" en UTM 30: vértices con Este y Norte a 5 decimales, y las caras de cada celda en dos triángulos."""
    vertices: list[Vertex] = []
    for j in range(NY + 1):
        for i in range(NX + 1):
            dx, dy = i * STEP_M, j * STEP_M
            lon, lat = geographic(dx, dy)
            e, n = TO_UTM30.transform(lon, lat)
            vertices.append(Vertex(ID_START + ID_STEP * (j * (NX + 1) + i), round(e, 5), round(n, 5), height(dx, dy)))
    faces: list[Face] = []
    for j in range(NY):
        for i in range(NX):
            a = j * (NX + 1) + i
            b, c, d = a + 1, a + NX + 1, a + NX + 2
            hidden = (i, j) == HOLE_CELL
            faces.append((a, b, d, hidden))
            faces.append((a, d, c, hidden))
    return vertices, faces


def rasante() -> tuple[list[Vertex], list[Face]]:
    lon, lat = geographic(400.0, 0.0)
    e0, n0 = TO_UTM30.transform(lon, lat)
    e0, n0 = round(e0, 5), round(n0, 5)
    vertices = [Vertex(1, e0, n0, 610.0), Vertex(2, e0 + 30.0, n0, 610.0), Vertex(3, e0 + 30.0, n0 + 30.0, 610.0), Vertex(4, e0, n0 + 30.0, 610.0)]
    return vertices, [(0, 1, 2, False), (0, 2, 3, False)]


def num(value: float, decimals: int = 5) -> str:
    return f"{value:.{decimals}f}"


def landxml(units: str, surfaces: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2" date="2026-10-26" time="12:00:00">\n'
        f"  <Units>{units}</Units>\n"
        f"  <Surfaces>\n{surfaces}  </Surfaces>\n"
        "</LandXML>\n"
    )


METRIC = '<Metric areaUnit="squareMeter" linearUnit="meter" volumeUnit="cubicMeter" temperatureUnit="celsius" pressureUnit="HPA"/>'
IMPERIAL = '<Imperial areaUnit="squareFoot" linearUnit="foot" volumeUnit="cubicFeet" temperatureUnit="fahrenheit" pressureUnit="inHG"/>'


def surface_xml(
    name: str, vertices: list[Vertex], faces: list[Face], scale: float = 1.0, ids: list[str] | None = None, decimals: int = 5
) -> str:
    """Una superficie TIN. LandXML escribe cada punto como Norte, Este, cota; `scale` pasa de metros a la unidad del fichero."""
    labels = ids if ids is not None else [str(v.id) for v in vertices]
    lines = [f'    <Surface name="{name}">', '      <Definition surfType="TIN">', "        <Pnts>"]
    for label, v in zip(labels, vertices):
        lines.append(f'          <P id="{label}">{num(v.n * scale, decimals)} {num(v.e * scale, decimals)} {num(v.z * scale, decimals)}</P>')
    lines.append("        </Pnts>")
    lines.append("        <Faces>")
    for a, b, c, hidden in faces:
        flag = ' i="1"' if hidden else ""
        lines.append(f"          <F{flag}>{labels[a]} {labels[b]} {labels[c]}</F>")
    lines.append("        </Faces>")
    lines.append("      </Definition>")
    lines.append("    </Surface>")
    return "\n".join(lines) + "\n"


def generate_dos_superficies() -> str:
    t_vertices, t_faces = terrain()
    r_vertices, r_faces = rasante()
    return landxml(METRIC, surface_xml("terreno", t_vertices, t_faces) + surface_xml("rasante", r_vertices, r_faces))


def generate_en_pies() -> str:
    vertices, faces = terrain()
    return landxml(IMPERIAL, surface_xml("terreno", vertices, faces, scale=1.0 / FOOT_M, decimals=6))


def utm29_vertices() -> list[tuple[Vertex, float, float]]:
    """Cada vértice del terreno con su Este y su Norte en UTM 29, calculados desde las coordenadas geográficas del punto."""
    vertices, _ = terrain()
    out = []
    for k, v in enumerate(vertices):
        i, j = k % (NX + 1), k // (NX + 1)
        lon, lat = geographic(i * STEP_M, j * STEP_M)
        e29, n29 = TO_UTM29.transform(lon, lat)
        out.append((v, round(e29, 5), round(n29, 5)))
    return out


def generate_en_utm29() -> str:
    _, faces = terrain()
    moved = [Vertex(v.id, e29, n29, v.z) for v, e29, n29 in utm29_vertices()]
    return landxml(METRIC, surface_xml("terreno", moved, faces))


def generate_ids_texto() -> str:
    vertices = [Vertex(0, 1000.0, 2000.0, 10.0), Vertex(0, 1010.0, 2000.0, 11.0), Vertex(0, 1010.0, 2010.0, 12.0), Vertex(0, 1000.0, 2010.0, 13.0)]
    faces: list[Face] = [(0, 1, 2, False), (0, 2, 3, False)]
    return landxml(METRIC, surface_xml("texto", vertices, faces, ids=["A1", "B2", "C3", "D4"], decimals=3))


def generate_malla() -> str:
    surface = (
        '    <Surface name="malla">\n'
        '      <Definition surfType="grid">\n'
        '        <Grid rowCount="2" colCount="2" cellSize="10"><Pnts><P>10.0</P><P>11.0</P></Pnts></Grid>\n'
        "      </Definition>\n"
        "    </Surface>\n"
    )
    return landxml(METRIC, surface)


def generate_sin_superficies() -> str:
    return landxml(METRIC, "")


def generate_mal_formado() -> str:
    return generate_dos_superficies()[:900]


@dataclass(frozen=True)
class Query:
    kind: str
    e: float
    n: float


def inside(p: tuple[float, float], a: Vertex, b: Vertex, c: Vertex) -> bool:
    """Tres productos vectoriales con el mismo signo (o cero, sobre el borde), sin coordenadas baricéntricas."""
    def cross(o: Vertex, q: Vertex) -> float:
        return (q.e - o.e) * (p[1] - o.n) - (q.n - o.n) * (p[0] - o.e)

    s = (cross(a, b), cross(b, c), cross(c, a))
    tol = EPS * max(1.0, math.hypot(b.e - a.e, b.n - a.n)) * 1e3
    return all(x >= -tol for x in s) or all(x <= tol for x in s)


def plane_height(p: tuple[float, float], a: Vertex, b: Vertex, c: Vertex) -> float:
    """La cota del plano por a, b y c en p, resolviendo z = z0 + gx·dE + gy·dN por la regla de Cramer."""
    d1e, d1n, d1z = b.e - a.e, b.n - a.n, b.z - a.z
    d2e, d2n, d2z = c.e - a.e, c.n - a.n, c.z - a.z
    det = d1e * d2n - d1n * d2e
    gx = (d1z * d2n - d1n * d2z) / det
    gy = (d1e * d2z - d1z * d2e) / det
    return a.z + gx * (p[0] - a.e) + gy * (p[1] - a.n)


def expected_height(p: tuple[float, float]) -> float | None:
    vertices, faces = terrain()
    for a, b, c, hidden in faces:
        if hidden:
            continue
        if inside(p, vertices[a], vertices[b], vertices[c]):
            return plane_height(p, vertices[a], vertices[b], vertices[c])
    return None


def queries() -> list[Query]:
    vertices, faces = terrain()
    out: list[Query] = []

    def centroid(face: Face) -> tuple[float, float]:
        a, b, c, _ = face
        return ((vertices[a].e + vertices[b].e + vertices[c].e) / 3, (vertices[a].n + vertices[b].n + vertices[c].n) / 3)

    for index in (0, 3, 8, 15, 22, 41, 58):
        e, n = centroid(faces[index])
        out.append(Query("dentro", round(e + 0.37, 5), round(n - 0.21, 5)))
    for a_idx, b_idx in ((0, 1), (7, 8), (20, 27), (14, 21), (33, 34)):  # aristas, algunas del contorno
        a, b = vertices[a_idx], vertices[b_idx]
        out.append(Query("arista", round((a.e + b.e) / 2, 5), round((a.n + b.n) / 2, 5)))
    out.append(Query("arista", round((vertices[0].e + vertices[NX + 2].e) / 2, 5), round((vertices[0].n + vertices[NX + 2].n) / 2, 5)))  # diagonal
    for index in (0, 6, 17, 24, 41):
        v = vertices[index]
        out.append(Query("vertice", v.e, v.n))
    lon_c, lat_c = geographic((HOLE_CELL[0] + 0.5) * STEP_M, (HOLE_CELL[1] + 0.5) * STEP_M)
    e, n = TO_UTM30.transform(lon_c, lat_c)
    out.append(Query("hueco", round(e, 5), round(n, 5)))
    out.append(Query("hueco", round(e + 3.0, 5), round(n - 2.0, 5)))
    first, last = vertices[0], vertices[-1]
    for de, dn in ((-5.0, -5.0), (-2.0, 10.0), (last.e - first.e + 5.0, 10.0), (50.0, last.n - first.n + 3.0), (1000.0, 1000.0)):
        out.append(Query("fuera", round(first.e + de, 5), round(first.n + dn, 5)))
    return out


def header(name: str) -> str:
    return (
        f"# Superficies LandXML ({name}) calculadas aparte (S042). Generado por tools/superficies/generar_superficies.py; no editar a mano.\n"
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; EPSG:4258 → 25830 y 25829 → 25830. Cotas en metros.\n"
    )


def generate_consultas() -> str:
    lines = [header("consultas"), "tipo,este,norte,cota\n"]
    for q in queries():
        z = expected_height((q.e, q.n))
        lines.append(f"{q.kind},{num(q.e)},{num(q.n)},{'' if z is None else num(z, 9)}\n")
    return "".join(lines)


def generate_conversion() -> str:
    lines = [header("conversion"), "id,este29,norte29,este30,norte30\n"]
    for v, e29, n29 in utm29_vertices():
        e30, n30 = UTM29_TO_30.transform(e29, n29)
        lines.append(f"{v.id},{num(e29)},{num(n29)},{num(e30, 9)},{num(n30, 9)}\n")
    return "".join(lines)


FILES = {
    "dos-superficies.xml": generate_dos_superficies,
    "en-pies.xml": generate_en_pies,
    "en-utm29.xml": generate_en_utm29,
    "ids-texto.xml": generate_ids_texto,
    "malla.xml": generate_malla,
    "sin-superficies.xml": generate_sin_superficies,
    "mal-formado.xml": generate_mal_formado,
    "consultas.csv": generate_consultas,
    "conversion.csv": generate_conversion,
}


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, build in FILES.items():
        content = build()
        (OUT / name).write_text(content, encoding="utf-8", newline="\n")
        print(f"{OUT / name}: {len(content.splitlines())} líneas")
    return 0


if __name__ == "__main__":
    sys.exit(main())
