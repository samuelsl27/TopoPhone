# Plan técnico — Spec 031

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/031-kml-kmz/spec.md`, `specs/030-shapefile/plan.md` y `specs/048-geopackage/plan.md` (el importador, el recuento por zona, el dibujo y la pantalla que esta spec reutiliza), `specs/042-superficies-landxml/plan.md` (el lector XML por streaming), `specs/055-exportar-shapefile/plan.md` (el constructor de la exportación, guardar y compartir) y `specs/017b-fotos-de-puntos/plan.md` (dónde están las fotos). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- Leer KML: el `XmlStreamReader` de `core:formats/xml` (S042), que ya lee XML por eventos, ignora los espacios de nombres (sirve para `gx:` y `kml:`) y aguanta 100 MB. Leer un KMZ: `java.util.zip.ZipFile`. Escribir KML y KMZ: código propio sobre `java.io` y `java.util.zip`. Alternativas descartadas: una librería de KML (`JAK`, `kml4j` o `libkml` por JNI: licencias y tamaño que RNF-T14 no admite sin revisar) y `kxml2` o `XmlPullParser` (dependencia nueva o `android.*` en un módulo `core`).
- Las referencias las generan `tools/formatos/generar_kml.py` y `generar_kml_exportado.py` con `xml`, `zipfile` y `pyproj`, que `tools` ya usa. Los de «hechos con QGIS» salen del Python de QGIS, como en S030 (`qgis_kml_ejemplos.py`), y se guardan con su procedencia anotada.

## Idea: el KML entra por la misma puerta que el GeoPackage y el Shapefile
La importación de S048 ya hace casi todo lo que pide la primera mitad de esta spec: capas marcables, recuento por zona, polígonos como líneas, cotas, nombre, atributos, tandas, cancelar sin dejar nada, resumen, mapa, ficha, replanteo, borrado y copias, y desde S030 trabaja con una interfaz `FeatureSession` que no sabe de qué formato viene. Por eso el plan **no copia** el importador ni la pantalla: escribe una `KmlSession` que cumple `FeatureSession` y deja que `GpkgImportViewModel` y su pantalla la sirvan con un `ImportFormat.KML`. Lo propio de KML (XML, KMZ, carpetas, estilos, altitudes, HTML) es lo que se escribe nuevo.

Una diferencia con el Shapefile: **un XML no tiene acceso aleatorio**, y el recuento por zona y la ficha piden objetos por clave. Por eso `KmlReader` lee el fichero **una sola vez por flujo** y vuelca lo leído a un almacén binario temporal con un registro por objeto (D2); de ahí sale todo lo demás.

La segunda mitad (exportar) reutiliza lo de S055: los valores de las columnas (`GpkgPointAttributes` y `AttributeColumns`), la selección con líneas completas, el fichero temporal y guardar o compartir.

## Estructura de módulos
- `core:model`
  - `CadOrigin.KML` (RF-16, RF-19); `GpkgIgnoredReason` gana `NETWORK_LINK`, `OVERLAY`, `MODEL`, `TRACK` y `TOUR` (RF-9); `GpkgImportSummary` gana `ignoredElements`, los elementos que no se importan contados por fichero (RF-9, D6).
- `core:formats` (`kml/`), sin `android.*`
  - `KmlFileKind`: reconoce por contenido un KML (`<kml` con espacio de nombres `2.x`, tras BOM y declaración XML) y un KMZ (`PK` con un `.kml` en la raíz, `doc.kml` primero y si no el primero por orden alfabético) (RF-2, RF-3).
  - `KmlColors`: de `aabbggrr` a ARGB opaco (la transparencia se ignora) y de ARGB a `aabbggrr` para escribir (RF-12, RF-23).
  - `KmlStyleTable`: los `Style` y `StyleMap` del documento por `id`, con `styleUrl` `#id` y el estilo normal de un `StyleMap`; el color de `IconStyle` para puntos y de `LineStyle` para líneas y contornos (RF-12).
  - `KmlCoordinates`: el texto de `<coordinates>` (`lon,lat[,alt]` separadas por espacios o saltos de línea) a vértices, con la regla «una geometría lleva Z solo si todos sus vértices traen altitud» (RF-7, RF-13, RF-14).
  - `KmlHtml`: la descripción sin etiquetas (`<br>` y `<p>` a saltos de línea, entidades resueltas, `CDATA`) (RF-11).
  - `KmlParser`: recorre el XML y entrega, por marca, su ruta de carpetas, nombre, descripción, datos extendidos (`Data` y `SimpleData`), estilo, modo de altitud y geometrías (`Point`, `LineString`, `LinearRing`, `Polygon` con huecos y `MultiGeometry` anidada), y cuenta los elementos que no se importan (RF-5, RF-8, RF-9, RF-10, RF-11).
  - `KmlGeometryEncoder`: de las partes de una marca al blob de geometría de GeoPackage, con envolvente, para que el importador y el recuento lo lean con `GpkgGeometryReader` como los de S048 (D1).
