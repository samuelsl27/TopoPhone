# Tareas — Spec 042

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/superficies`: `generar_superficies.py` con los casos de `fixtures/superficies/` (LandXML con dos superficies, en pies, con caras invisibles, con identificadores no consecutivos, con una malla `GRID`, sin superficies y mal formado) y lo esperado con pyproj y con un cálculo baricéntrico aparte (dentro, arista, vértice, fuera y hueco). (Constitución nº 2, RF-6, RF-7, RF-12, RF-22)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `Surface`, `SurfaceHeight`, `ElevationSource` y las ampliaciones de `StakeoutRef` (origen, superficie, fecha, cota de la superficie y desplazamiento). (RF-14, RF-28, RF-29)
      Hecho cuando: los tests de `core:model` en verde, con los valores por defecto (los puntos de S018 a S041 no cambian).
- [x] T3. `core:model`: `SurfaceTileCodec`, la tesela binaria autosuficiente de hasta 256 kB. (RF-14, plan A3)
      Hecho cuando: `SurfaceTileCodecTest` en verde: ida y vuelta sin redondear, una tesela vacía y una de 256 kB.
- [x] T4. `core:formats`: `XmlStreamReader`, el tokenizador XML por streaming. (RF-2, RF-3)
      Hecho cuando: `XmlStreamReaderTest` en verde: entidades, `CDATA`, comentarios, `DOCTYPE`, BOM, UTF-8 e ISO-8859-1, XML mal formado y un fichero que no es XML.
- [x] T5. `core:formats`: `LandXmlReader`, `scan` y `read` de una superficie, con el orden Norte-Este-cota, las caras invisibles y la unidad. (RF-2, RF-3, RF-5, RF-7, RF-12)
      Hecho cuando: `LandXmlReaderTest` en verde con las versiones 1.0 a 1.2, identificadores no consecutivos y de texto, `GRID`, sin superficies, varias superficies y las tres unidades.
- [x] T6. `core:survey`: `TinTiler`, `TinTile` y `TinElevation`, la cota por interpolación lineal en el triángulo que contiene la posición. (RF-22, RF-23, RF-12)
      Hecho cuando: `TinTilerTest` y `TinElevationTest` en verde frente a `fixtures/superficies/` (≤ 1 mm): dentro, arista, vértice, fuera, hueco, triángulo que cruza hojas y hoja partida por tamaño.
- [x] T7. `core:survey`: `SurfaceProjector`, la conversión de los puntos al SC y a la unidad del proyecto y la extensión de las cuatro esquinas. (RF-6, RF-7, RF-8)
      Hecho cuando: `SurfaceProjectorTest` en verde frente a pyproj (≤ 1 mm) con pies, pies topográficos de EE. UU. y otro SC.
- [x] T8. `data`: las tablas `surfaces` y `surface_tiles`, las columnas nuevas de `projects` y de `points`, sus DAO y `AutoMigration(14, 15)`. (RNF-T6, RF-14, RF-19, RF-28)
      Hecho cuando: `TopoPhoneDatabaseTest` 14 → 15 en verde: los proyectos, los puntos, los replanteos y los dibujos conservan sus datos y el desplazamiento queda en 0.
- [x] T9. `data`: `SurfaceRepository` (`begin`, `addTiles`, `finish`, `abort`, la limpieza, la lista, activar, borrar y el desplazamiento) y `PointRepository` con los datos de la superficie en la misma transacción. (RF-11, RF-16 a RF-19, RF-28, RF-32)
      Hecho cuando: `SurfaceRepositoryTest` y `PointRepositoryTest` en verde: la primera superficie se activa sola, borrar la activa, el desplazamiento que se conserva y la superficie borrada después de replantear.
- [x] T10. `data`: `SurfaceImporter`, `scan` e `import` con progreso, cancelación y fallos. (RF-5, RF-8, RF-10, RF-11, RF-13 a RF-15)
      Hecho cuando: `SurfaceImporterTest` en verde: importar, cancelar, fallo de escritura sin dejar nada, varias superficies y el mismo nombre de fichero.
- [x] T11. `data`: `ActiveSurface` y `ActiveSurfaceProvider`, el índice de teselas, la caché, `Loading`, la precarga y el vaciado al desactivar. (RF-16, RF-22, RF-23, RF-27)
      Hecho cuando: `ActiveSurfaceTest` en verde: `Loading` y después `Value` o `Outside`, la precarga y la superficie borrada o desactivada.
- [x] T12. `data`: copia de seguridad, paquete y restauración con las superficies, la activa y el desplazamiento. (RF-21, RNF-T6)
      Hecho cuando: `SurfaceBackupTest` en verde: copia completa, paquete de un proyecto con identificadores nuevos y restauración, con la activa y el desplazamiento.
- [x] T13. `app`: `SurfaceImportViewModel`, los pasos, los rechazos, la elección de superficie, el SC y la unidad, la lejanía y el nombre repetido. (RF-1 a RF-10, RF-15)
      Hecho cuando: `SurfaceImportViewModelTest` en verde.
- [x] T14. `app`: `SurfaceImportScreen`, `SurfaceImportRoute` y «Importar superficie» en los menús, con sus textos en es y en. (RF-1, RF-5, RF-8, RF-13, RNF-T7, RNF-T8)
      Hecho cuando: `SurfaceImportScreenTest` en verde, con las zonas táctiles de 56 dp y el resumen.
- [ ] T15. `app`: `SurfacesSheet` y `SurfacesViewModel`, la lista, activar, borrar con confirmación y el desplazamiento vertical. (RF-16 a RF-20)
      Hecho cuando: `SurfacesSheetTest` en verde: activar otra y ninguna, borrar la activa y un desplazamiento que no es un número.
- [ ] T16. `app`: `StakeContext` con la superficie y el origen, y `StakeoutController` con la cota de cada época, el nivel que no se reinicia, `SurfaceUi` y «Fuera de la superficie». (RF-23, RF-24, RF-26)
      Hecho cuando: `StakeoutControllerSurfaceTest` en verde y los tests de S018, S019 y S041 siguen en verde.
- [ ] T17. `app`: el origen de la cota en `StakeoutViewModel` y `LineStakeoutViewModel`: la regla de A5, el conmutador, la cota a mano fuera de la superficie y la desactivación con el replanteo abierto. (RF-23, RF-24, RF-25, RF-27)
      Hecho cuando: `StakeoutViewModelSurfaceTest` y `LineStakeoutViewModelSurfaceTest` en verde.
- [ ] T18. `app`: el conmutador en `StakeoutPanel` y `LineStakeoutPanel`, «Fuera de la superficie» en el nivel vertical, y las etiquetas de TalkBack, con sus textos en es y en. (RF-23, RF-25, RF-26, RNF-T7, RNF-T8)
      Hecho cuando: los tests de los paneles en verde, con el estado en texto además del color y las zonas táctiles de 56 dp.
- [ ] T19. `app`: medir y guardar con la superficie: `StakeObjective`, `StakeoutDiff.ref` y los dos `ViewModel`, también fuera de la superficie. (RF-28, RF-29, RF-32)
      Hecho cuando: `StakeoutMeasureSurfaceTest` y `StakeoutDiffTest` en verde: origen, superficie, cota, desplazamiento, diferencia, y las cinco columnas vacías fuera.
- [ ] T20. La ficha del punto replanteado y el informe de replanteo con las cuatro columnas nuevas del CSV. (RF-30, RF-31)
      Hecho cuando: el test de la ficha y `StakeoutReportCsvTest` en verde: columnas vacías sin superficie, `Locale.ROOT`, unidades del proyecto y goldens regenerados.
- [ ] T21. `app`: la cota de la superficie bajo la punta en el recolector, sin guardarla en el punto. (RF-33)
      Hecho cuando: `CollectorSurfaceTest` en verde: dentro, fuera y que el punto medido no lleva nada de la superficie.
- [ ] T22. Rendimiento: `SurfacePerformanceTest`. (RNF)
      Hecho cuando: activar una superficie de 1.000.000 de triángulos da la primera cota en menos de 1 s, la consulta a 10 Hz no pasa de 16 ms y un LandXML de 100 MB generado al vuelo se importa en menos de 90 s sin quedarse sin memoria.
- [ ] T23. Reproducción sintética `rs3-superficie`: un recorrido que cruza una superficie con un hueco y su resultado esperado calculado aparte. (Criterios de finalización)
      Hecho cuando: el test en verde con la cota, el desmonte o terraplén esperados y el paso por «Fuera de la superficie».
- [ ] T24. Validación: revisión de los cambios en documentos anteriores (hoja de ruta, glosario, S018/S019/S041), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2b) quedan para el usuario.
