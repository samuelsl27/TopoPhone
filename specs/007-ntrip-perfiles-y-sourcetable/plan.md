# Plan técnico — Spec 007

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-13, ADR-17), `specs/007-ntrip-perfiles-y-sourcetable/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `core:ntrip` | Lector de la tabla de fuentes; cliente de la tabla de fuentes; orden y búsqueda de puntos de montaje por distancia; TLS opcional y GGA solo si hace falta en `NtripClient`; fallo `TLS_INVALID` | RF-14 a RF-21, RF-27, RF-28 |
| `core:gnss` | Lector incremental de tramas RTCM 3 (CRC24Q) y recuento por tipo; clasificación de los tipos conocidos | RF-32, RF-33 |
| `core:model` | `NtripProfileDraft` y sus reglas (nombre, servidor, puerto); catálogo de casters públicos de España | RF-2, RF-3, RF-11 a RF-13 |
| `data` | Tabla `ntrip_profiles` (Room v2, migración 1→2 probada); contraseña cifrada con AES-GCM y clave del Android Keystore; `NtripProfileRepository`; paso de los datos de S002 a un perfil; preferencias de NTRIP (último perfil, si estaba abierta, umbral de la alarma) | RF-1 a RF-10, RF-26, RF-31 |
| `receiver` | En `ReceiverSession`: reconexión del NTRIP con espera creciente, estado `Reconnecting`, recuento RTCM y vigilancia de la edad de las correcciones; `Alerts.correctionsLost()` con sonido y vibración | RF-23 a RF-25, RF-29, RF-30, RF-32, RF-33 |
| `app` | Pantalla Correcciones con perfiles, estado y mensajes RTCM; formulario de perfil con elección de caster público y de punto de montaje; `CorrectionsController` (conectar, detener, arranque automático, reconectar al editar); aviso en el indicador de estado y en la notificación; umbral en Ajustes | RF-1 a RF-33 |

Sin dependencias nuevas: TLS con `javax.net.ssl` del JDK/Android y cifrado con `javax.crypto` + `AndroidKeyStore`. Alternativa descartada: `androidx.security:security-crypto` (`EncryptedSharedPreferences`), obsoleta desde 2025 y con un fichero aparte de la base de datos.

## Modelo de datos
```kotlin
// core:model
data class NtripProfileDraft(name, host, port = 2101, useTls = false, username = "", mountpoint = "")
enum class NtripProfileError { EMPTY_NAME, DUPLICATE_NAME, EMPTY_HOST, INVALID_PORT }
enum class CasterAccess { FREE_REGISTRATION, REGISTRATION, REQUEST, OPEN, PAID }
data class PublicCaster(id, host, port, access, signupUrl, openUsername?, openPassword?)  // useTls siempre false hoy

// core:ntrip
data class SourcetableStream(mountpoint, identifier, format, formatDetails, carrier, navSystems, network, country,
                             latitude?, longitude?, needsGga: Boolean, solution, authentication, fee, bitrate?)
data class NtripConfig(host, port, mountpoint, username, password, useTls = false, sendGga = true)

