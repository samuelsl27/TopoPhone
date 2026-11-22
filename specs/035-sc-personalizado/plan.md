# Plan técnico — Spec 035

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/035-sc-personalizado/spec.md`, y los planes de S034 (biblioteca) y S057 (formatos).

## Estructura de módulos
- `core:model` → `CustomCrs` (un SC de Mis SC), `CustomHeight`, `CustomCrsDraft` y `ProjectCrsChange` (un cambio del historial); `CrsDefinition.isCustom` (RF-1, RF-5, RF-10, RF-20).
- `core:geodesy` → `CustomCrsBuilder` (validación y definición desde el borrador), `CrsProbe` (coordenadas de prueba, RF-7), `CustomCrsWkt` (WKT1 de GDAL y de ESRI desde una definición, RF-23) (RF-2 a RF-8, RF-23).
- `core:formats` → `CustomCrsJson` (exportar e importar), `CustomCrsRecognition` (un `.prj` o un GeoPackage reconocido como el SC del proyecto) y el uso de `CustomCrsWkt` en `ShpPrjDefinitions` y `GpkgSrsDefinitions` (RF-12 a RF-15, RF-23, RF-24).
- `core:survey` → el cambio de SC: `CrsChangePlan` (qué puntos no se pueden calcular antes de aplicar, RF-18, RF-19) y la altura constante en `LibraryEngine` y `SourceHeights` (RF-5).
- `data` → tablas `custom_crs` y `project_crs_history` (Room v38), `CustomCrsRepository`, `ProjectCrsHistoryRepository`, y su paso por la copia completa y los paquetes (RF-9 a RF-11, RF-16, RF-20 a RF-22).
- `app` → editor (`CustomCrsEditor*`), Mis SC en el selector de S034, en el formulario de proyecto, en Ajustes y en el selector de SC de origen de S057, importar y exportar JSON, y el historial del proyecto con «Volver al SC anterior» (RF-1, RF-7 a RF-9, RF-13 a RF-15, RF-17 a RF-21).

## Modelo de datos
Un SC personalizado **es una `CrsDefinition` de S034 con `epsg = 0`**: el proyecto sigue guardándolo como `LibraryCrs` con su copia de la definición (RF-11, S034/RF-13), así que todo lo que calcula con la biblioteca (captura, replanteo, mapa, COGO, S057) funciona sin cambios. `isCustom` (`epsg == 0`) solo decide qué se muestra («SC personalizado» en vez de «EPSG:0») y cómo se declara en los ficheros.

```
CustomCrs(id: String, name: String, definition: CrsDefinition, height: CustomHeight, createdAtMs, modifiedAtMs)
CustomHeight = Ellipsoidal | Geoid(grid: String) | ConstantGeoid(undulationM: Double)
CrsDefinition.epsg = 0, name = el del usuario, datumCode = 0, datumName = «Personalizado» (o el del SC del que se parte)
```

Room v38 (migración 37→38, solo tablas nuevas y una columna; S017b ya está hecha):

| Tabla | Campos |
|---|---|
| `custom_crs` | `id` (PK), `name` (único sin mayúsculas), `definition` (texto de `CrsDefinition.toText()`), `height_kind`, `height_value` (grid o metros), `created_at_ms`, `modified_at_ms` |
| `project_crs_history` | `id` (PK), `project_id` (FK con borrado en cascada), `at_ms`, `from_crs` y `to_crs` (texto del SC: `name` o `LIB:<definición>` más `geoidGrid`/`geoidConstantM`), `kind` (`CHANGE` o `UNDO`) |
| `projects` | columna nueva `geoid_constant_m REAL NULL` (RF-5) |

`LibraryCrs` gana `geoidConstantM: Double?`; `geoidGrid` y `geoidConstantM` no pueden estar los dos. El texto de un SC del historial reutiliza el de `ProjectRepository` (`crs` + `crs_definition` + geoide), de modo que «Volver al SC anterior» funciona aunque el SC ya no esté en Mis SC (casos límite).

**JSON de un SC** (RF-12). `{"format":"topophone-crs","version":1,"name":…,"ellipsoid":{"a":…,"invF":…},"primeMeridianDeg":…,"projection":{"method":"tm","parameters":{…}},"unitMetres":…,"datum":{"name":…,"frame":"ETRS89","transformation":{"name":…,"accuracyM":…,"steps":"…"}},"height":{"kind":"geoid","grid":"…"}}`. Los números como `Double` de JSON sin redondeo (kotlinx.serialization escribe el `toString` más corto que vuelve exacto: ida y vuelta exacta). `Locale.ROOT` no hace falta con `kotlinx`, que no usa la regional.

## Algoritmos
1. **Validación del borrador** (RF-2 a RF-6): `CustomCrsBuilder.validate(draft)` da un mapa campo → motivo. Elipsoide: `a > 0`, `invF == 0` (esfera) o `invF > 1`; la proyección pasa por `ProjectionFactory.create` de S032 (sus errores de parámetro se pasan tal cual); Helmert por `HelmertParameters`; MB por `MolodenskyBadekasParameters`; rejilla: existe en el catálogo de rejillas o está instalada. Sin dos errores del mismo campo.
2. **De borrador a definición**: `CustomCrsBuilder.build(draft, installed)` devuelve `Ready`, `NeedsGrids(grids)` (RF-8) o `Invalid(errors)`. La transformación se escribe en la notación de pasos del catálogo (`null`, `H;A=…;B=…;inv=0;conv=PV;p=tx,ty,tz,rx,ry,rz,s[;px=…]`, `G;grid=…;inv=0`) para reutilizar `CrsDefinitions.resolve` y `CatalogTransformations.build` sin tocarlos. Helmert de 3 y 4 parámetros: los rotadores a 0 (y la escala a 0 en 3). Elipsoide del datum = el del SC.
3. **Coordenadas de prueba** (RF-7): `CrsProbe.forward(definition, grids, lat, lon)` y `inverse(…, e, n)` con `resolve` → proyección y transformación hasta el datum de las correcciones; se muestran en los dos sentidos y la ida y vuelta con su diferencia.
4. **Altura constante** (RF-5): `LibraryEngine.undulation` devuelve `geoidConstantM` cuando no hay rejilla; `SourceHeights` lo trata como un geoide del proyecto que existe.
5. **Cambio de SC** (RF-17 a RF-21): `CrsChangePlan.of(points, current, target)` calcula cada punto de las geodésicas brutas en el SC nuevo (los puntos ya se guardan por sus geodésicas, `arquitectura.md`) y da la lista de los que `target` no puede calcular (fuera del ámbito del SC, rejilla sin instalar); con la lista vacía se aplica sin preguntar. Aplicar = una transacción que cambia el SC del proyecto y escribe la fila del historial (RNF-T6). «Volver al SC anterior» = la misma transacción con `from`/`to` invertidos y `kind = UNDO`. Solo se ofrece en el cambio más reciente.
6. **WKT de un SC personalizado** (RF-23): `CustomCrsWkt.gdal(definition)` y `.esri(definition)`. TM → `Transverse_Mercator`, LCC → `Lambert_Conformal_Conic_1SP`/`_2SP`, OM A/B → `Hotine_Oblique_Mercator`/`_Azimuth_Center`; el datum con `TOWGS84[...]` si la transformación es Helmert de 3 o 7 parámetros PV (el WKT1 no admite más); con rejilla, MB o CF el WKT va sin `TOWGS84` y el aviso de S057/RF-25 lo dice. Para GeoPackage, `srs_id = 100000 + n` con organización `TOPOPHONE` y el WKT en `definition`. Reconocimiento (RF-24): `CustomCrsRecognition.sameAs(wkt, definition)` por método y parámetros a 1e-9 (misma lógica que `ShpCrsCatalog.sameDefinition` de S057).

## Contratos
- `CustomCrsBuilder.build(…): CustomBuildResult` = `Ready(definition)` | `NeedsGrids(definition, grids)` | `Invalid(errors: Map<CustomField, CustomError>)`.
- `CustomCrsJson.write(CustomCrs): String` y `read(text): JsonReadResult` = `Ok(CustomCrs)` | `Rejected(reason)` con `INVALID`, `UNKNOWN_VERSION`, `UNSUPPORTED_METHOD`, `OUT_OF_RANGE`.
- `CustomCrsRepository`: `observe()`, `save`, `duplicate`, `rename` (nombre único, sin mayúsculas ni espacios de más), `delete`, `import(crs, onConflict: REPLACE | KEEP_BOTH | SKIP)`.
- `ProjectCrsHistoryRepository.apply(projectId, newCrs)` y `undo(projectId)`: una transacción cada una.
- Textos nuevos en `strings.xml` (es/en): errores de cada campo, «SC personalizado», «Mis SC», motivos del JSON, historial.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| El SC personalizado es una `CrsDefinition` con `epsg = 0` | Todo el cálculo de S034 y S057 funciona sin cambios y el proyecto ya guarda la copia (RF-11) | Un tipo nuevo de `ProjectCrs`: obligaba a tocar los ~30 sitios que ramifican por `LibraryCrs` |
| JSON con `kotlinx.serialization` (ya está en `core:formats`) | Cumple la constitución: ninguna dependencia nueva; ida y vuelta exacta de los `Double` | Texto a mano: más código y más errores; una librería nueva |
| Pasos de la transformación en la notación del catálogo | `resolve` y `CatalogTransformations` ya los calculan y están contrastados con pyproj | Un modelo de transformación propio: duplicaba S033 |
| Historial del proyecto en tabla propia con el SC como texto | No depende de que el SC siga en Mis SC; viaja con la copia y el paquete | Guardar solo una referencia a `custom_crs`: se rompe al borrar |
| Deshacer solo en el cambio más reciente | Cada cambio se calcula de las geodésicas, así que cualquiera sería válido, pero encadenar deshacer con otros cambios sin una pila es más difícil de explicar | Deshacer cualquier cambio del historial |
| WKT1 con `TOWGS84` solo para Helmert 3/7 PV | Es lo que expresa WKT1; QGIS y GDAL lo leen | WKT2: GDAL y ArcGIS lo leen peor y S057 ya usa WKT1 |
| Altura constante como `geoidConstantM` de `LibraryCrs` | Una columna y una rama en `LibraryEngine`; sin rejilla | Una rejilla sintética constante: más pesada y más lenta |

## Estrategia de tests
- Unitarios JVM: `CustomCrsBuilderTest` (validación por campo; esfera; todos los tipos de transformación), `CustomCrsVectorsTest` (un SC personalizado con los parámetros de uno de la biblioteca —UTM 30N, Lambert-93, Stereo 70, GGRS87, ED50 con rejilla— da lo mismo que él, ≤ 1 mm, ida y vuelta ≤ 0,1 mm, con vectores de pyproj), `CrsProbeTest`, `CustomCrsJsonTest` (ida y vuelta exacta con 10.000 `Double` aleatorios; versión, método y números fuera de rango), `CrsChangePlanTest`, `CustomCrsWktTest` (el WKT se lee con pyproj y da las mismas coordenadas, ≤ 1 mm), `CustomCrsRecognitionTest`.
- Room/Robolectric: `CustomCrsRepositoryTest` (nombres únicos, reemplazar/conservar/omitir), `CrsHistoryTest` (aplicar y deshacer en una transacción, historial tras borrar el SC), `Migration37To38Test`, copia completa y paquete con Mis SC y el historial (S013).
- Compose/Robolectric: editor (campo marcado, vista previa), Mis SC en el selector, importar JSON con conflicto, historial con «Volver al SC anterior».
- Rendimiento: `CrsChangePerformanceTest` (10.000 puntos < 2 s) y el editor < 100 ms por cambio de campo (cálculo de la vista previa fuera del hilo de la UI).
- Manual o de campo: demo del emulador (crear, probar, exportar, importar, cambiar y deshacer); la de campo con un SC de un cliente queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | `CustomCrsDraft.of`, editor | `CustomCrsBuilderTest`, `CustomCrsEditorTest` |
| RF-3, RF-4 | pasos de transformación y proyección en el builder | `CustomCrsBuilderTest`, `CustomCrsVectorsTest` |
| RF-5 | `CustomHeight`, `geoidConstantM` | `CustomHeightTest`, `SourceHeightsTest` |
| RF-6 | `validate` | `CustomCrsBuilderTest`, `CustomCrsEditorTest` |
| RF-7 | `CrsProbe` | `CrsProbeTest` |
| RF-8 | `NeedsGrids` y `MissingGridDownloads` | `CustomCrsBuilderTest`, `CustomCrsEditorTest` |
| RF-9 | Mis SC en el selector y en Ajustes | `MyCrsListTest` |
| RF-10 | `CustomCrsRepository` | `CustomCrsRepositoryTest` |
| RF-11 | copia de la definición en el proyecto | `CrsHistoryTest` |
| RF-12, RF-13, RF-14, RF-15 | `CustomCrsJson`, importación | `CustomCrsJsonTest`, `ImportCustomCrsTest` |
| RF-16 | copia completa | `CustomCrsBackupTest` |
| RF-17 a RF-19 | `CrsChangePlan`, formulario | `CrsChangePlanTest`, `ChangeProjectCrsTest` |
| RF-20, RF-21 | `ProjectCrsHistoryRepository` y pantalla | `CrsHistoryTest`, `ProjectHistoryScreenTest` |
| RF-22 | paquete y copia | `CustomCrsBackupTest` |
| RF-23, RF-24 | `CustomCrsWkt`, `CustomCrsRecognition` | `CustomCrsWktTest`, `CustomCrsFormatsTest` |

## Cambios al implementar
- **T1 y T11 (vectores).** `personalizado.csv` tiene ocho casos (TM, LCC 1SP y 2SP, estereográfica y las dos Oblique Mercator; sin cambio de datum, Helmert de 3, de 7 PV y de 7 CF) calculados con una cadena de pasos explícita de PROJ, sin pasar por el catálogo. Los parámetros de Helmert son inventados: con los reales de Pulkovo 1942, PROJ reconocía el datum al leer el WKT y usaba la operación de EPSG, con lo que el contraste no medía lo nuestro. `personalizado_wkt.csv` (tabuladores, porque el WKT lleva comas) guarda el WKT1 que debe escribir la app y lo que da PROJ leyéndolo.
- **«Ninguna» y el elipsoide.** «Ninguna» deja la latitud y la longitud como están (el datum es el de las correcciones). Si el usuario escribe a la vez un elipsoide distinto del GRS 1980 y «Ninguna», PROJ (que conserva las coordenadas cartesianas) y la app difieren ~1 mm; el editor no lo impide.
- **T3.** `CustomCrsDraft` y `CustomTransformation` están en `core:geodesy`, no en `core:model`: necesitan leer los pasos del catálogo. `CustomTransformation.Kept` conserva las cadenas de la biblioteca que el editor no sabe expresar (varios pasos o un paso inverso); la validación solo exige que se puedan leer.
- **T4.** Un parámetro escrito fuera de rango se marca aunque falten otros (`partialProblems` en el núcleo); sin eso, en LCC 2SP no se marcaba nada hasta tenerlos los seis.
- **T11 y T12.** Hay un solo WKT1 (el «OGC» de GDAL) para el `.prj` y el GeoPackage, no el de ESRI de S057. `TOWGS84` va en posición vectorial (un Helmert de marco de coordenadas lleva las rotaciones con el signo cambiado, contrastado con PROJ). Una rejilla, Molodensky-Badekas o una cadena no caben en WKT1: el SC **no se escribe** (`undefined` / sin `.prj`) y las coordenadas se exportan igualmente (S057/RF-25). La estereográfica polar C tampoco tiene forma en WKT1.
- **T13.** El WKT llega a la pantalla de importación en `GpkgCrs.Unavailable.wkt` (un `.prj` sin código, o un GeoPackage con `srs_id` ≥ 100000 sin autoridad), y `CustomCrsRecognition` lo compara con el SC del proyecto por método, parámetros, unidad y elipsoide.
- **T14.** Room v38 añade al historial `from_height_system` y `to_height_system` (el plan solo decía SC anterior y nuevo): un cambio puede cambiar también la altura y «Volver al SC anterior» tiene que devolverla.
- **T17.** La ondulación constante también se guarda en los valores por defecto de Ajustes (`default_geoid_constant_m`).
- **T18 (lo importante).** Los puntos guardan `easting`, `northing` y `height` (y los de la medida) como «sus coordenadas actuales»; antes de S035 nada los recalculaba al cambiar el SC, así que cambiar el huso dejaba valores viejos. Ahora el cambio reescribe esas columnas derivadas de **todos** los puntos del proyecto (también los de la papelera) desde sus geodésicas, que no se tocan. **No se reproyectan** los objetos que se importaron ya en coordenadas del proyecto (dibujos CAD, superficies, rásteres, ejes, diseño de replanteo guardado en un punto): conservan las coordenadas que tenían al importarse. Con un cambio de huso o de SC a otro lugar quedan desplazados hasta que se vuelvan a importar; queda anotado como límite conocido y no hay aviso en pantalla.
- **«Mismo datum».** `CrsChange.sameDatum` compara el elipsoide, el meridiano de origen y la transformación: ETRS89, REGCAN95 y cualquier SC con GRS 1980 sin cambio de datum son uno; ED50 es el de la rejilla del IGN (o el datum 6230); WGS 84 no es ETRS89.
- **T19.** «Volver al SC anterior» solo se ofrece si el SC y las alturas del proyecto siguen siendo los del último cambio.
- **T20.** Restaurar una copia reemplaza todas las filas, también las de Mis SC: no hay colisión de nombres que resolver (el caso de la tabla de casos límite no se da).
- **T21 a T23.** Un cero se deja en blanco en el formulario (vacío vale 0): con `0.0` escrito, el usuario tenía que borrarlo para escribir encima. Los campos usan el teclado ASCII porque el numérico de Android no deja escribir el signo menos. Se acepta la coma o el punto decimal.
- **T24.** Mis SC vive dentro del selector de S034 (`CrsLibraryFlow`), así que el formulario, los valores por defecto de Ajustes y el SC de origen de las importaciones de S057 lo ofrecen sin tocar esas pantallas. `CustomCrs.toProjectCrs` fija el datum de las correcciones según la transformación del SC (como un SC de la biblioteca). Un SC personalizado se ve como «SC personalizado» y no como «EPSG:0» en el formulario, el SC de origen y la exportación a Shapefile.
- **T26.** Al editar un proyecto ahora se ofrece la biblioteca y Mis SC, solo del mismo datum; S034/RF-13 decía que al editar no se cambiaba de SC desde la biblioteca y su test se cambió. El resto del formulario se guarda con el SC de antes y el cambio lo hace el historial en una transacción.
- **T27.** El historial se abre desde «Datos del proyecto». También se corrige ahí el nombre del SC de un proyecto de la biblioteca (`crsLabel` lo escribía como geográficas).

## Validación (22/11/2026)
- **Trazabilidad.** `rf_report.py`: S035 con 24 RF, 24 con test y 0 pendientes.
- **Constitución nº 2.** Los ocho casos de `personalizado.csv` frente a pyproj a ≤ 1 mm y con la ida y vuelta a ≤ 0,1 mm (`CustomCrsVectorsTest`), cinco SC de la biblioteca reproducidos con parámetros sueltos al mismo valor (`CustomCrsVectorsTest`) y el WKT de cada caso leído por PROJ a ≤ 1 mm de los parámetros sueltos (`test_personalizado_wkt.py`). Las coordenadas de prueba del editor de Madrid dan el Este y el Norte de pyproj (`CustomCrsEditorProbeTest`).
- **Exactitud.** El JSON vuelve igual con 10.000 `Double` aleatorios y con la regional es-ES (`CustomCrsJsonTest`).
- **Rendimiento.** 10.000 puntos: planificar el cambio de SC en menos de 2 s (`CrsChangePlanTest`) y aplicarlo con la transacción completa en menos de 2 s (`CrsHistoryTest`, base en memoria); el editor valida cada cambio de campo en menos de 100 ms (`CustomCrsEditorProbeTest`).
- **Atomicidad.** Un fallo a mitad del cambio deja el SC, los puntos y el historial como estaban (`CrsHistoryTest`).
- **Migración.** `Migration37To38Test` conserva proyectos y puntos; `BackupMigrationTest` abre copias de las 37 versiones anteriores.
- **Dependencias.** Ninguna nueva (el JSON usa `kotlinx.serialization`, que ya estaba en `core:formats`); Room pasa a v38.
- **Construcción.** `./gradlew check` (T27, 22/11/2026), `pytest` de `tools`, `forbidden_files.py` y `assembleDebug` en verde.
- **Demo en el emulador (22/11/2026).** APK `standard` debug: Ajustes → «Mis SC» abre la lista vacía; «Nuevo SC» abre el editor; «Biblioteca…» abre el selector (sin Mis SC, como pide RF-1) y al elegir ED50 / UTM 30N trae su elipsoide y su rejilla de desplazamiento; falta la rejilla del ICGC (4 KB) y el editor lo dice con su descarga; el SC se guarda y aparece en Mis SC, y «Duplicar» crea «(copia)». Al editar un proyecto de ETRS89, «Buscar en la biblioteca» ofrece Mis SC y «Crear SC personalizado…», y elegir el SC de ED50 da «Solo se puede cambiar a un SC del mismo datum que el actual». No se probaron en el emulador la importación y la exportación del JSON (necesitan el selector de ficheros del sistema) ni el cambio de SC de un proyecto con puntos (cubierto en `ChangeProjectCrsTest`).
- **Lo que queda abierto.** (1) La demo de campo con un SC que dé el cliente queda para el usuario. (2) Los objetos importados en coordenadas del proyecto no se reproyectan al cambiar el SC (T18). (3) El WKT de un SC con rejilla, Molodensky-Badekas o cadena no se escribe, así que QGIS no lo reconoce (RF-23 solo cubre lo que WKT1 puede expresar).
