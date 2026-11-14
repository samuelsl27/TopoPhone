"""Teselas de una caja y cajas de círculos y extensiones, calculadas aparte de la app (S037).

Genera en fixtures/mapas_offline/:

- teselas.csv: por caso (una caja, una fuente y un rango de zoom del mapa) y nivel de tesela, las columnas y filas de teselas que cubren la caja y su
  número. 3 lugares (Madrid, Santa Cruz de Tenerife y Palma) × 4 cajas (radios de 500 m y 5 km, un encuadre y todo un proyecto con margen) × 5
  fuentes de teselas (PNOA, WMS, IGN vectorial, relieve del IGN y OpenFreeMap) × 6 rangos de zoom, dos de ellos con zoom fraccionario.
- medidos.csv: lo que contó MapLibre 13.6.1 en el emulador en T1 (completedTileCount de cada región); el script comprueba que su propio cálculo da lo mismo.
- esquinas.csv: la caja en EPSG:3857 de teselas sueltas, con fórmulas de Web Mercator y con pyproj (4326 → 3857); el script comprueba que no difieren de 1 mm.
- cajas.csv: la caja exacta de círculos de 100 m a 20 km (los extremos de 3.600 puntos de la circunferencia con pyproj.Geod) y de rectángulos con margen
  (los extremos de los círculos de las cuatro esquinas), con la que se comparan las cajas de la app (superconjunto a menos de un 2 %).

Cómo se calcula (otro algoritmo que el del móvil, para que la coincidencia no sea una tautología):
- Nivel de tesela de un zoom `z` del mapa: `z + log2(512 / tamaño)`, con `round` (la mitad hacia arriba, como el `::round` de C++) en las ráster y `floor` en las
  vectoriales, acotado al `minzoom` y `maxzoom` de la fuente; los niveles son todos los enteros entre el de `minZoom` y el de `maxZoom`. Es lo que MapLibre
  hace (medido en T1, plan de S037, «Resultados del spike»).
- Tesela de un punto: `x = (lon + 180) / 360 · 2ⁿ`, `y = (1 − asinh(tan φ) / π) / 2 · 2ⁿ`; la caja cubre de `floor(x oeste)` a `floor(x este)` y de `floor(y norte)` a
  `floor(y sur)`. Ningún caso cae en el borde exacto de una tesela (el script lo comprueba).
- Las cajas de la app son un superconjunto de las exactas: el círculo se calcula con el problema geodésico directo de GRS80 cada 0,1°.

Uso: uv run --project tools tools/mapas_offline/generar_teselas.py
"""

from __future__ import annotations

import csv
import io
import math
import sys
from dataclasses import dataclass
from pathlib import Path

from pyproj import Geod, Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "mapas_offline"

RADIUS_M = 6378137.0
WORLD_M = 2 * math.pi * RADIUS_M
TO_3857 = Transformer.from_crs(4326, 3857, always_xy=True)
GEOD = Geod(ellps="GRS80")
CIRCLE_POINTS = 3600


@dataclass(frozen=True)
class Source:
    name: str
    kind: str  # raster | vector
    tile_size: int
    min_zoom: int
    max_zoom: int


SOURCES = [
    Source("pnoa", "raster", 256, 0, 19),
    Source("wms", "raster", 256, 0, 22),
    Source("ign-vector", "vector", 512, 0, 17),
    Source("ign-relieve", "raster", 512, 5, 15),
    Source("ofm", "vector", 512, 0, 14),
]

ZOOM_RANGES = [(10.0, 13.0), (14.0, 16.0), (15.0, 17.0), (16.2, 18.5), (18.0, 20.0), (20.0, 22.0)]

PLACES = {
    "madrid": (40.416775, -3.703790),
    "tenerife": (28.463670, -16.251850),
    "palma": (39.569600, 2.650200),
}

# Lo que contó MapLibre en el emulador en T1: caso, fuente, S, W, N, E, zoom mínimo, zoom máximo, teselas.
MEASURED = [
    ("pnoa-sol", "pnoa", 40.4160, -3.7050, 40.4180, -3.7020, 15, 17, 17),
    ("wms-sol", "wms", 40.4160, -3.7050, 40.4180, -3.7020, 15, 17, 17),
    ("pnoa-450m", "pnoa", 40.4150, -3.7065, 40.4190, -3.7005, 15, 17, 38),
    ("ign-vector-sol", "ign-vector", 40.4160, -3.7050, 40.4180, -3.7020, 15, 17, 10),
    ("ign-relieve-sol", "ign-relieve", 40.4160, -3.7050, 40.4180, -3.7020, 15, 17, 2),
    ("ofm-sol", "ofm", 40.4160, -3.7050, 40.4180, -3.7020, 12, 17, 3),
    ("raster-92", "pnoa", 40.40, -3.72, 40.43, -3.68, 14, 15, 92),
    ("raster-3849", "pnoa", 40.36, -3.78, 40.46, -3.62, 14, 16, 3849),
]


