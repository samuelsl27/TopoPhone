# Plan técnico — Spec 002

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-2, 4, 5, 6, 7, 11, 13, 17), `specs/002-cadena-rtk-minima/spec.md`.

## Estructura de módulos (nuevos: `core:gnss`, `core:ntrip`, `core:geodesy`, `core:survey`, `receiver`)
| Módulo | Contenido | RF |
|---|---|---|
| `core:gnss` (JVM) | `NmeaFramer` (bytes → frases con suma de control válida, cualquier talker, tolerante a binario intercalado); `GgaParser`, `GstParser`; `SolutionStatus`; `GgaFix`, `GstAccuracy` | RF-11–RF-15 |
| `core:ntrip` (JVM, solo kotlinx-coroutines) | `NtripRequest` (petición v2 compatible con v1), `NtripResponseParser` (`ICY 200 OK`, `HTTP/1.x 200`, 401, `SOURCETABLE`, 404), `ChunkedDecoder`, `NtripClient` (socket crudo, `SocketFactory` inyectada, GGA cada 5 s, sin datos 10 s), `NtripStats` | RF-19–RF-25 |
| `core:geodesy` (JVM) | `Ellipsoid.GRS80`, `TransverseMercator` (Krüger, orden 6), `Utm` (husos 29–31), `GeoTiffGrid` (lector propio: DEFLATE + predictor 3, teselas o tiras), `GeoidModel` con interpolación bilineal como PROJ; recurso `geoid/es_ign_egm08-rednap.tif` | RF-36–RF-39 |
| `core:survey` (JVM) | `VerticalPole` (punta del jalón, RS3: +0,134 m), `GroundPointCalculator` (h = alt + sep; UTM; H = h − N; motivos de "no disponible") | RF-29, RF-32–RF-41 |
| `core:model` | `AngleFormat` (DMS con 5 decimales de segundo, DD con 9), `LengthFormat` (3 decimales), según la configuración regional de la interfaz | RF-34–RF-38 |
| `receiver` (Android) | `ReceiverTransport` + `BluetoothSppTransport` (UUID SPP, alternativa `createRfcommSocket(1)`), `BondedDevices`, `ReceiverSession` (estados, NMEA → estado, cola única de escritura al receptor, orquesta NTRIP), `SessionLogStore` (fichero por sesión, máximo 10) | RF-1–RF-10, RF-19, RF-28, RF-42, RF-44 |
| `data` | `ReceiverPreferences` en DataStore: servidor, puerto, punto de montaje, usuario y altura del jalón (sin contraseña) | RF-26, RF-27, RF-30 |
| `app` | `ReceiverViewModel` (estado conflado a ≤ 5 Hz), `ReceiverScreen` con tarjetas Conexión, Estado, NTRIP, Coordenadas y Sesión, permisos, vibración, compartir con FileProvider; atribución del IGN en Licencias | todos los de interfaz, RF-43, RF-45 |
| `tools/geodesia` | `descargar_rejillas.py` (CDN de PROJ con SHA-256) y `generar_vectores.py` (pyproj 3.8: UTM con EPSG:4258→25829/25830/25831 y ondulación con `vgridshift` sobre la misma rejilla) → `fixtures/geodesia/*.csv` | criterios de finalización |

Reglas de módulos (`docs/arquitectura.md`): `core:gnss` y `core:geodesy` dependen solo de `core:model`; `core:ntrip` no depende de ningún módulo; `core:survey` depende de `core:model`, `core:gnss` y `core:geodesy`; `receiver` puede usar cualquier `core`.

## Modelo de datos
- `GgaFix(utcTime, latitudeDeg, longitudeDeg, quality: SolutionStatus, satellites, hdop?, altitudeM?, geoidSeparationM?, correctionAgeS?, rawSentence)`.
- `GstAccuracy(utcTime, sigmaLatM?, sigmaLonM?, sigmaAltM?)`, con `horizontalM = √(σlat² + σlon²)` y `verticalM = σalt`.
- `GroundPoint(latitudeDeg, longitudeDeg, ellipsoidalHeightM, utm: Utm?, orthometricHeightM?, unavailable: Set<Reason>)`.
  - `Reason`: `OUTSIDE_UTM_ZONES`, `OUTSIDE_GEOID`, `NO_HEIGHT`.
- DataStore `receiver_preferences`: `ntrip_host`, `ntrip_port` (2101), `ntrip_mountpoint`, `ntrip_username`, `pole_height_m` (1.8). Sin `password` (RF-27, RNF-T11).
- Sesiones: `files/sessions/AAAAMMDD-HHMMSS.nmea`, bytes tal como llegan; se conservan las 10 más recientes (RF-44).

