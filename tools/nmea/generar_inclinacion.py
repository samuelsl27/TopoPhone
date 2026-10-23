"""Reproducciones sintéticas del RS3 con la inclinación (ETC) y lo que debe salir de ellas (S016).

Genera en fixtures/nmea/sintetico/, a 5 Hz como el RS3 (GGA y ETC en cada época; GST, RMC y ZDA cada segundo):
- rs3-inclinacion.nmea: 2 s en configuración, 2 s en alineación y 10 s compensando con la punta quieta sobre un
  punto y el jalón balanceándose de 0° a 40°, con 1 s de "movimiento rápido" en medio. El receptor no compensa: la
  GGA es la antena. rs3-inclinacion.esperado.csv da, por época, la punta calculada con pyproj (tubería
  `cart` + `topocentric`) desde la GGA tal como la lee la app, y su Este y Norte en ETRS89 / UTM 30.
- rs3-asistente-app.nmea: la prueba del asistente con el receptor sin compensar (la GGA sigue a la antena).
- rs3-asistente-receptor.nmea: la misma prueba con el receptor que compensa (la GGA queda en la punta, con 5 mm de
  ruido).

Uso: uv run --project tools tools/nmea/generar_inclinacion.py
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

START = datetime(2026, 10, 20, 10, 0, 0, tzinfo=timezone.utc)
EPOCH_MS = 200
# La punta quieta: Madrid, a 699,423 m de altura elipsoidal.
TIP = (40.4168, -3.7038, 699.423)
SEPARATION = 51.234
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
COEFFICIENTS = ("1.013", "0.993", "1.021")  # N, E, U (ETC_rev_3)
STATES = {"SETUP": "10", "ALIGNMENT": "20", "COMPENSATING": "30"}
NOTIFICATIONS = {"NONE": "0", "FAST_MOTION": "10"}


@dataclass(frozen=True)
class Spec:
    """Una época: estado y aviso del ETC, inclinación y dirección en grados, y si el receptor compensa."""

    index: int
    state: str
    notification: str = "NONE"
    tilt: float = 0.0
    direction: float = 0.0
    compensates: bool = False
    noise: tuple[float, float] = (0.0, 0.0)

    @property
    def at(self) -> datetime:
        return START + timedelta(milliseconds=self.index * EPOCH_MS)

    @property
    def utc(self) -> str:
        return self.at.strftime("%H%M%S.") + f"{self.at.microsecond // 10_000:02d}"


def topocentric(lat: float, lon: float, h: float) -> Transformer:
    return g.generar_vectores.topocentric(lat, lon, h)


def pole(spec: Spec) -> tuple[float, float, float]:
    """Vector unitario de la punta a la antena: (sen θ·sen α, sen θ·cos α, cos θ) (ETC_rev_3)."""
    theta, alpha = math.radians(spec.tilt), math.radians(spec.direction)
    return math.sin(theta) * math.sin(alpha), math.sin(theta) * math.cos(alpha), math.cos(theta)


def gga_position(spec: Spec) -> tuple[float, float, float]:
    """Lo que da el receptor: la antena sobre la punta si no compensa, o la punta (con su ruido) si compensa."""
    east, north, up = pole(spec)
    to_tip = topocentric(*TIP)
    if spec.compensates:
        lon, lat, h = to_tip.transform(spec.noise[0], spec.noise[1], 0.0, direction=TransformDirection.INVERSE)
    else:
        lon, lat, h = to_tip.transform(ANTENNA_HEIGHT * east, ANTENNA_HEIGHT * north, ANTENNA_HEIGHT * up, direction=TransformDirection.INVERSE)
    return lat, lon, h


def etc_body(spec: Spec) -> str:
    compensating = spec.state == "COMPENSATING"
    fields = [STATES[spec.state], NOTIFICATIONS[spec.notification]]
    if compensating:
        fields += ["123.40", f"{spec.direction:.3f}", f"{spec.tilt:.3f}", *COEFFICIENTS]
    else:
        fields += [""] * 6
    return ",".join(["GNETC", spec.utc, *fields])


def write(specs: list[Spec]) -> tuple[bytes, list[tuple[Spec, float, float, float]]]:
    """Las frases de cada época y la GGA tal como la lee la app (latitud, longitud y altura elipsoidal)."""
    nmea = bytearray()
    parsed = []
    for spec in specs:
        lat, lon, h = gga_position(spec)
        epoch = g.Epoch(spec.utc, "GN", "FIX", lat, lon, 28, 0.5, round(h - SEPARATION, 3), SEPARATION, 1.0, (0.008, 0.006, 0.015))
        body, parsed_lat, parsed_lon = g.gga_body(epoch)
        nmea += g.sentence(body)
        nmea += g.sentence(etc_body(spec))
        if spec.at.microsecond == 0:
            nmea += g.sentence(g.gst_body(epoch))
            lat_text, lat_hemi = g.nmea_coordinate(lat, 2, "N", "S")
            lon_text, lon_hemi = g.nmea_coordinate(lon, 3, "E", "W")
            day = spec.at.strftime("%d%m%y")
            nmea += g.sentence(",".join(["GNRMC", spec.utc, "A", lat_text, lat_hemi, lon_text, lon_hemi, "0.010", "45.0", day, "", "", "R", "V"]))
            nmea += g.sentence(f"GNZDA,{spec.utc},{spec.at.day:02d},{spec.at.month:02d},{spec.at.year},00,00")
        parsed.append((spec, parsed_lat, parsed_lon, epoch.altitude + SEPARATION))
    return bytes(nmea), parsed


def inclination_specs() -> list[Spec]:
    specs = [Spec(i, "SETUP") for i in range(10)]
    specs += [Spec(i, "ALIGNMENT") for i in range(10, 20)]
    for i in range(20, 70):
        k = i - 20
        notification = "FAST_MOTION" if 30 <= i < 35 else "NONE"
        specs.append(Spec(i, "COMPENSATING", notification, tilt=20.0 + 20.0 * math.sin(k * 0.3), direction=(k * 12.0) % 360.0))
    return specs


def wizard_specs(compensates: bool) -> list[Spec]:
    """2 s con el jalón casi vertical (1°), 1 s pasando por 10° y 3 s a 25°, siempre compensando sin aviso."""
    rng = random.Random(20261020)

    def noise() -> tuple[float, float]:
        return (rng.uniform(-0.005, 0.005), rng.uniform(-0.005, 0.005)) if compensates else (0.0, 0.0)

    specs = [Spec(i, "COMPENSATING", tilt=1.0, direction=90.0, compensates=compensates, noise=noise()) for i in range(10)]
    specs += [Spec(i, "COMPENSATING", tilt=10.0, direction=90.0, compensates=compensates, noise=noise()) for i in range(10, 15)]
    specs += [Spec(i, "COMPENSATING", tilt=25.0, direction=90.0, compensates=compensates, noise=noise()) for i in range(15, 31)]
    return specs


def expected_inclination(parsed: list[tuple[Spec, float, float, float]]) -> str:
    """La punta de cada época compensando, desde la GGA leída: −H·u en su plano tangente local, con pyproj."""
    utm = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
    rows = []
    for spec, lat, lon, h in parsed:
        tip = ["", "", "", "", ""]
        if spec.state == "COMPENSATING":
            east, north, up = pole(spec)
            lon_t, lat_t, h_t = topocentric(lat, lon, h).transform(
                -ANTENNA_HEIGHT * east, -ANTENNA_HEIGHT * north, -ANTENNA_HEIGHT * up, direction=TransformDirection.INVERSE
            )
            e, n = utm.transform(lon_t, lat_t)
            tip = [g.fmt(lat_t), g.fmt(lon_t), g.fmt(h_t), g.fmt(e), g.fmt(n)]
        rows.append(",".join([spec.utc, spec.state, spec.notification, f"{spec.tilt:.3f}", f"{spec.direction:.3f}", *tip]))
    header = [
        "# Punta del jalón esperada de rs3-inclinacion.nmea (S016). Generado por tools/nmea/generar_inclinacion.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        "hora,estado,aviso,inclinacion,direccion,latitud_punta,longitud_punta,h_punta,este,norte",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    inclination, parsed = write(inclination_specs())
    app, _ = write(wizard_specs(compensates=False))
    receiver, _ = write(wizard_specs(compensates=True))
    return {
        "rs3-inclinacion.nmea": inclination,
        "rs3-inclinacion.esperado.csv": expected_inclination(parsed),
        "rs3-asistente-app.nmea": app,
        "rs3-asistente-receptor.nmea": receiver,
    }


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
