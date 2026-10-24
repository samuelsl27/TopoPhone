"""Referencias de la cota de diseño y del informe de replanteo calculadas aparte (S019).

Genera tres ficheros en fixtures/replanteo/:

- replanteo-cota.csv: la diferencia de cota (punta menos cota de diseño), el desmonte, el terraplén o "en cota", con y sin
  cota en el punto de diseño, con cota escrita, sin altura en la punta y con diferencias de varios metros.
- replanteo-informe.csv: puntos replanteados de tres proyectos (ETRS89 / UTM 30, REGCAN95 / UTM 28 y un SC geográfico), con
  varios replanteos por punto de diseño, con y sin cota, con tolerancias y sin ellas (los de antes de S019). Las diferencias
  de Norte y Este salen de pyproj (medido menos diseño), la distancia horizontal y la diferencia de cota de las
  coordenadas de la fila, y "dentro" de las tolerancias de la fila.
- replanteo-resumen.csv: el resumen de cada proyecto, con todos los puntos y con una selección, contando solo el último
  replanteo de cada punto de diseño.

- Las posiciones salen de desplazar el diseño en un plano tangente local (`topocentric` de PROJ); los Este y Norte de
  EPSG:4258 → 25830 y EPSG:4081 → 4083, redondeados a 5 decimales antes de restar, así que la app y este script parten de
  los mismos números.
- En el SC geográfico no hay Este ni Norte: las diferencias son las del plano tangente local del punto medido.
- Las alturas del proyecto (`diseno_h`, `cota_usada`, `medido_h`) son números del sistema de alturas del proyecto, sin
  geoide; las elipsoidales (`*_gh`) solo colocan el plano local.
- `dentro` vale 1 o 0 con tolerancias guardadas y queda vacío sin ellas ("sin evaluar").

Uso: uv run --project tools tools/replanteo/generar_informe.py
"""

from __future__ import annotations

import math
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replanteo as base  # noqa: E402
from pyproj import Transformer  # noqa: E402
from pyproj.enums import TransformDirection  # noqa: E402

g = base.g
OUT = base.OUT
NAME_COTA = "replanteo-cota.csv"
NAME_INFORME = "replanteo-informe.csv"
NAME_RESUMEN = "replanteo-resumen.csv"

# ---------------------------------------------------------------------------------------------------------------------
# La cota: diseño, cota escrita, altura de la punta y tolerancia vertical (metros). Los casos del límite exacto usan
# números que se representan sin error (múltiplos de 0,25): la app y este script hacen la misma resta.
# ---------------------------------------------------------------------------------------------------------------------
COTA_CASES = (
    # (caso, diseño, escrita, punta, tolerancia_v)
    ("exacta", 100.0, None, 100.0, 0.03),
    ("desmonte-1-cm", 100.0, None, 100.01, 0.03),
    ("terraplen-1-cm", 100.0, None, 99.99, 0.03),
    ("desmonte-en-el-limite", 100.0, None, 100.25, 0.25),
    ("terraplen-en-el-limite", 100.0, None, 99.75, 0.25),
    ("desmonte-justo-fuera", 100.0, None, 100.035, 0.03),
    ("terraplen-justo-fuera", 100.0, None, 99.965, 0.03),
    ("desmonte-de-metros", 702.456, None, 705.706, 0.03),
    ("terraplen-de-metros", 702.456, None, 698.206, 0.03),
    ("desmonte-de-decenas", 50.0, None, 62.5, 0.03),
    ("terraplen-de-decenas", 50.0, None, 10.0, 0.03),
    ("tolerancia-minima", 10.0, None, 10.009, 0.01),
    ("tolerancia-maxima", 10.0, None, 10.9, 1.0),
    ("cota-escrita-sobre-la-del-punto", 100.0, 98.5, 98.52, 0.03),
    ("cota-escrita-sin-altura-en-el-punto", None, 250.0, 250.2, 0.03),
    ("cota-negativa", -3.5, None, -3.52, 0.03),
    ("sin-cota-de-diseno", None, None, 120.0, 0.03),
    ("punta-sin-altura", 100.0, None, None, 0.03),
    ("sin-cota-ni-altura", None, None, None, 0.03),
    ("cota-escrita-sin-altura-en-la-punta", 100.0, 99.0, None, 0.03),
)