Ejemplo:
```
$GNGGA,101530.20,4025.1234567,N,00342.7654321,W,4,24,0.6,650.123,M,51.234,M,1.0,0001*hh
h_rec = 650.123 + 51.234 = 701.357 m;  h_suelo = 701.357 − (1.800 + 0.134) = 699.423 m
UTM 30 (λ = −3.7127572°) → E, N;  N_geoide (EGM08-REDNAP) ≈ 51.3 m  →  H = h_suelo − N
```

## Algoritmos

### Entramado NMEA (RF-11)
1. Se acumulan bytes. Una frase empieza en `$`, termina en `\n` (con o sin `\r`) y tiene como máximo 256 bytes; los bytes no ASCII la invalidan.
2. Es válida si `*HH` es el XOR de los bytes entre `$` y `*`, comparado sin distinguir mayúsculas.
3. Tipo = los tres caracteres después del talker de dos letras (`GNGGA` → `GGA`). Todo lo que no cumple se descarta sin error.

### GGA y GST
- **Coordenadas**: latitud `ddmm.mmmm` → `dd + mm.mmmm/60`, negativa con `S`; longitud `dddmm.mmmm`, negativa con `W`.
- **Calidad**:

  | Valor | Estado |
  |---|---|
  | 0 | Sin solución |
  | 1 | Autónoma |
  | 2 | Diferencial |
  | 4 | Fija |
  | 5 | Flotante |
  | 6 | Estimada |
  | otro | Sin solución |

- **Campos vacíos**: se tratan como `null`; con calidad 0 no hay posición.
- **GST**: campos 6, 7 y 8 (σlat, σlon, σalt).

### Punta del jalón (RF-32, RF-33)
- `h_rec = altitud + separación` (ADR-6). Si falta cualquiera de las dos, no hay altura (`NO_HEIGHT`).
- **Jalón vertical**: la punta tiene la misma φ y λ, y `h = h_rec − (jalón + 0,134)`. Moverse sobre la normal al elipsoide no cambia φ ni λ.
- El 0,134 m es una constante del modelo de receptor (`ReceiverModel.RS3.baseToReferenceM`). Se valida en G0.

### UTM (RF-36, RF-37), Krüger de orden 6 (Karney 2011, equivalente al `etmerc` de PROJ)
1. **Huso**: `floor((λ + 180)/6) + 1`. Solo se calcula en los husos 29, 30 y 31; fuera de ellos, `OUTSIDE_UTM_ZONES`.
2. **Parámetros**: λ0 = 6·huso − 183; k0 = 0,9996; FE = 500 000; FN = 0.
3. **Coeficientes**:
   - n = f/(2 − f)
   - A = a/(1 + n)·(1 + n²/4 + n⁴/64 + n⁶/256)
   - α1…α6 en serie de n (fórmulas de Karney).
4. **Coordenadas transformadas**:
   - τ = tan φ, σ = sinh(e·atanh(e·τ/√(1 + τ²))), τ′ = τ√(1 + σ²) − σ√(1 + τ²)
   - ξ′ = atan2(τ′, cos Δλ), η′ = asinh(sin Δλ / √(τ′² + cos² Δλ))
   - ξ = ξ′ + Σ αj sin 2jξ′ cosh 2jη′, η = η′ + Σ αj cos 2jξ′ sinh 2jη′
5. **Resultado**: E = FE + k0·A·η, N = FN + k0·A·ξ.

### Rejilla GeoTIFF del geoide (RF-38, RF-39; ADR-4, ADR-5)
1. **Lectura de TIFF**:
   - Orden de bytes `II` o `MM`.
   - IFD 0, con las etiquetas 256/257 (tamaño), 258 = 32, 339 = 3 (float).
   - 259: 1 (sin compresión) u 8 (DEFLATE, `java.util.zip.Inflater`).
   - 317: 1 o 3 (predictor de coma flotante).
   - Teselas (322–325) o tiras (273/278/279).
   - Geolocalización: 33550 (paso) y 33922 (punto de anclaje); la clave GeoTIFF 1025 indica punto o área (si es área, se desplaza medio paso hacia el centro del píxel).
   - 42113 (`GDAL_NODATA`), si existe.
2. **Predictor 3** (TIFF TechNote 3), por fila de tesela:
   - deshacer la diferencia horizontal byte a byte sobre `ancho × 4` bytes;
   - reordenar los planos de bytes (el primer plano son los bytes más significativos) a float32 big-endian.
