# Tareas — Spec 037

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero.

- [x] T1. Spike en el emulador (no se entrega código): una región de prueba del PNOA y otra de una capa WMS en una caja pequeña, y otra del mapa vectorial del IGN, con un contador de peticiones en el cliente HTTP. Se anotan en «Resultados del spike» de `plan.md` las cinco respuestas (desfase de zoom y recuento de teselas, revalidación con red, qué hace el mapa sin red por encima del último nivel, errores y 2 %, direcciones iguales a las del mapa vivo) y los tamaños medios de tesela; cada plan B que haga falta se escribe en el plan. (RF-11, RF-20 a RF-23)
      Hecho cuando: `plan.md` tiene el apartado «Resultados del spike» completo, con la medida de cada pregunta, y las decisiones que dependen de ella confirmadas o cambiadas.
- [x] T2. `core:model/offline`: `DetailLevel`, `OfflineArea`, `DownloadableSource`, `ZoneState` y `SourceState`, las constantes de límites y `ZoneMeta` (en `core:model`) con su JSON `ZoneMetaJson` (en `core:formats`, que ya tiene `kotlinx.serialization`). (RF-3, RF-4, RF-9, RF-12, RF-14, RF-19, RF-31)
      Hecho cuando: `./gradlew :core:model:test :core:formats:test` en verde con `DetailLevelTest`, `OfflineLimitsTest`, `OfflineAreaTest`, `DownloadableSourceTest`, `ZoneStateTest` y `ZoneMetaJsonTest`.
- [x] T3. `tools/mapas_offline/generar_teselas.py`: la referencia independiente (teselas de una caja a cada nivel con fórmulas propias de Web Mercator, esquinas de cada tesela comprobadas con pyproj a menos de 1 mm, cajas de círculos con `pyproj.Geod`, rectángulo de todo el proyecto con margen) y `fixtures/mapas_offline/teselas.csv`. (RF-7, constitución nº 2)
      Hecho cuando: `uv run --project tools pytest tools` en verde con `test_teselas.py`, que comprueba que lo guardado es lo que se genera.
- [x] T4. `core:geodesy`: `TileCover` (teselas y número por caja, zoom del mapa y tipo de fuente, con el desfase de A2 que T1 confirmó, y varias fuentes de teselas por fuente descargable: el IGN suma su relieve). (RF-7, RF-11)
      Hecho cuando: `./gradlew :core:geodesy:test` en verde con `TileCoverTest` frente a `teselas.csv` (números de tesela exactos en 6 niveles y 3 latitudes).
- [x] T5. `core:geodesy`: `AreaBounds` (de un radio, un encuadre o todo el proyecto con margen a una caja). (RF-2 a RF-4, RF-7)
      Hecho cuando: `./gradlew :core:geodesy:test` en verde con `AreaBoundsTest`: todos los puntos de la circunferencia con `Geod` dentro de la caja y la caja a menos de un 2 % de la exacta.
- [x] T6. `DownloadEstimate`: teselas y tamaño por fuente, y los bloqueos (OpenFreeMap, espacio, límite, sin internet) y confirmaciones (datos móviles, WMS propio). (RF-9 a RF-14)
      Hecho cuando: `./gradlew :app:testDebugUnitTest --tests "*DownloadEstimateTest"` en verde, con un caso por bloqueo y por confirmación.
- [x] T7. `OfflineRegionStore` (interfaz), su `FakeOfflineRegionStore` para los tests y `RegionStyles` (el estilo de cada fuente, el JSON de la ortofoto y de cada capa WMS con la plantilla de S036, y su dirección `https://topophone.invalid/...` con la plantilla dentro, porque MapLibre no lee `file://`). (RF-4, RF-8, RF-22)
      Hecho cuando: `RegionStylesTest` en verde: el JSON de cada estilo y la plantilla WMS idéntica a `WmsRequests.tileTemplate`.
- [x] T8. `MapLibreOfflineStore`: la implementación sobre `OfflineManager` y `OfflineRegion` (crear, listar, activar, pausar, invalidar, borrar, metadatos, estado), con corrutinas. (RF-8 a RF-19, RF-24 a RF-28)
      Hecho cuando: `./gradlew assembleDebug` y `check` en verde con el almacén ya enlazado en Hilt. Cambio respecto al plan: el botón temporal en el emulador se quita (T1 ya probó con un arnés las mismas llamadas de MapLibre: crear, activar, pausar, invalidar, borrar y listar), y esta implementación se comprueba entera en el emulador en T20 y T22, sin tener que arrancarlo diez tareas antes.
- [x] T9. `OfflineSettings` (DataStore): límite de almacenamiento y última elección de área, radio, margen y nivel. (RF-8, RF-31)
      Hecho cuando: `OfflineSettingsTest` en verde (por omisión, límites de 200 MB a 10 GB, recuerda lo último).
- [x] T10. `OfflineDownloader`: la cola de fuentes de una en una, la máquina de estados, pausar, reanudar y cancelar, la pausa al salir de la app, el 2 % de fallos y «Reintentar». (RF-15, RF-16, RF-18, RF-19)
      Hecho cuando: `OfflineDownloaderTest` en verde con el `FakeOfflineRegionStore`.
