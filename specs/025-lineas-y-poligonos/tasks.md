# Tareas — Spec 025

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `ProjectLine`, `LineVertex`, `LineSummary` y los términos en `docs/glosario.md` («Línea (del proyecto)», «Línea activa», «Línea abierta»). (RF-21)
      Hecho cuando: los tests de `core:model` en verde.
- [x] T2. `core:model`: `LineNaming` y `LineRules` (mínimos de vértices, cierre, abrir, invertir, repetidos seguidos). (RF-2, RF-4, RF-10, RF-11, RF-15, RF-19)
      Hecho cuando: `LineNamingTest` y `LineRulesTest` en verde.
- [x] T3. `core:model`: `LineTarget.decide` (añadir, crear o nada). (RF-2, RF-3, RF-7, RF-8)
      Hecho cuando: `LineTargetTest` en verde.
- [x] T4. `core:model`: `LineEdit` y `LineChainage` (PK de cada vértice). (RF-16, RF-17)
      Hecho cuando: `LineEditTest` y `LineChainageTest` en verde.
- [x] T5. `core:model`: historial de líneas (`LineChange`, `HistoryLine`) y `LineSearch`. (RF-18, RF-21)
      Hecho cuando: `LineHistoryTest` y `LineSearchTest` en verde.
- [x] T6. `core:model`: `ProjectCodes` con parámetro de geometrías. (RF-1, RF-20)
      Hecho cuando: `ProjectCodesGeometryTest` en verde y los tests de S024 intactos.
- [x] T7. `data`: Room v30 (tablas de D1, D2 y D6), `AutoMigration(29, 30)` y el esquema 30. (RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 29 a 30 en verde.
- [x] T8. `data`: `LineRepository` lectura (`lines`, `summaries`, `line`, `active`, `history`) y mapeos. (RF-21)
      Hecho cuando: `LineRepositoryTest` de lectura en verde.
- [x] T9. `data`: `saveMeasured` en una transacción, una sola línea activa, `finish`, `continueLine` y `undoVertex`. (RF-2 a RF-9, RF-8b)
      Hecho cuando: los tests de medir, terminar, continuar, deshacer y reinicio de la base en verde, con añadir un vértice en menos de 100 ms.
- [x] T10. `data`: `close`, `open` y `createFromPoints`. (RF-10, RF-11, RF-14, RF-15)
      Hecho cuando: tests en verde y ningún punto modificado.
- [x] T11. `data`: `edit` y `changeCode` con historial. (RF-16 a RF-20)
      Hecho cuando: tests en verde con rechazo por menos vértices y vértices anteriores en el historial.
- [x] T12. `data`: `setHidden`, papelera de líneas, `restore`, `deletePermanently` y `purge`. (RF-23, RF-24)
      Hecho cuando: tests en verde con nombre reutilizado.
- [x] T13. `data`: la papelera de puntos con líneas (omitir, recuperar, borrado definitivo con `VERTEX_REMOVED`) y `linesUsing`. (RF-25)
      Hecho cuando: `LinePointTrashTest` en verde.
- [x] T14. `data`: copia de seguridad y paquetes con las tablas nuevas. (RF-26, RNF-T6)
      Hecho cuando: `LineBackupTest` en verde.
- [x] T15. `core:formats` y `data`: capas `lines` y `polygons` del GeoPackage. (RF-26)
      Hecho cuando: `GpkgLinesTest` y `GeoPackageWriterLinesTest` en verde.
- [x] T16. `app`: estado de línea activa en el recolector, el guardado con `saveMeasured` y el formulario de atributos según D11. (RF-1, RF-2, RF-3, RF-7, RF-8, RF-8b, RF-9)
      Hecho cuando: `CollectorLineViewModelTest` en verde, con pantalla encendida con línea activa.
- [x] T17. `app`: botones «Terminar línea», «Continuar línea» y «Deshacer vértice» y la lista de líneas abiertas. (RF-4, RF-5, RF-6)
      Hecho cuando: `CollectorLineScreenTest` en verde.
- [x] T18. `app`: asistente «Línea desde puntos». (RF-12, RF-13, RF-14, RF-15)
      Hecho cuando: `LineFromPointsTest` en verde.
- [x] T19. `app`: ficha de la línea con vértices y PK, edición, cierre, apertura, cambio de código, atributos e historial. (RF-10, RF-16 a RF-20, RF-22)
      Hecho cuando: `LineDetailsTest` en verde.
- [x] T20. `app`: lista de objetos con líneas, búsqueda, ocultar, borrar a la papelera, restaurar y el aviso al borrar un punto vértice. (RF-21, RF-23, RF-24, RF-25)
      Hecho cuando: `ObjectListLinesTest` en verde.
- [x] T21. `app`: `MapLines` y capas de línea, relleno y nombre, con toque para abrir la ficha. (RF-22)
      Hecho cuando: `MapLinesTest` y `LinesLayerTest` en verde.
- [x] T22. `app`: rendimiento del mapa (1.000 líneas y 100.000 vértices) y de la ficha (100.000 vértices en menos de 1 s). (RNF-T13)
      Hecho cuando: `LinesPerformanceTest` en verde.
- [x] T23. `app`: exportar las líneas en las pantallas de GeoPackage. (RF-26)
      Hecho cuando: tests de exportación en verde.
- [ ] T24. Accesibilidad y textos: etiquetas, 56 dp, abierta, polígono y activa con texto, es/en. (RNF-T7, RNF-T8)
      Hecho cuando: `LinesAccessibilityTest` en verde y sin textos literales en código.
- [ ] T25. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files` y la demo en el emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo de la spec funciona y la hoja de ruta marca S025 como implementada.
