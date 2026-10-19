# Plan técnico — Spec 014 (log de la app e informe de sistema)

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/014-log-e-informe-de-sistema/spec.md`. Sin código.

## Estructura de módulos
- `core:model` (paquete `log`), sin Android:
  - `AppLog`: la interfaz con la que todos anotan; `AppLog.NONE` para los tests y las clases que no la reciben (RF-1 a RF-7);
  - `LogLine`: el formato de cada anotación (RF-1, RNF-T10);
  - `LogRetention`: cuántos ficheros sobran y de qué tamaño se cortan (RF-8, RF-9).
- `core:formats`: `BackupFileNames.systemReport`, el nombre propuesto del informe (RF-16).
- `data`:
  - `FileAppLog`: escribe el log en `filesDir/log/` desde un canal, en su propio hilo, y vuelca a disco como mucho cada segundo (RF-8, RF-9, RNF);
  - `LogPreferences`: el tamaño del log, entre 5 y 50 MB, 10 MB por defecto, en el DataStore "settings" (RF-8, RF-9);
  - `RoomPointRepository` y `BackupRepository` anotan los puntos guardados (RF-3) y las copias, restauraciones, deshacer e importaciones (RF-4).
- `app`:
  - `CrashLog`: el manejador de errores no capturados, instalado en `TopoPhoneApplication` (RF-6);
  - `ReceiverLogRecorder`: observa el estado de la sesión del receptor y anota las conexiones, las reproducciones, la NTRIP y sus avisos (RF-1, RF-2, RF-5);
  - los ViewModels que muestran avisos y errores los anotan (RF-5);
  - `SessionSelection`: qué sesiones entran en el informe (RF-12);
  - `SystemReportWriter`: el ZIP del informe (RF-11 a RF-15, RF-17);
  - en Ajustes, la sección "Diagnóstico": "Crear informe de sistema" y "Tamaño del log" (RF-9, RF-10, RF-17).

`receiver`, `core:gnss` y `core:ntrip` no cambian: el log mira su estado desde fuera.

## Modelo de datos

### Una anotación
```
2026-10-19T09:15:52.123Z  INFO   receptor  Conectado: RS3 Ana
2026-10-19T09:16:10.004Z  AVISO  ntrip     Cortada (sin datos); reintento 1
2026-10-19T09:20:31.871Z  INFO   puntos    Guardado 12 en «Calle Mayor 12»: FIX
2026-10-19T09:31:02.540Z  ERROR  app       Cierre inesperado: java.lang.IllegalStateException: … (pila completa en las líneas siguientes)
```
- Hora UTC con milisegundos (RNF-T10); nivel `INFO`, `AVISO` o `ERROR`; categoría `receptor`, `ntrip`, `puntos`, `copias` o `app`; el texto.
- Los textos son constantes en español en el código, no recursos: no cambian con el idioma de la app (suposición de la spec).
- Un salto de línea dentro del texto (la pila de un error) se escribe con una sangría de dos espacios, para que cada anotación empiece por su fecha.

### Ficheros del log
`filesDir/log/app-<AAAAMMDD-HHMMSS>.log`, en UTF-8. Cada fichero se corta al llegar a la décima parte del límite (al menos 512 kB). Cuando el total pasa del límite, se borran los más antiguos (RF-8). El fichero en uso no se borra nunca.

### Tamaño del log
Clave `log_size_mb` (Int) del DataStore "settings", con las opciones 5, 10, 20 y 50 MB y 10 MB por defecto. Viaja en la copia de seguridad como el resto de ese DataStore (S013; suposición de la spec).

### El informe
`TopoPhone_informe_<AAAAMMDD-HHMM>.zip`:

| Entrada | Contenido |
|---|---|
| `informe.txt` | Datos de la app y del móvil, ajustes, perfiles NTRIP sin usuario ni contraseña, y la lista de sesiones incluidas (RF-13, RF-14) |
| `log/app-….log` | Todos los ficheros del log, del más antiguo al más reciente (RF-11) |
| `sesiones/<id>/…` | Los ficheros de las sesiones elegidas, tal como se graban (RF-12) |

```
Informe de sistema de TopoPhone
Creado: 2026-10-19T09:40:12Z
Zona horaria del móvil: Europe/Madrid
App: 0.14.0 (commit 0123456789ab)
Móvil: Google Pixel 7, Android 15 (API 35)
Espacio libre: 18 432 MB
Permisos: BLUETOOTH_CONNECT=sí, POST_NOTIFICATIONS=no, BLUETOOTH_SCAN=no
Sin optimización de batería: sí
Último receptor: RS3 Ana

