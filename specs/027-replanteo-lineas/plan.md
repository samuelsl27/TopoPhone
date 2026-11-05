# Plan técnico — Spec 027

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/027-replanteo-lineas/spec.md`, `specs/041-replanteo-cad/plan.md` (motor de línea, `LineStakeoutViewModel`, `StakeSource`), `specs/025-lineas-y-poligonos/plan.md` (`ProjectLine`, `vertexPoints`), `specs/019-*/plan.md` (informe), `specs/049-*/plan.md` (eje desde polilínea) y `specs/054-*/plan.md` (replanteo por eje).

Sin dependencias nuevas, ni Gradle ni Python. Room pasa a la **v31** (`AutoMigration(30, 31)`, solo columnas nuevas); la siguiente spec que lo toque (S017b) pasa a la v32.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Una sola máquina de replanteo de línea (RF-1, RF-2) | `LineStakeoutViewModel` recibe una `LineSource` sellada: `Drawing(selection)` (lo de hoy) o `ProjectLine(lineId, …)`. Cada variante aporta la `StakeLine`, el tipo de elemento, la `StakeSource`, el identificador y el nombre del objetivo y quién vigila el cierre. El motor (`LineLocator`, `LineObjectives`, `LineSeries`, `LineVertices`) no se toca, de modo que una línea del proyecto y una de un dibujo con la misma geometría dan lo mismo (RF-2) | Un ViewModel nuevo para líneas del proyecto (duplica cinco modos y avisos) |
| D2 | Cota parcial (RF-8, RF-9) | `StakeLine` guarda la altura por vértice con `NaN` si falta. Si todos la tienen, `heightAt` es exactamente la de S041. Si faltan algunas: `heightAt(pk)` interpola en el PK entre el vértice con altura anterior y el siguiente (saltando los que no la tienen) y devuelve `null` antes del primero y después del último con altura; en un polígono el tramo de cierre sí interpola (no hay «antes» ni «después»). Un solo vértice con altura solo da cota en su propio PK. `height(v)` devuelve la altura del punto o `null`. Lo de un dibujo no cambia (sus alturas son todo o nada) | Una segunda clase de línea para cota parcial |
| D3 | Qué ve la guía sin cota de diseño (RF-9, RF-10) | La cota de diseño es una propiedad de cada objetivo: `LineObjective.heightM == null` activa el aviso con los vértices sin altura, la cota escrita a mano para ese objetivo y la superficie activa. Hoy `LineStakeoutViewModel` decide «sin cota» para toda la línea con `hasHeight`; pasa a decidirse por objetivo | Mantener «sin cota» para toda la línea y perder RF-8 |
| D4 | De `ProjectLine` a `StakeLine` (RF-3, RF-5, RF-6, RF-7) | `ProjectStakeLine.of(vertices, closed)` en `core:survey`: recibe Este, Norte, altura (o `NaN`) y nombre de cada vértice (los que `LineRepository.vertexPoints` ya entrega sin los puntos de la papelera), todos «originales» y de tipo polilínea. `StakeLine.create` quita vértices consecutivos repetidos, así que devuelve también el índice de origen de cada vértice que queda; con él «Por segmentos» ofrece el nombre del punto de cada vértice y marca el vértice con cota interpolada. Con menos de 2 vértices devuelve «no se puede» y el replanteo no se abre | Reconstruir los nombres por posición (se descuadra con repetidos) |
| D5 | Qué se guarda del punto replanteado (RF-11, RF-12, RF-16) | Nueva variante `StakeSource.ProjectLine(lineId, lineName, lineCode)` y, en `points`, `stake_line_id` y `stake_line_name` (texto, nulos). Sin clave foránea: al borrar la línea el punto conserva el identificador y el nombre (RF-16). El nombre del punto, el modo, el PK, el desplazamiento y las diferencias salen como en S041. **Room v31** | Clave foránea a `project_lines` con `SET NULL` (pierde el nombre) |
| D6 | Nombres y código (RF-11, RF-12) | Sin cambios de regla: `StakeoutNames.next` en «Por segmentos» (`<vértice>_stkd`) y `StakeoutNames.nextForLayer(<línea>, tomados)` en los demás. El objetivo (`StakeTarget`) lleva el `PointCode` de la línea (con sus atributos por valor) y el recolector ya lo hereda al guardar | Reescribir el recolector |
| D7 | Cerrar si la línea cambia (RF-15) | `LineStakeoutViewModel` observa `LineRepository.line(id)` y `vertexPoints(id)` y compara con la instantánea del momento de abrir (nombre, código, cierre, vértices y, de cada uno, nombre, Este, Norte y altura; no cuentan ocultar ni «activa»). Si la línea desaparece o se mueve a la papelera cierra con `LINE_DELETED`; si cambia, con `LINE_CHANGED`. El patrón es el de `watchDrawing`: `reset()` del controlador y la razón se enseña en el panel | Recalcular (descartado en la entrevista) |
| D8 | Dónde se abre (RF-1) | Botón «Replantear» en la ficha de la línea; hoja de acciones al tocar una línea en el mapa (nueva `LineMapSheet`: «Abrir ficha», «Replantear», «Información», «Crear eje»); y menú «⋮» en cada fila de línea de la lista de objetos. Tocar una línea en el mapa deja de abrir la ficha directamente: ahora ofrece la hoja, que lo hace con «Abrir ficha» | Solo el botón de la ficha (incumple RF-1) |
| D9 | Mapa (RF-4) | El replanteo dibuja la línea elegida con el trazo grueso que ya usa `LineScene` para la línea activa; el panel enseña nombre, código, longitud o perímetro y si es abierta o polígono (`LineElementInfo` pasa a recibir esos datos de cualquiera de las dos fuentes) | Una capa nueva |
| D10 | «Crear eje» (RF-17, RF-18) | `PolylineAlignmentBuilder` gana `buildFromVertices(east, north, height, nearE, nearN, startChainage)` y `build(entity, …)` pasa a llamarlo, sin cambiar lo de S049. Con una línea del proyecto: vértices como tramos rectos, el inicio es el primer vértice, la rasante usa solo los vértices con altura (si hay al menos 2) sin acuerdos, y la línea y sus puntos no se tocan. `AlignmentFromDrawingViewModel` gana `convertLine`; el diálogo `ConvertToAlignmentDialog` no cambia. Solo se ofrece en una línea abierta con al menos 2 vértices. El replanteo por PK del eje creado es el de S054, sin repetirlo | Construir un `CadEntity` sintético |
| D11 | Informe (RF-14) | `StakeoutReportCsv` gana la columna «Line» tras «Lateral offset» (vacía si no viene de una línea del proyecto); los ficheros golden de S019/S041 se actualizan con la columna vacía. Una fila de este replanteo lleva «Line» y deja vacíos «Drawing» y «Layer» | Reusar la columna «Layer» |
| D12 | Rendimiento (RNF-T13, abrir en menos de 1 s) | Se abre con `vertexPoints` (un `JOIN`, sin cargar todos los puntos) y se construye la `StakeLine` en `Dispatchers.Default`. El resto del replanteo es el motor de S041 | Cargar los puntos del proyecto |
| D13 | Cambios en otras specs | No se tocan los `spec.md` de S019, S041 ni S049. Se citan en los tests como `S027/RF-n`. Sí se actualizan el glosario y la hoja de ruta | — |

## Estructura de módulos
- `core:model`: `StakeSource.ProjectLine`; `LineStakeoutMode` sin cambios.
- `core:survey`
  - `StakeLine`: altura por vértice con `NaN`, `heightAt` y `height(v)` según D2; `create` devuelve también el origen de cada vértice (`StakeLine.Built`).
  - `ProjectStakeLine` (D4): entrada de la línea del proyecto, nombres, índices de origen y vértices con cota interpolada.
  - `PolylineAlignmentBuilder.buildFromVertices` (D10).
- `core:formats`: `StakeoutReportCsv` con la columna «Line» (D11).
- `data`: Room v31 (`AutoMigration(30, 31)`, `PointEntity.stakeLineId` y `stakeLineName`), mapeos `StakeSource.ProjectLine` ida y vuelta.
- `app`
  - `stakeout/`: `LineSource`, `LineStakeoutViewModel` generalizado, cierre por cambio (`LineClosedReason.LINE_DELETED`, `LINE_CHANGED`), `LineElementInfo` común, lista de «Por segmentos» con nombres, cota por objetivo.
  - `lines/`: botones «Replantear» y «Crear eje» en `LineDetailsScreen`; `LineMapSheet`.
  - `points/`: menú «⋮» de la fila de línea; fila de línea de replanteo en `PointDetailsScreen`.
  - `alignment/`: `AlignmentFromDrawingViewModel.convertLine`.
  - `projects/ProjectViewScreen`: hoja del mapa, apertura del replanteo y del diálogo del eje con la línea.

## Modelo de datos
```
points   + stake_line_id TEXT NULL, stake_line_name TEXT NULL     -- Room v31, AutoMigration(30, 31), sin clave foránea
```
- `StakeSource.ProjectLine(lineId, lineName)`; el código y los atributos van en el `Point` como en S024, no en la fuente.
- `PointEntity.cadSource()`: `ProjectLine` si hay `stake_line_id`, antes de `Alignment` y de `CadElement`.

## Algoritmos
### A1. Cota de diseño de un objetivo (D2)
Con las alturas por vértice `h[i]` (o `NaN`):
1. Si no falta ninguna: `heightAt(pk)` de S041, interpolando entre los extremos del tramo.
2. Si falta alguna: se busca el vértice con altura `a` de PK ≤ `pk` más cercano y el vértice con altura `b` de PK ≥ `pk` más cercano. Si existen los dos, `h = h[a] + (h[b] − h[a])·(pk − pk[a])/(pk[b] − pk[a])` (si `pk[a] = pk[b]`, `h[a]`). En un polígono, si falta `a` o `b`, se busca al otro lado del cierre sumando el perímetro. En una línea abierta, si falta uno, `null`.
3. En «Por segmentos», un vértice con altura propia usa esa; si no, la interpolada, marcada como interpolada.
### A2. Abrir el replanteo de una línea (D1, D4, D12)
`LineStakeoutViewModel.open(projectId, LineSource.ProjectLine(lineId))` lee la línea y sus `vertexPoints`, construye la `StakeLine` con `ProjectStakeLine.of` fuera del hilo principal y, si no hay al menos 2 vértices, deja `LineClosedReason.TOO_FEW_VERTICES` (RF-3). El `StakeTarget` lleva el identificador `line:<id>`, el nombre y el código de la línea.
### A3. Vigilar la línea (D7)
Un `Flow` combina `line(id)` y `vertexPoints(id)` en una instantánea sin `hidden` ni `active`; la primera se guarda al abrir. Si la línea es `null` (borrada del todo o en la papelera) → `LINE_DELETED`; si la instantánea difiere → `LINE_CHANGED`. Con el replanteo cerrado, `edit`, `setMode` y la guía se rechazan como con el dibujo borrado.
### A4. Crear eje desde una línea (D10)
`PolylineAlignmentBuilder.buildFromVertices` hace los mismos tramos rectos que con un dibujo; las alturas con valor forman la rasante (`VerticalVertex(pk, cota)` en el PK de cada uno) si hay al menos 2. Un fallo del constructor (menos de 2 vértices distintos) se enseña como en S049.

## Contratos
- `LineSource`: `Drawing(selection: DesignSelection)` y `ProjectLine(lineId: String)`.
- `ProjectStakeLine.of(vertices: List<ProjectStakeVertex>, closed: Boolean): ProjectStakeLineResult` = `Built(line, names, sourceIndex, interpolated)` o `TooFewVertices`.
- `LineClosedReason`: `DRAWING_DELETED`, `LINE_DELETED`, `LINE_CHANGED`, `TOO_FEW_VERTICES`.
- Textos (es/en, prefijos `line_stakeout_*` y `line_sheet_*`): «Replantear», «Crear eje», «Abrir ficha», motivos del cierre, «Cota interpolada», «Sin cota en este tramo» y la fila «Línea» de la ficha del punto.

## Estrategia de tests
- Unitarios JVM (`core:model`): `StakeSourceProjectLineTest`.
- Unitarios JVM (`core:survey`): `StakeLinePartialHeightTest` (frente a `fixtures/replanteo/cota-parcial/`), `ProjectStakeLineTest`, `ProjectLineParityTest` (la misma geometría como `StakeLine.of(CadEntity)` y como línea del proyecto da lo mismo en los cinco modos, con `LineFixtures` de S041 y 1 mm), `PolylineAlignmentFromVerticesTest`.
- Unitarios JVM (`core:formats`): `StakeoutReportCsvLineTest` y los golden de S019/S041 con la columna nueva.
- Python: `tools/replanteo/test_cota_parcial.py`.
- Robolectric (`data`): `TopoPhoneDatabaseTest` de la v30 a la v31, `StakeLineSourceMappingTest` (ida y vuelta), `LineStakeoutBackupTest` (la copia conserva la fuente).
- Robolectric (`app`): `LineStakeoutProjectLineTest` (abrir, menos de 2 vértices, papelera, cota por objetivo, superficie), `LineStakeoutSegmentsTest` (nombres, PK, polígono), `LineStakeoutSaveTest` (nombres, fuente, código y atributos), `LineStakeoutClosedTest` (editar, mover, borrar), `LineDeletedKeepsPointsTest`, `LineActionsTest` (ficha, hoja del mapa, menú de fila), `PointDetailsLineTest`, `StakeoutReportLineTest`, `CreateAxisFromLineTest`, `LineStakeoutPerformanceTest`, `LineStakeoutAccessibilityTest`; `viewModelScope` cancelado antes de cerrar la base, esperas a estados asentados y nombres cortos por Windows.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | D1, D8 | `LineActionsTest`, `LineStakeoutProjectLineTest` |
| RF-2 | D1 | `ProjectLineParityTest` |
| RF-3 | D4, A2 | `ProjectStakeLineTest`, `LineStakeoutProjectLineTest` |
| RF-4 | D9 | `LineStakeoutProjectLineTest` |
| RF-5, RF-6, RF-7 | D4 | `ProjectStakeLineTest`, `LineStakeoutSegmentsTest` |
| RF-8, RF-9 | D2, D3, A1 | `StakeLinePartialHeightTest`, `LineStakeoutProjectLineTest` |
| RF-10 | D3 | `LineStakeoutProjectLineTest` |
| RF-11, RF-12 | D5, D6 | `LineStakeoutSaveTest`, `StakeLineSourceMappingTest`, `TopoPhoneDatabaseTest` |
| RF-13 | D5 | `PointDetailsLineTest` |
| RF-14 | D11 | `StakeoutReportCsvLineTest`, `StakeoutReportLineTest` |
| RF-15 | D7, A3 | `LineStakeoutClosedTest` |
| RF-16 | D5 | `LineDeletedKeepsPointsTest` |
| RF-17, RF-18 | D10, A4 | `PolylineAlignmentFromVerticesTest`, `CreateAxisFromLineTest` |
| RNF-T2, RNF-T3 | los de S041 | los tests de S041 intactos |
| RNF-T6 | D5 | `TopoPhoneDatabaseTest`, `LineStakeoutBackupTest` |
| RNF-T7, RNF-T8 | textos y 56 dp | `LineStakeoutAccessibilityTest` |
| RNF-T13 y abrir en menos de 1 s | D12 | `LineStakeoutPerformanceTest` |
| Constitución nº 2 | D2, D1 | `StakeLinePartialHeightTest`, `ProjectLineParityTest` |
