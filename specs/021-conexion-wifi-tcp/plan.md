# Plan técnico — Spec 021

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-17, ruteo de red), `specs/021-conexion-wifi-tcp/spec.md`, `specs/004-conexion-bluetooth-robusta/plan.md` (reconexión, `ReceiverConnections`), `specs/007-ntrip-perfiles-y-sourcetable/plan.md` (NTRIP y su `SocketFactory`), `specs/046-antena-y-jalon/plan.md` (`receiver_gear` por receptor) y `specs/016-inclinacion-rs3/plan.md` (modo de inclinación por receptor).

Sin dependencias nuevas, ni Gradle ni Python (el servidor TCP de la demo es Python de la biblioteca estándar). **Room pasa de la v33 a la v34.** No hay módulo `core:testing` (la spec lo cita): el servidor TCP falso de los tests vive en `receiver/src/test` y no se crea un módulo nuevo.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Identidad de un receptor Wi-Fi (RF-5, RF-18) | `WifiReceiver(id, name, host, positionPort, correctionsPort?)` con `id` = UUID. La **clave de receptor** pasa a ser un texto: la dirección Bluetooth (como hoy), `replay`, o `wifi:<id>`. Esa clave es la que ya usan el último receptor (`SavedReceiver.address`), el equipo (`receiver_gear.receiver_key`), el modo de inclinación y `ReceiverConnections.currentAddress`; no hay que migrar nada de lo existente y el prefijo `wifi:` no choca con una dirección Bluetooth | Un tipo cerrado `ReceiverKey` en todas esas piezas (toca muchos más sitios) |
| D2 | Dónde se guardan (RF-4, RNF-T6) | Tabla Room `wifi_receivers(id PK, name, host, position_port, corrections_port NULL, created_at)`: **v34**, `AutoMigration(33, 34)` y su `34.json`. Entra en `DatabaseSnapshot.TABLES` (copia completa; no hay paquete de proyecto porque no es de ningún proyecto), como ya hacen los perfiles de antena y el equipo | DataStore con un JSON (sin transacción ni copia coherente) |
| D3 | El transporte TCP (RF-7, RF-14) | `TcpTransport` en `receiver/transport` implementa `ReceiverTransport`: abre un `Socket` creado por una `SocketFactory` que se le inyecta (la de la red Wi-Fi en la app, la de por defecto en los tests), `connect` con plazo de 10 s, `tcpNoDelay` y `keepAlive`. La sesión no cambia: ya abre con plazo, lee, vigila y reconecta con cualquier `ReceiverTransport`. Un rechazo en menos de 3 s ya es `BUSY` (S004/RF-27) | Un cliente propio con `SocketChannel` (más código para el mismo resultado) |
| D3b | Descripción | `nombre (host:puerto)`, como el Bluetooth usa `nombre (dirección)` | — |
| D4 | Correcciones (RF-11, RF-12) | `ReceiverTransport` gana `acceptsCorrections: Boolean` (por defecto `true`). Con **puerto de correcciones vacío**: `false` y `output` descarta. Con el **mismo puerto** que el de posición: `output` es el del mismo socket. Con **otro puerto**: ver D5 | Un puerto por defecto para las correcciones (no está documentado) |
| D5 | Puerto de correcciones distinto (RF-11, RF-13) | `CorrectionsLink`: un segundo socket que abre el transporte en segundo plano **después** de abrir el de posición, con su propia cola de escritura y su propio reintento con las esperas de S004/RF-14 (1, 2, 4, 8, 16 y luego 30 s). Si no se abre o falla una escritura, la posición **no se corta**: el estado `CorrectionsLinkStatus` (`Connecting`, `Ready`, `Failed(intento, próximo en ms)`) lo cuenta la sesión en `ReceiverSnapshot.correctionsLink` y la pantalla Correcciones lo dice (RF-13). Las correcciones que llegan con el enlace caído se descartan, no se encolan (RTCM atrasado no sirve) | Un solo reintento por escritura |
| D6 | Sin puerto de correcciones (RF-12) | `NtripState.NoCorrectionsPort`: la sesión, al ver `acceptsCorrections = false`, no abre el caster y lo dice. La pantalla Correcciones muestra «Este receptor debe recibir las correcciones por otra vía» | Conectar al caster igualmente y tirar los datos (gasta datos móviles) |
| D7 | Encaminar el tráfico del receptor (RF-8) | `NetworkRouter` (en `receiver/platform`, con un fake en tests): `wifiNetwork()` pide con `ConnectivityManager.requestNetwork` una red `TRANSPORT_WIFI` **sin exigir `INTERNET`**, y mantiene la petición mientras dura la conexión (Android no la deja caer por no tener internet). El `TcpTransport` crea sus sockets con `network.socketFactory`, que los ata a esa red aunque no sea la de por defecto. Sin Wi-Fi: `WifiUnavailableException` | `bindProcessToNetwork` (ataría también el NTRIP y el mapa a la Wi-Fi sin internet) |
| D8 | Encaminar el NTRIP (RF-9, RF-10) | `NetworkRouter.internetNetwork()` pide una red con `INTERNET` y `VALIDATED`. Una `RoutedSocketFactory` (para `NtripClient` y `SourcetableClient`, que ya reciben una `SocketFactory`) crea cada socket en esa red cuando el receptor es Wi-Fi, y la de por defecto con Bluetooth (sin cambios). Si no hay red con internet: `NtripState.NeedsInternet` («el NTRIP necesita datos móviles activos»), sin tocar la conexión del receptor, y se reintenta cuando aparece una red (callback, no sondeo) | Cambiar la red por defecto del proceso |
| D9 | Permiso de red local (RF-6) | `ACCESS_LOCAL_NETWORK` (existe en el SDK 37) declarado en el manifiesto y pedido en tiempo de ejecución **solo** al conectar con un receptor Wi-Fi y solo si el sistema lo exige (API ≥ 37 y sin concederlo), con una explicación en español y, si se deniega, el aviso con el camino a Ajustes (patrón de `NotificationPermission`). También se declaran `ACCESS_NETWORK_STATE` y `CHANGE_NETWORK_STATE` (permisos normales, sin diálogo) para pedir las redes. Nada de `NEARBY_WIFI_DEVICES` (no se busca nada, RF-5/RF-6 del borrador salieron) | Pedir el permiso al abrir la app |
| D10 | `ReceiverConnections` (RF-7, RF-15, RF-17) | `connect(key)` decide el transporte por la clave: Bluetooth como hoy, o `wifi:<id>` → lee el `WifiReceiver` del repositorio y crea un `TcpTransport` sobre la red Wi-Fi. `reopen` repite eso (con el Wi-Fi apagado lanza `WifiUnavailableException`, que la reconexión trata como cualquier corte, igual que `BluetoothOffException`). `autoConnect` admite `wifi:` si el receptor guardado sigue existiendo (sin las comprobaciones de Bluetooth). Un receptor Wi-Fi **borrado** que era el último deja de recordarse | Una clase aparte para Wi-Fi |
| D11 | Diagnóstico (RF-14, RF-16) | `ConnectFailure` no cambia (TIMEOUT, ERROR, BUSY); la pantalla del receptor elige los textos según el tipo de la clave actual: con `wifi:` explica las causas del RF-14 (móvil fuera de la red del receptor, dirección o puerto mal, salida por TCP desactivada, otro cliente conectado), y el aviso de «sin frases NMEA durante 10 s» de S004/RF-28 dice «salida de posición por TCP» y dónde activarla | Un `ConnectFailure` nuevo por causa (no se pueden distinguir desde fuera) |
| D12 | Interfaz (RF-1 a RF-5) | «Añadir receptor» abre una hoja con «Bluetooth» (lo de S004, sin cambios) y «Wi-Fi (TCP)», que abre `WifiReceiverFormScreen` (alta y edición, con el borrado confirmado). La lista de receptores muestra los Wi-Fi aunque el Bluetooth esté apagado o sin permiso, con su dirección y puerto y la marca de último usado | Pantalla aparte para los Wi-Fi |
| D13 | Validación (RF-3) | `WifiReceiverValidator` en `core:model`: dirección no vacía y sin espacios, ni URL ni puerto dentro (`192.168.42.1`, un nombre de host con letras, dígitos, guiones y puntos, o una IPv6 entre corchetes), puertos enteros de 1 a 65535 (los de menos de 3000 están reservados por el fabricante: se avisa pero se deja, es el receptor quien manda) | Rechazar los puertos reservados |
| D14 | Referencia independiente y demo | `tools/simulador/servidor_tcp.py`, con la biblioteca estándar, sirve por TCP un NMEA de `fixtures/nmea/` en bucle y puede cortar y volver a aceptar; con `pytest` de su lógica de repetición. En el emulador el servidor corre en el PC (el móvil lo ve en `10.0.2.2`) | Un servidor Kotlin dentro de la app (código de producción solo para una demo) |
| D15 | Toques al código de specs protegidas | Mínimos y acordados antes: **S013** (`DatabaseSnapshot.TABLES`: una fila `wifi_receivers`); **S006** (`TopoPhoneDatabase`: entidad, v34 y `AutoMigration`); **S002 a S005 y S007** (`ReceiverTransport`, `ReceiverSession`, `ReceiverConnections`, `ReceiverViewModel`, `ReceiverList`, `CorrectionsScreen`, `ReceiverModule`). No se tocan los `spec.md` anteriores | — |

