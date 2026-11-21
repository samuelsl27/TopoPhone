"""Vectores de referencia de las transformaciones de datum de S033, generados con pyproj/PROJ (constitución nº 2, ADR-2).

Cada familia tiene su generador y su CSV en `fixtures/geodesia/` (los llama `generar_vectores.py`):
- helmert.csv: Helmert de 3, 4, 6 y 7 parámetros en convención PV y CF, con operaciones de EPSG y casos propios (S033/RF-1, RF-2, RF-5).
- molodensky_badekas.csv: Molodensky-Badekas de 10 parámetros (7 y el punto de rotación) en PV y CF, con operaciones de EPSG (método 9636) y
  casos propios, uno de ellos con el punto de rotación en el centro de la Tierra (S033/RF-3, RF-5).
- rejilla_desplazamiento.csv: la rejilla SPED2ETV2 de la app como `hgridshift` de PROJ, directa e inversa, en el interior de sus dos subrejillas,
  a ±1e-4° de sus bordes y fuera de ella (vacío donde PROJ no da valor) (S033/RF-7, RF-8, RF-10).
- cadena_tres_pasos.csv: cadenas Helmert → rejilla → Helmert (tres pasos) y Helmert → Helmert → rejilla → Helmert (cuatro), cada una como una
  sola tubería de PROJ (S033/RF-11). La columna `pasos` describe la cadena (`G` es la rejilla; `H:…` un Helmert con sus elipsoides y parámetros).

Cómo se calcula cada fila:
- **Casos de EPSG**: los parámetros y los elipsoides salen de la propia operación de la base de PROJ (`urn:ogc:def:coordinateOperation:EPSG::N`),
  y se comprueba que el método es el esperado (Geocentric translations, Position Vector o Coordinate Frame) antes de usarla. La tubería de
  PROJ de esas operaciones es 2D (guarda la altura y calcula con h = 0); aquí se quita eso (`axisswap`, `push v_3`, `pop v_3`) y se calcula
  en 3D con la altura, que es lo que hace TopoPhone.
- **Casos propios** (parámetros a cero, rotaciones grandes, escala grande…): una tubería `+proj=helmert` con los parámetros dados.
- **Directa**: la tubería hacia delante. **Inversa**: la misma tubería hacia atrás aplicada al mismo punto, como si fuera una coordenada del
  datum de destino. PROJ invierte el Helmert cambiando el signo de los parámetros; TopoPhone lo invierte exactamente (S033/RF-5) y la
  diferencia entre las dos es de décimas de milímetro como mucho (por debajo de 1 mm).

Las coordenadas van en grados (longitud, latitud) y metros; las rotaciones en segundos de arco y la escala en ppm. Es determinista: la
misma versión de pyproj/PROJ y la misma semilla dan el mismo fichero.
"""

from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass
from pathlib import Path

import pyproj
from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rejillas  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "geodesia"

SEMILLA_HELMERT = 20261127
SEMILLA_MB = 20261128
SEMILLA_REJILLA = 20261129
SEMILLA_CADENA = 20261130

# Nombre de método de PROJ de cada familia de operaciones de EPSG que se usa (comprobado antes de generar).
METODO_TRASLACIONES = "Geocentric translations (geog2D domain)"
METODO_PV = "Position Vector transformation (geog2D domain)"
METODO_CF = "Coordinate Frame rotation (geog2D domain)"

# (código EPSG, método esperado, número de parámetros, convención). 3 traslaciones, y 7 parámetros en PV y en CF.
OPERACIONES_HELMERT = [
    (10085, METODO_TRASLACIONES, 3, "PV"),
    (1112, METODO_PV, 7, "PV"),
    (1024, METODO_CF, 7, "CF"),
]


def header(tipo: str) -> list[str]:
    return [
        f"# {tipo}. Generado por tools/geodesia/datum.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
    ]


def fmt(valor: float) -> str:
    return repr(float(valor))


@dataclass(frozen=True)
class Elipsoide:
    semieje: float
    rf: float

    @property
    def tuberia(self) -> str:
        return f"+a={self.semieje!r} +rf={self.rf!r}"


