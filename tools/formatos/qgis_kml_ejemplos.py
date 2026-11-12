"""KML y KMZ de ejemplo escritos por QGIS (GDAL) y lo que QGIS lee de ellos (S031, criterio de finalización).

Este script NO se ejecuta con `uv`: necesita PyQGIS, que viene con QGIS. Se ejecuta con el Python de QGIS, por ejemplo:

    "C:\\Program Files\\QGIS 3.44.6\\bin\\python-qgis.bat" tools/formatos/qgis_kml_ejemplos.py

Escribe en fixtures/formatos/kml/qgis/ los ficheros con lo que escribe QGIS al exportar a KML, tal como salen, sin tocarlos:

- qgis-kml.kml: el controlador «KML» de OGR (el de «Guardar objetos como… → Keyhole Markup Language»).
- qgis-libkml.kml y qgis-libkml.kmz: el controlador «LIBKML», el KML de Google Earth, y su capa de puntos en un KMZ.
- qgis-libkml-multicapa.kmz: el KMZ de las tres capas de LIBKML. Su `doc.kml` solo trae vínculos de red a `layers/<capa>.kml`, dentro del
  propio KMZ (S031/RF-2b): QGIS los sigue y la app también.

Cada uno lleva tres capas (puntos con Z, líneas y polígonos con un hueco) con los campos Name, Description, altura, codigo y n, y cuatro ficheros
con lo que QGIS lee de ellos al abrirlos de nuevo:

- esperado-qgis-capas.csv: por capa, el tipo de geometría, los objetos y las columnas.
- esperado-qgis-geometria.csv: por parte de cada objeto, sus vértices ("lon lat alt", `_` si no hay Z), en WGS84.
- esperado-qgis-atributos.csv: por valor de cada objeto, su columna y su valor tal como lo ve QGIS.
- esperado-qgis-proyecto.csv: los vértices en ETRS89 UTM 30 (EPSG:25830) que calcula QGIS tomando la longitud y la latitud como ETRS89, que es
  lo que hace la app (criterio de finalización: lo que importa la app coincide con QGIS a menos de 1 mm).

Los ficheros guardados no se regeneran en los tests (QGIS no está en `tools`): se comprueban con otro lector y con pyproj. La versión de QGIS y
de GDAL que los escribió queda en fixtures/formatos/kml/qgis/LEEME.txt.
"""

from __future__ import annotations

import csv
import io
import math
import os
import shutil
import sys
import tempfile
from pathlib import Path

from osgeo import gdal

gdal.UseExceptions()
from qgis.core import (
    Qgis,
    QgsApplication,
    QgsCoordinateReferenceSystem,
    QgsCoordinateTransform,
    QgsCoordinateTransformContext,
    QgsFeature,
    QgsGeometry,
    QgsProject,
    QgsVectorFileWriter,
    QgsVectorLayer,
    QgsWkbTypes,
)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "formatos" / "kml" / "qgis"
LON0, LAT0 = -3.7038, 40.4168

# (fichero, controlador, capas que lleva). Un KMZ se escribe de una vez, no capa a capa: se convierte del KML de LIBKML.
FILES = (
    ("qgis-kml.kml", "KML", None),
    ("qgis-libkml.kml", "LIBKML", None),
    ("qgis-libkml.kmz", "LIBKML", ["puntos"]),
    ("qgis-libkml-multicapa.kmz", "LIBKML", None),
)


def at(dx: float, dy: float, z: float | None = None) -> str:
    lon, lat = round(LON0 + dx * 0.001, 9), round(LAT0 + dy * 0.001, 9)
    return f"{lon!r} {lat!r}" + ("" if z is None else f" {z!r}")


def ring(x: float, y: float, size: float) -> str:
    pts = [(x, y), (x + size, y), (x + size, y + size), (x, y + size), (x, y)]
    return "(" + ", ".join(at(*p) for p in pts) + ")"


