# Plan técnico — Spec 043

> Lee antes: `docs/constitution.md` (nº 5: nada de APIs no documentadas), `docs/arquitectura.md`, `docs/rnf-transversales.md` (RNF-T4), `specs/043-receptor-guiado/spec.md`, `specs/015-estado-receptor-detallado/plan.md` (base y RTCM), `specs/016-inclinacion-rs3/spec.md` (ETC y modo), `specs/021-conexion-wifi-tcp/plan.md` (claves y red Wi-Fi), `specs/022-gnss-movil-y-receptor-virtual/plan.md` (fuentes) y `specs/046-antena-y-jalon/plan.md` (catálogo).

Sin dependencias nuevas, ni Gradle ni Python: la vista web es `android.webkit.WebView` de la plataforma (sin `androidx.webkit`) y el servidor web de la demo es Python de la biblioteca estándar. **Room pasa de la v35 a la v36.** **Ninguna escritura en el receptor** (RF-11): la guía solo lee lo que ya llega y muestra texto.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Quién calcula las comprobaciones (RF-5 a RF-10) | `ReceiverChecker` en `core:survey` (Kotlin puro, sin Android): recibe una `ObservationWindow` y el contexto (modo de inclinación, datum del proyecto, estado del NTRIP) y devuelve un `ReceiverCheck` por comprobación con su `CheckStatus` (`OK`, `WARNING`, `FAIL`, `NO_DATA`), el valor medido y la causa probable. `ObservationWindow(windowMs = 10_000)` guarda, con el reloj monótono de la sesión, las épocas completas (`SessionEpoch`) y una foto por segundo de las estadísticas RTCM; no lee el receptor. | Calcularlo dentro del ViewModel. Descartada: no se probaría con sesiones grabadas sin Android (RNF-T12). |
| D2 | De dónde salen las épocas (RF-5) | Un `ReceiverWindowRecorder` (en `app`, único para toda la app) se suscribe a `ReceiverSession.epochs` y a `state` (para las RTCM y el NTRIP) mientras hay una fuente conectada y rellena la ventana; «Comprobar» solo lee la ventana ya llena, así que termina en menos de 1 s (RNF). Con una reproducción o el receptor virtual funciona igual. | Pedir las épocas al pulsar. Descartada: habría que esperar 10 s. |
| D3 | Las comprobaciones y sus umbrales (RF-6, RF-7, RF-8) | **GGA**: `FAIL` sin ninguna en la ventana, `WARNING` por debajo de 1 Hz. **Interpretación del caso límite «GGA a 1 Hz con GNSS a 5 Hz»:** si otra frase con hora (GST, RMC, ZDA o ETC) llega al menos el doble de deprisa que la GGA, `WARNING` con la causa «la salida de posición está a menos frecuencia que el GNSS». **Frases**: `FAIL` si falta la GGA; `WARNING` si falta GST, GSV, ZDA o RMC. **Solución**: `FAIL` sin posición, `WARNING` si no es FIX (con el tipo actual), `OK` si es FIX. **Edad de las correcciones**: `OK` hasta 5 s, `WARNING` hasta 30 s, `FAIL` más (S007/RF-29 usa 30 s por defecto), y `NO_DATA` si no hay NTRIP ni base. **Hora UTC**: `OK` si las épocas traen hora y fecha (ZDA o RMC) y crece de una en una; `FAIL` si no traen hora. **Inclinación** (solo con ETC en la ventana): estado `COMPENSATING`, sin aviso, y modo elegido y comprobado (S016/RF-2, RF-10). **RTCM** (solo con NTRIP): 1005 o 1006, observaciones (MSM 4 a 7 o 1004/1012) y GLONASS (1012 o MSM de GLONASS) solo si el receptor da satélites GLONASS en las GSV. Una comprobación que no aplica (sin ETC, sin NTRIP) no sale. | Umbrales por lista. Descartada: la spec los da globales. |
| D4 | El datum de las correcciones (RF-8, decidido en la entrevista) | `ReferenceStationDecoder` (S015) deja de saltar el año de realización ITRF de 1005/1006 (6 bits tras el identificador) y lo guarda en `ReferenceStation.itrfYear` (0 = sin declarar). La comprobación: con año distinto de 0, el datum de las correcciones es ITRF/WGS84 y se compara con `Project.correctionsDatum` (`WARNING` si el proyecto declara ETRS89 o REGCAN95, `OK` si declara ITRF/WGS84); con 0, `WARNING` «No se puede comprobar» con el datum declarado del proyecto. | Quitar la comprobación. Descartada por ti. |
| D5 | Las listas (RF-1 a RF-4) | Datos estáticos en `core:model` (`ConfigLists`): cuatro `ConfigList` con sus `ConfigStep(id, checkId?)`. Los textos (título, qué ajuste y en qué pantalla del receptor, la nota de firmware) están en `strings.xml` en es y en, nombrados `guide_<lista>_<paso>_*`. Contenido inicial de cada lista y su redacción, en una tabla en `plan.md` (más abajo); lo valida el usuario en la demo de campo. Las listas se redactan **sin versión de firmware** (entrevista). | Cargarlas de un fichero. Descartada: los textos van en recursos (RNF-T7). |
| D6 | Progreso de las listas y perfil de equipo (RF-3, RF-18) | Room v36, `AutoMigration(35, 36)`: `config_steps(receiver_key, list_id, step_id, done_at)` y `equipment_profiles(receiver_key PK, model, serial, firmware)`; la clave es la de S021 (dirección Bluetooth o `wifi:<id>`). Entran en la copia completa (`DatabaseSnapshot.TABLES`, sin filtro de proyecto, como `wifi_receivers`). | DataStore. Descartada: hay varios receptores y se copian con el resto. |
| D7 | El perfil de equipo en los puntos (RF-19, RF-20) | `Measurement.equipment: EquipmentProfile?` (nulo en los puntos de antes y con el móvil, el virtual o una reproducción) y tres columnas nuevas en `points` (`equipment_model`, `equipment_serial`, `equipment_firmware`) en la misma migración v36. El recolector y el control de base lo leen de `CurrentEquipment` al empezar a medir. | Una tabla aparte. Descartada: el punto es una fila. |
| D8 | Exportación (RF-20, entrevista: CSV, GeoPackage y Shapefile) | Tres `ExportColumn` nuevas (`EQUIPMENT_MODEL`, `EQUIPMENT_SERIAL`, `EQUIPMENT_FIRMWARE`, cabeceras «Receiver model», «Receiver serial», «Receiver firmware») **tras `AUTOMATIC` y antes de `EDITED`**, como dice S012/RF-6. GeoPackage: tres atributos de texto. Shapefile: `RX_MODEL`, `RX_SERIAL`, `RX_FW` (hasta 10 caracteres). **El KML y la pantalla de exportar ya recorren `ExportColumn.entries`, así que también las llevan** (KML en `ExtendedData`; la pantalla, tres filas más en «Personalizado» con su texto). Hay que regenerar los ficheros de referencia (`fixtures/formatos/csv`, `gpkg-exportado`, `shp-exportado`, `kml-exportado`) con los generadores de `tools/formatos` y sus tests. | Solo el CSV. Descartada por ti. |
| D9 | Interfaz web (RF-12 a RF-17) | `WebView` en una pantalla propia con: JavaScript y almacenamiento DOM activos (el Reach Panel es una aplicación web), sin acceso a ficheros ni contenido, sin cookies de terceros, sin `addJavascriptInterface`, sin contenido mixto. Solo se carga una URL `http://<IP privada>:<puerto>/`; `shouldOverrideUrlLoading` y `shouldInterceptRequest` bloquean cualquier petición cuya dirección no sea una IP privada (10/8, 172.16/12, 192.168/16) o de enlace local (169.254/16) y no siguen redirecciones a otra dirección. **Wi-Fi:** puerto 80 de la dirección guardada del receptor. **Bluetooth:** se pide una IP (se valida igual, sin nombres) y se recuerda por receptor en `equipment_profiles.web_address`. Un temporizador de 10 s sin `onPageFinished` muestra las causas probables con «Reintentar» y «Abrir en el navegador». | `androidx.webkit` o una pestaña personalizada. Descartada: dependencia nueva sin necesidad. |
| D10 | **Tráfico en claro hacia el panel (tu OK)** | Android bloquea HTTP sin cifrar por defecto y `network-security-config` no admite rangos de IP (solo nombres y direcciones sueltas), así que no se puede permitir solo lo privado a nivel de plataforma. Propongo `android:usesCleartextTraffic="true"` en el manifiesto y **hacer cumplir RNF-T4 en el código**: el `WebViewClient` de D9 bloquea todo lo que no sea una IP privada, y los demás clientes HTTP de la app ya validan o usan HTTPS (S036: `WmsAddress` solo admite HTTP privado; mapas, NTRIP y el resto, HTTPS o sockets). Lo reviso en la tarea con un test que recorre los sitios que abren conexiones. | Un servicio local que haga de proxy HTTP. Descartada: mucho más código y una superficie nueva. |
| D11 | Por la red del receptor (RF-15) | `NetworkRouter.bindProcessToWifi(): Closeable` (en `receiver/platform`): pide la red Wi-Fi sin exigir internet (S021) y la enlaza al proceso con `ConnectivityManager.bindProcessToNetwork` mientras la vista está abierta; al cerrarla se desenlaza. El NTRIP y la posición de un receptor Wi-Fi ya van por su socket explícito, así que no cambian; lo demás de la app (mapas, internet) usa el Wi-Fi sin internet mientras el panel está abierto, y se dice en pantalla. La conexión de posición no se toca (RF-17): la sesión vive en el servicio y no en la pantalla. | `WebView.setNetwork` (no existe). |
| D12 | Fuentes (RF-22, RF-23) | `Guía`, `Datos del receptor` y `Abrir interfaz web` solo con una clave real (dirección Bluetooth o `wifi:`); con `phone` o `virtual` el menú los sustituye por un texto «no aplica a esta fuente». `Comprobar` también con `replay` y `virtual`, pero no con `phone`. El perfil de equipo no se guarda con puntos del virtual, del móvil ni de reproducciones. | — |
| D13 | Propuesta de perfil de antena (RF-21) | Al elegir en «Datos del receptor» un modelo del catálogo de S046 (`AntennaCatalog`), una fila «Usar su perfil de antena» (botón) lo propone y, al pulsarla, llama a `CurrentGear.setProfile`; no se cambia solo y no se toca si el receptor ya tiene uno elegido hasta que el usuario pulse. | Cambiarlo solo. Descartada por RF-21. |
| D14 | Menú (S005/RF-9) | Ocho entradas en este orden: Estado, Coordenadas, Antena y jalón, Inclinación, Correcciones, Sesiones grabadas, **Guía** y **Datos del receptor**. «Comprobar» está solo dentro de la Guía. | — |
| D15 | Toques al código de specs protegidas | Mínimos: `ReferenceStationDecoder` (S015, año ITRF); `ReceiverMenu`, `ReceiverPages`, `ReceiverScreen`/`TopoPhoneApp` (S005, entradas y rutas); `CollectorViewModel` y `BaseShiftViewModel` (S010 y S020: el perfil al empezar la medida); `PointDetailsScreen` (S010: la fila de equipo); `TopoPhoneDatabase` y `DatabaseSnapshot.TABLES` (S006 y S013: v36 y dos tablas); `CsvExport`, `GpkgPointAttributes`, `GpkgExportSchema`, `ShpColumnNames`, `KmlExportBuilder` y `ExportScreen` (S012, S053, S055, S031: las tres columnas) y sus ficheros de referencia; `AndroidManifest.xml` (D10). Los `spec.md` ya aprobados (S005 RF-9 y S012 RF-6) cambiaron al aprobar. | — |

