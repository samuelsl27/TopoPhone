# Tareas — Spec 002

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y empieza por sus tests. Tras cada una, `./gradlew check` en verde y commit.

- [x] T1. Módulos `core:gnss`, `core:ntrip`, `core:geodesy`, `core:survey` y `receiver` con sus convenciones, y reglas de dependencias comprobadas. (RF: —)
      Hecho cuando: `./gradlew check` en verde y `checkModuleRules` sin infracciones con los módulos nuevos.
- [x] T2. `core:gnss`: `NmeaFramer` (suma de control, cualquier talker, cortes y binario intercalado). (RF-11)
      Hecho cuando: `NmeaFramerTest` en verde.
- [x] T3. `core:gnss`: `GgaParser`, `GstParser` y `SolutionStatus`. (RF-12, RF-13, RF-14, RF-15)
      Hecho cuando: `GgaParserTest` y `GstParserTest` en verde, con campos vacíos y todas las calidades.
- [ ] T4. `tools/geodesia`: pyproj, `descargar_rejillas.py` (SHA-256) y `generar_vectores.py` → `fixtures/geodesia/*.csv`; rejilla EGM08-REDNAP como recurso de `core:geodesy`. (RF-36, RF-38)
      Hecho cuando: los CSV generados son reproducibles y la rejilla está versionada con su SHA-256 comprobado.
- [ ] T5. `core:geodesy`: `Ellipsoid`, `TransverseMercator` (Krüger, orden 6) y `Utm` (husos 29–31). (RF-36, RF-37)
      Hecho cuando: `UtmTest` coincide con pyproj a ≤ 1 mm en todos los vectores.
- [ ] T6. `core:geodesy`: `GeoTiffGrid` (DEFLATE, predictor 3, teselas o tiras) y `GeoidModel` bilineal. (RF-38, RF-39)
      Hecho cuando: `GeoidModelTest` coincide con pyproj a ≤ 1 mm y fuera de la rejilla da "no disponible".
- [ ] T7. `core:model`: `AngleFormat` (DMS y DD) y `LengthFormat`. (RF-34, RF-35, RF-36, RF-38)
      Hecho cuando: `AngleFormatTest` en verde (es/en, hemisferios, redondeo de 59,999995″).
- [ ] T8. `core:survey`: `VerticalPole` y `GroundPointCalculator`. (RF-32, RF-33, RF-37, RF-39, RF-40, RF-41)
      Hecho cuando: `GroundPointCalculatorTest` en verde.
- [ ] T9. `core:ntrip`: `NtripRequest`, `NtripResponseParser` y `ChunkedDecoder`. (RF-21, RF-22)
      Hecho cuando: `NtripResponseParserTest` en verde (ICY, HTTP 200, chunked, 401, `SOURCETABLE`, 404).
- [ ] T10. `core:ntrip`: `NtripClient` (caster falso: reenvío, GGA cada 5 s, sin datos 10 s, corte, inaccesible) y `NtripStats`. (RF-19, RF-20, RF-23, RF-24, RF-25)
      Hecho cuando: `NtripClientTest` en verde.
- [ ] T11. `receiver`: `ReceiverTransport`, transporte falso, `BluetoothSppTransport` y `BondedDevices`. (RF-1, RF-5)
      Hecho cuando: compila, y `BondedDevicesTest` (Robolectric) en verde.
- [ ] T12. `receiver`: `ReceiverSession` (estados, plazos, pérdida, reenvío de RTCM, NTRIP sin receptor). (RF-5–RF-10, RF-19, RF-28)
      Hecho cuando: `ReceiverSessionTest` en verde con tiempo virtual.
- [ ] T13. `receiver`: `SessionLogStore` (registro tal cual y retención de 10). (RF-42, RF-44)
      Hecho cuando: `SessionLogStoreTest` en verde.
- [ ] T14. `data`: `ReceiverPreferences` (servidor, puerto, punto de montaje, usuario y jalón; nunca la contraseña). (RF-26, RF-27, RF-30)
      Hecho cuando: `ReceiverPreferencesTest` en verde.
- [ ] T15. `app`: `ReceiverViewModel` y tarjeta Conexión (permisos, Bluetooth apagado, lista, estados, vibración). (RF-1–RF-10)
      Hecho cuando: `ReceiverConnectionCardTest` en verde.
- [ ] T16. `app`: tarjeta Estado. (RF-12–RF-17)
      Hecho cuando: `StatusCardTest` en verde.
- [ ] T17. `app`: tarjeta NTRIP. (RF-18–RF-28)
      Hecho cuando: `NtripCardTest` en verde.
- [ ] T18. `app`: tarjeta Coordenadas con la altura del jalón. (RF-29–RF-41)
      Hecho cuando: `CoordinatesCardTest` en verde.
- [ ] T19. `app`: compartir sesión (FileProvider) y atribución del IGN en Licencias. (RF-43, RF-45)
      Hecho cuando: `ShareSessionTest` y `LicensesScreenTest` en verde.
- [ ] T20. Reproducción de principio a fin de una sesión NMEA sintética (fichero → sesión → coordenadas) y latencia < 300 ms. (RF-11–RF-15, RF-32–RF-38)
      Hecho cuando: `EndToEndReplayTest` en verde.
- [ ] T21. Validación: recorrer la spec RF por RF, `rf_report`, demo con el RS3 real y puerta G0 en campo (protocolo (a)+(c), acta). (Todos)
      Hecho cuando: cada RF tiene test, la demo funciona y el acta G0 está firmada por el usuario.
