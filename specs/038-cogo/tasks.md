# Tareas — Spec 038

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`), marcar la casilla y un commit `S038 Tn: …`.

- [x] T1. `core:model`: `PointOrigin.COGO` (no cuenta como medida), `Point.cogo` y los tipos de `cogo/` (`CogoOperation`, `CogoEntry`, `LegKind`, `TraverseLeg`, `CogoValue`, `IntersectionSide`, `CogoRef`). (RF-22, RF-24)
      Hecho cuando: `PointOriginCogoTest` y `CogoRefTest` en verde y los `when` exhaustivos de `PointOrigin` compilan en todos los módulos.
- [x] T2. `core:model`: `DirectionInput` y `DistanceInput`. (RF-4)
      Hecho cuando: `DirectionInputTest` y `DistanceInputTest` en verde con DD, DMS, gon, coma decimal, 360° y negativos rechazados, deflexión de 180° aceptada y distancia negativa rechazada.
- [x] T3. `tools`: `generar_cogo.py` de la inversa y las intersecciones, con `pyproj` y `fixtures/cogo/` (ETRS89 UTM 30, REGCAN95 UTM 28 y ED50 UTM 30, metros y pies, cuadrícula y terreno, semilla fija) y su test de `pytest`. (Constitución nº 2)
      Hecho cuando: `uv run --project tools pytest tools` en verde y los ficheros de inversas e intersecciones generados en el repositorio.
- [x] T4. `tools`: el mismo generador para la poligonal (azimut, ángulo a la derecha y a la izquierda, deflexión a la derecha y a la izquierda; cierre y alturas) a partir de puntos conocidos. (Constitución nº 2)
      Hecho cuando: `pytest` en verde y los ficheros de poligonales generados.
- [x] T5. `core:survey`: `CogoPoint`, `CogoScale` (factor del primer punto con `GroundScale`) e `Inverse`. (RF-5, RF-6, RF-7, RF-8)
      Hecho cuando: `CogoScaleTest` e `InverseTest` en verde frente a las referencias (≤ 1 mm, 0,001″, factor ≤ 1e-9), con puntos coincidentes y sin altura.
- [x] T6. `core:survey`: `Traverse` con tramos por azimut y por ángulo a la derecha y a la izquierda, en cuadrícula y en terreno. (RF-10, RF-11)
      Hecho cuando: `TraverseAzimuthTest` y la parte de ángulos de `TraverseAnglesTest` en verde frente a las referencias.
- [x] T7. `core:survey`: deflexión a la derecha y a la izquierda, y visual atrás indefinida (`stoppedAt`, hecha junto con T6 porque comparten el mismo `when`). (RF-10, RF-31)
      Hecho cuando: `TraverseAnglesTest` completo en verde, con la referencia en el inicio y un tramo anterior de longitud 0.
- [x] T8. `core:survey`: alturas por tramo y error de cierre. (RF-11, RF-12)
      Hecho cuando: `TraverseHeightTest` y `TraverseClosureTest` en verde, con poligonal que cierra, que no cierra y con un desnivel que falta.
- [x] T9. `core:survey`: `Intersection.distanceDistance`. (RF-14, RF-17)
      Hecho cuando: `IntersectionCircleTest` en verde con dos soluciones (izquierda y derecha), tangentes, concéntricas y circunferencias que no se cortan.
- [x] T10. `core:survey`: `Intersection.directionDirection` y `.distanceDirection`. (RF-15, RF-16, RF-17)
      Hecho cuando: `IntersectionLinesTest` en verde con solución detrás de un punto, direcciones paralelas y coincidentes, y cero, una y dos soluciones.
- [x] T11. `core:survey`: `CogoPointBuilder` (punto COGO con su `CogoRef`, altura opcional, rechazo fuera del ámbito). (RF-18, RF-21, RF-22)
      Hecho cuando: `CogoPointBuilderTest` en verde con altura, sin altura, fuera del huso y las coordenadas guardadas sin redondear.
- [ ] T12. `core:survey`: rendimiento de la poligonal de 200 tramos. (RNF-T13)
      Hecho cuando: `TraversePerformanceTest` en verde (menos de 100 ms, en terreno y en cuadrícula).
- [ ] T13. `core:formats`: `CogoRefJson` y `TraverseDraftJson` con el tipo `TraverseDraft` y `DraftPoint` en `core:model`. (RF-22, RF-26, RNF-T9)
      Hecho cuando: `CogoRefJsonTest` y `TraverseDraftJsonTest` en verde con ida y vuelta exacta de los `Double`, versión `v`, `Locale.ROOT` y campos que faltan.
- [ ] T14. `data`: Room v33 (`points.cogo`, `traverse_drafts`, `AutoMigration(32, 33)`, esquema `33.json`) y la correspondencia de `Point.cogo`. (RF-22, RNF-T6)
      Hecho cuando: el test de migración v32 a v33 de `TopoPhoneDatabaseTest` y `CogoPointPersistTest` en verde y los tests de las versiones anteriores intactos.
- [ ] T15. `data`: `CogoRepository` con el borrador (observar, guardar, vaciar) y su borrado con el proyecto. (RF-26, RF-29)
      Hecho cuando: `TraverseDraftRepositoryTest` en verde con un borrador por proyecto, sustitución al guardar y borrado en cascada.
- [ ] T16. `data`: `CogoRepository.savePoints` en una transacción, con nombre repetido, vaciado del borrador y log sin nombres ni coordenadas. (RF-19, RF-20, RF-23, RF-25, RF-30)
      Hecho cuando: `CogoSavePointsTest` en verde (todo o nada, `DuplicateName`, borrador vaciado solo si se pide, línea de log sin datos).
- [ ] T17. `data`: el borrador en la copia de seguridad y no en el duplicado ni en los paquetes (`DatabaseSnapshot.TABLES`). (RF-29)
      Hecho cuando: `CogoBackupTest` en verde y `DatabaseSnapshotTest` de S013 intacto.
- [ ] T18. `core:formats` y `data`: «Cogo» en la columna Origin del CSV y del GeoPackage, y el punto COGO se edita con historial, se duplica como manual, va a la papelera y se replantea como uno manual. (RF-24)
      Hecho cuando: `CogoExportOriginTest` y las pruebas de edición, duplicado, papelera y replanteo con un punto COGO en verde.
- [ ] T19. `app`: la pantalla de elección «COGO» (Inversa, Poligonal, Intersección), su ruta y las entradas en el menú de la lista de objetos y en el del proyecto; SC geográfico sin COGO. (RF-1, RF-2)
      Hecho cuando: `CogoMenuTest` en verde con SC proyectado y con SC geográfico.
- [ ] T20. `app`: `CogoPointField` (elegir con búsqueda o escribir Este y Norte, con altura), campos de dirección y distancia con su unidad, y el selector Cuadrícula/Terreno con la elección de S026. (RF-3, RF-4, RF-5)
      Hecho cuando: `CogoPointFieldTest` en verde, con coma decimal, DMS y valores rechazados, y la elección compartida con S026.
- [ ] T21. `app`: `InverseViewModel` e `InverseScreen`: resultado, intercambiar y copiar como texto. (RF-5, RF-6, RF-7, RF-8, RF-9)
      Hecho cuando: `InverseViewModelTest` en verde, con unidades del proyecto, puntos coincidentes y sin altura.
- [ ] T22. `app`: `IntersectionViewModel` e `IntersectionScreen`: las tres clases, soluciones con texto «Izquierda», «Derecha», «Primera» o «Segunda», motivos de sin solución y altura opcional. (RF-13 a RF-18)
      Hecho cuando: `IntersectionViewModelTest` en verde con las tres clases, la elección de solución y el aviso de «detrás».
- [ ] T23. `app`: `TraverseViewModel`: punto de inicio, referencia y cierre, tramos (añadir, editar, quitar), cálculo en vivo y cierre. (RF-10, RF-11, RF-12, RF-31)
      Hecho cuando: `TraverseViewModelTest` en verde sin escribir nada en la base.
- [ ] T24. `app`: `TraverseScreen` con la lista de tramos, los puntos calculados, el cierre y los avisos. (RF-10, RF-11, RF-12, RF-31)
      Hecho cuando: `CogoScreensTest` (poligonal) en verde, con `performScrollToNode` en la lista.
- [ ] T25. `app`: el borrador de la poligonal en el ViewModel: guardar al instante, recuperar recalculado, descartar con confirmación y punto de entrada que ya no está. (RF-26, RF-27, RF-28)
      Hecho cuando: `TraverseDraftViewModelTest` en verde, incluida la reapertura tras destruir el ViewModel.
- [ ] T26. `app`: `CogoSaveSheet`: marcar resultados, nombres propuestos y editables, «Guardar puntos», errores de nombre repetido y de fuera del ámbito, salir sin guardar, vaciado del borrador. (RF-19, RF-20, RF-21, RF-23, RF-30)
      Hecho cuando: `CogoSaveTest` en verde con la poligonal y las tres intersecciones, sin crear nada al salir.
- [ ] T27. `app`: rendimiento con 200 tramos y guardado del borrador fuera del hilo principal. (RNF-T13)
      Hecho cuando: `CogoPerformanceTest` en verde (cálculo en menos de 100 ms y sin bloquear el hilo principal más de 16 ms).
- [ ] T28. Accesibilidad y textos: etiquetas, 56 dp, soluciones con texto, es y en, sin literales en código. (RNF-T7, RNF-T8)
      Hecho cuando: `CogoAccessibilityTest` en verde y Lint sin textos sin usar.
- [ ] T29. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files` y la demo en el emulador (inversa, poligonal de cuatro tramos con cierre y los tres tipos de dirección, cerrar la app y recuperar el borrador, las tres intersecciones, guardar y replantear uno).
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo funciona y la hoja de ruta marca S038 como implementada.
