# Plan técnico — Spec 011

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-2, ADR-5, ADR-8, ADR-9, ADR-19), `specs/011-objetos-y-puntos-manuales/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `tools/geodesia` | Vectores pyproj de puntos manuales: UTM inversa en ETRS89, REGCAN95 y ED50, altitud ↔ altura elipsoidal, y el punto del suelo con otra altura del jalón | RF-14, RF-17, RF-19, RF-20 |
| `core:geodesy` | `TransverseMercator.inverse` y `Utm.inverse` (serie de Krüger de orden 6, la misma de `forward`) | RF-17, RF-20 |
| `core:model` | `Point` (sustituye a `CollectedPoint`), con su origen, su medida, las coordenadas escritas, el historial, la visibilidad y la papelera; `PointNames.nextFree`; `PointSearch`; `NumberInput` y `AngleInput` para leer lo que se escribe | RF-1 a RF-4, RF-8, RF-16 a RF-18, RF-21 |
| `core:survey` | `ProjectPositionCalculator.fromGround` (del punto del suelo al SC del proyecto); `ManualCoordinates` (de lo escrito al punto del suelo); `PointCorrections` (jalón, volver a la medida, duplicar) | RF-12 a RF-15, RF-17 a RF-21 |
| `data` | Room v6: columnas nuevas en `points` y tabla `point_history` (migración 5→6 probada); `PointRepository` con edición, historial, visibilidad, papelera y purga | RF-1, RF-4, RF-9 a RF-11, RF-13 a RF-15, RF-20 a RF-26 |
| `app` | Lista de objetos, ficha del punto, formulario de punto (nuevo o editado), papelera de puntos, botón "Objetos" en el mapa y toque sobre un punto del mapa | RF-1 a RF-26 |

Sin dependencias nuevas.

## Modelo de datos
```kotlin
// core:model
enum class PointOrigin { MEASURED, MANUAL }

/** Coordenadas de un punto del suelo: geográficas en el datum de los cálculos (ETRS89 o REGCAN95) y en el SC del proyecto. */
data class PointCoordinates(
    val ground: GeodeticPosition,                          // φ, λ, h elipsoidal; h null = sin altura
    val easting: Double?, val northing: Double?, val height: Double?,  // SC y sistema de alturas del proyecto
)

/** La medida de S010: no cambia nunca, salvo `coordinates` al corregir el jalón (recalculadas desde `antenna`). */
data class Measurement(
    val antenna: GeodeticPosition, val poleHeightM: Double, val quality: PointQuality, val rawNmea: String,
    val outOfTolerance: Set<PrecisionFailure>, val measuredFromMs: Long, val measuredToMs: Long,
    val coordinates: PointCoordinates,
)

enum class EntryForm { PROJECT_CRS, GEOGRAPHIC }
enum class HeightKind { PROJECT, ELLIPSOIDAL }

/** Coordenadas tal como se escribieron (S011/RF-20): Este y Norte en metros, o φ y λ en grados del datum del proyecto. */
data class TypedCoordinates(val form: EntryForm, val first: Double, val second: Double, val height: Double?, val heightKind: HeightKind)

data class Point(
    val id: String, val projectId: String, val name: String, val description: String,
    val origin: PointOrigin,
    val coordinates: PointCoordinates,       // las actuales: las de la medida, las escritas o las editadas
    val measurement: Measurement?,           // solo MEASURED
    val typed: TypedCoordinates?,            // MANUAL, o MEASURED con coordenadas editadas
    val edited: Boolean,                     // MEASURED con coordenadas distintas de las de su medida (S011/RF-12)
    val hidden: Boolean,                     // S011/RF-4
    val copyOf: String?,                     // nombre del original al duplicar (S011/RF-21)
    val createdAtMs: Long,
    val deletedAtMs: Long?,                  // en la papelera desde entonces (S011/RF-22)
) { val hasHeight: Boolean get() = coordinates.ground.ellipsoidalHeightM != null }

/** Lo que cambia de un punto, para el historial (S011/RF-10). */
enum class PointChange { EDIT, POLE, REVERT }
data class PointSnapshot(
    val name: String, val description: String, val poleHeightM: Double?,
    val coordinates: PointCoordinates, val measuredCoordinates: PointCoordinates?,
    val typed: TypedCoordinates?, val edited: Boolean,
)
data class PointHistoryEntry(val id: String, val pointId: String, val changedAtMs: Long, val change: PointChange, val previous: PointSnapshot)