## Estructura de módulos
- `core:model`: `EquipmentProfile`, `ConfigList`, `ConfigStep`, `ConfigLists` (D5), `CheckId`, `CheckStatus`, `ReceiverCheck`, `PrivateAddress` (valida las IP de D9), `Measurement.equipment` (D7).
- `core:gnss`: `ReferenceStation.itrfYear` y su lectura en `ReferenceStationDecoder` (D4).
- `core:survey`: `ObservationWindow` y `ReceiverChecker` (D1, D3, D4).
- `data`: `config/ConfigProgressRepository`, `equipment/EquipmentRepository` (D6), `db/TopoPhoneDatabase` v36, `backup/DatabaseSnapshot.TABLES`, columnas de `points` (D7).
- `core:formats`: las tres columnas en `csv/CsvExport`, `gpkg/*`, `shp/export/*` y `kml/export/*` (D8).
- `receiver`: `platform/NetworkRouter.bindProcessToWifi` y su versión Android (D11).
- `app`:
  - `receiver/ReceiverWindowRecorder`, `CurrentEquipment` (D2, D7), manifiesto (D10), `di/ReceiverModule`.
  - `ui/guide/`: `GuideScreen` y `GuideViewModel` (listas, pasos y comprobaciones).
  - `ui/web/`: `ReceiverWebScreen`, `ReceiverWebViewModel` y `WebAddressPolicy` (D9).
  - `ui/equipment/`: «Datos del receptor» (formulario y catálogo, D13).
  - `ui/receiver/ReceiverMenu`, `ReceiverPages`, `TopoPhoneApp` (rutas); `ui/points/PointDetailsScreen`; textos `guide_*`, `equipment_*`, `web_*` en es y en.
