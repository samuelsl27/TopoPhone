# Tareas — Spec 031

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`). Nombres de clase y de test de `data` y `app` de 60 caracteres o menos. La importación (T1 a T13) y la exportación (T14 a T24) son independientes: la exportación solo usa de la importación el lector de la ida y vuelta (T18).

## Importar
- [x] T1. `tools/formatos`: `generar_kml.py` con KML y KMZ de ejemplo (carpetas anidadas, marcas sin carpeta, `MultiGeometry`, polígono con hueco, `LinearRing`, `ExtendedData` y `SimpleData`, descripción con HTML, colores en la marca, por `styleUrl` y por `StyleMap`, altitudes en los tres modos, `NetworkLink`, `GroundOverlay`, `Model` y `gx:Track`, KMZ sin `doc.kml`, con varios KML, sin KML y dañado, un KMZ con vínculos internos con sus ciclos, niveles y vínculos que no valen, el KMZ de QGIS con LIBKML, un KML con BOM y otro con espacio de nombres 2.0), `qgis_kml_ejemplos.py` con los hechos con el Python de QGIS y lo esperado de cada uno con pyproj en ETRS89 UTM 30 y REGCAN95 UTM 28; y su test. (RF-2 a RF-14, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/kml/` y su test de `tools` está en verde, con las coordenadas esperadas de cada marca calculadas con pyproj.
- [x] T2. `core:model` y `data`: `CadOrigin.KML`, los motivos de ignorado nuevos, `GpkgImportSummary.ignoredElements`, `GpkgFeature.color` (el convertidor y el importador lo pasan al `CadEntity`) y `FeatureSession.hasElevations`. (RF-9, RF-12, RF-13, RF-16)
      Hecho cuando: los tests de S048 y S030 siguen en verde sin cambios y un test nuevo comprueba que un objeto con color lo guarda en su elemento y que sin color toma el de su capa.
- [x] T3. `core:formats`: `KmlFileKind` (KML y KMZ por contenido, KML principal de un KMZ) y `KmlColors` (`aabbggrr` a ARGB y vuelta). (RF-2, RF-3, RF-12, RF-23)
      Hecho cuando: `KmlFileKindTest` y `KmlColorsTest` en verde con los ficheros de T1.
- [x] T4. `core:formats`: `KmlCoordinates` (tuplas, separadores, Z solo si todos los vértices la traen) y `KmlHtml`. (RF-7, RF-11, RF-13, RF-14)
      Hecho cuando: `KmlCoordinatesTest` y `KmlHtmlTest` en verde.
- [x] T5. `core:formats`: `KmlStyleTable` (estilos en línea, `styleUrl`, `StyleMap`, `IconStyle` frente a `LineStyle`, resolución al terminar la marca). (RF-12)
      Hecho cuando: `KmlStyleTableTest` en verde con los casos de T1.
- [x] T6. `core:formats`: `KmlParser` (carpetas y rutas, marcas sin carpeta, geometrías, datos extendidos, modo de altitud, elementos ignorados y geometrías vacías) por streaming sobre `XmlStreamReader`. (RF-2b, RF-5, RF-8, RF-9, RF-10, RF-11, RF-13, RF-14)
      Hecho cuando: `KmlParserTest` en verde contra lo esperado de `tools`.
- [x] T7. `core:formats`: `KmlGeometryEncoder` (de las partes de una marca al blob de geometría de GeoPackage con envolvente). (RF-7, RF-8)
      Hecho cuando: `KmlGeometryEncoderTest` en verde: el blob se lee igual con `GpkgGeometryReader`, con y sin Z.
- [x] T8. `data`: `KmlReader` (KML y KMZ desde la fuente elegida, vínculos internos de un KMZ, límites de 100 MB y 500 MB por bytes leídos, zip-slip, espacio, limpieza del temporal) y `KmlStore` (el almacén binario de una sola pasada). (RF-1 a RF-4, RF-2b, RF-17)
      Hecho cuando: `KmlReaderTest` en verde, incluidos KMZ cortado, sin `.kml`, con varios y cuyo tamaño declarado miente.
- [x] T9. `data`: `KmlSession : FeatureSession` (capas con ruta y nombres únicos, sistema geográfico del datum con aviso, objetos con color y atributos, cabeceras, geometrías por clave, altitudes solo de las marcas absolutas, elementos ignorados). (RF-5 a RF-9, RF-11 a RF-15)
      Hecho cuando: `KmlSessionTest` en verde.
- [x] T10. `data`: la importación con `KmlSession` (resumen, zona, polígonos como líneas, «Sí» y «No» a las altitudes, color por objeto, nombre y atributos, cancelar sin dejar nada, fallo de escritura) y la copia de seguridad y el paquete con un dibujo KML. (RF-10 a RF-19)
      Hecho cuando: `KmlImporterTest` y `KmlBackupTest` en verde.
- [x] T11. `app`: `GpkgImportViewModel` con `ImportFormat.KML` (`startKml`, problemas de fichero, sin selector de sistema ni de codificación, la pregunta de las altitudes con «No» por defecto y solo si hay altitudes absolutas, aviso de datum, elementos ignorados). (RF-1, RF-3, RF-4, RF-13, RF-17, RF-18)
      Hecho cuando: `KmlImportViewModelTest` en verde.
