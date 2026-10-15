# Plan técnico — Spec 005

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/005-estructura-interfaz/spec.md`. Sin código.

## Estructura de módulos
Ningún módulo ni dependencia nueva. No cambia la lógica de `receiver` ni la de `core:*`: es una reorganización de la interfaz.

| Módulo | Qué añade o cambia | RF |
|---|---|---|
| `core:designsystem` | `TopoPhoneTopBar` admite un hueco opcional para el indicador de estado, delante del acceso al modo sol | RF-18; S003/RF-16 |
| `app` | Indicador de estado compacto (sustituye a la franja `StatusBar`); grafo de navegación de Receptor con pantallas hijas; lista de receptores; pantalla del receptor (cabecera + menú con resúmenes); pantallas Estado, Coordenadas, Correcciones y Sesiones grabadas; `ReceiverViewModel` compartido por todo el grafo | RF-1 a RF-19; S003/RF-14 a RF-23, RF-35; S004/RF-23 |

## Modelo de datos
Sin datos persistidos nuevos. Cambios en el estado de la interfaz:
```kotlin
// Qué muestra la raíz de Receptor (RF-1, RF-5)
enum class ReceiverHomeMode { LIST, DASHBOARD }
fun receiverHomeMode(snapshot: ReceiverSnapshot): ReceiverHomeMode
// DASHBOARD si connection es Connecting, Receiving, NoData o Reconnecting, o si hay reproducción en curso; si no, LIST.

ReceiverUiState += lastReceiverAddress: String?      // preferencias del receptor (S004/RF-24), para señalarlo (RF-3)

// Indicador de estado (S003/RF-16 a RF-23 editados): sin edad, sin "Grabando" y sin inclinación
data class StatusIndicatorState(source, stale, solution, satellites, reconnecting)
```
Rutas de navegación nuevas (serializables, como las de S001): `ReceiverStatusRoute`, `ReceiverCoordinatesRoute`, `ReceiverCorrectionsRoute`; `SessionsRoute` ya existe (S003).

Ejemplo de resúmenes del menú (RF-10 a RF-13):

| Entrada | Recibiendo con NTRIP | Sin datos | Reproducción |
|---|---|---|---|
| Estado | "Fija · 24 sat" (chip con color) | "Sin datos" | "Fija · 24 sat" |
| Coordenadas | "Jalón 1,800 m" | "Jalón 1,800 m" | "Jalón 1,800 m" |
| Correcciones | "Recibiendo correcciones · 1,0 s" | "En pausa" (S004) | "No disponible en la simulación" (S003) |
| Sesiones grabadas | "Grabando" | "Grabando" | — |

## Algoritmos
### Raíz de Receptor (RF-1, RF-5, RNF de la spec)
```
modo = receiverHomeMode(snapshot)     // se recalcula con cada estado (como mucho a 5 Hz, RNF-T13)
si modo == LIST: lista de receptores
si no:           pantalla del receptor
```
La raíz no navega: cambia de contenido, así que no hay pila que deshacer al conectar o desconectar.

### Pantallas hijas (RF-14 a RF-17)
- Cada entrada del menú navega a su ruta. "Atrás" y la flecha hacen `popBackStack()` (RF-15).
- Las rutas son serializables, así que el giro y la muerte del proceso las restauran como las secciones de S001 (RF-17).
- Si la conexión termina, la pantalla hija sigue en la pila (RF-16) y muestra arriba "No hay ningún receptor conectado". Su contenido se queda en el estado sin datos de siempre.
- El `ReceiverViewModel` se pide con la entrada del grafo de Receptor como dueña: la raíz y las hijas comparten el formulario NTRIP y la altura del jalón.

### Indicador de estado (S003/RF-16 a RF-23, S004/RF-23, RF-18)
```
prioridad del texto:
  sin fuente               → "Sin receptor"
  reconectando             → "Reconectando"
  datos obsoletos          → "Sin datos"
  solución                 → chip con color y nombre + "N sat"
