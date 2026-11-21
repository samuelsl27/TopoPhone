# Plan técnico — Spec 057

> Lee antes: `docs/constitution.md` (nº 2 precisión frente a pyproj, nº 4 red solo para lo que el usuario pide, nº 6 núcleo puro), `docs/arquitectura.md` (ADR-2, ADR-3, ADR-4, ADR-14), `docs/rnf-transversales.md` (RNF-T5, T7, T8, T9, T14), `specs/057-formatos-con-sc-de-biblioteca/spec.md`, `specs/034-biblioteca-sc-mundial/plan.md` y los planes de S029 a S031, S040, S042, S045, S048, S049, S053 y S055.

**Ninguna dependencia nueva** (Gradle ni Python). **Sin cambios de Room** (sigue en v37): el SC de origen de un dibujo o una superficie ya se guarda como texto en una columna y solo gana un valor nuevo.

## Estructura de módulos
- `tools/geodesia/catalogo_wkt.py` (nuevo) y `core/geodesy/src/main/resources/catalog/crs_wkt.tsv.gz` (generado): la definición WKT1 (la de `.prj` y la de `gpkg_spatial_ref_sys`) de cada SC del catálogo, en un recurso aparte que se lee solo al exportar (RF-26, RNF ≤ 1 MB).
- `core:geodesy.catalog`: `CrsWkt` (lector del recurso, WKT por EPSG) y `SourceCrsResolver` (de un EPSG y del proyecto al SC de origen con su transformación, RF-2, RF-8, RF-10).
- `core:model`: `CadSourceCrs.Library(definition)` (RF-1, RF-2), junto a `Project`, `Utm` y `Geographic`.
- `core:survey`: `SourceGround` (del vértice del fichero al suelo en el datum de las correcciones, RF-7, RF-11, RF-12), `ImportTransformation` (nombre, precisión, ámbito y rejillas del cambio de datum, y si pide confirmación, RF-8 a RF-10) y la adaptación de `CadProjector`, `SurfaceProjector`, `AlignmentProjector`, `RasterElevation`, `RasterTiler` e `ImportAreaFilter` (RF-13 a RF-16, RF-19, RF-20).
- `core:formats`: `GpkgCrsCatalog`, `ShpCrsCatalog`, `PrjReader` y el lector de GeoTIFF reconocen el SC por EPSG o por nombre en la biblioteca (RF-4 a RF-6); `GpkgSrsDefinitions` y `ShpPrjDefinitions` toman la definición de `CrsWkt` (RF-22, RF-23, RF-25).
- `data`: `CadRepository` y `SurfaceRepository` guardan y leen el SC de origen nuevo (`LIB:` + el texto de la definición de S034); los importadores de GeoPackage, Shapefile y KML usan el SC de cada capa (RF-16, RF-17).
- `app`: `SourceCrsPicker` común (el selector de S034 con el SC del proyecto marcado) en las pantallas de importación de DXF, LandXML (superficies y ejes), ráster, Shapefile y GeoPackage (RF-1, RF-3, RF-6); vista previa con la transformación y su precisión, confirmación y descarga de la rejilla que falta (RF-8 a RF-10, RF-18); exportaciones sin la restricción de S034/RF-29 (RF-21 a RF-25, RF-28).

## Modelo de datos
```kotlin
sealed interface CadSourceCrs { Project; Utm(datum, zone); Geographic(datum); data class Library(val definition: CrsDefinition) }
```
- Se guarda como `LIB:<CrsDefinition.toText()>` (`cad_drawings.source`, `surfaces.source`); los valores `PROJECT`, `UTM:…` y `GEO:…` no cambian (RF-27).
- La definición del SC de origen se construye con `CrsCatalog.build(epsg, correcciones del proyecto, centro)` (S034/RF-13): ya lleva la transformación de su datum al de las correcciones, así que no hace falta nada más para recalcular al abrir el proyecto sin biblioteca.
- `crs_wkt.tsv.gz`: una línea por SC, `epsg<TAB>nombre<TAB>wkt1<TAB>tipo` (WKT1 GDAL, con `AUTHORITY["EPSG","n"]`), ordenada y sin marcas de tiempo.

