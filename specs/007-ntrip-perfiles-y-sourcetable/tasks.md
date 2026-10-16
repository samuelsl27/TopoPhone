# Tareas — Spec 007

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. Fixtures: grabar las tablas de fuentes reales (IGN 2101 y 2102, y una red autonómica) y unos segundos de RTCM 3 de una red de acceso libre, cada uno con su `.yaml` (caster, punto de montaje, fecha). (RF-14, RF-32)
      Hecho cuando: los ficheros están en `fixtures/ntrip/sourcetables/` y `fixtures/rtcm/`, sin credenciales, y `LEEME.md` los cita.
- [x] T2. `core:gnss`: `RtcmFramer` incremental con CRC24Q, recuento por tipo y errores, y `RtcmMessages.kind`. (RF-32, RF-33)
      Hecho cuando: `RtcmFramerTest` en verde con el flujo real, en bloques de 1 byte, con bytes corrompidos y 4 KB en < 50 ms.
- [x] T3. `core:ntrip`: `SourcetableParser`, orden por distancia y búsqueda de puntos de montaje. (RF-14 a RF-18)
      Hecho cuando: `SourcetableParserTest` (tablas reales y casos raros, 5000 puntos en < 2 s) y `MountpointsTest` en verde.
- [x] T4. `core:ntrip`: `SourcetableClient` (v1, v2, chunked, 401, respuesta que no es tabla, vacía, inalcanzable). (RF-14, RF-20)
      Hecho cuando: `SourcetableClientTest` en verde contra el caster falso.
- [x] T5. `core:ntrip`: TLS opcional en `NtripClient` y `SourcetableClient` (fallo `TLS_INVALID`) y GGA solo con `sendGga`; certificados de test con `keytool`. (RF-21, RF-27, RF-28)
      Hecho cuando: `NtripClientTlsTest` (válido, otro nombre, no confiable) y los casos nuevos de `NtripClientTest` en verde.
- [x] T6. `core:model`: `NtripProfileDraft` y `NtripProfileRules`, y catálogo `PublicCasters`. (RF-2, RF-3, RF-11, RF-13)
      Hecho cuando: `NtripProfileRulesTest` y `PublicCastersTest` en verde.
- [x] T7. `data`: `SecretCipher` AES-GCM con clave del Keystore (clave en memoria en los tests). (RF-4, RF-6)
      Hecho cuando: `SecretCipherTest` en verde (ida y vuelta, IV distinto cada vez, otra clave → no se descifra).
- [x] T8. `data`: Room v2 con `ntrip_profiles`, migración 1→2 probada y esquema exportado. (RNF de la spec)
      Hecho cuando: el test de migración con datos de la v1 y el de esquema en verde, y `data/schemas/…/2.json` en el repositorio.
- [x] T9. `data`: `NtripProfileRepository` (crear, editar, borrar, unicidad, `setMountpoint`, `password()`), contraseña solo cifrada. (RF-1, RF-3, RF-4, RF-6, RF-7, RF-19)
      Hecho cuando: `NtripProfileRepositoryTest` en verde, incluido que la base no contiene la contraseña en claro.
- [x] T10. `data`: preferencias nuevas (último perfil, "estaba abierta", umbral 2–60 s) e importación única de los datos de S002. (RF-10, RF-26, RF-31)
      Hecho cuando: `ReceiverPreferencesTest` y `NtripLegacyImportTest` en verde.
- [x] T11. `receiver`: reconexión del NTRIP con espera creciente, estado `Reconnecting`, sin reintento ante rechazos y TLS, y detener durante la espera. (RF-23 a RF-25)
      Hecho cuando: `ReceiverSessionNtripTest` en verde para esos casos con reloj virtual.
- [x] T12. `receiver`: recuento RTCM en `Streaming` (reenviar antes de contar) y alarma por edad con `Alerts.correctionsLost()`. (RF-29, RF-30, RF-32, RF-33)
      Hecho cuando: `ReceiverSessionNtripTest` en verde para alarma (umbral, vuelta, una por corte) y recuento con reenvío intacto.
- [x] T13. `app`: `CorrectionsController` (conectar con perfil y contraseña, detener, arranque automático, editar o borrar el perfil en uso, GGA según el perfil) e inyección de dependencias. (RF-6, RF-8, RF-9, RF-21, RF-22, RF-26)
      Hecho cuando: `CorrectionsControllerTest` en verde.
- [x] T14. `app`: pantalla Correcciones con lista de perfiles, tarjeta de estado (reconectando y "Detener") y menú de perfil; sustituye a la tarjeta NTRIP de S002. (RF-1, RF-7, RF-22, RF-25)
      Hecho cuando: `CorrectionsScreenTest` en verde y los tests de S002 adaptados.
- [x] T15. `app`: formulario de perfil (validaciones, contraseña oculta con botón de ver, TLS) y diálogo "Elegir caster" con casters públicos. (RF-2, RF-3, RF-5, RF-11 a RF-13)
      Hecho cuando: `NtripProfileFormTest` en verde.
- [x] T16. `app`: elegir punto de montaje desde la tabla de fuentes (orden, búsqueda, sin posición, fallo y escribirlo a mano). (RF-14 a RF-20)
      Hecho cuando: `MountpointPickerTest` en verde.
- [x] T17. `app`: tarjeta "Mensajes RTCM" en Correcciones. (RF-32, RF-33)
      Hecho cuando: `CorrectionsScreenTest` en verde para tipos, nombres, cuántos y errores.
- [x] T18. `app`: alarma en el indicador de estado y en la notificación, sonido y vibración del servicio, y umbral en Ajustes. (RF-29 a RF-31)
      Hecho cuando: `StatusIndicatorTest`, `ReceiverNotificationsTest` y `CorrectionsSettingsTest` en verde.
- [x] T19. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador con el caster falso según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo con el RS3 queda para el usuario.
