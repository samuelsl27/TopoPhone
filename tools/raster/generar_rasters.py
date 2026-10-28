"""Rásteres de cotas de prueba y sus valores esperados calculados aparte (S045).

Genera en fixtures/raster/:

- GeoTIFF de 40 × 36 celdas de 10 m en ETRS89 / UTM 30 (EPSG:25830) con una cota que no es un plano y un hueco sin dato de 2 × 2
  celdas, escritos por un escritor TIFF de este fichero (Python puro: struct y zlib, más un codificador LZW propio): sin comprimir,
  LZW y Deflate, con y sin predictor (2 en enteros y 3 en coma flotante), por tiras y por teselas de 16 × 16 (con relleno en el borde),
  de uint16, int16, int32, float32 y float64, con la celda como área y como punto, con la cota en pies, con dos bandas.
- El mismo terreno en ETRS89 / UTM 29 y en ETRS89 geográficas, y un ráster en el norte de la Península sin geoide.
- Rechazos: BigTIFF, girado, de color, con una compresión que no se lee, sin georreferencia, sin ninguna celda con dato, un texto que
  no es un ráster y un fichero vacío.
- ASCII Grid con la esquina y con el centro, sin NODATA_value (-9999 por defecto) y con valores que no son un número.
- ficheros.csv (qué es cada fichero y lo que debe decir el escaneo), celdas.csv (el valor de algunas celdas) y consultas.csv (la cota
  esperada en el sistema del proyecto, UTM 30, y en el sistema de alturas del proyecto).

La cota esperada sale de otro cálculo que el de la app: la posición pasa al sistema del ráster con pyproj, se interpola
bilinealmente entre los cuatro centros de celda con una regla propia y la altura cambia de sistema con la ondulación del geoide que
da la rejilla de la app con PROJ (vgridshift).

Uso: uv run --project tools tools/raster/generar_rasters.py
"""

from __future__ import annotations

import math
import struct
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path

from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "raster"
sys.path.insert(0, str(ROOT / "tools" / "geodesia"))
import generar_vectores  # noqa: E402

FOOT_M = 0.3048
COLS, ROWS, STEP = 40, 36, 10.0
XC0, YC0 = 440005.0, 4474005.0  # el centro de la celda (0, 0): arriba a la izquierda, UTM 30
HOLE = {(10, 8), (11, 8), (10, 9), (11, 9)}
NODATA = -9999.0
EPS_M = 0.001  # la tolerancia de borde: 1 mm sobre el terreno
TO_UTM29 = Transformer.from_crs("EPSG:25830", "EPSG:25829", always_xy=True)
FROM_UTM29 = Transformer.from_crs("EPSG:25829", "EPSG:25830", always_xy=True)
UTM30_TO_GEO = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)
GEO_TO_UTM30 = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
GEOID = generar_vectores.geoid_transformer()

# tipo → (formato struct, bytes, SampleFormat, bits)
DTYPES = {"f32": ("f", 4, 3, 32), "f64": ("d", 8, 3, 64), "i16": ("h", 2, 2, 16), "i32": ("i", 4, 2, 32), "u16": ("H", 2, 1, 16)}
NO_COMPRESSION, LZW, DEFLATE, JPEG = 1, 5, 8, 7


def terrain(i: int, j: int) -> float:
    """La cota del terreno en metros: una pendiente con una ondulación, para que no sea un plano."""
    return round(600.0 + 0.5 * i + 0.8 * j + 1.5 * math.sin(i / 3.0) * math.cos(j / 4.0), 3)


