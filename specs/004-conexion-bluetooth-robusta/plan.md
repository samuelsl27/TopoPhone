# Plan técnico — Spec 004

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/004-conexion-bluetooth-robusta/spec.md`. Sin código.

## Estructura de módulos
Ningún módulo nuevo ni dependencia nueva: todo usa el SDK de Android (CompanionDeviceManager, servicios, notificaciones, PowerManager).

| Módulo | Qué añade | RF |
|---|---|---|
| `receiver` (Android) | `ReceiverSession` con reconexión (estado `Reconnecting`, esperas, 30 min, "Reintentar ahora", NTRIP en pausa, la misma grabación), `ConnectFailure.BUSY` y aviso de NMEA ausente; `ReceiverService` (servicio en primer plano `connectedDevice`, notificación, wakelock); `ReceiverPairing` (CompanionDeviceManager y emparejamiento) | RF-1–RF-4, RF-7–RF-11, RF-13–RF-22, RF-27, RF-28 |
| `data` | `lastReceiver` (dirección y nombre) y `notificationsAsked` en las preferencias del receptor | RF-5, RF-24 |
| `app` | `ReceiverConnections` (un único punto para conectar: servicio + sesión + último receptor), conexión automática al arrancar, tarjeta Conexión con reconexión y diagnósticos, "Añadir receptor", permiso de notificaciones, ayuda de segundo plano en Ajustes, apertura de Receptor desde la notificación, "Reconectando" en la barra de estado | RF-1–RF-6, RF-9, RF-12, RF-13, RF-15, RF-16, RF-20, RF-22–RF-28 |

## Modelo de datos
```kotlin
ConnectionState += Reconnecting(description, attempt: Int, nextAttemptAtMs: Long?, lostAtMs: Long)   // nextAttemptAtMs null: intentándolo ahora
ConnectFailure  += BUSY            // rechazo en menos de 3 s (RF-27)
NtripState      += Paused          // NTRIP detenido mientras se reconecta (RF-18)
ReceiverSnapshot += nmeaMissing: Boolean   // 10 s con la conexión abierta sin ninguna frase válida (RF-28)
ReceiverPreferences += lastReceiver: Flow<SavedReceiver?>, saveLastReceiver(), notificationsAsked: Flow<Boolean>, markNotificationsAsked()
data class SavedReceiver(val address: String, val name: String)
```
DataStore "receiver": `last_receiver_address`, `last_receiver_name`, `notifications_asked`.

## Algoritmos
### Reconexión dentro de la sesión (RF-13–RF-22; RNF-T3)
```
connect(transport, reopen: (() -> ReceiverTransport)? = null)    // reopen: cómo volver a abrir el mismo receptor
bucle de la conexión:
  leer hasta que se corte
  si el usuario desconectó o reopen == null: Lost (S002) y fin
  lostAt = ahora; intento = 0; índice = 0
  si había NTRIP: recordar su configuración (en memoria), cancelarlo, NtripState.Paused        // RF-18
  repetir:
    si ahora − lostAt ≥ 30 min: Lost; cerrar grabación; fin                                    // RF-20
    espera = ESPERAS[min(índice, 5)]   con ESPERAS = 1, 2, 4, 8, 16, 30 s                         // RF-14
    Reconnecting(intento, siguiente = ahora + espera)
    esperar(espera) o hasta "Reintentar ahora" (entonces índice = 0)                            // RF-16
    intento++; Reconnecting(intento, siguiente = null)
    t = reopen(); abrir con plazo de 10 s
    si falla: índice++; seguir                                                                 // también con el Bluetooth apagado (RF-22)
    si abre: nueva época vacía (assembler nuevo); Receiving con la primera frase (RF-17);
             misma grabación (RF-19); si había NTRIP: startNtrip(configuración recordada) (RF-18); volver a leer