- `core:formats` (`kml/export/`)
  - `KmlNumber`: el texto de un número sin notación científica y sin la configuración regional (RNF-T9).
  - `KmlWriter`: el XML de la exportación a un `Writer`, con las entidades escapadas, `CDATA` para la descripción con fotos y el estilo de cada código (RF-21 a RF-23, RF-29).
  - `KmlExportBuilder`: de los puntos, líneas y polígonos del proyecto, con las opciones, a las carpetas, marcas, geometrías, estilos y datos extendidos, y al resumen; reutiliza `GpkgPointAttributes` y `AttributeColumns` de S053/S024 (RF-20 a RF-27, RF-32).
  - `KmzWriter`: de la exportación y las fotos al KMZ, con `doc.kml` la primera entrada y las fotos en `files/` (RF-28, RF-30).
- `data`
  - `FeatureSession` (S030) gana `hasElevations: Boolean?` (RF-13) y `GpkgFeature` gana `color: Int?` ARGB propio del objeto (RF-12). Sin cambios de comportamiento en S048 y S030: los valores por defecto son `null` y `null`.
  - `kml/KmlReader`: de la fuente elegida (un KML o un KMZ) a una carpeta temporal de la caché, con los límites de 100 MB y 500 MB, el nombre de cada entrada normalizado (zip-slip) y la limpieza, y de ahí a una `KmlSession` (RF-1 a RF-4, RF-17).
  - `kml/KmlStore` y `kml/KmlSession : FeatureSession`: el almacén binario temporal, las capas, el sistema (siempre el geográfico del datum de los cálculos del proyecto, con el aviso de WGS84), los objetos con acceso por clave, las cabeceras y las geometrías (RF-5 a RF-9, RF-11, RF-13, RF-14).
  - `GeoPackageImporter` y `GpkgFeatureConverter`: el color del objeto pasa al `CadEntity` (RF-12), sin cambios para los demás formatos.
  - `KmlExportPreferences` en DataStore («kml-export»): formato, «Incluir altura» e «Incluir fotos», para todos los proyectos (RF-32).
  - `PhotoRepository.photosForExport(pointIds)` (la misma consulta de S056: la implementa la primera de las dos specs que se haga) y la lectura de los ficheros con `PhotoStore.photoFile` (RF-28, RF-30).
