"""Referencias de la información geométrica calculadas aparte: longitudes, azimut, pendiente, área y ángulo (S026).

Genera cuatro ficheros en fixtures/geometria/:

- vertices.csv: los vértices de cada caso. Los de los casos proyectados salen de unas coordenadas ETRS89 / UTM 30 (EPSG:25830) definidas
  a mano, a 5 decimales, y su latitud y longitud (EPSG:4258, 10 decimales) de pyproj. El caso geográfico solo lleva latitud y longitud.
- segmentos.csv: por caso y modo (`grid` o `ground`) y segmento: PK al inicio, longitud 2D, longitud 3D, azimut, desnivel y pendiente.
- totales.csv: por caso y modo: longitud 2D, longitud 3D, perímetro, área (valor absoluto de la fórmula de Gauss) y si hay cruces.
- angulos.csv: por caso de ángulo y modo: el ángulo en B, los azimuts de BA y BC y las distancias BA y BC.

Reglas que la spec fija y que el script aplica sin usar nada de Kotlin:
- Distancia de cuadrícula = hypot(ΔE, ΔN). Distancia de terreno = la de cuadrícula entre el factor combinado del centro del segmento.
- El factor combinado es k·R/(R+h): k = parallel_scale de pyproj, R = radio medio de Gauss de GRS80 y h = altura elipsoidal. El centro del
  segmento es el punto medio de las latitudes y longitudes de sus extremos, con la media de sus alturas elipsoidales; si a un extremo le
  falta la altura se usa FALLBACK_HEIGHT_M (la altura del punto central del proyecto).
- El área de terreno es la de cuadrícula entre el cuadrado del factor combinado del centro del polígono: la media de las latitudes,
  longitudes y alturas de todos sus vértices (con FALLBACK_HEIGHT_M si falta alguna altura).
- Azimut = atan2(ΔE, ΔN) en [0, 2π), sobre las coordenadas de cuadrícula.
- Longitud 3D = hypot(distancia horizontal del modo, desnivel); pendiente = desnivel / distancia horizontal · 100, vacía si la distancia es 0.
- Sin altura del proyecto («cota») en un extremo, el segmento no lleva longitud 3D, desnivel ni pendiente.
- Caso geográfico (sin Este y Norte): las coordenadas planas son el ENU de cada vértice respecto al primero (con alturas elipsoidales,
  0 si faltan) y no hay factor combinado: `ground` coincide con `grid`.
- Ángulo en B = atan2(|BA × BC|, BA · BC), de 0 a π.

Uso: uv run --project tools tools/geometria/generar_referencias.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

from pyproj import Proj, Transformer

OUT = Path(__file__).resolve().parents[2] / "fixtures" / "geometria"
NAME_VERTICES = "vertices.csv"
NAME_SEGMENTOS = "segmentos.csv"
NAME_TOTALES = "totales.csv"
NAME_ANGULOS = "angulos.csv"

FALLBACK_HEIGHT_M = 650.0
A = 6378137.0
F = 1 / 298.257222101
E2 = F * (2 - F)
UTM30 = Proj(proj="utm", zone=30, ellps="GRS80")
INVERSE = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)
TO_ECEF = Transformer.from_crs("EPSG:4979", "EPSG:4978", always_xy=True)
E0, N0 = 440000.0, 4474000.0
UNDULATION_M = 49.5


@dataclass(frozen=True)
class Vertex:
    lat: float
    lon: float
    ell: float | None
    east: float | None
    north: float | None
    height: float | None


@dataclass(frozen=True)
class Case:
    name: str
    closed: bool
    vertices: tuple[Vertex, ...]


def projected(de: float, dn: float, cota: float | None, elip: bool = True) -> Vertex:
    """Un vértice a (de, dn) metros de la base en la cuadrícula; la altura elipsoidal es la cota más la ondulación."""
    east, north = round(E0 + de, 5), round(N0 + dn, 5)
    lon, lat = INVERSE.transform(east, north)
    ell = cota + UNDULATION_M if cota is not None else 650.0 + UNDULATION_M
    return Vertex(round(lat, 10), round(lon, 10), ell if elip else None, east, north, cota)


def geographic(lat: float, lon: float, ell: float | None) -> Vertex:
    return Vertex(lat, lon, ell, None, None, None)


def cases() -> list[Case]:
    return [
        Case("recta", False, (projected(0, 0, 640.0), projected(240.0, 180.0, 652.5))),
        Case(
            "quebrada",
            False,
            tuple(
                projected(x, y, z)
                for x, y, z in (
                    (0, 0, 640.0),
                    (900.25, 120.5, 651.0),
                    (1800.0, -400.0, 649.5),
                    (3100.0, 800.0, 720.0),
                    (4200.0, 2600.0, 700.0),
                    (3800.0, 5200.0, 698.5),
                )
            ),
        ),
        Case("vertical", False, (projected(0, 0, 640.0), projected(100, 50, 645.0), projected(100, 50, 655.0), projected(160, 120, 650.0))),
        Case("sin_cota", False, (projected(0, 0, 640.0), projected(120, 40, None), projected(300, 90, 646.0), projected(420, 200, 650.0))),
        Case("sin_elip", False, (projected(0, 0, 640.0, elip=False), projected(500, 300, 660.0, elip=False), projected(900, 100, 655.0, elip=False))),
        Case(
            "concavo",
            True,
            tuple(projected(x, y, z) for x, y, z in ((0, 0, 700.0), (200, 0, 701.0), (200, 50, 702.0), (100, 50, 701.5), (100, 150, 703.0), (0, 150, 702.5))),
        ),
        Case("cruces", True, tuple(projected(x, y, 700.0 + x / 50) for x, y in ((0, 0), (100, 100), (100, 0), (0, 60)))),
        Case(
            "parcela",
            True,
            tuple(projected(x, y, z) for x, y, z in ((0, 0, 700.0), (420, 30, 705.0), (480, 380, 712.0), (260, 520, 710.0), (-30, 340, 704.0))),
        ),
        Case(
            "geografico",
            False,
            (
                geographic(40.4168, -3.7038, 700.0),
                geographic(40.4182, -3.7021, 705.25),
                geographic(40.4199, -3.7040, 710.5),
                geographic(40.4210, -3.7002, None),
            ),
        ),
        Case("angulo_general", False, (projected(-120, -80, 640.0), projected(0, 0, 645.0), projected(150, -60, 650.0))),
        Case("angulo_recto", False, (projected(0, 100, 640.0), projected(0, 0, 645.0), projected(100, 0, 650.0))),
        Case("angulo_casi_llano", False, (projected(-1000, -0.0001, 640.0), projected(0, 0, 645.0), projected(1000, 0, 650.0))),
        Case("angulo_casi_nulo", False, (projected(1000, 10, 640.0), projected(0, 0, 645.0), projected(1000, 0, 650.0))),
    ]


def combined_factor(lat: float, lon: float, ell: float | None) -> float:
    """k·R/(R+h) con k de pyproj y R el radio medio de Gauss de GRS80 en la latitud."""
    h = FALLBACK_HEIGHT_M if ell is None else ell
    k = UTM30.get_factors(lon, lat).parallel_scale
    sin2 = math.sin(math.radians(lat)) ** 2
    radius = A * math.sqrt(1 - E2) / (1 - E2 * sin2)
    return k * radius / (radius + h)


def centre(points: tuple[Vertex, ...] | list[Vertex]) -> tuple[float, float, float | None]:
    lat = sum(v.lat for v in points) / len(points)
    lon = sum(v.lon for v in points) / len(points)
    heights = [v.ell for v in points]
    ell = None if any(h is None for h in heights) else sum(heights) / len(heights)
    return lat, lon, ell


def segment_factor(case_geographic: bool, mode: str, a: Vertex, b: Vertex) -> float:
    if mode == "grid" or case_geographic:
        return 1.0
    return combined_factor(*centre([a, b]))


def plane(case: Case) -> list[tuple[float, float]]:
    """Coordenadas planas: las de cuadrícula o, sin ellas, el ENU respecto al primer vértice."""
    first = case.vertices[0]
    if first.east is not None:
        return [(v.east, v.north) for v in case.vertices]
    lat0, lon0 = math.radians(first.lat), math.radians(first.lon)
    x0, y0, z0 = TO_ECEF.transform(first.lon, first.lat, first.ell or 0.0)
    result = []
    for v in case.vertices:
        x, y, z = TO_ECEF.transform(v.lon, v.lat, v.ell or 0.0)
        dx, dy, dz = x - x0, y - y0, z - z0
        east = -math.sin(lon0) * dx + math.cos(lon0) * dy
        north = -math.sin(lat0) * math.cos(lon0) * dx - math.sin(lat0) * math.sin(lon0) * dy + math.cos(lat0) * dz
        result.append((east, north))
    return result


def azimuth(de: float, dn: float) -> float:
    return math.atan2(de, dn) % (2 * math.pi)


def segments_of(case: Case) -> list[tuple[int, int]]:
    n = len(case.vertices)
    count = n if case.closed else n - 1
    return [(i, (i + 1) % n) for i in range(count)]


def ccw(p, q, r) -> float:
    return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])


def crossing(p1, p2, p3, p4) -> bool:
    d1, d2, d3, d4 = ccw(p3, p4, p1), ccw(p3, p4, p2), ccw(p1, p2, p3), ccw(p1, p2, p4)
    return d1 * d2 < 0 and d3 * d4 < 0


def has_crossings(case: Case, xy: list[tuple[float, float]]) -> bool:
    segs = segments_of(case)
    for i, (a, b) in enumerate(segs):
        for j in range(i + 1, len(segs)):
            c, d = segs[j]
            if len({a, b, c, d}) < 4:
                continue  # comparten un vértice: son vecinos
            if crossing(xy[a], xy[b], xy[c], xy[d]):
                return True
    return False


def num(value: float | None, digits: int = 9) -> str:
    return "" if value is None else f"{value:.{digits}f}"


def vertices_rows() -> list[str]:
    rows = ["caso,orden,latitud,longitud,altura_elipsoidal,este,norte,cota,cerrada"]
    for case in cases():
        for i, v in enumerate(case.vertices):
            rows.append(
                ",".join(
                    [
                        case.name,
                        str(i),
                        f"{v.lat:.10f}",
                        f"{v.lon:.10f}",
                        num(v.ell, 5),
                        num(v.east, 5),
                        num(v.north, 5),
                        num(v.height, 5),
                        "1" if case.closed else "0",
                    ]
                )
            )
    return rows


def evaluate(case: Case, mode: str) -> tuple[list[str], str]:
    xy = plane(case)
    geo = case.vertices[0].east is None
    seg_rows, pk, total2d, total3d, missing = [], 0.0, 0.0, 0.0, False
    for index, (a, b) in enumerate(segments_of(case)):
        va, vb = case.vertices[a], case.vertices[b]
        de, dn = xy[b][0] - xy[a][0], xy[b][1] - xy[a][1]
        d2 = math.hypot(de, dn) / segment_factor(geo, mode, va, vb)
        d3 = dh = slope = None
        if va.height is not None and vb.height is not None:
            dh = vb.height - va.height
            d3 = math.hypot(d2, dh)
            slope = dh / d2 * 100 if d2 > 0 else None
        else:
            missing = True
        seg_rows.append(
            ",".join([case.name, mode, str(index), str(a), str(b), num(pk), num(d2), num(d3), num(azimuth(de, dn), 12), num(dh), num(slope, 9)])
        )
        pk += d2
        total2d += d2
        total3d += d3 or 0.0
    perimeter = area = None
    if case.closed:
        perimeter = total2d
        x0, y0 = xy[0]
        rel = [(x - x0, y - y0) for x, y in xy]
        signed = sum(rel[i][0] * rel[(i + 1) % len(rel)][1] - rel[(i + 1) % len(rel)][0] * rel[i][1] for i in range(len(rel))) / 2
        f = 1.0 if (mode == "grid" or geo) else combined_factor(*centre(case.vertices))
        area = abs(signed) / (f * f)
    total = ",".join(
        [
            case.name,
            mode,
            num(total2d),
            "" if missing else num(total3d),
            num(perimeter),
            num(area, 6),
            "1" if has_crossings(case, xy) else "0",
        ]
    )
    return seg_rows, total


def angle_row(case: Case, mode: str) -> str:
    a, b, c = case.vertices
    xy = plane(case)
    ba = (xy[0][0] - xy[1][0], xy[0][1] - xy[1][1])
    bc = (xy[2][0] - xy[1][0], xy[2][1] - xy[1][1])
    angle = math.atan2(abs(ba[0] * bc[1] - ba[1] * bc[0]), ba[0] * bc[0] + ba[1] * bc[1])
    d_ba = math.hypot(*ba) / segment_factor(False, mode, b, a)
    d_bc = math.hypot(*bc) / segment_factor(False, mode, b, c)
    return ",".join([case.name, mode, num(angle, 12), num(azimuth(*ba), 12), num(azimuth(*bc), 12), num(d_ba), num(d_bc)])


def build() -> dict[str, list[str]]:
    segmentos = ["caso,modo,indice,desde,hasta,pk,longitud_2d,longitud_3d,azimut_rad,desnivel,pendiente_pct"]
    totales = ["caso,modo,longitud_2d,longitud_3d,perimetro,area,cruces"]
    angulos = ["caso,modo,angulo_rad,azimut_ba_rad,azimut_bc_rad,distancia_ba,distancia_bc"]
    for case in cases():
        for mode in ("grid", "ground"):
            if case.name.startswith("angulo"):
                angulos.append(angle_row(case, mode))
                continue
            rows, total = evaluate(case, mode)
            segmentos += rows
            totales.append(total)
    return {NAME_VERTICES: vertices_rows(), NAME_SEGMENTOS: segmentos, NAME_TOTALES: totales, NAME_ANGULOS: angulos}


def all_files() -> list[str]:
    return [NAME_VERTICES, NAME_SEGMENTOS, NAME_TOTALES, NAME_ANGULOS]


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in build().items():
        (out / name).write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
