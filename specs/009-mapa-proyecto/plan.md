# Plan técnico — Spec 009

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-9, ADR-10, ADR-15), `specs/009-mapa-proyecto/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `tools` | Vectores de pyproj: círculo de precisión (`Geod.fwd` en 64 rumbos), Web Mercator (EPSG:3857) y metros por píxel | RF-12, RF-17; RNF |
| `core:geodesy` | `WebMercator` (directa, metros por píxel, zoom para una escala), `AccuracyCircle` (polígono sobre el elipsoide) y `MapGrid` (cuadrícula de la zona visible) | RF-12, RF-14, RF-17, RF-19 |
| `core:model` | `Basemap`, `MapSettings`, `MapViewport` y `ScaleBar` (longitud redonda en la unidad del proyecto) | RF-6, RF-8, RF-10, RF-12, RF-21 |
| `data` | Room v4: tabla `map_viewports` (último encuadre de cada proyecto, migración 3→4 probada); `MapPreferences` en DataStore (mapa base y Catastro) | RF-8, RF-21 |
| `app` | `MapController` (ADR-9) con su implementación MapLibre y un doble para los tests; vista de proyecto con mapa, panel de posición y "Datos del proyecto"; selector de mapa base y Catastro; atribución y barra de escala; rover y círculo; seguimiento; caché, cliente HTTP y aviso sin conexión | RF-1 a RF-21 |

### Dependencia nueva
`org.maplibre.gl:android-sdk-opengl` **13.6.1** (BSD-2-Clause, compatible con GPL-3.0; minSdk 23). Es ADR-9: MapLibre Native detrás de `MapController`.
- Variante OpenGL ES y no `android-sdk`: desde la 13.0 esa usa Vulkan y lo declara obligatorio en el manifiesto, lo que deja fuera móviles sin Vulkan. La variante que elige en tiempo de ejecución pesa unos 10 MB más.
- 13.6.1 es la última con más de dos semanas publicada. La 13.7.1 (15/10/2026) corrige una carrera del renderizador (#4686): se actualiza en la validación (T13) si ya tiene dos semanas.
- Sin envoltorio de Compose: `org.maplibre.compose` sigue en 0.x, desde la 0.15 no usa `android-sdk` (otro motor, sin `OfflineManager` ni `HttpRequestUtil`) y trae Compose Multiplatform. Se usa el `MapView` clásico dentro de `AndroidView`.
- MapLibre añade al manifiesto permisos de ubicación y de estado de la Wi-Fi que la app no usa: se quitan con `tools:node="remove"` (RNF-T1).
- Tamaño: `libmaplibre.so` ocupa 10,8 MB por arquitectura (3,8 MB comprimido). La app se limita a `arm64-v8a`, `armeabi-v7a` y `x86_64`, con las bibliotecas nativas comprimidas en el APK (`useLegacyPackaging = true`): unos 12 MB más en el APK publicado (RNF de la spec, ≤ 15 MB).

## Fuentes de mapas
| Capa | Servicio | Zoom de las teselas | Atribución |
|---|---|---|---|
| IGN vectorial | Estilo `https://vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json`; teselas `https://vt-mapabase.idee.es/1.0.0/mapabase/{z}/{x}/{y}.pbf` | 0–17 | "CC BY 4.0 scne.es" |
| OSM vectorial | OpenFreeMap, estilo "Liberty" `https://tiles.openfreemap.org/styles/liberty` | 0–14 | "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" |
| Ortofoto PNOA | WMTS `https://www.ign.es/wmts/pnoa-ma?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=OI.OrthoimageCoverage&STYLE=default&TILEMATRIXSET=GoogleMapsCompatible&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg` | 0–19 | "CC BY 4.0 scne.es" |
| Ninguno | — | — | — |
| Catastro (encima) | WMS INSPIRE `https://ovc.catastro.meh.es/cartografia/INSPIRE/spadgcwms.aspx`, capas `CP.CadastralParcel,BU.Building`, PNG transparente, `CRS=EPSG:3857` | una imagen por encuadre | "© Dirección General del Catastro" |

