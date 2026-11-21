# Plan técnico — Spec 034

> Lee antes: `docs/constitution.md` (nº 2 precisión frente a pyproj, nº 4 red solo para lo que el usuario pide, nº 6 núcleo puro), `docs/arquitectura.md` (ADR-2, ADR-3, ADR-4, ADR-5, ADR-8, ADR-13), `docs/rnf-transversales.md` (RNF-T4, T6, T8, T14), `specs/034-biblioteca-sc-mundial/spec.md`, `specs/032-proyecciones-completas/plan.md` y `specs/033-transformaciones-datum/plan.md`.

**Ninguna dependencia nueva** (Gradle ni Python): el catálogo se genera con `pyproj` (ya fijado en `tools/pyproject.toml`), se guarda como texto comprimido con `java.util.zip` y se descarga con OkHttp (ADR-13, ya está). **Room pasa de v36 a v37** (la siguiente spec con migración será v38).

## Estructura de módulos
- `core:model` (JVM puro):
  - `ProjectCrs` deja de ser un `enum` y pasa a ser una **interfaz sellada** con los mismos miembros que usa hoy el código (`name`, `epsg`, `datum`, `isUtm`, `utmZone`, `area`, `heightSystems`, `correctionsDatum`) y dos implementaciones: `SpainCrs` (el enum actual, sin cambiar una línea de su comportamiento: RF-25) y `LibraryCrs` (un SC de la biblioteca con su definición completa, RF-13). El `companion` de `ProjectCrs` conserva `ETRS89_UTM30…`, `entries`, `of`, `suggested` y `valueOf`, para que el código que usa los diez SC de España compile sin tocarlo.
  - `CrsDefinition` (datos de un SC de la biblioteca: nombre, EPSG, elipsoide, meridiano de origen, proyección con sus parámetros, unidades, ámbito, transformación elegida con sus pasos y rejillas) y su texto estable (RF-13).
- `core:geodesy` (JVM puro), paquete nuevo `es.topophone.core.geodesy.catalog`:
  - Lector y modelo del catálogo (RF-4): `CrsCatalog`, `CrsEntry`, `DatumEntry`, `TransformationOption`, `GridEntry`, `GridLicense`.
  - Búsqueda (RF-2, RF-3, RF-5): `CrsSearch`.
  - Construcción del cálculo (RF-9 a RF-12, RF-14): `CrsBuilder` (de `CrsDefinition` a `ProjectionFactory` + `DatumTransformation` de S033), `TransformationPicker` (RF-10), `GridRequirement` (qué rejillas faltan).
  - `GeoidFromGrid` (modelo de geoide leído de una rejilla instalada, RF-14 a RF-16).
  - Recurso `src/main/resources/catalog/crs.tsv.gz` (≤ 2 MB, generado).
- `core:survey`: `CoordinateEngine` (nuevo): del punto geodésico del suelo al SC del proyecto y a la inversa, con factores y altura, para `SpainCrs` (delegando en el código de S008, sin cambios de resultado) y para `LibraryCrs` (RF-26, RF-27, RF-28). Los siete sitios que hoy llaman a `Utm`/`Ed50` por su cuenta pasan a usarlo (RF-28).
- `data`:
  - Room v37: `projects.crs_definition` (texto, NULL en los de España), `projects.geoid_grid` (nombre de la rejilla de geoide, NULL = el de S008 o elipsoidal), tablas `favorite_crs` y `recent_crs` (RF-6 a RF-8). El `.topoproj` y la copia de seguridad llevan la definición (RF-13, S013).
  - `GridStore` (rejillas instaladas en `filesDir/grids/`, listado, uso por proyecto, borrado: RF-23) y `GridDownloader` (RF-19 a RF-22).
- `app`: selector de SC con búsqueda (RF-1 a RF-8), ficha (RF-9 a RF-12, RF-14), descargas y rejillas instaladas (RF-19, RF-20, RF-23), altura/geoide en el formulario (RF-14 a RF-16), aviso de formatos no disponibles (RF-29), factores en la pantalla de coordenadas (RF-27).
- `tools/geodesia/catalogo.py` (nuevo) con tests en `tools/geodesia/test_catalogo.py`: genera `crs.tsv.gz` y los vectores `fixtures/geodesia/catalogo_*.csv`.

