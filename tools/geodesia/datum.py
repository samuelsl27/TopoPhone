"""Vectores de referencia de las transformaciones de datum de S033, generados con pyproj/PROJ (constitución nº 2, ADR-2).

Cada familia tiene su generador y su CSV en `fixtures/geodesia/` (los llama `generar_vectores.py`):
- helmert.csv: Helmert de 3, 4, 6 y 7 parámetros en convención PV y CF, con operaciones de EPSG y casos propios (S033/RF-1, RF-2, RF-5).

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

import random
from dataclasses import dataclass
from pathlib import Path

import pyproj
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "geodesia"

SEMILLA_HELMERT = 20261127

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

    def tuberia(self) -> Transformer:
        convencion = "position_vector" if self.convencion == "PV" else "coordinate_frame"
        partes = [f"+x={self.t[0]!r}", f"+y={self.t[1]!r}", f"+z={self.t[2]!r}"]
        if self.n in (6, 7):
            partes += [f"+rx={self.r[0]!r}", f"+ry={self.r[1]!r}", f"+rz={self.r[2]!r}"]
        if self.n in (4, 7):
            partes.append(f"+s={self.escala!r}")
        partes.append(f"+convention={convencion}")
        return Transformer.from_pipeline(
            "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
            f"+step +proj=cart {self.origen.tuberia} +step +proj=helmert {' '.join(partes)} "
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
    helmert = next(p for p in pasos if p.startswith("proj=helmert"))
    campos = dict(token.split("=") for token in helmert.split() if "=" in token)
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


def helmert_vectors(rng: random.Random) -> list[str]:
    lineas = header("Helmert de 3, 4, 6 y 7 parámetros, PV y CF (pyproj/PROJ y operaciones de EPSG)") + [
        "caso,n_parametros,convencion,semieje_origen,rf_origen,semieje_destino,rf_destino,tx,ty,tz,rx_seg,ry_seg,rz_seg,escala_ppm,"
        "latitud,longitud,altura,latitud_dir,longitud_dir,altura_dir,latitud_inv,longitud_inv,altura_inv"
    ]
    casos = [operacion_epsg(*operacion) for operacion in OPERACIONES_HELMERT] + CASOS_PROPIOS
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
        ]
        for lon, lat, h in _puntos(rng, caso.area):
            lon_d, lat_d, h_d = tuberia.transform(lon, lat, h)
            lon_i, lat_i, h_i = tuberia.transform(lon, lat, h, direction="INVERSE")
            lineas.append(",".join(fijos + [fmt(lat), fmt(lon), fmt(h), fmt(lat_d), fmt(lon_d), fmt(h_d), fmt(lat_i), fmt(lon_i), fmt(h_i)]))
    return lineas


def todos() -> list[tuple[str, list[str]]]:
    """Todos los ficheros de este módulo, con su nombre: los usa `generar_vectores.py` y `main` de aquí."""
    return [
        ("helmert.csv", helmert_vectors(random.Random(SEMILLA_HELMERT))),
    ]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for nombre, lineas in todos():
        (OUT / nombre).write_text("\n".join(lineas) + "\n", encoding="utf-8", newline="\n")
        print(f"{nombre}: {len(lineas) - 3} vectores")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
