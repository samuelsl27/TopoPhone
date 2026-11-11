"""Shapefiles de ejemplo escritos por QGIS (GDAL) y lo que QGIS lee de ellos (S030, criterio de finalización).

Este script NO se ejecuta con `uv`: necesita PyQGIS, que viene con QGIS. Se ejecuta con el Python de QGIS, por ejemplo:

    "C:\\Program Files\\QGIS 3.44.6\\bin\\python-qgis.bat" tools/formatos/qgis_shapefile_ejemplos.py

Escribe en fixtures/formatos/shp/qgis/ una carpeta por escenario con los ficheros que escribe QGIS al exportar a «ESRI Shapefile»
(`.shp`, `.shx`, `.dbf`, `.prj` y `.cpg`, tal como salen, sin tocarlos) y tres ficheros con lo que QGIS lee de ellos al abrirlos de nuevo:

- esperado-qgis-capas.csv: por capa, el tipo de geometría, los objetos, el sistema que QGIS reconoce del `.prj` y el contenido del `.cpg`.
- esperado-qgis-geometria.csv: por parte de cada objeto, sus vértices ("x y z", `_` si no hay Z), en el sistema de la capa.
- esperado-qgis-atributos.csv: por valor de cada objeto, su columna y su valor tal como lo ve QGIS.

Las coordenadas de proyecto (ETRS89 UTM 30) de cada vértice las calcula el test con pyproj, que es un cálculo aparte de QGIS. Los ficheros
guardados no se regeneran en los tests (QGIS no está en `tools`): se comprueban con otro lector y con pyproj. La versión de QGIS y de GDAL que
los escribió queda en fixtures/formatos/shp/qgis/LEEME.txt.
"""

from __future__ import annotations

import csv
import io
import os
import shutil
import sys
import tempfile
from pathlib import Path

from qgis.core import (
    Qgis,
    QgsApplication,
    QgsCoordinateReferenceSystem,
    QgsCoordinateTransform,
    QgsCoordinateTransformContext,
    QgsFeature,
    QgsField,
    QgsGeometry,
    QgsProject,
    QgsVectorFileWriter,
    QgsVectorLayer,
)
from qgis.PyQt.QtCore import QDate, QVariant
from osgeo import gdal

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "shp" / "qgis"
E0, N0 = 440000.0, 4474000.0  # esquina de los dibujos, en ETRS89 UTM 30 (Madrid)
PROJECT = QgsProject.instance()


def fields(*items):
    out = []
    for name, kind, length, precision in items:
        out.append(QgsField(name, kind, "", length, precision))
    return out


def memory(name: str, wkb: str, epsg: str, schema: list) -> QgsVectorLayer:
    layer = QgsVectorLayer(f"{wkb}?crs={epsg}", name, "memory")
    layer.dataProvider().addAttributes(schema)
    layer.updateFields()
    return layer


def feature(layer: QgsVectorLayer, wkt: str, *values) -> None:
    f = QgsFeature(layer.fields())
    f.setGeometry(QgsGeometry.fromWkt(wkt))
    f.setAttributes(list(values))
    layer.dataProvider().addFeature(f)


def ring(e: float, n: float, size: float, closed: bool = True) -> str:
    pts = [(e, n), (e, n + size), (e + size, n + size), (e + size, n), (e, n)]
    return ", ".join(f"{E0 + x!r} {N0 + y!r}" for x, y in pts)


def to_epsg(epsg_from: str, epsg_to: str, x: float, y: float) -> tuple[float, float]:
    transform = QgsCoordinateTransform(QgsCoordinateReferenceSystem(epsg_from), QgsCoordinateReferenceSystem(epsg_to), PROJECT)
    p = transform.transform(x, y)
    return p.x(), p.y()


