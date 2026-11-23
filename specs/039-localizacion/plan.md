# Plan técnico — Spec 039

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (orden de cálculo: bruto → punta del jalón → shift → SC → localización), `specs/039-localizacion/spec.md`, y los planes de S020 (el patrón: vector activo, coordenadas del punto derivadas, `ActiveBaseShift`, copia y paquete), S035 (historial del SC, «Mis SC»), S058 (exclusión con terreno, `TerrainView`) y S012/S053/S055 (exportaciones).

Sin dependencias nuevas, ni Gradle ni Python. La referencia independiente (`tools/localizacion/`) usa `fractions` y `pyproj`, que ya está en `tools`. Alternativa descartada: una librería de álgebra lineal (el ajuste es una división de sumas y un sistema 3×3).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde se guardan las localizaciones (RF-13, RF-21, RF-26) | **Room v40** (`AutoMigration(39, 40)`): tablas `localizations` (una fila por localización, con `status` BORRADOR, ACTIVA o ARCHIVADA, y sus parámetros) y `localization_pairs`, y la columna `localization_id` en `points`. Entran solas en copias, paquetes y papelera (RNF-T6) | DataStore: no entra en las copias ni en los paquetes del proyecto |
| D2 | Qué coordenadas lleva un punto GNSS localizado (RF-17, RF-18) | Como S020: las coordenadas del punto (`easting`, `northing`, `height`) son las **localizadas**, que es lo que se ve, se replantea y se exporta; la medida (`measured_*`) sigue en el **SC sin localizar**, que es lo que enseña la ficha. Aplicar o quitar reescribe las columnas del punto en una transacción (RNF-T6) | Guardar el SC y localizar al leer: cada pantalla y cada exportación tendrían que conocer la localización (el recorrido de S058) |
| D3 | Puntos GNSS con coordenadas editadas (RF-17) | Un punto con `edited = true` **no se transforma**: sus coordenadas las escribió el usuario en el sistema de la obra, como las de un punto manual (RF-20). La ficha lo indica | Transformarlos: cambiaría coordenadas que el usuario fijó a mano |
| D4 | Dónde se aplica en vivo (RF-17) | Después de `ProjectPositionCalculator.fromGround`, sobre Este, Norte y altura del SC: `LocalizationApplier.apply(position, params)`. Los sitios son los mismos que recibieron el vector de S020 (`PointMeasurement`, `PointCorrections`, `LiveTip`, recolector, `AutoCollector`, los cuatro replanteos, el análisis de ejes, el mapa y el panel) | Meterlo dentro de `fromGround`: lo usan también los puntos manuales y las importaciones, que no se transforman (RF-20) |
| D5 | Parametrización de la transformación (RF-7, RF-10) | Respecto al centroide de los puntos GNSS en el SC: `[E′ N′] = C′ + s·R(θ)·([E N] − C)`; en vertical, `h′ = h + dh + px·(E − Ec) + py·(N − Nc)`. Con coordenadas centradas el ajuste está bien condicionado aunque Este y Norte valgan millones de metros. La traslación que se enseña es la del centroide | Parámetros respecto al origen del SC: pierde precisión numérica y el giro y la traslación se mezclan |
| D6 | Borrador y localización activa (RF-16) | Un solo borrador por proyecto (`status = DRAFT`); «Aplicar» lo copia a una fila `ACTIVE` y pasa la activa anterior a `ARCHIVED` en la misma transacción. «Quitar» pasa la activa a `ARCHIVED`; «Reactivar» copia una archivada al borrador y exige aplicarlo (con la confirmación de RF-14) | Reactivar directamente: saltaría la confirmación y el recuento de puntos |
| D7 | Cambiar el SC o las alturas con una localización activa (RF-27) | No se permite: el formulario del proyecto marca el campo y dice que hay que quitar antes la localización. Los pares están calculados con las coordenadas del SC de entonces | Recalcular la localización al cambiar de SC: no hay garantía de que los pares sigan siendo válidos |
| D8 | SC guardado con localización (RF-24, RF-28) | Tabla `localized_crs` en «Mis SC» (nombre único, el SC base como texto de `ProjectCrsText`, el sistema de alturas y los parámetros), listada junto a los SC propios con la marca «Localizado». Al elegirla en un proyecto, el SC del proyecto pasa a ser el base y se crea una localización `ACTIVE` sin pares con origen `SAVED_CRS` y el nombre del SC. **No entra en el JSON de importar y exportar de S035** en esta spec | Añadir un campo `localization` a `CustomCrs`: obliga a representar un SC de España como definición personalizada |
| D9 | Exportaciones con localización activa (RF-23; S053 y S055) | CSV: columna `Localization id` al final de «Todas las columnas» (y en el GeoPackage de S053, que repite esas columnas) y opción «Exportar sin localizar» (usa `measured_*` de los puntos GNSS no editados). Shapefile y GeoPackage no ofrecen «sistema del proyecto» con una localización activa: solo geográficas (sin SC declarado erróneo). DXF y KML no cambian | Seguir declarando el SC del proyecto: sus coordenadas dejan de corresponder al EPSG |
| D10 | Terreno (RF-22) | La pantalla de localización no deja aplicar con el proyecto en terreno y el formulario del proyecto no deja pasar a terreno con una localización activa; los dos con el mismo texto | Dejar que `TerrainView` y la localización convivan |

