"""Vectores de contraste del catálogo de SC de S034: tres puntos por SC proyectado, calculados con pyproj/PROJ (constitución nº 2).

Uso: uv run --project tools tools/geodesia/catalogo_vectores.py   (escribe `fixtures/geodesia/catalogo_<método>.csv`)

Cada fila: SC de EPSG, número de punto, latitud y longitud (grados, longitud de Greenwich, en el datum del SC) y Este y Norte en metros. La
referencia es el `Transformer` de PROJ desde el CRS geográfico del propio SC hasta el SC, con ejes Este/Norte (`always_xy`) y pasando a metros
con la unidad del eje. Los tres puntos caen dentro del ámbito de uso del SC, en las fracciones (0,5; 0,5), (0,25; 0,35) y (0,75; 0,65) de su
recuadro, de modo que son los mismos al regenerar. Los SC geográficos no tienen vectores: no proyectan nada.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import pyproj
from pyproj import CRS, Transformer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalogo as c  # noqa: E402

OUT = c.ROOT / "fixtures" / "geodesia"
FRACCIONES = [(0.5, 0.5), (0.25, 0.35), (0.75, 0.65)]  # (de sur a norte, de oeste a este)
MAX_COORDENADA_M = 1e9


def puntos_de_prueba(ambito: str, fracciones: list[tuple[float, float]] | None = None) -> list[tuple[float, float]]:
    """(latitud, longitud) de los tres puntos dentro del recuadro S,W,N,E; si cruza el antimeridiano (W > E) se cuenta hacia el este."""
    sur, oeste, norte, este = (float(v) for v in ambito.split(","))
    if este < oeste:
        este += 360.0
    puntos = []
    for fraccion_lat, fraccion_lon in fracciones or FRACCIONES:
        longitud = oeste + (este - oeste) * fraccion_lon
        longitud = (longitud + 180.0) % 360.0 - 180.0
        puntos.append((round(sur + (norte - sur) * fraccion_lat, 6), round(longitud, 6)))
    return puntos


def _vectores_de(fila: list[str]) -> list[list[str]]:
    epsg = int(fila[c.CRS_EPSG])
    crs = CRS.from_epsg(epsg)
    geografico = crs.geodetic_crs
    factor_angulo = geografico.axis_info[0].unit_conversion_factor  # radianes por unidad del eje
    meridiano = c._grados(
        geografico.prime_meridian.longitude, geografico.prime_meridian.unit_name, geografico.prime_meridian.unit_conversion_factor or 1.0
    )
    transformador = Transformer.from_crs(geografico, crs, always_xy=True)
    unidad_m = float(fila[c.CRS_UNIDAD_M])
    filas = []
    for numero, (latitud, longitud) in enumerate(puntos_de_prueba(fila[c.CRS_AMBITO]), start=1):
        x = math.radians(longitud - meridiano) / factor_angulo
        y = math.radians(latitud) / factor_angulo
        este, norte = transformador.transform(x, y)
        if not (math.isfinite(este) and math.isfinite(norte)) or max(abs(este), abs(norte)) > MAX_COORDENADA_M:
            continue
        filas.append([str(epsg), str(numero), c.num(latitud), c.num(longitud), c.num(este * unidad_m), c.num(norte * unidad_m)])
    return filas


def generar_vectores(secciones: dict[str, list[list[str]]]) -> dict[str, list[list[str]]]:
    """Método del catálogo → filas de vectores de sus SC proyectados."""
    por_metodo: dict[str, list[list[str]]] = {}
    for fila in secciones["crs"]:
        if fila[c.CRS_TIPO] != "P" or not fila[c.CRS_AMBITO]:
            continue
        por_metodo.setdefault(fila[c.CRS_METODO], []).extend(_vectores_de(fila))
    return por_metodo


COLUMNAS_CSV = ["epsg", "punto", "latitud", "longitud", "este_m", "norte_m"]


def a_csv(metodo: str, filas: list[list[str]]) -> str:
    cabecera = [
        f"# Vectores del catálogo de SC de S034, método {metodo}. Generado por tools/geodesia/catalogo_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        ",".join(COLUMNAS_CSV),
    ]
    return "\n".join(cabecera + [",".join(f) for f in filas]) + "\n"


def escribir(vectores: dict[str, list[list[str]]], carpeta: Path = OUT) -> list[Path]:
    carpeta.mkdir(parents=True, exist_ok=True)
    rutas = []
    for metodo, filas in sorted(vectores.items()):
        ruta = carpeta / f"catalogo_{metodo}.csv"
        ruta.write_text(a_csv(metodo, filas), encoding="utf-8", newline="\n")
        rutas.append(ruta)
    return rutas


# --- Transformaciones de datum de cada opción (sin rejillas, o con las que van en la app) ----------------------------------------------

REJILLAS_DE_LA_APP = {"es_ign_SPED2ETV2.tif"}
FRACCIONES_TRANSFORMACION = [(0.5, 0.5), (0.3, 0.7)]


def generar_vectores_transformaciones(secciones: dict[str, list[list[str]]]) -> list[list[str]]:
    """Para cada opción cuyos pasos son Helmert, Molodensky-Badekas o nulos (o la rejilla ED50 de la app): dos puntos dentro de su ámbito y lo que
    da la tubería de PROJ (altura 0, como las operaciones 2D de EPSG). Sirve para comprobar la traducción de las tuberías a los pasos."""
    import warnings

    from pyproj.transformer import TransformerGroup

    pyproj.network.set_network_enabled(True)
    datums = {f[c.DATUM_CODIGO]: f for f in secciones["datums"]}
    filas: list[list[str]] = []
    for opcion in secciones["options"]:
        if not set(c.rejillas_de(opcion[c.OPCION_PASOS])) <= REJILLAS_DE_LA_APP or not opcion[c.OPCION_AMBITO]:
            continue
        datum = datums[opcion[c.OPCION_DATUM]]
        if int(datum[c.DATUM_CODIGO]) == c.REGCAN95:
            continue
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            try:
                grupo = TransformerGroup(int(datum[c.DATUM_CODIGO]), c.DESTINOS[opcion[c.OPCION_DESTINO]], always_xy=True, allow_ballpark=False)
            except (IndexError, pyproj.exceptions.ProjError):
                continue
        for t in grupo.transformers:
            if c.limpiar(t.description.removesuffix(c.SUFIJO_EJES)) != opcion[c.OPCION_NOMBRE]:
                continue
            if c.pasos_de_tuberia(t.definition, float(datum[c.DATUM_MERIDIANO])) != opcion[c.OPCION_PASOS]:
                continue
            for latitud, longitud in puntos_de_prueba(opcion[c.OPCION_AMBITO], FRACCIONES_TRANSFORMACION):
                # PROJ cuenta la longitud desde Greenwich y espera las del CRS: para meridianos de origen distintos se usa la de Greenwich.
                x = math.radians(longitud - float(datum[c.DATUM_MERIDIANO])) / grupo_unidad(t, datum)
                y = math.radians(latitud) / grupo_unidad(t, datum)
                lon_out, lat_out = t.transform(x, y)
                if not (math.isfinite(lon_out) and math.isfinite(lat_out)):
                    continue
                filas.append([opcion[c.OPCION_DATUM], opcion[c.OPCION_DESTINO], opcion[c.OPCION_NOMBRE], c.num(latitud), c.num(longitud), c.num(lat_out), c.num(lon_out)])
            break
    return filas


def grupo_unidad(transformador, datum: list[str]) -> float:
    """Radianes por unidad de los ejes del CRS geográfico de origen (el grado casi siempre; el gon en las NTF de París)."""
    return CRS.from_epsg(int(datum[c.DATUM_CODIGO])).axis_info[0].unit_conversion_factor


def a_csv_transformaciones(filas: list[list[str]]) -> str:
    """CSV de los vectores; las comas del nombre de la transformación (la tercera columna) se escriben como `;`."""
    cabecera = [
        "# Transformaciones de datum de cada opción del catálogo de S034 (altura 0). Generado por tools/geodesia/catalogo_vectores.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        "datum,destino,nombre,latitud,longitud,latitud_destino,longitud_destino",
    ]
    cuerpo = [",".join(valor.replace(",", ";") if k == 2 else valor for k, valor in enumerate(fila)) for fila in filas]
    return "\n".join(cabecera + cuerpo) + "\n"


def main() -> int:
    secciones = c.generar()
    c.escribir(secciones)
    rutas = escribir(generar_vectores(secciones))
    (OUT / "catalogo_transformaciones.csv").write_text(
        a_csv_transformaciones(generar_vectores_transformaciones(secciones)), encoding="utf-8", newline="\n"
    )
    print(f"{len(rutas)} ficheros de vectores y las transformaciones")
    return 0


if __name__ == "__main__":
    sys.exit(main())
