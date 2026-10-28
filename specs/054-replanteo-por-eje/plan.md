# Plan técnico — Spec 054

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/054-replanteo-por-eje/spec.md`, `specs/049-ejes-y-rasantes/plan.md` (`AlignmentGeometry`, `VerticalProfileEval`, `AxisPointBuilder`, el PK y el desplazamiento), `specs/018-replanteo-guiado/` y `specs/019-replanteo-cota/` (la guía, la diana, las zonas, el nivel y el informe que se reutilizan), `specs/041-replanteo-cad/plan.md` (el objetivo guardado con su origen, el PK y el error lateral; el informe) y `specs/042-superficies-landxml/plan.md` (la superficie activa). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- La guía es el `StakeoutController` de S018 con un **punto de diseño fijo** (el del PK y el desplazamiento elegidos), igual que «Por segmentos» e «Intervalos» de S041. El punto cambia solo cuando el usuario cambia el PK, el desplazamiento o el espesor, así que no hace falta un objetivo que se mueva con la punta. Alternativa descartada: un segundo controlador de guía.
- La cota de diseño llega por el `designElevationM` que ya tiene `StakeContext` (rasante en el PK más espesor), así que el nivel de S019 y su tolerancia vertical funcionan sin tocarlos.
- Las referencias independientes las genera `tools/ejes/generar_ejes.py` (ampliado con una reproducción NMEA que recorre un eje), con las series de Fresnel de S049. Alternativa descartada: reutilizar solo `AlignmentGeometry` en los tests (la prueba sería una tautología).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Cómo se guarda un punto replanteado por PK (RF-10). `StakeSource` solo conoce puntos del proyecto y elementos de un dibujo | `StakeSource.Alignment(alignmentName)` (el nombre copiado, como el dibujo en S041, para que borrar el eje no toque el punto) y **Room v21** (S017b pasa a la v22) con cinco columnas anulables en `points`: `stake_axis`, `stake_thickness`, `stake_d_chainage`, `axis_d_height`, `axis_height_ref`. El PK, el desplazamiento y el error transversal reutilizan `stake_chainage`, `stake_offset` y `stake_d_lateral` de S041 | Reutilizar `stake_cad_drawing` con un prefijo (sin migración, pero mezcla dos conceptos y rompe el informe) |
| D2 | Puntos singulares (RF-4). El glosario dice «cambia el tipo de tramo o el vértice de la rasante» | Al pie de la letra: el PK donde cambia la clase de tramo (recta, arco, clotoide) y el PK de cada vértice de la rasante. No se añaden el inicio y el final de los acuerdos verticales | Incluir también el inicio y el final de cada acuerdo (útil en obra, pero la spec no lo pide) |
| D3 | Cambiar de eje en mitad del replanteo (RF-11) | El selector del replanteo **cambia el eje activo del proyecto** (un solo concepto de «activo», como en S049/RF-14). El PK, el desplazamiento y el espesor escritos se conservan y se vuelven a validar contra el eje nuevo (si el PK no cabe, RF-9) | El selector cambia el eje solo dentro del replanteo y no toca el activo del proyecto |
| D4 | Si el eje se borra con el replanteo o el análisis abiertos (no está en la spec, pero hay que decidir qué pasa) | Se cierra con el motivo «Se ha borrado el eje», como S041/RF-31 con el dibujo. Los puntos replanteados conservan su copia del nombre | Dejarlo abierto con el último estado (sin geometría que calcular) |
| D5 | Referencia de cota del análisis (RF-12; [SUPOSICIÓN] de la spec) | Empieza con la rasante si el eje la tiene y con la superficie activa si no; el conmutador solo aparece cuando hay las dos; sin ninguna, solo PK y desplazamiento. No se recuerda entre sesiones | Recordar la última elección por proyecto |
| D6 | Dónde están «Replantear eje» y «Analizar» (RF-1, RF-12) | En cada fila de `AlignmentsSheet` (junto a «Punto sobre eje») y en la ficha que sale al tocar un eje en el mapa (`AlignmentFicha`) | Además, en el menú del proyecto |
| D7 | Cómo se escribe el desplazamiento (RF-1, RF-5) | Con signo, negativo a la izquierda y positivo a la derecha (como en «Punto sobre eje» de S049), con la palabra «izquierda» o «derecha» siempre al lado (RNF-T8) | Un selector Izquierda/Derecha y un valor siempre positivo |

## Estructura de módulos
- `core:model`
  - `StakeSource.Alignment(alignmentName)` (D1). `StakeoutRef` gana `dChainageM` y `thicknessM`; `AxisRef` gana `dHeightM` y `heightRef` (`AxisHeightRef { PROFILE, SURFACE }`) para los puntos medidos desde «Analizar» (RF-13).
  - `Point.measured` acepta un `AxisRef` (hoy solo lo trae el punto calculado `ON_ALIGNMENT`).
- `core:survey` (sin Android ni Hilt)
  - `AxisStakeTarget`: dado el eje, el PK, el desplazamiento y el espesor da el punto de diseño (Este y Norte, de `AlignmentGeometry.pointAt`), el azimut del eje en ese PK y la cota de diseño (`VerticalProfileEval.at` más el espesor), o el motivo por el que no se puede (RF-1, RF-7, RF-9).
  - `AxisStakeInputs`: valida lo escrito (PK, desplazamiento, espesor, PK final e intervalo) con el motivo de cada error, y el PK fuera del eje con el PK inicial y el final del eje (RF-9).
  - `AxisChainageSeries`: la lista de PK del replanteo: inicio, inicio + i, …, final, mezclada en orden con los puntos singulares de dentro del rango y sin repetidos a menos de 1 mm; «siguiente» y «anterior» no pasan de los extremos (RF-2 a RF-4). Los puntos se calculan al pedirlos, como `LineSeries`.
  - `KeyStations`: los PK singulares de un eje (D2).
  - `AxisGuidance`: descompone «diseño menos punta» en lo que hay que mover **adelante o atrás en PK** y **hacia dentro o fuera del eje** con el azimut del eje en el PK de diseño (RF-6). Con desplazamiento 0 no hay dentro ni fuera: se dice izquierda o derecha.
  - `AxisAnalysis`: el PK, el desplazamiento y la diferencia de cota de una posición (`locate` y `VerticalProfileEval` o la superficie), o «sin proyección» (RF-8, RF-12, RF-14).
- `data`
  - Room v21: las cinco columnas de D1, `AutoMigration(20, 21)` sin especificación. `PointRepository` guarda y lee el `StakeoutRef` con su `StakeSource.Alignment` y el `AxisRef` ampliado en una sola transacción (RF-10, RF-13, RNF-T6).
  - `DatabaseSnapshot`: las columnas viajan con la tabla `points`, no cambia la lista de tablas.
- `core:formats`
  - `StakeoutReportCsv` añade tres columnas al final, tras las de la superficie: `Alignment`, `Thickness`, `Delta chainage`. El PK y el desplazamiento van en las columnas `Chainage` y `Lateral offset` de S041, que ya existían (RF-10).
- `app`
  - `ui/stakeout/AxisStakeoutViewModel` y `AxisStakeoutPanel`: el eje, los campos, la lista de PK, los bloqueos y los textos (RF-1 a RF-9, RF-11). Reutiliza `StakeoutController`, `StakeObjective`/`StakeObjectiveProvider` y el recolector para medir y guardar.
  - `ui/alignment/AxisAnalysisViewModel` y `AxisAnalysisPanel` (RF-12 a RF-14).
  - `AlignmentsSheet` y `AlignmentFicha`: los dos botones nuevos (D6). `ProjectViewScreen`: el hueco de la parte baja ya aloja el replanteo de puntos y el de líneas; aquí se pasa a un estado único `StakeMode { NONE, POINT, LINE, AXIS, ANALYSIS }` en lugar de un booleano por modo.
  - El mapa dibuja el objetivo con la marca de S018 (`MapController.showStakeLine` ya sirve) y, en el análisis, la proyección de la punta sobre el eje.
  - La ficha del punto (`PointDetailsScreen`) y el informe de replanteo (S019) muestran el eje, el PK y el desplazamiento (RF-10).
- `tools/ejes`: una reproducción NMEA que recorre un eje con desplazamiento, sus épocas esperadas (PK, desplazamiento, guía y desmonte, calculadas con las series de Fresnel) en `fixtures/ejes/replanteo/`.

## Modelo de datos
### Room v21
```
points  + stake_axis TEXT NULL, stake_thickness REAL NULL, stake_d_chainage REAL NULL,
          axis_d_height REAL NULL, axis_height_ref TEXT NULL
