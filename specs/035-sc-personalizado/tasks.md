# Tareas — Spec 035

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S035 Tn: …` en `main`, con la línea Co-Authored-By.

## Vectores y modelo (`tools/geodesia`, `core:model`)
- [x] T1. `tools/geodesia/personalizado_vectores.py`: tres puntos con ida y vuelta de cinco SC (UTM 30N, Lambert-93, Stereo 70, GGRS87 por Helmert y ED50 con rejilla) escritos como parámetros sueltos de un SC personalizado, en `fixtures/geodesia/personalizado.csv`. (RF-3, RF-4, RNF)
      Hecho cuando: `test_personalizado.py` comprueba las filas, que el fichero se regenera idéntico y que `pytest tools` pasa.
- [x] T2. `CustomCrs`, `CustomHeight`, `CrsDefinition.isCustom` (`epsg == 0`) y `LibraryCrs.geoidConstantM`, con el texto de ida y vuelta del SC del proyecto. (RF-5, RF-11)
      Hecho cuando: `CustomCrsModelTest` comprueba la ida y vuelta del texto, que `geoidGrid` y `geoidConstantM` no pueden estar a la vez y que los diez SC de España y los de la biblioteca no cambian.
- [x] T3. `CustomCrsDraft` y `CustomCrsDraft.of(definition | CustomCrs | vacío)`. (RF-1)
      Hecho cuando: `CustomCrsDraftTest` parte de un SC de la biblioteca, de uno de Mis SC y de uno vacío y obtiene los campos esperados.

## Núcleo geodésico (`core:geodesy`)
- [x] T4. `CustomCrsBuilder.validate`: elipsoide (esfera incluida), proyección por `ProjectionFactory`, Helmert, Molodensky-Badekas y rejilla, con un motivo por campo. (RF-2, RF-3, RF-4, RF-6)
      Hecho cuando: `CustomCrsBuilderTest` cubre cada campo válido y no válido y que no hay dos motivos para el mismo campo.
- [x] T5. `CustomCrsBuilder.build`: transformación en la notación de pasos, definición y `Ready`, `NeedsGrids` o `Invalid`. (RF-3, RF-8)
      Hecho cuando: `CustomCrsBuilderTest` comprueba los tres resultados, los seis tipos de transformación y que la definición vuelve a `resolve` sin el catálogo.
- [x] T6. Contraste con pyproj: `CustomCrsVectorsTest` con `personalizado.csv`. (RF-3, RF-4, constitución nº 2)
      Hecho cuando: los cinco SC dan ≤ 1 mm con ida y vuelta ≤ 0,1 mm frente a los de la biblioteca y a pyproj.
- [x] T7. `CrsProbe` (ida y vuelta de unas coordenadas de prueba). (RF-7)
      Hecho cuando: `CrsProbeTest` comprueba los dos sentidos, la diferencia de la vuelta y un SC con rejilla no instalada.
- [x] T8. La altura constante en `LibraryEngine` y `SourceHeights`. (RF-5)
      Hecho cuando: `CustomHeightTest` y `SourceHeightsTest` comprueban la ondulación constante, la elipsoidal y la del geoide, sin cambio con la de S034.

## JSON, WKT y formatos (`core:formats`)
- [x] T9. `CustomCrsJson.write` y `read` con la versión 1. (RF-12, RF-13)
      Hecho cuando: `CustomCrsJsonTest` comprueba la ida y vuelta exacta con 10.000 `Double` aleatorios y con los extremos.
- [x] T10. `CustomCrsJson.read` rechaza el contenido no válido: versión, método, números fuera de rango, JSON dañado y fichero de otro tipo con extensión `.json`. (RF-13, RF-14)
      Hecho cuando: `CustomCrsJsonTest` comprueba cada motivo y que no queda nada a medias.
- [x] T11. `CustomCrsWkt.gdal` y `.esri`: TM, LCC 1SP/2SP, OM A/B y geográficas, con `TOWGS84` para Helmert 3 y 7 PV. (RF-23)
      Hecho cuando: `CustomCrsWktTest` lee cada WKT con pyproj y da las mismas coordenadas, ≤ 1 mm (vectores en `fixtures/geodesia/personalizado_wkt.csv`).
- [x] T12. El `.prj` del Shapefile y el `gpkg_spatial_ref_sys` de un SC personalizado con `CustomCrsWkt`, y el aviso de S057/RF-25 si no se puede expresar. (RF-23)
      Hecho cuando: `CustomCrsFormatsTest` exporta un Shapefile y un GeoPackage de un proyecto personalizado y QGIS (pyproj) lee su SC.
- [x] T13. `CustomCrsRecognition` y su uso al importar un Shapefile o un GeoPackage exportados desde un proyecto personalizado. (RF-24)
      Hecho cuando: `CustomCrsFormatsTest` reimporta el fichero y lo reconoce como el SC del proyecto, y uno de otro SC no.

## Datos (`data`)
- [x] T14. Room v38: tablas `custom_crs` y `project_crs_history`, columna `geoid_constant_m`, entidades, DAO y migración 37→38. (RF-10, RF-20)
      Hecho cuando: `Migration37To38Test` conserva los proyectos y los puntos, y el esquema v38 está exportado.
- [x] T15. `CustomCrsRepository`: guardar, duplicar, renombrar y borrar con nombres únicos. (RF-10)
      Hecho cuando: `CustomCrsRepositoryTest` comprueba cada operación y el nombre repetido sin mayúsculas ni espacios de más.
- [x] T16. Importar un JSON con conflicto: reemplazar, conservar ambos con otro nombre u omitir. (RF-15)
      Hecho cuando: `ImportCustomCrsTest` comprueba los tres caminos y que una definición igual no pregunta.
- [x] T17. `ProjectRepository` guarda `geoid_constant_m` y la copia de la definición; editar o borrar un SC de Mis SC no cambia el proyecto. (RF-5, RF-11)
      Hecho cuando: `CrsHistoryTest` borra un SC usado por un proyecto y este sigue calculando igual.
- [x] T18. `CrsChangePlan` y `ProjectCrsHistoryRepository.apply`: transacción única con el historial. (RF-17, RF-18, RF-19, RF-20)
      Hecho cuando: `CrsChangePlanTest` y `CrsHistoryTest` comprueban los puntos no calculables, las medidas intactas y la atomicidad (un fallo no deja el cambio a medias); `CrsChangePerformanceTest` con 10.000 puntos < 2 s.
- [x] T19. `ProjectCrsHistoryRepository.undo`: «Volver al SC anterior» como un cambio más, solo del más reciente. (RF-21)
      Hecho cuando: `CrsHistoryTest` deshace un cambio, lo vuelve a deshacer y comprueba el historial con tres filas; con el SC anterior ya borrado de Mis SC también funciona.
- [x] T20. Copia completa y paquete: Mis SC y el historial del proyecto, con las colisiones de nombre al restaurar. (RF-16, RF-22)
      Hecho cuando: `CustomCrsBackupTest` hace copia y restauración con Mis SC, el historial y un nombre repetido (S013).

## Interfaz (`app`)
- [x] T21. Editor, parte 1: pantalla con nombre, punto de partida (vacío, biblioteca o Mis SC), elipsoide y unidades, con un campo marcado cuando falla. (RF-1, RF-2, RF-5, RF-6)
      Hecho cuando: `CustomCrsEditorTest` crea un SC, ve un campo marcado y no deja guardar con parámetros no válidos.
- [x] T22. Editor, parte 2: transformación del datum (ninguna, Helmert, Molodensky-Badekas, rejilla), proyección y altura. (RF-3, RF-4, RF-5)
      Hecho cuando: `CustomCrsEditorTest` elige cada tipo de transformación y de proyección y la altura constante.
- [x] T23. Editor, parte 3: coordenadas de prueba en los dos sentidos fuera del hilo de la UI y descarga de lo que falta. (RF-7, RF-8)
      Hecho cuando: `CustomCrsEditorTest` comprueba el resultado, la rejilla no instalada con su descarga y que cada cambio de campo responde en menos de 100 ms.
- [ ] T24. Mis SC en el selector de S034, en el formulario de proyecto, en Ajustes y en el selector de SC de origen de S057, con duplicar, editar, renombrar y borrar. (RF-9, RF-10)
      Hecho cuando: `MyCrsListTest` crea un proyecto con un SC personalizado y lo elige como SC de origen de una importación.
- [ ] T25. Importar y exportar el JSON de un SC desde Mis SC, con la confirmación, el motivo del rechazo y el conflicto de nombre. (RF-12 a RF-15)
      Hecho cuando: `ImportCustomCrsTest` exporta un SC, lo importa y confirma que queda igual; un JSON no válido muestra su motivo.
- [ ] T26. Cambiar el SC de un proyecto con puntos: solo el mismo datum, aviso de los puntos no calculables con cancelar o continuar. (RF-17, RF-18, RF-19)
      Hecho cuando: `ChangeProjectCrsTest` cambia de SC con y sin puntos fuera de ámbito, y no ofrece uno de otro datum.
- [ ] T27. Historial del proyecto con «Volver al SC anterior». (RF-20, RF-21)
      Hecho cuando: `ProjectHistoryScreenTest` muestra los cambios, deshace el último y el resultado figura en la lista.

## Cierre
- [ ] T28. Validación final: `rf_report` con los RF cubiertos, `check`, `pytest tools`, `forbidden_files` y `assembleDebug` en verde; `plan.md` con «Cambios al implementar» y «Validación»; hoja de ruta con S035 implementada (Room v38) y glosario; demo del emulador (crear un SC, probarlo, exportarlo e importarlo, cambiar el SC de un proyecto con puntos y deshacerlo).
      Hecho cuando: el informe de trazabilidad no tiene RF huérfanos y la demo está hecha.
