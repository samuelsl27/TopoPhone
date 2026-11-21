"""Catálogo de sistemas de coordenadas de la biblioteca de S034, generado del registro EPSG con pyproj/PROJ (ADR-3).

Uso: uv run --project tools tools/geodesia/catalogo.py   (escribe `core/geodesy/src/main/resources/catalog/crs.tsv.gz`)

El catálogo es un texto TSV con una sección por tabla (`#nombre` en una línea y luego sus filas), ordenado y sin marcas de tiempo, para que
regenerarlo dé el mismo fichero. Secciones (columnas separadas por tabulador):

- `#ellipsoids`: nombre, semieje mayor (m), inverso del aplanamiento (0 = esfera).
- `#datums`: código del CRS geográfico de EPSG que lo identifica, nombre, elipsoide, meridiano de origen (° respecto a Greenwich), ámbito (S,W,N,E).
- `#crs`: EPSG, nombre, tipo (`P` proyectado, `G` geográfico), datum, método, parámetros, metros por unidad lineal, ámbito (S,W,N,E), países (ISO).
- `#countries`: ISO, nombre en español, nombre en inglés.
- (los pasos de transformación, las rejillas y las licencias las añade la parte 2 y 3 de este fichero.)

Qué entra (S034/RF-4): los CRS proyectados y geográficos 2D de EPSG no obsoletos, con ejes Este-Norte (los de orden Norte-Este se normalizan; los
orientados al oeste o al sur no entran), unidad lineal metro, pie o pie topográfico de EE. UU., y un método de proyección que S008, S009 y S032
calculan. Qué más hace falta (transformación hasta ETRS89 o WGS84, y que el contraste con pyproj dé ≤ 1 mm) lo decide la parte 2 y 4.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import pyproj
from pyproj import CRS
from pyproj.database import query_crs_info
from pyproj.enums import PJType

sys.path.insert(0, str(Path(__file__).resolve().parent))
import paises  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "core" / "geodesy" / "src" / "main" / "resources"

# Métodos que S008, S009 y S032 calculan, por su código de método de EPSG, y el nombre corto que lleva el catálogo.
METODOS_EPSG = {
    9807: "tm",
    9801: "lcc1",
    9802: "lcc2",
    9804: "merca",
    9805: "mercb",
    9812: "omerca",
    9815: "omercb",
    9809: "stereo",
    9810: "polara",
    9829: "polarb",
    9830: "polarc",
    1024: "webmerc",
}
METODOS_CALCULABLES = set(METODOS_EPSG.values()) | {"geo"}

# Parámetro de EPSG (por su código) → nombre del parámetro de `ProjectionParameters` (S032), por método.
PARAMETROS = {
    "tm": {8801: "latitudeOfOriginDeg", 8802: "longitudeOfOriginDeg", 8805: "scaleFactor", 8806: "falseEasting", 8807: "falseNorthing"},
    "lcc1": {8801: "latitudeOfOriginDeg", 8802: "longitudeOfOriginDeg", 8805: "scaleFactor", 8806: "falseEasting", 8807: "falseNorthing"},
    "lcc2": {
        8821: "latitudeOfFalseOriginDeg",
        8822: "longitudeOfFalseOriginDeg",
        8823: "standardParallel1Deg",
        8824: "standardParallel2Deg",
        8826: "eastingAtFalseOrigin",
        8827: "northingAtFalseOrigin",
    },
    "merca": {8802: "longitudeOfOriginDeg", 8805: "scaleFactor", 8806: "falseEasting", 8807: "falseNorthing"},
    "mercb": {8802: "longitudeOfOriginDeg", 8823: "standardParallelDeg", 8806: "falseEasting", 8807: "falseNorthing"},
    "omerca": {
        8811: "latitudeOfCentreDeg",
        8812: "longitudeOfCentreDeg",
        8813: "azimuthDeg",
        8814: "rectifiedGridAngleDeg",
        8815: "scaleFactor",
        8806: "falseEasting",
        8807: "falseNorthing",
    },
    "omercb": {
        8811: "latitudeOfCentreDeg",
        8812: "longitudeOfCentreDeg",
        8813: "azimuthDeg",
        8814: "rectifiedGridAngleDeg",
        8815: "scaleFactor",
        8816: "eastingAtCentre",
        8817: "northingAtCentre",
    },
    "stereo": {8801: "latitudeOfOriginDeg", 8802: "longitudeOfOriginDeg", 8805: "scaleFactor", 8806: "falseEasting", 8807: "falseNorthing"},
    "polara": {8801: "latitudeOfOriginDeg", 8802: "longitudeOfOriginDeg", 8805: "scaleFactor", 8806: "falseEasting", 8807: "falseNorthing"},
    "polarb": {8832: "latitudeOfStandardParallelDeg", 8833: "longitudeOfOriginDeg", 8806: "falseEasting", 8807: "falseNorthing"},
    "polarc": {
        8832: "latitudeOfStandardParallelDeg",
        8833: "longitudeOfOriginDeg",
        8826: "eastingAtFalseOrigin",
        8827: "northingAtFalseOrigin",
    },
    "webmerc": {},
}
ANGULARES = {
    "latitudeOfOriginDeg", "longitudeOfOriginDeg", "latitudeOfFalseOriginDeg", "longitudeOfFalseOriginDeg", "standardParallel1Deg",
    "standardParallel2Deg", "standardParallelDeg", "latitudeOfCentreDeg", "longitudeOfCentreDeg", "azimuthDeg", "rectifiedGridAngleDeg",
    "latitudeOfStandardParallelDeg",
}  # fmt: skip
LINEALES = {"falseEasting", "falseNorthing", "eastingAtFalseOrigin", "northingAtFalseOrigin", "eastingAtCentre", "northingAtCentre"}

# Unidades lineales que S032 sabe aplicar (metros por unidad): m, pie internacional y pie topográfico de EE. UU.
UNIDADES_M = (1.0, 0.3048, 1200.0 / 3937.0)
TOLERANCIA_UNIDAD = 1e-9

# Columnas de cada sección y las posiciones que usan los tests y la parte 2.
COLUMNAS = {"ellipsoids": 3, "datums": 5, "crs": 10, "countries": 3}
(CRS_EPSG, CRS_NOMBRE, CRS_TIPO, CRS_DATUM, CRS_METODO, CRS_PARAMETROS, CRS_UNIDAD_M, CRS_AMBITO, CRS_PAISES, CRS_AMBITO_NOMBRE) = range(10)
DATUM_CODIGO, DATUM_NOMBRE, DATUM_ELIPSOIDE, DATUM_MERIDIANO, DATUM_AMBITO = range(5)


def cabecera() -> list[str]:
    return [f"# Catálogo de SC de TopoPhone (S034). Generado por tools/geodesia/catalogo.py; no editar a mano. EPSG vía pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}"]


def num(valor: float) -> str:
    return repr(float(valor))


def parametros(texto: str) -> dict[str, float]:
    """Los parámetros `nombre=valor;…` de una fila de `#crs`."""
    if not texto:
        return {}
    return {clave: float(valor) for clave, valor in (par.split("=") for par in texto.split(";"))}


def ambito_texto(area) -> str:
    """S,W,N,E de un `AreaOfUse` de pyproj; vacío si no tiene."""
    if area is None:
        return ""
    return ",".join(num(v) for v in (area.south, area.west, area.north, area.east))


def limpiar(texto: str) -> str:
    return " ".join(texto.replace("\t", " ").splitlines())


def _ejes_validos(crs: CRS) -> bool:
    """Ejes Este y Norte (en cualquier orden) y ambos en la misma unidad."""
    direcciones = {eje.direction for eje in crs.axis_info}
    unidades = {eje.unit_conversion_factor for eje in crs.axis_info}
    return direcciones == {"east", "north"} and len(unidades) == 1


def _unidad_m(factor: float) -> float | None:
    for unidad in UNIDADES_M:
        if abs(factor - unidad) < TOLERANCIA_UNIDAD:
            return unidad
    return None


def _grados(valor: float, unidad: str, factor_a_radianes: float) -> float:
    """Un ángulo en grados sin el ruido de pasar por radianes cuando la unidad ya es el grado o el gon."""
    if unidad == "degree":
        return valor
    if unidad == "grad":
        return valor * 0.9
    return math.degrees(valor * factor_a_radianes)


def _parametros_de(crs: CRS, metodo: str, unidad_m: float) -> str | None:
    """Los parámetros del método con los ángulos en grados y los falsos en la unidad lineal del SC; `None` si falta alguno."""
    por_codigo = {int(p.code): p for p in crs.coordinate_operation.params if p.code is not None}
    salida = []
    for codigo, nombre in PARAMETROS[metodo].items():
        p = por_codigo.get(codigo)
        if p is None:
            return None
        if nombre in ANGULARES:
            valor = _grados(p.value, p.unit_name, p.unit_conversion_factor)
        elif nombre in LINEALES:
            valor = p.value * p.unit_conversion_factor / unidad_m
        else:  # factor de escala: sin unidad
            valor = p.value
        salida.append(f"{nombre}={num(valor)}")
    return ";".join(salida)


def _meridiano_origen_deg(crs: CRS) -> float:
    meridiano = crs.prime_meridian
    return _grados(meridiano.longitude, meridiano.unit_name, meridiano.unit_conversion_factor or 1.0)


def _fila_crs(info, datums: dict[str, list[str]], elipsoides: dict[str, list[str]]) -> list[str] | None:
    """La fila de `#crs` de un CRS de EPSG o `None` si no entra en el catálogo."""
    crs = CRS.from_epsg(int(info.code))
    if info.type == PJType.GEOGRAPHIC_2D_CRS:
        tipo, metodo, geografico, unidad_m, texto_parametros = "G", "geo", crs, 1.0, ""
    else:
        if crs.coordinate_operation is None or not _ejes_validos(crs):
            return None
        metodo = METODOS_EPSG.get(crs.coordinate_operation.method_code and int(crs.coordinate_operation.method_code))
        if metodo is None:
            return None
        unidad_m = _unidad_m(crs.axis_info[0].unit_conversion_factor)
        if unidad_m is None:
            return None
        texto_parametros = _parametros_de(crs, metodo, unidad_m)
        if texto_parametros is None:
            return None
        tipo, geografico = "P", crs.geodetic_crs
    if geografico is None or geografico.to_epsg() is None or len(geografico.axis_info) != 2:
        return None
    codigo_datum = str(geografico.to_epsg())
    elipsoide = geografico.ellipsoid
    if codigo_datum not in datums:
        elipsoides.setdefault(
            limpiar(elipsoide.name), [limpiar(elipsoide.name), num(elipsoide.semi_major_metre), num(elipsoide.inverse_flattening or 0.0)]
        )
        datums[codigo_datum] = [
            codigo_datum,
            limpiar(geografico.name),
            limpiar(elipsoide.name),
            num(_meridiano_origen_deg(geografico)),
            ambito_texto(geografico.area_of_use),
        ]
    nombre_ambito = crs.area_of_use.name if crs.area_of_use else ""
    return [
        str(info.code),
        limpiar(info.name),
        tipo,
        codigo_datum,
        metodo,
        texto_parametros,
        num(unidad_m),
        ambito_texto(crs.area_of_use),
        ",".join(paises.paises_del_ambito(nombre_ambito)),
        limpiar(nombre_ambito),
    ]


def generar_crs() -> dict[str, list[list[str]]]:
    """Las secciones `#ellipsoids`, `#datums`, `#crs` y `#countries`, ordenadas (S034/RF-4)."""
    datums: dict[str, list[str]] = {}
    elipsoides: dict[str, list[str]] = {}
    filas = []
    for tipo in (PJType.PROJECTED_CRS, PJType.GEOGRAPHIC_2D_CRS):
        for info in query_crs_info(auth_name="EPSG", pj_types=tipo):
            if info.deprecated:
                continue
            fila = _fila_crs(info, datums, elipsoides)
            if fila is not None:
                filas.append(fila)
    usados = {pais for fila in filas for pais in fila[CRS_PAISES].split(",") if pais}
    return {
        "ellipsoids": sorted(elipsoides.values()),
        "datums": sorted(datums.values(), key=lambda f: int(f[0])),
        "crs": sorted(filas, key=lambda f: int(f[0])),
        "countries": [[iso, es, en] for iso, es, en, _alias in paises.tabla() if iso in usados],
    }


def a_texto(secciones: dict[str, list[list[str]]]) -> str:
    lineas = cabecera()
    for nombre, filas in secciones.items():
        lineas.append(f"#{nombre}")
        lineas.extend("\t".join(fila) for fila in filas)
    return "\n".join(lineas) + "\n"


def main() -> int:
    texto = a_texto(generar_crs())
    print(f"{len(texto)} caracteres")
    return 0


if __name__ == "__main__":
    sys.exit(main())