- **IGN:** los estilos escriben `"maxZoom"` en lugar de `"maxzoom"`. MapLibre no lo lee, pediría teselas por encima de 17 (respuesta 403) y el mapa se quedaría en blanco al acercarse. Un interceptor de OkHttp corrige la respuesta del estilo (`IgnStylePatch`, función pura con test) antes de que MapLibre la guarde en caché.
- **OpenFreeMap:** sin clave ni límites de vistas; pide un User-Agent propio. Se descartan las teselas de la OSMF: prohíben guardar para usar sin conexión y piden no fijar su URL.
- **PNOA:** WMTS y no TMS, porque envía `Cache-Control` de un año. Por encima de 19, MapLibre amplía las teselas de 19.
- **Catastro:** sus condiciones prohíben las peticiones teseladas y la descarga masiva. Se pide una sola imagen `GetMap` del encuadre visible (ancho y alto de la vista, como máximo 2048 px) cuando la cámara se detiene, 500 ms después, y se pinta como `ImageSource` sobre las cuatro esquinas del encuadre. El encuadre es siempre un rectángulo, porque el mapa no gira ni se inclina (RF-11). El `BBOX` en EPSG:3857 sale de `WebMercator`. Mientras llega la nueva, se mantiene la anterior. No pasa por la caché de MapLibre.

## Modelo de datos
```kotlin
// core:model
enum class Basemap { IGN_VECTOR, OSM_VECTOR, PNOA_ORTHOPHOTO, NONE }
data class MapSettings(val basemap: Basemap = Basemap.IGN_VECTOR, val cadastre: Boolean = false)  // RF-6 a RF-8
data class MapViewport(val latitudeDeg: Double, val longitudeDeg: Double, val zoom: Double)         // RF-21
data class ScaleBarLength(val value: Double, val unit: LinearUnit, val widthPx: Float)               // RF-10
object ScaleBar { fun choose(metresPerPixel: Double, maxWidthPx: Float, unit: LinearUnit): ScaleBarLength }

// core:geodesy
data class GeoPoint(val latitudeDeg: Double, val longitudeDeg: Double)
object WebMercator {
    fun forward(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double>   // EPSG:3857, m
    fun metresPerPixel(latitudeDeg: Double, zoom: Double): Double                  // teselas de 512 px
    fun zoomForScale(scaleDenominator: Double, latitudeDeg: Double): Double        // 1 px = 1/160″ (dp)
}
object AccuracyCircle { fun polygon(center: GeoPoint, radiusM: Double, segments: Int = 64): List<GeoPoint> }
object MapGrid { fun lines(south: Double, west: Double, north: Double, east: Double, spacingM: Double): List<List<GeoPoint>> }

// data (Room v4)
@Entity(tableName = "map_viewports", foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["project_id"], onDelete = CASCADE)])
data class MapViewportEntity(@PrimaryKey val projectId: String, val latitude: Double, val longitude: Double, val zoom: Double)
interface MapViewportRepository { suspend fun viewport(projectId: String): MapViewport?; suspend fun save(projectId: String, viewport: MapViewport) }
interface MapPreferences { val settings: Flow<MapSettings>; suspend fun save(settings: MapSettings) }

// app
data class RoverMarker(val position: GeoPoint, val tone: StatusTone, val accuracyM: Double?)        // RF-16, RF-17
interface MapController {
    fun setSettings(settings: MapSettings)
    fun showRover(marker: RoverMarker?)
    fun moveTo(viewport: MapViewport)
    fun fitBounds(south: Double, west: Double, north: Double, east: Double)
    val camera: StateFlow<MapCamera>     // centro, zoom, metros por píxel, límites visibles, tamaño en px
    val userGestures: Flow<Unit>         // el usuario mueve el mapa con el dedo (RF-20)
}
```
- El encuadre se guarda en Room y no en DataStore: se borra solo con el proyecto (clave ajena en cascada) y sigue el banco de pruebas de migraciones de S006. La migración 3→4 solo añade una tabla, así que la genera Room.
- El mapa base y el Catastro son de toda la app (suposición de la spec): DataStore, como las demás preferencias.

## Algoritmos
### Círculo de precisión (RF-17)
Polígono de 64 vértices alrededor del rover: para cada rumbo θ, Δφ = r·cos θ / M y Δλ = r·sin θ / (N·cos φ), con M y N los radios de curvatura de GRS80 en φ. Para r ≤ 100 m, el error frente a `Geod.fwd` es ≤ 1 mm. El rover y el polígono se pasan a MapLibre con las coordenadas de la GGA tal cual, sin redondear. MapLibre los proyecta a EPSG:3857, como `WebMercator` (RNF de la spec, ≤ 1 cm).

### Barra de escala y cuadrícula (RF-10, RF-12, RF-14)
- Metros por píxel de la vista desde la cámara. Longitud = la mayor de 1, 2 o 5 × 10ⁿ en la unidad del proyecto (m, ft o ft EE. UU.) que cabe en 100 dp.
- Zoom máximo 22: con él, 100 dp son menos de 1,7 m en toda España, así que la barra puede bajar a 1 m (RF-12).
- Cuadrícula: líneas cada longitud de la barra (en metros) en la zona visible, recalculadas cuando la cámara se detiene, con 40 líneas como máximo.

