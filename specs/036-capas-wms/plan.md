# Plan técnico — Spec 036

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/036-capas-wms/spec.md`, `specs/009-mapa-proyecto/plan.md` (el mapa, el Catastro, la atribución, la caché y el aviso sin conexión), `specs/042-superficies-landxml/plan.md` (el lector de XML por eventos que esta spec reutiliza) y `specs/013-copia-seguridad-local/plan.md` (la instantánea de la base de datos). Sin código.

Una dependencia de Gradle que ya está en el proyecto y pasa a otro módulo; ninguna nueva, y ninguna de Python:
- `data` usa `okhttp` (ya en `gradle/libs.versions.toml` para el mapa, ADR-13) para pedir el GetCapabilities y la consulta de información. Alternativa descartada: `HttpURLConnection` de la JDK (ADR-13 eligió OkHttp para el HTTP normal; con él, la carga de 20 s, la cancelación y las pruebas con un interceptor que devuelve respuestas grabadas son directas, sin servidor de pruebas nuevo).
- Leer el GetCapabilities: `XmlStreamReader` de `core:formats` (S042), que ya salta el `DOCTYPE`, ignora los espacios de nombres y entiende ISO-8859-1. Alternativas descartadas: `XmlPullParser` de Android (no existe en la JVM de los tests) y un DOM (un GetCapabilities de 20 MB no cabe en un árbol).
- Los presets son un JSON en los recursos de `core:formats`, con `kotlinx.serialization` (ya en ese módulo). Alternativa descartada: una lista en código Kotlin (la tendría que leer también el script de `tools` que los verifica, y dos copias se desincronizan).

## Estructura de módulos
- `core:model` (`wms/`)
  - `WmsService`, `WmsLayerInfo`, `WmsRequestMode`, `ProjectMapLayer`, `WmsPreset` y las constantes (`MAX_PROJECT_LAYERS = 10`, `TIMEOUT_S = 20`, `OPACITY_STEP = 5`) (RF-4, RF-17, RF-21, RF-26).
  - `WmsAddress`: normaliza la dirección (HTTPS por defecto si se escribe sin esquema, host en minúsculas, sin `#`), separa sus parámetros (`?map=`) y decide si es HTTP admisible (red privada o de enlace local) (RF-5, RF-10).
- `core:formats` (`wms/`), sin `android.*`, con `core:geodesy` para Web Mercator
  - `WmsCapabilitiesReader`: de un `InputStream` a `WmsCapabilities` (título, versión, atribución, formatos de GetMap y de GetFeatureInfo, árbol de capas), por eventos, 1.1.1 y 1.3.0, hasta 20 MB y 10 niveles; lee también la `ServiceException` (RF-2, RF-3, RF-8, RF-9, RF-11, RF-12).
  - `WmsRequests`: las URL de GetCapabilities (conserva los parámetros de la dirección), de teselas con `{bbox-epsg-3857}`, de una imagen y de GetFeatureInfo; números con `Locale.ROOT` (RF-1, RF-5, RF-29, RF-36).
  - `FeatureInfoText`: de la respuesta de una consulta (texto plano, GML o HTML) al texto que se muestra, o «sin datos» (RF-36, RF-38).
  - `WmsPresets`: el catálogo de `presets.json` (RF-13, RF-16).
- `data` (`wms/`)
  - Entidades y DAO de Room: `wms_services`, `wms_layers` y `project_wms_layers` (v32).
  - `WmsHttp` (interfaz) y `OkHttpWmsHttp`: el GetCapabilities y la consulta con 20 s y 20 MB, sin el aviso de falta de conexión del mapa (RF-8, RF-11, RF-39).
  - `WmsLibraryRepository`: servicios, capas leídas, renombrar, borrar con el aviso, actualizar (RF-4, RF-6, RF-7); y `WmsServiceAdder`: pedir, leer, validar y guardar (RF-1, RF-2, RF-8 a RF-10, RF-14).
  - `ProjectLayersRepository`: las capas de cada proyecto con orden, visibilidad y opacidad (RF-18 a RF-28).
  - La copia y el paquete: las tres tablas en `DatabaseSnapshot.TABLES` y la fusión por dirección al importar un paquete (RF-25).
