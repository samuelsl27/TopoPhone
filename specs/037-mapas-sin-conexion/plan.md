# Plan técnico — Spec 037

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/037-mapas-sin-conexion/spec.md`, `specs/009-mapa-proyecto/plan.md` (el mapa, la caché ambiente de 200 MB, el aviso de sin conexión y el cliente HTTP del mapa) y `specs/036-capas-wms/plan.md` (cómo se piden las teselas WMS y la capa ancla). Sin código.

Ninguna dependencia nueva, ni de Gradle ni de Python: la descarga usa `OfflineManager` y `OfflineRegion` de MapLibre 13.6.1 (ya en `gradle/libs.versions.toml`, ADR-9) y el `OkHttpClient` del mapa; la referencia de `tools` usa `pyproj`, que ya está.

Una parte de lo que decide este plan solo se sabe probando MapLibre en el emulador: cinco comportamientos que la documentación no deja claros. **T1 los mide antes de escribir nada** y anota el resultado en «Resultados del spike» al final de este plan; cada decisión que depende de ellos dice qué plan B tiene.

## Estructura de módulos
- `core:model` (`offline/`), sin `android.*`
  - `DetailLevel` (`NORMAL`, `HIGH`, `MAX`), `OfflineArea` (`Radius`, `Viewport`, `ProjectExtent`), `DownloadableSource` y su tipo (`IGN_VECTOR`, `PNOA`, `OPENFREEMAP`, `WMS`), `ZoneState` y `SourceState`, y las constantes: radio 100 m a 20 km, margen 200 m a 2 km, límite 2 GB por defecto (200 MB a 10 GB), reserva de 500 MB libres, confirmación con datos móviles a partir de 50 MB, 2 % de fallos, tope de OpenFreeMap (3.000 teselas, 50 MB) y 4 peticiones por servidor (RF-3, RF-4, RF-9, RF-12, RF-14, RF-19, RF-31).
  - `ZoneMeta`: el texto JSON que cada región guarda en sus metadatos (ver «Modelo de datos»).
- `core:geodesy`, sin `android.*`
  - `AreaBounds`: de un `OfflineArea` a la caja en latitud y longitud que lo contiene (RF-2, RF-7).
  - `TileCover`: de una caja, un zoom del mapa y un tipo de fuente, las teselas que MapLibre descarga y su número (RF-7, RF-11, constitución nº 2).
- `app` (`ui/offline/`)
  - `OfflineRegionStore` (interfaz) y `MapLibreOfflineStore`: crear, listar, activar, pausar, invalidar, borrar y leer el estado de las regiones de MapLibre, con corrutinas (RF-8 a RF-19, RF-24 a RF-28).
  - `RegionStyles`: el estilo de cada región: el del IGN y el de OpenFreeMap por su dirección; el de la ortofoto y los de cada capa WMS, generados en `files/offline-styles/` (RF-4, RF-8).
  - `DownloadEstimate`: teselas, tamaño estimado y comprobaciones antes de empezar (RF-9 a RF-14).
  - `OfflineDownloader`: la cola de fuentes, la máquina de estados y la pausa al salir de la app (RF-15 a RF-19, RF-26).
  - `OfflineSettings` (DataStore): límite de almacenamiento y la última elección de área y nivel (RF-8, RF-31).
  - `OfflineZoneJanitor`: borra las zonas de los proyectos que ya no existen (RF-28).
  - `DownloadPlanViewModel`, `OfflineZonesViewModel` y sus hojas dentro de «Capas» (RF-1 a RF-31).
  - `OfflineCoverage`: dice si el punto visible cae en el área de una fuente completa y si el zoom pasa del nivel guardado (RF-20, RF-21).
- `app` (`ui/map/`): `MapEnvironment` limita las peticiones por servidor y, si el spike lo exige, alarga la caducidad de lo guardado (RF-22, RF-23); la vista de proyecto muestra «Detalle máximo guardado» y deja de mostrar el aviso de sin conexión dentro de una fuente completa (RF-20, RF-21).
- `tools/mapas_offline/`: `generar_teselas.py`, la referencia independiente con fórmulas de Web Mercator comprobadas con pyproj y `pyproj.Geod`.

## Modelo de datos
**Sin Room y sin migración.** Una zona son tantas **regiones sin conexión de MapLibre** como fuentes tiene (una región por fuente), todas con el mismo `zoneId` en sus metadatos. La base de MapLibre (`mbgl-offline.db`, en el almacenamiento privado, RNF-T5) guarda las teselas, el estilo, los glifos y los símbolos, y es independiente de la caché ambiente de S009: lo descargado en una región no lo descarta el límite de 200 MB. Como no está en la base de la app, **no entra en la copia ni en el paquete** (RF-29) sin código ni test de exclusión más allá de comprobar que `DatabaseSnapshot.TABLES` no cambia.

Metadatos de una región (JSON UTF-8, `v` es la versión):
```
{ "v": 1, "zone": "<uuid>", "project": "<uuid>", "name": "Obra – 14/11/2026",
  "source": { "kind": "WMS", "serviceId": "<uuid>", "layer": "OI.OrthoimageCoverage", "title": "Ortoimagen" },
  "area": { "kind": "RADIUS", "lat": 40.4, "lon": -3.7, "radiusM": 500.0 },
  "detail": "HIGH", "tiles": 4650, "estimatedBytes": 123456789,
  "downloadedAt": 1763100000000, "failed": 0 }
