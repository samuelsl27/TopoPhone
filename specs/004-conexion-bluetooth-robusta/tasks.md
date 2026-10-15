# Tareas — Spec 004

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero.

- [x] T1. `receiver`: reconexión en `ReceiverSession` (`connect` con `reopen`, estado `Reconnecting`, esperas de 1 s a 30 s, rendición a los 30 min, "Reintentar ahora", desconectar la detiene). (RF-13, RF-14, RF-16, RF-17, RF-20, RF-21)
      Hecho cuando: `ReceiverSessionReconnectTest` y los tests de S002 y S003 en verde.
- [x] T2. `receiver`: durante la reconexión, NTRIP en pausa y reanudado con la misma configuración, y la misma grabación. (RF-18, RF-19)
      Hecho cuando: `ReceiverSessionReconnectTest` en verde con esos casos.
- [x] T3. `receiver`: reintentos con el Bluetooth apagado, `ConnectFailure.BUSY` (rechazo en < 3 s) y `nmeaMissing` (10 s sin NMEA). (RF-22, RF-27, RF-28)
      Hecho cuando: `ReceiverSessionReconnectTest` en verde con esos casos.
- [x] T4. `app`: tarjeta Conexión con "Reconectando" (intento, cuenta atrás, "Reintentar ahora", Bluetooth apagado), vibración al empezar a reconectar y "Conexión perdida" tras rendirse; "Reconectando" en la barra de estado. (RF-13, RF-15, RF-16, RF-20, RF-22, RF-23)
      Hecho cuando: `ReconnectingCardTest`, `StatusBarStateTest` y `StatusBarTest` en verde.
- [x] T5. `app`: mensajes de diagnóstico (receptor ocupado por otra conexión, salida de posición desactivada). (RF-27, RF-28)
      Hecho cuando: `ConnectionDiagnosisTest` en verde.
- [x] T6. `data`: último receptor y `notificationsAsked` en las preferencias. (RF-5, RF-24)
      Hecho cuando: `ReceiverPreferencesTest` en verde.
- [x] T7. `app`: `ReceiverConnections` (un único punto para conectar con reconexión y guardar el último receptor) y conexión automática al arrancar. (RF-24, RF-25, RF-26)
      Hecho cuando: `AutoConnectTest` en verde.
- [x] T8. `receiver` + manifiestos: `ReceiverService` en primer plano (`connectedDevice`) con la notificación (solución, edad de las correcciones, reconectando) a 1 Hz como máximo; se detiene al no haber receptor. (RF-7, RF-8, RF-11)
      Hecho cuando: `ReceiverServiceTest` en verde y Lint sin errores.
- [x] T9. `receiver` + `app`: la notificación abre Receptor y su acción "Desconectar" cierra la conexión. (RF-9, RF-10)
      Hecho cuando: `ReceiverServiceTest` y `OpenReceiverFromNotificationTest` en verde.
- [ ] T10. `receiver`: wakelock parcial mientras el servicio sigue (plazo de 10 min renovado). (RF-7)
      Hecho cuando: `ReceiverServiceTest` en verde con el wakelock.
- [ ] T11. `app`: permiso de notificaciones al conectar por primera vez (Android 13+) y aviso con "Activar" si se deniega. (RF-5, RF-6)
      Hecho cuando: `NotificationPermissionTest` en verde.
- [ ] T12. `receiver`: `ReceiverPairing` (CompanionDeviceManager, emparejamiento con PIN por `createBond`, resultados y reserva sin companion_device_setup). (RF-1, RF-2, RF-4)
      Hecho cuando: `ReceiverPairingTest` en verde.
- [ ] T13. `app`: "Añadir receptor" en la tarjeta Conexión (buscar, emparejar, conectar, mensajes de cancelación y fallo). (RF-1, RF-2, RF-3, RF-4)
      Hecho cuando: `AddReceiverTest` en verde.
- [ ] T14. `app`: ayuda "Funcionamiento en segundo plano" en Ajustes, con acceso a los ajustes de batería de la app. (RF-12)
      Hecho cuando: `BackgroundHelpTest` en verde.
- [ ] T15. Validación: recorrer la spec RF por RF, `rf_report`, demo en el emulador (reconexión con un transporte simulado no aplica; notificación, ayuda y estados con la reproducción) y demo con el RS3 real según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo con el RS3 funciona.
