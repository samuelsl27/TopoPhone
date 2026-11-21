# Tareas — Spec 034

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S034 Tn: …` en `main`, con la línea Co-Authored-By.

## Generador del catálogo (`tools/geodesia`)
- [x] T1. `tools/geodesia/catalogo.py`, parte 1: SC proyectados y geográficos activos de EPSG con ejes E–N, método y parámetros, unidades, ámbito y países; descarte de los métodos que no calculamos, y formato TSV de las secciones `#ellipsoids`, `#datums` y `#crs`. (RF-4)
      Hecho cuando: `test_catalogo.py` comprueba columnas, recuento, ausencia de obsoletos y de ejes al oeste o al sur, y la salida es idéntica al regenerarla.
- [x] T2. `catalogo.py`, parte 2: transformaciones de cada datum a ETRS89 y a WGS84 (`#option`: nula, Helmert 3/7 PV y CF, Molodensky-Badekas, rejilla y cadenas) con precisión y ámbito; los SC sin ninguna se descartan. (RF-4, RF-9, RF-10)
      Hecho cuando: `test_catalogo.py` comprueba que cada SC tiene al menos una opción, que los pasos solo usan los métodos de S033 y que REGCAN95 comparte las de ETRS89 (D8).
- [x] T3. `catalogo.py`, parte 3: rejillas del CDN (`#grids` con tamaño, SHA-256 y ámbito) y licencias curadas por `source_id` (`#licenses`), y escritura de `core/geodesy/src/main/resources/catalog/crs.tsv.gz`. (RF-4, RF-19, RF-20)
      Hecho cuando: `test_catalogo.py` comprueba que cada rejilla usada tiene SHA-256 y licencia (o «no clara» con URL), y que el fichero no pasa de 2 MB.
- [x] T4. Vectores de contraste: tres puntos por SC con pyproj en `fixtures/geodesia/catalogo_*.csv` (por método), y el descarte de los SC que no se reproducen. (RF-4)
      Hecho cuando: `test_catalogo.py` comprueba el recuento de vectores (tres por SC) y la regeneración idéntica.

## Núcleo del catálogo (`core:geodesy`)
- [x] T5. Lector y modelo del catálogo: `CrsCatalog`, `CrsEntry`, `DatumEntry`, `TransformationOption`, `GridEntry`, `GridLicense`. (RF-4)
      Hecho cuando: `CrsCatalogTest` carga el recurso, comprueba los recuentos del generador y que un SC conocido (25830, 4326, 2154) tiene sus parámetros.
- [x] T6. Contraste del catálogo con pyproj: `CrsBuilder` convierte una entrada en su `MapProjection` y los vectores se comprueban SC por SC. (RF-4)
      Hecho cuando: `CatalogVectorsTest` pasa con los tres puntos de cada SC a ≤ 1 mm y ida y vuelta ≤ 0,1 mm, y `CatalogContentTest` fija qué métodos hay.
- [x] T7. Búsqueda: `CrsSearch` por país, código y nombre, sin mayúsculas ni tildes, con España primero y filtro por país. (RF-2, RF-3, RF-5)
      Hecho cuando: `CrsSearchTest` cubre «25830», «etrs89 utm 30» y «Francia», las tildes, el orden y una búsqueda en menos de 300 ms.
- [x] T8. `TransformationPicker` (A3) y `GridRequirement`, y `GeoidFromGrid` con la lectura de las rejillas del CDN; medida del tamaño de los ficheros y decisión de lectura por teselas si no caben. (RF-10, RF-11, RF-12, RF-14, RF-16)
      Hecho cuando: `TransformationPickerTest` y `GeoidFromGridTest` pasan (geoide pequeño contra pyproj a ≤ 1 mm), y el resultado de la medida está anotado en `plan.md`.
- [x] T9. `CrsDefinition` y su texto estable, ida y vuelta, con `CrsBuilder.build` devolviendo `Ready`, `NeedsGrids` o `Unavailable`. (RF-8, RF-12, RF-13)
      Hecho cuando: `CrsDefinitionTest` comprueba la ida y vuelta del texto, que la definición no depende del catálogo y los tres resultados.

## SC del proyecto y cálculo (`core:model`, `core:survey`)
- [x] T10. `ProjectCrs` como interfaz sellada con `SpainCrs` y `LibraryCrs` y su API actual en el `companion`. (RF-25)
      Hecho cuando: todo el proyecto compila sin tocar los usos de los diez SC de España, `SpainCrsUnchangedTest` pasa y `check` pasa.
