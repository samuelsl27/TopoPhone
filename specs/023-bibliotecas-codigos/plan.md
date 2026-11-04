# Plan técnico — Spec 023

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/023-bibliotecas-codigos/spec.md`, `specs/028-importar-csv-txt/plan.md` (`TextDecoding`, `DelimitedReader`, `DecimalDetector`, `ImportNumber`), `specs/013-copia-seguridad-local/plan.md` y `BackupRepository.copyProject` (restauración y paquetes), `core/model/.../ProjectNames` (clave sin mayúsculas ni acentos).

Sin dependencias nuevas, ni Gradle ni Python:
- La comparación sin mayúsculas ni acentos usa `java.text.Normalizer` (JDK), como `ProjectNames.key`. Alternativa descartada: ICU4J (+10 MB para lo que ya hace el JDK).
- El CSV se lee con `DelimitedReader` y `TextDecoding` de S028 y se escribe con las reglas de comillas de S012/RF-15. Alternativa descartada: una biblioteca de CSV (S028 ya decidió no añadirla).
- Los ficheros de prueba (`fixtures/codigos/`) se escriben a mano: no hay cálculo geodésico, así que no hay referencia independiente que generar (la constitución nº 2 no aplica).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde viven las bibliotecas del usuario y la copia del proyecto (RF-1, RF-20, RF-22) | Las mismas tablas para las dos: una fila de `code_libraries` con `project_id` NULL es una biblioteca del usuario, y con `project_id` es la copia de ese proyecto (como mucho una por proyecto). La copia es una fila más, con sus códigos y atributos, y se borra en cascada con el proyecto. **Room v28** | Tablas distintas para la copia (duplica DAO, validación y pantallas) |
| D2 | Atributos y opciones (RF-9, RF-10) | Cuatro tablas: `code_libraries`, `library_codes`, `code_attributes` y `attribute_options`, sin textos codificados. Los códigos se ordenan por su clave; los atributos y las opciones, por su posición | Una columna JSON o un texto con separador (hay que escapar y migrar si cambia) |
| D3 | Bibliotecas por defecto (RF-3) | No están en Room: son datos de la app (`DefaultCodeLibraries`, con textos en `strings.xml` es/en y los códigos tal cual). Ids fijos `default:general` y `default:urbanizacion`. Duplicar o vincular una las copia a Room con las descripciones del idioma de ese momento | Insertarlas en Room al instalar (hay que migrarlas si cambia el contenido y protegerlas del borrado) |
| D4 | Vincular a un proyecto (RF-19, RF-21) | `ProjectDraft` gana `libraryChoice` (Mantener, Ninguna o Biblioteca(id)); `RoomProjectRepository.create` y `update` hacen el cambio dentro de su transacción: borran la copia anterior y copian la nueva. Mantener es el valor de `toDraft()`, así que los demás usos no cambian. La confirmación de RF-21 es de la pantalla | Una llamada aparte tras crear el proyecto (si falla en medio, el proyecto queda sin biblioteca) |
| D5 | Duplicar un proyecto (RF-20) | La copia de biblioteca viaja con el duplicado (el proyecto «la lleva consigo»). Los ejes y superficies no se duplican hoy; la biblioteca sí | No copiarla (el duplicado quedaría sin códigos) |
| D6 | Biblioteca de los proyectos nuevos (RF-19) | `ProjectDefaults.codeLibraryId` en DataStore, `null` por defecto. Si esa biblioteca ya no existe, se trata como ninguna | Una tabla de ajustes |
| D7 | Valor por defecto de un atributo (RF-9, RF-18) | Se guarda como texto canónico: Texto tal cual; Entero con `toLong`; Número con `Double.toString` (ida y vuelta exacta, sin redondear) en `Locale.ROOT`. El usuario escribe en su configuración regional | Guardar Double/Long en columnas aparte |
| D8 | Opciones con la barra vertical en el CSV (RF-13, RF-18) | En el CSV las opciones van separadas por `\|`; una barra vertical dentro de una opción se escribe precedida de una barra invertida, y la barra invertida se duplica. La app no prohíbe ningún carácter (ida y vuelta de RF-18) | Prohibir la barra vertical en las opciones (comportamiento nuevo no pedido por la spec) |
| D9 | Duplicados dentro del fichero (RF-16) | «Omitir» conserva la primera aparición y lo que ya había; «Reemplazar» deja la última aparición y sustituye lo que ya había (código, descripción, geometría, color y atributos) | Fallar el fichero entero |
| D10 | Nombre de la biblioteca nueva al importar (RF-14) | El nombre del fichero sin extensión; si ya existe (también entre las por defecto), se le añade « (2)», « (3)»… | Pedir otro nombre antes de importar |
| D11 | Color (RF-8, RF-13) | Se guarda `0xRRGGBB`. La paleta ofrece 12 colores; por defecto Punto, Línea y Polígono llevan uno propio. En el CSV vale cualquier `#RRGGBB` (sin distinguir mayúsculas); vacío toma el de la geometría; otra cosa es fila inválida (RF-15) | Solo los colores de la paleta |
| D12 | Ficheros grandes (RF-12, RNF) | Mismos límites que S028 (20 MB, 50.000 filas) y el mismo aviso; la importación entera en una transacción y sin dejar nada si falla | Límites propios |
| D13 | Cabecera del CSV (RF-13) | Se reconoce sin distinguir mayúsculas; las columnas `Attribute_N_*` pueden saltarse números; un atributo sin `Name` pero con otras columnas rellenas es fila inválida. Las columnas desconocidas se ignoran | Cabecera exacta |
| D14 | Cambios de S006 y S013 (spec, «Cambios en documentos anteriores») | No toco sus `spec.md` (hace falta tu permiso); el comportamiento queda descrito aquí y en los tests, que citan `S023/RF-n`. Sí actualizo el glosario | — |

