# Tareas — Spec 030

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`). Nombres de clase y de test de `data` y `app` de 60 caracteres o menos.

- [x] T1. `tools/formatos`: `generar_shapefile.py` con los Shapefiles de ejemplo (puntos, multipuntos, líneas, polígonos con hueco y multianillo, con Z y con M, en ETRS89 UTM 30, REGCAN95 UTM 28, ED50 UTM 30 y ETRS89 geográficas; con y sin `.cpg`; `.dbf` en Windows-1252; sin `.prj`, sin `.shx`, sin `.dbf`; `.dbf` corto; registros borrados; objeto nulo y `MultiPatch`) y lo esperado con pyproj; y su test. (RF-2, RF-8, RF-9, RF-15 a RF-21, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/shp/` y su test de `tools` está en verde.
- [x] T2. `tools/formatos`: los ZIP de ejemplo (varias capas, dos capas con el mismo nombre en carpetas distintas, huérfanos, sin `.shp`, dañado, tamaño declarado falso, ficheros sueltos) y un Shapefile en ETRS89 UTM 30 y otro en geográficas hechos con QGIS por MCP, con su procedencia anotada y sus valores esperados con pyproj. (RF-2 a RF-7, RF-15, constitución nº 2)
      Hecho cuando: los ficheros están en `fixtures/formatos/shp/`, el test de `tools` comprueba lo esperado y el `LEEME` dice cómo se generaron.
- [x] T3. `core:model`: `CadOrigin.SHAPEFILE`, los motivos `DELETED_RECORD` y `MULTIPATCH` y `GpkgLayerSummary.withoutAttributes`, con los `when` adaptados y el glosario ya al día. (RF-16, RF-20, RF-26)
      Hecho cuando: `core:model` y los módulos que lo usan compilan y sus tests siguen en verde, con un test del resumen que suma los objetos sin atributos.
- [x] T4. `core:formats`: `ShpFileKind` (cabecera de 100 bytes) y `ShpSelection` (ZIP, `.shp` por contenido, asociación por nombre, huérfanos, mezcla y límites de 100 MB y 500 MB). (RF-2 a RF-6)
      Hecho cuando: `ShpFileKindTest` y `ShpSelectionTest` en verde: ZIP, sueltos, `.shp` con otra extensión, mayúsculas, más de un ZIP, ZIP con sueltos, huérfanos, sin `.shp`, y los dos límites.
- [x] T5. `core:formats`: `ShpReader` (todos los tipos, Z, M descartada, nulo, `MultiPatch`, envolvente y un anillo por línea cerrada). (RF-15, RF-16, RF-17)
      Hecho cuando: `ShpReaderTest` en verde contra los ficheros de `tools`, con los vértices a menos de 1e-9 m de lo esperado.
- [x] T6. `core:formats`: `ShxIndex` (con `.shx`, sin él y con uno roto) y `ShpBlobEncoder` (blob con cabecera y envolvente). (RF-19, RF-13)
      Hecho cuando: `ShxIndexTest` y `ShpBlobEncoderTest` en verde: el blob se lee con `GpkgGeometryReader` y da las mismas partes y envolvente que `ShpReader`.
- [x] T7. `core:formats`: `DbfReader` (tipos de campo, borrados, registros que faltan) y `ShpEncoding` (`.cpg`, UTF-8 válido y Windows-1252). (RF-18, RF-19, RF-20, RF-21)
      Hecho cuando: `DbfReaderTest` y `ShpEncodingTest` en verde con y sin `.cpg`, con tildes y eñes, con un `.cpg` desconocido y con un `.dbf` corto.
- [x] T8. `core:formats`: `PrjReader` y `ShpCrsCatalog` (autoridad, estructura, WGS 84, no disponible y sin `.prj`) y el nombre en `GpkgCrs.Unavailable`. (RF-8, RF-9)
      Hecho cuando: `PrjReaderTest` y `ShpCrsCatalogTest` en verde con el WKT de ESRI y el de OGC de cada `ProjectCrs` contra pyproj, y con pies, otro huso y otra proyección como no disponibles.
- [x] T9. `data`: `FeatureSession` extraída de `GpkgSession`, `GpkgFeature.deleted`, y `GeoPackageImporter` y `GeoPackageScan` sobre la interfaz con el origen de la sesión. (RF-16, RF-26)
      Hecho cuando: los tests de S048 (`GeoPackageReaderTest`, `GeoPackageScanTest`, `GeoPackageImporterTest`, `GpkgBackupTest`) siguen en verde sin cambios y un test del importador cuenta un objeto `deleted` como ignorado.
