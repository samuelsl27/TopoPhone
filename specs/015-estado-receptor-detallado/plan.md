# Plan técnico — Spec 015

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/015-estado-receptor-detallado/spec.md`. Sin código.

## Estructura de módulos
- `core:gnss`:
  - `rtcm/RtcmBits`: lector de bits de la carga de una trama, de más a menos significativo (A1);
  - `rtcm/ReferenceStationDecoder`: mensajes 1005/1006 → identificador, ECEF del ARP y altura de antena (RF-12, RF-13, A1);
  - `rtcm/MsmDecoder`: CNR de los MSM 4 a 7 por satélite y señal; los MSM 1 a 3 no la traen (RF-8, RF-10, A2);
  - `RtcmFramer`: entrega además la carga de cada trama con el CRC bien, por un parámetro `onFrame` (RF-8, RF-12);
  - `signal/Band` y `signal/SatelliteNumber`: la banda de una señal NMEA o RTCM y el número propio del satélite en su constelación (RF-7, RF-8, A3, A4);
  - `nmea/SkyView`: lo último conocido de GSA y GSV (DOP, satélites usados y en vista), para no perderlo en las épocas de 5 Hz sin esas frases (RF-2 a RF-7, A8).
- `core:geodesy` → `Ecef`: geodésicas ↔ ECEF sobre un elipsoide (A5). Lo usan la base y la línea base (RF-13, RF-14).
- `core:survey`:
  - `BaseTracker`: la base en uso (EBP o RTCM), su caducidad, la SNR de la base y los cambios de base (RF-10 a RF-13, RF-16, RF-17, A6);
  - `Baseline`: la distancia en línea recta entre la antena y la base (RF-14, A7).
- `receiver` → `ReceiverSession`:
  - es dueño de un `BaseTracker`, al que pasa las EBP de cada época y las tramas 1005/1006 y MSM de las correcciones;
  - publica en `ReceiverSnapshot` dos campos nuevos con valor por defecto: `sky: SkyView?` y `base: BaseState?`.
- `core:model` → `PointBase` y `BaseSource`; `Measurement.base: PointBase? = null` (RF-20).
- `data` → columnas de la base en `points`, migración automática 6 → 7 y `forExport` con esas columnas (RF-20, RF-22).
- `core:formats` → siete `ExportColumn` nuevas tras `MEASURED_TO` (RF-22).
- `app`:
  - pantalla Estado con las tarjetas "Satélites", "Skyplot", "SNR" y "Base" (RF-1 a RF-17);
  - `BaseNotices` (@Singleton): qué avisos ha cerrado el usuario (RF-15, RF-17);
  - avisos y reinicio de la medida en el recolector (RF-15, RF-17, RF-18);
  - la base en el mapa (`MapController.showBase`, RF-19);
  - la tarjeta "Base" en la ficha del punto (RF-21);
  - las coordenadas de la base para el CSV (RF-22);
  - anotaciones en el log de los avisos (S014/RF-5).

## Modelo de datos

### En la sesión (no se guarda)
```
SkyView(
  dop: Dop?,                                    // PDOP, HDOP, VDOP de la GSA (A8)
  used: Map<Constellation, Set<Int>>,           // números propios de los satélites usados (A3)
  inView: Map<GsvKey, SatellitesInView>,        // como en Epoch
  updatedAtMs: Long,
)