@dataclass
class Raster:
    """Un ráster de cotas: el centro de la celda (0, 0), el paso (siempre al sur en Y) y sus valores (NaN = sin dato)."""

    name: str
    epsg: int
    cols: int
    rows: int
    x0: float
    y0: float
    step_x: float
    step_y: float
    dtype: str = "f32"
    compression: int = NO_COMPRESSION
    predictor: int = 1
    tiles: int = 0  # 0 = tiras; si no, el lado de la tesela
    strip_rows: int = 7
    pixel_is_point: bool = False
    nodata_nan: bool = False
    unit: str = "m"  # m, ft
    bands: int = 1
    values: list[float] = field(default_factory=list)  # filas seguidas; ya redondeados al tipo del fichero

    @property
    def geographic(self) -> bool:
        return self.epsg == 4258

    def value(self, i: int, j: int) -> float:
        return self.values[j * self.cols + i]

    def valid(self, i: int, j: int) -> bool:
        return not math.isnan(self.value(i, j))


def stored(dtype: str, value: float) -> float:
    """El valor tal como queda en el fichero: float32 se redondea, los enteros a metros enteros."""
    if dtype == "f32":
        return struct.unpack("<f", struct.pack("<f", value))[0]
    if dtype == "f64":
        return value
    return float(round(value))


def fill(r: Raster, hole: set[tuple[int, int]]) -> Raster:
    r.values = [math.nan if (i, j) in hole else stored(r.dtype, terrain(i, j)) for j in range(r.rows) for i in range(r.cols)]
    return r


# --------------------------------------------------------------------------------------------------------------------
# Escritor TIFF
# --------------------------------------------------------------------------------------------------------------------


def lzw_encode(data: bytes) -> bytes:
    """LZW de TIFF: códigos de 9 a 12 bits de más a menos significativo, con cambio de anchura temprano (como libtiff)."""
    out = bytearray()
    acc, nbits = 0, 0

    def emit(code: int, width: int) -> None:
        nonlocal acc, nbits
        acc = (acc << width) | code
        nbits += width
        while nbits >= 8:
            nbits -= 8
            out.append((acc >> nbits) & 0xFF)
        acc &= (1 << nbits) - 1

    clear, eoi = 256, 257
    table: dict[bytes, int] = {bytes([i]): i for i in range(256)}
    next_code, width = 258, 9
    emit(clear, width)
    current = b""
    for byte in data:
        candidate = current + bytes([byte])
        if candidate in table:
            current = candidate
            continue
        emit(table[current], width)
        table[candidate] = next_code
        next_code += 1
        if next_code == 4094:
            emit(clear, width)
            table = {bytes([i]): i for i in range(256)}
            next_code, width = 258, 9
        elif next_code > (1 << width) - 1:
            width += 1
        current = bytes([byte])
    if current:
        emit(table[current], width)
        next_code += 1
        if next_code > (1 << width) - 1 and width < 12:
            width += 1
    emit(eoi, width)
    if nbits:
        emit(0, 8 - nbits)
    return bytes(out)


def block_bytes(r: Raster, x0: int, y0: int, width: int, height: int, band: int = 0) -> bytes:
    """Las celdas de un bloque (tira o tesela, con relleno de ceros fuera del ráster), con el predictor y la compresión."""
    fmt, size, _, bits = DTYPES[r.dtype]
    nodata = math.nan if r.nodata_nan else (NODATA if r.dtype != "u16" else 65535.0)
    rows_bytes = []
    for y in range(y0, y0 + height):
        samples = []
        for x in range(x0, x0 + width):
            if x < r.cols and y < r.rows:
                v = r.value(x, y)
                v = nodata if math.isnan(v) else v
                if band:
                    v += 1000.0
            else:
                v = 0.0
            samples.append(v)
        rows_bytes.append(predict(r, samples, fmt, size, bits))
    raw = b"".join(rows_bytes)
    if r.compression == LZW:
        return lzw_encode(raw)
    if r.compression == DEFLATE:
        return zlib.compress(raw)
    return raw


