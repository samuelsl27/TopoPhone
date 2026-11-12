# Tareas — Spec 055

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`). Nombres de clase y de test de `data` y `app` de 60 caracteres o menos. Empieza cuando S030 esté implementada: la ida y vuelta usa sus lectores.

- [x] T1. `tools/formatos`: `generar_shp_exportado.py` con las referencias del contenido de cada fichero del ZIP (`.shp`, `.shx`, `.dbf` y `.cpg`) para un proyecto de ejemplo (puntos con y sin altura, con atributos de código, líneas y polígonos), el WKT de cada `.prj` con pyproj y el fichero de datos `ShpPrjDefinitionsData`; y su test. (RF-3, RF-5, RF-6, RF-12 a RF-15, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/shp-exportado/` y su test de `tools` está en verde, con cada `.prj` leído por pyproj como el sistema de su proyecto y las geográficas a menos de 1 mm.
- [x] T2. `core:formats`: `ShpColumnNames` (la tabla fija en español y en inglés y los nombres de los atributos de código). (RF-8, RF-9)
      Hecho cuando: `ShpColumnNamesTest` en verde: hasta 10 caracteres, mayúsculas ASCII, sin repetir, todas las `ExportColumn` en los dos idiomas y atributos de código repetidos, largos o con acentos renombrados.
- [ ] T3. `core:formats`: `DbfWriter` y `DbfColumn` (texto, entero y decimal, vacíos, recorte a 254 bytes sin partir un carácter, `Locale.ROOT`, cabecera y `.cpg`). (RF-6, RF-7, RF-10, RF-11)
      Hecho cuando: `DbfWriterTest` en verde y el `.dbf` coincide byte a byte con la referencia de `tools`.
- [ ] T4. `core:formats`: `ShpWriter` (`.shp` y `.shx` de `PointZ`, `PolyLineZ` y `PolygonZ`, envolventes, contorno horario, primer vértice repetido y registro nulo). (RF-3, RF-5, RF-12 a RF-14b)
      Hecho cuando: `ShpWriterTest` en verde y los ficheros coinciden byte a byte con las referencias de `tools`.
- [ ] T5. `core:formats`: `ShpPrjDefinitions` con los datos generados (sistema del proyecto, geográficas del datum y sin definición). (RF-15, RF-16)
      Hecho cuando: `ShpPrjDefinitionsTest` en verde contra los WKT de `tools` para cada `ProjectCrs`, y el caso sin definición da `null`.
- [ ] T6. `core:formats`: `ShpExportBuilder` para los puntos: columnas fijas y de atributos de código, Z del proyecto y elipsoidal, Z = 0 con recuento, punto sin posición y resumen. (RF-1, RF-2, RF-6, RF-8, RF-9, RF-12 a RF-14b, RF-22)
      Hecho cuando: `ShpExportBuilderTest` en verde para los puntos, en proyectadas y en geográficas.
- [ ] T7. `core:formats`: `ShpExportBuilder` para las líneas y los polígonos: selección con líneas completas, líneas con pocos vértices, columnas de nombre, código, vértices y atributos, y capas vacías. (RF-2, RF-3, RF-7, RF-22)
      Hecho cuando: `ShpExportBuilderTest` en verde para líneas y polígonos y para la selección.
- [ ] T8. `core:formats`: `ShapefileZipWriter` (nombres limpios, cinco ficheros por capa, ninguna capa vacía) y la ida y vuelta con los lectores de S030. (RF-3, RF-4, RF-5, RF-10 a RF-14)
      Hecho cuando: `ShapefileZipWriterTest` y `ShpRoundTripTest` en verde: lo escrito se relee con las mismas coordenadas (menos de 0,05 mm), atributos y sistema.
- [ ] T9. `core:formats`: exportar 10.000 puntos con sus columnas en menos de 5 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.
- [ ] T10. `data`: `ShpExportPreferences` en DataStore (el sistema elegido, para todos los proyectos) y su inyección. (RF-23)
      Hecho cuando: `ShpExportPreferencesTest` en verde (por defecto, guardar y valor no válido).
- [ ] T11. `app`: `ShpExportViewModel` (puntos o selección con sus líneas, sistema, idioma de los nombres, escribir entero o nada, nombre del ZIP, resumen y log sin datos). (RF-1, RF-2, RF-12, RF-13, RF-17 a RF-23)
      Hecho cuando: `ShpExportViewModelTest` en verde, incluidos sin puntos y fallo de escritura sin dejar fichero.
- [ ] T12. `app`: `ShpExportScreen` (sistema, guardar, compartir, mensajes y resumen) con los textos en es y en. (RF-12, RF-13, RF-17, RF-18, RF-22)
      Hecho cuando: `ShpExportScreenTest` en verde y la pantalla solo ofrece elegir sistema en un proyecto proyectado.
- [ ] T13. `app`: «Exportar Shapefile» en el menú del proyecto y en el de su lista de objetos, «Exportar» con una selección, y la navegación. (RF-1, RF-2)
      Hecho cuando: `ShpExportEntriesTest` en verde con los tres puntos de entrada.
- [ ] T14. Validación: recorrer la spec RF por RF, abrir el ZIP exportado en QGIS por MCP (proyectadas y geográficas, español e inglés) y comparar coordenadas, atributos y sistema, `rf_report`, `pytest tools`, `forbidden_files`, la demo en el emulador según los criterios de finalización, y «Cambios al implementar» en `plan.md`, hoja de ruta a `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