## Estructura de módulos
- `core:model` (`receiver/`): `WifiReceiver`, `WifiReceiverDraft`, `WifiReceiverValidator` (D13), `ReceiverKey` (D1: `wifi(id)`, `isWifi`, `wifiId`).
- `data`: `receiver/WifiReceiverEntity`, `WifiReceiverDao` y `WifiReceiverRepository` (observar, alta, edición, borrado, por id); `db/TopoPhoneDatabase` (D2); `backup/DatabaseSnapshot.TABLES`.
- `receiver`:
  - `transport/TcpTransport`, `CorrectionsLink` y `CorrectionsLinkStatus` (D3 a D5); `ReceiverTransport.acceptsCorrections` y `correctionsStatus`.
  - `platform/NetworkRouter` y `AndroidNetworkRouter`, `RoutedSocketFactory`, `WifiUnavailableException` (D7, D8).
  - `session/ReceiverSession`: `correctionsLink` en el snapshot, `NtripState.NoCorrectionsPort` y `NeedsInternet` (D6, D8).
- `app`
  - `receiver/ReceiverConnections` (D10), `LocalNetworkPermission` (D9), `di/ReceiverModule` (NTRIP con `RoutedSocketFactory`).
  - `ui/receiver/`: la hoja de tipo, `WifiReceiverFormScreen` y su ViewModel, la lista (D12), textos de diagnóstico (D11) y de Correcciones.
  - `AndroidManifest.xml` (D9), textos `wifi_*` en es y en.