- `app`
  - `ui/map`: `MapController` gana `showWmsLayers`, `longPresses` y `showPressMarker`; `WmsLayerPlan` (qué fuentes y capas de MapLibre se dibujan, en qué orden y con qué opacidad, puro y comprobable) y `WmsOneImageLoader` (las capas en «una imagen», con el patrón del Catastro); `MapAttribution` suma las atribuciones de las capas visibles (RF-29 a RF-35).
  - `ui/wms`: `WmsLibraryViewModel` y su hoja (añadir servicio, presets, capas de un servicio, actualizar, renombrar, borrar), y la sección de capas del proyecto dentro de la hoja «Capas» de S009 (RF-1 a RF-28).
  - `ui/map`: `MapMenuViewModel` y las hojas del menú del mapa, de la consulta de información y de las coordenadas (RF-34 a RF-41).
- `tools/formatos`: `generar_wms.py` (lector de referencia con `xml.etree` y pyproj, ficheros sintéticos de los casos límite) y `wms_presets.py` (verifica los presets en vivo y graba sus GetCapabilities; no se ejecuta en CI).

## Modelo de datos
Room pasa de la versión 31 a la 32 con tres tablas nuevas (auto-migración, sin tocar las existentes):
- **`wms_services`**: `id` (UUID), `name` (el que ve el usuario, editable), `address` (normalizada, única), `title`, `version` (`1.3.0` o `1.1.1`, la del último GetCapabilities leído), `attribution`, `preset_key` (null si es propio), `request_mode` (`TILES` o `IMAGE`), `map_formats` y `info_formats` (listas separadas por `|`), `created_at`, `updated_at`.
- **`wms_layers`** (el árbol leído, en orden del documento): `service_id` (con borrado en cascada), `ordinal`, `depth`, `name` (null si solo agrupa), `title`, `abstract`, `transparent`, `queryable`, `mercator_code` (el código de Web Mercator que ella o un ascendiente anuncia, `EPSG:3857` o un alias, o null si no anuncia ninguno). El árbol se reconstruye con `ordinal` y `depth`; índices por `(service_id, ordinal)` y `(service_id, name)`.
- **`project_wms_layers`**: `id`, `project_id` y `service_id` (los dos con cascada), `layer_name`, `position` (0 es la de arriba), `visible`, `opacity` (0 a 100, de 5 en 5), `added_at`; única por `(project_id, service_id, layer_name)`.

Ejemplo de un servicio guardado: `Ortofoto ICV 2024` → `https://terramapas.icv.gva.es/0202_2024CVAL0025`, `1.3.0`, `© Institut Cartogràfic Valencià`, `TILES`, capas `2024CVAL0025_RGB` y `2024CVAL0025_IRG` con `mercator_code = EPSG:3857`.

La **copia completa** lleva las tres tablas enteras. El **paquete** lleva las capas del proyecto y, de la biblioteca, solo los servicios que usan y sus capas leídas. Al importar un paquete (S013/RF-25), cada servicio se compara por su dirección: si el móvil ya lo tiene se usa el suyo y las capas se reenlazan; si no, se añade (A7).

Los presets, en `presets.json` (los verifica `wms_presets.py`):
```
{ "key": "ign-pnoa-ma", "name": "PNOA máxima actualidad", "region": "España",
  "address": "https://www.ign.es/wms-inspire/pnoa-ma", "attribution": "© IGN", "license": "CC BY 4.0 scne.es",
  "licenseUrl": "…", "requestMode": "TILES", "preferredFormat": "image/jpeg", "infoVersion": null,
  "group": "IGN", "suggestedLayers": ["OI.OrthoimageCoverage"] }
```
`preferredFormat` es el formato de las teselas (la ortofoto va en JPEG), `infoVersion` fuerza la versión de las consultas de información cuando el servicio no responde bien en la suya, y `group` y `year` agrupan los presets en la lista (la ortofoto del ICV tiene 36 años).

## Presets verificados
Las direcciones salen de una verificación con peticiones reales del 13/11/2026 (GetCapabilities en las dos versiones, una consulta de información de prueba y, en los casos dudosos, un `GetMap`); todas responden por HTTPS con certificado válido y ninguna pasa de 3,3 MB ni de 7 niveles de capas. El catálogo completo está en `presets.json` (T6) y `wms_presets.py` lo vuelve a verificar en vivo.