Ajustes
  collector.collector_mode = AVERAGED
  settings.log_size_mb = 10
  …

Perfiles NTRIP
  ERGNSS (IGN): ergnss-tr.ign.es:2101, TLS no, punto de montaje VRS3M

Sesiones incluidas
  20261019-091500 (12,3 MB)
  20261018-171000 (11,9 MB, solo la parte más reciente)
```

## Algoritmos

### A1. Escribir sin bloquear (RF-1 a RF-6, RNF)
1. `record(nivel, categoría, texto)` pone la anotación con su hora en un canal sin límite y vuelve: no bloquea a quien anota (RNF-T13).
2. Un único lector en `Dispatchers.IO` escribe en el fichero en uso con un búfer; vuelca a disco si han pasado 1 s desde el último volcado o si el canal se queda vacío (RNF: lo anotado hasta 1 s antes de un cierre está en el log).
3. Antes de escribir, si el fichero en uso pasa del tamaño de corte, abre otro y aplica `LogRetention` (A2).
4. `flushNow()` escribe lo que haya en el canal y vuelca, en el hilo que lo llama: lo usa el manejador de errores (RF-6).

### A2. Retención (RF-8, RF-9)
- Tamaño de corte = `max(límite / 10, 512 kB)`.
- Con los ficheros del más antiguo al más reciente, se borran desde el principio mientras el total pase del límite, sin borrar nunca el último.
- Al cambiar el límite (RF-9) se aplica en ese momento: `FileAppLog` escucha `LogPreferences.sizeMb`.

### A3. Cierre inesperado (RF-6)
`Thread.setDefaultUncaughtExceptionHandler`: anota `ERROR app` con la excepción y su pila, llama a `flushNow()` y pasa el error al manejador anterior (el del sistema, que cierra la app).

### A4. Lo que anota `ReceiverLogRecorder` (RF-1, RF-2, RF-5)
Observa `ReceiverSession.state` con `distinctUntilChanged` sobre cada parte:

| Cambio | Nivel | Texto (ejemplo) |
|---|---|---|
| `Connecting(d)` | INFO | Conectando: d |
| `Receiving(d)` | INFO | Conectado: d |
| `NoData` | AVISO | Sin datos del receptor (más de 2 s) |
| `Lost` / `Reconnecting(attempt, bluetoothOff)` | AVISO | Enlace perdido; reintento n (Bluetooth apagado) |
| `Failed(BUSY / TIMEOUT / ERROR)` | ERROR | No se pudo conectar: otro dispositivo conectado / tiempo agotado / error |
| `Disconnected` | INFO | Desconectado |
| `replay` empieza o termina; `ReplayFinished` | INFO | Reproducción: nombre, velocidad; terminada |
| `nmeaMissing` pasa a `true` | AVISO | 10 s sin frases NMEA válidas (S004/RF-28) |
| `recordingStoppedLowSpace` pasa a `true` | AVISO | Sesión sin grabar por falta de espacio |
| NTRIP `Connecting` / `Streaming` (al empezar) / `Idle` | INFO | Conectando al caster / Recibiendo correcciones / Cerrada |
| NTRIP `Stopped(f)` / `Reconnecting(n, f)` | ERROR / AVISO | Rechazada: usuario o contraseña no válidos… / Cortada (f); reintento n |
| `correctionsAlarm` pasa a `true` | AVISO | Correcciones con más edad que el umbral |

Los estados no llevan el usuario ni la contraseña del perfil, así que el texto no puede llevarlos (RF-7).

### A5. Avisos y errores que ve el usuario (RF-5)
Además de los de A4, anotan los que muestran:
- el recolector: los errores al medir o guardar y la medida fuera de tolerancia (S010);
- la exportación CSV: no se ha podido exportar (S012);
- las copias y los paquetes: los motivos de rechazo, no se ha podido guardar, restaurar o importar, y la copia automática fallida (S013);
- la importación de sesiones: fichero rechazado y su motivo (S003).

Las indicaciones de los formularios (nombre vacío, número no válido) no son fallos de funcionamiento y no se anotan.

### A6. Sesiones del informe (RF-12)
1. Solo las grabadas en el móvil; las importadas no (suposición de la spec).
2. De la más reciente a la más antigua, la sesión en curso la primera: se añade entera mientras quepa en lo que queda de los 25 MB.
3. Si la primera no cabe entera, se añaden sus partes más recientes (las del receptor y las de las correcciones, de la última hacia atrás) mientras quepan, y se marca "solo la parte más reciente".
4. La sesión en curso se lee tal como está en ese momento: sus partes cerradas y lo escrito de la parte abierta.

### A7. Escribir el informe (RF-10 a RF-17)
1. `informe.txt` (A1 del modelo), los ficheros del log tras `flushNow()` y los de las sesiones elegidas, en un ZIP temporal de la caché.
2. Copiarlo al sitio elegido o compartirlo desde `cache/exports/` (como el CSV y los paquetes).
3. Si falla, borrar el documento a medias e indicar que no se ha guardado (RF-17).

## Contratos

```text
enum LogLevel { INFO, WARNING, ERROR }
enum LogCategory { RECEIVER, NTRIP, POINTS, BACKUPS, APP }
interface AppLog {
  fun record(level: LogLevel, category: LogCategory, message: String)
  fun flushNow()
  companion object { val NONE: AppLog }
}