- `tools`: `simulador/servidor_tcp.py` y su test (D14).

## Modelo de datos
- Room v34: `wifi_receivers(id TEXT PK, name TEXT, host TEXT, position_port INTEGER, corrections_port INTEGER NULL, created_at INTEGER)`.
- Clave de receptor: `wifi:<uuid>` (D1); en `receiver_gear.receiver_key`, el último receptor y el modo de inclinación va tal cual.
- Ejemplo: `{id: "3f2a…", name: "RS3 hotspot", host: "192.168.42.1", positionPort: 9001, correctionsPort: null}`.

## Algoritmos
### A1. Abrir un receptor Wi-Fi (RF-7, RF-8, RF-14)
1. `NetworkRouter.wifiNetwork()` (o `WifiUnavailableException`); 2. `TcpTransport.open()`: `socket = network.socketFactory.createSocket()`, `connect(host:positionPort, 10 s)`; 3. si hay puerto de correcciones distinto, arrancar `CorrectionsLink` en segundo plano; 4. la sesión pasa a «Recibiendo» con la primera frase válida (S002/RF-6). Todo el plazo, la detección de `BUSY` y la reconexión son los de `ReceiverSession`.
### A2. Escribir correcciones (RF-11, RF-12, RF-13)
`output.write(bytes)`: si `acceptsCorrections` es falso no se llega (D6); con el mismo puerto va al socket de posición; con otro va a la cola de `CorrectionsLink`, que escribe si está `Ready` y, si no, descarta el trozo. Un `IOException` al escribir cierra ese socket, marca `Failed(n, próximo)` y reabre tras la espera `[1, 2, 4, 8, 16, 30, 30…] s`; al abrir vuelve a `Ready` y el contador a 0.
### A3. Red para el caster (RF-9, RF-10)
Con receptor Wi-Fi, `RoutedSocketFactory.createSocket()` pregunta a `NetworkRouter.internetNetwork()`; si hay, `network.socketFactory.createSocket()`; si no, lanza `NoInternetException`, que el conector NTRIP convierte en `NtripState.NeedsInternet` y espera al callback de red (sin sondeo).

