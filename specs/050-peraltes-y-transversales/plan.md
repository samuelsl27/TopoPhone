# Plan técnico — Spec 050

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/050-peraltes-y-transversales/spec.md`, `specs/049-ejes-y-rasantes/plan.md` (`AlignmentGeometry`, `VerticalProfileEval`, `AlignmentImporter`, el PK y el desplazamiento con signo), `specs/054-replanteo-por-eje/plan.md` (`AxisStakeTarget`, `AxisChainageSeries`, `KeyStations`, `AxisGuidance`, `AxisAnalysis`, `AxisStakeoutViewModel`) y `specs/028-importar-csv/` (`DelimitedReader`, asignación de columnas). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El LandXML se lee con el `XmlStreamReader` de S042 y la unidad con la misma regla que `LandXmlAlignmentReader`. Alternativa descartada: un lector nuevo.
- Los CSV usan `DelimitedReader`, `TextDecoding` y `DecimalDetector` de S028. Alternativa descartada: un lector propio.
- Las referencias independientes las calcula `tools/ejes/generar_secciones.py` con `fractions` (cota, peralte e interpolación en aritmética racional) y, para la planta, las fórmulas de S049 ya generadas por `generar_ejes.py`. Alternativa descartada: reutilizar `SectionEval` en los tests (sería una tautología).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Convivencia de una sección tipo y de secciones explícitas (CSV o LandXML) en un mismo eje, y rangos que se pisan (RF-2, RF-3) | En un PK manda la sección explícita si el PK cae entre la primera y la última de diseño (se interpola, RF-12); si no, la sección tipo cuyo rango lo contiene. Dos secciones tipo no pueden solaparse: se rechaza al guardar con el motivo y los dos rangos | Que no puedan coexistir en un eje |
| D2 | Signo de la pendiente transversal (RF-2, RF-6) | Positiva si el terreno **baja al alejarse del eje** en ese lado, así un bombeo es 2 % en los dos lados y un peralte a derechas es −4 % a la izquierda y +4 % a la derecha. Se muestra siempre con la palabra «baja» o «sube» (RNF-T8) | Positiva si la cota sube hacia la derecha (el bombeo sería +2 % a la izquierda y −2 % a la derecha) |
| D3 | Fuera de la tabla de peralte. Los Términos dicen «fuera del primero y del último, los tramos que siguen el peralte usan su pendiente propia (el bombeo)», pero la tabla de casos límite dice «con una sola fila se mantiene su pendiente fuera de ella». Se contradicen | Con **una fila**, su pendiente se mantiene en todo el eje (caso límite). Con **dos o más**, fuera de la primera y de la última vale la pendiente propia del tramo. Pide arreglar la spec con una frase (sin cambiar el sentido) | Siempre la pendiente propia, también con una fila |
| D4 | Sección con cotas absolutas frente a diferencias con la rasante (RF-4, RF-11) | Cada sección guarda `HeightKind { RELATIVE, ABSOLUTE }`. La cota de una sección absoluta es la declarada, aunque el eje tenga rasante; entre dos secciones no se interpola si el tipo difiere (se indica como los códigos que no coinciden, RF-12) | Convertir siempre a relativa con la rasante del eje |
| D5 | Qué hace el perfil de terreno hasta que lleguen S051 y S052 | Se importa del CSV o del LandXML marcado como terreno, se guarda como `SectionKind.GROUND` y solo se dibuja en «Ver sección» (RF-13). Nada más lo usa en S050 | No importarlo todavía |
| D6 | Dónde está «Secciones» y «Replantear por código» (RF-1, RF-16) | «Secciones» en cada fila de `AlignmentsSheet` y en `AlignmentFicha` (como «Replantear eje» de S054/D6). «Por código» es un modo del panel de «Replantear eje», no una pantalla nueva | Un panel aparte |
| D7 | Las líneas por código en el mapa (RF-14) | Se activan por eje desde la ficha, no se recuerdan entre sesiones (como la referencia de S054/D5) y se muestrean con la flecha de 1 cm de S049 más las estaciones donde cambia la sección | Persistirlo por proyecto |
| D8 | Formato de `CrossSect` de LandXML | Se lee `CrossSects/CrossSect@sta` con `CrossSectSurf` (`state`: `proposed` es diseño, `existing` es terreno) y `DesignCrossSectSurf`, y cada `CrossSectPnt` como «desplazamiento cota» (cota absoluta). **No hay ningún LandXML real con secciones**: se prueba solo con ficheros sintéticos escritos según LandXML 1.2, y esa limitación queda en el resumen de importación y en la validación de campo | Esperar a un fichero real |

## Estructura de módulos
- `core:model` (`alignment/section/`)
  - `SectionPoint(code, offsetM, dHeightM)`: desplazamiento con signo (S049) y cota (absoluta o diferencia, según `HeightKind`).
  - `DesignSection(chainage, heightKind, points)`: puntos ordenados de izquierda a derecha; `GroundProfile` es la misma estructura con `SectionKind.GROUND`.
  - `SectionTemplate(fromChainage, toChainage, gradeCode, left: List<TemplateSegment>, right: List<TemplateSegment>)` con `TemplateSegment(code, widthM, slope, followsSuperelevation)`.
  - `Superelevation(rows: List<SuperelevationRow(chainage, leftSlope, rightSlope)>)`.
  - `SectionCode` (≤12 caracteres, único en la sección) y `SectionSlope` (la pendiente en fracción, D2; `%` solo al mostrar).
  - `SectionCodec`: formato binario de cada bloque (`Double` sin redondear, número de formato en la cabecera).
  - `PointOrigin.ON_ALIGNMENT` ya existe; el punto lleva además `sectionCode`.
- `core:formats`
  - `section/SectionCsvReader`: PK, código, desplazamiento y cota, diseño o terreno, con las opciones de S028/RF-4 a RF-9 (RF-4).
  - `section/SuperelevationCsvReader`: PK, pendiente izquierda y derecha (RF-6).
  - `landxml/LandXmlCrossSectReader`: `scan` (secciones por PK, número de puntos, diseño o terreno) y `read` (las elegidas), por streaming (RF-5).
  - `csv/StakeoutReportCsv`: una columna más, `Section code` (RF-19).
- `core:survey` (sin Android ni Hilt)
  - `SectionValidator` y `SuperelevationValidator`: códigos repetidos, puntos desordenados, pendiente de módulo mayor de 100 %, PK fuera del eje, PK repetidos y rangos solapados (D1), con el punto y el motivo (RF-7).
  - `SectionBuilder`: de una `SectionTemplate`, el peralte y el punto de rasante a una `DesignSection` en un PK (RF-2, RF-9).
  - `SectionResolver`: la sección de un PK (explícita interpolada o tipo, D1), con los códigos que faltan si no se puede (RF-12).
  - `SectionEval`: desplazamiento, Este, Norte, cota y pendiente de un código en un PK; la cota a un desplazamiento y «Fuera de la sección» (RF-9 a RF-11).
  - `SectionStakeTarget`: de eje, PK, código y espesor al punto de diseño (planta de `AlignmentGeometry.pointAt` con el desplazamiento de la sección; cota de `SectionEval`), o el motivo (RF-16, RF-21).
  - `SectionStationSeries`: la lista de pares (PK, código) en los dos órdenes, con los puntos singulares de S054 y los de la sección y la tabla de peralte (RF-16, RF-17).
  - `AxisAnalysis` gana la referencia «Sección» (RF-20).
- `data`
  - Room v25 (ver abajo), `SectionRepository` y `SectionImporter`: altas y bajas en una sola transacción, con el log sin nombres ni coordenadas (RF-8, RNF-T6).
  - Copias de seguridad y paquetes: `DatabaseSnapshot.TABLES` con las tres tablas nuevas (RF-8).
- `app`
  - `ui/section/`: `SectionsViewModel` y `SectionsScreen` (lista, importar CSV o LandXML, tipo a mano, peralte), `SectionImportViewModel`, `SectionPointViewModel` y `SectionPointScreen` (calcular y guardar), `SectionViewScreen` (alzado con `Canvas`).
  - `AxisStakeoutViewModel` y `AxisStakeoutPanel`: el modo «Por código» (RF-16 a RF-19, RF-21).
  - `AxisAnalysisViewModel` y `AxisAnalysisPanel`: la referencia «Sección» (RF-20).
  - `MapController.showAlignments(scene)`: `AlignmentScene` gana las líneas por código (RF-14).
- `tools/ejes`: `generar_secciones.py`, `fixtures/ejes/secciones/` (CSV, LandXML sintético y lo esperado).

## Modelo de datos
### Room v25
```
section_templates  id TEXT PK, alignment_id TEXT FK(alignments, CASCADE), from_chainage REAL, to_chainage REAL,
                   grade_code TEXT NULL, geometry BLOB, created_at_ms INTEGER