## Algoritmos
- **A1. Del fichero al proyecto (RF-7, RF-11, RF-12).** Con el SC de origen igual al del proyecto no se hace nada (`CadSourceCrs.Project`, como hoy). Con uno de la biblioteca: (x, y) × unidad → metros → `projection.inverse` (lat, lon en el datum de origen) → `transformation.forward` hasta el datum de las correcciones → `CoordinateEngine.fromGround` del proyecto → Este y Norte (S034/RF-26). Un SC de origen geográfico salta la proyección. Una rejilla fuera de cobertura o el ámbito del proyecto rechazan el elemento entero (S040/RF-18).
- **A2. Cambio de datum y aviso (RF-8, RF-9).** `ImportTransformation.of(source, project, centro)` elige con `TransformationPicker` (S034/RF-10) la más precisa que cubre la zona del fichero (su recuadro) o el centro del proyecto; devuelve su nombre, `accuracyM`, ámbito y rejillas. Pide confirmación si los datums difieren y `accuracyM` es mayor que 1 m o desconocida; si los datums coinciden (paso nulo) no hay aviso.
- **A3. Rejillas que faltan (RF-10).** `GridRequirement.missing` (S034) con la transformación elegida; si falta alguna, la importación no empieza y la pantalla ofrece `GridDownloadDialog` (S034/RF-19).
- **A4. Reconocer el SC de un fichero (RF-4 a RF-6).** (1) EPSG declarado (`gpkg_spatial_ref_sys`, GeoKeys de GeoTIFF, `AUTHORITY` del `.prj`) → `CrsCatalog.crsByEpsg`; (2) sin EPSG, el nombre del `.prj` normalizado (minúsculas, sin signos ni espacios) contra el de la biblioteca y sus equivalentes de ESRI (`ETRS_1989_UTM_Zone_30N`); si da uno solo se propone, y si da cero o varios, se pide elegir; (3) fuera de la biblioteca → hay que elegir (S048/RF-9).
- **A5. Alturas (RF-19, RF-20).** Una cota declarada ortométrica pasa al sistema del proyecto con `LibraryEngine.undulation` (S034/A6); sin geoide o sin instalar, la geometría entra y las cotas quedan marcadas como no convertibles con su motivo; la altura elipsoidal no se convierte.
- **A6. Definiciones WKT (RF-22 a RF-26).** `catalogo_wkt.py` pide a pyproj `CRS.to_wkt(WKT1_GDAL)` de cada SC del catálogo; si PROJ no puede expresarlo en WKT1 se omite y la exportación avisa. GeoPackage y Shapefile leen `CrsWkt.of(epsg)` y escriben el EPSG y la definición; el `.prj` usa WKT1 ESRI (`to_wkt(WKT1_ESRI)`) en una segunda columna.

## Contratos
- `SourceGround.toGround(x, y): GroundResult` (el de S034), con `Unavailable(OUTSIDE_DATUM_GRID | MISSING_GRID | OUTSIDE_CRS_AREA)`.
- `ImportTransformation(name, accuracyM, area, gridNames, needsConfirmation)` o `null` si los datums coinciden.
- `SourceCrsResolver.resolve(epsg, project, centre): CrsBuildResult` (reutiliza S034): `Ready`, `NeedsGrids` o `Unavailable`.
- Los textos nuevos van en `strings.xml` (RNF-T7); los motivos de las filas ignoradas reutilizan los de S040/S030/S048 más `OUTSIDE_DATUM_GRID` y `MISSING_GRID`.

## Decisiones técnicas
| # | Decisión | Motivo | Alternativa descartada |
|---|---|---|---|
| D1 | `CadSourceCrs.Library(definition)` con la definición completa, como el proyecto (S034/D4) | El dibujo no cambia si la biblioteca se actualiza; no hace falta tocar Room | Guardar solo el EPSG y resolverlo con el catálogo |
| D2 | Las definiciones WKT en un recurso aparte y se leen al exportar | La biblioteca sigue siendo de 0,36 MB y la búsqueda no carga 3 MB de texto | Meterlas en `crs.tsv.gz` |
| D3 | El WKT sale de pyproj, no de una plantilla propia | Constitución nº 2 y ADR-3: lo que declaramos es lo que dice EPSG | Escribir el WKT a mano por método |
| D4 | Una sola transformación por importación (la más precisa), con aviso y confirmación | Entrevista del 21/11/2026; menos pasos en campo | Dejar elegir la transformación (S034 la ofrece, aquí no) |
| D5 | KML = datum de las correcciones, sin transformación | Entrevista; igual que S031 | WGS 84 real con transformación |
| D6 | El selector de S034 como selector de SC de origen | Entrevista; una sola interfaz para elegir SC | Lista corta con «Otro…» |

