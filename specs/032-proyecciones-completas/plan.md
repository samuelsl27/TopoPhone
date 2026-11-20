# Plan técnico — Spec 032

> Lee antes: `docs/constitution.md` (nº 2: precisión frente a pyproj), `docs/arquitectura.md` (ADR-2, ADR-3, ADR-5), `docs/rnf-transversales.md` (RNF-T9, RNF-T14), `specs/032-proyecciones-completas/spec.md` y el código de `core:geodesy` (`TransverseMercator.kt`, `WebMercator.kt`).

**Ninguna dependencia nueva**, ni en el APK ni en `tools/`: todo el cálculo es Kotlin puro en `core:geodesy` (ADR-2) y los vectores salen de `pyproj` (ya fijado en `tools/pyproject.toml`, `>=3.8,<3.9`). **Sin cambios de Room** (sigue en v36), **sin interfaz** y sin textos de usuario: S034 y S035 usarán estas clases. **Sin cambios en `docs/arquitectura.md`** (ADR-3 ya limita el catálogo a los métodos implementados).

## Estructura de módulos
- `core:geodesy`, paquete nuevo `es.topophone.core.geodesy.projection` (JVM puro, sin `android.*` ni Hilt), todo nuevo:
  - Contrato (RF-16, RF-17, RF-19, RF-20): `ProjectionMethod`, `ProjectionParameters` (una clase de datos por variante), `ParameterProblem`, `MapProjection`, `ProjectionSetup` y `ProjectionFactory`.
  - Métodos (RF-1 a RF-15): `LambertConic`, `Mercator`, `ObliqueMercator`, `ObliqueStereographic` y `PolarStereographic`, cada uno con sus variantes.
- `TransverseMercator.kt`, `WebMercator.kt` y todo lo que ya existe **no se tocan** (RF-21): el código nuevo no comparte funciones privadas con ellos (se duplican, en pocas líneas, las de la latitud conforme).
- `tools/geodesia/proyecciones.py` (nuevo): genera los vectores de estos métodos y lo llama `generar_vectores.py`. `fixtures/geodesia/`: un CSV por familia (`lcc.csv`, `mercator.csv`, `omerc.csv`, `stereo_oblicua.csv`, `stereo_polar.csv`, `meridiano_origen.csv`, `unidades_proyeccion.csv`).

## Modelo de datos
Sin datos persistentes. En memoria, todo con grados en la frontera (como `TransverseMercator`, `GeoPoint` y los vectores) y radianes dentro:
```kotlin
enum class ProjectionMethod { TRANSVERSE_MERCATOR, LAMBERT_CONIC_1SP, LAMBERT_CONIC_2SP, MERCATOR_A, MERCATOR_B,
    OBLIQUE_MERCATOR_A, OBLIQUE_MERCATOR_B, OBLIQUE_STEREOGRAPHIC, POLAR_STEREOGRAPHIC_A, POLAR_STEREOGRAPHIC_B,
    POLAR_STEREOGRAPHIC_C, WEB_MERCATOR }                         // + ProjectionMethod.implemented (RF-20)
sealed interface ProjectionParameters { val method: ProjectionMethod }
// p. ej.: LambertConic2Sp(latFalseOriginDeg, lonFalseOriginDeg, lat1Deg, lat2Deg, falseEasting, falseNorthing)
data class ProjectionSetup(val ellipsoid: Ellipsoid, val parameters: ProjectionParameters,
    val linearUnit: LinearUnit = LinearUnit.METRE, val primeMeridianDeg: Double = 0.0)
data class ParameterProblem(val parameter: ProjectionParameter, val kind: ProblemKind)   // RF-3, RF-6, RF-9, RF-12, RF-15
interface MapProjection {
    fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint?   // null = punto sin imagen (RF-19)
    fun inverse(eastingM: Double, northingM: Double): GeoPoint?
    fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors?     // PointFactors ya existe (S008)
}
sealed interface ProjectionResult { data class Ok(val projection: MapProjection); data class Invalid(val problems: List<ParameterProblem>) }
```
- Los parámetros de cada variante son los de EPSG (GN 7-2): LCC 1SP (latitud y longitud de origen, factor de escala, falso Este y Norte), LCC 2SP (latitud y longitud del origen falso, dos paralelos, Este y Norte en el origen falso), Mercator A (longitud de origen, factor de escala, falsos; la latitud de origen natural es el ecuador, como define EPSG) y B (longitud de origen, paralelo estándar, falsos), OM A y B (latitud y longitud del centro, azimut, ángulo de rectificación, factor de escala, y el falso Este y Norte en el origen natural o en el centro), Estereográfica oblicua (latitud y longitud de origen, factor de escala, falsos), Polar A (polo, longitud de origen, factor de escala, falsos), B (paralelo estándar, longitud de origen, falsos) y C (paralelo estándar, longitud de origen y Este y Norte en el origen falso, que es donde el paralelo estándar corta la longitud de origen).
- `falseEasting` y `falseNorthing` se escriben en la unidad `linearUnit` del SC y `ProjectionSetup` los pasa a metros con `LinearUnit.toMetres` antes de calcular (RF-16); `forward` devuelve metros y `inverse` los recibe en metros, como `TransverseMercator`.
- `primeMeridianDeg` es la longitud del meridiano de origen respecto a Greenwich, positiva al este (Madrid ≈ −3,687938°). Las longitudes que entran y salen de `MapProjection` son siempre de Greenwich; las del SC (longitud de origen, paralelos) se cuentan desde su meridiano de origen (RF-17).