## Modelo de datos
**Catálogo (texto TSV comprimido, una sección por tabla; lo escribe `catalogo.py`, lo lee `CrsCatalog`):**
```
#ellipsoids   code  name  a  invf
#datums       code  name  ellipsoid  primeMeridianDeg  area(S,W,N,E)  options…
#option       datum  target(ETRS89|WGS84)  name  epsgOp  accuracyM  area(S,W,N,E)  steps
              steps = null | h3(tx,ty,tz) | h7(PV|CF, tx,ty,tz,rx,ry,rz,s) | mb(…, px,py,pz) | grid(name) | encadenados con «>»
#grids        name  type(HORIZONTAL|GEOID)  sizeB  sha256  licenseId  source  sourceUrl  verticalCrs  area(S,W,N,E)
#licenses     id  name  open(0|1)  url
#crs          epsg  name  kind(PROJ|GEO)  datum  method  params…  unit  area(S,W,N,E)  countries(ISO,…)
```
- Ejes: solo entran los SC con ejes Este–Norte (los de orden N–E se normalizan a E–N, como hace TopoPhone en todo el resto; los orientados al oeste o al sur no entran).
- Descartados al generar (RF-4): obsoletos, método que S032/S008/S009 no calcula (Albers, Cassini, Krovak, TM orientada al sur…), sin transformación a ETRS89 o a WGS84, o que no pasan el contraste con pyproj de los tres puntos de prueba.

**`CrsDefinition` guardado en el proyecto (RF-13)**, texto de una línea, versionado:
```
v1|25830|ETRS89 / UTM zone 30N|ellps=GRS80|pm=0|method=tm|lat0=0|lon0=-3|k0=0.9996|x0=500000|y0=0|unit=m|datumOp=null|grids=
```
Con el datum, la proyección, las unidades y los pasos de la transformación elegida (`datumOp=h7(PV,…)` o `grid(nombre)`), de modo que ni una actualización del catálogo ni un fichero ausente lo cambian. Unos cientos de bytes.

**Room v37** (`MIGRATION_36_37`, con su test en el banco de migraciones de S006): `ALTER TABLE projects ADD COLUMN crs_definition TEXT`, `… geoid_grid TEXT`; `CREATE TABLE favorite_crs(epsg INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)`; `CREATE TABLE recent_crs(epsg INTEGER PRIMARY KEY, used_at INTEGER NOT NULL)`. Ninguna columna existente cambia (RF-25). `projects.crs` guarda el nombre del enum de España, y para un SC de la biblioteca `LIBRARY:<epsg>` con la definición en `crs_definition`.

**Rejillas instaladas**: ficheros en `filesDir/grids/<nombre>`; no hay tabla (el listado sale del directorio y del catálogo; el «proyecto que la usa» sale de las definiciones de los proyectos). Las descargas van a `<nombre>.part` y se renombran al terminar.

