# Plan técnico — Spec 022

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (receiver: transportes y `ReceiverSession`), `specs/022-gnss-movil-y-receptor-virtual/spec.md`, `specs/003-flujo-nmea-y-simulador/plan.md` (reproducción: `simulated`), `specs/021-conexion-wifi-tcp/plan.md` (clave de receptor como texto), `specs/047-tipos-de-solucion/spec.md` (SINGLE admitido con confirmación).

Sin dependencias nuevas, ni Gradle ni Python: la ubicación es `android.location` de la plataforma y el generador de escenarios es Kotlin puro. **Room pasa de la v34 a la v35** (una columna nueva en `points`).

La idea central: **las dos fuentes nuevas son transportes que entregan NMEA**, igual que el Bluetooth, el TCP y la reproducción. El generador del receptor virtual y el traductor de la ubicación del móvil escriben frases GGA, GST, GSA, GSV, ZDA (y EBP y ETC en el virtual) y la `ReceiverSession` las lee con los mismos analizadores de siempre. Así el recolector, el replanteo, el mapa y la barra de estado no cambian de camino; solo cambian la fuente que se muestra y lo que se guarda.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Clave de receptor (RF-1, RF-12) | `ReceiverKey` gana `phone` y `virtual` (junto a la dirección Bluetooth, `wifi:<id>` y `replay`), con `isPhone` e `isVirtual`. Son fuentes fijas: no hay alta ni edición, solo una fila en la lista. Con ellas se guardan el equipo (jalón y antena) y el modo de inclinación como con cualquier otra clave. | Un `PhoneReceiver` con identidad en Room, como el Wi-Fi (S021). Descartada: no tiene datos propios que guardar. |
| D2 | Qué se guarda con el punto (RF-7, RF-11, RF-19) | `enum PositionSource { RECEIVER, REPLAY, PHONE, PHONE_MOCK, VIRTUAL }` en `core:model` y `PointQuality.source` (por defecto `RECEIVER`). Room v35: `points.position_source TEXT NULL` con `AutoMigration(34, 35)` y su `35.json`; NULL equivale a `RECEIVER` o, si `from_replay` es verdadero, a `REPLAY`. Entra solo en la copia completa que ya cubre `points`. | Dos columnas (fuente y «posición simulada»). Descartada: `PHONE_MOCK` ya dice las dos cosas. |
| D3 | `fromReplay` y la exportación (RF-19) | `from_replay` se conserva y pasa a decir «simulado»: verdadero con `REPLAY`, `VIRTUAL` y `PHONE_MOCK`. Así la columna «Replay» de «Todas las columnas» (S012/RF-6), el GeoPackage y el Shapefile no cambian de formato ni de cabecera, y los puntos simulados salen marcados. La fuente exacta va en la ficha. S012/RF-6 y S010/RF-18 cambian solo en su texto (ver «Cambios en documentos anteriores»). | Columna nueva «Fuente» en CSV, GeoPackage y Shapefile. Descartada ahora: cambia tres formatos y sus ficheros de referencia; se puede añadir después sin tocar nada de esto. |
| D4 | Generador del receptor virtual (RF-14, RF-15) | En `core:gnss` (`es.topophone.core.gnss.scenario`), sin `android.*`: `Scenario` (nombre, descripción, duración y una función de la posición local, la solución y los eventos en cada segundo), `ScenarioPlayer(scenario, origin, startUtcMillis)` que da la época del segundo n como líneas NMEA (`NmeaWriter`, con suma de control y `Locale.ROOT`). Sin azar: la época n es función solo de (escenario, origen, hora de inicio, n). | Ficheros NMEA grabados en `assets`. Descartada: no serían «a la posición inicial elegida» y pesarían en el APK. |
| D5 | Posición del virtual (RF-13) | El escenario da desplazamientos locales (este, norte, arriba, en metros) respecto al origen; se pasan a latitud y longitud con el elipsoide WGS84 en la propia `NmeaWriter` (aproximación local, suficiente a decenas de metros: error menor que 1 mm a 100 m). El origen es el centro del proyecto (S006/RF-37), o unas coordenadas escritas; sin proyecto y sin coordenadas, el diálogo no deja continuar. | Usar `core:geodesy`. Descartada: `core:gnss` no depende de él (AGENTS.md). |
| D6 | Tiempo (RF-12, RNF-T10) | `VirtualReceiverTransport` entrega una época por segundo a ×1 (cada 200 ms a ×5) con el reloj monótono de la sesión; la hora UTC de la época 0 es la del reloj del móvil al conectar y avanza con el escenario (no con el reloj). La velocidad se cambia en caliente como `ReplayTransport.speed`. Al terminar el escenario el flujo de entrada termina y la sesión pasa a `ScenarioFinished` (como `ReplayFinished`), con «repetir», «elegir otro» y «desconectar». | Un hilo propio con `delay`. Descartada: la sesión ya lee de un `InputStream` bloqueante. |
| D7 | Qué es «simulado» para la sesión (RF-18) | `ReceiverTransport.simulated` ya impide grabar, conectar el NTRIP y reconectar (S003/RF-44, S003/RF-45): lo usa el virtual sin tocar. Nuevo `ReceiverTransport.positionSource: PositionSource` (por defecto `RECEIVER`) que la sesión copia al `ReceiverSnapshot.source`, y `ReceiverSnapshot.scenario: ScenarioInfo?` (nombre, velocidad) junto a `replay`. | Un tipo nuevo de estado de conexión. Descartada: la conexión no cambia, solo qué fuente la alimenta. |
| D8 | El GNSS del móvil como transporte (RF-5, RF-6, RF-8) | `PhoneGnssTransport(source: LocationSource)` en `receiver/transport`. `LocationSource` es una interfaz (`Flow` de ubicaciones con precisión, altura elipsoidal, hora, «es simulada»); `AndroidLocationSource` usa `LocationManager` con el proveedor GNSS (`GPS_PROVIDER`) a 1 Hz y no el combinado de Google (no hay Play Services; ADR de «sin nube»). Cada ubicación se escribe como GGA con calidad 1 (SINGLE) y **separación del geoide 0** y la altura elipsoidal de Android en el campo de la altitud (RF-6), GST con σ = precisión ÷ √2 en cada eje (la horizontal de la app sale igual a la de Android) y, si Android no da vertical, sin σ de la altura; y ZDA. HDOP vacío. | Pasar a la sesión un objeto de posición sin NMEA. Descartada: obligaría a cambiar la sesión, el recolector y la grabación. |
| D9 | «Sin datos» a los 3 s (RF-8, RNF-T3) | `ReceiverTransport.noDataTimeoutMs: Long?` (nulo = el de la sesión, 2 s): 3 000 en el móvil. | Subir el de todos. Descartada: el RS3 sigue a 2 s. |
| D10 | Qué no ofrece el móvil (RF-9, RF-20) | `PhoneGnssTransport.acceptsCorrections = false` y una causa propia: el NTRIP no se abre y la pantalla Correcciones dice «El GNSS del móvil no usa correcciones». La inclinación y el base shift no se ofrecen (menú y botones ocultos con un texto de motivo al pedirlos). No graba sesión: `simulated` es falso pero la sesión no graba cuando `ReceiverTransport.recordsSession` es falso (nuevo, verdadero por defecto). | Marcar el móvil como `simulated`. Descartada: es una posición real y el recolector la usa. |
| D11 | Permisos y servicio (RF-2, RF-3, RF-4, RNF-T1, RNF-T2) | `ACCESS_FINE_LOCATION` (el manifiesto de la app hoy lo **quita** de MapLibre con `tools:node="remove"`: se deja de quitar y se declara a propósito), `FOREGROUND_SERVICE_LOCATION`. El servicio pasa a `connectedDevice|location` en el manifiesto, pero se arranca con `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` **solo** con el móvil y con el de siempre con el resto. El permiso se pide en `ReceiverViewModel` al elegir «GNSS del móvil» (nunca al arrancar). Si está desactivada la ubicación, un aviso con `Settings.ACTION_LOCATION_SOURCE_SETTINGS`. | Pedirlo al arrancar. Descartada: RNF-T1. |
| D12 | Marca de ubicación simulada (RF-7) | `Location.isMock` (API 31+; antes `isFromMockProvider`). Si es verdadera, la fuente pasa a `PHONE_MOCK`, la pantalla lo avisa con un texto y los puntos se guardan así. | Rechazar la posición. Descartada: la spec pide avisar y marcar, y es la forma de probar con el emulador. |
| D13 | Interfaz (RF-1, RF-12, RF-13, RF-16, RF-17) | La lista de receptores muestra siempre dos filas fijas, «GNSS del móvil» y «Receptor virtual», debajo de las de Bluetooth y Wi-Fi (también con el Bluetooth apagado). La hoja de «Añadir receptor» gana esas dos opciones. El virtual abre un diálogo (escenario con descripción y duración, origen, velocidad ×1/×5) y conecta. La cabecera y el indicador de estado muestran «Simulado · nombre del escenario» o «GNSS del móvil · SINGLE» con texto. Al terminar, una hoja con repetir, elegir otro y desconectar. | Una pantalla propia para el virtual. Descartada: es un diálogo, no un flujo. |
| D14 | Puntos (RF-10, RF-11, RF-19) | El recolector y el control de base (`measuringReplay`) pasan a `measuringSource`, leído del snapshot al empezar la medida, y lo guardan en `PointQuality.source` y `fromReplay`. No cambia ninguna regla de S010 ni de S047: con «solo FIX» el móvil (siempre SINGLE) no mide sin confirmar. Un punto del virtual se guarda sin confirmación. | Una confirmación nueva para el móvil. Descartada: lo cubre S047/RF-4 y RF-6. |
| D15 | Último receptor y conexión automática | El móvil y el virtual **no** se reconectan solos al abrir la app: el móvil pediría el permiso sin que el usuario lo pida (RNF-T1) y el virtual no tiene sentido sin elegir escenario. `autoConnect` los ignora y el «último receptor» no los guarda. | Reconectar el último. Descartada por lo dicho. |
| D16 | Toques al código de specs protegidas | Mínimos, y los de S002 a S007 los mismos que en S021: `ReceiverTransport`, `ReceiverSession` (fuente y escenario en el snapshot), `ReceiverConnections`, `ReceiverViewModel`, `ReceiverList`/`ReceiverScreen`/`ReceiverHeader`, `StatusIndicator`, `CorrectionsScreen`, `ReceiverService` (tipo de servicio) y `ReceiverModule`; **S006** (`TopoPhoneDatabase`: v35 y `AutoMigration`), **S010** (`CollectorViewModel`: la fuente al medir; `PointDetailsScreen`: la fuente en la ficha) y **S020** (`BaseShiftViewModel`: `measuringSource`). **S012**: ningún cambio de formato (D3). Los `spec.md` de S010 y S012 cambian solo en una frase de RF-18 y RF-6 (D3), con el diff a la vista. | — |

