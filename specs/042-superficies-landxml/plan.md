# Plan técnico — Spec 042

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/042-superficies-landxml/spec.md`, `specs/019-replanteo-cota/` (la cota de diseño, el nivel vertical, las tolerancias y el informe que se reutilizan), `specs/041-replanteo-cad/` (el controlador con objetivo móvil y el CSV con columnas añadidas) y `specs/040-capa-cad-dxf/` (la importación por streaming, los trozos de 256 kB, el convertidor de SC y la copia de seguridad que se imitan).

Sin dependencias nuevas (Gradle ni Python). El LandXML se lee con un tokenizador XML propio en `core:formats`; los valores de referencia los calcula `tools/` con `pyproj` (ya es dependencia de `tools`) y con un cálculo baricéntrico aparte en Python puro.

## Estructura de módulos
- `core:model`:
  - `surface/Surface`: la superficie guardada (nombre, fichero, fecha de importación, SC y unidad de origen, extensión, cota mínima y máxima, vértices, triángulos y huecos) (RF-13, RF-14, RF-16);
  - `surface/SurfaceHeight { Value(m), Outside, Loading }`: el resultado de preguntar la cota de la superficie en una posición (RF-22, RF-23);
  - `point/ElevationSource { POINT, SURFACE }` (RF-25, RF-28);
  - `StakeoutRef` gana `elevationSource`, `surfaceName`, `surfaceImportedAtMs`, `surfaceElevationM` y `verticalOffsetM`, todos opcionales (RF-28, RF-29);
  - `surface/SurfaceTileCodec`: el formato binario de una tesela de la superficie, de hasta 256 kB (A3).
- `core:formats`:
  - `xml/XmlStreamReader`: tokenizador XML por streaming, sin DOM (A1);
  - `landxml/LandXmlReader`: `scan` (las superficies del fichero con sus recuentos y su extensión) y `read` (los puntos y las caras de una superficie), con la unidad, la versión y los triángulos invisibles (RF-2 a RF-5, RF-7, RF-12);
  - `csv/StakeoutReportCsv` gana cuatro columnas (RF-31).
- `core:survey` (sin Android ni Hilt):
  - `TinTiler`: reparte los triángulos en teselas con un árbol cuaternario (A3);
  - `TinTile` y `TinElevation`: la cota de un triángulo por interpolación lineal y la búsqueda del triángulo que contiene una posición (RF-22);
  - `SurfaceProjector`: convierte los puntos leídos al SC y a la unidad del proyecto con el mismo convertidor que S040 (`CadProjector`, `CadPlacement`) (RF-6, RF-7);
  - `StakeoutDiff.ref` recibe la cota de la superficie y el origen (RF-28).
- `data`:
  - Room v15: tablas `surfaces` y `surface_tiles`, dos columnas nuevas en `projects` y cinco en `points`, con `AutoMigration(14, 15)` (RNF-T6);
  - `SurfaceRepository` y `SurfaceImporter` (A2), con la misma forma que `CadRepository` y `CadDrawingImporter`;
  - `ActiveSurface` y `ActiveSurfaceProvider`: el índice de teselas de la superficie activa y su caché (A4);
  - `BackupRepository` y `DatabaseSnapshot` incluyen las tablas nuevas (RF-21).
- `app`:
  - `ui/surface/`: `SurfaceImportViewModel`, `SurfaceImportScreen`, `SurfaceImportRoute`, `SurfacesSheet` y su `SurfacesViewModel` (RF-1 a RF-20);
  - `StakeContext` gana `surface: SurfaceGuide?` y `elevationSource` (A5); `StakeoutController` calcula la cota de cada época (RF-23 a RF-27);
  - `StakeoutPanel`, `LineStakeoutPanel` y `StakeoutLevelView`: el conmutador y «Fuera de la superficie» (RF-23, RF-25, RF-26);
  - `StakeoutViewModel`, `LineStakeoutViewModel` y `StakeObjective`: el origen de la cota y lo que se guarda (RF-24, RF-27, RF-28, RF-29);
  - `CollectorViewModel` y su pantalla: la cota de la superficie bajo la punta (RF-33);
  - la ficha del punto replanteado, `StakeoutRefText` y el informe (RF-30, RF-31).
- `tools`: `tools/superficies/generar_superficies.py` y los casos de `fixtures/superficies/` con lo esperado calculado aparte (constitución nº 2).

## Modelo de datos

### Room v15
```
surfaces        id TEXT PK, project_id TEXT FK(projects, CASCADE), file_name, imported_at_ms, source_crs, source_unit,
                name TEXT, status TEXT (IMPORTING | READY), min_e, max_e, min_n, max_n, min_z, max_z REAL,
                vertex_count, triangle_count, hole_count INTEGER
