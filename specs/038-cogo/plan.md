# Plan técnico — Spec 038

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/038-cogo/spec.md`, `specs/026-informacion-geometrica/plan.md` (`GroundScale`, `GeometryPreferences`, `DistanceMode`, selector de puntos), `specs/049-ejes-y-rasantes/plan.md` (A7: el punto calculado que se guarda, `AxisPointBuilder`), `specs/011-*/plan.md` (`ManualCoordinates`, nombres, papelera), `specs/013-*/plan.md` (`DatabaseSnapshot.TABLES`) y `specs/028-*/plan.md` (guardado de varios puntos en una transacción).

Sin dependencias nuevas, ni Gradle ni Python: la codificación JSON va en `core:formats`, que ya usa `kotlinx.serialization.json` (árbol JSON, sin el plugin del compilador), y `pyproj` ya está en `tools/`. **Room pasa de la v32 a la v33.**

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde vive el cálculo (RF-5 a RF-18, RF-31) | Kotlin puro en `core:survey`, paquete `cogo/`, que ya puede usar `core:geodesy`. Recibe puntos con Este, Norte, altura opcional y posición geodésica opcional; no sabe de proyectos, Room ni pantallas | Calcular en los ViewModel |
| D2 | Factor de terreno (RF-5) | Se reutiliza `GroundScale` de S026 evaluado **en el primer punto de entrada** (el de inicio en la poligonal), como dice la spec: `GroundScale(crs, null).polygon(listOf(primero))`. Distancia de cuadrícula = distancia de terreno × factor. Sin altura elipsoidal, con los avisos de S026/D7. Un punto escrito a mano toma su posición de `GroundPosition.of(crs, E, N)` | Factor del centro de cada segmento, como S026/RF-10: coincidiría al milímetro con la información geométrica, pero en una poligonal el final de cada tramo aún no se conoce (se necesitaría iterar). Con un punto a 250 km del meridiano central, usar el primer punto en vez del centro cambia una distancia de 1 km en unos 3 mm |
| D3 | Inversa (RF-6, RF-7) | Muestra siempre la distancia de cuadrícula y la de terreno (la activa, resaltada); la distancia inclinada y la pendiente usan la activa, como S026/D3: `inclinada = hypot(horizontal activa, Δh)`, `pendiente = Δh / horizontal activa × 100`; con distancia 0, azimut y pendiente indefinidos («—») | Calcularlas siempre en cuadrícula |
| D4 | Dirección de cada tramo de la poligonal (RF-10) | Con `αatrás` = azimut del punto de partida al punto anterior (en el primer tramo, al de referencia): **ángulo a la derecha** `α = αatrás + ángulo`; **a la izquierda** `α = αatrás − ángulo`; **deflexión a la derecha** `α = αprolongación + deflexión`; **a la izquierda** `α = αprolongación − deflexión`, con `αprolongación = αatrás + π`; todo módulo 2π. Los tramos por azimut no usan visual atrás. RF-31 solo cuenta para tramos por ángulo o por deflexión | — |
| D5 | Altura en la poligonal (RF-11) | Un punto tiene altura solo si el de inicio la tiene **y** todos los tramos hasta él llevan desnivel escrito; el desnivel vacío no se toma por 0, porque sería inventar un dato (constitución nº 1). A partir del primer tramo sin desnivel, los puntos salen sin altura | Tomar el desnivel vacío como 0 |
| D6 | Error de cierre (RF-12) | `ClosureError(dEasting, dNorthing, linear, totalLength, ratio)` con `d = último punto − punto de cierre` en el plano del SC; `totalLength` = suma de las distancias de cuadrícula de los tramos; `ratio` = `totalLength / linear` y «exacto» si `linear = 0`. Sin corregir ningún punto | Longitud de terreno en la precisión relativa |
| D7 | Intersecciones: tolerancias y orden (RF-13 a RF-17) | **Distancia–distancia**: tangentes si `h² ≤ (1e-6 m)²`, una sola solución; izquierda y derecha respecto al sentido del primero al segundo punto. **Dirección–dirección**: paralelas si `|sen(Δα)| < 1e-12`. **Distancia–dirección**: `disc ≤ (1e-6 m)²` es una solución; las dos se ordenan por su distancia `t` al segundo punto y se llaman «Primera» y «Segunda» (la izquierda y la derecha no tienen sentido con los dos objetos sobre una recta). Aviso «detrás del segundo punto» también cuando `t < 0` en Distancia–dirección, igual que RF-15 (**no está en la spec**) | No avisar en Distancia–dirección; etiquetar por cercanía |
| D8 | Punto escrito a mano (RF-3) | Una entrada `CogoPoint(easting, northing, height?, nameOrNull)`; si viene del proyecto, el ViewModel la lee con su estado actual (se recalcula al editar el punto). Se pasa a `GeometryVertex` solo cuando hace falta el factor | Obligar a guardarlo antes |
| D9 | Qué es un punto COGO (RF-19 a RF-24) | `PointOrigin.COGO`, como `IMPORTED` y `ON_ALIGNMENT`: el nombre del enum se guarda por texto, sin migración de datos. `hasMeasurement` es falso. Lo construye `CogoPointBuilder` (como `AxisPointBuilder`): Este y Norte escritos en el SC del proyecto, altura en el sistema de alturas del proyecto, vía `ManualCoordinates` (rechaza lo que cae fuera del ámbito, RF-21). Duplicar un COGO da un punto manual, como duplicar cualquier otro | Reutilizar `MANUAL` y guardar el origen en otro sitio |
| D10 | Rastro del cálculo (RF-22) | Columna `points.cogo` (TEXT, admite NULL) con un JSON: operación, puntos de entrada **copiados por valor** (nombre, Este, Norte, altura), los tramos hasta ese punto o los valores escritos de la intersección, y la solución elegida. Se codifica en `core:formats/cogo/CogoRefJson` (versión `v` dentro del JSON). Así borrar un punto de entrada no cambia el punto COGO. La ficha del punto no lo muestra (no está en la spec); queda guardado para cuando una spec lo pida | Columnas sueltas por cada valor (varían con la operación) |
| D11 | Esquema (RNF-T6) | **v33**: `points.cogo` y la tabla `traverse_drafts(project_id PK → projects ON DELETE CASCADE, data TEXT NOT NULL, updated_at INTEGER NOT NULL)`. Solo columnas que admiten NULL y una tabla nueva: `AutoMigration(32, 33)` y su esquema `33.json` | Borrador en DataStore (fuera de la copia y sin transacción con los puntos) o columna en `projects` (copiada en el duplicado) |
| D12 | Borrador de la poligonal (RF-26, RF-27) | `TraverseDraft` guarda **valores ya interpretados en unidades SI** (radianes y metros; `null` si el campo no es un número) más la clase de cada tramo y las referencias a los puntos (`pointId` y nombre, o Este y Norte escritos). Lo que el usuario está tecleando vive solo en el estado de la pantalla (si no, un «12,» a medias se borraría al reescribir el campo). El modo Cuadrícula/Terreno no se guarda: las distancias se interpretan según la elección actual. Escritura al instante con una cola conflada en `Dispatchers.IO` y una transacción por escritura | Guardar los textos tal cual (cambiar la unidad del proyecto los reinterpretaría) |
| D13 | Recuperar el borrador (RF-27, RF-28) | Los puntos del proyecto se leen con su estado actual y el cálculo se rehace. Si un punto ya no está activo, se indica cuál y no se calcula hasta elegir otro (RF-28) | Guardar sus coordenadas de entonces |
| D14 | Copias y duplicado (RF-29) | El borrador entra en `DatabaseSnapshot.TABLES` (copia completa y restauración). El duplicado de proyecto no copia puntos ni borrador, y los paquetes `.topoproj` se escriben tabla a tabla en `BackupRepository`, donde no se añade (comprobado en los tests). El proyecto purgado lo elimina por la clave foránea | Incluirlo en los paquetes |
| D15 | Guardar los puntos (RF-19, RF-20, RF-23, RF-30) | `CogoRepository.savePoints(projectId, points, clearDraft)` en una sola transacción de Room (`PointDao.insertAll` más, si pide `clearDraft`, borrar el borrador). El índice único `(proyecto, nombre)` de S011 hace fallar el lote entero (`DuplicateName`). Antes de guardar, el ViewModel comprueba **todos** los resultados con `CogoPointBuilder` y, si alguno cae fuera del ámbito, no guarda ninguno (RF-21). Los nombres propuestos salen de `PointNames.nextFree` encadenado, sin repetir | Insertar uno a uno |
| D16 | Log (RF-25) | `LogCategory.POINTS`: «COGO: n puntos (poligonal) guardados en «proyecto»», sin nombres ni coordenadas | — |
| D17 | Entrada desde la interfaz (RF-1, RF-2) | Entrada «COGO» en el menú de la lista de objetos (junto a «Ángulo», S026/RF-13) y en el menú de cada proyecto de Levantamiento; ambas abren la pantalla de elección con «Inversa», «Poligonal» e «Intersección». Con el SC geográfico, la pantalla de elección dice que hace falta un SC proyectado y no ofrece nada | Solo la lista de objetos |
| D18 | Entradas numéricas (RF-4) | `DirectionInput.parse(text, unit, kind)` y `DistanceInput.parse(text, unit)` en `core:model/format`, reutilizando la gramática de `AngleInput` (DD, DMS con símbolos o espacios, gon, coma o punto) con los rangos de la spec: azimut y ángulo en `[0, 360°)`, deflexión en `[0, 180°]`, distancia `≥ 0`. Rechazan lo que no es un número | Reutilizar `AngleInput` con un eje nuevo (su signo y sus letras de hemisferio no aplican) |
| D19 | Referencia independiente (constitución nº 2) | `tools/cogo/generar_referencias.py` con `pyproj` genera `fixtures/cogo/`: casos de manual y aleatorios con semilla fija, en ETRS89 UTM 30, REGCAN95 UTM 28 y ED50 UTM 30, en metros y en pies, con cuadrícula y terreno (factor de `Proj.get_factors`). Para la poligonal se parte de puntos conocidos, se calculan sus ángulos, deflexiones y azimuts, y Kotlin tiene que recuperar los puntos. Test de `pytest` del propio script | Valores copiados a mano |
| D20 | Rendimiento (RNF-T13) | Todo el cálculo en `Dispatchers.Default` y el guardado del borrador en `Dispatchers.IO`; una poligonal de 200 tramos se recalcula en menos de 100 ms (la geodesia solo se usa para el factor, una vez por cálculo) | Calcular en el hilo principal |
| D21 | Toques al código de specs protegidas | Mínimos y listados aquí para que los veas: **S013** (`DatabaseSnapshot.TABLES`: una fila `Table("traverse_drafts", "WHERE project_id = ?")`); **S012 y S053** (`CsvExport` y `GpkgPointAttributes`: una línea `PointOrigin.COGO -> "Cogo"` en el `when` del origen, como `IMPORTED` y `ON_ALIGNMENT`); **S011/S010** (`PointOrigin` gana `COGO`, `hasMeasurement` no lo cuenta, `ObjectListScreen`: etiqueta del origen y la entrada «COGO» del menú); **S006** (`TopoPhoneDatabase`: versión 33, entidades y `AutoMigration`). No se tocan sus `spec.md` | — |

## Estructura de módulos
- `core:model`
  - `point/PointOrigin.COGO`; `Point.cogo: CogoRef?` (D9, D10).
  - `cogo/`: `CogoOperation` (`TRAVERSE`, `INTERSECTION_DISTANCE_DISTANCE`, `INTERSECTION_DIRECTION_DIRECTION`, `INTERSECTION_DISTANCE_DIRECTION`), `CogoEntry(role, name, easting, northing, height)`, `LegKind` (`AZIMUTH`, `ANGLE_RIGHT`, `ANGLE_LEFT`, `DEFLECTION_RIGHT`, `DEFLECTION_LEFT`), `TraverseLeg(kind, angleRad, distanceM, dHeightM)`, `CogoValue(key, value)`, `IntersectionSide`, `CogoRef`, y el borrador `TraverseDraft(projectId, start, reference, closure, legs, updatedAtMs)` con `DraftPoint` (`Project(pointId, name)` o `Typed(easting, northing)`) (RF-22, RF-26).
  - `format/DirectionInput`, `format/DistanceInput` (D18). `geometry/DistanceMode` ya existe (S026).
- `core:survey` (`cogo/`)
  - `CogoPoint`, `CogoScale` (D2: factor del primer punto; 1 en cuadrícula; avisos de `GroundScale`).
  - `Inverse.of(a, b, mode, scale): InverseResult` (D3).
  - `Traverse.compute(start, reference, legs, closure, mode, scale): TraverseResult` con `TraverseResult(points, closure: ClosureError?, blockedFrom: Int?, notices)` (D4, D5, D6).
  - `Intersection.distanceDistance`, `.directionDirection`, `.distanceDirection` → `IntersectionResult` (`Solutions(list)` o `NoSolution(reason)`) (D7).
  - `CogoPointBuilder` (D9) → `CogoPointResult` = `Built(point)` o `Rejected(reason)`.
- `core:formats`: `cogo/CogoRefJson`, `cogo/TraverseDraftJson` (D10, D12).
- `data`
  - `point/PointEntities`: `cogo` en `PointEntity` y en `toEntity`/`toPoint`.
  - `cogo/`: `TraverseDraftEntity`, `TraverseDraftDao`, `CogoRepository` (borrador observable, guardar, vaciar; `savePoints`, D15, D16).
  - `db/TopoPhoneDatabase`: v33, entidad, `AutoMigration(32, 33)`.
  - `backup/DatabaseSnapshot.TABLES` (D14).
- `app` (`ui/cogo/`)
  - `CogoMenuScreen`, `CogoPointField` (elegir de la lista con búsqueda o escribir Este y Norte; muestra la altura), `CogoNumberFields` (dirección y distancia con su unidad), `CogoModeSelector` (Cuadrícula/Terreno, D2).
  - `InverseViewModel` e `InverseScreen`; `IntersectionViewModel` e `IntersectionScreen`; `TraverseViewModel` y `TraverseScreen` (tramos, cierre, borrador); `CogoSaveSheet` (nombres editables, marcar, «Guardar puntos»).
  - Entradas «COGO» en `ObjectListScreen` y en el menú del proyecto; `TopLevelDestination` (`CogoDestination`) y `TopoPhoneApp`; textos `cogo_*` en es y en.
- `tools`: `cogo/generar_referencias.py` y su test (D19).

## Modelo de datos
- Room v33: `points.cogo TEXT` (NULL salvo en los puntos COGO) y `traverse_drafts(project_id TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)` con clave foránea a `projects(id) ON DELETE CASCADE`.
- JSON de `points.cogo` (ejemplo, la tercera estación de una poligonal con un tramo por deflexión):
```json
{"v":1,"op":"TRAVERSE","entries":[{"role":"start","name":"P1","e":440100.0,"n":4474200.0,"h":650.2},{"role":"reference","name":"P0","e":440000.0,"n":4474100.0,"h":null}],
 "legs":[{"kind":"AZIMUTH","angleRad":0.7853981633974483,"distanceM":50.0,"dHeightM":0.4},{"kind":"DEFLECTION_RIGHT","angleRad":0.5235987755982988,"distanceM":35.5,"dHeightM":null}]}