// core:gnss
data class RtcmStats(val counts: Map<Int, Long>, val crcErrors: Long)
```

Room `topophone.db`, **versión 2**: se añade la tabla y no se toca nada de la v1.

| Tabla | Columnas |
|---|---|
| `ntrip_profiles` | `id` (UUID), `name`, `host`, `port`, `use_tls`, `username`, `mountpoint`, `mountpoint_needs_gga` (nulable: no se sabe), `password_iv` (nulable), `password_ct` (nulable), `created_at`, `modified_at` |

- La contraseña se guarda solo cifrada:
  - `password_ct` = AES-256-GCM (etiqueta de 128 bits) con un IV aleatorio de 12 bytes guardado en `password_iv`;
  - la clave tiene el alias `ntrip_passwords` y se crea en el `AndroidKeyStore` sin exportar.
  - Contraseña vacía = columnas nulas.
- Unicidad del nombre (sin mayúsculas ni tildes, como en S006) en el repositorio, dentro de una transacción.
- DataStore del receptor, claves nuevas:
  - `ntrip_last_profile_id`;
  - `ntrip_was_active` (booleano);
  - `corrections_alarm_s` (entero 2–60, por defecto 10);
  - `ntrip_legacy_imported` (booleano).

Ejemplo:

| name | host | port | use_tls | username | mountpoint | mountpoint_needs_gga |
|---|---|---|---|---|---|---|
| ERGNSS (IGN): red | ergnss-tr.ign.es | 2101 | 0 | sgarcia | VRS3M | 1 |

## Catálogo de casters públicos (RF-11 a RF-13)
Comprobado el 16/10/2026 en la web de cada red y con su tabla de fuentes (Ningún caster ofrece TLS):

| id | Servidor | Puerto | Acceso | Alta / notas |
|---|---|---|---|---|
| `ign_network` | ergnss-tr.ign.es | 2101 | FREE_REGISTRATION | https://www.ign.es/web/ign/portal/gds-gnss-tiempo-real |
| `ign_station` | ergnss-tr.ign.es | 2102 | FREE_REGISTRATION | ídem |
| `rap_network` | rap.juntadeandalucia.es | 2101 | REQUEST | https://www.juntadeandalucia.es/institutodeestadisticaycartografia/rap/correcciones-diferenciales |
| `rap_station` | rap.juntadeandalucia.es | 2102 | REQUEST | ídem |
| `aragea` | ntrip.aragon.es | 2101 | FREE_REGISTRATION | https://gnss.aragon.es/altas.php |
| `rgapa` | rgapa.cartografia.asturias.es | 2101 | OPEN (`anonimo` / `anonimo`) | https://rgapa.cartografia.asturias.es/rtk.html |
| `xgaib` | xarxagnss.caib.es | 2101 | FREE_REGISTRATION | https://www.caib.es/sites/xgaib/ca/registre_xgaib/ (servidor comprobado con su tabla de fuentes) |
| `grafcan` | 195.53.241.146 | 2101 | PAID | https://www.grafcan.es/servicios/red-estaciones-gnss/alta-gnss/ |
| `rgac` | gnss.cantabria.es | 2101 | OPEN (`anonimo` / `anonimo`) | https://www.territoriodecantabria.es/cartografia-sig/red-activa-de-estaciones-gnss-de-cantabria/puntos-acceso |
| `itacyl` | ntrip.itacyl.es | 2101 | FREE_REGISTRATION | https://gnss.itacyl.es/inscripcion |
| `catnet` | catnet-ip.icgc.cat | 2101 | FREE_REGISTRATION | https://catnet-ip.icgc.cat/spgic/es |
| `erva` | icverva2.icv.gva.es | 2101 | FREE_REGISTRATION | https://icv.gva.es/es/web/icv-erva/altes-en-rtk |
| `rep_network` | rep.juntaex.es | 2101 | FREE_REGISTRATION | https://rep.juntaex.es/gppstartpage_servlet (caído el 16/10/2026) |
| `rep_station` | rep.juntaex.es | 2102 | FREE_REGISTRATION | ídem |
| `larioja` | 195.55.164.9 | 2101 | REGISTRATION | https://iderioja.larioja.org/servicios/red-de-estaciones-gnss/informacion |
| `regam` | 147.84.177.82 | 2101 | REQUEST | https://sitmurcia.carm.es/conexion-regam (servidor comprobado con su tabla de fuentes) |
| `rgan_2` | 176.12.84.26 | 2101 | FREE_REGISTRATION | https://www.navarra.es/es/web/rgan/posicionamiento |
| `rgan_4` | 176.12.84.26 | 2102 | FREE_REGISTRATION | ídem |
| `euskadi_network` | www.gps2.euskadi.eus | 2101 | FREE_REGISTRATION | https://gis.hazi.eus/webredgnsseuskadi/ |
| `euskadi_station` | www.gps2.euskadi.eus | 2103 | FREE_REGISTRATION | ídem (el 2102 es CMR/Leica: no sirve al RS3) |
| `bizkaia` | 80.245.2.245 | 2101 | OPEN (`anonimo` / `1234`) | https://www.bizkaia.eus/es/servicios-de-correccion-en-tiempo-real |

- Los nombres de red ("ERGNSS (IGN)", "CatNet (ICGC)"…) son nombres propios y no se traducen.
- El ámbito y la variante ("solución de red", "estación única") van en `strings.xml`.

## Algoritmos
### Tabla de fuentes (RF-14, RF-20)
```
GET / HTTP/1.1, Ntrip-Version: Ntrip/2.0, Authorization si hay usuario, mismo socket/TLS que la conexión.
Respuesta válida: "SOURCETABLE 200 OK" (v1) o "HTTP/1.x 200" con Content-Type gnss/sourcetable (v2, quizá chunked).
Leer líneas hasta "ENDSOURCETABLE" o fin de flujo (máx. 8 MB, 15 s).
STR;mountpoint;identifier;format;format-details;carrier;nav-system;network;country;lat;lon;nmea;solution;generator;compr;auth;fee;bitrate;…
  - lat/lon 0,0 o vacíos → sin coordenadas; nmea "1" → necesita GGA.
  - CAS y NET se ignoran; una línea STR mal formada se salta (no invalida la tabla).
