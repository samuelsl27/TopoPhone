# Tareas — Spec 012

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:formats` (módulo nuevo): `ExportColumn`, `ExportFormat`, `CsvOptions`, `CsvExport.formatsFor` y `CsvExport.write` (columnas, cabecera, números, celdas vacías, comillas, CRLF). (RF-5 a RF-16)
      Hecho cuando: `CsvExportTest` en verde y el módulo pasa las reglas de módulos y Kover.
- [x] T2. `core:formats`: `ExportFileName`. (RF-19)
      Hecho cuando: `ExportFileNameTest` en verde con caracteres no válidos y nombres largos.
- [x] T3. Ficheros dorados en `fixtures/formatos/csv/` y `tools/formatos/test_csv_exportado.py`. (RF-11, RF-12, RF-15, RF-16)
      Hecho cuando: `CsvExportGoldenTest` y `uv run --project tools pytest tools` en verde.
- [x] T4. `data`: `PointRepository.pointsForExport` y `ExportPreferences` (DataStore "export"). (RF-1, RF-3, RF-21)
      Hecho cuando: `PointRepositoryExportTest` y `ExportPreferencesTest` en verde.
- [x] T5. `app`: `ExportSelection` y `ExportViewModel` (puntos, ajustes recordados, validez, nombre, escritura en un `OutputStream`, errores). (RF-1 a RF-4, RF-13, RF-14, RF-16, RF-19 a RF-21)
      Hecho cuando: `ExportViewModelTest` en verde.
- [x] T6. `app`: pantalla "Exportar CSV" y su ruta; accesos desde el menú del proyecto, el de la lista de objetos y la selección. (RF-1, RF-2, RF-5, RF-8, RF-10, RF-13, RF-14)
      Hecho cuando: `ExportScreenTest` y `ExportEntryTest` en verde.
- [x] T7. `app`: "Guardar" con el selector de ficheros y "Compartir" con FileProvider desde `cache/exports/`. (RF-17, RF-18, RF-20)
      Hecho cuando: los tests de guardar y compartir (intents y escritura en el `Uri`) en verde.
- [x] T8. Rendimiento: 10 000 puntos escritos en menos de 3 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.
- [x] T9. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador y ficheros abiertos en QGIS. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
