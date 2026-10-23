"""Promediados largos simulados y lo que debe salir de ellos (S017).

Genera en fixtures/promediado/, para cada escenario, a 10 Hz como el RS3:
- <escenario>.csv: las épocas de un punto quieto, con ruido y una deriva lenta, en geográficas (lat, lon, h elipsoidal)
  y en UTM (pyproj, EPSG:4258 → 25830 en la Península y REGCAN95 EPSG:4081 → 4083 en Canarias).
- <escenario>.esperado.csv: en algunos números de época, la media, las desviaciones típicas muestrales y la
  estabilidad (lo que se ha movido la media respecto a la de hace 60 s), calculadas aparte con `math.fsum` en dos
  pasadas. Son la referencia de constitución nº 2 (≤ 0,1 mm) de AverageStats y de PointMeasurement.

Los valores van redondeados antes de calcular lo esperado, así que la app y este script parten de los mismos números.

Uso: uv run --project tools tools/promediado/generar_promediado.py
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "nmea"))
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

OUT = ROOT / "fixtures" / "promediado"
EPOCH_S = 0.1
WINDOW_EPOCHS = 600  # 60 s de tiempo promediado a 10 Hz
SIGMA_M = (0.004, 0.003, 0.007)  # ruido de Este, Norte y altura
DRIFT_M_PER_MIN = (0.001, -0.0007, 0.0012)


@dataclass(frozen=True)
class Scenario:
    name: str
    origin: tuple[float, float, float]
    geographic: str
    utm: str
    epochs: int
    seed: int
    checkpoints: tuple[int, ...]


SCENARIOS = (
    Scenario("promediado-60min", (40.4168, -3.7038, 650.123), "EPSG:4258", "EPSG:25830", 36_000, 17, (1, 2, 10, 600, 601, 1200, 6000, 12_000, 36_000)),
    Scenario("promediado-canarias", (28.4636, -16.2518, 112.456), "EPSG:4081", "EPSG:4083", 6_000, 29, (1, 2, 10, 600, 601, 1200, 6000)),
)


@dataclass(frozen=True)
class Epochs:
    latitude: list[float]
    longitude: list[float]
    height: list[float]
    east: list[float]
    north: list[float]


def simulate(scenario: Scenario) -> Epochs:
    rng = random.Random(scenario.seed)
    offsets: list[list[float]] = [[], [], []]
    for i in range(scenario.epochs):
        minutes = i * EPOCH_S / 60.0
        for axis in range(3):
            offsets[axis].append(rng.gauss(0.0, SIGMA_M[axis]) + DRIFT_M_PER_MIN[axis] * minutes)
    lat0, lon0, h0 = scenario.origin
    local = g.generar_vectores.topocentric(lat0, lon0, h0)
    lons, lats, heights = local.transform(offsets[0], offsets[1], offsets[2], direction=TransformDirection.INVERSE)
    latitude = [float(f"{x:.11f}") for x in lats]
    longitude = [float(f"{x:.11f}") for x in lons]
    height = [float(f"{x:.5f}") for x in heights]
    projected = Transformer.from_crs(scenario.geographic, scenario.utm, always_xy=True)
    easts, norths = projected.transform(longitude, latitude)
    return Epochs(latitude, longitude, height, [float(f"{x:.5f}") for x in easts], [float(f"{x:.5f}") for x in norths])


def mean(values: list[float]) -> float:
    return math.fsum(values) / len(values)


def deviation(values: list[float]) -> float:
    """Desviación típica muestral (n − 1) en dos pasadas."""
    m = mean(values)
    return math.sqrt(math.fsum((x - m) ** 2 for x in values) / (len(values) - 1))


def fmt(value: float | None) -> str:
    return "" if value is None else f"{value:.9f}"


def expected_row(epochs: Epochs, n: int) -> str:
    east, north, height = epochs.east[:n], epochs.north[:n], epochs.height[:n]
    dev_h = dev_v = stab_h = stab_v = None
    if n >= 2:
        dev_h = math.hypot(deviation(east), deviation(north))
        dev_v = deviation(height)
    if n > WINDOW_EPOCHS:
        before = n - WINDOW_EPOCHS
        stab_h = math.hypot(mean(east) - mean(epochs.east[:before]), mean(north) - mean(epochs.north[:before]))
        stab_v = abs(mean(height) - mean(epochs.height[:before]))
    cells = [str(n), fmt(n * EPOCH_S), fmt(mean(east)), fmt(mean(north)), fmt(mean(height)), fmt(dev_h), fmt(dev_v), fmt(stab_h), fmt(stab_v)]
    return ",".join(cells)


def generate_one(scenario: Scenario) -> dict[str, str]:
    epochs = simulate(scenario)
    header = [
        f"# Promediado simulado de {scenario.epochs} épocas a 10 Hz (S017). Generado por tools/promediado/generar_promediado.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; {scenario.geographic} → {scenario.utm}; semilla={scenario.seed}",
        f"# ruido de E, N y h = {SIGMA_M} m; deriva = {DRIFT_M_PER_MIN} m/min",
    ]
    rows = [
        f"{i + 1},{epochs.latitude[i]:.11f},{epochs.longitude[i]:.11f},{epochs.height[i]:.5f},{epochs.east[i]:.5f},{epochs.north[i]:.5f}"
        for i in range(scenario.epochs)
    ]
    data = "\n".join(header + ["n,latitud,longitud,altura_elipsoidal,este,norte"] + rows) + "\n"
    expected_header = [
        f"# Lo esperado de {scenario.name}.csv: media, desviación típica muestral y estabilidad (60 s) tras n épocas. math.fsum en dos pasadas.",
        "# La estabilidad solo existe con más de 600 épocas (60 s a 10 Hz): media(n) frente a media(n − 600). Horizontal = hipotenusa de E y N.",
        "n,tiempo_s,media_e,media_n,media_h,dev_h,dev_v,estab_h,estab_v",
    ]
    expected = "\n".join(expected_header + [expected_row(epochs, n) for n in scenario.checkpoints]) + "\n"
    return {f"{scenario.name}.csv": data, f"{scenario.name}.esperado.csv": expected}


def generate() -> dict[str, str]:
    files: dict[str, str] = {}
    for scenario in SCENARIOS:
        files.update(generate_one(scenario))
    return files


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        (OUT / name).write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} caracteres")
    return 0


if __name__ == "__main__":
    sys.exit(main())
