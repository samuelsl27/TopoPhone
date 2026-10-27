# Tareas — Spec 047

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/nmea`: `generar_tipos_mezclados.py` con una grabación sintética de FIX, FLOAT y SINGLE alternados (también una época DGPS) y, calculado aparte, el punto esperado con cada combinación de tipos admitidos; y su test. (RF-8, RF-9, RF-10, RF-18, RNF)
      Hecho cuando: el script genera `fixtures/nmea/sintetico/tipos-mezclados.nmea` y su `.esperado.csv`, y su test de `tools` está en verde.
- [x] T2. `core:model`: `SolutionType`, `AdmittedSolutions` (toggle con confirmación, último tipo, codificación) y `PrecisionControl.admitted` con `check` por tipo (`PrecisionFailure.NOT_FIX` conserva su nombre); los términos nuevos en `docs/glosario.md`. (RF-1 a RF-4, RF-6, RF-7)
      Hecho cuando: `AdmittedSolutionsTest` y `PrecisionControlTest` en verde y `core:model` compila con los usos adaptados.
- [x] T3. `core:survey`: `SolutionTypes.of(SolutionStatus)`. (RF-18)
      Hecho cuando: `SolutionTypesTest` en verde: FIX, FLOAT, SINGLE, DGPS como SINGLE y ESTIMATED y NONE sin tipo.
- [x] T4. `core:survey`: `PointMeasurement` instantánea con `admitted` en lugar de `fixOnly`. (RF-8; D1)
      Hecho cuando: `PointMeasurementAdmittedTest` (instantánea) en verde y los tests de S010 adaptados.
- [x] T5. `core:survey`: `PointMeasurement` promediada con la regla del mejor tipo: descarte de lo acumulado, épocas peores descartadas, contadores, `SolutionDegraded`. (RF-9, RF-10, RF-15)
      Hecho cuando: `PointMeasurementAdmittedTest` (promediada) en verde, incluida la reproducción de `tipos-mezclados.nmea` contra su CSV (≤ 0,1 mm), y los tests de S017 sin cambios.
- [x] T6. `core:model`: `PointQuality` con `admitted` y `epochsDiscardedByType`; `MeasurementResult` los entrega. (RF-15)
      Hecho cuando: los tests de `core:model` y `core:survey` en verde y `PointQuality` sin cambios para quien no los pasa.
- [x] T7. `data`: Room v17 con las columnas `admitted_solutions` y `epochs_discarded_by_type` y `AutoMigration(16, 17)`. (RF-15, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 16 → 17 en verde con puntos antiguos que se leen sin tipos admitidos.
- [x] T8. `data`: guardar y leer los tipos admitidos y las épocas descartadas por tipo, (`NOT_FIX` se sigue guardando igual). (RF-7, RF-15)
      Hecho cuando: `PointRepositoryAdmittedTest` en verde: se guarda y se lee, un punto de antes sin tipos y un `NOT_FIX` guardado.
- [x] T9. `data`: `CollectorPreferences` con `collector_admitted_solutions` y la lectura de `collector_fix_only`. (RF-5, RF-17)
      Hecho cuando: `CollectorPreferencesTest` en verde: clave nueva, antigua `true` y `false`, ninguna y valores desconocidos; la clave antigua se borra al guardar.
- [x] T10. `app`: `StakeoutController`, `StakeoutViewModel` y `LineStakeoutViewModel` con `admitted`; la guía se apaga con `SOLUTION_NOT_ADMITTED`. (RF-12, RF-13)
      Hecho cuando: `StakeoutControllerAdmittedTest` en verde y los tests de S018, S019 y S041 adaptados.
- [x] T11. `app`: `CollectorViewModel` con `admitted`: la comprobación del punto, el aviso «Solución degradada», el modo automático que omite y los datos que se guardan. (RF-6, RF-7, RF-10, RF-11, RF-15)
      Hecho cuando: `CollectorViewModelAdmittedTest` en verde y los tests de S010 y S017 adaptados.
- [x] T12. `app`: el control de precisión con las tres casillas, la confirmación de FLOAT y SINGLE y el último tipo, con sus textos en es y en. (RF-1, RF-3, RF-4, RNF-T7, RNF-T8)
      Hecho cuando: `PrecisionDialogTest` en verde, con zonas de 56 dp y etiquetas de TalkBack.
- [x] T13. `app`: el indicador «No solo FIX» en el recolector y en el replanteo, con icono y texto. (RF-14, RNF-T8)
      Hecho cuando: `AdmittedBadgeTest` en verde y el cambio de tipos se refleja en menos de 200 ms.
- [ ] T14. `app`: los tipos admitidos al medir en la ficha del punto. (RF-16)
      Hecho cuando: `PointDetailsAdmittedTest` en verde: punto con tipos, punto de antes y punto manual.
- [ ] T15. Validación: revisión de los cambios en documentos anteriores (hoja de ruta, glosario, S010, S017, S018), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona (marcar FLOAT con su confirmación, medir, ver el indicador y la ficha, y ver la guía apagarse con SINGLE). La demo de campo queda para el usuario.