| Grupo | Presets | Modo | Notas |
|---|---|---|---|
| IGN | PNOA máxima actualidad, cartografía base, MDT, límites administrativos, hidrografía | teselas | PNOA y base en JPEG; la ortofoto no es consultable (la consulta útil está en `OI.MosaicElement`) |
| Catastro | cartografía (`Catastro`), INSPIRE (`CP.CadastralParcel`, `BU.Building`, la de S009) | una imagen | sin teselas ni descarga masiva (sus condiciones); el primero contesta en 1.1.1 con ISO-8859-1 y `DOCTYPE`; el INSPIRE solo consulta bien en 1.1.1 (`infoVersion`) |
| ICV | ortofotos de 1945 a 2025 (36 servicios, uno por vuelo) y cartografía 1:5.000, 1:25.000, 1:50.000, 1:100.000 y 1:200.000 | teselas | la cartografía se dibuja solo en su tramo de escala (cada servicio, el suyo); el nombre de la capa raíz agrupa todo; las pancromáticas llevan `_PAN` |
| ICGC | ortofoto vigente, referencial topográfico, mapa base | teselas | el topográfico solo a escalas menores de ~1:23.600; el mapa base contesta en 1.1.1 |
| GRAFCAN | OrtoExpress, ortofoto urbana, mapa topográfico integrado | una imagen | «prohibido el uso comercial y la descarga masiva»: la licencia se muestra al añadirlo; solo JPEG (ortofotos) o solo PNG (mapa); sin atribución declarada |
| Andalucía | ortofoto 2022 (25 cm), topográfico ráster 1:10.000 | teselas | el 1.1.1 del ráster usa un prefijo XML sin declarar (el lector no mira prefijos); sin licencia declarada |
| Navarra | IDENA (ortofoto de máxima actualidad y mapa base, un solo servicio) | teselas | XML de 3,3 MB y 1.454 capas (3,5 a 6 s) |
| País Vasco | ortofoto más actualizada (geoEuskadi) | teselas | su cartografía base solo existe como WMTS |
| Madrid | ortofoto (mosaico 2019, 50 cm), base topográfica BTA 1:10.000 | teselas | la ortofoto no es consultable y sus títulos son nombres de fichero |
| Galicia | ortofoto PNOA 2023 | teselas | solo declara EPSG:3785 y acepta 3785 y 3857; capa única de nombre `0`; su mapa base tiene 506 capas numéricas y no vale |

Lo que esta verificación cambia respecto a la spec:
- **País Vasco y Galicia no tienen mapa base WMS utilizable**: entran solo con su ortofoto (la suposición de la spec decía ortofoto y cartografía base de cada comunidad).
- **Los alias de Web Mercator** (`EPSG:900913`, `EPSG:102100`, `EPSG:102113` y `EPSG:3785`) se aceptan como EPSG:3857 (A1, decisión D5).
- **Un servicio contesta en una versión distinta de la pedida**: el Catastro en 1.1.1 aunque se pida la 1.3.0, y el INSPIRE del Catastro, los límites y la hidrografía del IGN y el ICV al contrario. Por eso se guarda la versión que declara el documento y todas las peticiones posteriores usan esa.

## Algoritmos
### A1. Añadir un servicio (RF-1, RF-2, RF-5, RF-8 a RF-12)
1. `WmsAddress` normaliza lo escrito. Si es HTTP y no es de una red privada o de enlace local, se rechaza con el motivo (RF-10).
2. Se pide el GetCapabilities con `SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0`, conservando los parámetros que ya trae la dirección y sustituyendo los suyos que coincidan sin distinguir mayúsculas (RF-5). El tope de cada petición es de 20 s y de 20 MB (RF-8, RF-11).
3. Se lee el documento por eventos, en la versión en que venga: el Catastro contesta 1.1.1 a una petición 1.3.0 y vale (RF-2). Si el servicio contesta con una excepción de versión no admitida o con un documento vacío, se repite con `VERSION=1.1.1`. Si tampoco, o devuelve otra excepción, un XML dañado o no responde, se indica el motivo y no se guarda nada (RF-8).
4. Una capa **admite EPSG:3857** si ella o un ascendiente lo anuncia (los CRS se heredan) con `EPSG:3857` o con un alias de Web Mercator: `EPSG:900913`, `EPSG:102100`, `EPSG:102113` o `EPSG:3785`. Se guarda el código que anuncia, con preferencia por `EPSG:3857`, y con ese código se piden sus imágenes. Si ninguna capa con nombre lo admite, el servicio se rechaza (RF-9). Las capas sin nombre o sin 3857 se guardan pero no se pueden añadir a un proyecto (RF-12).
5. El servicio y sus capas se guardan en una sola transacción, por lotes de 1.000 filas. Si la dirección ya está en la biblioteca, se abre la que hay en lugar de duplicarla (RF-15).

