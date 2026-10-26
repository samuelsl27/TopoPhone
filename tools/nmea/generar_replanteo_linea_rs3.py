"""Reproducción sintética del RS3 caminando paralelo a una línea y lo que debe salir de ella época a época (S041).

Genera en fixtures/nmea/sintetico/ (GGA a 5 Hz; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca de la antena con el
jalón de 1,800 m y el RS3 como en S002):
- rs3-linea.nmea: 360 épocas. El usuario camina a 1,5 m/s por una polilínea de 90 m (40 m al este, 30 m al norte y 20 m al este)
  a 1,5 m a la derecha de ella con 4 s sin FIX (FLOAT) a mitad (épocas 60 a 79); en la época 150 cambia a 2,0 m a la izquierda
  y, en la 299, sigue recto pasado el final de la línea.
- rs3-linea.guia.csv: por época, si la guía está encendida y, si lo está, el desplazamiento lateral que se pide (1,5 m a la derecha
  hasta la época 149 y 2,0 m a la izquierda desde la 150), el PK del punto más cercano, la distancia lateral con signo (positiva a la
  derecha del sentido de la línea), el error lateral (la distancia lateral menos el desplazamiento), si la punta cae EN_LINEA,
  ANTES_INICIO o DESPUES_FINAL, la distancia al objetivo (el punto más cercano desplazado), la zona y el aviso.

Las coordenadas de la línea son las del suelo de la primera época más los desplazamientos de los vértices; lo esperado se saca de
la GGA tal como la lee la app (pyproj) y de la geometría de tools/replanteo/generar_lineas.py, sin las funciones de la app. La
tolerancia es la de por defecto (2,5 cm), la zona de ayuda son 40 cm y el margen de salida, 5 mm (S018/RF-19, RF-20).

Uso: uv run --project tools tools/nmea/generar_replanteo_linea_rs3.py
"""

from __future__ import annotations

import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "replanteo"))
import generar_avanzado as a  # noqa: E402
import generar_lineas as gl  # noqa: E402
import generar_replanteo_rs3 as r  # noqa: E402
import generar_sintetico as g  # noqa: E402

VERTICES = ((0.0, 0.0), (40.0, 0.0), (40.0, 30.0), (60.0, 30.0))
STEP_M = 0.3  # 1,5 m/s a 5 Hz
EPOCH_S = 0.2
FIRST_OFFSET_M = 1.5
SECOND_OFFSET_M = -2.0
SWITCH = 150
PAST_END = 299  # la primera época pasado el final: 0,3 · (299 + 2) = 90,3 m
EPOCHS = 360
LOSS = range(60, 80)  # 4 s sin FIX


def offset_at(index: int) -> float:
    return FIRST_OFFSET_M if index < SWITCH else SECOND_OFFSET_M


def enu_line() -> gl.Line:
    return gl.Line("enu", False, tuple(v[0] for v in VERTICES), tuple(v[1] for v in VERTICES), None)


def grid_to_enu() -> tuple[tuple[float, float], tuple[float, float]]:
    """La matriz que lleva un desplazamiento de cuadrícula al plano local ENU de las épocas (convergencia y escala), medida con pyproj."""
    _, [origin, east, north] = a.write(
        [a.Fix(a.START, (0.0, 0.0, 0.0)), a.Fix(a.START, (100.0, 0.0, 0.0)), a.Fix(a.START, (0.0, 100.0, 0.0))]
    )
    m = ((east.ground_e - origin.ground_e) / 100.0, (north.ground_e - origin.ground_e) / 100.0), (
        (east.ground_n - origin.ground_n) / 100.0,
        (north.ground_n - origin.ground_n) / 100.0,
    )
    det = m[0][0] * m[1][1] - m[0][1] * m[1][0]
    return (m[1][1] / det, -m[0][1] / det), (-m[1][0] / det, m[0][0] / det)