### Estilo de cada mapa base (RF-6, RF-14)
- IGN y OSM: se carga el estilo por su URL, por la caché de MapLibre. Al cargarse, se quitan sus capas de tipo `background` y se ponen debajo de todo un fondo neutro y la cuadrícula. Así, donde falta una tesela se ve el fondo con cuadrícula y no el color del estilo.
- PNOA y Ninguno: estilo construido en código (fondo, cuadrícula y, en PNOA, la capa ráster).
- Encima de todo, en este orden: Catastro, círculo y rover. Se vuelven a añadir al cambiar de estilo.

### Seguimiento del rover (RF-19 a RF-21)
`CameraPolicy`, una máquina de estados pura en `app` con dos estados, SIGUIENDO y LIBRE:
- **Al abrir:** encuadre guardado del proyecto; si no hay, España entera (27,6° a 43,8° N, −18,2° a 4,4° E).
- **Primera posición tras abrir, o "Centrar en el rover":** pasa a SIGUIENDO y centra; si la escala es menor que 1:2.000 (más lejos), acerca a 1:2.000 (`WebMercator.zoomForScale`).
- **SIGUIENDO:** cada posición nueva (≤ 5 Hz) mueve el centro sin cambiar el zoom.
- **Gesto del usuario:** pasa a LIBRE.
- **Cámara detenida:** se guarda el encuadre del proyecto, como mucho cada 2 s.

### Sin conexión (RF-13 a RF-15)
- **Caché:** `OfflineManager.setMaximumAmbientCacheSize(200 MB)` al crear el primer mapa y antes de poner el estilo. MapLibre descarta lo usado hace más tiempo.
- **Cliente HTTP único**, el `@MapHttp OkHttpClient` de Hilt, para MapLibre (`HttpRequestUtil.setOkHttpClient`) y para el Catastro. Lleva tres interceptores:
  - User-Agent `TopoPhone/<versión> (+https://github.com/samuelsl27/TopoPhone)`;
  - el parche del estilo del IGN;
  - `NetworkFailureMonitor`.
- **`NetworkFailureMonitor`:** una `IOException` de conexión (sin red, host desconocido, tiempo agotado) pone `offline = true`, y cualquier respuesta lo devuelve a `false`. El aviso se ve mientras `offline` y el mapa esté a la vista.
- **RNF-T4:** hasta S019 la app no se conecta a la Wi-Fi del receptor, y el mapa usa la red por defecto, como el NTRIP. S019 enlazará este cliente a la red con internet.
- **Arranque sin red:** la vista no espera a las descargas, porque MapLibre carga en segundo plano (RNF de la spec, < 1 s).

## Interfaz
- **Vista de proyecto:** barra superior con el nombre, el indicador de estado y "Editar" ("Datos del proyecto" va en la cabecera del panel: con la simulación y una solución larga, la barra no tiene sitio y taparía "Volver"). Debajo:
  - el mapa, que llena la vista;
  - encima del mapa, el aviso sin conexión arriba y la barra de escala y la atribución abajo a la izquierda;
  - el botón "Capas" y "Centrar en el rover" a la derecha, de 56 dp (RNF-T8);
  - abajo, el panel de posición.
- **Panel plegado:** coordenadas y altura del proyecto con sus avisos (lo que hoy es `PositionValues`, sin factores) y "Precisión H". Desplegado ("Más datos"), también los factores y la convergencia.
- **"Datos del proyecto":** página nueva `SurveyPage.Info` con la tarjeta de datos actual (SC, altura, unidades, datum de las correcciones con su aviso) y la de puntos.
- **"Capas":** hoja inferior con el mapa base (cuatro opciones) y un interruptor para el Catastro.
- **Mapa en los tests:** el `MapView` no funciona en Robolectric (código nativo). La pantalla recibe el mapa como un parámetro composable, como las demás secciones de `TopoPhoneApp`, y los tests le pasan un `FakeMapController` que registra lo que se le pide.

Textos nuevos (es):

