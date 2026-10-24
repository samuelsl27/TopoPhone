"""Reproducción sintética del RS3 para el replanteo y lo que debe salir de ella época a época (S018).

Genera en fixtures/nmea/sintetico/ (GGA a 5 Hz; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca de la antena con el
jalón de 1,800 m y el RS3 como en S002):
- rs3-replanteo.nmea: 247 épocas. El usuario va andando a 1,2 m/s hacia el punto de diseño desde 40 m (rumbo 45°) con 10 s sin FIX
  (FLOAT) a mitad (épocas 60 a 109); salta a una parada de 12 s dentro de la tolerancia y se aparta a unos 32 cm del diseño.
- rs3-replanteo.guia.csv: por época, si la guía está encendida y, si lo está, la distancia horizontal, las diferencias de Este y
  Norte (diseño menos punta) y de cota (punta menos diseño), la zona, el aviso, el rumbo de marcha y su antigüedad.

El diseño es el suelo de una época sin desplazamiento, redondeado al milímetro, y va en la cabecera del CSV. Lo esperado se saca
de la GGA tal como la lee la app y de las reglas de la spec, sin las funciones de la app: la tolerancia es la de por defecto
(2,5 cm), la zona de ayuda son 40 cm y el margen de salida, 5 mm (RF-19, RF-20); el rumbo de marcha, la velocidad de 0,3 m/s
sobre 2 s y 10 s de conservación (RF-9). La guía solo está encendida con FIX (RF-22) y, al apagarse, el aviso es PERDIDO.

Uso: uv run --project tools tools/nmea/generar_replanteo_rs3.py
"""

from __future__ import annotations

import math
import random
import sys
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_avanzado as a  # noqa: E402
import generar_sintetico as g  # noqa: E402

TOLERANCE_M = 0.025
AID_M = 0.40
MARGIN_M = 0.005
MIN_SPEED = 0.3
HOLD_S = 10.0
WINDOW_MS = 2000

START_DISTANCE_M = 40.0
SPEED = 1.2
EPOCH_S = 0.2
WALK_EPOCHS = 167  # 40 m / 0,24 m por época
WALK_AZIMUTH = 45.0
LOSS = range(60, 110)  # 10 s sin FIX
STOP_EPOCHS = 60
STOP_OFFSET = (0.010, -0.008)
LEAVE_EPOCHS = 20
LEAVE_OFFSET = (0.25, -0.20)
EPOCHS = WALK_EPOCHS + STOP_EPOCHS + LEAVE_EPOCHS


def fixes() -> list[a.Fix]:
    rng = random.Random(20261026)
    azimuth = math.radians(WALK_AZIMUTH)
    out = []
    for i in range(EPOCHS):
        at = a.at_after(i * EPOCH_S)
        if i < WALK_EPOCHS:
            remaining = START_DISTANCE_M - SPEED * EPOCH_S * i
            lost = i in LOSS
            noise = 0.15 if lost else 0.01
            offset = (
                -remaining * math.sin(azimuth) + rng.gauss(0.0, noise),
                -remaining * math.cos(azimuth) + rng.gauss(0.0, noise),
                rng.gauss(0.0, 2 * noise),
            )
            out.append(a.Fix(at, offset, "FLOAT" if lost else "FIX"))
        else:
            east, north = STOP_OFFSET if i < WALK_EPOCHS + STOP_EPOCHS else LEAVE_OFFSET
            out.append(a.Fix(at, (east + rng.gauss(0.0, 0.003), north + rng.gauss(0.0, 0.003), rng.gauss(0.0, 0.006))))
    return out


class Heading:
    """El rumbo de marcha como lo define la spec (RF-9), con el tiempo de las épocas en ms."""

    def __init__(self) -> None:
        self.samples: deque[tuple[int, float, float]] = deque()
        self.last: float | None = None
        self.last_valid = 0

    def on_tip(self, t: int, east: float, north: float) -> None:
        self.samples.append((t, east, north))
        while len(self.samples) > 2 and t - self.samples[1][0] >= WINDOW_MS:
            self.samples.popleft()
        t0, e0, n0 = self.samples[0]
        span = t - t0
        if span < WINDOW_MS / 2:
            return
        d_east, d_north = east - e0, north - n0
        if math.hypot(d_east, d_north) / (span / 1000.0) >= MIN_SPEED:
            self.last = math.atan2(d_east, d_north) % (2.0 * math.pi)
            self.last_valid = t

    def walking(self, t: int) -> tuple[float, float] | None:
        if self.last is None:
            return None
        age = (t - self.last_valid) / 1000.0
        return (self.last, age) if age <= HOLD_S else None


