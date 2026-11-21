# Plan técnico — Spec 033

> Lee antes: `docs/constitution.md` (nº 2: precisión frente a pyproj), `docs/arquitectura.md` (ADR-2, ADR-3, ADR-4, ADR-5), `docs/rnf-transversales.md` (RNF-T9, RNF-T14), `specs/033-transformaciones-datum/spec.md` y el código de `core:geodesy` (`Ecef.kt`, `Ed50.kt`, `HorizontalShiftGrid.kt`, `GeoTiffGrid.kt`, `Ellipsoid` en `TransverseMercator.kt`).

**Ninguna dependencia nueva**, ni en el APK ni en `tools/`: todo el cálculo es Kotlin puro en `core:geodesy` (ADR-2) y los vectores salen de `pyproj` (ya fijado en `tools/pyproject.toml`). **Sin cambios de Room** (sigue en v36), **sin interfaz** y sin textos de usuario: S034 y S035 usarán estas clases y traducirán los resultados a `strings.xml`. **Sin cambios en `docs/arquitectura.md`**. `Ed50.kt` y `Ecef.kt` **no se tocan** (RF-16).

## Estructura de módulos
- `core:geodesy`, paquete nuevo `es.topophone.core.geodesy.datum` (JVM puro, sin `android.*` ni Hilt), todo nuevo:
  - Contrato (RF-4, RF-6, RF-14, RF-15): `DatumTransformation`, `TransformResult`, `TransformationInfo`, `AreaOfUse`, `ParameterProblem`.
  - Parámetros: `HelmertParameters` y `MolodenskyBadekasParameters` (unidades de entrada → SI).
  - Transformaciones (RF-1 a RF-3, RF-5, RF-7 a RF-10): `HelmertTransformation`, `MolodenskyBadekas`, `GridShiftTransformation`.
  - Cadena (RF-11 a RF-13): `TransformationChain`.
- `tools/geodesia/datum.py` (nuevo): genera los vectores y lo llama `generar_vectores.py`. `fixtures/geodesia/`: `helmert.csv`, `molodensky_badekas.csv`, `rejilla_desplazamiento.csv`, `cadena_tres_pasos.csv`.

## Modelo de datos
Sin datos persistentes. En memoria, grados en la frontera (como `GeodeticPoint`) y radianes dentro:
```kotlin
enum class HelmertConvention { POSITION_VECTOR, COORDINATE_FRAME }
data class HelmertParameters(translationM: Triple<Double,Double,Double>, rotationArcSec: Triple<...>, scalePpm: Double, convention)
   // factorías: translations3(), translationsScale4(), rotations6(), seven() (RF-1)
data class MolodenskyBadekasParameters(val helmert: HelmertParameters, val pivot: Ecef)
sealed interface TransformResult {
    data class Ok(val point: GeodeticPoint, val warnings: List<TransformWarning>) : TransformResult      // RF-15
    data class OutsideGrid(val gridName: String?) : TransformResult                                      // RF-8
    data class GridUnavailable(val gridName: String) : TransformResult                                   // RF-9, RF-12
    data class NotConverged(val gridName: String?) : TransformResult                                     // RF-10
}
interface DatumTransformation {
    val info: TransformationInfo                                  // nombre, EPSG?, precisión (m)?, AreaOfUse? (RF-14)
    fun forward(point: GeodeticPoint): TransformResult
    fun inverse(point: GeodeticPoint): TransformResult
}
```
- Cada transformación lleva sus elipsoides de origen y destino (`Ellipsoid` existente). La rejilla cambia latitud y longitud y deja la altura igual (como `hgridshift`).
- La validación de parámetros (RF-6) devuelve todos los problemas a la vez (`ParameterProblem(parameter, NOT_FINITE)`), como `ProjectionFactory` de S032; las factorías de `HelmertParameters` devuelven `Result`-like (`ParametersResult.Ok/Invalid`).

