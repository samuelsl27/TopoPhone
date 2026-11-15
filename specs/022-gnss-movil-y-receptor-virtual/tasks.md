# Tareas — Spec 022

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`), marcar la casilla y un commit `S022 Tn: …`.

- [x] T1. `core:model`: `PositionSource`, `ReceiverKey.phone/virtual` (`isPhone`, `isVirtual`) y `PointQuality.source`. (RF-1, RF-11, RF-12)
      Hecho cuando: `ReceiverKeyTest` y `PositionSourceTest` en verde y el resto de `core:model` intacto.
- [x] T2. `data`: Room v35 (`points.position_source`, `AutoMigration(34, 35)`, esquema `35.json`), lectura y escritura con su `fromReplay` compatible y copia de seguridad. (RF-11, RF-19, RNF-T6)
      Hecho cuando: el test de migración v34 a v35 de `TopoPhoneDatabaseTest`, `PointsSourceTest` y la copia completa con la columna en verde, y los puntos anteriores siguen siendo `RECEIVER` o `REPLAY`.
- [x] T3. `core:gnss`: `NmeaWriter` (GGA, GST, GSA, GSV, ZDA, EBP y ETC con suma de control y `Locale.ROOT`). (RF-15)
      Hecho cuando: `NmeaWriterTest` en verde: cada frase escrita se lee con su analizador y devuelve los mismos valores, y la suma de control coincide con la de las frases de `fixtures/nmea/`.
- [x] T4. `core:gnss`: `Scenario`, `ScenarioState` y `ScenarioPlayer` con los escenarios «FIX estable» y «Paseo en línea recta». (RF-13, RF-14, RF-15)
      Hecho cuando: `ScenarioPlayerTest` en verde: dos ejecuciones con el mismo origen y hora de inicio dan los mismos bytes, la hora UTC crece de 1 s en 1 s, el origen cambia las coordenadas y el paseo avanza 1 m/s.
- [x] T5. `core:gnss`: los otros cinco escenarios (pérdida de FIX, FLOAT y SINGLE, corte de datos, cambio de base, inclinación en ocho) y el coste de generar un escenario. (RF-14, RF-15)
      Hecho cuando: un test por escenario en verde con los instantes de sus eventos (solución, ausencia de frases, EBP, ETC) y generar 180 s tarda menos de 100 ms.
- [x] T6. `receiver`: `ReceiverTransport.positionSource`, `noDataTimeoutMs` y `recordsSession`, y `VirtualReceiverTransport` (1 Hz y ×5, cambio de velocidad, fin de escenario, cierre). (RF-12, RF-16, RF-17, RNF-T10)
      Hecho cuando: `VirtualReceiverTransportTest` en verde con reloj falso y los tests de transportes de S002 a S021 intactos.
- [ ] T7. `receiver`: `ReceiverSession` con `source` y `scenario` en el snapshot, `ScenarioFinished`, sin grabación ni NTRIP con el virtual y con el móvil, y el plazo de «Sin datos» del transporte. (RF-8, RF-9, RF-18, RF-20, RNF-T3)
      Hecho cuando: `ReceiverSessionSourcesTest` en verde y los de sesión de S002 a S021 intactos.
- [ ] T8. `receiver`: `LocationSource`, `PhoneGnssTransport` y `AndroidLocationSource` (SINGLE siempre, altura elipsoidal sin geoide, σ de la GST, sin vertical, `isMock`, sin correcciones). (RF-5, RF-6, RF-7, RF-8, RF-9)
      Hecho cuando: `PhoneGnssTransportTest` en verde con una fuente falsa: declara 1 m y sale SINGLE con esa precisión horizontal, sin vertical no hay σ de la altura, a los 3 s sin ubicación hay «Sin datos» y una ubicación simulada cambia la fuente.
- [ ] T9. `app`: `ReceiverConnections` con las claves `phone` y `virtual` (conectar, desconectar, repetir el escenario, no reconectar al abrir la app, no guardar el último receptor). (RF-1, RF-12, RF-17)
      Hecho cuando: `ReceiverConnectionsSourcesTest` en verde y los de Bluetooth y Wi-Fi intactos.
- [ ] T10. `app`: permisos y servicio del móvil: `ACCESS_FINE_LOCATION` y `FOREGROUND_SERVICE_LOCATION` en el manifiesto, petición al elegirlo, denegación con Ajustes, ubicación apagada con su acceso, y el servicio con el tipo `location` solo con el móvil. (RF-2, RF-3, RF-4, RNF-T1, RNF-T2)
      Hecho cuando: `PhonePermissionsTest` en verde y el manifiesto combinado de `assembleDebug` declara los permisos y quita solo los de la Wi-Fi.
- [ ] T11. `app`: filas «GNSS del móvil» y «Receptor virtual» en la lista (también con el Bluetooth apagado) y en la hoja de «Añadir receptor». (RF-1, RF-12)
      Hecho cuando: `ReceiverListSourcesTest` en verde y los tests de la lista de S004, S005 y S021 intactos.
- [ ] T12. `app`: `VirtualReceiverDialog` (escenario con descripción y duración, origen del proyecto o coordenadas, velocidad) y los textos de los siete escenarios. (RF-13, RF-14)
      Hecho cuando: `VirtualReceiverDialogTest` en verde: el centro del proyecto por defecto, unas coordenadas escritas, sin proyecto y sin coordenadas no deja continuar.
- [ ] T13. `app`: cabecera e indicador de estado con «Simulado · escenario» o «GNSS del móvil · SINGLE», cambio de velocidad y la hoja de fin de escenario (repetir, elegir otro, desconectar). (RF-16, RF-17, RNF-T8)
      Hecho cuando: `SourceHeaderTest` en verde, con el texto y no solo el color, y los de la cabecera y la barra de estado de S005 intactos.
- [ ] T14. `app`: el móvil sin NTRIP, inclinación ni base (con el motivo al pedirlos), el virtual sin NTRIP, y el aviso de ubicación simulada por otra app. (RF-7, RF-9, RF-18)
      Hecho cuando: `SourceLimitsTest` en verde y los tests de Correcciones y de inclinación de S007 y S016 intactos.
- [ ] T15. `app`: equipo del móvil (sin jalón ni desplazamiento al centro de fase) y `measuringSource` en el recolector y en el control de base: el punto se guarda con su fuente y marcado simulado con el virtual, con una ubicación simulada y con una reproducción. (RF-6, RF-7, RF-10, RF-11, RF-19)
      Hecho cuando: `CollectorSourceTest` en verde: con «solo FIX» el móvil no mide sin confirmar, y los tests del recolector de S010, S017 y S047 y de base de S020 intactos.
- [ ] T16. `app`: la fuente en la ficha del punto y en el mapa/lista donde ya se marca lo de reproducción. (RF-11, RF-19, RF-7)
      Hecho cuando: `PointSourceDetailsTest` en verde y la columna «Replay» de «Todas las columnas» sale verdadera con el virtual y con la ubicación simulada (`CsvExportTest`).
- [ ] T17. `app`: replanteo con el virtual (paseo) y con el móvil (SINGLE con la guía apagada o admitida según S047). (RF-14, RF-16)
      Hecho cuando: `StakeoutSourcesTest` en verde y los tests de replanteo de S018, S027 y S054 intactos.
- [ ] T18. `app`: accesibilidad y textos: todo control tiene etiqueta y mide al menos 56 dp, las pantallas salen en inglés con el idioma en inglés, las mismas claves en es y en y ningún literal en el código. (RNF-T7, RNF-T8)
      Hecho cuando: `SourcesAccessibilityTest` en verde.
- [ ] T19. Validación final: recorrer la spec RF por RF, `rf_report.py`, `check`, `pytest` de `tools`, `forbidden_files`, `assembleDebug` y la demo en el emulador (cada escenario en el recolector y el replanteo, y el móvil con `adb emu geo fix`); `plan.md` con «Cambios al implementar» y la validación, hoja de ruta con S022 implementada (Room v35). (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y la demo funciona.
