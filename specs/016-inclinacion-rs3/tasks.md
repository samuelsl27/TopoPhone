# Tareas — Spec 016

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/geodesia`: vectores `punta_jalon` de pyproj (`cart` + `topocentric`): antena, inclinación, dirección y altura de antena → punta, en la Península, Baleares y Canarias. (RF-13, RF-25, RF-27)
      Hecho cuando: el script genera `fixtures/geodesia/punta_jalon.csv` y su test de `tools` está en verde.
- [x] T2. `core:geodesy`: `LocalTangentPlane.offset` y `between` (A1). (RF-13, RF-7, RF-8)
      Hecho cuando: `LocalTangentPlaneTest` en verde contra `punta_jalon.csv` (≤ 1 mm; ida y vuelta ≤ 0,1 mm).
- [x] T3. `core:gnss` y `core:model`: `TiltData.rawSentence`; `TiltMode`, `PointTilt`, `TiltAngles`, `PoleVector` y `Measurement.tilt`; `PrecisionControl.maxTiltRad` y `PrecisionFailure.TILT`; `AngleFormat.direction`. (RF-11, RF-20, RF-22)
      Hecho cuando: `EbpEtcParserTest`, `PrecisionControlTest` y `CoordinateFormatTest` en verde con los casos nuevos.
- [x] T4. `core:survey`: `TiltEpoch` (estado 30, limpia, guía, vector del jalón y precisión de la inclinación, A2). (RF-12, RF-21)
      Hecho cuando: `TiltEpochTest` en verde con la tabla de guías y el ejemplo de ETC_rev_3.
- [x] T5. `core:survey`: `PoleTip` (A3), también sin altura. (RF-13, RF-27)
      Hecho cuando: `PoleTipTest` en verde contra `punta_jalon.csv` (≤ 1 mm), con 0° igual al jalón vertical (≤ 0,1 mm) y sin altura.
- [x] T6. `core:survey`: `LiveTip` (A4) y `GroundPointCalculator.fromGround`. (RF-14, RF-16, RF-17, RF-21)
      Hecho cuando: `LiveTipTest` en verde con la tabla de A4, y `GroundPointTest` sigue igual.
- [x] T7. `core:survey`: `PointMeasurement` con la inclinación (A5). (RF-13, RF-14, RF-19, RF-21, RF-22, RF-27)
      Hecho cuando: `PointMeasurementTiltTest` en verde (descarte con `TiltLost` una vez, promediado con el jalón moviéndose ≤ 1 mm, RECEIVER, VERTICAL con ángulos, precisión, ETC en `rawNmea`), y `PointMeasurementTest` sigue igual.
- [x] T8. `core:survey`: `PointCorrections.withPoleHeight` a lo largo del jalón (A6). (RF-25)
      Hecho cuando: `PointCorrectionsTiltTest` en verde en APP y RECEIVER (≤ 1 mm), y `PointCorrectionsTest` sigue igual.
- [x] T9. `core:survey`: `TiltModeWizard` (A7). (RF-5, RF-6, RF-7, RF-8, RF-9)
      Hecho cuando: `TiltModeWizardTest` en verde con los dos modos, los tres motivos de no concluyente, el jalón de 0 m y la decisión en la misma época.
- [x] T10. `data`: `TiltPreferences` (modo por receptor, `replay`, comprobado y detectado) y la inclinación máxima en `CollectorPreferences`; las claves `tilt_` no viajan en las copias. (RF-1, RF-3, RF-4, RF-10, RF-20)
      Hecho cuando: `TiltPreferencesTest`, `CollectorPreferencesTest` y `SettingsArchiveTest` en verde.
- [x] T11. `data`: columnas de la inclinación en `points`, migración 7 → 8 con `PointsV8`, mapeo y `forExport`. (RF-22)
      Hecho cuando: `TopoPhoneDatabaseTest` (7 → 8: medido `VERTICAL`, manual sin modo), `PointRepositoryTest` y `PointRepositoryExportTest` en verde, con el esquema 8 exportado.
- [x] T12. `receiver` y `app`: `Alerts.tiltLost`, `ReceiverConnections.currentAddress` y `CurrentTilt` (clave, modo, asistente abierto y revisión, A8, A9). (RF-1, RF-2, RF-19, RF-28)
      Hecho cuando: `CurrentTiltTest` en verde con un receptor, una reproducción, la revisión y el asistente.
- [x] T13. `app`: entrada "Inclinación" del menú del receptor, ruta y pantalla con los tres modos, el resumen y el aviso de RF-4. (RF-1, RF-3, RF-4)
      Hecho cuando: `TiltScreenTest` y `ReceiverMenuTest` en verde, con TalkBack.
- [x] T14. `app`: el asistente en la pantalla "Inclinación" (`TiltViewModel` con `TiltModeWizard`). (RF-5, RF-6, RF-7, RF-8, RF-9, RF-10)
      Hecho cuando: `TiltWizardScreenTest` en verde: qué falta, pasos, propuesta, confirmar, repetir y no concluyente.
- [x] T15. `app`: tarjeta "Inclinación" en la pantalla Estado. (RF-11)
      Hecho cuando: `StatusScreenTest` en verde con el estado, el aviso, los ángulos en el formato de los proyectos nuevos y las precisiones.
- [x] T16. `app`: `TiltGuideBanner` con los siete textos y la ilustración propia del ocho. (RF-12)
      Hecho cuando: `TiltGuideBannerTest` en verde, con la descripción de la ilustración para TalkBack.
- [x] T17. `app`: la punta en Coordenadas con "Punta sin compensar". (RF-16, RF-17)
      Hecho cuando: `CoordinatesCardTest` y `ReceiverViewModelTest` en verde en APP, RECEIVER, VERTICAL y sin modo.
- [x] T18. `app`: la punta en el panel de posición y en el rover, con el círculo de la precisión de la inclinación. (RF-16, RF-17, RF-21)
      Hecho cuando: `ProjectViewTest` y `RoverLayerTest` en verde con la punta, el aviso y el radio del círculo.
- [ ] T19. `app`: bloqueos del recolector (sin modo y fuera del estado 30, con la guía y el enlace) y la altura del jalón del receptor. (RF-2, RF-12, RF-15, RF-18)
      Hecho cuando: `CollectorViewModelTest` y `CollectorPanelTest` en verde con los dos bloqueos, la guía y la etiqueta de la altura.
- [ ] T20. `app`: medida con inclinación en el recolector: `TiltLost` con sonido y vibración, inclinación máxima en el control de precisión, precisión en directo y cancelación al cambiar el modo. (RF-19, RF-20, RF-21, RF-28)
      Hecho cuando: `CollectorViewModelTest` y `CollectorDialogsTest` en verde, con `TILT` en el aviso de fuera de tolerancia.
- [ ] T21. `app`: guardar la inclinación con el punto y anotar en el log los avisos de la inclinación (S014/RF-5). (RF-22)
      Hecho cuando: `CollectorViewModelTest` guarda un punto APP, uno RECEIVER y uno VERTICAL con sus datos, y los tests del log en verde.
- [ ] T22. `app`: ficha del punto con la tarjeta "Inclinación", el modo y la punta rotulada en RECEIVER; corrección del jalón a lo largo del jalón. (RF-23, RF-25, RF-26)
      Hecho cuando: `PointDetailsTiltTest` en verde, también corrigiendo el jalón de un punto APP.
- [ ] T23. `core:formats`, `app` y `tools`: las ocho columnas del CSV, sus nombres en Exportar, los ficheros dorados y `test_csv_exportado.py`. (RF-24)
      Hecho cuando: `CsvExportGoldenTest`, `ExportPreferencesTest`, `ExportScreenTest` y `test_csv_exportado.py` en verde con 43 columnas.
- [ ] T24. `tools/nmea`: `generar_inclinacion.py` con `rs3-inclinacion`, `rs3-asistente-app` y `rs3-asistente-receptor`, y lo esperado de cada uno. (RF-7, RF-8, RF-13, RF-19)
      Hecho cuando: los ficheros se generan y su test de `tools` está en verde.
- [ ] T25. Rendimiento y reproducción: `TiltReplayTest` con las tres reproducciones; `LiveTip` y `PointMeasurement` a 10 Hz en menos de 1 ms por época. (RNF, RF-7, RF-8, RF-13, RF-16, RF-19)
      Hecho cuando: los tests de reproducción y de rendimiento en verde, y `FullRs3ReplayTest` sigue igual.
- [ ] T26. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo quedan para el usuario.
