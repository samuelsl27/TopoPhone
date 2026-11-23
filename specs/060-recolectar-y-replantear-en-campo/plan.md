# Plan técnico — Spec 060

> Lee antes: `docs/constitution.md`, `docs/rnf-transversales.md` (RNF-T8, RNF-T13), `specs/060-recolectar-y-replantear-en-campo/spec.md` y los planes de S010/S017 (recolector), S018/S019 (replanteo), S041, S054 y S051 (replanteos de línea, eje y talud), S047 (tipos de solución) y S009 (cámara del mapa).

Sin dependencias nuevas, ni Gradle ni Python. Es una spec **solo de presentación**: no cambia `core:*`, ni Room, ni los ViewModels en su lógica (RF-32). Alternativas descartadas en las decisiones D1, D5 y D8.

## Lo que hay hoy (lo que se sustituye)
- `CollectorPanel` (`ui/collector`) es una `Card` con un formulario que se desplaza (`FORM_MAX_HEIGHT` 168 dp) y `Messages`, que apila los avisos. Lo usan cinco sitios de `ProjectViewScreen` (recolector suelto, sobre las secciones, sobre el perfil longitudinal, sobre la toma por eje y, dentro de los replanteos, solo sus trozos `MeasureButton`, `Measuring` y `Messages`).
- Los cuatro replanteos (`StakeoutPanel`, `LineStakeoutPanel`, `AxisStakeoutPanel`, `SlopeStakePanel`) son `Card` propias con `BODY_MAX_HEIGHT` 380 dp y comparten `Guidance`, `StakeModeChoice`, `StakeoutArrow`, `StakeoutDiana` y `StakeoutLevelView`.
- La cámara la gobierna `CameraPolicy` (S009): sigue al rover a escala fija, sin rumbo y sin ajustar el zoom al destino; `MapController` no tiene orientación.
- `MeasureBlocker` solo cubre nombre, jalón, equipo, segundos, posición, ámbito del SC, inclinación y atributos.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa descartada |
|---|---|---|---|
| D1 | La hoja de campo (RF-1 a RF-7) | Un componente propio `FieldSheet` en `ui/field/`: una columna anclada abajo, **no modal**, con asa y dos alturas (`FieldSheetValue.Folded` y `Expanded`) en `rememberSaveable` (RF-5). La plegada mide lo que su contenido; la expandida llega a un máximo del 70 % del alto y se desplaza dentro. Arrastre vertical con umbral, toque del asa y acciones de accesibilidad «Expandir» y «Plegar». Su alto medido sigue yendo a `MapController.setBottomInset` como hoy | `BottomSheetScaffold` de Material 3: pide recolocar el mapa dentro de su contenido y su estado parcial no se deja medir contra el 60 % |
| D2 | Cómo se verifica el 60 % libre (RF-2) | La suposición de la spec (≤ 40 %) no cabe en un móvil de 640 dp con avisos y pastillas informativas a la vez. Propuesta: plegada **sin** pastillas informativas ni motivo ≤ 34 % de 640 dp; con motivo ≤ 40 %; con además una pastilla informativa ≤ 45 %; con un móvil de 700 dp o más, siempre ≤ 40 %. **Cambio de spec que te pido aprobar:** RF-2 pasa a «al menos el 60 % del alto en pantallas de 700 dp o más y el 55 % en las de menos, con los avisos del caso más cargado» y el criterio de finalización mide 411×891 y 360×640. Las pastillas informativas (RF-14) solo salen si el ajuste no es el de por defecto, así que el caso normal es el corto | Dejar RF-2 como está: obliga a quitar el botón Medir o las pastillas del estado plegado |
| D3 | Pastillas y su fila (RF-9 a RF-13, RF-27) | `SettingPill` (56 dp, nombre pequeño arriba y valor debajo, rol Button, descripción para TalkBack «Jalón, 1,800 m, tocar para cambiar») dentro de un `FlowRow`: a 360 dp caben las cuatro en una fila; con la letra al 130 % pasan a dos filas sin cortar texto. Los selectores reutilizan las acciones de hoy: `onPoleHeight`, `onSeconds`, `onMode`, `onAutoEnabled`, `onAutoTrigger`, `onAutoInterval`, `onAutoDistance`, `onSettings`, `onSelectPole`. **No se crea ninguna acción de datos nueva** | Reescribir las acciones: arriesga RF-32 |
| D4 | El teclado de «Tiempo» (RF-11) | «Jalón» usa teclado `Decimal` en un diálogo pequeño. «Tiempo» promediado es «m:ss» (S017/RF-1) y el teclado numérico no tiene «:»: el diálogo lleva **dos campos numéricos, minutos y segundos**, y los junta en el texto «m:ss» que ya entiende el analizador de S017, sin tocar su formato ni sus rangos. El intervalo y la distancia de los automáticos usan teclado `Decimal` | Un campo de texto con teclado de letras (no es «solo teclado numérico») o aceptar solo segundos (cambia S017) |
| D5 | Los selectores de lista corta y de teclado (RF-11, RF-13) | `AlertDialog` pequeño: un diálogo es modal, pero dura un toque y ya se usa en el proyecto para los ajustes del replanteo y la cota. Se hace con `imePadding` para que el campo no quede bajo el teclado (RF-7). «Modo» lista las cuatro opciones y las traduce a `onMode` y `onAutoEnabled`/`onAutoTrigger` (`CollectionModeChoice`, puro y probado) | Un segundo nivel de hoja: más gestos para una elección de un toque |
| D6 | Motivos de bloqueo (RF-16 a RF-19) | `MeasureReasons.of(state)` (puro, en `ui/collector`) devuelve el motivo de mayor prioridad y su acción (`ReasonAction`: abrir receptores, abrir el jalón, abrir inclinación, expandir la hoja al código, abrir el SC…). El orden es el de la suposición de la spec. Las acciones nuevas que necesitan salir de la pantalla (`onOpenReceiver`, `onOpenProjectCrs`, `onOpenGear`) se añaden a `CollectorActions` y se enchufan en `ProjectViewScreen` a rutas que ya existen | Un enum nuevo en `CollectorViewModel`: toca la lógica |
| D7 | «Solución no admitida» y «precisión peor que el límite» (RF-17) | **Hoy no son bloqueos**: con FIX solamente la medición espera épocas admitidas (S047/RF-12) y la precisión fuera de límite pide confirmar después de medir (S010/RF-14). Hacerlos bloqueos cambiaría el comportamiento (RF-32). Propuesta: se muestran como **línea de estado** («Esperando FIX», «Precisión 0,045 m: el límite es 0,030 m») en texto neutro, nunca en rojo, y salen del texto de RF-17. **Cambio de spec que te pido aprobar:** RF-17 conserva solo los bloqueos reales y se añade «RF-17b: la línea de estado dirá, sin color de error, si la solución no es admitida o la precisión supera el límite» | Hacerlos bloqueos de verdad: cambia lo que se puede pulsar |
| D8 | Cámara que sigue y orienta (RF-25, RF-26) | `MapController` gana `follow(FollowCamera)` (centro, zoom y rumbo opcional, con transición corta) y `resetBearing()`. La lógica es una clase pura `StakeoutCameraPolicy` (`ui/map`): entra la punta, el destino, el rumbo y la cámara; sale la orden. Reglas: el zoom deja caber el destino y la punta con un margen del 25 %, **no se acerca más que 3 m de lado visible**; solo cambia si la diferencia pasa de 0,3 niveles; el rumbo solo se aplica con rumbo disponible (S018) y si cambia más de 5°; sin rumbo, norte arriba; como mucho 5 órdenes por segundo (RNF-T13). Un gesto del usuario (`userGestures`) la deja libre y sale «Recentrar». El destino sale de `StakeoutMarker.design`, que ya existe para los cuatro replanteos | Girar el mapa con la brújula del móvil: fuera de alcance |
| D9 | «En el punto» (RF-28) | Una función pura `ArrivalRules.within(ui, toleranceH, toleranceV)`: horizontal dentro de la tolerancia H (la zona `INSIDE` de hoy) y, si hay nivel vertical, `|dz| ≤ toleranceV`. La placa se pone en verde con el texto «En el punto». Los sonidos y la vibración siguen disparándose por `StakeAlert`, sin cambios (RF-31) | Cambiar la regla de la zona: tocaría S018 |
| D10 | Orden de trabajo y tamaño | Recolectar primero (T1–T12) y replantear después (T13–T25). Son 25 tareas contando la demo y el cierre; se hacen de seguido, pero **si prefieres dividir en dos specs, el punto de corte es entre T12 y T13**. Los tags de test existentes (`COLLECTOR_*`, `STAKEOUT_*`) se mantienen en los elementos que siguen existiendo para limitar el cambio en los tests | Reescribir los tags: obliga a rehacer más de 40 tests |