@dataclass(frozen=True)
class Helmert:
    """Un Helmert (o un caso de EPSG) con todo lo que hace falta para generar sus filas."""

    caso: str
    n: int
    convencion: str  # "PV" o "CF"
    origen: Elipsoide
    destino: Elipsoide
    t: tuple[float, float, float]
    r: tuple[float, float, float]  # segundos de arco
    escala: float  # ppm
    area: tuple[float, float, float, float] | None = None  # (oeste, sur, este, norte) del ámbito, si lo declara
    pivote: tuple[float, float, float] | None = None  # punto de rotación (X0, Y0, Z0) en metros: Molodensky-Badekas

    def tuberia(self) -> Transformer:
        convencion = "position_vector" if self.convencion == "PV" else "coordinate_frame"
        partes = [f"+x={self.t[0]!r}", f"+y={self.t[1]!r}", f"+z={self.t[2]!r}"]
        if self.n in (6, 7):
            partes += [f"+rx={self.r[0]!r}", f"+ry={self.r[1]!r}", f"+rz={self.r[2]!r}"]
        if self.n in (4, 7):
            partes.append(f"+s={self.escala!r}")
        if self.pivote is not None:
            partes += [f"+px={self.pivote[0]!r}", f"+py={self.pivote[1]!r}", f"+pz={self.pivote[2]!r}"]
        partes.append(f"+convention={convencion}")
        metodo = "molobadekas" if self.pivote is not None else "helmert"
        return Transformer.from_pipeline(
            "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
            f"+step +proj=cart {self.origen.tuberia} +step +proj={metodo} {' '.join(partes)} "
            f"+step +inv +proj=cart {self.destino.tuberia} +step +proj=unitconvert +xy_in=rad +xy_out=deg"
        )


GRS80 = Elipsoide(6378137.0, 298.257222101)
INTERNACIONAL = Elipsoide(6378388.0, 297.0)
BESSEL = Elipsoide(6377397.155, 299.1528128)
CLARKE_1880 = Elipsoide(6378249.145, 293.465)
WGS84 = Elipsoide(6378137.0, 298.257223563)

# Casos propios: parámetros a cero, de uso típico y grandes (rotaciones ≥ 1″ y escala ≥ 10 ppm, donde el inverso exacto y el cambio de signo
# se separan más).
CASOS_PROPIOS = [
    Helmert("cero_7p", 7, "PV", GRS80, GRS80, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), 0.0),
    Helmert("es_3p", 3, "PV", INTERNACIONAL, GRS80, (-131.032, -100.251, -163.354), (0.0, 0.0, 0.0), 0.0),
    Helmert("es_4p", 4, "PV", BESSEL, GRS80, (598.1, 73.7, 418.2), (0.0, 0.0, 0.0), 6.7),
    Helmert("pt_6p_pv", 6, "PV", INTERNACIONAL, WGS84, (-223.237, 110.193, 36.649), (0.0, 0.0, 0.0), 0.0),
    Helmert("rot_6p_pv", 6, "PV", INTERNACIONAL, GRS80, (-120.0, -90.0, -150.0), (0.5, -0.8, 1.2), 0.0),
    Helmert("rot_6p_cf", 6, "CF", INTERNACIONAL, GRS80, (-120.0, -90.0, -150.0), (0.5, -0.8, 1.2), 0.0),
    Helmert("ref_7p_pv", 7, "PV", GRS80, GRS80, (0.0522, 0.0491, -0.0521), (0.001, 0.002, -0.003), 0.04),
    Helmert("ref_7p_cf", 7, "CF", GRS80, GRS80, (0.0522, 0.0491, -0.0521), (0.001, 0.002, -0.003), 0.04),
    Helmert("grande_7p_pv", 7, "PV", CLARKE_1880, GRS80, (100.0, -200.0, 300.0), (1.5, -2.0, 3.0), 15.0),
    Helmert("grande_7p_cf", 7, "CF", CLARKE_1880, GRS80, (100.0, -200.0, 300.0), (1.5, -2.0, 3.0), 15.0),
    Helmert("enorme_7p_pv", 7, "PV", BESSEL, WGS84, (500.0, 500.0, -500.0), (10.0, -10.0, 10.0), 50.0),
]


METODO_MB_CF = "Molodensky-Badekas (CF geog2D domain)"
METODO_MB_PV = "Molodensky-Badekas (PV geog2D domain)"

# Operaciones de EPSG de Molodensky-Badekas: Amersfoort a ETRS89 (CF), Ocotepeque 1935 a WGS 84 y Pulkovo 1942(83) a BGS2005 (PV).
OPERACIONES_MB = [
    (1066, METODO_MB_CF, 7, "CF"),
    (6889, METODO_MB_PV, 7, "PV"),
    (7806, METODO_MB_PV, 7, "PV"),
]