- `tools`: `simulador/servidor_web.py` y su test (el Reach Panel falso de la demo).

## Modelo de datos
- `EquipmentProfile(model: String, serial: String, firmware: String)`; en `equipment_profiles(receiver_key TEXT PK, model TEXT, serial TEXT, firmware TEXT, web_address TEXT NULL)`.
- `config_steps(receiver_key TEXT, list_id TEXT, step_id TEXT, done_at INTEGER, PRIMARY KEY(receiver_key, list_id, step_id))`: una fila por paso hecho; desmarcar borra la fila y «Reiniciar» borra las de la lista.
- `points`: `equipment_model`, `equipment_serial`, `equipment_firmware` (texto, NULL en los de antes).
- `ReceiverCheck(id: CheckId, status: CheckStatus, measured: String?, cause: CheckCause?, stepId: String?)`; `CheckCause` es un identificador (el texto está en recursos). `CheckId`: `GGA_RATE`, `SENTENCES`, `SOLUTION`, `CORRECTION_AGE`, `UTC_TIME`, `TILT`, `RTCM_BASE`, `RTCM_OBSERVATIONS`, `RTCM_GLONASS`, `DATUM`.

## Las cuatro listas (contenido inicial, textos propios, sin versión de firmware)
Cada paso dice **qué ajuste** y **en qué pantalla del receptor**; los marcados con ✔ llevan comprobación.
- **Rover con NTRIP de la app:** 1. Salida de posición por Bluetooth o TCP activada ✔ (`GGA_RATE`, `SENTENCES`); 2. Frecuencia de la posición a 1 Hz como mínimo ✔; 3. Mensajes NMEA GGA, GST, GSA, GSV, ZDA y RMC activados ✔; 4. Constelaciones del receptor iguales a las del caster; 5. Modo RTK en «cinemático» (rover); 6. Hora UTC del receptor correcta ✔ (`UTC_TIME`); 7. Correcciones: perfil NTRIP de la app con su punto de montaje ✔ (`CORRECTION_AGE`, `RTCM_BASE`, `RTCM_OBSERVATIONS`, `RTCM_GLONASS`); 8. Datum de las correcciones declarado en el proyecto ✔ (`DATUM`); 9. Solución FIX alcanzada ✔ (`SOLUTION`).
- **Rover por Wi-Fi:** 1. Wi-Fi del receptor en modo punto de acceso o cliente de la red del móvil; 2. IP y puertos del receptor anotados en TopoPhone (S021); 3. Salida de posición por TCP activada y sin otros clientes conectados ✔ (`GGA_RATE`); 4. Puerto de correcciones del receptor (o enviarlas por otra vía); 5. Hora UTC ✔; 6. Mensajes NMEA ✔.
- **Rover con inclinación:** 1. Receptor con IMU calibrada (movimiento en ocho); 2. Salida ETC activada ✔ (`TILT`); 3. Modo de inclinación elegido y comprobado en TopoPhone ✔ (`TILT`); 4. Posición a 5 Hz (la inclinación compensa a la frecuencia de posición) ✔ (`GGA_RATE`, D3); 5. Altura de antena y jalón en TopoPhone (S046).
- **Base en un punto conocido:** 1. Coordenadas de la base escritas en el receptor; 2. Posición de la base como mensajes RTCM 1005 o 1006 ✔ (`RTCM_BASE`); 3. Observaciones MSM de las constelaciones usadas ✔ (`RTCM_OBSERVATIONS`); 4. Salida de las correcciones (NTRIP local, LoRa o la que se use), solo guía; 5. Datum de las coordenadas de la base ✔ (`DATUM`).