## Estructura
- `core:*` no cambia. `core:designsystem` puede recibir `SettingPill` si se quiere reutilizar en S059; en esta spec vive en `app` (`ui/field/`).
- `app/ui/field/` (nuevo): `FieldSheet`, `SettingPill`, `SettingsRow`, `NumericEntryDialog`, `ShortListDialog`, `InfoPill`.
- `app/ui/collector/`: `MeasureReasons`, `CollectionModeChoice`, `CollectorSheet` (sustituye a `CollectorPanel`), `CollectorFolded`, `CollectorExpanded`, `StatusLine`.
- `app/ui/stakeout/`: `GuidancePlate`, `ArrivalRules`, `StakeoutSheet` (envoltorio común de los cuatro), y los paneles de cada replanteo reducidos a su contenido.
- `app/ui/map/`: `StakeoutCameraPolicy`, `FollowCamera`, ampliación de `MapController` y `MapLibreMapController`.
- Textos en `strings.xml` (es y en, RNF-T7).

## Piezas
### P1. `FieldSheet`
`FieldSheet(value, onValueChange, folded, expanded, modifier)`. El contenido plegado y el expandido son lambdas: el expandido incluye el plegado y añade lo suyo debajo. Alto máximo expandido = 70 % del alto de la ventana; si no cabe, se desplaza solo la parte añadida, nunca el estado ni «Medir». Con el teclado abierto la hoja sube con `imePadding` y el campo enfocado se pone a la vista con `BringIntoViewRequester` (RF-7).

