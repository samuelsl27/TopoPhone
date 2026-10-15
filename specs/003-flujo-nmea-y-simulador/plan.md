# Plan técnico — Spec 003

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/003-flujo-nmea-y-simulador/spec.md`. Sin código.

## Estructura de módulos
Ningún módulo nuevo ni dependencia nueva.

| Módulo | Qué añade | RF |
|---|---|---|
| `core:gnss` (JVM) | `Constellation` (por talker y por *system ID* de NMEA 4.11), `NmeaTime` (hora → ms del día), parsers `GsaParser`, `GsvParser`, `RmcParser`, `VtgParser`, `ZdaParser`, `EbpParser` y `EtcParser`; `Epoch` y `EpochAssembler`; `ReplayPacer` | RF-1–RF-11, RF-36–RF-39 |
| `receiver` (Android) | `ReceiverSession`: épocas, "Sin datos" a 2 s, grabación de las correcciones, modo simulación. `SessionLogStore` v2: una carpeta por sesión, partes, metadatos, límite de espacio, migración, importación. `ReplayTransport` | RF-12, RF-24–RF-28, RF-32–RF-34, RF-36–RF-45 |
| `data` | `sessionSpaceMb` en las preferencias del receptor (500 por defecto) | RF-26 |
| `core:designsystem` | Tono `SIMULATION` en `StatusPalette` (contraste ≥ 7:1 en los tres temas) | RF-21 |
| `app` | `StatusBar` y su `StatusBarViewModel` (en `SectionScaffold`); fecha, hora y satélites en vista en la tarjeta Estado; pantalla Sesiones con importar, compartir y borrar; controles de reproducción en la tarjeta Conexión; fila "Espacio para sesiones" en Ajustes | RF-13–RF-23, RF-26, RF-29–RF-31, RF-33, RF-35, RF-37, RF-40–RF-43 |

Reglas de módulos sin cambios: `core:gnss` solo depende de `core:model`; `receiver` y `app` pueden usar cualquier `core`.

## Modelo de datos
### Frases (`core:gnss`)
Unidades SI y ángulos en radianes (AGENTS.md). Las latitudes y longitudes siguen en grados, como `GgaFix` (S002).

| Tipo | Campos |
|---|---|
| `GsaData` | `mode` (NONE, FIX_2D, FIX_3D), `constellation`, `satellitesUsed: List<Int>`, `pdop`, `hdop`, `vdop` (Double?) |
| `GsvData` | `constellation`, `signalId: Int?`, `totalMessages`, `messageNumber`, `satellitesInView`, `satellites: List<SatelliteView(prn, elevationRad?, azimuthRad?, snrDbHz?)>` |
| `RmcData` | `utcMillisOfDay`, `date: LocalDate?`, `valid`, `speedMps?`, `courseRad?` |
| `VtgData` | `courseRad?`, `speedMps?` (del campo en km/h; si falta, del de nudos) |
| `ZdaData` | `utcMillisOfDay`, `date: LocalDate` |
| `BasePosition` (EBP) | `latitudeDeg`, `longitudeDeg`, `ellipsoidalHeightM` |
| `TiltData` (ETC) | `utcMillisOfDay`, `state` (OFF, FATAL, SETUP, ALIGNMENT, COMPENSATING), `notification` (OFF, NONE, FAST_MOTION, BAD_GNSS, FILTER_FAULT), `headingRad?`, `tiltDirectionRad?`, `tiltRad?`, `accuracyCoefficients: Triple?` |

### Época
```
Epoch(
  utcMillisOfDay = 37_530_200,       // 10:25:30.20
  date = 2026-10-15 | null,          // RF-11
  gga, gst, rmc, vtg, zda, ebp, etc  // la última de cada tipo en la época (null si no llegó)
  gsa = { GPS: GsaData, GLONASS: … } // por constelación
  gsv = { (GPS, señal 1): […], … }   // por constelación y señal
  completedAtMs                       // reloj monótono
)
satellitesInView = Σ constelaciones de max(satellitesInView de sus grupos de señal)   // RF-15
```

### Sesión en disco (`receiver`)
```
files/sessions/
  20261015-101530/               ← inicio en UTC
    session.properties           ← Locale.ROOT
      source=RECORDED | IMPORTED
      receiver=reach-RS3          (o el nombre del fichero importado)
      startedAt=2026-10-15T10:15:30Z
      endedAt=2026-10-15T11:02:07Z  (se actualiza al cerrar y en cada vaciado)
    receiver-0001.nmea            ← bytes del receptor tal como llegan (S002/RF-42)
    receiver-0002.nmea            ← partes de 16 MB
    corrections-0001.rtcm3        ← bytes enviados al receptor (RF-24)
