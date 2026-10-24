# Plan técnico — Spec 018

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/018-replanteo-puntos/spec.md`. Sin código.

## Estructura de módulos
- `core:model`:
  - `PointOrigin.STAKED` y `StakeoutRef` (punto de diseño, coordenadas de diseño y diferencias) en `Point` (A5; RF-27, RF-28, RF-32);
  - `StakeoutSettings` (modo de guía, origen del rumbo, tolerancia de 1 cm a 1 m, 2,5 cm por defecto, con su conversión a la unidad del proyecto; RF-5, RF-8, RF-18);
  - `StakeoutNames`: `_stkd`, `_stkd2`… (RF-27); `NaturalOrder`: el orden natural de nombres (RF-3);
  - `PointSummary.stakeCount` (RF-33).
- `core:survey`:
  - `StakeoutGeometry`: distancia horizontal, diferencias de Norte y Este, rumbo al punto, adelante/derecha y diferencia de cota (A1; RF-6, RF-7, RF-12);
  - `HeadingSource`: rumbo de marcha y rumbo del IMU, con su disponibilidad (A2; RF-8 a RF-11);
  - `StakeoutZones`: máquina de estados de las zonas y los avisos (A3; RF-19 a RF-21);
  - `DianaScale`: la escala 1-2-5 de la diana (A4; RF-16);
  - `StakeoutDiff`: lo que se guarda, medido menos diseño (A5; RF-28).
- `core:geodesy`: sin cambios (se reutiliza `LocalTangentPlane` para los SC geográficos).
- `data`:
  - columnas nuevas de `points` para el replanteo, migración 9 → 10 y el recuento de replanteos por punto (A5; RF-27, RF-28, RF-32, RF-33);
  - `StakeoutPreferences` (DataStore `"stakeout"`) con el modo, el origen del rumbo y la tolerancia (RF-5, RF-8, RF-18).
- `receiver` → `Alerts.stakeoutApproach()`, `stakeoutArrived()` y `stakeoutLeft()` (RF-20, RF-21).
- `app`:
  - `StakeoutController`: orquesta la guía con las épocas, la punta, el rumbo, las zonas y los avisos (A6; RF-5 a RF-25, RF-35);
  - `StakeoutViewModel` y el panel de replanteo: el punto de diseño, siguiente y anterior, los dos modos, la diana, la tolerancia y el origen del rumbo (RF-1 a RF-8, RF-13 a RF-19);
  - `CollectorViewModel`: el contexto de replanteo para medir, el nombre `_stkd` y el guardado con la referencia al diseño (A5; RF-26 a RF-31);
  - `MapController.showStakeout` y el símbolo de los replanteados (RF-14, RF-34);
  - entradas "Replantear" en la ficha, la lista de objetos y el mapa (RF-1), marca de replanteos en la lista (RF-33) y la ficha del replanteado (RF-32).
- `tools`:
  - `tools/replanteo/generar_replanteo.py`: pares de diseño y punta en la Península, en Canarias y en un SC geográfico, con la distancia, las diferencias y el rumbo calculados con pyproj (constitución nº 2);
  - `tools/nmea/generar_replanteo.py`: la reproducción `rs3-replanteo` (andando hacia un punto de diseño, con el FIX perdido un rato) y lo esperado de cada época.

## Modelo de datos

### En el punto (Room v10)
```
points  +  stake_design_id TEXT NULL            // id del punto de diseño
        +  stake_design_name TEXT NULL          // su nombre al replantear
        +  stake_design_east REAL NULL, stake_design_north REAL NULL, stake_design_height REAL NULL
        +  stake_design_lat REAL NULL, stake_design_lon REAL NULL, stake_design_ground_h REAL NULL
        +  stake_d_east REAL NULL, stake_d_north REAL NULL    // medido − diseño
        +  stake_d_horizontal REAL NULL, stake_d_height REAL NULL