### A2. Actualizar y borrar (RF-6, RF-7, RF-27)
- «Actualizar» repite A1 sobre el mismo servicio y, en una transacción, sustituye sus capas leídas y compara los nombres: las capas de proyectos que ya no están se avisan con el nombre del proyecto; siguen en el proyecto como «no disponible» (RF-27).
- «Borrar» cuenta los proyectos con capas de ese servicio y los nombra en la confirmación; las capas de esos proyectos desaparecen con el servicio (cascada).

### A3. Teselas de una capa (RF-29, RF-17)
Para una capa visible en modo `TILES` el mapa recibe una fuente de teselas con esta plantilla, que MapLibre rellena con el cuadro envolvente en EPSG:3857 de cada tesela de 256 px:
`<dirección con sus parámetros>&SERVICE=WMS&VERSION=<v>&REQUEST=GetMap&LAYERS=<nombre>&STYLES=&FORMAT=<f>&TRANSPARENT=TRUE&WIDTH=256&HEIGHT=256&<CRS|SRS>=<código>&BBOX={bbox-epsg-3857}`
con `<v>` la versión que declara el servicio, `CRS` en 1.3.0 y `SRS` en 1.1.1, y `<código>` el que anuncia la capa (A1); el eje de Web Mercator es siempre este, norte, así que el cuadro no se invierte. El nombre de la capa se codifica en la URL (hay nombres con espacios y barras verticales). El formato es el `preferredFormat` del preset o, en un servicio propio, `image/png`; si el servicio no lo ofrece, el único o el primero que anuncie (la ortofoto de GRAFCAN solo da JPEG y su mapa solo PNG). `opaque` casi nunca viene bien declarado, así que no decide el formato. Las capas de un mismo servicio no se agrupan en una sola petición.

### A4. Una imagen de la zona visible (RF-16, RF-17, RF-29)
Como el Catastro de S009 (`CadastreRequest`): al detenerse el mapa durante 500 ms, una sola imagen `GetMap` con el cuadro envolvente de la zona visible en EPSG:3857 (con 3 decimales) y el lado mayor de 2.048 px como mucho; la llamada anterior se cancela; la imagen llega como una fuente de imagen con las cuatro esquinas de la zona visible. No hay teselas, no se guarda en la caché (RF-32) y la cancelación de una petición no cuenta como fallo de conexión.

### A5. Orden, visibilidad y opacidad (RF-20 a RF-22, RF-33)
`WmsLayerPlan` convierte la lista del proyecto en lo que se dibuja: solo las capas visibles y disponibles, de la última a la primera, cada una **justo debajo de una capa ancla**. La ancla es una capa de fondo invisible que `MapLibreMapController` añade al montar el estilo, antes de las capas de puntos: queda así por encima del mapa base y por debajo del Catastro, las líneas, los dibujos, los puntos y el rover, que se añaden después (RF-22, RF-33). La opacidad es `opacity / 100`. Cambiar el orden, la visibilidad o la opacidad rehace solo las capas WMS; el mapa base y el Catastro no se tocan.

### A6. Consulta de información (RF-34 a RF-39)
1. La pulsación larga da el punto (latitud y longitud), el píxel de la pantalla y la zona visible con su tamaño en píxeles; el mapa pone una marca en el punto.
2. Se consultan las capas **visibles, disponibles y consultables** (`queryable`), de arriba abajo. Si no hay ninguna, «Consultar información de las capas» sale desactivada con el motivo (RF-37).
3. Por cada una, una petición `GetFeatureInfo` con el cuadro envolvente y el tamaño de la zona visible, `QUERY_LAYERS` y `LAYERS` con su nombre, `FEATURE_COUNT=5` y el píxel como `I` y `J` (1.3.0) o `X` e `Y` (1.1.1), en la versión del servicio o en la `infoVersion` de su preset (el Catastro INSPIRE solo devuelve parcelas en 1.1.1). `INFO_FORMAT` es el primero que anuncie el servicio de esta lista: `text/plain`, `application/vnd.ogc.gml`, `text/xml`, `text/html`. Hasta 5 peticiones a la vez, cada una con 20 s (RF-39).
4. `FeatureInfoText` convierte la respuesta en texto: el texto plano, sin la cabecera `GetFeatureInfo results:` ni las direcciones internas de los nombres de tipo; el GML y el XML, como pares «nombre: valor» de sus elementos hoja (sin geometrías); el HTML, sin etiquetas y con las tablas por filas. Una `ServiceException` en el cuerpo (algunos servicios la devuelven con HTTP 200) es un error de ese servicio. Es «sin datos en este punto» (RF-38) un texto vacío, un GML sin elementos, las frases «no features were found» o «Search returned no results» y las entidades sin ningún atributo con valor (el ICV y GRAFCAN las devuelven vacías); no se puede saber más sin conocer cada servicio.
5. Cada capa muestra su respuesta, «sin datos» o «no responde» con su motivo, sin esperar a las demás (RF-39).

