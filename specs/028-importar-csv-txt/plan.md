# Plan técnico — Spec 028

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/028-importar-csv-txt/spec.md` y `specs/011-objetos-y-puntos-manuales/` (los puntos importados son puntos manuales con otro origen). Sin código.

Sin dependencias nuevas (Gradle ni Python). El lector de texto delimitado es propio: Apache Commons CSV u OpenCSV traerían una librería para una gramática de unas 150 líneas, y ninguna da el número de línea de cada fila ni deja las filas mal formadas como errores sin abortar. Las codificaciones salen de `java.nio.charset` (UTF-8 y Windows-1252 vienen con la JVM y con Android).

## Estructura de módulos
- `core:model`:
  - `PointOrigin.IMPORTED`, `Point.importId` y `PointOrigin.hasMeasurement` sin el importado (RF-31, RF-33);
  - los tipos de la importación, con poca lógica: `ImportEncoding`, `ImportSeparator` y `ImportDecimal` (propios, porque `core:formats` depende de `core:model` y no al revés), `ColumnRole`, `ColumnMapping`, `ImportCoordinates` (SC del proyecto o geográficas, con el tipo de altura), `DuplicatePolicy`, `ImportOptions` con la unidad (RF-4 a RF-20), y los resultados `ImportRowError`, `ImportErrorReason`, `ImportCounts`, `ImportPreviewRow` (RF-21, RF-23, RF-28).
- `core:formats`:
  - `TextDecoding`: UTF-8 con y sin BOM o Windows-1252 y su detección (A1; RF-4);
  - `DelimitedReader`: lectura por flujo de filas con su número de línea, con comillas, y la detección del separador (A1, A2; RF-2, RF-3, RF-5, RF-6);
  - `DecimalDetector` y `ImportNumber`: el signo decimal y la lectura de un número con el signo elegido (A2; RF-7, RF-8, RF-23);
  - `HeaderDetector` y `ColumnSuggester`: cabecera y asignación propuesta, con los nombres de las columnas de S012 (A3; RF-9, RF-10, RF-11).
- `core:survey`:
  - `PointImporter`: de las filas y las opciones a las filas válidas, repetidas y erróneas, y a los puntos (A4, A5, A6; RF-12 a RF-27);
  - `ImportReportText`: el informe de errores como texto (RF-24).
- `core:geodesy`: sin cambios (se reutiliza `ManualCoordinates` de `core:survey`).
- `data`:
  - columna `import_id` en `points`, índice y migración 11 → 12 (RNF-T6; RF-29);
  - `PointRepository.createAll(points)` en una sola transacción y `PointRepository.trashImport(importId)` (A7; RF-25, RF-26, RF-29).
- `app`:
  - `PointImportViewModel`: lee el fichero, lleva las opciones y el estado del asistente y lanza el análisis y la importación (A6; RF-1 a RF-30, RF-34);
  - `PointImportScreen`: el asistente en una sola pantalla con secciones, la vista previa, el informe de errores y el resultado con "Deshacer importación" (RF-4 a RF-24, RF-28, RF-29);
  - entradas "Importar CSV/TXT" en el menú de cada proyecto y en el de la lista de objetos, y el selector de ficheros (RF-1);
  - el origen "Importado" en la lista, la ficha y el mapa (RF-31).
- `tools`:
  - `tools/formatos/generar_importacion.py`: los ficheros de ejemplo de `fixtures/formatos/csv-importacion/` y lo esperado de cada uno, con las coordenadas proyectadas y geográficas calculadas con pyproj (constitución nº 2).

## Modelo de datos

### En el punto (Room v12)
```
points  +  import_id TEXT NULL     // la importación de la que viene; NULL en los demás
        INDEX(import_id)
