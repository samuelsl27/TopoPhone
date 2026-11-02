# Tareas — Spec 051

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/ejes`: `generar_taludes.py` con `fixtures/ejes/taludes/`: desmonte, terraplén, altura cero y sin intersección contra un perfil de terreno poligonal y contra un TIN pequeño, con lo esperado en aritmética racional. (RF-5 a RF-8)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `SlopeSide`, `SlopeKind`, `SlopeRule`, `ManualEdge`, `SlopeIssue`, `SlopeStakeInfo` en `StakeoutRef` y los términos en `docs/glosario.md`. (RF-1, RF-2, RF-15)
      Hecho cuando: los tests de `core:model` en verde.
- [x] T3. `core:survey`: `SlopeValidator` (pendiente no válida, rangos solapados, PK fuera del eje). (RF-3)
      Hecho cuando: `SlopeValidatorTest` en verde.
- [x] T4. `core:survey`: `SlopeEdge` (borde de la sección de S050, código elegido o borde manual). (RF-1, RF-2, RF-5)
      Hecho cuando: `SlopeEdgeTest` en verde.
- [x] T5. `core:survey`: `TerrainAt` (perfiles de terreno interpolados entre PK y superficie, por orden). (RF-6)
      Hecho cuando: `TerrainAtTest` en verde.
- [x] T6. `core:survey`: `SlopeCatch` (tipo, punto de talud, altura, altura cero, sin intersección, sin terreno). (RF-5, RF-7, RF-8)
      Hecho cuando: `SlopeCatchTest` en verde contra `fixtures/ejes/taludes/` a 1 mm.
- [x] T7. `core:survey`: `SlopeLive` (diferencia de cota, distancia a moverse, error en PK, encontrado y estaca). (RF-11 a RF-14)
      Hecho cuando: `SlopeLiveTest` en verde a 1 mm.
- [x] T8. `data`: Room v26 (`slope_rules` y seis columnas de `points`), `AutoMigration(25, 26)` y el esquema 26. (RF-4, RF-15, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 25 a 26 en verde.
- [x] T9. `data`: `SlopeRepository` (altas y bajas en una transacción, log, borrar el eje) y copias y paquetes. (RF-4)
      Hecho cuando: `SlopeRepositoryTest` y `SlopeBackupTest` en verde.
- [x] T10. `core:formats`: columnas de talud en `StakeoutReportCsv` y dorados regenerados. (RF-16)
      Hecho cuando: `StakeoutReportCsvTest` y el golden en verde.
- [x] T11. `app`: `SlopesViewModel` (definir por lado y rango, sección manual, rechazos). (RF-1 a RF-3)
      Hecho cuando: `SlopesViewModelTest` en verde.
- [x] T12. `app`: `SlopesScreen`, «Taludes» en `AlignmentsSheet` y `AlignmentFicha`, y la navegación. (RF-1, RNF-T7, RNF-T8)
      Hecho cuando: `SlopesScreenTest`, `AlignmentsSheetTest` y `AlignmentTouchTest` ampliados en verde.
- [x] T13. `app`: punto de talud teórico (PK y lado, terreno de perfiles o superficie). (RF-5 a RF-8)
      Hecho cuando: `SlopePointViewModelTest` y su test de pantalla en verde.
- [ ] T14. `app`: `SlopeStakeViewModel` (abrir, serie de PK, objetivo fijo, estaca, bloqueos y motivos de apagado). (RF-9, RF-14, RF-17, RF-18)
      Hecho cuando: `SlopeStakeViewModelTest` en verde.
- [ ] T15. `app`: `SlopeStakePanel` y `SlopeElevationView` (alzado en vivo, diferencia de cota, distancia a moverse, error en PK). (RF-10 a RF-12, RNF-T8)
      Hecho cuando: `SlopeStakePanelTest` y `SlopeElevationViewTest` en verde.
- [ ] T16. `app`: «Punto de talud encontrado» con sonido y vibración. (RF-13)
      Hecho cuando: el test del panel y del ViewModel en verde.
- [ ] T17. `app`: «Replantear taludes» en la hoja y la ficha del eje y `StakeMode.SLOPE` en `ProjectView`. (RF-9)
      Hecho cuando: los tests de la hoja y de la ficha ampliados en verde.
- [ ] T18. `app`: medir y guardar el punto de talud y mostrarlo en la ficha del punto. (RF-15)
      Hecho cuando: `SlopeStakeMeasureTest` y `PointDetails` ampliado en verde.
- [ ] T19. `app`: el símbolo propio en el mapa y la columna en el informe de la pantalla. (RF-16)
      Hecho cuando: los tests de escena y de informe en verde.
- [ ] T20. Reproducción que sube por una sección, época a época contra el cálculo aparte. (RF-10 a RF-13, RNF-T13)
      Hecho cuando: `SlopeStakeReplayTest` en verde a 1 mm.
- [ ] T21. Rendimiento: el alzado y la guía de una época en menos de 16 ms. (RNF-T13)
      Hecho cuando: `SlopeStakePerformanceTest` en verde.
- [ ] T22. Validación: cambios en documentos anteriores (hoja de ruta, Room v26), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