def predict(r: Raster, samples: list[float], fmt: str, size: int, bits: int) -> bytes:
    if r.predictor == 2:
        ints = [int(v) & ((1 << bits) - 1) for v in samples]
        diffs = [ints[0]] + [(ints[k] - ints[k - 1]) & ((1 << bits) - 1) for k in range(1, len(ints))]
        unsigned = {16: "H", 32: "I"}[bits]
        return struct.pack("<" + unsigned * len(diffs), *diffs)
    if r.predictor == 3:
        be = [struct.pack(">" + fmt, v) for v in samples]
        planes = bytearray(b"".join(s[b : b + 1] for b in range(size) for s in be))
        for k in range(len(planes) - 1, 0, -1):
            planes[k] = (planes[k] - planes[k - 1]) & 0xFF
        return bytes(planes)
    if r.dtype in ("i16", "i32"):
        return struct.pack("<" + fmt * len(samples), *[int(v) for v in samples])
    if r.dtype == "u16":
        return struct.pack("<" + fmt * len(samples), *[int(v) for v in samples])
    return struct.pack("<" + fmt * len(samples), *samples)


class Ifd:
    """Una IFD de TIFF clásico, little endian, con los datos grandes tras la cabecera."""

    def __init__(self) -> None:
        self.body = bytearray(b"II*\x00\x00\x00\x00\x00")  # el desplazamiento de la IFD se fija al final
        self.entries: list[tuple[int, int, int, bytes]] = []

    def append(self, data: bytes) -> int:
        if len(self.body) % 2:
            self.body.append(0)
        offset = len(self.body)
        self.body += data
        return offset

    def add(self, tag: int, kind: int, values: list, raw: bytes | None = None) -> None:
        size = {2: 1, 3: 2, 4: 4, 12: 8}[kind]
        fmt = {2: "B", 3: "H", 4: "I", 12: "d"}[kind]
        data = raw if raw is not None else struct.pack("<" + fmt * len(values), *values)
        count = len(data) // size
        self.entries.append((tag, kind, count, data))

    def build(self) -> bytes:
        packed = []
        for tag, kind, count, data in sorted(self.entries):
            if len(data) <= 4:
                value = data + b"\x00" * (4 - len(data))
            else:
                value = struct.pack("<I", self.append(data))
            packed.append(struct.pack("<HHI", tag, kind, count) + value)
        if len(self.body) % 2:
            self.body.append(0)
        ifd_offset = len(self.body)
        self.body += struct.pack("<H", len(packed)) + b"".join(packed) + b"\x00\x00\x00\x00"
        struct.pack_into("<I", self.body, 4, ifd_offset)
        return bytes(self.body)


def geo_keys(r: Raster, vertical_units: int | None) -> list[int]:
    keys = [(1024, 2 if r.geographic else 1), (1025, 2 if r.pixel_is_point else 1)]
    keys.append((2048, r.epsg) if r.geographic else (3072, r.epsg))
    if vertical_units:
        keys.append((4099, vertical_units))
    out = [1, 1, 0, len(keys)]
    for key, value in keys:
        out += [key, 0, 1, value]
    return out