- `app`
  - `GpkgImportViewModel` y `GpkgImportScreen` sirven también a `ImportFormat.KML`: sus problemas de fichero, la pregunta de las altitudes con «No» por defecto y solo si hay altitudes absolutas, la capa sin selector de sistema ni de codificación, el aviso de datum y el de elementos ignorados (RF-1 a RF-6, RF-13, RF-18).
  - Entradas «Importar KML/KMZ» en los dos menús, con su ruta de navegación; «Origen: KML» en `DrawingsSheet` y atributos en la ficha (RF-1, RF-11, RF-16).
  - `KmlExportViewModel` y `KmlExportScreen`: puntos o selección con sus líneas, formato, altura, fotos, el aviso de 1 GB, guardar y compartir, errores y resumen (RF-20, RF-26 a RF-32).
  - Entradas «Exportar KML/KMZ» en el menú del proyecto y en el de su lista de objetos, y «KML» en la barra de una selección, con su navegación (RF-20).
- `tools/formatos`: `generar_kml.py`, `generar_kml_exportado.py`, `qgis_kml_ejemplos.py` y `fixtures/formatos/kml/` con lo esperado de cada uno con pyproj (constitución nº 2).

## Modelo de datos
**Ningún cambio de Room** (D3): `cad_drawings.origin` es un `TEXT` y guarda `KML` sin migración, como `SHAPEFILE`. Un KML es **un dibujo** con una capa de diseño por capa marcada, como el de S048, con `cad_layers.source_table` igual al nombre de la capa y `name_field` el campo de nombre elegido, los atributos en `cad_chunk_attributes` y el color de cada objeto en `CadEntity.color` (que S040 ya guarda en el trozo).

Cómo se ve un KML con los tipos de S048:
```
GpkgLayerInfo   table = nombre de la capa (D4)        geometryColumn = "geometry"
                geometryType = POINT | LINESTRING      (los polígonos van en la capa de líneas, RF-8)
                featureCount = objetos con geometría    srsId = 0 (no se usa)      hasSpatialIndex = false
                columns = name, description, y los nombres de sus datos extendidos (TEXT)
GpkgFeature     fid = número del objeto en la capa (desde 1)    geometry = blob de geometría    values = texto
                color = el de su estilo (ARGB) o null
GpkgCrs         Supported(geográficas del datum de los cálculos, wgs84 = true)   siempre, sin selector
```
- **Una marca puede dar objetos en las dos capas** de su carpeta: una `MultiGeometry` con un punto y un polígono da un objeto en la capa de puntos y otro en la de líneas, con los mismos atributos.
- **Z.** El blob lleva Z solo en las marcas con `altitudeMode` `absolute` y con altitud en todos sus vértices; las pegadas al suelo, relativas (también `relativeToSeaFloor` y `clampToSeaFloor`) o sin altitud no la llevan, así que con «No» o «Sí» no se guarda cota de ellas (RF-13, RF-14).
- **Atributos.** `name` siempre, `description` sin HTML y una columna por nombre de dato extendido de la capa, por orden de aparición; un nombre repetido o igual a `name` o `description` lleva un número al final. `name` se propone como campo de nombre con la regla de S048/RF-21 y un objeto sin nombre toma su número en la capa (RF-10).

El almacén temporal (`KmlStore`), en `cacheDir/kml-<uuid>/`:
```
features.bin   un registro por objeto: capa, fid, color (4 bytes o ninguno), envolvente (4 Double), blob, pares (columna, texto)
```
En memoria solo quedan por capa el recuento, la lista de columnas y el `LongArray` de posiciones de sus registros: un KML de 100 MB con 700.000 marcas ocupa unos 6 MB.