## Estructura de módulos
- `core:model`: `PositionSource`, `ReceiverKey.phone/virtual` (D1, D2), `PointQuality.source` (D2).
- `core:gnss`:
  - `nmea/NmeaWriter`: escribe GGA, GST, GSA, GSV, ZDA, EBP y ETC con suma de control (D4, D8).
  - `scenario/`: `Scenario`, `ScenarioPlayer`, `Scenarios` (los siete de RF-14) (D4, D5).
- `data`: `points.position_source` (D2), `db/TopoPhoneDatabase` v35.
- `receiver`:
  - `transport/VirtualReceiverTransport`, `PhoneGnssTransport`, `LocationSource` y `AndroidLocationSource` (D6, D8).
  - `ReceiverTransport`: `positionSource`, `noDataTimeoutMs`, `recordsSession` (D7, D9, D10).
  - `session/ReceiverSession`: `source` y `scenario` en el snapshot, `ScenarioFinished`, `NtripState.UnavailableForSource` (D7, D10).
- `app`:
  - `receiver/ReceiverConnections` (claves `phone` y `virtual`, D1, D15), `PhonePermissions` (D11), `ReceiverService` (D11), `di/ReceiverModule`.
  - `ui/receiver/`: filas de la lista, hoja de añadir, `VirtualReceiverDialog`, cabecera, `ScenarioFinishedSheet`, avisos de ubicación y de simulada (D13).
  - `ui/status/StatusIndicator`, `ui/collector/CollectorViewModel`, `ui/baseshift/BaseShiftViewModel`, `ui/points/PointDetailsScreen` (D14).
  - `AndroidManifest.xml` (D11), textos `phone_*` y `virtual_*` en es y en.
