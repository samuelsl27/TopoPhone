# Arquitectura de TopoPhone

Decisiones transversales (en formato ADR breve). Los `plan.md` de cada spec las citan como `ADR-n` y solo documentan lo específico de su spec. Para cambiar un ADR hay que pedirlo expresamente y anotarlo aquí con la fecha.

## Módulos

```
app/                      Compose UI, navegación, ViewModels (MVVM/UDF), assets/rejillas/
core/model                tipos de dominio, unidades y formateadores (m/ft/usft, DD/DMS/gon)
core/geodesy              JVM puro: elipsoides, ECEF/ENU, TM (Krüger, orden 6), LCC, OM, Helmert, MB, rejillas GeoTIFF
core/gnss                 parser NMEA (incl. EBP/ETC), tramas RTCM3 (CRC24Q), modelo de época y de estado del receptor
core/ntrip                cliente NTRIP con sockets crudos (v1/v2, sourcetable, GGA); SocketFactory inyectada
core/survey               promediado, reglas de captura, punta del jalón, replanteo, base shift, COGO, localización, geometría
core/formats              CSV/PENZD/PNEZD, DXF R12, SHP, KML/KMZ, LandXML, GetCapabilities (streaming)
core/designsystem         Android: tema, modo sol, barra de estado, diana, botones grandes
core/testing              fakes, cargador de fixtures, caster NTRIP falso, reloj virtual para replay
data/                     Room, DataStore, ficheros por proyecto, copias de seguridad, repositorios
receiver/                 transportes (BT SPP, TCP, GNSS del móvil, replay, virtual), ReceiverSession, servicio en primer plano
receiver-experimental/    solo en el flavor "experimental": REST + Socket.IO de Reach (S043)
```

**Reglas de dependencia** (las comprueba `./gradlew checkModuleRules`, incluida en `check`; S001/RF-27):

| Módulo | Puede depender de |
|---|---|
| `core:model` | — |
| `core:ntrip` | — |
| `core:geodesy` | `core:model` |
| `core:gnss` | `core:model` |
| `core:formats` | `core:model`, `core:geodesy` |
| `core:survey` | `core:model`, `core:geodesy`, `core:gnss` |
| `core:testing` | cualquier `core` de dominio |
| `core:designsystem` (Android) | `core:model` |
| `app`, `data`, `receiver` | cualquier `core` |

- Los módulos de dominio (todos los `core` salvo `designsystem`) son JVM puro: no aplican el plugin de Android, Hilt ni KSP, ni importan `android.`, `androidx.`, `dagger.`, `javax.inject.` o `com.google.dagger.`.
- Ningún `core` depende de `app`, `data`, `receiver` ni `receiver-experimental`.
- Nadie depende de `app`. Solo `app` (flavor `experimental`) depende de `receiver-experimental`.
- Un módulo `core` nuevo necesita su fila en esta tabla y en `ModuleRules` antes de crearse.

## Flujo de datos de una medida

```
Transporte (SPP/TCP/replay) ─bytes─▶ separador NMEA/RTCM ─▶ parser ─▶ Época (GGA+GST+ETC+… con la misma hora)
        ▲                                                                │
        └──── RTCM3 ◀── cliente NTRIP ◀── GGA cada N s ◀─────────────────┤
                                                                         ▼
                       ReceiverSession (StateFlow<ReceiverState>, UI conflada a ≤ 5 Hz)
                                                                         ▼
  Orden fijo de cálculo:  (1) geodésicas brutas del APC en el datum de las correcciones
                          (2) punta del jalón: ENU de ETC_rev_3 o vertical (−altura de antena)
                          (3) base shift (ECEF)  (4) SC del proyecto: proyección + geoide
                          (5) localización (si la hay)
                                                                         ▼
                       Punto = observación bruta (1) + parámetros de (2)–(5) + metadatos de calidad
```

## ADR

