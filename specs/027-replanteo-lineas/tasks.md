# Tareas — Spec 027

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `StakeSource.ProjectLine` y los términos de `docs/glosario.md` («Línea de diseño»; «Replanteo de línea» y «Modo de línea» citan también S027). (RF-11, RF-12)
      Hecho cuando: `StakeSourceProjectLineTest` y los tests de `core:model` en verde.
- [x] T2. `tools`: referencias de cota parcial (`generar_cota_parcial.py`, `fixtures/replanteo/cota-parcial/`) con aritmética exacta y su test de `pytest`. (RF-8, RF-9; constitución nº 2)
      Hecho cuando: `uv run --project tools pytest tools` en verde y los ficheros en el repositorio.
- [x] T3. `core:survey`: `StakeLine` con altura por vértice (`NaN`), `heightAt` y `height(v)` de D2, y `create` que devuelve el origen de cada vértice. (RF-8, RF-9)
      Hecho cuando: `StakeLinePartialHeightTest` en verde frente a las referencias y los tests de S041 intactos.
- [x] T4. `core:survey`: `ProjectStakeLine.of` (nombres, índices de origen, cota interpolada, menos de 2 vértices) y `ProjectLineParityTest` en los cinco modos. (RF-2, RF-3, RF-5, RF-6)
      Hecho cuando: `ProjectStakeLineTest` y `ProjectLineParityTest` en verde (≤ 1 mm).
- [x] T5. `data`: Room v31 (`AutoMigration(30, 31)`, `stake_line_id` y `stake_line_name`) y el mapeo de `StakeSource.ProjectLine`. (RF-11, RF-12, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` de la v30 a la v31, `StakeLineSourceMappingTest` y `LineStakeoutBackupTest` en verde.
- [x] T6. `app`: `LineSource` y `LineStakeoutViewModel` generalizado sin cambiar el comportamiento de los dibujos; `LineElementInfo` común. (RF-1, RF-2)
      Hecho cuando: todos los tests de replanteo de línea de S041 siguen en verde.
- [x] T7. `app`: abrir el replanteo con una línea del proyecto (carga fuera del hilo principal, menos de 2 vértices, motivo, panel con nombre, código, longitud y forma, trazo grueso en el mapa). (RF-1, RF-3, RF-4)
      Hecho cuando: `LineStakeoutProjectLineTest` (apertura) en verde.
- [x] T8. `app`: «Por segmentos» con el nombre del punto de cada vértice, su PK, «Siguiente» y «Anterior» con vuelta en un polígono, y la papelera. (RF-5, RF-6, RF-7)
      Hecho cuando: `LineStakeoutSegmentsTest` en verde.
- [x] T9. `app`: cota de diseño por objetivo: interpolada, sin cota en el tramo, cota a mano y superficie activa. (RF-8, RF-9, RF-10)
      Hecho cuando: los tests de cota de `LineStakeoutProjectLineTest` en verde.
- [x] T10. `app`: medir y guardar con el nombre de «Por segmentos» o de la línea, la fuente `ProjectLine` y el código y los atributos de la línea. (RF-11, RF-12)
      Hecho cuando: `LineStakeoutSaveTest` en verde, con un solo guardado atómico del punto.
- [ ] T11. `app`: la ficha del punto replanteado dice de qué línea viene. (RF-13)
      Hecho cuando: `PointDetailsLineTest` en verde.
- [ ] T12. `core:formats` y `app`: columna «Line» en el informe de replanteo y golden actualizados. (RF-14)
      Hecho cuando: `StakeoutReportCsvLineTest`, `StakeoutReportLineTest` y los golden de S019 y S041 en verde.
- [ ] T13. `app`: cerrar el replanteo si la línea o un vértice cambia, o la línea se borra, con el motivo. (RF-15, RF-16)
      Hecho cuando: `LineStakeoutClosedTest` y `LineDeletedKeepsPointsTest` en verde.
- [ ] T14. `app`: «Replantear» en la ficha de la línea, en la hoja que se abre al tocar la línea en el mapa y en el menú «⋮» de la fila de la lista de objetos. (RF-1)
      Hecho cuando: `LineActionsTest` en verde y los tests de S025 de la ficha y del mapa adaptados o intactos.
- [ ] T15. `core:survey`: `PolylineAlignmentBuilder.buildFromVertices` con alturas parciales. (RF-17)
      Hecho cuando: `PolylineAlignmentFromVerticesTest` en verde y los tests de S049 intactos.
- [ ] T16. `app`: «Crear eje» desde la ficha y la hoja del mapa, con la línea y sus puntos intactos. (RF-17, RF-18)
      Hecho cuando: `CreateAxisFromLineTest` en verde.
- [ ] T17. `app`: rendimiento: abrir una línea de 100.000 vértices en menos de 1 s. (RNF-T13)
      Hecho cuando: `LineStakeoutPerformanceTest` en verde.
- [ ] T18. Accesibilidad y textos: etiquetas, 56 dp, lado con texto, es/en y sin literales en código. (RNF-T7, RNF-T8)
      Hecho cuando: `LineStakeoutAccessibilityTest` en verde.
- [ ] T19. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files` y la demo en el emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo de la spec funciona y la hoja de ruta marca S027 como implementada con Room v31.