COTA_HEADER = "caso,diseno_h,cota_escrita,punta_h,tol_v,cota_efectiva,d_cota,estado"


def fmt(value: float | None, decimals: int = 9) -> str:
    return "" if value is None else f"{value:.{decimals}f}"


def level_state(d_cota: float | None, tolerance: float) -> str | None:
    """CUT: la punta está por encima de la cota (hay que excavar). FILL: por debajo (hay que rellenar)."""
    if d_cota is None:
        return None
    if abs(d_cota) <= tolerance:
        return "ON_GRADE"
    return "CUT" if d_cota > 0 else "FILL"


def cota_row(case: tuple) -> str:
    name, design, written, tip, tolerance = case
    effective = written if written is not None else design
    d_cota = None if effective is None or tip is None else tip - effective
    cells = [
        name,
        fmt(design, 5),
        fmt(written, 5),
        fmt(tip, 5),
        f"{tolerance:.3f}",
        fmt(effective, 5),
        fmt(d_cota),
        level_state(d_cota, tolerance) or "",
    ]
    return ",".join(cells)


def generate_cota() -> str:
    header = [
        "# Diferencia de cota de la punta y su estado de nivel (S019). Generado por tools/replanteo/generar_informe.py; no editar a mano.",
        "# cota_efectiva: la escrita si hay y, si no, la del punto de diseño. d_cota: punta menos cota efectiva (positiva en desmonte).",
        "# estado: CUT (desmonte), FILL (terraplén) o ON_GRADE (en cota, |d_cota| <= tol_v); vacío sin cota o sin altura en la punta.",
        COTA_HEADER,
    ]
    return "\n".join(header + [cota_row(c) for c in COTA_CASES]) + "\n"


# ---------------------------------------------------------------------------------------------------------------------
# El informe: puntos de diseño con sus replanteos.
# ---------------------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Stake:
    offset: tuple[float, float, float]  # Este, Norte, Arriba desde el diseño hasta lo medido (m)
    tolerances: tuple[float, float] | None  # horizontal y vertical vigentes al medir; None: replanteo de antes de S019
    written: float | None = None  # cota escrita a mano


@dataclass(frozen=True)
class Design:
    name: str
    shift: tuple[float, float]  # Este y Norte desde el punto base del sitio (m)
    height: float | None  # altura del proyecto del punto de diseño
    stakes: tuple[Stake, ...]
    description: str = ""


TOL = (0.025, 0.03)
UNKNOWN_HEIGHT_BASE = 650.0  # altura medida de un punto sin cota de diseño
BASE_CREATED_MS = 1_760_000_000_000
STEP_MS = 60_000

DESIGNS = (
    Design(
        "P1",
        (0.0, 0.0),
        700.0,
        (
            Stake((0.030, 0.010, 0.050), TOL),  # fuera en H y en V
            Stake((0.020, -0.010, -0.010), TOL),  # dentro
            Stake((0.005, 0.005, 0.045), TOL),  # dentro en H y fuera en V: es el último
        ),
        "Esquina norte",
    ),
    Design("P2", (10.0, 5.0), 701.5, (Stake((0.001, 0.002, 0.001), TOL),), "Eje"),
    Design("P3", (20.0, 10.0), None, (Stake((0.010, 0.010, -0.020), TOL, written=698.25),), "Cota escrita"),
    Design("P4", (30.0, 15.0), None, (Stake((0.015, 0.000, 0.000), TOL),), "Sin cota"),
    Design(
        "P5",
        (40.0, 20.0),
        705.0,
        (Stake((0.010, 0.000, 0.000), None), Stake((0.000, 0.020, 0.010), TOL)),
        "Uno de antes y uno nuevo",
    ),
    Design("P6", (50.0, 25.0), 710.0, (Stake((0.500, 0.200, 2.000), None),), "Solo de antes"),
    Design("P10", (60.0, 30.0), 720.0, (Stake((1.200, -0.800, 3.250), TOL),), "Varios metros"),
)
SELECTION = {"P1", "P3", "P6"}


@dataclass
class Row:
    project: str
    design: str
    number: int
    name: str
    description: str
    created_ms: int
    cells: dict[str, str]
    d_horizontal: float
    d_cota: float | None
    within: bool | None
    last: bool = False
    selected: bool = False


