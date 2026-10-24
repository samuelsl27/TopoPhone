"""Reproducción sintética del RS3 para el nivel vertical del replanteo y lo que debe salir de ella época a época (S019).

Genera en fixtures/nmea/sintetico/ (GGA a 5 Hz; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca de la antena con el
jalón de 1,800 m y el RS3 como en S002):
- rs3-cota.nmea: 360 épocas. La punta se queda a 1 m del punto de diseño en horizontal (lejos de la diana, a 60 cm al Este y 80 cm
  al Sur) y sube y baja respecto a su cota: baja 3,5 m desde el desmonte hasta la cota (épocas 0 a 99), se queda en cota 10 s
  (100 a 149), baja a 1,2 m de terraplén (150 a 199), pierde el FIX 6 s (200 a 229), vuelve a la cota (230 a 299), oscila dentro
  del margen de la tolerancia (300 a 339) y sale a 30 cm de desmonte (340 a 359).
- rs3-cota.nivel.csv: por época, si la guía está encendida y, si lo está, la distancia horizontal, las diferencias de Este y Norte
  (diseño menos punta) y de cota (punta menos diseño), el estado del nivel, el aviso del nivel y el radio visible del nivel.

El diseño es el suelo de una época sin desplazamiento, redondeado al milímetro, y va en la cabecera del CSV. Lo esperado se saca de
la GGA tal como la lee la app y de las reglas de la spec, sin las funciones de la app: la tolerancia vertical es la de por defecto
(3 cm), el margen de salida, 5 mm (RF-9), y el radio del nivel va en pasos de 1, 2 y 5 por cada potencia de diez, con un 25 % de
margen para la burbuja y un 20 % para la banda de la tolerancia (RF-7). La guía solo está encendida con FIX (RF-11) y, al apagarse,
el nivel vuelve a empezar de cero y la guía avisa PERDIDO. Las alturas son elipsoidales: el proyecto de la prueba lo es también.

Uso: uv run --project tools tools/nmea/generar_cota_rs3.py
"""

from __future__ import annotations

import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_avanzado as a  # noqa: E402
import generar_sintetico as g  # noqa: E402

TOLERANCE_V_M = 0.03
MARGIN_M = 0.005
TIP_MARGIN = 1.25
BAND_MARGIN = 1.2
SHRINK_FILL = 0.8
MIN_RADIUS_M = 0.05

HORIZONTAL = (0.60, -0.80)  # a 1,0 m del diseño
EPOCH_S = 0.2
EPOCHS = 360
LOSS = range(200, 230)  # 6 s sin FIX
DESCENT = range(0, 100)  # del desmonte a la cota
HOLD = range(100, 150)
TO_FILL = range(150, 200)
BACK = range(230, 300)
EDGE = range(300, 340)
LEAVE = range(340, 360)
EDGE_VALUES = (0.022, 0.032, 0.026, 0.033, 0.024, 0.032)  # dentro de la banda y del margen, a más de 2 mm de los bordes
TOP_M = 3.5
FILL_M = -1.2
LEAVE_M = 0.30
# Las diferencias de cota que se quedan a menos de esto de un borde (tolerancia o tolerancia + margen) se descartan: la GGA va
# redondeada al milímetro y la clasificación no debe depender de ese redondeo.
CLEARANCE_M = 0.0015


def target_up(i: int) -> float:
    """La diferencia de cota deseada de la época i, en metros (positiva: la punta por encima)."""
    if i in DESCENT:
        return TOP_M * (1.0 - (i + 0.4) / 100.0)
    if i in HOLD:
        return 0.0
    if i in TO_FILL:
        return FILL_M * (i - 149) / 50.0
    if i in LOSS:
        return FILL_M
    if i in BACK:
        return FILL_M * (1.0 - (i - 229 + 0.6) / 70.0)
    if i in EDGE:
        return EDGE_VALUES[(i - 300) % len(EDGE_VALUES)]
    return LEAVE_M


def fixes() -> list[a.Fix]:
    rng = random.Random(20261027)
    out = []
    for i in range(EPOCHS):
        lost = i in LOSS
        noise = 0.15 if lost else 0.0
        # Sin ruido vertical salvo en la pérdida de FIX: así la clasificación no depende del azar.
        up = target_up(i) + (rng.gauss(0.0, noise) if lost else 0.0)
        east = HORIZONTAL[0] + rng.gauss(0.0, noise if lost else 0.003)
        north = HORIZONTAL[1] + rng.gauss(0.0, noise if lost else 0.003)
        out.append(a.Fix(a.at_after(i * EPOCH_S), (east, north, up), "FLOAT" if lost else "FIX"))
    return out


