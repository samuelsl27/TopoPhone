# Plan técnico — Spec 025

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/025-lineas-y-poligonos/spec.md`, `specs/024-recolectar-con-codigos/plan.md` (`PointCode`, `CollectorCodes`, `AttributeForm`, `AttributeCarryOver`, `ChangeCode`, símbolos del mapa), `specs/011-*/plan.md` (historial, papelera, ocultar), `specs/010-*/plan.md` (recolector), `specs/013-*/plan.md` (copias), `specs/053-*/plan.md` (GeoPackage) y `specs/041-*/plan.md` (sentido y PK de una línea).

Sin dependencias nuevas, ni Gradle ni Python. Todo se construye con lo que ya hay: Room, Compose, MapLibre (capas de línea, de relleno y de símbolos) y los modelos de S023 y S024.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Cómo se guarda una línea (RF-2, RF-14, RF-16, RF-17) | Tabla `project_lines` (cabecera: nombre, código por valor, cerrada, oculta, activa, creada, borrada) y `line_vertices(line_id, ordinal, point_id)`, con `point_id` referenciando a `points`. El vértice es una referencia, nunca una copia. **Room v30** | Guardar las coordenadas en la línea (duplica el dato y rompe RF-17) |
| D2 | Código y atributos de la línea (RF-8b, RF-20) | Como el punto (S024/D3): columnas `code`, `code_description`, `code_color`, `code_symbol` en `project_lines` y tabla `line_attributes(line_id, ordinal, name, type, value)`. Se reutilizan `PointCode` y `PointAttribute` | Tablas y modelos nuevos para el código de línea |
| D3 | Línea activa (RF-2 a RF-9) | Columna `active` en `project_lines`, con a lo sumo una activa por proyecto: el repositorio la cambia siempre en la misma transacción que borra la marca de la anterior. Sobrevive al cierre de la app por estar en la base. Borrar una línea, terminarla o cerrarla en polígono la desactiva | Preferencia en DataStore (puede quedar apuntando a una línea borrada) |
| D4 | Medir y añadir el vértice en una sola transacción (RF-2, RF-3, RF-7, RF-8, RF-8b) | `LineRepository.saveMeasured(point)`: dentro de una transacción de Room guarda el punto (`PointRepository.create`), decide con la función pura `LineTarget.decide` (añadir / crear línea / nada) y escribe el vértice. Si falla algo no queda ni el punto ni el vértice | Guardar el punto y luego el vértice por separado (puede quedar un punto sin vértice) |
| D5 | Nombre propuesto (RF-2, RF-14) | `LineNaming.next(code, names)`: `CÓDIGO_n` con n = mayor número usado con ese prefijo entre las líneas del proyecto (activas y en papelera no cuentan) + 1; sin ninguna, 1. Un nombre que ya existe se rechaza con el motivo (`LineSaveResult.DuplicateName`) y la restauración propone otro (RF-24) | Primer número libre (reutiliza huecos) |
| D6 | Historial (RF-10, RF-18, RF-25) | `line_history(id, line_id, changed_at, change, name, closed, code…)` con los valores anteriores, más `line_history_attributes` y `line_history_vertices(history_id, ordinal, point_id, point_name)` (sin clave foránea, porque el punto puede haber desaparecido). `LineChange { NAME, CODE, ATTRIBUTES, VERTICES, CLOSED, VERTEX_REMOVED }`. Medir, deshacer un vértice (RF-6) y terminar no entran en el historial: son recolección, no edición | Historial también de cada vértice medido (crece sin aportar) |
| D7 | Papelera de puntos (RF-24, RF-25) | La fila del vértice se queda mientras el punto está en la papelera; al leer la línea se omiten los vértices cuyo punto está borrado, por lo que al restaurarlo vuelve a su sitio. Al borrarlo definitivamente (`deletePermanently` y `purgeTrash`) una transacción anota `VERTEX_REMOVED` en cada línea afectada y luego borra el punto; la clave foránea con `CASCADE` quita el vértice. Las líneas tienen su propia papelera (`deleted_at`, `trash_key`) con los mismos 30 días y la misma regla de nombre único que los puntos | Impedir borrar puntos que son vértices; o arrastrar la línea |
| D8 | Aviso al borrar un punto vértice (RF-25) | `LineRepository.linesUsing(pointIds)` devuelve los nombres de las líneas activas (no en papelera) que los usan; la lista de objetos y la ficha del punto lo consultan antes de confirmar | Aviso genérico sin nombres |
| D9 | Qué ve el mapa (RF-22, RF-23) | `MapLines.from(lines, points)` combina las líneas con las coordenadas actuales de los puntos (en el SC del proyecto), de modo que editar un punto mueve la línea (RF-17). Capa de líneas (color del código, nombre), capa de relleno translúcido para polígonos y capa de símbolos de nombre. Toca una línea abre su ficha. Se calcula fuera del hilo principal y solo se redibuja lo que cambia | Redibujar todo en cada punto medido |
| D10 | Códigos de línea en el selector (RF-1, RF-20) | `ProjectCodes` gana un parámetro de geometrías: el recolector y «Cambiar código» de un punto siguen ofreciendo solo Punto; el recolector con líneas ofrece Punto, Línea y Polígono marcados con su geometría; «Cambiar código» de una línea ofrece solo Línea y Polígono. El selector y los atributos son los de S024 | Un selector nuevo |
| D11 | Atributos de la línea al medir (RF-8b) | El formulario de atributos del recolector se muestra solo cuando medir crearía una línea (no hay línea activa o su código es distinto). Con línea activa del mismo código se oculta y los valores salen de la línea | Pedirlos siempre |
| D12 | Cambios en S011, S024, S053, S013 | No se tocan sus `spec.md`. La lista de objetos, la papelera, el selector, las copias y GeoPackage se amplían aquí y se citan en los tests como `S025/RF-n`. Sí se actualizan el glosario y la hoja de ruta | — |
| D13 | GeoPackage (RF-26) | S053/RF-3 pide que las líneas lleguen al `.gpkg`: `GeoPackageWriter` recibe también las líneas y escribe una capa `lines` (LineString) y otra `polygons` (Polygon) con nombre, código, descripción y atributos tipados como en S024/D11. Los vértices se proyectan al SC del `.gpkg` como los puntos. Sin líneas, el fichero no cambia | Dejarlo para otra spec |

## Estructura de módulos
- `core:model`
  - `line/`: `ProjectLine(id, projectId, name, code: PointCode?, closed, hidden, active, vertices: List<LineVertex>)`, `LineVertex(pointId)`, `LineSummary`, `LineTarget` (decide qué hacer al medir; RF-2, RF-3, RF-7, RF-8), `LineNaming` (RF-2, RF-14), `LineRules` (mínimos de vértices, cierre, abrir, invertir, vértices consecutivos repetidos; RF-4, RF-10, RF-11, RF-15, RF-19), `LineEdit` (añadir, quitar, cambiar, mover; RF-16), `LineHistory` (`LineChange`, `HistoryLine`), `LineSearch` (RF-21), `LineChainage` (PK de cada vértice partiendo del primero, con el SC del proyecto, como S041; RF-16).
  - `codes/`: `ProjectCodes` con parámetro de geometrías (D10).
- `core:formats` (`gpkg/`): capas `lines` y `polygons` (D13).
- `data`
  - Room v30 y `AutoMigration(29, 30)`: `ProjectLineEntity`, `LineVertexEntity`, `LineAttributeEntity`, `LineHistoryEntity`, `LineHistoryAttributeEntity`, `LineHistoryVertexEntity`.
  - `line/LineRepository` (+ `RoomLineRepository`): `lines`, `line`, `active`, `saveMeasured`, `finish`, `continueLine`, `undoVertex`, `closeLine`, `openLine`, `createFromPoints`, `edit`, `changeCode`, `setHidden`, `moveToTrash`, `restore`, `deletePermanently`, `purge`, `linesUsing`, `history`.
  - `PointRepository.deletePermanently` y `purgeTrash` anotan el historial de las líneas (D7).
  - `DatabaseSnapshot.TABLES`, `BackupRepository.copyProject`, `GeoPackageWriter` (D13).
- `app`
  - `collector/`: estado de línea activa (nombre, vértices) y botones «Terminar línea», «Continuar línea», «Deshacer vértice»; el formulario de atributos según D11; el guardado llama a `saveMeasured`.
  - `lines/`: `LineDetailsScreen` (ficha: vértices con PK, atributos, historial, acciones), `LineFromPointsScreen` (asistente), `LineViewModel`s y `ContinueLineDialog`.
  - `points/`: la lista de objetos muestra líneas junto a puntos (RF-21); el aviso de RF-25 en el borrado y la papelera con líneas.
  - `map/`: `MapLines`, capas de línea, relleno y nombre, toque para abrir la ficha (D9).
  - `export/`: las líneas a GeoPackage (D13).

## Modelo de datos
### Room v30
```
project_lines     id TEXT PK, project_id TEXT FK(projects, CASCADE), name TEXT, closed INTEGER, hidden INTEGER,
                  active INTEGER, code TEXT NULL, code_description TEXT NULL, code_color INTEGER NULL, code_symbol TEXT NULL,
                  created_at INTEGER, deleted_at INTEGER NULL, trash_key TEXT DEFAULT ''
                  UNIQUE (project_id, name, trash_key)    -- como points
