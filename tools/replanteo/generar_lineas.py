"""Referencias del replanteo de líneas calculadas aparte: PK, distancia lateral, objetivo y serie de intervalos (S041).

Genera cuatro ficheros en fixtures/replanteo/lineas/:

- lineas.csv: las líneas de los casos (abiertas y cerradas, con y sin cota) con sus vértices en ETRS89 / UTM 30. Los vértices salen de
  coordenadas geográficas (EPSG:4258) pasadas a EPSG:25830 con pyproj y redondeadas a 5 decimales, igual que los demás ficheros.
- consultas.csv: para cada línea y cada punta (a derecha, a izquierda, sobre la línea, junto a un vértice, antes del inicio y después
  del final): el punto más cercano, el PK, la distancia a él, la distancia lateral con signo (positiva a la derecha del sentido de la
  línea; 0 antes del inicio y después del final, donde no hay lado), si la punta cae `EN_LINEA`, `ANTES_INICIO` o `DESPUES_FINAL`, lo que falta hasta el final y la cota interpolada en ese PK.
- objetivos.csv: de las puntas de consultas.csv que caen en la línea, con desplazamientos laterales de −2 a 3 m, el objetivo (el punto más cercano desplazado) y el error lateral.
- series.csv: tramos A–B, también invertidos, con intervalos, con B fuera de múltiplo, y con desplazamiento: cada punto con su PK,
  sus coordenadas y su cota.

Reglas que la spec no fija y que quedan escritas aquí (se confirman al hacer cada tarea):
- El punto más cercano es el del segmento a menor distancia; con dos a la misma distancia (hasta 1e-9 m), el primero.
- La distancia lateral tiene la magnitud de la distancia al punto más cercano y el lado del segmento elegido, también cuando ese punto
  es un vértice (en una esquina exterior no es la perpendicular al segmento, sino la distancia al vértice).
- `ANTES_INICIO` solo en una línea abierta, con el primer segmento elegido y la proyección antes de su inicio; `DESPUES_FINAL`, con
  el último segmento y la proyección más allá de su final. En una cerrada no hay extremos.
- El objetivo es el punto más cercano desplazado `d` hacia la derecha del segmento elegido (sin recortar ni unir esquinas).
- El error lateral es la distancia lateral menos `d`.
- Un PK que cae justo en un vértice usa el segmento que empieza ahí, salvo el final de la línea, que usa el último.
- La serie va de A a B en el sentido de A hacia B: A, A + i, A + 2i... y B siempre, aunque no sea múltiplo; con i ≥ |B − A| solo A y B.
- El PK de una línea cerrada llega hasta su perímetro; el cierre es un segmento más.

Uso: uv run --project tools tools/replanteo/generar_lineas.py
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replanteo as base  # noqa: E402
from pyproj import Transformer  # noqa: E402

g = base.g
OUT = base.OUT / "lineas"
NAME_LINEAS = "lineas.csv"
NAME_CONSULTAS = "consultas.csv"
NAME_OBJETIVOS = "objetivos.csv"
NAME_SERIES = "series.csv"
TIE_M = 1e-9
LAT0, LON0 = 40.4168, -3.7038
PROJECTION = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)


@dataclass(frozen=True)
class Line:
    name: str
    closed: bool
    xs: tuple[float, ...]
    ys: tuple[float, ...]
    zs: tuple[float, ...] | None

    def segments(self) -> list[tuple[int, int]]:
        n = len(self.xs)
        pairs = [(i, i + 1) for i in range(n - 1)]
        if self.closed:
            pairs.append((n - 1, 0))
        return pairs


def project(dx: float, dy: float) -> tuple[float, float]:
    """Un punto a (dx, dy) metros de la esquina de los casos, pasado a UTM 30 con pyproj."""
    lat = LAT0 + dy / 111_000.0
    lon = LON0 + dx / (111_000.0 * math.cos(math.radians(LAT0)))
    x, y = PROJECTION.transform(lon, lat)
    return round(x, 5), round(y, 5)


def make(name: str, closed: bool, points: list[tuple[float, float]], zs: list[float] | None = None) -> Line:
    xy = [project(dx, dy) for dx, dy in points]
    return Line(name, closed, tuple(p[0] for p in xy), tuple(p[1] for p in xy), None if zs is None else tuple(zs))


LINES = (
    make("recta", False, [(0.0, 0.0), (120.0, 35.0)]),
    make("recta-cota", False, [(0.0, 0.0), (100.0, 0.0)], [100.0, 104.0]),
    make("polilinea", False, [(0.0, 0.0), (60.0, 0.0), (60.0, 40.0), (20.0, 40.0), (20.0, 80.0)]),
    make("polilinea-cota", False, [(0.0, 0.0), (50.0, 10.0), (90.0, 0.0), (130.0, 30.0)], [10.0, 12.5, 12.5, 9.0]),
    make("cerrada", True, [(0.0, 0.0), (50.0, 0.0), (50.0, 30.0), (0.0, 30.0)]),
    make("cerrada-cota", True, [(0.0, 0.0), (40.0, 0.0), (20.0, 35.0)], [5.0, 7.0, 9.0]),
    make("zigzag", False, [(float(i * 7), float(12 * (i % 2))) for i in range(30)]),
)

HEADER_LINEAS = "linea,cerrada,n,este,norte,cota,pk,longitud"
HEADER_CONSULTAS = "linea,punta_e,punta_n,cercano_e,cercano_n,pk,distancia,lateral,posicion,falta,cota_pk"
HEADER_OBJETIVOS = "linea,punta_e,punta_n,desplazamiento,objetivo_e,objetivo_n,error_lateral,pk,cota_pk"
HEADER_SERIES = "linea,pk_a,pk_b,intervalo,desplazamiento,i,pk,este,norte,cota"


def cumulative(line: Line) -> list[float]:
    """El PK de cada vértice y, al final, la longitud o el perímetro."""
    out = [0.0]
    for a, b in line.segments():
        out.append(out[-1] + math.hypot(line.xs[b] - line.xs[a], line.ys[b] - line.ys[a]))
    return out


def locate(line: Line, tx: float, ty: float) -> dict:
    """Punto más cercano, PK, distancia lateral con signo y posición, por recorrido de todos los segmentos."""
    cum = cumulative(line)
    segs = line.segments()
    best = None
    for k, (a, b) in enumerate(segs):
        dx, dy = line.xs[b] - line.xs[a], line.ys[b] - line.ys[a]
        length = math.hypot(dx, dy)
        raw = ((tx - line.xs[a]) * dx + (ty - line.ys[a]) * dy) / (length * length)
        t = min(1.0, max(0.0, raw))
        px, py = line.xs[a] + t * dx, line.ys[a] + t * dy
        dist = math.hypot(tx - px, ty - py)
        if best is None or dist < best["dist"] - TIE_M:
            best = {"k": k, "raw": raw, "t": t, "px": px, "py": py, "dist": dist, "dx": dx, "dy": dy, "length": length}
    k = best["k"]
    # cross > 0: la punta está a la izquierda del sentido del segmento; el lado derecho es el positivo
    cross = best["dx"] * (ty - line.ys[segs[k][0]]) - best["dy"] * (tx - line.xs[segs[k][0]])
    signed = -cross / best["length"]
    position = "EN_LINEA"
    if not line.closed and k == 0 and best["raw"] < 0.0:
        position = "ANTES_INICIO"
    if not line.closed and k == len(segs) - 1 and best["raw"] > 1.0:
        position = "DESPUES_FINAL"
    pk = cum[k] + best["t"] * best["length"]
    if position != "EN_LINEA":
        signed = 0.0
    return {**best, "pk": pk, "lateral": 0.0 if best["dist"] == 0.0 or position != "EN_LINEA" else (best["dist"] if signed >= 0.0 else -best["dist"]),
            "signed": signed, "position": position,
            "total": cum[-1]}


def height_at(line: Line, pk: float) -> float | None:
    if line.zs is None:
        return None
    cum = cumulative(line)
    k = segment_at(cum, pk)
    a, b = line.segments()[k]
    span = cum[k + 1] - cum[k]
    return line.zs[a] + (line.zs[b] - line.zs[a]) * ((pk - cum[k]) / span)


def segment_at(cum: list[float], pk: float) -> int:
    """El segmento de un PK: el que empieza en un vértice, salvo el final de la línea, que usa el último."""
    last = len(cum) - 2
    for k in range(last + 1):
        if pk < cum[k + 1]:
            return k
    return last


def point_at(line: Line, pk: float, offset: float) -> tuple[float, float]:
    cum = cumulative(line)
    k = segment_at(cum, pk)
    a, b = line.segments()[k]
    dx, dy = line.xs[b] - line.xs[a], line.ys[b] - line.ys[a]
    length = math.hypot(dx, dy)
    t = (pk - cum[k]) / length
    # normal a la derecha del sentido
    return line.xs[a] + t * dx + offset * dy / length, line.ys[a] + t * dy - offset * dx / length


def tips(line: Line) -> list[tuple[float, float]]:
    """Las puntas de cada línea: sobre ella, a ambos lados, junto a vértices, antes del inicio y después del final y alrededor."""
    rng = random.Random(41 + len(line.xs))
    cum = cumulative(line)
    out: list[tuple[float, float]] = []
    total = cum[-1]
    for fraction in (0.01, 0.13, 0.37, 0.5, 0.71, 0.99):
        pk = total * fraction
        for offset in (0.0, 1.2, -0.8, 7.5, -15.0):
            out.append(point_at(line, pk, offset))
    for i in range(len(line.xs)):  # junto a cada vértice, fuera y dentro de las esquinas
        for dx, dy in ((0.6, 0.4), (-0.5, 0.7), (0.3, -1.1)):
            out.append((line.xs[i] + dx, line.ys[i] + dy))
    k0, k1 = line.segments()[0], line.segments()[-1]
    for k, (back, sign) in ((k0, (k0[0], -1.0)), (k1, (k1[1], 1.0))):  # antes del inicio y después del final
        a, b = k
        dx, dy = line.xs[b] - line.xs[a], line.ys[b] - line.ys[a]
        length = math.hypot(dx, dy)
        for along, side in ((3.0, 0.0), (9.0, 2.0), (2.0, -4.0)):
            out.append((line.xs[back] + sign * along * dx / length + side * dy / length, line.ys[back] + sign * along * dy / length - side * dx / length))
    for _ in range(12):
        out.append((line.xs[0] + rng.uniform(-30, 160), line.ys[0] + rng.uniform(-40, 90)))
    return [(round(x, 5), round(y, 5)) for x, y in out]  # como las coordenadas de la app: a 5 decimales


OFFSETS = (0.0, 1.5, -2.0, 3.0)
SERIES = (
    # (pk_a, pk_b, intervalo, desplazamiento): None = inicio, final; negativos = desde el final; todo en metros
    (0.0, None, 10.0, 0.0),
    (5.0, 47.5, 10.0, 0.0),
    (47.5, 5.0, 10.0, 0.0),
    (0.0, None, 1000.0, 0.0),
    (2.0, 31.0, 7.0, 2.5),
    (2.0, 31.0, 7.0, -2.5),
)


def n9(value: float) -> str:
    """Nueve decimales, sin el cero negativo."""
    return f"{0.0 if abs(value) < 5e-10 else value:.9f}"


def fmt(value: float | None) -> str:
    return "" if value is None else n9(value)


def generate_lineas() -> str:
    rows = []
    for line in LINES:
        cum = cumulative(line)
        for i in range(len(line.xs)):
            z = None if line.zs is None else line.zs[i]
            rows.append(f"{line.name},{int(line.closed)},{i},{line.xs[i]:.5f},{line.ys[i]:.5f},{fmt(z)},{n9(cum[i])},{n9(cum[-1])}")
    return header("lineas") + "\n".join([HEADER_LINEAS, *rows]) + "\n"


def generate_consultas() -> str:
    rows = []
    for line in LINES:
        for tx, ty in tips(line):
            r = locate(line, tx, ty)
            rows.append(
                f"{line.name},{tx:.5f},{ty:.5f},{n9(r['px'])},{n9(r['py'])},{n9(r['pk'])},{n9(r['dist'])},{n9(r['lateral'])},{r['position']},"
                f"{n9(r['total'] - r['pk'])},{fmt(height_at(line, r['pk']))}"
            )
    return header("consultas") + "\n".join([HEADER_CONSULTAS, *rows]) + "\n"


def generate_objetivos() -> str:
    rows = []
    for line in LINES:
        for tx, ty in tips(line)[::3]:
            r = locate(line, tx, ty)
            if r["position"] != "EN_LINEA":
                continue
            right = (r["dy"] / r["length"], -r["dx"] / r["length"])
            for d in OFFSETS:
                rows.append(
                    f"{line.name},{tx:.5f},{ty:.5f},{d:.3f},{n9(r['px'] + d * right[0])},{n9(r['py'] + d * right[1])},"
                    f"{n9(r['lateral'] - d)},{n9(r['pk'])},{fmt(height_at(line, r['pk']))}"
                )
    return header("objetivos") + "\n".join([HEADER_OBJETIVOS, *rows]) + "\n"


def series_pks(a: float, b: float, step: float) -> list[float]:
    span = abs(b - a)
    if step >= span:
        return [a, b]
    direction = 1.0 if b > a else -1.0
    pks = [a]
    n = 1
    while n * step < span - 1e-9:
        pks.append(a + direction * n * step)
        n += 1
    pks.append(b)
    return pks


def generate_series() -> str:
    rows = []
    for line in LINES:
        total = cumulative(line)[-1]
        for a, b, step, offset in SERIES:
            pk_b = total if b is None else min(b, total)
            if a >= total or a == pk_b:
                continue
            for i, pk in enumerate(series_pks(a, pk_b, step)):
                x, y = point_at(line, pk, offset)
                rows.append(f"{line.name},{n9(a)},{n9(pk_b)},{n9(step)},{n9(offset)},{i},{n9(pk)},{n9(x)},{n9(y)},{fmt(height_at(line, pk))}")
    return header("series") + "\n".join([HEADER_SERIES, *rows]) + "\n"


def header(name: str) -> str:
    return (
        f"# Replanteo de líneas ({name}) calculado aparte (S041). Generado por tools/replanteo/generar_lineas.py; no editar a mano.\n"
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → 25830. PK y distancias de cuadrícula, en metros.\n"
        "# lateral: distancia con signo, positiva a la derecha del sentido de la línea. falta: hasta el final de la línea.\n"
    )


FILES = {
    NAME_LINEAS: generate_lineas,
    NAME_CONSULTAS: generate_consultas,
    NAME_OBJETIVOS: generate_objetivos,
    NAME_SERIES: generate_series,
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
