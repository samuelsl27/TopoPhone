# Plan técnico — Spec 048

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/048-geopackage/spec.md`, `specs/040-capa-cad-dxf/plan.md` (el modelo de dibujos, trozos y capas que esta spec reutiliza), `specs/041-replanteo-cad/` y `specs/006-proyectos/plan.md` (ampliación del punto central). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- Leer SQLite: el `android.database.sqlite` del sistema, solo en lectura y solo en `data`. Incluye R-tree, y las funciones `ST_*` que usan los disparadores de escritura de un `.gpkg` no se llaman nunca. Alternativa descartada: una librería de GeoPackage (`geopackage-android` trae GeoTools/proj4j y duplica ADR-14) o un lector de SQLite propio (semanas de trabajo para leer un formato que Android ya lee).
- Los ficheros de ejemplo los escribe `tools/formatos/generar_geopackage.py` con `sqlite3` de la biblioteca estándar y `pyproj`, que `tools` ya usa.

## Estructura de módulos
- `core:model`
  - `CadSourceCrs.Geographic(datum)` junto a `Project` y `Utm` (RF-8); `CadOrigin { DXF, GEOPACKAGE }` (RF-26, el cambio pedido a S040).
  - `ImportArea`: `All` o `Circle(center: CentralPoint, radiusM)`, con el radio entre 100 m y 100 km (RF-13).
  - `GpkgLayerInfo` (tabla, columna de geometría, tipo de geometría, número de objetos, SRS, columnas) y `GpkgImportSummary` por capa (RF-5, RF-25).
  - `CadChunkCodec`: dos marcas más en el byte de marcas (`NAME = 16`, `POLYGON_RING = 32`) y, con `NAME`, el nombre en UTF-8 tras las coordenadas (RF-17, RF-22). Los trozos de S040 se leen igual.
- `core:formats` (`gpkg/`), sin `android.*`
  - `GpkgFileKind`: la cabecera `SQLite format 3\0` y el límite de 100 MB (RF-2 a RF-4). Que sea GeoPackage de verdad (`application_id`, tablas `gpkg_*`) lo comprueba `data` al abrirlo.
  - `GpkgGeometry`: cabecera del blob (`GP`, versión, indicadores, SRS, envolvente) y WKB de punto, línea, polígono y sus múltiples y colecciones, con Z, M o ZM, en códigos ISO y EWKB; da vértices por anillo en el orden del fichero, `EMPTY` o `CURVE` (RF-17, RF-18, RF-19).
  - `GpkgCrsCatalog`: del EPSG de `gpkg_spatial_ref_sys` al `CadSourceCrs` y su unidad, o «no disponible» (RF-8 a RF-10).
- `core:survey`
  - `CadProjector` acepta `Geographic` (latitud y longitud sin UTM inversa) y devuelve el motivo de ignorado por vértice (RF-8, RF-11).
  - `ImportAreaFilter`: el recuadro de la zona en el SC de la capa, la distancia de un vértice al centro y «algún vértice dentro» (RF-13, RF-15).
- `data`
  - Room v19 (S017b pasa a la v20): `cad_drawings.origin`, `cad_layers.source_table` y `name_field`, y la tabla `cad_chunk_attributes` (RF-20, RF-26).
  - `GeoPackageReader`: copia temporal, apertura de solo lectura, capas, columnas, recuentos y filas por zona.
  - `GeoPackageScan`: la extensión, la distancia y el recuento por capa para un radio, en segundo plano (RF-12, RF-13, RF-16).
  - `GeoPackageImporter`: la importación, con el mismo escritor por tandas y la misma limpieza de `IMPORTING` que `CadDrawingImporter` (RF-15 a RF-22, RF-29, RF-30).
  - `CadRepository.attributes(ref)` y las dos tablas nuevas en `DatabaseSnapshot.TABLES` (RF-20, RF-28).
- `app`
  - `GpkgImportViewModel` y `GpkgImportScreen`: capas, SC, zona, nombre, cotas, progreso y resumen (RF-1 a RF-16, RF-19 a RF-25, RF-29, RF-30).
  - Entradas «Importar GeoPackage» en los dos menús (RF-1).
  - Etiquetas de nombre y contornos marcados en el mapa (`MapController.showDesign` sigue igual con etiquetas opcionales en `DesignScene`) y la ficha con atributos (RF-20, RF-22, RF-23).
  - El replanteo de lo importado: S041 sin cambios, solo el nombre (RF-24).
- `tools/formatos`: `generar_geopackage.py`, los `.gpkg` de `fixtures/formatos/gpkg/` y lo esperado de cada uno con pyproj (constitución nº 2).

## Modelo de datos
### Room v19
```
cad_drawings      + origin TEXT NOT NULL DEFAULT 'DXF'            ('DXF' | 'GEOPACKAGE')
cad_layers        + source_table TEXT NULL, name_field TEXT NULL  (la tabla del .gpkg y su campo de nombre)
cad_chunk_attributes  chunk_id INTEGER PK → cad_chunks (CASCADE), data BLOB
```
- `AutoMigration(18, 19)` sin especificación: tablas y columnas con valor por defecto o NULL. Los dibujos de DXF quedan con `origin = 'DXF'`.
- `cad_chunk_attributes` guarda **un bloque por trozo** con los atributos de todos sus elementos en el mismo orden que el trozo: por elemento el número de campos y, por campo, su nombre de columna (índice de una tabla de nombres que va al principio del bloque), un tipo (nulo, entero, real, texto, fecha, booleano o blob omitido) y el valor. Se lee solo al tocar un elemento, así el dibujado del mapa no paga por los atributos. Un blob mayor de 64 kB en una columna no se guarda (se cuenta como atributo omitido en el resumen).
- El nombre de cada elemento va **dentro del trozo** (marca `NAME`), porque las etiquetas del mapa y el replanteo lo necesitan sin leer los atributos.
- Un polígono son varios elementos de la capa de líneas con la marca `POLYGON_RING` (el exterior y cada hueco); todos comparten el nombre y los atributos del objeto (RF-17).
- Coordenadas en el SC del proyecto, sin redondear, y altura sin convertir si se usan las cotas (RF-19, RF-26), como en S040.

## Algoritmos
### A1. Abrir el fichero (RF-1 a RF-7)
1. `GpkgFileKind` mira la cabecera y el tamaño con el `Uri` (más de 100 MB: error antes de seguir).
2. `GeoPackageReader.open` copia el flujo a un fichero de la caché de la app (solo se copia el fichero principal), comprobando antes que cabe en el espacio libre, y lo abre con `OPEN_READONLY`. El fichero temporal se borra al cerrar, al cancelar, al fallar y al arrancar la app (por si murió a mitad).
3. Se comprueba `PRAGMA application_id` (`GPKG` o `GP10`/`GP11`) o la tabla `gpkg_contents`, y `PRAGMA integrity_check` no se usa (es lento): una consulta que falla da «dañado» (RF-3).
4. Las capas salen de `gpkg_contents` con `data_type = 'features'` y `gpkg_geometry_columns`; las demás tablas (`attributes`, `tiles`, extensiones) se cuentan para el aviso de RF-6. Sin ninguna capa: error (RF-3).
5. Un `.gpkg` en modo WAL con cambios sin consolidar se lee consolidado: como solo se copia el fichero principal, lo que está en el `-wal` no se ve; el resumen lo avisa si junto al `.gpkg` había un `-wal` no vacío, cuando el proveedor del selector lo permite (RF-7). Nunca se escribe en el original.

### A2. Sistema de coordenadas de cada capa (RF-8 a RF-10)
- `GpkgCrsCatalog` reconoce: ETRS89 UTM 29 a 31 (EPSG 25829 a 25831), ED50 UTM 29 a 31 (23029 a 23031), REGCAN95 UTM 27 y 28 (4082, 4083) y geográficas ETRS89 (4258) y REGCAN95 (4081): exactamente los `ProjectCrs` de S008, que no ofrece ED50 geográficas (4230) ni los husos 28. La lista se valida contra `pyproj` en `tools`.
- EPSG:4326 (WGS 84) se trata como ETRS89, igual que S006/RF-11 trata las correcciones ITRF/WGS84 sobre ETRS89: la capa se importa y el resumen avisa de que pueden diferir unos 0,9 m. **Decisión D3, pendiente de tu confirmación.**
- Un SRS que no está en la lista, o `srs_id` 0 o −1 sin definición, deja la capa sin importar hasta que el usuario elija uno de los sistemas de la app (RF-9). La unidad sale del sistema (metro o grado) y no se puede cambiar; solo se pregunta si el SRS es «no disponible» (RF-10).

### A3. Filas de una capa dentro de la zona (RF-13, RF-15, RF-16)
- El recuadro de la zona en el SC de la capa: centro y radio pasados a ese sistema, con un margen del 0,5 % por la escala de la proyección. Es solo un prefiltro; la prueba exacta es la de A4.
- Si existe la tabla `rtree_<tabla>_<geometría>` se consulta por el recuadro (índice de SQLite, sin leer geometrías). Si no, la **exploración** lee de cada fila solo `substr(geom, 1, 40)` (cabecera del blob con su envolvente) y construye una vez, en memoria, una matriz `(fid, minE, maxE, minN, maxN)` por capa marcada; filtrar un radio nuevo es recorrer esa matriz. Si el blob no trae envolvente, se calcula una vez leyendo la geometría. Una capa de más de 5.000.000 de filas sin R-tree no se explora entera: se pide al usuario reducir las capas o importar «Todo el contenido», con el mensaje de RF-16.
- El recuento que se ve al cambiar el radio es el de los candidatos del prefiltro más la prueba exacta de A4 sobre ellos, en segundo plano, con 150 ms de espera y cancelando el cálculo anterior (RNF de 2 s).

### A4. Importar un objeto (RF-11, RF-15 a RF-19, RF-22)
1. Se lee la geometría con `GpkgGeometry`; `EMPTY` o `CURVE` → ignorado con su motivo (RF-18).
2. Cada vértice pasa por `CadProjector` (unidad, UTM o geográficas, ED50 por rejilla, ámbito del SC del proyecto). Un vértice que cae fuera del ámbito descarta el objeto entero y lo cuenta como ignorado (RF-11).
3. Con zona, el objeto entra si algún vértice está a menos del radio del centro del proyecto, en el plano del SC del proyecto; sin zona, entra (RF-15). Un objeto entra entero.
4. Punto → un elemento de la capa de puntos; línea → polilínea; polígono → una polilínea cerrada para el exterior y otra por hueco, con `POLYGON_RING`; los múltiples y las colecciones, un elemento por parte (RF-17).
5. Altura: con «¿Usar las cotas?» y Z presente, se guarda con `hasZ`; si no, sin altura. M se ignora (RF-19).
6. Nombre: el valor del campo de nombre como texto, y sin campo o con el valor vacío, el número del objeto dentro de la capa (RF-22). Los atributos van al bloque del trozo (RF-20).
7. Se agrupa por capa de diseño y por la cuadrícula de 500 m de S040/A9 (la misma escritura por trozos, tandas y buffers) y el dibujo pasa por `IMPORTING` y una transacción final (RF-26, RF-29, RF-30).

Una importación crea **un dibujo** con una capa de diseño por cada capa marcada y tipo (una tabla de polígonos da una capa de líneas; una tabla con geometrías de varios tipos da una de puntos y otra de líneas). El color de cada capa se asigna automáticamente de una paleta fija de 12 colores, por orden de aparición (RF-23).

### A5. Distancia y aviso (RF-12)
La extensión de cada capa marcada se calcula de las envolventes (o del R-tree) pasadas al SC del proyecto; la distancia es la de la gran circunferencia entre el centro de esa extensión y `ProjectCenterProvider` (S006/RF-37). Con «Todo el contenido» y sin centro del proyecto no se avisa, como S040/RF-10.

### A6. Fijar el punto central desde la importación (RF-14)
Si el proyecto no tiene centro y no se eligió «Todo el contenido», la pantalla ofrece «Fijar el punto central» y abre el selector de S006 (`CentralPointPicker` y el cuadro de coordenadas), guarda el punto con `ProjectRepository.update` y sigue. No se importa hasta que haya centro o se elija «Todo el contenido».

## Contratos
- `GeoPackageReader.open(source) : GpkgSession` (`layers`, `rows(layer, bbox)`, `close()`); `GeoPackageScan.count(layers, area): Flow<GpkgCounts>`; `GeoPackageImporter.import(projectId, crs, fileName, options: GpkgImportOptions, session, onProgress): GpkgImportResult`.
- `GpkgImportOptions`: por capa marcada su `CadSourceCrs` (propuesto o elegido), su campo de nombre, `useElevations` y la `ImportArea`.
- `CadRepository.attributes(ref: CadEntityRef): Map<String, GpkgValue>`.
- `MapController`: sin cambios; `DesignScene` gana el nombre opcional de cada punto y de cada línea para etiquetarlos.
- Navegación: `SurveyPage.ImportGpkg(projectId)` (ADR-19).

Textos nuevos (es y en): Importar GeoPackage, las capas con su tipo, recuento y SC, «Sin sistema de coordenadas: elige uno», el aviso de WGS 84, «Zona de importación», «Radio», «Todo el contenido», «Objetos en la zona: %d», el límite de 500.000 objetos, «Fijar el punto central», «Campo de nombre», «Sin nombre», los motivos de ignorado (curva, vacío, fuera del ámbito, atributo omitido), el aviso de que se dejaron fuera tablas sin geometría o de teselas, y el resumen por capa.

Accesibilidad (RNF-T8): casillas y botones de 56 dp; cada capa con nombre, tipo y estado en texto; el contorno de polígono se distingue por texto («Contorno») y no solo por trazo; TalkBack lee el recuento al terminar y no el progreso.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Copia temporal del `.gpkg` a la caché, borrada al terminar (D1, pendiente) | `SQLiteDatabase` solo abre rutas; `/proc/self/fd` falla con proveedores que no son ficheros y con WAL. El límite de 100 MB acota el espacio | Abrir por descriptor de fichero; un lector de SQLite propio |
| Atributos en una tabla con un bloque por trozo (D2, pendiente) | El mapa no los necesita y una fila por objeto haría 500.000 filas | Una fila por objeto; los atributos dentro del trozo |
| El nombre dentro del trozo, el resto de atributos aparte | Etiquetas y replanteo sin leer atributos | Todo en la tabla de atributos |
| Un único dibujo por importación con una capa por tabla | Se borra, se copia y se activa como un DXF (RF-27, RF-28) | Un dibujo por capa |
| Prefiltro por R-tree o envolvente y prueba exacta por vértice | Cumple RF-15 y los 2 s del radio sin leer todas las geometrías | Leer todas las geometrías en cada cambio de radio |
| Distancia a la zona en el plano del SC del proyecto | A 100 km el error de escala (≤ 0,1 %) cabe en el margen de un radio de importación | Distancia geodésica por vértice (más cara y sin ganancia) |
| WGS 84 como ETRS89 con aviso (D3, pendiente) | Es lo que traen los `.gpkg` de QGIS por defecto | Rechazar la capa y pedir elegir |
| Solo lectura y sin dependencias | RNF-T14 y ADR-14 | Librería de GeoPackage |

## Estrategia de tests
- **JVM `core:model`:** `CadChunkCodecTest` (nombre y contorno, trozos viejos), `ImportAreaTest`.
- **JVM `core:formats`:** `GpkgFileKindTest`; `GpkgGeometryTest` con todos los tipos de WKB, Z/M, vacío, curvas y las dos endianidades, contra lo que escribe `tools`; `GpkgCrsCatalogTest` contra la lista de pyproj.
- **JVM `core:survey`:** `CadProjectorGeographicTest` (≤ 1 mm con pyproj, ida y vuelta < 0,1 mm), `ImportAreaFilterTest`.
- **`tools`:** `generar_geopackage.py` y su test: `.gpkg` de puntos, líneas, polígonos con huecos y múltiples, en EPSG:25830, 4258, 4326 y 3857 (no disponible), con y sin R-tree, con altura, con una tabla sin geometría y una de teselas, uno dañado y uno que no es GeoPackage.
- **Robolectric `data`:** `GeoPackageReaderTest`, `GeoPackageScanTest`, `GeoPackageImporterTest` (resumen, cancelar sin dejar nada, fallo de escritura, limpieza del temporal), migración 18 → 19, `CadRepositoryAttributesTest`, `GpkgBackupTest` (copia y paquete), nombres de test de 60 caracteres o menos.
- **Robolectric `app`:** `GpkgImportViewModelTest`, `GpkgImportScreenTest`, `DesignLabelsTest`, `GpkgFeatureSheetTest`, `GpkgStakeoutTest`.
- **Rendimiento:** un `.gpkg` sintético de 100 MB se lee y se recorta en menos de 20 s y cambiar el radio recuenta en menos de 2 s (Robolectric como cota; el móvil de referencia en la demo).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-7 | A1, `GpkgFileKind`, `GeoPackageReader`, pantalla | `GpkgFileKindTest`, `GeoPackageReaderTest`, `GpkgImportViewModelTest` |
| RF-8 a RF-11 | A2, `GpkgCrsCatalog`, `CadProjector` | `GpkgCrsCatalogTest`, `CadProjectorGeographicTest`, `GeoPackageImporterTest` |
| RF-12 | A5 | `GeoPackageScanTest`, `GpkgImportViewModelTest` |
| RF-13 a RF-16 | A3, A4, A6 | `ImportAreaFilterTest`, `GeoPackageScanTest`, `GpkgImportViewModelTest` |
| RF-17 a RF-19 | `GpkgGeometry`, A4 | `GpkgGeometryTest`, `GeoPackageImporterTest` |
| RF-20 a RF-22 | atributos, nombre | `CadRepositoryAttributesTest`, `GpkgFeatureSheetTest`, `DesignLabelsTest` |
| RF-23 a RF-24 | mapa y replanteo | `DesignLabelsTest`, `GpkgStakeoutTest` |
| RF-25 a RF-30 | importador, copias | `GeoPackageImporterTest`, `GpkgBackupTest`, `GpkgImportScreenTest` |
