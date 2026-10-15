# Puerta G0 (S002): procedimiento y plantilla del acta

La puerta G0 cierra la spec 002 con dos ensayos del [protocolo](protocolo.md): **(a)** TopoPhone frente a pyproj aplicado a las GGA grabadas y **(c)** TopoPhone frente a la reseña de un vértice. El acta se guarda en `docs/validacion-campo/AAAA-MM-DD-<lugar>/acta.md`.

## Antes de salir
1. Instala la APK de depuración en el móvil (`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`).
2. Configura el RS3 en Emlid Flow, una sola vez:
   - salida de posición por Bluetooth: NMEA con GGA a 5 Hz y GST, talker GN;
   - entrada de correcciones: Bluetooth;
   - "Do not compensate receiver position".
3. Desconecta Emlid Flow del receptor: el firmware 33 admite una sola conexión Bluetooth.
4. Empareja el RS3 en los ajustes de Bluetooth del móvil.
5. Descarga del IGN la reseña del vértice REGENTE o ROI: ETRS89, UTM y altitud H.

## En el vértice
1. Estaciona el jalón vertical sobre la señal, con el nivel calado, y anota su altura (de la punta a la base del receptor).
2. Abre TopoPhone, ve a Receptor, conecta el RS3 y escribe la altura del jalón.
3. Conecta el NTRIP (caster ETRS89 del IGN o de la red autonómica) y espera a que la solución sea **Fija**.
4. Mantén la solución fija al menos 60 s sin mover el jalón. Haz capturas de la tarjeta Coordenadas.
5. Pulsa **Desconectar**, después **Compartir sesión**, y guarda el fichero `.nmea`.

## En el ordenador
1. Copia la sesión en `fixtures/nmea/rs3/fw<versión>/estatico-fix-vertice.nmea` y añade su descriptor `.yaml` (ver `fixtures/LEEME.md`).
   - El fichero contiene la posición del vértice. Al ser un vértice público, puede ir en el repositorio.
2. **Ensayo (a)**. Genera lo esperado con pyproj y compáralo con la cadena de cálculo de TopoPhone, a 1 mm:
   ```
   uv run --project tools tools/validacion/g0_sesion.py fixtures/nmea/rs3/fw<versión>/estatico-fix-vertice.nmea --jalon <m> --esperado
   ./gradlew :core:survey:test --tests "*RecordedSessionsTest*"
   ```
   - Además, como comprobación visual, compara una captura con la salida del script en esa época (`--hora hhmmss.ss`, hora UTC). La pantalla se refresca a 5 Hz y no muestra la hora, así que en esta comparación solo cuenta el orden de magnitud.
3. **Ensayo (c)**. Calcula la media de las épocas fijas y compárala con la reseña:
   ```
   uv run --project tools tools/validacion/g0_sesion.py fixtures/nmea/rs3/fw<versión>/estatico-fix-vertice.nmea --jalon <m> --resumen
   ```

## Plantilla del acta
```markdown
# Acta de validación — G0 — <AAAA-MM-DD> — <lugar>
- Receptor: RS3 FW <x.y> · Jalón: <m> · Móvil: <modelo / Android>
- TopoPhone <versión/commit> · Emlid Flow <versión> (solo para configurar el receptor)
- NTRIP: <caster/mountpoint/tipo> · SC: ETRS89 / UTM <huso> + EGM08-REDNAP · Datum de las correcciones: <…>
- Vértice: <nombre y número> · Reseña: E <…> N <…> H <…>

| Ensayo | Punto | Resultado | Tolerancia | OK |
|---|---|---|---|---|
| a | <vértice>: TopoPhone frente a pyproj (RecordedSessionsTest, <n> épocas) | máx. ΔE <…> ΔN <…> ΔH <…> mm | ≤ 1 mm | |
| c | <vértice>: media de <n> épocas fijas frente a la reseña | ΔH <…> cm (horizontal) · ΔV <…> cm | ≤ 5 cm H · ≤ 10 cm V | |

- Comprobaciones de la demo: conexión y reconexión Bluetooth, NTRIP (bytes y "hace N s"), estado Fija/Flotante con su color, aviso "sin precisión centimétrica" en Flotante, compartir sesión.
- Suposiciones de la spec que se confirman o no: desplazamiento del RS3 de 0,134 m; envío de GGA al caster cada 5 s (VRS); correcciones en ETRS89.
- Incidencias:
- Veredicto: APTO / NO APTO · Firmado: <nombre>
```
