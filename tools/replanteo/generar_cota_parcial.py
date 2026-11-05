"""Referencias de la cota de diseño de una línea con alturas en solo algunos vértices (S027/RF-8, RF-9).

Genera dos ficheros en fixtures/replanteo/cota-parcial/:

- casos.csv: las líneas de los casos (caso, cerrada, orden, este, norte, cota). La cota vacía es un vértice sin altura. Los vértices están
  en triángulos 3-4-5 y en un rectángulo de 30 x 40 m, para que todos los PK de los vértices sean enteros exactos.
- esperado.csv: para cada caso y PK, la cota de diseño esperada (vacía si no hay), calculada con `fractions` (aritmética exacta).

Reglas (las de la spec, escritas aquí con otro algoritmo que el del móvil: una lista de nudos (PK, cota) y una búsqueda de los dos nudos
que rodean el PK):
- Los nudos son los vértices con altura. En una línea abierta, un PK antes del primer nudo o después del último no tiene cota.
- En un polígono el último nudo se repite una vuelta antes y el primero una vuelta después (el PK de la vuelta es el perímetro), así que el
  tramo de cierre sí interpola y no hay «antes» ni «después».
- Un PK que cae justo en un nudo da su altura, aunque sea el único.
- Una línea sin ningún nudo no tiene cota en ningún PK.

Uso: uv run --project tools tools/replanteo/generar_cota_parcial.py
"""

from __future__ import annotations

import math
import sys
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "replanteo" / "cota-parcial"
NAME_CASOS = "casos.csv"
NAME_ESPERADO = "esperado.csv"

# caso: (cerrada, [(este, norte, cota o None)], [PK a consultar])
CASES: dict[str, tuple[bool, list[tuple[int, int, Fraction | None]], list[Fraction]]] = {
    "abierta_extremos": (
        False,
        [(0, 0, None), (30, 40, Fraction(10)), (60, 80, None), (90, 120, Fraction(20)), (120, 160, None)],
        [Fraction(x) for x in (0, 25, 50, 75, 100, 125, 150, 175, 200)],
    ),
    "abierta_completa": (
        False,
        [(0, 0, Fraction(5)), (30, 40, Fraction(7)), (60, 80, Fraction(6)), (90, 120, Fraction(10)), (120, 160, Fraction(10))],
        [Fraction(x) for x in (0, 25, 50, 75, 120, 150, 200)],
    ),
    "poligono_parcial": (
        True,
        [(0, 0, Fraction(10)), (30, 0, None), (30, 40, Fraction(20)), (0, 40, None)],
        [Fraction(x) for x in (0, 15, 30, 50, 70, 100, 120, 140)],
    ),
    "un_solo_vertice": (
        False,
        [(0, 0, None), (30, 40, Fraction(8)), (60, 80, None)],
        [Fraction(x) for x in (0, 25, 50, 75, 100)],
    ),
    "sin_alturas": (
        False,
        [(0, 0, None), (30, 40, None), (60, 80, None)],
        [Fraction(x) for x in (0, 25, 50, 100)],
    ),
}


def chainages(closed: bool, vertices: list[tuple[int, int, Fraction | None]]) -> list[Fraction]:
    """El PK de cada vértice y, en un polígono, el perímetro al final; todos exactos (las distancias son enteras)."""
    result = [Fraction(0)]
    count = len(vertices) if closed else len(vertices) - 1
    for i in range(count):
        x1, y1, _ = vertices[i]
        x2, y2, _ = vertices[(i + 1) % len(vertices)]
        squared = (x2 - x1) ** 2 + (y2 - y1) ** 2
        root = math.isqrt(squared)
        assert root * root == squared, "las distancias de los casos deben ser enteras"
        result.append(result[-1] + root)
    return result


def design_height(closed: bool, vertices: list[tuple[int, int, Fraction | None]], pk: Fraction) -> Fraction | None:
    pks = chainages(closed, vertices)
    knots = [(pks[i], h) for i, (_, _, h) in enumerate(vertices) if h is not None]
    if not knots:
        return None
    if closed:
        perimeter = pks[-1]
        knots = [(p - perimeter, h) for p, h in knots] + knots + [(p + perimeter, h) for p, h in knots]
    for p, h in knots:
        if p == pk:
            return h
    before = [(p, h) for p, h in knots if p < pk]
    after = [(p, h) for p, h in knots if p > pk]
    if not before or not after:
        return None
    (p1, h1), (p2, h2) = before[-1], after[0]
    return h1 + (h2 - h1) * (pk - p1) / (p2 - p1)


def number(value: Fraction | None) -> str:
    if value is None:
        return ""
    exact = Decimal(value.numerator) / Decimal(value.denominator)
    return format(exact.quantize(Decimal("0.000000001"), rounding=ROUND_HALF_UP), "f")


def rows() -> dict[str, list[str]]:
    casos = ["caso,cerrada,orden,este,norte,cota"]
    esperado = ["caso,pk,cota"]
    for name, (closed, vertices, pks) in CASES.items():
        for i, (x, y, h) in enumerate(vertices):
            casos.append(f"{name},{1 if closed else 0},{i},{x},{y},{number(h)}")
        for pk in pks:
            esperado.append(f"{name},{number(pk)},{number(design_height(closed, vertices, pk))}")
    return {NAME_CASOS: casos, NAME_ESPERADO: esperado}


def all_files() -> list[str]:
    return [NAME_CASOS, NAME_ESPERADO]


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, lines in rows().items():
        (out / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
