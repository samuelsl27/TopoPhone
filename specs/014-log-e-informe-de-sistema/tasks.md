# Tareas — Spec 014

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `AppLog` (con `NONE`), `LogLevel`, `LogCategory`, `LogLine` y `LogRetention`. (RF-1, RF-8)
      Hecho cuando: `LogLineTest` y `LogRetentionTest` en verde.
- [x] T2. `data`: `FileAppLog` (canal, un solo escritor, volcado como mucho cada segundo, `flushNow`, corte y retención) y `LogPreferences`. (RF-8, RF-9, RNF)
      Hecho cuando: `FileAppLogTest` y `LogPreferencesTest` en verde.
- [x] T3. `app`: `AppLog` en Hilt y `CrashLog` instalado en `TopoPhoneApplication`. (RF-6)
      Hecho cuando: `CrashLogTest` en verde y la app arranca con el log.
- [x] T4. `app`: `ReceiverLogRecorder` (conexiones, reproducciones, NTRIP y sus avisos). (RF-1, RF-2, RF-5, RF-7)
      Hecho cuando: `ReceiverLogRecorderTest` en verde con cada transición de A4.
- [x] T5. `data`: anotar los puntos guardados y las copias, restauraciones, deshacer e importaciones. (RF-3, RF-4, RF-7)
      Hecho cuando: `PointRepositoryLogTest` y `BackupLogTest` en verde.
- [x] T6. `app`: anotar los avisos y errores del recolector, la exportación, las copias, los paquetes, las copias automáticas y la importación de sesiones. (RF-5)
      Hecho cuando: los tests `S014 RF-5` de cada pantalla en verde.
- [x] T7. `core:formats`: nombre del informe; `app`: `SessionSelection`. (RF-12, RF-16)
      Hecho cuando: `BackupFileNamesTest` y `SessionSelectionTest` en verde.
- [x] T8. `app`: `SystemReportWriter` (`informe.txt`, log, sesiones, fallo al escribir). (RF-11, RF-12, RF-13, RF-14, RF-15, RF-17)
      Hecho cuando: `SystemReportWriterTest` en verde.
- [x] T9. `app`: sección "Diagnóstico" de Ajustes (crear el informe con guardar o compartir, tamaño del log). (RF-9, RF-10, RF-17)
      Hecho cuando: `DiagnosticsSettingsTest` en verde.
- [x] T10. `app`: ni el log ni el informe llevan contraseñas ni usuarios NTRIP. (RF-7, RF-15)
      Hecho cuando: `NoSecretsTest` en verde.
- [x] T11. Rendimiento: informe con 25 MB de sesiones en menos de 10 s; anotar no retrasa el guardado de un punto. (RNF)
      Hecho cuando: los tests de rendimiento en verde.
- [x] T12. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La puerta G1 queda para el usuario.