def covering_level(zoom: float, source: Source) -> int:
    value = zoom + math.log2(512 / source.tile_size)
    return math.floor(value + 0.5) if source.kind == "raster" else math.floor(value)


def tile_levels(zoom_min: float, zoom_max: float, source: Source) -> list[int]:
    low = max(covering_level(zoom_min, source), source.min_zoom)
    high = min(covering_level(zoom_max, source), source.max_zoom)
    return list(range(low, high + 1))


def tile_x(lon: float, level: int) -> float:
    return (lon + 180.0) / 360.0 * 2**level


def tile_y(lat: float, level: int) -> float:
    return (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * 2**level


def not_on_boundary(value: float) -> bool:
    return abs(value - round(value)) > 1e-7


def cover(south: float, west: float, north: float, east: float, level: int) -> tuple[int, int, int, int]:
    xs = (tile_x(west, level), tile_x(east, level))
    ys = (tile_y(north, level), tile_y(south, level))
    assert all(not_on_boundary(v) for v in (*xs, *ys)), (south, west, north, east, level)
    last = 2**level - 1
    return (
        max(0, math.floor(xs[0])),
        min(last, math.floor(xs[1])),
        max(0, math.floor(ys[0])),
        min(last, math.floor(ys[1])),
    )


def count(south: float, west: float, north: float, east: float, zoom_min: float, zoom_max: float, source: Source) -> int:
    total = 0
    for level in tile_levels(zoom_min, zoom_max, source):
        x0, x1, y0, y1 = cover(south, west, north, east, level)
        total += (x1 - x0 + 1) * (y1 - y0 + 1)
    return total


def circle_points(lat: float, lon: float, radius_m: float) -> list[tuple[float, float]]:
    out = []
    for i in range(CIRCLE_POINTS):
        lon2, lat2, _ = GEOD.fwd(lon, lat, i * 360.0 / CIRCLE_POINTS, radius_m)
        out.append((lat2, lon2))
    return out


def exact_circle_box(lat: float, lon: float, radius_m: float) -> tuple[float, float, float, float]:
    points = circle_points(lat, lon, radius_m)
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    return min(lats), min(lons), max(lats), max(lons)


def exact_extent_box(south: float, west: float, north: float, east: float, margin_m: float) -> tuple[float, float, float, float]:
    boxes = [exact_circle_box(lat, lon, margin_m) for lat in (south, north) for lon in (west, east)]
    return min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)


def boxes_of(place: str) -> dict[str, tuple[float, float, float, float]]:
    lat, lon = PLACES[place]
    return {
        "radio500": exact_circle_box(lat, lon, 500.0),
        "radio5000": exact_circle_box(lat, lon, 5000.0),
        "encuadre": (lat - 0.0013, lon - 0.0031, lat + 0.0017, lon + 0.0029),
        "proyecto": exact_extent_box(lat - 0.004, lon - 0.006, lat + 0.003, lon + 0.002, 500.0),
    }


def tile_box_3857(level: int, x: int, y: int) -> tuple[float, float, float, float]:
    size = WORLD_M / 2**level
    return (-WORLD_M / 2 + x * size, WORLD_M / 2 - (y + 1) * size, -WORLD_M / 2 + (x + 1) * size, WORLD_M / 2 - y * size)


def tile_box_pyproj(level: int, x: int, y: int) -> tuple[float, float, float, float]:
    def corner(cx: int, cy: int) -> tuple[float, float]:
        lon = cx / 2**level * 360.0 - 180.0
        lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * cy / 2**level))))
        return TO_3857.transform(lon, lat)

    min_x, max_y = corner(x, y)
    max_x, min_y = corner(x + 1, y + 1)
    return min_x, min_y, max_x, max_y


def fmt(value: float) -> str:
    return repr(float(value))