## Estructura de módulos
- `core:model` (`codes/`)
  - `CodeGeometry { POINT, LINE, POLYGON }`, `AttributeType { TEXT, INTEGER, NUMBER, LIST }`, `CodeAttribute(name, type, defaultValue, options)`, `SurveyCode(id, code, description, geometry, color, attributes)`, `CodeLibrary(id, name, isDefault, codes)`, `LibraryChoice`, `CodePalette` (RF-5, RF-8, RF-9).
  - `CodeKey` (clave sin mayúsculas, acentos ni espacios en los extremos), `CodeValidator` (vacío, más de 32 caracteres, salto de línea, duplicado; atributos: hasta 100, nombre distinto, valor por defecto válido para el tipo, Lista de 1 a 50 opciones distintas) y `LibraryNames` (RF-2, RF-6, RF-7, RF-9).
  - `CodeSearch` (RF-11) y `CodeMerge` (qué entra, qué se omite o reemplaza; RF-14 a RF-16).
  - `ProjectDraft.libraryChoice` y `ProjectDefaults.codeLibraryId` (RF-19).
- `core:formats` (`codes/`): `CodeLibraryCsvWriter` y `CodeLibraryCsvReader` (cabecera, filas válidas, filas inválidas con línea y motivo; RF-13, RF-15, RF-17, RF-18) sobre `DelimitedReader`.
- `data` (`codes/`)
  - Room v28 y `CodeLibraryRepository` (bibliotecas, códigos, atributos, importar, vincular y desvincular; log sin textos del usuario) (RF-1 a RF-10, RF-14 a RF-16, RF-19 a RF-21).
  - `DefaultLibrarySource` (interfaz) para que `data` no conozca los recursos de `app`.
  - `DatabaseSnapshot.TABLES` y `BackupRepository.copyProject` (RF-22).
- `app`
  - `DefaultCodeLibraries` con sus textos en recursos (RF-3) y su enlace con Hilt.
  - `ui/codes/`: `CodeLibraryListScreen`, `CodeLibraryScreen` (buscador), `CodeEditorScreen` (atributos), `CodeImportScreen` y su ViewModel, `ProjectCodesScreen` (solo consulta).
  - Ajustes: entrada «Bibliotecas de códigos» y la biblioteca de los proyectos nuevos; formulario de proyecto: selector y confirmación; menú del proyecto: «Códigos».

