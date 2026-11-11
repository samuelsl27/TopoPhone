# Plan técnico — Spec 055

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/055-exportar-shapefile/spec.md`, `specs/053-exportar-geopackage/plan.md` (el esquema de columnas, el resumen y la pantalla que esta spec reutiliza), `specs/029-exportar-dxf/plan.md` (las preferencias y la selección con líneas) y `specs/030-shapefile/plan.md` (el lector con el que se comprueba la ida y vuelta). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- Escribir ZIP, `.shp`, `.shx`, `.dbf`, `.prj` y `.cpg`: código propio sobre `java.util.zip` y `java.nio` de la JDK. Alternativas descartadas: una librería de Shapefile (los mismos motivos que en S030: licencias y tamaño) y `pyshp` en `tools` (una dependencia nueva para un formato que se escribe en unas 100 líneas).
- Las referencias las genera `tools/formatos/generar_shp_exportado.py` con `struct` de la biblioteca estándar y `pyproj`, que `tools` ya usa, como `generar_dxf_exportado.py` en S029.

## Estructura de módulos
- `core:formats` (`shp/export/`), sin `android.*`
  - `ShpColumnNames`: la tabla fija de nombres abreviados en español y en inglés de las columnas de "Todas las columnas", de las de líneas y polígonos, y el nombre de cada atributo de código (RF-8, RF-9).
  - `DbfWriter` y `DbfColumn`: la cabecera, los campos de texto, entero y decimal, los valores vacíos, el recorte a 254 bytes y los números con `Locale.ROOT` (RF-6, RF-7, RF-10, RF-11).
  - `ShpWriter`: el `.shp` y el `.shx` de `PointZ`, `PolyLineZ` y `PolygonZ`, con la envolvente de cada capa y la de cada registro, el contorno de los polígonos en sentido horario y su primer vértice repetido (RF-3, RF-5, RF-12 a RF-14).
  - `ShpPrjDefinitions` (y `ShpPrjDefinitionsData`, generado por `tools`): el WKT del `.prj` de cada `ProjectCrs` y de las geográficas de su datum (RF-15, RF-16).
  - `ShpExportBuilder`: de los puntos, las líneas y los polígonos del proyecto, con las opciones, a las capas con sus columnas, geometrías y valores, y al resumen; reutiliza `GpkgPointAttributes` y `AttributeColumns` de S053/S024 (RF-1 a RF-3, RF-6, RF-7, RF-12 a RF-14, RF-22).
  - `ShapefileZipWriter`: de las capas al ZIP, con el nombre de cada Shapefile y sin capas vacías (RF-3, RF-4).
- `data`
  - `ShpExportPreferences` en DataStore («shp-export»): el sistema elegido, para todos los proyectos (RF-23). Sin Room: la versión del esquema no cambia.
- `app`
  - `ShpExportViewModel` y `ShpExportScreen`: puntos o selección con sus líneas, el sistema, el idioma de los nombres, guardar y compartir, errores y resumen (RF-1, RF-2, RF-17 a RF-23).
  - Entradas «Exportar Shapefile» en el menú del proyecto y en el de su lista de objetos, y «Exportar» con una selección, con su navegación (RF-1, RF-2).
- `tools/formatos`: `generar_shp_exportado.py`, las referencias de `fixtures/formatos/shp-exportado/` (el contenido de cada fichero del ZIP) y el WKT de los `.prj` con pyproj (constitución nº 2).

## Modelo de datos
Sin Room ni formato propio. El ZIP exportado, con el nombre del proyecto limpiado (RF-4) en lugar de `<p>`:
```
<p>_puntos.shp .shx .dbf .prj .cpg       PointZ
<p>_lineas.shp .shx .dbf .prj .cpg       PolyLineZ      (si hay líneas abiertas)
<p>_poligonos.shp .shx .dbf .prj .cpg    PolygonZ       (si hay polígonos)
```
- **Geometría.** X, Y y Z son `double` sin redondear. En proyectadas, X es el Este, Y el Norte (en metros) y Z la altura del proyecto; en geográficas, la longitud, la latitud (en grados) y la altura elipsoidal (RF-12, RF-13). Lo que no tiene altura lleva Z = 0 y se cuenta (RF-14). `PointZ` lleva M = −1e38 («sin dato»); los de líneas y polígonos no llevan M, que el formato deja como opcional.
- **Polígonos.** Una sola parte por polígono (S025 no tiene huecos); el contorno en sentido horario en el plano X/Y, con el primer vértice repetido al final (RF-5). Un polígono se invierte si su área con signo es positiva.
- **Columnas del `.dbf`** (un registro por objeto, en el orden de los objetos de la capa):

| Tipo | `.dbf` | Valor vacío |
|---|---|---|
| texto | `C`, ancho = el mayor valor en bytes UTF-8, de 1 a 254 | espacios |
| entero | `N(w,0)`, `w` el mayor valor, hasta 18 | espacios |
| decimal | `N(20,9)` | espacios |

  Los booleanos de calidad (reproducción, editado, medido en automático…) salen como entero 0 o 1, como en S053/RF-4.
- **Tabla de nombres fijos (RF-8)**, hasta 10 caracteres, mayúsculas y sin acentos. Un test comprueba que no hay dos iguales en un idioma, que ninguno pasa de 10 y que cada `ExportColumn` tiene nombre en los dos.

| Columna de S012 | Español | Inglés |
|---|---|---|
| Name, Description, Origin | NOMBRE, DESCRIP, ORIGEN | NAME, DESCR, ORIGIN |
| Easting, Northing, Elevation | ESTE, NORTE, COTA | EASTING, NORTHING, ELEVATION |
| Latitude, Longitude, Ellipsoidal height | LATITUD, LONGITUD, ALT_ELIP | LATITUDE, LONGITUDE, ELLIP_HT |
| CRS, Height system, Units | SC, SIST_ALT, UNIDADES | CRS, HT_SYSTEM, UNITS |
| Pole height, Solution, Satellites, HDOP | ALT_JALON, SOLUCION, SATELITES, HDOP | POLE_HT, SOLUTION, SATS, HDOP |
| Correction age | EDAD_CORR | CORR_AGE |
| Horizontal accuracy, Vertical accuracy | PREC_H, PREC_V | ACC_H, ACC_V |
| Mode, Epochs used, Epochs discarded | MODO, EPOC_USAD, EPOC_DESC | MODE, EPOCH_USED, EPOCH_DISC |
| Replay, Out of tolerance | REPRODUC, FUERA_TOL | REPLAY, OUT_TOL |
| Measured from, Measured to | MED_INICIO, MED_FIN | MEAS_FROM, MEAS_TO |
| Base latitude, longitude, ellipsoidal height | BASE_LAT, BASE_LON, BASE_ELIP | BASE_LAT, BASE_LON, BASE_ELLIP |
| Base easting, northing, elevation | BASE_ESTE, BASE_NORTE, BASE_COTA | BASE_E, BASE_N, BASE_ELEV |
| Baseline | LINEABASE | BASELINE |
| Tilt mode, Tilt mode verified | MODO_INCL, INCL_VERIF | TILT_MODE, TILT_VERIF |
| Tilt, Tilt direction, Heading, Maximum tilt | INCLINAC, DIR_INCL, RUMBO, INCL_MAX | TILT, TILT_DIR, HEADING, MAX_TILT |
| Tilt horizontal / vertical accuracy | INCL_PRC_H, INCL_PRC_V | TILT_ACC_H, TILT_ACC_V |
| Averaged time, Deviation horizontal / vertical | T_PROMEDIO, DESV_H, DESV_V | AVG_TIME, DEV_H, DEV_V |
| Automatic, Edited, Copy of | AUTOMATICO, EDITADO, COPIA_DE | AUTOMATIC, EDITED, COPY_OF |
| Stake design, design elevation | REPL_DISEN, REPL_COTAD | STK_DESIGN, STK_DES_EL |
| Stake delta northing, easting | REPL_DN, REPL_DE | STK_DN, STK_DE |
| Stake horizontal distance, delta elevation | REPL_DH, REPL_DZ | STK_DH, STK_DZ |
| Stake within tolerance | REPL_TOL | STK_WITHIN |
| Base shift dX, dY, dZ | SHIFT_DX, SHIFT_DY, SHIFT_DZ | SHIFT_DX, SHIFT_DY, SHIFT_DZ |
| Code | CODIGO | CODE |
| (líneas y polígonos) nombre, código, vértices | NOMBRE, CODIGO, VERTICES | NAME, CODE, VERTICES |

## Algoritmos
### A1. Preparar las capas (RF-1 a RF-3, RF-6, RF-7)
1. Los puntos son los de `pointsForExport` (activos, ocultos incluidos, nunca la papelera) o los de la selección; las líneas, todas las del proyecto o, con selección, las que tienen todos sus vértices entre los puntos elegidos (como S029/RF-2 y S053).
2. La capa de puntos lleva las columnas de `ExportColumn` en orden, seguidas de una por cada atributo de código de los puntos (`AttributeColumns.of`). Los valores son los de `GpkgPointAttributes.value` y `attributeValue`: los mismos tipos y valores sin redondear que el GeoPackage de S053.
3. Las capas de líneas y de polígonos llevan nombre, código, número de vértices y una columna por atributo de código (`AttributeColumns.ofCodes` sobre los códigos de las líneas). Una línea con menos de 2 vértices con posición, o un polígono con menos de 3, no se escribe, como en S053. Una capa sin objetos no se crea (RF-3).
4. Los nombres: las columnas fijas, de `ShpColumnNames` en el idioma de la interfaz; cada atributo de código, a mayúsculas, con las letras y cifras ASCII y `_` y recortado a 10 caracteres; si choca con otro nombre de la capa, se recorta a 9 o a 8 y se le añade un número (`COTA_ALT1`, `COTA_ALT2`…) y el cambio se anota para el resumen (RF-9).

### A2. Escribir el `.dbf` (RF-6, RF-7, RF-10, RF-11)
- Se recorren los objetos una vez para calcular el ancho de cada columna de texto (el mayor valor en bytes UTF-8, con tope en 254) y de entero, y otra para escribirlos. Un texto de más de 254 bytes se recorta **sin partir un carácter** UTF-8 y se cuenta (RF-10).
- Los decimales se escriben con `N(20,9)` y `Locale.ROOT`, sin notación científica: 9 decimales dan 0,1 mm en grados y 1e-9 m en metros, dentro de la tolerancia de 0,05 mm de la spec.
- Un valor vacío es el campo entero de espacios.
- Cabecera: versión `0x03`, fecha del día, número de registros, longitud de la cabecera y del registro; fin de descriptores `0x0D`; fin de fichero `0x1A`; byte de motor de idioma `0x00` y `.cpg` con `UTF-8` (RF-11).

### A3. Escribir el `.shp` y el `.shx` (RF-3, RF-5, RF-12, RF-13, RF-14)
- Cabecera de 100 bytes: código 9994, versión 1000, tipo (11, 13 o 15), longitud en palabras de 16 bits y envolvente de X, Y y Z; los registros con su número desde 1 y su contenido; el `.shx` con la posición y la longitud de cada uno.
- Un punto sin Este y Norte (o con valores no finitos) se escribe como **registro nulo** (tipo 0) con sus atributos y se cuenta en el resumen (D3).
- Z de cada vértice: la altura del proyecto en proyectadas, la elipsoidal en geográficas (RF-12, RF-13); sin altura, 0 y se cuenta (RF-14).
- La envolvente de Z de la cabecera es la de los valores escritos, con los 0 de los vértices sin altura incluidos, porque es lo que verá un lector.

### A4. `.prj` y resumen (RF-15, RF-16, RF-22)
- `ShpPrjDefinitions.of(crs)` da el WKT de ESRI del sistema del proyecto y `geographicOf(crs)` el de las geográficas de su datum, ETRS89 o REGCAN95 (un proyecto ED50 exporta ETRS89, como `GpkgSrsDefinitions.geographicOf` en S053). Los dos salen de pyproj (`to_wkt` WKT1 ESRI) y los genera `tools` a un fichero de datos, validado en `tools` leyendo cada WKT de nuevo con pyproj. Si un sistema no tuviera definición, se omite el `.prj` y el resumen lo dice (RF-16).
- El resumen: puntos, líneas y polígonos escritos, puntos sin posición, vértices sin altura, textos recortados, atributos de código renombrados con su nombre nuevo, el aviso del `.prj` si lo hay y el sistema de alturas de la Z (RF-15, RF-22).

### A5. Escribir el ZIP y guardar (RF-3, RF-4, RF-17 a RF-21)
1. El nombre de cada Shapefile es el del proyecto con solo letras sin acentos, cifras, `_` y `-` (lo demás pasa a `_`; si queda vacío, `proyecto`) y `_puntos`, `_lineas` o `_poligonos` (RF-4).
2. `ShapefileZipWriter` escribe en un fichero temporal de la caché con `ZipOutputStream` (compresión Deflate) y solo cuando está entero se copia al destino que eligió el usuario: guardar con `CreateDocument` o compartir con FileProvider (RF-18). Un fallo borra el temporal y no deja nada (RF-20). Sin puntos no se crea nada (RF-19).
3. El nombre propuesto del ZIP es `ExportFileName.of(proyecto, "shapefile", ahora, zona, "zip")`, igual que S053 (RF-17).
4. La opción de proyectadas o geográficas se guarda en `ShpExportPreferences` al exportar y se lee al abrir la pantalla (RF-23). Un proyecto en geográficas no ofrece la elección.

## Contratos
- `ShpExportBuilder.build(points, lines, allPoints, project, options, baseProjection, language): ShpExport` con las capas y `ShpExportSummary`; `ShapefileZipWriter.write(export, baseName, out: OutputStream)`.
- `ShpExportOptions(coordinates: ShpCoordinates { PROJECTED, GEOGRAPHIC })` y `ShpNameLanguage { ES, EN }`: español salvo que el idioma de la app sea inglés.
- `ShpExportViewModel.start(projectId, selectionOnly)`, `setCoordinates(...)`, `fileName(at, zone)`, `writeTo(out, workDir)`, `writeFile(into)` y `report(outcome)`: como `GpkgExportViewModel`.
- Navegación: `SurveyPage.ExportShp(projectId, selectionOnly)` (ADR-19), junto a la de GeoPackage.

Textos nuevos (es y en): Exportar Shapefile, las opciones de sistema, el resumen y sus avisos (sin posición, sin altura, recortados, renombrados, sin `.prj`, sistema de alturas), el selector de guardar y el de compartir, y los errores (sin puntos, no se pudo escribir).

Accesibilidad (RNF-T8): zonas de 56 dp; el resumen en texto, con el recuento de cada aviso; TalkBack lee el resumen al terminar.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Reutilizar `GpkgPointAttributes` y `AttributeColumns` | Los mismos valores y tipos sin redondear que S053; una sola fuente para el CSV, el GeoPackage y el Shapefile | Recalcular las columnas para el `.dbf` |
| Nombres abreviados de una tabla fija y por idioma de la interfaz | Decisión 1 de la entrevista: legibles y estables; las columnas de S012 son largas y en inglés | Truncar a 10 caracteres («Stake delta northing» y «Stake delta easting» chocarían) |
| `.prj` en WKT ESRI, de pyproj | Es el que escribe ArcGIS y leen QGIS, GDAL y pyproj | El WKT con `AUTHORITY` de `GpkgSrsDefinitions` (pensado para GeoPackage) |
| Decimales `N(20,9)` y 9 decimales | Mantiene 0,1 mm en grados y 1e-9 m en metros dentro de los 20 caracteres del formato | `N(24,15)` (GDAL; algunos lectores antiguos truncan a 19) |
| Fichero temporal y copia al destino | Un fallo no deja un ZIP a medias (RNF-T6), como S053 | Escribir directamente al flujo del selector |
| Solo `PointZ`, `PolyLineZ` y `PolygonZ` | La spec lo pide; la M no se escribe | Tipos 2D, que pierden la altura |
| Sin Room | Solo se lee el proyecto | — |

**Decisiones confirmadas por el usuario (11/11/2026)**, ya reflejadas en la spec (RF-6, RF-12, RF-14b y RF-22):
- **D1. Unidad de las columnas numéricas del `.dbf`:** **metros y grados, como S053** (RNF-T9 de la spec dice «sin unidades del proyecto»); la columna de unidades dice la del proyecto. Descartada: la unidad del proyecto con 4 decimales, como el CSV de S012; en un proyecto en pies saldrían coordenadas en pies al lado de una geometría en metros.
- **D2. Z de un proyecto en geográficas:** **la altura del proyecto** (RF-12). La altura elipsoidal solo se escribe cuando un proyecto proyectado elige exportar en geográficas (RF-13). Un proyecto en geográficas no ofrece elegir.
- **D3. Puntos sin Este y Norte:** **registro nulo con sus atributos y un aviso en el resumen**, como S053 que los escribe sin geometría (RF-14b y RF-22).

## Estrategia de tests
Los nombres de clase y de test de `data` y `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:formats`:** `ShpColumnNamesTest` (≤ 10, mayúsculas ASCII, sin repetir, todas las columnas en los dos idiomas, atributos de código renombrados), `DbfWriterTest` (tipos, vacíos, recorte a 254 bytes sin partir un carácter, `Locale.ROOT`, cabecera y fin), `ShpWriterTest` (los tres tipos, envolventes, `.shx`, contorno horario, primer vértice repetido, registro nulo), `ShpPrjDefinitionsTest`, `ShpExportBuilderTest` (puntos, líneas y polígonos, Z del proyecto, Z elipsoidal, Z = 0 con recuento, selección con líneas completas, sin puntos, capas vacías), `ShapefileZipWriterTest` (nombres, entradas, ninguna vacía) y `ShpRoundTripTest` (el ZIP se relee con los lectores de S030 y las coordenadas difieren menos de 0,05 mm).
- **`tools`:** `generar_shp_exportado.py` y su test: las referencias del contenido de cada fichero (byte a byte para `.shp`, `.shx`, `.dbf` y `.cpg`) para el mismo proyecto de ejemplo, el WKT de cada `.prj` leído con pyproj como el mismo sistema, y las geográficas contra pyproj a menos de 1 mm.
- **JVM `data`:** `ShpExportPreferencesTest`.
- **Robolectric `app`:** `ShpExportViewModelTest` (todo, selección con líneas, sistema, idioma, escribir entero o nada, resumen), `ShpExportScreenTest` y `ShpExportEntriesTest` (menús y selección).
- **Rendimiento:** exportar 10.000 puntos con sus columnas en menos de 5 s (JVM como cota; el móvil de referencia en la demo).
- **Contra QGIS (demo):** abrir el ZIP exportado en QGIS por MCP con las mismas coordenadas, atributos y sistema, en proyectadas y en geográficas, con la app en español y en inglés.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A1, menús | `ShpExportViewModelTest`, `ShpExportEntriesTest`, `ShpExportBuilderTest` |
| RF-3, RF-4 | A1, A5, `ShapefileZipWriter` | `ShapefileZipWriterTest`, `ShpExportBuilderTest` |
| RF-5 | A3 | `ShpWriterTest` |
| RF-6, RF-7 | A1, A2 | `ShpExportBuilderTest`, `DbfWriterTest` |
| RF-8, RF-9 | A1 (nombres), `ShpColumnNames` | `ShpColumnNamesTest` |
| RF-10, RF-11 | A2 | `DbfWriterTest`, `ShpRoundTripTest` |
| RF-12 a RF-14b | A3 | `ShpWriterTest`, `ShpExportBuilderTest`, `ShpRoundTripTest` |
| RF-15, RF-16 | A4 | `ShpPrjDefinitionsTest`, tests de `tools` |
| RF-17 a RF-21 | A5 | `ShpExportViewModelTest`, `ShapefileZipWriterTest` |
| RF-22 | A4 | `ShpExportBuilderTest`, `ShpExportScreenTest` |
| RF-23 | A5 | `ShpExportPreferencesTest`, `ShpExportViewModelTest` |
