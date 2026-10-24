"""Referencias de la geometría del replanteo calculadas con pyproj (S018).

Genera fixtures/replanteo/replanteo-geometria.csv: pares de punto de diseño y punta del jalón, en la Península (ETRS89 /
UTM 30), en Canarias (REGCAN95 / UTM 28) y en un SC geográfico, con la distancia horizontal, las diferencias de Norte y
Este (diseño menos punta), el rumbo al punto y, para un rumbo del usuario, adelante y derecha.

- Las posiciones de la punta salen de desplazar el diseño en un plano tangente local con la tubería `cart` +
  `topocentric` de PROJ, y los Este y Norte de EPSG:4258 → 25830 y EPSG:4081 → 4083.
- Los valores van redondeados antes de calcular lo esperado, así que la app y este script parten de los mismos números.
- En el SC geográfico no hay Este ni Norte: las diferencias son las del plano tangente local de la punta, con
  `topocentric`.
- Con distancia cero el rumbo no existe: queda vacío, y adelante y derecha valen 0.

Uso: uv run --project tools tools/replanteo/generar_replanteo.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "nmea"))
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

OUT = ROOT / "fixtures" / "replanteo"
NAME = "replanteo-geometria.csv"

# Desplazamientos (Este, Norte, Arriba) en metros desde el diseño hasta la punta.
OFFSETS = (
    (0.0, 0.0, 0.0),
    (0.01, 0.0, 0.0),
    (0.0, 0.02, 0.005),
    (-0.3, 0.4, -0.1),
    (3.0, -4.0, 0.5),
    (-25.0, 35.0, 0.0),
    (120.0, -80.0, 2.0),
    (0.002, 0.003, 0.0),
    (-1.0, -1.0, 0.25),
    (0.0, 0.5, -0.02),
)
HEADINGS_DEG = (0.0, 45.0, 90.0, 180.0, 270.0, 330.0, 10.0, 200.0, 359.0, 135.0)


@dataclass(frozen=True)
class Site:
    sc: str
    design: tuple[float, float, float]  # latitud, longitud, altura elipsoidal
    geographic: str | None
    projected: str | None


SITES = (
    Site("UTM30", (40.4168, -3.7038, 699.423), "EPSG:4258", "EPSG:25830"),
    Site("UTM28", (28.4636, -16.2518, 112.456), "EPSG:4081", "EPSG:4083"),
    Site("GEOGRAFICO", (41.3874, 2.1686, 12.345), None, None),
)

HEADER = (
    "caso,sc,diseno_lat,diseno_lon,diseno_h,punta_lat,punta_lon,punta_h,diseno_e,diseno_n,punta_e,punta_n,rumbo_usuario_deg,"
    "distancia,d_este,d_norte,rumbo_deg,adelante,derecha,d_cota"
)


def fmt(value: float | None) -> str:
    return "" if value is None else f"{value:.9f}"


def tip_position(site: Site, offset: tuple[float, float, float]) -> tuple[float, float, float]:
    lat0, lon0, h0 = site.design
    local = g.generar_vectores.topocentric(lat0, lon0, h0)
    lon, lat, height = local.transform(*offset, direction=TransformDirection.INVERSE)
    return float(f"{lat:.11f}"), float(f"{lon:.11f}"), float(f"{height:.5f}")


def row(site: Site, index: int, offset: tuple[float, float, float], heading: float) -> str:
    lat_d, lon_d, h_d = site.design
    lat_t, lon_t, h_t = tip_position(site, offset)
    if site.projected is None:
        local = g.generar_vectores.topocentric(lat_t, lon_t, h_t)
        d_east, d_north, _ = local.transform(lon_d, lat_d, h_d)
        east_d = north_d = east_t = north_t = None
    else:
        projection = Transformer.from_crs(site.geographic, site.projected, always_xy=True)
        east_d, north_d = (round(v, 5) for v in projection.transform(lon_d, lat_d))
        east_t, north_t = (round(v, 5) for v in projection.transform(lon_t, lat_t))
        d_east, d_north = east_d - east_t, north_d - north_t
    distance = math.hypot(d_east, d_north)
    bearing = forward = right = None
    if distance > 1e-9:
        bearing = math.degrees(math.atan2(d_east, d_north)) % 360.0
        relative = math.radians(bearing - heading)
        forward, right = distance * math.cos(relative), distance * math.sin(relative)
    else:
        forward = right = 0.0
    cells = [
        f"{site.sc}-{index + 1}",
        site.sc,
        f"{lat_d:.11f}",
        f"{lon_d:.11f}",
        f"{h_d:.5f}",
        f"{lat_t:.11f}",
        f"{lon_t:.11f}",
        f"{h_t:.5f}",
        "" if east_d is None else f"{east_d:.5f}",
        "" if north_d is None else f"{north_d:.5f}",
        "" if east_t is None else f"{east_t:.5f}",
        "" if north_t is None else f"{north_t:.5f}",
        f"{heading:.1f}",
        fmt(distance),
        fmt(d_east),
        fmt(d_north),
        fmt(bearing),
        fmt(forward),
        fmt(right),
        fmt(h_t - h_d),
    ]
    return ",".join(cells)


def generate() -> str:
    header = [
        "# Geometría del replanteo calculada con pyproj (S018). Generado por tools/replanteo/generar_replanteo.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → 25830, EPSG:4081 → 4083 y plano tangente local",
        "# d_este, d_norte: diseño menos punta (lo que hay que moverse). rumbo_deg: del norte, en el sentido de las agujas del reloj.",
        "# adelante, derecha: con el rumbo del usuario rumbo_usuario_deg. d_cota: punta menos diseño. Con distancia 0, rumbo vacío.",
        HEADER,
    ]
    rows = [row(site, i, offset, HEADINGS_DEG[i]) for site in SITES for i, offset in enumerate(OFFSETS)]
    return "\n".join(header + rows) + "\n"


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    content = generate()
    (OUT / NAME).write_text(content, encoding="utf-8", newline="\n")
    print(f"{NAME}: {len(content)} caracteres")
    return 0


if __name__ == "__main__":
    sys.exit(main())