def shifted(point: tuple[float, float, float], offset: tuple[float, float, float]) -> tuple[float, float, float]:
    local = g.generar_vectores.topocentric(*point)
    lon, lat, height = local.transform(*offset, direction=TransformDirection.INVERSE)
    return float(f"{lat:.11f}"), float(f"{lon:.11f}"), float(f"{height:.5f}")


def project_en(site: base.Site, lat: float, lon: float) -> tuple[float | None, float | None]:
    if site.projected is None:
        return None, None
    projection = Transformer.from_crs(site.geographic, site.projected, always_xy=True)
    east, north = projection.transform(lon, lat)
    return round(east, 5), round(north, 5)


def build_rows(site: base.Site) -> list[Row]:
    rows: list[Row] = []
    counter = 0
    for design in DESIGNS:
        d_lat, d_lon, d_gh = shifted(site.design, (design.shift[0], design.shift[1], 0.0))
        d_e, d_n = project_en(site, d_lat, d_lon)
        for index, stake in enumerate(design.stakes):
            counter += 1
            m_lat, m_lon, m_gh = shifted((d_lat, d_lon, d_gh), stake.offset)
            m_e, m_n = project_en(site, m_lat, m_lon)
            if site.projected is None:
                local = g.generar_vectores.topocentric(m_lat, m_lon, m_gh)
                east, north, _ = local.transform(d_lon, d_lat, d_gh)
                d_east, d_north = 0.0 - east, 0.0 - north  # medido menos diseño; el 0.0 evita el cero negativo
            else:
                d_east, d_north = m_e - d_e, m_n - d_n
            used = stake.written if stake.written is not None else design.height
            measured_h = round(used + stake.offset[2], 5) if used is not None else round(UNKNOWN_HEIGHT_BASE + stake.offset[2], 5)
            d_cota = None if used is None else measured_h - used
            d_horizontal = math.hypot(d_east, d_north)
            within = None
            if stake.tolerances is not None:
                tol_h, tol_v = stake.tolerances
                within = d_horizontal <= tol_h and (d_cota is None or abs(d_cota) <= tol_v)
            number = index + 1
            name = f"{design.name}_stkd" + ("" if number == 1 else str(number))
            cells = {
                "proyecto": site.sc,
                "diseno": design.name,
                "numero": str(number),
                "nombre": name,
                "descripcion": design.description,
                "creado_ms": str(BASE_CREATED_MS + counter * STEP_MS),
                "diseno_lat": f"{d_lat:.11f}",
                "diseno_lon": f"{d_lon:.11f}",
                "diseno_gh": f"{d_gh:.5f}",
                "diseno_e": fmt(d_e, 5),
                "diseno_n": fmt(d_n, 5),
                "diseno_h": fmt(design.height, 5),
                "medido_lat": f"{m_lat:.11f}",
                "medido_lon": f"{m_lon:.11f}",
                "medido_gh": f"{m_gh:.5f}",
                "medido_e": fmt(m_e, 5),
                "medido_n": fmt(m_n, 5),
                "medido_h": f"{measured_h:.5f}",
                "cota_escrita": fmt(stake.written, 5),
                "cota_usada": fmt(used, 5),
                "tol_h": "" if stake.tolerances is None else f"{stake.tolerances[0]:.3f}",
                "tol_v": "" if stake.tolerances is None else f"{stake.tolerances[1]:.3f}",
                "d_norte": fmt(d_north),
                "d_este": fmt(d_east),
                "d_horizontal": fmt(d_horizontal),
                "d_cota": fmt(d_cota),
                "dentro": "" if within is None else ("1" if within else "0"),
                "en_seleccion": "1" if design.name in SELECTION else "0",
            }
            rows.append(
                Row(
                    site.sc,
                    design.name,
                    number,
                    name,
                    design.description,
                    BASE_CREATED_MS + counter * STEP_MS,
                    cells,
                    d_horizontal,
                    d_cota,
                    within,
                    selected=design.name in SELECTION,
                )
            )
    mark_last(rows)
    return rows


def mark_last(rows: list[Row]) -> None:
    """El último replanteo de cada punto de diseño es el de mayor fecha de creación."""
    latest: dict[str, Row] = {}
    for row in rows:
        if row.design not in latest or row.created_ms > latest[row.design].created_ms:
            latest[row.design] = row
    for row in rows:
        row.last = latest[row.design] is row
        row.cells["ultimo"] = "1" if row.last else "0"


