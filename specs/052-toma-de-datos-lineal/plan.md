# Plan técnico — Spec 052

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/052-toma-de-datos-lineal/spec.md`, `specs/049-ejes-y-rasantes/plan.md` (`PiAlignmentBuilder`, `AlignmentGeometry.locate`, `AlignmentRepository`), `specs/050-peraltes-y-transversales/plan.md` (`CrossSection` de terreno, `SectionRepository.addSections`), `specs/054-replanteo-por-eje/plan.md` (`AxisStakeoutViewModel`, series de PK, `AxisReferenceProvider`) y `specs/010-*`, `specs/017-*` (recolector y modo automático por distancia). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El eje tomado se construye con `PiAlignmentBuilder` (S049/RF-8) tal cual; no se escribe otro constructor. Alternativa descartada: un ajuste propio de arcos por mínimos cuadrados (fuera de alcance de la spec).
- Las referencias independientes las calcula `tools/ejes/generar_toma_lineal.py` con `fractions` y las fórmulas de S049 para la planta; el eje tomado se compara con `pi-vertices.csv` de S049. Alternativa descartada: usar `AlignmentGeometry` en los tests (sería una tautología).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Cómo se guarda una toma (RF-2, RF-9) | Dos tablas: `linear_surveys` (la toma) y `linear_survey_points` (qué puntos, en qué orden, si están incluidos y su PK, desplazamiento y marca). Cada alta de punto en la toma es una inserción atómica. **Room v27** | Un bloque binario con la lista de puntos en la fila de la toma |
| D2 | Punto frente a toma (RF-2, RF-8) | El punto es un punto normal (S010). Borrar el punto lo quita de la toma (`CASCADE`); borrar la toma o el eje no borra puntos (RF-8, RF-22) | Que la toma sea dueña de sus puntos |
| D3 | PK y desplazamiento de un punto del perfil (RF-11, RF-14, RF-17) | Se calculan al incluirlo en la toma, desde sus coordenadas con `AlignmentGeometry.locate`, y se guardan en `linear_survey_points`; valen igual para guiado, libre y selección | Releerlos del `AxisRef` o `StakeoutRef` del punto (no existen en la selección) |
| D4 | Modo guiado (RF-10, RF-16) | La guía es la del replanteo por PK de S054 (`AxisStakeoutViewModel` con la serie desde, hasta e intervalo; en el transversal, un solo PK) y el panel de toma se coloca encima. No hay un controlador nuevo | Una guía propia |
| D5 | Modo libre (RF-10) | El recolector en modo automático por distancia de S017 con `AxisReferenceProvider`, de modo que el punto lleva su `AxisRef` | Un registrador propio |
| D6 | Cómo sabe la toma que se guardó un punto (RF-2) | `CollectorViewModel.setPointSavedListener(listener)` avisa con el id, el nombre y las coordenadas del punto guardado; la toma lo añade en el mismo hilo de guardado | Observar la tabla de puntos y deducirlo |
| D7 | Historial del perfil transversal (RF-20) | Cada «Guardar perfil» cierra una toma `CROSS` en `linear_surveys` (el historial) y mete el perfil de terreno vigente en S050 con `SectionRepository.addSections` en la misma transacción; S050 sustituye al vigente del mismo PK | Una columna «vigente» en S050 |
| D8 | Origen «Tomado en campo» (RF-8) | Valor nuevo `AlignmentSource.FIELD` (se guarda como texto, sin cambio de esquema), y la toma guarda `alignment_id` con `SET NULL` al borrar el eje | Una columna nueva en `alignments` |
| D9 | Longitud acumulada y dibujo de la toma (RF-3) | La longitud es la poligonal de los puntos incluidos en planta; se dibuja en el mapa como la línea de un eje con color propio | Calcularla en el eje ya creado |
| D10 | Tolerancias (RF-12, RF-18) | 0,50 m y 0,10 m por defecto, editables al empezar; se guardan en la toma | Ajustes globales |

## Estructura de módulos
- `core:model` (`survey/linear/`)
  - `LinearSurveyKind { AXIS, LONGITUDINAL, CROSS }`, `LinearSurvey(id, projectId, kind, alignmentId, name, chainageFrom, chainageTo, intervalM, chainage, guided, toleranceM, createdAtMs, finishedAtMs)` y `SurveyPoint(pointId, ordinal, included, chainageM, offsetM, dHeightM, flag, code)`.
  - `SurveyFlag { NONE, OUT_OF_AXIS, NO_PROJECTION, OUT_OF_SECTION }`.
  - `AlignmentSource.FIELD`.
- `core:survey` (sin Android ni Hilt)
  - `FieldAxisBuilder`: de los puntos incluidos y los radios al eje, con los rechazos de RF-6 (menos de 2 puntos, puntos a menos de 1 cm, radio que no cabe) y la rasante opcional de RF-7 (RF-5 a RF-7).
  - `PolylineLength`: la longitud acumulada en planta (RF-3).
  - `ProfileSampler`: de unas coordenadas, el PK, el desplazamiento, la diferencia de cota con la rasante y la marca según la tolerancia (RF-11, RF-12, RF-14, RF-17, RF-18).
  - `CrossProfileBuilder`: de los puntos incluidos al `CrossSection` de terreno ordenado por desplazamiento, con códigos (RF-17, RF-19, RF-20).
  - `ProfileSeries`: los PK del modo guiado desde, hasta e intervalo (RF-10).
- `core:formats`: `ProfileCsv` (RF-15, RF-22), con `Locale.ROOT`.
- `data`
  - Room v27: `linear_surveys` y `linear_survey_points`; `LinearSurveyRepository` (abrir, añadir, excluir, terminar y borrar en transacciones; log sin nombres ni coordenadas) y `DatabaseSnapshot.TABLES` (RF-2, RF-4, RF-8, RF-9, RF-20, RF-22, RF-23).
- `app`
  - `ui/linear/`: `AxisSurveyViewModel` y su panel (RF-1 a RF-9), `LongitudinalViewModel` y `ProfilePanel` (RF-10 a RF-15), `CrossViewModel` (RF-16 a RF-20), `ProfileView` (alzado con zoom y desplazamiento, RF-13, RF-19), `LinearSurveysScreen` (lista, RF-21, RF-22).
  - `CollectorViewModel.setPointSavedListener` (D6); `ProjectViewScreen`: «Tomar eje» y «Perfiles» y exclusión mutua con los otros paneles; `AlignmentsSheet` y la ficha del eje: «Perfil longitudinal» y «Perfil transversal».
  - Mapa: línea de la toma (D9).
- `tools/ejes`: `generar_toma_lineal.py`, `fixtures/ejes/toma/` y dos reproducciones (longitudinal y transversal).

## Modelo de datos
### Room v27
```
linear_surveys        id TEXT PK, project_id TEXT FK(projects, CASCADE), kind TEXT, alignment_id TEXT NULL FK(alignments, SET NULL),
                      name TEXT, chainage_from REAL NULL, chainage_to REAL NULL, interval_m REAL NULL, chainage REAL NULL,
                      guided INTEGER, tolerance_m REAL NULL, created_at_ms INTEGER, finished_at_ms INTEGER NULL
