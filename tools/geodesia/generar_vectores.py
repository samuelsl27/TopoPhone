"""Vectores de referencia geodésicos generados con pyproj/PROJ (constitución nº 2, ADR-2).

Genera en fixtures/geodesia/:
- utm_etrs89.csv: ETRS89 geográficas → UTM husos 29, 30 y 31 (EPSG:25829/25830/25831), con los
  bordes del huso (±3° del meridiano central).
- geoide_egm08_rednap.csv: ondulación N de EGM08-REDNAP con la misma rejilla que va en la app
  (`vgridshift`), en nodos, celdas, bordes y fuera de la cobertura (N vacío).

Uso: uv run --project tools tools/geodesia/generar_vectores.py
Es determinista: la misma versión de pyproj/PROJ y de la rejilla dan el mismo fichero.
"""

from __future__ import annotations

import random
import sys
from pathlib import Path

import pyproj
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rejillas  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "geodesia"
GEOID = rejillas.GRIDS[0]

# Geometría de la rejilla (etiquetas GeoTIFF 33550/33922; PixelIsPoint): nodo (0,0) en el noroeste.
GRID_LON0, GRID_LAT0, GRID_STEP = -9.5, 43.9999964, 0.01666666
GRID_WIDTH, GRID_HEIGHT = 841, 541


def header(kind: str) -> list[str]:
    return [
        f"# {kind}. Generado por tools/geodesia/generar_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla={GEOID.name} sha256={GEOID.sha256}",
    ]


def fmt(value: float) -> str:
    return repr(float(value))


def utm_vectors(rng: random.Random) -> list[str]:
    lines = header("ETRS89 (EPSG:4258) a UTM") + ["latitud,longitud,huso,este,norte"]
    for zone in (29, 30, 31):
        transformer = Transformer.from_crs("EPSG:4258", f"EPSG:258{zone}", always_xy=True)
        central = 6 * zone - 183
        points = []
        for lat in (35.0, 36.5, 38.0, 39.5, 40.4, 41.5, 43.0, 44.0):
            for dlon in (-2.9999999, -2.0, -1.0, 0.0, 0.5, 1.5, 2.5, 2.9999999):
                points.append((lat, central + dlon))
        points += [(rng.uniform(35.0, 44.0), central + rng.uniform(-3.0, 2.9999999)) for _ in range(40)]
        for lat, lon in points:
            east, north = transformer.transform(lon, lat)
            lines.append(",".join([fmt(lat), fmt(lon), str(zone), fmt(east), fmt(north)]))
    return lines


def geoid_transformer() -> Transformer:
    """(lon, lat, 0) → (lon, lat, N) con la rejilla de la app; fuera de ella N no es finito."""
    pyproj.network.set_network_enabled(False)
    pyproj.datadir.append_data_dir(str(GEOID.path.parent))
    pipeline = (
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step +proj=vgridshift +grids={GEOID.name} +multiplier=1 "
        "+step +proj=unitconvert +xy_in=rad +xy_out=deg"
    )
    return Transformer.from_pipeline(pipeline)


def geoid_vectors(rng: random.Random) -> list[str]:
    transformer = geoid_transformer()

    def node(i: int, j: int) -> tuple[float, float]:
        return GRID_LAT0 - j * GRID_STEP, GRID_LON0 + i * GRID_STEP

    points = [node(i, j) for i, j in [(0, 0), (GRID_WIDTH - 1, 0), (0, GRID_HEIGHT - 1), (GRID_WIDTH - 1, GRID_HEIGHT - 1), (420, 270), (255, 256), (256, 255)]]
    points += [node(rng.randrange(GRID_WIDTH), rng.randrange(GRID_HEIGHT)) for _ in range(20)]
    points += [(rng.uniform(35.0, 43.9999964), rng.uniform(-9.5, 4.4999944)) for _ in range(150)]
    # Bordes interiores (último nodo exacto) y puntos de España peninsular y Baleares conocidos.
    points += [(35.0, 0.0), (43.9999964, -3.0), (40.0, 4.4999944), (40.0, -9.5), (40.4168, -3.7038), (39.5696, 2.6502)]
    # Fuera de la cobertura: no hay N.
    outside = [(34.99, -3.0), (44.01, -3.0), (40.0, -9.51), (40.0, 4.51), (28.1, -15.4)]

    lines = header("Ondulación EGM08-REDNAP (N = h - H)") + ["latitud,longitud,ondulacion"]
    for lat, lon in points:
        _, _, n = transformer.transform(lon, lat, 0.0)
        if not abs(n) < 1e6:
            raise SystemExit(f"punto dentro de la rejilla sin valor: {lat}, {lon}")
        lines.append(",".join([fmt(lat), fmt(lon), fmt(n)]))
    for lat, lon in outside:
        _, _, n = transformer.transform(lon, lat, 0.0)
        if abs(n) < 1e6:
            raise SystemExit(f"punto fuera de la rejilla con valor: {lat}, {lon}")
        lines.append(",".join([fmt(lat), fmt(lon), ""]))
    return lines


def main() -> int:
    rejillas.ensure(GEOID, download=True)
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261015)
    for name, lines in (("utm_etrs89.csv", utm_vectors(rng)), ("geoide_egm08_rednap.csv", geoid_vectors(rng))):
        (OUT / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {len(lines) - 3} vectores")
    return 0


if __name__ == "__main__":
    sys.exit(main())
