"""Vectores de referencia de las proyecciones de S032, generados con pyproj/PROJ (constitución nº 2, ADR-2).

Cada familia tiene su generador y su CSV en `fixtures/geodesia/` (los llama `generar_vectores.py`):
- lcc.csv: Cónica Conforme de Lambert 1SP y 2SP (EPSG 9801 y 9802): ida, vuelta, factor de escala y convergencia (S032/RF-1, RF-2).
- mercator.csv: Mercator A y B (EPSG 9804 y 9805) con lo mismo (S032/RF-4, RF-5).
- omerc.csv: Hotine Oblique Mercator A y B (EPSG 9812 y 9815) con lo mismo (S032/RF-7, RF-8).
- stereo_oblicua.csv: Estereográfica oblicua (EPSG 9809) con lo mismo y el punto publicado de GN 7-2 (S032/RF-10, RF-11).
- stereo_polar.csv: Estereográfica polar A, B y C (EPSG 9810, 9829 y 9830) con lo mismo, en los dos polos (S032/RF-13, RF-14).

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
SEMILLA_OMERC = 20261122
SEMILLA_STEREO_OBLICUA = 20261123
SEMILLA_STEREO_POLAR = 20261124
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


def normalizar_longitud(longitud: float) -> float:
    """La longitud en (−180°, 180°]: la que devuelve la vuelta de una proyección."""
    envuelta = (longitud + 180.0) % 360.0 - 180.0
    return 180.0 if envuelta == -180.0 else envuelta


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


# --- Oblique Mercator (Hotine) A y B ---------------------------------------------------------------------------------------------

# La base de PROJ dice qué variante es cada CRS (no siempre la que se recuerda: Borneo es la B y Alaska 1 la A).
EPSG_OMERC = {
    26931: "(variant A)",  # NAD83 / Alaska zone 1
    3375: "(variant A)",  # GDM2000 / Peninsula RSO (azimut distinto del ángulo de rectificación)
    29873: "(variant B)",  # Timbalai 1948 / RSO Borneo (m)
}


@dataclass(frozen=True)
class CasoOmerc:
    nombre: str
    metodo: str  # "A" o "B"
    elipsoide: Elipsoide
    meridiano_origen_deg: float
    unidad_m: float
    lat_centro_deg: float
    lon_centro_deg: float  # respecto al meridiano de origen
    azimut_deg: float
    angulo_rectificacion_deg: float
    k0: float
    falso_este: float  # A: en el origen natural; B: en el centro de la proyección
    falso_norte: float
    epsg: int | None = None

    def cadena_proj(self) -> str:
        """`+proj=omerc` con `lonc` de Greenwich; sin `+no_uoff` es la variante B y con él, la A."""
        variante_a = " +no_uoff" if self.metodo == "A" else ""
        return (
            f"+proj=omerc +lat_0={self.lat_centro_deg!r} +lonc={self.lon_centro_deg + self.meridiano_origen_deg!r} "
            f"+alpha={self.azimut_deg!r} +gamma={self.angulo_rectificacion_deg!r} +k_0={self.k0!r} "
            f"+x_0={self.falso_este * self.unidad_m!r} +y_0={self.falso_norte * self.unidad_m!r} "
            f"{self.elipsoide.argumentos_proj()} +units=m +no_defs{variante_a}"
        )


def caso_epsg_omerc(codigo: int) -> CasoOmerc:
    crs = CRS.from_epsg(codigo)
    metodo = crs.coordinate_operation.method_name
    esperado = EPSG_OMERC[codigo]
    assert esperado in metodo, f"EPSG:{codigo} es «{metodo}», no {esperado}"
    parametros = {p.name: p for p in crs.coordinate_operation.params}
    elipsoide = Elipsoide(crs.ellipsoid.semi_major_metre, crs.ellipsoid.inverse_flattening)
    meridiano = crs.geodetic_crs.prime_meridian
    meridiano_deg = a_grados(meridiano.longitude, meridiano.unit_conversion_factor)

    def angulo(nombre: str) -> float:
        p = parametros[nombre]
        return a_grados(p.value, p.unit_conversion_factor)

    variante = "A" if "variant A" in metodo else "B"
    este = parametros["False easting" if variante == "A" else "Easting at projection centre"]
    norte = parametros["False northing" if variante == "A" else "Northing at projection centre"]
    return CasoOmerc(
        f"EPSG:{codigo}", variante, elipsoide, meridiano_deg, este.unit_conversion_factor,
        angulo("Latitude of projection centre"), angulo("Longitude of projection centre"),
        angulo("Azimuth at projection centre"), angulo("Angle from Rectified to Skew Grid"),
        parametros["Scale factor at projection centre"].value, este.value, norte.value, codigo,
    )  # fmt: skip


def casos_omerc() -> list[CasoOmerc]:
    def c(nombre, metodo, elipsoide, lat, lon, azimut, gamma, k0=0.9996, este=1_000.0, norte=2_000.0):
        return CasoOmerc(nombre, metodo, elipsoide, 0.0, 1.0, lat, lon, azimut, gamma, k0, este, norte)

    propios = [
        # Azimut de la línea inicial de 0°, 90°, 180° y 270° (con 90° exactos PROJ da la variante A como si fuera la B: es una rareza suya y
        # el caso de 90° va solo en B; la A se comprueba a 89,9999° y 90,0001° en el test de Kotlin, que la sigue de forma continua).
        c("a-azimut-0", "A", GRS80, 45.0, 10.0, 0.0, 0.0),
        c("b-azimut-90", "B", GRS80, 45.0, 10.0, 90.0, 90.0),
        c("a-azimut-120", "A", GRS80, 45.0, 10.0, 120.0, 120.0),
        c("b-azimut-180", "B", GRS80, 45.0, 10.0, 180.0, 180.0),
        c("b-azimut-270", "B", GRS80, 45.0, 10.0, 270.0, 270.0),
        # Ángulo de rectificación distinto del azimut.
        c("b-gamma-distinto", "B", GRS80, 45.0, 10.0, 30.0, 10.0),
        # Centro de la proyección en el ecuador.
        c("b-ecuador", "B", GRS80, 0.0, 10.0, 30.0, 30.0),
        # Esfera, y hemisferio sur con elipsoide distinto.
        c("esfera-a", "A", ESFERA, 30.0, -20.0, 60.0, 60.0, 1.0, 0.0, 0.0),
        c("esfera-b", "B", ESFERA, 30.0, -20.0, 60.0, 45.0, 1.0, 0.0, 0.0),
        c("sur-a", "A", GRS80, -30.0, 120.0, 60.0, 75.0, 0.9999, 500_000.0, 1_000_000.0),
        c("sur-b", "B", INTERNACIONAL, -30.0, 120.0, 200.0, 190.0, 0.9999, 500_000.0, 1_000_000.0),
    ]
    return [caso_epsg_omerc(codigo) for codigo in EPSG_OMERC] + propios


def puntos_omerc(caso: CasoOmerc, rng: random.Random) -> list[tuple[float, float]]:
    """Una rejilla alrededor del centro (en Greenwich) de ±4° de latitud y ±5° de longitud, y unos puntos al azar. La columna central se
    corre 0,001°: justo en el meridiano de la línea inicial con azimut de 90° o 270° PROJ cambia de fórmula (u = A·λ) y da el signo opuesto
    en 270°; el centro exacto lo comprueba el test de Kotlin con la propiedad que define la variante B (el centro cae en su Este y Norte)."""
    lon0 = caso.lon_centro_deg + caso.meridiano_origen_deg
    puntos = [(limitar_latitud(caso.lat_centro_deg + dlat), lon0 + dlon) for dlat in (-4.0, 0.0, 4.0) for dlon in (-5.0, -2.5, 0.001, 2.5, 5.0)]
    puntos += [(limitar_latitud(caso.lat_centro_deg + rng.uniform(-4.0, 4.0)), lon0 + rng.uniform(-5.0, 5.0)) for _ in range(5)]
    return puntos


COLUMNAS_OMERC = (
    "caso,metodo,semieje,achatamiento_inverso,meridiano_origen_deg,unidad_m,lat_centro,lon_centro,azimut,angulo_rectificacion,k0,"
    "falso_este,falso_norte,latitud,longitud,este_m,norte_m,factor_escala,convergencia_grados"
)


def omerc_vectors(rng: random.Random) -> list[str]:
    """S032/RF-7, RF-8: Este, Norte, factor de escala y convergencia (grados) de la Oblique Mercator A y B."""
    lineas = header("Oblique Mercator (Hotine) A y B (pyproj/PROJ, Transformer de EPSG y get_factors)") + [COLUMNAS_OMERC]
    for caso in casos_omerc():
        proj = pyproj.Proj(caso.cadena_proj())
        epsg = ReferenciaEpsg.de(caso.epsg) if caso.epsg is not None else None
        for lat, lon in puntos_omerc(caso, rng):
            este, norte, k, gamma = referencia(caso, proj, epsg, lat, lon)
            lineas.append(
                ",".join(
                    [
                        caso.nombre, caso.metodo, fmt(caso.elipsoide.semieje), fmt(caso.elipsoide.achatamiento_inverso),
                        fmt(caso.meridiano_origen_deg), fmt(caso.unidad_m), fmt(caso.lat_centro_deg), fmt(caso.lon_centro_deg),
                        fmt(caso.azimut_deg), fmt(caso.angulo_rectificacion_deg), fmt(caso.k0), fmt(caso.falso_este),
                        fmt(caso.falso_norte), fmt(lat), fmt(lon), fmt(este), fmt(norte), fmt(k), fmt(gamma),
                    ]
                )  # fmt: skip
            )
    return lineas


# --- Estereográfica oblicua (doble estereográfica) -------------------------------------------------------------------------------

EPSG_ESTEREO_OBLICUA = {28992}  # Amersfoort / RD New

# El ejemplo publicado de GN 7-2 (Amersfoort / RD New): 53° N, 6° E → (196 105,283; 557 057,739) m.
PUNTO_GN_7_2 = (53.0, 6.0, 196105.283, 557057.739)
TOLERANCIA_PUNTO_PUBLICADO_M = 0.002


@dataclass(frozen=True)
class CasoEstereo:
    nombre: str
    elipsoide: Elipsoide
    meridiano_origen_deg: float
    unidad_m: float
    lat_origen_deg: float
    lon_origen_deg: float  # respecto al meridiano de origen
    k0: float
    falso_este: float
    falso_norte: float
    epsg: int | None = None

    def cadena_proj(self) -> str:
        return (
            f"+proj=sterea +lat_0={self.lat_origen_deg!r} +lon_0={self.lon_origen_deg + self.meridiano_origen_deg!r} +k_0={self.k0!r} "
            f"+x_0={self.falso_este * self.unidad_m!r} +y_0={self.falso_norte * self.unidad_m!r} "
            f"{self.elipsoide.argumentos_proj()} +units=m +no_defs"
        )


def caso_epsg_estereo(codigo: int) -> CasoEstereo:
    crs = CRS.from_epsg(codigo)
    metodo = crs.coordinate_operation.method_name
    assert "Oblique Stereographic" in metodo, f"EPSG:{codigo} es «{metodo}»"
    parametros = {p.name: p for p in crs.coordinate_operation.params}
    elipsoide = Elipsoide(crs.ellipsoid.semi_major_metre, crs.ellipsoid.inverse_flattening)
    meridiano = crs.geodetic_crs.prime_meridian
    latitud = parametros["Latitude of natural origin"]
    longitud = parametros["Longitude of natural origin"]
    este = parametros["False easting"]
    return CasoEstereo(
        f"EPSG:{codigo}", elipsoide, a_grados(meridiano.longitude, meridiano.unit_conversion_factor), este.unit_conversion_factor,
        a_grados(latitud.value, latitud.unit_conversion_factor), a_grados(longitud.value, longitud.unit_conversion_factor),
        parametros["Scale factor at natural origin"].value, este.value, parametros["False northing"].value, codigo,
    )  # fmt: skip


def casos_estereo() -> list[CasoEstereo]:
    propios = [
        CasoEstereo("origen-ecuador", GRS80, 0.0, 1.0, 0.0, 10.0, 0.9996, 1_000.0, 2_000.0),
        CasoEstereo("cerca-del-polo", GRS80, 0.0, 1.0, 85.0, 20.0, 1.0, 0.0, 0.0),
        CasoEstereo("esfera", ESFERA, 0.0, 1.0, 40.0, -30.0, 1.0, 500.0, 500.0),
        CasoEstereo("sur", INTERNACIONAL, 0.0, 1.0, -35.0, 150.0, 0.9999, 500_000.0, 10_000_000.0),
    ]
    return [caso_epsg_estereo(codigo) for codigo in sorted(EPSG_ESTEREO_OBLICUA)] + propios


def puntos_estereo(caso: CasoEstereo, rng: random.Random) -> list[tuple[float, float]]:
    """Una rejilla de ±5° de latitud y ±6° de longitud alrededor del origen, unos puntos al azar y el punto publicado de RD Nueva."""
    lon0 = caso.lon_origen_deg + caso.meridiano_origen_deg
    puntos = [(limitar_latitud(caso.lat_origen_deg + dlat), lon0 + dlon) for dlat in (-5.0, -2.5, 0.0, 2.5, 5.0) for dlon in (-6.0, -3.0, 0.0, 3.0, 6.0)]
    puntos += [(limitar_latitud(caso.lat_origen_deg + rng.uniform(-5.0, 5.0)), lon0 + rng.uniform(-6.0, 6.0)) for _ in range(5)]
    if caso.epsg == 28992:
        puntos.append((PUNTO_GN_7_2[0], PUNTO_GN_7_2[1]))
    return puntos


COLUMNAS_STEREO_OBLICUA = (
    "caso,semieje,achatamiento_inverso,meridiano_origen_deg,unidad_m,lat_origen,lon_origen,k0,falso_este,falso_norte,"
    "latitud,longitud,este_m,norte_m,factor_escala,convergencia_grados"
)


def stereo_oblicua_vectors(rng: random.Random) -> list[str]:
    """S032/RF-10, RF-11: Este, Norte, factor de escala y convergencia (grados) de la Estereográfica oblicua."""
    lineas = header("Estereografica oblicua (pyproj/PROJ, Transformer de EPSG y get_factors)") + [COLUMNAS_STEREO_OBLICUA]
    for caso in casos_estereo():
        proj = pyproj.Proj(caso.cadena_proj())
        epsg = ReferenciaEpsg.de(caso.epsg) if caso.epsg is not None else None
        for lat, lon in puntos_estereo(caso, rng):
            este, norte, k, gamma = referencia(caso, proj, epsg, lat, lon)
            if caso.epsg == 28992 and (lat, lon) == PUNTO_GN_7_2[:2]:
                assert abs(este - PUNTO_GN_7_2[2]) <= TOLERANCIA_PUNTO_PUBLICADO_M, f"RD Nueva: E={este}, publicado {PUNTO_GN_7_2[2]}"
                assert abs(norte - PUNTO_GN_7_2[3]) <= TOLERANCIA_PUNTO_PUBLICADO_M, f"RD Nueva: N={norte}, publicado {PUNTO_GN_7_2[3]}"
            lineas.append(
                ",".join(
                    [
                        caso.nombre, fmt(caso.elipsoide.semieje), fmt(caso.elipsoide.achatamiento_inverso), fmt(caso.meridiano_origen_deg),
                        fmt(caso.unidad_m), fmt(caso.lat_origen_deg), fmt(caso.lon_origen_deg), fmt(caso.k0), fmt(caso.falso_este),
                        fmt(caso.falso_norte), fmt(lat), fmt(lon), fmt(este), fmt(norte), fmt(k), fmt(gamma),
                    ]
                )  # fmt: skip
            )
    return lineas


# --- Estereográfica polar A, B y C ------------------------------------------------------------------------------------------------

EPSG_POLAR = {
    5041: "(variant A)",  # WGS 84 / UPS North
    5042: "(variant A)",  # WGS 84 / UPS South
    3031: "(variant B)",  # WGS 84 / Antarctic Polar Stereographic
    3413: "(variant B)",  # WGS 84 / NSIDC Sea Ice Polar Stereographic North
    2985: "(variant C)",  # Petrels 1972 / Terre Adelie Polar Stereographic
}


@dataclass(frozen=True)
class CasoPolar:
    nombre: str
    metodo: str  # "A", "B" o "C"
    elipsoide: Elipsoide
    meridiano_origen_deg: float
    unidad_m: float
    lat_param_deg: float  # A: latitud de origen (un polo); B y C: paralelo estándar. Su signo dice el polo.
    lon_origen_deg: float  # respecto al meridiano de origen
    k0: float | None  # solo la A
    falso_este: float  # C: en el origen falso
    falso_norte: float
    epsg: int | None = None

    @property
    def polo_deg(self) -> float:
        return 90.0 if self.lat_param_deg > 0 else -90.0

    def cadena_proj(self) -> str:
        lon = self.lon_origen_deg + self.meridiano_origen_deg
        elipsoide = f"{self.elipsoide.argumentos_proj()} +units=m +no_defs"
        if self.metodo == "A":
            return (
                f"+proj=stere +lat_0={self.polo_deg!r} +lon_0={lon!r} +k_0={self.k0!r} "
                f"+x_0={self.falso_este * self.unidad_m!r} +y_0={self.falso_norte * self.unidad_m!r} {elipsoide}"
            )
        sin_falsos = f"+proj=stere +lat_0={self.polo_deg!r} +lat_ts={self.lat_param_deg!r} +lon_0={lon!r} +x_0=0 +y_0=0 {elipsoide}"
        if self.metodo == "B":
            return sin_falsos.replace("+x_0=0 +y_0=0", f"+x_0={self.falso_este * self.unidad_m!r} +y_0={self.falso_norte * self.unidad_m!r}")
        # Variante C: PROJ no la tiene, pero es la B con el origen en el punto donde el paralelo estándar corta el meridiano de origen.
        y_origen_falso = pyproj.Proj(sin_falsos)(lon, self.lat_param_deg)[1]
        return sin_falsos.replace("+x_0=0 +y_0=0", f"+x_0={self.falso_este * self.unidad_m!r} +y_0={self.falso_norte * self.unidad_m - y_origen_falso!r}")


def caso_epsg_polar(codigo: int) -> CasoPolar:
    crs = CRS.from_epsg(codigo)
    metodo = crs.coordinate_operation.method_name
    esperado = EPSG_POLAR[codigo]
    assert esperado in metodo, f"EPSG:{codigo} es «{metodo}», no {esperado}"
    parametros = {p.name: p for p in crs.coordinate_operation.params}
    elipsoide = Elipsoide(crs.ellipsoid.semi_major_metre, crs.ellipsoid.inverse_flattening)
    meridiano = crs.geodetic_crs.prime_meridian
    meridiano_deg = a_grados(meridiano.longitude, meridiano.unit_conversion_factor)
    variante = esperado[-2]  # "(variant A)" → "A"

    def angulo(nombre: str) -> float:
        parametro = parametros[nombre]
        return a_grados(parametro.value, parametro.unit_conversion_factor)

    if variante == "A":
        lat, lon, k0 = angulo("Latitude of natural origin"), angulo("Longitude of natural origin"), parametros["Scale factor at natural origin"].value
        este, norte = parametros["False easting"], parametros["False northing"]
    else:
        lat, lon, k0 = angulo("Latitude of standard parallel"), angulo("Longitude of origin"), None
        nombre_este, nombre_norte = ("False easting", "False northing") if variante == "B" else ("Easting at false origin", "Northing at false origin")
        este, norte = parametros[nombre_este], parametros[nombre_norte]
    return CasoPolar(
        f"EPSG:{codigo}", variante, elipsoide, meridiano_deg, este.unit_conversion_factor, lat, lon, k0, este.value, norte.value, codigo
    )  # fmt: skip


def casos_polar() -> list[CasoPolar]:
    c = CasoPolar
    propios = [
        c("esfera-a", "A", ESFERA, 0.0, 1.0, 90.0, 30.0, 1.0, 0.0, 0.0),
        c("a-sur", "A", GRS80, 0.0, 1.0, -90.0, -60.0, 0.994, 500_000.0, 1_500_000.0),
        c("b-norte", "B", GRS80, 0.0, 1.0, 60.0, 20.0, None, 1_000.0, -2_000.0),
        c("esfera-b", "B", ESFERA, 0.0, 1.0, -45.0, 10.0, None, 0.0, 0.0),
        # Paralelo estándar en el propio polo: la B equivale a la A con la escala de PROJ.
        c("b-polo-norte", "B", GRS80, 0.0, 1.0, 90.0, 15.0, None, 500.0, 500.0),
        c("b-polo-sur", "B", INTERNACIONAL, 0.0, 1.0, -90.0, -100.0, None, 0.0, 0.0),
        c("c-norte", "C", INTERNACIONAL, 0.0, 1.0, 75.0, -100.0, None, 1_000.0, 2_000.0),
        c("c-sur", "C", GRS80, 0.0, 1.0, -80.0, 120.0, None, 0.0, 0.0),
    ]
    return [caso_epsg_polar(codigo) for codigo in EPSG_POLAR] + propios


def puntos_polar(caso: CasoPolar, rng: random.Random) -> list[tuple[float, float]]:
    """Latitudes desde 89,9° hasta el ecuador y 30° más allá (hacia el otro hemisferio) y longitudes alrededor de todo el polo."""
    signo = 1.0 if caso.lat_param_deg > 0 else -1.0
    lon0 = caso.lon_origen_deg + caso.meridiano_origen_deg
    puntos = [
        (signo * lat, normalizar_longitud(lon0 + dlon))
        for lat in (89.9, 80.0, 60.0, 40.0, 20.0, 0.0, -30.0)
        for dlon in (-170.0, -90.0, -30.0, 0.0, 59.0, 121.0, 170.0)
    ]
    puntos += [(signo * rng.uniform(-30.0, 89.9), normalizar_longitud(lon0 + rng.uniform(-180.0, 180.0))) for _ in range(5)]
    return puntos


COLUMNAS_POLAR = (
    "caso,metodo,semieje,achatamiento_inverso,meridiano_origen_deg,unidad_m,lat_param,lon_origen,k0,falso_este,falso_norte,"
    "latitud,longitud,este_m,norte_m,factor_escala,convergencia_grados"
)


def stereo_polar_vectors(rng: random.Random) -> list[str]:
    """S032/RF-13, RF-14: Este, Norte, factor de escala y convergencia (grados) de la Estereográfica polar A, B y C."""
    lineas = header("Estereografica polar A, B y C (pyproj/PROJ, Transformer de EPSG y get_factors)") + [COLUMNAS_POLAR]
    for caso in casos_polar():
        proj = pyproj.Proj(caso.cadena_proj())
        # PROJ no tiene la variante C (EPSG:2985 no se puede transformar): ahí la referencia es la B de PROJ con el origen desplazado.
        epsg = ReferenciaEpsg.de(caso.epsg) if caso.epsg is not None and caso.metodo != "C" else None
        for lat, lon in puntos_polar(caso, rng):
            este, norte, k, gamma = referencia(caso, proj, epsg, lat, lon)
            lineas.append(
                ",".join(
                    [
                        caso.nombre, caso.metodo, fmt(caso.elipsoide.semieje), fmt(caso.elipsoide.achatamiento_inverso),
                        fmt(caso.meridiano_origen_deg), fmt(caso.unidad_m), fmt(caso.lat_param_deg), fmt(caso.lon_origen_deg),
                        fmt(caso.k0), fmt(caso.falso_este), fmt(caso.falso_norte),
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
        ("omerc.csv", omerc_vectors(random.Random(SEMILLA_OMERC))),
        ("stereo_oblicua.csv", stereo_oblicua_vectors(random.Random(SEMILLA_STEREO_OBLICUA))),
        ("stereo_polar.csv", stereo_polar_vectors(random.Random(SEMILLA_STEREO_POLAR))),
    ]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for nombre, lineas in todos():
        (OUT / nombre).write_text("\n".join(lineas) + "\n", encoding="utf-8", newline="\n")
        print(f"{nombre}: {len(lineas) - 3} vectores")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
