# Plan técnico — Spec 026

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/026-informacion-geometrica/spec.md`, `specs/025-lineas-y-poligonos/plan.md` (`ProjectLine`, `LineChainage`, `LineRepository`, ficha de la línea), `specs/008-*/plan.md` (factor combinado), `specs/040-*/plan.md` y `specs/041-*/plan.md` (líneas de un dibujo, cota, plano local) y `specs/011-*/plan.md` (lista de objetos).

Sin dependencias nuevas, ni Gradle ni Python: `pyproj` ya está en `tools/`. Sin cambios en Room: se queda en la **v30** y la siguiente spec que lo toque (S017b) sigue en la v31.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde vive el cálculo (RF-1 a RF-6, RF-10, RF-11, RF-14, RF-15) | Kotlin puro en `core:survey` (paquete `geometry/`), que ya puede usar `core:geodesy`. Recibe vértices con Este, Norte, altura opcional y posición geodésica opcional; no sabe de líneas, puntos ni dibujos. Los adaptadores de la app construyen esa entrada | Calcular en `core:model` (no puede usar geodesia) o en cada pantalla |
| D2 | Distancia horizontal de cada segmento (RF-2, RF-10) | En cuadrícula: `hypot(ΔE, ΔN)`. En terreno: esa distancia dividida por el factor combinado del centro del segmento. El centro es el punto medio de las posiciones geodésicas de los dos extremos, con la media de sus alturas elipsoidales. Se obtiene con `ProjectPositionCalculator.fromGround` (S008) | Proyectar el punto medio de cuadrícula al SC inverso (más llamadas para un cambio de décimas de micra) |
| D3 | Longitud 3D, desnivel y pendiente (RF-2, RF-3) | Desnivel = diferencia de la altura del proyecto de los extremos; longitud 3D del segmento = `hypot(distancia horizontal elegida, desnivel)`; pendiente = `desnivel / distancia horizontal elegida × 100`. Con distancia horizontal 0, pendiente «—». Sin altura en un extremo: ese segmento no lleva ni 3D, ni desnivel, ni pendiente y la longitud 3D total no se muestra | Pendiente sobre la distancia inclinada |
| D4 | Perímetro y área (RF-1) | Perímetro = longitud 2D de todos los segmentos con el de cierre. Área = valor absoluto de la fórmula de Gauss (sumatorio de Este·Norte) sobre las coordenadas de cuadrícula; en terreno, dividida por el cuadrado del factor combinado del centro del polígono (media de las posiciones de sus vértices). Con cruces es el área con signo (RF-6) | Un factor por segmento (un área no se reparte por segmentos) |
| D5 | Detectar cruces (RF-6) | Barrido por Este: los segmentos se ordenan por su Este mínimo y solo se comparan los que se solapan en Este y en Norte; los vecinos que comparten un vértice no cuentan. Rápido en trazados de campo; peor caso cuadrático, que aquí no se da con 100.000 vértices (D12) | Comparar todos con todos (cuadrático) o el algoritmo de Shamos-Hoey (mucho código para el mismo resultado) |
| D6 | SC geográfico (RF-11) | Las posiciones se pasan a un plano local con `LocalTangentPlane.between` tomando el primer vértice como origen (el mismo plano local de S041/RF-15), y se avisa en pantalla. En ese plano no hay factor combinado: en «Terreno» se usa 1, se muestra la cifra de cuadrícula y se avisa de que no hay factor | Pedir un SC proyectado |
| D7 | SC proyectado sin factor combinado (RF-10) | Si `combinedFactor` es nulo (sin altura elipsoidal o fuera del área del SC), se usa la altura del punto central del proyecto (S006); si tampoco hay, el factor de escala sin corrección de altura, y se avisa «factor sin altura» en la información. Nunca se deja el modo «Terreno» sin cifra | Rechazar el modo «Terreno» |
| D8 | Elección Cuadrícula/Terreno (RF-9) | Preferencia global en DataStore, patrón de `ExportPreferences`: `GeometryPreferences` con `distanceMode: DistanceMode { GRID, GROUND }`, por defecto `GRID`. Sin Room ni copia de seguridad: es una preferencia de la app, no un dato del proyecto | Campo en el proyecto (Room v31) |
| D9 | Unidades (RF-12) | Nuevos `AreaFormat` (m², ft², usft²) y `SlopeFormat` (% con 2 decimales) en `core:model/format`; la longitud y el azimut usan `LengthFormat` y `AngleFormat` ya existentes. La unidad de área sale de la lineal del proyecto | Mostrar el área siempre en m² |
| D10 | Qué ve cada origen (RF-1, RF-4, RF-5) | Un único `GeometryInfoScreen` con tres orígenes: la línea del proyecto (se abre con un botón «Información» en su ficha, a la que se llega desde la lista de objetos o tocando la línea en el mapa), el ángulo y la línea de un dibujo (botón «Información» en la hoja del elemento). La línea del proyecto lee `vertexPoints` ampliado y se recalcula con cada cambio (RF-5) | Una pantalla por origen |
| D11 | Lectura de los vértices (RF-3, RF-5) | `VertexPoint` gana `height` (altura del proyecto), `latitudeDeg`, `longitudeDeg` y `ellipsoidalHeightM`, leídos con el mismo `JOIN` que ya usa la ficha (sin cargar todos los puntos). Es una ampliación de la interfaz de S025, sin cambios de esquema | Una consulta de puntos aparte (carga todo el proyecto) |
| D12 | Rendimiento (RNF-T13) | Todo el cálculo en `Dispatchers.Default`; la tabla es una lista perezosa con una fila por segmento. Medida: 100.000 vértices en menos de 1 s, en cuadrícula y en terreno. Si el terreno no llega, se reutiliza el factor de un segmento para los siguientes cuyo centro esté a menos de 10 m (el error es inferior a 10⁻⁷ m, dentro del milímetro) y se anota en «Cambios al implementar» | Calcularlo todo en el hilo principal |
| D13 | Ángulo entre tres puntos (RF-13 a RF-15) | `ThreePointAngle.of(a, b, c, distance)` con `atan2(|producto vectorial|, producto escalar)` en [0, π] (estable cerca de 0 y de π) y los azimuts y distancias de BA y BC como los de un segmento (D2). Los tres puntos se eligen en la lista del proyecto; desde la ficha de una línea la lista se limita a sus vértices | Ley del coseno, inestable con ángulos cercanos a 0° y 180° |
| D14 | CSV (RF-8) | `GeometryCsv` en `core:formats/csv`: cabecera con proyecto, línea, distancia activa, unidades de longitud, de área y del azimut; una fila por segmento; fila final de totales. Se escribe con `Locale.ROOT`, UTF-8 y el flujo de exportación de `ProfileExport` (CreateDocument o FileProvider) | Un formato nuevo de exportación |
| D15 | Referencia independiente (constitución nº 2) | Un script de `tools/` (`tools/geometria/generar_referencias.py`) con `pyproj` genera los ficheros de `fixtures/geometria/`: líneas rectas, un polígono cóncavo y uno con cruces, en cuadrícula y en terreno, con el factor combinado calculado en Python con la fórmula de la spec de S008. Los tests de Kotlin los leen y exigen 1 mm, 1 mm² y 1 µrad. Un test de `pytest` comprueba el propio script | Valores calculados a mano y copiados en los tests |
| D16 | Cambios en S006, S010, S011, S013, S018, S053 y en `spec.md` anteriores | No se tocan. La lista de objetos y la ficha de la línea (S011, S025) ganan entradas de menú; se citan en los tests como `S026/RF-n`. Sí se actualizan el glosario y la hoja de ruta | — |

## Estructura de módulos
- `core:model`
  - `format/`: `AreaFormat`, `SlopeFormat` (D9). `geometry/DistanceMode` (`GRID`, `GROUND`).
- `core:survey` (`geometry/`)
  - `GeometryVertex(easting?, northing?, height?, latitudeDeg?, longitudeDeg?, ellipsoidalHeightM?)`.
  - `PlanarFrame`: coordenadas planas de los vértices; las de cuadrícula tal cual o, con SC geográfico, el plano local (D6).
  - `SegmentScale`: factor combinado de un segmento o del polígono según D2, D4, D6 y D7; 1 en cuadrícula.
  - `GeometryCalculator.of(vertices, closed, mode, scale): GeometryInfo` con `GeometryInfo(length2D, length3D?, perimeter?, area?, vertexCount, segmentCount, segments: List<SegmentInfo>, crosses, missingHeight: List<Int>, notices)`; `SegmentInfo(index, from, to, chainage, length2D, length3D?, azimuth, deltaHeight?, slopePercent?)`.
  - `CrossingFinder` (D5).
  - `ThreePointAngle` (D13).
- `core:formats`: `csv/GeometryCsv` (D14).
- `tools`: `geometria/generar_referencias.py` y sus tests (D15).
- `data`
  - `preferences/GeometryPreferences` (+ `DataStoreGeometryPreferences`, binding en `DataModule`) (D8).
  - `line/LineRepository.vertexPoints` ampliado (D11).
- `app`
  - `geometry/`: `GeometryInfoViewModel`, `GeometryInfoScreen` (totales, selector Cuadrícula/Terreno, avisos, tabla de segmentos, exportar), `GeometryInput` (adaptadores desde `VertexPoint` y desde `CadEntity`), `ThreePointAngleViewModel` y `ThreePointAngleScreen`.
  - `lines/LineDetailsScreen`: botones «Información» y «Ángulo».
  - `points/ObjectListScreen`: entrada «Ángulo entre tres puntos» en el menú superior.
  - `cad/DesignElementSheet`: botón «Información» para las líneas de un dibujo.
  - Navegación y textos (`strings.xml` en español e inglés, prefijo `geometry_*`).

## Modelo de datos
- Room: sin cambios (v30).
- DataStore (global): `geometry_distance_mode` = `GRID` | `GROUND`.
- CSV (D14), una fila de cabecera por dato y una línea de columnas: `n; vértice_inicial; vértice_final; pk; longitud_2d; longitud_3d; azimut; desnivel; pendiente_%`, y al final `total; ; ; ; longitud_2d; longitud_3d; ; ; ` más, si es polígono, `perímetro` y `área`.

## Algoritmos
### A1. Plano y segmentos (D2, D3, D6)
1. `PlanarFrame` toma las coordenadas planas: Este y Norte si existen; si no, ENU respecto al primer vértice.
2. Para cada segmento `i` (con el de cierre en un polígono): `d = hypot(ΔE, ΔN)`; `f = SegmentScale.segment(i)`; `d2D = d / f`.
3. Azimut = `atan2(ΔE, ΔN)` en [0, 2π), sobre las coordenadas planas (de cuadrícula, no se divide por f).
4. Si los dos extremos tienen altura: `dH = h₂ − h₁`, `d3D = hypot(d2D, dH)`, `pendiente = dH / d2D × 100`, o ausente si `d2D = 0`.
5. PK del inicio de cada segmento = suma de las `d2D` anteriores, como `LineChainage` pero con la distancia activa.
### A2. Totales y área (D4)
`length2D = Σ d2D`; `length3D = Σ d3D` si ningún segmento carece de altura. Si la línea es un polígono: `perímetro = length2D`; `área = |½ Σ (Eᵢ·Nᵢ₊₁ − Eᵢ₊₁·Nᵢ)| / f²`, con coordenadas relativas al primer vértice para no perder dígitos con valores grandes de Este y Norte.
### A3. Cruces (D5)
Segmentos ordenados por su Este mínimo; para cada uno se recorren los siguientes mientras su Este mínimo no supere el Este máximo del actual, y se prueba la intersección propia (orientación de los cuatro puntos), saltando los segmentos consecutivos. Cualquier cruce marca `crosses`.
### A4. Ángulo entre tres puntos (D13)
`v₁ = A − B`, `v₂ = C − B` en el plano; `ángulo = atan2(|v₁ × v₂|, v₁ · v₂)`; azimuts de BA y BC con `atan2`; distancias como en A1 con el factor del centro de cada una. Si `|v₁|` o `|v₂|` es 0, no hay ángulo y se indican los puntos coincidentes (RF-15).

## Contratos
- `GeometryCalculator.of(vertices, closed, mode, scale): GeometryResult` = `Info(GeometryInfo)` o `TooFewVertices` (RF-7).
- `GeometryPreferences`: `distanceMode: Flow<DistanceMode>`, `setDistanceMode(mode)`.
- `ThreePointAngle.of(a, b, c, mode, scale): AngleResult` = `Angle(...)` o `Coincident(points)`.
- Textos (es/en, prefijo `geometry_*`): «Información», «Longitud 2D», «Longitud 3D», «Perímetro», «Área», «Cuadrícula», «Terreno», «Exportar tabla», «Ángulo entre tres puntos», avisos de cruces, de altura que falta, de plano local, de factor sin altura y de coincidencia, y sus unidades.

## Estrategia de tests
- Unitarios JVM (`core:model`): `AreaFormatTest`, `SlopeFormatTest`.
- Unitarios JVM (`core:survey`): `GeometryCalculatorGridTest`, `GeometryCalculatorGroundTest`, `GeometryAreaTest`, `CrossingFinderTest`, `GeometryGeographicTest`, `ThreePointAngleTest`, todos frente a `fixtures/geometria/` (D15, ≤ 1 mm, 1 mm², 1 µrad).
- Unitarios JVM (`core:formats`): `GeometryCsvTest` (`Locale.ROOT`, totales, cabecera).
- Python: `tools/geometria/test_generar_referencias.py`.
- Robolectric (`data`): `GeometryPreferencesTest`, `VertexPointTest` (altura y posición, sin cargar todos los puntos).
- Robolectric (`app`): `GeometryInfoViewModelTest` (modo recordado, recalcular al editar un vértice, sin vértices), `GeometryInfoScreenTest`, `GeometryExportTest`, `CadLineInfoTest`, `ThreePointAngleTest`, `GeometryPerformanceTest` (100.000 vértices en menos de 1 s y tabla sin bloquear el hilo principal más de 16 ms), `GeometryAccessibilityTest`; los `viewModelScope` se cancelan antes de cerrar la base y los nombres de test van cortos por Windows.
- Manual: la demo del emulador de la spec. Campo: queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | D1, D4, D10, A2 | `GeometryCalculatorGridTest`, `GeometryAreaTest`, `GeometryInfoScreenTest` |
| RF-2 | D3, A1 | `GeometryCalculatorGridTest`, `GeometryInfoScreenTest` |
| RF-3 | D3 | `GeometryCalculatorGridTest`, `GeometryInfoScreenTest` |
| RF-4 | D10 | `CadLineInfoTest` |
| RF-5 | D10, D11 | `GeometryInfoViewModelTest`, `VertexPointTest` |
| RF-6 | D5, A3 | `CrossingFinderTest`, `GeometryAreaTest`, `GeometryInfoScreenTest` |
| RF-7 | `TooFewVertices` | `GeometryCalculatorGridTest`, `GeometryInfoViewModelTest` |
| RF-8 | D14 | `GeometryCsvTest`, `GeometryExportTest` |
| RF-9 | D8 | `GeometryPreferencesTest`, `GeometryInfoViewModelTest` |
| RF-10 | D2, D7 | `GeometryCalculatorGroundTest` |
| RF-11 | D6 | `GeometryGeographicTest`, `GeometryInfoScreenTest` |
| RF-12 | D9 | `AreaFormatTest`, `SlopeFormatTest`, `GeometryInfoScreenTest` |
| RF-13 | D13, D16 | `ThreePointAngleTest` (app) |
| RF-14 | D13, A4 | `ThreePointAngleTest` (survey y app) |
| RF-15 | A4 | `ThreePointAngleTest` (survey y app) |
| RNF-T7, RNF-T8 | textos y 56 dp, tabla monoespaciada | `GeometryAccessibilityTest` |
| RNF-T9 | D9, D14 | `GeometryCsvTest`, `AreaFormatTest` |
| RNF-T13 | D12 | `GeometryPerformanceTest` |
| Constitución nº 2 | D15 | tests de `core:survey` y de `tools` |
| Solo lee | D1, D10 | `GeometryInfoViewModelTest` (ningún punto ni línea cambia) |

## Cambios al implementar
- **D7, altura de reserva:** el punto central del proyecto (S006) solo guarda latitud y longitud, no altura. La app pasa `fallbackHeightM = null`: si a un vértice le falta la altura elipsoidal, el terreno usa el factor de escala sin corregir por altura y lo avisa (`FACTOR_WITHOUT_HEIGHT`). `GroundScale` admite una altura de reserva (con test) por si una spec posterior guarda la del centro.
- **D7, sin factor:** si el SC no da factor (geográfico, vértice sin posición o fuera del área del SC) se usa 1 y se avisa (`NO_FACTOR`). Se añadió `GeometryResult.NoPosition` (y `AngleResult.NoPosition`) para vértices sin Este y Norte ni posición geodésica.
- **D10, menú del mapa y dibujos:** tocar una línea en el mapa ya abre su ficha (S025), que ahora trae «Información» y «Ángulo»; no se añadió un menú propio al mapa. La información de una línea de un dibujo no es un destino de navegación: la hoja del elemento (`DesignElementSheet`) abre `DesignGeometryDialog`, a pantalla completa, con el mismo `GeometryInfoList` que la pantalla de una línea del proyecto. Un anillo de polígono de un GeoPackage se calcula como polígono (sin repetir el primer vértice).
- **D1/D2, `GroundPosition`:** la ruta de Este y Norte a latitud y longitud (UTM inversa y rejilla de ED50) se sacó de `CadProjector` (S040) a `core:survey` como `GroundPosition`; `CadProjector` la usa y sus tests siguen en verde.
- **D4, polígono:** el área de terreno usa el factor del centro del polígono (media de las posiciones de sus vértices), como decía el plan; sin cruces es el área simple y con cruces el valor absoluto del área con signo.
- **D11, `VertexPoint`:** gana `height`, `latitudeDeg`, `longitudeDeg` y `ellipsoidalHeightM`, con campos por defecto para no tocar a quien lo construye.
- **D12, rendimiento:** no hizo falta reutilizar el factor entre segmentos próximos: 100.000 vértices en terreno salen en unos 0,3 s y en cuadrícula en menos de 0,1 s tras calentar; la información de una línea de 100.000 vértices desde la base llega en menos de 1 s.
- **D13, ángulo:** `AngleResult.Coincident` indica los pares de puntos coincidentes, también A y C. Los tres puntos se eligen en un selector de lista con búsqueda (todos los del proyecto o, desde la ficha de una línea, solo sus vértices); el nombre del elegido se lee del punto y se recalcula si se edita. En los tests de `app` los nombres son `ThreePointAngleViewModelTest` y `ThreePointAngleScreenTest`, no `ThreePointAngleTest`.
- **D14, CSV:** coma como separador y punto decimal siempre, sin las opciones de S012; la cabecera lleva la distancia y las unidades (`Length 2D (m grid)`), los totales van como filas `Total`, `Perimeter` y `Area (m2)` con su valor en la columna de la longitud 2D (el área en la unidad de área del proyecto y en ASCII). La exportación reutiliza `ProfileExportButtons` (Guardar y Compartir) bajo el título «Exportar tabla».
- **D15, referencias:** el lazo de las referencias se hizo asimétrico (área con signo 2.000 m²) para que el test del área con cruces no sea 0.
- **Tabla:** las filas comparten un único desplazamiento horizontal, con una cabecera que se desplaza con ellas; cada fila se fusiona en un solo elemento de accesibilidad con «Segmento n, de A a B».
- **RF-4:** con el dibujo sin cota o sin Z en el elemento se ocultan la longitud 3D, el aviso de alturas y las columnas de 3D, desnivel y pendiente (`planOnly`).
- **Tests intermitentes vistos (ajenos):** `AxisStakeoutPerformanceTest`, `CollectorViewModelAdmittedTest`, `LineFromPointsTest`, `GpkgImportCrsScreenTest`, `CollectorViewModelPhotosTest`, `GearBackupTest`, `ActiveSurfaceTest`, `BaseShiftViewModelTest` y `CollectorLineViewModelTest`; se repite el `check`.
- **D16, otras specs:** no se han tocado los `spec.md` de S006, S010, S011, S013, S018 ni S053. Room sigue en la v30.