## Estructura de módulos
- `core:model` → `LocalizationParams` (centroides, giro, escala, ppm, desplazamiento vertical, pendientes, escala fija), `LocalizationPair`, `LocalizationStatus`, `LocalizationLimits` (50 pares, 3 cm, 0,001) y `Point.localizationId` (RF-4, RF-13, RF-19).
- `core:survey` → `LocalizationSolver` (ajuste horizontal y vertical), `LocalizationResiduals` (residuos, RMS, mayor residuo y avisos), `LocalizationApplier` (localizar y deslocalizar), `LocalizationDraftRules` (admisión de pares, avisos de calidad) (RF-3 a RF-13, RF-17, RF-20). `PointMeasurement`, `LiveTip` y `PointCorrections` reciben los parámetros (D4).
- `core:formats` → columna `LOCALIZATION` del CSV y del informe (`LocalizationReportCsv`) (RF-23, RF-25).
- `data` → Room v40, `LocalizationRepository` (borrador, aplicar, quitar, reactivar, historial), `ActiveLocalization` (`StateFlow` y lectura instantánea), `LocalizedCrsRepository` («Mis SC»), copia y paquete (RF-13 a RF-16, RF-19, RF-21, RF-24, RF-26, RF-28).
- `app` → `ui/localization/` (`LocalizationViewModel`, `LocalizationScreen`, `LocalizationRoute`, `LocalizationBanner`), entrada en el menú del proyecto, ficha del punto, exportaciones y formulario del proyecto (RF-1, RF-2, RF-5, RF-6, RF-10 a RF-16, RF-17, RF-22, RF-23, RF-24, RF-27, RF-28).
- `tools/localizacion` → `generar_localizacion.py` y `fixtures/localizacion/` (constitución nº 2).

## Modelo de datos
### Room v40
```
localizations        id TEXT PK, project_id TEXT FK(projects, CASCADE), status TEXT, origin TEXT (PAIRS | SAVED_CRS), name TEXT NULL,
                     created_at INTEGER, applied_at INTEGER NULL, fix_scale INTEGER, residual_limit_m REAL, scale_limit REAL,
                     crs_text TEXT,            -- el SC del proyecto al calcularla (ProjectCrsText) y su sistema de alturas
                     height_system TEXT,
                     c_e REAL, c_n REAL, c_e2 REAL, c_n2 REAL,           -- centroide en el SC y centroide conocido
                     rotation_rad REAL, scale REAL, has_horizontal INTEGER,
                     dh REAL, slope_e REAL, slope_n REAL, h_e REAL, h_n REAL, vertical_kind TEXT, -- NONE | OFFSET | PLANE
                     rms_h REAL NULL, rms_v REAL NULL
localization_pairs   id TEXT PK, localization_id TEXT FK(localizations, CASCADE), position INTEGER,
                     point_id TEXT NULL, point_name TEXT,       -- copiado: el punto puede borrarse
                     sc_e REAL, sc_n REAL, sc_h REAL NULL,      -- la posición del punto GNSS en el SC sin localizar al calcular
                     known_e REAL, known_n REAL, known_h REAL NULL,
                     use_h INTEGER, use_v INTEGER, confirmed INTEGER,
                     res_e REAL NULL, res_n REAL NULL, res_v REAL NULL
points             + localization_id TEXT NULL
localized_crs        id TEXT PK, name TEXT UNIQUE NOCASE, crs_text TEXT, height_system TEXT, params (las mismas columnas de parámetros), created_at INTEGER
```
- `AutoMigration(39, 40)` sin especificación: dos tablas nuevas, una columna anulable y la tabla de «Mis SC» localizados; los proyectos y los puntos existentes quedan «Sin localización» (RNF-T6). Esquema 40 en `data/schemas/`.
- Un índice por `project_id`. Un solo `status = ACTIVE` y un solo `DRAFT` por proyecto lo garantiza la transacción; el test lo comprueba.
- `points.localization_id` no es clave foránea: borrar una localización no toca el punto.
- Todo en `Double`, sin redondear.

