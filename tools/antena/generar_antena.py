"""Referencias de la altura de antena y de la punta del jalón con cada perfil, calculadas aparte (S046).

Genera fixtures/antena/punta_perfiles.csv. Cada fila es un jalón de un perfil de antena en un lugar, con una inclinación:

- La altura vertical sale de la altura introducida: la misma si es vertical y, si es inclinada, √(inclinada² − radio²).
  Sin radio, o con la inclinada menor que el radio, no es válida y la fila lleva el estado y no lleva punta (S046/RF-17, RF-18).
- La altura de antena es la vertical más el desplazamiento al centro de fase del perfil (S046/RF-20).
- La punta es la antena desplazada en su plano tangente local, como en `punta_jalon.csv` (ETC_rev_3; S016/RF-13), con las
  conversiones de PROJ.

Los perfiles son los del catálogo de S046/RF-1 y uno propio con radio.

Uso: uv run --project tools tools/antena/generar_antena.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores as g  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

OUT = ROOT / "fixtures" / "antena"
NAME = "punta_perfiles.csv"

# (nombre, desplazamiento al centro de fase en m, radio de la base en m o None)
PROFILES = (
    ("Reach RS3", 0.134, None),
    ("Reach RS2", 0.134, None),
    ("Reach RS", 0.065, None),
    ("Reach RS4", 0.095, None),
    ("Reach RX", 0.1471, None),
    ("Propio con radio", 0.12, 0.06),
)
# (tipo, altura introducida en m)
POLES = (("VERTICAL", 1.8), ("VERTICAL", 0.0), ("VERTICAL", 2.0), ("VERTICAL", 10.0), ("SLANT", 1.95), ("SLANT", 0.06), ("SLANT", 0.05))
PLACES = ((40.4168, -3.7038, 650.123), (28.27223, -16.64251, 3000.0))
TILTS = ((0.0, 0.0), (30.0, 135.0))

HEADER = (
    "perfil,desplazamiento,tipo,introducida,radio,estado,vertical,altura_antena,"
    "latitud,longitud,altura_elipsoidal,inclinacion,direccion,latitud_punta,longitud_punta,altura_punta"
)


def vertical(kind: str, entered: float, radius: float | None) -> tuple[str, float | None]:
    if kind == "VERTICAL":
        return "OK", entered
    if radius is None:
        return "NO_RADIUS", None
    if entered < radius:
        return "SLANT_SHORTER_THAN_RADIUS", None
    return "OK", math.sqrt(entered * entered - radius * radius)


def rows() -> list[str]:
    lines = g.header("Punta del jalón con cada perfil de antena (S046)") + [HEADER]
    for name, offset, radius in PROFILES:
        for kind, entered in POLES:
            state, vert = vertical(kind, entered, radius)
            antenna = None if vert is None else vert + offset
            for lat, lon, h in PLACES:
                for tilt, direction in TILTS:
                    if antenna is None:
                        tip = ("", "", "")
                    else:
                        theta, alpha = math.radians(tilt), math.radians(direction)
                        east = -antenna * math.sin(theta) * math.sin(alpha)
                        north = -antenna * math.sin(theta) * math.cos(alpha)
                        up = -antenna * math.cos(theta)
                        lon_t, lat_t, h_t = g.topocentric(lat, lon, h).transform(east, north, up, direction=TransformDirection.INVERSE)
                        tip = (g.fmt(lat_t), g.fmt(lon_t), g.fmt(h_t))
                    cells = [
                        name, g.fmt(offset), kind, g.fmt(entered), "" if radius is None else g.fmt(radius), state,
                        "" if vert is None else g.fmt(vert), "" if antenna is None else g.fmt(antenna),
                        g.fmt(lat), g.fmt(lon), g.fmt(h), g.fmt(tilt), g.fmt(direction), *tip,
                    ]
                    lines.append(",".join(cells))
    return lines


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / NAME).write_text("\n".join(rows()) + "\n", encoding="utf-8", newline="\n")
    print(f"{OUT / NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