## Algoritmos
Todos sobre un elipsoide cualquiera (a, 1/f), con la esfera como caso f = 0.
- **A1. Lambert (RF-1 a RF-3; GN 7-2 §3.3.1.1 y §3.3.1.2).** `m = cosφ/√(1−e²sin²φ)`, `t = tan(π/4−φ/2) / ((1−e sinφ)/(1+e sinφ))^(e/2)`; 2SP: `n = (ln m₁−ln m₂)/(ln t₁−ln t₂)` (con los dos paralelos iguales, `n = sin φ₁`, que es el 1SP); `F = m₁/(n t₁ⁿ)`, `r = aFtⁿ`, `θ = n(λ−λ₀)`; `E = E_F + r sinθ`, `N = N_F + r_F − r cosθ`. Inversa: `r′ = ±√((E−E_F)² + (r_F−(N−N_F))²)`, `t′ = (r′/aF)^(1/n)`, φ por iteración desde `t′` (Newton sobre la latitud conforme, 6 pasos como mucho) y `θ′ = atan2(E−E_F, r_F−(N−N_F))`. Factores: `k = r·n/(a·m)` y `γ = n(λ−λ₀)` con el signo de S008 (positivo al este del meridiano central). Sin imagen: φ = −n·90° (el polo opuesto) y entradas no finitas.
- **A2. Mercator (RF-4 a RF-6; §3.5.1).** A: `E = E_F + a k₀ (λ−λ₀)`, `N = N_F + a k₀ ln(tan(π/4+φ/2)·((1−e sinφ)/(1+e sinφ))^(e/2))`; B: lo mismo con `k₀ = cosφ₁/√(1−e²sin²φ₁)`. Inversa: `t = exp(−(N−N_F)/(a k₀))` y φ por la iteración de A1. Factores: `k = k₀·√(1−e²sin²φ)/cosφ`, `γ = 0`. Sin imagen: los polos.
- **A3. Oblique Mercator de Hotine (RF-7 a RF-9; §3.3.3).** Constantes `B, A, t₀, D, F, H, G, γ₀, λ₀` del centro de la proyección; `(u, v)` sobre la superficie rectificada y `E, N` por el ángulo de rectificación γ_c; la variante B da el origen en el centro `(u_c, 0)` y la A en el origen natural (`u = 0`); inversa por `Q′, S′, T′, V′, U′` y la latitud por la iteración de A1. Factores: `k` y `γ` derivados de la misma aplicación conforme (`k = √(…)` en `(u, v)` y `γ = γ_c − atan(…)`), y si la fórmula cerrada no alcanza ≤ 1·10⁻⁹ y ≤ 0,01″, por diferencias centrales de cuarto orden sobre `forward` (paso de 1 m en el terreno), que es el plan B. Sin imagen: puntos a 90° o más de la línea central.
- **A4. Estereográfica oblicua (RF-10 a RF-12; §3.3.2.2).** Esfera conforme de Gauss: `R = √(ρ₀ν₀)`, `n`, `c`, `S₁`, `S₂`, `w₁`, `w₂`, `χ₀`; `E = E_F + 2Rk₀ cosχ sinΛ/B`, `N = N_F + 2Rk₀ (sinχ cosχ₀ − cosχ sinχ₀ cosΛ)/B`; inversa por `g, h, i, j` y la longitud/latitud conformes, y la latitud geodésica por la iteración de A1. Factores: `k = 2Rk₀ / (ρ_… B)` derivado de `B` y la latitud conforme, `γ = atan2(…)` (la de la proyección sobre la esfera conforme, más la convergencia de la esfera). Sin imagen: la antípoda del origen (`B → 0`).
- **A5. Estereográfica polar (RF-13 a RF-15; §3.3.2.3).** `t` como en A1; A: `ρ = 2 a k₀ t / √((1+e)^(1+e)(1−e)^(1−e))`; B y C: `ρ = a m_F t / t_F`; polo norte `E = E_F + ρ sin(λ−λ₀)`, `N = N_F − ρ cos(λ−λ₀)`; polo sur `N = N_F + ρ cos(λ−λ₀)`. En C, `E_F` y `N_F` están en el origen falso: se pasan al polo con `ρ_F = a m_F t_F/t_F` y `N_F′ = N_F ∓ ρ_F`. Inversa: `ρ′ = √((E−E_F)²+(N−N_F)²)`, `t′ = ρ′ t_F/(a m_F)` (A: con su constante), φ por A1 y `λ = λ₀ + atan2(E−E_F, ∓(N−N_F))`. Factores: `k = ρ/(a m)` y `γ = λ−λ₀` con el signo del polo. Sin imagen: el polo opuesto.
- **A6. Meridiano de origen (RF-17).** `ProjectionSetup` envuelve el método en `PrimeMeridianProjection`: al proyectar `λ_SC = λ_Greenwich − pm` (normalizada a (−180°, 180°]) y al desproyectar `λ_Greenwich = λ_SC + pm`. Un punto que con el desplazamiento cruza el antimeridiano se normaliza antes de proyectar.
- **A7. Ida y vuelta y rechazo (RF-18, RF-19).** Newton con tolerancia 1·10⁻¹⁴ rad (≈ 6·10⁻⁸ m): la ida y vuelta queda muy por debajo de 0,1 mm. Un punto sin imagen devuelve `null` (nunca un valor extrapolado, ADR-5); también lo devuelve `inverse` si el resultado no es finito o cae fuera de ±90°.

