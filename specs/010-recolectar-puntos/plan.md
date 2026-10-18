# Plan técnico — Spec 010

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-9, ADR-11), `specs/010-recolectar-puntos/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `core:gnss` | `GstAccuracy.rawSentence`: la frase GST tal como llegó, como ya tiene la GGA | RF-17 |
| `core:model` | `PointNames` (nombre siguiente), `CollectedPoint` y sus metadatos, `PrecisionControl` (ajustes y comprobación) y `CollectorSettings` | RF-2, RF-3, RF-13, RF-14, RF-18 |
| `core:survey` | `PointMeasurement`: medida instantánea o promediada a partir de épocas, con las épocas sin FIX descartadas, la media y el punto del suelo en el SC del proyecto | RF-8 a RF-10, RF-17, RF-18 |
| `receiver` | `ReceiverSession.epochs`: todas las épocas completas, sin perder ninguna; `Alerts.pointSaved()` y `Alerts.solutionLost()` | RF-9, RF-10, RF-19 |
| `data` | Room v5: tabla `points` (migración 4→5 probada) y `PointRepository`; `CollectorPreferences` en DataStore | RF-3, RF-13, RF-16, RF-21 |
| `app` | `CollectorViewModel`; panel del recolector y botón "Recolectar"; ajustes del control de precisión y confirmación de fuera de tolerancia; puntos en el mapa y número de puntos en los datos del proyecto | RF-1 a RF-21 |

Sin dependencias nuevas.

## Modelo de datos
```kotlin
// core:model
enum class MeasurementMode { INSTANT, AVERAGED }
data class PrecisionControl(val fixOnly: Boolean = true, val horizontalLimitM: Double? = null, val verticalLimitM: Double? = null)
enum class PrecisionFailure { NOT_FIX, HORIZONTAL, VERTICAL, NO_ACCURACY }
data class CollectorSettings(
    val mode: MeasurementMode = MeasurementMode.INSTANT,
    val averagingSeconds: Int = 5,          // 1 a 60 (S010/RF-9)
    val precision: PrecisionControl = PrecisionControl(),
)
object PointNames { fun next(last: String?): String }   // "P009" → "P010", "arqueta" → "arqueta1", null → "1"

data class CollectedPoint(
    val id: String, val projectId: String, val name: String, val description: String,
    val antenna: GeodeticPosition,           // φ, λ, h de la antena tal como los da el receptor (media si es promediado)
    val poleHeightM: Double,
    val ground: GeodeticPosition,            // φ, λ, h elipsoidal del punto del suelo
    val easting: Double?, val northing: Double?, val height: Double?,   // en el SC y la altura del proyecto
    val quality: PointQuality, val rawNmea: String,
    val outOfTolerance: Set<PrecisionFailure>,   // vacío si cumple
    val measuredFromMs: Long, val measuredToMs: Long, val createdAtMs: Long,
)
data class PointQuality(
    val solution: SolutionStatus, val satellites: Int?, val hdop: Double?, val correctionAgeS: Double?,
    val sigmaHorizontalM: Double?, val sigmaVerticalM: Double?,
    val mode: MeasurementMode, val epochsUsed: Int, val epochsDiscarded: Int, val fromReplay: Boolean,
)
```
- `core:model` no conoce `SolutionStatus` de `core:gnss`: `PointQuality.solution` se guarda por su nombre (`String`) y se convierte en `core:survey`.
- Las coordenadas son `Double` sin redondear (AGENTS.md). Las del SC del proyecto se guardan para exportar sin recalcular. Las brutas (antena, jalón, NMEA) permiten recalcularlas en S011.

```sql
-- data, Room v5
CREATE TABLE points (
  id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name TEXT NOT NULL, description TEXT NOT NULL,
  antenna_lat REAL, antenna_lon REAL, antenna_h REAL, pole_height REAL,
  ground_lat REAL, ground_lon REAL, ground_h REAL, easting REAL, northing REAL, height REAL,
  solution TEXT, satellites INTEGER, hdop REAL, correction_age REAL, sigma_h REAL, sigma_v REAL,
  mode TEXT, epochs_used INTEGER, epochs_discarded INTEGER, from_replay INTEGER,
  out_of_tolerance TEXT,      -- condiciones incumplidas separadas por comas; vacío si cumple
  raw_nmea TEXT, measured_from INTEGER, measured_to INTEGER, created_at INTEGER);