def catastro() -> list[QgsVectorLayer]:
    parcelas = memory("parcelas", "Polygon", "EPSG:25830", fields(("ref", QVariant.String, 20, 0), ("nombre", QVariant.String, 40, 0),
                                                                    ("area_m2", QVariant.Double, 12, 2), ("uso", QVariant.Int, 6, 0)))
    feature(parcelas, f"POLYGON (({ring(0, 0, 100)}))", "0001A", "Parcela 1", 10000.0, 1)
    feature(parcelas, f"POLYGON (({ring(150, 0, 200)}), ({ring(170, 20, 40)}), ({ring(250, 100, 60)}))", "0002B", "Parcela 2 (con huecos)", 38800.0, 2)
    lindes = memory("lindes", "LineString", "EPSG:25830", fields(("nombre", QVariant.String, 40, 0), ("ancho", QVariant.Double, 8, 2)))
    feature(lindes, f"LINESTRING ({E0} {N0 + 300}, {E0 + 120} {N0 + 340}, {E0 + 240} {N0 + 300})", "Calle Mayor", 12.5)
    feature(lindes, f"LINESTRING ({E0} {N0 + 400}, {E0 + 300} {N0 + 450})", "Camino de Árboles", None)
    mojones = memory("mojones", "PointZ", "EPSG:25830", fields(("nombre", QVariant.String, 40, 0), ("fecha", QVariant.Date, 10, 0),
                                                                ("activo", QVariant.Bool, 1, 0)))
    feature(mojones, f"POINT Z ({E0 + 100} {N0 + 100} 646.377)", "Esquina norte", QDate(2026, 10, 1), True)
    feature(mojones, f"POINT Z ({E0 + 150} {N0 + 120} 647.125)", "Árbol ñandú", QDate(2026, 10, 2), False)
    return [parcelas, lindes, mojones]


def geograficas() -> list[QgsVectorLayer]:
    ejes = memory("ejes", "LineStringZ", "EPSG:4258", fields(("nombre", QVariant.String, 40, 0)))
    pts = [to_epsg("EPSG:25830", "EPSG:4258", E0 + e, N0 + n) for e, n in ((0, 0), (100, 50), (200, 0))]
    feature(ejes, "LINESTRING Z (" + ", ".join(f"{x!r} {y!r} {650 + i}" for i, (x, y) in enumerate(pts)) + ")", "Eje 1")
    puntos = memory("multipuntos", "MultiPoint", "EPSG:4258", fields(("nombre", QVariant.String, 40, 0)))
    group = [to_epsg("EPSG:25830", "EPSG:4258", E0 + e, N0 + n) for e, n in ((50, 50), (90, 70), (130, 40))]
    feature(puntos, "MULTIPOINT (" + ", ".join(f"{x!r} {y!r}" for x, y in group) + ")", "Grupo")
    return [ejes, puntos]


def canarias() -> list[QgsVectorLayer]:
    layer = memory("vertices", "Point", "EPSG:4083", fields(("nombre", QVariant.String, 40, 0)))
    for i in range(3):
        x, y = to_epsg("EPSG:4081", "EPSG:4083", -15.4 + 0.001 * i, 28.1 + 0.001 * i)
        feature(layer, f"POINT ({x!r} {y!r})", f"Gran Canaria {i}")
    return [layer]


def ed50() -> list[QgsVectorLayer]:
    layer = memory("vertices", "Point", "EPSG:23030", fields(("nombre", QVariant.String, 40, 0)))
    for i, (e, n) in enumerate(((100, 100), (220, 160), (340, 90)), 1):
        x, y = to_epsg("EPSG:25830", "EPSG:23030", E0 + e, N0 + n)
        feature(layer, f"POINT ({x!r} {y!r})", f"Punto {i}")
    return [layer]


def windows_1252() -> list[QgsVectorLayer]:
    layer = memory("nombres", "Point", "EPSG:25830", fields(("nombre", QVariant.String, 40, 0)))
    for i, text in enumerate(("Árbol ñandú", "Camión", "Sin tildes"), 1):
        feature(layer, f"POINT ({E0 + 20 * i} {N0 + 20 * i})", text)
    return [layer]


SCENARIOS = {
    "catastro-25830": (catastro, "UTF-8"),
    "geograficas-4258": (geograficas, "UTF-8"),
    "canarias-4083": (canarias, "UTF-8"),
    "ed50-23030": (ed50, "UTF-8"),
    "windows-1252": (windows_1252, "CP1252"),
}


def write(layer: QgsVectorLayer, folder: Path, encoding: str) -> Path:
    options = QgsVectorFileWriter.SaveVectorOptions()
    options.driverName = "ESRI Shapefile"
    options.fileEncoding = encoding
    path = folder / f"{layer.name()}.shp"
    error, message, *_ = QgsVectorFileWriter.writeAsVectorFormatV3(layer, str(path), QgsCoordinateTransformContext(), options)
    if error != QgsVectorFileWriter.NoError:
        raise RuntimeError(f"{path}: {message}")
    return path