### A7. Servicios en la copia y el paquete (RF-25)
- Copia completa: las tres tablas se copian enteras y, al restaurar, la biblioteca queda como la de la copia, igual que los perfiles NTRIP (S013/RF-8); «Deshacer la restauración» la devuelve.
- Paquete: `wms_services`, `wms_layers` y `project_wms_layers` entran con filtros por proyecto. Al importar, por cada servicio del paquete se busca el del móvil con la misma dirección normalizada; si existe, las capas del proyecto se reenlazan a él y no se toca su biblioteca; si no, se añade tal cual con un id nuevo. Si el proyecto ya existe y se sustituye, sus capas también (S013/RF-27).

### A8. Coordenadas del punto (RF-40, RF-41)
La latitud y la longitud del mapa son las del datum del proyecto, como en el rover (S009/RF-16). Este y Norte salen de `ProjectPositionCalculator` (S006/S008) en el SC del proyecto y en sus unidades; la latitud y la longitud con los formateadores de ángulos del proyecto. Fuera del ámbito del SC solo salen la latitud y la longitud, con el motivo (S008/RF-5, RF-6). «Copiar» deja en el portapapeles las mismas líneas que se ven.

## Contratos
- `WmsHttp.get(url: String, maxBytes: Long): WmsResponse` (cuerpo como flujo, tipo de contenido y código); lanza `IOException` por red y un tipo propio por tiempo agotado y por tamaño.
- `WmsCapabilitiesReader.read(input: InputStream): WmsCapabilities` o `WmsCapabilitiesException(reason)`, con `reason` en `NOT_CAPABILITIES`, `SERVICE_EXCEPTION`, `MALFORMED` y `NO_WEB_MERCATOR`.
- `WmsServiceAdder.add(address)` y `.addPreset(key)` devuelven `AddResult` (`Added`, `AlreadyThere(id)` o `Failed(reason)`); `WmsLibraryRepository.refresh(id)` devuelve las capas de proyectos que han desaparecido.
- `ProjectLayersRepository`: `layers(projectId): Flow<List<ProjectLayerRow>>` (con su estado `available` y el nombre del servicio), `add(projectId, serviceId, layerName)` (rechaza la undécima y la repetida), `setVisible`, `setOpacity` (de 5 en 5), `move(id, newPosition)` y `remove`.
- `MapController`: `showWmsLayers(plan: WmsLayerPlan)`, `val longPresses: Flow<MapPress>` (con el punto, el píxel, la zona visible y su tamaño) y `showPressMarker(position: GeoPoint?)`.
- Estados de la hoja de servicios: `Idle`, `Loading`, `Added`, `Error(motivo)`, con los textos de cada motivo en es y en.

Textos nuevos (es y en): la hoja «Capas» con «Añadir capa» y «Añadir servicio»; la biblioteca y los presets; los motivos de A1; el aviso de capas desaparecidas y el de borrado; «no disponible»; los pasos de opacidad; «subir» y «bajar»; el menú del mapa; «sin datos en este punto», «no responde» y «ninguna capa visible se puede consultar»; «Ver coordenadas», «Copiar» y el motivo de fuera de ámbito.