El KML exportado, con `<p>` el nombre del proyecto:
```
<p>.kml            <kml><Document><name/>
                     <Style id="c1">…       uno por código con color
                     <Folder><name>código</name><Placemark>…   puntos, líneas y polígonos del código
<p>.kmz            doc.kml  +  files/<punto>_<n>.jpg   (con «Incluir fotos»)
```
- **Marca de un punto.** `<name>`, `<description>` (con las fotos si las hay), `<styleUrl>` del código, `<ExtendedData>` con un `<Data name="…"><value>…</value></Data>` por columna (las de «Todas las columnas», la del código y una por atributo de código) y `<Point>` con `<coordinates>lon,lat[,alt]</coordinates>`. Lo que el punto no tiene va vacío (`<value/>`).
- **Línea y polígono.** `<LineString>` o `<Polygon><outerBoundaryIs><LinearRing>` con el contorno en sentido antihorario (en el plano longitud/latitud, invirtiéndolo si hace falta y conservando el primer vértice) y su primer vértice repetido al final; datos extendidos: nombre, código, número de vértices y atributos del código.
- **Estilo de un código.** `IconStyle` y `LineStyle` con el color del código y `PolyStyle` con ese color y alfa `80`; sin código, sin estilo.
- **Altura (RF-26, RF-27).** Con «Incluir altura», una marca con altura en todos sus vértices lleva `<altitudeMode>absolute</altitudeMode>` y `lon,lat,h` con la altura del proyecto en metros (4 decimales); sin ella en alguno, o sin la opción, va sin altitud y sin `altitudeMode` (pegada al suelo) y se cuenta.
- **Números.** Longitud y latitud con 9 decimales (`KmlNumber`); los valores de los datos extendidos, los de S055/RF-6: sin redondear, en metros y grados, fechas ISO 8601 con Z.

## Algoritmos
### A1. Elegir y abrir el fichero (RF-1 a RF-4, RF-17)
1. El selector devuelve un `Uri`; el `ViewModel` lo envuelve en `ImportSource` y llama a `KmlReader.open`.
2. `KmlFileKind` mira los primeros bytes: **KMZ** si empieza por `PK\x03\x04` o `PK\x05\x06`, y **KML** si, tras un BOM y espacios, hay `<?xml` o `<kml` (RF-2). Un ZIP sin `.kml` en la raíz, o un XML cuya raíz no es `kml`, es «no es un KML» (RF-3).
3. El fichero se copia a `cacheDir/kml-<uuid>/` por flujo contando los bytes **leídos**: pasados 100 MB se corta con el error de RF-4 y se borra la carpeta. De un KMZ se abre `ZipFile` (un KMZ cortado es un error, no un ZIP más corto), se elige el KML principal (`doc.kml` o el primero de la raíz por orden alfabético) y solo se descomprime ese; el límite de 500 MB mira la suma de los tamaños declarados y los bytes que se copian. Un KMZ con más de un KML usa el principal y el resto se ignora sin aviso (RF-2).
4. La carpeta temporal se borra al cerrar la sesión, al cancelar, al fallar y al arrancar la app (`cleanUpStale`, como S048 y S030).

### A2. Leer el KML por flujo (RF-5 a RF-12)
1. `KmlParser` recorre el XML una vez con `XmlStreamReader`. Mantiene la pila de carpetas (`Folder` y `Document`, sin el `Document` raíz) y construye la ruta de la capa con « / ». Una marca fuera de toda carpeta va a las capas con el nombre del fichero sin extensión (RF-5).
2. Los `Style` y `StyleMap` pueden ir antes o después de la marca que los usa, y un KML grande los pone al principio: se leen en la misma pasada y el color se resuelve **al terminar la marca** con la tabla de estilos que ya haya (un estilo declarado después de su uso no se aplica; está en «Decisiones que pido que confirmes»).
3. De cada marca se leen el nombre, la descripción (pasada por `KmlHtml`), los datos extendidos, el `styleUrl` o el `Style` en línea, el `altitudeMode` y las geometrías. Una geometría sin vértices, o con una tupla de coordenadas ilegible, es «geometría vacía» (S048/RF-18); `NetworkLink`, `GroundOverlay`, `ScreenOverlay`, `PhotoOverlay`, `Model`, `gx:Track`, `gx:MultiTrack` y `gx:Tour` no dan objeto y se suman a `ignoredElements` con su motivo (RF-9).
4. Un polígono da su contorno exterior y uno por cada hueco; los convierte el importador en líneas cerradas con la marca de contorno, como S048/RF-17 (RF-8). Un `LinearRing` suelto es una línea cerrada.
5. Cada objeto se escribe en `features.bin` con su blob de GeoPackage (`KmlGeometryEncoder`) y su envolvente.