## Algoritmos
### A1. El ajuste horizontal (RF-7, RF-8, RF-12)
Con los `n` pares de componente horizontal activa, `(x, y)` = posición en el SC, `(x′, y′)` = conocida, y los centroides `C` y `C′`; `u = x − Cx`, `v = y − Cy`, `u′ = x′ − C′x`, `v′ = y′ − C′y`.
- `n = 1`: traslación `C′ − C`, `s = 1`, `θ = 0`.
- `n ≥ 2`, semejanza: `a = Σ(u·u′ + v·v′) / Σ(u² + v²)`, `b = Σ(u·v′ − v·u′) / Σ(u² + v²)`; `s = hypot(a, b)`, `θ = atan2(b, a)`.
- `n ≥ 2`, escala fija: `θ = atan2(Σ(u·v′ − v·u′), Σ(u·u′ + v·v′))`, `s = 1`.
- Degenerado si `Σ(u² + v²) < 1e-9 m²` con `n ≥ 2` (RF-12).
- Residuo de un par: `(x′, y′) − transformado(x, y)`; RMS horizontal = `sqrt(Σ(rE² + rN²) / n)`.

### A2. El ajuste vertical (RF-9)
Con los `m` pares de componente vertical activa y los centroides `Ec, Nc, hc` y `Ec′, Nc′, hc′`: con 1 o 2 pares, `dh` = media de las diferencias `h′ − h`; con `m ≥ 3`, el plano `Δh = dh + px·(E − Ec) + py·(N − Nc)` por mínimos cuadrados con las ecuaciones normales 3×3 sobre coordenadas centradas. Si el determinante normalizado es menor que `1e-9` (puntos alineados), se usa el desplazamiento y se avisa. La pendiente se da en ppm (`1e6·hypot(px, py)`).

### A3. Localizar y deslocalizar (RF-17, RF-18; D4, D5)
- `localize(E, N, h)`: horizontal `C′ + s·R(θ)·([E N] − C)`; vertical `h + dh + px·(E − Ec) + py·(N − Nc)` con los centroides `E/N` de la parte vertical.
- `delocalize(E′, N′, h′)`: horizontal con `s⁻¹·R(−θ)`; el Este y el Norte del SC que salen se usan en el plano vertical para quitar `Δh`.
- Ida y vuelta ≤ 0,1 mm; con `n = 1` es una traslación y ambas son exactas.
- Con `h = null` (punto sin altura) la altura sigue sin existir.

### A4. Aplicar, quitar, reactivar (RF-14 a RF-16, RF-21; D2, D3, D6)
En una transacción (`LocalizationRepository.apply`): la fila `DRAFT` pasa a `ACTIVE` (con `applied_at` y los residuos de cada par), la anterior `ACTIVE` a `ARCHIVED`, y se reescriben `easting`, `northing`, `height` y `localization_id` de cada punto GNSS no editado del proyecto con `localize(measured_*)`. «Quitar»: la `ACTIVE` a `ARCHIVED` y los mismos puntos vuelven a `measured_*` con `localization_id = NULL`. Los puntos manuales, importados, calculados y editados no se tocan (RF-20, D3). El recuento que enseña la confirmación es el de esos puntos.

### A5. Guardar un punto y recalcular el jalón (RF-19; D4)
`PointMeasurement.finish` calcula el punto como siempre, lo localiza con `ActiveLocalization` (que lee al empezar la medida, como el base shift) y guarda `localization_id`; `measured_*` queda sin localizar. `PointCorrections.withPoleHeight` y «Volver a la medida» localizan con la localización activa **en ese momento** (a diferencia del vector de S020, que es del punto: aquí todos los puntos siguen a la activa, RF-17).