Fallos: UNREACHABLE, UNAUTHORIZED (401/403), TLS_INVALID, NOT_A_SOURCETABLE (otra respuesta), EMPTY (sin STR).
```

### Orden y búsqueda (RF-15 a RF-18)
- Distancia = haversine sobre la esfera de 6371 km entre la posición del receptor (GGA) y la del punto de montaje, mostrada en km con un decimal. Basta para ordenar: el error es < 0,5 %.
- Con posición: por distancia, y los que no tienen coordenadas al final, por nombre. Sin posición: por nombre (sin mayúsculas).
- Búsqueda: contiene el texto en `mountpoint` o `identifier`, sin mayúsculas.

### GGA (RF-19, RF-21)
`sendGga = profile.mountpointNeedsGga ?: true` (null = escrito a mano o sin saber). `NtripClient` solo arranca el envío periódico si `sendGga`.

### Reconexión (RF-23 a RF-25)
```
al terminar una conexión con fallo f:
  si f ∈ {UNAUTHORIZED, MOUNTPOINT_NOT_FOUND, TLS_INVALID, PROTOCOL} → Stopped(f)   // RF-24, RF-28: no se reintenta
  si no → Reconnecting(intento n, f, próximo en t) y esperar backoff[n] = 1, 2, 4, 8, 16, 30, 30… s (RNF-T3)
          y volver a conectar con la misma config; al llegar a Streaming, n = 0
