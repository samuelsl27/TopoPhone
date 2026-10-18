"""Vectores de referencia geodésicos generados con pyproj/PROJ (constitución nº 2, ADR-2).

Genera en fixtures/geodesia/:
- utm_etrs89.csv: ETRS89 geográficas → UTM husos 29, 30 y 31 (EPSG:25829/25830/25831), con los
  bordes del huso (±3° del meridiano central).
- geoide_egm08_rednap.csv: ondulación N de EGM08-REDNAP con la misma rejilla que va en la app
  (`vgridshift`), en nodos, celdas, bordes y fuera de la cobertura (N vacío).
- proyecto_huso_fijo.csv: punto del suelo (φ, λ, h) → Este, Norte en el huso fijo del proyecto,
  aunque el punto caiga en otro huso, y altitud H = h − N (vacía fuera del geoide) (S006/RF-29, RF-30).
- utm_regcan95.csv: REGCAN95 geográficas → UTM husos 27 y 28 (EPSG:4082/4083) (S008/RF-1).
- geoide_egm08_rednap_canarias.csv: ondulación del geoide de Canarias, dentro y fuera (S008/RF-4).
- ed50_utm.csv: ETRS89 → ED50 geográficas y UTM 29–31 por la rejilla SPED2ETV2 del IGN (EPSG:15932),
  vacíos fuera de la rejilla (S008/RF-7, RF-8).
- factores_tm.csv: factor de escala y convergencia de meridianos de la UTM, con huso forzado
  (S008/RF-13, RF-14).

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
GEOID = rejillas.GEOID_PENINSULA

# Geometría de la rejilla (etiquetas GeoTIFF 33550/33922; PixelIsPoint): nodo (0,0) en el noroeste.
GRID_LON0, GRID_LAT0, GRID_STEP = -9.5, 43.9999964, 0.01666666
GRID_WIDTH, GRID_HEIGHT = 841, 541


def header(kind: str, grid: rejillas.Grid = GEOID) -> list[str]:
    return [
        f"# {kind}. Generado por tools/geodesia/generar_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla={grid.name} sha256={grid.sha256}",
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


def use_local_grids() -> None:
    """Solo las rejillas de la app, nunca las de la red ni otras instaladas."""
    pyproj.network.set_network_enabled(False)
    for grid in rejillas.GRIDS:
        pyproj.datadir.append_data_dir(str(grid.path.parent))


def geoid_transformer(grid: rejillas.Grid = GEOID) -> Transformer:
    """(lon, lat, 0) → (lon, lat, N) con la rejilla de la app; fuera de ella N no es finito."""
    use_local_grids()
    pipeline = (
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step +proj=vgridshift +grids={grid.name} +multiplier=1 "
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


def project_vectors(rng: random.Random) -> list[str]:
    geoid = geoid_transformer()
    lines = header("Punto del suelo a ETRS89 / UTM con huso fijo y altitud EGM08-REDNAP") + [
        "latitud,longitud,altura_elipsoidal,huso,este,norte,altitud"
    ]
    inside = [(lat, lon) for lat in (35.5, 37.0, 38.5, 40.0, 41.5, 43.0, 43.9) for lon in (-9.3, -7.5, -6.0, -4.5, -3.0, -1.5, 0.0, 1.5, 3.0, 4.3)]
    inside += [(rng.uniform(35.0, 43.99), rng.uniform(-9.5, 4.49)) for _ in range(30)]
    # Dentro del ámbito del SC (λ −10…+5, φ 35…44,5) pero fuera de la rejilla del geoide: sin altitud.
    no_geoid = [(44.3, -3.0), (40.0, -9.8), (40.0, 4.8)]
    for zone in (29, 30, 31):
        utm = Transformer.from_crs("EPSG:4258", f"EPSG:258{zone}", always_xy=True)
        for lat, lon in inside + no_geoid:
            h = round(rng.uniform(0.0, 1500.0), 4)
            east, north = utm.transform(lon, lat)
            _, _, n = geoid.transform(lon, lat, 0.0)
            altitude = fmt(h - n) if abs(n) < 1e6 else ""
            if (lat, lon) in no_geoid and altitude:
                raise SystemExit(f"punto sin geoide con valor: {lat}, {lon}")
            lines.append(",".join([fmt(lat), fmt(lon), fmt(h), str(zone), fmt(east), fmt(north), altitude]))
    return lines


def regcan95_vectors(rng: random.Random) -> list[str]:
    """S008/RF-1: REGCAN95 usa GRS80, como ETRS89; los husos 27 y 28 cubren Canarias."""
    lines = header("REGCAN95 (EPSG:4081) a UTM 27/28 (EPSG:4082/4083)", rejillas.GEOID_CANARIAS) + ["latitud,longitud,huso,este,norte"]
    for zone, epsg in ((27, 4082), (28, 4083)):
        transformer = Transformer.from_crs("EPSG:4081", f"EPSG:{epsg}", always_xy=True)
        central = 6 * zone - 183
        points = [(lat, central + dlon) for lat in (27.5, 28.0, 28.5, 29.0, 29.4) for dlon in (-2.9999999, -1.5, 0.0, 1.5, 2.9999999)]
        points += [(rng.uniform(27.0, 29.5), central + rng.uniform(-3.0, 2.9999999)) for _ in range(30)]
        for lat, lon in points:
            east, north = transformer.transform(lon, lat)
            lines.append(",".join([fmt(lat), fmt(lon), str(zone), fmt(east), fmt(north)]))
    return lines


def canarias_geoid_vectors(rng: random.Random) -> list[str]:
    """S008/RF-4: ondulación del geoide de Canarias; vacía fuera de su rejilla."""
    grid = rejillas.GEOID_CANARIAS
    transformer = geoid_transformer(grid)
    islands = [(28.4636, -16.2518), (28.1, -15.4), (28.96, -13.55), (28.68, -17.86), (27.75, -18.0), (28.36, -14.05), (28.09, -17.11)]
    points = islands + [(rng.uniform(27.7, 29.3), rng.uniform(-18.1, -13.4)) for _ in range(120)]
    outside = [(40.0, -3.0), (26.0, -15.5), (30.5, -15.5), (28.5, -20.5), (28.5, -11.0)]
    lines = header("Ondulación EGM08-REDNAP de Canarias (N = h - H)", grid) + ["latitud,longitud,ondulacion"]
    for lat, lon in points:
        _, _, n = transformer.transform(lon, lat, 0.0)
        lines.append(",".join([fmt(lat), fmt(lon), fmt(n) if abs(n) < 1e6 else ""]))
    for lat, lon in outside:
        _, _, n = transformer.transform(lon, lat, 0.0)
        if abs(n) < 1e6:
            raise SystemExit(f"punto fuera de la rejilla de Canarias con valor: {lat}, {lon}")
        lines.append(",".join([fmt(lat), fmt(lon), ""]))
    return lines


def ed50_transformer(zone: int | None) -> Transformer:
    """ETRS89 → ED50 por la rejilla oficial invertida (EPSG:15932) y, si hay huso, UTM sobre el internacional de 1924."""
    use_local_grids()
    steps = (
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step +inv +proj=hgridshift +grids={rejillas.ED50_SHIFT.name} "
    )
    steps += f"+step +proj=utm +zone={zone} +ellps=intl" if zone else "+step +proj=unitconvert +xy_in=rad +xy_out=deg"
    return Transformer.from_pipeline(steps)


def ed50_vectors(rng: random.Random) -> list[str]:
    """S008/RF-7, RF-8: ED50 geográficas y UTM 29–31; fuera de la rejilla, vacío."""
    geographic = ed50_transformer(None)
    lines = header("ETRS89 (EPSG:4258) a ED50 geográficas y UTM por SPED2ETV2", rejillas.ED50_SHIFT) + [
        "latitud,longitud,latitud_ed50,longitud_ed50,huso,este,norte"
    ]
    inside = [(lat, lon) for lat in (36.0, 37.5, 39.0, 40.4168, 42.0, 43.5) for lon in (-9.0, -7.0, -5.0, -3.7038, -2.0, 0.0, 2.0, 3.0)]
    inside += [(39.5696, 2.6502), (39.95, 4.1), (38.9, 1.43)]  # Baleares: Palma, Menorca, Ibiza
    inside += [(rng.uniform(36.0, 43.5), rng.uniform(-9.0, 3.2)) for _ in range(40)]
    outside = [(28.1, -15.4), (45.5, -3.0), (34.0, -3.0), (40.0, -12.0)]
    for zone in (29, 30, 31):
        utm = ed50_transformer(zone)
        for lat, lon in inside + outside:
            lon_ed, lat_ed = geographic.transform(lon, lat)
            if abs(lat_ed) <= 90 and abs(lon_ed) <= 180:
                east, north = utm.transform(lon, lat)
                values = [fmt(lat_ed), fmt(lon_ed), str(zone), fmt(east), fmt(north)]
            else:
                if (lat, lon) in inside:
                    raise SystemExit(f"punto dentro sin ED50: {lat}, {lon}")
                values = ["", "", str(zone), "", ""]
            lines.append(",".join([fmt(lat), fmt(lon)] + values))
    return lines


def factor_vectors(rng: random.Random) -> list[str]:
    """S008/RF-13, RF-14: factor de escala (parallel_scale) y convergencia de meridianos (grados) de pyproj."""
    lines = header("Factor de escala y convergencia de la UTM (pyproj get_factors)") + [
        "elipsoide,latitud,longitud,huso,factor_escala,convergencia_grados"
    ]
    cases = [("GRS80", zone, lat_range) for zone, lat_range in ((29, (35.0, 44.0)), (30, (35.0, 44.0)), (31, (35.0, 44.0)))]
    cases += [("GRS80", 27, (27.0, 29.5)), ("GRS80", 28, (27.0, 29.5)), ("intl", 30, (35.0, 44.0))]
    for ellps, zone, (lat_min, lat_max) in cases:
        proj = pyproj.Proj(proj="utm", zone=zone, ellps=ellps)
        central = 6 * zone - 183
        # Hasta 4,5° del meridiano central: también con huso forzado.
        points = [(lat, central + dlon) for lat in (lat_min, (lat_min + lat_max) / 2, lat_max) for dlon in (-4.5, -3.0, -1.0, 0.0, 1.0, 3.0, 4.5)]
        points += [(rng.uniform(lat_min, lat_max), central + rng.uniform(-4.5, 4.5)) for _ in range(15)]
        for lat, lon in points:
            factors = proj.get_factors(lon, lat)
            lines.append(",".join([ellps, fmt(lat), fmt(lon), str(zone), fmt(factors.parallel_scale), fmt(factors.meridian_convergence)]))
    return lines


def main() -> int:
    for grid in rejillas.GRIDS:
        rejillas.ensure(grid, download=True)
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261015)
    outputs = (
        ("utm_etrs89.csv", utm_vectors(rng)),
        ("geoide_egm08_rednap.csv", geoid_vectors(rng)),
        ("proyecto_huso_fijo.csv", project_vectors(rng)),
        # Cada fichero de S008 con su propia semilla: los de S002 y S006 no cambian.
        ("utm_regcan95.csv", regcan95_vectors(random.Random(20261018))),
        ("geoide_egm08_rednap_canarias.csv", canarias_geoid_vectors(random.Random(20261019))),
        ("ed50_utm.csv", ed50_vectors(random.Random(20261020))),
        ("factores_tm.csv", factor_vectors(random.Random(20261021))),
    )
    for name, lines in outputs:
        (OUT / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {len(lines) - 3} vectores")
    return 0


if __name__ == "__main__":
    sys.exit(main())