```
- `origin = 'STAKED'` en un punto replanteado; el resto de columnas de la medida son las de cualquier punto medido (S010/RF-17, RF-18).
- El diseño se guarda por valor (nombre y coordenadas de entonces), no solo por id: si el punto de diseño se edita o se borra, el replanteo conserva lo que había (RF-28).
- `AutoMigration(9, 10)` sin especificación: solo columnas opcionales y sin valor por defecto.
- Un punto "replanteado" es un punto medido para todo lo demás: `Point.measurement` no es nulo y `origin` solo cambia los textos y la ficha.
- El recuento de replanteos de un punto de diseño (RF-33) sale de `SELECT stake_design_id, COUNT(*) … GROUP BY` sobre los puntos activos.

### Ajustes del replanteo (DataStore `"stakeout"`)
```
StakeoutSettings(
  mode: StakeMode { BY_DIRECTION, BY_DISTANCE },     // BY_DIRECTION por defecto
  heading: HeadingChoice { WALKING, IMU },            // WALKING por defecto
  toleranceM: Double (0,01..1,0), 0,025 por defecto
)
```
Se guardan en metros SI y se escriben en la unidad del proyecto (RNF-T9). Fuera de rango, el valor se corrige y se avisa.

## Algoritmos

### A1. Geometría de la guía (RF-6, RF-7, RF-12; constitución nº 2)
Entradas: el punto de diseño en el SC del proyecto (Este, Norte, altura) y en geográficas, la punta (Este, Norte, altura de `ProjectPositionCalculator.fromGround` sobre `LiveTip`) y el rumbo del usuario.
1. SC proyectado: `ΔE = E_diseño − E_punta`, `ΔN = N_diseño − N_punta`.
2. SC geográfico (sin Este ni Norte): en el plano tangente local de la punta con `LocalTangentPlane` (como el modo automático, S017/RF-21).
3. `d = hypot(ΔE, ΔN)`; el rumbo al punto es `atan2(ΔE, ΔN)` normalizado a `[0, 2π)`, en el norte de cuadrícula.
4. Con rumbo del usuario `h`: ángulo relativo `a = rumbo_al_punto − h`; `adelante = d·cos a`, `derecha = d·sin a`.
5. `ΔH = H_punta − H_diseño` si ambos tienen altura; si no, nada.
- Sin factor combinado: son distancias de cuadrícula (suposición de la spec).
- Pura y sin Android; se prueba contra `fixtures/replanteo/` generada con pyproj (≤ 1 mm y 0,001°).

### A2. El rumbo del usuario (RF-8 a RF-11)
- **Marcha** (`WalkingHeading`): guarda las últimas posiciones de la punta de los últimos 2 s; con un avance de 0,3 m/s o más entre la más antigua y la última, el rumbo es el de ese avance. Parado, conserva el último rumbo válido 10 s y lo marca como "último rumbo"; pasados 10 s, no hay rumbo.
- **IMU**: el rumbo del ETC de la época (`TiltData.headingRad`), solo en estado 30. El ETC da el rumbo respecto al norte geográfico: se le resta la convergencia de cuadrícula (`ProjectPosition.convergenceRad`, 0 en un SC geográfico) para tener el rumbo de cuadrícula.
- Sin rumbo no hay flecha ni diana orientada, pero sí distancia y diferencias de Norte y Este (RF-11, RF-17).
- Es lo que más puede cambiar en el campo (la referencia del ETC y los 0,3 m/s): son constantes en `StakeoutConfig`, con tests que las usan por nombre.

### A3. Zonas y avisos (RF-19 a RF-21)
Estados por la distancia `d` y la tolerancia `t`: **LEJOS** (`d > 0,40`), **AYUDA** (`t < d ≤ 0,40`) y **DENTRO** (`d ≤ t`).
- Entrar en AYUDA desde LEJOS: `stakeoutApproach`; se rearma al superar 0,405 m.
- Entrar en DENTRO: `stakeoutArrived`; salir cuando `d > t + 0,005`: `stakeoutLeft`. Entre `t` y `t + 0,005` no cambia nada (el margen de 5 mm de RF-20).
- Si `t` > 0,40 (hasta 1 m), la zona AYUDA no existe: se pasa de LEJOS a DENTRO.
- Un corte, sin FIX con "Solo FIX" o sin estado 30 apagan la guía: el estado vuelve a LEJOS al volver, sin avisos de entrada nuevos hasta que se cumpla la condición otra vez.
- Una función pura: `StakeoutZones.next(state, d, t)` devuelve el estado y el aviso, si lo hay.

### A4. Escala de la diana (RF-15, RF-16, RF-17)
- Radio visible: el menor valor de `{0,05; 0,1; 0,2; 0,5}` m que deje dentro la punta con un 25 % de margen y el círculo de la tolerancia; nunca por debajo de 5 cm, y nunca por encima de 0,5 m (la diana solo se ve a 40 cm o menos).
- Cambia de paso sin saltos de ida y vuelta: no baja de escala hasta que la punta, con su margen del 25 %, quepa en el 80 % del paso menor (y el círculo de la tolerancia, siempre).
- Orientación: con el modo "por dirección" y rumbo disponible, el rumbo del usuario arriba; en los demás casos, el norte arriba.
- La diana es un `Canvas` métrico aparte del mapa (ADR-9).

### A5. Medir y guardar el replanteo (RF-26 a RF-31)
- El panel de replanteo da al `CollectorViewModel` el contexto de replanteo (`stakeTarget`) al abrirse y al cambiar de punto, y lo quita al cerrarse (se restaura el nombre del recolector).
- `startPoint` y todo lo demás no cambian: la medida es la misma que la del recolector, con su modo, su altura del jalón, su control de precisión, sus cortes y sus confirmaciones (RF-26).
- El nombre propuesto es `StakeoutNames.next(diseño, nombres)`; la descripción, la del punto de diseño.
- `save()` con contexto de replanteo crea el punto con `origin = STAKED` y `StakeoutRef` con `StakeoutDiff.of(diseño, medido)`: `medido − diseño` en Este, Norte, distancia horizontal y cota, sin redondear (RF-28). Se guarda como cualquier punto (una transacción, RF-16 de S010).
- Tras guardar, el panel enseña las diferencias y se queda en el mismo punto de diseño (RF-29); el nombre siguiente ya está propuesto (`_stkd2`).
- Una medida en marcha bloquea el cambio de punto, de modo y de tolerancia (RF-30): lo decide `StakeoutUiState.measuring`.

### A6. El controlador de la guía (RF-5 a RF-25, RF-35)
`StakeoutController` (clase de `app`, construida con constructor normal, como `AutoCollector`):
1. Se suscribe a las épocas de la `ReceiverSession` y a la punta con la altura del jalón del recolector y el modo de inclinación de `CurrentTilt`.
2. Con cada época calcula la punta (A1), el rumbo (A2), las diferencias (A1) y la zona (A3), y publica `GuidanceUi` a 5 Hz como mucho (RNF-T13). Las comprobaciones de los avisos van con cada época, no solo con las publicadas.
3. Las condiciones que apagan la guía (RF-22 a RF-25, RF-35): sin posición, corte, sin FIX con "Solo FIX" y sin estado 30 con la inclinación; cada una con su motivo; los avisos de pérdida reutilizan `Alerts.solutionLost()` (S010/RF-10) y `connectionLost()`.
4. Anota en el log los inicios y finales del replanteo y de cada guía apagada, con su causa (S014/RF-5), sin coordenadas (S014/RF-7).

## Contratos

### Estado del replanteo (`StakeoutUiState`)
- `target` (nombre, descripción, coordenadas, altura), `hasPrevious`, `hasNext`, `settings`, `guidance: GuidanceUi?`, `off: GuidanceOff?` (sin posición, corte, sin FIX, inclinación) y `measuring`/`progress`/`pending`/`savedName` (los del recolector), `lastResult: StakeoutDiff?`.
- `GuidanceUi`: distancia, diferencias de Norte y Este, rumbo al punto, adelante y derecha, diferencia de cota, zona, rumbo del usuario (con "último rumbo" y su edad), escala de la diana y posición de la punta en ella, y la solución, la precisión horizontal y la compensación de la punta (RF-13).

### Pantallas
- Panel de replanteo en el hueco del recolector, bajo el mapa: `StakeoutPanel`. Cabecera con el nombre, "Anterior", "Siguiente" y "Cerrar"; los dos modos como pestañas; el cuerpo con la flecha o las diferencias y, en la zona de ayuda, la diana; una fila con la solución, la precisión y el rumbo; "Ajustes" (tolerancia y origen del rumbo) en un diálogo; "Medir" con el progreso y "Cancelar" cuando se mide.
- Entradas: botón "Replantear" en la ficha, en el menú del punto de la lista de objetos y en la ficha del punto que sale al tocar el mapa (la misma). La lista de objetos y la ficha del punto abren el proyecto con el replanteo ya puesto.
- Mapa: `MapController.showStakeout(StakeoutMarker?)` con la punta, el punto de diseño y la línea mientras `d > 0,40` (RF-14); los puntos replanteados con otro símbolo (RF-34).

### Textos nuevos (en `strings.xml`, es y en)
Replantear, Por dirección, Por distancia, Siguiente, Anterior, Medir, Dentro de la tolerancia, adelante, atrás, derecha, izquierda, Norte, Este, "Diferencia de cota", Rumbo de marcha, IMU del receptor, "Último rumbo de marcha (hace %d s)", "Sin rumbo: camina para obtenerlo", "Rumbo del IMU no disponible", "Sin posición", "Sin datos del receptor", "Sin FIX: guía apagada", "Punta sin compensar: guía apagada", Tolerancia, "Replanteado de %s", "%d replanteos", "Punto %s guardado: ΔN %s · ΔE %s".

### Accesibilidad (RNF-T8)
- Cada cifra de la guía lleva su etiqueta de TalkBack; el texto "a 1,2 m, 30° a la derecha" se anuncia al entrar en cada zona y al pulsar sobre la flecha, no en cada refresco; "Dentro de la tolerancia" es una región viva.
- La diana lleva una descripción de texto: "Punta a 3 cm del punto, hacia el norte".

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| El replanteo es un panel del proyecto, en el hueco del recolector | El mapa se queda detrás con la punta y la línea (RF-14) y se comparte el panel de posición; como el recolector (S010/RF-1) | Una pantalla aparte: pierde el mapa y la navegación de atrás se complica |
| El `CollectorViewModel` mide y guarda; el `StakeoutController` solo guía | La medida, el control de precisión, los cortes y las confirmaciones (RF-26) ya están probados; duplicarlos sería el mayor riesgo | Un segundo flujo de medida propio del replanteo |
| El diseño se guarda por valor en el punto replanteado | Constitución nº 1: editar o borrar el diseño no cambia lo replanteado (RF-28) | Solo el id del diseño |
| Origen del punto: `PointOrigin.STAKED` con columnas propias | `MEASURED` seguiría sin distinguir los replanteados, y la lista, el mapa y la ficha lo necesitan (RF-32 a RF-34) | Una marca en la descripción o en el nombre `_stkd` |
| El rumbo del ETC se corrige con la convergencia | El IMU da el rumbo respecto al norte geográfico; la guía va en el norte de cuadrícula (RF-7) | Ignorar la convergencia: llega a 1,5° en el borde de un huso, que a 1 m son 2,6 cm |
| El rumbo de marcha sale de las posiciones de la punta, no del RMC | Funciona con cualquier receptor y con las reproducciones; el RMC solo da el rumbo de la antena y con retraso | `RmcData.courseRad` |
| Zonas y avisos en una función pura con 5 mm de margen (A3) | Constitución nº 6; el borde de la tolerancia con el ruido del GNSS repetiría el aviso | Comparar la distancia con la tolerancia en cada época |
| Margen de 5 mm también en el borde de los 40 cm | Mismo ruido, mismo problema; la spec solo lo exige en la tolerancia (RF-20) | Sin margen en la zona de ayuda |
| Distancias de cuadrícula, sin factor combinado | Suposición de la spec; irrelevante a estas distancias (0,4 m por km) | Aplicar el factor combinado |
| Orden natural por nombre para "Siguiente" y "Anterior" | Suposición de la spec: en un replanteo se va por números de punto | El orden de la lista, del más reciente al más antiguo |
| `AutoMigration` 9 → 10 sin especificación | Solo columnas nuevas opcionales | `RoomDatabase.Migration` a mano |
| `Alerts` con tres métodos nuevos con valor por defecto | Los fakes de tests no cambian (como en S017) | Un método con un parámetro |

## Estrategia de tests
- **Unitarios JVM**:
  - `StakeoutGeometryTest`: distancia, diferencias de Norte y Este, rumbo, adelante y derecha, diferencia de cota y sin altura, con `fixtures/replanteo/` frente a pyproj (≤ 1 mm y 0,001°), en la Península, en Canarias y con un SC geográfico (RF-6, RF-7, RF-12).
  - `HeadingSourceTest`: avance por encima y por debajo de 0,3 m/s, los 10 s de "último rumbo", el rumbo del IMU en estado 30 y fuera de él, y la convergencia (RF-8 a RF-11).
  - `StakeoutZonesTest`: las tres zonas, el margen de 5 mm, el aviso una sola vez al entrar, la tolerancia mayor de 40 cm y el rearmado (RF-19 a RF-21).
  - `DianaScaleTest`: los pasos, el margen, sin saltos de ida y vuelta (RF-16).
  - `StakeoutDiffTest`, `StakeoutNamesTest`, `NaturalOrderTest` y `StakeoutSettingsTest` (RF-3, RF-18, RF-27, RF-28).
- **`data`**:
  - `TopoPhoneDatabaseTest` 9 → 10: los puntos conservan sus datos y las columnas nuevas quedan a NULL.
  - `PointRepositoryStakeoutTest`: un punto replanteado se guarda y se lee con su diseño y sus diferencias sin redondear; el recuento de replanteos por punto de diseño; un diseño borrado no cambia el replanteo (RF-27, RF-28, RF-33).
  - `StakeoutPreferencesTest`, `DatabaseSnapshotTest` y `SettingsArchiveTest` con los ajustes nuevos.
- **`tools`**: tests de `generar_replanteo.py` (que lo guardado es lo que genera el script).
- **Robolectric (`app`)**:
  - `StakeoutControllerTest`, con la `ReceiverSession` real y las épocas de una en una: la guía por dirección y por distancia, los avisos al entrar en cada zona, sin FIX con "Solo FIX" y sin él, sin estado 30, un corte y su vuelta, sin posición al abrir (RF-5 a RF-25, RF-35).
  - `CollectorViewModelStakeoutTest`: medir y guardar un `_stkd`, repetir `_stkd2`, el control de precisión y la confirmación, un promediado con un corte, el bloqueo durante la medida, el diseño editado después y el nombre del recolector que se restaura (RF-26 a RF-31).
  - `StakeoutPanelTest`: lo que se ve en cada modo y situación, la diana, "Dentro de la tolerancia", los avisos de texto, siguiente y anterior en los extremos, los ajustes y la accesibilidad (RF-1 a RF-8, RF-13 a RF-19).
  - `StakeoutEntriesTest`: "Replantear" desde la ficha, la lista y el mapa (RF-1); `ObjectListStakeoutTest` y `PointDetailsStakeoutTest` con la marca y la ficha del replanteado (RF-32, RF-33); `MapStakeoutTest` con el símbolo (RF-34).
- **Replay NMEA** (`fixtures/nmea/sintetico/`): `rs3-replanteo` (andando 40 m hacia un punto de diseño, con el FIX perdido 10 s a mitad y una parada) y lo esperado de cada época. `StakeoutReplayTest` lo recorre con el `StakeoutController` y el `CollectorViewModel`.
- **Rendimiento** (`StakeoutPerformanceTest`): la guía a 10 Hz con la diana, menos de 1 ms por época; la latencia de una época a la pantalla, menos de 300 ms.
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, el ensayo e de la puerta G2: 5 puntos replantados y comprobados después (≤ 2,5 cm).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | Entradas, panel | `StakeoutEntriesTest` |
| RF-2 | Panel | `StakeoutEntriesTest` |
| RF-3 | `NaturalOrder`, panel | `NaturalOrderTest`, `StakeoutPanelTest` |
| RF-4 | Panel | `StakeoutPanelTest` |
| RF-5 | `StakeoutSettings`, A6 | `StakeoutSettingsTest`, `StakeoutPreferencesTest`, `StakeoutPanelTest` |
| RF-6 | A1 | `StakeoutGeometryTest`, `StakeoutPanelTest` |
| RF-7 | A1 | `StakeoutGeometryTest`, `StakeoutPanelTest` |
| RF-8 | A2 | `HeadingSourceTest`, `StakeoutPreferencesTest` |
| RF-9 | A2 | `HeadingSourceTest` |
| RF-10 | A2 | `HeadingSourceTest` |
| RF-11 | A2 | `HeadingSourceTest`, `StakeoutPanelTest` |
| RF-12 | A1 | `StakeoutGeometryTest`, `StakeoutPanelTest` |
| RF-13 | A6, panel | `StakeoutPanelTest`, `StakeoutControllerTest` |
| RF-14 | Mapa | `MapStakeoutTest` |
| RF-15 | A4, panel | `StakeoutPanelTest` |
| RF-16 | A4 | `DianaScaleTest` |
| RF-17 | A4 | `StakeoutPanelTest` |
| RF-18 | `StakeoutSettings` | `StakeoutSettingsTest`, `StakeoutPanelTest` |
| RF-19 | A3, panel | `StakeoutZonesTest`, `StakeoutPanelTest` |
| RF-20 | A3 | `StakeoutZonesTest`, `StakeoutControllerTest` |
| RF-21 | A3 | `StakeoutZonesTest`, `StakeoutControllerTest` |
| RF-22 | A6 | `StakeoutControllerTest` |
| RF-23 | A6 | `StakeoutControllerTest` |
| RF-24 | A6 | `StakeoutControllerTest` |
| RF-25 | A6 | `StakeoutControllerTest` |
| RF-26 | A5 | `CollectorViewModelStakeoutTest` |
| RF-27 | A5, `StakeoutNames` | `StakeoutNamesTest`, `CollectorViewModelStakeoutTest`, `PointRepositoryStakeoutTest` |
| RF-28 | A5, `StakeoutDiff` | `StakeoutDiffTest`, `PointRepositoryStakeoutTest`, `CollectorViewModelStakeoutTest` |
| RF-29 | A5 | `CollectorViewModelStakeoutTest`, `StakeoutPanelTest` |
| RF-30 | A5 | `CollectorViewModelStakeoutTest`, `StakeoutPanelTest` |
| RF-31 | A5 | `CollectorViewModelStakeoutTest` |
| RF-32 | Ficha | `PointDetailsStakeoutTest` |
| RF-33 | `PointSummary.stakeCount` | `PointRepositoryStakeoutTest`, `ObjectListStakeoutTest` |
| RF-34 | Mapa | `MapStakeoutTest` |
| RF-35 | A6 | `StakeoutControllerTest`, `StakeoutPanelTest` |
