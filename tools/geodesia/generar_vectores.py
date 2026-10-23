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
- circulo_precision.csv: vértices del círculo de precisión del rover, por el problema directo de la
  geodésica sobre GRS80 (`Geod.fwd`), con radios de 5 mm a 100 m y 16 rumbos (S009/RF-17).
- web_mercator.csv: ETRS89 geográficas → Web Mercator (EPSG:3857) y metros del terreno por píxel a
  varios zooms (teselas de 512 px), medidos con la geodésica entre los dos bordes de un píxel (S009/RF-12).
- puntos_manuales.csv: coordenadas escritas de un punto manual (en el SC del proyecto o en geográficas de
  su datum, con altitud, altura elipsoidal o sin altura) → punto del suelo en ETRS89 o REGCAN95 y
  coordenadas del proyecto; o el motivo por el que no se puede guardar (S011/RF-17 a RF-20).
- jalon_corregido.csv: antena de un punto medido y altura del jalón corregida → punto del suelo y
  coordenadas del proyecto (S011/RF-14).
- ecef.csv: ETRS89 geográficas con altura elipsoidal ↔ cartesianas geocéntricas (EPSG:4937 ↔ EPSG:4936,
  GRS80), en España, Canarias, la estación AVL13M de la grabación RTCM real y casos extremos (S015/RF-13).
- linea_base.csv: distancia en línea recta entre la antena del rover y la base, desde sus cartesianas
  geocéntricas de pyproj, de metros a 80 km (S015/RF-14).
- punta_jalon.csv: antena (φ, λ, h), inclinación, dirección de la inclinación y altura de antena → punta del
  jalón, con el desplazamiento de ETC_rev_3 en el plano tangente local de la antena, calculado con la tubería
  `cart` + `topocentric` de PROJ (S016/RF-13, RF-25, RF-27).

