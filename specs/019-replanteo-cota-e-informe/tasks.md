# Tareas — Spec 019

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/replanteo`: `generar_informe.py` con puntos de diseño con y sin altura y con cota escrita (Península, Canarias y un SC geográfico), la diferencia de cota, el desmonte o terraplén, "dentro", y un informe con varios replanteos por punto con su resumen, calculados con pyproj y Python aparte. (RF-6, RF-13, RF-14, RF-17, RF-21, RF-26)
      Hecho cuando: el script genera `fixtures/replanteo/` (la cota y el informe) y su test de `tools` está en verde.
- [x] T2. `core:model`: `StakeoutSettings.toleranceVM` (rango, 3 cm por defecto, conversión a la unidad del proyecto) y `StakeoutNames.number`; los términos nuevos en `docs/glosario.md`. (RF-10, RF-21)
      Hecho cuando: `StakeoutSettingsTest` y `StakeoutNamesTest` en verde con la tolerancia en m, ft y usft, fuera de rango y `_stkd`, `_stkd2` y un nombre editado.
- [x] T3. `core:model`: `StakeoutRef` con `designElevationM`, `toleranceHM`, `toleranceVM` y `withinTolerance`. (RF-13, RF-14, RF-26)
      Hecho cuando: los tests de `core:model` en verde y `StakeoutRef` sin cambios para quien no pasa los datos nuevos.
- [x] T4. `core:survey`: `StakeoutLevel`, los estados desmonte, terraplén y en cota con sus avisos y el margen de 5 mm. (RF-6, RF-8, RF-9)
      Hecho cuando: `StakeoutLevelTest` en verde: los tres estados, el aviso una sola vez, el borde oscilando, entrar en cota en la primera época y el reinicio.
- [x] T5. `core:survey`: `LevelScale` y `LevelView`. (RF-7)
      Hecho cuando: `LevelScaleTest` y `LevelViewTest` en verde: los pasos 1-2-5 hasta decenas de metros, sin saltos de ida y vuelta, la burbuja recortada al borde y la banda de la tolerancia.
- [x] T6. `core:survey`: `StakeoutDiff.ref` con la cota usada y las tolerancias, y `StakeoutDiff.within`. (RF-13, RF-14)
      Hecho cuando: `StakeoutDiffTest` en verde contra `fixtures/replanteo/` (≤ 1 mm): con y sin cota, la cota escrita, dentro en H y fuera en V y al revés.
- [x] T7. `core:model`: `StakeoutReport` con las filas, el último replanteo, los sin evaluar y el resumen. (RF-17, RF-18, RF-21, RF-26)
      Hecho cuando: `StakeoutReportTest` en verde contra `fixtures/replanteo/` (resumen ≤ 0,1 mm): varios replanteos, con selección, sin evaluar, sin ninguno y un nombre editado.
- [x] T8. `core:formats`: `StakeoutReportCsv`, y las ayudas de `CsvExport` abiertas al módulo. (RF-21, RF-22, RF-23)
      Hecho cuando: `StakeoutReportCsvTest` y su golden en verde: columnas y orden, geográficas, celdas vacías, unidades, comillas, separador y decimal, y `CsvExportGoldenTest` sin cambios.
- [x] T9. `core:formats`: las siete columnas del replanteo en `ExportColumn`. (RF-25)
      Hecho cuando: `CsvExportTest` y `CsvExportGoldenTest` en verde: en "Todas las columnas" y "Personalizado", vacías en los no replanteados, PENZD y PNEZD sin cambios y una selección guardada de antes que no las incluye.
- [x] T10. `data`: columnas nuevas en `points`, `AutoMigration(10, 11)` y su lectura y escritura, incluida la consulta de exportación. (RF-13, RF-14, RF-26)
      Hecho cuando: `TopoPhoneDatabaseTest` 10 → 11 y `PointRepositoryStakeoutTest` en verde: se guardan y se leen sin redondear, y un replanteo de antes se lee con su cota y sin evaluar.
- [x] T11. `data`: `StakeoutPreferences` con la tolerancia vertical y su paso por la copia de ajustes. (RF-10)
      Hecho cuando: `StakeoutPreferencesTest` y `SettingsArchiveTest` en verde, con los valores fuera de rango corregidos.
- [x] T12. `receiver`: `Alerts.stakeoutLevelArrived()` y `stakeoutLevelLeft()` con sonidos y vibraciones distintos. (RF-9)
      Hecho cuando: `VibrationAlertsTest` en verde con los dos patrones distintos entre sí y de los demás avisos, y los fakes sin cambios.
- [x] T13. `app`: `StakeoutController` con la cota efectiva, el nivel, sus avisos y su apagado junto a la guía, y el log. (RF-1, RF-5, RF-6, RF-8, RF-9, RF-11, RF-12)
      Hecho cuando: `StakeoutControllerTest` ampliado en verde con la `ReceiverSession` real y las épocas de una en una: con la cota del punto y la escrita, sin cota, punta sin altura, avisos, apagado con la guía y la llegada horizontal sin cambios.
- [x] T14. `app`: `StakeoutViewModel` con la cota escrita, "Usar la del punto", la tolerancia vertical y el bloqueo durante la medida. (RF-2, RF-3, RF-4, RF-10, RF-16)
      Hecho cuando: los tests del ViewModel en verde: cota válida y no numérica, volver a la del punto, cambio de punto, tolerancia vertical y el bloqueo durante la medida.
- [x] T15. `app`: `CollectorViewModel` guarda el replanteo con la cota usada, las tolerancias y si cumple. (RF-13, RF-14)
      Hecho cuando: `CollectorViewModelStakeoutTest` ampliado en verde: con y sin cota, la cota escrita, dentro en H y fuera en V y al revés, y un replanteo sin tolerancias propias intacto.
- [x] T16. `app`: el nivel vertical y "Desmonte", "Terraplén" y "En cota" en el panel, con sus textos en es y en. (RF-5, RF-6, RF-7, RF-8, RF-11)
      Hecho cuando: los tests del nivel y de `StakeoutPanelTest` en verde: a cualquier distancia y con varios metros de diferencia, verde con su texto, apagado con la guía, sin cota y la descripción para TalkBack.
- [x] T17. `app`: el diálogo de la cota de diseño y la tolerancia vertical en los ajustes. (RF-2, RF-3, RF-4, RF-10, RF-16)
      Hecho cuando: los tests del diálogo en verde: escribir la cota, el error si no es un número, "Usar la del punto", la tolerancia vertical en la unidad del proyecto y los controles bloqueados durante la medida.
- [ ] T18. `app`: la confirmación del punto replanteado y su ficha con la cota usada, el desmonte o terraplén y si cumple. (RF-15)
      Hecho cuando: `StakeoutPanelTest` y `PointDetailsStakeoutTest` en verde, con las unidades del proyecto y un replanteo de antes "sin evaluar".
- [ ] T19. `app`: `StakeoutReportViewModel` con el resumen, la selección, el vacío y la escritura del CSV. (RF-17, RF-18, RF-19, RF-20, RF-24)
      Hecho cuando: `StakeoutReportViewModelTest` en verde: todos y seleccionados, sin papelera, sin puntos replanteados, error de escritura y el nombre propuesto.
- [ ] T20. `app`: la pantalla del informe con su resumen, "Guardar" y "Compartir", y sus entradas en el menú del proyecto, en el de la lista de objetos y en la selección. (RF-17, RF-18, RF-20, RF-24)
      Hecho cuando: `StakeoutReportScreenTest` en verde, con el selector de ficheros cancelado y la lista de objetos con y sin selección.
- [ ] T21. `app`: las columnas del replanteo en la pantalla de exportación del CSV de puntos. (RF-25)
      Hecho cuando: `ExportViewModelTest` y `ExportScreenTest` en verde, con las columnas nuevas en "Personalizado" y la selección guardada de antes.
- [ ] T22. `tools/nmea`: `generar_cota_rs3.py` con `rs3-cota` (la punta sube y baja alrededor de la cota de diseño y 10 s sin FIX) y lo esperado de cada época. (RF-6, RF-8, RF-9, RF-11)
      Hecho cuando: los ficheros se generan y su test de `tools` está en verde.
- [ ] T23. Reproducción y rendimiento: `StakeoutLevelReplayTest` con `rs3-cota`, `StakeoutLevelPerformanceTest` y `StakeoutReportPerformanceTest`. (RNF, RF-6, RF-9, RF-11, RF-17)
      Hecho cuando: los tres en verde (nivel a 10 Hz en menos de 1 ms por época; de una época a la pantalla, menos de 300 ms; resumen y CSV de 1.000 puntos en menos de 2 s) y los demás tests de reproducción siguen igual.
- [ ] T24. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización, S019 a "implementada" en la hoja de ruta. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2) quedan para el usuario.