linear_survey_points  survey_id TEXT FK(linear_surveys, CASCADE), point_id TEXT FK(points, CASCADE), ordinal INTEGER,
                      included INTEGER, chainage REAL NULL, offset REAL NULL, d_height REAL NULL, flag TEXT, code TEXT NULL,
                      PRIMARY KEY (survey_id, point_id)
```
- `AutoMigration(26, 27)` sin especificación: dos tablas nuevas. Los números van sin redondear (constitución nº 1). Una toma sin `finished_at_ms` está abierta y se reanuda tras cerrar la app (RF-9).
- El eje tomado es una fila de `alignments` con `source = FIELD`; la toma de eje guarda en `alignment_id` el eje creado (null mientras está abierta).

## Algoritmos
### A1. Eje tomado (RF-5 a RF-7)
Con los puntos incluidos en orden: si hay menos de 2, se rechaza; si dos consecutivos distan menos de 1 cm, se rechaza con el número del vértice. Cada punto es un `PiVertex(E, N, R)` con el radio dado (0 si no hay) y se llama a `PiAlignmentBuilder.build(vertices, pkInicial)`; sus rechazos (`TangentsOverlap`, `InvalidParameter`) se traducen al vértice y al motivo. Con la casilla de rasante y cota en todos los puntos incluidos, los vértices de la rasante son (PK acumulado de cada punto sobre el eje construido, cota) sin acuerdos.

### A2. PK y desplazamiento de un punto (RF-11, RF-12, RF-17, RF-18)
`locate(E, N)` da PK, desplazamiento y distancia. Sin proyección, marca `NO_PROJECTION`. En el longitudinal, `|desplazamiento| > toleranceM` marca `OUT_OF_AXIS`. En el transversal, la distancia a la línea de la sección es `|pk - pkSección|` en planta y, si supera `toleranceM`, marca `OUT_OF_SECTION`. La diferencia de cota es la cota medida menos la de la rasante en el PK (longitudinal) o menos la de la sección de diseño de S050 en ese desplazamiento (transversal, `SectionEval.byOffset`); null si no hay.

### A3. Perfil transversal guardado (RF-17, RF-19, RF-20)
Los puntos incluidos y sin marca se ordenan por desplazamiento; el código es el de la toma o `P1`, `P2`… (`SectionCode.generated`); la cota es absoluta (`HeightKind.ABSOLUTE`). El resultado es un `CrossSection(GROUND, pkSección, ABSOLUTE, puntos)`. Dos puntos con el mismo desplazamiento se rechazan con el motivo (es el `SectionValidator` de S050).

### A4. Serie guiada (RF-10)
`ProfileSeries(from, to, interval)` da los PK `from, from+i, …` y siempre el final; el sentido lo marca `from <= to` o no.

## Contratos
- `FieldAxisBuilder.build(points, radii, startChainage, withGrade): FieldAxisResult` (`Built(elements, profile?)`, `Failed(issue, vertex)`).
- `ProfileSampler.of(geometry, profile?, section?, easting, northing, height, toleranceM, kind): ProfileSample(chainageM, offsetM, dHeightM, flag)`.
- `CrossProfileBuilder.build(chainage, points): CrossSection`.
- `LinearSurveyRepository.open/addPoint/exclude/finishAxis/finishCross/delete/surveys(projectId)/points(surveyId)`.
- Navegación: `SurveyPage.LinearSurveys(projectId)` (ADR-19); las tomas viven en `ProjectView` como los demás replanteos.

Textos nuevos (es y en): Tomar eje, Perfil longitudinal, Perfil transversal, Toma, Quitar último, Terminar, Guardar perfil, Guiado, Libre, Fuera del eje, Fuera de sección, Sin proyección, Tolerancia de eje, Tolerancia de sección, Tomado en campo, longitud acumulada, los motivos de rechazo del eje, la lista de perfiles tomados y la confirmación de borrado.

Accesibilidad (RNF-T8): campos y botones de 56 dp; izquierda y derecha con palabra; los gráficos llevan una frase para TalkBack (PK, cota y diferencia del punto seleccionado).

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| PK y desplazamiento guardados en la fila de la toma | Valen para cualquier punto y no dependen del origen del punto (D3) | Releerlos de cada punto |
| Reutilizar el replanteo por PK de S054 para el guiado | Diana, zonas y avisos sin copiarlos | Controlador nuevo |
| Reutilizar `PiAlignmentBuilder` | Misma construcción y misma referencia independiente que S049 (constitución nº 2) | Constructor propio |
| Historial en `linear_surveys` y vigente en S050 | S051 sigue leyendo solo los perfiles de S050 | Columna «vigente» |

## Estrategia de tests
- **`tools`:** `generar_toma_lineal.py` y su test: los ficheros guardados son los que genera el script (aritmética racional); el eje tomado se compara con `pi-vertices.csv` de S049.
- **JVM `core:model`:** `SurveyPoint` y valores por omisión.
- **JVM `core:survey`:** `FieldAxisBuilderTest` (rectas, con radios, menos de 2 puntos, duplicados a menos de 1 cm, radio que no cabe, rasante) contra `fixtures/ejes/toma/` a 1 mm; `PolylineLengthTest`; `ProfileSamplerTest` (dentro, fuera del eje, sin proyección, fuera de sección, sin rasante); `CrossProfileBuilderTest`; `ProfileSeriesTest`.
- **`core:formats`:** `ProfileCsvTest` con dorado.
- **`data`** (nombres de test de 60 caracteres o menos): migración 26 a 27, `LinearSurveyRepositoryTest` (alta atómica de puntos, excluir, terminar eje, historial del transversal, borrar el eje y la toma sin tocar puntos, reanudar), `LinearSurveyBackupTest`.
- **Robolectric `app`:** `CollectorSavedListenerTest`, `AxisSurveyViewModelTest`, `AxisSurveyPanelTest`, `LongitudinalViewModelTest`, `ProfilePanelTest`, `ProfileViewTest`, `CrossViewModelTest`, `LinearSurveysScreenTest`, ampliados `AlignmentsSheetTest` y `AlignmentTouchTest`.
- **Reproducción:** `LongitudinalReplayTest` y `CrossReplayTest` con la `ReceiverSession` real: PK, desplazamiento y cota de cada punto contra el cálculo aparte a 1 mm.
- **Rendimiento:** un perfil de 2.000 puntos se dibuja en menos de 100 ms y la guía de una época en menos de 16 ms.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | `AxisSurveyViewModel` | `AxisSurveyViewModelTest` |
| RF-2 | D6, `LinearSurveyRepository` | `CollectorSavedListenerTest`, `LinearSurveyRepositoryTest` |
| RF-3 | `PolylineLength`, mapa | `PolylineLengthTest`, `AxisSurveyPanelTest` |
| RF-4 | `exclude` | `LinearSurveyRepositoryTest`, `AxisSurveyViewModelTest` |
| RF-5 | A1 | `FieldAxisBuilderTest`, `AxisSurveyViewModelTest` |
| RF-6 | A1 | `FieldAxisBuilderTest`, `AxisSurveyViewModelTest` |
| RF-7 | A1 | `FieldAxisBuilderTest` |
| RF-8 | Room v27, D8 | `LinearSurveyRepositoryTest` |
| RF-9 | Room v27 | `LinearSurveyRepositoryTest`, `AxisSurveyViewModelTest` |
| RF-10 | A4, D4, D5 | `ProfileSeriesTest`, `LongitudinalViewModelTest` |
| RF-11, RF-12 | A2 | `ProfileSamplerTest`, `LongitudinalReplayTest` |
| RF-13 | `ProfileView` | `ProfileViewTest` |
| RF-14 | D3 | `LongitudinalViewModelTest` |
| RF-15 | `ProfileCsv` | `ProfileCsvTest` |
| RF-16 | D4 | `CrossViewModelTest` |
| RF-17, RF-18 | A2 | `ProfileSamplerTest`, `CrossReplayTest` |
| RF-19 | `ProfileView` | `ProfileViewTest` |
| RF-20 | A3, D7 | `CrossProfileBuilderTest`, `LinearSurveyRepositoryTest` |
| RF-21 | `exclude` | `LongitudinalViewModelTest`, `CrossViewModelTest` |
| RF-22 | `LinearSurveysScreen` | `LinearSurveysScreenTest`, `ProfileCsvTest` |
| RF-23 | copias, log | `LinearSurveyBackupTest`, `LinearSurveyRepositoryTest` |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S052 a «implementada» al terminar; la siguiente spec que toque Room pasa a la v28.
- `docs/glosario.md`: «Toma» (`LinearSurvey`), «Eje tomado», «Perfil longitudinal», «Perfil transversal», «Tolerancia de eje» y «Tolerancia de sección».
- S049: el origen `FIELD` («Tomado en campo»); S050: sin cambios (usa `addSections`); S054: sin cambios. Sus `spec.md` no se tocan sin que lo pidas.
- La cabecera de estado de `specs/052-*/spec.md` no se toca sin que lo pidas.

## Cambios al implementar (S052)
Lo que se hizo distinto de lo previsto arriba, y por qué:
- **`ProfileSeries` no existe**: la serie guiada de PK (desde, hasta, intervalo) es `AxisChainageSeries` de S054, que ya hace justo eso; el replanteo por PK se abre con `ProfileGuide` (`AxisStakeoutViewModel.open(..., guide)`), que le escribe los PK en la unidad del proyecto y lo arranca. En el perfil transversal la guía lleva un solo PK.
- **PK de los vértices de la rasante del eje tomado**: el del pie de la perpendicular desde cada punto sobre el eje construido, que es el punto medio del arco en un vértice con curva y el final de la recta anterior en el resto. El plan decía «PK acumulado»; esta es la definición exacta y la que calcula `tools`.
- **`FieldAxisResult.Built` lleva también el recuadro** (`extent`) del eje, que el repositorio guarda con él.
- **`LinearSurveyRepository`** gana `profileCounts(projectId)` (cuántos puntos forman el perfil de cada toma) para la lista de RF-22 y `finishLongitudinal`/`finishCross` no devuelven el historial: el historial son las propias tomas cerradas (D7). Un perfil de terreno de S050 que no vino de una toma (importado) y que un perfil nuevo sustituye en el mismo PK no queda en el historial.
- **`ProfileSampler`** reúne el muestreo de los dos perfiles (`longitudinal` y `cross`); el error en PK del transversal es la diferencia de PK del punto con el de la sección.
- **Entrada de los puntos elegidos (RF-14)**: el perfil usa los puntos que hay elegidos en la lista de objetos (`ExportSelection`); el panel ofrece «Usar los N puntos elegidos». No se tocó la lista de objetos.
- **Códigos del perfil transversal (RF-17)**: el que escribe el usuario para el siguiente punto, o el del punto de la sección de diseño de S050 a menos de 10 cm de desplazamiento, o `P1`, `P2`… al guardar (`CrossProfileBuilder`).
- **`PointSavedListener`** (`CollectorViewModel.setPointSavedListener`) avisa de cada punto guardado; lo usan los tres modelos. Un fallo del aviso no impide guardar el punto.
- **`ProfileView`** es un modelo puro (`ProfileChart`, `ProfileViewport`, `CutFill`) y un lienzo que solo lo dibuja; el texto del punto elegido es lo que lee TalkBack. `ProfileExportButtons` reúne «Guardar» y «Compartir» del CSV (`ProfileCsv`) para los tres usos.
- **Pantallas**: `AxisSurveyPanel` + `CollectorPanel` debajo, `LongitudinalPanel` + (`AxisStakeoutPanel` si es guiado o `CollectorPanel`), `CrossPanel` + `AxisStakeoutPanel`; la lista de tomas es `LinearSurveysScreen` (SurveyPage.LinearSurveys, «Perfiles tomados» en la hoja «Ejes»).
- **Reproducciones**: `ProfileReplayTest` agrupa las dos (longitudinal de 46 épocas y transversal de 20) y ninguna época cae justo en un extremo del eje ni de la sección, donde el redondeo de la latitud y la longitud decidiría el resultado.
- **Rendimiento**: el muestreo de un punto en un eje de 5.000 tramos tarda de media 66 µs (peor época 362 µs) frente a 16 ms; el alzado de 2.000 puntos se prepara en 24 ms y se redibuja en 13–17 ms frente a 100 ms.
- **Room v27**: `linear_surveys` y `linear_survey_points`; la siguiente spec que toque Room pasa a la v28.