- `tools`: `simulador/` no cambia (la demo usa `adb emu geo fix` para el móvil y el propio receptor virtual).

## Modelo de datos
- Room v35: `points.position_source TEXT NULL`: `RECEIVER`, `REPLAY`, `PHONE`, `PHONE_MOCK` o `VIRTUAL`; NULL en los puntos anteriores.
- `Scenario`: `{id, nameRes, descriptionRes, durationS, at(second: Int): ScenarioState}` con `ScenarioState(eastM, northM, upM, solution, satellites, hdop, sigmaH, sigmaV, emit: Set<Sentence>, base: BasePosition?, tilt: TiltData?)`. Los textos del nombre y de la descripción son recursos de `app` (RNF-T7); el `Scenario` lleva solo su identificador.
- Ejemplo: `Scenarios.LOSS_AND_RECOVERY.at(45)` → `ScenarioState(eastM = 12.0, northM = 0.0, solution = SINGLE, satellites = 7, …)`.

## Algoritmos
### A1. Época virtual (RF-14, RF-15)
1. Para el segundo `n` (0 ≤ n < duración): `s = scenario.at(n)`.
2. Hora UTC = `startUtc + n·1000 ms`; posición = `origin + (eastM, northM)` con `dLat = northM / Rm`, `dLon = eastM / (Nm·cos(lat))` y `Rm`, `Nm` los radios de curvatura WGS84 en el origen.
3. Se escriben las frases de `s.emit`: GGA (calidad según `s.solution`: 4 FIX, 5 FLOAT, 1 SINGLE, 0 sin solución), GST (σ de `s`), GSA, GSV, ZDA y, si `s.base` o `s.tilt` no son nulos, EBP y ETC.
4. Cada frase lleva `*` y suma de control de dos dígitos hexadecimales; los números con `Locale.ROOT` y un número fijo de decimales (así dos ejecuciones dan los mismos bytes).
### A2. Escenarios (RF-14)
| Escenario | Duración | Contenido |
|---|---|---|
| FIX estable | 120 s | quieto, FIX, 22 satélites, σh 0,008 m |
| Pérdida de FIX y recuperación | 180 s | FIX hasta 40 s, FLOAT 40–60 s, SINGLE 60–120 s, FLOAT 120–140 s, FIX desde 140 s |
| FLOAT y SINGLE | 150 s | FLOAT 0–60 s, SINGLE 60–100 s, FLOAT 100–150 s |
| Corte de datos | 120 s | FIX; sin ninguna frase de 50 a 65 s; sigue después |
| Cambio de base | 150 s | FIX con EBP de la base A hasta 70 s y de la base B (a 2 km) desde 70 s, con 5 s de FLOAT en el cambio |
| Inclinación con el movimiento en ocho | 120 s | ETC en `SETUP` 0–20 s, `ALIGNMENT` 20–40 s, `COMPENSATING` 40–120 s con un ocho de 1,2 m, aviso `FAST_MOTION` 90–95 s |
| Paseo en línea recta | 120 s | 1 m/s hacia el este y luego al norte (60 s cada tramo), FIX |
Los valores son los de partida; el test de cada escenario comprueba los instantes de los eventos, no el resto de los números.
### A3. Ubicación del móvil → NMEA (RF-5, RF-6, RF-8)
1. Cada `Location` con `hasAccuracy()`: σ = `accuracy / √2`; sin vertical (`hasVerticalAccuracy()` falso) se deja vacía la del GST.
2. GGA: calidad 1, HDOP vacío, altitud = `altitude` elipsoidal de Android y separación del geoide 0, hora UTC de la ubicación; ZDA con su fecha.
3. La marca «simulada» (`isMock`) se refleja en `positionSource` (`PHONE_MOCK`) y no en el NMEA.