```
- Duración = `endedAt − startedAt`. En las importadas, la diferencia entre la primera y la última hora NMEA del fichero.
- Tamaño = suma de las partes.
- Al arrancar, los ficheros sueltos `sessions/*.nmea` de la S002 se mueven a su carpeta como `receiver-0001.nmea`, con `source=RECORDED`.
- La preferencia `session_space_mb` vive en el DataStore "receiver". Valores: 100, 250, 500 (por defecto), 1000 o 2000.

## Algoritmos
### Ensamblado de épocas (RF-8–RF-11)
```
estado: actual = {hora: null, frases: []}, últimaCompleta: hora?, últimaLlegada: ms, fecha: LocalDate?, horaDeLaFecha
alimentar(frase, ahora):
  t = hora de la frase (GGA, GST, RMC, ZDA, ETC); null en GSA, GSV, VTG, EBP
  si t ≠ null:
    si últimaCompleta ≠ null y t < últimaCompleta y (últimaCompleta − t) < 12 h: descartar y salir   // RF-10
    si actual.hora ≠ null y t ≠ actual.hora: completar(actual)                                       // RF-9
    actual.hora = t
  añadir la frase a actual; últimaLlegada = ahora
tic(ahora):   // cada 25 ms desde la sesión
  si actual.hora ≠ null y ahora − últimaLlegada ≥ 150 ms: completar(actual)                          // RF-9
completar(é):
  si é trae RMC o ZDA: fecha = su fecha; horaDeLaFecha = é.hora
  si no, si fecha ≠ null y é.hora < horaDeLaFecha: fecha += 1 día; horaDeLaFecha = é.hora           // medianoche
  emitir Epoch(é, fecha); últimaCompleta = é.hora; actual = vacío
reiniciar():  // vuelta al principio del bucle de reproducción
  descartar actual; últimaCompleta = null; fecha = null
```
- Una hora más de 12 h anterior a la última completa se interpreta como el paso de medianoche (RF-10).
- Las frases sin hora que llegan antes de la primera frase con hora de una ráfaga se quedan en `actual` y pasan a la época en cuanto llega esa hora.

### Ritmo de la reproducción (RF-36–RF-39; referencia: NMEA 0183)
```
entrada: bytes del fichero, velocidad (StateFlow ×1/×5), bucle (StateFlow), reloj monótono, dormir(ms)
anclaje = (instanteReal0, horaFichero0); última = null
por cada línea (hasta \n, con la basura binaria incluida):
  t = hora de la línea, si es GGA, GST, RMC, ZDA o ETC
  si t ≠ null y t ≠ última:
    si última ≠ null:
      Δ = t − última; si Δ < 0: Δ += 24 h               // medianoche
      Δ = min(Δ, 10 s)                                   // RF-38
      objetivo += Δ / velocidad                          // acumulado: sin deriva (±1 %)
      dormir(objetivo − ahora)
    última = t
  entregar la línea
al cambiar la velocidad: el objetivo se vuelve a anclar en la época siguiente                    // RF-37
al terminar: si bucle → avisar reinicio() y empezar de nuevo (RF-39); si no → fin de flujo (RF-40)
```

### Límite de espacio (RF-25–RF-28)
```
cada vaciado (≤ 1 s) y al empezar una sesión:
  si espacioLibre < 100 MB: cerrar la grabación, recording = false, aviso "Grabación detenida"   // RF-28
  total = Σ tamaños de todas las sesiones
  borrar sesiones completas de la más antigua a la más reciente mientras total > límite,
    sin borrar nunca la más reciente ni la que está en curso                                   // RF-25
  si la sesión en curso por sí sola > límite: borrar sus partes más antiguas,
    salvo la que se está escribiendo                                                            // RF-27
rotación: al pasar una parte de 16 MB se abre la siguiente
```
- Una sesión importada mayor que el límite se conserva mientras sea la más reciente.

## Contratos
### Sesión (`receiver`)
```kotlin
ReceiverSnapshot(
  connection, gga, gst, ntrip,          // S002: la GGA actualiza la posición al momento (latencia < 300 ms)
  epoch: Epoch?,                         // última época completa (RF-14, RF-15, RF-22)
  recording: Boolean,                    // RF-20
  recordingStoppedLowSpace: Boolean,     // RF-28
)
ConnectionState += Replaying(name, speed: ReplaySpeed, loop: Boolean), ReplayFinished(name)
NtripState += UnavailableInReplay       // RF-44
ReceiverSession.connect(transport)       // si transport.simulated: sin grabación (RF-45) y reinicio de épocas en el bucle
ReceiverSession.startReplay(...) / setReplaySpeed / setReplayLoop / stopReplay
```
- "Sin datos" (RF-12): `NoData` cuando pasan más de 2 s sin época completa. La sesión llama a `tic` cada 25 ms.
- En una reproducción, el fin del flujo pasa a `ReplayFinished`, no a `Lost`, y no vibra.

### Transportes
- `ReceiverTransport` añade `val simulated: Boolean = false`.
- `ReplayTransport(files, speed, loop, clock)` entrega por `input` los bytes con el ritmo de `ReplayPacer`. Su `output` descarta lo que se escriba.

### Interfaz (`app`)
- **Barra de estado** (debajo de la barra superior de cada sección):
  - chip de solución (`StatusChip` y el tono de S002) o "Sin receptor" o "Sin datos";
  - "N sat.";
  - "Correcciones: N,N s";
  - "Grabando" (icono y texto);
  - "Simulación" (tono `SIMULATION`);
  - "Inclinación: Apagada | Error | Configuración | Alineación | Compensando".

  Usa `FlowRow` para no recortarse con fuente al 150 % y se refresca a 5 Hz como máximo. Al pulsarla se abre Receptor (RF-16–RF-23).
- **Tarjeta Estado**: "Hora: 15/10/2026 12:25:30" en hora local, o "10:25:30 UTC · fecha no disponible"; "Satélites: 24 usados de 31 en vista".
- **Pantalla Sesiones** (ruta anidada en Receptor, se abre desde la tarjeta Sesión): filas con inicio, duración, tamaño y receptor o "Importada"; acciones Reproducir, Compartir y Borrar; la sesión en curso se marca "En curso" y no se puede borrar. Acción "Importar fichero NMEA" en la barra superior.
- **Tarjeta Conexión en reproducción**: "Reproduciendo <nombre>", botones ×1/×5, interruptor Bucle y botón Detener. Al terminar: "Reproducción terminada" y "Volver a reproducir". Si hay un receptor conectado, un diálogo de confirmación (RF-42).
- **Ajustes**: "Espacio para sesiones" con `SingleChoiceDialog` (100 MB, 250 MB, 500 MB, 1 GB, 2 GB).

Textos de error:

| Caso | Texto |
|---|---|
| Fichero sin NMEA válido | "El fichero no tiene frases NMEA con hora." |
| Fichero de más de 200 MB | "El fichero supera los 200 MB." |
| NTRIP durante una reproducción | "La reproducción no admite correcciones." |
| Poco espacio libre | "Grabación detenida: queda poco espacio libre en el móvil." |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| La posición se actualiza con la GGA al momento; la época completa añade el resto | Mantener la latencia < 300 ms de S002: esperar a cerrar la época añadiría hasta 150 ms | Mostrar solo épocas completas |
| Una época se cierra con un cambio de hora o con 150 ms de silencio | El orden y el número de frases dependen de la configuración del receptor; el silencio entre ráfagas a 5 Hz es de unos 190 ms | Fijar la última frase de cada época |
| El simulador es un `ReceiverTransport` más | El resto de la app no distingue la reproducción salvo por `simulated` (una sola fuente de verdad, ADR-11) | Un flujo paralelo para la reproducción |
| El ritmo se calcula acumulado desde un ancla | Sin deriva: a ×1 la duración difiere < 1 % | Dormir el intervalo de cada época |
| Una carpeta por sesión, con partes de 16 MB y metadatos `.properties` | Cumplir RF-27 sin reescribir ficheros y compartir por partes; Room llega en S007 | Un único fichero por sesión; una tabla de Room |
| Espacio para sesiones elegido entre 5 valores | Sin errores de escritura en campo | Campo numérico libre |
| Compartir con `ACTION_SEND_MULTIPLE` (las partes y las correcciones) | FileProvider ya configurado (S002) | Comprimir en ZIP (S012) |
| La barra de estado va dentro de `SectionScaffold` mediante una ranura | Aparece en todas las secciones sin repetir código | Repetirla en cada pantalla |
| Importar validando el contenido con `NmeaFramer` | RNF-T5 | Fiarse de la extensión o del tipo MIME |
| Ángulos nuevos en radianes | AGENTS.md (unidades SI) | Grados, como las latitudes de `GgaFix` |

## Estrategia de tests
- **Unitarios JVM `core:gnss`**:
  - parsers con frases del RS3 (talker GN y por constelación, NMEA 4.10 y 4.11, campos vacíos y corruptos);
  - `EpochAssembler` con tiempo virtual: GGA a 5 Hz con el resto a 1 Hz, silencio, desorden, medianoche, fecha;
  - `ReplayPacer` con reloj virtual: ×1/×5, cambio de velocidad, huecos de 10 s, medianoche, bucle y duración ±1 %.
- **Unitarios JVM `receiver`**:
  - `ReceiverSession` con épocas: "Sin datos" a 2 s; la latencia de la GGA no cambia; la época en el estado;
  - grabación de las correcciones byte a byte;
  - `SessionLogStore`: carpetas, partes, metadatos, límite, sesión en curso grande, espacio libre inyectado, migración de S002, importación (válida, binaria, mayor de 200 MB);
  - `ReplayTransport`: fin, bucle con reinicio de épocas, sin grabación, NTRIP no disponible.
- **Robolectric `app`**:
  - barra de estado: todos los estados, tonos, presencia en las tres secciones, pulsación;
  - tarjeta Estado: fecha, "fecha no disponible", satélites en vista, "Sin datos";
  - pantalla Sesiones: lista, compartir varias partes, borrar con confirmación, sesión en curso;
  - importación con un documento falso;
  - controles de reproducción y exclusión con el receptor;
  - espacio para sesiones en Ajustes.
- **Reproducción de principio a fin**: `tools/nmea/generar_rs3_completo.py` genera `fixtures/nmea/sintetico/rs3-completo.nmea`, con todas las frases del RS3, cambio de día y un hueco de 15 s, y su `.epocas.csv` esperado (hora, fecha, satélites usados y en vista, estado de la inclinación, coordenadas con pyproj). Se reproduce a ×5 con `ReplayTransport` por la sesión y el ViewModel reales y se compara.
- **Manual**: demo en el emulador con un fichero importado; demo con el RS3 real (barra de estado, grabación con correcciones, lista, compartir y reproducir).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1–RF-7 | Parsers de `core:gnss` | `GsaGsvParserTest`, `RmcVtgZdaParserTest`, `EbpEtcParserTest` |
| RF-8–RF-11 | `EpochAssembler` | `EpochAssemblerTest` |
| RF-12, RF-13 | `ReceiverSession`, tarjetas | `ReceiverSessionEpochTest`, `StatusCardTest`, `CoordinatesCardTest` |
| RF-14, RF-15 | Tarjeta Estado | `StatusCardTest` |
| RF-16–RF-23 | Barra de estado | `StatusBarTest`, `StatusBarNavigationTest`, `StatusPaletteTest` |
| RF-24 | Grabación de las correcciones | `ReceiverSessionRecordingTest` |
| RF-25–RF-28 | `SessionLogStore` | `SessionLogStoreTest` |
| RF-26 | Preferencia y Ajustes | `ReceiverPreferencesTest`, `SessionSpaceSettingTest` |
| RF-29–RF-31 | Pantalla Sesiones | `SessionsScreenTest` |
| RF-32–RF-34 | Reproducir e importar | `ReplayTransportTest`, `SessionImportTest` |
| RF-35, RF-37, RF-40–RF-43 | Tarjeta Conexión en reproducción | `ReplayControlsTest`, `ReceiverViewModelReplayTest` |
| RF-36, RF-38, RF-39 | `ReplayPacer`, `ReplayTransport` | `ReplayPacerTest`, `ReplayTransportTest` |
| RF-44, RF-45 | Sesión en modo simulación | `ReplayTransportTest` |
| Todos (de principio a fin) | Sesión sintética completa del RS3 | `FullRs3ReplayTest` |
