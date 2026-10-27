# Plan técnico — Spec 049

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/049-ejes-y-rasantes/spec.md`, `specs/040-capa-cad-dxf/plan.md` (los dibujos, el `CadProjector` y las marcas de vértice original), `specs/041-replanteo-cad/` (el PK de una línea, que aquí se generaliza), `specs/042-superficies-landxml/plan.md` (el tokenizador XML y el LandXML que se reutilizan), `specs/048-geopackage/plan.md` (el menú de importación) y `specs/006-proyectos/plan.md` (el centro único del proyecto). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El LandXML se lee con el `XmlStreamReader` de S042. Alternativa descartada: un lector nuevo.
- La clotoide se integra con cuadratura de Gauss-Legendre por paneles en `core:survey`. Alternativa descartada: una librería numérica (dependencia nueva para una integral de dos líneas) o el desarrollo en serie de Taylor de Fresnel (solo converge bien para ángulos pequeños).
- Las referencias independientes las calcula `tools/ejes/generar_ejes.py` con Python puro: los desarrollos en serie de las integrales de Fresnel (otro algoritmo que el del móvil, así la coincidencia no es una tautología), las fórmulas analíticas de arco y de acuerdo vertical y `pyproj` para el cambio de SC. Alternativa descartada: `scipy` (dependencia nueva solo para `fresnel`).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Los arcos de una polilínea de DXF (RF-6). S040 los guarda ya como segmentos con los vértices originales marcados (`ORIGINAL_MARKS`) | Reconstruirlos: entre dos vértices originales con vértices intermedios no originales, el círculo por el inicio, un vértice central y el final (los vértices de S040 están exactamente sobre el arco). Si los intermedios se apartan más de 1 cm de ese círculo (una spline) el tramo no es un arco y la polilínea no se convierte, con el motivo y el tramo | Convertir las curvas a rectas con un aviso (el eje pierde el radio y el PK sale algo corto) |
| D2 | Un eje en un SC distinto del del proyecto (RF-11). Una curva proyectada con otra proyección deja de ser un círculo | Muestrear el eje en su SC con una flecha de 0,5 mm, convertir cada punto y guardar el resultado como rectas cortas, con el PK inicial del fichero. El PK pasa a ser la longitud medida en el SC del proyecto (así lo define la spec: «distancia medida sobre el eje») y el radio se muestra «—». El resumen lo avisa | Aceptar solo el SC del proyecto con cambio de unidad (hay que reescribir RF-11 en la spec) |
| D3 | Lo que no se admite en un LandXML fuera de RF-5: acuerdos verticales asimétricos (`UnsymParaCurve`), varios perfiles marcados como rasante, ecuaciones de PK (`StaEquation`) | Una rasante con un acuerdo no admitido se omite y el eje se importa sin ella, con el motivo en el resumen. Un eje con `StaEquation` no se importa (los PK saldrían mal; «Ecuaciones de PK» está fuera de alcance) | Rechazar el eje entero también cuando solo falla la rasante |
| D4 | El «parámetro del acuerdo vertical» de la definición a mano y del CSV (RF-8, RF-9) | Es el **Kv** de la norma española (m): la longitud de una parábola simétrica es `Kv · |Δi|`. Vacío o 0 es un vértice sin acuerdo. Los acuerdos circulares solo llegan por LandXML | La longitud del acuerdo, o el radio |

## Estructura de módulos
- `core:model` (`alignment/`)
  - `Alignment` (id, nombre, fichero, fecha, origen, PK inicial, longitud, visible) con `AlignmentElement`: `Line`, `Arc` y `Clothoid` (PK de inicio, punto de inicio, azimut de inicio, longitud, curvatura de inicio y de fin, sentido) y `VerticalProfile` con sus `VerticalVertex` (PK, cota, `VerticalCurve`: `None`, `Parabola(length)`, `Circular(radius, length)`).
  - `AlignmentCodec`: el formato binario de la planta y de la rasante (un bloque cada una, `Double` sin redondear, número de formato en la cabecera).
  - `AlignmentSource { LANDXML, DRAWING, CSV, MANUAL }`; `PointOrigin.ON_ALIGNMENT` («Calculado sobre eje», RF-22); `ChainageFormat` («1+240,50» con el formato del proyecto, RNF-T9) y `OffsetText` («3,50 m a la derecha», RNF-T8).
- `core:formats`
  - `landxml/LandXmlAlignmentReader`: `scan` (alineaciones con nombre, longitud, PK inicial, si traen rasante y la unidad) y `read` (tramos y perfil de las elegidas), por streaming sobre `XmlStreamReader` (RF-2 a RF-5).
  - `alignment/ProfileCsvReader`: PK, cota y Kv de un CSV con `DelimitedReader`, `TextDecoding` y `DecimalDetector` de S028 (RF-9).
- `core:survey` (sin Android ni Hilt)
  - `Clothoid` y `AlignmentGeometry`: punto, azimut, curvatura y PK de cada tramo; `locate(e, n)` da el PK y el desplazamiento con signo de una posición (RF-18, RF-20; S054 lo reutiliza).
  - `VerticalProfileEval`: cota y pendiente en un PK, con parábola y círculo (RF-18, RF-20).
  - `AlignmentValidator` (huecos de más de 1 cm y tipos no admitidos, RF-5), `ProfileValidator` (RF-10).
  - `PiAlignmentBuilder`: de los vértices con radio y clotoides a tramos (RF-8). `PolylineAlignmentBuilder`: de una polilínea de S040 a tramos, con la reconstrucción de arcos de D1 (RF-6, RF-7).
  - `AlignmentProjector`: unidad y SC como el `CadProjector` de S040, y el muestreo de D2 (RF-11, RF-12).
  - `AlignmentSampler` y `ChainageMarks`: la polilínea del mapa a 1 cm de flecha y las marcas de PK a un intervalo redondo (1, 2, 5 × 10ⁿ) según la escala (RF-17).
- `data`
  - Room v20 (S017b pasa a la v21): `alignments`, `projects.active_alignment_id` y tres columnas de `points` (A7).
  - `AlignmentRepository` y `AlignmentImporter`: importar, borrar, activar, renombrar y mostrar u ocultar en una sola transacción (RF-13 a RF-16, RF-19, RNF-T6).
  - Copias de seguridad y paquetes: `DatabaseSnapshot.TABLES` con la tabla nueva (RF-16).
- `app`
  - `ui/alignment/`: `AlignmentImportViewModel` y `AlignmentImportScreen` (cuatro orígenes), `ManualAlignmentScreen`, `AlignmentsSheet`, `AlignmentFicha` y `AlignmentPointScreen` con sus `ViewModel`.
  - «Importar eje» en el menú del proyecto y en el de su lista de objetos (RF-1).
  - `MapController.showAlignments(scene)` y `AlignmentScene` (las polilíneas, el eje activo destacado y las marcas rotuladas).
- `tools/ejes`: `generar_ejes.py`, los ficheros de `fixtures/ejes/` y lo esperado de cada uno.

## Modelo de datos
### Room v20
```
alignments  id TEXT PK, project_id TEXT FK(projects, CASCADE), name, file_name TEXT NULL, imported_at_ms INTEGER,
            source TEXT, start_chainage REAL, length_m REAL, visible INTEGER NOT NULL DEFAULT 1,
            min_e, max_e, min_n, max_n REAL, geometry BLOB, profile BLOB NULL