| Caso | Texto |
|---|---|
| Botones | "Datos del proyecto", "Capas", "Centrar en el rover", "Más datos", "Menos datos" |
| Capas | "Mapa base", "Mapa IGN", "Mapa OpenStreetMap", "Ortofoto PNOA", "Ninguno", "Catastro" |
| Precisión | "Precisión H" |
| Sin conexión | "Sin conexión: el mapa puede estar incompleto." |
| TalkBack | "Mapa del proyecto", "Rover" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| `MapView` clásico en `AndroidView` detrás de `MapController` | Es ADR-9; caché y cliente HTTP configurables; tests con un doble | `maplibre-compose` (0.x, otro motor, sin esos ajustes) |
| Variante OpenGL ES de MapLibre | Funciona en todos los móviles con Android 8 | Vulkan obligatorio (`android-sdk`), las dos (+10 MB) |
| Catastro como una imagen por encuadre | Sus condiciones prohíben las teselas | Fuente WMS teselada `{bbox-epsg-3857}` |
| OpenFreeMap para OpenStreetMap | Sin clave, permite caché sin conexión | Teselas de la OSMF (prohíben guardar para usar sin conexión) |
| Parche del estilo del IGN en un interceptor | La caché guarda el estilo ya corregido y funciona sin conexión | Copiar los estilos en la app (habría que mantenerlos) |
| Círculo como polígono geográfico | Se escala solo con el zoom y se prueba contra pyproj | Radio en píxeles recalculado en cada zoom |
| Fondo y cuadrícula debajo del mapa base | Donde falta una tesela se ve el fondo neutro (RF-14) sin detectar teselas | Capa encima que tape solo las teselas que fallan |
| Tres arquitecturas con bibliotecas comprimidas | El APK universal crece ~12 MB, no ~45 MB | APK por arquitectura (cambia la publicación de S001) |
| Encuadre por proyecto en Room | Se borra con el proyecto | DataStore con claves por proyecto (quedarían huérfanas) |

## Estrategia de tests
- **`tools` (pytest):** generador de vectores reproducible para el círculo (`Geod.fwd`), EPSG:3857 y metros por píxel.
- **JVM `core:geodesy`:** `WebMercator.forward` contra pyproj (≤ 1 mm); metros por píxel y zoom para 1:2.000; círculo contra `Geod.fwd` (≤ 1 mm, r de 0,005 a 100 m, cerca del ecuador y a 44° N); cuadrícula (separación, número de líneas, límite de 40).
- **JVM `core:model`:** barra de escala en m, ft y ft EE. UU. (longitudes redondas, ancho ≤ 100 dp, 1 m o menos en el zoom máximo).
- **Robolectric `data`:** migración 3→4 con un proyecto de la v3; encuadre por proyecto (guardar, leer, borrado en cascada al vaciar la papelera); preferencias del mapa (valores por defecto y lo elegido).
- **JVM `app`:**
  - `CameraPolicy` (primera posición, centrar, gesto, acercar a 1:2.000, encuadre guardado, España);
  - `IgnStylePatch`;
  - `NetworkFailureMonitor` con una cadena de OkHttp falsa;
  - URL `GetMap` del Catastro desde un encuadre;
  - atribución según las capas visibles.
- **Robolectric `app`** con `FakeMapController`:
  - vista con mapa y panel plegado y desplegado; precisión H;
  - "Datos del proyecto" (los tests de S006/RF-11, RF-28 y RF-31 y de S008/RF-18 pasan a esta página);
  - capas y su persistencia; rover con el color de la solución y sin datos; círculo;
  - centrar; aviso sin conexión; barra de escala;
  - permisos del manifiesto fusionado sin ubicación.
- **Tests de S006 y S008 de la vista de proyecto:** se adaptan al panel.
- **Validación:** tamaño del APK de publicación antes y después (≤ 15 MB más); demo en el emulador (MapLibre no se puede probar en la JVM).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-5 | Vista de proyecto, panel, datos | `ProjectMapScreenTest`, `ProjectInfoTest`, `ProjectViewCrsTest` |
| RF-6 a RF-8 | Mapas base, Catastro, preferencias | `MapLayersTest`, `MapPreferencesTest`, `CadastreRequestTest` |
| RF-9 | Atribución | `MapAttributionTest` |
| RF-10, RF-12 | Barra de escala, zoom máximo | `ScaleBarTest`, `WebMercatorTest`, `ProjectMapScreenTest` |
| RF-11 | Sin giro | `ProjectMapScreenTest` (opciones pedidas al controlador) |
| RF-13 a RF-15 | Caché, fondo, aviso | `NetworkFailureMonitorTest`, `MapGridTest`, `ProjectMapScreenTest` |
| RF-16 a RF-18 | Rover y círculo | `AccuracyCircleTest`, `RoverLayerTest` |
| RF-19 a RF-21 | Seguimiento y encuadre | `CameraPolicyTest`, `MapViewportRepositoryTest`, `TopoPhoneDatabaseTest` |
