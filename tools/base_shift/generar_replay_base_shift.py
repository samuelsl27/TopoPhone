"""Reproducción sintética del RS3 con una base en SINGLE (error constante) y el desplazamiento de base que la corrige (S020).

Genera:
- fixtures/nmea/sintetico/rs3-base-shift.nmea: cuatro paradas de 6 épocas a 5 Hz, todas con el mismo error de la base (2,1 m al este,
  1,7 m al sur y 0,9 m arriba): K, el vértice conocido; A y B, dos puntos que se miden con el desplazamiento activo; y C, un punto medido
  tras desactivarlo.
- fixtures/nmea/sintetico/rs3-base-shift.desplazamiento.csv: por parada, la punta tal como la lee la app (E, N y h elipsoidal), el
  vector ECEF conocido − medido calculado con la primera época de K, y la punta con el vector sumado en ECEF, todo con pyproj. C va sin
  desplazar. El vértice conocido se da con su Este, Norte y altura elipsoidal (el proyecto de la prueba es de alturas elipsoidales).

Uso: uv run --project tools tools/base_shift/generar_replay_base_shift.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from pyproj import Transformer
from pyproj.enums import TransformDirection

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "nmea"))
import generar_avanzado as a  # noqa: E402
import generar_sintetico as g  # noqa: E402

NAME = "rs3-base-shift.nmea"
CSV = "rs3-base-shift.desplazamiento.csv"
ERROR_ENU = (2.1, -1.7, 0.9)
STOPS = (("K", (0.0, 0.0, 0.0)), ("A", (12.0, 5.0, 0.3)), ("B", (-8.0, 15.0, -0.2)), ("C", (20.0, 20.0, 0.0)))
EPOCHS_PER_STOP = 6
CART = Transformer.from_crs("EPSG:4937", "EPSG:4936", always_xy=True)
UTM_TO_GEO = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)


def fixes() -> list[a.Fix]:
    out = []
    for s, (_, true) in enumerate(STOPS):
        offset = tuple(t + e for t, e in zip(true, ERROR_ENU))
        for i in range(EPOCHS_PER_STOP):
            out.append(a.Fix(a.at_after((s * EPOCHS_PER_STOP + i) * 0.2), offset))
    return out


def ecef(east: float, north: float, height: float) -> tuple[float, float, float]:
    lon, lat = UTM_TO_GEO.transform(east, north)
    return CART.transform(lon, lat, height)


def known_point() -> tuple[float, float, float]:
    """El vértice conocido: K sin el error de la base, con la punta del jalón vertical (antena menos 1,934 m)."""
    local = g.generar_vectores.topocentric(*a.ORIGIN)
    lon, lat, h = local.transform(*STOPS[0][1], direction=TransformDirection.INVERSE)
    east, north = a.UTM.transform(lon, lat)
    return east, north, h - a.ANTENNA_HEIGHT


def expected(parsed: list[a.Parsed]) -> str:
    known = known_point()
    first = [parsed[s * EPOCHS_PER_STOP] for s in range(len(STOPS))]
    k = first[0]
    xk, yk, zk = ecef(*known)
    xm, ym, zm = ecef(k.ground_e, k.ground_n, k.ground_h)
    dx, dy, dz = xk - xm, yk - ym, zk - zm
    rows = []
    for (name, _), epoch in zip(STOPS, first):
        x, y, z = ecef(epoch.ground_e, epoch.ground_n, epoch.ground_h)
        if name == "C":
            shifted = (epoch.ground_e, epoch.ground_n, epoch.ground_h)  # medido tras desactivar el desplazamiento
        else:
            lon, lat, h = CART.transform(x + dx, y + dy, z + dz, direction=TransformDirection.INVERSE)
            east, north = a.UTM.transform(lon, lat)
            shifted = (east, north, h)
        rows.append(
            ",".join(
                [name, epoch.utc]
                + [a.p.fmt(v) for v in (epoch.ground_e, epoch.ground_n, epoch.ground_h, dx, dy, dz, *shifted, *known)]
            )
        )
    header = [
        "# Lo esperado de rs3-base-shift.nmea (S020). Generado por tools/base_shift/generar_replay_base_shift.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; jalon_m={g.POLE_M}; receptor=RS3 "
        f"({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# {len(STOPS)} paradas de {EPOCHS_PER_STOP} épocas a 5 Hz con el error de la base ENU={ERROR_ENU} m; K es el conocido, A y B se miden "
        "con el desplazamiento activo y C tras desactivarlo.",
        "# este,norte,h: la punta de la primera época de la parada tal como la lee la app; dx,dy,dz: el vector conocido − medido de K; "
        "este_d,norte_d,h_d: la punta con el vector sumado en ECEF (C va sin desplazar); conocido_*: el vértice sin el error.",
        "parada,hora,este,norte,h,dx,dy,dz,este_d,norte_d,h_d,conocido_este,conocido_norte,conocido_h",
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
        print(f"{name}: {len(content)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())