/** Lo que necesitan la lista y el mapa, sin las frases NMEA. */
data class PointSummary(
    val id: String, val name: String, val description: String, val origin: PointOrigin,
    val latitudeDeg: Double, val longitudeDeg: Double, val hasHeight: Boolean,
    val outOfTolerance: Boolean, val edited: Boolean, val hidden: Boolean, val createdAtMs: Long,
)
```
- `CollectedPoint` desaparece: el recolector crea un `Point` con `origin = MEASURED`, `measurement` y `coordinates = measurement.coordinates`.
- Ejemplo: un punto medido en Sol (S010, punto 1) y editado a mano:
  - `coordinates = (40,41668°, −3,70371°, 699,423 m; E 440 300,000, N 4 474 244,001, H 648,311)`, las editadas;
  - `measurement.coordinates`: las de la medida (E 440 297,988);
  - `typed = (PROJECT_CRS, 440300.0, 4474244.001, 648.311, PROJECT)`, `edited = true`.

```sql
-- data, Room v6 (migración automática 5→6 con un AutoMigrationSpec)
ALTER TABLE points: antenna_*, pole_height, solution, mode, raw_nmea, measured_from, measured_to, ... pasan a admitir NULL (puntos manuales)
  + origin TEXT NOT NULL DEFAULT 'MEASURED'
  + measured_ground_lat REAL, measured_ground_lon REAL, measured_ground_h REAL,
    measured_easting REAL, measured_northing REAL, measured_height REAL      -- la medida; NULL en los manuales
  + typed_form TEXT, typed_first REAL, typed_second REAL, typed_height REAL, typed_height_kind TEXT
  + edited INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, copy_of TEXT,
  + deleted_at INTEGER,                         -- papelera
  + trash_key TEXT NOT NULL DEFAULT ''          -- '' si está activo; su id si está en la papelera
DROP INDEX index_points_project_id_name;
CREATE UNIQUE INDEX index_points_project_id_name_trash_key ON points(project_id, name, trash_key);   -- S010/RF-3 y S011/RF-25
-- onPostMigrate: UPDATE points SET measured_ground_lat = ground_lat, ..., measured_height = height;

CREATE TABLE point_history (
  id TEXT PRIMARY KEY, point_id TEXT NOT NULL REFERENCES points(id) ON DELETE CASCADE,
  changed_at INTEGER NOT NULL, change TEXT NOT NULL,
  name TEXT NOT NULL, description TEXT NOT NULL, pole_height REAL,
  ground_lat REAL NOT NULL, ground_lon REAL NOT NULL, ground_h REAL, easting REAL, northing REAL, height REAL,
  measured_ground_lat REAL, measured_ground_lon REAL, measured_ground_h REAL,
  measured_easting REAL, measured_northing REAL, measured_height REAL,
  typed_form TEXT, typed_first REAL, typed_second REAL, typed_height REAL, typed_height_kind TEXT,
  edited INTEGER NOT NULL);