## Algoritmos
- **A1. Generar el catálogo (RF-4; ADR-3).** (1) `pyproj.database.query_crs_info` de CRS proyectados y geográficos no obsoletos. (2) Para cada uno, método y parámetros de `CRS.coordinate_operation`, y su datum. (3) Transformaciones del datum a ETRS89 y a WGS84 con `TransformerGroup`: se guardan las que son nulas, Helmert (traslaciones, PV, CF), Molodensky-Badekas y rejilla de desplazamiento, y cadenas de ellas, con precisión y ámbito; las demás se descartan. (4) Se calcula el contraste de tres puntos por SC (centro del ámbito y dos más) con `pyproj` y se escribe `catalogo_<método>.csv`; el SC que no se pueda reproducir en el catálogo con la lógica de S032/S033 no entra. (5) Rejillas y licencias: tamaño y SHA-256 de `https://cdn.proj.org/files.geojson`; ese fichero no trae la licencia, que se anota en una tabla curada por `source_id` en `catalogo.py` (abierta si es CC-BY, CC0, OGL, dominio público…; las demás, «no clara», con la URL de la fuente) (RF-20). (6) Todo ordenado y sin marcas de tiempo, para que la regeneración sea idéntica.
- **A2. Búsqueda (RF-2, RF-3, RF-5).** Normalizar (minúsculas, sin tildes, sin signos) el nombre, el código y los países de cada SC al cargar. Una consulta se parte en palabras; entra un SC si todas aparecen como prefijo de alguna palabra o como subcadena del código. Orden: España primero, luego los favoritos/recientes ya vistos, luego por puntuación (código exacto > nombre exacto > prefijo) y nombre. El filtro de país mira la lista de países del SC y su ámbito.
- **A3. Elegir la transformación (RF-10, RF-11).** Para el datum del SC y el datum de las correcciones (ETRS89 y REGCAN95 se tratan como ETRS89; ITRF/WGS84 como WGS84), se toman las opciones cuyo ámbito contiene el centro del proyecto (o del SC si no hay), y se ordenan por precisión (menor `accuracyM`) y, a igualdad, por la que no necesita rejilla. Se propone la primera y se ofrecen las demás.
- **A4. Cálculo en un proyecto con un SC de la biblioteca (RF-26 a RF-28).** `fromGround`: geográficas del suelo en el datum de las correcciones → inversa de la transformación elegida → geográficas en el datum del SC → `ProjectionFactory.forward` → factores de la proyección → altura (elipsoidal, o altitud con el geoide elegido interpolado en la posición). `toGround` hace lo mismo al revés. Una rejilla ausente da el resultado «no disponible» con su nombre (S033/RF-12, RF-18); fuera del ámbito del SC, S006/RF-30.
- **A5. Descarga (RF-19 a RF-22).** `GET` con OkHttp por la red con internet (ADR-13, RNF-T4) a `<nombre>.part`; si no llega al tamaño esperado o se corta, se borra y se permite reintentar; al terminar se calcula SHA-256 en streaming y, si no coincide, se borra y se indica «dañado»; si coincide, `rename` atómico. Nunca se lee un `.part`.
- **A6. Rejilla de geoide instalada (RF-14 a RF-16).** `GeoidFromGrid` lee la rejilla con `GeoTiffGrid` (ADR-4) la primera vez y la guarda en memoria; fuera de su cobertura rechaza (ADR-5). Si la rejilla supera lo que cabe en memoria en el móvil de referencia se lee por teselas: se decide en T8 con la medida real de los ficheros del CDN.

## Contratos
- `CrsCatalog.search(query, country?)` → `List<CrsHit>` (nombre, EPSG, país, tipo; RF-3). `CrsCatalog.details(epsg)` → `CrsDetails` con la ficha (RF-9). `CrsCatalog.build(epsg, centre, correctionsDatum, choice?)` → `CrsBuildResult`: `Ready(definition)`, `NeedsGrids(definition, List<GridEntry>)` (RF-12) o `Unavailable(reason)` (no hay transformación, RF-4/RF-11).
- `CoordinateEngine.fromGround(...)` y `toGround(...)` devuelven `ProjectPosition` y `GroundPoint` con los `PositionUnavailable` actuales, ampliados con `MISSING_GRID(name)`.
- `GridStore.state(name)`: `Installed(sizeB)`, `Missing`, `Downloading`, `Damaged`. `GridDownloader.download(entry)` es un `Flow` de progreso y un resultado `Done | Interrupted | Damaged`.
- Textos de usuario en `strings.xml`, con `docs/glosario.md`. Los avisos de formatos (RF-29) salen de un único predicado `ProjectCrs.supportsFormats`.

## Decisiones técnicas
| # | Decisión | Motivo | Alternativa descartada |
|---|---|---|---|
| D1 | Catálogo generado con pyproj y guardado como TSV comprimido, leído con código propio | ADR-3; sin parser JSON ni dependencia nueva en un módulo puro; ~6.000 filas caben en unos cientos de KB (de 5.363 CRS proyectados activos, unos 5.100 tienen un método calculable) | JSON con una librería; SQLite con `proj.db` (varios MB) |
| D2 | `ProjectCrs` pasa a interfaz sellada con `SpainCrs` y `LibraryCrs` y conserva la API actual | RF-25: los proyectos de España no cambian ni un bit y los ~113 usos de `project.crs` siguen compilando | Un campo `libraryCrs` aparte con el enum como comodín (los sitios no migrados calcularían mal sin avisar) |
| D3 | El cálculo de coordenadas del proyecto se concentra en `CoordinateEngine` y se migran los siete sitios que llaman a `Utm`/`Ed50` | RF-26 a RF-28: un solo camino; los de España delegan en el código de S008 (RF-25) | Duplicar la lógica en cada sitio |
| D4 | La definición completa del SC se guarda en el proyecto | RF-13: no cambia si se actualiza la biblioteca; viaja en `.topoproj` y en la copia | Guardar solo el EPSG y resolverlo en el catálogo |
| D5 | Sin tabla de rejillas instaladas; el directorio es la fuente de verdad | RF-22, RNF-T6: sin riesgo de que la tabla y el disco discrepen | Tabla Room con la ruta y el SHA |
| D6 | La licencia se cura a mano por `source_id`, no se deduce | `files.geojson` no trae licencia; RNF-T14 la exige visible; «no clara» se ofrece con aviso (entrevista, RF-20) | Ocultar las rejillas de licencia desconocida (decisión del usuario: todas, avisando) |
| D7 | Los formatos DXF/SHP/GPKG/KML/LandXML/ráster se desactivan con un aviso en un proyecto con SC de la biblioteca | RF-29; los hace S057 | Hacerlos todos en S034 (la spec se haría inmanejable) |
| D8 | ETRS89 y REGCAN95 comparten opciones de transformación; ITRF/WGS84 usa las de WGS84 | Las redes españolas coinciden con ETRS89 dentro de la tolerancia de las transformaciones de EPSG; el aviso de 0,9 m entre ITRF y ETRS89 ya existe (arquitectura) | Un tercer conjunto de opciones por REGCAN95 sin datos en EPSG que lo justifiquen |