Accesibilidad (RNF-T8): zonas de 56 dp; reordenar con botones «subir» y «bajar» además de arrastrar; la opacidad con su valor en texto y pasos de 5 %; TalkBack lee el estado de cada capa (visible, opacidad, no disponible).

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Un cliente HTTP propio para el GetCapabilities y la consulta, sin el interceptor del mapa | Un servicio que falla al añadirlo no debe encender el aviso «Sin conexión» del mapa (S009/RF-15) | Reutilizar `@MapHttp` |
| Las teselas las pide MapLibre con `{bbox-epsg-3857}` | ADR-9; caché de S009 gratis (RF-32) | Descargar y pintar las teselas a mano |
| Una capa ancla invisible para el orden | El Catastro y el resto se añaden por debajo de los puntos y las capas WMS tienen que quedar bajo ellos y sobre el mapa base | Reordenar todas las capas del estilo en cada cambio |
| El árbol de capas en una tabla por orden y profundidad | Hasta 20 MB de documento: filas planas por lotes, y el árbol se rehace al verlo | Un JSON por servicio (se lee entero para mostrar una rama) |
| Presets en JSON en recursos de `core:formats` | Los lee el mismo script de `tools` que los verifica | Una lista en Kotlin |
| La restauración completa sustituye la biblioteca | Como los perfiles NTRIP y el resto de datos (S013/RF-8); el deshacer la devuelve | Fusionar por dirección también en la restauración completa (más caso límite, mismo resultado visible) |
| Consulta en texto, formato por orden de preferencia | Solo texto en la spec; GML y HTML se reducen a texto | Mostrar el HTML en una vista web (otra superficie de ataque, S036 no la pide) |
| `FEATURE_COUNT=5` | Una parcela suele dar una; el límite evita respuestas enormes | Sin límite |

## Decisiones que pido que confirmes
- **D1:** la restauración **completa** deja la biblioteca como la de la copia (los servicios del móvil que no estén en ella se pierden, y vuelven con «Deshacer la restauración»). La spec (RF-25) habla de añadir los que falten: eso es lo que hace el **paquete**. Si prefieres que la restauración completa también fusione, es más trabajo (reenlazar ids) y lo cambiaría.
- **D2:** una dirección que ya está en la biblioteca no se duplica: se abre la que hay y se avisa.
- **D3:** las capas sin nombre o sin 3857 se muestran en el árbol pero no se pueden añadir.
- **D4:** con más de 10 capas, «Añadir capa» queda desactivado con el motivo, no se quita la más antigua.
- **D5:** `EPSG:3785`, `EPSG:102113`, `EPSG:102100` y `EPSG:900913` valen como EPSG:3857 (RF-9): son Web Mercator con otro código y sin ellos la ortofoto de Galicia no se podría añadir. Lo comprobé con un `GetMap` real de Galicia, que acepta 3785 y 3857.
- **D6:** País Vasco y Galicia entran solo con su ortofoto, porque su cartografía base no existe como WMS utilizable.
- **D7:** los 36 vuelos de ortofoto del ICV son 36 presets, agrupados por año en la lista; los GetCapabilities grabados en `fixtures` son los de 4 de ellos (2025, 2024, 2017 y 1945) y `wms_presets.py` verifica los demás en vivo.
- **D8:** GRAFCAN va en «una imagen» y su licencia («prohibido el uso comercial y la descarga masiva») se muestra al añadirlo; la app no lo bloquea.

## Estrategia de tests
Los nombres de clase y de test de `data` y `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:model` y `core:formats`:**
  - `WmsAddressTest` (HTTPS por defecto, HTTP privado y público, parámetros, mayúsculas).
  - `WmsCapabilitiesReaderTest` con los ficheros de `fixtures/formatos/wms/`: 1.1.1 y 1.3.0, capas anidadas hasta 10 niveles, `DOCTYPE` con subconjunto interno como el del Catastro, ISO-8859-1, un prefijo sin declarar como el del 1.1.1 de Andalucía, herencia de CRS, alias de 3857 (también 3785 y 102113), sin 3857, sin `Name`, nombres con espacios y barras verticales, nombres repetidos entre grupo e hijo, excepción de servicio, página HTML con HTTP 200, XML dañado y vacío, atribución ausente; y la lectura de los GetCapabilities grabados de cada preset.
  - `WmsRequestsTest` (cuadro envolvente de A3 y A4 frente a pyproj en EPSG:3857 a ≤ 1 cm, `Locale.ROOT`, parámetros que ya trae la dirección).
  - `FeatureInfoTextTest` con respuestas grabadas (texto, GML, HTML, vacía).
  - `WmsPresetsTest` (JSON válido, claves únicas, HTTPS, atribución y licencia en todos).
  - Un test de rendimiento: un GetCapabilities sintético de 5 MB se lee en menos de 2 s.