## Contratos
- `ProjectionFactory.create(setup): ProjectionResult`: valida los parámetros del método (A1 a A5: paralelos opuestos o en un polo, paralelo de origen en el ecuador en LCC 2SP y 1SP, azimut o ángulo de rectificación fuera de rango, latitud de origen en un polo, polo equivocado, factor de escala no positivo, aplanamiento fuera de [0, 1)) y devuelve **todos** los problemas a la vez, cada uno con su parámetro y su causa; sin problemas, la proyección con el desplazamiento del meridiano ya aplicado.
- `ProjectionMethod.implemented` es la lista de RF-20 que consume el generador del catálogo (S034): 12 métodos, entre ellos TM y Web Mercator (que se calculan con las clases de siempre, sin que `ProjectionFactory` los cree: `create` devuelve `Invalid` con `UNSUPPORTED_METHOD` para ellos).
- Sin textos de usuario: S034 y S035 traducirán `ParameterProblem` a `strings.xml`.

## Decisiones técnicas
| # | Decisión | Motivo | Alternativa descartada |
|---|---|---|---|
| D1 | Cálculo propio en Kotlin puro, con las fórmulas de GN 7-2 y la latitud conforme de Karney (`tauFromConformal`) para la inversa | ADR-2, RNF-T14: sin librería de geodesia ni NDK; la misma técnica que `TransverseMercator` | proj4j (sin rejillas verticales y poco mantenida), PROJ por NDK (+10 MB por ABI) |
| D2 | Paquete `projection` nuevo y las clases existentes **sin tocar**; la latitud conforme se duplica en una función `internal` | RF-21: no cambiar ni un bit de UTM ni de Web Mercator; el coste es repetir ~15 líneas | Extraer `conformalTau` de `TransverseMercator` (más limpio, pero cambia un fichero con tests de S008 y el riesgo no compensa) |
| D3 | `forward`, `inverse` y `factors` devuelven `null` si el punto no tiene imagen | RF-19, ADR-5: un tipo que obliga al que llama a decidir; no se lanza ninguna excepción por un dato fuera de dominio | Excepciones (se olvidan y rompen la UI); devolver NaN (se propaga en silencio) |
| D4 | Los parámetros del SC son una clase de datos **por variante** y el método sale del tipo | Cada variante tiene sus propios parámetros (EPSG); imposible mezclar un paralelo de LCC 1SP con el de uno 2SP | Un mapa de parámetros por nombre (errores en tiempo de ejecución) |
| D5 | Ángulos en grados en la frontera y radianes por dentro | Es lo que hacen `TransverseMercator`, `GeoPoint` y los vectores; los formateadores de `core:model` ya convierten en la interfaz | Radianes en toda la API (obligaría a cambiar S008) |
| D6 | El falso Este y Norte llevan la unidad del SC y `ProjectionSetup` los pasa a metros; la proyección habla siempre en metros | RF-16, RNF-T9; igual que el resto de `core:geodesy` | Proyectar ya en pies (duplica la conversión en cada método) |
| D7 | El meridiano de origen se aplica en un envoltorio y las longitudes de `MapProjection` son de Greenwich | RF-17 en un único sitio, probado con todos los métodos | Un parámetro `primeMeridian` en cada método (diez sitios donde equivocarse) |
| D8 | Factores analíticos donde hay fórmula cerrada sencilla (LCC, Mercator, estereográficas) y, si la de OM no alcanza la tolerancia, diferencias centrales de 4.º orden sobre `forward` | Constitución nº 2: factor ≤ 1·10⁻⁹ y convergencia ≤ 0,01″; las diferencias con paso de 1 m dan ~1·10⁻¹⁰ sin deriva de la fórmula | Diferencias finitas en todos (más lento y con ruido cerca de los polos) |
| D9 | Los vectores salen de WKT2 construidos con las clases de EPSG y se contrastan con `Transformer`/`Proj.get_factors` | Cubre las nueve variantes con los mismos parámetros que EPSG (también el meridiano de origen, que PROJ resuelve en el `GEOGCRS`) | Cadenas `+proj=…` a mano (PROJ no tiene la variante C de la polar ni todas las unidades) |
| D10 | Casos de prueba reales de EPSG por método, además de los de rejilla propia: LCC 2SP EPSG:2154 (Lambert-93), 3034 y 32040 (Texas Sur Central, en `us-ft`); LCC 1SP EPSG:27571 y 27572 (NTF París); Mercator A EPSG:3002 y B EPSG:3388; OM A EPSG:29873 (Borneo) y B EPSG:26931 (Alaska 1); estereográfica oblicua EPSG:28992 (RD Nueva); polar A EPSG:5041 y 5042 (UPS), B EPSG:3031 y 3413 y C EPSG:2985 | Son los ejemplos de GN 7-2 y los SC que el catálogo ofrecerá; el generador comprueba el nombre del método de cada uno en la base de PROJ antes de usarlo | Solo parámetros inventados |
| D11 | Un CSV por familia con los factores dentro, generado por `proyecciones.py` y con la cabecera de versiones (`pyproj`, `PROJ`) | Reproducible (constitución nº 2); mismo formato que `utm_etrs89.csv` y `factores_tm.csv` | Un CSV por método y por magnitud (veinte ficheros) |