- [x] T12. `app`: la pantalla de importar KML/KMZ (capas con su ruta, tipo, objetos y extensión, la pregunta, los avisos y el resumen), «Importar KML/KMZ» en los dos menús con su navegación, «Origen: KML» y atributos en la ficha, y el replanteo de un objeto importado, con los textos en es y en. (RF-1, RF-6, RF-10, RF-11, RF-16, RF-18)
      Hecho cuando: `KmlImportScreenTest`, `KmlImportEntriesTest`, `DrawingsSheetKmlTest` y `KmlStakeoutTest` en verde (ajustar los tests de menús que ya no caben, como en S055).
- [x] T13. Rendimiento: un KML sintético de 100 MB se lee y se recorta en menos de 30 s y cambiar el radio recuenta en menos de 2 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.

## Exportar
- [x] T14. `tools/formatos`: `generar_kml_exportado.py` con el KML de referencia de un proyecto de ejemplo (puntos con y sin altura y con código, un punto sin posición, líneas y polígonos, un proyecto sin códigos) y las coordenadas con pyproj en ETRS89 UTM 30 y REGCAN95 UTM 28; y su test. (RF-21 a RF-27, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/kml-exportado/` y su test de `tools` está en verde.
- [x] T15. `core:formats`: `KmlNumber` y `KmlWriter` (XML con entidades escapadas, `CDATA`, estilos por código, datos extendidos vacíos). (RF-21 a RF-23, RF-29, RNF-T9)
      Hecho cuando: `KmlNumberTest` y `KmlWriterTest` en verde y el KML coincide byte a byte con la referencia de `tools`.
- [x] T16. `core:formats`: `KmlExportBuilder` para los puntos (posición del datum de los cálculos, columnas de «Todas las columnas», código y atributos, altura y modo, sin posición, resumen) y la selección. (RF-20, RF-21, RF-24 a RF-27, RF-32)
      Hecho cuando: `KmlExportBuilderTest` en verde para los puntos, con y sin «Incluir altura».
- [x] T17. `core:formats`: `KmlExportBuilder` para las líneas y los polígonos (contorno antihorario, primer vértice repetido, datos extendidos), las carpetas por código y los colores. (RF-22, RF-23)
      Hecho cuando: `KmlExportBuilderTest` en verde para líneas, polígonos y carpetas, y para un proyecto sin códigos.
- [x] T18. `core:formats`: la ida y vuelta (exportar e importar con la `KmlSession`) a menos de 0,1 mm y el rendimiento de exportar 10.000 puntos en menos de 5 s. (constitución nº 2, RNF)
      Hecho cuando: `KmlRoundTripTest` y el test de rendimiento en verde.
- [x] T19. `data`: `KmlExportPreferences` en DataStore (formato, «Incluir altura» e «Incluir fotos») y su inyección. (RF-32)
      Hecho cuando: `KmlExportPreferencesTest` en verde (por defecto, guardar y valor no válido).
- [x] T20. `data` y `core:formats`: `PhotoRepository.photosForExport` y los nombres de las fotos del KMZ (`PhotoArchiveNames`: si S056 ya los tiene, se reutilizan; si no, se escriben aquí en `core:formats/photo/` con sus tests). (RF-28)
      Hecho cuando: los tests de la consulta y de los nombres en verde.
- [x] T21. `core:formats`: `KmzWriter` (`doc.kml` primero, fotos de `files/` byte a byte, foto omitida, `<img>` en la descripción) y el rendimiento de 100 fotos de 1 MB. (RF-28 a RF-30, RNF)
      Hecho cuando: `KmzWriterTest` en verde y el test de rendimiento en menos de 10 s.
- [x] T22. `app`: `KmlExportViewModel` (puntos o selección con sus líneas, formato, altura, fotos, aviso de 1 GB y espacio, nombre del fichero, escribir entero o nada, resumen y log sin datos). (RF-20, RF-26 a RF-32)
      Hecho cuando: `KmlExportViewModelTest` en verde, incluidos sin puntos y fallo de escritura sin dejar fichero.
- [x] T23. `app`: `KmlExportScreen` (formato, «Incluir altura», «Incluir fotos» solo con KMZ, aviso, guardar, compartir, mensajes y resumen) con los textos en es y en, y «Exportar KML/KMZ» en el menú del proyecto y en el de su lista de objetos y «KML» en la barra de una selección, con su navegación. (RF-20, RF-26 a RF-32)
      Hecho cuando: `KmlExportScreenTest` y `KmlExportEntriesTest` en verde con los tres puntos de entrada.

## Cierre
- [x] T24. Validación: recorrer la spec RF por RF, leer con QGIS (Python de QGIS) el KML y el KMZ exportados y comparar marcas, coordenadas, colores y fotos, importar con la app un KML hecho con QGIS y comparar a menos de 1 mm, `rf_report`, `pytest tools`, `forbidden_files`, la demo en el emulador según los criterios de finalización, y «Cambios al implementar» en `plan.md`, hoja de ruta a `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