```
- `origin = 'IMPORTED'` en un punto importado. Es un punto manual para todo lo demás: `typed` lleva lo escrito en el fichero, `measurement` es nulo y `edited` es falso (S011).
- `AutoMigration(11, 12)` sin especificación: solo una columna opcional y un índice.
- Una importación no tiene tabla propia: el identificador (un UUID por importación) basta para deshacerla, y los puntos que se borraron después no cuentan.
- S017b (fotos) pasa de la versión 12 a la 13 cuando se retome; su `plan.md` aún dice 11.

### Opciones de la importación
Viven en el `ViewModel` mientras el asistente está abierto; no se guardan entre importaciones (la asignación como plantilla está fuera de alcance).
```
ImportOptions(
  encoding: ImportEncoding { UTF8, WINDOWS_1252 },
  separator: ColumnSeparator + { SPACES },       // los de S012 y el de espacios
  decimal: DecimalSeparator { POINT, COMMA },
  hasHeader: Boolean,
  mapping: ColumnMapping,                         // columna → ColumnRole
  coordinates: ImportCoordinates { PROJECT_CRS | GEOGRAPHIC(heightKind) },
  unit: LinearUnit,                               // la del proyecto por defecto
  zeroAsNoHeight: Boolean = false,
  duplicates: DuplicatePolicy { SKIP, RENAME, CANCEL } = SKIP,
)
```

## Algoritmos

### A1. Leer el fichero (RF-2 a RF-6)
- El fichero se abre con `ContentResolver` cada vez que hace falta, sin copiarlo (RNF-T5): el selector da un `Uri` que sigue siendo legible mientras el asistente está abierto.
- **Codificación**: se leen los primeros 64 kB como bytes; si empiezan por el BOM de UTF-8, o todos los bytes forman UTF-8 válido, es `UTF8` (y se quita el BOM); si no, `WINDOWS_1252`. Un byte nulo en esa muestra lo da por binario (RF-2).
- **Filas**: `DelimitedReader` lee carácter a carácter, con un estado de "dentro de comillas": una comilla doble abre o cierra el texto, dos seguidas dentro son una comilla y el separador y el salto de línea dentro de comillas no cortan (como S012/RF-15). Cada fila lleva su número de línea del fichero; las líneas vacías se saltan y la última puede no acabar en salto (RF-21). Acepta CRLF, LF y CR.
- **Límites**: más de 20 MB o más de 50.000 filas dan el error de RF-3 antes de abrir el asistente: el tamaño sale de `OpenableColumns.SIZE` y de la lectura, y las filas se cuentan en el primer recorrido.
- **Sin dos pasadas innecesarias**: el primer recorrido da la codificación, el separador, la cabecera, el número de columnas y el signo decimal; cada cambio de opción relee el fichero (A6).

### A2. Separador, signo decimal y cabecera (RF-5, RF-7, RF-9)
- **Separador**: sobre las 20 primeras filas no vacías, el candidato entre `,` `;` tabulador y espacio que da el mismo número de columnas (≥ 2) en más filas, contando fuera de comillas; empata a favor del orden `;` tabulador `,` espacio, porque la coma suele ser decimal. Los espacios juntos cuentan como uno.
- **Decimal**: se miran las celdas que parecen número en las columnas que no son la primera: si alguna lleva `,` entre dígitos y ninguna `.`, es la coma; si no, el punto. Con la coma como separador de columnas, el decimal es el punto y la combinación inversa no se permite (RF-8).
- **Número** (`ImportNumber.parse(text, decimal)`): signo opcional, dígitos y un solo signo decimal; no admite separador de miles, notación científica ni letras (RF-23: "1.234,56" no es un número con la coma decimal).
- **Cabecera**: la primera fila es cabecera si ninguna de las celdas que serían coordenadas (la segunda y la tercera columnas, o las asignadas) es un número (RF-9).

### A3. Asignación propuesta (RF-10, RF-11)
- Con cabecera: cada celda se normaliza (minúsculas, sin acentos, sin espacios ni signos) y se busca en la tabla de nombres: nombre (`name`, `nombre`, `point`, `punto`, `id`…), Este (`easting`, `east`, `este`, `x`…), Norte (`northing`, `north`, `norte`, `y`…), altura (`elevation`, `height`, `altura`, `altitud`, `cota`, `z`, `h`…), latitud, longitud y descripción (`description`, `descripcion`, `desc`, `code`, `codigo`, `notas`…).
- Cada papel se asigna a la primera columna que lo cumple y no se repite. Con las columnas de S012 (`Easting` y `Elevation` y también `Latitude`, `Ellipsoidal height`…) gana la pareja del SC elegido: con el SC del proyecto, Este y Norte con `Elevation`; con geográficas, latitud y longitud con `Ellipsoidal height`.
- Sin cabecera o sin reconocer nada: PENZD por orden (nombre, Este, Norte, altura, descripción) o, en geográficas, nombre, latitud, longitud, altura y descripción. PNEZD y el personalizado se eligen a mano. El resto de columnas quedan en "Ignorar".
- La asignación es válida si tiene un nombre y dos coordenadas del SC elegido y ningún papel repetido (RF-11).

### A4. De una fila a un punto (RF-12 a RF-16, RF-20, RF-23, RF-27)
1. Con el mapa de columnas, se toman el nombre (recortado, sin cambiar los ceros a la izquierda), la descripción (recortada) y los tres números.
2. Un nombre vacío, una columna que falta (fila corta) o una coordenada que no es un número dan el error `MISSING_NAME`, `MISSING_COLUMNS` o `NOT_A_NUMBER`.
3. Este, Norte y altura se pasan a metros con la unidad elegida (RNF-T9). Las coordenadas geográficas ya son grados.
4. La altura vacía o no numérica es "sin altura" (RF-15); con `zeroAsNoHeight`, un 0 también (RF-16).
5. Se llama a `ManualCoordinates.compute(typed, crs, heightSystem)` con `EntryForm.PROJECT_CRS` o `GEOGRAPHIC` y el `HeightKind` elegido: es la misma ruta que un punto manual (S011/RF-17 a RF-20), con su ámbito del SC y del geoide. Un `Rejected` da `OUTSIDE_AREA`, `OUTSIDE_GEOID` u `OUTSIDE_ED50_GRID` (RF-23).
6. El punto sale como `Point(origin = IMPORTED, typed = lo escrito sin redondear, coordinates = las calculadas, createdAtMs = ahora, importId)`, sin jalón ni calidad. En ED50 solo se admite altitud (RF-13).

### A5. Nombres repetidos (RF-17 a RF-19)
- El conjunto de nombres ocupados empieza con los del proyecto (`names(projectId)`, la papelera ya queda fuera) y crece con cada punto que entra.
- Un nombre ocupado es un repetido: con `SKIP` la fila no entra y se cuenta; con `RENAME` entra con `PointNames.nextFree(nombre, ocupados)` (S011/RF-16) y se cuenta; con `CANCEL` el análisis lo marca y "Importar" queda desactivado hasta cambiar la opción (RF-17).
- La comparación es exacta (como el índice único de la base de datos): "P1" y "p1" son distintos, igual que en S011.
- Un punto que ya existe no se toca: no hay ninguna operación de escritura sobre él (RF-19).

### A6. Analizar e importar en dos pasadas (RF-21, RF-22, RF-25, RF-26, RF-30)
- **Análisis** (con el asistente abierto): recorre el fichero por flujo, valida cada fila con A4 y A5 y guarda solo los recuentos (válidas, repetidas, renombradas, con error), las 20 primeras filas para la vista previa y los primeros 500 errores con su línea y su motivo. No guarda los puntos. Cada cambio de opción cancela el análisis anterior y lanza otro en `Dispatchers.Default`, con un retraso de 150 ms para que teclear no lance uno por letra (RNF-T13).
- **Importación**: recorre el fichero otra vez con las mismas opciones, ahora reuniendo los `Point` de las filas válidas, y los escribe con `createAll` en una sola transacción (A7). Un fallo no deja ninguno (RF-26). Salir del asistente antes no escribe nada (RF-30).
- El informe completo de errores (RF-24) también relee el fichero: la lista del asistente llega a 500 y el fichero de texto lleva todos.
- El resultado (RF-28) usa los recuentos de la propia importación, no los del análisis, por si el fichero cambió mientras tanto.

### A7. Escritura y deshacer (RF-25, RF-26, RF-29)
- `createAll(points)`: una transacción de Room con `@Insert` de la lista (`OnConflictStrategy.ABORT`); si el índice único de nombres falla (una carrera con otro punto creado a la vez), se revierte todo y devuelve `DuplicateName`. Los puntos importados no llevan frases NMEA, por lo que no hay tramos aparte.
- `trashImport(importId)`: mueve a la papelera con la misma operación que `moveToTrash` (S011/RF-22) los puntos activos con ese `import_id`, en una transacción, y devuelve cuántos. Los que ya estaban en la papelera o borrados no cuentan (RF-29).

## Contratos

### Estado del asistente (`PointImportUiState`)
`file` (nombre, tamaño), `options: ImportOptions`, `columnCount`, `headerCells`, `preview: List<ImportPreviewRow>`, `counts: ImportCounts`, `errors: List<ImportRowError>` (hasta 500) y su total, `analyzing`, `canImport`, `fileError` (RF-2, RF-3) y `result: ImportResult?` (RF-28), con el recuento final y `canUndo`.

### Pantallas
- **Asistente**, una pantalla con secciones: el fichero; "Lectura" (codificación, separador, signo decimal, cabecera); "Columnas" (un selector de papel por columna, PENZD y PNEZD a un toque); "Coordenadas" (SC del proyecto o geográficas, altura, unidad); "Opciones" (0 como sin altura, nombres repetidos); "Vista previa" (20 filas y los recuentos con icono y texto); "Errores" (la lista con "Guardar" y "Compartir"); y "Importar" abajo.
- **Resultado**: los recuentos, "Ver informe" y "Deshacer importación" con su confirmación.
- **Entradas**: "Importar CSV/TXT" en el menú del proyecto y en el de la lista de objetos; el selector es `OpenDocument` con cualquier tipo y la validación por contenido (RF-2).
- **Origen "Importado"** en la lista de objetos, la ficha y la columna Origin del CSV (`Imported`, S012/RF-6).

### Textos nuevos (en `strings.xml`, es y en)
Importar CSV/TXT, Fichero, Lectura, Codificación, Separador, Signo decimal, Primera fila de nombres, Columnas, Nombre, Este, Norte, Latitud, Longitud, Altura, Descripción, Ignorar, "Sistema de coordenadas", "Altitud o elipsoidal", Unidad, "Tratar 0 como sin altura", "Nombres repetidos", Saltar, Renombrar, "Cancelar la importación", "Vista previa", "%d válidas · %d repetidas · %d con error", los motivos de error, "Guardar informe", Importar, "Importados %d · saltados %d · renombrados %d · con error %d", "Deshacer importación", "Importación deshecha" y "Importado" (origen).

### Accesibilidad (RNF-T8)
- Cada fila de la vista previa y de los errores lleva su estado con icono y texto; TalkBack lee los recuentos al cambiar, sin anunciar cada refresco; los selectores de columna y las casillas llevan 56 dp y su etiqueta.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Lector de texto delimitado propio en `core:formats` | RNF-T14 y regla de dependencias: sin librería para una gramática pequeña; da la línea de cada fila y no se rompe con una fila mala | Apache Commons CSV u OpenCSV |
| El análisis no guarda los puntos ni las filas | RNF de la spec: lectura por flujo; solo recuentos, 20 filas y 500 errores | Cargar las 50.000 filas en memoria para recalcular rápido |
| Cada cambio de opción relee el fichero | Es la forma de no retener el fichero; 50.000 filas en menos de 1 s (RNF) | Una caché de celdas entre cambios |
| La conversión de cada fila pasa por `ManualCoordinates` | Constitución nº 2: una sola ruta geodésica probada para los puntos escritos (S011) | Una conversión propia para la importación |
| `import_id` en `points` y no una tabla de importaciones | Solo hace falta saber qué puntos vinieron juntos | Tabla `imports` con su fecha y su fichero |
| "Deshacer" manda a la papelera y no borra | Constitución nº 1: nada se pierde sin rastro y se recupera 30 días (S011) | Borrar definitivamente los importados |
| `createAll` es todo o nada | RF-25, RF-26 y RNF-T6: una importación no se queda a medias | Insertar punto a punto |
| El origen `IMPORTED` es nuevo y no una marca en los manuales | RF-31 y RF-32: se ve y se exporta distinto, y la importación se puede reconocer | Un punto manual con una descripción especial |
| Los espacios juntos cuentan como un solo separador | Los TXT de estaciones totales alinean con espacios | Un espacio por columna vacía |
| El nombre se compara exacto, distinguiendo mayúsculas | Es lo que impone el índice único de la base de datos (S011) | Compararlos sin distinguir mayúsculas |
| No se guarda copia del fichero (excepción de RNF-T5) | Los puntos ya llevan lo escrito y sus coordenadas | Copiarlo al almacenamiento del proyecto |
| `AutoMigration(11, 12)` sin especificación | Solo una columna opcional y un índice | `RoomDatabase.Migration` a mano |

## Estrategia de tests
- **Unitarios JVM**:
  - `TextDecodingTest`: UTF-8 con y sin BOM, Windows-1252 con acentos, binario, vacío y la muestra de 64 kB cortada en mitad de un carácter (RF-2, RF-4).
  - `DelimitedReaderTest`: los cuatro separadores, comillas con el separador y con saltos dentro, CRLF, LF y CR, líneas vacías, última línea sin salto, número de línea tras un texto con saltos y los límites de tamaño y de filas (RF-3, RF-5, RF-6).
  - `DecimalDetectorTest` y `ImportNumberTest`: punto y coma, miles, notación científica, letras, signo y la combinación de coma con coma (RF-7, RF-8, RF-23).
  - `HeaderDetectorTest` y `ColumnSuggesterTest`: nombres en es y en, las columnas de S012, PENZD sin cabecera, papeles repetidos, sin nombre y las dos parejas de coordenadas (RF-9 a RF-11).
  - `PointImporterTest`: unidades m, ft y usft, proyectadas y geográficas en ETRS89, REGCAN95 y ED50 frente a `fixtures/formatos/csv-importacion/` y pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm), altura vacía y 0, fuera del ámbito y del geoide, nombre vacío, fila corta y los tres modos de repetidos (RF-12 a RF-20, RF-23).
  - `ImportReportTextTest` (RF-24).
- **`data`**:
  - `TopoPhoneDatabaseTest` 11 → 12: los puntos conservan sus datos y `import_id` queda a NULL.
  - `PointRepositoryImportTest`: `createAll` guarda todos o ninguno ante un nombre repetido, conserva sin redondear lo escrito, `trashImport` solo manda los activos de esa importación y no toca los demás (RF-25, RF-26, RF-29).
- **Robolectric (`app`)**:
  - `PointImportViewModelTest` con ficheros de `fixtures/` leídos con un `ContentResolver` falso: fichero vacío o grande, detección y cambios de cada opción, vista previa y recuentos, informe de errores, importar con repetidos de cada tipo, fallo de escritura, salir sin importar, deshacer con algún punto ya borrado y el log (RF-1 a RF-30, RF-34).
  - `PointImportScreenTest`: cada sección, el estado con icono y texto, "Importar" desactivado con la coma de separador y de decimal o con `CANCEL` ante un repetido, el resultado y "Deshacer importación" (RF-4 a RF-24, RF-28, RF-29).
  - `PointImportEntriesTest`: el acceso desde el menú del proyecto y desde el de la lista de objetos (RF-1).
  - `ImportedPointsTest`: el origen "Importado" en la lista, la ficha y el mapa; editar, duplicar, borrar y replantear uno; `Imported` en el CSV de puntos (RF-31 a RF-33).
- **`tools`**: tests de `generar_importacion.py` (que los ficheros y lo esperado guardados son los que genera el script, con pyproj).
- **Rendimiento**: `PointImportPerformanceTest`: 50.000 filas leídas y validadas en menos de 3 s, un cambio de opción en menos de 1 s y 10.000 puntos importados en menos de 5 s (RNF).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, la puerta G2b con puntos de un CSV replanteados.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | Entradas, A1 | `PointImportEntriesTest`, `PointImportViewModelTest` |
| RF-2 | A1 | `TextDecodingTest`, `PointImportViewModelTest` |
| RF-3 | A1 | `DelimitedReaderTest`, `PointImportViewModelTest` |
| RF-4 | A1 | `TextDecodingTest`, `PointImportViewModelTest`, `PointImportScreenTest` |
| RF-5 | A1, A2 | `DelimitedReaderTest`, `PointImportScreenTest` |
| RF-6 | A1 | `DelimitedReaderTest` |
| RF-7 | A2 | `DecimalDetectorTest`, `PointImportScreenTest` |
| RF-8 | A2 | `ImportNumberTest`, `PointImportScreenTest` |
| RF-9 | A2 | `HeaderDetectorTest`, `PointImportViewModelTest` |
| RF-10 | A3 | `ColumnSuggesterTest`, `PointImportViewModelTest` |
| RF-11 | A3 | `ColumnSuggesterTest`, `PointImportScreenTest` |
| RF-12 | A4 | `PointImporterTest`, `PointImportScreenTest` |
| RF-13 | A4 | `PointImporterTest`, `PointImportScreenTest` |
| RF-14 | A4 | `PointImporterTest`, `PointImportScreenTest` |
| RF-15 | A4 | `PointImporterTest` |
| RF-16 | A4 | `PointImporterTest`, `PointImportScreenTest` |
| RF-17 | A5 | `PointImporterTest`, `PointImportViewModelTest`, `PointImportScreenTest` |
| RF-18 | A5 | `PointImporterTest`, `PointImportViewModelTest` |
| RF-19 | A5, A7 | `PointImporterTest`, `PointRepositoryImportTest` |
| RF-20 | A4 | `PointImporterTest` |
| RF-21 | A6 | `PointImportViewModelTest`, `PointImportScreenTest` |
| RF-22 | A6 | `PointImportViewModelTest` |
| RF-23 | A2, A4 | `ImportNumberTest`, `PointImporterTest` |
| RF-24 | A6 | `ImportReportTextTest`, `PointImportScreenTest`, `PointImportViewModelTest` |
| RF-25 | A6, A7 | `PointRepositoryImportTest`, `PointImportViewModelTest` |
| RF-26 | A7 | `PointRepositoryImportTest`, `PointImportViewModelTest` |
| RF-27 | A4 | `PointImporterTest`, `PointRepositoryImportTest` |
| RF-28 | A6 | `PointImportViewModelTest`, `PointImportScreenTest` |
| RF-29 | A7 | `PointRepositoryImportTest`, `PointImportViewModelTest`, `PointImportScreenTest` |
| RF-30 | A6 | `PointImportViewModelTest` |
| RF-31 | Origen | `ImportedPointsTest` |
| RF-32 | Origen | `CsvExportTest`, `ImportedPointsTest` |
| RF-33 | Origen | `ImportedPointsTest` |
| RF-34 | `PointImportViewModel` | `PointImportViewModelTest` |

## Pendiente para otras specs
- S017b (fotos) pasa a la versión 13 de la base de datos (`AutoMigration(12, 13)` y `TopoPhoneDatabaseTest` 12 → 13). Hay que actualizar el aviso de su `plan.md`, lo que requiere permiso para tocar `specs/`.
