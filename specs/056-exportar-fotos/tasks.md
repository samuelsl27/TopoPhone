# Tareas — Spec 056

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`). Nombres de clase y de test de `data` y `app` de 60 caracteres o menos.

- [x] T1. `tools/formatos`: `generar_fotos_zip.py` con el índice y el ZIP de referencia de un proyecto de ejemplo (diez fotos en un punto, dos puntos con el mismo nombre, símbolos y acentos, un nombre con coma y comillas, una foto sin metadatos), y su test. (RF-4 a RF-7)
      Hecho cuando: el script genera `fixtures/formatos/fotos-zip/` y su test de `tools` está en verde, leyendo el ZIP con `zipfile` y el índice con `csv`.
- [x] T2. `core:formats`: `PhotoArchiveNames` (saneado, «punto» si queda vacío, numeración por orden de alta, repetidos sin distinguir mayúsculas). (RF-5)
      Hecho cuando: `PhotoArchiveNamesTest` en verde con los casos de las referencias.
- [x] T3. `core:formats`: `PhotoIndexCsv` (cabecera, una fila por foto, comillas, fechas ISO 8601 UTC, UTF-8 sin BOM y fin de línea de S012). (RF-6, RF-7)
      Hecho cuando: `PhotoIndexCsvTest` en verde y el índice coincide byte a byte con la referencia de `tools`.
- [x] T4. `core:formats`: `PhotoZipWriter` (fotos byte a byte, índice al final, foto omitida y error a mitad de copia) y el rendimiento de 100 fotos de 1 MB. (RF-4, RF-6, RF-9, RF-14, RNF)
      Hecho cuando: `PhotoZipWriterTest` en verde y el test de rendimiento en menos de 10 s.
- [x] T5. `data`: `PhotoRepository.photosForExport` con la consulta troceada. (RF-1, RF-2)
      Hecho cuando: `PhotoExportRepoTest` en verde, con orden de alta y 2.000 puntos.
- [ ] T6. `data`: `PhotoExporter` (ZIP temporal entero, tamaño previsto, espacio libre, 1 GB, borrado del temporal si falla, fotos que faltan). (RF-4, RF-9 a RF-11, RF-14)
      Hecho cuando: `PhotoExporterTest` en verde, incluido el fallo sin dejar fichero.
- [ ] T7. `app`: `PhotoExportViewModel` (puntos o selección, recuento y tamaño, sin fotos, aviso de 1 GB, nombre del ZIP, escribir entero o nada, resumen y log sin nombres ni rutas). (RF-1 a RF-3, RF-8, RF-10 a RF-13)
      Hecho cuando: `PhotoExportViewModelTest` en verde.
- [ ] T8. `app`: `PhotoExportScreen` (recuento, aviso, guardar, compartir, mensajes y resumen) con los textos en es y en. (RF-3, RF-8, RF-10 a RF-13)
      Hecho cuando: `PhotoExportScreenTest` en verde.
- [ ] T9. `app`: «Exportar fotos» en el menú del proyecto y en el de su lista de objetos, «Fotos» en la barra de una selección, y la navegación. (RF-1, RF-2)
      Hecho cuando: `PhotoExportEntriesTest` en verde con los tres puntos de entrada (ajustar los tests de menús que ya no caben, como en S055).
- [ ] T10. Validación: recorrer la spec RF por RF, descomprimir el ZIP con herramientas externas y comparar byte a byte, abrir `fotos.csv` en QGIS, `rf_report`, `pytest tools`, `forbidden_files`, la demo en el emulador según los criterios de finalización, y «Cambios al implementar» en `plan.md`, hoja de ruta a `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
