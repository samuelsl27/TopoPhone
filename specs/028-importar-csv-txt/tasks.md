# Tareas — Spec 028

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/formatos`: `generar_importacion.py` con los ficheros de ejemplo de `fixtures/formatos/csv-importacion/` (UTF-8 con y sin BOM, Windows-1252, los cuatro separadores, coma y punto decimal, comillas, con y sin cabecera, un fallo de cada tipo) y lo esperado de cada uno, con las coordenadas proyectadas y geográficas de ETRS89, REGCAN95 y ED50 calculadas con pyproj. (RF-4 a RF-7, RF-9, RF-12, RF-13, RF-14, RF-27)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `PointOrigin.IMPORTED`, `Point.importId` y `hasMeasurement`; los términos nuevos en `docs/glosario.md`. (RF-31, RF-33)
      Hecho cuando: los tests de `core:model` en verde, con el importado sin medida y `Point` sin cambios para quien no pasa el identificador.
- [x] T3. `core:model`: los tipos de la importación (`ImportEncoding`, `ColumnRole`, `ColumnMapping`, `ImportCoordinates`, `DuplicatePolicy`, `ImportOptions`, `ImportRowError`, `ImportErrorReason`, `ImportCounts`, `ImportPreviewRow`). (RF-4 a RF-24)
      Hecho cuando: los tests de `core:model` en verde, con los valores por defecto de la spec (saltar repetidos, 0 como altura).
- [x] T4. `core:formats`: `TextDecoding`, la codificación con su detección. (RF-2, RF-4)
      Hecho cuando: `TextDecodingTest` en verde: UTF-8 con y sin BOM, Windows-1252 con acentos, binario, vacío y la muestra cortada en mitad de un carácter.
- [x] T5. `core:formats`: `DelimitedReader`, las filas por flujo con su línea, las comillas y el separador detectado. (RF-2, RF-3, RF-5, RF-6)
      Hecho cuando: `DelimitedReaderTest` en verde con los ficheros de `fixtures/`: los cuatro separadores, comillas con saltos, CRLF, LF y CR, líneas vacías, última línea sin salto, y los límites de 20 MB y 50.000 filas.
- [x] T6. `core:formats`: `DecimalDetector`, `ImportNumber` y `HeaderDetector`. (RF-7, RF-8, RF-9, RF-23)
      Hecho cuando: `DecimalDetectorTest`, `ImportNumberTest` y `HeaderDetectorTest` en verde: coma y punto, miles, notación científica, letras, la coma con la coma y la cabecera con y sin números.
- [x] T7. `core:formats`: `ColumnSuggester`, la asignación propuesta. (RF-10, RF-11)
      Hecho cuando: `ColumnSuggesterTest` en verde: nombres en es y en, las columnas del CSV de S012, PENZD sin cabecera, papeles repetidos y asignación sin nombre.
- [x] T8. `core:survey`: `PointImporter` para una fila: unidades, SC del proyecto o geográficas, alturas y errores de la fila. (RF-12 a RF-16, RF-20, RF-23, RF-27)
      Hecho cuando: `PointImporterTest` en verde frente a `fixtures/formatos/csv-importacion/` y pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm) en ETRS89, REGCAN95 y ED50, con altura vacía y 0, fuera del ámbito y del geoide, nombre vacío y fila corta.
- [x] T9. `core:survey`: los nombres repetidos y el análisis de un fichero entero con sus recuentos, las 20 filas de la vista previa y los primeros errores. (RF-17, RF-18, RF-19, RF-21)
      Hecho cuando: los tests en verde con `SKIP`, `RENAME` y `CANCEL`, repetidos del proyecto y del propio fichero, y sin retener los puntos.
- [x] T10. `core:survey`: `ImportReportText`, el informe de errores como texto. (RF-24)
      Hecho cuando: `ImportReportTextTest` en verde, con la línea y el motivo de cada error y el informe vacío.
- [x] T11. `data`: la columna `import_id`, su índice, `AutoMigration(11, 12)` y la lectura y escritura del origen importado. (RF-27, RF-31, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 11 → 12 en verde: los puntos y replanteos conservan sus datos y `import_id` queda a NULL.
- [x] T12. `data`: `PointRepository.createAll` y `trashImport`. (RF-19, RF-25, RF-26, RF-29)
      Hecho cuando: `PointRepositoryImportTest` en verde: todos o ninguno ante un nombre repetido, lo escrito sin redondear, y `trashImport` solo manda los activos de esa importación.
- [x] T13. `app`: `PointImportViewModel`, parte 1: leer el fichero, detectar y mostrar la vista previa y los errores del fichero. (RF-1 a RF-9, RF-21)
      Hecho cuando: `PointImportViewModelTest` en verde con un `ContentResolver` falso: fichero vacío, binario o demasiado grande, detección de cada opción y vista previa.
- [x] T14. `app`: `PointImportViewModel`, parte 2: cambiar las opciones y la asignación, y recalcular. (RF-10 a RF-20, RF-22)
      Hecho cuando: los tests en verde: cada opción actualiza los recuentos, un análisis nuevo cancela el anterior y `CANCEL` ante un repetido o la coma con la coma desactivan "Importar".
- [x] T15. `app`: `PointImportViewModel`, parte 3: importar, el resultado, deshacer, el informe y el log. (RF-24 a RF-30, RF-34)
      Hecho cuando: los tests en verde: importar con repetidos de cada tipo, fallo de escritura, salir sin importar, deshacer con algún punto ya borrado y el log sin nombres ni coordenadas.
- [x] T16. `app`: `PointImportScreen`, el fichero y la lectura (codificación, separador, signo decimal y cabecera), con sus textos en es y en. (RF-4 a RF-9)
      Hecho cuando: `PointImportScreenTest` en verde con cada selector y los avisos de la coma con la coma.
- [x] T17. `app`: `PointImportScreen`, las columnas, el SC, la unidad, la altura y los nombres repetidos. (RF-10 a RF-20)
      Hecho cuando: los tests en verde: un selector de papel por columna, PENZD y PNEZD, geográficas con la altura elipsoidal salvo en ED50, y las casillas.
- [ ] T18. `app`: la vista previa, los recuentos con icono y texto y el informe de errores con "Guardar" y "Compartir". (RF-21, RF-22, RF-24)
      Hecho cuando: los tests en verde, con las etiquetas de TalkBack, el fichero de informe y el selector de ficheros cancelado.
- [ ] T19. `app`: el resultado de la importación con "Ver informe" y "Deshacer importación" con su confirmación. (RF-28, RF-29)
      Hecho cuando: los tests en verde con los cuatro recuentos y deshacer.
- [ ] T20. `app`: "Importar CSV/TXT" en el menú del proyecto y en el de la lista de objetos, la navegación y el selector de ficheros. (RF-1, RF-2, RF-3)
      Hecho cuando: `PointImportEntriesTest` en verde, con el aviso de cada fichero que no se puede abrir.
- [ ] T21. `app` y `core:formats`: el origen "Importado" en la lista, la ficha y el mapa, e `Imported` en el CSV de puntos. (RF-31, RF-32)
      Hecho cuando: `ImportedPointsTest` y `CsvExportTest` en verde, con el origen en es y en.
- [ ] T22. Los puntos importados como manuales: editar, duplicar, borrar y restaurar, replantear, copia de seguridad y paquete. (RF-33)
      Hecho cuando: los tests en verde con un punto importado en cada operación y el paquete y la copia que lo conservan con su origen.
- [ ] T23. Rendimiento: `PointImportPerformanceTest`. (RNF)
      Hecho cuando: 50.000 filas leídas y validadas en menos de 3 s, un cambio de opción en menos de 1 s y 10.000 puntos importados en menos de 5 s.
- [ ] T24. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2b) quedan para el usuario.