INFORME_COLUMNS = (
    "proyecto,diseno,numero,nombre,descripcion,creado_ms,diseno_lat,diseno_lon,diseno_gh,diseno_e,diseno_n,diseno_h,"
    "medido_lat,medido_lon,medido_gh,medido_e,medido_n,medido_h,cota_escrita,cota_usada,tol_h,tol_v,d_norte,d_este,"
    "d_horizontal,d_cota,dentro,ultimo,en_seleccion"
)


def generate_informe() -> tuple[str, list[Row]]:
    all_rows: list[Row] = []
    for site in base.SITES:
        all_rows.extend(build_rows(site))
    header = [
        "# Puntos replanteados de tres proyectos (S019). Generado por tools/replanteo/generar_informe.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → 25830, EPSG:4081 → 4083 y plano tangente local",
        "# d_norte, d_este, d_horizontal, d_cota: medido menos diseño (d_cota contra cota_usada). dentro: vacío sin tolerancias (antes de S019).",
        "# ultimo: el replanteo más reciente de su punto de diseño. en_seleccion: los que entran en el informe de una selección.",
        INFORME_COLUMNS,
    ]
    lines = [",".join(row.cells[column] for column in INFORME_COLUMNS.split(",")) for row in all_rows]
    return "\n".join(header + lines) + "\n", all_rows


# ---------------------------------------------------------------------------------------------------------------------
# El resumen: solo el último replanteo de cada punto de diseño (entre los incluidos).
# ---------------------------------------------------------------------------------------------------------------------
RESUMEN_HEADER = "proyecto,ambito,puntos,dentro,fuera,sin_evaluar,h_max,h_media,v_n,v_max,v_media"


def summarize(rows: list[Row]) -> dict[str, float | int | None]:
    latest: dict[str, Row] = {}
    for row in rows:
        if row.design not in latest or row.created_ms > latest[row.design].created_ms:
            latest[row.design] = row
    lasts = list(latest.values())
    vertical = [abs(row.d_cota) for row in lasts if row.d_cota is not None]
    horizontal = [row.d_horizontal for row in lasts]
    return {
        "puntos": len(lasts),
        "dentro": sum(1 for row in lasts if row.within is True),
        "fuera": sum(1 for row in lasts if row.within is False),
        "sin_evaluar": sum(1 for row in lasts if row.within is None),
        "h_max": max(horizontal) if horizontal else None,
        "h_media": statistics.fmean(horizontal) if horizontal else None,
        "v_n": len(vertical),
        "v_max": max(vertical) if vertical else None,
        "v_media": statistics.fmean(vertical) if vertical else None,
    }


def generate_resumen(rows: list[Row]) -> str:
    header = [
        "# Resumen del informe de replanteo (S019). Generado por tools/replanteo/generar_informe.py; no editar a mano.",
        "# Cuenta solo el último replanteo de cada punto de diseño entre los incluidos; v_* solo de los que tienen cota.",
        RESUMEN_HEADER,
    ]
    lines = []
    for site in base.SITES:
        mine = [row for row in rows if row.project == site.sc]
        for scope, included in (("todos", mine), ("seleccion", [row for row in mine if row.selected])):
            s = summarize(included)
            cells = [
                site.sc,
                scope,
                str(s["puntos"]),
                str(s["dentro"]),
                str(s["fuera"]),
                str(s["sin_evaluar"]),
                fmt(s["h_max"]),  # type: ignore[arg-type]
                fmt(s["h_media"]),  # type: ignore[arg-type]
                str(s["v_n"]),
                fmt(s["v_max"]),  # type: ignore[arg-type]
                fmt(s["v_media"]),  # type: ignore[arg-type]
            ]
            lines.append(",".join(cells))
    return "\n".join(header + lines) + "\n"


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    cota = generate_cota()
    informe, rows = generate_informe()
    resumen = generate_resumen(rows)
    for name, content in ((NAME_COTA, cota), (NAME_INFORME, informe), (NAME_RESUMEN, resumen)):
        (OUT / name).write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} caracteres")
    return 0


if __name__ == "__main__":
    sys.exit(main())