# Punto de rotación cerca del centro de la Península, en geocéntricas de GRS80 (m).
PIVOTE_IBERIA = (4_800_000.0, -300_000.0, 4_150_000.0)

# Casos propios: con el punto de rotación en el centro de la Tierra MB es el Helmert de 7 parámetros; con uno cerca del área, no.
CASOS_MB = [
    Helmert("mb_pivote_cero_pv", 7, "PV", INTERNACIONAL, GRS80, (-120.0, -90.0, -150.0), (0.5, -0.8, 1.2), 3.0, pivote=(0.0, 0.0, 0.0)),
    Helmert("mb_pivote_iberia_pv", 7, "PV", INTERNACIONAL, GRS80, (-120.0, -90.0, -150.0), (0.5, -0.8, 1.2), 3.0, pivote=PIVOTE_IBERIA),
    Helmert("mb_pivote_iberia_cf", 7, "CF", INTERNACIONAL, GRS80, (-120.0, -90.0, -150.0), (0.5, -0.8, 1.2), 3.0, pivote=PIVOTE_IBERIA),
    Helmert("mb_grande_cf", 7, "CF", CLARKE_1880, GRS80, (100.0, -200.0, 300.0), (1.5, -2.0, 3.0), 15.0, pivote=PIVOTE_IBERIA),
]


def _elipsoide(paso: str) -> Elipsoide:
    """Elipsoide de un paso `proj=cart` de una tubería de PROJ (`ellps=NOMBRE` o `a=… b=…`)."""
    campos = dict(token.split("=") for token in paso.split() if "=" in token)
    if "ellps" in campos:
        definicion = pyproj.get_ellps_map()[campos["ellps"]]
        semieje = float(definicion["a"])
        if "rf" in definicion:
            return Elipsoide(semieje, float(definicion["rf"]))
        return Elipsoide(semieje, semieje / (semieje - float(definicion["b"])))
    semieje = float(campos["a"])
    return Elipsoide(semieje, semieje / (semieje - float(campos["b"])))


def operacion_epsg(codigo: int, metodo: str, n: int, convencion: str) -> Helmert:
    """Una operación de EPSG como `Helmert`, tras comprobar su método en la base de PROJ."""
    t = Transformer.from_pipeline(f"urn:ogc:def:coordinateOperation:EPSG::{codigo}")
    real = t.to_json_dict()["method"]["name"]
    if real != metodo:
        raise SystemExit(f"EPSG:{codigo} es «{real}» y se esperaba «{metodo}»")
    pasos = [paso.strip() for paso in t.definition.split(" step ")]
    cart = [p for p in pasos if p.replace("inv ", "").startswith("proj=cart")]
    origen = [p for p in pasos if p.startswith("inv proj=longlat")]
    helmert = next(p for p in pasos if p.startswith(("proj=helmert", "proj=molobadekas")))
    campos = dict(token.split("=") for token in helmert.split() if "=" in token)
    pivote = (float(campos["px"]), float(campos["py"]), float(campos["pz"])) if "px" in campos else None
    elipsoide_origen = _elipsoide(origen[0]) if origen else _elipsoide(cart[0])
    elipsoide_destino = _elipsoide(cart[-1].replace("inv ", ""))
    area = t.area_of_use
    return Helmert(
        f"EPSG:{codigo}",
        n,
        convencion,
        elipsoide_origen,
        elipsoide_destino,
        (float(campos["x"]), float(campos["y"]), float(campos["z"])),
        (float(campos.get("rx", 0.0)), float(campos.get("ry", 0.0)), float(campos.get("rz", 0.0))),
        float(campos.get("s", 0.0)),
        (area.west, area.south, area.east, area.north) if area else None,
        pivote,
    )


def _puntos(rng: random.Random, area: tuple[float, float, float, float] | None) -> list[tuple[float, float, float]]:
    """(longitud, latitud, altura): una rejilla y puntos al azar en el ámbito (o en la Península y el mundo si no hay)."""
    if area is not None:
        oeste, sur, este, norte = area
        rejilla = [
            (oeste + (este - oeste) * i / 4, sur + (norte - sur) * j / 4, h)
            for i in range(5)
            for j in range(5)
            for h in ((0.0,) if (i + j) % 2 else (150.0,))
        ]
        azar = [(rng.uniform(oeste, este), rng.uniform(sur, norte), rng.uniform(-20.0, 900.0)) for _ in range(15)]
    else:
        rejilla = [(lon, lat, h) for lon in (-9.0, -5.0, -3.7, 0.0, 3.0) for lat in (36.0, 40.4, 43.5) for h in (0.0, 600.0)]
        azar = [(rng.uniform(-9.5, 3.5), rng.uniform(35.5, 44.0), rng.uniform(-20.0, 1500.0)) for _ in range(15)]
        azar += [(rng.uniform(-180.0, 180.0), rng.uniform(-80.0, 80.0), rng.uniform(-100.0, 8800.0)) for _ in range(10)]
    return rejilla + azar