3. **Nodos**: λi = λ0 + i·Δλ, φj = φ0 − j·Δφ, siendo la fila 0 el norte.
4. **Interpolación bilineal**, como `pj_hgrid/vgrid` de PROJ:
   - fx = (λ − λ0)/Δλ, fy = (φ0 − φ)/Δφ;
   - i = ⌊fx⌋, j = ⌊fy⌋;
   - en el último nodo exacto se usa la celda anterior con fracción 1;
   - fuera de [0, ancho − 1] × [0, alto − 1] → `OUTSIDE_GEOID`; si algún nodo es "sin datos" o NaN → `OUTSIDE_GEOID`.
5. **Carga**: la rejilla se descodifica entera la primera vez (841 × 541 float32 ≈ 1,8 MB), en segundo plano, y queda en memoria.

### NTRIP (RF-19–RF-25; ADR-13)
- **Petición**: `GET /MOUNT HTTP/1.1`, con las cabeceras `Host`, `Ntrip-Version: Ntrip/2.0`, `User-Agent: NTRIP TopoPhone/<versión>`, `Authorization: Basic base64(usuario:contraseña)` y `Connection: close`. Los casters v1 la aceptan.
- **Respuesta** (primera línea y cabeceras, leídas byte a byte hasta `\r\n\r\n`, o `\r\n` en `ICY`):

  | Respuesta | Resultado |
  |---|---|
  | `ICY 200 OK` | datos v1 |
  | `HTTP/1.x 200` con `gnss/data` | datos v2 (con `chunked`, se descodifica) |
  | `SOURCETABLE 200 OK`, `gnss/sourcetable` o 404 | `MOUNTPOINT_NOT_FOUND` |
  | 401 o `ERROR - Bad Password` | `UNAUTHORIZED` |
  | otra | `PROTOCOL` |

- **Tiempos**: conexión con un plazo de 10 s → `UNREACHABLE`; lectura con un plazo de 10 s → `NO_DATA`; fin del flujo → `CONNECTION_LOST`.
- **GGA**: cada 5 s se escribe la última GGA del receptor (`rawSentence + "\r\n"`) en el socket. Escribe una sola corrutina, con un `Mutex` para no mezclar escrituras.
- **Reenvío**: cada bloque leído se entrega tal cual a `onCorrections(bytes)`; `ReceiverSession` lo escribe al receptor por su cola única de escritura (ADR-11).

### `ReceiverSession` (RF-5–RF-10, RF-28, RF-42)
- **Estados**: `Disconnected`, `Connecting`, `Receiving`, `NoData`, `Failed(reason)`, `Lost`.
- `connect(device)`:
  - abre el transporte con un plazo de 10 s (`Failed(CONNECT_TIMEOUT)`, RF-7);
  - un bucle lector pasa los bytes al `NmeaFramer` y al registro de sesión;
  - la primera frase válida lleva a `Receiving`;
  - 3 s sin frases válidas lleva a `NoData` (RF-8);
  - una excepción de E/S lleva a `Lost` (RF-9).
- `startNtrip(config)` sin receptor conectado → error `RECEIVER_NOT_CONNECTED` (RF-28).
- `disconnect()` cierra el NTRIP, el transporte y el registro (RF-10).
- El tiempo se inyecta (`TimeSource`) para probar los plazos con tiempo virtual.

## Contratos de interfaz (textos en `strings.xml`)
- **Tarjeta Conexión**:
  - lista de emparejados (nombre y MAC);
  - estado: Desconectado, Conectando, Recibiendo, Sin datos, Conexión perdida o Error y su causa;
  - botones Conectar, Desconectar y Reconectar;
  - avisos de permiso o de Bluetooth apagado.
- **Tarjeta Estado**: chip de solución (color + texto, RNF-T8), satélites, HDOP, edad de las correcciones, σH y σV.
- **Tarjeta NTRIP**: servidor, puerto, punto de montaje, usuario, contraseña (oculta), Conectar/Desconectar, estado o error, bytes y "hace N s".
- **Tarjeta Coordenadas**:
  - altura del jalón (teclado decimal, coma o punto);
  - φ y λ en DMS y en DD, h;
  - huso, E y N;
  - H (EGM08-REDNAP);
  - avisos "Sin precisión centimétrica" y "Sistema de las correcciones (se supone ETRS89)";
  - "No disponible: …" con su motivo.