CREATE INDEX index_point_history_point_id ON point_history(point_id);
```
- Las columnas principales (`ground_*`, `easting`, `northing`, `height`) son siempre las coordenadas actuales: el mapa, la lista y las exportaciones (S012) las leen tal cual.
- Cada fila de `point_history` es la versión anterior de un punto (RNF-T6: "las correcciones crean una versión nueva con referencia a la anterior"). La observación bruta (`antenna_*`, `raw_nmea`) no se escribe nunca después de crear el punto.
- **`PointRepository`.** Cada operación es una transacción, y las que cambian un punto guardan antes su versión anterior en el historial:
  - las de S010, que solo cuentan los puntos activos: `create`, `count`, `lastName` y `nameExists`;
  - lectura: `summaries(projectId)`, `point(id)`, `history(id)` y `trash(projectId)`;
  - cambios: `update(point, change)`, `updateAll(points, change)` (todo o nada) y `setHidden(ids, hidden)`;
  - papelera: `moveToTrash(ids)`, `restore(id, newName)`, `deletePermanently(id)` y `purgeTrash()`.

## Algoritmos
### UTM inversa (RF-17, RF-20)
`TransverseMercator.inverse(E, N)` usa la serie de Krüger de orden 6 en n (Karney 2011, ec. 11 a 19), la misma familia que `forward`:
1. `ξ' = (N − FN) / (k0·A)`, `η' = (E − FE) / (k0·A)`.
2. `ξ = ξ' − Σ βj·sin(2jξ')·cosh(2jη')` y `η = η' − Σ βj·cos(2jξ')·sinh(2jη')`, para j = 1…6.
3. `χ = asin(sin ξ / cosh η)` y `λ = λ0 + atan(sinh η / cos ξ)`.
4. φ desde la latitud conforme χ por iteración de Newton sobre `τ = tan φ`, hasta |Δτ| < 1e-12 (como mucho 5 pasos).
- La ida y vuelta `forward(inverse(E, N))` difiere menos de 0,1 mm en los husos 27 a 31 (constitución nº 2).

### Punto manual (RF-16 a RF-20)
`ManualCoordinates.compute(typed, crs, heightSystem)` devuelve `PointCoordinates` o el motivo de rechazo:
1. **φ, λ del datum del proyecto:**
   - en `PROJECT_CRS` con UTM, por `Utm.inverse` en el huso y el elipsoide del proyecto (GRS80 o Internacional 1924);
   - en `GEOGRAPHIC`, o en un SC geográfico, son los escritos.
2. **Datum de los cálculos:** en ED50, `Ed50.toEtrs89` (rejilla SPED2ETV2, en su sentido directo); fuera de la rejilla → `OUTSIDE_ED50_GRID`. En los demás, los mismos.
3. **Ámbito:** fuera de `crs.area` → `OUTSIDE_CRS_AREA` (RF-19).
4. **Altura elipsoidal:**
   - vacía → sin altura (RF-18);
   - `ELLIPSOIDAL`, o un proyecto con altura elipsoidal → la escrita;
   - una altitud → H + N con el geoide del ámbito (el de S008/RF-4); fuera del geoide → `OUTSIDE_GEOID`.
5. **Coordenadas del proyecto:** con `ProjectPositionCalculator.fromGround`. En `PROJECT_CRS` se guardan E, N y H tal como se escribieron, sin la ida y vuelta (RF-20).

`ProjectPositionCalculator.fromGround(ground, crs, heightSystem)` es la parte de `compute` que va del punto del suelo al SC del proyecto. `compute(fix, pole, …)` pasa a llamarla después de restar el jalón, sin cambiar sus resultados (los tests de S008 siguen igual).

### Corregir el jalón (RF-14, RF-15)
`PointCorrections.withPoleHeight(point, pole, crs, heightSystem)` solo se aplica a puntos medidos:
- h del suelo = h de la antena − (jalón + 0,134 m); φ y λ son las de la antena (jalón vertical, S002/RF-32);
- `measurement.coordinates = fromGround(...)` y `measurement.poleHeightM = pole`;
- si el punto no está editado, `coordinates = measurement.coordinates`; si lo está, se conservan las editadas y se avisa.

Para una selección, se calculan todos los puntos en memoria y se guardan con `updateAll`, en una sola transacción. Los manuales se cuentan como "sin cambiar".

### Volver a la medida y duplicar (RF-13, RF-21)
- `revertToMeasurement(point)` deja `coordinates = measurement.coordinates`, `typed = null` y `edited = false`.
- `duplicate(point, name, now)` crea un punto con:
  - id nuevo, `origin = MANUAL`, las mismas `coordinates` y descripción;
  - `typed` igual al del original o, si no tiene, las coordenadas del proyecto escritas como `PROJECT_CRS`;
  - `copyOf = point.name`, sin `measurement`, sin historial y visible.

### Nombres (RF-16, RF-21, RF-25)
`PointNames.nextFree(last, taken)` aplica `next` (S010/RF-2) hasta dar con un nombre que no esté en `taken`. `last` es el último punto activo del proyecto, y `taken`, los nombres de sus puntos activos.

### Papelera (RF-22 a RF-26)
- **Mover a la papelera:** `deleted_at = ahora` y `trash_key = id`. El nombre queda libre por el índice `(project_id, name, trash_key)`.
- **Restaurar:** `deleted_at = NULL` y `trash_key = ''`, con el nombre nuevo si se dio. Si choca con otro punto activo, el índice lo rechaza → `DuplicateName`, y la interfaz pide otro nombre (RF-25).
- **Días que quedan:** `30 − días desde deleted_at`, como en S006.
- **Purga:** al abrir la app, la misma `TrashPurge` de S006 llama también a `PointRepository.purgeTrash()`: borra los puntos con `deleted_at < ahora − 30 días` (el historial se va en cascada).

### Búsqueda (RF-2)
`PointSearch.matches(summary, query)` normaliza nombre, descripción y texto con NFD, sin marcas diacríticas y en minúsculas de `Locale.ROOT`, y comprueba que el texto esté contenido. La lista filtra en `Dispatchers.Default` sobre los resúmenes ya cargados.

### Lectura de lo escrito (RF-8, RF-17)
- `NumberInput.parse(text)` acepta coma o punto decimal. Si aparecen los dos, el último es el decimal y el otro, separador de miles. Un mismo signo repetido ("4.474.244") se toma como miles.
- Las longitudes se pasan de la unidad lineal del proyecto a metros.
- `AngleInput.parse(text, unit)` lee DD, DMS ("40 25 0,48", "40°25'0,48\"") o gon según la unidad angular del proyecto:
  - un signo menos, o las letras S, W u O, dan valores negativos;
  - fuera de ±90° en latitud o ±180° en longitud, o con minutos o segundos ≥ 60, el valor no es válido.

## Interfaz
- **Botón "Objetos":** un `FilledTonalIconButton` en la columna de la derecha del mapa, bajo "Capas" y "Centrar" (la barra superior ya va llena, S009).
- **Lista de objetos:**
  - buscador arriba; filas con nombre, descripción, origen ("Medido" o "Manual") y las marcas con icono y texto: "Fuera de tolerancia", "Editado", "Sin altura";
  - un botón de ojo por fila (RF-4);
  - una pulsación larga entra en selección múltiple, con una barra de acciones: "Ocultar", "Mostrar", "Altura del jalón" y "Borrar";
  - botón flotante "Añadir punto" y menú con "Papelera";
  - sin puntos: "Todavía no hay puntos" y el botón de añadir.
- **Ficha del punto:**
  - coordenadas actuales en el SC del proyecto y en geográficas;
  - si está editado, también las de la medida, con "Volver a la medida";
  - en los medidos, una tarjeta con el jalón, la antena, la calidad, la fecha y hora y el control de precisión, y "Ver frases NMEA" en un diálogo con texto monoespaciado;
  - en una copia, "Copia de P12";
  - acciones "Editar", "Duplicar", "Altura del jalón" (solo medidos), "Historial" y "Borrar".
- **Formulario de punto** (nuevo o editado):
  - nombre y descripción;
  - un selector "SC del proyecto" / "Geográficas" (oculto en los SC geográficos);
  - dos campos en la unidad del proyecto, con su símbolo;
  - la altura, opcional, con el selector "Altitud" / "Elipsoidal" en geográficas (salvo ED50 o un proyecto con altura elipsoidal);
  - el motivo de error bajo el campo y "Guardar" deshabilitado mientras haya uno.
- **Historial:** una lista de cambios con la fecha en hora local y lo que cambió, comparando cada versión con la siguiente (por ejemplo "Jalón: 1,800 → 2,000 m" o "Coordenadas editadas").
- **Papelera de puntos:** como la de proyectos (S006), con los días que quedan, "Restaurar" y "Borrar definitivamente". Si el nombre está ocupado, un diálogo pide otro.
- **Toque en el mapa:** `MapController.pointClicks: Flow<String>`. MapLibre busca en un cuadro de 48 dp alrededor del toque, en las capas de puntos y nombres; los puntos ocultos no están en la fuente. El toque abre la ficha (RF-5).
- **Navegación:** rutas tipadas nuevas en el grafo de Levantamiento (ADR-19): `ObjectListDestination(projectId)`, `PointDestination(projectId, pointId)`, `PointFormDestination(projectId, pointId?)` y `PointTrashDestination(projectId)`.

Textos nuevos (es):

| Caso | Texto |
|---|---|
| Lista | "Objetos", "Buscar punto", "Medido", "Manual", "Fuera de tolerancia", "Editado", "Sin altura", "Ocultar", "Mostrar", "Añadir punto", "Papelera", "%1$d seleccionados" (plurales) |
| Ficha | "Ficha del punto", "Coordenadas", "Coordenadas de la medida", "Volver a la medida", "Medida", "Ver frases NMEA", "Copia de %1$s", "Editar", "Duplicar", "Historial", "Borrar" |
| Formulario | "Nuevo punto", "Editar punto", "SC del proyecto", "Geográficas", "Latitud", "Longitud", "Altura (opcional)", "Altitud", "Elipsoidal", "Guardar", "Valor no válido." |
| Jalón | "Altura del jalón", "%1$d puntos manuales no tienen jalón y no han cambiado." (plurales), "Las coordenadas editadas se mantienen; se ha recalculado la medida." |
| Papelera | "Papelera de puntos", "Quedan %1$d días" (plurales), "Restaurar", "Borrar definitivamente", "¿Mover %1$d puntos a la papelera?" (plurales), "Ya hay un punto con ese nombre: escribe otro para restaurarlo." |
| Historial | "Nombre", "Descripción", "Jalón", "Coordenadas editadas", "De vuelta a la medida" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| `Point` con la medida opcional sustituye a `CollectedPoint` | Un solo tipo para medidos y manuales, con el nombre del glosario | Dos tablas o dos tipos (la lista y el mapa los tendrían que juntar) |
| Columnas principales = coordenadas actuales, con la medida aparte en `measured_*` | Lectores simples (mapa, lista, exportar) y la medida a mano para "Volver a la medida" | Columnas principales = medida y `edited_*` opcionales (cada lector tendría que elegir) |
| Historial como versiones anteriores completas | Cumple RNF-T6 tal cual y permite mostrar cualquier diferencia | Guardar solo el campo cambiado (más tipos de fila y difícil de recomponer) |
| Nombre único con `(project_id, name, trash_key)` | Libera el nombre en la papelera y la regla de S010 sigue en la base de datos | Índice parcial `WHERE deleted_at IS NULL` (Room no lo genera); comprobar solo en el repositorio |
| Migración automática con `AutoMigrationSpec` | Room rehace la tabla al cambiar la nulabilidad y el spec copia la medida | Migración escrita a mano (más SQL que mantener) |
| UTM inversa propia con la serie de Krüger | ADR-2; la misma precisión que `forward` | Inversa por iteración de `forward` (más lenta y sin referencia publicada) |
| Resúmenes sin NMEA para la lista y el mapa | 10 000 puntos en menos de 1 s sin cargar las frases | Leer los puntos completos |
| Botón "Objetos" en la columna del mapa | La barra superior no tiene sitio en móviles estrechos (S009) | Un icono más en la barra superior |

## Estrategia de tests
- **`tools`:** `puntos_manuales.csv` y `jalon_corregido.csv` generados con pyproj, con su test de regeneración.
- **JVM `core:geodesy`:** `Utm.inverse` frente a `utm_etrs89.csv`, `utm_regcan95.csv` y `ed50_utm.csv` al revés (≤ 1e-9°), e ida y vuelta (≤ 0,1 mm) en los husos 27 a 31.
- **JVM `core:model`:** `PointNames.nextFree`, `PointSearch` (mayúsculas y acentos), `NumberInput` y `AngleInput` en todos los formatos y casos inválidos.
- **JVM `core:survey`:**
  - `ManualCoordinates` frente a `puntos_manuales.csv`: proyectadas y geográficas en ETRS89, REGCAN95 y ED50, altitud y elipsoidal, sin altura y fuera de ámbito, de rejilla o de geoide;
  - `fromGround` igual a `compute` con los vectores de S008;
  - `PointCorrections` frente a `jalon_corregido.csv`, con un punto editado, volver a la medida y duplicar.
- **Robolectric `data`:**
  - migración 5→6 con un punto de S010: conserva todo, copia la medida y deja el punto activo y visible;
  - repositorio: punto manual sin altura, edición con historial, `updateAll` todo o nada, visibilidad, papelera con el nombre libre, restaurar con nombre ocupado, borrar definitivamente y purga a 30 días;
  - recuento y último nombre sin la papelera.
- **Robolectric `app`:**
  - ViewModels de lista (búsqueda, selección, ocultar, jalón, borrar), ficha, formulario y papelera;
  - pantallas: marcas con texto, formulario con errores y selector de altura según el SC, historial, diálogo de nombre ocupado;
  - el toque en el mapa abre la ficha (con `FakeMapController`) y los puntos ocultos no van al mapa.
- **Rendimiento:** 10 000 resúmenes leídos y filtrados en menos de 1 s en la JVM.
- **Validación:** demo en el emulador.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-4 | Lista, búsqueda, marcas y visibilidad | `ObjectListViewModelTest`, `ObjectListScreenTest`, `PointSearchTest`, `PointRepositoryTest` |
| RF-5 | Toque en el mapa y en la lista | `ObjectListScreenTest`, `PointsLayerTest` |
| RF-6, RF-7 | Ficha del punto | `PointDetailsScreenTest` |
| RF-8, RF-9 | Formulario en edición | `PointFormViewModelTest`, `PointRepositoryTest` |
| RF-10, RF-11 | Historial | `PointRepositoryTest`, `PointHistoryTest` |
| RF-12, RF-13 | Editado y volver a la medida | `PointCorrectionsTest`, `PointDetailsScreenTest` |
| RF-14, RF-15 | Corregir el jalón | `PointCorrectionsTest`, `ObjectListViewModelTest`, `PointRepositoryTest` |
| RF-16 a RF-20 | Punto manual | `UtmInverseTest`, `ManualCoordinatesTest`, `NumberInputTest`, `AngleInputTest`, `PointFormViewModelTest` |
| RF-21 | Duplicar | `PointCorrectionsTest`, `PointNamesTest`, `PointDetailsViewModelTest` |
| RF-22 a RF-26 | Papelera | `PointRepositoryTest`, `PointTrashViewModelTest`, `TrashPurgeTest` |