## Estrategia de tests
- **Python (`tools`)**: `test_wkt.py` (una definición por SC del catálogo, determinismo, tamaño ≤ 1 MB, WKT1 válido con `AUTHORITY`) y `fixtures/geodesia/reproyeccion_*.csv` (vectores de pyproj de tres SC de otros países y dos cambios de datum, uno de parámetros y uno con rejilla).
- **Unitarios JVM**: `SourceGroundTest` (contra los vectores a ≤ 1 mm y la ida y vuelta a ≤ 0,1 mm), `ImportTransformationTest` (aviso, confirmación, sin aviso con el mismo datum), `CadProjectorLibraryTest`, `SurfaceProjectorLibraryTest`, `RasterElevationLibraryTest`, `AlignmentProjectorLibraryTest`, `CrsRecognitionTest` (EPSG, nombre, ESRI, ambiguo), `SourceHeightsTest`.
- **`data`** (Robolectric): `CadSourceCrsPersistenceTest`, importadores de GeoPackage, Shapefile y KML con un SC de otro país, y exportadores con `CrsWkt`.
- **`app`**: `SourceCrsPickerTest`, pantallas de importación con vista previa y confirmación, rejilla que falta, menús sin aviso (`FormatsEnabledTest`).
- **No regresión**: los tests de S029 a S031, S040, S042, S045, S048, S049, S053 y S055 siguen sin cambios; `SpainFormatsUnchangedTest` compara una importación con un SC de España antes y después.
- **Rendimiento**: `ReprojectionPerformanceTest` con 100.000 puntos, menos de 2 s con parámetros y menos de 4 s con rejilla.
- **Emulador**: la demo de «Criterios de finalización».

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2, RF-3 | `SourceCrsPicker`, `CadSourceCrs.Library` | `SourceCrsPickerTest`, `CadSourceCrsPersistenceTest` |
| RF-4, RF-5, RF-6 | A4, `CrsRecognition` | `CrsRecognitionTest` |
| RF-7, RF-11, RF-12 | A1, `SourceGround` | `SourceGroundTest` |
| RF-8, RF-9, RF-10 | A2, A3, `ImportTransformation` | `ImportTransformationTest`, `ImportPreviewTest` |
| RF-13 | `CadProjector` | `CadProjectorLibraryTest` |
| RF-14 | `SurfaceProjector`, `AlignmentProjector` | `SurfaceProjectorLibraryTest`, `AlignmentProjectorLibraryTest` |
| RF-15 | `RasterElevation`, `RasterTiler` | `RasterElevationLibraryTest` |
| RF-16 | importadores de GeoPackage y Shapefile, `ImportAreaFilter` | `GpkgLibraryImportTest`, `ShpLibraryImportTest` |
| RF-17, RF-24 | KML | `KmlLibraryTest` |
| RF-18 | resúmenes | `ImportSummaryTest` |
| RF-19, RF-20 | A5 | `SourceHeightsTest` |
| RF-21 | exportar DXF | `DxfExportLibraryTest` |
| RF-22, RF-23, RF-25, RF-26 | A6, `CrsWkt` | `GpkgExportLibraryTest`, `ShpExportLibraryTest`, `CrsWktTest` |
| RF-27 | no regresión | `SpainFormatsUnchangedTest` |
| RF-28 | menús | `FormatsEnabledTest` |

