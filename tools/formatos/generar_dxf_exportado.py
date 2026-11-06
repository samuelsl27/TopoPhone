"""Referencias de la exportación a DXF R12 calculadas aparte de la app (S029).

Genera en fixtures/formatos/dxf-exportado/:

- aci.csv: los colores 1 a 255 de la tabla estándar de los CAD (ACI) con su RGB. Se calculan con la regla de la tabla (1 a 9 fijos, 10 a 249 en
  24 tonos de 15° por 10 variantes de brillo y saturación, 250 a 255 grises) con aritmética entera, que es como la define la tabla publicada.
  El test fija además a mano colores conocidos (rojo, blanco, grises) para que no sea circular con la regla.
- referencia-locales.dxf y referencia-globales.dxf: el DXF que debe escribir la app para un proyecto pequeño (S029, plan «Modelo del
  fichero») con coordenadas locales (ETRS89 / UTM 30) y con globales (longitud y latitud de pyproj, 9 decimales).

Y lo que usa el test de `tools`:

- `read_pairs`: un lector independiente del móvil que parte un DXF en pares de código y valor.
- `nearest_aci`: el color ACI más cercano a un RGB (distancia euclídea al cuadrado; en un empate, el índice menor), para comprobar el
  color de las capas.

Reglas de este conjunto que la spec no fija y que quedan escritas aquí (plan, D1 a D8):
- La tabla de capas declara la capa 0 primero, después las de código en orden de aparición (puntos y luego líneas), SIN_CODIGO si se usa y
  NOMBRES, COTAS y DESCRIPCIONES si tienen texto.
- Etiquetas: sin giro, desplazadas del punto +0,5 × altura en X; nombre +0,5 × altura en Y, cota −1,5 × altura y descripción −3 × altura.
- Los números van con punto, a 4 decimales en la unidad lineal y a 9 en grados; el texto de la cota, a 3 decimales.

Uso: uv run --project tools tools/formatos/generar_dxf_exportado.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "dxf-exportado"
METERS_PER_DEGREE = 111_320.0
TEXT_HEIGHT_M = 0.5

FIXED = {
    1: (255, 0, 0),
    2: (255, 255, 0),
    3: (0, 255, 0),
    4: (0, 255, 255),
    5: (0, 0, 255),
    6: (255, 0, 255),
    7: (255, 255, 255),
    8: (128, 128, 128),
    9: (192, 192, 192),
}
GRAYS = {250: 51, 251: 91, 252: 132, 253: 173, 254: 214, 255: 255}
BRIGHTNESS = (255, 204, 153, 127, 76)


def aci_color(index: int) -> tuple[int, int, int]:
    """El RGB del color ACI `index` (1 a 255)."""
    if index in FIXED:
        return FIXED[index]
    if index in GRAYS:
        return (GRAYS[index],) * 3
    assert 10 <= index <= 249, index
    hue_step, variant = divmod(index - 10, 10)
    value = BRIGHTNESS[variant // 2]
    low = 0 if variant % 2 == 0 else value // 2
    sector, position = divmod(hue_step, 4)  # 6 sectores de 60° con 4 pasos de 15°
    rising = low + (value - low) * position // 4
    falling = low + (value - low) * (4 - position) // 4
    r, g, b = (
        (value, rising, low),
        (falling, value, low),
        (low, value, rising),
        (low, falling, value),
        (rising, low, value),
        (value, low, falling),
    )[sector]
    return r, g, b


def aci_csv() -> str:
    rows = ["indice,r,g,b"]
    for index in range(1, 256):
        r, g, b = aci_color(index)
        rows.append(f"{index},{r},{g},{b}")
    return "\n".join(rows) + "\n"


def nearest_aci(rgb: int) -> int:
    """El color ACI más cercano a 0xRRGGBB; en un empate, el índice menor."""
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    best, best_distance = 7, None
    for index in range(1, 256):
        cr, cg, cb = aci_color(index)
        distance = (r - cr) ** 2 + (g - cg) ** 2 + (b - cb) ** 2
        if best_distance is None or distance < best_distance:
            best, best_distance = index, distance
    return best


# ------------------------------------------------------------------------------------------------------------------ el caso
@dataclass(frozen=True)
class RefPoint:
    name: str
    code: str | None
    east: float
    north: float
    height: float | None
    ellipsoidal: float | None
    description: str


@dataclass(frozen=True)
class RefLine:
    code: str
    closed: bool
    vertices: tuple[int, ...]


CODE_COLORS = {"ARBOL": 0x008000, "BORDE_1": 0xFF0000}
POINTS = (
    RefPoint("P1", "ARBOL", 440000.1234567, 4474000.7654321, 650.1236, 701.3576, "Roble"),
    RefPoint("P2", "BORDE_1", 440010.5, 4474010.25, None, None, ""),
    RefPoint("P3", None, 440020.0, 4474000.0, 651.0, 702.234, "Esquina"),
)
LINES = (RefLine("BORDE_1", False, (0, 1, 2)), RefLine("BORDE_1", True, (0, 1, 2)))


def number(value: float, decimals: int) -> str:
    return f"{value:.{decimals}f}"


def pair(code: int, value: str) -> str:
    return f"{code:3d}\r\n{value}\r\n"


def build_dxf(global_coordinates: bool) -> str:
    to_geo = Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True)
    decimals = 9 if global_coordinates else 4
    text_height = TEXT_HEIGHT_M / METERS_PER_DEGREE if global_coordinates else TEXT_HEIGHT_M

    def position(point: RefPoint) -> tuple[float, float, float]:
        if global_coordinates:
            lon, lat = to_geo.transform(point.east, point.north)
            return lon, lat, point.ellipsoidal or 0.0
        return point.east, point.north, point.height or 0.0

    layers = ["0"]
    for point in POINTS:
        if point.code and point.code not in layers:
            layers.append(point.code)
    for line in LINES:
        if line.code not in layers:
            layers.append(line.code)
    if any(p.code is None for p in POINTS):
        layers.append("SIN_CODIGO")
    layers += ["NOMBRES", "COTAS", "DESCRIPCIONES"]

    def color(layer: str) -> int:
        return nearest_aci(CODE_COLORS[layer]) if layer in CODE_COLORS else 7

    out = [pair(0, "SECTION"), pair(2, "HEADER"), pair(9, "$ACADVER"), pair(1, "AC1009"), pair(0, "ENDSEC")]
    out += [pair(0, "SECTION"), pair(2, "TABLES"), pair(0, "TABLE"), pair(2, "LAYER"), pair(70, str(len(layers)))]
    for layer in layers:
        out += [pair(0, "LAYER"), pair(2, layer), pair(70, "0"), pair(62, str(color(layer))), pair(6, "CONTINUOUS")]
    out += [pair(0, "ENDTAB"), pair(0, "ENDSEC"), pair(0, "SECTION"), pair(2, "ENTITIES")]

    for point in POINTS:
        x, y, z = position(point)
        layer = point.code or "SIN_CODIGO"
        out += [pair(0, "POINT"), pair(8, layer), pair(10, number(x, decimals)), pair(20, number(y, decimals)), pair(30, number(z, decimals))]
    for point in POINTS:
        x, y, z = position(point)
        tx = x + 0.5 * text_height
        labels = [("NOMBRES", y + 0.5 * text_height, point.name)]
        if point.height is not None:
            labels.append(("COTAS", y - 1.5 * text_height, number(point.ellipsoidal if global_coordinates else point.height, 3)))
        if point.description:
            labels.append(("DESCRIPCIONES", y - 3 * text_height, point.description))
        for layer, ty, text in labels:
            out += [
                pair(0, "TEXT"),
                pair(8, layer),
                pair(10, number(tx, decimals)),
                pair(20, number(ty, decimals)),
                pair(30, number(z, decimals)),
                pair(40, number(text_height, 9 if global_coordinates else 4)),
                pair(1, text),
            ]
    for line in LINES:
        out += [pair(0, "POLYLINE"), pair(8, line.code), pair(66, "1"), pair(70, "9" if line.closed else "8")]
        for index in line.vertices:
            x, y, z = position(POINTS[index])
            out += [
                pair(0, "VERTEX"),
                pair(8, line.code),
                pair(10, number(x, decimals)),
                pair(20, number(y, decimals)),
                pair(30, number(z, decimals)),
                pair(70, "32"),
            ]
        out += [pair(0, "SEQEND"), pair(8, line.code)]
    out += [pair(0, "ENDSEC"), pair(0, "EOF")]
    return "".join(out)


def expected() -> dict[str, str]:
    return {
        "aci.csv": aci_csv(),
        "referencia-locales.dxf": build_dxf(False),
        "referencia-globales.dxf": build_dxf(True),
    }


# ------------------------------------------------------------------------------------------------------------------ el lector
def read_pairs(text: str) -> list[tuple[int, str]]:
    """Parte un DXF en pares (código, valor); falla si no son pares completos."""
    lines = text.split("\r\n")
    assert lines[-1] == "", "el fichero acaba en salto de línea"
    lines = lines[:-1]
    assert len(lines) % 2 == 0, "pares incompletos"
    return [(int(lines[i].strip()), lines[i + 1]) for i in range(0, len(lines), 2)]


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    files = expected()
    for name, text in files.items():
        (out / name).write_bytes(text.encode("utf-8"))
    print(f"{len(files)} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