```
- `AutoMigration(20, 21)` sin especificación: solo columnas nuevas y anulables. Los puntos existentes quedan como están.
- Un punto replanteado por PK guarda las coordenadas de diseño en las columnas `stake_design_*` de S018, sin redondear. `stake_design_id` toma `axis:<nombre del eje>:<PK en milímetros>` y `stake_design_name` el nombre del eje; no casa con ningún punto del proyecto, así que no cuenta como replanteo de un punto de la lista. `stake_axis` es el nombre del eje copiado: borrar el eje no toca el punto (como RF-32 de S041).
- Una medida de «Analizar» es un punto medido normal que lleva `axis_name`, `axis_chainage` y `axis_offset` (las columnas de S049), más `axis_d_height` (medido menos referencia) y `axis_height_ref`. No es un replanteo: no lleva `stake_*`.
- La medida bruta no se toca (RNF-T6): todo es un añadido al punto, en la misma transacción que lo guarda.

## Algoritmos
### A1. El punto de diseño por PK (RF-1, RF-7, RF-9)
`AlignmentGeometry.pointAt(pk, offset)` da el Este y el Norte; la cota es `VerticalProfileEval.at(pk).elevation + espesor` si el eje tiene rasante y el PK está dentro de ella, o `null` (se replantea solo la planta, RF-7). Un PK fuera de `[startChainage, endChainage]` o un valor que no es número no abre el replanteo e informa del PK inicial y el final (RF-9). El desplazamiento 0 es el eje.

### A2. Serie de PK con puntos singulares (RF-2 a RF-4; D2)
- Sin intervalo: solo el PK inicial (RF-2). Con intervalo: `pk0, pk0 + i, pk0 + 2i, …` hasta el PK final (por defecto el final del eje) sin pasarse (RF-3); el PK final se incluye siempre aunque no sea múltiplo.
- Con «Puntos singulares», los PK singulares dentro de `[pk0, pkFinal]` se mezclan por orden; los que distan menos de 1 mm de otro se funden en uno (RF-4). Sin intervalo y con la casilla, la serie es el PK inicial más los singulares hasta el final.
- Si el PK final es menor que el inicial se recorre hacia atrás. «Siguiente» y «anterior» no pasan de los extremos.

### A3. Guía a lo largo y a través del eje (RF-6)
Con `Δ = diseño − punta` y el azimut `az` del eje en el PK de diseño: `adelante = Δ·(sen az, cos az)` y `derecha = Δ·(cos az, −sen az)`. La app dice «adelante/atrás» y «hacia la izquierda/derecha»; con desplazamiento ≠ 0 dice «hacia dentro» o «hacia fuera» según el movimiento acerque la punta al eje o la aleje. El conmutador a Norte y Este usa los de S018. La diana, las zonas, los avisos y el nivel son los de S018 y S019 sin cambios, porque el objetivo es un punto fijo.

### A4. Cota y desmonte/terraplén (RF-7)
La cota de diseño entra por `StakeContext.designElevationM`. El nivel de S019 compara la cota de la punta con ella y con la tolerancia vertical; el desmonte (punta por encima) o el terraplén (por debajo) y la unidad salen del `LevelUi` existente. Cambiar el PK, el desplazamiento o el espesor reinicia el nivel, como cambiar la cota escrita en S019.

### A5. Guardar el replanteo (RF-10)
`StakeObjective` (hoy con un `mode` obligatorio) pasa a admitir `mode = null`: el PK, el desplazamiento y el espesor viajan en él, y `lateralErrorOf` pasa a devolver el error transversal; un segundo cálculo da el error en PK (`dChainage`). El recolector termina la medida, pide el objetivo de ese momento y guarda con `StakeoutDiff.ref(...).copy(source = Alignment, chainageM, lateralOffsetM, dLateralM, dChainageM, thicknessM)`. El nombre propuesto es `<eje>_<n>_stkd` con `StakeoutNames.next`.

### A6. Análisis en vivo (RF-12 a RF-14; D5)
La punta de cada época se calcula como en `StakeoutController` (`LiveTip` y `ProjectPositionCalculator`). `AxisGeometry.locate(e, n)` da el PK y el desplazamiento; si devuelve `null` (antes del inicio o después del final) se dice «sin proyección» y no se muestra PK ni desplazamiento (RF-14). La diferencia de cota es la de la punta menos la rasante en ese PK, o menos la superficie activa en esa posición (fuera de ella, sin diferencia). «Medir» usa el recolector (S010) con el `AxisRef` del PK y desplazamiento de la época en que terminó la medida (RF-13).

### A7. Refresco y rendimiento (RNF-T13)
`locate` y `pointAt` ya recorren un eje de 5.000 tramos en menos de 16 ms (S049/T25). La guía se publica a 5 Hz como la de S018. Los PK singulares y la serie se calculan fuera del hilo principal.

## Contratos
- `AxisStakeTarget.of(geometry, profile?, chainage, offset, thickness): AxisStakeResult` (`Target` o `OutsideAxis(start, end)`).
- `AxisChainageSeries(geometry, profile?, start, end?, interval?, keyStations)`: `size`, `chainageOf(i)`, `next`, `previous`.
- `AxisGuidance.of(target, tipE, tipN): AxisGuidanceValue(alongM, acrossM, inward: Boolean?)`.
- `AxisAnalysis.of(geometry, profile?, surfaceHeight?, e, n, h): AxisAnalysisValue`.
- Navegación: `SurveyPage.AxisStakeout(projectId, alignmentId)` y `SurveyPage.AxisAnalysis(projectId, alignmentId)` si el panel no cabe en el hueco del mapa; si cabe, solo estado del `ProjectView` (ADR-19).

Textos nuevos (es y en): Replantear eje, Analizar, PK inicial, PK final, Desplazamiento, Espesor, Intervalo de PK, Puntos singulares, Siguiente PK, PK anterior, «Adelante»/«Atrás», «Hacia dentro»/«Hacia fuera», «a la izquierda»/«a la derecha», «Fuera del eje: PK inicial %1$s, PK final %2$s», «Sin proyección sobre el eje», «Desmonte»/«Terraplén» (los de S019), «Eje», «Se ha borrado el eje», «Cota: rasante»/«Cota: superficie», «Medir», las columnas del informe y las frases de TalkBack.

Accesibilidad (RNF-T8): campos y botones de 56 dp; el desplazamiento con la palabra «izquierda» o «derecha» además del signo; el estado y el desmonte o terraplén, en texto además del color; TalkBack lee «PK 1+240, 3,50 metros a la derecha, desmonte de 12 centímetros» al entrar en cada zona y a petición, no en cada refresco. Pantalla encendida en el replanteo y en el análisis (RNF-T2).

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Punto de diseño fijo dentro del `StakeoutController` | Diana, zonas, avisos, nivel, silencio y apagado de S018 y S019 se reutilizan sin copiarlos | Un controlador nuevo o un objetivo que se mueve |
| La serie de PK se calcula al pedirla | Un intervalo diminuto en un eje de 50 km no construye una lista enorme | Materializar todos los PK |
| `StakeSource.Alignment` con el nombre copiado | Borrar el eje no deja el punto sin origen (como RF-32 de S041) | Clave foránea al eje |
| Columnas nuevas en `points` en lugar de una tabla de replanteos por eje | Un punto replanteado ya es una fila con `stake_*`; una tabla aparte duplicaría el modelo | Tabla `stakeout_axis` |
| El análisis no es un replanteo | No tiene punto de diseño: guarda un punto medido con su referencia al eje (RF-13) | Meterlo en `stake_*` con un objetivo vacío |
| Un único `StakeMode` en `ProjectView` | Ya hay dos booleanos (`stakeoutOpen`, `lineOpen`) y los modos son excluyentes | Un tercer y cuarto booleano |

## Estrategia de tests
- **`tools`:** `generar_ejes.py` ampliado y su test: los ficheros y lo esperado guardados son los que genera el script.
- **JVM `core:model`:** `StakeoutRef` y `AxisRef` ampliados (valores por omisión, `fromAlignment`).
- **JVM `core:survey`:** `AxisStakeTargetTest` (PK, desplazamiento a ambos lados, espesor, sin rasante, PK fuera del eje) contra `fixtures/ejes/replanteo/` a 1 mm; `AxisStakeInputsTest`; `AxisChainageSeriesTest` (sin intervalo, intervalo que se pasa del final, singulares, sin repetidos, hacia atrás); `KeyStationsTest`; `AxisGuidanceTest` (adelante, atrás, dentro, fuera, desplazamiento 0, en curva y en clotoide); `AxisAnalysisTest` (con rasante, con superficie, sin proyección, antes y después).
- **Reproducción:** `AxisStakeoutReplayTest`: una reproducción que recorre un eje con desplazamiento; la guía de cada época coincide con el cálculo aparte (PK, desplazamiento, adelante, a través y desmonte) a 1 mm.
- **`data`** (nombres de test de 60 caracteres o menos): migración 20 → 21 con proyectos, ejes y puntos que se conservan; `AxisStakePointTest` (el punto y su replanteo en una transacción, borrar el eje después); `AxisMeasuredPointTest` (el `AxisRef` ampliado).
- **`core:formats`:** `StakeoutReportCsvTest` ampliado (tres columnas nuevas, vacías en los demás).
- **Robolectric `app`:** `AxisStakeoutViewModelTest`, `AxisStakeoutPanelTest`, `AxisAnalysisViewModelTest`, `AxisAnalysisPanelTest`, `AlignmentsSheetTest` y `AlignmentFichaTest` ampliados, `PointDetailsScreenTest` ampliado, `AxisStakeoutMeasureTest`.
- **Rendimiento:** `AxisStakeoutPerformanceTest`: la guía de una época en un eje de 5.000 tramos no pasa de 16 ms.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, panel | `AxisStakeInputsTest`, `AxisStakeoutViewModelTest`, `AxisStakeoutPanelTest` |
| RF-2, RF-3 | A2 | `AxisChainageSeriesTest`, `AxisStakeoutViewModelTest` |
| RF-4 | A2, D2 | `KeyStationsTest`, `AxisChainageSeriesTest` |
| RF-5 | panel | `AxisStakeoutViewModelTest` |
| RF-6 | A3 | `AxisGuidanceTest`, `AxisStakeoutReplayTest`, `AxisStakeoutPanelTest` |
| RF-7 | A1, A4 | `AxisStakeTargetTest`, `AxisStakeoutReplayTest`, `AxisStakeoutViewModelTest` |
| RF-8 | A6 | `AxisAnalysisTest`, `AxisStakeoutPanelTest` |
| RF-9 | A1 | `AxisStakeTargetTest`, `AxisStakeInputsTest`, `AxisStakeoutViewModelTest` |
| RF-10 | A5, Room v21 | `AxisStakePointTest`, `AxisStakeoutMeasureTest`, `StakeoutReportCsvTest`, `PointDetailsScreenTest` |
| RF-11 | D3, D4 | `AxisStakeoutViewModelTest`, `AlignmentsSheetTest` |
| RF-12 | A6 | `AxisAnalysisTest`, `AxisAnalysisViewModelTest` |
| RF-13 | A6 | `AxisMeasuredPointTest`, `AxisAnalysisViewModelTest` |
| RF-14 | A6 | `AxisAnalysisTest`, `AxisAnalysisPanelTest` |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S054 a «implementada»; S017b pasa a la v22.
- `docs/glosario.md`: «Espesor» y «Punto singular» ya están; no se añade nada.
- S018 y S019: sin cambios; el replanteo por PK reutiliza la diana y el desmonte y terraplén.
- La cabecera de estado de `specs/054-*/spec.md` no se toca sin que lo pidas.