## Cambios al implementar
- **T1 (definiciones).** `crs_wkt.tsv.gz` lleva `epsg`, WKT1 de GDAL y WKT1 de ESRI (sin la columna de nombre ni de tipo del plan: el nombre ya está en el catálogo). Pesa 0,24 MB (el tope era 1 MB) y PROJ expresó los 5.106 SC, así que el aviso de «no expresable» (RF-26) solo se puede probar con un SC inventado.
- **T3 y T5 (vectores).** Dos cosas que PROJ hace distinto de lo que suponía el plan. (1) En Cataluña elige la rejilla del ICGC y no la SPED2ETV2 que va en la app: el caso de rejilla pasó a ED50 / UTM 30N en el centro de España. (2) Una operación 2D de EPSG no se invierte exactamente (con 300 m de desplazamiento y alturas a 0 en cada lado, 1–2 mm en Grecia): `reproyeccion.csv` lleva por eso la ida (origen → proyecto) y la vuelta de PROJ, y las dos se comparan a 1 mm. El caso de Stereo 70 pasó a UTM 35N porque Bucarest queda fuera del ámbito del UTM 34N.
- **T5 (ámbito).** El ámbito del SC de origen **no** recorta (RF-11 habla del ámbito del SC del proyecto): un fichero en UTM 33N con puntos en Madrid se importa como lo haría PROJ. `SourceGround` construye el motor con la definición sin ámbito.
- **T4 y T7 (tipos).** Los textos `PROJECT`, `UTM:…` y `GEO:…` no cambian y `encode`/`decode` pasaron a `CadSourceCrs`, en vez de dos copias en `CadRepository` y `SurfaceRepository`. `CadProjector`, `AlignmentProjector`, `ImportAreaFilter` y `CadPlacement` usaban `utmZone == null` para decir «proyecto geográfico»: con un SC proyectado de la biblioteca (`utmZone` a `null`) un proyecto en Lambert-93 se trataba como geográfico. Ahora preguntan `!isProjected` y `CadPlacement.geographic` pasa por `GroundPosition` (el mapa y el aviso de distancia lo usan).
- **T9 (ráster).** La malla de un ráster en un SC proyectado de la biblioteca viene en la unidad del SC, no en metros (`gridUnitMetres`), y `RasterFootprint` la escala igual. Las alturas (RF-19, RF-20) son `SourceHeights.conversion` (mismas, convertibles, proyecto sin geoide, geoide sin instalar); una superficie de LandXML no declara sistema de alturas, así que solo cuenta para el ráster.
- **T10 (zona).** El recuadro de la zona en la capa de un SC cualquiera se mide con 32 puntos del círculo pasados al SC más un margen del 1 %; no se supone la escala de UTM (Mercator a 48° escala ×1,49).
- **T11 (reconocer).** Un `.prj` sin código solo se reconoce por nombre si sus parámetros, su unidad y su elipsoide son los del SC de la biblioteca: un UTM con la escala cambiada tiene el nombre de uno de la biblioteca y no lo es (`ShpCrsCatalog.sameDefinition`). Antes de S057 un SC fuera de España era «no disponible»; ocho tests de S030, S045 y S048 que lo afirmaban para EPSG:3857 y EPSG:25833 pasaron a esperar `Library`. El `.prj` de cada uno de los 5.106 SC vuelve a reconocerse como el mismo SC al importarlo (`ShpExportLibraryTest`).
- **T12.** Las capas de un GeoPackage o un Shapefile en un SC de la biblioteca vienen en la unidad del SC (`CadSourceCrs.Library.unit`). Los esperados de pyproj de las capas de ejemplo están en `fixtures/formatos/biblioteca/` (en la carpeta de GeoPackage o de Shapefile rompían el test que compara el directorio con lo que genera el script). La extensión de una capa en otro SC es la de las cuatro esquinas de su recuadro, que sale a unos centímetros de los puntos por el giro entre sistemas.
- **T13 a T16 (exportar).** Exportar un KML, un DXF, un GeoPackage o un Shapefile con un proyecto de la biblioteca ya funcionaba con lo de S034 salvo las definiciones del SC (GeoPackage y Shapefile): el KML y el DXF salen de las geográficas del suelo y de Este y Norte del proyecto. Las geográficas de un proyecto de la biblioteca son las del datum de las correcciones (EPSG:4258, o 4326 con ITRF o WGS 84).
- **T17 a T22 (interfaz).** El selector es el de S034 (`CrsLibraryFlow`), que además deja elegir la transformación en su ficha; el plan decía que no (D4), pero es el mismo flujo y no quita nada. `LibraryResources.isInstalled` y `ImportTransformation.forSource` (mirar qué rejillas faltan sin leerlas) evitaron dar a cada ViewModel un almacén de rejillas. Los resúmenes (RF-18) se enseñan en pantalla y en el texto que se guarda (DXF y capas de GeoPackage, Shapefile y KML); la superficie, el eje y el ráster solo en pantalla, porque no guardan resumen. El eje no tiene centro del proyecto: elige la transformación más precisa de todas.
- **T23.** `ProjectCrs.supportsFormats` y el aviso `formats_unavailable_library` desaparecen; el motivo de ignorado por rejilla ya no dice «ED50» (`OUTSIDE_ED50_GRID` pasó a «fuera de la rejilla de transformación de datum» en las cinco cadenas que lo mostraban, es y en).
- **Pendiente por diseño.** Con el SC de origen igual al del proyecto en una capa que el fichero declara con otro código (`EPSG:4326` en un proyecto en WGS 84) se sigue el criterio de S048 (se trata como ETRS89 con aviso).
- **Comprobaciones útiles.** Lanzar `pytest` de `tools` antes de un commit que toque `tools/` o `fixtures/` (un `esperado-*.csv` nuevo en la carpeta de un generador rompió dos tests de S048 y S030); no escribir ficheros fuente mientras corre un `check` (un fichero a medias hizo fallar ktlint dos veces); `ktlintFormat` no parte los nombres de test de más de 140 caracteres.

