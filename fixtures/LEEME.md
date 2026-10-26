# Fixtures de test

Datos compartidos por los tests de todos los módulos. Los ficheros de más de 5 MB van en Git LFS (ver `.gitattributes`).

| Carpeta | Contenido |
|---|---|
| `nmea/rs3/fw<versión>/` | Sesiones reales del RS3: `<escenario>.nmea` y su descriptor `<escenario>.yaml` |
| `nmea/sintetico/` | Sesiones sintéticas generadas por `tools/nmea/generar_sintetico.py` y sus resultados esperados (`.esperado.csv` para S002; `.epocas.csv`, `.punta.csv`, `.medias.csv`, `.tramos.csv` y `.disparos.csv` para las de S003, S016 y S017, que no son del formato de S002); no se editan a mano |
| `rtcm/` | Flujos RTCM 3 grabados (`.rtcm3`, tal como los envía el caster) con su descriptor `.yaml`: `rgapa-AVL13M` (S007) |
| `ntrip/sourcetables/` | Tablas de fuentes reales (`.sourcetable`, respuesta completa del caster, byte a byte) con su `.yaml`: IGN 2101 y 2102, RGAPA (S007) |
| `geodesia/` | Vectores de referencia generados por `tools/geodesia` (CSV) |
| `promediado/` | Promediados simulados de 60 min a 10 Hz y lo esperado (media, desviación típica y estabilidad), generados por `tools/promediado` (S017) |
| `replanteo/` | Diseño y punta del jalón con la distancia, las diferencias de Norte y Este y el rumbo calculados con pyproj, generados por `tools/replanteo` (S018) |
| `superficies/` | Superficies LandXML (metros, pies, UTM 29, identificadores de texto, malla, sin superficies, mal formado) y la cota esperada y la conversión de sus vértices calculadas con pyproj y con un cálculo aparte, generadas por `tools/superficies` (S042) |
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