## Contratos
- `NetworkRouter`: `suspend fun wifiNetwork(): Network?`, `fun internetNetwork(): Network?`, `val changes: Flow<NetworkChange>` (para avisar en menos de 3 s de un cambio de red) y `fun release()`.
- `ReceiverTransport.correctionsStatus: StateFlow<CorrectionsLinkStatus?>` (null si no aplica).
- Textos (es y en, prefijo `wifi_*`): «Wi-Fi (TCP)», nombre, dirección, puerto de posición, puerto de correcciones (opcional), motivos de rechazo del formulario, causas probables de RF-14, el aviso de salida TCP desactivada, «el receptor debe recibir las correcciones por otra vía», «el NTRIP necesita datos móviles activos», la explicación del permiso de red local y cómo concederlo, estados del enlace de correcciones.

## Estrategia de tests
- Unitarios JVM (`core:model`): `WifiReceiverValidatorTest`, `ReceiverKeyTest`.
- Unitarios JVM (`receiver`): `TcpTransportTest`, `TcpCorrectionsTest`, `CorrectionsLinkTest` (con `FakeTcpReceiver`: un `ServerSocket` en `localhost:0` que sirve un NMEA de `fixtures/nmea/`, acepta o rechaza, corta y vuelve a aceptar); `ReceiverSessionWifiTest` (estado del enlace de correcciones, sin puerto, sin internet); `NetworkRouterTest` (con la fuente falsa).
- Robolectric (`data`): `WifiReceiverRepositoryTest`, migración v33 a v34 en `TopoPhoneDatabaseTest`, `WifiBackupTest`.
- Robolectric (`app`): `ReceiverConnectionsWifiTest`, `WifiReceiverFormViewModelTest`, `WifiReceiverFormScreenTest`, `ReceiverListWifiTest`, `WifiDiagnosticsTest`, `LocalNetworkPermissionTest`, `WifiGearKeysTest`, `WifiAccessibilityTest`; los `viewModelScope` se cancelan antes de cerrar la base y los nombres de test van cortos por Windows.
- Python: `tools/simulador/test_servidor_tcp.py`.
- Manual: demo del emulador. Campo: queda para el usuario (RS3 en hotspot con NTRIP por datos móviles y montaje A/B).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | D12 | `ReceiverListWifiTest` |
| RF-2 | D12, D13 | `WifiReceiverFormScreenTest`, `WifiReceiverValidatorTest` |
| RF-3 | D13 | `WifiReceiverValidatorTest`, `WifiReceiverFormViewModelTest` |
| RF-4 | D2, D12 | `WifiReceiverRepositoryTest`, `ReceiverListWifiTest` |
| RF-5 | D1 | `WifiReceiverRepositoryTest`, `WifiReceiverFormViewModelTest`, `ReceiverConnectionsWifiTest` |
| RF-6 | D9 | `LocalNetworkPermissionTest` |
| RF-7 | D3, D10, A1 | `TcpTransportTest`, `ReceiverConnectionsWifiTest` |
| RF-8 | D7 | `NetworkRouterTest`, `TcpTransportTest` |
| RF-9 | D8, A3 | `NetworkRouterTest`, `ReceiverSessionWifiTest` |
| RF-10 | D8, A3 | `ReceiverSessionWifiTest` |
| RF-11 | D4, D5, A2 | `TcpCorrectionsTest`, `CorrectionsLinkTest` |
| RF-12 | D4, D6 | `TcpCorrectionsTest`, `ReceiverSessionWifiTest`, `WifiDiagnosticsTest` |
| RF-13 | D5 | `CorrectionsLinkTest`, `ReceiverSessionWifiTest` |
| RF-14 | D3, D11 | `TcpTransportTest`, `WifiDiagnosticsTest` |
| RF-15 | D10 | `ReceiverConnectionsWifiTest`, `ReceiverSessionWifiTest` |
| RF-16 | D11 | `WifiDiagnosticsTest` |
| RF-17 | D10 | `ReceiverConnectionsWifiTest` |
| RF-18 | D1 | `WifiGearKeysTest` |
| RNF-T1 | D9 | `LocalNetworkPermissionTest` |
| RNF-T4 | D7, D8 | `NetworkRouterTest` |
| RNF-T6 | D2 | `TopoPhoneDatabaseTest`, `WifiBackupTest` |
| RNF-T7, RNF-T8 | textos y 56 dp | `WifiAccessibilityTest` |
| RNF-T12 | A2 | `ReceiverSessionWifiTest` |
| Primera época en menos de 3 s | A1 | `TcpTransportTest` |
| Cambio de red avisado en menos de 3 s | D7 | `NetworkRouterTest` |