def next_zone(zone: str, distance: float) -> tuple[str, str]:
    has_aid = TOLERANCE_M < AID_M
    if zone == "FAR":
        if distance <= TOLERANCE_M:
            return "INSIDE", "ARRIVED"
        if has_aid and distance <= AID_M:
            return "AID", "APPROACH"
        return "FAR", ""
    if zone == "AID":
        if distance <= TOLERANCE_M:
            return "INSIDE", "ARRIVED"
        if distance > AID_M + MARGIN_M:
            return "FAR", ""
        return "AID", ""
    if distance > TOLERANCE_M + MARGIN_M:
        return ("AID" if has_aid and distance <= AID_M else "FAR"), "LEFT"
    return "INSIDE", ""


def millis(utc: str) -> int:
    return round((int(utc[0:2]) * 3600 + int(utc[2:4]) * 60 + float(utc[4:])) * 1000)


def design() -> tuple[float, float, float]:
    _, [first] = a.write([a.Fix(a.START, (0.0, 0.0, 0.0))])
    return round(first.ground_e, 3), round(first.ground_n, 3), round(first.ground_h, 3)


def expected(parsed: list[a.Parsed], target: tuple[float, float, float]) -> str:
    heading = Heading()
    zone, was_on = "FAR", False
    rows = []
    for e in parsed:
        t = millis(e.utc)
        ground = [a.p.fmt(e.ground_e), a.p.fmt(e.ground_n), a.p.fmt(e.ground_h)]
        if e.status != "FIX":
            alert = "PERDIDO" if was_on else ""
            was_on, zone = False, "FAR"
            rows.append(",".join([e.utc, e.status, *ground, "NO_FIX", "", "", "", "", "", alert, "", ""]))
            continue
        d_east, d_north = target[0] - e.ground_e, target[1] - e.ground_n
        distance = math.hypot(d_east, d_north)
        heading.on_tip(t, -d_east, -d_north)
        found = heading.walking(t)
        zone, alert = next_zone(zone, distance)
        was_on = True
        numbers = [a.p.fmt(distance), a.p.fmt(d_east), a.p.fmt(d_north), a.p.fmt(e.ground_h - target[2])]
        walking = [a.p.fmt(math.degrees(found[0])), a.p.fmt(found[1])] if found else ["", ""]
        rows.append(",".join([e.utc, e.status, *ground, "SI", *numbers, zone, alert, *walking]))
    header = [
        "# Lo esperado de rs3-replanteo.nmea (S018). Generado por tools/nmea/generar_replanteo_rs3.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# diseno: este={target[0]:.3f} norte={target[1]:.3f} h={target[2]:.3f}; "
        f"tolerancia_m={TOLERANCE_M}; ayuda_m={AID_M}; margen_m={MARGIN_M}",
        f"# {EPOCHS} épocas a 5 Hz: {WALK_EPOCHS} andando a {SPEED} m/s hacia el diseño desde {START_DISTANCE_M:g} m "
        f"(rumbo {WALK_AZIMUTH:g}°), sin FIX de la época {LOSS.start} a la {LOSS.stop - 1}; "
        f"{STOP_EPOCHS} de parada dentro de la tolerancia y {LEAVE_EPOCHS} a unos 32 cm.",
        "# d_este y d_norte: diseño menos punta; d_cota: punta menos diseño. rumbo_marcha: grados desde el norte de cuadrícula; vacío si no hay.",
        "hora,estado,este,norte,h,guia,distancia_m,d_este_m,d_norte_m,d_cota_m,zona,aviso,rumbo_marcha_grados,rumbo_edad_s",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    nmea, parsed = a.write(fixes())
    return {"rs3-replanteo.nmea": nmea, "rs3-replanteo.guia.csv": expected(parsed, design())}


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
