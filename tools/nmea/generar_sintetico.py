"""Sesión NMEA sintética y lo que la app debe mostrar en cada época (S002/T20).

Genera en fixtures/nmea/sintetico/:
- estatico-fix.nmea: GGA y GST de puntos de la Península, Baleares y Canarias, con basura binaria
  intercalada, frases que la app no usa, otro identificador de constelación, una GGA con suma de
  control errónea y una frase cortada (S002/RF-11).
- estatico-fix.esperado.csv: para cada GGA válida, estado, satélites, HDOP, edad, precisiones y el
  punto del suelo (jalón vertical de 1,800 m sobre un RS3, 0,134 m), con UTM EPSG:258zz y la altitud
  EGM08-REDNAP calculadas con pyproj/PROJ (constitución nº 2).

Uso: uv run --project tools tools/nmea/generar_sintetico.py
Es determinista: la misma versión de pyproj/PROJ y de la rejilla dan los mismos ficheros.
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from functools import reduce
from pathlib import Path

import pyproj
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "geodesia"))
import generar_vectores  # noqa: E402
import rejillas  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "nmea" / "sintetico"
NAME = "estatico-fix"

POLE_M = 1.8
RS3_BASE_TO_REFERENCE_M = 0.134
UTM_ZONES = (29, 30, 31)
# Una GGA válida en todo salvo la suma de control: la app nunca debe mostrar esta hora (S002/RF-11).
CORRUPT_UTC = "999999.00"

QUALITY = {"NONE": 0, "SINGLE": 1, "DGPS": 2, "FIX": 4, "FLOAT": 5, "ESTIMATED": 6}


@dataclass(frozen=True)
class Epoch:
    utc: str
    talker: str
    status: str
    lat: float | None
    lon: float | None
    satellites: int
    hdop: float
    altitude: float | None
    separation: float | None
    age: float | None
    sigma: tuple[float, float, float] | None


EPOCHS = [
    Epoch("101530.00", "GN", "FIX", 40.4168, -3.7038, 24, 0.6, 650.123, 51.234, 1.0, (0.008, 0.007, 0.015)),
    Epoch("101531.00", "GN", "FIX", 41.3874, 2.1686, 22, 0.7, 12.345, 49.876, 1.2, (0.010, 0.009, 0.018)),
    Epoch("101532.00", "GN", "FLOAT", 42.8782, -8.5448, 18, 1.1, 260.5, 54.321, 2.0, (0.150, 0.120, 0.300)),
    Epoch("101533.00", "GP", "FIX", 39.5696, 2.6502, 20, 0.8, 30.0, 48.765, 1.0, (0.009, 0.008, 0.016)),
    Epoch("101534.00", "GN", "FIX", 28.1, -15.4, 19, 0.9, 40.0, 35.0, 1.4, (0.011, 0.010, 0.020)),
    Epoch("101535.00", "GN", "NONE", None, None, 0, 99.99, None, None, None, None),
]


def checksum(body: str) -> str:
    return f"{reduce(lambda acc, ch: acc ^ ord(ch), body, 0):02X}"


def sentence(body: str) -> bytes:
    return f"${body}*{checksum(body)}\r\n".encode("ascii")


def nmea_coordinate(value: float, degree_digits: int, positive: str, negative: str) -> tuple[str, str]:
    """Grados decimales → `ddmm.mmmmmmm` con 7 decimales de minuto, como las frases del receptor."""
    magnitude = abs(value)
    degrees = int(magnitude)
    minutes = round((magnitude - degrees) * 60.0, 7)
    if minutes >= 60.0:
        degrees, minutes = degrees + 1, 0.0
    return f"{degrees:0{degree_digits}d}{minutes:010.7f}", positive if value >= 0 else negative


def parsed_coordinate(text: str, hemisphere: str, degree_digits: int) -> float:
    """Lo que obtiene `GgaParser` al leer la frase: grados + minutos/60, negativo al sur y al oeste."""
    decimal = int(text[:degree_digits]) + float(text[degree_digits:]) / 60.0
    return -decimal if hemisphere in ("S", "W") else decimal


def optional(value: float | None, digits: int) -> str:
    return "" if value is None else f"{value:.{digits}f}"


def gga_body(epoch: Epoch) -> tuple[str, float | None, float | None]:
    if epoch.lat is None or epoch.lon is None:
        lat_text, lat_hemi, lon_text, lon_hemi = "", "", "", ""
        lat = lon = None
    else:
        lat_text, lat_hemi = nmea_coordinate(epoch.lat, 2, "N", "S")
        lon_text, lon_hemi = nmea_coordinate(epoch.lon, 3, "E", "W")
        lat = parsed_coordinate(lat_text, lat_hemi, 2)
        lon = parsed_coordinate(lon_text, lon_hemi, 3)
    fields = [
        f"{epoch.talker}GGA",
        epoch.utc,
        lat_text,
        lat_hemi,
        lon_text,
        lon_hemi,
        str(QUALITY[epoch.status]),
        f"{epoch.satellites:02d}",
        f"{epoch.hdop:.2f}".rstrip("0").rstrip(".") if epoch.hdop < 99 else f"{epoch.hdop:.2f}",
        optional(epoch.altitude, 3),
        "M",
        optional(epoch.separation, 3),
        "M",
        optional(epoch.age, 1),
        "0001" if epoch.age is not None else "",
    ]
    return ",".join(fields), lat, lon


def gst_body(epoch: Epoch) -> str:
    sigma = epoch.sigma
    values = ["", "", ""] if sigma is None else [f"{s:.3f}" for s in sigma]
    rms = "" if sigma is None else "0.012"
    ellipse = ["", "", ""] if sigma is None else ["0.010", "0.007", "45.0"]
    return ",".join(["GNGST", epoch.utc, rms, *ellipse, *values])


def fmt(value: float | None) -> str:
    return "" if value is None else repr(float(value))


def expected_row(epoch: Epoch, lat: float | None, lon: float | None, geoid: Transformer, pole_m: float = POLE_M) -> list[str]:
    sigma = epoch.sigma
    sigma_h = math.sqrt(sigma[0] ** 2 + sigma[1] ** 2) if sigma else None
    sigma_v = sigma[2] if sigma else None
    point: list[str] = [""] * 7
    if lat is not None and lon is not None:
        h = None
        if epoch.altitude is not None and epoch.separation is not None:
            # Mismo orden de operaciones que GroundPointCalculator (S002/RF-32, S002/RF-33).
            h = (epoch.altitude + epoch.separation) - (pole_m + RS3_BASE_TO_REFERENCE_M)
        zone = math.floor((lon + 180.0) / 6.0) + 1
        east = north = None
        if zone in UTM_ZONES:
            east, north = Transformer.from_crs("EPSG:4258", f"EPSG:258{zone}", always_xy=True).transform(lon, lat)
        _, _, n = geoid.transform(lon, lat, 0.0)
        orthometric = h - n if h is not None and abs(n) < 1e6 else None
        point = [fmt(lat), fmt(lon), fmt(h), str(zone) if east is not None else "", fmt(east), fmt(north), fmt(orthometric)]
    return [
        f"{epoch.talker}GGA",
        epoch.utc,
        epoch.status,
        str(epoch.satellites),
        fmt(epoch.hdop),
        fmt(epoch.age),
        fmt(sigma_h),
        fmt(sigma_v),
        *point,
    ]


def generate() -> tuple[bytes, str]:
    geoid = generar_vectores.geoid_transformer()
    nmea = bytearray()
    # Restos de una trama RTCM3 y bytes sin sentido antes de la primera frase.
    nmea += bytes([0xD3, 0x00, 0x13, 0x3E, 0xD0, 0x00, 0x03, 0x8A, 0x24, 0x00, 0xFF, 0x0D, 0x24, 0x47])
    nmea += sentence("GPGSV,3,1,11,05,62,045,48,12,35,301,44,13,22,089,40,15,10,190,35")
    rows = []
    for index, epoch in enumerate(EPOCHS):
        body, lat, lon = gga_body(epoch)
        nmea += sentence(body)
        nmea += sentence(gst_body(epoch))
        rows.append(",".join(expected_row(epoch, lat, lon, geoid)))
        if index == 1:
            corrupt = sentence(body.replace(epoch.utc, CORRUPT_UTC).replace(",22,", ",23,"))
            wrong = b"00" if corrupt[-4:-2] != b"00" else b"01"
            nmea += corrupt[:-4] + wrong + b"\r\n"  # suma de control cambiada
            nmea += b"$GNGGA,101531.50,4124.0000000,N,00210.0000000,E,4,2"  # frase cortada
            nmea += b"\r\n"
            nmea += bytes([0xD3, 0x00, 0x08, 0x4C, 0xE0, 0x00, 0x8A, 0x00, 0x00, 0x00, 0x00, 0x2A])
    header = [
        "# Sesión sintética de principio a fin (S002/T20). Generado por tools/nmea/generar_sintetico.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; rejilla={rejillas.GRIDS[0].name} sha256={rejillas.GRIDS[0].sha256}",
        f"# jalon_m={POLE_M}; receptor=RS3 ({RS3_BASE_TO_REFERENCE_M} m); hora_corrupta={CORRUPT_UTC}",
        "frase,hora,estado,satelites,hdop,edad,sigma_h,sigma_v,latitud,longitud,h,huso,este,norte,H",
    ]
    return bytes(nmea), "\n".join(header + rows) + "\n"


def main() -> int:
    rejillas.ensure(rejillas.GRIDS[0], download=False)
    nmea, expected = generate()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{NAME}.nmea").write_bytes(nmea)
    (OUT / f"{NAME}.esperado.csv").write_text(expected, encoding="utf-8", newline="\n")
    print(f"{NAME}: {len(EPOCHS)} épocas, {len(nmea)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
