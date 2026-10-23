# Plan técnico — Spec 017

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/017-recolectar-avanzado/spec.md`. Sin código.

## Estructura de módulos
- `core:model`:
  - `CollectorSettings` gana `autoTrigger`, `autoIntervalS` y `autoDistanceM`, y `AVERAGING_SECONDS` pasa a `1..3600` (RF-1, RF-16, RF-17);
  - `DurationText`: escribir y leer un tiempo como `m:ss` (RF-1, RF-16);
  - `PointQuality` gana `averagedTimeS`, `deviationHorizontalM`, `deviationVerticalM` y `auto` (RF-12, RF-14);
  - `Measurement.nmeaChunks`: cuántos tramos de frases NMEA tiene el punto fuera de su fila (A6).
- `core:survey`:
  - `AverageStats`: media, desviación típica, estabilidad y muestra del gráfico, en una sola pasada (A2; RF-3 a RF-5, RF-12);
  - `PointMeasurement` sin retener épocas, con pausa, reanudación, "Terminar" y cambio de base con el tiempo descartado (A1, A3, A4; RF-1, RF-6 a RF-10, RF-12, RF-30);
  - `AutoMeasure`: el modo automático como máquina de estados pura (A5; RF-15 a RF-28, RF-32);
  - `MeasurementResult` gana el tiempo promediado y las desviaciones típicas (RF-12).
- `core:formats` → cuatro `ExportColumn` nuevas tras las de la inclinación (RF-14).
- `data`:
  - columnas nuevas de `points`, tabla `point_nmea`, migración 8 → 9 y `forExport` con ellas (A6; RF-12, RF-14);
  - `PointRepository.nmeaChunk` y guardado del punto con sus tramos en una transacción (A6; RF-12);
  - `DatabaseSnapshot` copia también `point_nmea` (S013);
  - `CollectorPreferences` con las claves del modo automático (RF-1, RF-16, RF-17).
- `receiver` → `Alerts.measurementPaused()`, `Alerts.pointOmitted()` y `Alerts.measurementRestarted()` (RF-8, RF-24, RF-30).
- `app`:
  - `CollectorViewModel`: "Terminar", pausa, pregunta de 5 min, bloqueo de los ajustes y cambio de modo de inclinación en pleno modo automático (RF-6 a RF-11, RF-29, RF-30, RF-32);
  - `AutoCollector`: orquesta `AutoMeasure` con el recolector (nombres, control de precisión, guardado, log, avisos; RF-15 a RF-28, RF-31, RF-33);
  - panel del recolector: tiempo en `m:ss`, "Automático", estadísticas con el gráfico de dispersión, banner de pausa y pregunta de 5 min (RF-1 a RF-5, RF-8, RF-10, RF-15 a RF-17, RF-26);
  - ficha del punto: tiempo promediado, desviaciones típicas y "medido en automático"; "Ver frases NMEA" por tramos (RF-13, RNF);
  - nombres de las columnas nuevas en Exportar (RF-14).
- `tools`:
  - `tools/promediado/generar_promediado.py`: 36.000 épocas simuladas con la media, las desviaciones típicas y la estabilidad esperadas, calculadas con pyproj y `math.fsum` (constitución nº 2);
  - `tools/nmea/generar_avanzado.py`: reproducciones `rs3-promediado-largo`, `rs3-corte` y `rs3-traza`, con lo esperado de cada una.

## Modelo de datos

### Ajustes del recolector (DataStore `"collector"`)
```
CollectorSettings(
  mode, averagingSeconds (1..3600), precision,          // ya existen
  autoTrigger: AutoTrigger { TIME, DISTANCE },          // TIME por defecto
  autoIntervalS: Int (1..3600), autoDistanceM: Double (0,1..1000),
)
```
- Claves nuevas `collector_auto_trigger` (texto), `collector_auto_interval_s` (int, 5 por defecto) y `collector_auto_distance_m` (double, 1,0 por defecto). Viajan en las copias, como todo el DataStore `"collector"` (S013, `SettingsArchive.PORTABLE`).
- Que el modo automático esté encendido **no** se guarda: el recolector siempre abre en manual, para no empezar a guardar puntos sin querer.
- Los rangos se guardan siempre en metros y segundos; la interfaz convierte a la unidad lineal del proyecto (RF-17).

### En el punto (Room v9)
`PointQuality` gana `averagedTimeS: Double?`, `deviationHorizontalM: Double?`, `deviationVerticalM: Double?` y `auto: Boolean?`. Columnas nuevas de `points`:

| Columna | Tipo | Ejemplo (promediado de 12 min, a mano) |
|---|---|---|
| `avg_time` | REAL | 720.0 (s) |
| `dev_h` | REAL | 0.0042 (m) |
| `dev_v` | REAL | 0.0068 (m) |
| `auto_mode` | INTEGER | 0 |
| `nmea_chunks` | INTEGER NOT NULL DEFAULT 0 | 6 |

- Los puntos anteriores y los manuales quedan con `avg_time`, `dev_h`, `dev_v` y `auto_mode` a NULL (RNF-T6): la ficha no los muestra y el CSV los deja vacíos. Los medidos nuevos guardan `auto_mode` 0 o 1, aunque no se promedien; `avg_time` y las desviaciones solo los promediados (con menos de 2 épocas, `dev_*` vacías).
- `AutoMigration(from = 8, to = 9)` sin especificación: solo hay columnas opcionales, una con valor por defecto, y una tabla nueva. Hay que exportar el esquema `9.json` y añadir una línea al registro de versiones.
- `forExport` lleva las cuatro columnas nuevas y sigue sin leer las frases NMEA.

### Frases NMEA de un promediado largo (RF-12, RNF)
Un promediado de 60 min a 10 Hz son unos 12 MB de frases por punto. Hoy van enteras en `points.raw_nmea`, y las consultas `SELECT *` (la ficha, la papelera y las copias de S013) las leen con un cursor de Android, cuya ventana es de unos 2 MB por fila: una fila de 12 MB falla en el móvil, aunque pase en Robolectric.
```
point_nmea(point_id TEXT, seq INTEGER, text TEXT NOT NULL,
           PRIMARY KEY (point_id, seq),
           FOREIGN KEY (point_id) REFERENCES points(id) ON DELETE CASCADE)