- [x] T11. `OfflineDownloader`: «Actualizar» (invalidar y activar sin perder la fecha anterior si se interrumpe) y las anotaciones del log. (RF-26, RF-30)
      Hecho cuando: `OfflineDownloaderTest` en verde con los casos de actualizar y las líneas del log sin coordenadas.
- [x] T12. Conexión, espacio y cliente HTTP: `StorageInfo` y `NetworkInfo` (internet validada, datos móviles) detrás de interfaces con su `Fake`; `ZoneHttp` en `MapHttpClient` (contesta los estilos de `topophone.invalid` sin red y pone `Cache-Control: max-age=31536000` solo mientras se descarga o se actualiza una zona) y 4 peticiones por servidor. Si no cabe en 30 min, `ZoneHttp` pasa a una tarea propia. (RF-12 a RF-14, RF-22, RF-23, RNF)
      Hecho cuando: `MapDispatcherTest` y `ZoneHttpTest` en verde (4 por servidor; estilo sin red; caducidad larga solo durante la descarga y también en los 304; el resto de peticiones intactas) y `DownloadEstimateTest` sigue en verde con las implementaciones de verdad detrás de la interfaz.
- [x] T13. `DownloadPlanViewModel`: área, fuentes, nivel, previsión, bloqueos y confirmaciones, y lo recordado de la última descarga. (RF-1 a RF-6, RF-8 a RF-14)
      Hecho cuando: `DownloadPlanViewModelTest` en verde (sin centro, sin puntos, cada bloqueo, recordar lo último).
- [x] T14. La hoja «Descargar zona»: las tres áreas con su radio o margen, las fuentes, los tres niveles, la previsión y los avisos, con textos en es y en. (RF-1 a RF-6, RF-8 a RF-14)
      Hecho cuando: `DownloadPlanSheetTest` en verde (zonas de 56 dp, cada bloqueo con su motivo, la confirmación del WMS propio con el nombre del servicio).
- [x] T15. El progreso: teselas, MB y fuente en curso, pausar, reanudar, cancelar con confirmación y pantalla encendida. (RF-15 a RF-18)
      Hecho cuando: `OfflineProgressTest` en verde (pantalla encendida mientras está a la vista, estado «Incompleta» al salir, botones de 56 dp).
- [x] T16. `OfflineZonesViewModel`: la lista con nombre, fuentes, nivel, tamaño real, fecha y estado, la ocupación total, renombrar, actualizar y borrar, y el límite. (RF-24 a RF-27, RF-31)
      Hecho cuando: `OfflineZonesViewModelTest` en verde.
- [ ] T17. La hoja «Mapas sin conexión» y su entrada en «Capas», con la lista, la ocupación, el límite y las acciones de cada zona, con textos en es y en. (RF-1, RF-24 a RF-27, RF-31)
      Hecho cuando: `OfflineZonesSheetTest` en verde (estado con texto además de color, borrar con confirmación, zonas de 56 dp).
- [ ] T18. `OfflineZoneJanitor`: borra las zonas de los proyectos que ya no existen, también al abrir la app, y comprobar que la copia no lleva zonas. (RF-28, RF-29)
      Hecho cuando: `OfflineZoneJanitorTest` y `OfflineBackupExclusionTest` en verde (un proyecto en la papelera conserva sus zonas; al borrarlo definitivamente se borran).
- [ ] T19. `OfflineCoverage` y el mapa: el aviso de sin conexión desaparece dentro del área de una fuente completa y «Detalle máximo guardado» sale por encima del último nivel. (RF-20, RF-21)
      Hecho cuando: `OfflineCoverageTest` y `ProjectOfflineNoticeTest` en verde (dentro de una zona completa no hay aviso, fuera sí y una zona incompleta no lo quita).
- [ ] T20. El mapa usa lo guardado con o sin red y sin pedirlo de nuevo (la caducidad larga de `ZoneHttp`), y se comprueba con una capa WMS viva lo que T1 dejó sin medir: que su dirección es la de la región. (RF-22, RF-23)
      Hecho cuando: en el emulador, con la zona descargada y un contador de peticiones temporal, una zona completa no pide a la red las teselas que tiene (el PNOA, el IGN vectorial y una capa WMS), sin red se dibuja desde la región, y las capas conservan su orden, visibilidad y opacidad; `./gradlew check` en verde.
- [ ] T21. Textos y accesibilidad: repaso de los textos en es y en, de TalkBack y de las zonas táctiles de todas las pantallas de la spec. (RNF-T7, RNF-T8)
      Hecho cuando: Lint sin textos sin usar ni literales en código y los tests de accesibilidad de las dos hojas en verde.
- [ ] T22. Validación final: recorrer la spec RF por RF, ejecutar el informe de trazabilidad, comprobar en el emulador que `getCompletedTileCount` de cada región es el de `TileCover`, hacer la demo con el modo avión, y dejar `plan.md`, la hoja de ruta y el glosario al día. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde y la demo (descargar una zona pequeña, modo avión, abrir el proyecto, ver el mapa y las capas dentro de la zona y el fondo neutro fuera) funciona.
