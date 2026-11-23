# Tareas — Spec 060

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S060 Tn: …` en `main`, con la línea Co-Authored-By.

## Componentes comunes (`app/ui/field`)
- [x] T1. `FieldSheet`: dos alturas, asa que arrastra y que se toca, acciones «Expandir» y «Plegar» para TalkBack, altura en `rememberSaveable`, máximo expandido del 70 % y cierre con «Cerrar». (RF-1, RF-3, RF-4, RF-5, RF-6)
      Hecho cuando: `FieldSheetTest` abre plegada, alterna con el toque y con las acciones semánticas, conserva la altura al recrear la actividad y el estado guardado, y mide el alto plegado.
- [x] T2. `SettingPill` y `SettingsRow`: 56 dp, nombre y valor en texto, descripción para TalkBack, y dos filas con letra al 130 %. (RF-9, RF-27, RNF-T8)
      Hecho cuando: `SettingsRowTest` comprueba a 360 y 411 dp con letra al 100 % y al 130 % que las cuatro pastillas existen, miden 56 dp y no cortan texto.
- [ ] T3. `NumericEntryDialog` y `ShortListDialog`: teclado `Decimal` con valor y rango actuales, error junto al campo sin aplicar, dos campos minutos y segundos para el tiempo, y lista corta con la opción vigente marcada; `imePadding`. (RF-7, RF-11, RF-12, RF-13)
      Hecho cuando: `FieldDialogsTest` cubre valor válido, fuera de rango, formato «m:ss» armado desde minutos y segundos, la lista con la vigente marcada y que el campo queda sobre el teclado.

## Recolectar: hoja plegada
- [ ] T4. `StatusLine` y las pastillas informativas (`InfoPill`): solo FIX y control de precisión, base shift y localización, solo si no son los de por defecto, en texto neutro y con su destino al tocar; «Esperando FIX» y precisión fuera de límite como línea sin rojo. (RF-14, RF-17b, RF-18, RF-19)
      Hecho cuando: `CollectorStatusLineTest` comprueba cada pastilla, que no hay ninguna con los ajustes por defecto, que nunca usan el color de error y que el estado sigue en texto.
- [ ] T5. `MeasureReasons` (puro): motivo de mayor prioridad y su acción, con los textos nuevos en español e inglés. (RF-16, RF-17, RF-19)
      Hecho cuando: `MeasureReasonsTest` cubre cada motivo de RF-17 con su acción, la prioridad entre dos motivos a la vez y que sin bloqueo no sale nada.
- [ ] T6. `CollectionModeChoice` (puro): las cuatro opciones de «Modo» traducidas a `onMode`, `onAutoEnabled` y `onAutoTrigger`, y el valor de la pastilla «Tiempo» según el modo. (RF-9, RF-10, RF-13, RF-32)
      Hecho cuando: `CollectionModeChoiceTest` comprueba que cada opción llama a las mismas acciones que hoy y que «Tiempo» muestra promediado, intervalo, distancia o «Instantáneo» sin selector.
- [ ] T7. `CollectorFolded`: línea de estado, fila con las cuatro pastillas y sus selectores, pastillas informativas, motivo con su acción y «Medir» con el nombre del punto. (RF-8, RF-9, RF-11, RF-13, RF-16)
      Hecho cuando: `CollectorFoldedTest` cambia el jalón y el tiempo desde la pastilla y comprueba que llegan a `onPoleHeight` y `onSeconds`, que Modo e Inclinación abren su lista y que el motivo lleva su acción.
- [ ] T8. Medición en curso en la hoja plegada: pastillas con los valores de partida y desactivadas, progreso con tiempo y épocas, «Terminar» y «Cancelar», y el aviso de pérdida de solución o inclinación en una sola línea. (RF-15, RF-22)
      Hecho cuando: `CollectorMeasuringSheetTest` comprueba pastillas desactivadas, progreso, que «Terminar» solo sale en promediado y que nunca hay más de una línea de aviso además del motivo.

## Recolectar: hoja expandida e integración
- [ ] T9. `CollectorExpanded`: nombre, descripción, selector de código con atributos, foto y galería (o el texto de que no hay punto), y lo de líneas y polígonos, sin filas cortadas; guía de inclinación, cambio de base y medición automática con su texto actual. (RF-20, RF-21, RF-22)
      Hecho cuando: `CollectorExpandedTest` recorre cada fila con una pantalla pequeña y letra al 130 % sin cortes, comprueba que sin punto no hay botones de foto desactivados y que el teclado no tapa el campo enfocado.
- [ ] T10. `CollectorSheet` en `ProjectViewScreen`: sustituye a `CollectorPanel` en el recolector suelto y mueve el hueco inferior del mapa a su alto medido; se añaden `onOpenReceiver`, `onOpenProjectCrs` y `onOpenGear` a `CollectorActions`. (RF-1, RF-2, RF-16)
      Hecho cuando: `CollectorSheetScreenTest` abre el recolector en el proyecto, comprueba el alto plegado frente a 411×891 y 360×640 (D2) y que las acciones del motivo llegan a sus rutas.
- [ ] T11. `CollectorSheet` bajo las secciones, el perfil longitudinal y la toma por eje, y migración de los tests antiguos de `CollectorPanel`. (RF-1, RF-32)
      Hecho cuando: los tests de `CollectorPanel*` y de las secciones, migrados, pasan, y `CollectorPanel` y `FORM_MAX_HEIGHT` ya no existen.
- [ ] T12. Cierre del recolector: `RF-32` con un test de regresión de los valores por defecto, rangos y textos de S010, S016, S017, S020, S024, S025, S046 y S047, y comprobación de que no queda ningún aviso permanente de configuración. (RF-19, RF-32)
      Hecho cuando: `CollectorNothingChangesTest` pasa y `rf_report` muestra RF-1 a RF-22 cubiertos.

## Replantear: la placa y la hoja
- [ ] T13. `ArrivalRules` y `GuidancePlate`: flecha, distancia, desplazamientos Norte y Este o adelante, atrás, izquierda y derecha, cota con «Desmonte» o «Terraplén», y «En el punto» en verde con texto. (RF-24, RF-28)
      Hecho cuando: `ArrivalRulesTest` y `GuidancePlateTest` cubren ambos modos, con y sin cota, sin rumbo, dentro y fuera de ambas tolerancias, con texto además de color.
- [ ] T14. `StakeoutSheet` común (cabecera con «Anterior», «Siguiente» y «Cerrar» a 56 dp, placa, fila de ajustes y los trozos de recolector) con las pastillas «Tolerancia H», «Tolerancia V» (con cota), «Cota» y «Modo». (RF-23, RF-27, RF-30)
      Hecho cuando: `StakeoutSheetTest` comprueba pastillas, selectores contra `onTolerance`, `onToleranceV`, `onElevation` y `onMode`, y las zonas de 56 dp de «Anterior» y «Siguiente».
- [ ] T15. Replanteo de puntos sobre `StakeoutSheet`, con el diálogo de la cota actual (con la superficie y el desplazamiento de S042) detrás de «Cota» y la hoja expandida con la diana, el nivel y las notas. (RF-23, RF-24, RF-27)
      Hecho cuando: `StakeoutPanelTest` migrado pasa y `StakeoutLevelView` y `StakeoutDiana` siguen visibles al expandir.
- [ ] T16. Replanteo de líneas sobre `StakeoutSheet`, con el PK, el lado, el error lateral y lo que falta en la placa. (RF-23, RF-29)
      Hecho cuando: `LineStakeoutPanelTest` migrado pasa y la placa lleva el objetivo de la línea con el diseño de la de un punto.
- [ ] T17. Replanteo por eje sobre `StakeoutSheet`, con el PK, el desplazamiento y el objetivo en la placa. (RF-23, RF-29)
      Hecho cuando: `AxisStakeoutPanelTest` migrado pasa.
- [ ] T18. Replanteo de taludes sobre `StakeoutSheet`, con el talud y la guía en la placa. (RF-23, RF-29)
      Hecho cuando: los tests de `SlopeStakePanel` migrados pasan.
- [ ] T19. Los motivos de bloqueo y los avisos del recolector dentro de los cuatro replanteos con una sola línea, y el panel de análisis de ejes y las secciones que usan los trozos antiguos. (RF-16, RF-22, RF-23)
      Hecho cuando: un test por replanteo comprueba que «Medir» bloqueado dice su motivo con su acción y que no se apilan avisos.

## Replantear: el mapa
- [ ] T20. `StakeoutCameraPolicy` (puro): centro, zoom con 25 % de margen y límite de 3 m, rumbo con umbral de 5°, norte arriba sin rumbo, histéresis de zoom y como mucho 5 órdenes por segundo. (RF-25)
      Hecho cuando: `StakeoutCameraPolicyTest` cubre destino lejos y cerca, el límite de 3 m, sin rumbo, el umbral del rumbo y la cadencia.
- [ ] T21. `MapController.follow` y `resetBearing`, su implementación en `MapLibreMapController` y su doble de test. (RF-25)
      Hecho cuando: `MapLibreMapControllerFollowTest` y el doble de la interfaz comprueban centro, zoom y rumbo, y que el hueco inferior se respeta.
- [ ] T22. El seguimiento en `ProjectViewScreen`: se activa con cualquier replanteo, un gesto lo suelta, sale «Recentrar» con 56 dp, y al cerrar vuelve el norte arriba y la política de S009. (RF-25, RF-26)
      Hecho cuando: `StakeoutFollowScreenTest` comprueba que sigue, que un gesto muestra «Recentrar», que al pulsarlo vuelve a seguir y que al cerrar el replanteo se restaura.

## Validación y cierre
- [ ] T23. Matriz de accesibilidad y tamaño: recolectar y los cuatro replanteos a 360 y 411 dp, con letra al 100 % y al 130 %, y etiquetas de TalkBack en todos los controles. (RNF-T8 y los dos específicos)
      Hecho cuando: `FieldAccessibilityMatrixTest` comprueba que cada pastilla, motivo y botón existe, es alcanzable y mide 56 dp, y que ningún texto queda cortado.
- [ ] T24. Rendimiento: la línea de estado, la placa y el seguimiento del jalón a 5 Hz con el hilo principal libre, y demo en el emulador con el receptor simulado y capturas de antes y después. (RNF-T13, criterios de finalización)
      Hecho cuando: `FieldSheetPerformanceTest` comprueba 5 Hz sin bloquear más de 16 ms, y las capturas muestran recolectar plegado y expandido, el cambio de jalón y tiempo, un motivo de bloqueo, replantear cerca y lejos y las tolerancias.
- [ ] T25. Cierre: `rf_report` con los 32 RF cubiertos, `check`, `pytest tools`, `forbidden_files` y `assembleDebug`; el plan con los cambios al implementar y la validación; hoja de ruta con la 060 implementada; glosario (hoja de campo, pastilla, placa de guiado). (todos)
      Hecho cuando: el informe de trazabilidad no tiene RF huérfanos y todo pasa.