## Algoritmos
- **A1. Helmert (RF-1, RF-2, RF-4; GN 7-2 §4.3.3.2 y §4.3.3.3).** Geográficas 3D → `Ecef.fromGeodetic` (elipsoide de origen) → `X′ = T + (1 + s·10⁻⁶)·R·X` → `Ecef.toGeodetic` (elipsoide de destino). La matriz `R` es la de rotaciones pequeñas de EPSG (`[1 −rz ry; rz 1 −rx; −ry rx 1]` en PV, traspuesta en CF), con las rotaciones en radianes. 3 parámetros: `R = I`, `s = 0`; 4: `R = I`; 6: `s = 0`; 7: todo.
- **A2. Inverso exacto (RF-5).** `X = R⁻¹·(X′ − T)/(1 + s·10⁻⁶)` con `R⁻¹` calculada exactamente (adjunta/determinante de 3×3), no `R` con las rotaciones cambiadas de signo. Así la ida y la vuelta cierran a precisión de máquina (≪ 0,1 mm) y pyproj/EPSG (cambio de signo) difiere en menos de 1 mm incluso con rotaciones de 1″ y escala de 10 ppm.
- **A3. Molodensky-Badekas (RF-3; §4.3.3.4).** `X′ = T + P + (1 + s·10⁻⁶)·R·(X − P)` con `P = (X₀, Y₀, Z₀)`; inversa exacta igual que A2 (`X = P + R⁻¹·(X′ − T − P)/(1 + s)`). Con `P = 0` coincide con el Helmert de 7 (caso límite del punto de rotación en el centro de la Tierra).
- **A4. Rejilla de desplazamiento (RF-7, RF-8; ADR-4, ADR-5).** Directa: `HorizontalShiftGrid.offset(lat, lon)` (primera subrejilla del fichero que contiene el punto) y suma de `Δφ/3600`, `Δλ/3600` en grados; sin offset → `OutsideGrid`. Es la fórmula de `Ed50.toEtrs89`, sin llamarla (RF-16).
- **A5. Inversa de la rejilla (RF-10).** Iteración de punto fijo sobre las coordenadas de destino como en `Ed50.fromEtrs89`, pero con parada cuando el paso cambia menos de 1·10⁻¹² ° (≈ 0,1 µm) y como máximo 30 iteraciones; si no converge o sale de la rejilla → `NotConverged` u `OutsideGrid`. La ida y vuelta se comprueba en test a ≤ 0,1 mm.
- **A6. Carga y fallo de la rejilla (RF-9, RF-12).** `GridShiftTransformation` recibe un `GridSource` (`fun read(name): ByteArray?`), lee la rejilla de forma perezosa y una sola vez; `null` o una excepción de lectura dan `GridUnavailable(name)` (nunca se calcula sin ella). La rejilla de España se lee del recurso `shift/` (igual que `Ed50`); las descargadas las proveerá S034 con otro `GridSource`.
- **A7. Cadena (RF-11, RF-12, RF-13).** `TransformationChain(steps)` en una sola pasada por punto: los pasos geocéntricos consecutivos (Helmert, MB) se componen en ECEF sin volver a geográficas, y solo se pasa a geográficas donde lo pide una rejilla o el final; no hay redondeos ni `Float` intermedios. Si un paso devuelve un resultado que no es `Ok`, la cadena devuelve ese resultado sin calcular los pasos siguientes (RF-12: no se da coordenada con un paso saltado). El sentido inverso recorre los pasos al revés y cada uno con su inverso.
- **A8. Metadatos y ámbito (RF-14, RF-15).** `TransformationInfo(name, epsgCode, accuracyM, areaOfUse)`; `AreaOfUse` es un rectángulo en geográficas (el de EPSG). Una transformación de parámetros con ámbito añade `TransformWarning.OutsideAreaOfUse` al `Ok` si el punto de entrada cae fuera, pero calcula. Las rejillas no avisan por ámbito: su límite es la propia rejilla (RF-8).

## Contratos
- Los resultados nunca lanzan excepción por un dato fuera de dominio (ADR-5, igual que S032/D3): un tipo cerrado obliga a decidir. Un punto con latitud/longitud no finita devuelve `OutsideGrid`/`Ok` según el caso; las transformaciones de parámetros con una entrada no finita devuelven `NotConverged(null)`.
- `TransformationChain` implementa `DatumTransformation`, así que una cadena puede ser un paso de otra.
- Sin textos de usuario: S034/S035 traducirán `TransformResult`, `TransformWarning` y `ParameterProblem`.

