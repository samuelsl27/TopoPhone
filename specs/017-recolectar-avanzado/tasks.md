# Tareas — Spec 017

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/promediado`: `generar_promediado.py` con 36.000 épocas simuladas y lo esperado (media, `dev_h`, `dev_v` y estabilidad) calculado con pyproj y `math.fsum`, en EPSG:25830 y 4083 (REGCAN95 / UTM 28). (RF-3, RF-4, RF-5, RF-12)
      Hecho cuando: el script genera `fixtures/promediado/` y su test de `tools` está en verde.
- [x] T2. `core:model`: `DurationText` (`m:ss`), `AutoTrigger` y `CollectorSettings` con el automático y `AVERAGING_SECONDS` de 1 a 3600. (RF-1, RF-16, RF-17)
      Hecho cuando: `DurationTextTest` y `CollectorSettingsTest` en verde, con los rangos, la conversión de la distancia a la unidad del proyecto y `CollectorSettings` por defecto igual que antes.
- [x] T3. `core:model`: `PointQuality` con el tiempo promediado, las desviaciones típicas y `auto`, y `Measurement.nmeaChunks`. (RF-12, RF-14)
      Hecho cuando: los tests de `core:model` en verde y `Point.measured` sin cambios para quien no pasa los datos nuevos.
- [x] T4. `core:survey`: `AverageStats` (Welford, estabilidad, muestra y escala del gráfico). (RF-3, RF-4, RF-5, RF-12)
      Hecho cuando: `AverageStatsTest` en verde contra `fixtures/promediado/` (≤ 0,1 mm), con `n < 2`, el minuto de la estabilidad y la muestra de 2.000.
- [x] T5. `core:survey`: `PointMeasurement` sin retener épocas (A1), con el mismo resultado. (RF-12)
      Hecho cuando: `PointMeasurementTest` y `PointMeasurementTiltTest` siguen en verde sin tocarlos y `PointMeasurementStreamingTest` comprueba que una época usada no queda retenida.
- [x] T6. `core:survey`: `PointMeasurement` con las estadísticas y `MeasurementResult` con el tiempo promediado y las desviaciones típicas. (RF-12, RF-3, RF-4)
      Hecho cuando: `PointMeasurementStreamingTest` en verde con una medida de 36.000 épocas frente a `fixtures/promediado/` y con los instantáneos sin datos nuevos.
- [x] T7. `core:survey`: pausa, reanudación y pregunta de 5 min de `PointMeasurement` (A3). (RF-8, RF-9, RF-10)
      Hecho cuando: `PointMeasurementPauseTest` en verde: pausa a los 2 s, el tiempo del corte no suma, `LongPause` a los 5 min y repetida con "Seguir esperando", y la instantánea sigue cancelándose.
- [x] T8. `core:survey`: `finishNow()` de `PointMeasurement` (A4). (RF-6, RF-7)
      Hecho cuando: `PointMeasurementPauseTest` en verde con "Terminar" con épocas, sin ninguna y en pausa.
- [x] T9. `core:survey`: `AutoMeasure` (A5). (RF-19, RF-20, RF-21, RF-24, RF-25, RF-27, RF-28, RF-32)
      Hecho cuando: `AutoMeasureTest` en verde con el primer punto inmediato, los dos disparos, el punto sin terminar, un solo aviso, el reintento por distancia, la pausa en los dos disparos, `stop()` y el cambio de día.
- [x] T10. `data`: columnas de `points`, tabla `point_nmea`, migración 8 → 9, `NmeaChunks` y el mapeo. (RF-12, RF-14)
       Hecho cuando: `TopoPhoneDatabaseTest` (8 → 9: datos conservados, columnas a NULL y `nmea_chunks` a 0), `NmeaChunksTest` y `PointRepositoryExportTest` en verde, con el esquema 9 exportado.
- [x] T11. `data`: guardar el punto con sus tramos en una transacción, `nmeaChunk` y las copias con `point_nmea`. (RF-12)
       Hecho cuando: `PointRepositoryNmeaTest` (12 MB en menos de 3 s, filas de hasta 256.000 caracteres, lectura tramo a tramo, cascada y sin restos si falla) y `DatabaseSnapshotTest` en verde.
- [x] T12. `data`: las claves del modo automático en `CollectorPreferences`. (RF-1, RF-16, RF-17)
       Hecho cuando: `CollectorPreferencesTest` y `SettingsArchiveTest` en verde.
- [x] T13. `core:formats` y `app`: las cuatro columnas del CSV y sus nombres en Exportar, con los ficheros dorados. (RF-14)
       Hecho cuando: `CsvExportGoldenTest`, `ExportPreferencesTest` y `ExportScreenTest` en verde con 47 columnas y `test_csv_exportado.py` en verde.
- [x] T14. `receiver`: `Alerts.measurementPaused()`, `pointOmitted()` y `measurementRestarted()`. (RF-8, RF-24, RF-30)
       Hecho cuando: el test de `VibrationAlerts` en verde con los tres avisos distintos de los actuales.
- [x] T15. `app`: el promediado largo en `CollectorViewModel`: tiempo en `m:ss`, flujo de estadísticas a 5 Hz y "Terminar". (RF-1 a RF-7)
       Hecho cuando: `CollectorViewModelAdvancedTest` en verde con la `ReceiverSession` real y las épocas de una en una, y `CollectorViewModelTest` sin cambios.
- [x] T16. `app`: pausa, reanudación, pregunta de 5 min y cambio de base con el tiempo descartado en `CollectorViewModel`. (RF-8 a RF-11, RF-30)
       Hecho cuando: `CollectorViewModelAdvancedTest` en verde con el corte corto, la pregunta con un reloj falso, que la medida sigue sin nadie recogiendo `state`, y el reinicio por la base.
- [x] T17. `app`: guardar el punto con el tiempo promediado, las desviaciones típicas, `auto` y los tramos, y anotar en el log los sucesos. (RF-12, RF-31)
       Hecho cuando: `CollectorViewModelAdvancedTest` guarda un punto promediado y uno de 12 MB con sus datos, y los tests del log en verde.
- [x] T18. `app`: panel del recolector con el tiempo en `m:ss`, las estadísticas, el gráfico de dispersión, "Terminar", el banner de pausa y la pregunta de 5 min. (RF-1 a RF-5, RF-8, RF-10)
       Hecho cuando: `CollectorPanelAdvancedTest` en verde, con la descripción del gráfico para TalkBack y el anuncio de cada minuto.
- [x] T19. `app`: `AutoCollector`: nombres libres, control de precisión, guardado, omisiones, log y avisos. (RF-15, RF-18, RF-19 a RF-28, RF-31, RF-33)
       Hecho cuando: `AutoCollectorTest` y `CollectorViewModelAdvancedTest` en verde con el modo automático por tiempo y por distancia y un punto omitido.
- [x] T20. `app`: los controles del modo automático en el panel, con los ajustes bloqueados, el estado y el cambio de modo de inclinación. (RF-15, RF-16, RF-17, RF-26, RF-29, RF-32)
       Hecho cuando: `CollectorPanelAdvancedTest` y `CollectorViewModelAdvancedTest` en verde con "Iniciar", "Parar", los contadores y el modo de inclinación cambiado en marcha.
- [x] T21. `app`: la ficha con el tiempo promediado, las desviaciones típicas, "medido en automático" y las frases NMEA por tramos. (RF-13)
       Hecho cuando: `PointDetailsAveragedTest` en verde con un punto con datos, uno anterior sin ellos y uno de varios tramos.
- [x] T22. `tools/nmea`: `generar_avanzado.py` con `rs3-promediado-largo`, `rs3-corte` y `rs3-traza`, y lo esperado de cada una. (RF-12, RF-19, RF-20, RF-21)
       Hecho cuando: los ficheros se generan y su test de `tools` está en verde.
- [ ] T23. Reproducciones y rendimiento: `AdvancedReplayTest` con las tres y `AdvancedPerformanceTest` (36.000 épocas a 10 Hz en menos de 1 ms por época; estadísticas a 5 Hz en menos de 16 ms). (RNF, RF-12, RF-19, RF-20, RF-21)
       Hecho cuando: los tests de reproducción y de rendimiento en verde y `FullRs3ReplayTest` y `TiltReplayTest` siguen igual.
- [ ] T24. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
       Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo quedan para el usuario.
