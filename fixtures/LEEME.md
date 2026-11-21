# Fixtures de test

Datos compartidos por los tests de todos los módulos. Los ficheros de más de 5 MB van en Git LFS (ver `.gitattributes`).

| Carpeta | Contenido |
|---|---|
| `nmea/rs3/fw<versión>/` | Sesiones reales del RS3: `<escenario>.nmea` y su descriptor `<escenario>.yaml` |
| `nmea/sintetico/` | Sesiones sintéticas generadas por `tools/nmea/generar_sintetico.py` y sus resultados esperados (`.esperado.csv` para S002; `.epocas.csv`, `.punta.csv`, `.medias.csv`, `.tramos.csv` y `.disparos.csv` para las de S003, S016 y S017, que no son del formato de S002; `rs3-base-shift.desplazamiento.csv` para S020, generado por `tools/base_shift`); no se editan a mano |
| `rtcm/` | Flujos RTCM 3 grabados (`.rtcm3`, tal como los envía el caster) con su descriptor `.yaml`: `rgapa-AVL13M` (S007) |
| `ntrip/sourcetables/` | Tablas de fuentes reales (`.sourcetable`, respuesta completa del caster, byte a byte) con su `.yaml`: IGN 2101 y 2102, RGAPA (S007) |
| `geodesia/` | Vectores de referencia generados por `tools/geodesia` (CSV). Los de S032 salen de `proyecciones.py` con pyproj/PROJ y llevan en la cabecera sus versiones: `lcc.csv` (Lambert 1SP y 2SP), `mercator.csv` (A y B), `omerc.csv` (Oblique Mercator A y B), `stereo_oblicua.csv` (con el punto publicado de RD Nueva), `stereo_polar.csv` (A, B y C en los dos polos), `meridiano_origen.csv` (los diez métodos con Madrid, París y Lisboa y un caso que cruza el antimeridiano) y `unidades_proyeccion.csv` (falsos en pies y pies de EE. UU.); cada fila trae el Este, el Norte, el factor de escala y la convergencia. Los de S033 salen de `datum.py` (mismas versiones en la cabecera) y traen la directa y la inversa de pyproj: `helmert.csv` (3, 4, 6 y 7 parámetros en PV y CF, con operaciones de EPSG y casos propios), `molodensky_badekas.csv` (con el punto de rotación), `rejilla_desplazamiento.csv` (la SPED2ETV2 como `hgridshift`: interior, bordes y fuera) y `cadena_tres_pasos.csv` (cadenas de tres y cuatro pasos de PROJ; la columna `pasos` las describe) |
| `promediado/` | Promediados simulados de 60 min a 10 Hz y lo esperado (media, desviación típica y estabilidad), generados por `tools/promediado` (S017) |
| `replanteo/` | Diseño y punta del jalón con la distancia, las diferencias de Norte y Este y el rumbo calculados con pyproj, generados por `tools/replanteo` (S018) |
| `superficies/` | Superficies LandXML (metros, pies, UTM 29, identificadores de texto, malla, sin superficies, mal formado) y la cota esperada y la conversión de sus vértices calculadas con pyproj y con un cálculo aparte, generadas por `tools/superficies` (S042) |
| `raster/` | Rásteres de cotas (GeoTIFF sin comprimir, LZW y Deflate con y sin predictor, tiras y teselas, uint16, int16, int32, float32 y float64, celda como área y como punto, en pies, multibanda, en UTM 29 y geográficas, sin geoide; ASCII Grid con esquina y centro), los rechazos y las celdas y la cota esperada calculadas con pyproj y un cálculo aparte, generados por `tools/raster` (S045) |
| `base_shift/` | Desplazamiento de base: vector ECEF conocido − medido, equivalente en el SC, módulo y clasificación, y puntos nuevos con el vector aplicado, en ETRS89, REGCAN95 y con correcciones en ITRF; generados por `tools/base_shift` con pyproj (S020) |
| `ejes/` | Ejes y rasantes LandXML (rectas, arcos, clotoides, acuerdos parabólicos y circulares, en metros y en pies, y los casos que se rechazan), un CSV de rasante y lo esperado (tramos, puntos por PK y desplazamiento, resumen) calculado con series de Fresnel y fórmulas analíticas, generados por `tools/ejes` (S049) |
| `mapas_offline/` | Teselas que cubren cajas de Madrid, Tenerife y Palma a 6 rangos de zoom y 5 fuentes (`teselas.csv`), lo que contó MapLibre en el emulador en T1 (`medidos.csv`), esquinas de teselas en EPSG:3857 comprobadas con pyproj a 1 mm (`esquinas.csv`) y las cajas exactas de círculos y de rectángulos con margen (`cajas.csv`), generados por `tools/mapas_offline` (S037) |
| `reach/` | Respuestas **sintéticas** (no grabadas de un receptor) de la API no oficial del receptor, para los tests de `receiver-experimental` (S044); ver su `LEEME.md` |
| `formatos/` | Ejemplos de CSV, DXF, SHP, KML, LandXML y GetCapabilities |

## Descriptor de escenario (`.yaml`)
```yaml
escenario: estatico-fix-vertice
receptor: { modelo: RS3, firmware: "33.1", imu: off, compensacion_nmea: no }
nmea: { talker: GN, frases: [GGA, GST, GSA, GSV, RMC, ZDA, EBP, ETC], gga_hz: 5 }
correcciones: { caster: "<host>", mountpoint: "<mp>", tipo: VRS, datum: ETRS89 }
jalon_m: 1.800
senal: { tipo: REGENTE, id: "<id>", etrs89: { lat: 0.0, lon: 0.0, h: 0.0 } }
esperado:
  solucion_final: FIX
  notas: "FLOAT→FIX a los ~40 s"
```

Escenarios mínimos que conviene grabar pronto:
- `estatico-fix-vertice`
- `float-a-fix`
- `perdida-correcciones`
- `corte-bluetooth`
- `inclinacion-setup-ocho`
- `inclinacion-15-30-45-4dir` (en los dos modos de compensación)
- `movimiento-rapido`