```
- `disconnect()` cancela el bucle en cualquier punto (RF-21) y cierra la grabación.
- La grabación ya no se cierra al perder el enlace, sino al desconectar, al rendirse o al conectar otro receptor.
- El vigilante (épocas, "Sin datos", vaciado de la grabación) sigue vivo durante la reconexión. "Sin datos" no sustituye a `Reconnecting`.

### Diagnóstico (RF-27, RF-28)
- `open()` mide el tiempo hasta el fallo: si es un error y no un plazo vencido, y tarda menos de 3 s → `BUSY`.
- El vigilante marca `nmeaMissing` cuando la conexión lleva 10 s abierta sin ninguna frase válida, y lo quita con la primera.

### Notificación (RF-8, RF-11; RNF-T2)
- `ReceiverService` observa la sesión muestreada a 1 Hz.
- Texto según el estado:
  - "Fija · correcciones 1,0 s";
  - "Sin datos";
  - "Reconectando · intento 3".
- Sigue en primer plano mientras el estado sea `Connecting`, `Receiving`, `NoData` o `Reconnecting`; en cualquier otro estado quita la notificación y se detiene.
- Wakelock parcial mientras sigue: se adquiere con un plazo de 10 min y se renueva cada 5 min.

### Conexión automática (RF-24–RF-26)
- Al arrancar el proceso, y una sola vez, se intenta si se cumplen todas estas condiciones:
  - hay un último receptor;
  - el Bluetooth está encendido y el permiso concedido;
  - el receptor sigue emparejado;
  - no hay ninguna conexión ni reproducción.
- Cada vez que se llega a "Recibiendo" se guarda el último receptor.
- Un fallo de la conexión inicial queda en `Failed`: la reconexión solo existe tras haber recibido datos (RF-26).

### Añadir un receptor (RF-1–RF-4)
```
si el móvil tiene companion_device_setup:
  CompanionDeviceManager.associate(filtro Bluetooth clásico, sin filtrar por nombre)  → diálogo del sistema
  resultado → BluetoothDevice
  si no está emparejado: createBond() → el sistema pide el PIN → esperar ACTION_BOND_STATE_CHANGED
  emparejado → ReceiverConnections.connect(dirección)
  cancelado / fallo → mensaje (RF-4)
si no: abrir los ajustes de Bluetooth del móvil (la vía de S002)
```

## Contratos
- `ReceiverSession.connect(transport, reopen)`, `retryNow()`, `disconnect()`.
- `ReceiverConnections.connect(address)` (en `app`) arranca el servicio y llama a `session.connect(bluetooth.transportFor(address)) { bluetooth.transportFor(address) }`. Es lo único que usan:
  - la tarjeta Conexión;
  - "Reconectar";
  - "Añadir receptor";
  - la conexión automática.
- `ReceiverService`:
  - acción `DISCONNECT` → `session.disconnect()` (RF-10);
  - la notificación abre `MainActivity` con `EXTRA_OPEN_RECEIVER`, que navega a Receptor (RF-9).
- Manifiesto:
  - **`app`:** el servicio con `foregroundServiceType="connectedDevice"`.
  - **`receiver`:** los permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` y `WAKE_LOCK`, y `uses-feature android.software.companion_device_setup` con `required="false"`.

Textos que ve el usuario:

| Caso | Texto |
|---|---|
| Reconectando | "Reconectando con %s… (intento %d, siguiente en %d s)" |
| Rendición | "Se ha perdido la conexión con %s" + "Reconectar" (el de S002) |
| BUSY | "El receptor ha rechazado la conexión. Lo más probable es que otro dispositivo, como Emlid Flow, esté conectado a él por Bluetooth: desconéctalo y vuelve a intentarlo." |
| NMEA ausente | "Conectado, pero el receptor no envía posición. Activa en Emlid Flow la salida de posición por Bluetooth (NMEA)." |
| Notificaciones denegadas | "Sin permiso de notificaciones no verás la conexión en segundo plano, aunque sigue activa." + "Activar" |
| Barra de estado | "Reconectando" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| La reconexión vive dentro de `ReceiverSession` (con `reopen`) | Una sola fuente de verdad (ADR-11): grabación, NTRIP y épocas siguen en el mismo sitio; sin `reopen`, S002 se comporta igual | Un supervisor externo que vuelva a llamar a `connect()` (cortaría la grabación) |
| Servicio en primer plano `connectedDevice` en `receiver` | RNF-T2; es el tipo que Android exige para un dispositivo externo | Mantener la conexión solo desde el ViewModel |
| CompanionDeviceManager para buscar el receptor | Sin permiso de localización ni de búsqueda; diálogo del sistema; permite el servicio en segundo plano | Búsqueda propia con BLUETOOTH_SCAN |
| `required="false"` y la vía de S002 como reserva | Algunos móviles no traen companion_device_setup | Exigir la característica (excluiría móviles) |
| Wakelock con plazo de 10 min renovado | Lint y seguridad ante un fallo que no lo libere | Wakelock sin plazo |
| Rechazo en menos de 3 s como `BUSY` | Lo más útil sin API para saber si el receptor está ocupado; se valida en campo (suposición de la spec) | Mostrar siempre todas las causas |
| Sin pedir la exención de optimización de batería | Política de Google Play; la ayuda abre los ajustes de la app | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

## Estrategia de tests
- **JVM `receiver`** (`ReceiverSessionReconnectTest`), con esperas cortas inyectadas (`backoffMs`, `giveUpAfterMs`) y transportes falsos que fallan N veces:
  - estados y esperas;
  - "Reintentar ahora";
  - rendición;
  - desconectar durante la reconexión;
  - NTRIP en pausa y reanudado con la misma configuración;
  - una sola carpeta de sesión;
  - `BUSY` frente a plazo vencido;
  - `nmeaMissing`.
- **Robolectric `receiver`:**
  - `ReceiverService`: notificación, texto, acción Desconectar, se detiene al desconectar, wakelock (`ShadowPowerManager`);
  - `ReceiverPairing`: resultado del diálogo, emparejamiento por difusión `ACTION_BOND_STATE_CHANGED`.
- **`data`:** el último receptor y `notificationsAsked`.
- **Robolectric `app`:**
  - tarjeta Conexión en `Reconnecting` y tras rendirse;
  - mensajes de diagnóstico;
  - "Añadir receptor" con un emparejamiento falso;
  - permiso de notificaciones;
  - ayuda de segundo plano;
  - conexión automática (condiciones);
  - apertura de Receptor desde la notificación;
  - "Reconectando" en la barra de estado.
- **Manual, con el RS3:** emparejar desde la app, 30 min con la pantalla apagada, apagar y encender el receptor, Emlid Flow conectado, y un móvil Xiaomi o Samsung.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1–RF-4 | `ReceiverPairing`, "Añadir receptor" | `ReceiverPairingTest`, `AddReceiverTest` |
| RF-5, RF-6 | Permiso de notificaciones | `NotificationPermissionTest` |
| RF-7, RF-8, RF-11 | `ReceiverService` | `ReceiverServiceTest` |
| RF-9, RF-10 | Notificación | `ReceiverServiceTest`, `OpenReceiverFromNotificationTest` |
| RF-12 | Ayuda de segundo plano | `BackgroundHelpTest` |
| RF-13–RF-22 | Reconexión en la sesión y tarjeta Conexión | `ReceiverSessionReconnectTest`, `ReconnectingCardTest` |
| RF-23 | Barra de estado | `StatusBarStateTest`, `StatusBarTest` |
| RF-24–RF-26 | Último receptor y conexión automática | `ReceiverPreferencesTest`, `AutoConnectTest` |
| RF-27, RF-28 | Diagnósticos | `ReceiverSessionReconnectTest`, `ConnectionDiagnosisTest` |