## Cambios al implementar
- **D7, la red se pide al abrir, no al construir el transporte:** `WifiSocketFactory` (en `receiver/platform`) pide la red Wi-Fi a `NetworkRouter` cada vez que se crea un socket (hasta 3 s). Así una reconexión con el Wi-Fi apagado falla en la apertura (`WifiUnavailableException`) y la reconexión de S004 la reintenta; `reopen` no necesita lanzar la excepción él mismo. `ReceiverConnections.connect(key)` con `wifi:<id>` es asíncrono (lee el receptor del repositorio) y `reopen` vuelve a leerlo, de modo que una IP o un puerto editados entre medias valen.
- **D8, `NetworkRouter.internetAvailable`:** además de `changes`, un `StateFlow<Boolean>`. Esperar un `SharedFlow` sin repetición podía perder el aviso de una red que aparece justo entre la comprobación y la suscripción (lo vio `ReceiverSessionWifiTest`). Si aun así se perdiera, la sesión vuelve a mirar cada 30 s.
- **D8, qué conector usa la sesión:** `ReceiverTransport.routesNtripOverNetwork` (verdadero solo en `TcpTransport`) y dos parámetros nuevos de `ReceiverSession` (`networkNtripConnector` y `networkRouter`). `NoInternetException` sale de `RoutedSocketFactory.createSocket()` fuera del `try` de `NtripClient`, y la sesión la convierte en `NeedsInternet` sin contar un intento. La tabla de fuentes usa `NetworkAwareSourcetable` (`app`), que decide en cada descarga según la clave del receptor en uso.
- **`NeedsInternet` y la alarma:** cuenta como NTRIP abierto para la alarma por edad de las correcciones (S007/RF-29): sin internet no llegan correcciones y el usuario debe enterarse.
- **D5, sin cola propia:** `CorrectionsLink` es un `OutputStream` que escribe directamente en el segundo socket desde la cola única de la sesión (ADR-11) y descarta si el enlace está caído; no tiene cola aparte. Un hilo lee el socket para notar un corte sin escribir, y su reloj es el monótono de la sesión (la cuenta atrás de la pantalla usa el mismo).
- **Grabación de correcciones:** la sesión graba solo lo que de verdad se ha enviado: con el enlace de correcciones caído (que lo descarta) no se graba (S003/RF-24, «tal como se envían»).
- **D6, texto:** «Este receptor debe recibir las correcciones por otra vía…» como dice el plan; `NtripState.NoCorrectionsPort` también está en el log y en el estado de Correcciones.
- **D9, permiso:** `ACCESS_LOCAL_NETWORK` va en el manifiesto del módulo `receiver` junto a `CHANGE_NETWORK_STATE`. La petición es de `ReceiverViewModel` (`askLocalNetwork`, `localNetworkDenied`): «Ahora no» cuenta como denegación y el aviso lleva a los ajustes de la app. Con el permiso denegado no se intenta conectar; el Bluetooth y el resto de la app no cambian.
- **D10, receptor borrado:** si se borra el receptor Wi-Fi con el que se está conectado, `ReceiverConnections` cierra la conexión. El «último receptor» no se olvida explícitamente: la conexión automática comprueba que siga existiendo y el siguiente que se conecte lo sustituye.
- **D12, «Añadir receptor»:** el botón sale de la sección Bluetooth (si no, no se podía añadir un Wi-Fi con el Bluetooth apagado) y abre siempre la hoja. Por eso **dos tests de S004** (`AddReceiverTest`) pulsan ahora antes «Bluetooth»; el resto de los de la lista de S005 no cambió.
- **D11, diagnóstico:** `ReceiverUiState.receiverKey` sale de `ReceiverConnections.currentAddress` (también cubre la conexión automática) y de ahí `wifiReceiver`. Un rechazo inmediato (menos de 3 s) con Wi-Fi también es `BUSY` y tiene su texto propio (otro cliente, puerto equivocado o salida TCP desactivada).
- **Tests de tests:** `FakeTcpReceiver` vive en `receiver/src/test` (no hay módulo de testing compartido), así que los tests de `app` levantan su propio servidor local. `TraverseDraftViewModelTest` (S038) tenía una carrera (esperaba el punto de inicio pero no los tramos) que apareció con la carga de este trabajo; ahora espera a los dos.
- **D15, código de specs protegidas:** tocado lo acordado: `DatabaseSnapshot.TABLES` (S013), `TopoPhoneDatabase` (S006), y de S002 a S007 `ReceiverTransport`, `ReceiverSession`, `ReceiverConnections`, `ReceiverViewModel`, `ReceiverList`/`ReceiverScreen`/`ReceiverHeader`, `CorrectionsScreen`/`CorrectionsController`, `ReceiverLogRecorder` y `ReceiverModule`. Los `spec.md` anteriores no se han tocado.