## Decisiones técnicas
| # | Decisión | Motivo | Alternativa descartada |
|---|---|---|---|
| D1 | Cálculo propio en Kotlin puro con las fórmulas de GN 7-2 | ADR-2, RNF-T14: sin librería ni NDK; la misma técnica que el resto de `core:geodesy` | proj4j (sin rejillas verticales y poco mantenida), PROJ por NDK (+10 MB por ABI) |
| D2 | Inverso exacto por inversión de `R` (entrevista 20/11/2026) | RF-5: la ida y vuelta cierra siempre; la diferencia con el cambio de signo de EPSG es < 1 mm | Cambio de signo (no cierra a 0,1 mm con rotaciones o escalas grandes) |
| D3 | Resultado como tipo cerrado `TransformResult` con la causa del rechazo, no `null` | RF-8, RF-9, RF-10 y RF-12 piden decir qué falla (fuera de la rejilla, qué rejilla falta, no converge); S032 usaba `null` porque solo había un motivo | `null` (pierde el nombre de la rejilla); excepciones (se olvidan y rompen la UI) |
| D4 | Los pasos geocéntricos consecutivos de una cadena se componen en ECEF y se vuelve a geográficas solo donde hace falta | RF-11 («una única conversión por punto»), menos error acumulado y más rápido; es lo que hace el pipeline de PROJ | Convertir a geográficas entre pasos (dos conversiones iterativas más por paso) |
| D5 | `HelmertTransformation`, `MolodenskyBadekas` y `GridShiftTransformation` en clases separadas, no `Ed50` generalizada | RF-16: no se toca ni un bit del cálculo de S008; el coste es repetir ~15 líneas de la iteración | Reescribir `Ed50` sobre `GridShiftTransformation` (más limpio, pero cambia un fichero con tests de S008) |
| D6 | El Helmert de 4 parámetros son traslaciones + escala (entrevista 20/11/2026) | Es una transformación de datum comparable con pyproj (`+proj=helmert` con rotaciones a 0); la semejanza plana ya es S039 | Semejanza 2D (no es un cambio de datum) |
| D7 | Las rejillas se leen con un `GridSource`; esta spec solo conoce la SPED2ETV2 incluida | S034 descarga las demás (fuera de alcance de esta spec); el test de «rejilla ausente» usa una fuente que devuelve `null` | Leer ficheros directamente (obliga a `java.io.File` y a Android después) |
| D8 | Un CSV por familia con la cabecera de versiones (`pyproj`, `PROJ`), generado por `datum.py` | Reproducible (constitución nº 2); mismo formato que `lcc.csv` y `ed50_utm.csv` | Un CSV por método y por sentido (veinte ficheros) |

## Estrategia de tests
- **Unitarios JVM (`core/geodesy/src/test/kotlin/…/datum/`, JUnit 5 + kotest):**
  - Helmert: ida frente a pyproj (≤ 1 mm; geográficas ≤ 1·10⁻⁸ °) en 3, 4, 6 y 7 parámetros en PV y CF, con varios pares de elipsoides; parámetros a cero; rotaciones ≥ 1″ y escala ≥ 10 ppm; una convención equivocada da un resultado distinto (RF-2).
  - Inversa: ida y vuelta ≤ 0,1 mm y diferencia con el inverso de pyproj < 1 mm (RF-5); parámetros no finitos (RF-6).
  - MB: vectores de pyproj, con `P = 0` igual al Helmert 7, y ida y vuelta (RF-3).
  - Rejilla: vectores de `hgridshift` con la SPED2ETV2 (centro, borde, fuera de la rejilla y subrejilla), inversa con desplazamientos de más de 100 m (RF-7, RF-8, RF-10); rejilla ausente e ilegible (RF-9).
  - Cadena: tres pasos (Helmert → rejilla → Helmert) frente a pyproj ≤ 1 mm (RF-11); paso con rejilla ausente rechaza el punto (RF-12); no se redondea (comparación bit a bit con la composición manual, RF-13); metadatos y aviso de ámbito (RF-14, RF-15); `Ed50` y las proyecciones de S008 con sus vectores sin cambio y una cadena con la rejilla de ED50 igual a `Ed50.toEtrs89` (RF-16).
  - Rendimiento: 100.000 puntos con rejilla < 2 s tras calentamiento (con el margen de siempre en los tests de tiempo).