Uso: uv run --project tools tools/geodesia/generar_vectores.py
Es determinista: la misma versión de pyproj/PROJ y de la rejilla dan el mismo fichero.
"""

from __future__ import annotations

import random
import sys
from pathlib import Path

import math

import pyproj
from pyproj import Transformer
from pyproj.enums import TransformDirection

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


def circle_vectors(rng: random.Random) -> list[str]:
    """S009/RF-17: vértices a distancia r y rumbo θ (grados desde el norte, en sentido horario) del centro."""
    lines = header("Círculo de precisión: problema directo de la geodésica sobre GRS80 (Geod.fwd)") + [
        "latitud,longitud,radio,rumbo,latitud_vertice,longitud_vertice"
    ]
    geod = pyproj.Geod(ellps="GRS80")
    centers = [(0.0, 0.0), (28.4636, -16.2518), (40.41668, -3.70371), (44.0, 3.0)]
    centers += [(rng.uniform(27.0, 44.0), rng.uniform(-18.5, 4.5)) for _ in range(2)]
    for lat, lon in centers:
        for radius in (0.005, 0.012, 0.5, 2.0, 25.0, 100.0):
            for k in range(16):
                azimuth = k * 22.5
                vlon, vlat, _ = geod.fwd(lon, lat, azimuth, radius)
                lines.append(",".join([fmt(lat), fmt(lon), fmt(radius), fmt(azimuth), fmt(vlat), fmt(vlon)]))
    return lines


# Teselas de 512 px (MapLibre): el mundo mide 512·2^z px y 2π·6378137 m en EPSG:3857.
MERCATOR_HALF_WORLD = 20037508.342789244
TILE_PX = 512


def mercator_vectors(rng: random.Random) -> list[str]:
    """S009/RF-12: x, y en EPSG:3857 y metros del terreno que mide un píxel horizontal en ese punto."""
    lines = header("ETRS89 geográficas a Web Mercator (EPSG:3857) y metros por píxel") + [
        "latitud,longitud,x,y,zoom,metros_por_pixel"
    ]
    forward = Transformer.from_crs("EPSG:4326", "EPSG:3857", always_xy=True)
    inverse = Transformer.from_crs("EPSG:3857", "EPSG:4326", always_xy=True)
    geod = pyproj.Geod(ellps="WGS84")
    points = [(0.0, 0.0), (27.6, -18.2), (28.4636, -16.2518), (36.0, -5.6), (40.41668, -3.70371), (43.8, 4.4), (44.0, -9.0), (85.0, 179.9)]
    points += [(rng.uniform(27.0, 44.0), rng.uniform(-18.5, 4.5)) for _ in range(22)]
    for lat, lon in points:
        x, y = forward.transform(lon, lat)
        for zoom in (5.0, 12.0, 17.0, 17.5, 22.0):
            pixel = 2 * MERCATOR_HALF_WORLD / (TILE_PX * 2**zoom)
            lon_a, lat_a = inverse.transform(x - pixel / 2, y)
            lon_b, lat_b = inverse.transform(x + pixel / 2, y)
            _, _, metres = geod.inv(lon_a, lat_a, lon_b, lat_b)
            lines.append(",".join([fmt(lat), fmt(lon), fmt(x), fmt(y), fmt(zoom), fmt(metres)]))
    return lines


# Altura del ARP del RS3 sobre la base del jalón (ETC_rev_3; `ReceiverModel.RS3` en la app).
RS3_ARP_M = 0.134

# SC del proyecto: datum, huso (None en geográficas), EPSG de las geográficas y del UTM, y su geoide.
MANUAL_CRS = (
    ("ETRS89", 29, "EPSG:4258", "EPSG:25829"),
    ("ETRS89", 30, "EPSG:4258", "EPSG:25830"),
    ("ETRS89", 31, "EPSG:4258", "EPSG:25831"),
    ("ETRS89", None, "EPSG:4258", None),
    ("REGCAN95", 27, "EPSG:4081", "EPSG:4082"),
    ("REGCAN95", 28, "EPSG:4081", "EPSG:4083"),
    ("REGCAN95", None, "EPSG:4081", None),
    ("ED50", 29, None, None),
    ("ED50", 30, None, None),
    ("ED50", 31, None, None),
)


def ed50_to_etrs89(zone: int | None) -> Transformer:
    """ED50 (UTM sobre el internacional de 1924, o geográficas) → ETRS89 geográficas por la rejilla en su sentido directo."""
    use_local_grids()
    steps = "+proj=pipeline "
    steps += f"+step +inv +proj=utm +zone={zone} +ellps=intl " if zone else "+step +proj=unitconvert +xy_in=deg +xy_out=rad "
    steps += f"+step +proj=hgridshift +grids={rejillas.ED50_SHIFT.name} +step +proj=unitconvert +xy_in=rad +xy_out=deg"
    return Transformer.from_pipeline(steps)


def finite(*values: float) -> bool:
    return all(abs(v) < 1e6 for v in values)


class ManualCrs:
    """Lo que la app hace con un punto manual, calculado con pyproj."""

    def __init__(self, datum: str, zone: int | None, geographic: str | None, utm: str | None) -> None:
        self.datum, self.zone = datum, zone
        self.geoid = geoid_transformer(rejillas.GEOID_CANARIAS if datum == "REGCAN95" else GEOID)
        self.area = ((27.0, 29.5), (-18.5, -13.0)) if datum == "REGCAN95" else ((35.0, 44.5), (-10.0, 5.0))
        if datum == "ED50":
            self.to_base_projected = ed50_to_etrs89(zone)
            self.to_base_geographic = ed50_to_etrs89(None)
            self.from_base_projected = ed50_transformer(zone)
            self.from_base_geographic = ed50_transformer(None)
        else:
            self.to_base_geographic = None
            self.to_base_projected = Transformer.from_crs(utm, geographic, always_xy=True) if utm else None
            self.from_base_projected = Transformer.from_crs(geographic, utm, always_xy=True) if utm else None
            self.from_base_geographic = None

    def typed_from_base(self, lat: float, lon: float, form: str) -> tuple[float, float]:
        """Lo que escribiría el usuario para el punto (lat, lon) de ETRS89 o REGCAN95."""
        if form == "PROJECT_CRS" and self.zone:
            east, north = self.from_base_projected.transform(lon, lat)
            return east, north
        if self.datum == "ED50":
            lon_ed, lat_ed = self.from_base_geographic.transform(lon, lat)
            return lat_ed, lon_ed
        return lat, lon

    def base_from_typed(self, form: str, first: float, second: float) -> tuple[float, float] | None:
        """Punto del suelo en ETRS89 o REGCAN95 desde lo escrito; None fuera de la rejilla ED50."""
        if form == "PROJECT_CRS" and self.zone:
            lon, lat = self.to_base_projected.transform(first, second)
        elif self.datum == "ED50":
            lon, lat = self.to_base_geographic.transform(second, first)
        else:
            lat, lon = first, second
        return (lat, lon) if finite(lat, lon) else None

    def undulation(self, lat: float, lon: float) -> float | None:
        _, _, n = self.geoid.transform(lon, lat, 0.0)
        return n if finite(n) else None

    def inside(self, lat: float, lon: float) -> bool:
        (lat_min, lat_max), (lon_min, lon_max) = self.area
        return lat_min <= lat <= lat_max and lon_min <= lon <= lon_max

    def projected(self, lat: float, lon: float) -> tuple[float, float] | tuple[None, None]:
        if not self.zone:
            return None, None
        east, north = self.from_base_projected.transform(lon, lat)
        return east, north


def manual_row(crs: ManualCrs, heights: str, form: str, first: float, second: float, height: float | None, kind: str) -> list[str]:
    """Una fila: lo escrito y lo que debe salir, o el motivo de rechazo (S011/RF-19)."""
    typed = [crs.datum, str(crs.zone or ""), heights, form, fmt(first), fmt(second), fmt(height) if height is not None else "", kind]
    base = crs.base_from_typed(form, first, second)
    if base is None:
        return typed + ["", "", "", "", "", "", "OUTSIDE_ED50_GRID"]
    lat, lon = base
    if not crs.inside(lat, lon):
        return typed + ["", "", "", "", "", "", "OUTSIDE_CRS_AREA"]
    n = crs.undulation(lat, lon)
    altitude_input = height is not None and kind == "PROJECT" and heights == "ORTHOMETRIC"
    if altitude_input and n is None:
        return typed + ["", "", "", "", "", "", "OUTSIDE_GEOID"]
    if height is None:
        ellipsoidal = None
    elif altitude_input:
        ellipsoidal = height + n
    else:
        ellipsoidal = height
    if ellipsoidal is None:
        project_height = None
    elif heights == "ELLIPSOIDAL":
        project_height = ellipsoidal
    elif altitude_input:
        project_height = height
    else:
        project_height = ellipsoidal - n if n is not None else None
    if form == "PROJECT_CRS" and crs.zone:
        east, north = first, second
    else:
        east, north = crs.projected(lat, lon)
    expected = [fmt(lat), fmt(lon), fmt(ellipsoidal) if ellipsoidal is not None else ""]
    expected += [fmt(east) if east is not None else "", fmt(north) if north is not None else ""]
    expected += [fmt(project_height) if project_height is not None else "", ""]
    return typed + expected


def manual_vectors(rng: random.Random) -> list[str]:
    """S011/RF-17 a RF-20: lo escrito se calcula con pyproj al revés, desde lo que verá el usuario."""
    lines = header("Puntos manuales: coordenadas escritas a punto del suelo y SC del proyecto", rejillas.ED50_SHIFT) + [
        "datum,huso,sistema_alturas,forma,primera,segunda,altura,tipo_altura,"
        "latitud,longitud,altura_elipsoidal,este,norte,altura_proyecto,motivo"
    ]
    for datum, zone, geographic, utm in MANUAL_CRS:
        crs = ManualCrs(datum, zone, geographic, utm)
        if datum == "REGCAN95":
            points = [(28.4636, -16.2518), (28.1, -15.4), (28.96, -13.55), (28.68, -17.86)]
            points += [(rng.uniform(27.7, 29.3), rng.uniform(-18.1, -13.4)) for _ in range(4)]
        else:
            central = 6 * (zone or 30) - 183
            points = [(40.41668, -3.70371), (39.5696, 2.6502), (43.36, -8.41), (36.72, -4.42)]
            points += [(rng.uniform(36.0, 43.5), central + rng.uniform(-2.9, 2.9)) for _ in range(4)]
        heights_options = ["ORTHOMETRIC"] if datum == "ED50" else ["ORTHOMETRIC", "ELLIPSOIDAL"]
        forms = ["PROJECT_CRS", "GEOGRAPHIC"] if zone else ["GEOGRAPHIC"]
        for lat, lon in points:
            if crs.base_from_typed("GEOGRAPHIC", *crs.typed_from_base(lat, lon, "GEOGRAPHIC")) is None:
                continue  # en el mar, fuera de la rejilla ED50: los rechazos van aparte
            h = round(rng.uniform(0.0, 1500.0), 4)
            n = crs.undulation(lat, lon)
            for heights in heights_options:
                for form in forms:
                    first, second = crs.typed_from_base(lat, lon, form)
                    kinds = [("PROJECT", h - n if heights == "ORTHOMETRIC" and n is not None else h)]
                    if form == "GEOGRAPHIC" and datum != "ED50" and heights == "ORTHOMETRIC":
                        kinds.append(("ELLIPSOIDAL", h))
                    for kind, value in kinds:
                        lines.append(",".join(manual_row(crs, heights, form, first, second, value, kind)))
                    lines.append(",".join(manual_row(crs, heights, form, first, second, None, "PROJECT")))
    # Rechazos: fuera del ámbito, de la rejilla ED50 o del geoide (con altitud; sin altura, se guarda).
    etrs30 = ManualCrs(*MANUAL_CRS[1])
    ed50_30 = ManualCrs(*MANUAL_CRS[8])
    canarias = ManualCrs(*MANUAL_CRS[5])
    rejected = [
        manual_row(etrs30, "ORTHOMETRIC", "PROJECT_CRS", 440000.0, 5600000.0, 650.0, "PROJECT"),  # φ ≈ 50,5°
        manual_row(etrs30, "ORTHOMETRIC", "GEOGRAPHIC", 28.1, -15.4, 650.0, "PROJECT"),  # Canarias en ETRS89
        manual_row(canarias, "ORTHOMETRIC", "GEOGRAPHIC", 40.0, -3.7, 650.0, "PROJECT"),  # Madrid en REGCAN95
        manual_row(ed50_30, "ORTHOMETRIC", "GEOGRAPHIC", 35.1, -3.0, 650.0, "PROJECT"),  # en el ámbito, fuera de la rejilla
        manual_row(etrs30, "ORTHOMETRIC", "GEOGRAPHIC", 44.3, -3.0, 650.0, "PROJECT"),  # fuera del geoide, con altitud
        manual_row(etrs30, "ORTHOMETRIC", "GEOGRAPHIC", 44.3, -3.0, 650.0, "ELLIPSOIDAL"),  # elipsoidal: se guarda
        manual_row(etrs30, "ORTHOMETRIC", "GEOGRAPHIC", 44.3, -3.0, None, "PROJECT"),  # sin altura: se guarda
    ]
    reasons = [row[-1] for row in rejected]
    if reasons != ["OUTSIDE_CRS_AREA", "OUTSIDE_CRS_AREA", "OUTSIDE_CRS_AREA", "OUTSIDE_ED50_GRID", "OUTSIDE_GEOID", "", ""]:
        raise SystemExit(f"rechazos inesperados: {reasons}")
    lines += [",".join(row) for row in rejected]
    return lines


def pole_vectors(rng: random.Random) -> list[str]:
    """S011/RF-14: el suelo bajo la antena con la nueva altura del jalón (jalón vertical, S002/RF-32)."""
    lines = header("Jalón corregido: antena y jalón a punto del suelo y SC del proyecto", rejillas.ED50_SHIFT) + [
        "datum,huso,latitud,longitud,altura_antena,jalon,altura_elipsoidal,este,norte,altitud"
    ]
    for datum, zone, geographic, utm in MANUAL_CRS:
        if zone is None:
            continue
        crs = ManualCrs(datum, zone, geographic, utm)
        central = 6 * zone - 183
        lat_range = (27.7, 29.3) if datum == "REGCAN95" else (36.0, 43.5)
        # Dentro del huso y del ámbito del SC: fuera de él la app no da coordenadas (S008/RF-9).
        (lon_min, lon_max) = (-18.1, -13.4) if datum == "REGCAN95" else (-9.9, 4.9)
        lon_range = (max(central - 2.9, lon_min), min(central + 2.9, lon_max))
        for _ in range(8):
            lat, lon = rng.uniform(*lat_range), rng.uniform(*lon_range)
            if datum == "ED50" and crs.from_base_geographic.transform(lon, lat)[0] > 1e6:
                continue
            antenna_h = round(rng.uniform(50.0, 1500.0), 4)
            pole = round(rng.uniform(0.0, 2.5), 3)
            ground_h = antenna_h - (pole + RS3_ARP_M)
            east, north = crs.projected(lat, lon)
            n = crs.undulation(lat, lon)
            altitude = fmt(ground_h - n) if n is not None else ""
            lines.append(",".join([datum, str(zone), fmt(lat), fmt(lon), fmt(antenna_h), fmt(pole), fmt(ground_h), fmt(east), fmt(north), altitude]))
    return lines


# Estación de referencia de fixtures/rtcm/rgapa-AVL13M.rtcm3 (mensaje 1006, estación 6), en metros.
AVL13M_ECEF = (4604889.1789, -476930.7239, 4372677.1220)


def geocentric() -> tuple[Transformer, Transformer]:
    """ETRS89 geográficas 3D (EPSG:4937) → geocéntricas (EPSG:4936), y al revés."""
    return (
        Transformer.from_crs("EPSG:4937", "EPSG:4936", always_xy=True),
        Transformer.from_crs("EPSG:4936", "EPSG:4937", always_xy=True),
    )


def ecef_vectors(rng: random.Random) -> list[str]:
    """S015/RF-13: geográficas con altura elipsoidal ↔ X, Y, Z sobre GRS80."""
    lines = header("Geográficas ETRS89 con altura elipsoidal ↔ geocéntricas (EPSG:4937 ↔ EPSG:4936)") + [
        "latitud,longitud,altura_elipsoidal,x,y,z"
    ]
    forward, inverse = geocentric()
    points = [(0.0, 0.0, 0.0), (0.0, 90.0, 10.0), (-33.8688, 151.2093, 58.0), (89.9999, 45.0, 120.0), (-89.5, -120.0, 2800.0)]
    points += [(40.41668, -3.70371, 699.42), (28.27223, -16.64251, 3718.0), (39.5696, 2.6502, -12.5)]
    points += [(rng.uniform(36.0, 43.8), rng.uniform(-9.3, 4.3), rng.uniform(-50.0, 3500.0)) for _ in range(40)]
    points += [(rng.uniform(27.6, 29.4), rng.uniform(-18.2, -13.4), rng.uniform(0.0, 3700.0)) for _ in range(10)]
    for lat, lon, h in points:
        x, y, z = forward.transform(lon, lat, h)
        lines.append(",".join(fmt(v) for v in (lat, lon, h, x, y, z)))
    lon, lat, h = inverse.transform(*AVL13M_ECEF)
    lines.append(",".join(fmt(v) for v in (lat, lon, h, *AVL13M_ECEF)))
    return lines


def baseline_vectors(rng: random.Random) -> list[str]:
    """S015/RF-14: |ECEF(rover) − ECEF(base)| con las geocéntricas de pyproj."""
    lines = header("Línea base: distancia en línea recta entre las geocéntricas del rover y de la base") + [
        "latitud_rover,longitud_rover,altura_rover,latitud_base,longitud_base,altura_base,linea_base"
    ]
    forward, inverse = geocentric()
    geod = pyproj.Geod(ellps="GRS80")
    base_lon, base_lat, base_h = inverse.transform(*AVL13M_ECEF)
    bases = [(base_lat, base_lon, base_h), (40.4166667, -3.7, 700.0), (28.4636, -16.2518, 35.0)]
    for lat_b, lon_b, h_b in bases:
        xb, yb, zb = forward.transform(lon_b, lat_b, h_b)
        for distance in (0.0, 0.25, 300.0, 5_000.0, 19_999.0, 20_001.0, 80_000.0):
            azimuth = rng.uniform(0.0, 360.0)
            lon_r, lat_r, _ = geod.fwd(lon_b, lat_b, azimuth, distance)
            # Desnivel de hasta 200 m en las largas y de hasta 2 m en las cortas, para que no tape la distancia.
            h_r = h_b + rng.uniform(-200.0, 200.0) if distance >= 1_000.0 else h_b + rng.uniform(-2.0, 2.0) if distance > 0 else h_b
            xr, yr, zr = forward.transform(lon_r, lat_r, h_r)
            d = ((xr - xb) ** 2 + (yr - yb) ** 2 + (zr - zb) ** 2) ** 0.5
            lines.append(",".join(fmt(v) for v in (lat_r, lon_r, h_r, lat_b, lon_b, h_b, d)))
    return lines


def topocentric(lat: float, lon: float, h: float) -> Transformer:
    """Geográficas GRS80 (grados) → ENU en el plano tangente local de (φ, λ, h), con las conversiones de PROJ."""
    return Transformer.from_pipeline(
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=cart +ellps=GRS80 "
        f"+step +proj=topocentric +ellps=GRS80 +lat_0={lat!r} +lon_0={lon!r} +h_0={h!r}"
    )


def pole_tip_vectors(rng: random.Random) -> list[str]:
    """S016/RF-13: la punta está en Δ = −H·(sen θ·sen α, sen θ·cos α, cos θ) desde la antena (ETC_rev_3)."""
    lines = header("Punta del jalón: antena desplazada en su plano tangente local según la inclinación (ETC_rev_3)") + [
        "latitud,longitud,altura_elipsoidal,inclinacion,direccion,altura_antena,latitud_punta,longitud_punta,altura_punta"
    ]
    places = [(40.4168, -3.7038, 650.123), (28.27223, -16.64251, 3000.0), (39.5696, 2.6502, 12.0), (43.3623, -8.4115, 0.0)]
    # Casos fijos: vertical, 60°, los cuatro cuadrantes, casi 360° y las alturas de antena extremas.
    cases = [(0.0, 0.0, 1.934), (60.0, 0.0, 1.934), (15.0, 90.0, 1.934), (30.0, 180.0, 1.934), (45.0, 270.0, 1.934)]
    cases += [(5.0, 359.9, 1.934), (12.0, 210.5, 0.134), (25.0, 33.3, 10.134), (0.0, 123.4, 10.134)]
    rows = [(lat, lon, h, tilt, direction, antenna) for lat, lon, h in places for tilt, direction, antenna in cases]
    for _ in range(40):
        rows.append((rng.uniform(36.0, 43.8), rng.uniform(-9.3, 4.3), rng.uniform(-50.0, 3000.0),
                     rng.uniform(0.0, 60.0), rng.uniform(0.0, 360.0), rng.uniform(0.134, 10.134)))
    for _ in range(20):
        rows.append((rng.uniform(27.6, 29.4), rng.uniform(-18.2, -13.4), rng.uniform(0.0, 3000.0),
                     rng.uniform(0.0, 60.0), rng.uniform(0.0, 360.0), rng.uniform(0.134, 10.134)))
    for lat, lon, h, tilt, direction, antenna in rows:
        theta, alpha = math.radians(tilt), math.radians(direction)
        east = -antenna * math.sin(theta) * math.sin(alpha)
        north = -antenna * math.sin(theta) * math.cos(alpha)
        up = -antenna * math.cos(theta)
        lon_t, lat_t, h_t = topocentric(lat, lon, h).transform(east, north, up, direction=TransformDirection.INVERSE)
        lines.append(",".join(fmt(v) for v in (lat, lon, h, tilt, direction, antenna, lat_t, lon_t, h_t)))
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
        # S009.
        ("circulo_precision.csv", circle_vectors(random.Random(20261022))),
        ("web_mercator.csv", mercator_vectors(random.Random(20261023))),
        # S011.
        ("puntos_manuales.csv", manual_vectors(random.Random(20261024))),
        ("jalon_corregido.csv", pole_vectors(random.Random(20261025))),
        # S015.
        ("ecef.csv", ecef_vectors(random.Random(20261026))),
        ("linea_base.csv", baseline_vectors(random.Random(20261027))),
        # S016.
        ("punta_jalon.csv", pole_tip_vectors(random.Random(20261028))),
    )
    for name, lines in outputs:
        (OUT / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {len(lines) - 3} vectores")
    return 0


if __name__ == "__main__":
    sys.exit(main())