def teselas_csv() -> str:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        ["case", "tile_kind", "tile_size", "source_min", "source_max", "map_zoom_min", "map_zoom_max", "south", "west", "north", "east"]
        + ["tile_zoom", "x_min", "x_max", "y_min", "y_max", "count"]
    )
    for place in PLACES:
        for box_name, (s, w, n, e) in boxes_of(place).items():
            for source in SOURCES:
                for zoom_min, zoom_max in ZOOM_RANGES:
                    case = f"{place}-{box_name}-{source.name}-{zoom_min:g}-{zoom_max:g}"
                    for level in tile_levels(zoom_min, zoom_max, source):
                        x0, x1, y0, y1 = cover(s, w, n, e, level)
                        writer.writerow(
                            [case, source.kind, source.tile_size, source.min_zoom, source.max_zoom, fmt(zoom_min), fmt(zoom_max)]
                            + [fmt(s), fmt(w), fmt(n), fmt(e), level, x0, x1, y0, y1, (x1 - x0 + 1) * (y1 - y0 + 1)]
                        )
    return buffer.getvalue()


def medidos_csv() -> str:
    by_name = {s.name: s for s in SOURCES}
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["case", "source", "tile_kind", "tile_size", "source_min", "source_max", "south", "west", "north", "east"] + ["map_zoom_min", "map_zoom_max", "tiles"])
    for case, name, s, w, n, e, zoom_min, zoom_max, expected in MEASURED:
        source = by_name[name]
        mine = count(s, w, n, e, zoom_min, zoom_max, source)
        assert mine == expected, f"{case}: MapLibre contó {expected} y el cálculo da {mine}"
        writer.writerow(
            [case, name, source.kind, source.tile_size, source.min_zoom, source.max_zoom, fmt(s), fmt(w), fmt(n), fmt(e)] + [zoom_min, zoom_max, expected]
        )
    return buffer.getvalue()


def esquinas_csv() -> str:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["tile_zoom", "x", "y", "min_x", "min_y", "max_x", "max_y"])
    for place, (lat, lon) in PLACES.items():
        for level in (0, 5, 10, 14, 17, 19, 22):
            x, y = math.floor(tile_x(lon, level)), math.floor(tile_y(lat, level))
            for dx, dy in ((0, 0), (1, 0), (0, 1)):
                tx, ty = min(x + dx, 2**level - 1), min(y + dy, 2**level - 1)
                mine = tile_box_3857(level, tx, ty)
                theirs = tile_box_pyproj(level, tx, ty)
                worst = max(abs(a - b) for a, b in zip(mine, theirs))
                assert worst < 0.001, f"{place} {level}/{tx}/{ty}: {worst} m de diferencia con pyproj"
                writer.writerow([level, tx, ty] + [f"{v:.6f}" for v in mine])
    return buffer.getvalue()


def cajas_csv() -> str:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["case", "kind", "lat", "lon", "radius_m", "base_south", "base_west", "base_north", "base_east", "margin_m"] + ["south", "west", "north", "east"])
    for place, (lat, lon) in PLACES.items():
        for radius in (100.0, 500.0, 5000.0, 20000.0):
            s, w, n, e = exact_circle_box(lat, lon, radius)
            writer.writerow([f"{place}-radio{radius:g}", "radius", fmt(lat), fmt(lon), fmt(radius), "", "", "", "", ""] + [fmt(s), fmt(w), fmt(n), fmt(e)])
        for margin in (200.0, 500.0, 2000.0):
            base = (lat - 0.004, lon - 0.006, lat + 0.003, lon + 0.002)
            s, w, n, e = exact_extent_box(*base, margin)
            writer.writerow(
                [f"{place}-proyecto{margin:g}", "project", "", "", ""] + [fmt(v) for v in base] + [fmt(margin)] + [fmt(s), fmt(w), fmt(n), fmt(e)]
            )
        point = (lat, lon, lat, lon)
        s, w, n, e = exact_extent_box(*point, 200.0)
        writer.writerow([f"{place}-un-punto", "project", "", "", ""] + [fmt(v) for v in point] + [fmt(200.0)] + [fmt(s), fmt(w), fmt(n), fmt(e)])
    return buffer.getvalue()


def all_files() -> dict[str, str]:
    return {
        "teselas.csv": teselas_csv(),
        "medidos.csv": medidos_csv(),
        "esquinas.csv": esquinas_csv(),
        "cajas.csv": cajas_csv(),
    }


def main(out: Path = OUT) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_bytes(text.encode("utf-8"))
        print(f"{out / name}: {len(text.splitlines()) - 1} filas")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else OUT)