def vertices(geometry: QgsGeometry) -> list[tuple[str, list[str]]]:
    """(parte, vértices) de una geometría: un anillo o una línea o un punto por parte."""
    def text(v) -> str:
        return f"{v.x()!r} {v.y()!r} {(repr(v.z()) if geometry.constGet().is3D() else '_')}"

    kind = QgsGeometry.type(geometry)
    abstract = geometry.constGet()
    out = []
    if kind == Qgis.GeometryType.Point:
        for i, part in enumerate(abstract.parts() if geometry.isMultipart() else [abstract]):
            out.append((str(i), [text(part)]))
    elif kind == Qgis.GeometryType.Line:
        for i, part in enumerate(abstract.parts() if geometry.isMultipart() else [abstract]):
            out.append((str(i), [text(v) for v in part.vertices()]))
    else:
        index = 0
        for polygon in abstract.parts() if geometry.isMultipart() else [abstract]:
            out.append((str(index), [text(v) for v in polygon.exteriorRing().vertices()]))
            index += 1
            for h in range(polygon.numInteriorRings()):
                out.append((str(index), [text(v) for v in polygon.interiorRing(h).vertices()]))
                index += 1
    return out


def value_text(value) -> str:
    if value is None or (hasattr(value, "isNull") and value.isNull()):
        return ""
    if isinstance(value, QDate):
        return value.toString("yyyy-MM-dd")
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def main() -> int:
    os.chdir(tempfile.gettempdir())  # QGIS deja ficheros de trabajo (symbology-style.db) en el directorio actual
    qgs = QgsApplication([], False)
    qgs.initQgis()
    if OUT.exists():
        shutil.rmtree(OUT)
    capas, geometria, atributos = [], [], []
    for scenario, (build, encoding) in SCENARIOS.items():
        folder = OUT / scenario
        folder.mkdir(parents=True)
        for layer in build():
            path = write(layer, folder, encoding)
            written = QgsVectorLayer(str(path), layer.name(), "ogr")
            crs = written.crs()
            cpg = (folder / f"{layer.name()}.cpg").read_text(encoding="ascii").strip() if (folder / f"{layer.name()}.cpg").exists() else ""
            capas.append([scenario, layer.name(), Qgis.WkbType(written.wkbType()).name,
                          written.featureCount(), crs.authid(), cpg])
            for f in written.getFeatures():
                number = f.id() + 1  # el número de registro del .shp, desde 1
                for part, verts in vertices(f.geometry()):
                    geometria.append([scenario, layer.name(), number, part, ";".join(verts)])
                for column, value in zip(written.fields().names(), f.attributes()):
                    atributos.append([scenario, layer.name(), number, column, value_text(value)])
    versions = [f"QGIS {Qgis.version()}", f"GDAL {gdal.VersionInfo('RELEASE_NAME')}"]
    banner = [f"# Escrito por {', '.join(versions)} con tools/formatos/qgis_shapefile_ejemplos.py; no editar a mano."]

    def write_csv(name: str, header: list[str], rows: list[list]) -> None:
        buffer = io.StringIO()
        out = csv.writer(buffer, lineterminator="\n")
        out.writerow(header)
        out.writerows(rows)
        (OUT / name).write_text("\n".join(banner) + "\n" + buffer.getvalue(), encoding="utf-8", newline="\n")

    write_csv("esperado-qgis-capas.csv", ["escenario", "capa", "tipo_wkb", "objetos", "sistema_qgis", "cpg"], capas)
    write_csv("esperado-qgis-geometria.csv", ["escenario", "capa", "registro", "parte", "vertices"], geometria)
    write_csv("esperado-qgis-atributos.csv", ["escenario", "capa", "registro", "columna", "valor"], atributos)
    (OUT / "LEEME.txt").write_text(
        "Shapefiles escritos por QGIS (exportar a «ESRI Shapefile») tal como salen, sin tocarlos.\n"
        f"Versiones: {', '.join(versions)}.\n"
        "Se generan con: python-qgis.bat tools/formatos/qgis_shapefile_ejemplos.py (necesita QGIS instalado).\n"
        "Los esperado-qgis-*.csv son lo que QGIS lee al abrir de nuevo esos ficheros.\n",
        encoding="utf-8", newline="\n")
    print("OK", len(capas), "capas")
    qgs.exitQgis()
    return 0


if __name__ == "__main__":
    sys.exit(main())