```
- JSON de `traverse_drafts.data`: `{"v":1,"start":{"pointId":"…","name":"P1"},"reference":{"e":…,"n":…},"closure":null,"legs":[…]}`; un `DraftPoint` es `{"pointId","name"}` o `{"e","n"}`.
- Los números del JSON salen con `Locale.ROOT` y sin redondear; los `Double` se guardan con todos sus dígitos.

## Algoritmos
Todos sobre las coordenadas de cuadrícula del SC del proyecto; `f` = factor de D2 (1 en cuadrícula); `s(α) = (sen α, cos α)`.
### A1. Inversa (D3, RF-6 a RF-8)
`ΔE = E₂−E₁`, `ΔN = N₂−N₁`; `dG = hypot(ΔE, ΔN)` (cuadrícula) y `dT = dG / f` (terreno); azimut directo `atan2(ΔE, ΔN)` mod 2π e inverso `+π` mod 2π; `Δh = h₂−h₁` si ambos tienen altura; inclinada y pendiente con la distancia activa. Con `dG = 0`: distancia 0 y azimut indefinido, sin error.
### A2. Poligonal (D4 a D6, RF-10 a RF-12, RF-31)
1. `αatrás₀ = azimut(inicio → referencia)`, indefinido si coinciden.
2. Para el tramo `i`: con tipo `AZIMUTH`, `αᵢ = valor`. Con ángulo o deflexión hace falta `αatrásᵢ` (para `i > 1`, `αᵢ₋₁ + π`, indefinido si el tramo `i−1` mide 0): si es indefinido, el tramo y los siguientes se bloquean (`blockedFrom = i`, RF-31).
3. `dᵢ = distancia escrita × (terreno ? f : 1)`; `Pᵢ = Pᵢ₋₁ + dᵢ · s(αᵢ)`.
4. Altura (D5): `hᵢ = hᵢ₋₁ + dHᵢ` si `hᵢ₋₁` y `dHᵢ` existen; si no, sin altura.
5. Cierre: `dE, dN = último − cierre`, `linear = hypot`, `totalLength = Σ dᵢ`, `1:N` con `N = totalLength / linear`.
### A3. Intersecciones (D7, RF-14 a RF-17)
- Distancia–distancia: `d = |P₂−P₁|`; `d = 0` no tiene solución; `a = (r₁²−r₂²+d²)/(2d)`; `h² = ((r₁+r₂)²−d²)(d²−(r₁−r₂)²)/(4d²)`; `h² < −tol` no se cortan; `M = P₁ + a·u`; soluciones `M ± h·(−uN, uE)` (izquierda con `+`).
- Dirección–dirección: `s₁, s₂`; `den = s₁ × s₂`; `|den| < 1e-12` no tiene solución; `t₁ = ((P₂−P₁) × s₂)/den`, `t₂ = ((P₂−P₁) × s₁)/den`; `t < 0` avisa «detrás» (RF-15).
- Distancia–dirección: `w = P₂−P₁`; `disc = (s·w)² − |w|² + r²`; `t = −s·w ± √disc`; solución `P₂ + t·s`; azimut del segundo punto = el escrito, distancia al primero = `r`.
### A4. Punto COGO (D9, RF-21, RF-22)
`TypedCoordinates(PROJECT_CRS, E, N, altura?, HeightKind.PROJECT)` → `ManualCoordinates.compute` → `Point(origin = COGO, cogo = CogoRef(...))`. El Este y el Norte se guardan tal cual, sin ida y vuelta.
### A5. Nombres (RF-19)
`taken = names(projectId)`; el primero `nextFree(lastName, taken)`, los siguientes `nextFree(anterior, taken + propuestos)`.

## Contratos
- `Inverse.of`, `Traverse.compute` e `Intersection.*` son funciones puras; nunca lanzan por datos de usuario: devuelven resultados con el motivo (`NoSolution.reason`: `CONCENTRIC`, `TOO_FAR`, `TOO_CLOSE`, `PARALLEL`).
- `CogoRepository`: `draft(projectId): Flow<TraverseDraft?>`, `saveDraft(draft)`, `clearDraft(projectId)`, `savePoints(projectId, points, clearDraft): PointSaveResult` (`Saved` o `DuplicateName`).
- `CogoPointBuilder.build(...)`: `Built` o `Rejected(PositionUnavailable)` (el de `ManualCoordinates`).
- Estados de pantalla: `loaded`, `geographic`, entradas, `result` (ninguno hasta tener los datos), avisos, «guardando»/«guardado»/«nombre repetido»/«fuera de ámbito».
- Textos (`cogo_*`, es y en): «COGO», «Inversa», «Poligonal», «Intersección», «Distancia–distancia», «Dirección–dirección», «Distancia–dirección», «Cuadrícula», «Terreno», «Izquierda», «Derecha», «Primera», «Segunda», clases de tramo (azimut, ángulo a la derecha, ángulo a la izquierda, deflexión a la derecha, deflexión a la izquierda), «Error de cierre», «Descartar borrador», «Guardar puntos», y los motivos de «sin solución», de «SC geográfico», de «detrás de…», de «visual atrás indefinida», de «nombre repetido» y de «fuera del ámbito».

## Estrategia de tests
- Unitarios JVM (`core:model`): `PointOriginCogoTest`, `CogoRefTest`, `DirectionInputTest`, `DistanceInputTest`.
- Unitarios JVM (`core:survey`): `InverseTest`, `TraverseAzimuthTest`, `TraverseAnglesTest`, `TraverseClosureTest`, `TraverseHeightTest`, `IntersectionCircleTest`, `IntersectionLinesTest`, `CogoPointBuilderTest`, `CogoScaleTest`, todos frente a `fixtures/cogo/` (≤ 1 mm y 0,001″; factor combinado ≤ 1e-9 frente a `Proj.get_factors`); `TraversePerformanceTest` (200 tramos, menos de 100 ms).
- Unitarios JVM (`core:formats`): `CogoRefJsonTest`, `TraverseDraftJsonTest` (ida y vuelta de los `Double`, `Locale.ROOT`).
- Python: `tools/cogo/test_cogo.py`.
- Robolectric (`data`): `TopoPhoneDatabaseTest` (migración v32 a v33), `CogoPointPersistTest`, `TraverseDraftRepositoryTest`, `CogoSavePointsTest`, `CogoBackupTest` (copia sí, paquete y duplicado no), `CogoExportOriginTest` (CSV y GeoPackage). Nombres de menos de 60 caracteres por Windows.
- Robolectric (`app`): `CogoMenuTest`, `CogoPointFieldTest`, `InverseViewModelTest`, `IntersectionViewModelTest`, `TraverseViewModelTest`, `TraverseDraftViewModelTest`, `CogoSaveTest`, `CogoScreensTest`, `CogoPerformanceTest`, `CogoAccessibilityTest`; los `viewModelScope` se cancelan antes de cerrar la base.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | D17 | `CogoMenuTest` |
| RF-2 | D17 | `CogoMenuTest` |
| RF-3 | D8 | `CogoPointFieldTest` |
| RF-4 | D18 | `DirectionInputTest`, `DistanceInputTest`, `CogoPointFieldTest` |
| RF-5 | D2 | `CogoScaleTest`, `InverseViewModelTest` |
| RF-6 | D3, A1 | `InverseTest`, `InverseViewModelTest` |
| RF-7 | D3 | `InverseTest` |
| RF-8 | A1 | `InverseTest` |
| RF-9 | — | `InverseViewModelTest` |
| RF-10 | D4, A2 | `TraverseAzimuthTest`, `TraverseAnglesTest`, `TraverseViewModelTest` |
| RF-11 | D5, A2 | `TraverseHeightTest`, `TraverseViewModelTest` |
| RF-12 | D6, A2 | `TraverseClosureTest` |
| RF-13 | A3 | `IntersectionViewModelTest` |
| RF-14 | D7, A3 | `IntersectionCircleTest` |
| RF-15 | D7, A3 | `IntersectionLinesTest` |
| RF-16 | D7, A3 | `IntersectionLinesTest` |
| RF-17 | D7 | `IntersectionCircleTest`, `IntersectionLinesTest` |
| RF-18 | A4 | `CogoPointBuilderTest`, `IntersectionViewModelTest` |
| RF-19 | D15, A5 | `CogoSavePointsTest`, `CogoSaveTest` |
| RF-20 | D15 | `CogoSavePointsTest`, `CogoSaveTest` |
| RF-21 | D9, D15 | `CogoPointBuilderTest`, `CogoSaveTest` |
| RF-22 | D10, A4 | `CogoRefJsonTest`, `CogoPointPersistTest`, `TopoPhoneDatabaseTest` |
| RF-23 | D15 | `CogoSavePointsTest`, `TraverseViewModelTest` |
| RF-24 | D9, D21 | `CogoPointPersistTest`, `CogoExportOriginTest` |
| RF-25 | D16 | `CogoSavePointsTest` |
| RF-26 | D11, D12 | `TraverseDraftRepositoryTest`, `TraverseDraftViewModelTest` |
| RF-27 | D12, D13 | `TraverseDraftViewModelTest` |
| RF-28 | D13 | `TraverseDraftViewModelTest` |
| RF-29 | D14 | `CogoBackupTest`, `TraverseDraftRepositoryTest` |
| RF-30 | D15 | `CogoSavePointsTest` |
| RF-31 | A2 | `TraverseAnglesTest` |
| RNF-T6 | D11, D15 | `TopoPhoneDatabaseTest`, `CogoSavePointsTest` |
| RNF-T7, RNF-T8 | textos, 56 dp, izquierda/derecha con texto | `CogoAccessibilityTest` |
| RNF-T9 | D16, JSON con `Locale.ROOT` | `CogoRefJsonTest`, `InverseViewModelTest` |
| RNF-T13 | D20 | `CogoPerformanceTest`, `TraversePerformanceTest` |
| Constitución nº 2 | D19 | tests de `core:survey` y de `tools` |

## Cambios al implementar
_(Se rellena al cerrar la spec.)_
