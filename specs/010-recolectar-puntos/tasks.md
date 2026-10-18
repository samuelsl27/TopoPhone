# Tareas — Spec 010

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:gnss` y `receiver`: la GST con su frase; `ReceiverSession.epochs` con todas las épocas; `Alerts.pointSaved()` y `Alerts.solutionLost()`. (RF-10, RF-17, RF-19)
      Hecho cuando: los tests de la GST y `ReceiverSessionEpochsTest` (todas las épocas de una reproducción a 10 Hz) en verde.
- [x] T2. `core:model`: `PointNames`, `PrecisionControl` con su comprobación, `CollectorSettings`, `CollectedPoint` y `PointQuality`. (RF-2, RF-13, RF-14, RF-18)
      Hecho cuando: `PointModelTest` (nombres y control de precisión) en verde.
- [ ] T3. `core:survey`: `PointMeasurement` instantánea y promediada, con épocas descartadas, media, punto del suelo y SC del proyecto. (RF-8 a RF-10, RF-12, RF-17, RF-18)
      Hecho cuando: `PointMeasurementTest` en verde (media ≤ 0,1 mm frente a la referencia).
- [ ] T4. `data`: Room v5 con `points` (migración 4→5), `PointRepository` y `CollectorPreferences`. (RF-3, RF-13, RF-16, RF-21)
      Hecho cuando: `TopoPhoneDatabaseTest`, `PointRepositoryTest` y `CollectorPreferencesTest` en verde.
- [ ] T5. `app`: `CollectorViewModel` (nombre, jalón, medida, control de precisión, guardado, sonidos). (RF-2 a RF-5, RF-8 a RF-19)
      Hecho cuando: `CollectorViewModelTest` en verde con una sesión de reproducción.
- [ ] T6. `app`: botón "Recolectar" y panel del recolector, con la pantalla encendida y los avisos. (RF-1, RF-4 a RF-7, RF-9, RF-11, RF-12)
      Hecho cuando: `CollectorPanelTest` en verde.
- [ ] T7. `app`: diálogos de ajustes del control de precisión y de fuera de tolerancia. (RF-13, RF-14, RF-15)
      Hecho cuando: `CollectorDialogsTest` en verde.
- [ ] T8. `app`: puntos en el mapa con su nombre y número de puntos en los datos del proyecto; glifos del IGN en los estilos locales. (RF-20, RF-21)
      Hecho cuando: `PointsLayerTest`, `MapStylesTest` y `ProjectInfoTest` en verde.
- [ ] T9. Rendimiento: 10 000 puntos leídos y preparados para el mapa en menos de 2 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.
- [ ] T10. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador con reproducciones según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo con el RS3 queda para el usuario.
