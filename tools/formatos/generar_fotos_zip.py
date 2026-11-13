"""Referencias del ZIP de fotos de los puntos y de su índice `fotos.csv` (S056/RF-4 a RF-7).

Genera en fixtures/formatos/fotos-zip/:

- `entrada-fotos.txt`: un proyecto de ejemplo, en el orden en que la app exporta sus puntos, con las fotos de cada uno en el orden en que se
  añadieron. Una línea por elemento, campos separados por `|`:
      point|<nombre del punto>
      photo|<camera|gallery>|<fecha de la foto, UTC en ms>|<bytes del fichero>|<exif|noexif>
  Una `photo` es del último `point`. Los puntos sin ninguna foto no entran en el ZIP.
- `fotos.csv`: el índice que debe escribir la app para esa entrada, byte a byte (UTF-8 sin BOM, coma, CRLF, comillas dobles solo donde hace falta).

Y lo que usa el test de `tools`:

- `names`: el nombre de cada foto en el ZIP (`<punto>_<n>.jpg`), calculado aquí con otra implementación que la de Kotlin.
- `payload`: los bytes de cada foto del ejemplo (empiezan por FFD8 y acaban en FFD9, con un segmento Exif o sin él). No son imágenes que
  se puedan ver: solo hace falta que el ZIP los guarde sin tocarlos, y que lo que sale del ZIP sea igual, byte a byte.
- `build_zip`: el ZIP con las fotos y el índice al final, escrito con `zipfile`. No se guarda en `fixtures` (los `.zip` van a LFS): el test lo
  escribe en una carpeta temporal y lo lee de vuelta.

Reglas que la spec no fija y que quedan escritas aquí:
- El nombre del punto se sanea con NFD, sin las marcas combinables, y lo que no es letra ASCII, cifra, `_` o `-` pasa a `_`; un punto en blanco da `punto`.
  Un nombre que ya existe, sin distinguir mayúsculas, se distingue con `_2`, `_3`… al final del nombre del punto, y un punto sin fotos no gasta el suyo.
- La fecha es la de la foto, al segundo, en UTC con la Z.

Uso: uv run --project tools tools/formatos/generar_fotos_zip.py
"""

from __future__ import annotations

import csv
import hashlib
import io
import sys
import unicodedata
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "fotos-zip"
INDEX_NAME = "fotos.csv"
HEADER = ["Photo", "Point", "Number", "Date", "Source", "Size"]
EXTENSION = "jpg"
EMPTY_NAME = "punto"
LINE_END = "\r\n"

# 2026-11-12 08:15:30 UTC, con milisegundos para comprobar que la fecha se corta al segundo.
BASE_MS = 1_794_471_330_123

# El proyecto de ejemplo: (nombre, [(origen, desplazamiento de la fecha en s, bytes, con Exif)]).
EXAMPLE = [
    # Diez fotos en un punto: el número va hasta el 10 y se mezclan cámara y galería.
    ("P1", [("camera" if i % 3 else "gallery", i * 61, 1_800 + i * 137, True) for i in range(10)]),
    # Dos puntos que se saturan al mismo nombre: «Árbol ñ/9» y «Arbol n 9» dan Arbol_n_9.
    ("Árbol ñ/9", [("camera", 700, 3_050, True)]),
    ("Arbol n 9", [("gallery", 760, 2_411, True), ("gallery", 801, 2_412, True)]),
    # Una coma y unas comillas en el nombre: el índice los escribe entre comillas dobles; el nombre del ZIP no lleva ninguno.
    ('Esquina "norte", lado 2', [("camera", 900, 4_096, True)]),
    # Un punto sin fotos no entra en el ZIP ni gasta el nombre del siguiente, que se llama igual sin distinguir mayúsculas.
    ("P2", []),
    # Una foto de galería sin metadatos.
    ("p2", [("gallery", 930, 777, False)]),
    # Mismos nombres con distinta mayúscula: el segundo pasa a a_2.
    ("A", [("camera", 1_000, 1_024, True), ("camera", 1_030, 1_025, True)]),
    ("a", [("gallery", 1_100, 999, True)]),
]


@dataclass
class Photo:
    origin: str
    taken_ms: int
    size: int
    exif: bool


@dataclass
class Point:
    name: str
    photos: list[Photo] = field(default_factory=list)


def example() -> list[Point]:
    return [Point(name, [Photo(o, BASE_MS + dt * 1_000, size, exif) for o, dt, size, exif in photos]) for name, photos in EXAMPLE]


def input_text(points: list[Point]) -> str:
    lines = []
    for point in points:
        lines.append(f"point|{point.name}")
        for photo in point.photos:
            lines.append(f"photo|{photo.origin}|{photo.taken_ms}|{photo.size}|{'exif' if photo.exif else 'noexif'}")
    return "\n".join(lines) + "\n"