```
- Hasta 256.000 caracteres, las frases siguen en `points.raw_nmea` y `nmea_chunks` es 0: nada cambia para los puntos actuales ni para los promediados de hasta unos 15 min a 1 Hz.
- Por encima, `raw_nmea` queda vacía, `nmea_chunks` es el número de tramos y `point_nmea` tiene un tramo por fila de hasta 256.000 caracteres, partido siempre en un salto de línea. El punto y sus tramos se guardan en una sola transacción (RNF-T6).
- `Measurement.rawNmea` es "" en esos puntos y `Measurement.nmeaChunks` dice cuántos tramos hay. Los tramos se leen de uno en uno con `PointRepository.nmeaChunk(pointId, seq)`.
- Borrar un punto definitivamente borra sus tramos (cascada). La papelera, restaurar y la copia de seguridad los llevan sin cambios; duplicar no copia frases (S011/RF-21).
- `DatabaseSnapshot.TABLES` añade `Table("point_nmea", "WHERE point_id IN (SELECT id FROM points WHERE project_id = ?)")` justo después de `points`; las copias y los paquetes siguen copiando con `SELECT *`, y cada fila cabe en la ventana.

## Algoritmos

### A1. Medida en una sola pasada (RF-1, RF-12)
`PointMeasurement` deja de guardar la lista de épocas usadas: 36.000 épocas con sus frases, sus objetos GGA, GST y ETC y sus cadenas serían decenas de MB vivos durante 60 min. En su lugar acumula, por cada época usada:
1. El número de épocas `n` y las sumas de φ, λ, h y separación del geoide respecto a la primera época usada (las diferencias son de centímetros y no pierden precisión); la media es la primera más la suma entre `n`. La altitud o la separación pasan a null si a alguna época le falta (como hoy).
2. De la última época: HDOP y edad de las correcciones; de todas, el mínimo de satélites y la peor solución.
3. Con la inclinación, mientras todas las épocas usadas estén en estado 30: la suma del vector unitario del jalón, la mayor inclinación, las mayores precisiones de la inclinación (A2 de S016, con la altura de antena) y los ángulos de la última. Si una época usada no está en estado 30, los ángulos quedan en null (S016/RF-22).
4. Las frases de la época (GGA, GST, ETC) a un `StringBuilder`, y la lista de GST ya incluidas, para añadir al final la última GST solo si no estaba (S010/RF-23).
5. Si la medida es promediada, la posición del suelo de la época en el SC del proyecto, para A2.
- El tiempo promediado es `n × T`, con `T` el periodo del receptor que ya calcula el recolector. Coincide con "la suma de los intervalos entre épocas usadas consecutivas" de la spec: los huecos por cortes o por épocas descartadas no tienen épocas y no suman. La hora UTC de las épocas da el `T`, así que vale también en una reproducción a ×5.
- El resultado de una medida no cambia: `PointMeasurementTest` y `PointMeasurementTiltTest` siguen en verde sin tocarlos.

### A2. Estadísticas (RF-3, RF-4, RF-5, RF-12)
Con la posición de cada época usada en el SC del proyecto (E, N, H), calculada con `ProjectPositionCalculator.fromGround` sobre la punta de esa época (la punta de ETC en "Calculada por la app", la GGA en "Compensada por el receptor" y la antena menos el jalón en "Jalón vertical"):
1. **Welford** por separado para E, N y H: `n`, media y `M2`.
2. **Desviación típica horizontal** = √((M2_E + M2_N) / (n − 1)); **vertical** = √(M2_H / (n − 1)); null con `n < 2` (RF-3, RF-12).
3. **Estabilidad**: un buffer circular con la media (E, N, H) tras cada época de los últimos 60 s de tiempo promediado, es decir, `K = round(60 / T) + 1` posiciones. Con más de `K − 1` épocas (60 s de tiempo promediado y una más, a los 60,1 s con 10 Hz, para tener la media de hace 60 s): planta = hipot(ΔE, ΔN) entre la media actual y la de hace `K − 1` épocas, y vertical = |ΔH|; antes, null (RF-4).
4. **Gráfico**: se guardan E y N de todas las épocas usadas en dos arrays de doubles (576 kB con 36.000). La muestra es una de cada `⌈n / 2000⌉` épocas, como diferencia con la media actual, y la escala es el mayor radio de la muestra con un mínimo de 1 cm, redondeado hacia arriba a 1, 2 o 5 por una potencia de 10 (RF-5).
5. Cada llamada es O(1), salvo la muestra del gráfico, que es O(2.000) y se pide a 5 Hz.
- Que los valores estén en el SC del proyecto, y no en un plano local, es lo que pide la spec. El test lo valida contra pyproj (EPSG:25830 y 25828) a menos de 0,1 mm.
- La posición de una época fuera del ámbito del SC (`unavailable`) no entra en las estadísticas, pero la época se usa; al terminar, la medida falla con `OUTSIDE_CRS_AREA` como hoy (S010/RF-12).

### A3. Pausa y reanudación (RF-8, RF-9, RF-10)
Solo en el promediado; la medida instantánea sigue cancelándose a los 2 s (S010/RF-11 y RF-12).
```
Midiendo ──(2 s sin época, reloj del móvil)──▶ Pausada ──(llega una época)──▶ Midiendo
Pausada ──(5 min seguidos sin época)──▶ Pregunta ──┬ Guardar con lo reunido → A4
                                                   ├ Descartar → cancel()
                                                   └ Seguir esperando → Pausada (otros 5 min)