BaseState(
  position: BaseFix?,                           // null si no hay base (RF-16)
  serial: Int,                                  // sube con cada base nueva o cambiada (RF-15, RF-17)
  change: BaseChange?,                          // último cambio: distancia y serial (RF-17)
  snr: Map<SignalKey, Double>,                  // CNR de la base por (constelación, banda, satélite) (RF-8)
  snrAvailable: Boolean,                        // false si en 10 s no ha llegado un MSM 4-7 (RF-10)
)
BaseFix(latitudeDeg, longitudeDeg, ellipsoidalHeightM, ecef: Xyz, source: BaseSource, stationId: Int?, antennaHeightM: Double?, receivedAtMs)
BaseChange(distanceM: Double, serial: Int)
```

### En el punto (Room v7)
`PointBase(position: GeodeticPosition, source: BaseSource {RECEIVER, CORRECTIONS}, stationId: Int?, antennaHeightM: Double?, baselineM: Double)`

Columnas nuevas de `points`, todas opcionales (NULL en los puntos sin base, los manuales y los de versiones anteriores):

| Columna | Tipo | Ejemplo (RGAPA AVL13M, 1006) |
|---|---|---|
| `base_lat` | REAL | 43.5578666032 |
| `base_lon` | REAL | -5.9130704113 |
| `base_h` | REAL | 78.4338 |
| `base_source` | TEXT | `CORRECTIONS` |
| `base_station` | INTEGER | 6 |
| `base_antenna_height` | REAL | 0.0 |
| `baseline` | REAL | 12345.678 (m) |

- `AutoMigration(from = 6, to = 7)` sin especificación (solo columnas nuevas que admiten NULL; ADR-8), esquema `7.json` y una línea más en el registro de versiones de `TopoPhoneDatabase`.
- Las copias y los paquetes (S013) copian las filas con `SELECT *`: no hay que tocar `DatabaseSnapshot.TABLES`; las copias antiguas se migran al abrirlas.
- Solo se guardan las geodésicas de la base (fuente de verdad, como los puntos). Su Este, Norte y altura se calculan al mostrarlos o exportarlos con `ProjectPositionCalculator.fromGround`, así siguen bien si cambia el SC del proyecto.

## Algoritmos

### A1. Tramas RTCM 1005/1006 (RF-12, RF-13)
1. `RtcmFramer(onFrame)` llama a `onFrame(tipo, carga)` con una copia de la carga de cada trama con el CRC bien. Las estadísticas de S007 no cambian.
2. `RtcmBits` lee enteros sin signo y con signo (complemento a dos) de hasta 64 bits.
3. 1005/1006, por este orden (RTCM 10403.3, DF002–DF028):
   - tipo (12 bits), estación (12), año ITRF (6) e indicadores (4);
   - X del ARP (38 con signo, 0,0001 m), oscilador (1), reservado (1);
   - Y del ARP (38 con signo), cuarto de ciclo (2), Z del ARP (38 con signo);
   - solo en el 1006: altura de antena (16 sin signo, 0,0001 m).
4. Comprobado con la grabación real: estación 6, X = 4 604 889,1789, Y = −476 930,7239, Z = 4 372 677,1220 y altura 0,0000. pyproj da 43,5578666032° N, 5,9130704113° O y h = 78,4338 m.

### A2. CNR de los MSM 4 a 7 (RF-8, RF-10)
1. Cabecera: tipo (12), estación (12), hora (30), varios mensajes (1), IODS (3), reservado (7), reloj (2 + 2), suavizado (1 + 3).
2. Máscara de satélites (64): el bit i, contado desde 1, es el satélite i. Máscara de señales (32): el bit j es la señal j de la tabla de su sistema. Máscara de celdas (Nsat × Nsig bits).
3. Se saltan los datos de satélite: 18·Nsat bits en MSM4 y MSM6, 36·Nsat bits en MSM5 y MSM7.
4. Se saltan los datos de señal anteriores a la CNR: 42·Ncel bits en MSM4 y MSM5, 55·Ncel bits en MSM6 y MSM7.
5. Se lee la CNR de cada celda: 6 bits, 1 dB-Hz, en MSM4 y MSM5; 10 bits, 0,0625 dB-Hz, en MSM6 y MSM7. El 0 es "sin valor".
6. Si la carga se acaba antes de tiempo, el mensaje se descarta entero.
7. Comprobado con un decodificador independiente en Python sobre la grabación real: GPS, GLONASS, Galileo y BeiDou, con CNR de 35 a 56 dB-Hz.

### A3. Número propio del satélite (RF-6, RF-8)
- Las GSA y GSV numeran como el receptor, los MSM por su máscara. Se pasan todos al número propio de su constelación:
  - GPS: 1–32;
  - GLONASS: el slot (NMEA 65–96 → −64);
  - Galileo: 1–36 (también 301–336 → −300);
  - BeiDou: 1–63 (también 201–263 → −200 y 401–463 → −400);
  - QZSS: 1–10 (NMEA 193–202 → −192);
  - NavIC: 1–14.
- SBAS no tiene constelación propia en `Constellation`: sus MSM se ignoran y en el skyplot no se dibuja.

### A4. Bandas (RF-7, RF-8)
- **NMEA 4.11** (identificador de señal de la GSV):
  - GPS: 1–3 L1, 4–6 L2, 7–8 L5;
  - GLONASS: 1–2 G1, 3–4 G2;
  - Galileo: 1 E5a, 2 E5b, 3 E5, 4–5 E6, 6–7 E1;
  - BeiDou: 1–2 B1, 3–4 B1C, 5 B2a, 6 B2b, 8–A B3, B–C B2;
  - QZSS: 1–4 L1, 5–6 L2, 7–8 L5;
  - NavIC: 1 L5.
- **RTCM** (máscara de señales del MSM):
  - GPS: 2–4 L1, 8–10 L2, 15–17 L2, 22–24 L5, 30–32 L1;
  - GLONASS: 2–3 G1, 8–9 G2;
  - Galileo: 2–6 E1, 8–12 E6, 14–16 E5b, 18–20 E5, 22–24 E5a;
  - BeiDou: 2–4 B1, 8–10 B3, 14–16 B2, 22–24 B2a, 25 B2b, 30–32 B1C;
  - QZSS: 2 L1, 15–17 L2, 22–24 L5, 30–32 L1;
  - NavIC: 22 L5.
- Una señal del rover sin identificador o fuera de la tabla va "sin banda" (RF-7). Una señal de la base fuera de la tabla se ignora: no tiene con qué compararse.

### A5. Geodésicas ↔ ECEF (RF-13, RF-14)
- Directa (EPSG GN 7-2, método 9602): X = (N + h)·cosφ·cosλ, Y = (N + h)·cosφ·sinλ, Z = (N·(1 − e²) + h)·sinφ, con N = `Geodesy.primeVerticalRadius`.
- Inversa: λ = atan2(Y, X), p = √(X² + Y²). Iteración de φ con h = p/cosφ − N hasta que |Δφ| < 1·10⁻¹² rad (2 o 3 vueltas); cerca del polo, h = Z/sinφ − N·(1 − e²).
- Elipsoide GRS80 (ETRS89 y REGCAN95). Con correcciones ITRF/WGS84, la diferencia entre GRS80 y WGS84 es de menos de 0,1 mm en h.

### A6. Base en uso (RF-10 a RF-13, RF-16, RF-17)
1. `onEbp(posición, t)` guarda la última EBP. `onReferenceStation(estación, t)` guarda el último 1005/1006, pasado a geodésicas con A5. `onMsm(observaciones, t)` guarda la CNR de su sistema con su hora.
2. Fuente:
   - la EBP si ha llegado en los últimos 60 s (RF-11);
   - si no, el 1005/1006 si ha llegado en los últimos 60 s (RF-12);
   - si no, no hay base (RF-16).
3. Cambio: si la nueva posición de una fuente está a más de 0,1 m (distancia ECEF) de la última conocida de **esa misma fuente**, sube `serial` y se guarda `BaseChange(distancia, serial)` (RF-17). La primera base de la conexión sube `serial` pero no es un cambio. Pasar de una fuente a otra no es un cambio.
4. La SNR de la base de un sistema caduca a los 10 s de su último MSM 4 a 7; sin ninguno vigente, `snrAvailable = false` (RF-10).
5. `tick(t)` aplica las caducidades; lo llama el vigilante de la sesión (cada 25 ms) y solo publica estado si algo cambia.
6. `reset()` en cada conexión nueva con el receptor: "la última base desde que se conectó el receptor" (RF-17). Las reconexiones automáticas (S004) no lo llaman.

### A7. Línea base (RF-14, RF-20)
- Distancia = |ECEF(antena) − ECEF(base)|, con la antena de la GGA: latitud, longitud y h = altitud + separación (ADR-6).
- En el punto se guarda la de la antena medida (la media de la medida) con la base de ese momento.

### A8. Satélites, DOP y skyplot (RF-2 a RF-7)
1. En cada época con GSA o GSV, `SkyView` se rehace con las de la época; sin ellas, sigue el anterior (el RS3 manda GSA y GSV a 1 Hz y GGA a 5 Hz). Se borra al desconectar.
2. DOP: la primera GSA de la época con PDOP, HDOP y VDOP (NMEA 4.11 repite los del conjunto en todas).
3. Usados por constelación: los números de sus GSA (A3). En vista por constelación: el mayor total de sus señales (S003/RF-15).
4. Skyplot: radio r = R·(90° − elevación)/90°; x = r·sin(azimut), y = −r·cos(azimut). El norte queda arriba y el azimut avanza en sentido horario. Anillos a 0°, 30° y 60°, y letras N, E, S, O.
5. Cada satélite con elevación y azimut se dibuja como círculo, relleno si está usado (RF-6), con su letra y su número al lado en el color del texto (RF-5, contraste de 7:1).

### A9. Filas de SNR (RF-7 a RF-9)
1. Rover: una fila por (constelación, banda, satélite) de las GSV de `SkyView`. Base: la CNR de `BaseState.snr` con la misma clave.
2. Grupos por constelación y banda (G L1, G L2, G L5, R G1…), y dentro, por número de satélite.
3. Una fila solo con base sale si la base ve el satélite o la banda y el rover no.
4. Tramo de color de cada barra: menos de 35, de 35 a menos de 45, y 45 o más (RF-9). Cada barra escribe su valor: entero el del rover, con un decimal el de la base en MSM6 y MSM7.

### A10. Reinicio de la medida (RF-18)
1. Cada época sale de la sesión con la base que había al emitirla (`SessionEpoch(epoch, base)`). La sesión actualiza la base antes de emitir la época.
2. Al empezar, la medida guarda el número de cambios de base (`base.change?.count`) de la época en curso.
3. En cada época, antes de pasarla a `PointMeasurement`, el recolector compara ese número con el de la época. Si el recolector va con retraso, no confunde la base de una época vieja con la de ahora, y la época que trae una base nueva no entra nunca en la media vieja.
4. Si el número ha cambiado, cancela la medida y crea otra con los mismos parámetros y la hora de ahora. El progreso vuelve a cero y se ve el aviso "La base ha cambiado".
5. Una base nueva que llega por las correcciones se detecta en la época siguiente.

## Contratos
- `RtcmFramer(onFrame: ((type: Int, payload: ByteArray) -> Unit)? = null)`.
- `ReferenceStationDecoder.decode(payload): ReferenceStation?`, con `ReferenceStation(stationId, x, y, z, antennaHeightM?)`.
- `MsmDecoder.decode(payload): MsmCnr?`, con `MsmCnr(system, cells: List<MsmCell(satellite, signal, cnrDbHz)>)`; null en MSM 1 a 3 o si la carga es corta.
- `Band.fromNmea(constellation, signalId?)`, `Band.fromRtcm(system, signal)` y `SatelliteNumber.native(constellation, prn)`.
- `Ecef.fromGeodetic(latDeg, lonDeg, h, ellipsoid)` y `Ecef.toGeodetic(x, y, z, ellipsoid)`.
- `BaseTracker(clock)`: `onEbp`, `onReferenceStation`, `onMsm`, `tick`, `reset` y `state: BaseState`.
- `ReceiverSnapshot`: `sky: SkyView? = null` y `base: BaseState = BaseState.NONE`.
- `ReceiverSession.epochs: SharedFlow<SessionEpoch>`, con `SessionEpoch(epoch, base)` (A10).
- `MapController.showBase(position: GeoPoint?)`.
- `BaseNotices` (app, @Singleton): `dismissedChangeSerial` y `dismissedLongBaselineSerial`.
  - "La base ha cambiado" se ve mientras `change.serial != dismissedChangeSerial`.
  - "Línea base larga" se ve mientras la línea base supera 20 km y `serial != dismissedLongBaselineSerial`, así que vuelve con cada base nueva.
  - Lo comparten la pantalla Estado y el recolector.
- Log (S014/RF-5; los anota `ReceiverLogRecorder`, sin coordenadas):
  - AVISO receptor "La base ha cambiado: 1,234 km";
  - AVISO receptor "Línea base larga: 23,456 km".
- Textos nuevos (es/en):
  - pantalla Estado: "Posición", "Satélites", "DOP", "Skyplot", "N", "E", "S", "O", "SNR (dB-Hz)", "Rover", "Sin banda", "SNR de la base no disponible";
  - base: "Base", "Receptor", "Correcciones, estación %1$d", "Altura de antena", "Línea base", "Base no disponible";
  - avisos: "Línea base larga: %1$s km", "La base ha cambiado: %1$s", "La medida ha empezado de nuevo con la base nueva";
  - nombres de las siete columnas nuevas del CSV en la pantalla Exportar.
- Columnas CSV: `Base latitude`, `Base longitude`, `Base ellipsoidal height`, `Base easting`, `Base northing`, `Base elevation` y `Baseline`. Las longitudes van en la unidad del proyecto con 4 decimales, y la latitud y la longitud con 9. Vacías sin base o fuera del SC (RF-22).

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Decodificar 1005/1006 y la CNR de los MSM con código propio en `core:gnss` | Pocos campos; Kotlin puro y testeable (constitución nº 6); sin dependencias nuevas | RTKLIB por JNI (C, NDK y peso); una librería Java de RTCM (ninguna pequeña y compatible con GPL-3.0 que haga solo esto) |
| La carga de las tramas sale por un parámetro `onFrame` del `RtcmFramer` | No cambia `RtcmEvent` ni los tests de S007 | Añadir un `ByteArray` a `RtcmEvent.Message`: rompe la igualdad del `data class` |
| ECEF ↔ geodésicas en `core:geodesy`, y la base pasada a geodésicas en `core:survey` | `core:gnss` no puede depender de `core:geodesy` (reglas de módulos); el decodificador devuelve ECEF bruto | Hacer la conversión dentro de `core:gnss`: geodesia duplicada |
| `BaseTracker` dentro de `ReceiverSession` | Una sola fuente de verdad (ADR-11) para la pantalla Estado, el recolector, el mapa y el log | Seguir la base en cada ViewModel: estados distintos según la pantalla |
| `SkyView` conserva lo último de GSA y GSV | El RS3 manda GSA y GSV a 1 Hz y GGA a 5 Hz: cuatro de cada cinco épocas vienen sin ellas | Leer la época tal cual: el skyplot y la SNR parpadearían |
| Guardar solo las geodésicas de la base y calcular su Este, Norte y altura al mostrarlos o exportarlos, con una caché por posición de base | Siguen bien si cambia el SC del proyecto; casi todos los puntos comparten base, así que exportar 10 000 cuesta lo mismo | Guardar también el Este, Norte y altura de la base: quedarían viejos al cambiar el SC |
| Migración automática 6 → 7 | Solo son columnas nuevas que admiten NULL (ADR-8) | Migración escrita a mano |
| Base en el mapa con un icono propio (triángulo con borde blanco) en una `SymbolLayer` | Se distingue del círculo del rover y de los puntos; es el símbolo habitual de una estación | Otro círculo (se confunde); un carácter "▲" (puede faltar en las fuentes del mapa) |
| SNR en barras horizontales, una fila por satélite con rover y base juntos, agrupadas por constelación y banda | Caben 60 satélites en un móvil y se leen con guantes; la comparación por satélite que pidió el usuario | Gráfico de barras vertical: no cabe y las etiquetas no se leen |
| Cada época sale con la base que había al emitirla, y el recolector compara su número de cambios | Ninguna época con la base nueva entra en la media vieja, aunque el recolector vaya con retraso | Leer el estado de la sesión al recibir la época: si el recolector va con retraso, el estado ya va por delante (lo detectó `ReceiverSessionBaseTest`) |
| Los avisos cerrados se guardan en un `BaseNotices` común | Cerrar un aviso en Estado lo cierra también en el recolector ("hasta que el usuario los cierra") | Un estado por pantalla |

## Estrategia de tests
- **Unitarios JVM en `core:gnss`:**
  - `RtcmBitsTest`;
  - `ReferenceStationDecoderTest`: la grabación real de RGAPA (estación 6, ECEF y altura de antena de A1) y un 1005 sintético;
  - `MsmDecoderTest`: la CNR de varias celdas de la grabación real (C11 señal 2 = 54 dB-Hz, E02 señal 2 = 41 dB-Hz…) y MSM4, MSM6 y MSM7 sintéticos (MSM6: 0,0625 dB-Hz); null en MSM1 a MSM3 y con la carga corta;
  - `RtcmFramerTest`: `onFrame` entrega 113 cargas y las estadísticas no cambian;
  - `BandTest` y `SatelliteNumberTest`: las tablas de A3 y A4;
  - `SkyViewTest`: conserva los datos entre épocas, DOP de la GSA, recuentos como S003/RF-15.
- **`core:geodesy`:** `EcefTest` contra vectores de pyproj generados con `tools/geodesia` (España, Canarias y AVL13M): geodésicas ≤ 1·10⁻⁹°, h ≤ 1 mm, ida y vuelta ≤ 0,1 mm (constitución nº 2).
- **`core:survey`:**
  - `BaseTrackerTest`: la EBP manda; los 1005/1006 si no hay EBP; caducidad a 60 s; la SNR caduca a 10 s; cambio a más de 0,1 m en la misma fuente; sin cambio al pasar de una fuente a otra; `reset`;
  - `BaselineTest` contra pyproj (≤ 1 mm).
- **`receiver`:**
  - `ReceiverSessionBaseTest`: la grabación real por un conector NTRIP falso da la base de la estación 6 y la SNR de la base;
  - una EBP en el NMEA pasa a ser la fuente;
  - la base cambiada sube el serial antes de emitir la época.
- **`data`:**
  - `TopoPhoneDatabaseTest`: paso 6 → 7 con un punto de la v6, `CURRENT_VERSION = 7` y el esquema 7 exportado;
  - `BackupMigrationTest` cubre la v6 sin cambios;
  - ida y vuelta de un punto con base y otro sin base;
  - `forExport` lleva las columnas.
- **`core:formats` y `tools`:**
  - ficheros dorados regenerados con un punto con base y otro sin base;
  - `CsvExportTest` pasa de 28 a 35 columnas;
  - `tools/formatos/test_csv_exportado.py` con las columnas nuevas en `LENGTHS` y `DEGREES`, y 35 cabeceras.
- **Robolectric (`app`):**
  - pantalla Estado: posición con el formato angular de los proyectos nuevos, DOP, recuentos, skyplot (usados rellenos, descripción para TalkBack), filas de SNR con su tramo y su valor, base y sus fuentes, base no disponible, avisos y cerrarlos;
  - `CollectorViewModelTest`: el cambio de base reinicia la medida, y el aviso "Línea base larga" se ve en el recolector;
  - mapa: `FakeMapController` recibe `showBase`;
  - ficha del punto: tarjeta "Base" en las unidades del proyecto y fuera del SC;
  - exportación: el formato personalizado guardado deja las columnas nuevas sin marcar;
  - `ReceiverLogRecorderTest`: los dos avisos en el log.
- **Rendimiento:** con 64 satélites y 3 bandas, `SkyView`, las filas de SNR y el dibujo están listos en menos de 1 s desde la época (RNF), y exportar 10 000 puntos con base sigue dentro del límite de S012.
- **Reproducción:** `FullRs3ReplayTest` con `rs3-completo.nmea`: la base de la EBP (a unos 300 m), la línea base, el skyplot y la SNR del rover.
- **Campo (del usuario):** con el RS3 y NTRIP:
  - SNR de la base;
  - posición de la base frente a la publicada de la estación;
  - línea base frente a una distancia calculada aparte;
  - si la EBP da el ARP o la marca del suelo (suposición de la spec).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | Tarjeta "Posición"; formato angular de los proyectos nuevos | `StatusScreenTest` |
| RF-2 | A8 (DOP) | `SkyViewTest`, `StatusScreenTest` |
| RF-3 | A8 (recuentos) | `SkyViewTest`, `StatusScreenTest` |
| RF-4 | A8 (skyplot) | `SkyplotTest` |
| RF-5 | A8 (letra y color) | `SkyplotTest` |
| RF-6 | A3, A8 (usados) | `SatelliteNumberTest`, `SkyplotTest` |
| RF-7 | A4, A9 | `BandTest`, `SnrChartTest` |
| RF-8 | A2, A4, A9 | `MsmDecoderTest`, `ReceiverSessionBaseTest`, `SnrChartTest` |
| RF-9 | A9 (tramos) | `SnrChartTest` |
| RF-10 | A6.4 | `BaseTrackerTest`, `SnrChartTest` |
| RF-11 | A6.2 | `BaseTrackerTest`, `FullRs3ReplayTest` |
| RF-12 | A1, A6.2 | `ReferenceStationDecoderTest`, `BaseTrackerTest`, `ReceiverSessionBaseTest` |
| RF-13 | A5, tarjeta "Base" | `EcefTest`, `StatusScreenTest` |
| RF-14 | A7 | `BaselineTest`, `StatusScreenTest` |
| RF-15 | `BaseNotices`, recolector | `StatusScreenTest`, `CollectorViewModelTest` |
| RF-16 | A6.2 | `BaseTrackerTest`, `StatusScreenTest` |
| RF-17 | A6.3, `BaseNotices` | `BaseTrackerTest`, `StatusScreenTest`, `CollectorViewModelTest` |
| RF-18 | A10 | `CollectorViewModelTest` |
| RF-19 | `MapController.showBase` | `BaseLayerTest` |
| RF-20 | `PointBase`, Room v7 | `TopoPhoneDatabaseTest`, `PointRepositoryTest`, `CollectorViewModelTest` |
| RF-21 | Ficha del punto | `PointDetailsBaseTest` |
| RF-22 | `ExportColumn`, cálculo de la base | `CsvExportGoldenTest`, `test_csv_exportado.py`, `ExportViewModelTest` |