### A6. Admisión de pares y calidad (RF-2, RF-3, RF-4, RF-6)
`LocalizationDraftRules.canAdd(draft, point)`: el punto tiene `origin.hasMeasurement`, no está ya en el borrador y hay menos de 50 pares. Si la solución del punto no fue FIX (S047) o su precisión supera la del control de precisión del proyecto, el par queda con `confirmed = false` y no entra en el cálculo hasta que el usuario confirme.

### A7. Avisos y umbrales (RF-13, RF-15)
Residuo horizontal = `hypot(rE, rN)`; vertical = `|rV|`. Se destaca el residuo `> residual_limit_m` y la escala con `|s − 1| > scale_limit`. Si hay alguno, «Aplicar» pide una segunda confirmación que los nombra.

### A8. Informe y guardar como SC (RF-24, RF-25, RF-28)
- Informe: CSV con una sección de parámetros (traslación del centroide, giro, escala y ppm, pendiente, RMS) y una fila por par (nombre, posición en el SC, conocida, componentes, residuos). Números con `Locale.ROOT`; sin fecha de la interfaz.
- Guardar como SC: copia los parámetros y el SC base a `localized_crs` con el nombre elegido (único sin distinguir mayúsculas). Elegirlo en otro proyecto aplica D8 y respeta RF-22 y RF-27.

## Contratos
- `LocalizationSolver.solve(pairs, fixScale): LocalizationSolution` (parámetros, residuos por par, RMS, mayor residuo, avisos, o el motivo `NoPairs` / `Degenerate`).
- `LocalizationApplier.localize/delocalize(position, params)`.
- `LocalizationRepository.draft/addPair/removePair/setComponents/setFixScale/setLimits/apply/remove/reactivate/history(projectId)` y `ActiveLocalization.current(projectId): LocalizationParams?`.
- `LocalizedCrsRepository.save/list/delete/apply(projectId, id)`.
- Rechazos del borrador (`PairRejection`): `NO_MEASUREMENT`, `ALREADY_IN_DRAFT`, `LIMIT_REACHED`.

### Textos nuevos (`strings.xml`, es y en)
Localización, el estado, los pares, añadir par, las coordenadas conocidas, las componentes, «Fijar escala = 1», la traslación, el giro, la escala, los ppm, los residuos, los RMS, los umbrales y sus avisos, «Aplicar al proyecto» y sus dos confirmaciones, «Quitar», «Reactivar», el historial, «Localizado», «Guardar como SC», «Informe», los motivos de rechazo, el aviso de terreno y el de cambio de SC, la marca «Localizado» de «Mis SC» y las frases de TalkBack. Sin apóstrofos sin escapar en inglés.

### Accesibilidad (RNF-T8)
Campos y botones de 56 dp; los residuos altos y la escala fuera de umbral con texto además de color; TalkBack lee el resumen al calcular.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Ajuste por fórmulas cerradas sobre coordenadas centradas | Sin dependencias, exacto y estable con UTM; el plano vertical es un 3×3 | Resolver las ecuaciones normales completas sin centrar |
| Coordenadas del punto localizadas, SC aparte | Todo lo que lee el punto ve lo que se replantea y exporta (patrón de S020) | Localizar al leer |
| Los puntos editados no se transforman | Son del sistema de la obra, como los manuales | Transformarlos |
| Borrador separado de la activa | No se mueven coordenadas por tocar un par (entrevista) | Recalcular la activa en vivo |
| «Mis SC» localizados en su propia tabla | No obliga a convertir un SC de España en definición personalizada | Ampliar `CustomCrs` |

