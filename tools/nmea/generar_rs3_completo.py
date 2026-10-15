"""Sesión sintética con todas las frases del RS3 y sus épocas esperadas (S003/T17).

Genera en fixtures/nmea/sintetico/:
- rs3-completo.nmea: GGA y ETC a 5 Hz; GSA y GSV por constelación (NMEA 4.11, con sistema y señal),
  GST, RMC, VTG, ZDA y EBP a 1 Hz. Cruza la medianoche UTC y tiene un hueco de 15 s.
- rs3-completo.epocas.csv: lo que debe dar cada época (hora, fecha, satélites usados y en vista,
  estado de la inclinación) y el punto del suelo de su GGA con pyproj (jalón de 1,800 m, RS3).

Uso: uv run --project tools tools/nmea/generar_rs3_completo.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_sintetico as g  # noqa: E402

NAME = "rs3-completo"
START = datetime(2026, 10, 15, 23, 59, 57, tzinfo=timezone.utc)
EPOCH_MS = 200
GAP_S = 15

# Constelación: (talker, system ID, satélites usados, satélites en vista por señal {señal: [prn…]}).
CONSTELLATIONS = [
    ("GP", 1, [5, 12, 13, 15, 18, 20, 24, 25], {1: [5, 12, 13, 15, 18, 20, 24, 25, 29, 31], 8: [5, 12, 24, 25, 29, 31]}),
    ("GL", 2, [70, 71, 72, 79, 80], {1: [70, 71, 72, 73, 79, 80, 81]}),
    ("GA", 3, [2, 7, 11, 19, 26, 30], {7: [2, 7, 11, 19, 26, 30, 33, 36]}),
    ("GB", 4, [6, 9, 16, 21], {1: [6, 9, 16, 21, 27, 39]}),
]
TILT_CODES = {"ALIGNMENT": "20", "COMPENSATING": "30", "SETUP": "10"}


@dataclass(frozen=True)
class EpochSpec:
    at: datetime
    tilt: str

    @property
    def full_second(self) -> bool:
        return self.at.microsecond == 0

    @property
    def utc(self) -> str:
        return self.at.strftime("%H%M%S.") + f"{self.at.microsecond // 10_000:02d}"


def epochs() -> list[EpochSpec]:
    """6 s a 5 Hz cruzando la medianoche, un hueco de 15 s y otros 2 s."""
    result = []
    for i in range(30):
        at = START + timedelta(milliseconds=i * EPOCH_MS)
        result.append(EpochSpec(at, "ALIGNMENT" if i < 10 else "COMPENSATING"))
    resume = result[-1].at + timedelta(seconds=GAP_S)
    for i in range(10):
        result.append(EpochSpec(resume + timedelta(milliseconds=i * EPOCH_MS), "SETUP"))
    return result


def position(index: int) -> tuple[float, float, float]:
    """Latitud, longitud y altitud con variaciones milimétricas entre épocas."""
    return 40.4168 + index * 1e-8, -3.7038 - index * 1e-8, 650.123 + index * 0.001


def gsv_sentences(talker: str, signal: int, prns: list[int]) -> list[bytes]:
    groups = [prns[i : i + 4] for i in range(0, len(prns), 4)]
    out = []
    for number, group in enumerate(groups, start=1):
        fields = [f"{talker}GSV", str(len(groups)), str(number), f"{len(prns):02d}"]
        for k, prn in enumerate(group):
            fields += [f"{prn:02d}", f"{(prn * 7 + k * 11) % 85 + 5:02d}", f"{(prn * 37) % 360:03d}", f"{30 + prn % 20}"]
        fields.append(f"{signal:X}")
        out.append(g.sentence(",".join(fields)))
    return out


def generate() -> tuple[bytes, str]:
    geoid = g.generar_vectores.geoid_transformer()
    nmea = bytearray()
    rows = []
    for index, spec in enumerate(epochs()):
        lat, lon, alt = position(index)
        epoch = g.Epoch(spec.utc, "GN", "FIX", lat, lon, 28, 0.5, alt, 51.234, 1.0, (0.008, 0.007, 0.015))
        body, parsed_lat, parsed_lon = g.gga_body(epoch)
        nmea += g.sentence(body)
        tilt_fields = [TILT_CODES[spec.tilt], "0", "123.40"] + (["210.50", "15.25", "1.10", "1.20", "0.30"] if spec.tilt == "COMPENSATING" else [""] * 5)
        nmea += g.sentence(",".join(["GNETC", spec.utc, *tilt_fields]))
        used = in_view = ""
        if spec.full_second:
            for talker, system, sats, _ in CONSTELLATIONS:
                fields = [f"{talker}GSA", "A", "3", *[f"{s:02d}" for s in sats], *[""] * (12 - len(sats)), "1.2", "0.5", "1.0", str(system)]
                nmea += g.sentence(",".join(fields))
            for talker, _, _, signals in CONSTELLATIONS:
                for signal, prns in signals.items():
                    nmea += b"".join(gsv_sentences(talker, signal, prns))
            nmea += g.sentence(g.gst_body(epoch))
            day = spec.at.strftime("%d%m%y")
            lat_text, lat_hemi = g.nmea_coordinate(lat, 2, "N", "S")
            lon_text, lon_hemi = g.nmea_coordinate(lon, 3, "E", "W")
            nmea += g.sentence(",".join(["GNRMC", spec.utc, "A", lat_text, lat_hemi, lon_text, lon_hemi, "0.010", "45.0", day, "", "", "R", "V"]))
            nmea += g.sentence("GNVTG,45.0,T,,M,0.010,N,0.019,K,R")
            nmea += g.sentence(f"GNZDA,{spec.utc},{spec.at.day:02d},{spec.at.month:02d},{spec.at.year},00,00")
            nmea += g.sentence("GNEBP,4025.0000000,N,00342.0000000,W,700.000,M")
            used = str(sum(len(c[2]) for c in CONSTELLATIONS))
            in_view = str(sum(max(len(p) for p in c[3].values()) for c in CONSTELLATIONS))
        point = g.expected_row(epoch, parsed_lat, parsed_lon, geoid)[8:]
        rows.append(",".join([spec.utc, spec.at.date().isoformat(), used, in_view, spec.tilt, *point]))
    header = [
        "# Sesión sintética completa del RS3 (S003/T17). Generado por tools/nmea/generar_rs3_completo.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; rejilla={g.rejillas.GRIDS[0].name} sha256={g.rejillas.GRIDS[0].sha256}",
        f"# jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m); hueco_s={GAP_S}",
        "hora,fecha,satelites_usados,satelites_en_vista,inclinacion,latitud,longitud,h,huso,este,norte,H",
    ]
    return bytes(nmea), "\n".join(header + rows) + "\n"


def main() -> int:
    g.rejillas.ensure(g.rejillas.GRIDS[0], download=False)
    nmea, expected = generate()
    g.OUT.mkdir(parents=True, exist_ok=True)
    (g.OUT / f"{NAME}.nmea").write_bytes(nmea)
    (g.OUT / f"{NAME}.epocas.csv").write_text(expected, encoding="utf-8", newline="\n")
    print(f"{NAME}: {len(epochs())} épocas, {len(nmea)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
