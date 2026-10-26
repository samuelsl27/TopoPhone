"""Reproducción sintética del RS3 cruzando una superficie con un hueco y lo que debe salir de ella época a época (S042).

Genera en fixtures/nmea/sintetico/ (GGA a 5 Hz; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca de la antena con el
jalón de 1,800 m y el RS3 como en S002):
- rs3-superficie.nmea: 360 épocas. El usuario camina hacia el este a 2,25 m/s por una línea a 50 m al norte del origen que cruza el
  "terreno" de fixtures/superficies/dos-superficies.xml: empieza 20 m antes del borde oeste (fuera de la superficie), la recorre,
  atraviesa el hueco de la celda (2, 2) y sale por el borde este. La punta va 20 cm por encima de la cota de diseño (la superficie
  más el desplazamiento vertical) hasta la época 59 (desmonte), en cota de la 60 a la 249 y 15 cm por debajo desde la 250
  (terraplén). En las épocas 100 a 119 pierde el FIX (4 s).
- rs3-superficie.nivel.csv: por época, si la guía está encendida y, si lo está, la cota de la superficie en la punta (vacía fuera del
  contorno y en el hueco), si la punta está dentro de la superficie (VALOR) o fuera de ella (FUERA), la diferencia de cota respecto a
  la superficie con desplazamiento (punta menos diseño), el estado del nivel y su aviso.

La cota esperada sale de otro cálculo que el de la app: la regla de Cramer de tools/superficies/generar_superficies.py sobre los
vértices del propio fichero, con las coordenadas de la GGA tal como las lee la app (pyproj). La tolerancia vertical es la de por defecto
(3 cm) y el margen de salida, 5 mm (S019/RF-9). Las alturas son elipsoidales: el proyecto de la prueba lo es también.

Uso: uv run --project tools tools/nmea/generar_superficie_rs3.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "superficies"))
import generar_avanzado as a  # noqa: E402
import generar_sintetico as g  # noqa: E402
import generar_superficies as gs  # noqa: E402

NAME = "rs3-superficie.nmea"
CSV = "rs3-superficie.nivel.csv"
TOLERANCE_V_M = 0.03
MARGIN_M = 0.005
OFFSET_M = 0.30  # el desplazamiento vertical del proyecto
EPOCH_S = 0.2
EPOCHS = 360
STEP_M = 0.45
START_EAST_M = -20.0
NORTH_M = 50.0
LOSS = range(100, 120)  # 4 s sin FIX
CUT_M = 0.20
FILL_M = -0.15
ON_GRADE = range(60, 250)
FILL_FROM = 250
CLEARANCE_M = 0.0015
TYPICAL_HEIGHT_M = 604.0  # la cota de la punta antes de la primera superficie, para tener un valor


def target_dz(i: int) -> float:
    """La diferencia de cota deseada de la época i respecto a la superficie con desplazamiento (positiva: la punta por encima)."""
    if i in ON_GRADE:
        return 0.0
    return FILL_M if i >= FILL_FROM else CUT_M


def horizontal(i: int) -> tuple[float, float]:
    return START_EAST_M + STEP_M * i, NORTH_M


def fixes_with_heights(up: list[float]) -> list[a.Fix]:
    return [
        a.Fix(a.at_after(i * EPOCH_S), (*horizontal(i), up[i]), "FLOAT" if i in LOSS else "FIX")
        for i in range(EPOCHS)
    ]


def fixes() -> list[a.Fix]:
    """Dos pasadas: la primera con la punta a la altura del origen, para saber dónde cae cada época y qué cota tiene la superficie ahí."""
    _, first = a.write(fixes_with_heights([0.0] * EPOCHS))
    up: list[float] = []
    previous = TYPICAL_HEIGHT_M
    for i, epoch in enumerate(first):
        surface = gs.expected_height((epoch.ground_e, epoch.ground_n))
        # Sin cota de la superficie (fuera o en el hueco) la punta conserva la altura que traía.
        wanted = previous if surface is None else surface + OFFSET_M + target_dz(i)
        previous = wanted
        up.append(wanted - epoch.ground_h)
    return fixes_with_heights(up)


def millis(utc: str) -> int:
    return round((int(utc[0:2]) * 3600 + int(utc[2:4]) * 60 + float(utc[4:])) * 1000)


def next_level(previous: str | None, dz: float) -> tuple[str, str]:
    """El estado del nivel y el aviso: CUT (desmonte), FILL (terraplén) o ON_GRADE; con 5 mm de margen para salir de la cota."""
    if abs(dz) <= TOLERANCE_V_M:
        state = "ON_GRADE"
    else:
        state = "CUT" if dz > 0 else "FILL"
    if previous == "ON_GRADE":
        if abs(dz) <= TOLERANCE_V_M + MARGIN_M:
            return "ON_GRADE", ""
        return state, "LEVEL_LEFT"
    if state == "ON_GRADE":
        return state, "LEVEL_ARRIVED"
    return state, ""


def check_clearance(dz: float, epoch: int) -> None:
    for edge in (TOLERANCE_V_M, TOLERANCE_V_M + MARGIN_M):
        if abs(abs(dz) - edge) < CLEARANCE_M:
            raise ValueError(f"época {epoch}: la diferencia de cota {dz:.4f} m queda a menos de {CLEARANCE_M} m de un borde")


def expected(parsed: list[a.Parsed]) -> str:
    level: str | None = None
    was_on = False
    rows = []
    for i, e in enumerate(parsed):
        ground = [a.p.fmt(e.ground_e), a.p.fmt(e.ground_n), a.p.fmt(e.ground_h)]
        if e.status != "FIX":
            alert = "PERDIDO" if was_on else ""
            was_on, level = False, None
            rows.append(",".join([e.utc, e.status, *ground, "NO_FIX", "", "", "", "", "", alert]))
            continue
        was_on = True
        surface = gs.expected_height((e.ground_e, e.ground_n))
        if surface is None:
            # Fuera del contorno o en el hueco no hay cota ni diferencia, y el nivel vuelve a empezar de cero.
            level = None
            rows.append(",".join([e.utc, e.status, *ground, "SI", "", "FUERA", "", "", "", ""]))
            continue
        dz = e.ground_h - (surface + OFFSET_M)
        check_clearance(dz, i)
        level, level_alert = next_level(level, dz)
        rows.append(",".join([e.utc, e.status, *ground, "SI", a.p.fmt(surface), "VALOR", a.p.fmt(dz), level, level_alert, ""]))
    header = [
        "# Lo esperado de rs3-superficie.nmea (S042). Generado por tools/nmea/generar_superficie_rs3.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# superficie: terreno de fixtures/superficies/dos-superficies.xml; desplazamiento_vertical_m={OFFSET_M}; "
        f"tolerancia_vertical_m={TOLERANCE_V_M}; margen_m={MARGIN_M}",
        f"# {EPOCHS} épocas a 5 Hz hacia el este desde {abs(START_EAST_M):g} m al oeste del origen, a {NORTH_M:g} m al norte, cruzando el "
        f"hueco; {CUT_M:g} m de desmonte hasta la 59, en cota de la 60 a la 249 y {abs(FILL_M):g} m de terraplén desde la 250; "
        "sin FIX de la 100 a la 119.",
        "# cota_sup: la cota de la superficie en la punta (vacía fuera y en el hueco); estado_sup: VALOR o FUERA; d_cota: punta menos "
        "(superficie más desplazamiento), positiva en desmonte. nivel: CUT, FILL u ON_GRADE; aviso_nivel: LEVEL_ARRIVED o LEVEL_LEFT; "
        "aviso: PERDIDO al apagarse la guía.",
        "hora,estado,este,norte,h,guia,cota_sup,estado_sup,d_cota,nivel,aviso_nivel,aviso",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    nmea, parsed = a.write(fixes())
    return {NAME: nmea, CSV: expected(parsed)}


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
