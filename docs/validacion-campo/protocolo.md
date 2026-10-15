# Protocolo de validación de campo

Cada puerta de hito (G0, G1, …; ver `docs/hoja-de-ruta.md`) se cierra con un acta en `docs/validacion-campo/AAAA-MM-DD-<lugar>/acta.md`. El acta acompaña los CSV exportados, los logs NMEA/RTCM grabados (RNF-T12) y fotos de las reseñas y del montaje.

## Material
- **Receptor**: Reach RS3 (anotar el firmware) sobre un jalón de altura conocida, con nivel esférico comprobado. Trípode o bípode para las estaciones largas.
- **Móvil Android**: anotar modelo y versión de Android. TopoPhone y Emlid Flow instalados, cada uno con su versión anotada.
- **Correcciones**: red NTRIP en ETRS89 (IGN ERGNSS o la red autonómica). Anotar caster, mountpoint y tipo (estación única o VRS).
- **Puntos de control**: al menos 2 vértices REGENTE/ROI y 1 clavo REDNAP, con las reseñas descargadas del IGN. Añadir Canarias si se usa REGCAN95.

## Configuración del receptor (una vez, en Emlid Flow)
1. Position streaming 1: Bluetooth, NMEA con GGA (5 Hz), GST, GSA, GSV, RMC, ZDA, EBP y ETC, talker GN.
2. Para la inclinación con la punta calculada en la app: "Do not compensate receiver position".
3. Correction input: Bluetooth.
4. IMU activado o desactivado según el ensayo.
5. Desconectar Emlid Flow, porque el FW 33 solo admite una conexión Bluetooth.

## Ensayos
| Id | Ensayo | Procedimiento | Tolerancia |
|---|---|---|---|
| a | **A/B simultáneo** (aísla la geodesia y la altura de antena) | El receptor usa su propio NTRIP. El NMEA se envía por TCP a TopoPhone mientras Emlid Flow sigue conectado por BLE; los dos ven la misma solución. Se mide el mismo punto en las dos apps | Δ ≤ 1 mm en E, N y H |
| b | **Secuencial** | TopoPhone (su NTRIP por Bluetooth) frente a Emlid Flow (su NTRIP): 3 estacionamientos alternos de 60 s sobre la misma señal | ≤ 2 cm H, ≤ 3 cm V |
| c | **Contra la reseña** | Comparar con las coordenadas oficiales del vértice o clavo | ≤ 5 cm H, ≤ 10 cm V (límite de la realización y del geoide) |
| d | **Inclinación** | Sobre una señal fija: jalón vertical frente a 15°, 30° y 45° en 4 direcciones, con la punta calculada en la app y con la compensada por el receptor | Dentro de la precisión publicada (RTK + 2 mm + 0,3 mm/°) |
| e | **Replanteo** | Replantear 5 puntos con TopoPhone, marcarlos y volver a medirlos con Emlid Flow | ≤ 2,5 cm |
| f | **Exportaciones** | Abrir CSV, DXF, SHP y KML en QGIS, AutoCAD y un MDT; comprobar que se reconoce el SC | Sin errores; SC correcto |
| g | **Robustez** | Cortar el Bluetooth y el NTRIP durante 30 s, bloquear la pantalla 10 min, dejar la batería del móvil por debajo del 15 % | Reconexión automática (RNF-T3); ningún dato perdido |

## Ensayos por puerta
| Puerta | Ensayos |
|---|---|
| G0 (S002) | a (solo TopoPhone frente a pyproj aplicado al GGA grabado), c |
| G1 (S013) | a, b, c, f (CSV), g |
| G2 (S021) | d, e, g |
| G3–G7 | f para cada formato nuevo; los que añada cada spec |

## Plantilla de acta
```markdown
# Acta de validación — <Puerta> — <AAAA-MM-DD> — <lugar>
- Receptor: RS3 FW <x.y> · Jalón: <m> · Móvil: <modelo / Android>
- TopoPhone <versión/commit> · Emlid Flow <versión>
- NTRIP: <caster/mountpoint/tipo> · SC del proyecto: <SC + geoide> · Datum de las correcciones: <…>
| Ensayo | Punto | Resultado | Tolerancia | OK |
|---|---|---|---|---|
- Incidencias:
- Veredicto: APTO / NO APTO · Firmado: <nombre>
```
