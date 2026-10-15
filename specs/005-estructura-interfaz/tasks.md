# Tareas — Spec 005

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check`.

- [ ] T1. `core:designsystem` + `app`: indicador de estado compacto en la barra superior en lugar de la franja (sin edad, "Grabando" ni inclinación). (S005/RF-18; S003/RF-16 a RF-19, RF-21, RF-23; S004/RF-23)
      Hecho cuando: `StatusIndicatorTest` en verde (también a 360 dp con la fuente al 130 %) y ninguna pantalla pinta la franja.
- [ ] T2. `app`: `receiverHomeMode` y raíz de Receptor que elige entre la lista y la pantalla del receptor. (RF-1, RF-5)
      Hecho cuando: `ReceiverHomeModeTest` en verde para todos los estados, con y sin reproducción.
- [ ] T3. `app`: lista de receptores: emparejados, último usado marcado, "Añadir receptor", acceso a las sesiones grabadas, fallo o pérdida encima. (RF-2, RF-3, RF-4)
      Hecho cuando: `ReceiverListTest` en verde y adaptados `ReceiverConnectionCardTest`, `AddReceiverTest` y la parte de fallos de `ConnectionDiagnosisTest`.
- [ ] T4. `app`: cabecera de la pantalla del receptor: nombre, estado, acciones, avisos, "Grabando", inclinación y "Reproduciendo". (RF-6, RF-7, RF-8; S003/RF-20, RF-22, RF-35)
      Hecho cuando: `ReceiverHeaderTest` en verde y adaptados `ReconnectingCardTest`, `ReplayControlsTest`, `NotificationPermissionTest` y el aviso de NMEA de `ConnectionDiagnosisTest`.
- [ ] T5. `app`: menú de la pantalla del receptor con sus cuatro entradas y resúmenes. (RF-9 a RF-13)
      Hecho cuando: `ReceiverMenuTest` en verde.
- [ ] T6. `app`: rutas de las pantallas Estado, Coordenadas y Correcciones en el grafo de Receptor, `ReceiverViewModel` con ámbito de grafo, Atrás y giro. (RF-14, RF-15, RF-17)
      Hecho cuando: `ReceiverNavigationTest` en verde para abrir, volver y girar.
- [ ] T7. `app`: contenido de las pantallas Estado y Coordenadas, con el aviso de "No hay ningún receptor conectado". (RF-16, RF-19; S003/RF-14, RF-15)
      Hecho cuando: adaptados `StatusCardTest` y `CoordinatesCardTest`, y el caso de RF-16 en `ReceiverNavigationTest`.
- [ ] T8. `app`: contenido de las pantallas Correcciones y Sesiones grabadas (desde la lista y desde el menú). (RF-19)
      Hecho cuando: adaptados `NtripCardTest` y `ShareSessionTest`, y `RelocationTest` en verde para todas las filas de la tabla.
- [ ] T9. `app`: quitar la página larga y las piezas que ya no se usan; adaptar `EndToEndReplayTest`, `FullRs3ReplayTest`, `NavigationTest`, `RotationTest` y `OpenReceiverFromNotificationTest`. (RF-19)
      Hecho cuando: `./gradlew check` en verde sin tests de S002–S004 borrados.
- [ ] T10. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador con una reproducción y demo con el RS3 real según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y las demos funcionan.
