"""Reproducción sintética del RS3 con FIX, FLOAT, SINGLE y DGPS alternados y lo que debe dar un promediado con cada combinación de tipos admitidos (S047).

Genera en fixtures/nmea/sintetico/ (GGA a 5 Hz; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca de la antena con el jalón
de 1,800 m y el RS3 como en S002):
- tipos-mezclados.nmea: 400 épocas de un punto quieto. Cada tipo de solución cae en su propio sitio, a distancia del punto
  verdadero y con su ruido, para que una época de más o de menos de otro tipo se note en la media: FIX (3 mm), FLOAT (3 cm),
  SINGLE (30 cm) y DGPS (15 cm). Tramos: FIX 0 a 49, FLOAT 50 a 99, SINGLE 100 a 149, DGPS 150 a 169, FLOAT 170 a 219,
  FIX 220 a 269, FLOAT 270 a 299 y FIX 300 a 399.
- tipos-mezclados.esperado.csv: por escenario (tipos admitidos, época de la que se empieza y segundos que se piden), con qué
  tipo acaba la medida, cuántas épocas usa, cuántas descarta por tipo, cuántas veces avisa «Solución degradada», en qué época
  termina y la media del suelo en ETRS89 / UTM 30 con `math.fsum`, desde la GGA tal como la lee la app.

Las reglas son las de la spec: solo valen las épocas de un tipo admitido (una DGPS cuenta como SINGLE, RF-18); la medida sigue con
el mejor tipo visto, y una época de un tipo mejor descarta lo reunido (RF-9) y una de un tipo peor se descarta (RF-10). Cada época
descartada por tipo se cuenta, también las reunidas que se tiran. El aviso sale una vez al descartar una época por tipo con algo ya
reunido, y vuelve a poder salir cuando se usa una época del mejor tipo. Cada época usada vale 0,2 s.

Uso: uv run --project tools tools/nmea/generar_tipos_mezclados.py
"""

from __future__ import annotations

import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(ROOT / "tools" / "promediado"))
import generar_avanzado as a  # noqa: E402
import generar_promediado as p  # noqa: E402
import generar_sintetico as g  # noqa: E402

EPOCH_S = 0.2
EPOCHS = 400
# (primera época, última época, estado, desplazamiento ENU del tramo en metros, desviación típica en metros)
SEGMENTS = (
    (0, 49, "FIX", (0.0, 0.0, 0.0), 0.003),
    (50, 99, "FLOAT", (0.15, -0.10, 0.20), 0.03),
    (100, 149, "SINGLE", (1.20, 0.80, -1.50), 0.30),
    (150, 169, "DGPS", (0.60, -0.40, 0.50), 0.15),
    (170, 219, "FLOAT", (0.15, -0.10, 0.20), 0.03),
    (220, 269, "FIX", (0.0, 0.0, 0.0), 0.003),
    (270, 299, "FLOAT", (0.15, -0.10, 0.20), 0.03),
    (300, 399, "FIX", (0.0, 0.0, 0.0), 0.003),
)
TYPE_OF = {"FIX": "FIX", "FLOAT": "FLOAT", "SINGLE": "SINGLE", "DGPS": "SINGLE"}
RANK = {"FIX": 0, "FLOAT": 1, "SINGLE": 2}  # menor es mejor
# (nombre, tipos admitidos, época de la que se empieza, segundos que se piden)
SCENARIOS = (
    ("solo-fix", ("FIX",), 0, 20),
    ("fix-y-float", ("FIX", "FLOAT"), 50, 20),
    ("los-tres", ("FIX", "FLOAT", "SINGLE"), 100, 20),
    ("solo-float", ("FLOAT",), 0, 20),
    ("solo-single-con-dgps", ("SINGLE",), 120, 10),
)
COLUMNS = "escenario,admitidos,inicio,pedidos_s,solucion,usadas,descartadas_tipo,avisos,epoca_fin,media_e,media_n,media_h"


def status_of(epoch: int) -> str:
    return next(s[2] for s in SEGMENTS if s[0] <= epoch <= s[1])


def fixes() -> list[a.Fix]:
    rng = random.Random(20261027)
    out = []
    for i in range(EPOCHS):
        _, _, status, offset, sigma = next(s for s in SEGMENTS if s[0] <= i <= s[1])
        east, north, up = (o + rng.gauss(0.0, sigma) for o in offset)
        out.append(a.Fix(a.at_after(i * EPOCH_S), (east, north, up), status))
    return out


def run(parsed: list[a.Parsed], admitted: tuple[str, ...], start: int, requested_s: float) -> dict[str, object]:
    best: str | None = None
    used: list[a.Parsed] = []
    discarded = warnings = 0
    warned = False
    needed = round(requested_s / EPOCH_S)
    for index in range(start, len(parsed)):
        epoch = parsed[index]
        kind = TYPE_OF.get(epoch.status)
        if kind is None or kind not in admitted:
            discarded += 1
            if used and not warned:
                warnings += 1
                warned = True
        elif best is None or RANK[kind] < RANK[best]:
            discarded += len(used)
            used = [epoch]
            best = kind
            warned = False
        elif kind == best:
            used.append(epoch)
            warned = False
        else:
            discarded += 1
            if not warned:
                warnings += 1
                warned = True
        if len(used) >= needed:
            # Con una DGPS y una SINGLE en la medida, la solución es la peor de las usadas (SINGLE).
            statuses = {e.status for e in used}
            solution = "SINGLE" if "SINGLE" in statuses else "DGPS" if "DGPS" in statuses else best
            return {
                "solution": solution, "used": len(used), "discarded": discarded, "warnings": warnings, "end": index,
                "east": p.mean([e.ground_e for e in used]), "north": p.mean([e.ground_n for e in used]),
                "height": p.mean([e.ground_h for e in used]),
            }  # fmt: skip
    raise ValueError(f"el escenario que empieza en {start} no reúne {requested_s} s")


def expected(parsed: list[a.Parsed]) -> str:
    rows = []
    for name, admitted, start, requested_s in SCENARIOS:
        r = run(parsed, admitted, start, requested_s)
        rows.append(",".join([
            name, "+".join(admitted), str(start), str(requested_s), str(r["solution"]), str(r["used"]), str(r["discarded"]),
            str(r["warnings"]), str(r["end"]), p.fmt(r["east"]), p.fmt(r["north"]), p.fmt(r["height"]),
        ]))  # fmt: skip
    header = [
        "# Lo esperado de tipos-mezclados.nmea (S047). Generado por tools/nmea/generar_tipos_mezclados.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        "# Tramos: " + "; ".join(f"{s[2]} {s[0]} a {s[1]}" for s in SEGMENTS) + f". {EPOCHS} épocas a 5 Hz.",
        "# usadas: épocas en la media; descartadas_tipo: épocas tiradas por no ser de un tipo admitido, ser peores que el mejor visto "
        "o ser reunidas antes de que llegara uno mejor; avisos: «Solución degradada»; epoca_fin: la época que cierra la medida.",
        "# media_*: suelo en ETRS89 / UTM 30 (E, N) y altura elipsoidal, math.fsum.",
        COLUMNS,
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    nmea, parsed = a.write(fixes())
    return {"tipos-mezclados.nmea": nmea, "tipos-mezclados.esperado.csv": expected(parsed)}


def main() -> int:
    g.OUT.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} {'bytes' if isinstance(content, bytes) else 'caracteres'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