## Contratos
- `LocationSource`: `fun updates(): Flow<LocationFix>` con `LocationFix(latitudeDeg, longitudeDeg, ellipsoidalHeightM?, horizontalAccuracyM?, verticalAccuracyM?, timeMillis, mock)`; `fun isEnabled(): Boolean`; `fun hasPermission(): Boolean`.
- `ReceiverTransport`: `positionSource`, `noDataTimeoutMs`, `recordsSession` (D7, D9, D10); los transportes de siempre no cambian.
- `ReceiverSnapshot.source: PositionSource` y `ReceiverSnapshot.scenario: ScenarioInfo?(nameId, speed, durationS, elapsedS)`.
- `ConnectionState.ScenarioFinished(description)`.
- Textos (es y en, prefijos `phone_*`, `virtual_*`, `scenario_*`): «GNSS del móvil», «Receptor virtual», los siete nombres y descripciones, «Simulado», «Solo SINGLE: precisión de pocos metros», el permiso y su denegación, la ubicación apagada, la ubicación simulada por otra app, «El GNSS del móvil no usa correcciones», «Este receptor no admite inclinación ni base», fin de escenario.
- Estados del móvil: sin permiso → sin ubicación → esperando posición → recibiendo → sin datos (3 s).

## Estrategia de tests
- Unitarios JVM (`core:model`): `ReceiverKeyTest` con `phone` y `virtual`.
- Unitarios JVM (`core:gnss`): `NmeaWriterTest` (las frases escritas se leen con los analizadores de S002/S003 y devuelven los mismos valores; suma de control), `ScenarioPlayerTest` (determinismo byte a byte en dos ejecuciones, hora UTC creciente), un test por escenario (instantes de cada evento), coste de generar los 180 s de un escenario (RNF: ≤ 5 % de CPU, medido como tiempo por época muy por debajo de 50 ms).
- Room (`data`): migración v34 a v35, copia de seguridad con la columna, `Points` con fuente.
- Unitarios JVM (`receiver`): `VirtualReceiverTransportTest` (reloj falso: ×1 y ×5, cambio de velocidad, fin de escenario, cierre), `PhoneGnssTransportTest` con un `LocationSource` falso (SINGLE aunque declare 1 m, altura elipsoidal sin geoide, sin vertical, «Sin datos» a los 3 s, `isMock`), `ReceiverSessionSourcesTest` (no graba, no abre el NTRIP, ve el escenario y la fuente).
- Robolectric (`app`): `ReceiverConnectionsSourcesTest`, `PhonePermissionsTest`, `ReceiverListSourcesTest`, `VirtualReceiverDialogTest`, `SourceHeaderTest`, `CollectorSourceTest` (SINGLE con «solo FIX» pide confirmar; punto del virtual guardado como simulado; punto del móvil real con su fuente), `StakeoutVirtualTest` (replanteo con el paseo), `SourcesAccessibilityTest` (RNF-T7 y T8).
- Manual: demo en el emulador con cada escenario en el recolector y el replanteo, y el móvil con `adb emu geo fix`; el aire libre es del usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-12 | D1, D13 | `ReceiverListSourcesTest` |
| RF-2, RF-3 | D11 | `PhonePermissionsTest` |
| RF-4 | D11 | `ReceiverConnectionsSourcesTest` |
| RF-5, RF-6, RF-8 | D8, D9, A3 | `PhoneGnssTransportTest` |
| RF-7 | D12 | `PhoneGnssTransportTest`, `CollectorSourceTest` |
| RF-9 | D10 | `ReceiverSessionSourcesTest`, `SourceHeaderTest` |
| RF-10 | D14 | `CollectorSourceTest` |
| RF-11, RF-19 | D2, D3, D14 | `CollectorSourceTest`, `PointsSourceTest` |
| RF-13 | D5, D13 | `VirtualReceiverDialogTest` |
| RF-14, RF-15 | D4, A1, A2 | `ScenarioPlayerTest`, un test por escenario |
| RF-16, RF-17 | D6, D13 | `SourceHeaderTest`, `VirtualReceiverTransportTest` |
| RF-18, RF-20 | D7, D10 | `ReceiverSessionSourcesTest` |

