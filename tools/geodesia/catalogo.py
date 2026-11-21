"""Catálogo de sistemas de coordenadas de la biblioteca de S034, generado del registro EPSG con pyproj/PROJ (ADR-3).

Uso: uv run --project tools tools/geodesia/catalogo.py   (escribe `core/geodesy/src/main/resources/catalog/crs.tsv.gz`)

El catálogo es un texto TSV con una sección por tabla (`#nombre` en una línea y luego sus filas), ordenado y sin marcas de tiempo, para que
regenerarlo dé el mismo fichero. Secciones (columnas separadas por tabulador):

- `#ellipsoids`: nombre, semieje mayor (m), inverso del aplanamiento (0 = esfera).
- `#datums`: código del CRS geográfico de EPSG que lo identifica, nombre, elipsoide, meridiano de origen (° respecto a Greenwich), ámbito (S,W,N,E).
- `#crs`: EPSG, nombre, tipo (`P` proyectado, `G` geográfico), datum, método, parámetros, metros por unidad lineal, ámbito (S,W,N,E), países (ISO).
- `#countries`: ISO, nombre en español, nombre en inglés.
- `#options`: datum de origen, destino (`E` ETRS89 o `W` WGS 84), nombre de la transformación de EPSG, precisión (m, vacío si no se conoce), ámbito (S,W,N,E) y pasos.
  Pasos separados por `|`: `null`; `H;A=a,1/f;B=a,1/f;inv=0|1;conv=PV|CF;p=tx,ty,tz,rx,ry,rz,s[;px=x,y,z]` (Helmert o Molodensky-Badekas, ejes en m,
  rotaciones en segundos de arco, escala en ppm, `A` y `B` los elipsoides antes y después); `G;grid=nombre;inv=0|1` (rejilla de desplazamiento horizontal).
- (las rejillas y las licencias las añade la parte 3 de este fichero.)

Qué entra (S034/RF-4): los CRS proyectados y geográficos 2D de EPSG no obsoletos, con ejes Este-Norte (los de orden Norte-Este se normalizan; los
orientados al oeste o al sur no entran), unidad lineal metro, pie o pie topográfico de EE. UU., y un método de proyección que S008, S009 y S032
calculan. Qué más hace falta (transformación hasta ETRS89 o WGS84, y que el contraste con pyproj dé ≤ 1 mm) lo decide la parte 2 y 4.
"""

from __future__ import annotations

import math
import re
import sys
import warnings
from pathlib import Path

import pyproj
import pyproj.network
from pyproj import CRS
from pyproj.database import query_crs_info
from pyproj.enums import PJType
from pyproj.transformer import TransformerGroup
from pyproj.transformer import TransformerGroup

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
COLUMNAS = {"ellipsoids": 3, "datums": 5, "crs": 10, "countries": 3, "options": 6}
(CRS_EPSG, CRS_NOMBRE, CRS_TIPO, CRS_DATUM, CRS_METODO, CRS_PARAMETROS, CRS_UNIDAD_M, CRS_AMBITO, CRS_PAISES, CRS_AMBITO_NOMBRE) = range(10)
DATUM_CODIGO, DATUM_NOMBRE, DATUM_ELIPSOIDE, DATUM_MERIDIANO, DATUM_AMBITO = range(5)
OPCION_DATUM, OPCION_DESTINO, OPCION_NOMBRE, OPCION_PRECISION_M, OPCION_AMBITO, OPCION_PASOS = range(6)
OPCION_DATUM, OPCION_DESTINO, OPCION_NOMBRE, OPCION_PRECISION_M, OPCION_AMBITO, OPCION_PASOS = range(6)


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


# --- Transformaciones de cada datum hasta ETRS89 y WGS 84 (S034/RF-4, RF-9, RF-10) -----------------------------------------------------

DESTINOS = {"E": 4258, "W": 4326}  # ETRS89 y WGS 84; REGCAN95 se trata como ETRS89 (plan D8)
REGCAN95 = 4081
SUFIJO_EJES = " (with axis order normalized for visualization)"
IGNORADOS = {"unitconvert", "push", "pop", "axisswap", "noop"}
CLAVES_HELMERT = {"x", "y", "z", "rx", "ry", "rz", "s", "px", "py", "pz", "convention", "proj", "exact"}
TOLERANCIA_MERIDIANO_DEG = 1e-8
_ELIPSOIDES: dict[str, tuple[float, float]] = {}
_MERIDIANOS: dict[str, float] = {}