COLUMNAS_HELMERT = (
    "caso,n_parametros,convencion,semieje_origen,rf_origen,semieje_destino,rf_destino,tx,ty,tz,rx_seg,ry_seg,rz_seg,escala_ppm"
)
COLUMNAS_PUNTO = "latitud,longitud,altura,latitud_dir,longitud_dir,altura_dir,latitud_inv,longitud_inv,altura_inv"


def helmert_vectors(rng: random.Random) -> list[str]:
    casos = [operacion_epsg(*operacion) for operacion in OPERACIONES_HELMERT] + CASOS_PROPIOS
    return _filas(header("Helmert de 3, 4, 6 y 7 parámetros, PV y CF (pyproj/PROJ y operaciones de EPSG)"), casos, rng, COLUMNAS_HELMERT)


def molodensky_badekas_vectors(rng: random.Random) -> list[str]:
    casos = [operacion_epsg(*operacion) for operacion in OPERACIONES_MB] + CASOS_MB
    return _filas(
        header("Molodensky-Badekas de 10 parámetros, PV y CF (pyproj/PROJ y operaciones de EPSG)"),
        casos,
        rng,
        COLUMNAS_HELMERT + ",pivote_x,pivote_y,pivote_z",
    )


def _filas(cabecera: list[str], casos: list[Helmert], rng: random.Random, columnas: str) -> list[str]:
    lineas = cabecera + [f"{columnas},{COLUMNAS_PUNTO}"]
    for caso in casos:
        tuberia = caso.tuberia()
        fijos = [
            caso.caso,
            str(caso.n),
            caso.convencion,
            fmt(caso.origen.semieje),
            fmt(caso.origen.rf),
            fmt(caso.destino.semieje),
            fmt(caso.destino.rf),
            *(fmt(v) for v in caso.t),
            *(fmt(v) for v in caso.r),
            fmt(caso.escala),
            *(fmt(v) for v in caso.pivote or ()),
        ]
        for lon, lat, h in _puntos(rng, caso.area):
            lon_d, lat_d, h_d = tuberia.transform(lon, lat, h)
            lon_i, lat_i, h_i = tuberia.transform(lon, lat, h, direction="INVERSE")
            lineas.append(",".join(fijos + [fmt(lat), fmt(lon), fmt(h), fmt(lat_d), fmt(lon_d), fmt(h_d), fmt(lat_i), fmt(lon_i), fmt(h_i)]))
    return lineas


def _rejilla_ed50(inverso: bool) -> Transformer:
    """La rejilla SPED2ETV2 de la app como `hgridshift`: directa ED50 → ETRS89, inversa ETRS89 → ED50 (EPSG:15932), solo con la copia local."""
    pyproj.network.set_network_enabled(False)
    pyproj.datadir.append_data_dir(str(rejillas.ED50_SHIFT.path.parent))
    return Transformer.from_pipeline(
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step {'+inv ' if inverso else ''}+proj=hgridshift +grids={rejillas.ED50_SHIFT.name} "
        "+step +proj=unitconvert +xy_in=rad +xy_out=deg"
    )


def _dentro(t: Transformer, lat: float, lon: float) -> bool:
    return all(math.isfinite(v) for v in t.transform(lon, lat))


def _borde(t: Transformer, dentro: float, fuera: float, fijo: float, es_latitud: bool) -> float:
    """Borde de la rejilla entre un punto dentro y otro fuera, a lo largo de una latitud o una longitud fijas, por bisección (1e-9°)."""
    while abs(fuera - dentro) > 1e-9:
        medio = (dentro + fuera) / 2
        if _dentro(t, medio if es_latitud else fijo, fijo if es_latitud else medio):
            dentro = medio
        else:
            fuera = medio
    return dentro


