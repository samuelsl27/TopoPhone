# Tareas — Spec 003

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero.

- [x] T1. Glosario (época, sesión, reproducción, barra de estado, satélites en vista, velocidad y rumbo sobre el suelo, posición de la base, estado de la inclinación) y, en `core:gnss`, `Constellation`, `NmeaTime`, `RmcParser`, `VtgParser` y `ZdaParser`. (RF-3, RF-4, RF-7)
      Hecho cuando: `RmcVtgZdaParserTest` en verde.
- [ ] T2. `core:gnss`: `GsaParser` y `GsvParser` (talker por constelación, NMEA 4.10 y 4.11 con sistema y señal). (RF-1, RF-2, RF-7)
      Hecho cuando: `GsaGsvParserTest` en verde.
- [ ] T3. `core:gnss`: `EbpParser` y `EtcParser` (estados y avisos de ETC_rev_3). (RF-5, RF-6, RF-7)
      Hecho cuando: `EbpEtcParserTest` en verde.
- [ ] T4. `core:gnss`: `Epoch` y `EpochAssembler` (agrupación, cierre por hora o 150 ms, desorden, medianoche, fecha, reinicio). (RF-8–RF-11)
      Hecho cuando: `EpochAssemblerTest` en verde.
- [ ] T5. `receiver`: `ReceiverSession` con épocas (época en el estado, "Sin datos" a 2 s sin época, tic de 25 ms, la latencia de la GGA no cambia). (RF-12)
      Hecho cuando: `ReceiverSessionEpochTest` y los tests de S002 en verde.
- [ ] T6. `app`: tarjeta Estado con fecha y hora local o "fecha no disponible" y satélites en vista; las tarjetas no muestran solución ni coordenadas con "Sin datos". (RF-13, RF-14, RF-15)
      Hecho cuando: `StatusCardTest` y `CoordinatesCardTest` en verde.
- [ ] T7. `core:designsystem` + `app`: tono `SIMULATION` y componente `StatusBar` sin estado (Sin receptor, solución, satélites, correcciones, Sin datos, Grabando, Simulación, inclinación). (RF-17–RF-22)
      Hecho cuando: `StatusBarTest` y `StatusPaletteTest` en verde.
- [ ] T8. `app`: `StatusBarViewModel`, la barra en `SectionScaffold` de las tres secciones y la pulsación que abre Receptor. (RF-16, RF-23)
      Hecho cuando: `StatusBarNavigationTest` y los tests de navegación de S001 en verde.
- [ ] T9. `receiver`: `SessionLogStore` v2 (carpeta por sesión, `session.properties`, partes de 16 MB, migración de los ficheros de S002) y grabación de las correcciones enviadas al receptor. (RF-24, S002/RF-42)
      Hecho cuando: `SessionLogStoreTest` y `ReceiverSessionRecordingTest` en verde.
- [ ] T10. `receiver`: límite de espacio (500 MB, poda de las más antiguas, partes de la sesión en curso), parada con menos de 100 MB libres y vaciado como máximo cada segundo. (RF-25, RF-27, RF-28, S002/RF-44)
      Hecho cuando: `SessionLogStoreTest` en verde con los casos de límite.
- [ ] T11. `data` + `app`: preferencia `session_space_mb` y fila "Espacio para sesiones" en Ajustes, aplicada al momento. (RF-26)
      Hecho cuando: `ReceiverPreferencesTest` y `SessionSpaceSettingTest` en verde.
- [ ] T12. `app`: pantalla Sesiones (lista con inicio, duración, tamaño y receptor; compartir las partes y las correcciones; borrar con confirmación salvo la sesión en curso), abierta desde la tarjeta Sesión. (RF-29, RF-30, RF-31)
      Hecho cuando: `SessionsScreenTest` en verde.
- [ ] T13. `receiver` + `app`: importar un fichero NMEA (selector de documentos, validación por contenido, límite de 200 MB, marcado "Importada"). (RF-33, RF-34)
      Hecho cuando: `SessionImportTest` en verde.
- [ ] T14. `core:gnss`: `ReplayPacer` (ritmo por hora UTC acumulado, ×1/×5, cambio de velocidad, huecos de 10 s, medianoche, bucle). (RF-36, RF-37, RF-38, RF-39)
      Hecho cuando: `ReplayPacerTest` en verde, con duración a ×1 dentro del 1 %.
- [ ] T15. `receiver`: `ReplayTransport` y sesión en modo simulación (estados `Replaying`/`ReplayFinished`, reinicio de épocas en el bucle, sin grabación, NTRIP no disponible). (RF-32, RF-39, RF-40, RF-44, RF-45)
      Hecho cuando: `ReplayTransportTest` en verde.
- [ ] T16. `app`: reproducir desde la lista y controles en la tarjeta Conexión (velocidad, bucle, Detener, "Reproducción terminada", volver a reproducir) y exclusión con el receptor (confirmación). (RF-35, RF-37, RF-40, RF-41, RF-42, RF-43)
      Hecho cuando: `ReplayControlsTest` y `ReceiverViewModelReplayTest` en verde.
- [ ] T17. `tools` + `app`: sesión sintética completa del RS3 con sus épocas esperadas (pyproj) y reproducción de principio a fin a ×5 por la sesión y el ViewModel reales. (RF-1–RF-11, RF-36)
      Hecho cuando: `test_rs3_completo.py` y `FullRs3ReplayTest` en verde.
- [ ] T18. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador (importar y reproducir) y demo con el RS3 real (barra de estado, grabación con correcciones, lista, compartir y reproducir). (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y las dos demos funcionan.