def fixes() -> list[a.Fix]:
    rng = random.Random(20261027)
    line = enu_line()  # el recorrido se calcula en cuadrícula y se pasa al plano local de las épocas
    inverse = grid_to_enu()
    total = gl.cumulative(line)[-1]
    last = (line.xs[-1] - line.xs[-2], line.ys[-1] - line.ys[-2])
    last_length = math.hypot(*last)
    out = []
    for i in range(EPOCHS):
        s = STEP_M * (i + 2)  # empieza a 0,6 m del inicio para no caer justo en su vértice
        lateral = offset_at(i)
        if s <= total:
            x, y = gl.point_at(line, s, lateral)
        else:  # pasado el final, recto por la dirección del último tramo
            ex, ey = gl.point_at(line, total, lateral)
            x = ex + (s - total) * last[0] / last_length
            y = ey + (s - total) * last[1] / last_length
        x, y = inverse[0][0] * x + inverse[0][1] * y, inverse[1][0] * x + inverse[1][1] * y
        lost = i in LOSS
        noise = 0.15 if lost else 0.01
        out.append(
            a.Fix(
                a.at_after(i * EPOCH_S),
                (x + rng.gauss(0.0, noise), y + rng.gauss(0.0, noise), rng.gauss(0.0, 2 * noise)),
                "FLOAT" if lost else "FIX",
            )
        )
    return out


def grid_line(start: tuple[float, float]) -> gl.Line:
    return gl.Line("grid", False, tuple(start[0] + v[0] for v in VERTICES), tuple(start[1] + v[1] for v in VERTICES), None)


def expected(parsed: list[a.Parsed], start: tuple[float, float]) -> str:
    line = grid_line(start)
    total = gl.cumulative(line)[-1]
    zone, was_on = "FAR", False
    rows = []
    for index, e in enumerate(parsed):
        offset = offset_at(index)
        ground = [a.p.fmt(e.ground_e), a.p.fmt(e.ground_n), a.p.fmt(e.ground_h)]
        if e.status != "FIX":
            alert = "PERDIDO" if was_on else ""
            was_on, zone = False, "FAR"
            rows.append(",".join([e.utc, e.status, *ground, "NO_FIX", a.p.fmt(offset), "", "", "", "", "", "", alert]))
            continue
        loc = gl.locate(line, e.ground_e, e.ground_n)
        if loc["position"] == "EN_LINEA":
            right = (loc["dy"] / loc["length"], -loc["dx"] / loc["length"])
            objective = (loc["px"] + offset * right[0], loc["py"] + offset * right[1])
            lateral = a.p.fmt(loc["lateral"])
            error = a.p.fmt(loc["lateral"] - offset)
        else:
            objective = gl.point_at(line, 0.0 if loc["position"] == "ANTES_INICIO" else total, offset)
            lateral = error = ""
        distance = math.hypot(objective[0] - e.ground_e, objective[1] - e.ground_n)
        zone, alert = r.next_zone(zone, distance)
        was_on = True
        rows.append(
            ",".join([e.utc, e.status, *ground, "SI", a.p.fmt(offset), a.p.fmt(loc["pk"]), lateral, error, loc["position"], a.p.fmt(distance), zone, alert])
        )
    header = [
        "# Lo esperado de rs3-linea.nmea (S041). Generado por tools/nmea/generar_replanteo_linea_rs3.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# inicio: este={start[0]:.3f} norte={start[1]:.3f}; tolerancia_m={r.TOLERANCE_M}; ayuda_m={r.AID_M}; margen_m={r.MARGIN_M}",
        "# linea: " + " ".join(f"{start[0] + x:.3f},{start[1] + y:.3f}" for x, y in VERTICES),
        f"# {EPOCHS} épocas a 5 Hz: a {STEP_M / EPOCH_S:g} m/s por la línea, a {FIRST_OFFSET_M:g} m a la derecha hasta la {SWITCH - 1} "
        f"y a {-SECOND_OFFSET_M:g} m a la izquierda desde la {SWITCH}; sin FIX de la época {LOSS.start} a la {LOSS.stop - 1}; "
        f"pasado el final de la línea desde la {PAST_END}.",
        "# desplazamiento_m: el que se pide (positivo a la derecha). lateral_m: distancia lateral con signo. error_lateral_m: lateral menos desplazamiento.",
        "hora,estado,este,norte,h,guia,desplazamiento_m,pk_m,lateral_m,error_lateral_m,posicion,distancia_m,zona,aviso",
    ]
    return "\n".join(header + rows) + "\n"


def start_ground() -> tuple[float, float]:
    east, north, _ = r.design()
    return east, north


def generate() -> dict[str, bytes | str]:
    nmea, parsed = a.write(fixes())
    return {"rs3-linea.nmea": nmea, "rs3-linea.guia.csv": expected(parsed, start_ground())}


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