### P2. La línea de estado
`StatusLine(live, unit)`: «FIX · H 0,012 m · V 0,020 m · 24 sat» con el color de la solución y su nombre en texto (RNF-T8). Reutiliza `LiveQuality` y `nameRes()`; los satélites salen de lo que ya lleva `LiveQuality` o del `ReceiverSnapshot` (se comprueba en T4; si no los lleva, se omite el dato y se anota).

### P3. Replanteo
`GuidancePlate(ui, settings, numbers)`: la flecha a la izquierda (`StakeoutArrow`, ya existe), la distancia grande en el centro y los desplazamientos a la derecha; abajo, la cota con «Desmonte», «Terraplén» o «En cota». Con «En el punto» (D9) el contenedor pasa a verde con el texto. La diana y el nivel vertical siguen en la hoja expandida. El rumbo, el PK, el error lateral y el talud (RF-29) entran como una segunda línea de la placa mediante el mismo gancho `extra` que usa `Guidance` hoy.

### P4. Hojas por tipo de replanteo
Cada `*StakeoutPanel` se queda con su contenido y deja de ser una `Card`: `StakeoutSheet(title, onClose, previous/next, pills, plate, collector)` pone la cabecera (nombre, «Anterior», «Siguiente» a 56 dp, «Cerrar»), la placa, la fila de ajustes (RF-27) y los trozos de recolector que ya comparte (`MeasureButton`, `Measuring`, `Messages` → motivo de bloqueo).

## Riesgos
- Reescribir cinco sitios de `ProjectViewScreen` (cruce, longitudinal, toma por eje, análisis de ejes, recolector suelto) sin romper la apertura de cada sección: se hace con un único punto de integración (`CollectorSheet` con los mismos parámetros que `CollectorPanel`) y se migra uno por tarea.
- Los tests de interfaz que cuentan filas o buscan textos largos fallarán al reordenar: se migran en la misma tarea que cambia la pieza (T8 y T20), no al final.
- Rendimiento de seguir al jalón a 5 Hz con MapLibre: `StakeoutCameraPolicy` limita las órdenes y T24 lo mide en el emulador.

## Cambios al implementar
(Se rellena al cerrar la spec.)

## Validación
(Se rellena al cerrar la spec.)
