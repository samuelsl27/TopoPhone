# Plan técnico — Spec 024

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/024-recolectar-con-codigos/spec.md`, `specs/023-bibliotecas-codigos/plan.md` (modelo de códigos, `CodeKey`, `AttributeValues`, `CodeSearch`, `projectCopy`), `specs/010-*/plan.md` (recolector y `Point`), `specs/011-*/plan.md` (historial de puntos), `specs/012-*/plan.md` (CSV) y `specs/053-*/plan.md` (GeoPackage).

Sin dependencias nuevas, ni Gradle ni Python:
- Los símbolos del mapa se dibujan con bitmaps SDF generados en código (como `stakedIcon` y `slopeIcon`) y el color se pone con `icon-color` desde una propiedad GeoJSON. Alternativa descartada: una biblioteca de iconos o ficheros PNG por color y símbolo (6 símbolos × 12+ colores).
- Los valores de los atributos se validan con `AttributeValues` de S023; no hay parser nuevo.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde viven los códigos creados en el proyecto (RF-5 a RF-7) | Una fila más de `code_libraries` con una columna nueva `project_codes_of` (FK a `projects`, CASCADE, única), que no se lista entre las bibliotecas del usuario ni es la copia vinculada. Reutiliza `library_codes`, `code_attributes`, `attribute_options`, el validador y el editor de S023. Así cambiar o quitar la biblioteca vinculada (S023/RF-21) no borra los códigos creados en el proyecto. **Room v29** | Tablas nuevas para los códigos del proyecto (duplica DAO, validación y mapeos); o meterlos en la copia (el cambio de biblioteca los borraría) |
| D2 | Símbolo (RF-5, RF-17, RF-19) | `CodeSymbol { CIRCLE, SQUARE, TRIANGLE, DIAMOND, CROSS, STAR }` y columna `library_codes.symbol` con valor por defecto `CIRCLE`. Solo el editor de códigos del proyecto lo muestra; el CSV y el editor de S023 no cambian y las bibliotecas siempre dan `CIRCLE` | Tabla aparte de símbolos |
| D3 | Qué guarda el punto (RF-10, RF-12) | En `points` cuatro columnas anulables: `code`, `code_description`, `code_color`, `code_symbol`. Los atributos, en la tabla `point_attributes(point_id, ordinal, name, type, value)`. Valores como texto canónico de S023/D7 (sin redondear). Sin código = columnas `NULL` y sin filas | Una columna JSON (hay que escapar y migrar si cambia) |
| D4 | Historial (RF-12, RF-14) | `point_history` gana las mismas cuatro columnas del código anterior y la tabla `point_history_attributes(history_id, ordinal, name, type, value)`. `PointChange` gana `CODE`; el historial muestra una línea «Código» | Guardar el código nuevo en vez del anterior (el historial de S011 guarda el estado previo) |
| D5 | Códigos recientes (RF-1) | Se calculan en una consulta sobre `points` del proyecto (no en la papelera): los 10 códigos distintos con el `created_at_ms` más reciente. Sin tabla ni preferencia. Un reciente que ya no está entre los del proyecto no se ofrece | Guardarlos en DataStore |
| D6 | Código elegido entre puntos (RF-3) | Vive en el estado del recolector junto a la descripción (`Form` con `SavedStateHandle`), como la descripción de S010/RF-4: se conserva entre puntos mientras el proyecto siga abierto. Se guarda la clave del código, no el objeto | DataStore (sobrevive a cerrar la app: no pedido) |
| D7 | Qué es «cambiar el código» (RF-13 a RF-16) | `PointRepository.changeCode(ids, newCode, values)` en una transacción: para cada punto guarda su estado anterior en el historial y escribe el nuevo código y atributos; si falla uno, no se aplica ninguno. No toca coordenadas ni observación bruta (sin cambiar `edited`, que es solo de coordenadas) | Reutilizar `updateAll` con un `PointChange` genérico (arrastra efectos sobre `edited`) |
| D8 | Conservar atributos al cambiar (RF-14, RF-15) | Función pura `AttributeCarryOver.apply(old, newAttributes)`: conserva el valor de cada atributo con el mismo nombre (clave `CodeKey`) y tipo; el resto toma el valor por defecto; devuelve los valores no vacíos descartados para el aviso | Conservar por posición |
| D9 | Mapa (RF-17, RF-19) | `MapPoints.from(summaries, projectCodes)` resuelve el color y el símbolo por la clave del código contra los códigos vigentes del proyecto; si no está, usa los del punto. Los puntos con símbolo `CIRCLE` siguen en la capa de círculos con el color de una propiedad; el resto va a una capa de símbolos con `icon-color`. Fuera de tolerancia y replanteado conservan su marca | Una capa de símbolos para todo (cambia el rendimiento medido de S010) |
| D10 | Herencia en el replanteo (RF-20) | `StakeTarget` gana `code: PointCode?`, que sale del punto de diseño cuando la fuente es `StakeSource.ProjectPoint`; en `CadElement` y `Alignment` es `null`. Se copia por valor al guardar | Resolver el código al guardar contra la tabla de puntos (otra lectura en la transacción) |
| D11 | Exportación (RF-21) | `ExportColumn.CODE` entra en «Todas las columnas». Los atributos son columnas dinámicas: una por cada par (nombre, tipo) distinto entre los puntos exportados, en orden de primera aparición; si un nombre sale con dos tipos, la cabecera del segundo lleva « (tipo)». En GeoPackage son columnas tipadas (`TEXT`, `INTEGER`, `REAL`; Lista como `TEXT`) | Una columna única con todos los atributos juntos |
| D12 | Copia de seguridad y duplicado de proyecto (RNF-T6) | `DatabaseSnapshot.TABLES` y `BackupRepository.copyProject` incluyen las tablas nuevas; duplicar un proyecto copia también sus códigos creados (D1). Los puntos duplicados llevan su código y atributos | No copiarlos |
| D13 | Cambios en S010, S011, S012, S018 y S053 | No se tocan sus `spec.md`: el comportamiento queda descrito aquí y en los tests, que citan `S024/RF-n`. Sí se actualizan el glosario y la hoja de ruta | — |

## Estructura de módulos
- `core:model`
  - `codes/`: `CodeSymbol`, `SurveyCode.symbol`, `ProjectCodes` (une copia vinculada y códigos del proyecto, solo geometría Punto, orden alfabético; RF-1), `RecentCodes` (ordena y recorta; RF-1), `AttributeCarryOver` (RF-14, RF-15), `AttributeForm` (valores iniciales y validación por atributo; RF-8, RF-9), `DescriptionProposal` (RF-4).
  - `point/`: `PointCode(code, description, color, symbol, attributes: List<PointAttribute>)` y `PointAttribute(name, type, value)`; `Point.code`; `PointSummary` gana `code`, `codeColor`, `codeSymbol`; `PointSnapshot` gana el código (RF-10, RF-12, RF-14). `PointChange.CODE`, `HistoryLine.Code` en `PointHistory`.
  - `PointSearch` busca también por código (RF-18).
- `core:formats` (`csv/`, `gpkg/`): `ExportColumn.CODE`, columnas dinámicas de atributos (RF-21).
- `data`
  - Room v29 y `AutoMigration(28, 29)`; `PointRepository.create` guarda código y atributos con el punto; `changeCode`; `recentCodes(projectId)` (RF-10, RF-13, RF-14, RF-16, RF-1).
  - `CodeLibraryRepository`: `projectCodes(projectId)`, `saveProjectCode`, `deleteProjectCode` (RF-5 a RF-7).
  - `DatabaseSnapshot`, `BackupRepository.copyProject`, `ProjectLibraryLinker` (D12).
- `app`
  - `collector/`: estado (código elegido, recientes, búsqueda, atributos), `MeasureBlocker.ATTRIBUTES`, selector y formulario de atributos (RF-1 a RF-4, RF-8, RF-9, RF-11).
  - `codes/`: `CodeEditorViewModel` parametrizado con destino (biblioteca o códigos del proyecto), con símbolo y geometría fija Punto (RF-5 a RF-7).
  - `points/`: ficha (código y atributos, edición con rastro), «Cambiar código» en ficha y en la selección de la lista, filtro y columna de código (RF-12, RF-13, RF-15, RF-18).
  - `map/`: `MapPoints`, capa de símbolos con bitmaps SDF (RF-17, RF-19).
  - `export/`: columnas de código y atributos en las pantallas existentes.

## Modelo de datos
### Room v29
```
code_libraries      + project_codes_of TEXT NULL FK(projects, CASCADE) UNIQUE   (D1)
library_codes       + symbol TEXT NOT NULL DEFAULT 'CIRCLE'                       (D2)
points              + code TEXT NULL, code_description TEXT NULL, code_color INTEGER NULL, code_symbol TEXT NULL
point_attributes    point_id TEXT FK(points, CASCADE), ordinal INTEGER, name TEXT, type TEXT, value TEXT
                    PRIMARY KEY (point_id, ordinal)