## Modelo de datos
### Room v28
```
code_libraries      id TEXT PK, name TEXT, name_key TEXT, project_id TEXT NULL FK(projects, CASCADE) UNIQUE, source_name TEXT NULL, created_at_ms INTEGER
library_codes       id TEXT PK, library_id TEXT FK(code_libraries, CASCADE), code TEXT, code_key TEXT, description TEXT, geometry TEXT, color INTEGER
                    UNIQUE(library_id, code_key)
code_attributes     id TEXT PK, code_id TEXT FK(library_codes, CASCADE), ordinal INTEGER, name TEXT, type TEXT, default_value TEXT NULL
attribute_options   attribute_id TEXT FK(code_attributes, CASCADE), ordinal INTEGER, value TEXT, PRIMARY KEY (attribute_id, ordinal)
```
- `AutoMigration(27, 28)` sin especificación: solo tablas nuevas. La unicidad del nombre entre bibliotecas del usuario se comprueba en la transacción (un índice único no cubre `project_id` NULL).
- `source_name` es el nombre de la biblioteca de origen de una copia, solo informativo.

## Algoritmos
### A1. Clave de comparación (RF-6, RF-16)
`trim` → `Normalizer.NFD` → quitar marcas combinantes → `lowercase(Locale.ROOT)`. Dos códigos son el mismo si tienen la misma clave. El nombre de biblioteca usa la misma clave.

### A2. Validar un código (RF-5 a RF-9)
1. Texto vacío tras `trim` → `Empty`; más de 32 caracteres → `TooLong`; contiene salto de línea → `NewLine`.
2. Clave ya usada por otro código de la biblioteca → `Duplicate(code)` con el código en conflicto.
3. Atributos: más de 100 → `TooManyAttributes`; nombre vacío o de clave repetida → `AttributeName`; Lista con 0 o más de 50 opciones o con opciones repetidas → `ListOptions`; valor por defecto no válido: Entero no parsea con `toLongOrNull`, Número no parsea o no es finito, Lista y el valor no está en las opciones → `DefaultValue`.
4. Se devuelve el primer motivo con el atributo afectado; ningún motivo guarda nada.

### A3. Importar CSV (RF-12 a RF-16)
1. `TextDecoding` y `DelimitedReader` dan las filas; la primera es la cabecera (D13); sin `Code` → `MissingCode`, no se importa nada.
2. Cada fila se convierte a `SurveyCode` con A2 (sin el chequeo de duplicado); un fallo es una fila inválida con su línea y su motivo (RF-15).
3. `CodeMerge` agrupa por clave (D9) contra los códigos de la biblioteca de destino y devuelve: nuevos, duplicados dentro del fichero y duplicados con la biblioteca.
4. El usuario elige Omitir o Reemplazar y se confirma; el repositorio aplica todo en una transacción (D12) y devuelve cuántos códigos entraron.

### A4. Ida y vuelta (RF-18)
Escribir: `Code, Description, Type, Color, Attribute_1_Name, Attribute_1_Type, Attribute_1_Default, Attribute_1_Options, …` hasta el máximo de atributos de la biblioteca; UTF-8 sin BOM, coma, CRLF, punto decimal y las comillas de S012/RF-15; las opciones, escapadas según D8. Leer el resultado debe dar los mismos códigos, geometrías, colores y atributos.

## Contratos
- `CodeLibraryRepository`: `libraries(): Flow<List<LibrarySummary>>` (las por defecto incluidas), `library(id): Flow<CodeLibrary?>`, `create`, `rename`, `duplicate`, `delete`, `saveCode`, `deleteCode`, `reorderAttributes`, `import(plan, policy, target)`, `projectCopy(projectId): Flow<CodeLibrary?>`.
- Resultados: `Saved`, `Invalid(reason)`, `NotEditable` (RF-3). Las bibliotecas por defecto nunca pasan por `saveCode`, `rename` ni `delete`.
- `DefaultLibrarySource.libraries(): List<CodeLibrary>`.
- Textos (es/en, en `strings.xml`): los motivos de A2, el informe de importación («Línea 7: tipo desconocido “Arco”»), las confirmaciones de borrar y de cambiar o quitar la biblioteca vinculada, y «por defecto».