- **`tools`:** `generar_wms.py` lee con `xml.etree` los mismos ficheros y escribe `esperado-capas.csv` (nombre, título, profundidad, 3857, transparente, consultable) y las cajas EPSG:3857 de pyproj; su test comprueba que lo guardado es lo que se genera. `wms_presets.py` se prueba con respuestas grabadas, no en vivo.
- **Robolectric `data`:** `WmsLibraryRepoTest` (guardar, listar, renombrar, actualizar con capas desaparecidas, borrar con proyectos), `ProjectLayersRepoTest` (límite de 10, repetidas, orden, opacidad, cascada, disponibilidad), `WmsServiceAdderTest` (con un `WmsHttp` que devuelve los ficheros grabados: 1.3.0, caída a 1.1.1, tiempo agotado, HTTP público), `WmsBackupTest` (la copia completa y el paquete llevan las tablas; fusión por dirección; deshacer) y `WmsMigrationTest` (31 → 32).
- **Robolectric `app`:** `WmsLayerPlanTest` (orden, ancla, ocultas y no disponibles fuera, opacidad), `WmsLibraryViewModelTest`, `WmsLibrarySheetTest`, `ProjectLayersSectionTest`, `MapMenuViewModelTest` (consultas en paralelo, sin datos, no responde, desactivada), `MapMenuSheetTest` y `MapCoordinatesTest` (Este y Norte frente a pyproj a ≤ 1 mm, fuera de ámbito).
- **Contra otras herramientas (T-cierre):** abrir con el Python de QGIS los GetCapabilities grabados (capas y cuadros envolventes) y compararlos con lo que lee `WmsCapabilitiesReader`; y una consulta real de cada preset con una capa consultable.
- **Manual (demo en el emulador):** añadir un preset y un servicio por dirección, ver sus capas, añadirlas a un proyecto, reordenarlas, ocultarlas, cambiar la opacidad, ver la atribución, mantener pulsado el mapa, consultar y ver coordenadas, restaurar una copia con capas y abrir sin red.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-5 | A1, `WmsRequests` | `WmsRequestsTest`, `WmsServiceAdderTest` |
| RF-2, RF-3 | A1, lector | `WmsCapabilitiesReaderTest`, `WmsLibrarySheetTest` |
| RF-4 | A1, repositorio | `WmsLibraryRepoTest` |
| RF-6, RF-7 | A2 | `WmsLibraryRepoTest`, `WmsLibraryViewModelTest` |
| RF-8, RF-9 | A1 | `WmsServiceAdderTest`, `WmsCapabilitiesReaderTest` |
| RF-10 | A1, `WmsAddress` | `WmsAddressTest`, `WmsServiceAdderTest` |
| RF-11 | A1, lector | `WmsCapabilitiesReaderTest`, test de rendimiento |
| RF-12 | A1 | `WmsCapabilitiesReaderTest`, `WmsLibrarySheetTest` |
| RF-13, RF-16 | presets | `WmsPresetsTest`, `WmsCapabilitiesReaderTest` |
| RF-14, RF-15 | presets, A1 | `WmsServiceAdderTest`, `WmsLibraryViewModelTest` |
| RF-17 | A3, A4 | `WmsLayerPlanTest`, `WmsLibraryViewModelTest` |
| RF-18 a RF-23 | capas del proyecto, A5 | `ProjectLayersRepoTest`, `ProjectLayersSectionTest` |
| RF-24 | modelo de datos | `ProjectLayersRepoTest`, `WmsMigrationTest` |
| RF-25 | A7 | `WmsBackupTest` |
| RF-26, RF-27, RF-28 | repositorio | `ProjectLayersRepoTest`, `ProjectLayersSectionTest` |
| RF-29 a RF-33 | A3 a A5 | `WmsRequestsTest`, `WmsLayerPlanTest`, prueba en el emulador |
| RF-34, RF-35 | A6, mapa | `MapMenuViewModelTest`, `MapMenuSheetTest` |
| RF-36 a RF-39 | A6 | `FeatureInfoTextTest`, `MapMenuViewModelTest`, `MapMenuSheetTest` |
| RF-40, RF-41 | A8 | `MapCoordinatesTest`, `MapMenuSheetTest` |