surface_tiles   id INTEGER PK autogenerado, surface_id TEXT FK(surfaces, CASCADE), min_e, max_e, min_n, max_n REAL,
                triangle_count INTEGER, data BLOB (≤ 256 kB)
projects  +     active_surface_id TEXT NULL, vertical_offset_m REAL NOT NULL DEFAULT 0
points    +     stake_elev_source TEXT NULL, stake_surface_name TEXT NULL, stake_surface_imported_ms INTEGER NULL,
                stake_surface_elev REAL NULL, stake_vertical_offset REAL NULL
```
- `AutoMigration(14, 15)` sin especificación: solo tablas y columnas con valor por defecto o anulables. S017b (fotos) pasa de la 15 a la 16 cuando se retome (aviso en su `plan.md`, que requiere permiso para tocar `specs/`).
- La superficie activa y el desplazamiento son del proyecto, así que viven en `projects`: una sola columna garantiza que hay una activa o ninguna (RF-16, RF-19). Borrar la superficie activa pone `active_surface_id` a NULL en la misma transacción (RF-18).
- Un punto replanteado copia el nombre y la fecha de la superficie en lugar de una clave foránea: borrar la superficie no deja el punto sin origen (RF-32). Las cuatro cotas y el desplazamiento se guardan sin redondear. La cota de diseño y la diferencia vertical ya tienen columna desde S019 (`stake_design_elevation`, `stake_d_height`).
- Fuera de la superficie con el origen vacío (RF-29): `stake_elev_source`, `stake_surface_elev` y las columnas de cota y diferencia de S019 quedan en NULL; `stake_surface_name` y `stake_surface_imported_ms` sí se guardan, y con ellos la ficha sabe que fue «Fuera de la superficie» (RF-30).
- La medida bruta no se toca (RNF-T6): lo de la superficie es un añadido del punto, en la misma transacción que lo guarda.

### Formato de una tesela (`SurfaceTileCodec`)
- Cabecera con un número de formato, el número de vértices y el de triángulos de la tesela.
- Vértices locales como `Double` (Este, Norte, cota), sin redondear, seguidos de los triángulos como tres índices `Int` a esos vértices. Cada tesela es autosuficiente: no necesita ninguna otra para dar una cota.
- Si una tesela superara los 256 kB al codificarla, el árbol la parte (A3).

## Algoritmos

### A1. Leer el LandXML por streaming (RF-2 a RF-5, RF-7, RF-12)
- `XmlStreamReader` lee eventos (inicio de elemento con atributos, texto, fin de elemento) de un `InputStream` sin cargar el fichero. Resuelve las cinco entidades predefinidas y las numéricas, salta comentarios, `CDATA`, instrucciones de proceso y `DOCTYPE`, acepta BOM y las codificaciones UTF-8 e ISO-8859-1 declaradas, y lanza un error propio con la posición si el XML está mal formado. Un fichero que no empieza por XML es «no es un LandXML» (RF-3).
- `LandXmlReader.scan` recorre el fichero una vez y devuelve, por superficie, el nombre, el número de puntos, de caras y de caras invisibles, y la extensión en Este, Norte y cota del fichero. Solo cuenta las superficies con `surfType="TIN"` y con caras; las demás (`GRID` o sin caras) no se ofrecen. Lee la versión de `<LandXML version>` y la unidad de `<Units>`: `Metric` con `linearUnit` metros, `Imperial` con `linearUnit` pies o pies topográficos de EE. UU. Sin `<Units>` no propone ninguna (RF-7).
- Orden de las coordenadas: **Norte, Este, cota** en cada `<P>` (así lo define LandXML; es lo contrario de un CSV de puntos). Un test con una superficie asimétrica lo fija.
- `LandXmlReader.read` vuelve a recorrer el fichero y entrega, de la superficie elegida, los puntos en arrays primitivos y las caras como tres posiciones. Los identificadores de punto del fichero pueden no ser consecutivos ni números: se traducen a posición con un mapa abierto de `Long` a `Int` y, si algún identificador no es un número, con un mapa de texto. Una cara marcada `i="1"` es invisible y no se guarda como triángulo, pero se cuenta como hueco (RF-12, RF-13). Una cara que cita un punto que no existe o repite un punto se descarta y se cuenta.
- El tamaño de 100 MB se comprueba antes de leer, como en S040/RF-5 (RF-4).

### A2. Importar una superficie (RF-6 a RF-15)
- `SurfaceImporter.scan(openStream)` devuelve `SurfaceScan` con las superficies del fichero (para RF-5 y RF-8). La extensión que se enseña en la pantalla es la de las cuatro esquinas del rectángulo del fichero convertidas al SC del proyecto; la exacta, calculada vértice a vértice, es la del resumen (RF-13).
- `SurfaceImporter.import(...)`: `begin` (estado `IMPORTING`) → leer los puntos y las caras → convertir cada punto con `SurfaceProjector` (SC de origen → SC del proyecto, unidad → metros; la cota solo cambia de unidad, no de sistema de alturas) → `TinTiler` → `addTiles` por lotes → `finish` (estado `READY`, con la extensión exacta y los recuentos). Cancelar o fallar la escritura llama a `abort`, que borra todo lo escrito (RF-11); `cleanUpUnfinished` borra al arrancar lo que quedó en `IMPORTING` tras un cierre brusco.
- Memoria: los puntos y las caras viven en arrays primitivos (unos 12 MB por cada 500.000 puntos y 12 MB por cada millón de triángulos); la conversión se hace en el sitio. El fichero de 100 MB no se carga entero nunca (RNF).
- Si el proyecto no tiene superficie activa, `finish` fija `active_surface_id` en la misma transacción (RF-17). Si el nombre del fichero ya existe, la pantalla avisa antes (RF-15), como `duplicateConfirmed` de S040.
- El progreso son los bytes leídos entre el tamaño del fichero; el botón «Cancelar» cancela la corrutina (RF-10).

### A3. Teselas y cota (RF-22)
- `TinTiler` reparte los triángulos con un árbol cuaternario sobre la extensión: una hoja guarda a lo sumo 4.000 triángulos. Un triángulo va a todas las hojas que cubre su caja; así cada hoja es autosuficiente y consultar no cruza hojas. Una hoja cuyo bloque codificado pasara de 256 kB se parte aunque tenga menos triángulos. Las cajas de las hojas se guardan en `surface_tiles` para encontrarlas sin leer los bloques.
- `TinElevation.at(tile, e, n)` recorre los triángulos de la hoja (a lo sumo 4.000) y devuelve la cota del primero que contiene la posición, por coordenadas baricéntricas con una holgura de 1e-12 en los bordes para que una arista o un vértice compartido no caigan en un hueco. En una arista la cota es la misma en los dos triángulos (continuidad), así que el resultado no depende de cuál se elija a menos de 1 mm.
- Una posición que no está en ningún triángulo es `Outside` (borde o hueco); si no cae en la caja de ninguna hoja también (RF-23). Con un SC geográfico se usa el plano local, como S018 (caso límite de la spec).

### A4. La superficie activa en memoria (RF-16, RF-22, RF-23, RNF-T13)
- `ActiveSurfaceProvider` observa `projects.active_surface_id` y carga el índice de teselas de la superficie activa (solo las cajas y los identificadores, unos pocos kB incluso con un millón de triángulos). No lee ningún bloque al activar: por eso la primera cota tarda menos de 1 s.
- `ActiveSurface.heightAt(e, n)` es síncrona y no bloquea: mira una caché de 16 teselas, y si la hoja que contiene la posición no está, devuelve `Loading`, lanza su lectura en segundo plano y precarga las hojas vecinas hacia donde se mueve la punta. Cuando llega, la siguiente época ya responde `Value` o `Outside`. `Loading` nunca se presenta como «Fuera de la superficie» (en el nivel vertical se mantiene el último valor hasta 1 s y después muestra «Calculando…»).
- Una superficie borrada o desactivada vacía la caché y avisa a los que observan (RF-27).

### A5. La cota de diseño en el replanteo (RF-24 a RF-27)
- `StakeContext` gana `surface: SurfaceGuide?` (la superficie activa y el desplazamiento vertical del proyecto, o nulo) y `elevationSource: ElevationSource`. La regla (RF-24, RF-25):
  1. Si hay cota escrita a mano (S019/RF-2), el origen es «Cota del punto».
  2. Si no, y el punto de diseño (o el objetivo de una línea) tiene cota propia, el origen es el elegido en el conmutador, con «Cota del punto» por defecto.
  3. Si no tiene cota propia y hay superficie, el origen es «Superficie».
  4. Sin ninguna de las tres, no hay nivel (`NO_DESIGN_ELEVATION`).
- Con origen «Superficie», `StakeoutController.onEpoch` pregunta `surface.heightAt(punta)` en cada época y toma como cota de diseño ese valor más el desplazamiento. Es la cota **en la posición de la punta** (suposición de la spec); en un punto fijo, al llegar a la diana, coincide con la del punto de diseño.
- El nivel vertical usa el estado de histéresis de S019, que se reinicia cuando cambia la cota de diseño (`levelKey`). Con la superficie la cota cambia en cada época, así que la clave del reinicio es el origen y el desplazamiento, no el valor de la cota: si no, el nivel se reiniciaría 10 veces por segundo y nunca aguantaría una zona. Cambiar de origen, de superficie o de desplazamiento sí reinicia.
- `GuidanceUi` gana `surface: SurfaceUi?` con la cota de la superficie, el desplazamiento y el resultado (`Value`, `Outside` o `Loading`); `LevelUnavailable` gana `OUTSIDE_SURFACE`, con el texto «Fuera de la superficie» en lugar del nivel (RF-23). La guía horizontal no cambia.
- Si el usuario escribe una cota a mano estando fuera de la superficie (RF-23), el origen pasa a «Cota del punto» y el conmutador sigue visible. Si desactiva o borra la superficie con origen «Superficie» (RF-27), el `ViewModel` observa `ActiveSurfaceProvider`, pasa a la cota propia si existe y si no a solo planta, y publica el motivo.

### A6. Medir y guardar (RF-28, RF-29, RF-32)
- `StakeObjective` y `StakeoutDiff.ref` reciben, en el momento de la medida, el origen, la superficie (nombre y fecha), la cota de la superficie en la punta, el desplazamiento, la cota de diseño y la tolerancia. La diferencia de cota sale con la convención de S019: medido menos diseño.
- Si en ese instante la punta está fuera de la superficie con origen «Superficie», se guarda sin origen de la cota, sin cota de la superficie, sin cota de diseño y sin diferencia vertical (RF-29); solo se guardan el nombre y la fecha de la superficie (y el desplazamiento), que son lo que dice a la ficha que fue «Fuera de la superficie» (RF-30).
- Un solo `PointRepository.save(point, stakeoutRef)`: el punto y todo lo del replanteo en una transacción (RNF-T6).

### A7. Informe, ficha y recolector (RF-30, RF-31, RF-33)
- `StakeoutReportCsv` añade cuatro columnas tras las de S041: origen de la cota, superficie, cota de la superficie y desplazamiento vertical, con `Locale.ROOT` y las unidades del proyecto, vacías sin superficie (RF-31). Los goldens se regeneran con `TOPOPHONE_REGENERAR_DORADOS=1`.
- La ficha lee el `StakeoutRef` ampliado a través de `StakeoutRefText` (RF-30).
- El recolector consulta `ActiveSurface.heightAt` con la punta de cada época a 5 Hz y muestra la cota de la superficie con desplazamiento y la diferencia con la cota de la punta como desmonte o terraplén, o «Fuera de la superficie». No añade nada al punto que se mide (RF-33).

### A8. Pantallas (RF-1, RF-5, RF-8 a RF-10, RF-13, RF-15 a RF-20)
- «Importar superficie» se añade a los menús que ya tienen «Importar DXF». La pantalla de importación sigue los cinco pasos de `CadImportStage` (carga, problema, configuración, importación, resultado), con la lista de superficies del fichero y un paso de elección cuando hay más de una.
- `SurfacesSheet` lista las superficies del proyecto con su activa, el interruptor de activación, el borrado con confirmación y el campo del desplazamiento vertical, que valida con `NumberInput` y las unidades del proyecto (RF-16 a RF-20).

## Contratos

### Estado de la cota de superficie (`SurfaceUi`)
`heightM` (o nulo), `offsetM`, `designM` (cota con desplazamiento) y `state` (`VALUE`, `OUTSIDE`, `LOADING`). Texto de TalkBack: «terraplén de 0,12 m sobre la superficie».

### Textos nuevos (`strings.xml`, es y en)
Importar superficie, los pasos de la importación, cada motivo de rechazo (RF-3, RF-4), la elección de superficie, «Superficies», «Activa», «Activar», «Desplazamiento vertical», «Fuera de la superficie», «Calculando…», «Cota del punto» y «Superficie» del conmutador, el aviso del RF-27, «Cota de la superficie» en el recolector y en la ficha, y los textos de TalkBack.

### Accesibilidad (RNF-T8)
Campos, conmutador y botones de 56 dp; «Fuera de la superficie», el desmonte y el terraplén nunca solo con color; TalkBack lee la frase al entrar en cada zona y a petición, no en cada refresco.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Tokenizador XML propio en `core:formats` | `core:*` no puede importar `android.*`; el LandXML es XML simple y 100 MB piden streaming | StAX (no existe en Android); `kxml2` o `XmlPullParser` (dependencia nueva o `android.*`); DOM (100 MB en memoria) |
| Teselas de un árbol cuaternario, cada una autosuficiente | Activar sin leer bloques (< 1 s) y consultar solo una hoja de ≤ 4.000 triángulos (RNF-T13) | Cargar toda la superficie al activar (24 MB y más de 1 s); una malla única con un índice en memoria |
| `heightAt` síncrona con `Loading` | `onEpoch` no es suspendible y no puede esperar a la base de datos | Hacer suspendible la guía entera |
| Activa y desplazamiento en `projects` | Una columna garantiza una sola activa y el desplazamiento se conserva al cambiar de superficie (RF-16, RF-19) | Un indicador `active` por superficie; una tabla de ajustes aparte |
| Clave de reinicio del nivel = origen y desplazamiento | La cota cambia cada época con la punta; reiniciar el nivel cada vez lo inutilizaría (RF-26) | Reiniciar al cambiar la cota |
| Cota de diseño en la posición de la punta | Es el desmonte o terraplén del sitio donde se está; coincide con el punto de diseño al llegar a la diana (suposición de la spec) | Cota en el punto de diseño (no sirve en líneas ni en explanaciones) |
| Copiar nombre y fecha de la superficie en el punto | Borrar la superficie no debe dejar el punto sin origen (RF-32) | Una clave foránea |
| Orden Norte, Este, cota al leer `<P>` | Así lo define LandXML | Leer como Este, Norte |
| `AutoMigration(14, 15)` sin especificación | Solo columnas con valor por defecto o anulables y tablas nuevas | Migración a mano |

## Estrategia de tests
- **Unitarios JVM**:
  - `XmlStreamReaderTest`: entidades, `CDATA`, comentarios, `DOCTYPE`, BOM, UTF-8 e ISO-8859-1, XML mal formado y un fichero sin XML (RF-2, RF-3).
  - `LandXmlReaderTest`: versiones 1.0 a 1.2, orden Norte-Este-cota, identificadores no consecutivos y de texto, caras invisibles, `GRID`, sin superficies, varias superficies, unidad métrica, en pies y sin unidad (RF-2, RF-3, RF-5, RF-7, RF-12).
  - `SurfaceTileCodecTest`: ida y vuelta sin redondear y el límite de 256 kB.
  - `TinTilerTest`: reparto en teselas, un triángulo que cruza hojas, una hoja que se parte por tamaño.
  - `TinElevationTest`: cota frente a `fixtures/superficies/` (≤ 1 mm) con un punto dentro, sobre una arista, sobre un vértice, fuera del contorno y en un hueco (RF-22, RF-23).
  - `SurfaceProjectorTest`: coordenadas en pies y en otro SC frente a pyproj (≤ 1 mm) (RF-6, RF-7).
  - `StakeoutDiffTest` (ampliado) y `PointStakeoutTest` (ampliado): la diferencia con la superficie y los valores por defecto (los puntos de S018 a S041 no cambian) (RF-28, RF-29).
- **`data`**:
  - `TopoPhoneDatabaseTest` 14 → 15 con un proyecto con dibujos y puntos replanteados: se conservan, y el desplazamiento queda en 0.
  - `SurfaceRepositoryTest`: `begin`/`finish`/`abort`, la limpieza al arrancar, la primera superficie que se activa sola, activar otra, borrar la activa y el desplazamiento que se conserva (RF-11, RF-16 a RF-19).
  - `SurfaceImporterTest`: importar, cancelar, fallo de escritura, varias superficies, mismo nombre de fichero (RF-5, RF-10, RF-11, RF-13 a RF-15).
  - `ActiveSurfaceTest`: `Loading` y después `Value` o `Outside`, la precarga, el vaciado al desactivar (A4).
  - `PointRepositoryTest` (ampliado): el punto y su replanteo con superficie en una transacción, y la superficie borrada después (RF-28, RF-32).
  - `SurfaceBackupTest`: copia, paquete y restauración con superficies, la activa y el desplazamiento (RF-21).
- **Robolectric (`app`)**:
  - `StakeoutControllerSurfaceTest`: la cota con cada época, la histéresis del nivel que no se reinicia, «Fuera de la superficie», el apagado por FIX perdido (RF-23, RF-24, RF-26).
  - `StakeoutViewModelSurfaceTest` y `LineStakeoutViewModelSurfaceTest`: la regla de A5 con cota propia, sin cota y a mano, el conmutador, la desactivación con el replanteo abierto (RF-24, RF-25, RF-27).
  - `SurfaceImportViewModelTest` y `SurfaceImportScreenTest`: los pasos, los rechazos, la elección de superficie y el aviso de lejanía (RF-1 a RF-10).
  - `SurfacesSheetTest`: la lista, activar, borrar y el desplazamiento no numérico (RF-16 a RF-20).
  - `StakeoutMeasureSurfaceTest`: lo guardado al medir y fuera de la superficie (RF-28, RF-29).
  - `StakeoutReportCsvTest` (ampliado) y el test de la ficha: las cuatro columnas y los textos (RF-30, RF-31).
  - `CollectorSurfaceTest`: la cota de la superficie bajo la punta y que no se guarda en el punto (RF-33).
- **`tools`**: tests de `generar_superficies.py` (que los ficheros y lo esperado guardados son los que genera el script).
- **Rendimiento**: `SurfacePerformanceTest`: activar una superficie de 1.000.000 de triángulos y tener la primera cota en menos de 1 s; la consulta a 10 Hz sin pasar de 16 ms; importar un LandXML de 100 MB generado al vuelo en menos de 90 s sin quedarse sin memoria.
- **Reproducción sintética**: un recorrido que cruza una superficie con un hueco, con su resultado esperado calculado aparte (criterios de finalización).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, la puerta G2b, que hace el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A8 | `SurfaceImportViewModelTest`, `SurfaceImportScreenTest` |
| RF-2 | A1 | `XmlStreamReaderTest`, `LandXmlReaderTest` |
| RF-3 | A1 | `LandXmlReaderTest`, `SurfaceImportViewModelTest` |
| RF-4 | A1 | `SurfaceImportViewModelTest` |
| RF-5 | A1, A8 | `LandXmlReaderTest`, `SurfaceImportViewModelTest` |
| RF-6 | A2 | `SurfaceProjectorTest`, `SurfaceImportViewModelTest` |
| RF-7 | A1, A2 | `LandXmlReaderTest`, `SurfaceProjectorTest` |
| RF-8 | A2 | `SurfaceImporterTest`, `SurfaceImportScreenTest` |
| RF-9 | A2 | `SurfaceImportViewModelTest` |
| RF-10 | A2 | `SurfaceImporterTest`, `SurfaceImportViewModelTest` |
| RF-11 | A2 | `SurfaceImporterTest`, `SurfaceRepositoryTest` |
| RF-12 | A1 | `LandXmlReaderTest`, `TinElevationTest` |
| RF-13 | A2 | `SurfaceImporterTest`, `SurfaceImportScreenTest` |
| RF-14 | A2, Room v15 | `SurfaceImporterTest`, `SurfaceRepositoryTest` |
| RF-15 | A2 | `SurfaceImportViewModelTest` |
| RF-16 | A4, A8 | `SurfaceRepositoryTest`, `SurfacesSheetTest` |
| RF-17 | A2 | `SurfaceRepositoryTest` |
| RF-18 | Room v15 | `SurfaceRepositoryTest`, `SurfacesSheetTest` |
| RF-19 | Room v15 | `SurfaceRepositoryTest`, `TopoPhoneDatabaseTest` |
| RF-20 | A8 | `SurfacesSheetTest` |
| RF-21 | Copia | `SurfaceBackupTest` |
| RF-22 | A3 | `TinElevationTest`, `SurfacePerformanceTest` |
| RF-23 | A3, A5 | `TinElevationTest`, `StakeoutControllerSurfaceTest` |
| RF-24 | A5 | `StakeoutViewModelSurfaceTest`, `LineStakeoutViewModelSurfaceTest` |
| RF-25 | A5 | `StakeoutViewModelSurfaceTest` |
| RF-26 | A5 | `StakeoutControllerSurfaceTest` |
| RF-27 | A5 | `StakeoutViewModelSurfaceTest` |
| RF-28 | A6, Room v15 | `StakeoutMeasureSurfaceTest`, `PointRepositoryTest`, `StakeoutDiffTest` |
| RF-29 | A6 | `StakeoutMeasureSurfaceTest` |
| RF-30 | A7 | test de la ficha |
| RF-31 | A7 | `StakeoutReportCsvTest` |
| RF-32 | Room v15, A6 | `PointRepositoryTest` |
| RF-33 | A7 | `CollectorSurfaceTest` |

## Pendiente para otras specs
- S045 (ráster) reutiliza `ElevationSource`, `SurfaceHeight`, `SurfaceGuide` y el desplazamiento vertical: el ráster será otra fuente de `heightAt` bajo la misma superficie activa.
- S017b (fotos) pasa a la versión 16 de la base de datos (`AutoMigration(15, 16)`); su `plan.md` aún habla de una versión anterior y solo se corrige con permiso para tocar `specs/`.
- S019/S041: sus `spec.md` no cambian en el texto; los RF que amplía esta spec están en «Cambios en documentos anteriores» de la spec 042.