def make_layers() -> list[QgsVectorLayer]:
    fields = "&field=Name:string&field=Description:string&field=altura:double&field=codigo:string&field=n:integer"
    layers = []
    for name, geometry, rows in (
        ("puntos", "PointZ", [
            (f"PointZ ({at(0, 0, 646.377)})", "Poste", "Un poste", 12.5, "POSTE", 1),
            (f"PointZ ({at(1, 0, 647.25)})", "Arqueta", "Tapa rota", 1.0, "ARQ", 2),
            (f"PointZ ({at(2, 0, 0.0)})", "Sin datos", "", None, "", 3),
        ]),
        ("lineas", "LineStringZ", [
            (f"LineStringZ ({at(0, 1, 10.5)}, {at(1, 1.5, 11.5)}, {at(2, 1, 12.5)})", "Eje", "Eje de prueba", 0.3, "EJE", 1),
            (f"LineStringZ ({at(0, 2, 20.0)}, {at(1, 2, 21.0)})", "Muro", "", 2.0, "MURO", 2),
        ]),
        ("poligonos", "PolygonZ", [
            (f"PolygonZ (({', '.join(at(*p, 100.0) for p in ((3, 0), (3.4, 0), (3.4, 0.4), (3, 0.4), (3, 0)))}), "
             f"({', '.join(at(*p, 100.0) for p in ((3.1, 0.1), (3.1, 0.2), (3.2, 0.2), (3.2, 0.1), (3.1, 0.1)))}))",
             "Parcela", "Con hueco", None, "PARC", 1),
            (f"PolygonZ (({', '.join(at(*p, 101.0) for p in ((5, 0), (5.3, 0), (5.3, 0.3), (5, 0.3), (5, 0)))}))", "Sin hueco", "", None, "PARC", 2),
        ]),
    ):
        layer = QgsVectorLayer(f"{geometry}?crs=EPSG:4326{fields}", name, "memory")
        provider = layer.dataProvider()
        features = []
        for wkt, label, description, altura, codigo, n in rows:
            f = QgsFeature(layer.fields())
            f.setGeometry(QgsGeometry.fromWkt(wkt))
            f.setAttributes([label, description, altura, codigo, n])
            features.append(f)
        provider.addFeatures(features)
        layers.append(layer)
    return layers


def write_file(layers: list[QgsVectorLayer], path: Path, driver: str, only: list[str] | None) -> None:
    if path.suffix == ".kmz":
        gdal.VectorTranslate(str(path), str(OUT / "qgis-libkml.kml"), format=driver, layers=only)
        return
    for index, layer in enumerate(layers):
        options = QgsVectorFileWriter.SaveVectorOptions()
        options.driverName = driver
        options.layerName = layer.name()
        options.fileEncoding = "UTF-8"
        options.actionOnExistingFile = (
            QgsVectorFileWriter.CreateOrOverwriteFile if index == 0 else QgsVectorFileWriter.CreateOrOverwriteLayer
        )
        error, message, *_ = QgsVectorFileWriter.writeAsVectorFormatV3(layer, str(path), QgsProject.instance().transformContext(), options)
        if error != QgsVectorFileWriter.NoError:
            raise RuntimeError(f"{path.name} / {layer.name()}: {message}")


def parts_of(geometry: QgsGeometry) -> list[list]:
    abstract = geometry.constGet()
    kind = QgsWkbTypes.flatType(abstract.wkbType())
    parts: list[list] = []

    def polygon(p) -> None:
        parts.append(list(p.exteriorRing().vertices()))
        for i in range(p.numInteriorRings()):
            parts.append(list(p.interiorRing(i).vertices()))

    if kind == QgsWkbTypes.Point:
        parts.append([abstract])
    elif kind == QgsWkbTypes.MultiPoint:
        parts.extend([abstract.geometryN(i)] for i in range(abstract.numGeometries()))
    elif kind == QgsWkbTypes.LineString:
        parts.append(list(abstract.vertices()))
    elif kind == QgsWkbTypes.MultiLineString:
        parts.extend(list(abstract.geometryN(i).vertices()) for i in range(abstract.numGeometries()))
    elif kind == QgsWkbTypes.Polygon:
        polygon(abstract)
    elif kind == QgsWkbTypes.MultiPolygon:
        for i in range(abstract.numGeometries()):
            polygon(abstract.geometryN(i))
    else:
        raise RuntimeError(f"Geometría no prevista: {QgsWkbTypes.displayString(abstract.wkbType())}")
    return parts


