# Plan técnico — Spec 008

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-2 a ADR-6, ADR-8), `specs/008-coordenadas-espana/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `tools` | Rejillas SPED2ETV2 y EGM08-REDNAP Canarias en el registro (con SHA-256); vectores de pyproj para REGCAN95 / UTM 27–28, geoide de Canarias, ED50 / UTM 29–31 (por la rejilla) y factor de escala y convergencia | RF-1, RF-4, RF-7, RF-13, RF-14; RNF |
| `core:geodesy` | `Ellipsoid.INTERNATIONAL_1924`; UTM sobre cualquier elipsoide; factor de escala y convergencia de la TM; lector GeoTIFF con varias subrejillas y bandas; `HorizontalShiftGrid` y la transformación ETRS89 → ED50; geoide de Canarias | RF-4, RF-7, RF-8, RF-13, RF-14 |
| `core:model` | `ProjectCrs` con los 11 SC (datum, proyección, huso, ámbito), `HeightSystem`, datum de las correcciones de cada SC, aviso de datum general, formato de ángulos en gon y de la convergencia | RF-1 a RF-3, RF-6, RF-9, RF-14, RF-17, RF-18 |
| `core:survey` | `ProjectPositionCalculator` para todos los SC y alturas, con factores y huso de la posición; `GroundPointCalculator` con husos 27–31 y Canarias | RF-4 a RF-9, RF-12 a RF-16, RF-20, RF-21 |
| `data` | Room v3: columna `height_system` en `projects` (migración 2→3 probada); `ProjectPreferences` con la altura por defecto | RF-2, RF-10; RNF |
| `app` | Formulario con datum, proyección y altura; huso propuesto; datum de las correcciones según el SC; vista de proyecto con geográficas, altura elipsoidal, factores y aviso de huso; Ajustes; pantalla Coordenadas con Canarias | RF-1 a RF-21 |

Sin dependencias nuevas. Las rejillas (`es_ign_SPED2ETV2.tif`, 180 KB, y `es_ign_egm08-rednap-canarias.tif`, 83 KB) salen del CDN de PROJ con licencia CC-BY 4.0 del IGN y van en `core/geodesy/src/main/resources/`, como la de la Península (ADR-4).

## Modelo de datos
```kotlin
// core:model
enum class GeodeticDatum { ETRS89, REGCAN95, ED50 }
enum class CrsArea { PENINSULA_BALEARES, CANARIAS }
enum class ProjectCrs(val datum: GeodeticDatum, val utmZone: Int?, val epsg: Int) {   // utmZone null = geográficas
    ETRS89_UTM29(ETRS89, 29, 25829), ETRS89_UTM30(ETRS89, 30, 25830), ETRS89_UTM31(ETRS89, 31, 25831),
    ETRS89_GEOGRAPHIC(ETRS89, null, 4258),
    REGCAN95_UTM27(REGCAN95, 27, 4082), REGCAN95_UTM28(REGCAN95, 28, 4083), REGCAN95_GEOGRAPHIC(REGCAN95, null, 4081),
    ED50_UTM29(ED50, 29, 23029), ED50_UTM30(ED50, 30, 23030), ED50_UTM31(ED50, 31, 23031);
    val area: CrsArea   // REGCAN95 → CANARIAS; los demás → PENINSULA_BALEARES
    val heightSystems: List<HeightSystem>   // ED50 → solo ORTHOMETRIC (RF-3)
}
enum class HeightSystem { ORTHOMETRIC, ELLIPSOIDAL }   // ORTHOMETRIC = EGM08-REDNAP del ámbito (RF-4)
```
- Los valores de S006 conservan su nombre: la columna `crs` de la v2 sigue valiendo sin migrar datos.
- `ProjectDraft`, `Project` y `ProjectDefaults` añaden `heightSystem` (por defecto `ORTHOMETRIC`).

Room `topophone.db`, **versión 3**: `projects.height_system TEXT NOT NULL DEFAULT 'ORTHOMETRIC'` (AutoMigration 2→3). DataStore "projects": clave `default_height_system`.

`ProjectPosition` (core:survey) pasa a:
```kotlin
data class ProjectPosition(
    val easting: Double?, val northing: Double?,          // SC UTM
    val latitudeDeg: Double?, val longitudeDeg: Double?,  // SC geográficas
    val height: Double?,                                   // altitud o altura elipsoidal, según el proyecto
    val scaleFactor: Double?, val convergenceRad: Double?, val combinedFactor: Double?,  // solo UTM (RF-13 a RF-16)
    val positionZone: Int?,                                // huso que corresponde a la longitud del punto (RF-12)
    val unavailable: Set<PositionUnavailable>,             // + OUTSIDE_ED50_GRID (RF-8)
)
```

## Algoritmos
### Factor de escala y convergencia de la TM (RF-13, RF-14)
Karney (2011), ecuaciones 13–14, con las mismas series de orden 6 que la proyección:
```
p' = 1 + Σ 2j·αj·cos(2jξ')·cosh(2jη')      q' = Σ 2j·αj·sin(2jξ')·sinh(2jη')
γ = atan2(τ'·tan λ, √(1+τ'²)) + atan2(q', p')
k = k0 · (A/a) · √(1 − e²·sin²φ) · √(1+τ²) / √(τ'² + cos²λ) · √(p'² + q'²)
```
La referencia es `pyproj.Proj(...).get_factors()`: `meridian_convergence` y `parallel_scale`.

### Factor combinado (RF-15)
- Radio medio de Gauss R = √(M·N), con M el radio de curvatura del meridiano y N el del primer vertical.
- Factor de altura = R / (R + h), con h la altura elipsoidal del punto del suelo.
- Combinado = k × factor de altura.

### ED50 desde ETRS89 (RF-7, RF-8)
La rejilla da los desplazamientos en ED50: `φ_ETRS89 = φ_ED50 + Δφ(φ_ED50, λ_ED50)` (lo mismo para λ), en segundos de arco.
```
φ₀ = φ_ETRS89 − Δφ(φ_ETRS89, λ_ETRS89)            (igual para λ)
repetir: φₖ₊₁ = φ_ETRS89 − Δφ(φₖ, λₖ)  hasta |Δ| < 1e-12° (≤ 10 iteraciones)
fuera de todas las subrejillas → OUTSIDE_ED50_GRID (sin extrapolar, ADR-5)
```
- Subrejillas: la primera que contiene el punto (BALEARES va antes que PENINSUL en el fichero).
- El signo de la longitud sale del metadato `positive_value` (east/west) de la banda.
- La interpolación es bilineal, como la de PROJ (`hgridshift`).
- Después: UTM sobre el elipsoide internacional de 1924.
- La altitud se calcula en ETRS89, con el geoide de la Península (RF-4).

### Lector GeoTIFF (RF-4, RF-7)
Lo que se añade a lo que ya lee:
- varias IFD (subrejillas);
- `SamplesPerPixel` 2 con `PlanarConfiguration` 2 (cada banda en sus propias teselas o tiras);
- el XML de `GDAL_METADATA` (nombre de la rejilla, `positive_value`).

### Posición en el SC del proyecto (RF-4 a RF-9, RF-12 a RF-16)
```
1. Punto del suelo en el datum de las correcciones (S002/S006): φ, λ, h.
2. Ámbito del SC (RF-9):
   PENINSULA_BALEARES = φ ∈ [35°, 44,5°], λ ∈ [−10°, 5°] (como S006)
   CANARIAS = φ ∈ [27°, 29,5°], λ ∈ [−18,5°, −13°]
   fuera → OUTSIDE_CRS_AREA.
3. Horizontal: ETRS89/REGCAN95 → φ, λ tal cual; ED50 → transformación de arriba.
   UTM → Este/Norte en el huso del proyecto (huso forzado) con el elipsoide del datum; geográficas → φ, λ.
4. Altura: ORTHOMETRIC → H = h − N (geoide de la Península en ETRS89/ED50, de Canarias en REGCAN95; fuera → OUTSIDE_GEOID);
   ELLIPSOIDAL → h.
5. UTM: k, γ y el factor combinado (con h); positionZone = zoneFor(λ).
```

### Huso propuesto (RF-11)
Al abrir el formulario de un proyecto nuevo, si hay posición del receptor y el SC por defecto es UTM:
- en Canarias → `REGCAN95_UTM27` o `REGCAN95_UTM28`, según la longitud;
- en la Península y Baleares → el de su datum (ETRS89 o ED50) en el huso de la longitud (29 a 31).
Fuera de los dos ámbitos no se propone nada.

### Datum de las correcciones (RF-17, RF-18)
- `ProjectCrs.correctionsDatum`: REGCAN95 → `CorrectionsDatum.REGCAN95`; ETRS89 y ED50 → `CorrectionsDatum.ETRS89`.
- `DatumCheck.mismatch(crs, corrections) = corrections != crs.correctionsDatum`.
- S006/RF-11 sigue siendo el caso ITRF/WGS84 con ETRS89, con su texto de 0,9 m. Los demás casos usan un texto general.

## Contratos
- `core:geodesy`:
  - `Utm.forward(lat, lon, zone, ellipsoid = GRS80)`;
  - `Utm.factors(lat, lon, zone, ellipsoid): PointFactors(scale, convergenceRad)`;
  - `GeoTiffGrid.readAll(bytes): List<GeoTiffGrid>` (bandas y metadatos);
  - `HorizontalShiftGrid.of(grids)`;
  - `Ed50.fromEtrs89(lat, lon): Pair<Double, Double>?`;
  - `GeoidModels.egm08RednapCanarias`;
  - `Ellipsoid.INTERNATIONAL_1924`;
  - `Geodesy.gaussMeanRadius(lat, ellipsoid)`.
- `core:model`:
  - `ProjectCrs`, `HeightSystem`, `CrsArea`;
  - `ProjectCrs.correctionsDatum`, `ProjectCrs.forPosition(lat, lon, family)` (huso propuesto);
  - `AngleFormat.gon(...)` y `AngleFormat.angle(valueRad, unit, locale)`.
- `core:survey`: `ProjectPositionCalculator.compute(fix, poleHeightM, crs, heightSystem): ProjectPosition?`; `GroundPointCalculator`, con husos 27–31 y el datum/geoide de Canarias.
- `data`: Room v3 y `ProjectEntity.heightSystem`; `ProjectPreferences` con la altura por defecto.
- `app`:
  - `ProjectFormScreen` con tres grupos: Datum (ETRS89, REGCAN95, ED50), Proyección (UTM huso N de ese datum, o Geográficas) y Altura (Altitud EGM08-REDNAP o Elipsoidal);
  - `ProjectViewScreen` con la tarjeta "Posición actual" ampliada;
  - `ProjectDefaultsScreen`; `CoordinatesCard` (Receptor).

Textos nuevos (es):

| Caso | Texto |
|---|---|
| Grupos del formulario | "Datum", "Proyección", "Altura" |
| Opciones | "UTM huso %d", "Coordenadas geográficas", "Altitud (EGM08-REDNAP)", "Altura elipsoidal" |
| SC en la lista | "ETRS89 / UTM huso 30", "REGCAN95 / geográficas", "ED50 / UTM huso 30" |
| Altura en la vista | "Altitud (H, EGM08-REDNAP)" / "Altura elipsoidal (h)" |
| Geográficas | "Latitud (φ)" / "Longitud (λ)" |
| Factores | "Factor de escala", "Convergencia", "Factor combinado" |
| Otro huso | "Estás en el huso %1$d; el proyecto usa el %2$d." |
| Fuera de ED50 | "Fuera de la transformación ED50 del IGN (rejilla SPED2ETV2)." |
| Aviso de datum general | "Las correcciones están en %1$s y el proyecto en %2$s: las coordenadas pueden diferir." |
| Huso propuesto | "Huso propuesto por tu posición." |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| SC del proyecto como un enum de 11 valores, más la altura aparte | Los nombres de S006 siguen valiendo: no hay que migrar datos, solo añadir una columna | Clase de datos con datum, proyección y huso (migración de todos los proyectos) |
| Formulario en tres grupos (datum, proyección, altura) | 11 opciones seguidas no se leen a pleno sol; al editar solo se bloquea el datum (RF-19) | Una lista de 11 SC |
| ED50 por la rejilla oficial invertida por iteración, como PROJ | Es la transformación EPSG:15932 que usa el IGN | Helmert de 7 parámetros (error de decímetros) |
| Rejillas en recursos de `core:geodesy` | Tests JVM sin Android y funcionamiento sin conexión | Assets de la app (los tests de `core` no los ven) |
| Factores con las mismas series de Krüger | Coherentes con las coordenadas; error despreciable hasta ±3,5° | Fórmulas de Redfearn (peor fuera del huso, con huso forzado) |
| Huso propuesto solo en el formulario de proyecto nuevo | Lo pide la spec; editar no cambia el SC por sorpresa | Cambiar el huso solo durante el trabajo |

## Estrategia de tests
- **`tools` (pytest):** las rejillas nuevas con su SHA-256; el generador de vectores reproducible (misma semilla, mismos CSV).
- **JVM `core:geodesy`:**
  - UTM REGCAN95 y ED50 contra vectores (≤ 1 mm), y ED50 fuera de la rejilla;
  - ida y vuelta de la rejilla ≤ 0,1 mm;
  - factor de escala (≤ 1·10⁻⁹) y convergencia (≤ 0,01″) contra pyproj, también con huso forzado;
  - lector con varias subrejillas y bandas;
  - geoide de Canarias (≤ 1 mm) y fuera de él;
  - tamaño de las rejillas incluidas (< 300 KB entre las dos nuevas).
- **JVM `core:model`:** catálogo (11 SC, ámbitos, EPSG, alturas por SC), datum de las correcciones por SC, aviso general, formatos de geográficas en DD, DMS y gon con su resolución, y huso propuesto.
- **JVM `core:survey`:**
  - `ProjectPositionCalculator` contra vectores para cada SC, cada altura y cada caso no disponible;
  - el factor combinado contra su fórmula con R de pyproj;
  - huso de la posición;
  - `GroundPointCalculator` en Canarias.
- **Robolectric `data`:** migración 2→3 con un proyecto de la v2 (que queda con `ORTHOMETRIC`), repositorio y preferencias con la altura.
- **Robolectric `app`:**
  - formulario (grupos, ED50 sin elipsoidal, datum de las correcciones, aviso, edición con el datum bloqueado, huso propuesto);
  - vista de proyecto (geográficas, elipsoidal, factores, otro huso, fuera de ED50);
  - Ajustes; pantalla Coordenadas en Canarias.
- **Tests de S006 y S002 que daban por hecho 3 SC o los husos 29–31:** se adaptan.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-3, RF-9 | Catálogo de SC | `ProjectCrsTest`, `ProjectFormCrsTest` |
| RF-4 | Geoides | `GeoidCanariasTest`, `ProjectPositionCalculatorTest` |
| RF-5, RF-6 | Altura elipsoidal y geográficas | `ProjectPositionCalculatorTest`, `AngleFormatTest`, `ProjectViewCrsTest` |
| RF-7, RF-8 | ED50 | `Ed50Test`, `HorizontalShiftGridTest`, `ProjectViewCrsTest` |
| RF-10 | Ajustes | `ProjectDefaultsTest`, `ProjectPreferencesTest` |
| RF-11, RF-12 | Huso propuesto y aviso | `ProjectCrsTest`, `ProjectFormCrsTest`, `ProjectViewCrsTest` |
| RF-13 a RF-16 | Factores | `UtmFactorsTest`, `ProjectPositionCalculatorTest`, `ProjectViewCrsTest` |
| RF-17, RF-18 | Datum de las correcciones | `ProjectNamesTest` (DatumCheck), `ProjectFormCrsTest` |
| RF-19 | Editar | `ProjectFormCrsTest` |
| RF-20, RF-21 | Coordenadas del receptor | `GroundPointCalculatorTest`, `CoordinatesCardTest` |