point_history       + code, code_description, code_color, code_symbol (todos NULL)
point_history_attributes  history_id FK(point_history, CASCADE), ordinal, name, type, value, PRIMARY KEY (history_id, ordinal)
```
- `AutoMigration(28, 29)`. Las columnas nuevas son anulables o con valor por defecto; los puntos existentes quedan sin código. La columna única nueva de `code_libraries` se declara con índice; en una migración automática hay que comprobar que Room la admite sin especificación y, si no, se añade un `AutoMigrationSpec`.
- Consultas de bibliotecas (`libraries()`, nombres únicos, `projectCopy`) excluyen las filas con `project_codes_of` (D1).

## Algoritmos
### A1. Códigos del proyecto y selector (RF-1, RF-2)
1. Unir los códigos de la copia vinculada y los del proyecto (D1); quedarse con geometría Punto.
2. Orden alfabético por `CodeKey`. Recientes (D5) delante, en su orden, solo los que sigan existiendo; el resto sin repetirlos.
3. Filtro con `CodeSearch.index` de S023 (sin mayúsculas ni acentos, por código y descripción). «Sin código» es siempre la primera opción.

### A2. Formulario de atributos (RF-8, RF-9)
1. Al elegir un código: un valor por atributo, el por defecto del código; vacío si no tiene.
2. Valor vacío es válido. En otro caso `AttributeValues.canonical(type, raw, decimal)`; `null` = inválido con su motivo. Lista: el valor debe ser una opción.
3. Con algún inválido el recolector no mide (`MeasureBlocker.ATTRIBUTES`). El texto guardado es el canónico (sin redondear).

### A3. Cambiar el código (RF-13 a RF-16)
1. Para cada punto seleccionado: `AttributeCarryOver.apply(valores actuales, atributos del código nuevo)` (D8).
2. Si algún punto descarta un valor no vacío, la pantalla lista los atributos afectados y pide confirmar antes de llamar al repositorio.
3. `changeCode` abre una transacción: por punto, inserta el estado anterior (código y atributos) en el historial y escribe el nuevo; cualquier fallo cancela todo. No se tocan coordenadas ni medida.

### A4. Columnas dinámicas de atributos (RF-21)
1. Recorrer los puntos exportados y reunir los pares (clave del nombre, tipo) en orden de primera aparición.
2. Cabecera = nombre original; si el mismo nombre repite con otro tipo, `nombre (tipo)`.
3. Valor de cada punto = su valor canónico, vacío si no tiene ese atributo; los números se escriben con `Locale.ROOT`.

## Contratos
- `PointRepository`: `create(point)` incluye `point.code`; `changeCode(ids: List<String>, change: CodeChange): CodeChangeResult` con `Changed(count)`, `NotFound`; `recentCodes(projectId): Flow<List<String>>`.
- `CodeLibraryRepository`: `projectCodes(projectId): Flow<List<SurveyCode>>`, `saveProjectCode(projectId, code): CodeSaveResult` (con el conflicto contra la copia vinculada y contra los del proyecto, S023/RF-6), `deleteProjectCode(projectId, codeId)`.
- Textos (es/en en `strings.xml`, prefijos `collector_code_*`, `objects_code_*`, `codes_symbol_*`): «Sin código», «Nuevo código», motivo de cada atributo inválido, aviso de valores que se pierden, «Cambiar código», nombres de los símbolos y confirmaciones de editar y borrar.

## Estrategia de tests
- Unitarios JVM (`core:model`): `ProjectCodesTest`, `RecentCodesTest`, `AttributeFormTest`, `AttributeCarryOverTest`, `DescriptionProposalTest`, `PointHistoryCodeTest`, `PointSearchCodeTest`; el selector con 2.000 códigos filtra en menos de 16 ms de CPU.
- Unitarios JVM (`core:formats`): `CsvExportCodeTest`, `GpkgCodeAttributesTest` (nombre repetido con dos tipos, números con `Locale.ROOT`).
- Robolectric (`data`): `TopoPhoneDatabaseTest` 28 a 29, `PointCodeRepositoryTest` (guardar punto y atributos en una transacción, cambiar el código de una selección entero o nada, historial, coordenadas intactas), `ProjectCodeRepositoryTest` (conflictos, borrar la biblioteca no toca puntos, cambiar de biblioteca vinculada conserva los códigos del proyecto), `CodeBackupTest`.
- Robolectric (`app`): `CollectorCodeViewModelTest`, `CollectorCodeScreenTest`, `PointDetailsCodeTest`, `ChangeCodeTest`, `ObjectListCodeTest`, `MapPointsCodeTest`, `PointsSymbolLayerTest`; los ViewModels guardados y su `viewModelScope` cancelado antes de cerrar la base; nombres cortos con base de fichero en Windows.
- Rendimiento: `PointsPerformanceTest` con 10.000 puntos con código (< 2 s) y el selector con 2.000 códigos.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A1, D5 | `ProjectCodesTest`, `RecentCodesTest`, `CollectorCodeViewModelTest` |
| RF-3 | D6 | `CollectorCodeViewModelTest` |
| RF-4 | `DescriptionProposal` | `DescriptionProposalTest`, `CollectorCodeViewModelTest` |
| RF-5, RF-6, RF-7 | D1, D2 | `ProjectCodeRepositoryTest`, `CodeEditorViewModelTest` |
| RF-8, RF-9 | A2 | `AttributeFormTest`, `CollectorCodeScreenTest` |
| RF-10 | D3 | `PointCodeRepositoryTest`, `CollectorCodeViewModelTest` |
| RF-11 | `MeasureBlocker` | `CollectorCodeViewModelTest` |
| RF-12 | D4 | `PointHistoryCodeTest`, `PointDetailsCodeTest` |
| RF-13 a RF-16 | A3, D7, D8 | `AttributeCarryOverTest`, `PointCodeRepositoryTest`, `ChangeCodeTest` |
| RF-17, RF-19 | D9 | `MapPointsCodeTest`, `PointsSymbolLayerTest` |
| RF-18 | `PointSearch`, lista | `PointSearchCodeTest`, `ObjectListCodeTest` |
| RF-20 | D10 | `CollectorCodeViewModelTest` (replanteo) |
| RF-21 | D11, A4 | `CsvExportCodeTest`, `GpkgCodeAttributesTest` |
| RNF-T6 | Room v29, D12 | `TopoPhoneDatabaseTest`, `CodeBackupTest` |

## Cambios al implementar
- **D12, duplicar un proyecto:** el duplicado de S006 no copia los puntos, así que solo viajan sus códigos creados (`ProjectLibraryLinker.copyBetweenProjects`); los puntos con código viajan en la copia y en los paquetes (`DatabaseSnapshot.TABLES`, `BackupRepository.copyProject`).
- **D7, `changeCode`:** recibe el `SurveyCode` nuevo (o null) y calcula el traspaso de atributos dentro de la transacción, punto a punto, con `AttributeCarryOver`; solo escribe las columnas del código.
- **D1, filtros:** las consultas de bibliotecas del usuario excluyen las filas con `project_codes_of`; la copia y los paquetes usan `? IN (project_id, project_codes_of)`.
- **D9, iconos del mapa:** en lugar de bitmaps SDF con `icon-color`, cada pareja de símbolo y color se dibuja como un bitmap propio (`SymbolShapes.iconId`) que se añade al estilo la primera vez que aparece; así los bordes y el color salen siempre bien y el número de parejas es pequeño. Las formas son polígonos del cuadro unidad (`SymbolShapes`). Fuera de tolerancia el icono lleva el color de su aviso. `CodeStyle` (color y símbolo) vive en `core:model`.
- **D9, color vigente:** `ProjectViewModel` calcula los estilos de los códigos del proyecto (`CollectorCodeSource.available`) y `MapPoints.from(points, styles)` los aplica por clave; los códigos de otra geometría no están en esa lista, así que un punto cuyo código es ahora de Línea se pinta con lo que guardó.
- **D5/D6, `CollectorCodes`:** el estado del código del recolector (selector, recientes, atributos) vive en `CollectorCodes`, con su propia `StateFlow` y una `CollectorCodeSource` inyectable (`RoomCollectorCodeSource`); `CollectorUiState.code` lo recoge.
- **RF-5 a RF-7, volver del editor:** el editor de códigos del proyecto (`CodeEditorViewModel.openProject`) avisa al recolector por un singleton, `CollectorCodeChoice`, con la clave del código creado o del existente que se prefirió (RF-6); el recolector lo elige cuando aparece en su lista. Editar y borrar un código propio se hace desde el botón «Editar» del selector.
- **RF-12, dónde se corrigen los atributos:** la ficha muestra el código y los valores (`CodeCard`); se corrigen en el formulario de edición del punto (`PointFormViewModel`), con el mismo guardado y rastro que el nombre. Como el punto guarda los valores por valor, una Lista se acepta con cualquier texto (`AttributeForm.validateValues`).
- **RF-13 a RF-15, `ChangeCode`:** un controlador común a la ficha y a la lista (`ChangeCode`) elige el código (reutiliza `CollectorCodes` y el selector del recolector), calcula los valores que se pierden y pide confirmar antes de llamar a `changeCode`. Tras aplicarlo a una selección se vacía la selección.
- **RF-18, filtro:** `PointCodeFilter` (modelo) da la clave de filtro y los códigos presentes; la lista muestra un chip por código, «Todos los códigos» y «Sin código». La búsqueda ya encuentra por código (`PointSearch`).
- **D11, columnas:** `ExportColumn.CODE` va siempre al final de «Todas las columnas», aunque ningún punto lleve código (cabecera estable). En PENZD y PNEZD no hay código. En el personalizado se elige como cualquier columna y los atributos no se escriben. En GeoPackage la capa gana `Code` y una columna por atributo; si un nombre choca con otra columna (sin distinguir mayúsculas) recibe « (atributo)» o « (atributo N)».
- **Tests heredados que cambian por la columna `Code`:** `CsvExportTest` (cabecera, fila, orden del base shift y del replanteo), `ExportViewModelTest`, `GpkgPointAttributesTest` (el punto completo lleva código), los dorados de CSV y de GeoPackage (`fixtures/formatos/`, regenerados con `TOPOPHONE_REGENERAR_DORADOS=1`) y `tools/formatos/test_csv_exportado.py` (58 columnas, `Code` la última).
- **D13, otras specs:** no se han tocado los `spec.md` de S010, S011, S012, S018 ni S053; el glosario y la hoja de ruta sí. La fila de S024 pasa a `implementada` (Room v29).