stopNtrip() o desconectar el receptor cancelan la espera. Durante la pausa de S004 no se reintenta (Paused manda).
```

### Edad y alarma (RF-29 a RF-31)
```
edad = ahora − última corrección (o − inicio de la conexión si aún no hubo ninguna)
abierta = estado ∈ {Streaming, Reconnecting}       // Paused tiene su propio aviso (RNF-T3)
cada 250 ms: alarma = abierta ∧ edad > umbral
flanco de subida → Alerts.correctionsLost() (tono + vibración) una vez por corte; flanco de bajada → se quita el aviso
```
La vigilancia vive en `ReceiverSession`, dentro del servicio en primer plano, y por eso suena también en segundo plano.

### Tramas RTCM 3 (RF-32, RF-33)
```
buscar 0xD3; longitud = 10 bits bajos de los dos bytes siguientes (≤ 1023); trama = 3 + longitud + 3 bytes
CRC24Q (polinomio 0x1864CFB) sobre cabecera + datos; tipo = 12 primeros bits de los datos
CRC bien → counts[tipo]++ ; CRC mal → crcErrors++ y seguir buscando desde el byte siguiente al 0xD3
```
- El lector es incremental: los bloques pueden partir una trama.
- Las correcciones se reenvían al receptor **antes** de contarlas y sin tocarlas (S002/RF-19).
- Tipos conocidos (nombres en `strings.xml`):
  - 1001–1004 (GPS) y 1009–1012 (GLONASS): observables antiguos;
  - 1005 y 1006: posición de la base;
  - 1007, 1008 y 1033: antena y receptor;
  - efemérides: 1019 (GPS), 1020 (GLONASS), 1042 (BeiDou), 1044 (QZSS), 1045 y 1046 (Galileo);
  - MSM1–7: 1071–1077 GPS, 1081–1087 GLONASS, 1091–1097 Galileo, 1101–1107 SBAS, 1111–1117 QZSS, 1121–1127 BeiDou, 1131–1137 NavIC;
  - 1230: sesgos GLONASS.
  - El resto se muestra solo con su número.

### Arranque automático y "estaba abierta" (RF-26)
`CorrectionsController` (app, singleton) guarda:
- `ntrip_was_active = true` cuando el usuario conecta un perfil o se arranca solo;
- `ntrip_was_active = false` cuando el usuario la detiene, borra el perfil o el caster la rechaza (RF-24);
- nada cuando se desconecta el receptor.

Cuando la conexión del receptor pasa a Recibiendo, si `was_active` es verdadero, el perfil existe y su contraseña se descifra, llama a `startNtrip`. Si la contraseña no se descifra, se queda en "Falta la contraseña" (RF-6).

### Datos de S002 (RF-10)
Al arrancar, una vez (`ntrip_legacy_imported`): si `ntrip_host` no está vacío se crea un perfil con nombre = servidor, el puerto, el punto de montaje y el usuario. La contraseña nunca se guardó. Después se borran las claves antiguas.

## Contratos
- `core:ntrip`:
  - `SourcetableParser.parse(lines): List<SourcetableStream>`;
  - `SourcetableClient(socketFactory, sslSocketFactory).fetch(host, port, useTls, username, password): SourcetableResult` (`Ok(streams)` o `Failed(reason)`);
  - `Mountpoints.sort(streams, position?)` y `Mountpoints.filter(streams, text)`;
  - `NtripClient(..., sslSocketFactory = SSLSocketFactory.getDefault())`. Con `useTls`, el socket conectado se envuelve con `createSocket(socket, host, port, true)` y `SSLParameters.endpointIdentificationAlgorithm = "HTTPS"`.
  - `NtripFailure` + `TLS_INVALID` (SSLHandshakeException o SSLPeerUnverifiedException).
- `core:gnss`: `RtcmFramer.feed(bytes, offset, length): List<RtcmFrame>` (tipo y si el CRC es bueno) y `RtcmMessages.kind(type): RtcmKind`.
- `core:model`: `NtripProfileRules.validate(draft, otherNames): Set<NtripProfileError>` y `PublicCasters.ALL`.
- `data`:
  - `NtripProfileRepository`, con `Flow`/`suspend`:
    - `profiles()`, `profile(id)`;
    - `create(draft, password)` y `update(id, draft, password?)` (null = no cambia), con `SaveResult`;
    - `delete(id)`, `setMountpoint(id, name, needsGga)`;
    - `password(id): PasswordResult` = `Ok(String)`, `None` o `Undecryptable`.
  - `SecretCipher`: `encrypt(String) → Sealed(iv, ct)` y `decrypt(Sealed) → String?`. `AesGcmCipher(keyProvider)` en producción con la clave del Keystore; los tests le pasan una `SecretKey` en memoria.
  - `ReceiverPreferences` + `lastNtripProfileId`, `ntripWasActive`, `correctionsAlarmS`.
- `receiver`:
  - `NtripState` + `Reconnecting(attempt, failure, nextAttemptAtMs)`;
  - `Streaming` + `rtcm: RtcmStats`;
  - `ReceiverSnapshot` + `correctionsAlarm: Boolean`;
  - `ReceiverSession.setCorrectionsAlarmThreshold(s)`;
  - `Alerts` + `correctionsLost()`.
- `app`:
  - rutas `NtripProfileFormDestination(profileId: String?, publicCasterId: String?)`;
  - `CorrectionsController`: `connect(profileId)`, `stop()`, observadores de arranque y de edición.

Organización de la pantalla, como la app de referencia y con diseño propio:
- **Correcciones:**
  - arriba, la tarjeta de estado: perfil, estado, edad, bytes, intento de reconexión y botón "Detener";
  - debajo, la lista de perfiles: filas de 56 dp con nombre, servidor:puerto y punto de montaje, y "⋮" con "Editar" y "Borrar";
  - después, la tarjeta "Mensajes RTCM": tipo, nombre, cuántos y errores de CRC;
  - botón flotante "Nuevo perfil".
- **Nuevo perfil:** primero un diálogo "Elegir caster" con la lista de casters públicos ("Red · ámbito · acceso") y "Otro caster"; después, el formulario.
- **Formulario:**
  - nombre, servidor, puerto, interruptor "Conexión segura (TLS)", usuario, contraseña con botón de ver;
  - punto de montaje con botón "Elegir de la lista", que abre a pantalla completa la tabla de fuentes con búsqueda;
  - con un caster público: la dirección de alta, que se puede pulsar.
- **Indicador de estado:** con la alarma, añade "Sin correcciones" con icono de aviso (color y texto, RNF-T8).
- **Notificación:** con la alarma, "Sin correcciones desde hace %d s".
- **Ajustes:** grupo "Correcciones" con "Alarma de correcciones" (2–60 s).

Textos nuevos que ve el usuario:

| Caso | Texto |
|---|---|
| Sin perfiles | "Todavía no hay perfiles NTRIP." + "Nuevo perfil" |
| Nombre vacío / repetido | "Escribe un nombre para el perfil." / "Ya hay un perfil con ese nombre." |
| Servidor vacío / puerto | "Escribe el servidor del caster." / "El puerto debe estar entre 1 y 65535." |
| Contraseña perdida | "No se pudo leer la contraseña guardada. Escríbela de nuevo." |
| Borrar perfil | "¿Borrar el perfil «%s»?" |
| Tabla de fuentes | "No se pudo descargar la tabla de fuentes: %s. Puedes escribir el punto de montaje." |
| Reconectando | "Reconectando (intento %d)…" + "Detener" |
| TLS | "El certificado del caster no es válido." |
| Alarma | "Sin correcciones desde hace %d s" |
| Acceso | "Registro gratuito" / "Registro" / "Solicitud" / "Acceso libre" / "De pago" |
| RTCM | "Mensajes RTCM" + "Errores de CRC: %d" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Perfiles en Room (v2) con la contraseña cifrada en columnas | Una transacción para perfil y contraseña; migraciones probadas (RNF-T6) | DataStore con JSON (sin unicidad transaccional) o `EncryptedSharedPreferences` (obsoleta) |
| AES-GCM con clave del Keystore sin exportar; descifrado fallido = pedir de nuevo | RNF-T11; la clave se pierde al restaurar el móvil y no se debe perder el perfil (RF-6) | Cifrar con una clave derivada de un PIN (otra contraseña más en campo) |
| Casters públicos como catálogo en código, no en la base de datos | Son plantillas (suposición de la spec); se revisan con cada versión | Perfiles precargados (llenan la lista de perfiles inservibles) |
| Reconexión en `ReceiverSession` con la misma espera que el receptor | Una sola máquina de estados; la pausa de S004 ya está ahí | Reintentos en la interfaz (no funcionan en segundo plano) |
| Alarma en `ReceiverSession`, sonido por `Alerts` del servicio | Suena con la pantalla apagada (RF-29) | Vigilarla en un ViewModel (solo con la app visible) |
| Lector RTCM en `core:gnss` | La arquitectura lo sitúa ahí y `core:ntrip` no depende de nadie | En `core:ntrip` (mezcla transporte y formato) |
| Reenviar antes de contar | Ningún retraso añadido a las correcciones (RNF de la spec) | Contar y luego reenviar |
| TLS envolviendo el socket ya conectado | Mantiene la `SocketFactory` inyectada para el ruteo (ADR-17) | `SSLSocketFactory.createSocket(host, port)` (se salta la red elegida) |
| Certificados de test generados con `keytool` del JDK | Sin dependencias; un certificado válido para `localhost` y otro para otro nombre | BouncyCastle en los tests |
| Elegir punto de montaje en una pantalla completa dentro del formulario | El formulario aún no está guardado: el estado se queda en su ViewModel | Ruta aparte (habría que pasar servidor y credenciales sin guardar) |

## Estrategia de tests
- **JVM `core:ntrip`:**
  - lector de tablas de fuentes reales grabadas (`fixtures/ntrip/sourcetables/`: IGN 2101 y 2102, una autonómica) y casos raros (líneas mal formadas, sin coordenadas, vacía);
  - cliente contra el caster falso con v1, v2 y chunked, 401, respuesta que no es tabla y TLS (válido, otro nombre, no confiable);
  - orden y búsqueda;
  - GGA enviada o no según `sendGga`;
  - 5000 puntos de montaje en < 2 s.
- **JVM `core:gnss`:**
  - `RtcmFramer` con un flujo RTCM 3 real grabado (`fixtures/rtcm/`, unos segundos de una red de acceso libre, con su `.yaml`): todas las tramas con CRC bueno y los tipos esperados;
  - el mismo flujo partido en bloques de 1 byte, y con bytes corrompidos (errores contados y resincronización);
  - 4 KB en < 50 ms.
- **JVM `core:model`:** reglas del perfil; el catálogo de casters, sin ids ni nombres repetidos, con puertos válidos y acceso libre con usuario.
- **Robolectric `data`:**
  - repositorio: crear, editar, borrar, unicidad, `setMountpoint`;
  - contraseña cifrada (en la base no aparece en claro), contraseña con otra clave → `Undecryptable`;
  - migración 1→2 con datos de la v1 intactos;
  - importación de S002 una sola vez;
  - preferencias nuevas.
- **JVM `receiver`** (`ReceiverSession` con conector falso y reloj virtual):
  - reconexión con las esperas 1, 2, 4… y vuelta a cero;
  - sin reintento ante 401, punto de montaje inexistente o TLS;
  - detener durante la espera;
  - alarma al pasar el umbral y al volver;
  - una alarma por corte;
  - recuento RTCM;
  - reenvío intacto.
- **Robolectric `app` (Compose):**
  - Correcciones: lista, estado, reconectando y mensajes RTCM;
  - formulario y validaciones, contraseña oculta y ver;
  - diálogo de casters públicos (rellena, alta, acceso libre);
  - elegir punto de montaje (orden, búsqueda, sin posición, fallo y a mano);
  - `CorrectionsController`: arranque automático, editar el perfil en uso y borrar el perfil en uso;
  - indicador y notificación con la alarma;
  - umbral en Ajustes.
- **Tests de S002 que daban por no guardada la contraseña** (S002/RF-26, RF-27): se adaptan a los perfiles.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-9 | Perfiles | `NtripProfileRepositoryTest`, `NtripProfileRulesTest`, `CorrectionsScreenTest`, `NtripProfileFormTest`, `CorrectionsControllerTest` |
| RF-10 | Datos de S002 | `NtripLegacyImportTest` |
| RF-11 a RF-13 | Casters públicos | `PublicCastersTest`, `NtripProfileFormTest` |
| RF-14 a RF-20 | Tabla de fuentes | `SourcetableParserTest`, `SourcetableClientTest`, `MountpointsTest`, `MountpointPickerTest` |
| RF-21 | GGA | `NtripClientTest`, `CorrectionsControllerTest` |
| RF-22 a RF-26 | Conexión y reconexión | `ReceiverSessionNtripTest`, `CorrectionsControllerTest`, `CorrectionsScreenTest` |
| RF-27, RF-28 | TLS | `NtripClientTlsTest`, `SourcetableClientTest` |
| RF-29 a RF-31 | Alarma | `ReceiverSessionNtripTest`, `StatusIndicatorTest`, `ReceiverNotificationsTest`, `CorrectionsSettingsTest` |
| RF-32, RF-33 | RTCM | `RtcmFramerTest`, `ReceiverSessionNtripTest`, `CorrectionsScreenTest` |