def _tokens(paso: str) -> tuple[bool, dict[str, str]]:
    """(¿inverso?, clave→valor) de un paso de una tubería de PROJ."""
    partes = paso.split()
    inverso = "inv" in partes
    return inverso, {k: v for k, _, v in (p.partition("=") for p in partes if p != "inv")}


def _elipsoide_de(tokens: dict[str, str]) -> tuple[float, float] | None:
    """(a, 1/f) del elipsoide de un paso `cart` (`ellps=…` o `a=… rf=…`)."""
    if "ellps" in tokens:
        nombre = tokens["ellps"]
        if nombre not in _ELIPSOIDES:
            e = CRS.from_proj4(f"+proj=longlat +ellps={nombre} +no_defs +type=crs").ellipsoid
            _ELIPSOIDES[nombre] = (e.semi_major_metre, e.inverse_flattening or 0.0)
        return _ELIPSOIDES[nombre]
    if "a" in tokens and "rf" in tokens:
        return float(tokens["a"]), float(tokens["rf"])
    return None


def _meridiano_deg(texto: str) -> float:
    """Longitud de un meridiano de origen de PROJ (nombre o grados) respecto a Greenwich."""
    if texto not in _MERIDIANOS:
        meridiano = CRS.from_proj4(f"+proj=longlat +ellps=WGS84 +pm={texto} +no_defs +type=crs").prime_meridian
        _MERIDIANOS[texto] = _grados(meridiano.longitude, meridiano.unit_name, meridiano.unit_conversion_factor or 1.0)
    return _MERIDIANOS[texto]


def _elipsoide_texto(elipsoide: tuple[float, float]) -> str:
    return f"{num(elipsoide[0])},{num(elipsoide[1])}"


def _paso_helmert(paso: dict, a: tuple[float, float], b: tuple[float, float]) -> str:
    texto = f"H;A={_elipsoide_texto(a)};B={_elipsoide_texto(b)};inv={int(paso['inv'])};conv={paso['conv']};p=" + ",".join(num(v) for v in paso["p"])
    if paso["px"] is not None:
        texto += ";px=" + ",".join(num(v) for v in paso["px"])
    return texto


def pasos_de_tuberia(definicion: str, meridiano_datum_deg: float) -> str | None:
    """Los pasos del catálogo de una tubería de PROJ, o `None` si usa algo que S033 no calcula (otros métodos, variación con el tiempo, listas de
    rejillas, otro meridiano de origen…). `null` si no cambia nada."""
    pasos: list[str] = []
    elipsoide_entrada: tuple[float, float] | None = None
    en_bloque: list[dict] = []
    for crudo in definicion.split(" step ")[1:]:
        inverso, tokens = _tokens(crudo)
        metodo = tokens.get("proj", "")
        if metodo in IGNORADOS:
            continue
        if metodo == "longlat":
            if "pm" in tokens and abs(_meridiano_deg(tokens["pm"]) - meridiano_datum_deg) > TOLERANCIA_MERIDIANO_DEG:
                return None
            continue
        if metodo == "cart":
            elipsoide = _elipsoide_de(tokens)
            if elipsoide is None:
                return None
            if not inverso:
                elipsoide_entrada, en_bloque = elipsoide, []
                continue
            if elipsoide_entrada is None:
                return None
            if not en_bloque:  # solo cambia el elipsoide: un Helmert nulo
                en_bloque.append({"inv": False, "conv": "PV", "p": [0.0] * 7, "px": None})
            for k, paso in enumerate(en_bloque):
                salida = elipsoide if k == len(en_bloque) - 1 else elipsoide_entrada
                pasos.append(_paso_helmert(paso, elipsoide_entrada, salida))
            elipsoide_entrada, en_bloque = None, []
        elif metodo == "helmert":
            if elipsoide_entrada is None or not set(tokens) <= CLAVES_HELMERT:
                return None
            p = [float(tokens.get(k, 0.0)) for k in ("x", "y", "z", "rx", "ry", "rz", "s")]
            pivote = [float(tokens[k]) for k in ("px", "py", "pz")] if "px" in tokens else None
            convencion = "CF" if tokens.get("convention") == "coordinate_frame" else "PV"
            en_bloque.append({"inv": inverso, "conv": convencion, "p": p, "px": pivote})
        elif metodo == "hgridshift":
            rejillas = tokens.get("grids", "").lstrip("@")
            if not rejillas or "," in rejillas:
                return None  # una lista de rejillas alternativas no se calcula todavía
            pasos.append(f"G;grid={rejillas};inv={int(inverso)}")
        else:
            return None
    if elipsoide_entrada is not None:
        return None
    return "|".join(pasos) if pasos else "null"