section_profiles   id TEXT PK, alignment_id TEXT FK(alignments, CASCADE), kind TEXT, chainage REAL, height_kind TEXT,
                   points BLOB, created_at_ms INTEGER
superelevation_rows id TEXT PK, alignment_id TEXT FK(alignments, CASCADE), chainage REAL, left_slope REAL, right_slope REAL,
                   created_at_ms INTEGER
points  + section_code TEXT NULL
```
- `AutoMigration(24, 25)` sin especificación: tres tablas nuevas y una columna anulable. Los puntos existentes quedan como están.
- Las secciones tipo y las explícitas van en **un bloque por fila**, y la tabla de peralte en **una fila por PK** (para validar el PK repetido con un índice único `(alignment_id, chainage)` y editar una fila). Valores sin redondear.
- `CASCADE` desde `alignments`: borrar un eje borra sus secciones y peraltes sin tocar ningún punto (RF-8). El punto guarda `section_code` copiado, como `axis_name`, sin clave foránea.
- El punto replanteado por código usa las columnas de S054 (`stake_axis`, `stake_thickness`…) más `section_code` (RF-19); no hay más columnas.
- S017b ya está en la v24, así que esta spec es la v25 y la siguiente en tocar Room pasa a la v26.

## Algoritmos
### A1. Sección tipo y peralte a puntos (RF-2, RF-9; D2, D3)
1. En el PK, cada tramo que sigue el peralte toma la pendiente de la tabla: lineal entre las dos filas que lo rodean (`s = s0 + (s1 − s0)·(pk − pk0)/(pk1 − pk0)`); con una fila, su pendiente en todas partes; con dos o más, fuera de la primera y de la última, la propia del tramo (D3). Los tramos que no siguen el peralte usan la propia.
2. Se acumulan los desplazamientos desde el eje hacia cada lado (`offset = ±Σ ancho`) y la cota relativa al eje (`dz = −Σ ancho·pendiente`, D2). El eje (desplazamiento 0) tiene `dz = 0`.
3. Punto de rasante (Términos): se resta a todos los `dz` el del punto de rasante, de modo que ese punto queda a la cota de la rasante y el resto gira alrededor de él. Con el eje es la identidad.
4. Resultado: una `DesignSection` relativa con los puntos ordenados por desplazamiento.

### A2. La sección de un PK (RF-3, RF-12; D1, D4)
- Si el PK está entre la primera y la última sección explícita de diseño: se buscan las dos consecutivas que lo rodean y se interpola linealmente con `t = (pk − pk0)/(pk1 − pk0)` el desplazamiento y la cota de cada código. Si los códigos o el `HeightKind` difieren, no se interpola y se devuelve la lista de lo que falta. Un PK exactamente igual al de una sección da esa sección.
- Si no, y hay una sección tipo cuyo rango contiene el PK (extremos incluidos), se construye con A1.
- Si no, no hay sección en ese PK (RF-12, RF-21).

### A3. Punto de la sección (RF-9 a RF-11)
- Por código: se busca el código en la sección del PK. Planta: `AlignmentGeometry.pointAt(pk, offset)`. Cota: rasante en el PK (`VerticalProfileEval`) más `dz` si la sección es relativa; la propia `dz` si es absoluta; sin rasante y relativa, sin cota (RF-11).
- Pendiente transversal en el punto: la del tramo que acaba en él por el lado del eje (D2); en el eje, la del tramo derecho. Se dice con «baja» o «sube».
- Por desplazamiento: interpolación lineal entre los dos puntos de sección que lo rodean; si queda fuera de los exteriores, «Fuera de la sección» sin cota (RF-10).

### A4. Serie de pares (PK, código) (RF-16, RF-17)
- PK de la serie como `AxisChainageSeries` de S054 (inicio, intervalo, final, singulares), más los PK donde empieza o acaba una sección tipo o una sección explícita, y los de la tabla de peralte, fundiendo los que distan menos de 1 mm (RF-17).
- Orden «de PK en PK»: para cada PK, todos los códigos en el orden elegido. Orden «de código en código»: para cada código, todos los PK. «Siguiente» y «anterior» recorren la lista sin pasar de los extremos. Se calcula al pedirla, como en S054.
- Un par cuyo código no existe en la sección de ese PK se salta en la serie y se indica; si el PK no tiene sección, el replanteo no se abre (RF-21).

### A5. Replanteo por código (RF-18)
La planta y la cota de diseño salen de A3 más el espesor; entran en el `StakeoutController` como el punto de diseño fijo de S054. La guía es `AxisGuidance` con el azimut del eje en ese PK. Al cambiar de par se muestran su desplazamiento, su cota y su pendiente.

### A6. LandXML `CrossSects` (RF-5; D8)
Por streaming, `CrossSect@sta` es el PK; cada `CrossSectSurf` o `DesignCrossSectSurf` da una sección, de diseño si `state="proposed"` (o es `DesignCrossSectSurf`) y de terreno si `existing`. Cada `CrossSectPnt` es «desplazamiento cota» con la unidad del fichero; sin nombre se le asigna `P1`, `P2`… por desplazamiento creciente. Cotas absolutas (`HeightKind.ABSOLUTE`). Un fichero sin `CrossSects`, no XML o de más de 100 MB se rechaza sin abrir la lista, como en S049.

### A7. Mapa (RF-14; D7)
`AlignmentSampler` (S049/A8) da los PK de la polilínea del eje; para cada código y cada PK se evalúa A3 en segundo plano y se entrega la escena ya construida; en el hilo principal solo se pinta. Donde la sección cambia de códigos la línea se corta.

## Contratos
- `SectionResolver.at(alignmentId, chainage): SectionResolution` (`Found(section)`, `Missing(codes)`, `OutsideRange`).
- `SectionEval.byCode(resolution, geometry, profile?, code): SectionPointResult` y `.byOffset(…, offset)`.
- `SectionStakeTarget.of(geometry, profile?, resolution, code, thickness): SectionStakeResult` (`Target`, `NoCode`, `OutsideRange`).
- `SectionImporter.import(alignmentId, candidates, summary): SectionImportResult`; `SectionRepository.saveTemplate/saveProfiles/saveSuperelevation/delete`.
- Navegación: `SurveyPage.Sections(projectId, alignmentId)` y `SurveyPage.SectionView(projectId, alignmentId, chainage)` (ADR-19).

Textos nuevos (es y en): Secciones, Sección tipo, Punto de rasante, Peralte, Importar CSV de secciones, Importar LandXML, «Ver sección», «Fuera de la sección», «Por código», «De PK en PK», «De código en código», «baja»/«sube», los motivos de rechazo (código repetido, puntos desordenados, pendiente mayor de 100 %, PK fuera del eje, PK repetido, rangos que se pisan, códigos que faltan, sin sección en el PK, sin rasante).

Accesibilidad (RNF-T8): campos y botones de 56 dp; izquierda y derecha con la palabra además del signo; la pendiente con «baja» o «sube»; TalkBack lee «PK 1+240, borde derecho, 7,00 metros a la derecha, cota 612,345».

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Una fila por sección y por fila de peralte, con bloque binario solo para los puntos | Editar y borrar una fila, validar el PK repetido con un índice único | Un único bloque por eje |
| `CASCADE` desde `alignments` y `section_code` copiado en el punto | Borrar el eje no toca los puntos (RF-8) | Clave foránea del punto a la sección |
| Pendiente en fracción y `%` solo al mostrar | Unidades SI internas (AGENTS) | Guardar el porcentaje |
| Giro por resta del `dz` del punto de rasante | Una sola fórmula, igual para el eje | Rotar la sección con seno y coseno (cambia desplazamientos) |
| El replanteo por código reutiliza el punto de diseño fijo de S054 | Diana, zonas y nivel de S018 y S019 sin copiarlos | Un controlador nuevo |

## Estrategia de tests
- **`tools`:** `generar_secciones.py` y su test: los ficheros y lo esperado guardados son los que genera el script (aritmética racional).
- **JVM `core:model`:** `SectionCodecTest` (ida y vuelta sin redondear), `SectionPolicyTest` (código de hasta 12 caracteres, pendiente).
- **JVM `core:formats`:** `SectionCsvReaderTest`, `SuperelevationCsvReaderTest`, `LandXmlCrossSectReaderTest` (diseño y terreno, unidad, `P1`, `P2`…, sin `CrossSects`, 100 MB al vuelo), `StakeoutReportCsvTest` ampliado.
- **JVM `core:survey`:** `SectionValidatorTest`, `SuperelevationValidatorTest`; `SectionBuilderTest` (bombeo, peralte en curva, giro en un borde, fuera de la tabla, una fila) contra `fixtures/ejes/secciones/` a 1 mm y 1e-9; `SectionResolverTest` (interpolación, códigos que faltan, fuera de rango, D1); `SectionEvalTest` (por código, por desplazamiento, fuera de la sección, sin rasante); `SectionStakeTargetTest`; `SectionStationSeriesTest` (los dos órdenes, singulares); `AxisAnalysisTest` ampliado.
- **`data`** (nombres de test de 60 caracteres o menos): migración 24 → 25, `SectionRepositoryTest` (alta y baja atómicas, borrar el eje, PK repetido), `SectionBackupTest`, `SectionPointTest`.
- **Robolectric `app`:** `SectionsViewModelTest`, `SectionImportViewModelTest`, `SectionsScreenTest`, `SectionPointViewModelTest`, `SectionViewScreenTest`, `AlignmentSceneTest` ampliado, `AxisStakeoutViewModelTest` y `AxisStakeoutPanelTest` ampliados, `AxisAnalysisViewModelTest` ampliado, `SectionStakeMeasureTest`.
- **Reproducción:** `SectionStakeReplayTest`: una reproducción que recorre el borde derecho de un eje con peralte; guía y desmonte de cada época contra el cálculo aparte a 1 mm.
- **Rendimiento:** un LandXML con 2.000 secciones se importa en menos de 5 s; la guía de una época por código no pasa de 16 ms.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | pantalla, D6 | `SectionsScreenTest` |
| RF-2 | A1 | `SectionBuilderTest`, `SectionsViewModelTest` |
| RF-3 | A2 | `SectionResolverTest`, `SectionRepositoryTest` |
| RF-4 | CSV | `SectionCsvReaderTest`, `SectionImportViewModelTest` |
| RF-5 | A6 | `LandXmlCrossSectReaderTest`, `SectionImportViewModelTest` |
| RF-6 | CSV de peralte | `SuperelevationCsvReaderTest`, `SectionsViewModelTest` |
| RF-7 | validadores | `SectionValidatorTest`, `SuperelevationValidatorTest` |
| RF-8 | Room v25 | `SectionRepositoryTest`, `SectionBackupTest` |
| RF-9 | A3 | `SectionEvalTest`, `SectionBuilderTest`, `SectionPointViewModelTest` |
| RF-10 | A3 | `SectionEvalTest`, `SectionPointViewModelTest` |
| RF-11 | A3 | `SectionEvalTest` |
| RF-12 | A2 | `SectionResolverTest` |
| RF-13 | pantalla | `SectionViewScreenTest` |
| RF-14 | A7 | `AlignmentSceneTest` |
| RF-15 | Room v25 | `SectionPointTest`, `SectionPointViewModelTest` |
| RF-16 | A4 | `SectionStationSeriesTest`, `AxisStakeoutViewModelTest` |
| RF-17 | A4 | `SectionStationSeriesTest` |
| RF-18 | A5 | `SectionStakeReplayTest`, `AxisStakeoutPanelTest` |
| RF-19 | Room v25, CSV | `SectionStakeMeasureTest`, `StakeoutReportCsvTest` |
| RF-20 | `AxisAnalysis` | `AxisAnalysisTest`, `AxisAnalysisViewModelTest` |
| RF-21 | A4 | `SectionStakeTargetTest`, `AxisStakeoutViewModelTest` |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S050 a «implementada»; la siguiente spec que toque Room pasa a la v26.
- `docs/glosario.md`: «Sección de diseño», «Código de sección» (`SectionCode`), «Peralte» (`Superelevation`), «Punto de rasante» y «Perfil de terreno» (`GroundProfile`).
- S054: `AxisStakeoutViewModel`, `AxisStakeoutPanel`, `AxisAnalysis` y `KeyStations` se amplían sin cambiar lo que ya hacen; su `spec.md` no se toca sin que lo pidas.
- S049: sin cambios (su «Fuera de alcance» ya apunta aquí).
- La cabecera de estado de `specs/050-*/spec.md` no se toca sin que lo pidas.

## Cambios al implementar (S050)
Lo que se hizo distinto de lo previsto arriba, y por qué:
- **`CrossSection`** en lugar de `DesignSection` y `GroundProfile` por separado: una sola clase con `SectionKind { DESIGN, GROUND }`; el glosario nombra las dos cosas con ese tipo.
- **`SectionCode` y `SectionSlope` son `object`** con las reglas (12 caracteres, pendiente de módulo menor o igual que 1), y los códigos viajan como `String`: así el validador puede decir qué código no vale en lugar de que el modelo lance la excepción.
- **`SectionSaveResult`** y la validación en `SectionRepository`: el repositorio valida (con `SectionValidator`) y guarda en una sola transacción; el `SectionImporter` del plan no hizo falta. Una sección explícita nueva en el mismo PK y tipo sustituye a la anterior; el peralte se guarda entero (`setSuperelevation`) y sustituye al anterior.
- **Modo «Por código»**: lo lleva `AxisStakeoutViewModel` (con `SectionRepository`) y `SectionStationSeries` no salta pares sin código: lo hace quien recorre la serie (`moveCode`), que ya sabe si el código existe en la sección de ese PK (RF-21).
- **Columna `Section code`** del informe de replanteo: va tras `Delta chainage` (en el grupo del eje) y no al final, para no mover las columnas del dibujo y la superficie; los dorados se regeneraron.
- **`AxisHeightRef.SECTION`** se guarda en `axis_height_ref` (texto): la referencia «Sección» del análisis llega a la ficha del punto.
- **El código de sección del punto** vive en `points.section_code`, que lee `AxisRef` (calculado sobre eje) y `StakeoutRef` (replanteado); un punto nunca tiene los dos.
- **Pantallas**: `SectionsScreen` (listas y editores de sección tipo y de peralte), `SectionImportScreen`, `SectionPointScreen` con «Ver sección» (`SectionDrawing`); las líneas por código del mapa se activan con un interruptor en la ficha del eje y no se recuerdan (D7).
- **`AlignmentMapViewModel`** y **`AxisStakeoutViewModel`** ganan el parámetro `SectionRepository`; `AxisAnalysisViewModel` también.
- **D8** se mantiene: el lector de `CrossSects` solo se ha probado con ficheros sintéticos.