CREATE UNIQUE INDEX index_points_project_id_name ON points(project_id, name);   -- S010/RF-3
```
- Un punto, una inserción en una transacción, con WAL y `synchronous=FULL` como el resto de la base de datos (RNF-T6). El índice único impide duplicar el nombre aunque se pase la comprobación de la interfaz.
- `PointRepository`: `create(point)`, `points(projectId): Flow<List<CollectedPoint>>`, `count(projectId): Flow<Int>`, `lastName(projectId)`, `nameExists(projectId, name)`.

## Algoritmos
### Medida (RF-8 a RF-10)
`PointMeasurement` es una máquina de estados pura en `core:survey` que recibe épocas una a una:
- **Instantánea:** la primera época con posición que llega tras pulsar "Medir". Si en 2 s no llega ninguna, termina con error "sin datos".
- **Promediada:**
  - Cada época cuenta el intervalo desde la anterior, como mucho 1 s.
  - Con el control de solo FIX activo, una época sin FIX se descarta y avisa una vez de "Solución perdida" hasta que vuelve el FIX. Sin el control, cuentan todas.
  - Termina cuando las épocas usadas suman el tiempo pedido.
- **Media:** media aritmética de φ, λ y h de la antena de las épocas usadas. Con dispersiones de centímetros coincide con la media en ECEF a menos de 1 µm.
  - Del resto de datos: la precisión de la GST y la edad de las correcciones son las de la última época; los satélites, el mínimo de las usadas.
  - La solución es la peor de las usadas.
- **Punto del suelo:** con `ProjectPositionCalculator` (S008) sobre una GGA con la posición media y la altura del jalón del momento.
- **Fuera del ámbito:** si cae fuera del ámbito del SC (o de la rejilla ED50), la medida no termina en punto (RF-12).

### Control de precisión (RF-13, RF-14)
`PrecisionControl.check(solution, sigmaH, sigmaV)` devuelve las condiciones incumplidas:
- `NOT_FIX` con solo FIX y una solución distinta de FIX;
- `HORIZONTAL` o `VERTICAL` si se pasa del límite;
- `NO_ACCURACY` con un límite activo y sin GST.

La precisión horizontal es la de S002/RF-14, √(σφ² + σλ²); la vertical es σh.

### Nombre siguiente (RF-2)
Se separa el número final (con sus ceros) y se suma uno conservando el ancho: "P009" → "P010", "P99" → "P100", "12" → "13". Sin número final, se añade "1". Sin puntos, "1".

## Interfaz
- **Botón "Recolectar":** extendido, de 56 dp, abajo a la derecha encima del panel de posición.
- **Panel del recolector:** sustituye al panel de posición, que ya muestra lo mismo, mientras está abierto. No es modal: el mapa sigue a la vista y se puede mover. Contiene:
  - nombre y descripción;
  - altura del jalón (la de `ReceiverPreferences`, de 0 a 10 m);
  - modo instantáneo o promediado, con los segundos;
  - resumen del control de precisión con "Ajustar";
  - solución, precisión H y precisión V;
  - el botón "Medir", que durante un promediado pasa a ser el progreso ("3 / 5 s") con "Cancelar";
  - los avisos: nombre repetido, por qué no se puede medir y "Solución perdida".
- **Pantalla encendida:** `View.keepScreenOn` mientras el panel está abierto (RF-7).
- **Fuera de tolerancia:** un diálogo con las condiciones incumplidas y dos botones, "Guardar fuera de tolerancia" y "Descartar".
- **Ajustes del control de precisión:** un diálogo con el interruptor "Solo FIX" y los dos límites, cada uno con su interruptor y su valor en la unidad lineal del proyecto (se guardan en metros).
- **Puntos en el mapa:**
  - una fuente GeoJSON con un círculo azul por punto (los fuera de tolerancia, naranjas con borde discontinuo) y el nombre en una capa de texto;
  - el texto usa "Noto Sans Regular", que sirven tanto el IGN como OpenFreeMap;
  - los estilos locales (PNOA y Ninguno) usan los glifos del IGN;
  - el nombre se ve desde el zoom de 1:2.000 en la latitud de los puntos;
  - los puntos quedan encima del Catastro y debajo del rover.
- **Datos del proyecto:** "N puntos" en lugar de "Todavía no hay puntos" en cuanto hay uno.

Textos nuevos (es):

| Caso | Texto |
|---|---|
| Botón y panel | "Recolectar", "Recolector", "Nombre", "Descripción", "Medir", "Cancelar", "Cerrar" |
| Modos | "Instantáneo", "Promediado", "Segundos" |
| Avisos | "Ya hay un punto con ese nombre.", "Sin posición del receptor.", "Fuera del ámbito del sistema de coordenadas del proyecto.", "Solución perdida: no cuentan las épocas sin FIX.", "Sin datos del receptor: la medida se ha cancelado." |
| Control de precisión | "Control de precisión", "Solo FIX", "Límite horizontal", "Límite vertical", "Ajustar" |
| Fuera de tolerancia | "La medida no cumple el control de precisión:", "No es FIX", "Precisión horizontal mayor que el límite", "Precisión vertical mayor que el límite", "Sin precisión del receptor", "Guardar fuera de tolerancia", "Descartar" |
| Guardado | "Punto %1$s guardado." |
| Datos del proyecto | "%1$d puntos" (plurales) |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Flujo de épocas propio en `ReceiverSession` | El `StateFlow` del estado conserva solo la última: a 10 Hz podría saltarse épocas del promediado | Promediar desde `session.state` |
| Media aritmética de φ, λ, h | Exacta para dispersiones de centímetros y fácil de comprobar | Media en ECEF (misma cifra, más código) |
| Coordenadas del proyecto guardadas con el punto | Exportar (S012) sin recalcular y ver lo que se vio en campo | Calcularlas siempre al leer |
| Nombre único con índice en la base de datos | La regla se cumple aunque falle la interfaz | Solo comprobación en la interfaz |
| Panel en lugar de una hoja modal | El mapa sigue manejable mientras se mide | `ModalBottomSheet` (tapa el mapa y bloquea los gestos) |
| Glifos del IGN en los estilos locales | La misma fuente en todos los mapas base y en la caché | Etiquetas dibujadas en Compose (más código con muchos puntos) |

## Estrategia de tests
- **JVM `core:gnss`:** la GST conserva su frase.
- **JVM `core:model`:** nombre siguiente (ceros, desbordamiento a más cifras, sin número, sin puntos); comprobación del control de precisión en cada combinación.
- **JVM `core:survey`:**
  - instantánea, y sin datos en 2 s;
  - promediado a 1 y 10 Hz;
  - pérdida de FIX (épocas descartadas, aviso una vez, termina igual);
  - sin el control de solo FIX;
  - cancelar;
  - media frente a una referencia calculada aparte (≤ 0,1 mm);
  - punto del suelo y SC del proyecto, igual que S008;
  - fuera del ámbito.
- **JVM `receiver`:** el flujo de épocas entrega todas las de una reproducción a 10 Hz.
- **Robolectric `data`:**
  - migración 4→5 con un proyecto de la v4;
  - repositorio: guardado y lectura sin redondeo, nombre repetido rechazado, último nombre y número de puntos;
  - borrado en cascada al vaciar la papelera;
  - preferencias del recolector.
- **Robolectric `app`:**
  - `CollectorViewModel` con una sesión de reproducción (nombre propuesto, medir, guardar en < 1 s, fuera de tolerancia confirmado y descartado, cancelar, avisos y sonidos con un doble de `Alerts`);
  - panel (campos, pantalla encendida, botón deshabilitado y motivo);
  - diálogos;
  - puntos al `FakeMapController`;
  - número de puntos en los datos del proyecto.
- **Rendimiento:** 10 000 puntos leídos y convertidos para el mapa en menos de 2 s en la JVM.
- **Validación:** demo en el emulador con reproducciones.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-4 a RF-7 | Panel del recolector | `CollectorPanelTest` |
| RF-2, RF-3 | Nombres | `PointNamesTest`, `PointRepositoryTest`, `CollectorViewModelTest` |
| RF-8 a RF-12 | Medida | `PointMeasurementTest`, `CollectorViewModelTest`, `ReceiverSessionEpochsTest` |
| RF-13 a RF-15 | Control de precisión | `PrecisionControlTest`, `CollectorDialogsTest`, `CollectorPreferencesTest` |
| RF-16 a RF-19 | Guardado y metadatos | `PointRepositoryTest`, `TopoPhoneDatabaseTest`, `CollectorViewModelTest` |
| RF-20, RF-21 | Puntos en el mapa y en los datos | `PointsLayerTest`, `ProjectInfoTest` |
