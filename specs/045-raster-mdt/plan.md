# Plan técnico — Spec 045

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/045-raster-mdt/spec.md`, `specs/042-superficies-landxml/plan.md` (la superficie activa, `SurfaceGuide`, `SurfaceHeight`, el replanteo y el recolector que se reutilizan sin cambios), `specs/040-capa-cad-dxf/` y `specs/048-geopackage/` (el convertidor de SC `CadProjector`, `GpkgCrsCatalog.resolveEpsg` y la importación con progreso y cancelación que se imitan) y `specs/008-coordenadas-espana/` (el geoide y `HeightSystem`). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El GeoTIFF y el ASCII Grid se leen con un lector propio en `core:formats` (RNF-T14). `GeoTiffGrid` (el lector de rejillas de geoide de S002/S008) carga el fichero entero y solo lee float32, así que no sirve para el ráster; se deja como está. Alternativa descartada: ampliar `GeoTiffGrid` (mezclaría dos usos con requisitos opuestos: rejilla pequeña en memoria frente a ráster de 250 MB por flujo).
- Los ficheros de prueba se escriben con un escritor TIFF en Python puro (`struct` y `zlib`, más un codificador LZW propio de unas 40 líneas) en `tools/raster/`, con los valores esperados calculados aparte con `pyproj` (ya es dependencia de `tools`) y con una bilineal en Python puro. Alternativa descartada: `numpy`, `tifffile` o `rasterio` (dependencia nueva solo para fabricar fixtures). La compatibilidad con GDAL real se comprueba en la validación con QGIS (T18), no con una dependencia del repositorio.
- La cota del ráster entra en el replanteo y el recolector por la interfaz que ya existe, `SurfaceHeightSource`: el ráster es otra fuente de `heightAt` bajo `SurfaceGuide`. Por eso RF-24 no toca ni una línea del replanteo, del recolector, de la ficha ni del informe: se demuestra con tests (T15, T17).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde se guardan las celdas (RF-14, RF-20) | En **Room**, como el TIN: `surfaces` gana columnas y una tabla nueva `raster_tiles` con bloques de 128 × 128 celdas (64 kB en float32). Entran solos en las copias, los paquetes y la papelera, y una importación entera o nada es una transacción por tandas (RNF-T6). **Room v22** (S017b pasa a la v23) con `AutoMigration(21, 22)` | Copiar el fichero original al almacenamiento privado y leerlo a demanda: el ASCII Grid no admite acceso directo y los TIFF comprimidos obligan a descomprimir una tira entera por consulta |
| D2 | Precisión de las celdas guardadas (RF-14) | Float32 si el fichero trae enteros de 16 o 32 bits o reales de 32 (float32 representa sin error los enteros hasta 16.777.216, más que cualquier cota en milímetros), y **float64 si el fichero trae reales de 64**: así «tal como viene» es cierto y no hay error de redondeo en ningún caso. Una marca por ráster dice cuál | Siempre float32: la mitad de espacio, pero redondea un MDT de 64 bits (hasta 0,5 mm a 8.000 m; dentro de la constitución nº 2 pero contra RF-14) |
| D3 | Cuándo se convierte la altura (RF-10) | **Al consultar**, no al importar: `cota del proyecto = cota del ráster ∓ N(lat, lon)` con la ondulación en la posición consultada. Así el ráster no se remuestrea (RF-14) y sigue siendo correcto si el usuario cambia el sistema de alturas del proyecto. N sale del geoide de S008 del ámbito del proyecto (Canarias o Península). ED50 solo tiene altitud ortométrica (S008), así que nunca convierte hacia elipsoidal | Convertir al importar: obliga a guardar otro ráster ya convertido y se rompe si el proyecto cambia de sistema de alturas |
| D4 | Acceso al fichero (RF-13) | Un TIFF necesita acceso directo y el selector de ficheros da un flujo: `RasterImporter` **copia el contenido elegido a un fichero temporal de la caché** (con progreso y «Cancelar», hasta 250 MB), lo lee con un `RandomAccessSource` y lo borra al terminar, falle o se cancele. Hace falta espacio libre para el temporal; si no lo hay, el motivo es «No se pudo escribir en el almacenamiento» (RF-13) | Pedir al sistema un descriptor con acceso directo (no todos los proveedores de ficheros lo dan) |
| D5 | Memoria al importar (RNF: «por flujo») | Se importa **por bandas de 128 filas**: se leen y descomprimen solo los bloques del fichero que tocan esas filas, se cortan en bloques de 128 × 128 y se escriben. La memoria es una banda (anchura × 128 × 4 u 8 bytes) más un bloque del fichero. Un TIFF por teselas de 512 se descomprime 4 veces, a cambio de no guardar una fila de teselas entera | Una fila de teselas del fichero entera en memoria (con teselas de 512 y 50.000 celdas de ancho, 100 MB) |
| D6 | Un ráster tan ancho que una banda no cabe (no está en la spec; hay que decidir qué pasa) | Si una banda pasa de **64 MB** (más de 125.000 celdas de ancho en float32), no se importa y se indica el límite de la anchura, como el del RF-5 | Dejar que la app se quede sin memoria |
| D7 | Pantalla de importación (RF-1 a RF-16) | **Una pantalla propia**, `RasterImport*`, y el mismo menú que «Importar superficie» con una entrada nueva «Importar ráster»: las opciones son otras (SC, unidad, sistema de alturas, sin elegir superficie dentro del fichero) y así no se toca la pantalla de S042 | Ampliar `SurfaceImportScreen` con un modo ráster |
| D8 | Tipo de superficie en el modelo (RF-17, RF-18) | `Surface` gana `kind: SurfaceKind { TIN, RASTER }` y los datos del ráster en un `RasterInfo?` (con los recuentos de TIN a 0). `SurfacesSheet`, el borrado y la activación son los de S042 | Un modelo `Raster` aparte con su propia lista (rompe «una superficie más», RF-17) |
| D9 | Fixtures y MDT05 real (criterios de finalización) | Los de `tools/raster/` (puro Python) cubren todos los casos. Para el trozo de **MDT05 del IGN** necesito un fichero pequeño de verdad: lo recorto yo con QGIS (MCP) de una descarga del CNIG, o lo aportas tú. Propongo que lo recorte yo, y que se guarde en `fixtures/` con su atribución CC-BY 4.0 (RNF-T14) | Sin MDT05 real: solo fixtures sintéticos |

## Estructura de módulos
- `core:model`
  - `surface/SurfaceKind` y `surface/RasterInfo` (columnas y filas, centro de la celda 0,0, paso en Este y en Norte en las unidades del SC del ráster, tipo de valor, sistema de alturas, unidad de la cota, celdas con y sin dato) (RF-14, RF-17); `Surface` los incorpora (D8).
  - `surface/RasterTileCodec`: el bloque de 128 × 128 celdas en float32 o float64, con `NaN` para «sin dato» (D2); `RasterTile.valueAt(col, row)`.
  - `project/HeightSystem` ya existe; el ráster guarda el suyo.
- `core:formats`
  - `raster/RasterSource` (interfaz de acceso directo: `size`, `read(position, buffer)`) y `RasterSink` (recibe una banda de filas).
  - `raster/RasterReader`: `scan(source)` (RF-2 a RF-9) y `readBands(source, sink)`; reconoce GeoTIFF o ASCII Grid por el contenido.
  - `raster/GeoTiffRasterReader`: IFD, tipos de muestra, tiras y teselas, `RasterGeoref` (escala, punto de enlace, `PixelIsArea/PixelIsPoint`, claves GeoTIFF 1024, 2048, 3072, 3076, 4099, `GDAL_NODATA`), rechazos de RF-3 y RF-4.
  - `raster/TiffDecoder`: sin comprimir, LZW (MSB, `early change`), Deflate, predictor 2 (enteros) y 3 (coma flotante), little y big endian.
  - `raster/AsciiGridReader`: la cabecera (`ncols`, `nrows`, `xllcorner` o `xllcenter`, `cellsize`, `NODATA_value`) y las filas por flujo, con punto decimal (`Locale.ROOT`).
  - `raster/RasterCrsResolver`: del código EPSG del GeoTIFF a un `CadSourceCrs`, con `GpkgCrsCatalog.resolveEpsg` (no se duplica la tabla de sistemas); la unidad de la cota con la clave 4099.
- `core:survey` (sin Android ni Hilt)
  - `RasterTiler`: corta una banda en bloques de 128 × 128, con `NaN` en las celdas sin dato y las de relleno del borde.
  - `RasterElevation`: dada la posición del proyecto, la cota del ráster en metros y en el sistema de alturas del proyecto, o fuera (RF-21 a RF-23). Pasos en A3.
  - `RasterExtent`: la extensión, las cotas y la distancia al centro del proyecto para la pantalla de importación (RF-11, RF-12, RF-16).
- `data`
  - Room v22: columnas en `surfaces`, tabla `raster_tiles`, `AutoMigration(21, 22)` (D1).
  - `SurfaceRepository` gana `beginRaster`, `addRasterTiles` y `finishRaster`; `activate`, `delete`, el desplazamiento y el límite de una activa son los que hay.
  - `RasterImporter` (D4, D5): `scan` e `import` con progreso, cancelación y fallos, igual que `SurfaceImporter`.
  - `ActiveRaster`: el equivalente de `ActiveSurface` para un ráster, con la misma caché sin cerrojos y `Loading`; `ActiveSurfaceProvider.guide` devuelve la fuente que toque según `Surface.kind`.
  - `BackupRepository` y `DatabaseSnapshot`: `raster_tiles` entra con su `WHERE` (RF-20).
- `app`
  - `ui/raster/`: `RasterImportViewModel`, `RasterImportScreen`, `RasterImportRoute` (RF-1 a RF-16); «Importar ráster» en los menús donde está «Importar superficie».
  - `SurfacesSheet` y `SurfacesViewModel`: tipo, tamaño de celda, extensión y cotas del ráster (RF-17).
  - Replanteo, recolector, ficha e informe: **sin cambios** (RF-24, RF-25); solo tests nuevos.
- `tools/raster`: `generar_rasters.py` y `fixtures/raster/` con lo esperado calculado aparte (constitución nº 2).

## Modelo de datos
### Room v22
```
surfaces       + kind TEXT NOT NULL DEFAULT 'TIN', height_system TEXT NULL, value_type TEXT NULL, columns INTEGER NULL, rows INTEGER NULL,
                 origin_x REAL NULL, origin_y REAL NULL, step_x REAL NULL, step_y REAL NULL, source_epsg_unit TEXT NULL,
                 data_cells INTEGER NULL, nodata_cells INTEGER NULL