## Estrategia de tests
- **`tools`:** `generar_localizacion.py` y su test: pares sintéticos con una transformación conocida, con y sin ruido, con y sin escala fija y con plano vertical; ajuste por las ecuaciones normales en `fractions`; posiciones obtenidas con `pyproj` desde ETRS89 a UTM 30N y a Lambert-93.
- **JVM `core:model`:** `LocalizationParamsTest`.
- **JVM `core:survey`:** `LocalizationSolverTest` (traslación, semejanza, rígido, 1 y 2 pares verticales, plano, alineados, degenerados), `LocalizationVectorsTest` frente a `fixtures/localizacion/` (parámetros y residuos a 0,1 mm y 1e-9, sin ruido residuos < 0,1 mm), `LocalizationApplierTest` (ida y vuelta ≤ 0,1 mm), `LocalizationDraftRulesTest`, `LocalizationPerformanceTest` (50 pares en menos de 100 ms), `PointMeasurementLocalizationTest`, `PointCorrectionsLocalizationTest`.
- **JVM `core:formats`:** `CsvExportTest` ampliado (columna, sin localizar) y `LocalizationReportCsvTest`.
- **`data`** (nombres de 60 caracteres o menos): `TopoPhoneDatabaseTest` 39 → 40 con un proyecto con puntos; `LocalizationRepositoryTest` (un borrador, una activa, aplicar, quitar, reactivar, puntos manuales y editados intactos, transacción); `LocalizationBackupTest`; `LocalizedCrsRepositoryTest`.
- **Robolectric (`app`):** `LocalizationViewModelTest`, `LocalizationScreenTest` (pares, componentes, residuos, umbrales, confirmaciones), `CollectorLocalizationTest`, `StakeoutLocalizationTest`, `PointDetailsLocalizationTest`, `LocalizationTerrainTest` (RF-22), `LocalizationCrsLockTest` (RF-27), `LocalizedCrsFlowTest` (RF-24, RF-28), exportaciones.
- **Reproducción sintética:** `rs3-localizacion`: medir cuatro puntos, formar pares con una transformación conocida, aplicar, medir otro y comprobar su posición localizada con el resultado calculado aparte.
- **Manual o de campo:** demo en el emulador (criterios de finalización); en campo, medir puntos de control reales, que hace el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A4, pantalla | `LocalizationScreenTest` |
| RF-2 | A6 | `LocalizationViewModelTest`, `LocalizationScreenTest` |
| RF-3, RF-4 | A6 | `LocalizationDraftRulesTest` |
| RF-5 | A1, A2 | `LocalizationViewModelTest`, `LocalizationSolverTest` |
| RF-6 | A6 | `LocalizationDraftRulesTest`, `LocalizationViewModelTest` |
| RF-7, RF-8 | A1 | `LocalizationSolverTest`, `LocalizationVectorsTest` |
| RF-9 | A2 | `LocalizationSolverTest`, `LocalizationVectorsTest` |
| RF-10 | A1, A2, pantalla | `LocalizationScreenTest` |
| RF-11 | A1, A2 | `LocalizationViewModelTest`, `LocalizationPerformanceTest` |
| RF-12 | A1, A2 | `LocalizationSolverTest` |
| RF-13, RF-15 | A7 | `LocalizationSolverTest`, `LocalizationScreenTest` |
| RF-14 | A4 | `LocalizationRepositoryTest`, `LocalizationScreenTest` |
| RF-16 | D6, A4 | `LocalizationRepositoryTest`, `LocalizationViewModelTest` |
| RF-17 | A3, A4, A5, D4 | `PointMeasurementLocalizationTest`, `CollectorLocalizationTest`, `StakeoutLocalizationTest`, `LocalizationRepositoryTest` |
| RF-18 | D2 | `PointDetailsLocalizationTest`, `LocalizationRepositoryTest` |
| RF-19 | A5 | `PointMeasurementLocalizationTest`, `LocalizationRepositoryTest` |
| RF-20 | A4, D3 | `LocalizationRepositoryTest` |
| RF-21 | A4 | `LocalizationRepositoryTest`, `LocalizationScreenTest` |
| RF-22 | D10 | `LocalizationTerrainTest` |
| RF-23 | D9 | `CsvExportTest`, exportaciones |
| RF-24, RF-28 | A8, D8 | `LocalizedCrsRepositoryTest`, `LocalizedCrsFlowTest` |
| RF-25 | A8 | `LocalizationReportCsvTest` |
| RF-26 | copia, paquete, log | `LocalizationBackupTest`, `LocalizationRepositoryTest` |
| RF-27 | D7 | `LocalizationCrsLockTest` |

## Pendiente para otras specs
- Los SC de «Mis SC» localizados no entran en el JSON de importar y exportar de S035 (D8).
- Dibujos, ejes y superficies no se transforman (fuera de alcance de la spec).

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S039 pasa a «spec»; depende además de S058.
- `docs/glosario.md`: «Par de localización» (`LocalizationPair`), «Localización activa», «Localizado».