def _opciones_de(datum: str, meridiano_deg: float, destino: str) -> list[list[str]]:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            grupo = TransformerGroup(int(datum), DESTINOS[destino], always_xy=True, allow_ballpark=False)
        except (IndexError, pyproj.exceptions.ProjError):  # sin ninguna operación entre los dos datums
            return []
    filas, vistos = [], set()
    for t in grupo.transformers:
        pasos = pasos_de_tuberia(t.definition, meridiano_deg)
        if pasos is None:
            continue
        area = ambito_texto(t.area_of_use)
        if (pasos, area) in vistos:
            continue
        vistos.add((pasos, area))
        precision = "" if t.accuracy is None or t.accuracy < 0 else num(t.accuracy)
        filas.append([datum, destino, limpiar(t.description.removesuffix(SUFIJO_EJES)), precision, area, pasos])
    return filas


def _opciones_regcan95(codigo: str, destino: str, meridiano_deg: float) -> list[list[str]]:
    """REGCAN95 coincide con ETRS89 (RD 1071/2007): sin pasos hasta ETRS89 y con los de ETRS89 hasta WGS 84."""
    if destino == "E":
        return [[codigo, "E", "REGCAN95 coincide con ETRS89 (Real Decreto 1071/2007)", "", "", "null"]]
    return [[codigo, "W", f[2] + " (vía ETRS89)", f[3], f[4], f[5]] for f in _opciones_de("4258", meridiano_deg, "W")]


def generar_opciones(datums: list[list[str]]) -> list[list[str]]:
    """Las transformaciones de cada datum hasta ETRS89 y WGS 84, con las rejillas por la red (sin descargarlas)."""
    pyproj.network.set_network_enabled(True)
    filas: list[list[str]] = []
    for datum in datums:
        codigo, meridiano = datum[DATUM_CODIGO], float(datum[DATUM_MERIDIANO])
        for destino in DESTINOS:
            if int(codigo) == REGCAN95:
                filas.extend(_opciones_regcan95(codigo, destino, meridiano))
            else:
                filas.extend(_opciones_de(codigo, meridiano, destino))
    return sorted(filas, key=lambda f: (int(f[0]), f[1], float(f[3]) if f[3] else 1e9, f[2], f[5]))


def generar() -> dict[str, list[list[str]]]:
    """El catálogo hasta la parte 2: los SC sin ninguna transformación hasta ETRS89 o WGS 84 no entran (S034/RF-4)."""
    secciones = generar_crs()
    opciones = generar_opciones(secciones["datums"])
    con_opcion = {f[OPCION_DATUM] for f in opciones}
    secciones["crs"] = [f for f in secciones["crs"] if f[CRS_DATUM] in con_opcion]
    usados = {f[CRS_DATUM] for f in secciones["crs"]}
    secciones["datums"] = [f for f in secciones["datums"] if f[DATUM_CODIGO] in usados]
    secciones["options"] = [f for f in opciones if f[OPCION_DATUM] in usados]
    return secciones


def a_texto(secciones: dict[str, list[list[str]]]) -> str:
    lineas = cabecera()
    for nombre, filas in secciones.items():
        lineas.append(f"#{nombre}")
        lineas.extend("\t".join(fila) for fila in filas)
    return "\n".join(lineas) + "\n"


def main() -> int:
    texto = a_texto(generar())
    print(f"{len(texto)} caracteres")
    return 0


if __name__ == "__main__":
    sys.exit(main())
