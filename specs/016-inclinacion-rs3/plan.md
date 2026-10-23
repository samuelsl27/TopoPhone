# Plan técnico — Spec 016

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/016-inclinacion-rs3/spec.md`. Sin código.

## Estructura de módulos
- `core:geodesy` → `LocalTangentPlane`: desplazar un punto en el plano tangente local y la diferencia ENU entre dos puntos, sobre `Ecef` de S015 (A1). Lo usan la punta (RF-13, RF-25, RF-27) y el asistente (RF-7, RF-8).
- `core:gnss` → `TiltData.rawSentence`, la frase ETC tal como llegó, para guardarla con el punto (RF-22).
- `core:model`:
  - `TiltMode { APP, RECEIVER, VERTICAL }`, `PointTilt`, `TiltAngles` y `PoleVector`; `Measurement.tilt: PointTilt? = null` (RF-22);
  - `PrecisionControl.maxTiltRad` y `PrecisionFailure.TILT` (RF-20);
  - `AngleFormat.direction`, para la inclinación, la dirección y el rumbo (RF-11, RF-23).
- `core:survey`:
  - `TiltEpoch`: si una época está en estado 30 y sin aviso, su guía, el vector del jalón y la precisión de la inclinación (A2; RF-12, RF-19, RF-21);
  - `PoleTip`: la punta desde la antena, también sin altura (A3; RF-13, RF-27);
  - `LiveTip`: la punta y la precisión que se muestran en directo, según el modo (A4; RF-16, RF-17, RF-21);
  - `PointMeasurement` con la inclinación (A5; RF-13, RF-14, RF-19, RF-21, RF-22);
  - `PointCorrections.withPoleHeight` a lo largo del jalón (A6; RF-25);
  - `TiltModeWizard`: la prueba del asistente como máquina de estados pura (A7; RF-5 a RF-9);
  - `GroundPointCalculator.fromGround(ground, solution)`, para que Coordenadas muestre la punta (RF-16).
- `data`:
  - `TiltPreferences`: el modo de cada receptor en el DataStore `"receiver"` (RF-1, RF-3, RF-4, RF-10);
  - columnas de la inclinación en `points`, migración 7 → 8 y `forExport` con ellas (RF-22, RF-24);
  - `CollectorPreferences` con la inclinación máxima (RF-20).
- `receiver` → `Alerts.tiltLost()`: sonido y vibración al descartar épocas por la inclinación (RF-19).
- `core:formats` → ocho `ExportColumn` nuevas tras `BASELINE` (RF-24).
- `app`:
  - `ReceiverConnections.currentAddress: StateFlow<String?>` y `CurrentTilt` (@Singleton): el receptor en uso, su modo, si el asistente está abierto y un número de revisión (A8, A9; RF-1, RF-2, RF-28);
  - apartado "Inclinación" del menú del receptor: elección del modo, aviso de RF-4 y asistente (`TiltViewModel`; RF-1 a RF-10);
  - tarjeta "Inclinación" de la pantalla Estado (RF-11);
  - `TiltGuideBanner`, con la ilustración propia del ocho (RF-12);
  - la punta en Coordenadas, en el panel de posición y en el rover, con "Punta sin compensar" (RF-16, RF-17, RF-21);
  - recolector: bloqueos, altura del jalón del receptor, épocas descartadas, inclinación máxima, precisión, guardado y cancelación al cambiar el modo (RF-2, RF-15, RF-18 a RF-22, RF-28);
  - ficha del punto: tarjeta "Inclinación", modo en la medida y antena rotulada como punta (RF-23, RF-26); la corrección del jalón ya pasa por `PointCorrections` (RF-25);
  - nombres de las columnas nuevas en Exportar (RF-24);
  - anotaciones en el log de cada aviso que se muestra (S014/RF-5).
- `tools`:
  - `tools/geodesia/generar_vectores.py`: conjunto `punta_jalon` con pyproj (A1, A3, A6);
  - `tools/nmea/generar_inclinacion.py`: tres reproducciones sintéticas del RS3 con ETC, con lo esperado de cada una (criterios de finalización).

## Modelo de datos

### En la sesión (no se guarda)
No cambia `ReceiverSnapshot`: cada `Epoch` ya trae `tilt: TiltData?` (S003). `TiltData` gana `rawSentence: String = ""`, como `GstAccuracy`.

### Modo de cada receptor (DataStore `"receiver"`)
```
ReceiverTiltSettings(
  mode: TiltMode?,        // null: sin elegir (RF-2)
  verified: Boolean,      // true si lo confirmó el asistente (RF-10); false si se eligió a mano (RF-3)
  detected: TiltMode?,    // lo último que encontró el asistente, aunque luego se elija otro modo (RF-4)
)
```
- Una clave por receptor: `tilt_<clave>`, con la dirección Bluetooth o `replay` para todas las reproducciones (A8).
- Valor de texto `MODO;comprobado;DETECTADO`. Ejemplos: `APP;1;APP` (comprobado), `VERTICAL;0;RECEIVER` (a mano, el asistente encontró que compensa), `RECEIVER;0;` (a mano, sin asistente).
- No viaja en las copias (S013): `SettingsArchive.PORTABLE` no la incluye, como el último receptor, porque depende del receptor y no del proyecto.

### Inclinación máxima (DataStore `"collector"`)
- `PrecisionControl(fixOnly, horizontalLimitM, verticalLimitM, maxTiltRad: Double? = 60° en radianes)`; null es desactivada.
- Claves `collector_max_tilt_enabled` (bool, por defecto true) y `collector_max_tilt_rad` (double, por defecto 60°). Hacen falta dos porque, al revés que los límites H/V, está activa por defecto.
- Rango 1° a 60° (`PrecisionControl.MAX_TILT_RANGE`).

### En el punto (Room v8)
```
PointTilt(mode: TiltMode, verified: Boolean, angles: TiltAngles?)
TiltAngles(
  tiltRad, directionRad, headingRad: Double?,     // de la última época usada (RF-22)
  maxTiltRad,                                      // la mayor de las épocas usadas (RF-20, RF-22)
  horizontalAccuracyM, verticalAccuracyM,          // precisión de la inclinación, la mayor de las épocas usadas (A2)
  pole: PoleVector(east, north, up),               // media del vector unitario del jalón de las épocas usadas (A3, A6)
  receiverPoleHeightM: Double?,                    // solo en RECEIVER: el jalón declarado al medir (A6)
)
```
Columnas nuevas de `points`, todas opcionales:

| Columna | Tipo | Ejemplo (jalón de 1,8 m inclinado 12°) |
|---|---|---|
| `tilt_mode` | TEXT | `APP` |
| `tilt_verified` | INTEGER | 1 |
| `tilt` | REAL | 0.2094395 (rad) |
| `tilt_direction` | REAL | 3.6738 (rad) |
| `tilt_heading` | REAL | 2.1537 (rad) |
| `tilt_max` | REAL | 0.2181662 (rad) |
| `tilt_sigma_h` | REAL | 0.0027 (m) |
| `tilt_sigma_v` | REAL | 0.0020 (m) |
| `tilt_pole_e` | REAL | −0.1055 |
| `tilt_pole_n` | REAL | −0.1791 |
| `tilt_pole_u` | REAL | 0.9781 |
| `tilt_receiver_pole` | REAL | NULL |

- `AutoMigration(from = 7, to = 8, spec = PointsV8)`. `PointsV8.onPostMigrate` pone `tilt_mode = 'VERTICAL'` y `tilt_verified = 0` en los puntos `MEASURED`, que se calcularon así (RNF-T6). Los manuales quedan sin modo.
- Hay que exportar el esquema `8.json`, añadir una línea al registro de versiones y poner las columnas en la consulta de `forExport`.
- Las copias y los paquetes (S013) copian con `SELECT *` y migran al abrir: no se toca `DatabaseSnapshot`.
- El historial (`point_history`) no cambia: la inclinación es de la medida y no se edita. RF-25 cambia el suelo y la altura del jalón, que ya se guardan.
- `rawNmea` lleva, por cada época usada, su GGA, su GST si la trae y su ETC (RF-22).

## Algoritmos

### A1. Plano tangente local (RF-13, RF-25, RF-27, RF-7, RF-8)
Rotación ENU ↔ ECEF de ETC_rev_3 (página 5), que es la conversión geocéntrica/topocéntrica de EPSG GN 7-2 (métodos 9836 y 9837). Origen (φ, λ, h) en GRS80, con `Ecef` (S015/A5).
- `offset(origen, e, n, u)`:
  1. P₀ = ECEF(φ, λ, h).
  2. ΔX = −sinλ·e − sinφ·cosλ·n + cosφ·cosλ·u.
  3. ΔY = cosλ·e − sinφ·sinλ·n + cosφ·sinλ·u.
  4. ΔZ = cosφ·n + sinφ·u.
  5. Resultado: geodésicas de P₀ + Δ.
- `between(a, b)` → ENU de b respecto a a, con la rotación traspuesta:
  - e = −sinλ·ΔX + cosλ·ΔY;
  - n = −sinφ·cosλ·ΔX − sinφ·sinλ·ΔY + cosφ·ΔZ;
  - u = cosφ·cosλ·ΔX + cosφ·sinλ·ΔY + sinφ·ΔZ.
- Se valida contra pyproj con el conjunto `punta_jalon`: tubería `cart` + `topocentric` de PROJ, un cálculo independiente. Tolerancia de 1 mm (constitución nº 2); se espera menos de 1 µm.

### A2. Época de inclinación (RF-12, RF-19, RF-21, términos de la spec)
1. **En estado 30:** `state == COMPENSATING` con `tiltRad`, `tiltDirectionRad` y los tres coeficientes. El rumbo puede faltar, porque no se usa para calcular.
2. **Limpia:** en estado 30 y con `notification == NONE`.
3. **Guía:** null si la época está limpia. Si no:

   | Época | Guía |
   |---|---|
   | sin ETC, o estado OFF | `NO_TILT` |
   | estado 0 | `FATAL` |
   | estado 10 | `SETUP` |
   | estado 20 | `ALIGNMENT` |
   | estado 30 con aviso 10, 20 o 30 | `FAST_MOTION`, `BAD_GNSS` o `FILTER_FAULT` |
   | estado 30 sin algún campo, o con un aviso imposible | `SETUP` ("Esperar"; ver decisiones) |

4. **Vector unitario del jalón**, de la punta a la antena, con θ la inclinación y α su dirección: u = (sinθ·sinα, sinθ·cosα, cosθ). Las fórmulas de ETC_rev_3 son Δ = −H·u.
5. **Precisión de la inclinación**, con H = altura de antena en m:
   - cada eje: σₑ = cₑ·H/1000, σₙ = cₙ·H/1000 y σᵤ = cᵤ·H/1000, en m (ETC_rev_3 da mm);
   - en planta: √(σₑ² + σₙ²); en vertical: σᵤ.
   - Con los coeficientes del ejemplo de ETC_rev_3 (1,013; 0,993; 1,021) y H = 1,934 m: planta 2,74 mm y vertical 1,97 mm.

### A3. Punta del jalón en "Calculada por la app" (RF-13, RF-27)
1. Antena: φ, λ de la GGA y h = altitud + separación (ADR-6). H = jalón + 0,134 m (`VerticalPole.antennaHeightM`).
2. Una época: punta = `offset(antena, −H·u)`.
3. **Promediado:** antena = la media aritmética de φ, λ y h de S010, y ū = la media de los uᵢ (sin normalizar); punta = `offset(antena, −H·ū)`.
   - Es la media de las puntas de cada época, porque media(aᵢ − H·uᵢ) = media(aᵢ) − H·media(uᵢ).
   - Las orientaciones del plano tangente en puntos a centímetros difieren en 10⁻⁸ rad: menos de 1 µm.
   - ū se guarda con el punto (A6).
4. **Sin altitud o separación (RF-27):** se hace el paso 2 o 3 con h = 0 y la punta sale sin altura.
   - Usar h = 0 en lugar de la altura real desvía la planta H·sinθ·h/R: 0,5 mm a 2000 m con 1,9 m de altura de antena a 60°.
5. La punta pasa a `ProjectPositionCalculator.fromGround` (paso 2 del orden fijo de `docs/arquitectura.md`, antes del SC).

### A4. Posición en directo (RF-16, RF-17, RF-21)
`LiveTip.of(fix, tilt, mode, poleHeightM)` → `LiveTipResult(ground: GeodeticPosition, compensated: Boolean, extraHorizontalM: Double, extraVerticalM: Double)`:

| Modo | Época | Suelo | `compensated` | Precisión extra |
|---|---|---|---|---|
| APP | en estado 30 (con o sin aviso) | A3 con una época | true | A2.5 |
| APP | fuera del estado 30 | jalón vertical (S002) | false | 0 |
| RECEIVER | cualquiera | la GGA tal cual (φ, λ, alt + sep) | true si está en estado 30 | 0 |
| VERTICAL o sin modo | cualquiera | jalón vertical (S002) | — (no se avisa) | 0 |

- Coordenadas usa `GroundPointCalculator.fromGround(ground, solución)`. El panel usa `ProjectPositionCalculator.fromGround`.
- El rover se dibuja en la φ, λ del suelo. El círculo mide GST H + la precisión extra en planta (RF-21).
- "Punta sin compensar" se ve cuando el modo usa la inclinación y `compensated == false`.

### A5. Medida (RF-13, RF-14, RF-19 a RF-22, RF-27)
`PointMeasurement(..., tilt: TiltSetup(mode, verified) = TiltSetup.VERTICAL)`. Para cada época:
1. Sin posición GGA: se descarta (como ahora).
2. Si el modo usa la inclinación y la época no está limpia (A2.2): se descarta. Se emite `MeasurementUpdate.TiltLost(guía)` una vez, hasta que llegue una época limpia (RF-19).
   - Va antes de la comprobación del FIX: si fallan los dos, la guía ("GNSS malo") explica más.
3. Promediado con solo FIX y una época sin FIX: como S010/RF-10.
4. Época usada: si está en estado 30 (en cualquier modo), se guardan su uᵢ, su θᵢ y su precisión de la inclinación.
5. Al terminar:
   - **VERTICAL:** como ahora. Los `TiltAngles` se guardan si todas las épocas usadas están en estado 30.
   - **APP:**
     - suelo según A3;
     - precisión H = GST H + la mayor precisión de la inclinación en planta, y V = GST V + la mayor en vertical (RF-21);
     - `TiltAngles` siempre (el paso 2 lo garantiza).
   - **RECEIVER:**
     - la antena guardada es la media de la GGA, que ya es la punta, y el suelo es esa misma posición sin restar nada (RF-14);
     - precisión de la GST;
     - `TiltAngles` con `receiverPoleHeightM = jalón`.
   - `MeasurementResult.tilt: PointTilt` lleva el modo, si estaba comprobado y los ángulos.
   - `maxTiltRad` es la mayor θᵢ.
6. El recolector comprueba el control con `PrecisionControl.check(isFix, h, v, maxTiltRad)`. Con el modo VERTICAL pasa `maxTiltRad = null` y la condición no se mira (RF-20).

### A6. Corrección del jalón (RF-25)
`PointCorrections.withPoleHeight(point, jalón, crs, hs)`, con `a` la antena guardada y ū el `pole` guardado:
- APP con ángulos: suelo = `offset(a, −(jalón + 0,134)·ū)`.
- RECEIVER con ángulos: suelo = `offset(a, −(jalón − receiverPoleHeightM)·ū)`. `a` ya era la punta con el jalón declarado, que no cambia.
- VERTICAL o sin ángulos: como ahora (S011/RF-14).
- `a` sin altura: A3.4.
- Lo demás de S011 no cambia: coordenadas editadas, historial y transacción.

### A7. Prueba del asistente (RF-5 a RF-9)
`TiltModeWizard(antennaHeightM)`. H = jalón de la app + 0,134 m. Estados:
`Ready(missing)` → `Reference(progress)` → `Tilt(tiltRad, expectedM)` → `Hold(progress)` → `Proposed(mode, movedM, expectedM)` o `Inconclusive(reason)`.
1. **Antes de empezar (RF-5):** `missing` = {FIX si la GGA no es FIX, TILT si la época no está limpia}. `start()` solo vale con `missing` vacío.
2. **Después de empezar:** cada época tiene que tener FIX y estar limpia. Si no, `Inconclusive(LOST)` (RF-9).
3. **Referencia (RF-6):**
   - se juntan épocas seguidas con θ < 5° hasta cubrir 1 s (Σ intervalos ≥ 1 s, con el intervalo de las horas UTC); una época con θ ≥ 5° vuelve a empezar la cuenta;
   - referencia = media de φ, λ y h de la GGA, y a_ref = media de H·(sinθ·sinα, sinθ·cosα).
4. **Inclinación (RF-6 a RF-8):**
   - por época, el movimiento esperado es e = H·(sinθ·sinα, sinθ·cosα) − a_ref;
   - la cuenta se junta con épocas seguidas con θ ≥ 15° y |e| ≥ 0,20 m; con θ ≥ 15° y |e| < 0,20 m, se pide "Inclina más";
   - cubierto 1 s, m = la planta de `between(referencia, media de la GGA del segundo)` y ē = la media de e.
5. **Decisión:**
   - |m| < 0,05 m → `RECEIVER` (RF-7);
   - si no, |m − ē| < 0,25·|ē| → `APP` (RF-8);
   - si no, `Inconclusive(MISMATCH)` (RF-9).
   - Las dos condiciones nunca se cumplen a la vez: con |ē| ≥ 0,20 y |m| < 0,05, |m − ē| > |ē| − 0,05 ≥ 0,75·|ē|.
6. **Plazo:** a los 60 s de `start()` sin decisión, `Inconclusive(TIMEOUT)` (RF-9).
7. La decisión sale en la misma época que completa el segundo (menos de 1 s; RNF).
8. **Confirmar (RF-10):** se guarda `ReceiverTiltSettings(mode, verified = true, detected = mode)`. "Repetir" vuelve a `Ready`.

### A8. Receptor en uso y su modo (RF-1, RF-2, RF-4)
1. Clave:
   - con una reproducción (`snapshot.replay != null`), `replay`;
   - con un receptor real, `ReceiverConnections.currentAddress`;
   - sin conexión, null.
2. `CurrentTilt.state: StateFlow<CurrentTiltState(key, settings, wizardOpen, revision)>`, con `TiltPreferences.settings(key)` y `flatMapLatest`.
3. **RF-2:** con clave, `settings.mode == null` y la época con ETC en un estado que no sea OFF → `MeasureBlocker.TILT_MODE_MISSING`. Sin ETC, o con OFF, se mide con el jalón vertical como hasta ahora.
4. **RF-18:** con un modo que usa la inclinación y la época fuera del estado 30 → `MeasureBlocker.TILT_NOT_READY(guía)`.
5. **RF-4:** al elegir `VERTICAL` con `detected == RECEIVER`, se muestra el aviso. El modo se guarda igualmente: es una decisión del usuario.

### A9. Cancelar al cambiar el modo (RF-28)
1. `CurrentTilt.revision` sube cada vez que se guarda el modo del receptor en uso y cada vez que se abre el asistente (`wizardOpen` pasa a true).
2. El recolector guarda la revisión al empezar. Si cambia durante la medida, llama a `cancel()` y muestra "Medida cancelada: ha cambiado el modo de inclinación".

## Contratos
- `LocalTangentPlane.offset(origin: GeodeticPoint, east: Double, north: Double, up: Double, ellipsoid = GRS80): GeodeticPoint` y `LocalTangentPlane.between(from: GeodeticPoint, to: GeodeticPoint, ellipsoid = GRS80): Enu`, con `Enu(east, north, up)`.
- `TiltEpoch`:
  - `isCompensating(tilt: TiltData?): Boolean`, `isClean(tilt: TiltData?): Boolean`, `guide(tilt: TiltData?): TiltGuide?`;
  - `poleVector(tilt): PoleVector` y `accuracy(tilt, antennaHeightM): TiltAccuracy(horizontalM, verticalM)`.
- `enum TiltGuide { NO_TILT, FATAL, SETUP, ALIGNMENT, FAST_MOTION, BAD_GNSS, FILTER_FAULT }`.
- `PoleTip.fromAntenna(antenna: GeodeticPosition, pole: PoleVector, antennaHeightM: Double): GeodeticPosition`, y `PoleTip.alongPole(position, pole, deltaM)` para A6.
- `LiveTip.of(fix: GgaFix, tilt: TiltData?, mode: TiltMode?, poleHeightM: Double): LiveTipResult?`.
- `TiltSetup(mode: TiltMode, verified: Boolean)`, `MeasurementUpdate.TiltLost(guide: TiltGuide)` y `MeasurementResult.tilt: PointTilt`, `maxTiltRad: Double?`.
- `TiltModeWizard(antennaHeightM)`: `onEpoch(epoch, nowMs)`, `start(nowMs)`, `reset()` y `state: WizardState`.
- `TiltPreferences`: `settings(key: String): Flow<ReceiverTiltSettings>`, `save(key, settings)`.
- `CurrentTilt` (app, @Singleton): `state`, `choose(mode)` (a mano, RF-3), `confirm(mode)` (asistente, RF-10), `openWizard()`, `closeWizard()`.
- `Alerts.tiltLost()`: un pitido y una vibración, distintos de `solutionLost()`.
- `PrecisionControl.check(isFix, horizontalM, verticalM, maxTiltRad: Double? = null)`: añade `TILT` si `maxTiltRad` supera el límite.
- `AngleFormat.direction(valueRad, unit, locale)`: DD con 3 decimales y "°", DMS con segundos enteros, y gon con 3 decimales.
- **Textos nuevos (es/en):**
  - **Menú y apartado:**
    - entrada y título: "Inclinación";
    - resumen de la entrada: "%1$s · comprobado", "%1$s · sin comprobar" o "Sin elegir";
    - modos: "Calculada por la app", "Compensada por el receptor" y "Jalón vertical", con una línea de ayuda cada uno;
    - botón: "Asistente del modo";
    - RF-4: "El asistente encontró este receptor compensando la posición. Con «Jalón vertical», el receptor tiene que estar sin compensar la posición o con la inclinación apagada."
  - **Asistente:**
    - qué falta: "Falta: solución FIX", "Falta: inclinación en «Compensando» sin avisos";
    - pasos: "Empezar la prueba", "Mantén la punta quieta con el jalón vertical", "Inclina el jalón 15° o más sin mover la punta", "Inclina más el jalón", "Aguanta así";
    - resultados:
      - "El receptor compensa la posición: «Compensada por el receptor». Se recomienda «Calculada por la app», con el receptor sin compensar la posición.";
      - "El receptor no compensa la posición: «Calculada por la app».";
      - "Movimiento de la posición: %1$s; esperado sin compensar: %2$s";
    - no concluyente: "La prueba no es concluyente: %1$s", con los motivos "la posición no se ha movido como en ningún modo", "se ha perdido el FIX o la inclinación" y "el jalón no se ha inclinado a tiempo";
    - botones: "Confirmar" y "Repetir".
  - **Guía (RF-12):** los siete textos de la tabla de la spec y la descripción de la ilustración para TalkBack.
  - **Recolector:**
    - RF-2: "Elige el modo de inclinación de este receptor o pasa el asistente";
    - RF-15: "Altura del jalón (la del receptor)" y "Tiene que coincidir con la altura de antena configurada en el receptor";
    - RF-17: "Punta sin compensar";
    - RF-20: "Inclinación máxima" e "Inclinación máxima superada";
    - RF-28: "Medida cancelada: ha cambiado el modo de inclinación".
  - **Estado (RF-11):**
    - tarjeta y filas: "Inclinación", "Estado", "Aviso", "Dirección", "Rumbo", "Precisión en planta", "Precisión vertical";
    - avisos: "Ninguno", "Movimiento rápido", "GNSS malo", "Fallo del filtro".
  - **Ficha (RF-23, RF-26):**
    - tarjeta y filas: "Inclinación", "Modo", "Comprobado" / "Sin comprobar", "Inclinación máxima", "Precisión de la inclinación en planta" y "en vertical";
    - en "Compensada por el receptor", la antena se rotula "Punta (compensada por el receptor)".
  - **Exportar:** nombres de las ocho columnas nuevas.
- **Columnas CSV (RF-24),** tras `Baseline`:
  - `Tilt mode` (`APP`, `RECEIVER` o `VERTICAL`) y `Tilt mode verified` (`true` o `false`, como `Replay`; vacía en `VERTICAL`);
  - `Tilt`, `Tilt direction`, `Heading` y `Maximum tilt`, en grados decimales con 3 decimales;
  - `Tilt horizontal accuracy` y `Tilt vertical accuracy`, en la unidad del proyecto con 4 decimales.
  - Todas van vacías sin el dato. El total pasa de 35 a 43 columnas.
- **Log (S014/RF-5),** sin coordenadas:
  - AVISO recolector "Guía de la inclinación: <texto>", al aparecer o cambiar;
  - AVISO recolector "Épocas descartadas por la inclinación: <texto>";
  - AVISO recolector "Medida cancelada: ha cambiado el modo de inclinación";
  - INFO receptor "Modo de inclinación: <modo> (comprobado | sin comprobar)";
  - AVISO receptor "Asistente de la inclinación no concluyente: <motivo>";
  - AVISO receptor "Jalón vertical con un receptor que compensa".

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Plano tangente local en `core:geodesy`, sobre `Ecef` | Geodesia en un solo módulo, validada contra pyproj (constitución nº 2); lo usan la punta y el asistente | Hacer la rotación dentro de `core:survey`: geodesia repartida |
| Media de la antena menos H·ū en lugar de la media de las puntas | Da lo mismo (A3.3) y deja ū guardado para corregir el jalón (RF-25) | Volver a leer las ETC de `rawNmea` al corregir: frágil, y `forExport` no lleva `rawNmea` |
| Se guarda ū (tres números) y la altura del jalón declarada en RECEIVER | Con ellos RF-25 es un desplazamiento exacto, sin las épocas | Guardar cada época: tabla nueva sin otro uso |
| El modo por receptor en el DataStore `"receiver"`, sin viajar en las copias | Es del receptor y del móvil, como el último receptor (S004) | Tabla de Room: no hay consultas ni relaciones que la justifiquen |
| Clave `replay` común a todas las reproducciones | Una reproducción no tiene dirección; las de prueba vienen del mismo receptor simulado | Un modo por fichero: obliga a elegirlo en cada reproducción |
| La inclinación máxima con dos claves (activa y valor) | Está activa por defecto: la ausencia de clave no puede ser "desactivada" | Una sola clave con un valor centinela |
| La migración 7 → 8 marca `VERTICAL` en los puntos medidos | Es como se calcularon (RNF-T6); el CSV y la ficha lo dicen sin adivinar | Dejar NULL y leerlo como vertical: el NULL también es el de los puntos manuales |
| Estado 30 sin algún campo → guía "Esperar" (`SETUP`) | No se puede calcular la punta y no hay nada que hacer salvo esperar; es lo menos engañoso | Tratarlo como sin ETC: pediría encender algo que ya está encendido |
| La comprobación de la inclinación va antes que la del FIX en la medida | Con "GNSS malo" se pierden las dos, y la guía dice qué hacer | Avisar dos veces |
| El asistente como máquina de estados en `core:survey` | Kotlin puro y testeable con épocas sintéticas (constitución nº 6) | Lógica dentro del ViewModel: solo con Robolectric |
| Medias de 1 s en el asistente y 20 cm de movimiento esperado | El ruido RTK (1 a 2 cm por época) queda lejos de 5 cm, y los criterios de RF-7 y RF-8 no se solapan (A7.5) | Una sola época: un salto del FIX decide el modo |
| `CurrentTilt` con un número de revisión | El recolector y el apartado "Inclinación" están en grafos distintos; la revisión dice sin ambigüedad que algo cambió durante la medida (RF-28) | Comparar el modo antes y después: no ve que se abriera el asistente |
| `ReceiverConnections.currentAddress` como `StateFlow` | La clave del modo necesita saber qué receptor está conectado; hoy es una variable privada | Leer la dirección de la descripción "Nombre (dirección)": depende de un texto |
| Ilustración del ocho como un vector propio (lemniscata con flechas) | RNF-T14: ningún recurso de terceros | Una animación: más peso sin ganar claridad |
| `TiltData.rawSentence` | Guardar la ETC tal como llegó, como la GGA y la GST (RF-22) | Volver a montar la frase: no sería la original |

## Estrategia de tests
- **`core:geodesy`:** `LocalTangentPlaneTest` contra `punta_jalon.csv`, generado con pyproj (`cart` + `topocentric`):
  - Península, Baleares y Canarias, de 0 a 3000 m de altura;
  - inclinaciones de 0° a 60°, direcciones de 0° a 360° y alturas de antena de 0,134 a 10,134 m;
  - offset ≤ 1 mm; `between` de ida y vuelta ≤ 0,1 mm.
- **`core:gnss`:** `EbpEtcParserTest`, con `rawSentence`.
- **`core:survey`:**
  - `TiltEpochTest`: estado 30 con y sin campos, limpia, la tabla de guías, el vector del jalón y la precisión (ejemplo de ETC_rev_3, A2.5);
  - `PoleTipTest`:
    - contra `punta_jalon.csv` (≤ 1 mm);
    - con 0° coincide con el jalón vertical (≤ 0,1 mm);
    - sin altura, solo planta (RF-27, ≤ 1 mm frente a pyproj con la altura real hasta 2000 m);
  - `LiveTipTest`: la tabla de A4;
  - `PointMeasurementTiltTest`:
    - descarte y `TiltLost` una sola vez;
    - instantánea que espera a una época limpia;
    - promediado con el jalón moviéndose y la punta fija, que vuelve a la punta (≤ 1 mm);
    - RECEIVER sin restar el jalón;
    - VERTICAL con los ángulos como dato;
    - precisión con la mayor de las épocas;
    - ETC en `rawNmea`;
    - sin altura;
  - `PointCorrectionsTiltTest`: APP y RECEIVER a lo largo del jalón, contra la punta calculada con el jalón nuevo (≤ 1 mm);
  - `TiltModeWizardTest`:
    - con épocas sintéticas de los dos modos;
    - no concluyente por mezcla, por pérdida y por plazo;
    - jalón de 0 m (pide más inclinación);
    - una época inclinada durante la referencia;
    - la decisión en la misma época;
  - `PrecisionControlTest` con `TILT`.
- **`data`:**
  - `TopoPhoneDatabaseTest`: paso 7 → 8, en el que un punto medido queda `VERTICAL` y uno manual sin modo; `CURRENT_VERSION = 8`;
  - `BackupMigrationTest` sin cambios;
  - `PointRepositoryTest`: ida y vuelta de un punto con inclinación;
  - `PointRepositoryExportTest`: `forExport` con las columnas;
  - `TiltPreferencesTest`: por receptor, `replay`, a mano y comprobado, y `detected`;
  - `CollectorPreferencesTest`: la inclinación máxima por defecto, desactivada y su valor;
  - `SettingsArchiveTest`: las claves `tilt_` no viajan.
- **`core:formats` y `tools`:**
  - `CsvExportTest` y los ficheros dorados regenerados con un punto APP, uno RECEIVER y uno VERTICAL antiguo;
  - `tools/formatos/test_csv_exportado.py` con 43 cabeceras, las longitudes nuevas y una tolerancia para los ángulos de 3 decimales;
  - `ExportPreferencesTest`: un personalizado guardado deja las columnas nuevas sin marcar.
- **`tools/nmea`:** `generar_inclinacion.py` y su test. A 5 Hz, como el RS3, genera:
  - `rs3-inclinacion.nmea`:
    - configuración, alineación, compensando y un tramo de "movimiento rápido";
    - la punta quieta sobre un punto conocido y el jalón balanceándose (de 0° a 40°), con el receptor sin compensar;
    - `rs3-inclinacion.esperado.csv`, con la punta en ETRS89 y UTM de cada época, calculada con pyproj;
  - `rs3-asistente-app.nmea`: la prueba del asistente con el receptor sin compensar (la GGA sigue a la antena);
  - `rs3-asistente-receptor.nmea`: la prueba con el receptor que compensa (la GGA quieta en la punta, con ruido de 5 mm).
- **Robolectric (`app`):**
  - `TiltScreenTest`: los tres modos, "sin comprobar", el aviso de RF-4, el resumen en el menú y TalkBack;
  - `TiltWizardScreenTest`: qué falta, los pasos, la propuesta, la confirmación, la repetición y el no concluyente;
  - `CurrentTiltTest`: la clave por dirección y `replay`, y la revisión;
  - `StatusScreenTest`: la tarjeta "Inclinación" con el formato angular y la precisión;
  - `TiltGuideBannerTest`: los siete textos y la ilustración con su descripción;
  - `CoordinatesCardTest` y `ProjectViewTest`: la punta, "Punta sin compensar" y el círculo con la precisión de la inclinación;
  - `CollectorViewModelTest`:
    - bloqueos de RF-2 y RF-18;
    - la altura del jalón del receptor;
    - `TiltLost` con sonido y vibración;
    - la inclinación máxima y `TILT`;
    - guardado con inclinación;
    - cancelación por la revisión;
  - `PointDetailsTiltTest`: la tarjeta en las unidades del proyecto, el modo y la punta rotulada en RECEIVER;
  - `ReceiverLogRecorderTest` o `CollectorLogTest`: los avisos de la inclinación en el log.
- **Reproducción:** `TiltReplayTest`, con las tres reproducciones:
  - el asistente propone APP con `rs3-asistente-app` y RECEIVER con `rs3-asistente-receptor`;
  - con `rs3-inclinacion` en APP, la punta del panel coincide con lo esperado (≤ 1 mm) y un promediado descarta las épocas de "movimiento rápido".
  - `FullRs3ReplayTest` sigue igual: sin modo elegido, `rs3-completo` se ve con el jalón vertical.
- **Rendimiento:** `LiveTip` y `PointMeasurement` con épocas a 10 Hz caben en menos de 1 ms por época (RNF-T13), y el asistente decide en la misma época (menos de 1 s).
- **Campo (del usuario):** con el RS3:
  - el asistente en los dos modos;
  - punta calculada frente a jalón vertical sobre un mismo punto (parte de G2);
  - qué da la GGA al compensar fuera del estado 30;
  - si la GST ya incluye la inclinación en ese modo.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | `TiltPreferences`, `CurrentTilt`, apartado "Inclinación" | `TiltPreferencesTest`, `CurrentTiltTest`, `TiltScreenTest` |
| RF-2 | A8.3 | `CollectorViewModelTest` |
| RF-3 | `CurrentTilt.choose` | `TiltScreenTest`, `TiltPreferencesTest` |
| RF-4 | A8.5 | `TiltScreenTest` |
| RF-5 | A7.1 | `TiltModeWizardTest`, `TiltWizardScreenTest` |
| RF-6 | A7.3, A7.4 | `TiltModeWizardTest`, `TiltWizardScreenTest` |
| RF-7 | A7.5 | `TiltModeWizardTest`, `TiltReplayTest` |
| RF-8 | A7.5 | `TiltModeWizardTest`, `TiltReplayTest` |
| RF-9 | A7.2, A7.5, A7.6 | `TiltModeWizardTest`, `TiltWizardScreenTest` |
| RF-10 | A7.8, `CurrentTilt.confirm` | `TiltWizardScreenTest`, `TiltPreferencesTest` |
| RF-11 | Tarjeta "Inclinación", A2.5, `AngleFormat.direction` | `StatusScreenTest` |
| RF-12 | A2.3, `TiltGuideBanner` | `TiltEpochTest`, `TiltGuideBannerTest` |
| RF-13 | A1, A3 | `LocalTangentPlaneTest`, `PoleTipTest`, `PointMeasurementTiltTest`, `TiltReplayTest` |
| RF-14 | A5.5 (RECEIVER), A4 | `PointMeasurementTiltTest`, `LiveTipTest` |
| RF-15 | Recolector, etiqueta de la altura | `CollectorViewModelTest` |
| RF-16 | A4, Coordenadas, panel y rover | `LiveTipTest`, `CoordinatesCardTest`, `ProjectViewTest` |
| RF-17 | A4 | `LiveTipTest`, `CoordinatesCardTest`, `ProjectViewTest` |
| RF-18 | A8.4 | `CollectorViewModelTest` |
| RF-19 | A5.2, `Alerts.tiltLost` | `PointMeasurementTiltTest`, `CollectorViewModelTest`, `TiltReplayTest` |
| RF-20 | `PrecisionControl.maxTiltRad`, A5.6 | `PrecisionControlTest`, `CollectorPreferencesTest`, `CollectorViewModelTest` |
| RF-21 | A2.5, A4, A5.5 | `TiltEpochTest`, `LiveTipTest`, `PointMeasurementTiltTest`, `ProjectViewTest` |
| RF-22 | `PointTilt`, Room v8, A5 | `PointMeasurementTiltTest`, `TopoPhoneDatabaseTest`, `PointRepositoryTest`, `CollectorViewModelTest` |
| RF-23 | Ficha, tarjeta "Inclinación" | `PointDetailsTiltTest` |
| RF-24 | `ExportColumn`, `forExport` | `CsvExportGoldenTest`, `test_csv_exportado.py`, `ExportPreferencesTest` |
| RF-25 | A6 | `PointCorrectionsTiltTest`, `PointDetailsTiltTest` |
| RF-26 | Ficha, rótulo de la antena | `PointDetailsTiltTest` |
| RF-27 | A3.4 | `PoleTipTest`, `PointMeasurementTiltTest` |
| RF-28 | A9 | `CollectorViewModelTest` |