### A3. Sistema, altitudes, zona y recuento (RF-7, RF-13, RF-14, RF-15, RF-18)
- `KmlSession.crs(layer)` da siempre `GpkgCrs.Supported` con las geográficas del datum de los cálculos del proyecto (ETRS89 o REGCAN95; un proyecto ED50 usa ETRS89, como `ShpPrjDefinitions.geographicOf` en S055) y `wgs84 = true`: el convertidor de S048 reproyecta al sistema del proyecto y la pantalla muestra el aviso de que WGS84 y el datum del proyecto se han tomado como iguales (RF-18). La sesión recibe el datum al abrirse.
- `hasElevations` es `true` si alguna marca de las capas lleva Z (altitud absoluta): la pantalla pregunta «¿Usar las altitudes del fichero?» solo entonces, con «No» por defecto y el aviso de EGM96 (RF-13). El valor `null` (S048 y S030) mantiene su comportamiento actual.
- La zona, la confirmación a más de 100 km, el punto central, el prefiltro por envolvente, el recuento y el límite de 500.000 objetos son los de S048, sin tocarlos (RF-15): `forEachHead` lee la envolvente de cada registro del almacén y `forEachGeometry` el blob por clave.

### A4. Pantalla, mapa, resumen y dibujo (RF-6, RF-16 a RF-19)
- La pantalla es la de S048: lista de capas con tipo, objetos y extensión, todas marcadas (RF-6); sin selector de sistema ni de codificación; el aviso de datum, los elementos ignorados (RF-9) y la pregunta de las altitudes. El resumen cuenta por capa los importados, los de fuera de la zona y los ignorados con su motivo, y añade los elementos ignorados del fichero.
- El color de cada objeto va al `CadEntity`: el del estilo o, si es `null`, el de su capa, como en un DXF (S040/RF-24), así que el mapa no cambia (RF-12, RF-16).
- El dibujo, el borrado, las copias, los paquetes y el log son los de S048 (RF-19) con origen `KML`. El nombre del dibujo es el del fichero.

### A5. Preparar la exportación (RF-20 a RF-27)
1. Los puntos son los de `pointsForExport` o los de la selección; las líneas, todas las del proyecto o, con selección, las de `ShpExportBuilder.linesOf` (todos sus vértices entre los elegidos) (RF-20).
2. Cada punto con posición (longitud y latitud del datum de los cálculos: `c.ground`) es una marca; los demás se cuentan (RF-24, RF-25). Una línea con menos de 2 vértices con posición, o un polígono con menos de 3, no se escribe, como en S055.
3. Las marcas van a la carpeta de su código y las sin código, a la del proyecto (RF-23). Los datos extendidos salen de `GpkgPointAttributes` y `AttributeColumns`, como S055 (RF-21, RF-22).
4. La altura y el modo de la marca siguen la regla de «Modelo de datos» (RF-26, RF-27).

### A6. Fotos en el KMZ (RF-28 a RF-30)
1. Con KMZ e «Incluir fotos», `photosForExport` da las fotos de los puntos escritos. Los nombres son los de S056/RF-5 (`<punto>_<n>.jpg`, saneado y sin repetir), en `files/`; el código de nombres es el mismo de `PhotoArchiveNames` (S056) y, si S031 se hace antes, se escribe en `core:formats/photo/` con sus tests y S056 lo reutiliza.
2. La descripción de la marca lleva el texto del punto (con `&`, `<` y `>` escapados) y, tras un `<br/>`, un `<img src="files/<nombre>" width="400"/>` por foto, todo en `CDATA`.
3. `KmzWriter` escribe `doc.kml` primero y luego las fotos desde sus ficheros, byte a byte, con `NO_COMPRESSION`. Una foto que no se puede leer se omite antes de escribir su primer byte, no lleva `<img>` y se cuenta (RF-30). El tamaño previsto es el KML más la suma de `sizeBytes`; con más de 1 GB se pide confirmar antes de escribir, y sin espacio libre (`usableSpace` más un 5 %) no se escribe.