## Estrategia de tests
- Unitarios JVM (`core:model`, `core:formats`): `CodeValidatorTest`, `CodeKeyTest`, `CodeSearchTest` (2.000 códigos en < 1 s), `CodeMergeTest`, `CodeLibraryCsvTest` (fixtures en Windows-1252, punto y coma, coma decimal, sin `Code`, filas inválidas, duplicados; 2.000 códigos en < 3 s; ida y vuelta).
- Robolectric (`data`): `TopoPhoneDatabaseTest` 27 a 28, `CodeLibraryRepositoryTest` (transacciones, importación atómica con fallo a medias, borrar la biblioteca vinculada no toca el proyecto), `CodeLibraryLinkTest` (crear, cambiar, quitar, duplicar proyecto), `CodeLibraryBackupTest` (copia completa y paquete restaurado sin la biblioteca original).
- Robolectric (`app`): ViewModels y pantallas con semántica (etiquetas, 56 dp); los ViewModels guardados en una lista y su `viewModelScope` cancelado antes de cerrar la base.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | `CodeLibraryRepository`, lista | `CodeLibraryRepositoryTest`, `CodeLibraryListViewModelTest` |
| RF-3 | D3, `DefaultCodeLibraries` | `DefaultCodeLibrariesTest`, `CodeLibraryRepositoryTest` |
| RF-4 | D1 | `CodeLibraryLinkTest` |
| RF-5, RF-7 | A2 | `CodeValidatorTest`, `CodeEditorViewModelTest` |
| RF-6 | A1, A2 | `CodeKeyTest`, `CodeValidatorTest` |
| RF-8 | D11, `CodePalette` | `CodePaletteTest`, `CodeEditorScreenTest` |
| RF-9, RF-10 | A2, D2 | `CodeValidatorTest`, `CodeRepositoryTest` |
| RF-11 | `CodeSearch` | `CodeSearchTest` |
| RF-12, RF-13 | A3, D13 | `CodeLibraryCsvReaderTest`, `CodeImportViewModelTest` |
| RF-14 | A3, D10 | `CodeImportRepositoryTest`, `CodeImportViewModelTest` |
| RF-15, RF-16 | A3, D9 | `CodeLibraryCsvReaderTest`, `CodeMergeTest` |
| RF-17, RF-18 | A4, D8 | `CodeLibraryCsvWriterTest`, `CodeLibraryRoundTripTest` |
| RF-19 a RF-21 | D4, D5, D6 | `CodeLibraryLinkTest`, `ProjectFormViewModelTest` |
| RF-22 | D1 | `CodeLibraryBackupTest` |

## Cambios al implementar
- **D7, texto canónico del Número:** se escribe en plano, sin ceros sobrantes y sin notación científica (`BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString()`), no con `Double.toString` a secas, porque este da `1.0E-7` y esa forma no la lee el propio lector (RF-9, RF-18). Sigue sin redondear.
- **D8:** el escape de las opciones del CSV vive en `CodeCsvFormat` (`encodeOptions` y `decodeOptions`), que comparten el escritor y el lector.
- **D10, nombre de la biblioteca nueva:** sale del nombre del fichero sin la extensión; si el fichero no da nombre se usa «Biblioteca importada».
- **D12, límites:** la cabecera cuenta como fila, como en S028/RF-3: un fichero admite 49.999 códigos y no 50.000.
- **A3, separadores:** el asistente solo ofrece coma, punto y coma y tabulador (los espacios no sirven en un texto con descripciones); si la detección de S028 propone espacios, se usa la coma.
- **D4, selector del proyecto:** `ProjectDraft.libraryChoice` es `Mantener` por defecto, y `ProjectDefaults.toDraft()` pone `Biblioteca(id)` solo si Ajustes tiene una; una biblioteca de Ajustes que ya no existe cuenta como ninguna. La confirmación de RF-21 se pide al elegir la opción (no al guardar) y solo si el proyecto ya lleva copia.
- **RF-20, dónde se consulta la copia:** «Códigos del proyecto» está en el menú de cada proyecto de la lista de Levantamiento (no en el mapa, que ya tiene demasiados botones) y reutiliza la pantalla de una biblioteca en modo de consulta.
- **RF-17, exportar:** se guarda con el selector de ficheros (`CreateDocument`); la alternativa de compartir con el FileProvider que admite la spec no se ha construido.
- **RF-12, importar:** se llega desde la lista de bibliotecas (a una biblioteca nueva) y desde cada biblioteca editable (a esa biblioteca); la elección entre biblioteca nueva o una existente se puede cambiar dentro del asistente.
- **Identificadores de las por defecto:** `default:general` y `default:urbanizacion`, y el de cada código es `<biblioteca>:<código>`.
- **S006 y S013:** sus `spec.md` no se han tocado (D14); el glosario sí se actualizó en T1.
