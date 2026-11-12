# Plan técnico — Spec 030

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/030-shapefile/spec.md`, `specs/048-geopackage/plan.md` (el importador, el recuento por zona, el dibujo y la pantalla que esta spec reutiliza) y `specs/040-capa-cad-dxf/plan.md` (dibujos, trozos y capas). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- Leer ZIP, `.shp`, `.shx`, `.dbf` y `.prj`: código propio sobre `java.util.zip`, `java.nio` y `java.nio.charset` de la JDK y de Android. Alternativas descartadas: una librería de Shapefile (`geotools-shapefile` y `jts` traen proj y licencias que RNF-T14 no admite sin revisar; `shapefile-java` no se mantiene) y GDAL por JNI (decenas de MB de APK para leer un formato de cabeceras fijas de 100 bytes).
- Los ficheros de ejemplo los escribe `tools/formatos/generar_shapefile.py` con `struct` y `zipfile` de la biblioteca estándar y `pyproj`, que `tools` ya usa. Alternativa descartada: `pyshp`, una dependencia nueva de Python para un formato que se escribe en 80 líneas. Los de «hechos con QGIS» (criterio de finalización) salen de la herramienta de QGIS por MCP y se guardan con su procedencia anotada.

## Idea: el Shapefile entra por la misma puerta que el GeoPackage
La importación de S048 ya hace todo lo que pide esta spec salvo leer el formato: capas marcables, SC por capa, recuento por zona, polígonos como líneas, cotas, nombre, atributos, tandas, cancelar sin dejar nada, resumen, mapa, ficha, replanteo, borrado y copias. Sus tipos (`GpkgLayerInfo`, `GpkgFeature`, `GpkgCrs`, `GpkgValue`, `GpkgImportSummary`) no dependen de SQLite: son «capa», «objeto con blob de geometría» y «valor». Por eso el plan **no copia** el importador, el recuento ni la pantalla: saca de `GpkgSession` una interfaz de sesión de capas (D1), escribe una `ShapefileSession` que la cumple y deja que la pantalla, el `GpkgImportViewModel` y el `GeoPackageImporter` trabajen con las dos. Lo que es propio de Shapefile (ficheros sueltos, ZIP, `.prj`, `.dbf`, codificación) es lo que se escribe nuevo.

## Estructura de módulos
- `core:model`
  - `CadOrigin { DXF, GEOPACKAGE, SHAPEFILE }` (RF-26); `GpkgIgnoredReason` gana `DELETED_RECORD` y `MULTIPATCH` (RF-16); `GpkgLayerSummary.withoutAttributes` (objetos sin registro en el `.dbf`, RF-20).
- `core:formats` (`shp/`), sin `android.*`
  - `ShpSelection`: de la lista de ficheros elegidos (nombre, primeros bytes, tamaño) a las capas con sus ficheros asociados por nombre, los huérfanos y el error si hay mezcla de ZIP o varios ZIP (RF-2 a RF-5); `ShpFileKind` (cabecera de 100 bytes: código 9994, versión 1000, tipo de geometría) y las constantes de los límites de 100 MB y 500 MB (RF-6).
  - `ShpReader`: los registros del `.shp` en todos sus tipos (`Point`, `MultiPoint`, `PolyLine`, `Polygon` y sus Z y M, `MultiPatch` y nulo) como `GpkgGeometry`, con M descartada y envolvente (RF-15, RF-16, RF-17); `ShxIndex`: las posiciones de los registros del `.shx`, o de una pasada por el `.shp` si falta o está roto (RF-19).
  - `ShpBlobEncoder`: de una `GpkgGeometry` y su envolvente al blob de geometría de GeoPackage (cabecera con envolvente y WKB), para que el importador y el recuento lo lean con `GpkgGeometryReader` como los de S048 (D1).
  - `DbfReader`: cabecera, campos de texto, número, fecha y lógico, registros, marca de borrado y registros que faltan, a `GpkgValue` (RF-18, RF-20, RF-21); `ShpEncoding`: la codificación del `.cpg`, la comprobación de UTF-8 válido y Windows-1252 (RF-18).
  - `PrjReader` y `ShpCrsCatalog`: el WKT del `.prj` a un árbol y de ahí a un `GpkgCrs` de la app (RF-8, RF-9). `GpkgCrs.Unavailable` gana el nombre del sistema para mostrarlo (D4).
- `data` (`shp/` y `cad/`), con `android.*` solo donde hace falta
  - `FeatureSession` (en `cad/`): la interfaz que cumplen `GpkgSession` y `ShapefileSession` (`layers`, `leftOutTables`, `origin`, `crs(layer)`, `forEachFeature`, `forEachHead`, `forEachIndexRow`, `forEachGeometry`, `close`); `GpkgFeature` gana `deleted` (D1).
  - `ShapefileReader`: de los ficheros elegidos (flujos de `ImportSource`) a una carpeta temporal de la caché, con los límites y la limpieza, y de ahí a una `ShapefileSession` (RF-1 a RF-7, RF-29, RF-30).
  - `ShapefileSession`: capas, SC, codificación, objetos con acceso aleatorio, cabeceras y geometrías por número de registro (RF-8, RF-15 a RF-21).
  - `GeoPackageImporter` y `GeoPackageScan` trabajan con `FeatureSession` y el importador guarda el origen de la sesión (RF-16, RF-26); sin cambios de comportamiento en S048.
  - `CadRepository`: sin cambios (el origen es un texto). Sin Room nuevo: la versión del esquema no cambia (D2).
- `app`
  - `GpkgImportViewModel` y `GpkgImportScreen` pasan a servir a los dos formatos con un `ImportFormat` (D3): más problemas de fichero, el selector de codificación por capa, el aviso de ficheros huérfanos y los textos propios de Shapefile (RF-1 a RF-10, RF-18).
  - Entradas «Importar Shapefile» en los tres menús, con el selector de varios ficheros, y su ruta de navegación (RF-1).
  - `DrawingsSheet` y `DesignMapViewModel`: «Origen: Shapefile» y los atributos de la ficha también para `SHAPEFILE` (RF-21, RF-23).
- `tools/formatos`: `generar_shapefile.py`, los ficheros de `fixtures/formatos/shp/` y lo esperado de cada uno con pyproj (constitución nº 2).

## Modelo de datos
Ningún cambio de Room (D2): `cad_drawings.origin` ya es un `TEXT` y guarda `SHAPEFILE` sin migración. Un dibujo de Shapefile es **un dibujo** con una capa de diseño por capa marcada y tipo, como el de S048, con `cad_layers.source_table` igual al nombre de la capa y `name_field` el campo de nombre elegido, y los atributos en `cad_chunk_attributes`.

Cómo se ve un Shapefile con los tipos de S048:
```
GpkgLayerInfo   table = nombre de la capa (RF-7)        geometryColumn = "geometry"
                geometryType = POINT | MULTIPOINT | LINESTRING | POLYGON (por el tipo del .shp; MIXED no existe)
                featureCount = registros del .shp       srsId = 0 (no se usa)      hasSpatialIndex = false
                columns = campos del .dbf con su tipo: TEXT, INTEGER, REAL, DATE, BOOLEAN