## Validación (T18)
- `rf_report.py`: S021 con 18 RF y los 18 con test, 0 pendientes. `./gradlew check` y `uv run --project tools pytest tools` (613 tests) en verde; `forbidden_files` sin hallazgos; `assembleDebug` compila.
- **Demo en el emulador (TopoPhone_API36, Android 16; el permiso de red local no se exige) contra `tools/simulador/servidor_tcp.py` en el PC (10.0.2.2:9001, correcciones en el 9002, corte cada 40 s de 8 s):**
  - «Añadir receptor» abre la hoja «Bluetooth / Wi-Fi (TCP)» incluso con el permiso de Bluetooth sin conceder. El formulario sale con 192.168.42.1 y 9001; con `http://x` no guarda y dice «La dirección no es válida…»; con 10.0.2.2, 9001 y 9002 guarda y sale en la lista «Receptores Wi-Fi (TCP)» con «10.0.2.2:9001 · correcciones 9002» y su «Editar RS3 demo».
  - Al pulsarlo: «Recibiendo datos», «Fija», 28 satélites, «Grabando», y el servicio en primer plano con la notificación «Receptor RS3 demo (10.0.2.2:9001)».
  - Perfil NTRIP «Red de Bizkaia» (acceso libre): la tabla de fuentes se descarga con el receptor Wi-Fi conectado (por la red con internet), «Recibiendo correcciones» con mensajes RTCM 1004, 1006, 1008, 1012…, y el servidor cuenta los mismos bytes en su puerto 9002 (más de 34 000 en la demo).
  - Corte del servidor: la app pasa a «Reconectando… Intento 1 · siguiente en 1 s» y, al restablecerse el servicio, a «Recibiendo datos» y las correcciones siguen llegando; la grabación sigue abierta.
  - Puerto 9005 sin servidor: «El receptor ha rechazado la conexión» con la causa del Wi-Fi (otro cliente, puerto equivocado o salida TCP desactivada).
  - Receptor sin puerto de correcciones (editado vacío, misma identidad): al pulsar el perfil NTRIP, «Este receptor debe recibir las correcciones por otra vía…» y el caster no se abre.
- **Sin comprobar en el emulador:** el permiso `ACCESS_LOCAL_NETWORK` (Android 17+; los tests lo cubren), el NTRIP sin red con internet (datos móviles apagados; lo cubren `ReceiverSessionWifiTest` y `NetworkRouterTest`), el segundo socket de correcciones con un puerto distinto del de posición y su corte (lo cubren `CorrectionsLinkTest`; en el emulador solo se usó el puerto 9002 de la demo, que sí es distinto del 9001, y el corte de ese puerto no), los textos en inglés en el dispositivo (solo en tests) y un receptor Reach real.
- **Sin medir:** el plazo de «cambio de red avisado en menos de 3 s» solo en la JVM (`NetworkRouterTest`), no con las redes reales de un móvil.
- **Incidencias del trabajo:** el aviso de un monitor se disparó con el log de otra tarea (misma etiqueta que una de S038) y se leyó mal un instante; varios `check` fallaron por tests ajenos intermitentes de `app` (`TiltReplayTest`, `DesignMapViewModelTest`, `LineStakeoutSegmentsTest`, `CollectorLineViewModelTest`…) y se repitieron; un test mío de corte de `CorrectionsLink` era intermitente con la carga y se hizo robusto (espera a la conexión y no al estado).
