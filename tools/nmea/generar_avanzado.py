"""Reproducciones sintéticas del RS3 para el recolector avanzado y lo que debe salir de ellas (S017).

Genera en fixtures/nmea/sintetico/ (GGA a la frecuencia indicada; GST, RMC y ZDA cada segundo; sin ETC, el suelo se saca
de la antena con el jalón de 1,800 m y el RS3 como en S002):
- rs3-promediado-largo.nmea: 10 min a 10 Hz con un punto quieto, ruido y un corte de 6 s a mitad (5.940 épocas).
  rs3-promediado-largo.esperado.csv da, tras n épocas, el tiempo promediado, la media del suelo en ETRS89 / UTM 30 y
  sus desviaciones típicas muestrales, con `math.fsum` en dos pasadas desde la GGA tal como la lee la app.
- rs3-corte.nmea: a 5 Hz, 20 s, un corte corto de 4 s, 20 s, un corte largo de 6 min y 10 s.
  rs3-corte.esperado.csv da los tres tramos y cómo debe acabar cada corte.
- rs3-traza.nmea: 2 min andando a 1,2 m/s a 5 Hz, con 10 s sin FIX (FLOAT) a mitad.
  rs3-traza.esperado.csv da, por época, el suelo y cuándo disparan el modo automático por distancia (10 m) y por tiempo (30 s)
  contados desde el último disparo.

Uso: uv run --project tools tools/nmea/generar_avanzado.py
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(ROOT / "tools" / "promediado"))
import generar_promediado as p  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

START = datetime(2026, 10, 23, 10, 0, 0, tzinfo=timezone.utc)
# El punto quieto y el origen de la traza: Madrid, a 699,423 m de altura elipsoidal.
ORIGIN = (40.4168, -3.7038, 699.423)
SEPARATION = 51.234
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M

LONG_EPOCHS = 6000
CUT_AT_EPOCH = 3000  # la última época antes del corte es la 3.000
CUT_EPOCHS = 60  # 6 s a 10 Hz
LONG_CHECKPOINTS = (1, 2, 10, 600, 601, 3000, 3001, 5940)
LONG_SIGMA_M = (0.004, 0.003, 0.007)

CUT_SEGMENTS = ((100, 0.0, "inicio"), (100, 4.0, "pausa-y-reanuda"), (50, 360.0, "pausa-larga"))
CUT_SIGMA_M = 0.003

WALK_EPOCHS = 600
WALK_SPEED = 1.2
WALK_AZIMUTH = 60.0  # desde el norte, hacia el este
WALK_LOSS = range(250, 300)  # 10 s sin FIX
TRIGGER_DISTANCE_M = 10.0
TRIGGER_INTERVAL_S = 30.0

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)


@dataclass(frozen=True)
class Fix:
    """Una época: instante, desplazamiento ENU desde el origen en metros y estado."""

    at: datetime
    offset: tuple[float, float, float]
    status: str = "FIX"


@dataclass(frozen=True)
class Parsed:
    """Lo que la app lee de la GGA: hora, estado, latitud, longitud, altura elipsoidal y el suelo."""

    utc: str
    status: str
    ground_e: float
    ground_n: float
    ground_h: float


def utc_text(at: datetime) -> str:
    return at.strftime("%H%M%S.") + f"{at.microsecond // 10_000:02d}"


def at_after(seconds: float) -> datetime:
    return START + timedelta(microseconds=round(seconds * 1_000_000))


def write(fixes: list[Fix]) -> tuple[bytes, list[Parsed]]:
    local = g.generar_vectores.topocentric(*ORIGIN)
    nmea = bytearray()
    parsed = []
    for fix in fixes:
        lon, lat, h = local.transform(*fix.offset, direction=TransformDirection.INVERSE)
        float_fix = fix.status == "FLOAT"
        sigma = (0.15, 0.12, 0.30) if float_fix else (0.008, 0.006, 0.015)
        epoch = g.Epoch(utc_text(fix.at), "GN", fix.status, lat, lon, 14 if float_fix else 28, 1.4 if float_fix else 0.5,
                        round(h - SEPARATION, 3), SEPARATION, 1.0, sigma)
        body, parsed_lat, parsed_lon = g.gga_body(epoch)
        nmea += g.sentence(body)
        if fix.at.microsecond == 0:
            nmea += g.sentence(g.gst_body(epoch))
            lat_text, lat_hemi = g.nmea_coordinate(lat, 2, "N", "S")
            lon_text, lon_hemi = g.nmea_coordinate(lon, 3, "E", "W")
            day = fix.at.strftime("%d%m%y")
            nmea += g.sentence(",".join(["GNRMC", epoch.utc, "A", lat_text, lat_hemi, lon_text, lon_hemi, "0.010", "45.0", day, "", "", "R", "V"]))
            nmea += g.sentence(f"GNZDA,{epoch.utc},{fix.at.day:02d},{fix.at.month:02d},{fix.at.year},00,00")
        east, north = UTM.transform(parsed_lon, parsed_lat)
        # Mismo orden de operaciones que GroundPointCalculator (S002/RF-32, S002/RF-33).
        parsed.append(Parsed(epoch.utc, fix.status, east, north, (epoch.altitude + SEPARATION) - ANTENNA_HEIGHT))
    return bytes(nmea), parsed


def long_fixes() -> list[Fix]:
    rng = random.Random(20261023)
    fixes = []
    for i in range(LONG_EPOCHS - CUT_EPOCHS):
        slot = i + (CUT_EPOCHS if i >= CUT_AT_EPOCH else 0)
        offset = tuple(rng.gauss(0.0, sigma) for sigma in LONG_SIGMA_M)
        fixes.append(Fix(at_after(slot * p.EPOCH_S), offset))
    return fixes


def expected_long(parsed: list[Parsed]) -> str:
    east, north, height = [e.ground_e for e in parsed], [e.ground_n for e in parsed], [e.ground_h for e in parsed]
    rows = []
    for n in LONG_CHECKPOINTS:
        dev_h = dev_v = None
        if n >= 2:
            dev_h = math.hypot(p.deviation(east[:n]), p.deviation(north[:n]))
            dev_v = p.deviation(height[:n])
        rows.append(",".join([str(n), p.fmt(n * p.EPOCH_S), p.fmt(p.mean(east[:n])), p.fmt(p.mean(north[:n])), p.fmt(p.mean(height[:n])), p.fmt(dev_h), p.fmt(dev_v)]))
    header = [
        "# Lo esperado de rs3-promediado-largo.nmea (S017). Generado por tools/nmea/generar_avanzado.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# {len(parsed)} épocas a 10 Hz con un corte de {CUT_EPOCHS * p.EPOCH_S:g} s tras la época {CUT_AT_EPOCH}; el corte pausa y reanuda, no descarta.",
        "# Media del suelo (E, N, h elipsoidal) y desviaciones típicas muestrales (n − 1) tras n épocas; math.fsum en dos pasadas.",
        "n,tiempo_s,media_e,media_n,media_h,dev_h,dev_v",
    ]
    return "\n".join(header + rows) + "\n"


def cut_fixes() -> tuple[list[Fix], list[tuple[int, str, str]]]:
    rng = random.Random(20261024)
    fixes, spans = [], []
    clock = 0.0
    for count, cut_s, _ in CUT_SEGMENTS:
        clock += cut_s
        first = len(fixes)
        for _ in range(count):
            fixes.append(Fix(at_after(clock), (rng.gauss(0.0, CUT_SIGMA_M), rng.gauss(0.0, CUT_SIGMA_M), rng.gauss(0.0, 2 * CUT_SIGMA_M))))
            clock += 0.2
        spans.append((count, utc_text(fixes[first].at), utc_text(fixes[-1].at)))
    return fixes, spans


def expected_cut(spans: list[tuple[int, str, str]]) -> str:
    rows = [
        ",".join([str(i + 1), start, end, str(count), f"{cut_s:g}", outcome])
        for i, ((count, start, end), (_, cut_s, outcome)) in enumerate(zip(spans, CUT_SEGMENTS))
    ]
    header = [
        "# Lo esperado de rs3-corte.nmea (S017). Generado por tools/nmea/generar_avanzado.py; no editar a mano.",
        "# Tres tramos a 5 Hz. Un corte de más de 2 s pausa el promediado y, si dura menos de 5 min, lo reanuda sin descartar nada (S017/RF-8, RF-9);",
        "# a los 5 min avisa y espera la decisión del usuario (S017/RF-10). Una medida instantánea se cancela en los dos casos (S010/RF-11).",
        "segmento,hora_inicio,hora_fin,epocas,corte_previo_s,desenlace",
    ]
    return "\n".join(header + rows) + "\n"


def walk_fixes() -> list[Fix]:
    rng = random.Random(20261025)
    azimuth = math.radians(WALK_AZIMUTH)
    fixes = []
    for i in range(WALK_EPOCHS):
        travelled = WALK_SPEED * i * 0.2
        lost = i in WALK_LOSS
        noise = 0.15 if lost else 0.01
        offset = (
            travelled * math.sin(azimuth) + rng.gauss(0.0, noise),
            travelled * math.cos(azimuth) + rng.gauss(0.0, noise),
            rng.gauss(0.0, 2 * noise),
        )
        fixes.append(Fix(at_after(i * 0.2), offset, "FLOAT" if lost else "FIX"))
    return fixes


def expected_walk(parsed: list[Parsed]) -> str:
    rows = []
    travelled = 0.0
    last_distance = last_time = 0
    for i, e in enumerate(parsed):
        if i:
            travelled += math.hypot(e.ground_e - parsed[i - 1].ground_e, e.ground_n - parsed[i - 1].ground_n)
        by_distance = i == 0 or math.hypot(e.ground_e - parsed[last_distance].ground_e, e.ground_n - parsed[last_distance].ground_n) >= TRIGGER_DISTANCE_M
        by_time = i == 0 or (i - last_time) * 0.2 >= TRIGGER_INTERVAL_S - 1e-9
        if by_distance:
            last_distance = i
        if by_time:
            last_time = i
        rows.append(",".join([e.utc, e.status, p.fmt(e.ground_e), p.fmt(e.ground_n), p.fmt(e.ground_h), p.fmt(travelled), str(int(by_distance)), str(int(by_time))]))
    header = [
        "# Lo esperado de rs3-traza.nmea (S017). Generado por tools/nmea/generar_avanzado.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# {WALK_EPOCHS} épocas a 5 Hz andando a {WALK_SPEED} m/s hacia {WALK_AZIMUTH:g}°; sin FIX (FLOAT) de la época {WALK_LOSS.start} a la {WALK_LOSS.stop - 1}.",
        f"# disparo_distancia: planimétrica ≥ {TRIGGER_DISTANCE_M:g} m desde el último disparo; disparo_tiempo: cada {TRIGGER_INTERVAL_S:g} s desde el último; el primero dispara siempre.",
        "hora,estado,este,norte,h,distancia_recorrida_m,disparo_distancia,disparo_tiempo",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, bytes | str]:
    long_nmea, long_parsed = write(long_fixes())
    fixes, spans = cut_fixes()
    cut_nmea, _ = write(fixes)
    walk_nmea, walk_parsed = write(walk_fixes())
    return {
        "rs3-promediado-largo.nmea": long_nmea,
        "rs3-promediado-largo.esperado.csv": expected_long(long_parsed),
        "rs3-corte.nmea": cut_nmea,
        "rs3-corte.esperado.csv": expected_cut(spans),
        "rs3-traza.nmea": walk_nmea,
        "rs3-traza.esperado.csv": expected_walk(walk_parsed),
    }


def main() -> int:
    g.OUT.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} {'bytes' if isinstance(content, bytes) else 'caracteres'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