def rejilla_vectors(rng: random.Random) -> list[str]:
    """Rejilla de desplazamiento horizontal (SPED2ETV2): directa e inversa de PROJ en el interior, los bordes y fuera de ella."""
    directa, inversa = _rejilla_ed50(False), _rejilla_ed50(True)
    puntos: list[tuple[str, float, float]] = []
    puntos += [("peninsula", lat, lon) for lat in (36.0, 37.5, 39.0, 40.4168, 42.0, 43.5) for lon in (-9.0, -7.0, -5.0, -3.7038, -2.0, 0.0, 2.0, 3.0)]
    puntos += [("peninsula", rng.uniform(36.0, 43.5), rng.uniform(-9.0, 3.2)) for _ in range(40)]
    puntos += [("baleares", lat, lon) for lat, lon in ((39.5696, 2.6502), (39.95, 4.1), (38.9, 1.43), (39.6, 3.0), (39.8, 3.9))]
    puntos += [("baleares", rng.uniform(38.7, 40.1), rng.uniform(1.2, 4.4)) for _ in range(10)]
    # Bordes de la subrejilla de la Península, a ± 1e-4° de cada uno (los de un nodo exacto no se prueban: PROJ y TopoPhone toleran la orilla).
    norte = _borde(directa, 43.5, 46.0, -3.0, True)
    sur = _borde(directa, 38.0, 33.0, -3.0, True)
    oeste = _borde(directa, -3.0, -13.0, 40.0, False)
    este = _borde(directa, 0.0, 6.0, 41.0, False)
    for nombre, lat, lon in (
        ("borde_dentro", norte - 1e-4, -3.0), ("borde_fuera", norte + 1e-4, -3.0),
        ("borde_dentro", sur + 1e-4, -3.0), ("borde_fuera", sur - 1e-4, -3.0),
        ("borde_dentro", 40.0, oeste + 1e-4), ("borde_fuera", 40.0, oeste - 1e-4),
        ("borde_dentro", 41.0, este - 1e-4), ("borde_fuera", 41.0, este + 1e-4),
    ):
        puntos.append((nombre, lat, lon))
    puntos += [("fuera", lat, lon) for lat, lon in ((28.1, -15.4), (45.5, -3.0), (34.0, -3.0), (40.0, -12.0), (0.0, 0.0), (-33.9, 18.4))]
    lineas = header("Rejilla de desplazamiento horizontal SPED2ETV2 (hgridshift de PROJ, directa ED50→ETRS89 e inversa)") + [
        "grupo,latitud,longitud,latitud_dir,longitud_dir,latitud_inv,longitud_inv"
    ]
    for grupo, lat, lon in puntos:
        valores = []
        for t in (directa, inversa):
            lon_s, lat_s = t.transform(lon, lat)
            valores += [fmt(lat_s), fmt(lon_s)] if math.isfinite(lat_s) and math.isfinite(lon_s) else ["", ""]
        if grupo != "fuera" and not grupo.endswith("fuera") and "" in valores[:2]:
            raise SystemExit(f"punto dentro sin desplazamiento: {grupo} {lat}, {lon}")
        lineas.append(",".join([grupo, fmt(lat), fmt(lon), *valores]))
    return lineas


REJILLA_ED50 = "G"

# Cadenas de varios pasos. Un paso es un Helmert (sus elipsoides: el de destino de uno es el de origen del siguiente) o la rejilla SPED2ETV2
# («G»), que trabaja en geográficas del datum de origen (ED50) y da las del de destino (ETRS89) sin cambiar de elipsoide por su cuenta.
CADENAS = {
    # Datum local (WGS 84) → ED50 por Helmert → ETRS89 por rejilla → otro marco por Helmert: el ejemplo de tres pasos de la spec.
    "helmert_rejilla_helmert": [
        Helmert("a", 7, "PV", WGS84, INTERNACIONAL, (-100.0, -90.0, -110.0), (0.1, 0.2, -0.3), 1.5),
        REJILLA_ED50,
        Helmert("c", 7, "CF", GRS80, GRS80, (0.0522, 0.0491, -0.0521), (0.001, 0.002, -0.003), 0.04),
    ],
    # Dos Helmert seguidos (que se pueden componer en geocéntricas) antes de la rejilla y otro después.
    "helmert_helmert_rejilla_helmert": [
        Helmert("a", 6, "PV", WGS84, WGS84, (1.2, -0.8, 2.1), (0.05, -0.04, 0.03), 0.0),
        Helmert("b", 7, "CF", WGS84, INTERNACIONAL, (-100.0, -90.0, -110.0), (0.1, 0.2, -0.3), 1.5),
        REJILLA_ED50,
        Helmert("d", 3, "PV", GRS80, GRS80, (0.5, -0.25, 0.75), (0.0, 0.0, 0.0), 0.0),
    ],
}