## Cambios en documentos anteriores
- `specs/010-recolectar-puntos/spec.md`, RF-18: «…y si viene de una reproducción» pasa a «…y la fuente de la posición (receptor, reproducción, GNSS del móvil o receptor virtual)».
- `specs/012-exportar-csv/spec.md`, RF-6: «si viene de una reproducción» pasa a «si viene de una fuente simulada (reproducción, receptor virtual o posición simulada por otra app)».
- Sin cambios en `docs/rnf-transversales.md`.

## Cambios al implementar
- **D8, `LocationFix.satellites`:** `LocationFix` lleva un `satellites` opcional (el extra `satellites` del proveedor GPS de Android, que no todos los móviles dan). La GGA escribe 0 si no lo hay y la app **no enseña «0 sat»** con el móvil (barra de estado y entrada «Estado»): 0 es «no se sabe», no «ninguno». Lo vio la demo en el emulador.
- **D8, precisión al milímetro:** la GST escribe las σ con tres decimales, como el RS3, así que con 1 m de precisión de Android sale 0,707 en cada eje y la horizontal de la app es 0,9998 m; el test compara con 1 mm.
- **D9, `recordsSession`:** además de `noDataTimeoutMs`, el transporte dice si la sesión lo graba (`recordsSession`, por defecto lo contrario de `simulated`); así el móvil no graba sin ser una fuente simulada (RF-20).
- **D10, quién ofrece qué:** el **receptor virtual sí da inclinación y base** (hay escenarios de ETC y de EBP) y el móvil no. El móvil muestra el motivo en lugar del estado del NTRIP y de la inclinación, tiene `BaseShiftBlocker.PHONE_SOURCE` para el desplazamiento de base y un equipo fijo sin jalón (`CurrentGear.PHONE_GEAR`, con nombres internos que no se enseñan: la entrada «Antena y jalón» dice «Sin jalón: se usa la posición del móvil»). El virtual solo pierde el NTRIP.
- **D11, permisos:** Android 12+ exige pedir la ubicación aproximada junto a la precisa; se declaran y se piden las dos, y solo cuenta la precisa. El manifiesto ya no quita `ACCESS_FINE_LOCATION` ni `ACCESS_COARSE_LOCATION` de MapLibre y sigue quitando `ACCESS_WIFI_STATE`. Tres tests de otras specs que miraban el manifiesto o el orden de los jalones se actualizaron: `MapSetupTest` (S009), `ReceiverServiceTest` (S004, el servicio es `connectedDevice|location`) y `WifiGearKeysTest` (S021, ya no depende del orden de dos jalones creados en el mismo milisegundo).
- **D13, escenarios en el indicador:** el nombre del escenario se acorta a 72 dp con puntos suspensivos; con 112 dp el indicador empujaba el icono del modo sol fuera de la barra en 360 dp de ancho.
- **D14, la fuente de un punto:** `PointQuality.source` con `fromReplay` verdadero para reproducción, virtual y ubicación simulada (D3). La ficha tiene una fila «Fuente de la posición» (no para un receptor de siempre). Una ubicación simulada que llega durante la medida marca el punto entero (`sourceForPoint`).
- **D15, claves:** `phone` y `virtual` no se recuerdan como último receptor ni se reconectan al abrir la app, y el servicio recibe la clave para elegir su tipo (`ReceiverConnections.onConnecting(key)`).
- **D16, código de specs protegidas:** tocado lo acordado: `TopoPhoneDatabase` (S006, v35), `CollectorViewModel` y `PointDetailsScreen` (S010), `BaseShiftViewModel` y su pantalla (S020: la fuente y el bloqueo con el móvil), y de S002 a S007 `ReceiverTransport`, `ReceiverSession`, `ReceiverConnections`, `ReceiverViewModel`, `ReceiverList`/`ReceiverScreen`/`ReceiverHeader`/`ReceiverMenu`/`ReceiverPages`, `CorrectionsScreen`, `StatusIndicator`, `ReceiverService`, `ReceiverModule` y el manifiesto. Los `spec.md` de S010 y S012 cambian solo en la frase de RF-18 y RF-6; ningún formato de exportación cambia (D3).
- **Tests de tests:** `PhoneGnssTransportTest` y `ReceiverSessionSourcesTest` usan un reloj falso para el plazo de «Sin datos» de 3 s; `VirtualReceiverTransportTest` usa un reloj y una espera falsos, así que no tarda nada y el ritmo es exacto.

