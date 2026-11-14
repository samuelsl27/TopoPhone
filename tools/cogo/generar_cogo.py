"""Referencias de COGO calculadas aparte: puntos, inversas, intersecciones y poligonales (S038).

Genera en fixtures/cogo/:

- puntos.csv: los puntos de los casos en tres SC (ETRS89 UTM 30, REGCAN95 UTM 28 y ED50 UTM 30): Este y Norte (5 decimales), su latitud y
  longitud ETRS89 (10 decimales, por pyproj y, en ED50, por la rejilla SPED2ETV2 del IGN), la altura elipsoidal y la cota del proyecto
  (la elipsoidal menos 49,5 m). P1 a P8 son fijos (P7 sin cota ni altura elipsoidal) y R1 a R12 salen de la semilla 38.
- inversas.csv: por caso, SC y modo (`grid` o `ground`): factor combinado del primer punto, distancias de cuadrícula y de terreno,
  azimut directo e inverso, diferencias, distancia inclinada y pendiente con la distancia activa.
- intersecciones.csv: por caso, SC y modo: lo que se escribe (distancias o azimuts) y cada solución con su etiqueta, o el motivo si no
  hay. Los casos se construyen a partir de un punto conocido T: las entradas son las distancias o los azimuts que dan T, y las
  soluciones esperadas T y su simétrica, calculadas por otro camino (reflexión, pie de la perpendicular).
- poligonales.csv y cierres.csv: ver `poligonales()`; las poligonales parten de puntos conocidos y de ellas se sacan los azimuts, ángulos
  y deflexiones que Kotlin debe recorrer para recuperar los puntos.

Reglas que la spec fija y que el script aplica sin usar nada de Kotlin:
- Azimut = atan2(ΔE, ΔN) en [0, 2π), sobre las coordenadas de cuadrícula. Distancia de terreno = la de cuadrícula entre el factor
  combinado del PRIMER punto de entrada (S038/RF-5); lo que se escribe en «terreno» se multiplica por ese factor.
- Factor combinado = k·R/(R+h): k = parallel_scale de pyproj en el SC (en ED50, sobre el elipsoide internacional y las geográficas
  ED50), R = radio medio de Gauss del elipsoide en la latitud de datum y h = altura elipsoidal; sin altura, solo k (como S026/D7).
- Izquierda y derecha, respecto al sentido del primer al segundo punto, por el signo del producto vectorial (B−A) × (S−A).

Uso: uv run --project tools tools/cogo/generar_cogo.py
Es determinista: la misma versión de pyproj/PROJ y de la rejilla dan los mismos ficheros.
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from pathlib import Path

from pyproj import Proj

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "cogo"
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores as gv  # noqa: E402

NAME_PUNTOS = "puntos.csv"
NAME_INVERSAS = "inversas.csv"
NAME_INTERSECCIONES = "intersecciones.csv"
NAME_POLIGONALES = "poligonales.csv"
NAME_CIERRES = "cierres.csv"

SEED = 38
UNDULATION_M = 49.5
MODES = ("grid", "ground")
TWO_PI = 2 * math.pi


@dataclass(frozen=True)
class Crs:
    name: str
    zone: int
    ed50: bool
    e0: float
    n0: float

    @property
    def ellipsoid(self) -> tuple[float, float]:
        """Semieje mayor y aplanamiento: GRS80 o el internacional de 1924 (ED50)."""
        return (6378388.0, 1 / 297.0) if self.ed50 else (6378137.0, 1 / 298.257222101)


CRSS = (
    Crs("ETRS89_UTM30", 30, False, 440000.0, 4474000.0),
    Crs("REGCAN95_UTM28", 28, False, 378000.0, 3147000.0),
    Crs("ED50_UTM30", 30, True, 440000.0, 4474000.0),
)

# id: (dE, dN, cota). P7 no tiene cota ni altura elipsoidal.
FIXED = {
    "P1": (0.0, 0.0, 650.0),
    "P2": (150.25, 40.125, 652.5),
    "P3": (210.5, 190.75, 655.25),
    "P4": (-35.0, 230.5, 648.0),
    "P5": (-120.75, 80.25, 646.5),
    "P6": (400.0, -150.0, 660.0),
    "P7": (50.0, -60.0, None),
    # Sobre la recta que sale de P1 con azimut 0,7 rad, a 100 m: para las direcciones coincidentes.
    "P8": (round(100 * math.sin(0.7), 5), round(100 * math.cos(0.7), 5), 651.0),
}


@dataclass(frozen=True)
class Pt:
    id: str
    lat: float
    lon: float
    ell: float | None
    east: float
    north: float
    height: float | None


_UTM: dict[tuple[int, bool], Proj] = {}
_TRANSFORMERS: dict[str, object] = {}


def utm(zone: int, ed50: bool) -> Proj:
    key = (zone, ed50)
    if key not in _UTM:
        _UTM[key] = Proj(proj="utm", zone=zone, ellps="intl" if ed50 else "GRS80")
    return _UTM[key]


def to_ground(crs: Crs, east: float, north: float) -> tuple[float, float]:
    """Latitud y longitud ETRS89 (o REGCAN95) de un Este y Norte del SC."""
    if crs.ed50:
        if "to_etrs89" not in _TRANSFORMERS:
            _TRANSFORMERS["to_etrs89"] = gv.ed50_to_etrs89(crs.zone)
        lon, lat = _TRANSFORMERS["to_etrs89"].transform(east, north)
    else:
        lon, lat = utm(crs.zone, False)(east, north, inverse=True)
    return lat, lon


def datum_position(crs: Crs, lat: float, lon: float) -> tuple[float, float]:
    """Latitud y longitud del datum del SC: las mismas, salvo en ED50 (por la rejilla)."""
    if not crs.ed50:
        return lat, lon
    if "to_ed50" not in _TRANSFORMERS:
        _TRANSFORMERS["to_ed50"] = gv.ed50_transformer(None)
    lon_ed, lat_ed = _TRANSFORMERS["to_ed50"].transform(lon, lat)
    return lat_ed, lon_ed


def combined_factor(crs: Crs, point: Pt) -> float:
    lat, lon = datum_position(crs, point.lat, point.lon)
    k = utm(crs.zone, crs.ed50).get_factors(lon, lat).parallel_scale
    if point.ell is None:
        return k
    a, f = crs.ellipsoid
    e2 = f * (2 - f)
    radius = a * math.sqrt(1 - e2) / (1 - e2 * math.sin(math.radians(lat)) ** 2)
    return k * radius / (radius + point.ell)


def make_point(crs: Crs, pid: str, de: float, dn: float, height: float | None) -> Pt:
    east, north = round(crs.e0 + de, 5), round(crs.n0 + dn, 5)
    lat, lon = to_ground(crs, east, north)
    ell = None if height is None else round(height + UNDULATION_M, 5)
    return Pt(pid, round(lat, 10), round(lon, 10), ell, east, north, height)


def points_of(crs: Crs) -> dict[str, Pt]:
    points = {pid: make_point(crs, pid, de, dn, h) for pid, (de, dn, h) in FIXED.items()}
    rng = random.Random(SEED + crs.zone + int(crs.ed50))
    for i in range(1, 13):
        points[f"R{i}"] = make_point(
            crs, f"R{i}", round(rng.uniform(-600, 600), 3), round(rng.uniform(-600, 600), 3), round(rng.uniform(640, 720), 3)
        )
    return points


def fmt(value: float | None) -> str:
    return "" if value is None else repr(float(value))


def azimuth(de: float, dn: float) -> float:
    return math.atan2(de, dn) % TWO_PI


def direction(az: float) -> tuple[float, float]:
    return math.sin(az), math.cos(az)


def cross(a: tuple[float, float], b: tuple[float, float]) -> float:
    return a[0] * b[1] - a[1] * b[0]


def dot(a: tuple[float, float], b: tuple[float, float]) -> float:
    return a[0] * b[0] + a[1] * b[1]


def sub(a: Pt, b: Pt) -> tuple[float, float]:
    return a.east - b.east, a.north - b.north


def puntos_rows() -> list[str]:
    rows = ["crs,id,latitud,longitud,altura_elipsoidal,este,norte,cota"]
    for crs in CRSS:
        for p in points_of(crs).values():
            rows.append(",".join([crs.name, p.id, fmt(p.lat), fmt(p.lon), fmt(p.ell), fmt(p.east), fmt(p.north), fmt(p.height)]))
    return rows


INVERSE_PAIRS = (
    ("directa", "P1", "P2"),
    ("inversa", "P2", "P1"),
    ("larga", "P1", "P6"),
    ("cuarto_cuadrante", "P3", "P4"),
    ("oeste", "P5", "P3"),
    ("coincidentes", "P1", "P1"),
    ("sin_altura_a", "P7", "P1"),
    ("sin_altura_b", "P1", "P7"),
)


def inversas_rows() -> list[str]:
    rows = [
        "caso,crs,modo,punto_a,punto_b,factor,dist_cuadricula,dist_terreno,azimut_directo_rad,azimut_inverso_rad,"
        "d_este,d_norte,d_altura,dist_inclinada,pendiente_pct"
    ]
    for crs in CRSS:
        pts = points_of(crs)
        pairs = list(INVERSE_PAIRS) + [(f"aleatoria_{i}", f"R{i}", f"R{i + 6}") for i in range(1, 7)]
        for name, a, b in pairs:
            pa, pb = pts[a], pts[b]
            f = combined_factor(crs, pa)
            de, dn = sub(pb, pa)
            grid = math.hypot(de, dn)
            ground = grid / f
            az = azimuth(de, dn) if grid > 0 else None
            for mode in MODES:
                horizontal = grid if mode == "grid" else ground
                dh = pb.height - pa.height if pa.height is not None and pb.height is not None else None
                inclined = math.hypot(horizontal, dh) if dh is not None else None
                slope = dh / horizontal * 100 if dh is not None and horizontal > 0 else None
                rows.append(
                    ",".join(
                        [
                            name, crs.name, mode, a, b, fmt(f), fmt(grid), fmt(ground), fmt(az),
                            fmt((az + math.pi) % TWO_PI if az is not None else None), fmt(de), fmt(dn), fmt(dh), fmt(inclined), fmt(slope),
                        ]
                    )
                )
    return rows


@dataclass(frozen=True)
class Scenario:
    name: str
    kind: str  # dd, bb, db
    p1: str
    p2: str
    v1: float  # lo que se escribe, en grid (distancias de cuadrícula o azimut en rad)
    v2: float
    solutions: tuple[tuple[str, float, float, float, float], ...]  # etiqueta, este, norte, t1, t2 (de cuadrícula)
    reason: str = ""


def side(a: Pt, b: Pt, e: float, n: float) -> float:
    return cross((b.east - a.east, b.north - a.north), (e - a.east, n - a.north))


def dd_scenarios(pts: dict[str, Pt], name: str, a: str, b: str, t: str) -> list[Scenario]:
    pa, pb, pt = pts[a], pts[b], pts[t]
    r1, r2 = math.hypot(*sub(pt, pa)), math.hypot(*sub(pt, pb))
    # Simétrica de T respecto de la recta AB, por reflexión con la normal unitaria.
    ux, uy = pb.east - pa.east, pb.north - pa.north
    length = math.hypot(ux, uy)
    nx, ny = -uy / length, ux / length
    dist = (pt.east - pa.east) * nx + (pt.north - pa.north) * ny
    mirror = (pt.east - 2 * dist * nx, pt.north - 2 * dist * ny)
    found = []
    for e, n in ((pt.east, pt.north), mirror):
        label = "LEFT" if side(pa, pb, e, n) > 0 else "RIGHT"
        found.append((label, e, n, math.hypot(e - pa.east, n - pa.north), math.hypot(e - pb.east, n - pb.north)))
    found.sort(key=lambda s: 0 if s[0] == "LEFT" else 1)
    return [Scenario(name, "dd", a, b, r1, r2, tuple(found))]


def bb_scenarios(pts: dict[str, Pt], name: str, a: str, b: str, t: str, behind: bool = False) -> list[Scenario]:
    pa, pb, pt = pts[a], pts[b], pts[t]
    az1, az2 = azimuth(*sub(pt, pa)), azimuth(*sub(pt, pb))
    t1, t2 = math.hypot(*sub(pt, pa)), math.hypot(*sub(pt, pb))
    if behind:
        az1, t1 = (az1 + math.pi) % TWO_PI, -t1
    return [Scenario(name, "bb", a, b, az1, az2, (("SINGLE", pt.east, pt.north, t1, t2),))]


def db_roots(pa: Pt, pb: Pt, r: float, az: float) -> list[tuple[float, float, float]]:
    """Puntos de la recta que sale de B con el azimut `az` a distancia r de A: (t, este, norte), por el pie de la perpendicular."""
    s = direction(az)
    w = (pa.east - pb.east, pa.north - pb.north)
    foot = dot(w, s)
    h = abs(cross(w, s))
    if h > r + 1e-6:
        return []
    half = math.sqrt(max(r * r - h * h, 0.0))
    ts = [foot] if abs(r - h) <= 1e-6 else [foot - half, foot + half]
    return [(t, pb.east + t * s[0], pb.north + t * s[1]) for t in ts]


def db_scenario(pts: dict[str, Pt], name: str, a: str, b: str, r: float, az: float) -> Scenario:
    pa, pb = pts[a], pts[b]
    roots = db_roots(pa, pb, r, az)
    if not roots:
        return Scenario(name, "db", a, b, r, az, (), "LINE_MISSES")
    labels = ["SINGLE"] if len(roots) == 1 else ["FIRST", "SECOND"]
    return Scenario(name, "db", a, b, r, az, tuple((lab, e, n, r, t) for lab, (t, e, n) in zip(labels, roots)))


def scenarios(pts: dict[str, Pt]) -> list[Scenario]:
    out: list[Scenario] = []
    out += dd_scenarios(pts, "dd_general", "P1", "P2", "P3")
    out += dd_scenarios(pts, "dd_otro", "P5", "P6", "P4")
    d12 = math.hypot(*sub(pts["P2"], pts["P1"]))
    p1, p2 = pts["P1"], pts["P2"]
    foot = ("SINGLE", p1.east + 0.4 * (p2.east - p1.east), p1.north + 0.4 * (p2.north - p1.north), 0.4 * d12, 0.6 * d12)
    out.append(Scenario("dd_tangente", "dd", "P1", "P2", 0.4 * d12, 0.6 * d12, (foot,)))
    out.append(Scenario("dd_lejos", "dd", "P1", "P2", 0.3 * d12, 0.3 * d12, (), "TOO_FAR"))
    out.append(Scenario("dd_dentro", "dd", "P1", "P2", 3 * d12, d12, (), "TOO_CLOSE"))
    out.append(Scenario("dd_concentricos", "dd", "P1", "P1", 50.0, 70.0, (), "CONCENTRIC"))
    out += bb_scenarios(pts, "bb_general", "P1", "P2", "P3")
    out += bb_scenarios(pts, "bb_otro", "P5", "P6", "P4")
    out += bb_scenarios(pts, "bb_detras", "P1", "P2", "P3", behind=True)
    out.append(Scenario("bb_paralelas", "bb", "P1", "P2", 0.7, 0.7, (), "PARALLEL"))
    out.append(Scenario("bb_coincidentes", "bb", "P1", "P8", 0.7, 0.7, (), "PARALLEL"))
    pt3 = pts["P3"]
    out.append(db_scenario(pts, "db_general", "P1", "P2", math.hypot(*sub(pt3, p1)), azimuth(*sub(pt3, p2))))
    out.append(db_scenario(pts, "db_otro", "P5", "P6", math.hypot(*sub(pts["P4"], pts["P5"])), azimuth(*sub(pts["P4"], pts["P6"]))))
    az = azimuth(*sub(pt3, p2))
    h = abs(cross(sub(p1, p2), direction(az)))
    out.append(db_scenario(pts, "db_tangente", "P1", "P2", h, az))
    out.append(db_scenario(pts, "db_sin_solucion", "P1", "P2", 0.5 * h, az))
    for i in range(1, 7):
        a, b, t = f"R{i}", f"R{i + 6}", f"R{(i % 6) + 1}"
        out += dd_scenarios(pts, f"dd_aleatoria_{i}", a, b, t)
        out += bb_scenarios(pts, f"bb_aleatoria_{i}", a, b, t)
        out.append(db_scenario(pts, f"db_aleatoria_{i}", a, b, math.hypot(*sub(pts[t], pts[a])), azimuth(*sub(pts[t], pts[b]))))
    return out


def intersecciones_rows() -> list[str]:
    rows = ["caso,tipo,crs,modo,punto_1,punto_2,valor_1,valor_2,n_soluciones,motivo,solucion,etiqueta,este,norte,t1,t2"]
    for crs in CRSS:
        pts = points_of(crs)
        for sc in scenarios(pts):
            f = combined_factor(crs, pts[sc.p1])
            for mode in MODES:
                divisor = 1.0 if mode == "grid" else f
                if sc.kind == "dd":
                    v1, v2 = sc.v1 / divisor, sc.v2 / divisor
                elif sc.kind == "bb":
                    v1, v2 = sc.v1, sc.v2
                else:
                    v1, v2 = sc.v1 / divisor, sc.v2
                head = [sc.name, sc.kind, crs.name, mode, sc.p1, sc.p2, fmt(v1), fmt(v2), str(len(sc.solutions))]
                if not sc.solutions:
                    rows.append(",".join(head + [sc.reason, "", "", "", "", "", ""]))
                for index, (label, e, n, t1, t2) in enumerate(sc.solutions):
                    rows.append(",".join(head + ["", str(index), label, fmt(e), fmt(n), fmt(t1 / divisor), fmt(t2 / divisor)]))
    return rows


def build() -> dict[str, list[str]]:
    return {
        NAME_PUNTOS: puntos_rows(),
        NAME_INVERSAS: inversas_rows(),
        NAME_INTERSECCIONES: intersecciones_rows(),
    }


def all_files() -> list[str]:
    return list(build().keys())


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in build().items():
        (out / name).write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