def _codifica_paso(paso: Helmert | str) -> str:
    """`G` o `H:conv:a:rf:a:rf:tx:ty:tz:rx:ry:rz:s:n`, sin comas ni barras, para ir en una columna del CSV."""
    if isinstance(paso, str):
        return paso
    campos = [
        "H", paso.convencion, fmt(paso.origen.semieje), fmt(paso.origen.rf), fmt(paso.destino.semieje), fmt(paso.destino.rf),
        *(fmt(v) for v in paso.t), *(fmt(v) for v in paso.r), fmt(paso.escala), str(paso.n),
    ]
    return ":".join(campos)


def _tuberia_cadena(pasos: list[Helmert | str]) -> Transformer:
    """La cadena entera como una tubería de PROJ, con una conversión completa a cartesianas por cada Helmert (la referencia independiente)."""
    _rejilla_ed50(False)  # solo registra la carpeta de la rejilla local
    partes = ["+proj=pipeline", "+step +proj=unitconvert +xy_in=deg +xy_out=rad"]
    for paso in pasos:
        if isinstance(paso, str):
            partes.append(f"+step +proj=hgridshift +grids={rejillas.ED50_SHIFT.name}")
            continue
        convencion = "position_vector" if paso.convencion == "PV" else "coordinate_frame"
        parametros = [f"+x={paso.t[0]!r}", f"+y={paso.t[1]!r}", f"+z={paso.t[2]!r}"]
        if paso.n in (6, 7):
            parametros += [f"+rx={paso.r[0]!r}", f"+ry={paso.r[1]!r}", f"+rz={paso.r[2]!r}"]
        if paso.n in (4, 7):
            parametros.append(f"+s={paso.escala!r}")
        partes += [
            f"+step +proj=cart {paso.origen.tuberia}",
            f"+step +proj=helmert {' '.join(parametros)} +convention={convencion}",
            f"+step +inv +proj=cart {paso.destino.tuberia}",
        ]
    partes.append("+step +proj=unitconvert +xy_in=rad +xy_out=deg")
    return Transformer.from_pipeline(" ".join(partes))


def cadena_vectors(rng: random.Random) -> list[str]:
    """Cadenas de tres y cuatro pasos de PROJ en la Península: solo la directa (la vuelta se prueba con la ida y vuelta de TopoPhone)."""
    lineas = header("Cadenas de transformaciones de datum: Helmert → rejilla → Helmert (la tubería completa de PROJ, directa)") + [
        "caso,pasos,latitud,longitud,altura,latitud_dir,longitud_dir,altura_dir"
    ]
    for nombre, pasos in CADENAS.items():
        tuberia = _tuberia_cadena(pasos)
        codificados = "|".join(_codifica_paso(p) for p in pasos)
        puntos = [(lon, lat, h) for lon in (-8.5, -5.0, -3.7, 0.0, 2.5) for lat in (37.0, 40.4, 43.0) for h in (0.0, 600.0)]
        puntos += [(rng.uniform(-9.0, 3.2), rng.uniform(36.0, 43.5), rng.uniform(-20.0, 1500.0)) for _ in range(40)]
        for lon, lat, h in puntos:
            lon_d, lat_d, h_d = tuberia.transform(lon, lat, h)
            if not all(math.isfinite(v) for v in (lon_d, lat_d, h_d)):
                raise SystemExit(f"punto de la cadena {nombre} sin imagen: {lat}, {lon}")
            lineas.append(",".join([nombre, codificados, fmt(lat), fmt(lon), fmt(h), fmt(lat_d), fmt(lon_d), fmt(h_d)]))
    return lineas


def todos() -> list[tuple[str, list[str]]]:
    """Todos los ficheros de este módulo, con su nombre: los usa `generar_vectores.py` y `main` de aquí."""
    return [
        ("helmert.csv", helmert_vectors(random.Random(SEMILLA_HELMERT))),
        ("molodensky_badekas.csv", molodensky_badekas_vectors(random.Random(SEMILLA_MB))),
        ("rejilla_desplazamiento.csv", rejilla_vectors(random.Random(SEMILLA_REJILLA))),
        ("cadena_tres_pasos.csv", cadena_vectors(random.Random(SEMILLA_CADENA))),
    ]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for nombre, lineas in todos():
        (OUT / nombre).write_text("\n".join(lineas) + "\n", encoding="utf-8", newline="\n")
        print(f"{nombre}: {len(lineas) - 3} vectores")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