si reproducción: chip "Simulación" delante
```
- Va en las acciones de la barra superior. Material 3 mide primero las acciones y da al título el resto, que se acorta con puntos suspensivos (RF-18).
- Toda la zona es pulsable (≥ 56 dp de alto) y abre Receptor (S003/RF-23).

## Contratos
- `TopoPhoneTopBar(title, sunModeActive, onToggleSunMode, onNavigateBack, statusIndicator: (@Composable () -> Unit)?)`.
- `LocalStatusBar` pasa a `LocalStatusIndicator`; `SectionScaffold` lo pasa a la barra superior en lugar de pintarlo debajo.
- Composables de Receptor, sin estado propio, para poder probarlos con un `ReceiverUiState`:
  - `ReceiverHomeScreen(state, actions)`: lista de receptores o pantalla del receptor, según `receiverHomeMode`;
  - `ReceiverStatusScreen(state, onBack)`;
  - `ReceiverCoordinatesScreen(state, onPoleHeightChange, onBack)`;
  - `ReceiverCorrectionsScreen(state, actions, onBack)`.
- `ReceiverActions` += `onOpenStatus`, `onOpenCoordinates`, `onOpenCorrections` (y el `onOpenSessions` que ya existe).
- Etiquetas de test que se conservan: `CONNECTION_STATUS_TAG` está en la lista (estado o fallo de la última conexión) y en la cabecera (estado de la conexión). Las de los dispositivos, NTRIP, coordenadas, reproducción y reconexión no cambian.

Textos nuevos que ve el usuario:

| Caso | Texto |
|---|---|
| Lista: cabecera de los emparejados | "Receptores emparejados" |
| Lista: marca del último | "Último usado" |
| Entradas del menú | "Estado", "Coordenadas", "Correcciones", "Sesiones grabadas" |
| Resumen de Estado | "%1$d sat" junto al chip de la solución, o "Sin datos" |
| Resumen de Coordenadas | "Jalón %1$s m" |
| Pantalla hija sin conexión | "No hay ningún receptor conectado." |
| Indicador | Los de S003 y S004 ("Sin receptor", "Sin datos", "Reconectando", "Simulación") y "%1$d sat" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| La raíz de Receptor cambia de contenido según el estado, sin navegar | Conectar o desconectar no deja pantallas en la pila, y la notificación (S004/RF-9) y el indicador (S003/RF-23) siguen abriendo "Receptor" sin saber el estado | Dos rutas (lista y receptor) con navegación al cambiar el estado: pilas incoherentes al reconectar |
| Las pantallas hijas son rutas del grafo de Receptor | Atrás, giro y muerte del proceso salen gratis de Navigation (S001) | Hojas inferiores o estado propio: habría que guardar a mano la pantalla abierta |
| `ReceiverViewModel` con ámbito de grafo | Un solo estado para la raíz y las hijas; el formulario NTRIP no se pierde al entrar y salir | Un ViewModel por pantalla, con el formulario duplicado o sincronizado |
| Indicador en las acciones de la `TopAppBar` | Material 3 ya prioriza las acciones y acorta el título (RF-18), y se libera la franja (decisión del usuario) | Barra superior propia con medidas a mano |
| Reutilizar el contenido de las tarjetas actuales en las pantallas hijas | Ningún comportamiento cambia (RF-19) y los tests de S002–S004 se adaptan cambiando solo la pantalla que pintan | Reescribir las pantallas |

## Estrategia de tests
- **JVM:** `receiverHomeMode` para cada `ConnectionState`, con y sin reproducción (RF-1, RF-5).
- **Robolectric (Compose):**
  - `ReceiverListTest`: emparejados, último marcado, "Añadir receptor", acceso a sesiones, fallo y pérdida encima de la lista (RF-1 a RF-4);
  - `ReceiverHeaderTest`: nombre, estado, acciones por estado, avisos, "Grabando", inclinación y "Reproduciendo" (RF-5 a RF-8; S003/RF-20, RF-22, RF-35);
  - `ReceiverMenuTest`: orden de las entradas y resúmenes (RF-9 a RF-13);
  - `ReceiverNavigationTest`: abrir cada pantalla, Atrás, conexión que termina con una hija abierta, giro (RF-14 a RF-17), con `StatefulTestApp` y estados inyectados;
  - `StatusIndicatorTest` (sustituye a `StatusBarTest`): textos y prioridades, pulsar abre Receptor, 360 dp con la fuente al 130 % (RF-18; S003/RF-16 a RF-23; S004/RF-23);
  - `RelocationTest`: cada fila de la tabla de ubicación encuentra su función en el lugar nuevo (RF-19).
- **Tests existentes de S002–S004:** se adaptan a la pantalla nueva que corresponda, conservando su nombre y el RF que citan; ninguno se borra.
- **Replay:** `EndToEndReplayTest` y `FullRs3ReplayTest` recorren la pantalla del receptor y la pantalla Estado.
- **Manual:** demo en el emulador con una reproducción y demo con el RS3 (criterios de finalización).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-5 | Raíz de Receptor | `ReceiverHomeModeTest`, `ReceiverListTest`, `ReceiverHeaderTest` |
| RF-2, RF-3, RF-4 | Lista de receptores | `ReceiverListTest` |
| RF-6, RF-7, RF-8 | Cabecera | `ReceiverHeaderTest` |
| RF-9 a RF-13 | Menú con resúmenes | `ReceiverMenuTest` |
| RF-14 a RF-17 | Pantallas hijas y navegación | `ReceiverNavigationTest` |
| RF-18 | Indicador en la barra superior | `StatusIndicatorTest` |
| RF-19 | Pantallas Estado, Coordenadas, Correcciones y Sesiones | `RelocationTest` y los tests adaptados de S002–S004 |
| S003/RF-16 a RF-23, S004/RF-23 (editados) | Indicador de estado | `StatusIndicatorTest` |
| S003/RF-20, RF-22, RF-35 (editados) | Cabecera | `ReceiverHeaderTest` |
| S003/RF-14, RF-15 (editados) | Pantalla Estado | `StatusCardTest` adaptado |
