# Tareas — Spec 036

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`). Nombres de clase y de test de `data` y `app` de 60 caracteres o menos. El orden va de lo que no depende de nada (referencias, lector, peticiones) a lo que se ve (mapa, hojas, menú). Antes de T13 hay que enseñar al usuario el diff de S013 y tener su visto bueno.

## Núcleo y referencias
- [ ] T1. `core:model`: los modelos de `wms/` (`WmsService`, `WmsLayerInfo`, `WmsRequestMode`, `ProjectMapLayer`, `WmsPreset` con `preferredFormat`, `infoVersion`, `group` y `year`, y las constantes de 10 capas, 20 s y pasos de 5 %) y `WmsAddress` (HTTPS por defecto, parámetros, HTTP admisible solo en red privada o de enlace local). (RF-5, RF-10, RF-17, RF-21, RF-26)
      Hecho cuando: `WmsAddressTest` en verde con HTTP privado y público, direcciones sin esquema, con `?map=` y con mayúsculas.
- [ ] T2. `tools/formatos`: `generar_wms.py` con los GetCapabilities sintéticos de los casos límite (1.1.1, 1.3.0, anidadas a 10 niveles, `DOCTYPE` con subconjunto interno como el del Catastro, ISO-8859-1, un prefijo sin declarar, CRS heredados, alias 3785, 102113, 102100 y 900913, sin 3857, sin `Name`, nombres con espacios y barras verticales, nombres repetidos entre grupo e hijo, excepción de servicio, página HTML con HTTP 200, XML dañado y vacío), su lectura de referencia con `xml.etree`, `esperado-capas.csv` y las cajas EPSG:3857 de pyproj, y su test. (RF-2, RF-3, RF-9, RF-11, RF-12)
      Hecho cuando: el script genera `fixtures/formatos/wms/` y su test de `tools` está en verde.
- [ ] T3. `core:formats`: `WmsCapabilitiesReader` (1.1.1 y 1.3.0, por eventos con `XmlStreamReader`, hasta 20 MB y 10 niveles, atribución, formatos de GetMap y de GetFeatureInfo, `queryable`, `opaque`, código de Web Mercator heredado con sus alias, `ServiceException`). (RF-2, RF-3, RF-8, RF-9, RF-11, RF-12)
      Hecho cuando: `WmsCapabilitiesReaderTest` en verde con todos los ficheros de T2, y un GetCapabilities sintético de 5 MB se lee en menos de 2 s.
- [ ] T4. `core:formats`: `WmsRequests` (GetCapabilities que conserva los parámetros de la dirección, plantilla de teselas con `{bbox-epsg-3857}` en 1.1.1 y 1.3.0 con el código de la capa y el nombre codificado, GetMap de una imagen, GetFeatureInfo con `I`/`J` o `X`/`Y` y su versión), con `core:geodesy` y números en `Locale.ROOT`. (RF-1, RF-5, RF-17, RF-29, RF-36)
      Hecho cuando: `WmsRequestsTest` en verde, con los cuadros envolventes iguales a los de pyproj a 1 cm o menos.
- [ ] T5. `core:formats`: `FeatureInfoText` (texto plano sin cabecera ni direcciones internas, GML y XML como pares nombre-valor sin geometrías, HTML sin etiquetas, `ServiceException` con HTTP 200, vacío, frases de «sin datos» y entidades sin atributos como «sin datos») con respuestas grabadas. (RF-36, RF-38)
      Hecho cuando: `FeatureInfoTextTest` en verde.
- [ ] T6. `core:formats`: el catálogo `presets.json` con los presets verificados del plan (IGN, Catastro, ICV con sus 36 ortofotos y 5 cartografías, ICGC, GRAFCAN, Andalucía, Navarra, País Vasco, Madrid y Galicia) y su lector `WmsPresets`. (RF-13, RF-16)
      Hecho cuando: `WmsPresetsTest` en verde (claves únicas, HTTPS, atribución y licencia en todos, el Catastro y GRAFCAN en «una imagen», los demás por teselas, el `infoVersion` del Catastro INSPIRE).
- [ ] T7. `tools/formatos`: `wms_presets.py`, que verifica en vivo cada dirección del catálogo (las dos versiones, el código de Web Mercator, la consulta de prueba) y graba los GetCapabilities y una consulta de los presets en `fixtures/formatos/wms/presets/` (los de 4 vuelos del ICV: 2025, 2024, 2017 y 1945); no se ejecuta en CI. (RF-13, RF-16)
      Hecho cuando: el test de `tools` con respuestas grabadas está en verde y los ficheros se han grabado una vez en vivo.
- [ ] T8. `core:formats`: los GetCapabilities grabados de cada preset se leen con `WmsCapabilitiesReader`, todos tienen alguna capa con código de Web Mercator y las capas sugeridas que dice el catálogo. (RF-9, RF-13)
      Hecho cuando: `WmsPresetFixturesTest` en verde con todos los presets grabados.

## Datos
- [ ] T9. `data`: las entidades y DAO de `wms_services`, `wms_layers` y `project_wms_layers`, Room 31 → 32 con auto-migración, y el esquema exportado. (RF-4, RF-24)
      Hecho cuando: `WmsMigrationTest` en verde (una base v31 con datos pasa a v32 sin perder nada) y `./gradlew check` con el esquema nuevo.
- [ ] T10. `data`: `WmsLibraryRepository` (guardar un servicio con sus capas por lotes, listar, renombrar, borrar contando los proyectos afectados, actualizar devolviendo las capas desaparecidas, no duplicar una dirección). (RF-4, RF-6, RF-7, RF-15)
      Hecho cuando: `WmsLibraryRepoTest` en verde.
- [ ] T11. `data`: `ProjectLayersRepository` (añadir con el tope de 10 y sin repetir, visibilidad, opacidad de 5 en 5, mover, quitar, disponibilidad, nombre del servicio para las repetidas, cascada). (RF-18 a RF-24, RF-26 a RF-28)
      Hecho cuando: `ProjectLayersRepoTest` en verde.
- [ ] T12. `data`: `WmsHttp` y `OkHttpWmsHttp` (20 s, 20 MB, sin el interceptor del mapa) y `WmsServiceAdder` (1.3.0 y caída a 1.1.1, respuesta en otra versión, validación de Web Mercator, HTTP público, motivos, presets, ya en la biblioteca), con el cliente en la inyección de `app`. (RF-1, RF-2, RF-8, RF-9, RF-10, RF-14, RF-15)
      Hecho cuando: `WmsServiceAdderTest` en verde con las respuestas grabadas, y `data` con `okhttp` como dependencia (justificada en el plan).
- [ ] T13. `data`: `DatabaseSnapshot.TABLES` con las tres tablas (copia completa entera y paquete con filtros por proyecto) y la restauración completa que deja la biblioteca como la de la copia. (RF-25)
      Hecho cuando: `WmsBackupTest` en verde: copia completa, restauración, deshacer y copia de una versión anterior. Precondición: el usuario ha aprobado el diff de S013 (RF-2 y RF-24), que se aplica antes del código.
- [ ] T14. `data`: la importación del paquete con la fusión por dirección (servicio que el móvil ya tiene, servicio nuevo, proyecto sustituido). (RF-25)
      Hecho cuando: `WmsPackageImportTest` en verde.

## Mapa
- [ ] T15. `app`: `WmsLayerPlan` (qué fuentas y capas se dibujan, en qué orden, con la plantilla de teselas o la imagen, ocultas y no disponibles fuera, opacidad) y `MapAttribution` con las atribuciones de las capas visibles. (RF-17, RF-20 a RF-22, RF-29, RF-30, RF-33)
      Hecho cuando: `WmsLayerPlanTest` y `MapAttributionWmsTest` en verde.
- [ ] T16. `app`: `MapController.showWmsLayers` y `MapLibreMapController` con la capa ancla, las capas por teselas (`RasterSource` y `RasterLayer`) y su orden y opacidad; el doble del mapa para las pruebas. (RF-22, RF-29, RF-32, RF-33)
      Hecho cuando: `FakeMapController` y los tests de interfaz existentes en verde y las capas WMS pintadas en el emulador entre el mapa base y el Catastro.
- [ ] T17. `app`: `WmsOneImageLoader` (las capas en «una imagen» al detenerse el mapa, con cancelación y sin caché, con el patrón del Catastro). (RF-16, RF-17, RF-29, RF-31, RF-32)
      Hecho cuando: `WmsOneImageLoaderTest` en verde (cancelación, cuadro envolvente, no cuenta como fallo de conexión) y el Catastro de S009 sin cambios.
- [ ] T18. `app`: la pulsación larga del mapa (`longPresses`), la marca del punto (`showPressMarker`) y su cierre. (RF-34, RF-35)
      Hecho cuando: `MapPressTest` en verde (punto, píxel, zona visible) y la marca vista en el emulador.

## Interfaz
- [ ] T19. `app`: `WmsLibraryViewModel` (añadir por dirección con sus estados y motivos, presets agrupados con «ya en la biblioteca» y su licencia, capas de un servicio con las no compatibles, actualizar con el aviso, renombrar, borrar con el aviso, cambiar el modo de petición). (RF-1 a RF-17)
      Hecho cuando: `WmsLibraryViewModelTest` en verde.
- [ ] T20. `app`: la hoja de la biblioteca (servicios, presets por grupos, capas de un servicio) con los textos en es y en. (RF-2, RF-3, RF-6, RF-7, RF-12 a RF-15, RF-17)
      Hecho cuando: `WmsLibrarySheetTest` en verde.
- [ ] T21. `app`: las capas del proyecto en el estado y el `ProjectViewModel` y su unión con el mapa. (RF-18 a RF-24, RF-27)
      Hecho cuando: `ProjectLayersViewModelTest` en verde y las capas siguen al abrir el proyecto.
- [ ] T22. `app`: la sección de capas del proyecto en la hoja «Capas» (visibilidad, opacidad de 5 en 5 con su valor, subir y bajar y arrastrar, quitar, «no disponible», el servicio junto a los nombres repetidos, «Añadir capa» y «Añadir servicio», el tope de 10). (RF-18 a RF-23, RF-26 a RF-28)
      Hecho cuando: `ProjectLayersSectionTest` en verde con zonas de 56 dp.
- [ ] T23. `app`: `MapMenuViewModel` (la consulta de A6 en paralelo con tope de 5, versión y formato, «sin datos», «no responde», desactivada sin capas consultables). (RF-36 a RF-39)
      Hecho cuando: `MapMenuViewModelTest` en verde con respuestas grabadas.
- [ ] T24. `app`: «Ver coordenadas» (Este y Norte en el SC del proyecto y en sus unidades, latitud y longitud, copiar, fuera de ámbito). (RF-40, RF-41)
      Hecho cuando: `MapCoordinatesTest` en verde, con Este y Norte iguales a los de pyproj a 1 mm o menos.
- [ ] T25. `app`: el menú del mapa y las hojas de la consulta y de las coordenadas con sus textos en es y en, unidos a la pulsación larga de la vista de proyecto. (RF-34 a RF-41)
      Hecho cuando: `MapMenuSheetTest` en verde con las opciones, el desactivado con su motivo y el cierre que quita la marca.

## Cierre
- [ ] T26. Validación: recorrer la spec RF por RF, abrir con el Python de QGIS los GetCapabilities grabados y comparar capas y cuadros envolventes con `WmsCapabilitiesReader`, una consulta real de cada preset con capa consultable, `rf_report`, `pytest tools`, `forbidden_files`, la demo en el emulador según los criterios de finalización, y «Cambios al implementar» en `plan.md`, hoja de ruta a `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