## Algoritmos
### A1. La ventana (RF-5, D1, D2)
1. `ReceiverWindowRecorder.onEpoch(SessionEpoch, nowMs)` añade la época; se descartan las de más de 10 s.
2. Cada segundo guarda `RtcmStats.counts` del NTRIP en curso con su instante; el delta entre la primera y la última foto de la ventana dice qué tipos llegaron.
3. `window.snapshot(nowMs)` devuelve épocas, deltas RTCM y el tiempo realmente cubierto (la tasa se calcula sobre ese tiempo, no sobre 10 s).
### A2. Una comprobación (RF-6 a RF-10)
1. Sin ninguna época y la conexión abierta: todas `NO_DATA` con la causa «sin datos» que remite al diagnóstico de S004 (RF-10).
2. `GGA_RATE`: `rate = epochsWithGga / coveredSeconds`; `FAIL` si 0; `WARNING` si `rate < 1`; si otra frase con hora (GST, RMC, ZDA, ETC) tiene una tasa de al menos 2× la de la GGA, `WARNING` con la causa `GGA_SLOWER_THAN_GNSS`.
3. `SENTENCES`: presencia de GST, GSV, ZDA y RMC en alguna época; cada una que falta es una causa.
4. `SOLUTION`, `CORRECTION_AGE`, `UTC_TIME` como en D3; `TILT` solo si algún `epoch.tilt != null`.
5. `RTCM_*` solo si el NTRIP está abierto; `DATUM` con `ReferenceStation.itrfYear` (D4).
6. Cada fallo o aviso trae `stepId`: el paso de la lista que lo resuelve (RF-9), tomado de `ConfigLists`.
### A3. Dirección permitida (RF-14, D9)
`PrivateAddress.parse(text)`: cuatro números 0–255 separados por puntos y un puerto opcional; válida si cae en 10/8, 172.16/12, 192.168/16 o 169.254/16. Cualquier otra cosa (nombres, IPv6, direcciones públicas, `127.x`) es inválida. La URL cargada es `http://<ip>[:puerto]/`.
### A4. Carga de la vista (RF-16)
Al cargar se arranca un temporizador de 10 s; `onPageFinished` lo cancela; si vence o hay `onReceivedError` en la página principal se pasa a `Failed(causes)`: móvil fuera de la red del receptor, IP o puerto equivocados, receptor sin interfaz web o con otro cliente. «Reintentar» recarga; «Abrir en el navegador» lanza `ACTION_VIEW` con la misma URL.