| Id | Decisión | Motivo | Alternativa descartada |
|---|---|---|---|
| ADR-1 | Kotlin + Jetpack Compose, nativo Android; minSdk 26; toolchain JDK 21 | Acceso completo a Bluetooth SPP, servicios y NDK si hiciera falta | Flutter / KMP (iOS no admite SPP con el RS3) |
| ADR-2 | Geodesia en **Kotlin puro**, validada con vectores pyproj y ejemplos EPSG GN 7-2 | APK pequeño, tests JVM rápidos, control total; para España basta TM con rejillas | PROJ por NDK (+10 MB por ABI, proj.db, toolchain JNI); proj4j (sin rejillas verticales) |
| ADR-3 | Catálogo de SC **generado offline** desde EPSG con `tools/geodesia` (pyproj fijado con uv), limitado a los métodos implementados | Catálogo reproducible; nada que no sepamos calcular | Catálogo escrito a mano |
| ADR-4 | Rejillas en un único formato: **GeoTIFF del CDN de PROJ**, con lector propio e interpolación bilineal. EGM08-REDNAP (Península y Canarias) y SPED2ETV2 van incluidas; el resto se descarga con SHA-256 | Un solo lector; funciona sin conexión desde el primer arranque | GTX, NTv2 `.gsb`, ASCII del IGN (cinco parsers) |
| ADR-5 | Fuera de una rejilla no se extrapola: el punto se rechaza con un aviso | Constitución nº 2 | Extrapolar en silencio |
| ADR-6 | Altura elipsoidal = altitud GGA + separación GGA (EGM96 del receptor); el geoide propio se aplica después | GGA da una altitud ortométrica con el EGM96 interno | Usar la altitud GGA tal cual |
| ADR-7 | Inclinación: por defecto la **app calcula la punta desde ETC**, con el receptor en "Do not compensate" (ETC_rev_3: Δe = −H·sinθ·sinα, Δn = −H·sinθ·cosα, Δu = −H·cosθ, con H = jalón + 0,134 m en el RS3) | Una sola altura del jalón, en la app; bloqueo con estado 30; metadatos por época; así lo hacen FieldGenius y TcpGPS | Compensado por el receptor como modo por defecto (la altura del jalón obliga a usar Emlid Flow, la pérdida de inclinación no se indica en GGA). Queda como modo secundario |
| ADR-8 | Persistencia: **Room** (WAL, `synchronous=FULL`, UUID, esquemas exportados) + DataStore + ficheros privados por proyecto. El esquema crece spec a spec: cada una añade sus tablas con una migración y su test (15/10/2026, S006) | Herramientas maduras y migraciones testeables | SQLDelight, Realm (sin futuro), GeoPackage como almacén principal |
| ADR-9 | Mapa: **MapLibre Native** detrás de la interfaz propia `MapController`; WMS con `{bbox-epsg-3857}`; la diana de replanteo es un canvas métrico aparte | Libre y sin claves; capa sustituible | osmdroid (archivado), Google Maps y Mapbox (cuentas y claves) |
| ADR-10 | DI con **Hilt** solo en `app`, `data` y `receiver` | Errores en compilación; los `core` siguen siendo puros | Koin (errores en ejecución), DI manual |
| ADR-11 | Corrutinas + Flow; un único `ReceiverSession` gestionado por el servicio; escrituras al socket por un solo Channel | Una única fuente de verdad | Varios lectores del socket |
| ADR-12 | Parser NMEA **propio**, con tests de propiedades y fuzzing | Pocas frases y EBP/ETC propias de Emlid; control total de la tolerancia a frases corruptas | marine-api (sin EBP/ETC, poco mantenida) |
| ADR-13 | Cliente NTRIP **propio** sobre sockets crudos; OkHttp solo para HTTP normal (GetCapabilities, descargas) | La v1 responde `ICY 200 OK`; la v2 usa chunked; hay que elegir la red | OkHttp para el flujo de correcciones |
| ADR-14 | DXF, SHP, KML y LandXML con lectores y escritores **propios** por streaming; XML con XmlPullParser | Compatibles con Android y sin dependencias pesadas | JDXF (AWT), GeoTools (Java SE), Kabeja (abandonado); DWG fuera de alcance |
| ADR-15 | Tests: JUnit 5 + kotest en `core`; coroutines-test + Turbine; Robolectric y Roborazzi en Android; Gradle Managed Devices cada noche | Rapidez en JVM y cobertura de la interfaz | Solo tests instrumentados |
| ADR-16 | CI con GitHub Actions: ktlint, Android Lint, reglas de módulos propias, tests, Kover ≥ 80 % en `core`, informe de RF, ficheros prohibidos y build con R8 | Puerta automática (constitución nº 8) | Verificación manual; detekt 1.23, que no soporta Kotlin 2.4 (se reevaluará con detekt 2.0 estable) |
| ADR-19 | Navegación con Navigation Compose y rutas tipadas: barra inferior con varias pilas y un grafo anidado por sección | Patrón estándar y conservación del estado | Estado de navegación propio; Navigation 3 |
| ADR-20 | La versión sale de la etiqueta git `vX.Y.Z` (versionCode = X·10000 + Y·100 + Z); sin etiqueta, `-dev`. Se publica en GitHub Releases con una única clave de firma cuya huella está en `release/certificado-sha256.txt` | Una sola fuente de la versión; actualizaciones garantizadas | versionName escrito a mano |
| ADR-17 | Ruteo de red: el tráfico al receptor por la red Wi-Fi y el NTRIP por la red con internet | En el hotspot del receptor no hay internet por Wi-Fi | Red por defecto del sistema |
| ADR-18 | La API no oficial de Reach solo existe en `receiver-experimental` y en el flavor `experimental`, desactivada por defecto y con aviso legal | Constitución nº 5; los términos de Emlid prohíben la ingeniería inversa | Integrarla en el núcleo |

## Coordenadas y datum de las correcciones
- Cada proyecto declara el **datum de las correcciones**: ETRS89 (redes españolas), REGCAN95 (Canarias) o ITRF/WGS84 (por ejemplo, una base promediada en SINGLE o el Emlid Caster).
- Si el datum de las correcciones no coincide con el del SC del proyecto, la app avisa (≈ 0,9 m entre ITRF y ETRS89 en 2026). Las transformaciones dependientes del tiempo quedan fuera de alcance; la solución es el base shift o la localización.
- Los puntos medidos se guardan con sus coordenadas geodésicas brutas como fuente de verdad. Las coordenadas de cuadrícula son derivadas y se pueden recalcular.
