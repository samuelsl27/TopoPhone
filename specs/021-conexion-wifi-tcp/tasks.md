# Tareas — Spec 021

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`), marcar la casilla y un commit `S021 Tn: …`.

- [x] T1. `core:model`: `WifiReceiver`, `WifiReceiverDraft`, `WifiReceiverValidator` (dirección, puertos, valores iniciales 192.168.42.1 y 9001) y `ReceiverKey` (`wifi:<id>`). (RF-2, RF-3, RF-5)
      Hecho cuando: `WifiReceiverValidatorTest` y `ReceiverKeyTest` en verde con direcciones IPv4, nombres, IPv6, vacías, con espacios o con URL, y puertos de 0, 1, 65535 y 65536.
- [x] T2. `data`: Room v34 (`wifi_receivers`, `AutoMigration(33, 34)`, esquema `34.json`) y su fila en `DatabaseSnapshot.TABLES`. (RNF-T6)
      Hecho cuando: el test de migración v33 a v34 de `TopoPhoneDatabaseTest` y `WifiBackupTest` en verde y los de versiones anteriores intactos.
- [ ] T3. `data`: `WifiReceiverRepository` (observar, alta, edición, borrado, por id) con binding de Hilt. (RF-4, RF-5)
      Hecho cuando: `WifiReceiverRepositoryTest` en verde: el `id` no cambia al editar la dirección o el puerto y los puertos y el nombre se guardan tal cual.
- [ ] T4. `receiver`: `FakeTcpReceiver` de tests y `TcpTransport` de solo posición (conexión con plazo de 10 s, lectura, cierre, rechazo rápido). (RF-7, RF-14)
      Hecho cuando: `TcpTransportTest` en verde con servidor que acepta, que rechaza, que no contesta (plazo) y que corta, y la primera época del NMEA de `fixtures/nmea/` llega en menos de 3 s.
- [ ] T5. `receiver`: `acceptsCorrections` y el `output` de `TcpTransport` con el mismo puerto y sin puerto de correcciones. (RF-11, RF-12)
      Hecho cuando: `TcpCorrectionsTest` en verde: lo escrito llega intacto al servidor por el mismo socket, y sin puerto no se escribe nada.
- [ ] T6. `receiver`: `CorrectionsLink` (segundo socket, cola, descarte con el enlace caído y reintento 1, 2, 4, 8, 16 y 30 s) y `CorrectionsLinkStatus`. (RF-11, RF-13)
      Hecho cuando: `CorrectionsLinkTest` en verde con el puerto distinto, con el servidor de correcciones caído y vuelto a levantar, y con un corte a mitad de escritura sin que la posición se corte.
- [ ] T7. `receiver`: `ReceiverSession` con `correctionsLink` en el snapshot y `NtripState.NoCorrectionsPort`. (RF-12, RF-13, RNF-T12)
      Hecho cuando: `ReceiverSessionWifiTest` en verde: sin puerto el caster no se abre, con puerto las correcciones se escriben y se graban, y los tests de sesión de S002 a S007 intactos.
- [ ] T8. `receiver`: `NetworkRouter` (Wi-Fi sin exigir internet, red con internet validada, cambios de red) con `AndroidNetworkRouter`, `RoutedSocketFactory` y `WifiUnavailableException`. (RF-8, RF-9, RNF-T4)
      Hecho cuando: `NetworkRouterTest` en verde con una fuente de redes falsa: el Wi-Fi sin internet se usa para el receptor, el caster pide otra red y un cambio de red se avisa en menos de 3 s.
- [ ] T9. `receiver` y `app`: `NtripState.NeedsInternet` y el caster del NTRIP y la tabla de fuentes por `RoutedSocketFactory` solo con receptor Wi-Fi (`ReceiverModule`). (RF-9, RF-10)
      Hecho cuando: `ReceiverSessionWifiTest` en verde: sin red con internet el NTRIP lo dice y reanuda al aparecer una, sin cerrar el receptor, y con Bluetooth todo sigue igual.
- [ ] T10. `app`: `ReceiverConnections` con la clave `wifi:<id>`: conectar, reabrir al reconectar, conexión automática, último receptor y receptor borrado. (RF-7, RF-15, RF-17)
      Hecho cuando: `ReceiverConnectionsWifiTest` en verde, con corte y reconexión de una sesión y el Wi-Fi apagado, y los de Bluetooth intactos.
- [ ] T11. `app`: permiso `ACCESS_LOCAL_NETWORK` (manifiesto, petición al conectar, explicación y aviso con Ajustes) y los permisos de red. (RF-6, RNF-T1)
      Hecho cuando: `LocalNetworkPermissionTest` en verde: se pide solo cuando el sistema lo exige y al denegar el resto de la app sigue.
- [ ] T12. `app`: la lista de receptores con los Wi-Fi y la hoja «Bluetooth / Wi-Fi (TCP)» de «Añadir receptor». (RF-1, RF-4)
      Hecho cuando: `ReceiverListWifiTest` en verde, también con el Bluetooth apagado, y los de la lista de S005 intactos.
- [ ] T13. `app`: `WifiReceiverFormScreen` y su ViewModel (alta, edición, borrado confirmado, motivos de rechazo). (RF-2, RF-3, RF-5)
      Hecho cuando: `WifiReceiverFormViewModelTest` y `WifiReceiverFormScreenTest` en verde.
- [ ] T14. `app`: diagnóstico con receptor Wi-Fi (causas del fallo, aviso de salida TCP desactivada) y los textos de Correcciones (sin puerto, enlace caído, sin internet). (RF-12, RF-13, RF-14, RF-16)
      Hecho cuando: `WifiDiagnosticsTest` en verde con cada caso y con el Bluetooth sin cambios.
- [ ] T15. `app`: ajustes por receptor bajo la clave Wi-Fi (jalón, antena, inclinación). (RF-18)
      Hecho cuando: `WifiGearKeysTest` en verde: un receptor Wi-Fi y uno Bluetooth no comparten ajustes y editar la IP del Wi-Fi conserva los suyos.
- [ ] T16. `tools`: `servidor_tcp.py` (NMEA de `fixtures/nmea/` por TCP, en bucle, con corte y reanudación) y su test. (Demo)
      Hecho cuando: `uv run --project tools pytest tools` en verde y el servidor sirve una sesión de fixtures.
- [ ] T17. Accesibilidad y textos: etiquetas, 56 dp, es y en y sin literales en código. (RNF-T7, RNF-T8)
      Hecho cuando: `WifiAccessibilityTest` en verde y Lint sin textos sin usar.
- [ ] T18. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files`, `assembleDebug` y la demo en el emulador contra el servidor TCP con corte y reconexión.
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo funciona y la hoja de ruta marca S021 como implementada.