### A7. Escribir y guardar (RF-31, RF-32)
`KmlExportViewModel` escribe en un fichero temporal de la caché y solo cuando está entero lo copia al destino (`CreateDocument`) o lo comparte con FileProvider, como S055; el nombre propuesto es `ExportFileName.of(proyecto, "kml", ahora, zona, "kml" | "kmz")`. Sin puntos no se crea nada. El formato, «Incluir altura» e «Incluir fotos» se guardan en `KmlExportPreferences` al cambiarlos.

## Contratos
- `KmlReader.open(source: ImportSource, datum: GeodeticDatum): KmlOpenResult` (`Opened(session)` o `Failed(reason)`), con `KmlOpenFailure { EMPTY, NOT_KML, NO_PLACEMARKS, DAMAGED, TOO_LARGE, NO_SPACE, READ_ERROR }`.
- `KmlSession : FeatureSession` con `ignoredElements: Map<GpkgIgnoredReason, Int>` y `hasElevations: Boolean`.
- `KmlExportBuilder.build(points, project, options, baseProjection, lines, allPoints, photos): KmlExport` y `KmlWriter.write(export, out: Writer)`; `KmzWriter.write(export, photoSources, out: OutputStream): KmzResult`.
- `KmlExportOptions(format: KmlFormat { KML, KMZ }, includeHeight: Boolean, includePhotos: Boolean)`.
- `GpkgImportViewModel.startKml(projectId, source)`; navegación `SurveyPage.ImportKml(projectId, fileUri)` y `SurveyPage.ExportKml(projectId, selectionOnly)` (ADR-19).

Textos nuevos (es y en): Importar KML/KMZ; los problemas (no es un KML o KMZ, sin marcas, dañado, más de 100 MB o 500 MB); «¿Usar las altitudes del fichero?» con el aviso de EGM96; el aviso de datum («WGS84 y el datum del proyecto se han tomado como iguales; pueden diferir de 0,5 a 1 m»); los motivos de ignorado (vínculo de red, imagen, modelo, recorrido); «Origen: KML»; Exportar KML/KMZ, KML o KMZ, «Incluir altura», «Incluir fotos», el aviso de 1 GB y el resumen con sus avisos; los errores.

