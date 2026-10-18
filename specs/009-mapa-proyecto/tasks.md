# Tareas — Spec 009

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools`: vectores de pyproj para el círculo de precisión (`Geod.fwd`, 16 rumbos, r de 0,005 a 100 m), Web Mercator (EPSG:3857) y metros por píxel. (RF-12, RF-17)
      Hecho cuando: `uv run --project tools pytest tools` en verde y los CSV nuevos en `fixtures/geodesia/`.
- [x] T2. `core:geodesy`: `WebMercator` (directa, metros por píxel, zoom para una escala), `AccuracyCircle` y `MapGrid`. (RF-12, RF-14, RF-17, RF-19)
      Hecho cuando: `WebMercatorTest`, `AccuracyCircleTest` y `MapGridTest` en verde contra los vectores (≤ 1 mm).
- [x] T3. `core:model`: `Basemap`, `MapSettings`, `MapViewport` y `ScaleBar` en m, ft y ft EE. UU. (RF-6, RF-10, RF-12)
      Hecho cuando: `ScaleBarTest` en verde (longitudes redondas, ≤ 100 dp, 1 m o menos en el zoom máximo).
- [x] T4. `data`: Room v4 con `map_viewports` (migración 3→4) y su repositorio; `MapPreferences` en DataStore. (RF-8, RF-21)
      Hecho cuando: `TopoPhoneDatabaseTest` (migración), `MapViewportRepositoryTest` (cascada al vaciar la papelera) y `MapPreferencesTest` en verde.
- [x] T5. `app`: MapLibre (OpenGL ES 13.6.1) con tres arquitecturas y bibliotecas comprimidas, sin permisos de ubicación; `MapController` y su implementación con el mapa del IGN; cliente HTTP con User-Agent. (RF-6, RF-11)
      Hecho cuando: el test de permisos del manifiesto en verde, `assembleDebug` y `assembleRelease` compilan y el APK de publicación crece ≤ 15 MB.
- [ ] T6. `app`: estilos de los cuatro mapas base (IGN con el parche de `maxzoom`, OpenFreeMap, PNOA, Ninguno) con el fondo neutro y la cuadrícula debajo. (RF-6, RF-14)
      Hecho cuando: `IgnStylePatchTest` y `MapStylesTest` en verde.
- [ ] T7. `app`: vista de proyecto con el mapa a pantalla completa, panel de posición plegado y desplegado con la precisión H, y página "Datos del proyecto"; tests de S006 y S008 adaptados. (RF-1 a RF-5)
      Hecho cuando: `ProjectMapScreenTest`, `ProjectInfoTest` y `ProjectViewCrsTest` en verde.
- [ ] T8. `app`: hoja "Capas" con el mapa base y el Catastro, recordados para toda la app; el Catastro como una imagen por encuadre al detenerse la cámara. (RF-6, RF-7, RF-8)
      Hecho cuando: `MapLayersTest` y `CadastreRequestTest` en verde.
- [ ] T9. `app`: atribución de las capas visibles y barra de escala; norte arriba y zoom máximo 22. (RF-9, RF-10, RF-11, RF-12)
      Hecho cuando: `MapAttributionTest` y los casos de barra de escala y opciones del mapa de `ProjectMapScreenTest` en verde.
- [ ] T10. `app`: rover con el color de la solución y círculo de precisión; desaparecen sin datos. (RF-16, RF-17, RF-18)
      Hecho cuando: `RoverLayerTest` en verde (color por solución, círculo con la precisión H, sin GST, "Sin datos").
- [ ] T11. `app`: `CameraPolicy` (seguir, gesto, centrar, acercar a 1:2.000) y encuadre guardado por proyecto o España. (RF-19, RF-20, RF-21)
      Hecho cuando: `CameraPolicyTest` y los casos de centrar y encuadre de `ProjectMapScreenTest` en verde.
- [ ] T12. `app`: caché de 200 MB, `NetworkFailureMonitor` y aviso sin conexión. (RF-13, RF-14, RF-15)
      Hecho cuando: `NetworkFailureMonitorTest` y el caso del aviso de `ProjectMapScreenTest` en verde.
- [ ] T13. Validación: recorrer la spec RF por RF, `rf_report`, tamaño del APK, demo en el emulador con una reproducción según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos, el APK crece ≤ 15 MB y la demo funciona. La demo de campo con el RS3 queda para el usuario.
