"""Vectores de referencia de las proyecciones de S032, generados con pyproj/PROJ (constitución nº 2, ADR-2).

Cada familia tiene su generador y su CSV en `fixtures/geodesia/` (los llama `generar_vectores.py`):
- lcc.csv: Cónica Conforme de Lambert 1SP y 2SP (EPSG 9801 y 9802): ida, vuelta, factor de escala y convergencia (S032/RF-1, RF-2).
- mercator.csv: Mercator A y B (EPSG 9804 y 9805) con lo mismo (S032/RF-4, RF-5).

Cómo se calcula cada fila:
- **Casos de EPSG**: el Este y el Norte salen de la propia definición del CRS en la base de PROJ (`Transformer` desde su CRS geográfico), que es
  la referencia independiente; se pasan a metros con la unidad de su eje.
- **Casos propios** (esfera, hemisferio sur, paralelos iguales…): salen de la cadena `+proj=…` equivalente.
- **Comprobación cruzada**: en los de EPSG, la cadena `+proj=…` construida aquí con los parámetros leídos de la base debe dar el mismo
  resultado (≤ 1 µm) o la generación falla; así se detecta un parámetro mal leído.
- **Factor y convergencia**: `Proj.get_factors` de esa cadena. PROJ cuenta `lon_0` desde el meridiano de origen (`+pm`) y recibe longitudes
  de Greenwich al proyectar, pero `get_factors` espera la longitud ya medida desde ese meridiano; por eso la cadena lleva `lon_0` de
  Greenwich y no `+pm`.

Las columnas `falso_este` y `falso_norte` van en la unidad lineal del SC (`unidad_m` metros por unidad), como los define EPSG; `este_m` y
`norte_m` van siempre en metros. `meridiano_origen_deg` es la longitud del meridiano de origen respecto a Greenwich (positiva al este).

Es determinista: la misma versión de pyproj/PROJ y la misma semilla dan el mismo fichero.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from pathlib import Path

import pyproj
from pyproj import CRS, Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "geodesia"

SEMILLA_LCC = 20261120
SEMILLA_MERCATOR = 20261121
TOLERANCIA_CRUZADA_M = 1e-6


def header(tipo: str) -> list[str]:
    return [
        f"# {tipo}. Generado por tools/geodesia/proyecciones.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
    ]


def fmt(valor: float | None) -> str:
    return "" if valor is None else repr(float(valor))


@dataclass(frozen=True)
class Elipsoide:
    semieje: float
    achatamiento_inverso: float  # 0 = esfera

    def argumentos_proj(self) -> str:
        if self.achatamiento_inverso == 0:
            return f"+a={self.semieje!r} +b={self.semieje!r}"
        return f"+a={self.semieje!r} +rf={self.achatamiento_inverso!r}"


GRS80 = Elipsoide(6378137.0, 298.257222101)
INTERNACIONAL = Elipsoide(6378388.0, 297.0)
ESFERA = Elipsoide(6378137.0, 0.0)


def a_grados(valor: float, factor_a_radianes: float) -> float:
    return valor * factor_a_radianes * 180.0 / math.pi


def limitar_latitud(latitud: float) -> float:
    return max(-85.0, min(85.0, latitud))


# --- Cónica Conforme de Lambert --------------------------------------------------------------------------------------------------

# CRS de EPSG que se usan como casos y el método que debe tener cada uno en la base de PROJ (se comprueba al generar y en el test).
EPSG_LCC = {
    2154: "(2SP)",  # RGF93 / Lambert-93
    3034: "(2SP)",  # ETRS89-extended / LCC Europe
    32040: "(2SP)",  # NAD27 / Texas South Central, en pies topográficos de EE. UU.
    27571: "(1SP)",  # NTF (Paris) / Lambert zone I
    27572: "(1SP)",  # NTF (Paris) / Lambert zone II étendu
}


@dataclass(frozen=True)
class CasoLcc:
    nombre: str
    metodo: str  # "1SP" o "2SP"
    elipsoide: Elipsoide
    meridiano_origen_deg: float
    unidad_m: float
    lat_origen_deg: float  # 2SP: del origen falso; 1SP: del origen natural
    lon_origen_deg: float  # respecto al meridiano de origen
    lat1_deg: float | None
    lat2_deg: float | None
    k0: float | None
    falso_este: float  # en la unidad del SC
    falso_norte: float
    epsg: int | None = None

    @property
    def lon_origen_greenwich_deg(self) -> float:
        return self.lon_origen_deg + self.meridiano_origen_deg

    def cadena_proj(self) -> str:
        """La cadena `+proj=lcc` equivalente, con `lon_0` de Greenwich y los falsos en metros (sin `+pm`)."""
        comunes = (
            f"+lon_0={self.lon_origen_greenwich_deg!r} +x_0={self.falso_este * self.unidad_m!r} "
            f"+y_0={self.falso_norte * self.unidad_m!r} {self.elipsoide.argumentos_proj()} +units=m +no_defs"
        )
        if self.metodo == "2SP":
            return f"+proj=lcc +lat_1={self.lat1_deg!r} +lat_2={self.lat2_deg!r} +lat_0={self.lat_origen_deg!r} {comunes}"
        return f"+proj=lcc +lat_1={self.lat_origen_deg!r} +lat_0={self.lat_origen_deg!r} +k_0={self.k0!r} {comunes}"


def caso_epsg_lcc(codigo: int) -> CasoLcc:
    """Lee de la base de PROJ los parámetros de un CRS de EPSG y comprueba que es del método esperado."""
    crs = CRS.from_epsg(codigo)
    metodo = crs.coordinate_operation.method_name
    esperado = EPSG_LCC[codigo]
    assert esperado in metodo, f"EPSG:{codigo} es «{metodo}», no {esperado}"
    parametros = {p.name: p for p in crs.coordinate_operation.params}
    elipsoide = Elipsoide(crs.ellipsoid.semi_major_metre, crs.ellipsoid.inverse_flattening)
    meridiano = crs.geodetic_crs.prime_meridian
    meridiano_deg = a_grados(meridiano.longitude, meridiano.unit_conversion_factor)

    def angulo(nombre: str) -> float:
        p = parametros[nombre]
        return a_grados(p.value, p.unit_conversion_factor)

    def longitud(nombre: str) -> tuple[float, float]:
        p = parametros[nombre]
        return p.value, p.unit_conversion_factor

    if esperado == "(2SP)":
        este, unidad = longitud("Easting at false origin")
        norte, _ = longitud("Northing at false origin")
        return CasoLcc(
            f"EPSG:{codigo}", "2SP", elipsoide, meridiano_deg, unidad,
            angulo("Latitude of false origin"), angulo("Longitude of false origin"),
            angulo("Latitude of 1st standard parallel"), angulo("Latitude of 2nd standard parallel"), None,
            este, norte, codigo,
        )  # fmt: skip
    este, unidad = longitud("False easting")
    norte, _ = longitud("False northing")
    return CasoLcc(
        f"EPSG:{codigo}", "1SP", elipsoide, meridiano_deg, unidad,
        angulo("Latitude of natural origin"), angulo("Longitude of natural origin"), None, None,
        parametros["Scale factor at natural origin"].value, este, norte, codigo,
    )  # fmt: skip


def casos_lcc() -> list[CasoLcc]:
    propios = [
        # Hemisferio sur.
        CasoLcc("sur-2sp", "2SP", GRS80, 0.0, 1.0, -30.0, -60.0, -20.0, -40.0, None, 500_000.0, 200_000.0),
        # Esfera (aplanamiento 0).
        CasoLcc("esfera-2sp", "2SP", ESFERA, 0.0, 1.0, 40.0, 0.0, 33.0, 45.0, None, 0.0, 0.0),
        # Los dos paralelos iguales: equivale a 1SP con k0 = 1.
        CasoLcc("paralelos-iguales-2sp", "2SP", GRS80, 0.0, 1.0, 45.0, -100.0, 45.0, 45.0, None, 1_000.0, -2_000.0),
        # 1SP en el hemisferio sur, con otro elipsoide y factor de escala.
        CasoLcc("sur-1sp", "1SP", INTERNACIONAL, 0.0, 1.0, -12.0, 30.0, None, None, 0.99, 10_000.0, 5_000.0),
    ]
    return [caso_epsg_lcc(codigo) for codigo in EPSG_LCC] + propios


def puntos_lcc(caso: CasoLcc, rng: random.Random) -> list[tuple[float, float]]:
    """Una rejilla alrededor del origen (en Greenwich) y unos puntos al azar dentro de ±6° de él."""
    lon0 = caso.lon_origen_greenwich_deg
    puntos = [(limitar_latitud(caso.lat_origen_deg + dlat), lon0 + dlon) for dlat in (-5.0, 0.0, 5.0) for dlon in (-6.0, -3.0, 0.0, 3.0, 6.0)]
    puntos += [(limitar_latitud(caso.lat_origen_deg + rng.uniform(-5.0, 5.0)), lon0 + rng.uniform(-6.0, 6.0)) for _ in range(5)]
    return puntos


@dataclass(frozen=True)
class ReferenciaEpsg:
    """Un CRS de EPSG como referencia independiente: su transformador y las unidades de sus ejes."""

    transformador: Transformer
    unidad_eje_m: float  # metros por unidad del eje del CRS proyectado
    unidad_angulo_rad: float  # radianes por unidad del CRS geográfico (grados, o gon en el de París)

    @staticmethod
    def de(codigo: int) -> ReferenciaEpsg:
        crs = CRS.from_epsg(codigo)
        return ReferenciaEpsg(
            Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True),
            crs.axis_info[0].unit_conversion_factor,
            crs.geodetic_crs.axis_info[0].unit_conversion_factor,
        )

    def proyectar(self, lat: float, lon_local: float) -> tuple[float, float]:
        """Este y Norte en metros de un punto con latitud y longitud (esta, desde el meridiano de origen) en grados."""
        a_unidad = math.pi / 180.0 / self.unidad_angulo_rad
        este, norte = self.transformador.transform(lon_local * a_unidad, lat * a_unidad)
        return este * self.unidad_eje_m, norte * self.unidad_eje_m


def referencia(caso: CasoLcc | CasoMercator, proj: pyproj.Proj, epsg: ReferenciaEpsg | None, lat: float, lon: float):
    """(Este, Norte, factor, convergencia) de un punto, y la comprobación cruzada de los casos de EPSG."""
    este, norte = proj(lon, lat)
    if epsg is not None:
        # El CRS geográfico de EPSG cuenta las longitudes desde su meridiano de origen: se le pasa la de Greenwich menos ese meridiano.
        este_epsg, norte_epsg = epsg.proyectar(lat, lon - caso.meridiano_origen_deg)
        if abs(este_epsg - este) > TOLERANCIA_CRUZADA_M or abs(norte_epsg - norte) > TOLERANCIA_CRUZADA_M:
            raise AssertionError(f"{caso.nombre} ({lat}, {lon}): EPSG da ({este_epsg}, {norte_epsg}) y la cadena ({este}, {norte})")
        este, norte = este_epsg, norte_epsg
    factores = proj.get_factors(lon, lat)
    return este, norte, factores.parallel_scale, factores.meridian_convergence


COLUMNAS_LCC = (
    "caso,metodo,semieje,achatamiento_inverso,meridiano_origen_deg,unidad_m,lat_origen,lon_origen,lat1,lat2,k0,falso_este,falso_norte,"
    "latitud,longitud,este_m,norte_m,factor_escala,convergencia_grados"
)


def lcc_vectors(rng: random.Random) -> list[str]:
    """S032/RF-1, RF-2: Este, Norte, factor de escala (parallel_scale) y convergencia (grados) de la Cónica Conforme de Lambert."""
    lineas = header("Cónica Conforme de Lambert 1SP y 2SP (pyproj/PROJ, Transformer de EPSG y get_factors)") + [COLUMNAS_LCC]
    for caso in casos_lcc():
        proj = pyproj.Proj(caso.cadena_proj())
        epsg = ReferenciaEpsg.de(caso.epsg) if caso.epsg is not None else None
        for lat, lon in puntos_lcc(caso, rng):
            este, norte, k, gamma = referencia(caso, proj, epsg, lat, lon)
            lineas.append(
                ",".join(
                    [
                        caso.nombre, caso.metodo, fmt(caso.elipsoide.semieje), fmt(caso.elipsoide.achatamiento_inverso),
                        fmt(caso.meridiano_origen_deg), fmt(caso.unidad_m), fmt(caso.lat_origen_deg), fmt(caso.lon_origen_deg),
                        fmt(caso.lat1_deg), fmt(caso.lat2_deg), fmt(caso.k0), fmt(caso.falso_este), fmt(caso.falso_norte),
                        fmt(lat), fmt(lon), fmt(este), fmt(norte), fmt(k), fmt(gamma),
                    ]
                )  # fmt: skip
            )
    return lineas


# --- Mercator A y B --------------------------------------------------------------------------------------------------------------

# CRS de EPSG de Mercator y su variante en la base de PROJ (se comprueba al generar y en el test).
EPSG_MERCATOR = {
    3002: "(variant A)",  # Makassar / NEIEZ
    3395: "(variant A)",  # WGS 84 / World Mercator
    3388: "(variant B)",  # Pulkovo 1942 / Caspian Sea Mercator
}


@dataclass(frozen=True)
class CasoMercator:
    nombre: str
    metodo: str  # "A" o "B"
    elipsoide: Elipsoide
    meridiano_origen_deg: float
    unidad_m: float
    lon_origen_deg: float
    k0: float | None  # variante A
    lat_estandar_deg: float | None  # variante B
    falso_este: float
    falso_norte: float
    epsg: int | None = None

    def cadena_proj(self) -> str:
        comunes = (
            f"+lon_0={self.lon_origen_deg + self.meridiano_origen_deg!r} +x_0={self.falso_este * self.unidad_m!r} "
            f"+y_0={self.falso_norte * self.unidad_m!r} {self.elipsoide.argumentos_proj()} +units=m +no_defs"
        )
        if self.metodo == "A":
            return f"+proj=merc +k_0={self.k0!r} {comunes}"
        return f"+proj=merc +lat_ts={self.lat_estandar_deg!r} {comunes}"


def caso_epsg_mercator(codigo: int) -> CasoMercator:
    crs = CRS.from_epsg(codigo)
    metodo = crs.coordinate_operation.method_name
    esperado = EPSG_MERCATOR[codigo]
    assert esperado in metodo, f"EPSG:{codigo} es «{metodo}», no {esperado}"
    parametros = {p.name: p for p in crs.coordinate_operation.params}
    elipsoide = Elipsoide(crs.ellipsoid.semi_major_metre, crs.ellipsoid.inverse_flattening)
    meridiano = crs.geodetic_crs.prime_meridian
    meridiano_deg = a_grados(meridiano.longitude, meridiano.unit_conversion_factor)
    longitud = parametros["Longitude of natural origin"]
    este = parametros["False easting"]
    norte = parametros["False northing"]
    comunes = (elipsoide, meridiano_deg, este.unit_conversion_factor, a_grados(longitud.value, longitud.unit_conversion_factor))
    if "variant A" in metodo:
        # El origen natural de la variante A es el ecuador (así la define EPSG): el catálogo excluye lo que no lo cumpla.
        assert parametros["Latitude of natural origin"].value == 0.0, f"EPSG:{codigo}: la variante A no está en el ecuador"
        return CasoMercator(
            f"EPSG:{codigo}", "A", *comunes, parametros["Scale factor at natural origin"].value, None, este.value, norte.value, codigo
        )
    paralelo = parametros["Latitude of 1st standard parallel"]
    return CasoMercator(
        f"EPSG:{codigo}", "B", *comunes, None, a_grados(paralelo.value, paralelo.unit_conversion_factor), este.value, norte.value, codigo
    )


def casos_mercator() -> list[CasoMercator]:
    propios = [
        CasoMercator("esfera-a", "A", ESFERA, 0.0, 1.0, 10.0, 1.0, None, 0.0, 0.0),
        CasoMercator("esfera-b", "B", ESFERA, 0.0, 1.0, -30.0, None, 45.0, 1_000.0, -2_000.0),
        CasoMercator("sur-a", "A", GRS80, 0.0, 1.0, -60.0, 0.9996, None, 500_000.0, 10_000_000.0),
        CasoMercator("sur-b", "B", INTERNACIONAL, 0.0, 1.0, 20.0, None, -35.0, 100.0, 200.0),
        # Paralelo estándar en el ecuador: equivale a la variante A con factor 1.
        CasoMercator("b-ecuador", "B", GRS80, 0.0, 1.0, 5.0, None, 0.0, 0.0, 0.0),
    ]
    return [caso_epsg_mercator(codigo) for codigo in EPSG_MERCATOR] + propios


def puntos_mercator(caso: CasoMercator, rng: random.Random) -> list[tuple[float, float]]:
    """Latitudes de −60° a 60° y longitudes hasta ±20° del meridiano de origen, más unos puntos al azar."""
    lon0 = caso.lon_origen_deg + caso.meridiano_origen_deg
    puntos = [(lat, lon0 + dlon) for lat in (-60.0, -30.0, 0.0, 30.0, 60.0) for dlon in (-20.0, -10.0, 0.0, 10.0, 20.0)]
    puntos += [(rng.uniform(-70.0, 70.0), lon0 + rng.uniform(-20.0, 20.0)) for _ in range(5)]
    return puntos


COLUMNAS_MERCATOR = (
    "caso,metodo,semieje,achatamiento_inverso,meridiano_origen_deg,unidad_m,lon_origen,k0,lat_estandar,falso_este,falso_norte,"
    "latitud,longitud,este_m,norte_m,factor_escala,convergencia_grados"
)


def mercator_vectors(rng: random.Random) -> list[str]:
    """S032/RF-4, RF-5: Este, Norte, factor de escala y convergencia (siempre 0) de la Mercator A y B."""
    lineas = header("Mercator A y B (pyproj/PROJ, Transformer de EPSG y get_factors)") + [COLUMNAS_MERCATOR]
    for caso in casos_mercator():
        proj = pyproj.Proj(caso.cadena_proj())
        epsg = ReferenciaEpsg.de(caso.epsg) if caso.epsg is not None else None
        for lat, lon in puntos_mercator(caso, rng):
            este, norte, k, gamma = referencia(caso, proj, epsg, lat, lon)
            lineas.append(
                ",".join(
                    [
                        caso.nombre, caso.metodo, fmt(caso.elipsoide.semieje), fmt(caso.elipsoide.achatamiento_inverso),
                        fmt(caso.meridiano_origen_deg), fmt(caso.unidad_m), fmt(caso.lon_origen_deg), fmt(caso.k0),
                        fmt(caso.lat_estandar_deg), fmt(caso.falso_este), fmt(caso.falso_norte),
                        fmt(lat), fmt(lon), fmt(este), fmt(norte), fmt(k), fmt(gamma),
                    ]
                )  # fmt: skip
            )
    return lineas


def todos() -> list[tuple[str, list[str]]]:
    """Todos los ficheros de este módulo, con su nombre: los usa `generar_vectores.py` y `main` de aquí."""
    return [
        ("lcc.csv", lcc_vectors(random.Random(SEMILLA_LCC))),
        ("mercator.csv", mercator_vectors(random.Random(SEMILLA_MERCATOR))),
    ]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for nombre, lineas in todos():
        (OUT / nombre).write_text("\n".join(lineas) + "\n", encoding="utf-8", newline="\n")
        print(f"{nombre}: {len(lineas) - 3} vectores")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