def z_text(v) -> str:
    return "_" if not v.is3D() or math.isnan(v.z()) else repr(v.z())


def vertex_text(v) -> str:
    return f"{v.x()!r} {v.y()!r} {z_text(v)}"


def main() -> None:
    os.chdir(tempfile.mkdtemp(prefix="s031-qgis-"))
    app = QgsApplication([], False)
    app.initQgis()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.iterdir():
        if old.is_file():
            old.unlink()

    layers = make_layers()
    transform = QgsCoordinateTransform(
        QgsCoordinateReferenceSystem("EPSG:4258"), QgsCoordinateReferenceSystem("EPSG:25830"), QgsCoordinateTransformContext()
    )
    capas, geometria, atributos, proyecto = [], [], [], []
    for file_name, driver, only in FILES:
        path = OUT / file_name
        write_file(layers, path, driver, only)
        for layer in layers:
            if only is not None and layer.name() not in only:
                continue
            read = QgsVectorLayer(f"{path}|layername={layer.name()}", layer.name(), "ogr")
            if not read.isValid():
                raise RuntimeError(f"QGIS no abre {file_name} / {layer.name()}")
            names = [f.name() for f in read.fields()]
            capas.append([file_name, layer.name(), QgsWkbTypes.displayString(read.wkbType()), read.featureCount(), ";".join(names)])
            for number, feature in enumerate(sorted(read.getFeatures(), key=lambda f: f.id()), 1):
                for name in names:
                    value = feature[name]
                    if value is not None and str(value) != "NULL" and str(value) != "":
                        atributos.append([file_name, layer.name(), number, name, value])
                for index, part in enumerate(parts_of(feature.geometry()), 1):
                    geometria.append([file_name, layer.name(), number, index, ";".join(vertex_text(v) for v in part)])
                    projected = []
                    for v in part:
                        p = transform.transform(v.x(), v.y())
                        projected.append(f"{p.x()!r} {p.y()!r} {z_text(v)}")
                    proyecto.append([file_name, layer.name(), number, index, ";".join(projected)])

    def save(name: str, header: list[str], rows: list[list]) -> None:
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)
        (OUT / name).write_bytes(buffer.getvalue().encode("utf-8"))

    save("esperado-qgis-capas.csv", ["archivo", "capa", "tipo", "objetos", "columnas"], capas)
    save("esperado-qgis-geometria.csv", ["archivo", "capa", "objeto", "parte", "vertices"], geometria)
    save("esperado-qgis-atributos.csv", ["archivo", "capa", "objeto", "columna", "valor"], atributos)
    save("esperado-qgis-proyecto.csv", ["archivo", "capa", "objeto", "parte", "vertices_utm30"], proyecto)
    (OUT / "LEEME.txt").write_text(
        f"Ficheros escritos por QGIS {Qgis.version()} (GDAL {gdal.__version__}) con tools/formatos/qgis_kml_ejemplos.py.\n"
        "Controladores: KML y LIBKML. Los esperado-qgis-*.csv son lo que QGIS lee de ellos.\n",
        encoding="utf-8",
        newline="\n",
    )
    app.exitQgis()
    shutil.rmtree(Path.cwd(), ignore_errors=True)
    print(f"{len(list(OUT.iterdir()))} ficheros en {OUT}")


if __name__ == "__main__":
    sys.exit(main())