## Validación (21/11/2026)
- **Trazabilidad.** `rf_report.py`: S057 con 28 RF, 28 con test y 0 pendientes; S034 sigue en 29 de 29.
- **Constitución nº 2 (≤ 1 mm frente a pyproj).** Reproyección: `SourceGroundTest`, `CadProjectorLibraryTest`, `SurfaceProjectorLibraryTest`, `AlignmentProjectorLibraryTest`, `RasterElevationLibraryTest` con 12 puntos de cuatro SC de otros países (Lambert-93 con paso nulo, GGRS87 y Stereo 70 por parámetros, ED50 UTM 30N con la rejilla SPED2ETV2), ida y vuelta de PROJ; importación de capas: GeoPackage en EPSG:3857 y Shapefile en UTM 33N (`GpkgLibraryImportTest`, `ShpLibraryImportTest`, esperados de `generar_biblioteca.py`).
- **No regresión de España (RF-27).** Todos los tests de S029 a S031, S040, S042, S045, S048, S049, S053 y S055 siguen en verde; solo se tocaron los que afirmaban «no disponible» para un SC que ahora está en la biblioteca. `SpainFormatsUnchangedTest`.
- **Rendimiento.** 100.000 puntos al SC del proyecto: 0,7 s con un cambio de datum por parámetros y 0,5 s con la rejilla (RNF: 2 s y 4 s) en la JVM; en un móvil habrá que medirlo.
- **Tamaño.** `crs_wkt.tsv.gz`: 0,24 MB (RNF: 1 MB).
- **Dependencias.** Ninguna nueva, ni en Gradle ni en Python; Room sigue en v37.
- **Construcción.** `./gradlew check`, `pytest` de `tools`, `forbidden_files.py` y `assembleDebug` en verde.
- **Lo que no se pudo contrastar o queda abierto.** (1) La demo de campo (importar un fichero en el SC de otro país con un receptor real) queda para el usuario. (2) Las rejillas no se descargan en los tests: el aviso y el bloqueo se prueban con una rejilla que falta (DHDN → ETRS89, `BETA2007`), la descarga misma es la de S034. (3) La transformación de un fichero de un SC con una lista de rejillas alternativas sigue sin ofrecerse (S034). (4) Un `.prj` con el nombre de un SC que comparten varios de la biblioteca pide elegir.
- **Demo en el emulador (21/11/2026).** APK `standard` debug: la app arranca sin fallos; en el menú de un proyecto de ETRS89 UTM 30 están los 4 formatos de salida y los 7 de entrada; «Importar DXF» con un DXF de ED50 UTM 29 ofrece «Otro de la biblioteca…», abre el selector de S034 y, al elegir ED50 / UTM zone 29N (EPSG:23029), enseña «Cambio de datum: ED50 to ETRS89 (12)», «Precisión declarada: 0,2 m» y la extensión del dibujo; un SC sin transformación hasta ETRS89 (AGD66) no se deja elegir. No se llegó a importar el fichero ni a probar la descarga de una rejilla.
