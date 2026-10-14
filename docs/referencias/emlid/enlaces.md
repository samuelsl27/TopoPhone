# Integración con Reach RS3: datos técnicos y enlaces

Consultado en octubre de 2026 (firmware Reach 33.1, Emlid Flow 15.11). Antes de cada spec que dependa de estos datos hay que volver a comprobarlos en los changelogs.

## Documentado y soportado (núcleo de TopoPhone)
- **Salida de posición**: "Position streaming 1/2" por Bluetooth (Classic SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`, PIN en el emparejamiento), TCP servidor/cliente (puerto 9001 por defecto; IP 192.168.42.1 en hotspot) o serie (RS-232/USB). Los puertos < 3000 están reservados.
  - https://docs.emlid.com/reachrs3/integration/mock-location/
  - https://docs.emlid.com/reachrs3/integration/fieldgenius/
  - https://community.emlid.com/t/connecting-rs3-to-qfield-ios/38095
- **NMEA**: talker GN por defecto (también GP).
  - GGA a 5 Hz y GSA/GST/GSV/RMC/VTG/ZDA a 1 Hz.
  - EBP (posición de la base, altura elipsoidal) a 1 Hz.
  - ETC (inclinación) a la misma frecuencia que GGA.
  - GSA/GSV se envían una vez por constelación.
  - La frecuencia de salida debe ser ≤ que la frecuencia GNSS.
  - https://docs.emlid.com/reachrs3/specifications/nmea-format/
- **Altitud GGA**: ortométrica con el EGM96 interno del receptor; la altura elipsoidal es h = campo 9 + campo 11. El GGA no está "localizado": va en el datum de la base o del caster.
  - https://community.emlid.com/t/position-streaming-to-echo-sounder-from-reach-rs2-how-to-transform-heights-referenced-to-emlids-egm96-1-deg-x-1-deg-resolution/43501
- **Inclinación (ETC_rev_3)**: ver `ETC_rev_3.pdf` en esta carpeta.
  - Altura de antena = jalón + **0,134 m** (de la base del receptor al ARP en el RS3).
  - Fórmulas ENU de la punta, aplicadas antes de proyectar o aplicar el geoide.
  - Desde el **FW 32**, GGA/GST compensados ("Compensate receiver position"). En ese modo la altura del jalón se introduce en Emlid Flow y en el software externo va 0.
  - El GGA compensado **no indica** la pérdida de la inclinación; hay que mirar el estado 30 de ETC.
  - https://docs.emlid.com/reachrs3/changelogs/
  - https://community.emlid.com/t/reach-firmware-32-enhances-reach-rs3-compatibility-with-surveying-software/39069
- **Entrada de correcciones por Bluetooth**: Correction input → Advanced → Bluetooth. El RTCM3 se escribe en el mismo socket SPP del que se lee el NMEA. También por TCP.
  - https://community.emlid.com/t/is-it-possible-to-use-bluetooth-for-the-correction-input-and-position-output-at-the-same-time/7684
  - https://docs.emlid.com/reachrs3/integration/fieldgenius-android/
  - https://docs.emlid.com/reachrx2/api-integration/rtk-corrections-and-casters/
- **FW 33**: una sola conexión Bluetooth (Classic o BLE) a la vez, así que Emlid Flow debe estar desconectado.
  - https://community.emlid.com/t/bluetooth-is-now-main-connectivity-option-for-r-series-reach-receivers/42233
- **Reach Panel**: interfaz web oficial en la IP del receptor (navegador).
  - https://docs.emlid.com/reach/troubleshooting/how-to-access-RP/

## NTRIP (cliente propio)
- **v1**: `GET /MOUNT HTTP/1.0`, `User-Agent: NTRIP <app>`, `Authorization: Basic …`. La respuesta es `ICY 200 OK`, que las librerías HTTP rechazan, así que se usa un socket crudo.
- **v2**: `HTTP/1.1`, con `Host` y `Ntrip-Version: Ntrip/2.0`. La respuesta trae `Content-Type: gnss/data` y puede venir chunked.
- **Sourcetable** (`GET /`): líneas `STR;`, `CAS;` y `NET;` hasta `ENDSOURCETABLE`. Campos de STR: mountpoint, identifier, format, format-details, carrier, nav-system, network, country, lat, lon, **nmea** (1 = requiere GGA), solution, generator, compression, auth, fee, bitrate, misc.
- **GGA para VRS/NEAREST**: cada 1–10 s, a partir del último fix del receptor.
- **Errores**: 401; el caster devuelve el sourcetable cuando el mountpoint es malo; reconexión con espera creciente.
- **Referencias**:
  - NTRIP 1.0: https://igs.bkg.bund.de/root_ftp/NTRIP/documentation/NtripDocumentation.pdf
  - Buenas prácticas para clientes NTRIP (RTCM, 2023): https://rtcm.myshopify.com/collections/differential-global-navigation-satellite-dgnss-standards/products/rtcm-paper-2023-sc104-1344-ntrip-client-devices-best-practices
  - Flujo de una conexión NTRIP explicado: https://rtkdata.com/ntrip-connection-flow-explained
- **Emlid Caster**: `caster.emlid.com:2101`, con credenciales separadas de base y rover.

## No documentado (solo para S042, experimental)
- **No existe ninguna API oficial de configuración.**
  - https://community.emlid.com/t/reach-rs2-ssh-credentials/34346
  - https://docs.emlid.com/reachrx2/api-integration/api-integration-intro/
- **API local obtenida por ingeniería inversa**: https://github.com/bscholer/home-assistant-emlid
  - HTTP en el puerto 80, sin autenticación.
  - `GET /info`, `/configuration`, `/configuration/device`, `/configuration/positioning_settings`, `/battery`, `/wifi/status`, `/bluetooth/status`, `/lora/state`, `/logs`.
  - `POST /configuration/device` (rol, altura de antena), `/configuration/logging/logs {"started":bool}`.
  - Socket.IO (Engine.IO v3) en `/socket.io`, evento `broadcast` `{name,payload}`: `navigation`, `observations`, `stream_status`, `active_logs`, `battery_status`.
  - Descubrimiento mDNS `_reach._tcp.local`.
- **Aspecto legal**: los términos de Emlid prohíben la ingeniería inversa (https://store.emlid.com/pages/apps-terms). La Directiva UE 2009/24/CE (arts. 5.3 y 6) protege la interoperabilidad (esto no es asesoramiento legal). Antes de distribuir el módulo, conviene pedir permiso a Emlid.

## Geodesia y rejillas (España)
- **Rejillas del CDN de PROJ** (GeoTIFF): `es_ign_egm08-rednap.tif`, `es_ign_egm08-rednap-canarias.tif`, `es_ign_SPED2ETV2.tif` (ED50→ETRS89).
  - https://cdn.proj.org/
  - Formato: https://proj.org/en/stable/specifications/geodetictiffgrids.html
- **Referencia de proyecciones y transformaciones**: IOGP Guidance Note 7-2 (Coordinate Conversions and Transformations including Formulas).

## Librerías evaluadas
- **MapLibre Native Android** (BSD-2): WMS con `{bbox-epsg-3857}`, regiones offline, PMTiles.
  - https://maplibre.org/maplibre-style-spec/sources/
- **osmdroid**: archivado el 20-11-2024, así que no se usa.
- **proj4j**: no soporta rejillas verticales. PROJ por NDK es demasiado pesado (ADR-2).
- **JDXF**: depende de AWT y no funciona en Android. **GeoTools**: no es apto para Android. **marine-api**: LGPL y sin EBP/ETC. Por eso se escriben implementaciones propias (ADR-12, ADR-14).