## Estrategia de tests
- **Unitarios JVM (`core/geodesy/src/test/kotlin/…/projection/`, JUnit 5 + kotest):**
  - Por método: ida frente a pyproj (≤ 1 mm), vuelta (≤ 1·10⁻⁸ °), factor (≤ 1·10⁻⁹) y convergencia (≤ 0,01″) con los vectores de `fixtures/geodesia/`; los casos límite de la spec (paralelos iguales, ecuador, azimut 0°/90°/180°, polo sur, esfera, antípodas).
  - Parámetros no válidos: cada causa de RF-3, RF-6, RF-9, RF-12 y RF-15 devuelve su `ParameterProblem`; varios fallos a la vez salen todos.
  - Transversal: ida y vuelta ≤ 0,1 mm en una rejilla por método (RF-18); puntos sin imagen devuelven `null` (RF-19); desplazamiento del meridiano de origen con Madrid, París y Lisboa en cada método y a través del antimeridiano (RF-17); falsos en `ft` y `us-ft` (RF-16); lista de métodos y `UNSUPPORTED_METHOD` (RF-20); UTM y Web Mercator con sus vectores de S008 y S009 sin cambio (RF-21).
  - Rendimiento: 100.000 puntos < 1 s por método (con el margen de siempre en los tests de tiempo).
