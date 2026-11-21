# Tareas — Spec 057

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S057 Tn: …` en `main`, con la línea Co-Authored-By. Antes del `check`: `./gradlew ktlintCheck :app:checkHardcodedUiText :app:lintStandardDebug` (S034, lecciones del plan).

## Definiciones WKT y vectores
- [x] T1. `tools/geodesia/catalogo_wkt.py`: la definición WKT1 de GeoPackage y la de ESRI de cada SC del catálogo en `crs_wkt.tsv.gz`. (RF-26)
      Hecho cuando: `test_wkt.py` comprueba una definición por SC, `AUTHORITY` en las de EPSG, determinismo y que el recurso pesa menos de 1 MB.
- [x] T2. `CrsWkt` en `core:geodesy.catalog`: lector perezoso por EPSG. (RF-22, RF-23, RF-25, RF-26)
      Hecho cuando: `CrsWktTest` lee las de EPSG:25830, 2154 y 4326 y devuelve `null` si no hay definición.
- [x] T3. Vectores de pyproj de la reproyección: tres SC de otros países y dos cambios de datum (uno de parámetros y uno con rejilla). (RF-7, RF-8)
      Hecho cuando: `test_reproyeccion.py` los comprueba y el fichero se regenera igual.

## Núcleo
- [x] T4. `CadSourceCrs.Library` y su persistencia (`LIB:`), sin cambiar los valores de ahora. (RF-1, RF-2, RF-27)
      Hecho cuando: `CadSourceCrsPersistenceTest` guarda y lee los cuatro tipos en `CadRepository` y `SurfaceRepository`.
- [x] T5. `SourceGround` y `SourceCrsResolver`: del vértice del fichero al suelo, con A1. (RF-7, RF-11, RF-12)
      Hecho cuando: `SourceGroundTest` pasa con los vectores a ≤ 1 mm y la ida y vuelta a ≤ 0,1 mm, con rejilla ausente y con punto fuera de la rejilla.
- [x] T6. `ImportTransformation` y las rejillas que faltan (A2, A3). (RF-8, RF-9, RF-10)
      Hecho cuando: `ImportTransformationTest` cubre la más precisa que cubre la zona, el aviso por encima de 1 m o desconocida, y sin aviso con el mismo datum.
- [x] T7. `CadProjector` con un SC de origen de la biblioteca. (RF-13)
      Hecho cuando: `CadProjectorLibraryTest` pasa y `CadProjectorTest` de S040 sigue en verde.
- [ ] T8. `SurfaceProjector` y `AlignmentProjector` con un SC de origen de la biblioteca. (RF-14)
      Hecho cuando: `SurfaceProjectorLibraryTest` y `AlignmentProjectorLibraryTest` pasan.
- [ ] T9. `RasterElevation` y `RasterTiler` con un SC de origen de la biblioteca, y las alturas (A5). (RF-15, RF-19, RF-20)
      Hecho cuando: `RasterElevationLibraryTest` y `SourceHeightsTest` pasan.
- [ ] T10. `ImportAreaFilter` (recorte alrededor del centro) con un SC de origen de la biblioteca. (RF-16)
      Hecho cuando: `ImportAreaFilterLibraryTest` pasa con un Shapefile en otro país.

## Formatos
- [ ] T11. Reconocer el SC de un Shapefile, un GeoPackage y un GeoTIFF en la biblioteca (A4). (RF-4, RF-5, RF-6)
      Hecho cuando: `CrsRecognitionTest` cubre EPSG, nombre de EPSG, nombre de ESRI, ambiguo y desconocido.
- [ ] T12. Importadores de GeoPackage y Shapefile con el SC de cada capa. (RF-16)
      Hecho cuando: `GpkgLibraryImportTest` y `ShpLibraryImportTest` importan una capa en otro SC y reproyectan.
- [ ] T13. KML con un SC de la biblioteca: importar y exportar como el datum de las correcciones. (RF-17, RF-24)
      Hecho cuando: `KmlLibraryTest` pasa con un proyecto en Lambert-93 y otro con correcciones ITRF/WGS84.
- [ ] T14. Exportar DXF con un SC de la biblioteca. (RF-21)
      Hecho cuando: `DxfExportLibraryTest` pasa con coordenadas globales y locales y con un SC geográfico.
- [ ] T15. Exportar GeoPackage con la definición del SC (A6). (RF-22, RF-25)
      Hecho cuando: `GpkgExportLibraryTest` pasa y un SC sin definición exportable lo avisa en el resumen.
- [ ] T16. Exportar Shapefile con el `.prj` del SC. (RF-23, RF-25)
      Hecho cuando: `ShpExportLibraryTest` pasa y el `.prj` reaparece al volver a importarlo con el mismo SC.

## Interfaz
- [ ] T17. `SourceCrsPicker` común y la importación de DXF. (RF-1, RF-2, RF-3)
      Hecho cuando: `SourceCrsPickerTest` y `CadImportLibraryTest` pasan, con el SC del proyecto el primero y marcado.
- [ ] T18. Importación de superficies y ejes de LandXML. (RF-1, RF-3, RF-14)
      Hecho cuando: `SurfaceImportLibraryTest` y `AlignmentImportLibraryTest` pasan.
- [ ] T19. Importación de ráster con el SC declarado reconocido. (RF-1, RF-3, RF-4)
      Hecho cuando: `RasterImportLibraryTest` pasa con un GeoTIFF en otro SC.
- [ ] T20. Importación de Shapefile y GeoPackage: el SC de cada capa y elegir uno si no se reconoce. (RF-4, RF-6)
      Hecho cuando: `GpkgImportLibraryTest` y `ShpImportLibraryTest` pasan.
- [ ] T21. Vista previa con la transformación y su precisión, confirmación y descarga de la rejilla que falta. (RF-8, RF-9, RF-10)
      Hecho cuando: `ImportPreviewTest` pasa con aviso, con confirmación y con rejilla ausente.
- [ ] T22. Resúmenes con el SC de origen, la transformación y lo ignorado. (RF-11, RF-18)
      Hecho cuando: `ImportSummaryTest` pasa en las siete importaciones.
- [ ] T23. Los menús vuelven a ofrecer los formatos con un SC de la biblioteca y desaparece el aviso de S034. (RF-28)
      Hecho cuando: `FormatsEnabledTest` pasa y `LibraryProjectFlowTest` de S034 se actualiza.

## Cierre
- [ ] T24. No regresión de España y rendimiento. (RF-27)
      Hecho cuando: `SpainFormatsUnchangedTest` y `ReprojectionPerformanceTest` pasan (100.000 puntos: menos de 2 s con parámetros y de 4 s con rejilla).
- [ ] T25. Validación final: recorrer la spec RF por RF, informe de trazabilidad, `plan.md` con «Cambios al implementar» y «Validación», hoja de ruta y la demo del emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, `check`, `pytest`, `forbidden_files` y `assembleDebug` pasan y la demo funciona.
