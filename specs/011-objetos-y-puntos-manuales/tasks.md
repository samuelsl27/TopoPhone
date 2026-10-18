# Tareas — Spec 011

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/geodesia`: vectores `puntos_manuales.csv` (UTM inversa en ETRS89, REGCAN95 y ED50; altitud y altura elipsoidal; fuera de ámbito, de rejilla y de geoide) y `jalon_corregido.csv`. (RF-14, RF-17, RF-19, RF-20)
      Hecho cuando: los CSV están en `fixtures/geodesia/` y `uv run --project tools pytest tools` los regenera iguales.
- [x] T2. `core:geodesy`: `TransverseMercator.inverse` y `Utm.inverse`. (RF-17, RF-20)
      Hecho cuando: `UtmInverseTest` en verde frente a los CSV de S008 al revés (≤ 1e-9°) y con la ida y vuelta ≤ 0,1 mm en los husos 27 a 31.
- [x] T3. `core:model`: `NumberInput` y `AngleInput` (DD, DMS y gon, con signo o letra de hemisferio). (RF-8, RF-17)
      Hecho cuando: `NumberInputTest` y `AngleInputTest` en verde, con los casos inválidos.
- [ ] T4. `core:model`: `Point`, `Measurement`, `TypedCoordinates`, `PointSnapshot`, `PointHistoryEntry` y `PointSummary`; `PointNames.nextFree` y `PointSearch`. `CollectedPoint` pasa a `Point` en todo el código de S010. (RF-1 a RF-3, RF-16, RF-21)
      Hecho cuando: `PointNamesTest` y `PointSearchTest` en verde, y los tests de S010 siguen en verde.
- [ ] T5. `core:survey`: `ProjectPositionCalculator.fromGround` y `ManualCoordinates`. (RF-17 a RF-20)
      Hecho cuando: `ManualCoordinatesTest` en verde frente a `puntos_manuales.csv` (≤ 1 mm) y los tests de S008 siguen en verde.
- [ ] T6. `core:survey`: `PointCorrections` (jalón, volver a la medida, duplicar). (RF-12 a RF-14, RF-21)
      Hecho cuando: `PointCorrectionsTest` en verde frente a `jalon_corregido.csv` (≤ 1 mm), con un punto editado.
- [ ] T7. `data`: Room v6, con las columnas nuevas de `points`, `point_history` e índice `(project_id, name, trash_key)`; migración 5→6. (RF-20, RF-22, RF-25)
      Hecho cuando: `TopoPhoneDatabaseTest` en verde con un punto de S010 migrado (todo conservado, la medida copiada).
- [ ] T8. `data`: `PointRepository` con resúmenes, ficha, edición con historial, `updateAll`, visibilidad y papelera (mover, restaurar, borrar, purgar); `TrashPurge` purga también los puntos. (RF-1, RF-4, RF-9 a RF-11, RF-13 a RF-15, RF-22 a RF-26)
      Hecho cuando: `PointRepositoryTest` y `TrashPurgeTest` en verde.
- [ ] T9. `app`: navegación nueva, botón "Objetos" en el mapa, `ObjectListViewModel` y pantalla de la lista (búsqueda, marcas, ojo, selección múltiple con ocultar, mostrar y borrar). (RF-1 a RF-4, RF-22)
      Hecho cuando: `ObjectListViewModelTest` y `ObjectListScreenTest` en verde.
- [ ] T10. `app`: corrección del jalón de una selección desde la lista, con el aviso de los manuales. (RF-14, RF-15)
      Hecho cuando: los tests del jalón en `ObjectListViewModelTest` y `ObjectListScreenTest` en verde.
- [ ] T11. `app`: ficha del punto (coordenadas, medida, NMEA, editado y "Volver a la medida", copia de) y toque en el mapa y en la lista. (RF-5 a RF-7, RF-12, RF-13)
      Hecho cuando: `PointDetailsViewModelTest`, `PointDetailsScreenTest` y el test del toque en `PointsLayerTest` en verde.
- [ ] T12. `app`: acciones de la ficha: jalón, duplicar, borrar e historial. (RF-10, RF-11, RF-14, RF-21, RF-22)
      Hecho cuando: los tests de las acciones y `PointHistoryTest` en verde.
- [ ] T13. `app`: formulario de punto, nuevo y editado (SC del proyecto o geográficas, altura opcional y su tipo, errores, nombre repetido). (RF-8, RF-9, RF-16 a RF-20)
      Hecho cuando: `PointFormViewModelTest` y `PointFormScreenTest` en verde.
- [ ] T14. `app`: papelera de puntos (lista con días, restaurar, nombre ocupado, borrar definitivamente). (RF-23 a RF-26)
      Hecho cuando: `PointTrashViewModelTest` y `PointTrashScreenTest` en verde.
- [ ] T15. Rendimiento: 10 000 resúmenes leídos y filtrados en menos de 1 s; puntos ocultos y de la papelera fuera del mapa y del recuento. (RF-4, RF-22, RNF)
      Hecho cuando: el test de rendimiento y los de recuento y mapa en verde.
- [ ] T16. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