- **Python (`tools/geodesia/test_datum.py`):** cada fichero lleva la cabecera de versiones, las columnas y el recuento de filas esperados, y cada caso usa el método de EPSG que se espera; el generador es determinista.
- **Manual o de campo:** no aplica (sin interfaz). La demo es ejecutar los tests de referencia.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, D6 | `HelmertTransformationTest` |
| RF-2 | A1 | `HelmertConventionTest` |
| RF-3 | A3 | `MolodenskyBadekasTest` |
| RF-4 | A1, contratos | `HelmertParametersTest` |
| RF-5 | A2, A3, D2 | `HelmertInverseTest` |
| RF-6 | modelo de datos | `HelmertParametersTest` |
| RF-7 | A4 | `GridShiftTransformationTest` |
| RF-8 | A4 | `GridShiftOutsideTest` |
| RF-9 | A6 | `GridShiftUnavailableTest` |
| RF-10 | A5 | `GridShiftInverseTest` |
| RF-11 | A7, D4 | `TransformationChainTest` |
| RF-12 | A7 | `TransformationChainMissingGridTest` |
| RF-13 | A7 | `TransformationNoRoundingTest` |
| RF-14 | A8 | `TransformationInfoTest` |
| RF-15 | A8 | `TransformationAreaWarningTest` |
| RF-16 | D5 | `Ed50UnchangedTest` |

## Riesgos
- **Nota GN 7-2:** el PDF no está en este equipo (`docs/referencias/geodesia/` está vacía). Las comprobaciones independientes son pyproj/PROJ con las mismas operaciones de EPSG que usa la nota (métodos 9603, 9606, 9607 y 9636, que el generador comprueba en la base de PROJ antes de usarlas). Si aportas la nota, se añaden sus valores publicados como `ejemplos_epsg_gn72.csv`.
- **Una sola rejilla real:** solo está instalada la SPED2ETV2; el resto de rejillas (CDN de PROJ) las descarga S034. Para probar subrejillas y la inversa con desplazamientos grandes se usan los puntos de esa rejilla; no se inventa otra.
- **Diferencia inversa con EPSG:** si pyproj no diera < 1 mm en el inverso con rotaciones grandes, se amplía la comparación a la ida y vuelta propia y se anota en «Cambios al implementar».
- **Tests de tiempo:** bajo carga pueden fallar a la primera: se repiten con `--no-parallel` antes de investigar y se mira `Get-Process` por procesos sueltos.

## Cambios en documentos anteriores
- Aplicados al aprobar la spec: `docs/hoja-de-ruta.md` (S033 `spec`, S034 descarga también las rejillas de desplazamiento), `docs/glosario.md` (Helmert, Molodensky-Badekas, Rejilla de desplazamiento) y `specs/pendientes/README.md`.
- Al terminar: `docs/hoja-de-ruta.md` (S033 `implementada`), `fixtures/LEEME.md` (fila de los cuatro CSV nuevos) y `tools/LEEME.md` (nota del generador).
- `specs/pendientes/034-biblioteca-sc-mundial` y `035-sc-personalizado`: se anota al abrirlas que S033 entrega `DatumTransformation`/`TransformResult` y que S034 descarga las rejillas de desplazamiento.