- [ ] T10. `data`: `ShapefileReader` (ZIP o ficheros sueltos a una carpeta temporal de la caché, límites por bytes leídos, espacio libre, zip-slip, limpieza) y la inyección en `DataModule`. (RF-1 a RF-7, RF-29, RF-30)
      Hecho cuando: `ShapefileReaderTest` en verde con los ZIP y sueltos de `tools`, el tamaño declarado falso, un nombre de entrada con `..`, un ZIP cancelado a mitad, y sin dejar el temporal.
- [ ] T11. `data`: `ShapefileSession` (capas con su tipo, columnas y SC, objetos con acceso aleatorio, cabeceras y geometrías por registro, codificación por capa). (RF-7 a RF-9, RF-15 a RF-21)
      Hecho cuando: `ShapefileSessionTest` en verde: capas con el mismo nombre distinguidas por carpeta, `forEachFeature`, `forEachHead`, `forEachGeometry`, cambio de codificación y `.dbf` corto.
- [ ] T12. `data`: importar un Shapefile con `GeoPackageImporter` y recontar con `GeoPackageScan` (reproyección, zona, polígonos como líneas, cotas, nombre, atributos, borrados, `.dbf` corto, tandas, cancelar y fallar sin dejar nada, resumen). (RF-10 a RF-17, RF-20 a RF-22, RF-25, RF-26, RF-29, RF-30)
      Hecho cuando: `ShapefileImporterTest` y `ShapefileScanTest` en verde con los valores contra pyproj y el resumen por capa.
- [ ] T13. `data`: las copias de seguridad y los paquetes con un dibujo de Shapefile, y el log sin nombres ni coordenadas. (RF-27, RF-28)
      Hecho cuando: `ShpBackupTest` en verde (copia, restauración, paquete y borrado sin tocar los puntos del proyecto).
- [ ] T14. `app`: `GpkgImportViewModel` y `GpkgImportScreen` por `ImportFormat` (varias fuentes, problemas de fichero, lista de capas, aviso de huérfanos y textos de Shapefile en es y en). (RF-1 a RF-7)
      Hecho cuando: `ShpImportViewModelTest` y `ShpImportScreenTest` en verde para esos casos y los de GeoPackage sin cambios.
- [ ] T15. `app`: «Importar Shapefile» en los tres menús, el selector de varios ficheros, `SurveyPage.ImportShp` y su destino de navegación. (RF-1, RF-3, RF-4)
      Hecho cuando: `ShpImportEntriesTest` en verde con los tres menús y la navegación con varios `Uri`.
- [ ] T16. `app`: sistema de coordenadas de cada capa (el del `.prj`, elegido, sin `.prj`), extensión, distancia y aviso de 100 km, y el selector de codificación por capa. (RF-8 a RF-10, RF-18)
      Hecho cuando: los mismos tests en verde para esos casos, con el nombre del sistema no disponible a la vista.
- [ ] T17. `app`: zona de importación, cotas, campo de nombre, «Importar» con progreso y cancelar, y resumen por capa con guardar y compartir. (RF-11 a RF-14, RF-17, RF-22, RF-25, RF-29, RF-30)
      Hecho cuando: los mismos tests en verde y el resumen se guarda como texto con las líneas de borrados y objetos sin atributos.
- [ ] T18. `app`: «Origen: Shapefile» en «Dibujos», las etiquetas y los contornos en el mapa y la ficha con atributos al tocar, y borrar el dibujo. (RF-21, RF-23, RF-26, RF-27, RNF-T8)
      Hecho cuando: `DrawingsSheetShpTest` y `ShpFeatureSheetTest` en verde.
- [ ] T19. `app`: replanteo de los puntos y las líneas de un dibujo de Shapefile con S041, con su nombre. (RF-24)
      Hecho cuando: `ShpStakeoutTest` en verde.
- [ ] T20. Rendimiento: un ZIP de 100 MB se lee y recorta en menos de 20 s y cambiar el radio recuenta en menos de 2 s. (RNF)
      Hecho cuando: el test de rendimiento en verde, con la lectura por flujo (sin cargar el `.shp` entero).
- [ ] T21. Validación: recorrer la spec RF por RF, comparar con QGIS un Shapefile en ETRS89 UTM 30 y otro en geográficas (≤ 1 mm), `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización; «Cambios al implementar» en `plan.md`, hoja de ruta a `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
