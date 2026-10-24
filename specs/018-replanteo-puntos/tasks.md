# Tareas — Spec 018

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/replanteo`: `generar_replanteo.py` con pares de diseño y punta en la Península, en Canarias y en un SC geográfico, y la distancia, las diferencias de Norte y Este y el rumbo calculados con pyproj. (RF-6, RF-7, RF-12)
      Hecho cuando: el script genera `fixtures/replanteo/` y su test de `tools` está en verde.
- [ ] T2. `core:model`: `StakeoutSettings` (modo, origen del rumbo y tolerancia con su rango y su conversión a la unidad del proyecto), `StakeoutNames` y `NaturalOrder`. (RF-3, RF-5, RF-8, RF-18, RF-27)
      Hecho cuando: `StakeoutSettingsTest`, `StakeoutNamesTest` y `NaturalOrderTest` en verde, con `_stkd`, `_stkd2`, un nombre que ya acaba en número, 1, 2, 10 y la tolerancia en m, ft y usft.
- [ ] T3. `core:model`: `PointOrigin.STAKED`, `StakeoutRef` en `Point` y `PointSummary.stakeCount`. (RF-27, RF-28, RF-32, RF-33)
      Hecho cuando: los tests de `core:model` en verde y `Point.measured` sin cambios para quien no pasa los datos nuevos.
- [ ] T4. `core:survey`: `StakeoutGeometry`. (RF-6, RF-7, RF-12)
      Hecho cuando: `StakeoutGeometryTest` en verde contra `fixtures/replanteo/` (≤ 1 mm y 0,001°), con el diseño sin altura, la punta sin altura, distancia 0 y el SC geográfico.
- [ ] T5. `core:survey`: `HeadingSource` con el rumbo de marcha y el del IMU. (RF-8, RF-9, RF-10, RF-11)
      Hecho cuando: `HeadingSourceTest` en verde: por encima y por debajo de 0,3 m/s, los 10 s de "último rumbo", el IMU en estado 30 y fuera de él y la convergencia de cuadrícula.
- [ ] T6. `core:survey`: `StakeoutZones` y `DianaScale`. (RF-16, RF-19, RF-20, RF-21)
      Hecho cuando: `StakeoutZonesTest` y `DianaScaleTest` en verde: las tres zonas, el margen de 5 mm, el aviso una vez, una tolerancia mayor de 40 cm, el rearmado y la escala sin saltos de ida y vuelta.
- [ ] T7. `core:survey`: `StakeoutDiff`, medido menos diseño. (RF-28)
      Hecho cuando: `StakeoutDiffTest` en verde, con los signos, sin redondear y con y sin altura.
- [ ] T8. `data`: columnas de replanteo en `points`, `AutoMigration(9, 10)` y su lectura y escritura. (RF-27, RF-28, RF-32)
      Hecho cuando: `TopoPhoneDatabaseTest` 9 → 10 y `PointRepositoryStakeoutTest` en verde: un punto replanteado se guarda y se lee con su diseño y sus diferencias sin redondear, y los puntos de antes conservan sus datos.
- [ ] T9. `data`: el recuento de replanteos por punto de diseño en los resúmenes. (RF-33)
      Hecho cuando: los tests del recuento en verde: con dos replanteos, con un diseño borrado, con un replanteo en la papelera y sin ninguno.
- [ ] T10. `data`: `StakeoutPreferences` y su paso por la copia de ajustes. (RF-5, RF-8, RF-18)
      Hecho cuando: `StakeoutPreferencesTest` y `SettingsArchiveTest` en verde, con los valores fuera de rango corregidos.
- [ ] T11. `receiver`: `Alerts.stakeoutApproach()`, `stakeoutArrived()` y `stakeoutLeft()` con sus sonidos y vibraciones distintos. (RF-20, RF-21)
      Hecho cuando: `VibrationAlertsTest` en verde con los tres patrones distintos entre sí y de los demás avisos, y los fakes sin cambios.
- [ ] T12. `app`: `StakeoutController` con la guía y las condiciones que la apagan. (RF-5 a RF-13, RF-22 a RF-25, RF-35)
      Hecho cuando: `StakeoutControllerTest` en verde con la `ReceiverSession` real y las épocas de una en una: por dirección y por distancia, sin posición al abrir, un corte y su vuelta, sin FIX con "Solo FIX" y sin él, sin estado 30 con la inclinación y la solución y la precisión que se muestran.
- [ ] T13. `app`: los avisos de entrada y salida de las zonas y el log del replanteo. (RF-20, RF-21)
      Hecho cuando: los tests de avisos de `StakeoutControllerTest` en verde: un aviso al entrar, otro al salir, nada con el borde oscilando, el aviso de la zona de ayuda una sola vez y las anotaciones del log sin coordenadas.
- [ ] T14. `app`: `CollectorViewModel` con el contexto de replanteo: nombre `_stkd`, guardado `STAKED` con el diseño y las diferencias, y el nombre del recolector que se restaura. (RF-26 a RF-31)
      Hecho cuando: `CollectorViewModelStakeoutTest` en verde: medir y guardar, repetir (`_stkd2`), el control de precisión con su confirmación, un promediado con un corte, el diseño editado después, fuera del ámbito del SC y el recolector sin cambios sin contexto.
- [ ] T15. `app`: `StakeoutViewModel`: el punto de diseño, siguiente y anterior, los ajustes y la medida en marcha. (RF-1 a RF-4, RF-30)
      Hecho cuando: los tests del ViewModel en verde: siguiente y anterior con búsqueda y en los extremos, el cambio de punto bloqueado durante la medida y los ajustes que se recuerdan.
- [ ] T16. `app`: `StakeoutPanel`, con la cabecera, los dos modos y las cifras de la guía, con sus textos en es y en. (RF-4, RF-5, RF-6, RF-7, RF-11, RF-12, RF-13, RF-35)
      Hecho cuando: `StakeoutPanelTest` en verde con los textos de cada modo y situación, la guía apagada con su motivo y las etiquetas de TalkBack.
- [ ] T17. `app`: la flecha y la diana métrica, con su escala, su orientación y "Dentro de la tolerancia". (RF-15, RF-16, RF-17, RF-19)
      Hecho cuando: los tests de la diana en verde: aparece a 40 cm o menos, el círculo de la tolerancia a escala, el verde con su texto y la descripción de texto.
- [ ] T18. `app`: "Medir", el progreso, el resultado con las diferencias, "Cancelar" y el diálogo de ajustes en el panel. (RF-18, RF-26, RF-29, RF-30)
      Hecho cuando: los tests de medir de `StakeoutPanelTest` en verde: el progreso, las diferencias tras guardar, los controles bloqueados durante la medida y la tolerancia cambiada.
- [ ] T19. `app`: el panel en la vista de proyecto y las entradas "Replantear" en la ficha, la lista de objetos y el mapa. (RF-1)
      Hecho cuando: `StakeoutEntriesTest` en verde, con el recolector y el replanteo que no se pisan y la pantalla encendida con el replanteo abierto.
- [ ] T20. `app`: `MapController.showStakeout` con la punta, el diseño y la línea, y el símbolo de los replanteados. (RF-14, RF-34)
      Hecho cuando: `MapStakeoutTest` en verde: la línea a más de 40 cm, sin ella dentro, y los símbolos distintos.
- [ ] T21. `app`: la ficha del punto replanteado y la marca de replanteos en la lista de objetos. (RF-32, RF-33)
      Hecho cuando: `PointDetailsStakeoutTest` y `ObjectListStakeoutTest` en verde, con las unidades del proyecto.
- [ ] T22. `tools/nmea`: `generar_replanteo.py` con `rs3-replanteo` (40 m andando hacia el diseño, 10 s sin FIX y una parada) y lo esperado de cada época. (RF-6, RF-9, RF-20, RF-21, RF-22)
      Hecho cuando: los ficheros se generan y su test de `tools` está en verde.
- [ ] T23. Reproducción y rendimiento: `StakeoutReplayTest` con `rs3-replanteo` y `StakeoutPerformanceTest`. (RNF, RF-6, RF-20, RF-22, RF-27)
      Hecho cuando: ambos en verde (guía a 10 Hz en menos de 1 ms por época; de una época a la pantalla, menos de 300 ms) y los demás tests de reproducción siguen igual.
- [ ] T24. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo quedan para el usuario.
