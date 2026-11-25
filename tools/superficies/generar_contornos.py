"""Referencias del contorno de una superficie TIN y de la rampa de colores por cota, calculadas aparte (S061).

Genera en fixtures/superficies/contornos/:

- cuadrado.txt: dos triángulos que forman un cuadrado; un anillo exterior de cuatro vértices.
- hueco.txt: una malla de 4 × 4 celdas sin las cuatro celdas del centro; un anillo exterior de 16 vértices y un hueco de 8.
- islas.txt: dos cuadrados separados; dos anillos exteriores.
- vertice.txt: dos cuadrados que se tocan en una esquina; dos anillos exteriores que comparten ese vértice.
- rampa.csv: el color ARGB de la rampa de siete paradas para varias cotas entre una mínima y una máxima.

Formato de los .txt (una línea por dato; `#` comenta):
  V,e,n          un vértice (su índice es el orden de aparición, desde 0)
  T,a,b,c        un triángulo con tres índices de vértice
  R,tipo,e n;…   un anillo esperado: `EXT` (exterior, antihorario) o `HOLE` (hueco, horario), cerrado implícitamente,
                 empezando por su vértice de menor Este y, a igualdad, de menor Norte

El contorno esperado sale de otro cálculo que el de la app (que cuenta las aristas): aquí una arista es de borde si, de los dos
puntos a un lado y a otro de su punto medio, exactamente uno cae dentro de algún triángulo. Los anillos se encadenan con ese
conjunto de aristas y se orientan por su área con signo.

Uso: uv run --project tools tools/superficies/generar_contornos.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "superficies" / "contornos"
E0, N0 = 440_000.0, 4_474_000.0
SIDE_OFFSET = 1e-4  # metros a cada lado del punto medio de una arista

# Rampa de siete paradas (S061/RF-10): azul oscuro, azul, cian, verde, amarillo, naranja y rojo.
RAMP = [0x1A237E, 0x1E88E5, 0x00BCD4, 0x43A047, 0xFDD835, 0xFB8C00, 0xE53935]

Vertex = tuple[float, float]
Tri = tuple[int, int, int]


def grid(nx: int, ny: int, step: float, e0: float, n0: float, skip: set[tuple[int, int]]) -> tuple[list[Vertex], list[Tri]]:
    """Malla de nx × ny celdas con dos triángulos por celda, sin las celdas de `skip`."""
    vertices = [(e0 + i * step, n0 + j * step) for j in range(ny + 1) for i in range(nx + 1)]
    tris: list[Tri] = []
    for j in range(ny):
        for i in range(nx):
            if (i, j) in skip:
                continue
            a = j * (nx + 1) + i
            b, c = a + 1, a + nx + 1
            tris += [(a, b, c + 1), (a, c + 1, c)]
    return vertices, tris


def merge(*parts: tuple[list[Vertex], list[Tri]]) -> tuple[list[Vertex], list[Tri]]:
    """Une mallas y funde los vértices con las mismas coordenadas exactas."""
    vertices: list[Vertex] = []
    index: dict[Vertex, int] = {}
    tris: list[Tri] = []
    for vs, ts in parts:
        local = []
        for v in vs:
            if v not in index:
                index[v] = len(vertices)
                vertices.append(v)
            local.append(index[v])
        tris += [(local[a], local[b], local[c]) for a, b, c in ts]
    return vertices, tris


def cases() -> dict[str, tuple[list[Vertex], list[Tri]]]:
    return {
        "cuadrado": grid(1, 1, 10.0, E0, N0, set()),
        "hueco": grid(4, 4, 5.0, E0, N0, {(1, 1), (2, 1), (1, 2), (2, 2)}),
        "islas": merge(grid(1, 1, 10.0, E0, N0, set()), grid(1, 1, 10.0, E0 + 30.0, N0 + 5.0, set())),
        "vertice": merge(grid(1, 1, 10.0, E0, N0, set()), grid(1, 1, 10.0, E0 + 10.0, N0 + 10.0, set())),
    }


def inside_any(p: Vertex, vertices: list[Vertex], tris: list[Tri]) -> bool:
    def cross(o: Vertex, a: Vertex, b: Vertex) -> float:
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    for a, b, c in tris:
        va, vb, vc = vertices[a], vertices[b], vertices[c]
        d1, d2, d3 = cross(va, vb, p), cross(vb, vc, p), cross(vc, va, p)
        if (d1 > 0 and d2 > 0 and d3 > 0) or (d1 < 0 and d2 < 0 and d3 < 0):
            return True
    return False


def boundary_edges(vertices: list[Vertex], tris: list[Tri]) -> set[tuple[int, int]]:
    """Aristas de borde dirigidas con el interior a su izquierda: de los dos puntos a un lado y a otro del punto medio,
    exactamente uno cae dentro de algún triángulo."""
    edges = {tuple(sorted(e)) for a, b, c in tris for e in ((a, b), (b, c), (c, a))}
    out = set()
    for a, b in edges:
        (ea, na), (eb, nb) = vertices[a], vertices[b]
        me, mn = (ea + eb) / 2, (na + nb) / 2
        length = math.hypot(eb - ea, nb - na)
        ue, un = -(nb - na) / length, (eb - ea) / length  # normal unitaria hacia la izquierda de a → b
        left = inside_any((me + ue * SIDE_OFFSET, mn + un * SIDE_OFFSET), vertices, tris)
        right = inside_any((me - ue * SIDE_OFFSET, mn - un * SIDE_OFFSET), vertices, tris)
        if left and not right:
            out.add((a, b))
        elif right and not left:
            out.add((b, a))
    return out


def signed_area(ring: list[Vertex]) -> float:
    return sum(p[0] * q[1] - q[0] * p[1] for p, q in zip(ring, ring[1:] + ring[:1])) / 2


def chain(vertices: list[Vertex], edges: set[tuple[int, int]]) -> list[list[Vertex]]:
    """Anillos a partir de las aristas dirigidas. En un vértice con varias salidas (anillos que se tocan) se toma la que gira
    más a la izquierda respecto de la llegada, para que cada anillo rodee solo su interior."""
    remaining = set(edges)
    rings = []
    while remaining:
        a, b = min(remaining)
        remaining.discard((a, b))
        ring = [a, b]
        while ring[-1] != ring[0]:
            here, prev = ring[-1], ring[-2]
            options = [e for e in remaining if e[0] == here]
            hx, hy = vertices[here]
            px, py = vertices[prev]
            heading = math.atan2(hy - py, hx - px)

            def left_turn(edge: tuple[int, int]) -> float:
                ox, oy = vertices[edge[1]]
                d = math.atan2(oy - hy, ox - hx) - heading
                return (d + math.pi) % (2 * math.pi) - math.pi  # en (-π, π]: positivo es a la izquierda

            best = max(options, key=left_turn)
            remaining.discard(best)
            ring.append(best[1])
        rings.append([vertices[i] for i in ring[:-1]])
    return rings


def canonical(ring: list[Vertex]) -> list[Vertex]:
    start = min(range(len(ring)), key=lambda i: ring[i])
    return ring[start:] + ring[:start]


def classify(rings: list[list[Vertex]]) -> list[tuple[str, list[Vertex]]]:
    """Con el interior a la izquierda, un exterior sale antihorario (área positiva) y un hueco horario (área negativa)."""
    out = [("EXT" if signed_area(r) > 0 else "HOLE", canonical(r)) for r in rings]
    return sorted(out, key=lambda kr: (kr[0], kr[1][0]))


def num(v: float) -> str:
    return f"{v:.6f}"


def case_text(name: str, vertices: list[Vertex], tris: list[Tri]) -> str:
    rings = classify(chain(vertices, boundary_edges(vertices, tris)))
    lines = [
        f"# Contorno de la superficie «{name}» calculado aparte (S061). Generado por tools/superficies/generar_contornos.py; no editar a mano.\n"
    ]
    lines += [f"V,{num(e)},{num(n)}\n" for e, n in vertices]
    lines += [f"T,{a},{b},{c}\n" for a, b, c in tris]
    lines += [f"R,{kind},{';'.join(f'{num(e)} {num(n)}' for e, n in ring)}\n" for kind, ring in rings]
    return "".join(lines)


def ramp_argb(z: float, zmin: float, zmax: float) -> int:
    """El color de la cota: interpolación lineal en RGB entre las dos paradas vecinas, redondeando hacia arriba en el medio."""
    t = 0.5 if zmax <= zmin else min(1.0, max(0.0, (z - zmin) / (zmax - zmin)))
    x = t * (len(RAMP) - 1)
    i = min(int(math.floor(x)), len(RAMP) - 2)
    f = x - i
    a, b = RAMP[i], RAMP[i + 1]
    channels = []
    for shift in (16, 8, 0):
        ca, cb = (a >> shift) & 0xFF, (b >> shift) & 0xFF
        channels.append(int(math.floor(ca + (cb - ca) * f + 0.5)))
    return (0xFF << 24) | (channels[0] << 16) | (channels[1] << 8) | channels[2]


def ramp_csv() -> str:
    lines = [
        "# Rampa de colores por cota de siete paradas (S061/RF-10). Generado por tools/superficies/generar_contornos.py; no editar a mano.\n",
        "minima,maxima,cota,argb\n",
    ]
    rows = [(600.0, 720.0, z) for z in (600.0, 610.0, 620.0, 637.5, 660.0, 685.25, 700.0, 719.9, 720.0, 590.0, 800.0)]
    rows += [(600.0, 600.0, 600.0), (600.0, 600.0, 10.0), (0.0, 1.0, 0.25), (0.0, 1.0, 0.5), (-12.5, 12.5, 0.0)]
    for zmin, zmax, z in rows:
        lines.append(f"{num(zmin)},{num(zmax)},{num(z)},{ramp_argb(z, zmin, zmax):08X}\n")
    return "".join(lines)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    files = {f"{name}.txt": case_text(name, *case) for name, case in cases().items()}
    files["rampa.csv"] = ramp_csv()
    for name, content in files.items():
        (OUT / name).write_text(content, encoding="utf-8", newline="\n")
        print(f"{OUT / name}: {len(content.splitlines())} líneas")
    return 0


if __name__ == "__main__":
    sys.exit(main())