Accesibilidad (RNF-T8): zonas de 56 dp; los avisos y el resumen en texto y no solo en color; TalkBack lee el resumen al terminar.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| **D1** Una `KmlSession` que cumple `FeatureSession` y da el blob de geometría de GeoPackage | El importador, el recuento, el convertidor, el mapa, la ficha y el replanteo funcionan sin cambios; solo se escribe lo propio del formato | Copiar importador y pantalla (más de 1.000 líneas duplicadas) |
| **D2** Un almacén binario temporal con un registro por objeto, escrito en una sola pasada | Un XML no tiene acceso aleatorio y la zona y la ficha piden objetos por clave; releerlo en cada cambio de radio costaría decenas de segundos | Releer el KML en cada cambio de radio; cargar el árbol entero (100 MB de XML) en memoria |
| **D3** Sin cambios de Room | `origin` es un `TEXT`; el color por objeto ya se guarda en el trozo (S040/RF-24) | Una columna o tabla nueva sin necesidad |
| **D4** `table` de la capa = la ruta de la carpeta, y si una carpeta tiene puntos y líneas, la ruta lleva « (puntos)» o « (líneas)» | La clave de capa de S048 tiene que ser única y la lista distingue el tipo; con una sola capa por carpeta (lo normal) el nombre es la ruta tal cual, como pide RF-5 | La ruta repetida en las dos capas (choca la clave) |
| **D5** Las altitudes solo existen en el blob de las marcas absolutas | La pregunta de RF-13 y RF-14 se resuelve sin tocar el convertidor: sin Z no hay cota | Guardar la Z de todas y decidir al convertir |
| **D6** Elementos no importables, contados por fichero (`ignoredElements`) | Un `NetworkLink` no tiene capa; los vacíos y los de fuera de la zona sí se cuentan por capa, como S048 | Inventar una capa «ignorados» |
| **D7** Color propio por objeto en `GpkgFeature.color` | S048 coloreaba por capa; un KML lleva estilos por marca y `CadEntity` ya admite color propio | Un color por capa (pierde RF-12) |
| **D8** Los datos extendidos son texto | Un KML no declara tipos (salvo `Schema`, que no se lee) | Inferir números y fechas (se rompe con ceros a la izquierda y códigos) |
| **D9** `KmlWriter` propio, con `<Data>` por columna | La escritura es de unas 150 líneas y no hay dependencia que justificar; `<Data>` lo lee QGIS y Google Earth | `Schema` y `SimpleData` (más corto, pero QGIS y Google Earth no lo muestran igual) |
| **D10** Contorno exterior antihorario y primer vértice repetido | Lo que pide la especificación de KML | Dejar el sentido del proyecto |
| **D11** Las fotos en `CDATA` con `<img width="400">` | Es lo que Google Earth muestra en la burbuja de la marca | Un enlace sin imagen incrustada |
| **D12** El fichero temporal y la copia al destino | Un fallo no deja un KML o KMZ a medias (RNF-T6), como S055 | Escribir directo al flujo del selector |