class FileAppLog(dir: File, sizeMb: Flow<Int>, scope: CoroutineScope, clock: () -> Long) : AppLog {
  fun files(): List<File>        // del más antiguo al más reciente
}

interface SystemReportWriter {
  suspend fun write(out: OutputStream): Boolean   // RF-11..RF-15, RF-17
}
```

- **Ajustes, sección "Diagnóstico":** "Crear informe de sistema", que abre un diálogo con "Guardar" y "Compartir", y "Tamaño del log" con el valor elegido (5 MB, 10 MB, 20 MB o 50 MB).
- **Mensajes:** "Informe guardado." y "No se ha podido crear el informe."
- **Tipo MIME** del informe: `application/zip`.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| El log mira el estado de la sesión del receptor desde `app` | `receiver`, `core:gnss` y `core:ntrip` no cambian, y sus tests tampoco; el estado ya tiene las causas | Llamadas al log dentro de `ReceiverSession` y del cliente NTRIP: toca código ya probado y mete el log en el núcleo de la conexión |
| `AppLog` en `core:model`, sin Android | La usan `data` y `app`; el formato y la retención se prueban en la JVM (constitución nº 6) | La interfaz en `data`: el formato y la retención irían en un módulo Android |
| Canal sin límite y un solo lector en IO | Anotar nunca bloquea la interfaz ni el guardado de un punto (RNF-T13); un solo escritor no necesita cerrojos | Escribir en el hilo que anota: el disco podría retrasar el guardado de un punto |
| Volcado a disco como mucho cada segundo, y al momento ante un cierre inesperado | Cumple "hasta 1 s antes de un cierre" sin un `fsync` por anotación | Volcar cada anotación: miles de escrituras con el receptor a 5 Hz |
| Textos del log en español, constantes en el código | El log no cambia con el idioma de la app (suposición de la spec); no son textos de la interfaz | Recursos `strings.xml`: el log saldría en el idioma de la interfaz de cada momento |
| Ficheros cortados a la décima parte del límite | Borrar lo más antiguo es borrar ficheros enteros, sin reescribir el log | Un único fichero que se recorta por delante: hay que reescribirlo entero |
| Informe en ZIP con un `informe.txt` legible | Se abre en cualquier ordenador; las sesiones van tal como se graban, listas para reproducirlas (S003) | JSON: menos legible para quien lo recibe |
| Sin dependencias nuevas | `java.util.zip`, corrutinas y DataStore ya están en el proyecto | Timber u otra biblioteca de log: no aporta nada con un solo destino |

## Estrategia de tests
- **Unitarios JVM (`core:model`, `core:formats`):** `LogLineTest` (formato, UTC, saltos de línea con sangría), `LogRetentionTest` (qué se borra con cada límite; el fichero en uso nunca), `BackupFileNamesTest` (nombre del informe, RF-16).
- **Robolectric `data`:**
  - `FileAppLogTest`: orden; volcado antes de 1 s sin cerrar; `flushNow`; corte de ficheros y retención; cambio de límite en el momento (RF-8, RF-9, RNF);
  - `LogPreferencesTest` (5 a 50 MB, 10 MB por defecto);
  - `PointRepositoryLogTest` (RF-3, sin coordenadas) y `BackupLogTest` (RF-4).
- **Robolectric `app`:**
  - `CrashLogTest`: un error no capturado queda anotado y llega al manejador anterior (RF-6);
  - `ReceiverLogRecorderTest`: con una `ReceiverSession` real y un transporte de prueba, cada transición de A4 (RF-1, RF-2, RF-5);
  - los avisos de A5, en los tests de cada pantalla (`CollectorViewModelTest`, `ExportViewModelTest`, `SessionsViewModelTest`, `AutoBackupSchedulerTest`, `BackupsViewModelTest`, `PackageImportTest`) (RF-5);
  - `SessionSelectionTest`: 25 MB, la sesión en curso primero, la parte más reciente si no cabe, sin importadas (RF-12);
  - `SystemReportWriterTest`: entradas del ZIP, `informe.txt`, fallo al escribir (RF-11 a RF-17);
  - `DiagnosticsSettingsTest`: la sección de Ajustes, el diálogo y el tamaño del log (RF-9, RF-10);
  - `NoSecretsTest`: tras una conexión NTRIP rechazada con un perfil con usuario y contraseña, ni el log ni ningún fichero del informe los contienen (RF-7, RF-15).
- **Rendimiento:** con 25 MB de sesiones, el informe en menos de 10 s; 10 000 anotaciones seguidas no retrasan el guardado de un punto más de 50 ms (Robolectric como cota).
- **Manual:** demo en el emulador de los criterios de finalización; la puerta G1 es del usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A4 | `ReceiverLogRecorderTest` |
| RF-3, RF-4 | Repositorios de `data` | `PointRepositoryLogTest`, `BackupLogTest` |
| RF-5 | A4, A5 | `ReceiverLogRecorderTest` y los tests de cada pantalla (A5) |
| RF-6 | A3 | `CrashLogTest` |
| RF-7 | A4, textos sin secretos | `NoSecretsTest`, `PointRepositoryLogTest` |
| RF-8, RF-9 | A2, `LogPreferences` | `LogRetentionTest`, `FileAppLogTest`, `DiagnosticsSettingsTest` |
| RF-10 | Ajustes | `DiagnosticsSettingsTest` |
| RF-11 a RF-15 | A6, A7 | `SessionSelectionTest`, `SystemReportWriterTest`, `NoSecretsTest` |
| RF-16 | `BackupFileNames.systemReport` | `BackupFileNamesTest` |
| RF-17 | A7 paso 3 | `SystemReportWriterTest`, `DiagnosticsSettingsTest` |