## Estrategia de tests
- **Python (`tools`)**: `test_catalogo.py` (determinismo, recuentos, sin SC obsoletos, ejes E–N, tamaño de `crs.tsv.gz` ≤ 2 MB, cada rejilla con SHA-256 y licencia).
- **Unitarios JVM** en `core:geodesy`/`core:survey`/`core:model`: lector del catálogo, búsqueda (casos «25830», «etrs89 utm 30», «Francia»; tildes y mayúsculas; tiempo < 300 ms), contraste con pyproj de tres puntos por SC (`catalogo_*.csv`, ≤ 1 mm, ida y vuelta ≤ 0,1 mm), elección de transformación, geoides contra pyproj con la misma rejilla (≤ 1 mm; solo los ficheros pequeños del CDN van en `fixtures/geodesia/rejillas/`, con su licencia; los grandes se contrastan al regenerar con `catalogo.py` y se anotan en «Validación»), `CoordinateEngine` (los diez SC de España dan lo mismo que S008 bit a bit; el SC de la biblioteca da lo de pyproj).
- **`data`** (Robolectric): migración v36 → v37 sin pérdida, `GridDownloader` con un servidor falso de OkHttp (completo, cortado, SHA alterado), `GridStore`, favoritos y recientes, `.topoproj` con definición.
- **`app`** (Robolectric/Compose): selector, ficha, descarga, rejillas instaladas, formulario con geoide, aviso de formatos (RNF-T8: zonas de 56 dp, etiquetas de TalkBack).
- **Emulador**: la demo de «Criterios de finalización». **Campo**: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2, RF-3, RF-5 | Selector, A2 | `CrsSearchTest`, `CrsPickerTest` |
| RF-4 | A1, catálogo | `CatalogContentTest`, `CatalogVectorsTest`, `test_catalogo.py` |
| RF-6, RF-7, RF-8 | Favoritos y recientes | `FavoriteCrsTest`, `RecentCrsTest`, `CrsPickerShortcutsTest` |
| RF-9, RF-10, RF-11, RF-12 | Ficha, A3 | `CrsDetailsTest`, `TransformationPickerTest`, `CrsDetailsScreenTest` |
| RF-13 | `CrsDefinition`, Room v37 | `CrsDefinitionTest`, `Migration36To37Test`, `ProjectBackupCrsTest` |
| RF-14, RF-15, RF-16 | A6, formulario | `GeoidFromGridTest`, `ProjectFormGeoidTest` |
| RF-17, RF-25 | Recursos incluidos; no regresión | `BundledGridsTest`, `SpainCrsUnchangedTest` |
| RF-18 | A4, apertura de proyecto | `MissingGridProjectTest` |
| RF-19, RF-20, RF-21, RF-22 | A5 | `GridDownloaderTest`, `GridDownloadScreenTest` |
| RF-23 | `GridStore` | `GridStoreTest`, `InstalledGridsScreenTest` |
| RF-24 | A4 sin rejilla | `OfflineProjectTest` |
| RF-26, RF-27, RF-28 | A4, `CoordinateEngine` | `CoordinateEngineTest`, `LibraryProjectFlowTest` |
| RF-29 | `supportsFormats` | `FormatsDisabledTest` |