## Cambios al implementar
- **El inverso exacto no es «siempre < 1 mm» frente a pyproj (T4).** Cambiar el signo de los parámetros solo invierte la matriz hasta el primer orden: la diferencia con el inverso exacto es del orden de R·(s² + Σθ²). Con rotaciones de 3″ y 15 ppm ya son 1,3 mm y con 10″ y 50 ppm unos 5 cm. La spec dice «siempre < 1 mm»; solo es cierto con parámetros habituales (≤ 1,2″ y ≤ 7 ppm en los vectores). El test usa 1 mm para esos y la cota teórica (el doble de R·(s² + Σθ²)) para los grandes. La ida y vuelta propia sí cierra a ≤ 0,1 mm en todos los casos. **Pendiente de decisión del usuario:** corregir la frase del NFR de `spec.md`.
- **Núcleo geocéntrico común (T6).** El plan decía clases separadas para Helmert y Molodensky-Badekas; lo son, pero comparten `GeocentricSevenParameter` (`X′ = T + P + (1 + s)·R·(X − P)`; Helmert es MB con `P` en el centro de la Tierra). La matriz inversa se calcula por adjunta y determinante.
- **Contrato (T1, T13, T14).** Los resultados de parámetros son `HelmertParametersResult` y `MolodenskyBadekasParametersResult` (no un `ParametersResult` único) y hay un `DatumParameter` con los diez parámetros. Se añadió al contrato `DatumTransformation.missingGrid()` (no estaba en el plan): la cadena lo consulta antes de calcular, así RF-12 se cumple tal cual (se nombra la rejilla que falta, también ante un rechazo anterior de otro paso, y no se calcula ningún paso). `GeocentricStep` y `ChainLink` son internos.
- **Cadenas (T12).** Las cadenas anidadas se aplanan; los pasos geocéntricos seguidos se componen en ECEF solo si el elipsoide de destino de uno es el de origen del siguiente; si no, se vuelve a geográficas. El aviso de ámbito se calcula también al componer (se sacan las geográficas solo para mirarlo).
- **Rejilla (T8–T10).** `GridSource` y `ClasspathGridSource` para leer las rejillas; la rejilla se rechaza (`GridUnavailable`) si falta, no se lee, está truncada, la fuente lanza una excepción o no tiene dos bandas. `maxIterations` (30) es parámetro del constructor para poder probar «no converge». Si el resultado de la inversa cae fuera de la rejilla se rechaza (`OutsideGrid`) aunque PROJ lo extrapole en el borde; en la franja de unos 140 m dentro del borde la coordenada de partida cae fuera de la rejilla aunque la solución esté dentro, y para eso la iteración arranca con un desplazamiento de sondeo cercano (el resultado final siempre se evalúa dentro de la rejilla).
- **Solape de subrejillas (T10, T16).** Donde se solapan la subrejilla de la Península y la de Baleares (en el mar, 42 de 459 puntos de la prueba de medio grado) sus desplazamientos difieren varios metros, la transformación no es inyectiva y `inverse(forward(x))` puede dar el otro punto. La inversa siempre devuelve un punto que la directa lleva al de partida (se prueba). PROJ falla en la misma zona: su inversa no cierra 3,6 m en (38,39°, 0,83°), punto que el test excluye de la comparación (tope de dos exclusiones).
- **Vectores (T2, T5, T7, T11).** Las tuberías de PROJ de las operaciones de EPSG son 2D (`axisswap`, `push v_3`, `pop v_3`); se quitan y se calcula en 3D. Operaciones de EPSG usadas: 10085, 1112, 1024 (Helmert) y 1066, 6889, 7806 (MB), con su método comprobado en la base de PROJ. La cadena solo lleva la directa de PROJ (la vuelta se prueba con la ida y vuelta propia). En una cadena la rejilla no cambia de elipsoide ni toca la altura: el paso siguiente interpreta (latitud, longitud, altura) con su propio elipsoide, igual que la tubería de PROJ.
- **No se tocó:** `Ed50.kt`, `Ecef.kt`, `TransverseMercator.kt`, `HorizontalShiftGrid.kt` ni `GeoTiffGrid.kt` (RF-16, comprobado con `git diff` y con `Ed50UnchangedTest`), ni ninguna dependencia, ni Room.

## Validación
- `uv run --project tools tools/trazabilidad/rf_report.py`: **S033: 16 RF, 16 con test, 0 pendientes**.
- `./gradlew check --no-parallel` en verde (ktlint, Lint, reglas de módulos, tests y Kover; los tests de tiempo y de ViewModel intermitentes de `:app` y `:data` se repiten hasta que pasan, a veces cuatro veces; durante la sesión un `explorer.exe` ajeno ocupaba casi un núcleo); `uv run --project tools pytest tools` en verde; `forbidden_files.py` sin hallazgos; `assembleDebug` compila.
- Tests nuevos: 85 en `core/geodesy` (paquete `datum`, 16 clases) más los de `tools/geodesia/test_datum.py`. Cuatro CSV nuevos con 1.322 vectores (≈ 330 KB) en `fixtures/geodesia/`: `helmert.csv` (725), `molodensky_badekas.csv` (340), `rejilla_desplazamiento.csv` (117) y `cadena_tres_pasos.csv` (140).
- Precisión contra pyproj/PROJ (9.8.1): directa ≤ 1 mm y ≤ 1·10⁻⁸ ° en Helmert (3, 4, 6 y 7 en PV y CF), MB, rejilla (5·10⁻⁹ °) y cadenas de tres y cuatro pasos; ida y vuelta ≤ 0,1 mm en todo el mundo (Helmert y MB), en la Península y Baleares (rejilla, salvo el solape de subrejillas) y en las cadenas; 100.000 puntos en 19 ms la rejilla directa, 23 ms la inversa y de 0,1 a 0,8 s la cadena de tres pasos, frente a un límite de 2 s.
- **Lo que no se pudo contrastar con la nota GN 7-2:** el PDF no está en este equipo; las comprobaciones independientes son pyproj/PROJ sobre operaciones de EPSG (los mismos CRS que usa la nota). Solo hay una rejilla real instalada (SPED2ETV2): el resto de rejillas del CDN de PROJ las descargará S034.
- Sin demo visible, como dice la spec (es un motor de cálculo): la pantalla que lo use llega con S034 y S035.