line_vertices     line_id TEXT FK(project_lines, CASCADE), ordinal INTEGER, point_id TEXT FK(points, CASCADE)
                  PRIMARY KEY (line_id, ordinal)          -- INDEX (point_id) para linesUsing
line_attributes   line_id FK(project_lines, CASCADE), ordinal, name, type, value, PRIMARY KEY (line_id, ordinal)
line_history      id PK, line_id FK(project_lines, CASCADE), changed_at, change, name, closed, code, code_description, code_color, code_symbol
line_history_attributes  history_id FK(line_history, CASCADE), ordinal, name, type, value
line_history_vertices    history_id FK(line_history, CASCADE), ordinal, point_id TEXT, point_name TEXT
```
- `AutoMigration(29, 30)`: solo tablas nuevas, sin tocar las existentes.
- Los puntos de una línea nunca se escriben desde `LineRepository` (RF-17).

## Algoritmos
### A1. Medir con una línea (RF-2, RF-3, RF-7, RF-8, RF-8b, D4)
`LineTarget.decide(active, pointCode, pointId)`:
1. Si el código del punto no es de Línea ni Polígono: `Nothing`; la línea activa no cambia.
2. Si hay línea activa con el mismo código (clave `CodeKey`): si `pointId` es el último vértice, `Nothing`; si no, `Append(line)`.
3. En otro caso, `CreateLine`: nombre por `LineNaming`, atributos del formulario (D11), esta línea pasa a ser la activa y la anterior deja de serlo.
La transacción de `saveMeasured` ejecuta la decisión y el guardado del punto.

### A2. Terminar, cerrar y abrir (RF-4, RF-10, RF-11)
- Terminar: quita `active`; si la línea es de código Polígono y tiene ≥ 3 vértices, `closed = true`.
- Cerrar a mano / abrir: ≥ 3 vértices; el cierre une el último con el primero sin repetirlo. Ambos escriben historial (`CLOSED`).

### A3. Línea desde puntos (RF-12 a RF-15)
Estado del asistente = lista ordenada de puntos, código, atributos, abierta o cerrada, nombre. «Ordenar por nombre» usa el orden natural (`1`, `2`, `10`). `LineRules.validate`: ≥ 2 vértices (≥ 3 polígono) y ningún par seguido repetido. Al confirmar, `createFromPoints` crea la línea, sus vértices y sus atributos en una transacción, sin escribir ningún punto.

### A4. Editar vértices (RF-16 a RF-19)
`LineEdit` devuelve la lista nueva (añadir al final o entre dos, quitar, cambiar el punto, mover, invertir). `LineRules.validate` la comprueba antes de guardar. `edit` guarda el historial con los vértices anteriores y reescribe `line_vertices` en una transacción.

### A5. Cambiar el código de una línea (RF-20)
Igual que S024/A3 (`AttributeCarryOver`), con el aviso de valores que se pierden, restringido a códigos de Línea y Polígono.

### A6. Papelera de puntos y líneas (RF-24, RF-25)
- Mover un punto a la papelera no toca `line_vertices`; `LineRepository.line` filtra los vértices con el punto en la papelera.
- `deletePermanently`/`purgeTrash` de puntos: dentro de una transacción, para cada línea que usa el punto se inserta una fila de historial `VERTEX_REMOVED` con los vértices anteriores y después se borra el punto.
- Restaurar una línea: igual que un punto (`trash_key`); si su nombre está ocupado, se ofrece uno nuevo con `LineNaming`.

### A7. Mapa (D9)
`MapLines.from(lines, pointsById)` genera un `LineString` por línea abierta y un `Polygon` por línea cerrada con las coordenadas del SC del proyecto convertidas a longitud y latitud como los puntos. Las líneas ocultas, borradas o con menos de 2 vértices visibles no se dibujan. La línea activa se marca con el trazo más grueso.

## Contratos
- `LineRepository`: `lines(projectId): Flow<List<ProjectLine>>`, `summaries(projectId): Flow<List<LineSummary>>`, `line(id): Flow<ProjectLine?>`, `active(projectId): Flow<ProjectLine?>`, `saveMeasured(point): MeasuredResult` (`Saved(line, created)` o `PointOnly`), `finish(id)`, `continueLine(id)`, `undoVertex(id)`, `close(id)`, `open(id)`, `createFromPoints(spec): LineSaveResult`, `edit(id, edit): LineSaveResult`, `changeCode(id, code?)`, `setHidden`, `moveToTrash`, `restore(id, newName?)`, `deletePermanently`, `linesUsing(pointIds): Flow<List<String>>`, `history(id)`.
- `LineSaveResult`: `Saved`, `DuplicateName`, `TooFewVertices`, `RepeatedVertex`, `NotFound`.
- Textos (es/en en `strings.xml`, prefijos `line_*`, `collector_line_*`): «Terminar línea», «Continuar línea», «Deshacer vértice», «Cerrar en polígono», «Abrir», «Línea desde puntos», estados «Abierta», «Polígono» y «Activa», motivos de cada rechazo, aviso de RF-25 con los nombres y filas del historial.

## Estrategia de tests
- Unitarios JVM (`core:model`): `LineTargetTest`, `LineNamingTest`, `LineRulesTest`, `LineEditTest`, `LineHistoryTest`, `LineSearchTest`, `LineChainageTest`, `ProjectCodesGeometryTest`.
- Unitarios JVM (`core:formats`): `GpkgLinesTest` (capas `lines` y `polygons`).
- Robolectric (`data`): `TopoPhoneDatabaseTest` 29 a 30, `LineRepositoryTest` (medir en una transacción, una sola línea activa, terminar, continuar, deshacer, cerrar y abrir, crear desde puntos, editar con historial, no se escribe ningún punto), `LinePointTrashTest` (RF-24, RF-25), `LineBackupTest`, `GeoPackageWriterLinesTest`, rendimiento de añadir vértice (< 100 ms) y de abrir 100.000 vértices (< 1 s).
- Robolectric (`app`): `CollectorLineViewModelTest`, `CollectorLineScreenTest`, `LineFromPointsTest`, `LineDetailsTest`, `ObjectListLinesTest`, `MapLinesTest`, `LinesLayerTest`, `LinesPerformanceTest` (1.000 líneas y 100.000 vértices sin bloquear el hilo principal más de 16 ms), `LinesAccessibilityTest`; los ViewModels guardados y su `viewModelScope` cancelado antes de cerrar la base; nombres cortos con base de fichero en Windows.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-20 | D10 | `ProjectCodesGeometryTest`, `CollectorLineScreenTest` |
| RF-2, RF-3, RF-7, RF-8 | A1, D3, D4, D5 | `LineTargetTest`, `LineNamingTest`, `LineRepositoryTest`, `CollectorLineViewModelTest` |
| RF-8b | D2, D11 | `CollectorLineViewModelTest`, `LineRepositoryTest` |
| RF-4, RF-10, RF-11 | A2 | `LineRulesTest`, `LineRepositoryTest` |
| RF-5, RF-6, RF-9 | D3 | `LineRepositoryTest`, `CollectorLineViewModelTest` |
| RF-12 a RF-15 | A3 | `LineRulesTest`, `LineFromPointsTest` |
| RF-16, RF-17, RF-19 | A4 | `LineEditTest`, `LineChainageTest`, `LineRepositoryTest`, `LineDetailsTest` |
| RF-18 | D6 | `LineHistoryTest`, `LineRepositoryTest` |
| RF-21 | `LineSearch` | `LineSearchTest`, `ObjectListLinesTest` |
| RF-22, RF-23 | D9, A7 | `MapLinesTest`, `LinesLayerTest`, `LineDetailsTest` |
| RF-24, RF-25 | D7, D8, A6 | `LinePointTrashTest`, `ObjectListLinesTest` |
| RF-26 | D12, D13 | `LineBackupTest`, `GeoPackageWriterLinesTest`, `GpkgLinesTest` |
| RNF-T2 | pantalla encendida con línea activa | `CollectorLineViewModelTest` |
| RNF-T6 | Room v30 | `TopoPhoneDatabaseTest`, `LineRepositoryTest` |
| RNF-T7, RNF-T8 | textos y 56 dp | `LinesAccessibilityTest` |
| RNF-T13 y rendimiento | D9 | `LinesPerformanceTest`, pruebas de añadir vértice y de abrir la ficha |

## Cambios al implementar
- **D1, geometría de la línea:** `project_lines` lleva además `geometry` (`LINE` o `POLYGON`, la del código al crearla), porque RF-4 cierra al terminar solo las líneas de un código de polígono y el código guardado por valor (`PointCode`) no sabe su geometría. `ProjectLine.geometry`.
- **D4, `saveMeasured`:** recibe un tercer parámetro `save` con el guardado del punto, que el recolector rellena con el de las fotos (`createWithPhotos`) o con `create`, así punto, fotos y vértice siguen en una sola transacción. El recolector no habla con `LineRepository` sino con `CollectorLines` (con `NONE`, como `CollectorCodeSource`) y `CollectorLineRules` decide si los atributos salen de la línea activa (D11).
- **D5, nombre:** al crear desde puntos, si el usuario no ha escrito el nombre se recalcula `suggestName` al pulsar «Crear línea» con el código y la numeración de ese momento (el propuesto al abrir el asistente salía sin código).
- **D6, historial:** medir, deshacer un vértice, terminar y el cierre automático de un código de polígono al terminar no se anotan. Editar los vértices parte de la lista que se ve en la ficha; un vértice cuyo punto está en la papelera no se conserva al reescribir los vértices (RF-25 solo vale mientras nadie edite la línea).
- **D7, papelera de puntos:** `LineHistoryWriter` (compartido por `RoomLineRepository` y `RoomPointRepository`) escribe `VERTEX_REMOVED` antes de `deletePermanently` y de `purgeTrash`.
- **Lecturas coherentes:** `lines`, `summaries`, `line`, `active`, `history`, `trash` y `vertexPoints` leen una instantánea dentro de una transacción cada vez que cambia alguna de sus tablas (`InvalidationTracker.createFlow`), en vez de combinar consultas independientes que dejaban ver una línea con los vértices o el historial de antes.
- **Ficha de 100.000 vértices:** la ficha lee `vertexPoints` (nombre, Este y Norte de cada vértice con un `JOIN`) en vez de todos los puntos del proyecto, y los puntos del selector solo se cargan al abrirlo; la ficha abre en menos de 1 s.
- **D9, mapa:** `MapLines.scene` escribe el GeoJSON de las líneas visibles en `ProjectViewModel` (hilo de fondo) y `showLines` solo se lo pasa a MapLibre (`LineScene`). Las capas son `topophone-lines-fill`, `topophone-lines` y `topophone-line-labels`, debajo de los puntos; tocar una línea emite `lineClicks`.
- **RF-12, mapa:** el asistente «Línea desde puntos» elige los puntos tocándolos en su lista; elegirlos tocando el mapa no se ha hecho (el RF dice «en la lista o en el mapa»).
- **RF-24, borrar:** la línea se borra desde su ficha («Borrar línea», con confirmación); la lista de objetos permite abrirla y ocultarla. La papelera de líneas es una sección «Líneas» de la papelera del proyecto.
- **RF-20, `ChangeLineCode`:** reutiliza `ChangeCodeUi`, `ChangeCodeActions` y `ChangeCodeDialogs`, con `allowNone = false` (una línea siempre lleva código).
- **RF-26, selección:** con puntos seleccionados el GeoPackage lleva solo las líneas cuyos vértices están todos en la selección.
- **`ObjectLines`:** una interfaz de la app (con `NONE`) para que la lista de objetos, la papelera, la ficha del punto, el mapa y la exportación vean las líneas sin cambiar el constructor de sus tests.
- **Deshacer el único vértice:** la línea queda activa y vacía; el siguiente punto con su código se añade a ella.
- **Tests heredados que cambian:** `CollectorCodeViewModelTest` (el selector del recolector ya ofrece los códigos de línea, S025/RF-1) en dos pruebas; `FakeMapController` y `projectViewModel` ganan líneas.
- **D12, otras specs:** no se han tocado los `spec.md` de S010, S011, S013, S024 ni S053; el glosario y la hoja de ruta sí. La fila de S025 pasa a `implementada` (Room v30).