GpkgFeature     fid = número de registro del .shp (desde 1)   geometry = blob de geometría   values = los del .dbf   deleted
GpkgCrs         Supported / Unavailable(authority = "PRJ", code = 0, name) / Undeclared (sin .prj)
```
- El tipo de la capa sale de la cabecera del `.shp`; un `.shp` es de un solo tipo de geometría, así que `MIXED` no se da (salvo `MultiPatch`, que se ignora entero).
- Los atributos se guardan como en S048 (`GpkgAttributesCodec.encodeRow`): nombre de columna, tipo y valor. Un campo memo (`.dbt`) no se lista como columna (fuera de alcance).
- Coordenadas en el SC del proyecto, sin redondear, y altura sin convertir si se usan las cotas (RF-17, RF-26), como en S048.

## Algoritmos
### A1. Elegir y abrir los ficheros (RF-1 a RF-7, RF-29, RF-30)
1. El selector devuelve uno o varios `Uri`; el `ViewModel` los envuelve en `ImportSource` y llama a `ShapefileReader.open(sources)`.
2. `ShpSelection.classify` mira los primeros 100 bytes de cada fichero y su nombre: **ZIP** si empieza por `PK\x03\x04` o `PK\x05\x06` (RF-2); **`.shp`** si su cabecera es de Shapefile (código 9994 y versión 1000), sea cual sea su extensión, salvo `.shx`, que tiene la misma cabecera y es el índice; el resto se asocia por nombre. Más de un ZIP, o un ZIP junto a otros ficheros, da el error de RF-4 sin leer nada más.
3. Con ficheros sueltos, un `.shx`, `.dbf`, `.prj` o `.cpg` se asocia al `.shp` de su mismo nombre sin distinguir mayúsculas; sin `.shp` queda huérfano y se avisa (RF-5). Sin ningún `.shp` legible: error (RF-3), con el mensaje de «elige también el `.shp` o el ZIP» si había ficheros sueltos.
4. Los ficheros se copian a `cacheDir/shp-<uuid>/` (D5), por flujo y contando los bytes **leídos**, no los declarados: pasados 100 MB entre todos, o 500 MB descomprimidos en un ZIP, se corta con el error de RF-6 y se borra la carpeta. Antes se comprueba que cabe en el espacio libre.
5. De un ZIP se recorre `ZipInputStream` entrada a entrada: se copian las que son `.shp` por contenido o tienen extensión `.shx`, `.dbf`, `.prj` o `.cpg`, y se salta el resto contando lo descomprimido. El nombre de cada entrada se normaliza (sin `..`, sin ruta absoluta, sin prefijos de carpeta de sistema como `__MACOSX/`) antes de escribirlo: un nombre que escapa de la carpeta se descarta. Un ZIP dañado, vacío o cifrado da RF-3.
6. La carpeta temporal se borra al cerrar la sesión, al cancelar, al fallar y al arrancar la app (`cleanUpStale`, como en S048).
7. Cada `.shp` legible es una capa; su nombre es el del fichero sin extensión y, si dos capas se llaman igual, lleva su carpeta del ZIP delante, `parcelas/catastro` (RF-7).

### A2. Leer un `.shp` y su `.dbf` (RF-15 a RF-21)
- **Registros.** Cada registro tiene cabecera de 8 bytes (número y longitud, en 16 bits y orden de bytes «grande») y contenido con el tipo (orden «pequeño»). `ShpReader` lee con `FileChannel` y un `ByteBuffer` por registro, sin cargar el fichero. Tipos 1, 3, 5, 8, 11, 13, 15, 18, 21, 23, 25 y 28 dan geometría; 0 es nulo (vacío) y 31 es `MultiPatch` (ignorado).
- **Partes.** Un punto da un elemento; un multipunto, un elemento por punto; una línea, una línea por parte; un polígono, **una línea cerrada por anillo**: no se distinguen exteriores de huecos porque S048/RF-17 los trata igual, con la marca de contorno de polígono. La Z se toma si el tipo la trae y la M se lee para saltarla.
- **Acceso aleatorio.** `ShxIndex` da la posición del registro `n` desde el `.shx`; sin `.shx`, o con una longitud que no cuadra con el `.shp`, se recorre el `.shp` una vez y se guardan las posiciones en un `IntArray`/`LongArray` (RF-19).
- **`.dbf`.** Cabecera de 32 bytes, descriptores de 32 bytes hasta `0x0D`, y registros de longitud fija, así que el registro `n` está en `cabecera + n × longitud`. Marca `*` en el primer byte: borrado (RF-16). Tipos: `C` texto, `N` y `F` número (entero si no tiene decimales y cabe en un `Long`, real si no; vacío es nulo), `L` lógico (`Y/y/T/t` verdadero, `N/n/F/f` falso, `?` o vacío nulo), `D` fecha `AAAAMMDD` a `AAAA-MM-DD`; los demás tipos (`M`, `B`, `G`…) no se listan. Un `.dbf` con menos registros que el `.shp` da los registros que faltan sin atributos (RF-20); con más, los sobrantes no se leen.
- **Codificación (RF-18).** El `.cpg` se lee como texto: `UTF-8`/`65001` es UTF-8, `1252`/`windows-1252`/`ANSI 1252` es Windows-1252 y cualquier otra se lee como Windows-1252 y se avisa. Sin `.cpg`, una pasada por los registros con un `CharsetDecoder` de UTF-8 que informa de los errores decide: UTF-8 si todo es válido y Windows-1252 si no. El usuario puede cambiarla por capa; los nombres de campo se vuelven a leer con la elegida.
- **Envolvente.** Los tipos con recuadro (3, 5, 8, 13, 15, 18, 23, 25, 28) lo traen en el registro; un punto es su propia envolvente. `ShpBlobEncoder` la pone en la cabecera del blob, de modo que `forEachHead` devuelve la cabecera sin leer los vértices, como el R-tree y las cabeceras de S048/A3.

### A3. SC de cada capa (RF-8, RF-9)
`PrjReader` parsea el WKT (`PROJCS`/`GEOGCS`, nombres entre comillas, números, anidado, en OGC y en ESRI). `ShpCrsCatalog.resolve` va por orden y se queda con el primero que acierta:
1. **Autoridad:** si el nodo raíz trae `AUTHORITY["EPSG", n]`, `GpkgCrsCatalog.resolveEpsg(n)`. QGIS y ArcGIS suelen escribir el `.prj` sin autoridad, así que esto rara vez decide.
2. **Estructura:** el sistema se reconoce por sus propiedades, no por su nombre. Proyectado: proyección Transverse Mercator, factor de escala 0,9996, falso este 500.000, falso norte 0, latitud de origen 0, unidad metro (factor 1) y meridiano central 6·huso − 183, con la **familia de datum** deducida del nombre del datum y del elipsoide: ETRS89 (`D_ETRS_1989`, GRS 1980), REGCAN95 (`REGCAN95`) y ED50 (`European_1950`, Internacional 1924). Huso y familia dan el `ProjectCrs` si es uno de la app (ETRS89 29 a 31, REGCAN95 27 y 28, ED50 29 a 31). Geográfico: la familia de datum con unidad grado: ETRS89 y REGCAN95.
3. **WGS 84:** una geográfica WGS 84 se trata como ETRS89 y se avisa, como S048 (decisión D3 de ese plan).
4. Cualquier otro (otro huso o datum, pies, otra proyección) es «no disponible» con el nombre que trae el `.prj`; sin `.prj`, «sin sistema». En los dos casos la capa espera a que el usuario elija un sistema de la app (RF-9).
La unidad la fija el sistema (metro o grado), igual que en S048/RF-10. La lista se valida contra `pyproj` en `tools`, con el WKT de ESRI y el de OGC de cada `ProjectCrs`.

### A4. Zona, importación y recuento (RF-10 a RF-14, RF-16, RF-20, RF-22, RF-25, RF-26)
Son los de S048, sin tocarlos, porque `FeatureSession` da lo mismo que `GpkgSession`:
- La extensión y la distancia al centro (A5 de S048) salen de las envolventes de `forEachHead`; el recuento por radio (A3 de S048) filtra los candidatos por envolvente y pasa los demás por `GpkgFeatureConverter`, que los convierte y mira el ámbito del SC del proyecto.
- El importador recorre `forEachFeature`; un objeto con `deleted` se cuenta como ignorado con `DELETED_RECORD` sin convertirlo; `MultiPatch` y los nulos salen de `ShpReader` como `MULTIPATCH` y vacío. La suma de objetos pasa por el mismo límite de 500.000.
- El campo de nombre se propone con la misma regla (`suggestNameField`) y el nombre sin campo es el número de registro (RF-22).
- El resumen cuenta por capa los importados, los de fuera de la zona, los ignorados con motivo y los objetos sin registro en el `.dbf` (RF-20, RF-25).
- Una sola diferencia: la sesión de Shapefile no tiene índice espacial, así que `forEachFeature` recorre todos los registros y el prefiltro por envolvente de cabecera descarta los de fuera sin convertirlos, como S048 con una tabla sin R-tree.

### A5. Pantalla (RF-1 a RF-10, RF-18)
`ImportFormat.SHAPEFILE` cambia: el título; los problemas de fichero (`NOT_SHAPEFILE`, `NO_SHP`, `MIXED_SELECTION`, `TOO_BIG`…); el aviso «Se han dejado fuera: …» (los ficheros huérfanos de RF-5 en lugar de las tablas ajenas); el aviso de WGS 84 y de CRS como en S048; y en cada fila de capa, el selector de **codificación** (UTF-8 o Windows-1252) que relee las columnas de esa capa. La zona, el campo de nombre, las cotas, el progreso, el resumen y el botón de importar son los mismos.

## Contratos
- `ShapefileReader.open(sources: List<ImportSource>): ShpOpenResult` (`Opened(session)` o `Failed(reason)`), con `ShpOpenFailure { EMPTY, NOT_SHAPEFILE, NO_SHP, MIXED_SELECTION, DAMAGED, TOO_LARGE, NO_SPACE, READ_ERROR }` y los huérfanos en la sesión.
- `ShapefileSession : FeatureSession` más `withEncoding(table, charset): GpkgLayerInfo` y `encoding(table)`.
- `GpkgImportViewModel.start(projectId, sources: List<ImportSource>, format: ImportFormat)`; el de GeoPackage pasa una sola fuente.
- Navegación: `SurveyPage.ImportShp(projectId, fileUris: List<String>)` y `ImportShpDestination` (ADR-19), junto a las de GeoPackage.
- `GeoPackageImporter.import(…, session: FeatureSession, …)`: el origen del dibujo es `session.origin`.

Textos nuevos (es y en): Importar Shapefile; los problemas (no es un Shapefile, falta el `.shp`, «elige un solo ZIP o ficheros sueltos», ZIP dañado, más de 100 MB o 500 MB); «Ficheros sin su `.shp`: …»; «Codificación», UTF-8 y Windows-1252; el aviso de codificación declarada que no se conoce; los motivos de ignorado (borrado, `MultiPatch`); «Objetos sin atributos: %d» del resumen; «Origen: Shapefile»; «Sin sistema de coordenadas (.prj)».

Accesibilidad (RNF-T8): zonas de 56 dp en los selectores nuevos; el estado de cada capa y los avisos en texto y no solo en color; TalkBack lee el selector de codificación con su valor.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| **D1** Extraer `FeatureSession` de `GpkgSession` y dejar que la sesión de Shapefile dé el blob de geometría de GeoPackage | El importador, el recuento, `GpkgFeatureConverter`, la envolvente de cabecera y el ViewModel funcionan sin cambios; el coste es una codificación y una lectura de WKB por objeto, que cabe en los 20 s (se mide en T20) | Copiar importador, recuento y pantalla (unas 1.200 líneas duplicadas); que `GpkgFeature` lleve la `GpkgGeometry` ya leída (toca `GeoPackageScan`, el importador y sus tests) |
| **D2** Sin cambios de Room | `origin` es un `TEXT` y `valueOf` lee el valor nuevo; los dibujos de DXF y GeoPackage no cambian | Una columna o tabla nueva sin necesidad |
| **D3** Mantener los nombres `Gpkg*` y generalizar el `ViewModel` y la pantalla por `ImportFormat` | 91 ficheros nombran `Gpkg*` o `GeoPackage*`: renombrarlos es ruido sin cambio de comportamiento y arriesga los tests de S048 | Renombrar a un nombre neutro; un `ViewModel` y una pantalla nuevos para Shapefile |
| **D4** `GpkgCrs.Unavailable` lleva el nombre del sistema | El usuario ve «ETRS89 / UTM 28N» y no «PRJ 0» | Mostrar solo un código sin sentido |
| **D5** Copia temporal de los ficheros a la caché, borrada al terminar | El acceso aleatorio por registro de `forEachGeometry` y la relectura del recuento no se pueden hacer sobre un ZIP comprimido ni sobre un flujo; los 100 MB y 500 MB acotan el espacio; igual que D1 de S048 | Releer el ZIP en cada cambio de radio (lento); cargar el `.shp` entero en memoria (RNF) |
| Un anillo de polígono es una línea con la marca de contorno | S048/RF-17 y S041 solo replantean puntos y líneas; no hace falta saber qué anillo es hueco | Clasificar huecos por orientación y contención (cálculo sin uso) |
| El SC se reconoce por estructura y no por nombre | Los `.prj` de QGIS y ArcGIS traen nombres distintos para el mismo sistema («ETRS89_UTM_zone_30N», «ETRS_1989_UTM_Zone_30N») y casi nunca `AUTHORITY` | Una tabla de nombres (se rompe con el primer `.prj` de otra herramienta) |
| Solo UTF-8 y Windows-1252 | Spec RF-18; el `.cpg` que declara otra se lee como Windows-1252 con aviso | Un `Charset` por cada página de códigos dBase |
| Contar los bytes leídos para los límites | Un ZIP declara el tamaño que quiere: un «zip bomb» cabe en 100 KB | Fiarse del tamaño declarado en la cabecera |
| Los nombres de entrada del ZIP se normalizan | Evita escribir fuera de la carpeta temporal (zip-slip) | Extraer con el nombre tal cual |

## Estrategia de tests
Los nombres de clase y de test de `data` y `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:formats`:** `ShpSelectionTest` (ZIP, sueltos, mezcla, huérfanos, límites), `ShpFileKindTest`, `ShpReaderTest` (todos los tipos y las envolventes contra lo que escribe `tools`, Z y M, nulo y `MultiPatch`), `ShxIndexTest` (con `.shx`, sin él y con uno roto), `ShpBlobEncoderTest` (se lee igual con `GpkgGeometryReader`), `DbfReaderTest` (tipos, borrados, registros que faltan, codificaciones), `ShpEncodingTest`, `PrjReaderTest` y `ShpCrsCatalogTest` contra pyproj para cada `ProjectCrs` en WKT de ESRI y de OGC, y casos de no disponible (pies, otro huso, otra proyección).
- **`tools`:** `generar_shapefile.py` y su test: Shapefiles de puntos, multipuntos, líneas, polígonos con hueco y multianillo, con Z y con M, en ETRS89 UTM 30, REGCAN95 UTM 28, ED50 UTM 30 y ETRS89 geográficas, con y sin `.cpg`, con un `.dbf` en Windows-1252, sin `.prj`, sin `.shx`, sin `.dbf`, con el `.dbf` corto, con registros borrados, con un objeto nulo y un `MultiPatch`, en un ZIP con varias capas y con dos capas del mismo nombre en carpetas distintas, como ficheros sueltos, un ZIP dañado, uno sin `.shp` y uno cuyo tamaño declarado miente; y lo esperado de cada uno con pyproj.
- **Robolectric `data`:** `ShapefileReaderTest` (ZIP, sueltos, límites, espacio, zip-slip, limpieza del temporal), `ShapefileSessionTest`, `ShapefileImporterTest` (resumen, zona, polígonos como líneas, cotas, nombre, atributos, borrados, `.dbf` corto, cancelar sin dejar nada, fallo de escritura), `ShapefileScanTest`, `ShpBackupTest` (copia y paquete) y los de S048 sin cambios.
- **Robolectric `app`:** `ShpImportViewModelTest`, `ShpImportScreenTest` (problemas, capas, SC, codificación, huérfanos), `ShpImportEntriesTest` (los tres menús), `DrawingsSheetShpTest` y `ShpFeatureSheetTest`, `ShpStakeoutTest`.
- **Rendimiento:** un ZIP sintético de 100 MB se lee y se recorta en menos de 20 s y cambiar el radio recuenta en menos de 2 s (Robolectric como cota; el móvil de referencia en la demo).
- **Contra QGIS (demo):** un Shapefile en ETRS89 UTM 30 y otro en geográficas, importados con la app y leídos con QGIS por MCP, coinciden a menos de 1 mm.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, entradas de menú, selector múltiple | `ShpImportEntriesTest`, `ShpImportViewModelTest` |
| RF-2 a RF-5 | A1, `ShpSelection`, `ShpFileKind`, `ShapefileReader` | `ShpSelectionTest`, `ShpFileKindTest`, `ShapefileReaderTest` |
| RF-6 | A1 (bytes leídos) | `ShapefileReaderTest`, `ShpSelectionTest` |
| RF-7 | A1, capas | `ShapefileSessionTest`, `ShpImportScreenTest` |
| RF-8, RF-9 | A3, `ShpCrsCatalog` | `ShpCrsCatalogTest`, `ShpImportViewModelTest` |
| RF-10 | A4 (extensión y distancia) | `ShapefileScanTest`, `ShpImportViewModelTest` |
| RF-11 a RF-14 | A4 (zona) | `ShapefileScanTest`, `ShpImportViewModelTest` |
| RF-15, RF-16 | A2, `ShpReader`, importador | `ShpReaderTest`, `ShapefileImporterTest` |
| RF-17 | A4 (cotas) | `ShapefileImporterTest` |
| RF-18 | A2 (codificación), A5 | `ShpEncodingTest`, `DbfReaderTest`, `ShpImportScreenTest` |
| RF-19, RF-20 | A2 (`ShxIndex`, `.dbf`) | `ShxIndexTest`, `DbfReaderTest`, `ShapefileImporterTest` |
| RF-21, RF-22 | atributos y nombre | `DbfReaderTest`, `ShpFeatureSheetTest`, `ShapefileImporterTest` |
| RF-23, RF-24 | mapa y replanteo | `DrawingsSheetShpTest`, `ShpStakeoutTest` |
| RF-25, RF-26 | importador, resumen | `ShapefileImporterTest`, `ShpImportViewModelTest` |
| RF-27, RF-28 | borrado, copias, log | `ShpBackupTest`, `ShapefileImporterTest` |
| RF-29, RF-30 | segundo plano, cancelar | `ShapefileImporterTest`, `ShpImportViewModelTest` |

## Cambios al implementar
Lo que salió distinto del plan al hacerlo (el resto, tal cual):
- **El ZIP se copia y se abre con `ZipFile`** (A1.4 y A1.5). Con `ZipInputStream`, un ZIP cortado en la cabecera de una entrada se lee como un ZIP válido que acaba antes de tiempo, y RF-3 pide dar el ZIP dañado como error. Ahora el ZIP se copia a la carpeta temporal (hasta 100 MB leídos), `ZipFile` lee su directorio central y solo se descomprimen las entradas que son un `.shp` (por su cabecera o su extensión) o un fichero asociado; las demás no se tocan. El límite de 500 MB mira la suma de los tamaños que declaran las cabeceras y los bytes que se copian de verdad.
- **El `.shx` tiene la misma cabecera que el `.shp`**: un `.shp` se reconoce por su contenido salvo que su extensión sea `.shx`. Los nombres de ZIP que escapan de la carpeta se descartan sin leerlos y se anotan en `dropped`.
- **`FeatureSession` y `GpkgFeature` viven en `data/cad`** (no en `data/gpkg`) para que `data.shp` no dependa de `data.gpkg`. `GpkgFeature` gana `ignored` (el motivo, en lugar de `deleted`: sirve para el registro borrado, el `MultiPatch` y el registro ilegible) y `withoutAttributes` (el objeto sin registro en un `.dbf` corto).
- **Se añade `ShpByteSource`** (acceso por posición sobre un fichero o sobre bytes) como entrada de `ShpReader`, `ShxIndex` y `DbfReader`, y **`ShpReader.forEachEnvelope`**: una pasada por las cabeceras de los registros con un buffer de 1 MB, sin decodificar vértices. Las envolventes del recuento salen de ahí y, con una zona, `ShapefileSession.forEachFeature` solo decodifica los registros cuyo recuadro la toca. Un ZIP de 88 MB con 1,1 millones de líneas se lee y recorta en 6 s (antes de esta pasada, 18 s).
- **`readNBytes` pide la API 33** (Lint): la cabecera de un fichero se lee con un bucle propio en `data`. `ShapefileReader` crea la carpeta de la caché antes de mirar el espacio libre (sin ella sale cero).
- **Nombres de codificación del `.cpg`**: QGIS escribe `UTF-8` y `1252`; se admiten también `65001`, `ANSI 1252`, `windows-1252` y `CP1252`. **Nombres de datum**: QGIS escribe `D_Red_Geodesica_de_Canarias_1995` y `GCS_REGCAN95`, así que la familia REGCAN95 se deduce de cualquiera de los dos nombres (`regcan` o `canarias`).
- **`GpkgCrs.Unavailable` gana `name`** (D4) y la pantalla lo muestra; un `.prj` sin `AUTHORITY` da «no disponible» con autoridad `PRJ` y código 0.
- **El `ViewModel` de la importación sigue siendo `GpkgImportViewModel`** (D3) con `startShapefile`, `setEncoding` y `ImportFormat` en el estado; la ruta de navegación y la pantalla comparten el cuerpo con las de GeoPackage (`ImportRoute`). `ShpImportRoute` y `rememberImportShpLauncher` son nuevos; la dirección lleva una lista de URIs (`ImportShpDestination`).
- **Ficheros «hechos con QGIS»**: el MCP de QGIS no estaba en marcha, así que se generaron con el Python de QGIS 3.44.6 (GDAL 3.12.1, `tools/formatos/qgis_shapefile_ejemplos.py`), que también escribe lo que QGIS lee de ellos y los vértices en ETRS89 UTM 30 que calcula al reproyectar. La app coincide con QGIS a menos de 1 mm en ETRS89 UTM 30 y en geográficas ETRS89.
- **Los `.dbf` de `fixtures/formatos/shp/qgis/` cambian en cada ejecución** del script de QGIS porque la cabecera de un `.dbf` guarda la fecha en que se escribe; los tests comparan el contenido.
- **Sin Room nuevo**: el origen del dibujo es un texto y `SHAPEFILE` entra sin migración (v31).

## Demo en el emulador (12/11/2026)
Recorrido de los criterios de finalización con el APK de debug en el emulador `TopoPhone_API36` (un subagente manejó `adb`; el zoom del mapa sí se acciona con `input mouse scroll`). Funciona: «Importar Shapefile» en el menú del proyecto con selector de varios ficheros; un ZIP con tres capas, con tipo, número de objetos, sistema y unidad de cada una; el punto central del proyecto y el recuento por radio al instante; importar solo las capas marcadas con su resumen (guardado como `resumen-shapefile.txt`, solo recuentos); «Origen: Shapefile» y el interruptor de cada capa en «Dibujos»; la ficha de un punto con sus atributos; el replanteo de un punto y de un contorno de polígono con su nombre, dibujo y longitud; ficheros sueltos; la capa sin `.prj` y la de un sistema que la app no ofrece, que piden elegir uno; los tres errores de selección sin dejar nada importado; los ficheros huérfanos; la codificación Windows-1252 detectada sin `.cpg` (con «Árbol ñandú» y sus tildes); y cancelar una importación de 196.311 objetos sin dejar dibujo. `logcat` sin cierres.

Corregido a raíz de la demo: durante la importación de un Shapefile el texto decía «Importando el GeoPackage…» (ahora `shp_importing`, con test).

Queda anotado, sin tocar porque no es de esta spec o la spec no lo pide:
- **Los contornos de polígono no se dibujan en el mapa del emulador**, aunque sí se tocan y salen en la ficha («Contorno de polígono», 400 m, 5 vértices) y las líneas que no son contorno sí se ven. Es la capa de contornos a trazos de S048 (`topophone-design-rings`, con `lineDasharray`), la misma que usa un GeoPackage, y S048 ya dejó los trazos para la demo de campo (el emulador usa render por software). Falta comprobarlo en el móvil de referencia y, si falla allí, es un defecto de S048.
- «¿Usar las cotas de la capa?» sale siempre, también con capas sin Z; RF-17 dice «DONDE las capas traigan Z». Es el comportamiento de S048 y hace falta que la capa diga si trae Z (no lo dice `GpkgLayerInfo`). El resultado es correcto: sin Z no se guarda cota.
- Con un proyecto sin punto central, el aviso de más de 100 km y la distancia («553,2 km») se calculan contra el centro que da `ProjectCenterProvider` (sin puntos ni vista, el encuadre por defecto); es la lógica de S048/S006.
- Un vértice de `vertices` que cae sobre un borde de parcela abre la ficha del contorno al tocarlo (gana el más cercano), como en un GeoPackage.