projects  + active_alignment_id TEXT NULL
points    + axis_name TEXT NULL, axis_chainage REAL NULL, axis_offset REAL NULL
```
- `AutoMigration(19, 20)` sin especificación: tabla nueva y columnas anulables.
- La planta y la rasante van en **un bloque cada una dentro de la fila**, no en filas por tramo: un eje de 50 km tiene como mucho decenas de miles de tramos (una polilínea densa de DXF) y los usos son siempre «todo el eje». La importación es una sola inserción, así que ya es atómica y no necesita el estado `IMPORTING` de S040/S042 (RNF-T6).
- `active_alignment_id` vive en `projects`, como la superficie activa de S042: una columna garantiza uno o ninguno. El primero que se importa lo fija en la misma transacción (RF-14); borrar el activo la pone a NULL (RF-15).
- Un punto guardado sobre un eje copia el nombre del eje en lugar de una clave foránea, para que borrar el eje no deje el punto sin origen ni lo modifique (RF-15, RF-22).
- El PK inicial, la longitud y la extensión se guardan fuera del bloque para listar y dibujar sin decodificarlo.

## Algoritmos
### A1. Geometría en planta (RF-4, RF-20)
Cada tramo guarda su punto, azimut, PK y curvatura de inicio y de fin. La curvatura varía linealmente (cero en la recta, constante en el arco, lineal en la clotoide), así que el azimut es `θ(s) = θ₀ + k₀·s + (k₁ − k₀)·s²/(2L)` para las tres clases y la posición es la integral de `(sen θ, cos θ)` (el azimut es horario desde el Norte, RNF-T9). Recta y arco tienen forma cerrada; la clotoide se integra con Gauss-Legendre de 16 puntos por paneles de 5 m como máximo, con un error muy inferior a 1e-9 m en las longitudes reales. El desplazamiento positivo es a la derecha: `E = E_eje + d·cos θ`, `N = N_eje − d·sen θ`.

### A2. LandXML (RF-2 a RF-5)
- `scan` recorre `Alignments/Alignment` (nombre, `length`, `staStart`) y detecta si hay `Profile/ProfAlign` en el mismo `Alignment`. La unidad sale de `Units` como en S042. Sin alineaciones, no XML o más de 100 MB: se rechaza sin abrir la lista (RF-3).
- `read` toma `CoordGeom`: `Line`, `Curve` (inicio, fin, centro, radio y `rot`) y `Spiral` (largo, radios de inicio y de fin, `rot`, `spiType="clothoid"`). Cualquier otro (`IrregularLine`, `spiType` distinto, `Chain`) es un tipo no admitido (RF-5). Se reconstruye cada tramo con sus parámetros y se comprueba que su final calculado queda a menos de 1 cm del declarado y del inicio del siguiente.
- El orden de las coordenadas de LandXML es **Norte, Este** (como en S042). Un test con un eje asimétrico lo fija.
- La rasante sale del `ProfAlign` marcado como rasante (`PVI`, `ParaCurve`, `CircCurve`); `UnsymParaCurve` o `StaEquation` siguen D3.

### A3. Rasante (RF-10, RF-20)
Vértices ordenados con PK y cota; entre dos vértices la pendiente es la de la recta, y en un vértice con acuerdo se sustituye un tramo de longitud `L` centrado en él (parábola: `z = z_ini + g₁·x + (g₂ − g₁)·x²/(2L)`; círculo: arco de radio `R`, con signo por el cambio de pendiente). Un acuerdo cabe si sus extremos están dentro de los vértices vecinos y los acuerdos de dos vértices contiguos no se solapan (RF-10). Vértices fuera del PK del eje o con PK repetido se rechazan con el vértice y el motivo.

### A4. PK y desplazamiento de una posición (RF-18, constitución nº 2)
Para cada tramo candidato (por la caja con holgura, sin recorrerlos todos) se busca el pie de la perpendicular: recta y arco en forma cerrada; clotoide con Newton sobre `f(s) = (P − C(s))·T(s)` partiendo de la cuerda y comprobando el extremo. Se queda el de menor distancia y se comprueba que la proyección cae dentro del tramo (si no, el extremo más cercano). Fuera del eje por delante o por detrás no hay PK (RF-21).

### A5. Polilínea de un dibujo (RF-6, RF-7; D1)
Se toma una polilínea de un dibujo (DXF o GeoPackage), se orienta desde el extremo más cercano al punto tocado y se recorren sus vértices originales (`isOriginal`); entre dos consecutivos con vértices no originales se ajusta el círculo y se comprueba el residuo (1 cm). Una polilínea con cotas en todos sus vértices da la rasante con los mismos vértices (PK acumulado y Z), sin acuerdos (RF-7).

### A6. Eje en otro SC (RF-11, RF-12; D2)
Con el SC del proyecto, solo se convierte la unidad a metros y se comprueba el ámbito (`crs.area`) en cada vértice y en el muestreo de las curvas. Con otro SC se muestrea con una flecha de 0,5 mm, cada punto pasa por la misma ruta que el `CadProjector` de S040 y se guardan los tramos rectos. Cualquier punto fuera del ámbito rechaza el eje entero (RF-12).

### A7. Punto sobre el eje (RF-20 a RF-22)
Con el PK y el desplazamiento se calculan Este, Norte, azimut y radio del eje en ese PK (A1) y la cota y la pendiente de la rasante (A3). «Guardar punto» crea un punto de origen `ON_ALIGNMENT` con esas coordenadas, el nombre del eje, el PK y el desplazamiento, con el nombre libre siguiente (S011/RF-16) en una sola transacción (RNF-T6). Si el eje no tiene rasante, el punto se guarda sin altura, como un punto manual sin ella. Después se replantea como cualquier otro (S018).

### A8. Mapa (RF-17, RNF-T13)
`AlignmentSampler` genera en segundo plano la polilínea de cada eje visible (flecha de 1 cm, una sola vez por eje y se guarda en caché hasta que se borra) y las marcas de PK para el intervalo que pide la escala. En el hilo principal solo se entrega la escena ya construida. El toque usa `locate` con una tolerancia en píxeles (RF-18).

## Contratos
- `AlignmentGeometry(alignment).pointAt(chainage, offset): AxisPoint` y `.locate(e, n): AxisLocation?`; `VerticalProfileEval.at(chainage): ProfileValue?` (cota y pendiente).
- `AlignmentImporter.import(projectId, candidates, summary): AlignmentImportResult` y `AlignmentRepository.delete/rename/setVisible/setActive`.
- `MapController.showAlignments(scene: AlignmentScene?)`.
- Navegación: `SurveyPage.ImportAlignment(projectId)`, `SurveyPage.AlignmentPoint(projectId, alignmentId)` (ADR-19).

Textos nuevos (es y en): Importar eje, los cuatro orígenes, la lista de alineaciones con longitud, PK inicial y «con rasante», los motivos de rechazo (sin alineaciones, no válido, más de 100 MB, hueco entre tramos, tipo no admitido, ecuación de PK, rasante con vértice repetido, fuera del eje o acuerdo que no cabe, eje fuera del ámbito, curva no circular), «Eje activo», «Mostrar»/«Ocultar», «PK», «Desplazamiento», «a la izquierda»/«a la derecha», «Fuera del eje: PK inicial %1$s, PK final %2$s», «Guardar punto», «Calculado sobre eje», el aviso de D2 y el resumen de lo importado e ignorado.

Accesibilidad (RNF-T8): botones y campos de 56 dp; el desplazamiento con la palabra «izquierda» o «derecha» además del signo; la ficha y el punto calculado se leen con TalkBack como frase («PK 1+240, 3,50 metros a la derecha, desmonte de 12 centímetros»).

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Planta y rasante como un bloque por eje | Se usan siempre enteros y el eje cabe en memoria; una sola inserción es atómica | Una fila por tramo y un estado `IMPORTING` |
| Tres columnas en `points` con el nombre del eje copiado | Borrar el eje no toca los puntos (RF-15) | Clave foránea al eje |
| Integración numérica de la clotoide y referencia en serie | Algoritmos distintos: la prueba es independiente (constitución nº 2) | La misma fórmula en el móvil y en `tools` |
| Activo en `projects` | Uno o ninguno por construcción (S042) | Un indicador por eje |
| Generalizar el PK de S041 con tramos curvos | El PK de una línea de S041 es el caso de solo rectas | Dos conceptos de PK |

## Estrategia de tests
- **JVM `core:model`:** `AlignmentCodecTest` (ida y vuelta sin redondear), `ChainageFormatTest`, `OffsetTextTest`.
- **JVM `core:formats`:** `LandXmlAlignmentReaderTest` (versiones, unidad, orden Norte-Este, tipos no admitidos, `StaEquation`, 100 MB al vuelo), `ProfileCsvReaderTest`.
- **JVM `core:survey`:** `AlignmentGeometryTest` (recta, arco, clotoide de entrada y salida, curva en S, PK inicial distinto de 0, desplazamiento 0 y a ambos lados) contra `fixtures/ejes/` a 1 mm; `AlignmentLocateTest` (PK y desplazamiento a 1 mm y ida y vuelta); `VerticalProfileEvalTest` (parábola, círculo y vértices); `AlignmentValidatorTest`, `ProfileValidatorTest`; `PiAlignmentBuilderTest`, `PolylineAlignmentBuilderTest` (con arcos, spline y sin cotas); `AlignmentProjectorTest` (unidad, ámbito y SC distinto con pyproj); `ChainageMarksTest`.
- **`tools`:** `generar_ejes.py` y su test.
- **`data`:** migración 19 → 20, `AlignmentRepositoryTest`, `AlignmentImporterTest` (varias alineaciones, repetido, fallo de escritura sin dejar nada), `AlignmentBackupTest`, `PointOnAxisTest` (nombres de test de 60 caracteres o menos).
- **Robolectric `app`:** `AlignmentImportViewModelTest` y `AlignmentImportScreenTest` (los cuatro orígenes y los rechazos), `ManualAlignmentTest`, `AlignmentsSheetTest`, `AlignmentSceneTest`, `AlignmentFichaTest`, `AlignmentPointViewModelTest`.
- **Rendimiento:** un LandXML de 100 MB con un eje de 50 km se importa en menos de 10 s y dibujar el eje no pasa de 16 ms en el hilo principal.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | pantalla | `AlignmentImportScreenTest` |
| RF-2, RF-3 | A2 | `LandXmlAlignmentReaderTest`, `AlignmentImportViewModelTest` |
| RF-4, RF-5 | A1, A2 | `LandXmlAlignmentReaderTest`, `AlignmentValidatorTest` |
| RF-6, RF-7 | A5 | `PolylineAlignmentBuilderTest`, `AlignmentImportViewModelTest` |
| RF-8 | `PiAlignmentBuilder` | `PiAlignmentBuilderTest`, `ManualAlignmentTest` |
| RF-9 | `ProfileCsvReader` | `ProfileCsvReaderTest`, `AlignmentImportViewModelTest` |
| RF-10 | A3 | `ProfileValidatorTest` |
| RF-11, RF-12 | A6 | `AlignmentProjectorTest`, `AlignmentImporterTest` |
| RF-13 | `AlignmentImporter` | `AlignmentImporterTest`, `AlignmentImportScreenTest` |
| RF-14, RF-15 | Room v20 | `AlignmentRepositoryTest`, `AlignmentsSheetTest` |
| RF-16 | copias | `AlignmentBackupTest` |
| RF-17 | A8 | `ChainageMarksTest`, `AlignmentSceneTest` |
| RF-18 | A4, A8 | `AlignmentLocateTest`, `AlignmentFichaTest` |
| RF-19 | Room v20 | `AlignmentRepositoryTest`, `AlignmentsSheetTest` |
| RF-20, RF-21 | A1, A3, A7 | `AlignmentGeometryTest`, `AlignmentPointViewModelTest` |
| RF-22 | A7 | `PointOnAxisTest`, `AlignmentPointViewModelTest` |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S049 a «implementada»; S017b pasa a la v21.
- `docs/glosario.md`: los términos de la spec.
- La cabecera de estado de `specs/049-*/spec.md` no se toca sin que lo pidas.