## Validación (T19)
- `rf_report.py`: S022 con 20 RF y los 20 con test, 0 pendientes. `./gradlew check` y `uv run --project tools pytest tools` en verde, `forbidden_files` sin hallazgos y `assembleDebug` compila.
- **Demo en el emulador (TopoPhone_API36, Android 16):**
  - **GNSS del móvil:** la fila «GNSS del móvil» pide el permiso con su explicación («Continuar»); denegado, sale el aviso con el botón a los ajustes; concedido desde fuera de la app, el aviso se quita solo al volver. Con una ubicación de `adb emu geo fix` conecta: cabecera «GNSS del móvil · SINGLE: posición de pocos metros», estado «Autónoma», jalón 0,000 m, «Inclinación: No disponible con el GNSS del móvil» y «Correcciones: El GNSS del móvil no usa correcciones».
  - **Receptor virtual:** el diálogo lista los siete escenarios con descripción y duración y ofrece el centro del proyecto «demo s025» o unas coordenadas. Con «Pérdida de FIX y recuperación» a ×5: cabecera «Simulado · Pérdida de FIX y recuperación», avance «0:34 de 3:00», Fija con 22 sat, Flotante con 14, Autónoma con 7 y Fija otra vez; indicador de la barra con «Simulado», el escenario, «×5» y la solución. Al acabar, «Repetir escenario», «Elegir otro escenario» y «Desconectar». «Correcciones: El receptor virtual no admite correcciones».
  - **Recolector:** un promediado con el virtual dice «Solución degradada» y se guarda; la ficha del punto dice «Fuente de la posición: Punto simulado: medido con el receptor virtual.»
- **Sin comprobar en el emulador:** el resto de los escenarios, el replanteo con el paseo y la ubicación simulada por otra app (los cubren `ScenarioEventsTest`, `StakeoutSourcesTest` y `CollectorSourceTest`), el GNSS del móvil al aire libre con satélites reales (queda para el usuario), la petición de «solo aproximada» del sistema y los textos en inglés en el dispositivo (solo en tests).
- **Incidencias del trabajo:** el `check` falló varias veces por tests ajenos intermitentes de `app` (colas de corrutinas en `Collector*ViewModelTest`, `LineStakeout*` y los de rendimiento, que miden tiempo) y se repitió; `WifiGearKeysTest` (S021) dependía del orden de dos jalones y se arregló; un script de edición con saltos de línea literales en un heredoc dejó medio aplicada una tarea y se completó a mano.

## Validación
_(Se rellena en la última tarea.)_