- [x] T11. `CoordinateEngine` para `SpainCrs`, delegando en el código de S008, y migración de `ProjectPositionCalculator`. (RF-25, RF-26)
      Hecho cuando: `CoordinateEngineSpainTest` da bit a bit lo de S008 con los vectores `utm_*.csv` y `ed50_utm.csv`.
- [x] T12. `CoordinateEngine` para `LibraryCrs` (A4): proyección, transformación del datum, altura con geoide y factores. (RF-26, RF-27, RF-18, RF-24)
      Hecho cuando: `CoordinateEngineTest` pasa con varios SC de la biblioteca frente a pyproj, con un geoide ausente y sin conexión.
- [x] T13. Migración al motor de los demás sitios que usan `Utm` o `Ed50` (`ManualCoordinates`, `GroundPosition`, `GroundPoint`, `CadPlacement`, `ImportAreaFilter`, `RasterElevation`). (RF-26, RF-28)
      Hecho cuando: los tests de cada uno pasan sin cambios en España y `LibraryProjectCoreTest` comprueba puntos manuales, geometría y COGO en un SC de la biblioteca.
- [x] T14. Replanteo, base shift y mapa con un SC de la biblioteca. (RF-28)
      Hecho cuando: `LibraryStakeoutTest`, `LibraryBaseShiftTest` y `LibraryMapProjectionTest` pasan.
- [x] T15. `supportsFormats` y el aviso de los formatos aún no disponibles; exportar e importar CSV con el SC de la biblioteca. (RF-28, RF-29)
      Hecho cuando: `FormatsDisabledTest` y `LibraryCsvTest` pasan.

## Datos (`data`)
- [x] T16. Room v37 (`crs_definition`, `geoid_grid`, `favorite_crs`, `recent_crs`) con su migración, `ProjectRepository` con `LibraryCrs`, `.topoproj` y copia de seguridad. (RF-13, RF-25)
      Hecho cuando: `Migration36To37Test` y `ProjectBackupCrsTest` pasan, y el esquema `37.json` está exportado.
- [ ] T17. `GridStore`: rejillas instaladas, estado, uso por proyecto y borrado de las que ninguno usa. (RF-23, RF-17)
      Hecho cuando: `GridStoreTest` y `BundledGridsTest` pasan.
- [ ] T18. `GridDownloader` (A5): descarga atómica con SHA-256 y reintento. (RF-19, RF-21, RF-22)
      Hecho cuando: `GridDownloaderTest` pasa con servidor falso: completa, cortada, SHA alterado y reintento.
- [ ] T19. Favoritos y recientes. (RF-6, RF-7, RF-8)
      Hecho cuando: `FavoriteCrsTest` y `RecentCrsTest` pasan (diez recientes, sin repetir, favorito que desaparece del catálogo).

## Interfaz (`app`)
- [ ] T20. Selector de SC con búsqueda, filtro por país, favoritos y recientes, en el formulario y en Ajustes. (RF-1, RF-2, RF-3, RF-5, RF-6, RF-7, RF-8)
      Hecho cuando: `CrsPickerTest` y `CrsPickerShortcutsTest` pasan, con zonas de 56 dp y etiquetas de TalkBack.
- [ ] T21. Ficha del SC con la transformación y sus alternativas. (RF-9, RF-10, RF-11, RF-12)
      Hecho cuando: `CrsDetailsScreenTest` pasa, incluido el SC sin transformación (no elegible, con motivo).
- [ ] T22. Descarga con confirmación, tamaño, licencia y fuente, avisos de licencia, y pantalla de rejillas instaladas. (RF-19, RF-20, RF-23)
      Hecho cuando: `GridDownloadScreenTest` e `InstalledGridsScreenTest` pasan.
- [ ] T23. Formulario de proyecto con SC de la biblioteca, altura y geoide, y apertura sin rejilla. (RF-14, RF-15, RF-16, RF-18, RF-24)
      Hecho cuando: `ProjectFormGeoidTest`, `MissingGridProjectTest` y `OfflineProjectTest` pasan.
- [ ] T24. Factores de escala, convergencia y factor combinado, y avisos de formatos en los menús. (RF-27, RF-29)
      Hecho cuando: `LibraryProjectFlowTest` pasa.
- [ ] T25. Validación final: recorrer la spec RF por RF, informe de trazabilidad, regenerar el catálogo, `plan.md` con «Cambios al implementar» y «Validación», hoja de ruta, y la demo del emulador. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, `check`, `pytest`, `forbidden_files` y `assembleDebug` pasan y la demo funciona.