raster_tiles     id INTEGER PK autogenerado, surface_id TEXT FK(surfaces, CASCADE), tile_col INTEGER, tile_row INTEGER, data BLOB (≤ 128 kB)
```
- `AutoMigration(21, 22)` sin especificación: una columna con valor por defecto, las demás anulables y una tabla nueva. Las superficies existentes quedan con `kind = 'TIN'` y los puntos replanteados no cambian (RNF-T6).
- `origin_x`, `origin_y` son el **centro de la celda (0, 0)**, esquina superior izquierda, en el SC del ráster (`source_crs`) y `step_y` positivo hacia el sur. Las celdas «como área» se llevan al centro al leer (RF-7), así que la consulta es siempre entre centros.
- `min_e … max_n` de `surfaces` guardan la extensión en el sistema del proyecto (el recuadro de las cuatro esquinas de los centros convertidas), `min_z`/`max_z` las cotas en metros **tal como están en el ráster y sin convertir de sistema de alturas** (se muestran con la unidad ya a metros; la conversión con el geoide es por posición, D3).
- Un punto replanteado contra un ráster guarda nombre y fecha de importación en `stake_surface_name` y `stake_surface_imported_ms`, que ya existen (S042). No cambia `points`.
- Las celdas sin dato son `NaN` en el bloque. Un bloque entero sin dato se guarda igual (el índice por `tile_col`, `tile_row` no tiene huecos).

### Bloque (`RasterTileCodec`)
Cabecera con el número de formato y el tipo de valor (float32 o float64), seguida de las 128 × 128 celdas en orden de filas, sin redondear. Las celdas de relleno del borde derecho e inferior son `NaN`.

## Algoritmos
### A1. Reconocer y explorar el fichero (RF-2 a RF-9, RF-11)
- Por contenido: `II*\0` o `MM\0*` es TIFF (`II+\0` o `MM\0+` es BigTIFF y se rechaza, RF-4); un texto que empieza por `ncols` (sin distinguir mayúsculas) es ASCII Grid; lo demás no es un ráster (RF-3).
- `scan` lee solo cabeceras: tamaño, tipo de muestra, bandas (RF-6), compresión, geotransformación, sistema, unidad de la cota y valor sin dato. No lee celdas. Rechaza con su motivo: sin georreferencia (sin escala o sin punto de enlace) (RF-3), con `ModelTransformationTag` o `ModelTiepoint` con giro (RF-4), de color (8 bits con 3 o más bandas) (RF-4), con una compresión distinta de las tres (RF-4) y por encima de 250 MB o 250 millones de celdas (RF-5).
- El tamaño en megabytes se comprueba antes de leer, como S042/RF-4 y S040/RF-5.
- «Ninguna celda con dato» solo se sabe tras leer: lo detecta `import` y no guarda nada (RF-3).

### A2. Importar por bandas (RF-7, RF-13, RF-14, RF-16; D4 a D6)
- `RasterImporter.import`: copia el fichero a la caché si no tiene acceso directo (D4), `begin` (estado `IMPORTING`) → por cada banda de 128 filas: leer los bloques del fichero que la tocan (tira o tesela), descomprimir, deshacer el predictor, convertir cada valor a `Double` y sustituir por `NaN` el declarado como sin dato y los que no son un número (RF-7) → `RasterTiler` → `addRasterTiles` (una transacción por banda) → `finishRaster` con el recuento de celdas con y sin dato, las cotas mínima y máxima y la extensión → borrar el temporal.
- ASCII Grid: la cabecera da `xllcorner` (esquina) o `xllcenter` (centro); se lleva siempre al centro de la celda 0,0 sumando media celda. El flujo se lee por filas y las bandas se llenan en orden.
- GeoTIFF: `PixelIsArea` suma media celda al punto de enlace para llegar al centro; `PixelIsPoint` ya es el centro (RF-7). El orden de las filas es de norte a sur.
- La cota no se redondea ni se convierte de unidad al guardar: la unidad (m, ft, usft) va en la fila y se aplica al consultar (RF-9, RF-14). Los mínimos y máximos de `surfaces` sí van ya en metros.
- Cancelar o fallar en cualquier paso llama a `abort` (borra todo lo escrito) y al borrado del temporal; `cleanUpUnfinished` limpia al arrancar lo que quedó `IMPORTING` (RF-13, RNF-T6).
- Se reutiliza `CountingInputStream` de S042 para el progreso de la copia y se informa también de las bandas escritas.

### A3. La cota del ráster en una posición (RF-21 a RF-23; D3)
1. De Este y Norte del proyecto a latitud y longitud: la transversa de Mercator inversa de S008 (`Utm.inverse`) con el elipsoide del datum del proyecto; en ED50, además `Ed50.toEtrs89` para tener el ETRS89 del geoide y de las pasadas de sistema. Si falla (ED50 fuera de la rejilla), fuera (RF-22).
2. De latitud y longitud al SC del ráster: la identidad en geográficas del mismo datum, la proyección directa de S008 en UTM y el cambio de datum por la rejilla de ED50 si el ráster y el proyecto difieren en él; mismo código que `CadProjector` en sentido contrario.
3. A coordenadas de celda: `gx = (x − origin_x) / step_x`, `gy = (origin_y − y) / step_y`. Fuera si `gx < −ε`, `gx > columnas − 1 + ε`, `gy < −ε` o `gy > filas − 1 + ε`, con ε el equivalente a 1 mm sobre el terreno (la tolerancia de borde de S042, para que un centro de celda del borde o una posición reconvertida con micras de más no caigan fuera). Más allá, es la media celda del borde y se trata como «Fuera de la superficie» (RF-22).
4. Las cuatro celdas: índices `i = min(floor(gx), columnas − 2)`, `j` igual, y fracciones `fx`, `fy`. Si alguna es `NaN`, fuera (RF-22). Si `fx` y `fy` valen 0 o 1 exactamente devuelve el valor de la celda (RF-23). La interpolación es la bilineal de `GeoTiffGrid.interpolate`.
5. De unidad a metros y de sistema de alturas al del proyecto: si difieren, `H = h − N` o `h = H + N` con `N` el geoide del ámbito del proyecto en la posición (D3); sin geoide en la zona, fuera (RF-22). Si coinciden no se consulta el geoide.
6. El resultado es un `SurfaceHeight`; `Loading` aparece solo cuando uno de los bloques que hacen falta no está en memoria (A4).

### A4. El ráster activo en memoria (RF-17, RF-18, RF-21, RNF-T13)
- `ActiveRaster.heightAt(e, n)` hace los pasos 1 a 3 de A3 sin tocar la base de datos, calcula los bloques que necesita (hasta cuatro si la posición está en una esquina de bloque) y mira una caché concurrente de 64 bloques (4 MB). Si falta alguno responde `Loading`, avisa por la misma cola sin espera de `ActiveSurface` y las siguientes épocas ya responden. Precarga los bloques vecinos hacia donde se mueve la punta.
- `Loading` nunca se presenta como «Fuera de la superficie» (S042/A4). Desactivar o borrar el ráster vacía la caché y avisa a quien observa (S042/RF-27).
- El índice es solo una cuadrícula: no hace falta cargar ninguna celda al activar, así que la primera cota tarda menos de 1 s.
- La geometría y el geoide se evalúan en cada consulta: son del orden de microsegundos (el geoide es una bilineal de una rejilla en memoria).

### A5. Lo que no cambia (RF-24, RF-25)
`SurfaceGuide(surface, source, offset)` ya admite cualquier `SurfaceHeightSource`. `StakeoutController`, `ElevationSources`, `SurfaceStake`, el conmutador, «Fuera de la superficie», el guardado, la ficha, el informe y el recolector solo ven una superficie con nombre y fecha de importación; los tests de T15 y T17 lo demuestran con un ráster en lugar de un TIN.

## Contratos
- `RasterReader.scan(source): RasterScan` (`Found(RasterInfoDraft)` o `Rejected(reason)`) y `RasterReader.readBands(source, georef, sink)`.
- `RasterElevation.heightAt(projectCrs, projectHeightSystem, raster, tileReader, e, n): SurfaceHeight`.
- `RasterTileCodec.encode(tile): ByteArray` y `decode(bytes): RasterTile`.
- Motivos de rechazo (`RasterImportFailure`): `NOT_A_RASTER`, `EMPTY`, `NOT_GEOREFERENCED`, `NO_DATA_CELLS`, `BIGTIFF`, `UNSUPPORTED_COMPRESSION`, `ROTATED`, `COLOUR`, `TOO_LARGE`, `TOO_WIDE`, `OUTSIDE_CRS_AREA`, `OUTSIDE_ED50_GRID`, `READ_ERROR`, `WRITE_ERROR`.

### Textos nuevos (`strings.xml`, es y en)
Importar ráster, los pasos de la importación y cada motivo de rechazo, «Bandas del fichero: %1$d, se usa la primera», la unidad de la cota, el sistema de alturas del ráster («Altitud ortométrica» y «Altura elipsoidal», con los términos del glosario), el aviso de lejanía, el resumen, «Ráster» y «TIN» como tipo en la lista de superficies, y las frases de TalkBack. Sin apóstrofos sin escapar en inglés.

### Accesibilidad (RNF-T8)
Campos, selectores y botones de 56 dp; el aviso de lejanía, el progreso y los rechazos en texto además del color; TalkBack lee el resumen al terminar.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Lector TIFF y ASCII Grid propios en `core:formats`, por flujo y sin librerías | RNF-T14 y `core:*` sin `android.*`; hay que leer 250 MB sin cargarlos | `GeoTiffGrid` ampliado; una librería de imágenes |
| Bloques de 128 × 128 en Room | Misma persistencia, copia y migración que S042; un bloque cabe en 128 kB (el límite de ventana del cursor es de 2 MB) | Ficheros sueltos fuera de la base de datos |
| La altura se convierte al consultar | No se remuestrea (RF-14) y sobrevive a un cambio de sistema de alturas del proyecto | Convertir al importar |
| `SurfaceHeightSource` ya existente | El replanteo y el recolector no cambian (RF-24) | Una interfaz nueva para rásteres |
| Bilineal estricta entre centros de celda | Coincide con `gdallocationinfo -r bilinear` y con S002/RF-39 (nunca extrapolar) | Celda más próxima en el borde |
| Temporal en caché para el acceso directo | El selector no garantiza un descriptor con `seek` | Pedir el descriptor |
| Importar por bandas de 128 filas | Memoria acotada con tiras y con teselas | Una fila de teselas entera |

## Estrategia de tests
- **`tools`:** `generar_rasters.py` y su test (los ficheros y lo esperado guardados son los que genera el script): GeoTIFF sin comprimir, LZW y Deflate (con y sin predictor), por tiras y por teselas, de int16, int32, float32 y float64, celda como área y como punto, un BigTIFF, uno girado, uno de color, uno multibanda y uno sin georreferencia; ASCII Grid con esquina y con centro, con y sin sistema; y para posiciones de centro de celda, a medio camino, en el borde, en una celda sin dato y con la conversión del geoide y de otro SC, el valor esperado con `pyproj` y bilineal en Python puro.
- **JVM `core:model`:** `RasterTileCodecTest` (ida y vuelta sin redondear en float32 y float64, `NaN`, bloque de borde), `SurfaceTest` ampliado (los valores por omisión de TIN no cambian).
- **JVM `core:formats`:** `TiffDecoderTest` (LZW, Deflate, predictores 2 y 3, big y little endian, bloques de borde), `GeoTiffRasterReaderTest` (cada rechazo de RF-3 y RF-4, georreferencia, `PixelIs*`, sin dato, unidad), `AsciiGridReaderTest` (esquina y centro, sin dato, `NaN`, cabecera mal formada), `RasterReaderTest` (reconocer por contenido, multibanda) y `RasterCrsResolverTest` (EPSG que reconoce y los que no).
- **JVM `core:survey`:** `RasterTilerTest` y `RasterElevationTest` frente a `fixtures/raster/` a menos de 1 mm: centro de celda, a medio camino, borde, media celda del borde, celda sin dato, otro SC, pies, el geoide en los dos sentidos y sin geoide.
- **`data`** (nombres de test de 60 caracteres o menos): `TopoPhoneDatabaseTest` 21 → 22 con un proyecto con superficies y puntos replanteados; `RasterRepositoryTest` (`begin`, tandas, `finish`, `abort`, la limpieza, activar con un TIN activo y a la inversa, borrar); `RasterImporterTest` (cada formato, cancelar, fallo de escritura, el límite, el mismo nombre); `ActiveRasterTest` (`Loading` y después `Value` o `Outside`, vaciado al desactivar); `RasterBackupTest` (copia, paquete y restauración).
- **Robolectric (`app`):** `RasterImportViewModelTest` y `RasterImportScreenTest` (pasos, rechazos, sistema, unidad, sistema de alturas, aviso de lejanía, nombre repetido, cancelar); `SurfacesSheetTest` ampliado (tipo y datos del ráster); `StakeoutControllerRasterTest`, `StakeoutViewModelRasterTest`, `StakeoutMeasureRasterTest`, `CollectorRasterTest` y el test de la ficha y del informe con un ráster (RF-24, RF-25).
- **Rendimiento:** `RasterPerformanceTest`: un ráster de 250 MB generado al vuelo se importa en menos de 180 s sin quedarse sin memoria (JVM con el mismo tope de montón que el móvil de referencia), la primera cota tras activarlo en menos de 1 s y la consulta a 10 Hz sin pasar de 16 ms.
- **Reproducción sintética:** `rs3-raster`: un recorrido que cruza un ráster con un hueco sin dato y su resultado esperado calculado aparte.
- **Manual o de campo:** demo en el emulador (criterios de finalización); en campo, la puerta G2b, que hace el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, pantallas | `RasterImportViewModelTest`, `RasterImportScreenTest` |
| RF-2 | A1 | `RasterReaderTest`, `GeoTiffRasterReaderTest`, `AsciiGridReaderTest`, `TiffDecoderTest` |
| RF-3 | A1, A2 | `GeoTiffRasterReaderTest`, `RasterImporterTest`, `RasterImportViewModelTest` |
| RF-4 | A1 | `GeoTiffRasterReaderTest`, `RasterImportViewModelTest` |
| RF-5 | A1, D6 | `RasterImporterTest`, `RasterImportViewModelTest` |
| RF-6 | A1 | `RasterReaderTest`, `RasterImportScreenTest` |
| RF-7 | A2 | `GeoTiffRasterReaderTest`, `AsciiGridReaderTest`, `RasterImporterTest` |
| RF-8 | A1, `RasterCrsResolver` | `RasterCrsResolverTest`, `RasterImportViewModelTest` |
| RF-9 | A2, A3 | `RasterElevationTest`, `RasterImportViewModelTest` |
| RF-10 | A3, D3 | `RasterElevationTest` |
| RF-11 | `RasterExtent` | `RasterImportViewModelTest`, `RasterImportScreenTest` |
| RF-12 | `RasterExtent` | `RasterImportViewModelTest` |
| RF-13 | A2, D4 | `RasterImporterTest`, `RasterImportViewModelTest` |
| RF-14 | A2, Room v22 | `RasterTileCodecTest`, `RasterImporterTest`, `RasterRepositoryTest` |
| RF-15 | A2 | `RasterImportViewModelTest` |
| RF-16 | A2 | `RasterImporterTest`, `RasterImportScreenTest` |
| RF-17 | D8 | `RasterRepositoryTest`, `SurfacesSheetTest` |
| RF-18 | Room v22 | `RasterRepositoryTest`, `SurfacesSheetTest` |
| RF-19 | A5 | `StakeoutControllerRasterTest` |
| RF-20 | Copia | `RasterBackupTest` |
| RF-21 | A3, A4 | `RasterElevationTest`, `ActiveRasterTest`, `RasterPerformanceTest` |
| RF-22 | A3 | `RasterElevationTest`, `ActiveRasterTest` |
| RF-23 | A3 | `RasterElevationTest` |
| RF-24 | A5 | `StakeoutControllerRasterTest`, `StakeoutViewModelRasterTest`, `CollectorRasterTest` |
| RF-25 | A5 | `StakeoutMeasureRasterTest` |

## Pendiente para otras specs
- S017b (fotos) pasa a la versión 23 de la base de datos (`AutoMigration(22, 23)`); su `plan.md` aún habla de una versión anterior y solo se corrige con permiso para tocar `specs/`.
- S026 (información geométrica): pendientes y volúmenes entre superficies siguen fuera.

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: la fila S045 pasó a `spec` (con la conversión ortométrica/elipsoidal) y pasa a «implementada» al final; S017b pasa a la v23.
- `docs/glosario.md`: «Ráster de cotas» (`ElevationRaster`), «Celda sin dato» (`NoDataCell`); «Superficie (MDT/TIN)» y «Superficie activa» incluyen el ráster.
- `specs/042-superficies-landxml/spec.md`: su «Fuera de alcance» del ráster apunta a S045. **No se toca sin que lo pidas.**
- `specs/pendientes/README.md`: S045 ya no está en `pendientes/`; el enlace del índice se corrige al mover la carpeta.
