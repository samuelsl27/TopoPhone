# Tareas — Spec 015

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [ ] T1. `tools/geodesia`: vectores de pyproj de geodésicas ↔ ECEF (España, Canarias y la estación AVL13M) y de líneas base. (RF-13, RF-14)
      Hecho cuando: el script genera los CSV de referencia y su test de `tools` está en verde.
- [ ] T2. `core:geodesy`: `Ecef` (directa e inversa, A5). (RF-13, RF-14)
      Hecho cuando: `EcefTest` en verde contra los vectores de T1 (≤ 1·10⁻⁹°, h ≤ 1 mm, ida y vuelta ≤ 0,1 mm).
- [ ] T3. `core:gnss`: `RtcmBits` y `onFrame` en `RtcmFramer`. (RF-8, RF-12)
      Hecho cuando: `RtcmBitsTest` en verde y `RtcmFramerTest` entrega 113 cargas de la grabación real con las mismas estadísticas.
- [ ] T4. `core:gnss`: `ReferenceStationDecoder` (1005/1006, A1). (RF-12, RF-13)
      Hecho cuando: `ReferenceStationDecoderTest` en verde con la estación 6 de la grabación real y un 1005 sintético.
- [ ] T5. `core:gnss`: `MsmDecoder` (CNR de los MSM 4 a 7, A2). (RF-8, RF-10)
      Hecho cuando: `MsmDecoderTest` en verde con celdas de la grabación real, MSM4/6/7 sintéticos, MSM1 a 3 y cargas cortas.
- [ ] T6. `core:gnss`: `Band` y `SatelliteNumber` (A3, A4). (RF-6, RF-7, RF-8)
      Hecho cuando: `BandTest` y `SatelliteNumberTest` en verde con las tablas de NMEA 4.11 y RTCM.
- [ ] T7. `core:gnss`: `SkyView` (lo último de GSA y GSV, DOP y recuentos, A8). (RF-2, RF-3, RF-6)
      Hecho cuando: `SkyViewTest` en verde, también con épocas de 5 Hz sin GSA ni GSV.
- [ ] T8. `core:survey`: `BaseTracker` y `Baseline` (A6, A7). (RF-10, RF-11, RF-12, RF-14, RF-16, RF-17)
      Hecho cuando: `BaseTrackerTest` y `BaselineTest` en verde (línea base ≤ 1 mm frente a pyproj).
- [ ] T9. `receiver`: `ReceiverSession` con `sky` y `base` en el estado, el `BaseTracker` alimentado por las EBP y las correcciones, y `reset` al conectar. (RF-8, RF-11, RF-12, RF-17)
      Hecho cuando: `ReceiverSessionBaseTest` en verde con la grabación real por un conector falso y con EBP en el NMEA.
- [ ] T10. `app`: tarjetas "Posición" y "Satélites" (DOP y recuentos por constelación) en la pantalla Estado. (RF-1, RF-2, RF-3)
      Hecho cuando: `StatusScreenTest` en verde con el formato angular de los proyectos nuevos.
- [ ] T11. `app`: skyplot (A8) con su descripción para TalkBack. (RF-4, RF-5, RF-6)
      Hecho cuando: `SkyplotTest` en verde.
- [ ] T12. `app`: gráfico de SNR del rover y de la base (A9). (RF-7, RF-8, RF-9, RF-10)
      Hecho cuando: `SnrChartTest` en verde con los tramos, los valores, las filas solo de base y "sin banda".
- [ ] T13. `app`: tarjeta "Base" (fuente, estación, altura de antena, línea base, base no disponible). (RF-13, RF-14, RF-16)
      Hecho cuando: los tests de la tarjeta en `StatusScreenTest` en verde.
- [ ] T14. `app`: `BaseNotices` y los avisos "Línea base larga" y "La base ha cambiado" en la pantalla Estado, con su anotación en el log. (RF-15, RF-17, S014/RF-5)
      Hecho cuando: los tests de los avisos y `ReceiverLogRecorderTest` en verde.
- [ ] T15. `app`: avisos en el recolector y reinicio de la medida cuando cambia la base (A10). (RF-15, RF-17, RF-18)
      Hecho cuando: los tests nuevos de `CollectorViewModelTest` en verde.
- [ ] T16. `app`: la base en el mapa del proyecto (`showBase` e icono propio). (RF-19)
      Hecho cuando: `BaseLayerTest` en verde.
- [ ] T17. `core:model` y `data`: `PointBase`, columnas de la base y migración 6 → 7. (RF-20)
      Hecho cuando: `TopoPhoneDatabaseTest` (paso 6 → 7 y esquema 7) y la ida y vuelta de un punto con base y sin base en verde.
- [ ] T18. `app`: el recolector guarda la base con el punto. (RF-20)
      Hecho cuando: el test de `CollectorViewModelTest` que guarda un punto con su base y su línea base está en verde.
- [ ] T19. `app`: tarjeta "Base" en la ficha del punto. (RF-21)
      Hecho cuando: `PointDetailsBaseTest` en verde, también con la base fuera del SC.
- [ ] T20. `core:formats` y `tools`: las siete columnas de la base en el CSV, con los ficheros dorados y la comprobación de Python. (RF-22)
      Hecho cuando: `CsvExportGoldenTest`, `CsvExportTest` y `test_csv_exportado.py` en verde con 35 columnas.
- [ ] T21. `app`: la exportación calcula el Este, Norte y altura de la base, y los nombres de las columnas nuevas en la pantalla Exportar. (RF-22)
      Hecho cuando: `ExportViewModelTest` en verde, también con un formato personalizado guardado antes.
- [ ] T22. Rendimiento y reproducción: 64 satélites con 3 bandas en menos de 1 s; exportar 10 000 puntos con base; `FullRs3ReplayTest` con la base de la EBP. (RNF, RF-11, RF-14)
      Hecho cuando: los tests de rendimiento y de reproducción en verde.
- [ ] T23. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo quedan para el usuario.