def steps() -> list[float]:
    return [0.05, 0.1, 0.2, 0.5] + [m * 10.0**e for e in range(0, 7) for m in (1.0, 2.0, 5.0)]


def radius(previous: float | None, dz_abs: float) -> float:
    """El radio visible del nivel: el menor paso que deja dentro la burbuja y la banda; sube enseguida y baja con holgura."""
    floor = max(BAND_MARGIN * TOLERANCE_V_M, MIN_RADIUS_M)
    need = max(TIP_MARGIN * dz_abs, floor)
    available = steps()
    fitting = next((s for s in available if s >= need), available[-1])
    if previous is None or fitting >= previous:
        return fitting
    result = previous
    for step in sorted((s for s in available if s < previous), reverse=True):
        if step >= floor and TIP_MARGIN * dz_abs <= SHRINK_FILL * step:
            result = step
        else:
            break
    return result


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


def millis(utc: str) -> int:
    return round((int(utc[0:2]) * 3600 + int(utc[2:4]) * 60 + float(utc[4:])) * 1000)


def design() -> tuple[float, float, float]:
    _, [first] = a.write([a.Fix(a.START, (0.0, 0.0, 0.0))])
    return round(first.ground_e, 3), round(first.ground_n, 3), round(first.ground_h, 3)


def check_clearance(dz: float, epoch: int) -> None:
    for edge in (TOLERANCE_V_M, TOLERANCE_V_M + MARGIN_M):
        if abs(abs(dz) - edge) < CLEARANCE_M:
            raise ValueError(f"época {epoch}: la diferencia de cota {dz:.4f} m queda a menos de {CLEARANCE_M} m de un borde")


def expected(parsed: list[a.Parsed], target: tuple[float, float, float]) -> str:
    level: str | None = None
    previous_radius: float | None = None
    was_on = False
    rows = []
    for i, e in enumerate(parsed):
        ground = [a.p.fmt(e.ground_e), a.p.fmt(e.ground_n), a.p.fmt(e.ground_h)]
        if e.status != "FIX":
            alert = "PERDIDO" if was_on else ""
            was_on, level, previous_radius = False, None, None
            rows.append(",".join([e.utc, e.status, *ground, "NO_FIX", "", "", "", "", "", "", "", alert]))
            continue
        d_east, d_north = target[0] - e.ground_e, target[1] - e.ground_n
        distance = math.hypot(d_east, d_north)
        dz = e.ground_h - target[2]
        check_clearance(dz, i)
        level, level_alert = next_level(level, dz)
        previous_radius = radius(previous_radius, abs(dz))
        was_on = True
        numbers = [a.p.fmt(distance), a.p.fmt(d_east), a.p.fmt(d_north), a.p.fmt(dz)]
        rows.append(",".join([e.utc, e.status, *ground, "SI", *numbers, level, level_alert, f"{previous_radius:g}", ""]))
    header = [
        "# Lo esperado de rs3-cota.nmea (S019). Generado por tools/nmea/generar_cota_rs3.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# diseno: este={target[0]:.3f} norte={target[1]:.3f} h={target[2]:.3f}; "
        f"tolerancia_vertical_m={TOLERANCE_V_M}; margen_m={MARGIN_M}",
        f"# {EPOCHS} épocas a 5 Hz a 1 m del diseño en horizontal: de {TOP_M:g} m de desmonte a la cota (0 a 99), en cota 10 s (100 a 149), "
        f"a {abs(FILL_M):g} m de terraplén (150 a 199), sin FIX 6 s (200 a 229), de vuelta a la cota (230 a 299), "
        f"oscilando dentro del margen (300 a 339) y a {LEAVE_M:g} m de desmonte (340 a 359).",
        "# d_este y d_norte: diseño menos punta; d_cota: punta menos diseño (positiva en desmonte). nivel: CUT, FILL u ON_GRADE; "
        "aviso_nivel: LEVEL_ARRIVED o LEVEL_LEFT; radio_m: el radio visible del nivel; aviso: PERDIDO al apagarse la guía.",
        "hora,estado,este,norte,h,guia,distancia_m,d_este_m,d_norte_m,d_cota_m,nivel,aviso_nivel,radio_m,aviso",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    nmea, parsed = a.write(fixes())
    return {"rs3-cota.nmea": nmea, "rs3-cota.nivel.csv": expected(parsed, design())}


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