```
- `onTick` emite `Paused` una vez al entrar y `LongPause` al cumplirse los 5 min (y cada 5 min más con "Seguir esperando"). La primera época de después emite `Resumed`; si la pregunta estaba abierta, el recolector la cierra (RF-9).
- Como las épocas solo se cuentan al llegar, el tiempo del corte no suma. La pérdida de la conexión se ve igual que "Sin datos": no llegan épocas.
- Pausar o reanudar es inmediato respecto de `onTick` (250 ms) y de la llegada de la época: menos de 1 s (RNF).

### A4. Terminar (RF-6, RF-7)
- `finishNow()` hace lo que `finish()` al completarse el tiempo, con las épocas reunidas. Vale también con la medida en pausa.
- Con `n = 0` no termina y devuelve `NoEpochs`: la medida sigue y el recolector lo dice, sin cancelar nada (RF-7).
- El resultado lleva `averagedTimeS = n × T` y las desviaciones de A2; después pasa por el control de precisión de siempre (S010/RF-14, RF-15).

### A5. Modo automático (RF-15 a RF-28, RF-32)
`AutoMeasure` no sabe de Android ni del guardado: decide cuándo medir y lleva la cuenta.
- **Estado**: `Idle`, `Running` (con un punto en curso o esperando el siguiente disparo) y `Paused`; `lastTriggerUtcMs`, `lastSavedEN`, `saved`, `omitted`, `warned`.
- **`start(utcMs)`**: primer disparo inmediato (RF-19).
- **`onEpoch(utcMs, groundEN)`**, con la hora UTC de la época y el suelo de esa época en el SC del proyecto:
  1. Por tiempo: toca si `utcMs − lastTriggerUtcMs ≥ intervalo` (con el cambio de día) (RF-20).
  2. Por distancia: toca si no hay punto en curso y hipot(groundEN − lastSavedEN) ≥ distancia, o si hay un disparo pendiente de RF-25 (RF-21).
  3. Si toca y hay un punto en curso sin terminar: sale `Omit(NOT_FINISHED)` y después `StartPoint` (RF-24).
  4. Si toca y no hay punto en curso: `StartPoint`, y `lastTriggerUtcMs = utcMs`.
- **`onOutcome(saved | omitted(causa))`**: al guardar, se actualiza `lastSavedEN` y `saved`, y se vuelve a avisar de omisiones. Al omitir, `omitted++` y `warned`; el aviso suena solo si `!warned` (RF-24). En un disparo por distancia el disparo queda pendiente y se reintenta con la siguiente época, y la racha cuenta como una sola omisión (RF-25).
- **`pause(utcMs)` y `resume(utcMs)`** (RF-28): al reanudar, por tiempo `lastTriggerUtcMs = utcMs` (otro intervalo completo) y por distancia no cambia nada, así que si ya se cumple sale un único punto.
- **`stop()`** devuelve `AutoSummary(saved, omitted)` (RF-27).
- **Qué se puede cambiar** (RF-32): `autoTrigger`, el intervalo, la distancia y el modo (instantáneo o promediado) se leen al iniciar y no se tocan mientras está en marcha; la interfaz los deshabilita. El nombre, la descripción y la altura del jalón se leen en cada `StartPoint`.
- Cada punto es un `PointMeasurement` nuevo, con la misma fábrica que "Medir"; así reutiliza el descarte por la inclinación (S016/RF-19), el cambio de base (S015/RF-18) y el control de precisión.

### A6. Guardado con tramos (RF-12; RNF)
1. `NmeaChunks.split(text, maxChars = 256_000)` parte por saltos de línea; `join` los une con "\n". Con `text.length ≤ maxChars`, un solo tramo, que va a `raw_nmea`.
2. `PointRepository.create(point)`: una transacción de Room que inserta el punto con `raw_nmea` o con `""` y `nmea_chunks`, y los tramos. Si algo falla, no queda nada a medias (RNF-T6).
3. La ficha lee la fila del punto sin las frases; "Ver frases NMEA" pide el tramo 0 y deja pasar al siguiente y al anterior, con "tramo i de N".

### A7. Textos de tiempo y distancia (RF-1, RF-16, RF-17)
- `DurationText.format(seconds)` → `m:ss` ("0:05", "12:30", "60:00"); `parse` acepta `m:ss`, `mm:ss` y un entero (segundos), y devuelve null si está fuera de rango o mal escrito.
- La distancia se escribe en la unidad lineal del proyecto con `LengthFormat` y se convierte a metros para guardarla; los límites mostrados son 0,1 m a 1.000 m convertidos (0,328 ft a 3.280,8 ft).

## Contratos

### Lo que sale de una medida (`MeasurementUpdate`)
Se añaden, sin cambiar los actuales:
- `Paused` y `Resumed` (A3), `LongPause` (A3).
- `Failed(NO_EPOCHS)` no existe: `finishNow()` devuelve `NoEpochs` y la medida sigue (A4).
- `Progress` no lleva las estadísticas, para no crear 2.000 puntos por época: el recolector pide `measurement.stats()` en su propio flujo.

### Estado del recolector (`CollectorUiState`)
- `paused: PauseState?` (`Paused(sinceMs)` o `LongPause`), y `restartedDiscardedS: Double?` para RF-30.
- `auto: AutoUiState?` con `running`, `paused`, `saved`, `omitted`, `nextInS` o `nextDistanceM`, y `summary` al parar.
- El gráfico y las estadísticas van en un `StateFlow<AverageStatsUi?>` aparte, muestreado cada 200 ms (5 Hz, RNF-T13), para que `state` solo cambie con los sucesos y los tests actuales no se alteren.
- `MeasurementInProgress` pasa a `measuring || pending || auto.running` (S013/RF-16, cambio de S017).

### Botones del recolector
| Situación | Botones |
|---|---|
| Parado, manual | "Medir" |
| Promediando | "Terminar" y "Cancelar" |
| Parado, automático | "Iniciar" |
| Modo automático en marcha | "Parar" (sin "Cancelar", RF-27) |

### Textos nuevos (en `strings.xml`, es y en)
- "Automático", "Por tiempo", "Por distancia", "Iniciar", "Parar", "Terminar", "Intervalo (m:ss)", "Distancia".
- "Medida en pausa: sin datos del receptor. Sigue sola al volver." y la pregunta de 5 min con "Guardar con lo reunido", "Descartar" y "Seguir esperando".
- "Tiempo %1$s de %2$s", "Épocas: %1$d usadas, %2$d descartadas", "Desviación típica H %1$s, V %2$s", "Estabilidad del último minuto: planta %1$s, vertical %2$s", "Sin dato".
- "Guardados: %1$d · Omitidos: %2$d", "Siguiente punto en %1$s", "Siguiente punto a %1$s", "Modo automático parado: %1$d guardados, %2$d omitidos".
- "Medida empezada de nuevo: se han descartado %1$s de promediado." y "Aún no hay épocas".
- El log (S014/RF-5) lleva los mismos sucesos en español y sin coordenadas: pausa, reanudación, pregunta, `Terminar`, cada punto omitido con su causa (RF-31), inicio y parada del modo automático y el reinicio por la base.

### Accesibilidad (RNF-T8)
- El panel de estadísticas no es una región viva: lo que se anuncia es un texto aparte que cambia solo al cambiar de minuto ("Promediando, 12 minutos de 60"), al pausar y al omitir un punto.
- El gráfico lleva `contentDescription` con las desviaciones típicas y el número de épocas.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| `PointMeasurement` acumula en una pasada y no retiene épocas (A1) | 36.000 épocas son decenas de MB de objetos vivos 60 min; RNF-T13 | Mantener la lista y recalcular: memoria y recálculos de 36.000 elementos en cada refresco |
| Estadísticas con Welford por eje (A2) | Estable con 36.000 épocas y O(1) por época; constitución nº 2 (≤ 0,1 mm) | Dos pasadas sobre todas las épocas |
| Desviaciones en el SC del proyecto, no en un plano local (A2) | Es lo que pide la spec y lo que ve el usuario en el CSV | ENU local: casi igual (el factor de escala cambia 4·10⁻⁴), pero distinto de lo que dice la spec |
| Frases NMEA largas en la tabla `point_nmea`, en tramos de 256.000 caracteres (A6) | La ventana del cursor de Android (≈ 2 MB) rompe las lecturas y las copias de S013 con filas de 12 MB; atomicidad (RNF-T6) y cascada gratis; las copias siguen copiando tablas con `SELECT *` | Un fichero por punto (la copia y los paquetes de S013 tendrían que llevar ficheros y la atomicidad se complica); un BLOB comprimido (frágil y sigue siendo una fila grande) |
| La pausa y la pregunta de 5 min viven en `PointMeasurement` (A3) | Pura y comprobable sin Android; la medida ya cuenta el reloj del móvil para "Sin datos" | Lógica en el ViewModel |
| `AutoMeasure` como máquina pura que decide y cuenta; el guardado lo hace `AutoCollector` (A5) | Constitución nº 6; reutiliza el control de precisión, el guardado y el log del recolector | Un servicio en primer plano propio: haría sobrevivir la medida al cierre de la app, que queda fuera de alcance (spec, "Dudas abiertas") |
| El disparo por tiempo se cuenta con la hora UTC de las épocas (A5) | Una reproducción a ×5 da intervalos coherentes con RNF-T10 | Reloj del móvil: una reproducción a ×5 dispararía 5 veces más despacio |
| "Automático" no se recuerda; el disparo, el intervalo y la distancia sí | No empezar a guardar puntos al abrir el recolector (constitución nº 3) | Recordarlo todo |
| `finishNow()` sin épocas no cancela (A4) | RF-7 dice que no guarda nada, no que cancele; el usuario puede esperar a la primera época | Cancelar la medida |
| Estadísticas en un `StateFlow` aparte, a 5 Hz | RNF-T13 sin tocar los tests de `state` | Subir `state` a 5 Hz con `sample`: cambiaría el momento en que se ven los sucesos |
| `AutoMigration` 8 → 9 sin especificación | Solo hay columnas opcionales, una con valor por defecto, y una tabla nueva (ADR-8) | `RoomDatabase.Migration` a mano |
| `AutoCollector` es una clase del paquete del recolector, no un ViewModel | `CollectorViewModel` ya pasa de 600 líneas; se construye con constructor normal y se prueba con la `ReceiverSession` real | Meter todo en `CollectorViewModel` |

## Estrategia de tests
- **Unitarios JVM**:
  - `AverageStatsTest`: 36.000 épocas de `fixtures/promediado/` frente a pyproj y `math.fsum` (media, `dev_h`, `dev_v` y estabilidad ≤ 0,1 mm), `n < 2`, antes y después del minuto, la muestra de 2.000 y la escala mínima de 1 cm (RF-3 a RF-5, RF-12).
  - `PointMeasurementStreamingTest`: el resultado es el de siempre (se mantienen `PointMeasurementTest` y `PointMeasurementTiltTest`), y una época usada ya no está retenida (`WeakReference` tras `System.gc()`; RNF).
  - `PointMeasurementPauseTest`: pausa a los 2 s, reanudación, el tiempo del corte no suma, `LongPause` a los 5 min y repetida con "Seguir esperando", `finishNow()` con y sin épocas y en pausa, la instantánea sigue cancelándose (RF-6 a RF-10, RF-28).
  - `AutoMeasureTest`: primer punto inmediato, disparo por tiempo y por distancia, punto sin terminar, omisiones con un solo aviso y reintento por distancia, pausa y reanudación en los dos disparos, `stop()` y cambio de día (RF-19 a RF-28).
  - `DurationTextTest`, `CollectorSettingsTest` y `NmeaChunksTest`.
- **`data`**:
  - `TopoPhoneDatabaseTest` 8 → 9: los puntos conservan sus datos y las columnas nuevas quedan a NULL, y `nmea_chunks` a 0.
  - `PointRepositoryNmeaTest`: un punto de 12 MB se guarda en una transacción en menos de 3 s, ninguna fila supera los 256.000 caracteres, se lee tramo a tramo y se borra con la cascada; un fallo a medias no deja nada.
  - `DatabaseSnapshotTest` copia los tramos; `PointRepositoryExportTest`, `CollectorPreferencesTest` y `SettingsArchiveTest` con las claves nuevas.
- **`core:formats`**: `CsvExportGoldenTest` con 47 columnas y ficheros dorados nuevos; `ExportPreferencesTest` y `ExportScreenTest` con los nombres (RF-14).
- **Robolectric (`app`)**:
  - `CollectorViewModelAdvancedTest`, con la `ReceiverSession` real y las épocas de una en una (`noDataTimeoutMs = 60_000`): un promediado largo, "Terminar", un corte con pausa y reanudación, la pregunta de 5 min con un reloj falso, el cambio de base con el tiempo descartado, el modo automático por tiempo y por distancia, el log y los avisos, el cambio de modo de inclinación con el modo en marcha y que la medida sigue sin nadie recogiendo `state` (RF-11).
  - `CollectorPanelAdvancedTest`: los botones de cada situación, los ajustes bloqueados, la entrada en `m:ss`, las estadísticas, el gráfico con su descripción y los textos (RF-1 a RF-5, RF-15 a RF-17, RF-26, RF-32).
  - `PointDetailsAveragedTest`: la ficha con y sin datos y el visor de frases por tramos; `AutoCollectorTest` con el nombre libre y el nombre repetido al iniciar (RF-13, RF-23, RF-33).
- **Replay NMEA** (`fixtures/nmea/sintetico/`): `rs3-promediado-largo` (10 min a 10 Hz, con un corte de 6 s), `rs3-corte` (corte corto y otro largo) y `rs3-traza` (2 min andando a 5 Hz con una pérdida de FIX), con lo esperado de cada una. `AdvancedReplayTest` las recorre con el `CollectorViewModel`.
- **Rendimiento** (`AdvancedPerformanceTest`): 36.000 épocas a 10 Hz con las estadísticas, menos de 1 ms por época; `AverageStats` pedido a 5 Hz con 36.000 épocas, menos de 16 ms.
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, un promediado de 60 min sobre un vértice y una traza con el RS3.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | `CollectorSettings`, `DurationText`, A7 | `DurationTextTest`, `CollectorPreferencesTest`, `CollectorPanelAdvancedTest` |
| RF-2 | A1, `Progress`, panel | `CollectorPanelAdvancedTest`, `CollectorViewModelAdvancedTest` |
| RF-3 | A2.2 | `AverageStatsTest`, `CollectorPanelAdvancedTest` |
| RF-4 | A2.3 | `AverageStatsTest`, `CollectorPanelAdvancedTest` |
| RF-5 | A2.4, gráfico | `AverageStatsTest`, `CollectorPanelAdvancedTest` |
| RF-6 | A4 | `PointMeasurementPauseTest`, `CollectorViewModelAdvancedTest` |
| RF-7 | A4 | `PointMeasurementPauseTest`, `CollectorViewModelAdvancedTest` |
| RF-8 | A3, `Alerts.measurementPaused` | `PointMeasurementPauseTest`, `CollectorViewModelAdvancedTest` |
| RF-9 | A3 | `PointMeasurementPauseTest`, `CollectorViewModelAdvancedTest` |
| RF-10 | A3, pregunta | `PointMeasurementPauseTest`, `CollectorPanelAdvancedTest` |
| RF-11 | `viewModelScope`, sin cancelar al pasar a segundo plano | `CollectorViewModelAdvancedTest` |
| RF-12 | A1, A6 | `PointRepositoryNmeaTest`, `PointMeasurementStreamingTest`, `AdvancedReplayTest` |
| RF-13 | ficha del punto | `PointDetailsAveragedTest` |
| RF-14 | `core:formats`, `forExport` | `CsvExportGoldenTest`, `ExportScreenTest`, `PointRepositoryExportTest` |
| RF-15 | panel y `AutoCollector` | `CollectorPanelAdvancedTest`, `CollectorViewModelAdvancedTest` |
| RF-16 | ajustes y A7 | `CollectorPreferencesTest`, `CollectorPanelAdvancedTest` |
| RF-17 | ajustes y A7 | `CollectorPreferencesTest`, `CollectorPanelAdvancedTest` |
| RF-18 | `AutoCollector` | `AutoCollectorTest` |
| RF-19 | A5 | `AutoMeasureTest`, `AdvancedReplayTest` |
| RF-20 | A5 | `AutoMeasureTest`, `AdvancedReplayTest` |
| RF-21 | A5 | `AutoMeasureTest`, `AdvancedReplayTest` |
| RF-22 | `AutoCollector` | `CollectorViewModelAdvancedTest` |
| RF-23 | `PointNames.nextFree` | `AutoCollectorTest` |
| RF-24 | A5, `Alerts.pointOmitted` | `AutoMeasureTest`, `CollectorViewModelAdvancedTest` |
| RF-25 | A5 | `AutoMeasureTest` |
| RF-26 | `AutoUiState`, panel | `CollectorPanelAdvancedTest` |
| RF-27 | A5 | `AutoMeasureTest`, `CollectorViewModelAdvancedTest` |
| RF-28 | A5 | `AutoMeasureTest`, `CollectorViewModelAdvancedTest` |
| RF-29 | tilt de S016 en `PointMeasurement` y `AutoCollector` | `CollectorViewModelAdvancedTest` |
| RF-30 | cambio de base (S015/RF-18) y `Alerts.measurementRestarted` | `CollectorViewModelAdvancedTest` |
| RF-31 | log del modo automático | `CollectorViewModelAdvancedTest` |
| RF-32 | A5, panel | `AutoMeasureTest`, `CollectorPanelAdvancedTest` |
| RF-33 | blocker `NAME_TAKEN` | `AutoCollectorTest` |