## Contratos
- `ReceiverChecker.check(window: ObservationWindowSnapshot, context: CheckContext): List<ReceiverCheck>`; `CheckContext(tiltMode: TiltMode?, tiltVerified: Boolean, projectDatum: CorrectionsDatum?, ntripOpen: Boolean, glonassInView: Boolean)`.
- `ConfigProgressRepository`: `done(receiverKey, listId): Flow<Set<String>>`, `setDone(receiverKey, listId, stepId, done)`, `reset(receiverKey, listId)`.
- `EquipmentRepository`: `profile(receiverKey): Flow<EquipmentProfile?>`, `save(receiverKey, profile)`, `webAddress(receiverKey)` y `saveWebAddress`.
- `CurrentEquipment`: el perfil del receptor en uso (nulo con `phone`, `virtual`, reproducción o sin receptor).
- Textos de usuario (es y en): títulos de las listas, cada paso, causas, estados «Correcto», «Aviso», «Fallo», «Sin datos», «No se puede comprobar», la vista web y sus errores, «Datos del receptor» y «no aplica a esta fuente».

## Estrategia de tests
- Unitarios JVM (`core:gnss`): `ReferenceStationDecoderTest` con vectores 1005 y 1006 con y sin año ITRF.
- Unitarios JVM (`core:model`): `PrivateAddressTest` (los cuatro rangos y sus bordes: 172.15, 172.16, 172.31, 172.32, 169.254, 169.253, 192.168, 192.169, 127.0.0.1, nombres, IPv6, puertos), `ConfigListsTest` (ids únicos, cada paso comprobable apunta a una comprobación válida, cada texto existe en es y en).
- Unitarios JVM (`core:survey`): `ObservationWindowTest` y `ReceiverCheckerTest` con épocas sintéticas y con las sesiones de `fixtures/nmea` (`rs3-completo`, `rs3-corte`, `rs3-inclinacion`, `rs3-base-shift`): cada comprobación en `OK`, `WARNING`, `FAIL` y `NO_DATA`, incluido el caso de la GGA a 1 Hz con otras frases a 5 Hz y el datum con y sin año ITRF.
- Room (`data`): migración v35 a v36, `ConfigProgressRepositoryTest`, `EquipmentRepositoryTest`, copia de seguridad con las dos tablas y las tres columnas.
- Robolectric (`app`): `GuideViewModelTest`, `GuideScreenTest` (marcar, desmarcar, reiniciar, contadores), `ReceiverWindowRecorderTest`, `ReceiverWebViewModelTest` (temporizador de 10 s, reintento, dirección bloqueada), `ReceiverWebScreenTest` (ajustes del `WebView` y bloqueo de peticiones fuera de las privadas con `shadowOf`), `EquipmentScreenTest`, `CollectorEquipmentTest` (el punto guarda el perfil y uno posterior no lo cambia; no con el virtual ni el móvil), `PointEquipmentDetailsTest`, `GuideSourcesTest` (RF-22, RF-23), `GuideAccessibilityTest`.
- Formatos: `CsvExportTest` y los de GeoPackage, Shapefile y KML actualizados; `uv run --project tools pytest tools` con las referencias regeneradas y `servidor_web.py`.
- Manual: demo en el emulador con reproducciones y el receptor virtual (cada comprobación en Correcto, Aviso y Fallo) y la vista web contra `servidor_web.py` (en el emulador el PC es `10.0.2.2`, que es privada); la RAM de la vista con `adb shell dumpsys meminfo` (≤ 50 MB). El RS3 real queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | D5, D14 | `ConfigListsTest`, `GuideScreenTest` |
| RF-3, RF-4 | D5, D6 | `ConfigProgressRepositoryTest`, `GuideViewModelTest` |
| RF-5 | D1, D2, A1 | `ObservationWindowTest`, `ReceiverWindowRecorderTest` |
| RF-6, RF-7 | D3, A2 | `ReceiverCheckerTest` |
| RF-8 | D3, D4 | `ReceiverCheckerTest`, `ReferenceStationDecoderTest` |
| RF-9, RF-10 | A2 | `ReceiverCheckerTest`, `GuideViewModelTest` |
| RF-11 | Todo el plan (nada escribe en el receptor) | `GuideViewModelTest` (no hay `output.write`) |
| RF-12, RF-13 | D9 | `ReceiverWebViewModelTest`, `EquipmentRepositoryTest` |
| RF-14 | D9, D10, A3 | `PrivateAddressTest`, `ReceiverWebScreenTest` |
| RF-15, RF-17 | D9, D11 | `ReceiverWebScreenTest`, `NetworkRouterWebTest` |
| RF-16 | A4 | `ReceiverWebViewModelTest` |
| RF-18, RF-21 | D6, D13 | `EquipmentScreenTest`, `EquipmentRepositoryTest` |
| RF-19 | D7 | `CollectorEquipmentTest`, migración v36 |
| RF-20 | D8 | `CsvExportTest`, los de GeoPackage, Shapefile y KML, `PointEquipmentDetailsTest` |
| RF-22, RF-23 | D12 | `GuideSourcesTest` |

## Cambios en documentos anteriores
- `specs/005-estructura-interfaz/spec.md` (RF-9) y `specs/012-exportar-csv/spec.md` (RF-6): ya cambiados al aprobar la spec.
- `docs/glosario.md`: ya con «Lista de configuración», «Comprobación previa» y «Perfil de equipo» (entrada «Datos del receptor»).
- `specs/015-estado-receptor-detallado/plan.md`: el decodificador de la base lee ahora el año ITRF (D4); se anota en «Cambios al implementar» de S043.

## Cambios al implementar
_(Se rellena al cerrar la spec.)_

## Validación
_(Se rellena en la última tarea.)_
