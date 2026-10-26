# Plan técnico — Spec 041

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/041-replanteo-cad/spec.md`, `specs/018-replanteo-guiado/` y `specs/019-replanteo-cota/` (la guía, la diana, las zonas, el nivel y el informe que se reutilizan), `specs/040-capa-cad-dxf/` (los dibujos, `CadEntityRef` y el formato de los trozos).

Sin dependencias nuevas (Gradle ni Python). El motor de líneas es geometría plana sobre `Double` en `core:survey`; los valores de referencia los calcula `tools/` con `pyproj` (ya es dependencia de `tools`) y con un cálculo geométrico aparte, sin librería nueva.

## Estructura de módulos
- `core:model`:
  - `LineStakeoutMode { BY_SEGMENTS, TO_LINE, ALONG, SEGMENT_AB, INTERVALS }` (RF-3);
  - `StakeSource`: de dónde viene el punto de diseño, `ProjectPoint` o `CadElement(drawingName, layerName, kind)`, con los nombres y no los identificadores para que sobrevivan al borrado del dibujo (RF-28, RF-32); la referencia al elemento va en `StakeTarget` y en `stake_design_id`;
  - `StakeoutRef` gana `source`, `mode`, `chainageM`, `lateralOffsetM` y `dLateralM`, todos opcionales (RF-28, RF-29);
  - `CadChunkCodec` guarda una marca por vértice, «original del DXF» o «de la aproximación de una curva», y lee los trozos antiguos con todos sus vértices originales (S040/RF-34, RF-33, RF-34).
- `core:formats` (`dxf/`): `CadCurves`, `DxfBlocks` y `DxfReader` marcan los vértices que vienen escritos en el DXF y los de la aproximación (S040/RF-34).
- `core:survey` (el motor, sin Android ni Hilt; lo reutiliza S027):
  - `StakeLine`: la línea lista para consultar, con sus vértices, si es cerrada, el PK acumulado de cada vértice, las cotas y las marcas de vértice original. Sale de un `CadElement` o, en S027, de una línea del proyecto (RF-15, RF-23, RF-33);
  - `LineLocator`: el punto más cercano, el PK, la distancia lateral con su signo, el antes del inicio y el después del final (RF-10, RF-14, RF-15);
  - `LineObjective`: el objetivo de un modo con un desplazamiento lateral y el error lateral, con la cota interpolada (RF-10, RF-11, RF-17, RF-23, RF-25);
  - `LineSeries`: el tramo A–B y la serie de intervalos, con las validaciones (RF-16 a RF-21);
  - `LineVertices`: los vértices de «Por segmentos» según el tipo de línea (RF-6 a RF-9, RF-33, RF-34).
- `data`:
  - Room v14: columnas nuevas en `points` para el replanteo de un elemento de dibujo, con `AutoMigration(13, 14)` (RNF-T6; RF-28, RF-32);
  - `PointRepository` guarda y lee el `StakeoutRef` con su origen en una sola transacción (RF-27, RF-28);
  - `CadRepository.element(ref)` y `CadRepository.line(ref)`: leen el elemento entero (y los trozos en que S040 partió una polilínea larga) como `StakeLine` (RF-2, RF-10).
- `app`:
  - `StakeTarget` gana `source`; `StakeContext` gana `line: LineObjectiveProvider?` (el objetivo que se mueve con la punta) (RF-1, RF-6, RF-10);
  - `StakeoutController` calcula el objetivo de cada época (A4) y publica `LineGuidanceUi` con el PK, la distancia lateral, el error lateral y lo que falta (RF-10 a RF-15, RF-26);
  - `LineStakeoutViewModel` y `LineStakeoutPanel`: el modo, los campos, las listas de vértices y de la serie, los bloqueos y los textos (RF-2 a RF-9, RF-16 a RF-22, RF-31);
  - el botón «Replantear» en `DesignElementSheet` y la navegación (RF-1, RF-2);
  - `MapController`: la línea elegida más gruesa, el objetivo y la línea de la punta al objetivo (RF-5);
  - la ficha del punto replanteado y `StakeoutReportCsv` con las columnas nuevas (RF-29, RF-30).
- `tools`: `tools/replanteo/generar_lineas.py`, los casos de `fixtures/replanteo/lineas/` con lo esperado calculado con pyproj y con un cálculo geométrico aparte (constitución nº 2).

## Modelo de datos

### Room v14
`points` gana, todas anulables (los puntos existentes quedan como están):
```
stake_cad_drawing   TEXT     nombre del dibujo (copia: sobrevive al borrado, RF-32)
stake_cad_layer     TEXT     nombre de la capa
stake_cad_kind      TEXT     tipo de elemento original (POINT, LINE, POLYLINE, ARC, CIRCLE, SPLINE, BLOCK_INSERT)
stake_mode          TEXT     LineStakeoutMode, solo si venía de una línea
stake_chainage      REAL     PK del objetivo, en metros de cuadrícula
stake_offset        REAL     desplazamiento lateral, en metros
stake_d_lateral     REAL     error lateral en el momento de medir, en metros
```
- `AutoMigration(13, 14)` sin especificación: solo columnas nuevas. S017b (fotos) pasa de la 14 a la 15 cuando se retome (aviso en su `plan.md`, que requiere permiso para tocar `specs/`).
- Un punto replanteado de un elemento guarda las coordenadas de diseño en las columnas `stake_design_*` de S018, sin redondear, igual que uno que viene de un punto del proyecto. `stake_design_id` toma el valor `cad:<layerId>:<chunkId>:<índice>` y `stake_design_name` el nombre de la capa. No casa con ningún punto del proyecto, así que el recuento de replanteos de la lista no se ve afectado, y borrar el dibujo no toca el punto (RF-32).
- Los nombres de dibujo y de capa se copian: no hay clave foránea al dibujo (RF-32). `StakeoutRef.source` es `ProjectPoint` si `stake_cad_drawing` es nulo.
- La medida bruta no se toca (RNF-T6): el replanteo es un añadido al punto, en la misma transacción que lo guarda.

### Formato de los trozos (cambio de S040/RF-34)
- Por vértice, una marca de un bit «original»; los bits van empaquetados tras la lista de coordenadas de cada elemento y solo si el elemento lo declara con un bit nuevo en el byte de marcas (`ORIGINAL_VERTICES = 8`; las marcas actuales son `HAS_Z = 1`, `HAS_COLOR = 2`, `AUTO_CONTRAST = 4`).
- Un trozo sin el bit nuevo es un trozo de antes de S041: todos sus vértices son originales (RF-34). No hay migración de datos ni se reescriben los dibujos existentes.
- Un arco, un círculo o una spline importados desde S041 llevan siempre las marcas, aunque todos sus vértices valgan «original» (un arco diminuto de dos vértices); sin ellas, el elemento viene de un dibujo anterior y se muestra el aviso de RF-34. Las polilíneas solo las llevan si tienen tramos con bulge, y los puntos y las líneas no. No hace falta una marca del dibujo entero.

## Algoritmos

### A1. La línea como `StakeLine` (RF-15, RF-23, RF-33)
- Se arma con los vértices del elemento (Este y Norte del SC del proyecto, `Double`, sin redondear) y la cota de cada uno si el elemento tiene cota. Si S040 partió una polilínea en trozos, cada trozo es una línea distinta (suposición de la spec).
- El PK acumulado de cada vértice es la suma de las distancias planas entre vértices consecutivos, en metros de cuadrícula (RF-15). En una línea cerrada se añade el cierre al perímetro.
- Para consultar sin recorrer 100.000 vértices a 10 Hz, se guarda una rejilla de segmentos (celdas de unos 25 m) calculada una sola vez al elegir la línea (RNF: empezar a guiar en menos de 1 s). La rejilla es solo un acelerador: el resultado es el de recorrer todos los segmentos.

### A2. Punto más cercano, PK y distancia lateral (RF-10, RF-14, RF-15)
- Para cada segmento candidato, la proyección de la punta con el parámetro recortado a [0, 1]; se elige la menor distancia. En un vértice con dos candidatos a la misma distancia se toma el primero.
- La búsqueda empieza por los segmentos de las celdas junto a la punta y se amplía al doble de radio hasta encontrar uno; si no hay ninguno en 2 km se recorre todo. La última posición se usa de arranque en la época siguiente (la punta se mueve poco entre épocas).
- El PK del punto más cercano es el acumulado del inicio del segmento más el parámetro por su longitud. El signo de la distancia lateral es el del producto vectorial del sentido del segmento por el vector hacia la punta: positivo a la derecha.
- Antes del inicio y después del final (solo en una línea abierta): el punto más cercano es el extremo, la distancia es hasta él, sin lado y con el motivo `BEFORE_START` o `AFTER_END` (RF-14). En una línea cerrada no hay extremos.
- Lo que falta hasta el final es el perímetro, o la longitud, menos el PK (RF-10).

### A3. La paralela con desplazamiento (RF-11, RF-17, RF-25)
- El objetivo del modo es el punto del segmento más cercano desplazado `d` perpendicularmente, positivo a la derecha. No se recorta ni se une en las esquinas: en un vértice se usa el segmento más cercano (suposición de la spec). La distancia lateral y el error lateral (distancia lateral menos `d`) salen del punto más cercano de la línea sin desplazar, y la guía hacia el objetivo sale del objetivo desplazado.
- «A la línea» es el caso `d = 0`. La llegada horizontal depende solo del error lateral (RF-13, RF-25), sin mirar el PK.
- Cota: la Z interpolada linealmente entre los vértices del segmento en el PK del objetivo (RF-23); sin cota queda como `null` y la escribe el usuario (RF-24).

### A4. El objetivo que se mueve con la punta (RF-6, RF-10 a RF-13, RF-26)
- `StakeoutController.onEpoch` ya calcula la guía contra un punto de diseño fijo (`context.target`). Con una línea, `StakeContext.line` entrega en cada época el objetivo según el modo y la punta; el controlador lo usa como `design` y todo lo demás de S018 y S019 queda igual: zonas, diana, avisos, nivel, silencio y apagado.
- La distancia a la que se aplican las zonas y la diana es la distancia al objetivo, que en «A la línea», «A lo largo» y «Tramo A–B» es el error lateral en valor absoluto cuando el punto más cercano está dentro del tramo (RF-13).
- `HeadingSource` necesita una posición de la punta respecto a un punto fijo para el rumbo de marcha. Con un objetivo que se mueve, se le da la punta respecto a un origen fijo (el primer vértice de la línea) y no respecto al objetivo, y el rumbo del usuario no cambia al moverse el objetivo.
- Con «Por segmentos» e «Intervalos» el objetivo es un punto fijo (el vértice o el punto de la serie elegido): `context.line` es nulo y se usa `target` tal cual (RF-6, RF-20).
- En el modo «Tramo A–B», fuera del tramo el objetivo es el extremo más cercano (RF-17).
- La guía apagada (RF-26) reutiliza `GuidanceOff` sin cambios.

### A5. Tramo A–B e intervalos (RF-16 a RF-22)
- A y B son PK; por defecto el inicio y el final de la línea (el perímetro en una cerrada). «Invertir» los intercambia; «Mi posición» pone en A el PK del punto más cercano a la punta, o no hace nada si la guía está apagada.
- Si A > B se recorre en sentido inverso: el PK relativo a A y lo que falta hasta B salen con ese sentido.
- La serie: A, A + i, A + 2i, … y B siempre al final aunque no sea múltiplo; si `i ≥ |B − A|` solo A y B (RF-19). Cada punto se calcula en su PK, sobre la línea o sobre la paralela, con su cota (RF-18, RF-23).
- Validación (RF-21), sin aplicar el valor y con el motivo: no es número, PK fuera de [0, longitud], A = B, intervalo ≤ 0. Se evalúa en el `ViewModel` con un resultado `LineInputError`.
- Con una medida en marcha, todos esos cambios se rechazan (RF-22).

### A6. Los vértices de «Por segmentos» (RF-6 a RF-9, RF-33, RF-34)
- Los vértices originales con su PK. En un arco o una spline, solo los dos extremos; en un círculo, el punto de partida (RF-9).
- Los puntos de un dibujo anterior a S041 no traen marcas: todos sus vértices son originales y, en un arco, una spline o un círculo, `PromptReimport` avisa en «Por segmentos» (RF-34).
- «Siguiente» y «Anterior» no pasan de los extremos; en una línea cerrada, tras el último vuelve al primero (RF-7).

### A7. Medir y guardar (RF-27 a RF-32)
- El nombre propuesto es `StakeoutNames.next("<capa>", takenNames)`, que da `<capa>_<n>_stkd` (RF-27). El usuario puede cambiarlo antes de medir.
- Al terminar la medida, `StakeoutDiff.ref` recibe el objetivo del momento (no el de ahora) con la cota de diseño, y devuelve el mismo `StakeoutRef` de S018 y S019, al que se añaden el origen, el modo, el PK, el desplazamiento lateral y el error lateral (RF-28). El error lateral sale con el mismo signo que en la guía.
- Un solo `PointRepository.save(point, stakeoutRef)`: el punto y todo lo del replanteo en una transacción (RNF-T6).
- Cuando se borra el dibujo, el `ViewModel` que tiene abierto el replanteo lo observa (`CadRepository.drawings`) y lo cierra con el motivo (RF-31); los puntos conservan sus copias (RF-32).

### A8. Informe de replanteo (RF-30)
- `StakeoutReportCsv` añade seis columnas, dibujo, capa, tipo de elemento, modo, PK y desplazamiento lateral, tras las de S019, vacías en los puntos que vienen de un punto del proyecto. Los números van con `Locale.ROOT` y las unidades del proyecto.
- La pantalla del informe incluye los puntos replanteados de un dibujo, que ya salen de la misma consulta por `stake_within`.

### A9. El mapa y el botón (RF-1, RF-2, RF-4, RF-5)
- «Replantear» en `DesignElementSheet`: un punto o un centro de bloque abre el replanteo de S018 con `StakeTarget(source = CadElement)`; una línea abre `LineStakeoutPanel`.
- El mapa dibuja la línea elegida con la anchura doble y el objetivo con la marca de S018 mediante `MapController.showStakeLine(line, objective, tip)`. La capa de S040 sigue pudiendo estar oculta; el replanteo dibuja lo suyo aparte (caso límite de la spec).

## Contratos

### Estado de la guía de línea (`LineGuidanceUi`)
`mode`, `chainageM`, `remainingM`, `lateralM` (con signo) y su `side` (`LEFT`, `RIGHT`, `ON_LINE`), `lateralErrorM`, `position` (`ON`, `BEFORE_START`, `AFTER_END`), `objective` y los demás campos de `GuidanceUi`. Texto de TalkBack: «a 1,2 m a la derecha de la línea, en el PK 45,0».

### Pantalla del replanteo de línea
Una hoja inferior sobre el mapa con el nombre de la capa y del dibujo, el tipo, la longitud y la cota (RF-4); un selector de modo con cinco opciones (RF-3); los campos de cada modo (desplazamiento, A, B, intervalo, «Invertir» y «Mi posición»); la lista de vértices o de puntos de la serie con «Anterior» y «Siguiente» (RF-8, RF-20); la guía de S018 con la distancia lateral, su lado en texto y el PK; y «Medir».

### Textos nuevos (`strings.xml`, es y en)
Replantear (en la ficha), los cinco modos, PK, desplazamiento lateral, distancia lateral, error lateral, «a la derecha» y «a la izquierda», «Antes del inicio» y «Después del final», «Invertir», «Mi posición», intervalo, A y B, «Siguiente» y «Anterior», cada motivo de `LineInputError`, el aviso de RF-34, el cierre por el borrado del dibujo (RF-31) y los textos de TalkBack.

### Accesibilidad (RNF-T8)
Los campos y botones tienen 56 dp; el lado de la distancia lateral y «Dentro de la tolerancia» van siempre en texto además del color; TalkBack lee la frase de la distancia lateral al entrar en cada zona y a petición, no en cada refresco.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Un objetivo que se mueve dentro del `StakeoutController` existente | Zonas, diana, avisos, nivel, silencio y apagado de S018 y S019 se reutilizan sin copiarlos (RF-12, RF-13, RF-26) | Un segundo controlador para las líneas |
| El motor de líneas en `core:survey`, sin Android | Lo reutiliza S027 para las líneas del proyecto, y se prueba frente a pyproj en JVM | Ponerlo en `app` |
| `LineLocator` con una rejilla de segmentos como acelerador | 100.000 vértices a 10 Hz sin pasar de 16 ms y empezar a guiar en menos de 1 s (RNF-T13) | Recorrer todos los segmentos en cada época; un R-tree |
| El rumbo de marcha respecto a un origen fijo | Un objetivo que se mueve estropearía el rumbo del usuario | Recalcular el rumbo contra el objetivo |
| Una marca de vértice original por bit dentro de un elemento | Dibujos anteriores siguen legibles sin migrar datos (RF-34) y el trozo no crece más de 1 bit por vértice | Una columna nueva o reescribir los trozos |
| Copiar los nombres de dibujo y de capa en el punto | Borrar el dibujo no debe dejar el punto sin origen (RF-32) | Una clave foránea al dibujo |
| `stake_design_id` con el valor `cad:<capa>:<trozo>:<índice>` | No toca el código de S018 que cuenta replanteos por punto del proyecto | Una columna aparte para la referencia |
| `AutoMigration(13, 14)` sin especificación | Solo columnas nuevas | Migración a mano |
| PK y distancias de cuadrícula | RF-15 y la suposición de la spec; las de terreno son de S026 | Distancias de terreno |

## Estrategia de tests
- **Unitarios JVM**:
  - `CadChunkCodecTest` (ampliado): la marca por vértice y la lectura de un trozo antiguo con todo original (RF-33, RF-34).
  - `CadCurvesTest`, `DxfBlocksTest` y `DxfReaderTest` (ampliados): vértices originales y de la aproximación en arcos, círculos, splines y bulges (RF-33).
  - `StakeLineTest`: PK acumulado, línea cerrada, cotas (RF-15, RF-23).
  - `LineLocatorTest`: punto más cercano, PK y distancia lateral frente a `fixtures/replanteo/lineas/` (≤ 1 mm), con rectas, polilíneas, cerradas, antes y después, ambos lados y 100.000 vértices (RF-10, RF-14, RF-15, RNF-T13).
  - `LineObjectiveTest`: el desplazamiento a ambos lados, el error lateral y la cota interpolada (RF-11, RF-17, RF-23, RF-25).
  - `LineSeriesTest`: A–B, «Invertir», «Mi posición», intervalo mayor que el tramo, B fuera de múltiplo y todas las validaciones (RF-16 a RF-21).
  - `LineVerticesTest`: tipos de línea, extremos de un arco y de una spline, círculo y un dibujo antiguo (RF-6 a RF-9, RF-34).
- **`data`**:
  - `TopoPhoneDatabaseTest` 13 → 14 con un proyecto con dibujos y puntos replanteados: se conservan.
  - `PointRepositoryTest` (ampliado): el punto y su replanteo de un elemento en una transacción, y el dibujo borrado después (RF-27, RF-28, RF-32).
  - `CadRepositoryTest` (ampliado): leer un elemento o una línea partida por S040 como `StakeLine` (RF-2).
- **Robolectric (`app`)**:
  - `StakeoutControllerLineTest`: el objetivo que se mueve, la diana, las zonas con el error lateral, los avisos y el apagado por FIX perdido (RF-12, RF-13, RF-25, RF-26).
  - `LineStakeoutViewModelTest`: el modo que se recuerda, los campos, los bloqueos con una medida en marcha, el cierre por el borrado del dibujo y los nombres (RF-2, RF-3, RF-16 a RF-22, RF-27, RF-31).
  - `LineStakeoutPanelTest`: cada modo, las listas, los textos de RF-14 y las etiquetas de TalkBack (RF-4, RF-8, RF-14, RF-20, RNF-T8).
  - `DesignElementSheetTest` (ampliado): el botón «Replantear» y lo que abre (RF-1, RF-2).
  - `StakeoutMeasureLineTest`: lo guardado al medir, las diferencias y la ficha del punto (RF-28, RF-29).
  - `StakeoutReportCsvTest` (ampliado): las seis columnas, vacías en un punto del proyecto (RF-30).
  - `LineMapMarkersTest`: la línea gruesa, el objetivo y la punta (RF-5).
- **`tools`**: tests de `generar_lineas.py` (que los ficheros y lo esperado guardados son los que genera el script).
- **Rendimiento**: `LineStakeoutPerformanceTest`: una línea de 100.000 vértices empieza a guiar en menos de 1 s y el punto más cercano a 10 Hz no pasa de 16 ms por consulta.
- **Reproducción sintética**: un recorrido paralelo a una línea con un desplazamiento, con su resultado esperado calculado aparte (criterios de finalización).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, la puerta G2b, que hace el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A9 | `DesignElementSheetTest`, `LineStakeoutViewModelTest` |
| RF-2 | A1, A9 | `CadRepositoryTest`, `LineStakeoutViewModelTest`, `DesignElementSheetTest` |
| RF-3 | `LineStakeoutMode` | `LineStakeoutViewModelTest` |
| RF-4 | Pantalla | `LineStakeoutPanelTest` |
| RF-5 | A9 | `LineMapMarkersTest` |
| RF-6 | A4, A6 | `LineVerticesTest`, `StakeoutControllerLineTest` |
| RF-7 | A6 | `LineVerticesTest`, `LineStakeoutViewModelTest` |
| RF-8 | A6 | `LineVerticesTest`, `LineStakeoutPanelTest` |
| RF-9 | A6 | `LineVerticesTest` |
| RF-10 | A2 | `LineLocatorTest`, `StakeoutControllerLineTest` |
| RF-11 | A3 | `LineObjectiveTest` |
| RF-12 | A4 | `StakeoutControllerLineTest` |
| RF-13 | A4 | `StakeoutControllerLineTest` |
| RF-14 | A2 | `LineLocatorTest`, `LineStakeoutPanelTest` |
| RF-15 | A1, A2 | `StakeLineTest`, `LineLocatorTest` |
| RF-16 | A5 | `LineSeriesTest`, `LineStakeoutViewModelTest` |
| RF-17 | A3, A5 | `LineObjectiveTest`, `LineSeriesTest` |
| RF-18 | A5 | `LineSeriesTest` |
| RF-19 | A5 | `LineSeriesTest` |
| RF-20 | A4, A5 | `LineSeriesTest`, `LineStakeoutPanelTest` |
| RF-21 | A5 | `LineSeriesTest`, `LineStakeoutViewModelTest` |
| RF-22 | A5 | `LineStakeoutViewModelTest` |
| RF-23 | A1, A3 | `StakeLineTest`, `LineObjectiveTest` |
| RF-24 | A3 | `StakeoutControllerLineTest`, `LineStakeoutViewModelTest` |
| RF-25 | A3, A4 | `LineObjectiveTest`, `StakeoutControllerLineTest` |
| RF-26 | A4 | `StakeoutControllerLineTest` |
| RF-27 | A7 | `LineStakeoutViewModelTest`, `StakeoutMeasureLineTest` |
| RF-28 | A7, Room v14 | `StakeoutMeasureLineTest`, `PointRepositoryTest` |
| RF-29 | A7 | `StakeoutMeasureLineTest` |
| RF-30 | A8 | `StakeoutReportCsvTest` |
| RF-31 | A7 | `LineStakeoutViewModelTest` |
| RF-32 | Room v14, A7 | `PointRepositoryTest` |
| RF-33 | Formato de los trozos | `CadChunkCodecTest`, `CadCurvesTest`, `LineVerticesTest` |
| RF-34 | Formato de los trozos, A6 | `CadChunkCodecTest`, `LineVerticesTest`, `LineStakeoutPanelTest` |

## Pendiente para otras specs
- S017b (fotos) pasa a la versión 15 de la base de datos (`AutoMigration(14, 15)`); su `plan.md` aún habla de la 11, la 12 o la 14, y solo se corrige con permiso para tocar `specs/`.
- S027 reutiliza `StakeLine`, `LineLocator`, `LineObjective` y `LineSeries` con las líneas propias del proyecto; el `StakeSource` tendrá una variante para ellas.
- S026 (información geométrica y distancias de terreno) puede reutilizar `StakeLine` para la longitud y la pendiente.
- S040: se añade RF-34 a su `spec.md` (diff mostrado y aprobado con la spec 041) y se actualiza su `plan.md` con el bit de vértices originales.
