"""Reproducción sintética del RS3 para la localización (S039).

Genera:
- fixtures/nmea/sintetico/rs3-localizacion.nmea: cinco paradas de 6 épocas a 5 Hz, en un sitio de unos 60 m: cuatro vértices (V1 a V4) con coordenadas
  conocidas en el sistema de la obra, y un punto P5 que se mide después de aplicar la localización.
- fixtures/nmea/sintetico/rs3-localizacion.pares.csv: por parada, la punta tal como la lee la app (E, N y h elipsoidal en UTM 30N, con pyproj), las
  coordenadas conocidas que le corresponden en el sistema de la obra (la transformación de `generar_localizacion.py` aplicada sin ruido) y el rol de la
  parada (`par` o `medido`). Para P5, las coordenadas conocidas son lo que debe dar la app al medirlo con la localización activa.

Uso: uv run --project tools tools/localizacion/generar_replay_localizacion.py
"""

from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "nmea"))
sys.path.insert(0, str(HERE))
import generar_avanzado as a  # noqa: E402
import generar_localizacion as loc  # noqa: E402
import generar_sintetico as g  # noqa: E402

NAME = "rs3-localizacion.nmea"
CSV = "rs3-localizacion.pares.csv"
STOPS = (
    ("V1", "par", (0.0, 0.0, 0.0)),
    ("V2", "par", (40.0, 5.0, 0.4)),
    ("V3", "par", (-15.0, 35.0, -0.3)),
    ("V4", "par", (25.0, -30.0, 0.2)),
    ("P5", "medido", (-30.0, -10.0, 0.1)),
)
EPOCHS_PER_STOP = 6


def fixes() -> list[a.Fix]:
    return [
        a.Fix(a.at_after((s * EPOCHS_PER_STOP + i) * 0.2), offset)
        for s, (_, _, offset) in enumerate(STOPS)
        for i in range(EPOCHS_PER_STOP)
    ]


def expected(parsed: list[a.Parsed]) -> str:
    rows = []
    for s, (name, role, _) in enumerate(STOPS):
        epoch = parsed[s * EPOCHS_PER_STOP]
        known = loc.conocida(epoch.ground_e, epoch.ground_n, epoch.ground_h, None)
        rows.append(",".join([name, role, epoch.utc] + [a.p.fmt(v) for v in (epoch.ground_e, epoch.ground_n, epoch.ground_h, *known)]))
    header = [
        "# Lo esperado de rs3-localizacion.nmea (S039). Generado por tools/localizacion/generar_replay_localizacion.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; jalon_m={g.POLE_M}; receptor=RS3 "
        f"({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# {len(STOPS)} paradas de {EPOCHS_PER_STOP} épocas a 5 Hz; V1 a V4 forman los pares y P5 se mide con la localización activa. "
        f"Transformación: giro={loc.GIRO} rad, escala={loc.ESCALA}, traslación=({loc.TE}, {loc.TN}) m y plano vertical dh={loc.DH}, "
        f"pendientes=({loc.PE}, {loc.PN}).",
        "# este,norte,h: la punta de la primera época de la parada tal como la lee la app; conocido_*: sus coordenadas en el sistema de la obra.",
        "parada,rol,hora,este,norte,h,conocido_este,conocido_norte,conocido_h",
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