- **Tarjeta Sesión**: "Compartir sesión" (FileProvider, `text/plain`).

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Geodesia propia (Krüger de orden 6 y bilineal de PROJ) validada con pyproj 3.8 | ADR-2; tolerancia de 1 mm | proj4j (sin rejillas verticales) |
| Rejilla como recurso Java de `core:geodesy`, en git normal y no en LFS (828 KB) | Se puede probar en la JVM y en el CI sin LFS; funciona sin red | Asset de Android (no se puede probar en `core`); LFS (el CI no lo descarga) |
| Lector GeoTIFF propio limitado a lo que usan las rejillas de PROJ | Un solo formato (ADR-4); sin dependencias | GeoTools/GDAL (no válidos en Android) |
| Socket crudo para NTRIP con `SocketFactory` inyectada | `ICY 200 OK`; selección de red en S019 (ADR-13, ADR-17) | OkHttp |
| `ReceiverSession` en `receiver`, con `TimeSource` inyectado y transporte abstracto | Se prueba en la JVM con transporte y caster falsos | Lógica en el ViewModel (no se puede probar sin Android) |
| Estado de la interfaz conflado (`sample(200 ms)`) | RNF-T13, GGA a 5 Hz | Recomponer con cada frase |
| La contraseña solo en memoria | RF-27; el cifrado llega con S005 | Guardarla en claro |
| FileProvider con ruta `sessions/` | Compartir sin permisos de almacenamiento (RNF-T5) | `MANAGE_EXTERNAL_STORAGE` |

## Estrategia de tests
- **JVM `core:gnss`**: frases reales y sintéticas (GN/GP), suma de control mal calculada, cortes, binario intercalado, campos vacíos y todas las calidades.
- **JVM `core:geodesy`**:
  - vectores pyproj (`fixtures/geodesia/utm_etrs89.csv`, `geoide_egm08_rednap.csv`) a ≤ 1 mm, con bordes de huso, ±3° y esquinas y fuera de la rejilla;
  - descodificación de la rejilla frente a nodos leídos con numpy en `generar_vectores.py` (valor exacto del nodo).
- **JVM `core:survey`**: casos resueltos a mano: jalón, h, motivos de "no disponible".
- **JVM `core:ntrip`**: caster falso (`ServerSocket` local) que responde ICY, HTTP 200 + chunked, 401, `SOURCETABLE` y silencio, y corta; comprobación de la GGA cada 5 s con tiempo virtual.
- **JVM `receiver`**: `ReceiverSession` con transporte falso (tuberías) y tiempo virtual: estados, reenvío de RTCM, sin datos, pérdida, registro y retención.
- **Robolectric `app`**: tarjetas con estado falso, permisos (`ShadowApplication.grantPermissions`), Bluetooth apagado, vibración (`ShadowVibrator`), compartir (intención con `FileProvider`), atribución del IGN.
- **Reproducción de principio a fin**: `fixtures/nmea/sintetico/estatico-fix.nmea` → sesión → coordenadas esperadas (generadas con pyproj).
- **Campo (G0)**: protocolo (a)+(c), `docs/validacion-campo/`.

## Cobertura de RF
| RF | Parte | Test |
|---|---|---|
| RF-1–RF-4 | `BondedDevices`, permisos, `ReceiverScreen` | `ReceiverConnectionCardTest` |
| RF-5–RF-10 | `ReceiverSession` | `ReceiverSessionTest`, `ReceiverConnectionCardTest` |
| RF-11–RF-15, RF-17 | `core:gnss` y tarjeta Estado | `NmeaFramerTest`, `GgaParserTest`, `GstParserTest`, `StatusCardTest` |
| RF-16 | Tarjeta Estado | `StatusCardTest` |
| RF-18–RF-25, RF-28 | `core:ntrip`, `ReceiverSession` y tarjeta NTRIP | `NtripResponseParserTest`, `NtripClientTest`, `ReceiverSessionTest`, `NtripCardTest` |
| RF-26, RF-27, RF-30 | `ReceiverPreferences` | `ReceiverPreferencesTest` |
| RF-29, RF-31 | Tarjeta Coordenadas | `CoordinatesCardTest` |
| RF-32–RF-33, RF-39–RF-41 | `core:survey` | `GroundPointCalculatorTest`, `CoordinatesCardTest` |
| RF-34–RF-38 | `core:geodesy`, `core:model` | `UtmTest`, `GeoidModelTest`, `AngleFormatTest`, `CoordinatesCardTest` |
| RF-42–RF-44 | `SessionLogStore` y compartir | `SessionLogStoreTest`, `ShareSessionTest` |
| RF-45 | Licencias | `LicensesScreenTest` |
