"""Puntos de talud de ejemplo y lo que debe salir de cada caso (S051).

Genera en fixtures/ejes/taludes/:

- perfiles.csv: los perfiles de terreno poligonales (nombre, desplazamiento con signo respecto al eje y cota absoluta).
- tin.csv: los triángulos de un TIN pequeño (una fila por triángulo con sus tres vértices Este, Norte y cota).
- casos.csv: por caso, el borde de explanación (desplazamiento y cota), las dos relaciones H:V, el terreno que se usa (un perfil o `TIN`)
  y lo esperado: `estado` (`found`, `edge` si el terreno está a la cota del borde, `sin_interseccion` si el terreno llega a 100 m sin
  cortar al talud y `sin_terreno` si el terreno se acaba antes de cortarlo), el tipo (`CUT` cabeza o `FILL` pie), la distancia `t` del
  borde al punto de talud en planta, su desplazamiento, su cota, la altura de talud y su Este y Norte.

Cómo se calcula (otro algoritmo que el del móvil, para que la coincidencia no sea una tautología):
- Todo con `fractions`, sin redondeo hasta escribir.
- Perfil poligonal: entre cada dos puntos el terreno es lineal, así que «terreno menos talud» es lineal por tramo y la raíz sale de una
  división exacta; se toma la primera con t > 0 y t <= 100.
- TIN: se recorren los triángulos; para cada uno se recorta la recta de la sección con sus aristas, se obtiene el tramo [t0, t1] y las
  cotas de sus extremos por interpolación sobre las aristas, y se resuelve la raíz en ese tramo. Los triángulos se ordenan por t.
- El tipo: el terreno por encima del borde (en t = 0) es desmonte, por debajo es terraplén y a la misma cota es el propio borde.
- La planta es la del eje C de S049 (recta al Este desde el PK 0 en 441000; la derecha es el Sur): Este = 441000 + PK y Norte = 4474000
  menos el desplazamiento.

Uso: uv run --project tools tools/ejes/generar_taludes.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_ejes as g  # noqa: E402

OUT = g.ROOT / "fixtures" / "ejes" / "taludes"
EAST0 = Fraction(441000)
NORTH0 = Fraction(4474000)
MAX_T = Fraction(100)


def F(x) -> Fraction:
    return x if isinstance(x, Fraction) else Fraction(str(x))


def fl(x: Fraction) -> str:
    return g.A(float(x))


PROFILES = {
    "desmonte": [(3.5, 100.5), (8, 102), (15, 104), (30, 110), (110, 140)],
    "terraplen": [(3.5, 99.2), (8, 98), (20, 95), (110, 70)],
    "borde": [(3.5, 100.0), (10, 101.0), (110, 105.0)],
    "paralelo": [(3.5, 100.5), (110, 171.5)],
    "izquierda": [(-3.5, 100.5), (-8, 102), (-15, 104), (-30, 110), (-110, 140)],
    "corto": [(3.5, 100.5), (13.5, 120.0)],
}
PK = Fraction(105)


def tin_height(x: Fraction, y: Fraction) -> Fraction:
    """Cota de un vértice del TIN: una rampa hacia el Norte con un abombamiento, para que los triángulos no sean iguales."""
    return F("100.5") + (NORTH0 - y) / 3 + (y - Fraction(4473990)) ** 2 / 400 + (x - 441100) ** 2 / 200


def tin_triangles() -> list[tuple[tuple[Fraction, Fraction, Fraction], ...]]:
    xs = [Fraction(v) for v in range(441080, 441121, 10)]
    ys = [Fraction(v) for v in range(4473960, 4474011, 5)]
    tris = []
    for i in range(len(xs) - 1):
        for j in range(len(ys) - 1):
            a, b, c, d = (xs[i], ys[j]), (xs[i + 1], ys[j]), (xs[i + 1], ys[j + 1]), (xs[i], ys[j + 1])
            v = {p: (p[0], p[1], tin_height(*p)) for p in (a, b, c, d)}
            tris.append((v[a], v[b], v[c]))
            tris.append((v[a], v[c], v[d]))
    return tris


@dataclass(frozen=True)
class Case:
    name: str
    terrain: str  # nombre de un perfil o "TIN"
    side: int  # 1 derecha, -1 izquierda
    edge_offset: Fraction
    edge_height: Fraction
    cut: Fraction
    fill: Fraction


def terrain_profile(name: str, side: int, edge_offset: Fraction) -> list[tuple[Fraction, Fraction]]:
    """El perfil como (t, cota) con t la distancia hacia fuera desde el borde."""
    return [(side * (F(d) - edge_offset), F(z)) for d, z in PROFILES[name]]


def first_root(segments: list[tuple[Fraction, Fraction, Fraction, Fraction]], slope) -> Fraction | None:
    """La primera raíz con 0 < t <= 100 de «terreno menos talud» en tramos (ta, ga, tb, gb) donde ambos son lineales."""
    for ta, ga, tb, gb in segments:
        fa, fb = ga - slope(ta), gb - slope(tb)
        if fa == 0 and ta > 0:
            return ta
        if fa * fb < 0 or (fb == 0 and tb > 0):
            t = ta + (tb - ta) * fa / (fa - fb) if fa != fb else tb
            if 0 < t <= MAX_T:
                return t
    return None


def solve(case: Case) -> dict:
    if case.terrain == "TIN":
        segments = tin_segments(case)
        reach = segments[-1][2] if segments else Fraction(0)
    else:
        pts = terrain_profile(case.terrain, case.side, case.edge_offset)
        segments = [(a[0], a[1], b[0], b[1]) for a, b in zip(pts, pts[1:])]
        reach = pts[-1][0]
    ground0 = segments[0][1]
    if ground0 == case.edge_height:
        return {"estado": "edge", "tipo": "", "t": Fraction(0)}
    kind = "CUT" if ground0 > case.edge_height else "FILL"
    if kind == "CUT":
        def slope(t): return case.edge_height + t / case.cut
    else:
        def slope(t): return case.edge_height - t / case.fill
    t = first_root(segments, slope)
    if t is None:
        return {"estado": "sin_interseccion" if reach >= MAX_T else "sin_terreno", "tipo": kind, "t": None}
    return {"estado": "found", "tipo": kind, "t": t, "height": slope(t)}


def tin_segments(case: Case) -> list[tuple[Fraction, Fraction, Fraction, Fraction]]:
    """Los tramos (t0, g0, t1, g1) en que la recta de la sección corta cada triángulo, por t creciente."""
    east = EAST0 + PK
    out = []
    for tri in tin_triangles():
        hits = []
        for p, q in ((tri[0], tri[1]), (tri[1], tri[2]), (tri[2], tri[0])):
            if p[0] == q[0]:
                continue
            if min(p[0], q[0]) <= east <= max(p[0], q[0]):
                s = (east - p[0]) / (q[0] - p[0])
                y = p[1] + s * (q[1] - p[1])
                z = p[2] + s * (q[2] - p[2])
                hits.append((y, z))
        hits = sorted(set(hits))
        if len(hits) < 2:
            continue
        (ya, za), (yb, zb) = hits[0], hits[-1]
        # t crece hacia el Sur en la derecha y hacia el Norte en la izquierda
        start = NORTH0 - case.side * case.edge_offset
        ta, tb = case.side * (start - ya), case.side * (start - yb)
        if ta > tb:
            ta, tb, za, zb = tb, ta, zb, za
        if tb <= 0 or ta == tb:
            continue
        out.append((ta, za, tb, zb))
    out.sort(key=lambda s: s[0])
    out = [s for s in out if s[2] > 0]
    # El primero que cubre t = 0 empieza antes: se recorta a t = 0 por interpolación.
    clipped = []
    for ta, za, tb, zb in out:
        if ta < 0:
            za = za + (zb - za) * (0 - ta) / (tb - ta)
            ta = Fraction(0)
        clipped.append((ta, za, tb, zb))
    return clipped


def ground_at(case: Case) -> Fraction:
    """La cota del terreno en t = 0: sirve para poner un borde justo a esa cota."""
    return tin_segments(case)[0][1]


EDGE_Z = F("100.0")
CASES = [
    Case("desmonte-derecha", "desmonte", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("terraplen-derecha", "terraplen", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("borde-derecha", "borde", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("sin-interseccion", "paralelo", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("desmonte-izquierda", "izquierda", -1, F("-3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("sin-terreno-perfil", "corto", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("desmonte-tin", "TIN", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")),
    Case("terraplen-tin", "TIN", 1, F("3.5"), F("105"), F("1.5"), F("2")),
    Case("sin-terreno-tin", "TIN", 1, F("3.5"), EDGE_Z, F("100"), F("2")),
]


def rows() -> list[str]:
    cases = list(CASES)
    tin_edge = ground_at(Case("x", "TIN", 1, F("3.5"), EDGE_Z, F("1.5"), F("2")))
    cases.append(Case("borde-tin", "TIN", 1, F("3.5"), tin_edge, F("1.5"), F("2")))
    lines = [
        "caso,terreno,lado,pk,borde_desp,borde_cota,razon_desmonte,razon_terraplen,estado,tipo,t,desp_talud,cota_talud,altura,este,norte"
    ]
    for c in cases:
        r = solve(c)
        side = "derecha" if c.side > 0 else "izquierda"
        head = f"{c.name},{c.terrain},{side},{fl(PK)},{fl(c.edge_offset)},{fl(c.edge_height)},{fl(c.cut)},{fl(c.fill)},{r['estado']},{r['tipo']}"
        if r["estado"] == "found":
            t = r["t"]
            offset = c.edge_offset + c.side * t
            height = r["height"]
            east, north = EAST0 + PK, NORTH0 - offset
            tail = f"{fl(t)},{fl(offset)},{fl(height)},{fl(abs(height - c.edge_height))},{fl(east)},{fl(north)}"
        elif r["estado"] == "edge":
            east, north = EAST0 + PK, NORTH0 - c.edge_offset
            tail = f"0.0,{fl(c.edge_offset)},{fl(c.edge_height)},0.0,{fl(east)},{fl(north)}"
        else:
            tail = ",,,,,"
        lines.append(head + "," + tail)
    return lines


def all_files() -> dict[str, str]:
    profiles = ["perfil,desplazamiento,cota"] + [f"{n},{g.A(float(d))},{g.A(float(z))}" for n, pts in PROFILES.items() for d, z in pts]
    tin = ["x1,y1,z1,x2,y2,z2,x3,y3,z3"] + [",".join(fl(v) for p in tri for v in p) for tri in tin_triangles()]
    return {
        "perfiles.csv": "\n".join(profiles) + "\n",
        "tin.csv": "\n".join(tin) + "\n",
        "casos.csv": "\n".join(rows()) + "\n",
    }


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(all_files())} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