def write_geotiff(r: Raster, georef: str = "scale") -> bytes:
    fmt, size, sample_format, bits = DTYPES[r.dtype]
    ifd = Ifd()
    blocks: list[bytes] = []
    if r.tiles:
        t = r.tiles
        across, down = -(-r.cols // t), -(-r.rows // t)
        for ty in range(down):
            for tx in range(across):
                blocks.append(block_bytes(r, tx * t, ty * t, t, t))
    else:
        for y in range(0, r.rows, r.strip_rows):
            blocks.append(block_bytes(r, 0, y, r.cols, min(r.strip_rows, r.rows - y)))
        if r.bands == 2:
            # Dos bandas entrelazadas: se reescribe cada tira con las dos muestras de cada celda.
            blocks = []
            for y in range(0, r.rows, r.strip_rows):
                rows = min(r.strip_rows, r.rows - y)
                first = struct.unpack("<" + fmt * (rows * r.cols), block_bytes(r, 0, y, r.cols, rows))
                second = struct.unpack("<" + fmt * (rows * r.cols), block_bytes(r, 0, y, r.cols, rows, band=1))
                blocks.append(struct.pack("<" + fmt * (2 * rows * r.cols), *[v for pair in zip(first, second) for v in pair]))
    offsets = [ifd.append(b) for b in blocks]
    counts = [len(b) for b in blocks]

    ifd.add(256, 4, [r.cols])
    ifd.add(257, 4, [r.rows])
    ifd.add(258, 3, [bits] * r.bands)
    ifd.add(259, 3, [r.compression])
    ifd.add(262, 3, [1])
    ifd.add(277, 3, [r.bands])
    ifd.add(284, 3, [1])
    if r.predictor != 1:
        ifd.add(317, 3, [r.predictor])
    ifd.add(339, 3, [sample_format] * r.bands)
    if r.tiles:
        ifd.add(322, 4, [r.tiles])
        ifd.add(323, 4, [r.tiles])
        ifd.add(324, 4, offsets)
        ifd.add(325, 4, counts)
    else:
        ifd.add(278, 4, [r.strip_rows])
        ifd.add(273, 4, offsets)
        ifd.add(279, 4, counts)
    area_shift_x = 0.0 if r.pixel_is_point else -r.step_x / 2
    area_shift_y = 0.0 if r.pixel_is_point else r.step_y / 2
    if georef == "scale":
        ifd.add(33550, 12, [r.step_x, r.step_y, 0.0])
        ifd.add(33922, 12, [0.0, 0.0, 0.0, r.x0 + area_shift_x, r.y0 + area_shift_y, 0.0])
        vertical = {"m": None, "ft": 9002}[r.unit]
        ifd.add(34735, 3, geo_keys(r, vertical))
    elif georef == "rotated":
        c, s = math.cos(math.radians(10)), math.sin(math.radians(10))
        ifd.add(34264, 12, [r.step_x * c, r.step_y * s, 0.0, r.x0, r.step_x * s, -r.step_y * c, 0.0, r.y0, 0, 0, 0, 0, 0, 0, 0, 1])
        ifd.add(34735, 3, geo_keys(r, None))
    nodata = "nan" if r.nodata_nan else ("65535" if r.dtype == "u16" else "-9999")
    ifd.add(42113, 2, [], raw=nodata.encode("ascii") + b"\x00")
    return ifd.build()


def write_ascii(r: Raster, corner: bool, default_nodata: bool = False, odd: dict[tuple[int, int], str] | None = None) -> bytes:
    half = r.step_x / 2
    header = [f"ncols {r.cols}", f"nrows {r.rows}"]
    low_x, low_y = r.x0, r.y0 - (r.rows - 1) * r.step_y
    if corner:
        header += [f"xllcorner {low_x - half!r}", f"yllcorner {low_y - half!r}"]
    else:
        header += [f"xllcenter {low_x!r}", f"yllcenter {low_y!r}"]
    header.append(f"cellsize {r.step_x!r}")
    if not default_nodata:
        header.append("NODATA_value -9999")
    lines = list(header)
    for j in range(r.rows):
        cells = []
        for i in range(r.cols):
            if odd and (i, j) in odd:
                cells.append(odd[(i, j)])
            elif r.valid(i, j):
                cells.append(repr(r.value(i, j)))
            else:
                cells.append("-9999")
        lines.append(" ".join(cells))
    return ("\n".join(lines) + "\n").encode("ascii")


def write_bigtiff() -> bytes:
    return b"II+\x00\x08\x00\x00\x00" + struct.pack("<Q", 16) + struct.pack("<Q", 0) + struct.pack("<Q", 0)


# --------------------------------------------------------------------------------------------------------------------
# Los rásteres y los ficheros
# --------------------------------------------------------------------------------------------------------------------


def base(name: str, **kw) -> Raster:
    return fill(Raster(name, 25830, COLS, ROWS, XC0, YC0, STEP, STEP, **kw), HOLE)


def utm29_raster() -> Raster:
    e, n = TO_UTM29.transform(XC0 - 250, YC0 + 250)
    r = Raster("f32-utm29.tif", 25829, 80, 80, round(e), round(n), 10.0, 10.0, dtype="f32")
    # El hueco: 3 × 3 celdas alrededor de la celda que contiene el punto (20, 18) del terreno base.
    gx, gy = ground(20.0, 18.0)
    ci, cj = grid_cell(r, *TO_UTM29.transform(gx, gy))
    r.values = [
        math.nan if abs(i - ci) <= 1 and abs(j - cj) <= 1 else stored("f32", terrain(i % COLS, j % ROWS)) for j in range(r.rows) for i in range(r.cols)
    ]
    return r


def geographic_raster() -> Raster:
    lon, lat = UTM30_TO_GEO.transform(XC0 - 150, YC0 + 150)
    r = Raster("f32-geograficas.tif", 4258, 80, 60, round(lon, 4), round(lat, 4), 0.0001, 0.0001, dtype="f32")
    gx, gy = ground(20.0, 18.0)
    ci, cj = grid_cell(r, *UTM30_TO_GEO.transform(gx, gy))
    r.values = [
        math.nan if abs(i - ci) <= 1 and abs(j - cj) <= 1 else stored("f32", terrain(i % COLS, j % ROWS)) for j in range(r.rows) for i in range(r.cols)
    ]
    return r


def grid_cell(r: Raster, x: float, y: float) -> tuple[int, int]:
    return round((x - r.x0) / r.step_x), round((r.y0 - y) / r.step_y)


def no_geoid_raster() -> Raster:
    lon, lat = -3.0, 44.3
    x, y = GEO_TO_UTM30.transform(lon, lat)
    r = Raster("f32-sin-geoide.tif", 25830, 10, 10, round(x), round(y), 10.0, 10.0, dtype="f32")
    r.values = [stored("f32", terrain(i, j)) for j in range(10) for i in range(10)]
    return r


def ground(gx: float, gy: float) -> tuple[float, float]:
    """Una posición del terreno base (en fracciones de celda desde el centro de la celda 0, 0) en UTM 30."""
    return XC0 + gx * STEP, YC0 - gy * STEP


def geotiff_rasters() -> list[Raster]:
    return [
        base("f32-sin-tiras-area.tif", dtype="f32", strip_rows=7),
        base("f32-lzw-teselas-area.tif", dtype="f32", compression=LZW, tiles=16, nodata_nan=True),
        base("f32-lzw-pred3-tiras.tif", dtype="f32", compression=LZW, predictor=3, strip_rows=5),
        base("f32-deflate-pred3-teselas.tif", dtype="f32", compression=DEFLATE, predictor=3, tiles=16),
        base("i16-sin-tiras.tif", dtype="i16", strip_rows=4),
        base("i16-lzw-pred2-tiras.tif", dtype="i16", compression=LZW, predictor=2, strip_rows=9),
        base("i32-deflate-pred2-teselas.tif", dtype="i32", compression=DEFLATE, predictor=2, tiles=16),
        base("u16-sin-tiras.tif", dtype="u16", strip_rows=36),
        base("f64-sin-tiras-punto.tif", dtype="f64", pixel_is_point=True),
        base("f64-deflate-pred3-teselas-punto.tif", dtype="f64", compression=DEFLATE, predictor=3, tiles=16, pixel_is_point=True),
        base("f32-pies.tif", dtype="f32", unit="ft"),
        base("f32-multibanda.tif", dtype="f32", bands=2),
        utm29_raster(),
        geographic_raster(),
        no_geoid_raster(),
    ]


def ascii_rasters() -> list[tuple[Raster, bool, bool, dict[tuple[int, int], str] | None]]:
    def grid(name: str) -> Raster:
        r = Raster(name, 25830, COLS, ROWS, XC0, YC0, STEP, STEP, dtype="f64")
        return fill(r, HOLE)

    odd = {(3, 3): "NaN", (4, 3): "abc"}
    raros = grid("valores-raros.asc")
    for (i, j) in odd:
        raros.values[j * COLS + i] = math.nan
    return [
        (grid("esquina.asc"), True, False, None),
        (grid("centro.asc"), False, False, None),
        (grid("sin-dato-defecto.asc"), True, True, None),
        (raros, True, False, odd),
    ]


def rejected_files() -> dict[str, tuple[bytes, str]]:
    small = Raster("pequeno", 25830, 8, 8, XC0, YC0, STEP, STEP)
    small = fill(small, set())
    small.strip_rows = 8
    jpeg = Raster("jpeg", 25830, 8, 8, XC0, YC0, STEP, STEP, compression=JPEG)
    jpeg = fill(jpeg, set())
    jpeg.strip_rows = 8
    alldata = Raster("sin-dato", 25830, 8, 8, XC0, YC0, STEP, STEP)
    alldata.values = [math.nan] * 64
    alldata.strip_rows = 8
    return {
        "bigtiff.tif": (write_bigtiff(), "BIGTIFF"),
        "girado.tif": (write_geotiff(small, georef="rotated"), "ROTATED"),
        "color-rgb.tif": (rgb_color(), "COLOUR"),
        "compresion-jpeg.tif": (write_geotiff(jpeg), "UNSUPPORTED_COMPRESSION"),
        "sin-georreferencia.tif": (write_geotiff(small, georef="none"), "NOT_GEOREFERENCED"),
        "todo-sin-dato.tif": (write_geotiff(alldata), "NO_DATA_CELLS"),
        "no-es-raster.txt": ("Esto no es un ráster: es una nota de texto.\n".encode("utf-8"), "NOT_A_RASTER"),
        "vacio.tif": (b"", "EMPTY"),
    }


def rgb_color() -> bytes:
    """Un TIFF de color: 8 bits y tres bandas entrelazadas, con georreferencia."""
    ifd = Ifd()
    pixels = bytes((x * 30 % 256, y * 30 % 256, 128) [k] for y in range(8) for x in range(8) for k in range(3))
    offset = ifd.append(pixels)
    for tag, kind, values in [
        (256, 4, [8]), (257, 4, [8]), (258, 3, [8, 8, 8]), (259, 3, [1]), (262, 3, [2]), (273, 4, [offset]), (277, 3, [3]),
        (278, 4, [8]), (279, 4, [len(pixels)]), (284, 3, [1]), (339, 3, [1, 1, 1]),
        (33550, 12, [STEP, STEP, 0.0]), (33922, 12, [0.0, 0.0, 0.0, XC0 - STEP / 2, YC0 + STEP / 2, 0.0]),
    ]:
        ifd.add(tag, kind, values)
    ifd.add(34735, 3, [1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 25830])
    return ifd.build()


# --------------------------------------------------------------------------------------------------------------------
# Valores esperados
# --------------------------------------------------------------------------------------------------------------------


def sample(r: Raster, e30: float, n30: float) -> float | None:
    """La cota (en la unidad del fichero) del ráster en una posición de UTM 30: bilineal entre centros de celda; None si no hay."""
    if r.epsg == 25829:
        x, y = TO_UTM29.transform(e30, n30)
        eps = EPS_M / r.step_x
    elif r.geographic:
        x, y = UTM30_TO_GEO.transform(e30, n30)
        eps = EPS_M / (r.step_x * 111320.0 * math.cos(math.radians(y)))
    else:
        x, y = e30, n30
        eps = EPS_M / r.step_x
    gx, gy = (x - r.x0) / r.step_x, (r.y0 - y) / r.step_y
    if gx < -eps or gy < -eps or gx > r.cols - 1 + eps or gy > r.rows - 1 + eps:
        return None
    gx, gy = min(max(gx, 0.0), r.cols - 1.0), min(max(gy, 0.0), r.rows - 1.0)
    i, j = min(int(math.floor(gx)), r.cols - 2), min(int(math.floor(gy)), r.rows - 2)
    fx, fy = gx - i, gy - j
    corners = [r.value(i, j), r.value(i + 1, j), r.value(i, j + 1), r.value(i + 1, j + 1)]
    if any(math.isnan(c) for c in corners):
        return None
    return (1 - fx) * (1 - fy) * corners[0] + fx * (1 - fy) * corners[1] + (1 - fx) * fy * corners[2] + fx * fy * corners[3]


def undulation(e30: float, n30: float) -> float | None:
    lon, lat = UTM30_TO_GEO.transform(e30, n30)
    n = GEOID.transform(lon, lat, 0.0)[2]
    return n if math.isfinite(n) else None


# Posiciones de la rejilla base: (fila de la tabla, fracción de columna, fracción de fila)
BASE_QUERIES = [
    ("centro_celda", 7.0, 9.0),
    ("medio_camino", 7.5, 9.5),
    ("cuarto", 7.25, 9.75),
    ("esquina_noroeste", 0.0, 0.0),
    ("esquina_sureste", COLS - 1.0, ROWS - 1.0),
    ("borde_medio", 0.0, 5.5),
    ("media_celda_borde", -0.5, 5.0),
    ("fuera_lejos", -30.0, 5.0),
    ("junto_al_hueco", 8.5, 8.5),
    ("borde_del_hueco", 9.5, 8.5),
    ("centro_del_hueco", 10.5, 8.5),
    ("borde_medio_milimetro", -0.00005, 5.0),
    ("fuera_dos_milimetros", -0.0002, 5.0),
]
OTHER_QUERIES = [("centro_celda", 7.0, 9.0), ("medio_camino", 7.5, 9.5), ("cuarto", 7.25, 9.75), ("hueco", 20.0, 18.0), ("lejos", 30.3, 25.7),
                 ("fuera_lejos", -30.0, 5.0)]
HEIGHT_QUERIES = [("centro_celda", 7.0, 9.0), ("medio_camino", 7.5, 9.5), ("lejos", 30.3, 25.7)]


def fmt(value: float | None) -> str:
    return "" if value is None else repr(value)


def queries_csv() -> str:
    rows = ["fichero,altura_raster,altura_proyecto,tipo,este,norte,cota_esperada_m"]
    by_name = {r.name: r for r in geotiff_rasters()}
    for r in by_name.values():
        if r.name in ("f32-utm29.tif", "f32-geograficas.tif"):
            queries = OTHER_QUERIES
        elif r.name == "f32-sin-geoide.tif":
            continue
        else:
            queries = BASE_QUERIES
        factor = FOOT_M if r.unit == "ft" else 1.0
        for kind, gx, gy in queries:
            e, n = ground(gx, gy)
            value = sample(r, e, n)
            rows.append(f"{r.name},ORTHOMETRIC,ORTHOMETRIC,{kind},{e!r},{n!r},{fmt(None if value is None else value * factor)}")
    for r, _, _, _ in ascii_rasters():
        for kind, gx, gy in BASE_QUERIES + ([("celda_nan", 3.0, 3.0), ("celda_texto", 4.0, 3.0), ("entre_nan", 3.5, 3.5)] if r.name == "valores-raros.asc" else []):
            e, n = ground(gx, gy)
            value = sample(r, e, n)
            rows.append(f"{r.name},ORTHOMETRIC,ORTHOMETRIC,{kind},{e!r},{n!r},{fmt(value)}")
    reference = by_name["f32-sin-tiras-area.tif"]
    for raster_system, project_system in (("ELLIPSOIDAL", "ORTHOMETRIC"), ("ORTHOMETRIC", "ELLIPSOIDAL")):
        for kind, gx, gy in HEIGHT_QUERIES:
            e, n = ground(gx, gy)
            value = sample(reference, e, n)
            n_geoid = undulation(e, n)
            if value is None or n_geoid is None:
                result = None
            else:
                result = value - n_geoid if raster_system == "ELLIPSOIDAL" else value + n_geoid
            rows.append(f"{reference.name},{raster_system},{project_system},{kind}_geoide,{e!r},{n!r},{fmt(result)}")
    far = by_name["f32-sin-geoide.tif"]
    ex = far.x0 + 4.5 * far.step_x
    ny = far.y0 - 4.5 * far.step_y
    for raster_system, project_system in (("ELLIPSOIDAL", "ORTHOMETRIC"), ("ORTHOMETRIC", "ORTHOMETRIC")):
        value = sample(far, ex, ny)
        n_geoid = undulation(ex, ny)
        if raster_system == "ORTHOMETRIC" and project_system == "ORTHOMETRIC":
            result = value
        else:
            result = None if (value is None or n_geoid is None) else value - n_geoid
        rows.append(f"{far.name},{raster_system},{project_system},medio_camino_geoide,{ex!r},{ny!r},{fmt(result)}")
    return "\n".join(rows) + "\n"


CELLS = [(0, 0), (39, 0), (0, 35), (39, 35), (7, 9), (10, 8), (11, 9), (20, 18), (13, 27), (8, 8)]


def cells_csv() -> str:
    rows = ["fichero,columna,fila,valor"]
    rasters = [r for r in geotiff_rasters() if r.name not in ("f32-utm29.tif", "f32-geograficas.tif", "f32-sin-geoide.tif")]
    rasters += [r for r, _, _, _ in ascii_rasters()]
    for r in rasters:
        for i, j in CELLS:
            rows.append(f"{r.name},{i},{j},{'nodata' if not r.valid(i, j) else repr(r.value(i, j))}")
    for name in ("f32-utm29.tif", "f32-geograficas.tif", "f32-sin-geoide.tif"):
        r = next(x for x in geotiff_rasters() if x.name == name)
        for i, j in [(0, 0), (r.cols - 1, r.rows - 1), (7, 9)]:
            rows.append(f"{r.name},{i},{j},{'nodata' if not r.valid(i, j) else repr(r.value(i, j))}")
    return "\n".join(rows) + "\n"


def files_csv() -> str:
    head = "fichero,formato,columnas,filas,epsg,paso_x,paso_y,centro_x,centro_y,tipo,compresion,predictor,disposicion,celda,sin_dato,unidad,bandas,resultado,con_dato,sin_dato_celdas,cota_min,cota_max"
    rows = [head]
    for r in geotiff_rasters():
        valid = [v for v in r.values if not math.isnan(v)]
        layout = f"teselas{r.tiles}" if r.tiles else "tiras"
        sin = "nan" if r.nodata_nan else ("65535" if r.dtype == "u16" else "-9999")
        rows.append(
            f"{r.name},geotiff,{r.cols},{r.rows},{r.epsg},{r.step_x!r},{r.step_y!r},{r.x0!r},{r.y0!r},{r.dtype},{r.compression},{r.predictor},{layout},"
            f"{'punto' if r.pixel_is_point else 'area'},{sin},{r.unit},{r.bands},OK,{len(valid)},{len(r.values) - len(valid)},{min(valid)!r},{max(valid)!r}"
        )
    for r, corner, default, _ in ascii_rasters():
        valid = [v for v in r.values if not math.isnan(v)]
        rows.append(
            f"{r.name},ascii,{r.cols},{r.rows},,{r.step_x!r},{r.step_y!r},{r.x0!r},{r.y0!r},f64,,,,{'esquina' if corner else 'centro'},"
            f"{'' if default else '-9999'},m,1,OK,{len(valid)},{len(r.values) - len(valid)},{min(valid)!r},{max(valid)!r}"
        )
    for name, (_, result) in rejected_files().items():
        rows.append(f"{name},,,,,,,,,,,,,,,,,{result},,,,")
    return "\n".join(rows) + "\n"


def generate(out: Path) -> dict[str, bytes]:
    """Todos los ficheros que hay en `out`, por nombre."""
    files: dict[str, bytes] = {}
    for r in geotiff_rasters():
        files[r.name] = write_geotiff(r)
    for r, corner, default, odd in ascii_rasters():
        files[r.name] = write_ascii(r, corner, default, odd)
    for name, (data, _) in rejected_files().items():
        files[name] = data
    files["ficheros.csv"] = files_csv().encode("utf-8")
    files["celdas.csv"] = cells_csv().encode("utf-8")
    files["consultas.csv"] = queries_csv().encode("utf-8")
    return files


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in generate(OUT).items():
        (OUT / name).write_bytes(data)
    print(f"{len(list(OUT.iterdir()))} ficheros en {OUT}")


if __name__ == "__main__":
    main()