```
La caja, el zoom mínimo y máximo y la dirección del estilo los guarda la propia región (`OfflineTilePyramidRegionDefinition`). `downloadedAt` es `null` hasta que la región se completa y se conserva al actualizar (RF-26).

Los ajustes (límite de almacenamiento, último nivel de detalle, último tipo de área, último radio y margen) van en DataStore, como los del mapa (S009/RF-8). El nombre de la zona, su fecha y sus fuentes salen de los metadatos; el estado, del estado de cada región.

## Algoritmos
### A1. Del área a una caja (RF-2 a RF-7)
Toda zona se descarga como **una caja** (`LatLngBounds`): la región de pirámide de teselas de MapLibre trabaja con cajas.
- **Radio:** la caja que contiene el círculo de radio `r` alrededor del centro (S006/RF-37): `Δφ = r·1,01 / M(φ0)`, con `M` el radio de curvatura del meridiano en el centro; `Δλ = r·1,01 / (N(φe)·cos φe)`, con `φe` la latitud de borde más alejada del ecuador. El 1 % de más es un margen de seguridad: la caja contiene todo punto a distancia ≤ `r` (RF-7) y queda a menos de un 2 % de la caja exacta.
- **Encuadre:** la zona visible del mapa en ese momento (RF-2).
- **Todo el proyecto:** de la latitud y longitud mínimas y máximas de los puntos del proyecto, ampliadas en `margen` por cada lado con las mismas fórmulas de arriba (RF-4, RF-6).
Alternativa descartada: `OfflineGeometryRegionDefinition` con el círculo como polígono (menos teselas, un 21 % menos con un radio, pero el número exacto de teselas de RF-11 dependería de cómo corte MapLibre el polígono y no habría cálculo independiente que lo compruebe con un test de JVM).

### A2. Teselas de una fuente (RF-7, RF-11, constitución nº 2)
MapLibre pide, para cada zoom `z` del mapa entre el mínimo y el máximo de la región, las teselas que cubren la caja, con el nivel de tesela de la fuente: `round(z + log2(512 / tamañoTesela))` en las de ráster y `floor(z + log2(512 / tamañoTesela))` en las vectoriales (512 px por omisión), acotado al `minzoom` y `maxzoom` de la fuente. `TileCover` calcula lo mismo:
1. Para cada nivel de tesela `n`, las columnas `x` de `floor(X(lonOeste, n))` a `floor(X(lonEste, n))` y las filas `y` de `floor(Y(latNorte, n))` a `floor(Y(latSur, n))`, con `X` e `Y` las coordenadas de tesela de Web Mercator (`WebMercator`).
2. El número de teselas de la fuente es la suma sobre los niveles; el de la zona, la suma sobre sus fuentes.
Esta convención (`floor` a ambos lados, el borde exacto incluye la tesela de al lado) es la de MapLibre y se confirma en T1; la referencia de `tools` usa fórmulas propias de Web Mercator y comprueba con pyproj (EPSG:4326 → 3857) que las esquinas de cada tesela caen donde dice el cálculo.

### A3. Nivel de detalle (RF-8)
El zoom máximo del mapa de cada nivel sale del denominador de escala en la latitud del centro de la zona (`WebMercator.zoomForScale`): `NORMAL` 1:5.000, `HIGH` 1:1.000 y `MAX` el zoom máximo del mapa (S009/RF-12, 22). El zoom mínimo es 0 en las fuentes vectoriales y los 10 niveles de abajo del máximo en las ráster, para no descargar el mundo (el mapa de S009 ya sirve de fondo desde la caché). Cada fuente se acota después a su propio `maxzoom` (el PNOA, 19; el IGN vectorial, 17): por encima, MapLibre amplía el último nivel, y `OfflineCoverage` lo avisa (A6).

### A4. Antes de descargar (RF-9 a RF-14)
1. Para cada fuente marcada: número exacto de teselas (A2) y tamaño estimado = teselas × tamaño medio de su tipo. Los tamaños medios son constantes revisadas con descargas reales en T1: PNOA (JPEG) 25 KB, IGN vectorial 20 KB, OpenFreeMap 25 KB, WMS en JPEG 25 KB y WMS en PNG 60 KB; la lista de zonas muestra después el tamaño real (RF-24).
2. **OpenFreeMap:** si la fuente pasa de 3.000 teselas o de 50 MB estimados, bloquea con el motivo y la forma de reducirla (RF-9).
3. **Servicios WMS propios** (los que no son un preset): confirmación con el nombre del servicio, una vez por zona y servicio (RF-10).
4. **Espacio:** `StatFs` del almacenamiento privado; si libre < estimado + 500 MB, o ocupado + estimado > límite, bloquea y dice cuánto falta (RF-12).
5. **Red:** sin red con internet (`NET_CAPABILITY_INTERNET` y `NET_CAPABILITY_VALIDATED`), no empieza (RF-13); con datos móviles (`TRANSPORT_CELLULAR`) y más de 50 MB, pide confirmación (RF-14); nunca por la Wi-Fi del receptor (RNF-T4: se comprueba la red por omisión de Android, que es la de internet).

### A5. Descargar, pausar, reanudar y cancelar (RF-15 a RF-19)
Una fuente cada vez, en el orden de la lista (así «la fuente en curso» de RF-15 es una sola): `OfflineRegion.setDownloadState(ACTIVE)`; el observador da `completedResourceCount`, `requiredResourceCount` y `completedResourceSize`, y la interfaz suma los de las fuentes ya terminadas.
- **Pausar y reanudar:** `INACTIVE` y `ACTIVE` sobre la región en curso; lo ya guardado se conserva (RF-16).
- **Cancelar:** `INACTIVE` y borrar todas las regiones de la zona, con confirmación; no es lo mismo que pausar, que la deja «Incompleta» y reanudable (RF-16, RF-18).
- **Salir de la app (`ON_STOP`), bloquear la pantalla o perder la red:** todas las regiones activas pasan a `INACTIVE`; la zona queda «Incompleta» con su porcentaje y se reanuda a mano (RF-18). Al arrancar la app las regiones están inactivas, así que un reinicio da el mismo estado.
- **Errores:** cada `onError` suma un fallo a su fuente; al terminar, si los fallos pasan del 2 % de sus teselas, la fuente queda «Incompleta» con cuántas faltan y «Reintentar» vuelve a activar la región (descarga lo que no tiene, RF-19).
- **Pantalla encendida** mientras la pantalla de descarga está a la vista (`keepScreenOn`, RF-17).
- **Peticiones:** el `Dispatcher` del cliente del mapa pasa a `maxRequestsPerHost = 4` (RNF); como la cola va de una en una, a OpenFreeMap nunca llegan más de 4 a la vez.

### A6. Usar el mapa (RF-20 a RF-23)
MapLibre busca primero en su base sin conexión por la dirección de cada tesela, y las regiones guardan las teselas con las mismas direcciones que pide el mapa vivo: la plantilla de cada capa WMS de la región es la misma de `WmsRequests.tileTemplate` (S036/A3) y la del PNOA, la de `MapStyles`. Por eso lo guardado se usa solo, con o sin red (RF-22), y las capas conservan su orden, visibilidad y opacidad porque no se tocan (S036).
- **Sin consultar la red (RF-23):** depende de que MapLibre no revalide lo guardado. T1 lo mide; si revalidara, el plan B es que el cliente del mapa (el que ya corrige el estilo del IGN, S009/RF-6) ponga `Cache-Control: max-age=31536000` en las respuestas mientras se descarga una zona.
- **Aviso de sin conexión (RF-20):** `OfflineCoverage.isCovered(zonas, cámara)` es cierto si el centro de la vista cae en la caja de una fuente completa que dibuja el mapa actual; en ese caso la vista de proyecto no muestra el aviso de S009/RF-15.
- **«Detalle máximo guardado» (RF-21):** si el zoom pasa del máximo de la zona (y no del `maxzoom` de la fuente, que MapLibre ya amplía) dentro de su caja, se muestra el texto. Si sin red MapLibre no amplía el último nivel, el controlador baja el `maxzoom` de las fuentes de la zona mientras no haya conexión (T1 lo dice).

### A7. Actualizar, borrar y limpiar (RF-24 a RF-28)
- **Actualizar:** `OfflineRegion.invalidate()` marca lo guardado como caducado y la descarga activa revalida cada tesela con su `ETag`: las que no cambiaron no se vuelven a bajar y las nuevas sustituyen a las viejas al llegar; la zona sigue sirviendo con las anteriores y, si se interrumpe, conserva su fecha (RF-26).
- **Borrar:** `OfflineRegion.delete()` de cada región; MapLibre libera las teselas que no usa otra región. Los metadatos se borran con ella (RF-27).
- **Proyecto borrado (RF-28):** `OfflineZoneJanitor` observa los identificadores de todos los proyectos (también los de la papelera) y, al desaparecer uno, borra las regiones con ese `project`; se ejecuta también al abrir la app. No toca el código de S006.
- **Tamaño total:** la suma de `completedResourceSize` de todas las regiones; el límite se aplica a ese total (RF-31).

## Contratos
- `OfflineRegionStore`: `create(definition, meta): RegionId`, `list(): List<StoredRegion>` (con metadatos y estado), `observe(id): Flow<RegionProgress>`, `setActive(id, active)`, `invalidate(id)`, `delete(id)`, `updateMeta(id, meta)`. Todas devuelven en el hilo principal lo que MapLibre entrega en él y no bloquean más de 16 ms (RNF-T13).
- `RegionProgress`: `state` (`ACTIVE`, `INACTIVE`), `completed`, `required`, `precise`, `bytes`, `tiles`, `failures`.
- `DownloadEstimate.of(zone, settings, storage, network): Estimate` con `sources: List<SourceEstimate>`, `blocks: List<Block>` (`OPENFREEMAP_LIMIT`, `NO_SPACE(missingBytes)`, `OVER_LIMIT(missingBytes)`, `NO_INTERNET`) y `confirmations: List<Confirmation>` (`MOBILE_DATA`, `OWN_WMS(serviceName)`).
- `TileCover.count(bounds, mapZoomRange, kind, tileSize, sourceZoomRange): Long` y `.tiles(...)`: `Sequence<TileId>`.
- Estados de la zona: `Completa` (todas sus fuentes completas), `Incompleta` (alguna no; con su porcentaje) y `Actualizando` (solo en memoria, mientras dura); la interfaz da cada uno con texto además de color (RNF-T8).
- Entradas del log (S014, categoría `APP`): `Zona sin conexión «<nombre>»: <fuente>, <n> teselas, <resultado>`, sin coordenadas (RF-30).

Textos nuevos (es y en): «Mapas sin conexión», «Descargar zona», las tres formas de definir el área con su radio y su margen, los tres niveles, la lista de fuentes con su motivo si no se puede, la previsión (teselas, tamaño, espacio libre, ocupado), cada bloqueo con cómo reducir, las dos confirmaciones, el progreso con pausa, reanudar y cancelar, los estados, «Reintentar», «Actualizar», renombrar, borrar con confirmación, el límite de almacenamiento y «Detalle máximo guardado».

Accesibilidad (RNF-T8): zonas de 56 dp; el progreso y el estado también por texto; TalkBack lee el estado, el porcentaje y el tamaño de cada zona.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Una zona son regiones sin conexión de MapLibre, una por fuente | MapLibre ya baja el estilo, los glifos por rango, los símbolos y las teselas; guarda aparte de la caché ambiente (no se descarta); pausar, reanudar y revalidar ya existen; y no entra en la copia (RF-29) | Un descargador propio con un almacén de ficheros y un interceptor: hay que repetir el lector de estilos, los glifos por rango y las direcciones exactas de MapLibre |
| Sin Room: metadatos JSON en la región | La base de MapLibre y la de la app no se desincronizan; no hay migración ni fila que la copia lleve sin teselas | Una tabla `offline_zones` (v33): dos fuentes de verdad y filas huérfanas al restaurar |
| Una región por fuente | Estado, tamaño y fallos por fuente (RF-15, RF-19) sin mezclar | Una región con todas las fuentes en un solo estilo (no se sabría cuál falla) |
| Una caja y no un polígono | Número de teselas comprobable con un test de JVM (constitución nº 2) | `OfflineGeometryRegionDefinition` |
| Una fuente cada vez | «Fuente en curso» y 4 peticiones por servidor | Todas a la vez |
| `maxRequestsPerHost = 4` en el cliente del mapa | Condición de OpenFreeMap; el mapa con 4 por servidor sigue fluido (HTTP/2) | Un cliente aparte para las descargas (MapLibre solo usa uno) |
| Estilos de la ortofoto y de cada WMS como ficheros en `files/offline-styles/` | MapLibre pide el estilo por dirección; `file://` vale | Estilos en línea (no existen en la API) |
| Tamaños medios por tipo de fuente | Estimar sin pedir nada, también sin red | Pedir unas teselas de prueba (necesita red y retrasa) |
| El límite de 2 GB se aplica al total de las regiones | Es lo que pide RF-31; la caché ambiente tiene el suyo (S009) | Sumarlos |
| Limpiar con un observador y no desde S006 | S006 no se toca | Un gancho en el borrado definitivo de S006 |

