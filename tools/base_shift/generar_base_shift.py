"""Vectores de referencia del desplazamiento de base generados con pyproj/PROJ (constitución nº 2, S020).

Genera en fixtures/base_shift/:
- vectores.csv: por caso, el punto conocido (E, N y altura en el SC del proyecto, la altura puede faltar), la
  posición medida (φ, λ, h elipsoidal en el datum de las correcciones), el vector ECEF conocido − medido
  (conocido a ECEF en el datum del proyecto, medido a ECEF tal cual, sin transformar entre datums; S020/RF-3),
  su equivalente en Este, Norte y altura del SC, su módulo y la clasificación (S020/RF-4 a RF-6).
  Sin altura, el conocido se coloca a la altura de la medida, se toma su Este y su Norte en el plano tangente local
  de la medida y el vector es (Este, Norte, 0) devuelto a ECEF (S020/RF-6, plan D9).
- aplicados.csv: tres puntos del suelo por caso (el medido y dos desplazados en su plano local), el vector sumado en
  ECEF y las coordenadas del SC del resultado (S020/RF-7).

Las alturas se trabajan como elipsoidales (sistema de alturas elipsoidal del proyecto) para no depender del geoide.
El caso ITRF obtiene la posición medida con la transformación ETRS89 (EPSG:4937) → ITRF2014 (EPSG:7912) en la época 2026,8.

Uso: uv run --project tools tools/base_shift/generar_base_shift.py
Es determinista: la misma versión de pyproj/PROJ da los mismos ficheros.
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

import pyproj
from pyproj import Transformer
from pyproj.enums import TransformDirection

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "base_shift"

CART = Transformer.from_crs("EPSG:4937", "EPSG:4936", always_xy=True)
EPOCH = 2026.8
REVIEW_M = 10.0
REJECT_M = 1000.0


@dataclass(frozen=True)
class Case:
    name: str
    datum: str  # ETRS89 o REGCAN95
    zone: int
    corrections: str  # ETRS89, REGCAN95 o ITRF
    lat: float  # el punto verdadero
    lon: float
    h: float
    offset: tuple[float, float, float]  # Este, Norte, Up de la base en SINGLE, en m
    no_height: bool = False


def horizontal_crs(case: Case) -> tuple[str, str]:
    """(geográficas 2D, UTM) del proyecto."""
    if case.datum == "REGCAN95":
        return "EPSG:4081", {27: "EPSG:4082", 28: "EPSG:4083"}[case.zone]
    return "EPSG:4258", f"EPSG:258{case.zone}"


def topocentric(lat: float, lon: float, h: float) -> Transformer:
    return Transformer.from_pipeline(
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=cart +ellps=GRS80 "
        f"+step +proj=topocentric +ellps=GRS80 +lat_0={lat!r} +lon_0={lon!r} +h_0={h!r}"
    )


def enu_offset(lat: float, lon: float, h: float, e: float, n: float, u: float) -> tuple[float, float, float]:
    lon2, lat2, h2 = topocentric(lat, lon, h).transform(e, n, u, direction=TransformDirection.INVERSE)
    return lat2, lon2, h2


def ecef(lat: float, lon: float, h: float) -> tuple[float, float, float]:
    return CART.transform(lon, lat, h)


def geodetic(x: float, y: float, z: float) -> tuple[float, float, float]:
    lon, lat, h = CART.transform(x, y, z, direction=TransformDirection.INVERSE)
    return lat, lon, h


def measured_position(case: Case) -> tuple[float, float, float]:
    """Posición que da el receptor: la verdadera, en el datum de las correcciones, más la base desplazada."""
    lat, lon, h = case.lat, case.lon, case.h
    if case.corrections == "ITRF":
        lon, lat, h, _ = Transformer.from_crs("EPSG:4937", "EPSG:7912", always_xy=True).transform(lon, lat, h, EPOCH)
    return enu_offset(lat, lon, h, *case.offset)


def project_en(case: Case, lat: float, lon: float) -> tuple[float, float]:
    geographic, utm = horizontal_crs(case)
    return Transformer.from_crs(geographic, utm, always_xy=True).transform(lon, lat)


def project_inverse(case: Case, east: float, north: float) -> tuple[float, float]:
    geographic, utm = horizontal_crs(case)
    lon, lat = Transformer.from_crs(geographic, utm, always_xy=True).transform(
        east, north, direction=TransformDirection.INVERSE
    )
    return lat, lon


def classification(module: float) -> str:
    if module > REJECT_M:
        return "REJECTED"
    return "NEEDS_CONFIRMATION" if module > REVIEW_M else "OK"


def fmt(value: float | None) -> str:
    return "" if value is None else repr(float(value))


def header(kind: str) -> list[str]:
    return [
        f"# {kind}. Generado por tools/base_shift/generar_base_shift.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}; época ITRF={EPOCH}",
    ]


def cases() -> list[Case]:
    madrid = (40.4168, -3.7038, 650.123)
    tenerife = (28.27223, -16.64251, 3000.0)
    barcelona = (41.3874, 2.1686, 12.5)
    out = [
        Case("etrs89-single-3m", "ETRS89", 30, "ETRS89", *madrid, (2.1, -1.7, 1.2)),
        Case("etrs89-sin-altura", "ETRS89", 30, "ETRS89", *madrid, (2.1, -1.7, 1.2), no_height=True),
        Case("etrs89-huso31", "ETRS89", 31, "ETRS89", *barcelona, (-0.8, 4.4, -2.5)),
        Case("regcan95-huso28", "REGCAN95", 28, "REGCAN95", *tenerife, (-3.2, 0.6, 0.9)),
        Case("itrf-sobre-etrs89", "ETRS89", 30, "ITRF", *madrid, (0.0, 0.0, 0.0)),
        Case("itrf-mas-single", "ETRS89", 30, "ITRF", *madrid, (2.1, -1.7, 1.2)),
        Case("itrf-sin-altura", "ETRS89", 30, "ITRF", *madrid, (2.1, -1.7, 1.2), no_height=True),
        Case("cero", "ETRS89", 30, "ETRS89", *madrid, (0.0, 0.0, 0.0)),
    ]
    # Módulos alrededor de los umbrales de 10 y 1.000 m, con la altura incluida en el módulo.
    for length in (9.99, 10.01, 999.9, 1000.1):
        az, el = math.radians(37.0), math.radians(10.0)
        offset = (
            length * math.cos(el) * math.sin(az),
            length * math.cos(el) * math.cos(az),
            length * math.sin(el),
        )
        out.append(Case(f"modulo-{length}".replace(".", "_"), "ETRS89", 30, "ETRS89", *madrid, offset))
    return out


def solve(case: Case) -> dict[str, float | str | None]:
    lat_m, lon_m, h_m = measured_position(case)
    east_k, north_k = project_en(case, case.lat, case.lon)
    lat_k, lon_k = project_inverse(case, east_k, north_k)
    height_k = None if case.no_height else case.h
    xm, ym, zm = ecef(lat_m, lon_m, h_m)
    if height_k is None:
        # Sin altura: el conocido a la altura de la medida; Este y Norte en el plano local de la medida; Up = 0.
        e, n, _ = topocentric(lat_m, lon_m, h_m).transform(lon_k, lat_k, h_m)
        lat_s, lon_s, h_s = enu_offset(lat_m, lon_m, h_m, e, n, 0.0)
        xs, ys, zs = ecef(lat_s, lon_s, h_s)
        dx, dy, dz = xs - xm, ys - ym, zs - zm
    else:
        xk, yk, zk = ecef(lat_k, lon_k, height_k)
        dx, dy, dz = xk - xm, yk - ym, zk - zm
    lat_a, lon_a, h_a = geodetic(xm + dx, ym + dy, zm + dz)
    east_m, north_m = project_en(case, lat_m, lon_m)
    east_a, north_a = project_en(case, lat_a, lon_a)
    module = math.sqrt(dx * dx + dy * dy + dz * dz)
    return {
        "known_e": east_k, "known_n": north_k, "known_h": height_k,
        "lat": lat_m, "lon": lon_m, "h": h_m,
        "dx": dx, "dy": dy, "dz": dz,
        "de": east_a - east_m, "dn": north_a - north_m, "dh": h_a - h_m,
        "module": module, "class": classification(module),
    }


def vectors() -> list[str]:
    lines = header("Desplazamiento de base: vector ECEF conocido − medido, su equivalente en el SC, su módulo y su clasificación") + [
        "caso,datum_proyecto,datum_correcciones,huso,conocido_este,conocido_norte,conocido_altura,"
        "medido_latitud,medido_longitud,medido_altura,dx,dy,dz,delta_este,delta_norte,delta_altura,modulo,clasificacion"
    ]
    for case in cases():
        r = solve(case)
        lines.append(
            ",".join(
                [case.name, case.datum, case.corrections, str(case.zone)]
                + [fmt(r[k]) for k in ("known_e", "known_n", "known_h", "lat", "lon", "h", "dx", "dy", "dz", "de", "dn", "dh", "module")]
                + [str(r["class"])]
            )
        )
    return lines


POINTS = ((0.0, 0.0, 0.0), (35.0, 80.0, 1.5), (-420.0, 310.0, -6.0))


def applied() -> list[str]:
    lines = header("Desplazamiento de base aplicado: punta del suelo + vector ECEF → coordenadas del SC") + [
        "caso,huso,latitud,longitud,altura,dx,dy,dz,este,norte,altura_proyecto"
    ]
    for case in cases():
        r = solve(case)
        for e, n, u in POINTS:
            lat, lon, h = enu_offset(float(r["lat"]), float(r["lon"]), float(r["h"]), e, n, u)
            x, y, z = ecef(lat, lon, h)
            lat_s, lon_s, h_s = geodetic(x + r["dx"], y + r["dy"], z + r["dz"])
            east, north = project_en(case, lat_s, lon_s)
            lines.append(",".join([case.name, str(case.zone)] + [fmt(v) for v in (lat, lon, h, r["dx"], r["dy"], r["dz"], east, north, h_s)]))
    return lines


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, lines in (("vectores.csv", vectors()), ("aplicados.csv", applied())):
        (OUT / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