- **Python (`tools/geodesia/test_vectores.py`):** cada fichero nuevo lleva la cabecera de versiones, tiene las columnas y el recuento de filas esperados, y cada caso EPSG usa el método que se espera.
- **Manual o de campo:** no aplica (sin interfaz). La demo es ejecutar los tests de referencia.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1, D4 | `LambertConicTest` |
| RF-2 | A1, D8 | `LambertConicFactorsTest` |
| RF-3 | A1, contratos | `LambertConicParametersTest` |
| RF-4 | A2 | `MercatorTest` |
| RF-5 | A2 | `MercatorFactorsTest` |
| RF-6 | A2, contratos | `MercatorParametersTest` |
| RF-7 | A3 | `ObliqueMercatorTest` |
| RF-8 | A3, D8 | `ObliqueMercatorFactorsTest` |
| RF-9 | A3, contratos | `ObliqueMercatorParametersTest` |
| RF-10 | A4 | `ObliqueStereographicTest` |
| RF-11 | A4 | `ObliqueStereographicFactorsTest` |
| RF-12 | A4, contratos | `ObliqueStereographicParametersTest` |
| RF-13 | A5 | `PolarStereographicTest` |
| RF-14 | A5 | `PolarStereographicFactorsTest` |
| RF-15 | A5, contratos | `PolarStereographicParametersTest` |
| RF-16 | D6 | `ProjectionUnitsTest` |
| RF-17 | A6, D7 | `PrimeMeridianProjectionTest`, `PrimeMeridianVectorsTest` |
| RF-18 | A7 | `ProjectionRoundTripTest` |
| RF-19 | A7, D3 | `ProjectionNoImageTest` |
| RF-20 | contratos | `ProjectionMethodTest` |
| RF-21 | D2 | `ExistingProjectionsUnchangedTest` |

## Riesgos
- **Nota GN 7-2:** el PDF no está en este equipo (`docs/referencias/geodesia/` está vacía y los PDF son solo locales). Las comprobaciones independientes son pyproj/PROJ sobre los **mismos CRS de EPSG** que usa la nota como ejemplos (D10) y el punto publicado de RD Nueva (53° N, 6° E → E 196105,283 m, N 557057,739 m), que se teclea en el test; si aportas la nota, se añaden sus demás valores publicados como `ejemplos_epsg_gn72.csv`.
- **PROJ y los factores:** `Proj.get_factors` puede no admitir alguna variante (OM A o polar C) desde un CRS; si falla, el generador usa la cadena `+proj=` equivalente para los factores de ese caso y lo anota en el CSV.
- **OM:** es la fórmula más larga de GN 7-2 (con casos de ángulo de rectificación y azimut particulares); por eso lleva tres tareas y un plan B para los factores (D8).
- **Rendimiento de los tests de tiempo:** bajo carga pueden fallar a la primera, como los de S025 a S027: se repiten con `--no-parallel` antes de investigar.

## Cambios en documentos anteriores
- Aplicados al aprobar la spec: `docs/hoja-de-ruta.md` (fila de S032), `docs/glosario.md` (cinco términos) y `specs/pendientes/README.md`.
- Al terminar: `docs/hoja-de-ruta.md` (S032 `implementada`) y `fixtures/LEEME.md` (fila de los siete CSV nuevos); `tools/LEEME.md` (nota del generador).
- `specs/033-transformaciones-datum` y `034`/`035` (en `pendientes/`): el meridiano de origen ya no es cosa de S033; se anota en sus borradores cuando se abran.