## Decisiones que pido que confirmes
- **D1:** el radio descarga la **caja** que lo contiene (un 27 % más de teselas que el círculo) en lugar del círculo exacto, para que el número de teselas se pueda comprobar de forma independiente.
- **D2:** una zona es un grupo de regiones de MapLibre sin tabla en Room; si algún día hace falta guardarla en la copia habría que añadir una.
- **D3:** el zoom mínimo de las fuentes ráster es el máximo menos 10; por debajo sirve la caché de S009.
- **D4:** la «Zona» no se puede editar (área, nivel, fuentes) una vez descargada: para cambiarla se borra y se descarga otra; «Actualizar» solo renueva lo que hay.
- **D5:** «Cancelar» durante la descarga borra la zona entera (con confirmación); «Pausar» es lo que la deja «Incompleta» para seguir después. La spec (RF-16) no dice qué conserva cancelar.

## Estrategia de tests
Los nombres de clase y de test de `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:model`:** `DetailLevelTest`, `OfflineLimitsTest` y `ZoneMetaTest` (ida y vuelta del JSON, versión desconocida).
- **JVM `core:geodesy`:** `TileCoverTest` frente a `fixtures/mapas_offline/teselas.csv` (radio, encuadre y todo el proyecto, en 6 niveles y 3 latitudes: España peninsular, Canarias y Baleares; números de tesela exactos), `AreaBoundsTest` (todos los puntos de la circunferencia con `Geod` dentro de la caja, y la caja a menos de un 2 % de la exacta; el rectángulo de todo el proyecto con margen).
- **`tools`:** `generar_teselas.py` calcula con fórmulas propias de Web Mercator las teselas y las cajas EPSG:3857 de cada tesela y las compara con pyproj (EPSG:4326 → 3857) a menos de 1 mm; `test_teselas.py` comprueba que lo guardado es lo que se genera.
- **Robolectric `app`:** con un `FakeOfflineRegionStore` — `DownloadEstimateTest` (cada bloqueo y cada confirmación), `OfflineDownloaderTest` (cola, pausa, reanudación, cancelar, `ON_STOP`, 2 % de fallos, reintento, actualizar sin perder la fecha), `DownloadPlanViewModelTest`, `OfflineZonesViewModelTest`, `OfflineZoneJanitorTest`, `OfflineCoverageTest`, `RegionStylesTest` (el JSON de cada estilo y la plantilla WMS idéntica a la del mapa), `OfflineSettingsTest`, `DownloadPlanSheetTest`, `OfflineZonesSheetTest`, `OfflineProgressTest` (pantalla encendida, botones de 56 dp), `ProjectOfflineNoticeTest` (el aviso desaparece dentro de una fuente completa y vuelve fuera) y `MapDispatcherTest` (4 por servidor).
- **Manual (spike T1 y demo en el emulador):** el spike de T1; después, descargar una zona pequeña con la ortofoto y una capa WMS, activar el modo avión, abrir el proyecto y ver el mapa y las capas dentro de la zona y el fondo neutro fuera; y comprobar que `getCompletedTileCount` de cada región es el que da `TileCover`.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | `ui/offline`, entrada en «Capas» | `OfflineZonesSheetTest` |
| RF-2, RF-3, RF-4 | A1, `AreaBounds` | `AreaBoundsTest`, `DownloadPlanViewModelTest` |
| RF-5, RF-6 | A1, `DownloadPlanViewModel` | `DownloadPlanViewModelTest` |
| RF-7 | A1, A2 | `AreaBoundsTest`, `TileCoverTest` |
| RF-8 | A3, `OfflineSettings` | `DownloadPlanViewModelTest`, `OfflineSettingsTest` |
| RF-9 | A4 | `DownloadEstimateTest` |
| RF-10 | A4 | `DownloadEstimateTest`, `DownloadPlanSheetTest` |
| RF-11 | A2, A4 | `TileCoverTest`, `DownloadEstimateTest`, `DownloadPlanSheetTest` |
| RF-12 | A4 | `DownloadEstimateTest` |
| RF-13, RF-14 | A4 | `DownloadEstimateTest` |
| RF-15, RF-16 | A5 | `OfflineDownloaderTest`, `OfflineProgressTest` |
| RF-17 | A5 | `OfflineProgressTest` |
| RF-18, RF-19 | A5 | `OfflineDownloaderTest` |
| RF-20 | A6 | `OfflineCoverageTest`, `ProjectOfflineNoticeTest` |
| RF-21 | A6 | `OfflineCoverageTest` |
| RF-22, RF-23 | A6 | `RegionStylesTest`, `MapDispatcherTest`, demo en el emulador |
| RF-24, RF-25 | `OfflineZonesViewModel` | `OfflineZonesViewModelTest`, `OfflineZonesSheetTest` |
| RF-26 | A7 | `OfflineDownloaderTest`, `OfflineZonesViewModelTest` |
| RF-27 | A7 | `OfflineZonesViewModelTest` |
| RF-28 | A7 | `OfflineZoneJanitorTest` |
| RF-29 | Modelo de datos | `OfflineBackupExclusionTest` |
| RF-30 | Contratos | `OfflineDownloaderTest` |
| RF-31 | A4, A7, `OfflineSettings` | `OfflineSettingsTest`, `DownloadEstimateTest` |

## Resultados del spike
(Pendiente: T1. Cinco preguntas, cada una con su plan B: 1) el desfase entre zoom del mapa y nivel de tesela en las fuentes ráster de 256 px y en las vectoriales, y si `getCompletedTileCount` coincide con `TileCover`; 2) si MapLibre revalida lo guardado cuando hay red; 3) qué hace el mapa sin red por encima del último nivel guardado; 4) qué cuenta `onError` y si la región se queda esperando; 5) si el estilo `file://` y la plantilla WMS de una región llevan a las mismas direcciones que el mapa vivo.)
