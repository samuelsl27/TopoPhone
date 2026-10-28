# Plan técnico — Spec 053

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/053-exportar-geopackage/spec.md`, `specs/012-exportar-csv/plan.md` (las columnas, el nombre del fichero, guardar y compartir) y `specs/048-geopackage/plan.md` (el lector de GeoPackage, `GpkgGeometryReader` y `GpkgCrsCatalog`). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El fichero se escribe con el `android.database.sqlite` del sistema, solo en `data`, como S048 lo lee. Alternativa descartada: una librería de GeoPackage (`geopackage-android` trae GeoTools/proj4j, ADR-14) o un escritor de SQLite propio.
- Todo lo que no necesita SQLite (los valores tipados de cada punto, el blob de la geometría, el esquema y las definiciones de los sistemas) es Kotlin puro en `core:formats`, probado en la JVM.
- Las referencias independientes las genera `tools/formatos/generar_gpkg_exportado.py` con `pyproj` (las definiciones WKT de los sistemas y las coordenadas geográficas) y las lee con el `sqlite3` de Python (un lector que no es el nuestro). Alternativa descartada: comprobar el fichero solo con nuestro lector de S048 (la prueba sería circular).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Unidades de los atributos (RF-4, RNF-T9). El CSV escribe las longitudes en la unidad del proyecto | Todas las longitudes y ángulos como atributos van **en metros y grados** (SI), sin redondear, para que el fichero sea el mismo en un proyecto en metros y en uno en pies. La columna `Units` conserva el nombre de la unidad del proyecto como dato informativo | Las unidades del proyecto como el CSV (en pies, los valores de un fichero con SC en metros se contradicen) |
| D2 | Tipo de los atributos de sí o no (`Replay`, `Edited`, `Automatic`, `Tilt mode verified`, `Stake within tolerance`) | `BOOLEAN` de GeoPackage (un entero 0 o 1 que QGIS muestra como casilla). Es un tipo más que los «texto, entero o decimal» de RF-4 | Entero 0 o 1 (sin casilla) o texto `true`/`false` como el CSV |
| D3 | Sistema geográfico de un proyecto ED50 (RF-7). El móvil calcula la latitud y la longitud en ETRS89 (o REGCAN95), también en un proyecto ED50 | Declarar lo que son: EPSG:4258 (ETRS89) en un proyecto ED50 peninsular. El resumen lo indica. Un proyecto ETRS89 da 4258 y uno REGCAN95 da 4081 | Declarar ED50 geográficas (4230), que no son las coordenadas que tiene el punto |
| D4 | Líneas y polígonos (RF-3) | Solo el punto de extensión: el escritor admite una lista de capas, y hoy solo se añade la de puntos. El test comprueba que un proyecto sin líneas ni polígonos no añade ninguna capa. Las capas de líneas y polígonos llegan con S025 | Reservar ya las tablas vacías de líneas y polígonos |
| D5 | Índice espacial | No se escribe (la extensión R-tree es opcional y QGIS lo crea por su cuenta); 10.000 puntos abren al instante | Escribir el R-tree con sus disparadores |
| D6 | Un punto sin Este y Norte (fuera del ámbito del sistema) al exportar en proyectadas | Se escribe con la **geometría vacía** y todos sus atributos: el fichero tiene los mismos puntos que el proyecto (RNF). El resumen cuenta cuántos | No exportarlo (el fichero tendría menos puntos) |

## Estructura de módulos
- `core:formats` (`gpkg/`), sin `android.*`
  - `GpkgAttributeType { TEXT, INTEGER, REAL, BOOLEAN }` y `GpkgPointAttributes`: de cada `ExportColumn` su tipo y su valor tipado y **sin redondear** de un punto (RF-4); vacío (`null`) lo que el punto no tiene. Reutiliza `ExportColumn` y no cambia `CsvExport`; un test comprueba que cada valor coincide con la celda del CSV del mismo punto.
  - `GpkgGeometryWriter`: el blob de una geometría de punto (`GP`, versión 0, little-endian, `srs_id`) con WKB `Point` o `Point Z` y la geometría vacía (RF-5). Lo lee `GpkgGeometryReader` de S048 en los tests.
  - `GpkgExportSchema`: el `application_id` y la `user_version`, las tablas `gpkg_spatial_ref_sys`, `gpkg_contents` y `gpkg_geometry_columns`, las tres filas obligatorias de sistemas y la del sistema de la exportación, y el DDL de la capa de puntos con sus columnas (RF-4, RF-6).
  - `GpkgSrsDefinitions`: por `ProjectCrs` (y para la geográfica de cada datum) el código, el nombre y la definición WKT. Las cadenas vienen de pyproj (`fixtures/formatos/gpkg-exportado/esperado-srs.csv`) y un test las compara con el fichero. Sin código reconocido, `GpkgSrsDefinition.custom(wkt)` con `srs_id` propio ≥ 100000, `organization = NONE` y su definición completa (RF-8).
  - `GpkgExportLayer`: una capa (nombre de tabla, tipo de geometría, columnas y filas); hoy solo se construye la de puntos (D4).
- `data` (`gpkg/`)
  - `GeoPackageWriter`: crea el fichero temporal con `SQLiteDatabase`, escribe el esquema y todas las filas en **una transacción** con una sentencia preparada, cierra y devuelve el fichero y un `GpkgExportSummary` (puntos, sin geometría, SC declarado, si el SC va con su definición completa). Cualquier fallo borra el fichero (RF-12, RNF-T6).
  - Sin Room: no cambia el esquema de la base de datos ni `DatabaseSnapshot`.
- `app` (`ui/export/`)
  - `GpkgExportViewModel` y `GpkgExportScreen`: el recuento, el sistema (el del proyecto o geográficas, RF-7), guardar, compartir y los mensajes (RF-1, RF-2, RF-7 a RF-13).
  - «Exportar GeoPackage» en el menú del proyecto, en el de su lista de objetos y con una selección (RF-1, RF-2). `SurveyPage.ExportGeoPackage(projectId, selection)` (ADR-19).
- `tools/formatos`: `generar_gpkg_exportado.py`, los `.gpkg` dorados de `fixtures/formatos/gpkg-exportado/` y su test.

## Modelo de datos del fichero
```
gpkg_spatial_ref_sys   las 3 filas obligatorias (−1, 0, 4326) + la del SC elegido (EPSG, o NONE con definición completa)
gpkg_contents          points: features, identifier "points", srs_id, extensión (min/max de x e y), last_change fijo del momento
gpkg_geometry_columns  points, geom, POINT, srs_id, z = 2 (opcional: la tienen unos puntos y otros no), m = 0
points                 fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, y una columna por cada ExportColumn
```
- `PRAGMA application_id = 0x47504B47` (`GPKG`) y `user_version = 10300`. Journal `DELETE`: un solo fichero, sin `-wal`.
- Los nombres de columna son los encabezados del CSV (`Name`, `Ellipsoidal height`…), entre comillas dobles; los tipos son `TEXT`, `INTEGER`, `REAL` y `BOOLEAN` (D2). Las fechas (`Measured from`/`to`) van como texto ISO-8601 UTC, como en el CSV.
- La altura (RF-5) es la **tercera coordenada** del blob (la del proyecto: la del sistema de alturas elegido), y también el atributo `Elevation`. `Easting` y `Northing` son atributos en metros; `Latitude`, `Longitude` y `Ellipsoidal height`, en grados y metros (D1).
- La capa se escribe en el sistema del proyecto (x = Este, y = Norte, RF-6) o en geográficas (x = longitud, y = latitud, RF-7); los atributos de ambos sistemas van siempre.

## Algoritmos
### A1. Valor tipado de un punto (RF-4)
`GpkgPointAttributes.value(point, column, project, base)`: el mismo origen de datos que `CsvExport.value`, pero devuelve `Double`, `Long`, `Boolean` o `String` sin pasar por el texto, así las coordenadas no se redondean (RF-5). `OUT_OF_TOLERANCE` es el texto de las claves separadas por `|`, como en el CSV; el recuento de satélites y de épocas, enteros; las precisiones y las alturas, decimales.

### A2. Geometría (RF-5, RF-6, RF-7)
- Proyectadas: (Este, Norte) y, si el punto tiene altura, Z; si falta el Este o el Norte, la geometría vacía (D6).
- Geográficas: (longitud, latitud) del datum de los cálculos (D3), con la altura del proyecto como Z.
- El blob lleva `srs_id` del sistema de la capa; la extensión de `gpkg_contents` se calcula con todos los puntos que tienen geometría.

### A3. Sistema declarado (RF-6, RF-8)
`GpkgSrsDefinitions.of(crs)` da el código EPSG y su WKT. Con un sistema sin código reconocido se escribe la definición completa con `NONE` y el resumen lo dice. Los ocho SC de S008 más las geográficas de cada datum tienen código, así que RF-8 solo se alcanza con un sistema definido a mano (hoy ninguno) y se prueba con una definición sintética.

### A4. Escritura entera o ninguna (RF-11 a RF-13, RNF-T6)
1. Sin puntos no se crea nada (RF-11).
2. Se escribe en `cache/exports/` (`ExportFiles.prepare`) con el nombre del fichero propuesto; si algo falla (disco lleno, SQLite) se borra y se informa de que no se ha exportado (RF-12).
3. Solo con el fichero completo se abre el selector de ficheros (`CreateDocument`) o se ofrece al FileProvider (RF-10). El proyecto nunca se modifica (RF-13): el escritor solo lee puntos ya cargados.

### A5. Nombre y resumen (RF-9)
`ExportFileName.of(proyecto, "geopackage", ahora, zona, "gpkg")` → `Obra_geopackage_20261028-1030.gpkg`. El resumen dice los puntos, cuántos van sin geometría, el sistema declarado y si va con definición completa.

## Contratos
- `GeoPackageWriter.write(points, project, crs: GpkgExportCrs, baseProjection, into: File): GpkgExportSummary` (lanza `GpkgExportException` en un fallo).
- `GpkgExportCrs { Project, Geographic }`; `GpkgExportSummary(points, withoutGeometry, srsId, customDefinition)`.
- Navegación: `SurveyPage.ExportGeoPackage(projectId, selection)`.
- MIME al compartir: `application/geopackage+sqlite3`.

Textos nuevos (es y en): Exportar GeoPackage, el recuento de puntos, el sistema (del proyecto o geográficas, con el código), el resumen de lo exportado (sin geometría, definición completa), guardar y compartir (los de S012), «Sin puntos que exportar», «No se ha exportado el fichero». Accesibilidad (RNF-T8): botones y selector de 56 dp.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Valores tipados aparte de `CsvExport` | El CSV redondea a 4 decimales y el GeoPackage no (RF-5); un test los cruza | Refactorizar `CsvExport.value` a valores tipados (riesgo para los dorados de S012, S019, S041, S042) |
| Escribir en la caché y copiar al destino | El fichero se escribe entero o no se escribe (RF-12, RNF-T6); el selector y el FileProvider ya trabajan con `OutputStream` y ficheros (S012) | Escribir directamente en el `Uri` elegido (un fallo a mitad deja un fichero roto) |
| Una transacción y sentencia preparada | 10.000 puntos en menos de 5 s (RNF) y atomicidad | Una inserción por punto |
| Definiciones WKT como constantes contrastadas con pyproj | Sin proj4j (ADR-14) y con la prueba independiente (constitución nº 2) | Generar el WKT en el móvil |

## Estrategia de tests
- **`tools`:** `generar_gpkg_exportado.py` y su test: los ficheros guardados son los que genera el script; un `.gpkg` dorado de cada caso leído con `sqlite3` de Python y `pyproj`: puntos, atributos, SC y coordenadas geográficas a menos de 1 mm.
- **JVM `core:formats`:** `GpkgPointAttributesTest` (tipos, vacíos, y cada valor frente a la celda del CSV), `GpkgGeometryWriterTest` (ida y vuelta con `GpkgGeometryReader`, con y sin Z, vacía), `GpkgExportSchemaTest`, `GpkgSrsDefinitionsTest` (contra `esperado-srs.csv` y la definición completa de RF-8).
- **`data` (Robolectric, nombres de 60 caracteres o menos):** `GeoPackageWriterTest` (el fichero se abre con `GeoPackageReader` de S048 y da los mismos puntos y coordenadas; atributos tipados; sin puntos; fallo de escritura sin dejar fichero; selección), `GeoPackageWriterPerformanceTest` (10.000 puntos en menos de 5 s).
- **Robolectric `app`:** `GpkgExportViewModelTest` y `GpkgExportScreenTest`; las entradas en los tres sitios.
- **QGIS:** el `.gpkg` dorado se abre con la herramienta de QGIS y da las mismas coordenadas y atributos (criterio de finalización).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A4, pantalla | `GpkgExportViewModelTest`, `GpkgExportScreenTest`, `GeoPackageWriterTest` |
| RF-2 | A4 | `GpkgExportViewModelTest`, `GeoPackageWriterTest` |
| RF-3 | D4 | `GeoPackageWriterTest` (sin líneas ni polígonos no hay más capas) |
| RF-4 | A1 | `GpkgPointAttributesTest`, `GeoPackageWriterTest` |
| RF-5 | A2 | `GpkgGeometryWriterTest`, `GeoPackageWriterTest`, `tools` |
| RF-6 | A3 | `GpkgSrsDefinitionsTest`, `GpkgExportSchemaTest`, `tools` |
| RF-7 | A2, D3 | `GeoPackageWriterTest`, `GpkgExportViewModelTest`, `tools` |
| RF-8 | A3 | `GpkgSrsDefinitionsTest`, `GeoPackageWriterTest` |
| RF-9 | A5 | `GpkgExportViewModelTest` |
| RF-10 | A4 | `GpkgExportScreenTest` |
| RF-11 | A4 | `GeoPackageWriterTest`, `GpkgExportViewModelTest` |
| RF-12 | A4 | `GeoPackageWriterTest`, `GpkgExportViewModelTest` |
| RF-13 | A4 | `GeoPackageWriterTest` (los puntos antes y después son iguales) |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S053 a «implementada».
- `docs/glosario.md`: «GeoPackage» si S048 no lo añadió.
- La cabecera de estado de `specs/053-*/spec.md` no se toca sin que lo pidas.