## Cambios al implementar
- **Vectores sin WKT2 (D9):** el generador usa la cadena `+proj=` equivalente de cada caso y, para los CRS de EPSG, el `Transformer` del propio CRS como referencia independiente con comprobación cruzada (≤ 1 µm) entre las dos. `Proj.get_factors` funcionó con todas las variantes (el plan B de los factores por cadena no hizo falta).
- **Dos rarezas de PROJ en la OM (T7, T8):** con el azimut de exactamente 90° PROJ ignora `+no_uoff` (da la A como la B), y en el meridiano exacto de la línea inicial con azimut 270° cambia de fórmula (`u = A·λ`) y da el signo opuesto. Los vectores evitan esos puntos (el caso de 90° va solo en B, `a-azimut-120` cubre la A, la columna central de la rejilla va a +0,001°) y Kotlin los comprueba aparte: la A a 90° sigue de forma continua a los azimuts vecinos y la B pone siempre su centro en el Este y Norte dados. Por eso `uc` se calcula como la `u` del propio centro (`atan2(G/cosγ₀, √(1 − (G·tanγ₀)²))`) y no con el caso aparte de 90° de GN 7-2.
- **Factores de OM por diferencias centrales (T9, D8):** sin fórmula cerrada; diferencia central de 4.º orden sobre `forward` con paso de 10⁻⁴ rad (no de 1 m), que da ≤ 1·10⁻⁹ y ≤ 0,01″ contra PROJ. Las demás tienen fórmula cerrada.
- **Polar C (T13):** PROJ no la tiene (EPSG:2985 no se puede transformar), así que su referencia es la B de PROJ con `y_0 = N_F − y(φ_F, λ₀)`; no hay `Transformer` de EPSG para ese caso.
- **Nombres (T11):** el objeto de cálculo se llama `DoubleStereographic` porque `ObliqueStereographic` es la clase de parámetros.
- **Validación (T12, T15):** la estereográfica oblicua rechaza también la latitud de origen en un polo (`AT_POLE`: u₀ sería infinito y ahí va la polar), más estricto que el RF-12 literal. Nuevo `ProblemKind.NOT_A_POLE` (origen de la polar A que no es un polo). En la polar B y C el polo sale del signo del paralelo, así que «hemisferio contrario» no puede darse: solo el ecuador (sin polo) se rechaza (`AT_EQUATOR`); `WRONG_HEMISPHERE` queda sin uso. El azimut y el ángulo de rectificación de la OM valen de −360° a 360°.
- **Polos y antípodas:** la vuelta de OM, oblicua y polar devuelve el polo (±90°) con la longitud de origen si el punto cae en él (tiene imagen, la longitud no está definida); la ida de OM da `null` a 90° de la línea central, la oblicua en su antípoda y la polar en el polo opuesto. Los factores no dan valor en los polos (la convergencia no está definida).
- **Lista y fábrica (T15, T20):** con los diez métodos el `when` de `ProjectionFactory` es exhaustivo, así que `UNSUPPORTED_METHOD` ya no se devuelve (queda en el enum); la lista publicada son los doce métodos y la Transversa de Mercator y Web Mercator siguen con sus clases de S008 y S009, sin tocarlas. `tasks.md` decía «11 métodos» donde eran doce.
- **Ficheros de vectores:** `meridiano_origen.csv` y `unidades_proyeccion.csv` usan un formato genérico (método de TopoPhone y `p1`…`p7` en el orden de los parámetros) y los lee un único `PrimeMeridianVector`; EPSG:29873 está en metros (su variante en pies usa el pie de Sears, que `LinearUnit` no tiene). Los vectores polares llevan longitudes en (−180°, 180°] y sin puntos justo en ±180° (ruido de redondeo en la vuelta).
- **No se tocó:** `TransverseMercator.kt` y `WebMercator.kt` (RF-21), ni ninguna dependencia.

## Validación
- `uv run --project tools tools/trazabilidad/rf_report.py`: **S032: 21 RF, 21 con test, 0 pendientes**.
- `./gradlew check` en verde (ktlint, Lint, reglas de módulos, tests y Kover; los tests de tiempo y de ViewModel intermitentes de `:app` se repiten con `--no-parallel` hasta que pasan, y un proceso `python -` mío olvidado, que ocupaba una CPU, los hacía fallar más); `uv run --project tools pytest tools` en verde; `forbidden_files.py` sin hallazgos; `assembleDebug` compila.
- Tests nuevos: 159 en `core/geodesy` (paquete `projection`, 23 clases), más los de `tools/geodesia/test_proyecciones.py`. Siete CSV nuevos con 2.201 vectores (≈ 400 KB) en `fixtures/geodesia/`.
- Precisión contra pyproj/PROJ (9.8.1): ida ≤ 1 mm, vuelta ≤ 1·10⁻⁸ °, factor de escala ≤ 1·10⁻⁹ y convergencia ≤ 0,01″ en los diez métodos; ida y vuelta ≤ 0,1 mm; 100.000 puntos en menos de 1 s por método (medido: 0,2 a 0,5 s con los diez a la vez).
- **Lo que no se pudo contrastar con la nota GN 7-2:** el PDF no está en este equipo; las comprobaciones independientes son pyproj/PROJ sobre los CRS de EPSG que usa la nota y el punto publicado de RD Nueva (53° N, 6° E → 196 105,283 / 557 057,739 m, a menos de 2 mm). La polar C (EPSG:2985) no tiene referencia en PROJ y se contrasta con la B de PROJ desplazada al origen falso. Los dos casos que PROJ resuelve distinto de la definición (OM con azimut de 90° en A y de 270° en el meridiano de la línea inicial) se comprueban con propiedades de la definición, no con PROJ.
- Sin demo visible, como dice la spec (es un motor de cálculo): la pantalla que lo use llega con S034 y S035.
