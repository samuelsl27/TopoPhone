# Tareas — Spec 024

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `CodeSymbol`, `SurveyCode.symbol`, `PointCode`, `PointAttribute`, `Point.code`, campos de código en `PointSummary` y los términos en `docs/glosario.md`. (RF-10, RF-17)
      Hecho cuando: los tests de `core:model` en verde.
- [x] T2. `core:model`: `ProjectCodes` (unión, solo Punto, orden) y `RecentCodes`. (RF-1)
      Hecho cuando: `ProjectCodesTest` y `RecentCodesTest` en verde.
- [x] T3. `core:model`: filtro del selector con `CodeSearch` y `DescriptionProposal`. (RF-2, RF-4)
      Hecho cuando: tests en verde, con 2.000 códigos filtrados en menos de 16 ms de CPU.
- [x] T4. `core:model`: `AttributeForm` (valores iniciales y validación por atributo). (RF-8, RF-9)
      Hecho cuando: `AttributeFormTest` en verde con los cuatro tipos y la coma decimal.
- [x] T5. `core:model`: `AttributeCarryOver`. (RF-14, RF-15)
      Hecho cuando: `AttributeCarryOverTest` en verde.
- [x] T6. `core:model`: `PointSnapshot` con código, `PointChange.CODE`, `HistoryLine.Code` y `PointSearch` por código. (RF-12, RF-14, RF-18)
      Hecho cuando: `PointHistoryCodeTest` y `PointSearchCodeTest` en verde.
- [x] T7. `data`: Room v29 (columnas y tablas de D1 a D4), `AutoMigration(28, 29)` y el esquema 29. (RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 28 a 29 en verde.
- [ ] T8. `data`: `PointRepository.create` guarda código y atributos en la misma transacción y los lee (punto, resumen). (RF-10)
      Hecho cuando: `PointCodeRepositoryTest` en verde.
- [ ] T9. `data`: `changeCode` atómico con historial, y lectura del historial con el código anterior. (RF-13, RF-14, RF-16)
      Hecho cuando: los tests de cambio de código (entero o nada, coordenadas intactas) en verde.
- [ ] T10. `data`: `recentCodes(projectId)`. (RF-1)
      Hecho cuando: test en verde con papelera, repetidos y límite de 10.
- [ ] T11. `data`: códigos creados en el proyecto (`project_codes_of`, `saveProjectCode`, `deleteProjectCode`, conflictos, exclusión de las listas de bibliotecas). (RF-5, RF-6, RF-7)
      Hecho cuando: `ProjectCodeRepositoryTest` en verde, incluidos borrar la biblioteca y cambiar de biblioteca vinculada.
- [ ] T12. `data`: copia de seguridad y duplicado de proyecto con las tablas nuevas. (RNF-T6)
      Hecho cuando: `CodeBackupTest` en verde.
- [ ] T13. `app`: estado del recolector con código elegido, recientes, búsqueda, atributos y `MeasureBlocker.ATTRIBUTES`. (RF-1 a RF-4, RF-8, RF-9, RF-11)
      Hecho cuando: `CollectorCodeViewModelTest` en verde.
- [ ] T14. `app`: el recolector guarda el código con el punto y el replanteo hereda el del punto de diseño (`StakeTarget.code`). (RF-10, RF-20)
      Hecho cuando: tests del ViewModel de guardado y de replanteo en verde.
- [ ] T15. `app`: selector de códigos en el recolector (recientes, buscador, «Sin código»). (RF-1, RF-2, RF-3)
      Hecho cuando: `CollectorCodeScreenTest` en verde.
- [ ] T16. `app`: formulario de atributos en el recolector con errores por campo. (RF-8, RF-9)
      Hecho cuando: test de pantalla en verde con los cuatro tipos y un valor inválido.
- [ ] T17. `app`: editor de código parametrizado (destino, geometría fija Punto, símbolo) y «Nuevo código» desde el selector con editar y borrar. (RF-5, RF-6, RF-7)
      Hecho cuando: `CodeEditorViewModelTest` y el test de pantalla en verde.
- [ ] T18. `app`: ficha del punto con código y atributos, y edición con rastro. (RF-12)
      Hecho cuando: `PointDetailsCodeTest` en verde.
- [ ] T19. `app`: «Cambiar código» en la ficha y en la selección de la lista, con el aviso de valores descartados. (RF-13, RF-15)
      Hecho cuando: `ChangeCodeTest` en verde.
- [ ] T20. `app`: lista de objetos con código visible, filtro y búsqueda por código. (RF-18)
      Hecho cuando: `ObjectListCodeTest` en verde.
- [ ] T21. `app`: `MapPoints` con color y símbolo vigentes o guardados. (RF-17, RF-19)
      Hecho cuando: `MapPointsCodeTest` en verde.
- [ ] T22. `app`: capa de símbolos del mapa con bitmaps SDF y `icon-color`. (RF-17)
      Hecho cuando: `PointsSymbolLayerTest` en verde y `PointsPerformanceTest` con 10.000 puntos con código en menos de 2 s.
- [ ] T23. `core:formats`: columna `CODE` y atributos dinámicos en «Todas las columnas». (RF-21)
      Hecho cuando: `CsvExportCodeTest` en verde.
- [ ] T24. GeoPackage: atributos de los códigos como columnas tipadas. (RF-21)
      Hecho cuando: `GpkgCodeAttributesTest` y `GeoPackageWriterTest` en verde.
- [ ] T25. Accesibilidad y textos: etiquetas, 56 dp, TalkBack lee código con descripción, es/en. (RNF-T7, RNF-T8)
      Hecho cuando: los tests de semántica en verde y sin textos literales en código.
- [ ] T26. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files` y la demo en el emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo de la spec funciona y la hoja de ruta marca S024 como implementada.