## Estrategia de tests
Los nombres de clase y de test de `data` y `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:formats` (importar):** `KmlFileKindTest` (KML, KMZ con y sin `doc.kml`, con varios KML, sin KML, dañado), `KmlColorsTest`, `KmlStyleTableTest` (en línea, `styleUrl`, `StyleMap`, estilo declarado después), `KmlCoordinatesTest` (separadores, tuplas rotas, Z parcial), `KmlHtmlTest`, `KmlParserTest` (carpetas anidadas, marcas sin carpeta, `MultiGeometry`, polígono con hueco, datos extendidos, los tres modos de altitud y los elementos ignorados) y `KmlGeometryEncoderTest` (se lee igual con `GpkgGeometryReader`).
- **JVM `core:formats` (exportar):** `KmlNumberTest`, `KmlWriterTest` (comparado byte a byte con la referencia de `tools`), `KmlExportBuilderTest` (puntos, líneas, polígonos antihorarios, carpetas y colores, altura, marcas sin altura, punto sin posición, selección con líneas completas, sin puntos), `KmzWriterTest` (fotos byte a byte, `doc.kml` primero, foto omitida) y `KmlRoundTripTest` (exportar e importar con la `KmlSession`: menos de 0,1 mm).
- **`tools`:** `generar_kml.py` y `generar_kml_exportado.py` con sus tests: KML y KMZ de ejemplo (todos los casos de «Criterios de finalización») y lo esperado de cada uno con pyproj en ETRS89 UTM 30 y REGCAN95 UTM 28, y el KML de referencia de un proyecto de ejemplo.
- **Robolectric `data`:** `KmlReaderTest` (KML, KMZ, límites, espacio, zip-slip, limpieza del temporal), `KmlSessionTest`, `KmlImporterTest` (resumen, zona, polígonos como líneas, altitudes con «Sí» y «No», color por objeto, nombre, atributos, cancelar sin dejar nada, fallo de escritura), `KmlBackupTest` (copia y paquete) y `KmlExportPreferencesTest`; los de S048 y S030 sin cambios.
- **Robolectric `app`:** `KmlImportViewModelTest`, `KmlImportScreenTest` (problemas, capas con ruta, altitudes, avisos), `KmlImportEntriesTest`, `DrawingsSheetKmlTest`, `KmlStakeoutTest`, `KmlExportViewModelTest`, `KmlExportScreenTest` y `KmlExportEntriesTest`.
- **Rendimiento:** exportar 10.000 puntos en menos de 5 s; un KML sintético de 100 MB se lee y se recorta en menos de 30 s y cambiar el radio recuenta en menos de 2 s (JVM y Robolectric como cota; el móvil de referencia en la demo); 100 fotos de 1 MB en un KMZ en menos de 10 s.
- **Contra QGIS (demo):** leer con QGIS (Python de QGIS, controlador KML de OGR) el KML y el KMZ exportados y comparar marcas, coordenadas y fotos con pyproj; importar con la app un KML hecho con QGIS y comparar a menos de 1 mm.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, entradas de menú | `KmlImportEntriesTest`, `KmlImportViewModelTest` |
| RF-2, RF-3 | A1, `KmlFileKind` | `KmlFileKindTest`, `KmlReaderTest` |
| RF-4 | A1 (bytes leídos) | `KmlReaderTest` |
| RF-5, RF-6 | A2, `KmlSession` | `KmlParserTest`, `KmlSessionTest`, `KmlImportScreenTest` |
| RF-7 | A2, A3 | `KmlCoordinatesTest`, `KmlImporterTest` |
| RF-8, RF-9 | A2, `KmlGeometryEncoder` | `KmlParserTest`, `KmlImporterTest` |
| RF-10, RF-11 | A2, `KmlHtml` | `KmlHtmlTest`, `KmlImporterTest`, `KmlImportScreenTest` |
| RF-12 | `KmlStyleTable`, A4 | `KmlStyleTableTest`, `KmlColorsTest`, `KmlImporterTest` |
| RF-13, RF-14 | A3 | `KmlParserTest`, `KmlImporterTest`, `KmlImportViewModelTest` |
| RF-15 | A3 | `KmlSessionTest`, `KmlImportViewModelTest` |
| RF-16 | A4 | `DrawingsSheetKmlTest`, `KmlStakeoutTest` |
| RF-17 | A1, A4 | `KmlImporterTest`, `KmlImportViewModelTest` |
| RF-18, RF-19 | A3, A4 | `KmlImportViewModelTest`, `KmlBackupTest`, `KmlImporterTest` |
| RF-20 | A5 | `KmlExportViewModelTest`, `KmlExportEntriesTest`, `KmlExportBuilderTest` |
| RF-21, RF-22 | A5 | `KmlExportBuilderTest`, `KmlWriterTest` |
| RF-23 | A5 | `KmlExportBuilderTest`, `KmlWriterTest` |
| RF-24, RF-25 | A5 | `KmlExportBuilderTest`, `KmlRoundTripTest`, tests de `tools` |
| RF-26, RF-27 | A5 | `KmlExportBuilderTest`, `KmlWriterTest` |
| RF-28, RF-29, RF-30 | A6 | `KmzWriterTest`, `KmlExportViewModelTest` |
| RF-31, RF-32 | A7 | `KmlExportViewModelTest`, `KmlExportScreenTest`, `KmlExportPreferencesTest` |

## Decisiones que pido que confirmes
- **D4:** cuando una carpeta tiene puntos y líneas, las dos capas se llaman «Ruta (puntos)» y «Ruta (líneas)»; con un solo tipo, solo la ruta. La clave de capa tiene que ser única.
- **D6:** los vínculos de red, imágenes, modelos y recorridos se cuentan en el aviso y el resumen **del fichero**, no por capa, porque no tienen capa.
- **A2.2:** un estilo declarado **después** de la marca que lo usa no se aplica a ella (se resuelve al terminar la marca). Es lo habitual en KML de QGIS y de Google Earth, que ponen los estilos al principio.