def parse_input(text: str) -> list[Point]:
    points: list[Point] = []
    for raw in text.splitlines():
        if not raw.strip():
            continue
        parts = raw.split("|")
        if parts[0] == "point":
            points.append(Point(parts[1]))
        elif parts[0] == "photo":
            points[-1].photos.append(Photo(parts[1], int(parts[2]), int(parts[3]), parts[4] == "exif"))
        else:
            raise ValueError(f"línea no reconocida: {raw!r}")
    return points


def sanitize(point_name: str) -> str:
    """Solo letras ASCII sin acentos, cifras, `_` y `-`; lo demás pasa a `_`, y `punto` si el nombre está en blanco."""
    if not point_name.strip():
        return EMPTY_NAME
    decomposed = unicodedata.normalize("NFD", point_name.strip())
    kept = [c for c in decomposed if not 0x0300 <= ord(c) <= 0x036F]
    return "".join(c if (c.isascii() and c.isalnum()) or c in "_-" else "_" for c in kept)


def names(points: list[Point]) -> list[list[str]]:
    """Los nombres de las fotos de cada punto, en el mismo orden y sin repetir ninguno (sin distinguir mayúsculas)."""
    taken: set[str] = set()
    out: list[list[str]] = []
    for point in points:
        base = sanitize(point.name)
        candidate = base
        n = 1
        while True:
            given = [f"{candidate}_{i}.{EXTENSION}" for i in range(1, len(point.photos) + 1)]
            if not any(g.lower() in taken for g in given):
                taken.update(g.lower() for g in given)
                out.append(given)
                break
            n += 1
            candidate = f"{base}_{n}"
    return out


def utc(ms: int) -> str:
    return datetime.fromtimestamp(ms // 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def index_csv(points: list[Point]) -> bytes:
    """El índice: cabecera y una fila por foto, en el orden del ZIP, con CRLF y comillas solo donde hacen falta."""
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator=LINE_END, quoting=csv.QUOTE_MINIMAL)
    writer.writerow(HEADER)
    for point, given in zip(points, names(points)):
        for number, (photo, name) in enumerate(zip(point.photos, given), start=1):
            writer.writerow([name, point.name, number, utc(photo.taken_ms), photo.origin, photo.size])
    return buffer.getvalue().encode("utf-8")


def payload(key: str, size: int, exif: bool) -> bytes:
    """Bytes deterministas con la forma de un JPEG: FFD8, un segmento Exif o JFIF, datos y FFD9, y justo [size] bytes."""
    if exif:
        data = b"Exif\x00\x00MM\x00*\x00\x00\x00\x08\x00\x00"  # APP1: la cabecera TIFF mínima, sin etiquetas
        segment = b"\xff\xe1" + (len(data) + 2).to_bytes(2, "big") + data
    else:
        segment = b"\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"  # APP0
    head, tail = b"\xff\xd8" + segment, b"\xff\xd9"
    fill = size - len(head) - len(tail)
    assert fill >= 0, size
    body = bytearray()
    counter = 0
    while len(body) < fill:
        body += hashlib.sha256(f"{key}/{counter}".encode()).digest()
        counter += 1
    return head + bytes(body[:fill]) + tail


def photo_bytes(index: int, number: int, photo: Photo) -> bytes:
    return payload(f"punto{index}/foto{number}", photo.size, photo.exif)


def build_zip(points: list[Point], path: Path) -> None:
    """El ZIP: las fotos en el orden del índice, sin recomprimir, y `fotos.csv` el último."""
    with zipfile.ZipFile(path, "w") as z:
        for index, (point, given) in enumerate(zip(points, names(points))):
            for number, (photo, name) in enumerate(zip(point.photos, given), start=1):
                z.writestr(zipfile.ZipInfo(name, date_time=(2026, 11, 12, 8, 15, 30)), photo_bytes(index, number, photo), zipfile.ZIP_STORED)
        z.writestr(zipfile.ZipInfo(INDEX_NAME, date_time=(2026, 11, 12, 8, 15, 30)), index_csv(points), zipfile.ZIP_DEFLATED)


def generate_all() -> dict[str, bytes]:
    points = example()
    return {"entrada-fotos.txt": input_text(points).encode("utf-8"), INDEX_NAME: index_csv(points)}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    produced = generate_all()
    for name, data in produced.items():
        (OUT / name).write_bytes(data)
    for stale in [p for p in OUT.iterdir() if p.is_file() and p.name not in produced]:
        stale.unlink()
    print(f"{len(produced)} ficheros en {OUT}")


if __name__ == "__main__":
    sys.exit(main())
