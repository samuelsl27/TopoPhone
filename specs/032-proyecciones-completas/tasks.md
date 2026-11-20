# Tareas — Spec 032

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa en un prompt propio, con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S032 Tn: …` en `main`. Las tareas de vectores añaden también `uv run --project tools pytest tools`.

## Contrato común
- [x] T1. Contrato del paquete `es.topophone.core.geodesy.projection`: `ProjectionMethod` con `implemented`, `ProjectionParameters` (solo el contrato y las clases de datos de cada variante), `ParameterProblem`, `MapProjection`, `ProjectionSetup`, `ProjectionFactory` (por ahora `Invalid` con `UNSUPPORTED_METHOD`), el envoltorio `PrimeMeridianProjection` y la conversión de los falsos a metros. (RF-16, RF-17, RF-20)
      Hecho cuando: `ProjectionMethodTest`, `ProjectionUnitsTest` y `PrimeMeridianProjectionTest` pasan con una proyección de prueba (identidad con falsos), y `check` pasa.

## Lambert Conformal Conic
- [x] T2. `tools/geodesia/proyecciones.py` (constructor de WKT2, comprobación del método de cada CRS en PROJ y cabecera de versiones) y `fixtures/geodesia/lcc.csv` con ida, vuelta, factor y convergencia: EPSG:2154, 3034, 32040 (`us-ft`), 27571 y 27572 (París), y una rejilla propia con esfera, hemisferio sur y paralelos iguales; `generar_vectores.py` lo invoca. (RF-1, RF-2)
      Hecho cuando: `test_vectores.py` comprueba columnas, recuento y método de cada caso, y el fichero se regenera igual (determinista).
- [x] T3. `LambertConic1Sp` y `LambertConic2Sp`: `forward` e `inverse`, con la inversa por Newton sobre la latitud conforme y `null` para el polo opuesto. (RF-1, RF-19)
      Hecho cuando: `LambertConicTest` cubre los 40 o más vectores a ≤ 1 mm y ≤ 1·10⁻⁸ ° y los casos límite de la spec.
- [ ] T4. Factor de escala y convergencia de LCC y validación de parámetros en `ProjectionFactory`. (RF-2, RF-3)
      Hecho cuando: `LambertConicFactorsTest` (≤ 1·10⁻⁹ y ≤ 0,01″) y `LambertConicParametersTest` (cada causa con su parámetro) pasan.

## Mercator
- [ ] T5. Vectores de Mercator A y B (`mercator.csv`): EPSG:3002 y 3388 y una rejilla propia con esfera, hemisferio sur y paralelo estándar en el ecuador. (RF-4, RF-5)
      Hecho cuando: `test_vectores.py` los comprueba y el fichero se regenera igual.
- [ ] T6. `MercatorA` y `MercatorB` completos: `forward`, `inverse`, factores, validación y `null` en los polos. (RF-4, RF-5, RF-6, RF-19)
      Hecho cuando: `MercatorTest`, `MercatorFactorsTest` y `MercatorParametersTest` pasan.

## Oblique Mercator
- [ ] T7. Vectores de OM A y B (`omerc.csv`): EPSG:29873 y 26931 y una rejilla propia con azimut de 0°, 90° y 180°, ángulo de rectificación distinto del azimut y esfera. (RF-7, RF-8)
      Hecho cuando: `test_vectores.py` los comprueba (con los factores por la cadena `+proj=` si hace falta) y el fichero se regenera igual.
- [ ] T8. `ObliqueMercatorA` y `ObliqueMercatorB`: `forward` e `inverse` y `null` a 90° o más de la línea central. (RF-7, RF-19)
      Hecho cuando: `ObliqueMercatorTest` pasa a ≤ 1 mm y ≤ 1·10⁻⁸ ° con los casos límite.
- [ ] T9. Factor de escala y convergencia de OM (fórmula cerrada o diferencias de 4.º orden) y validación de parámetros. (RF-8, RF-9)
      Hecho cuando: `ObliqueMercatorFactorsTest` y `ObliqueMercatorParametersTest` pasan.

## Estereográfica oblicua
- [ ] T10. Vectores de la Estereográfica oblicua (`stereo_oblicua.csv`): EPSG:28992 más el punto publicado de GN 7-2, y una rejilla propia con origen en el ecuador, cerca de un polo y esfera. (RF-10, RF-11)
      Hecho cuando: `test_vectores.py` los comprueba y el fichero se regenera igual.
- [ ] T11. `ObliqueStereographic`: `forward` e `inverse` y `null` en la antípoda del origen. (RF-10, RF-19)
      Hecho cuando: `ObliqueStereographicTest` pasa, incluido el punto publicado de RD Nueva (53° N, 6° E).
- [ ] T12. Factores de la Estereográfica oblicua y validación de parámetros. (RF-11, RF-12)
      Hecho cuando: `ObliqueStereographicFactorsTest` y `ObliqueStereographicParametersTest` pasan.

## Estereográfica polar
- [ ] T13. Vectores de la polar A, B y C (`stereo_polar.csv`): EPSG:5041, 5042, 3031, 3413 y 2985, y una rejilla propia en los dos polos, con paralelo estándar en el polo y esfera. (RF-13, RF-14)
      Hecho cuando: `test_vectores.py` los comprueba y el fichero se regenera igual.
- [ ] T14. `PolarStereographicA`, `B` y `C`: `forward` e `inverse` en los dos polos y `null` en el polo opuesto. (RF-13, RF-19)
      Hecho cuando: `PolarStereographicTest` pasa a ≤ 1 mm y ≤ 1·10⁻⁸ °.
- [ ] T15. Factores de la polar y validación de parámetros (polo equivocado, paralelo estándar fuera de rango, factor no positivo). (RF-14, RF-15)
      Hecho cuando: `PolarStereographicFactorsTest` y `PolarStereographicParametersTest` pasan.

## Meridiano de origen, unidades y transversales
- [ ] T16. Vectores con meridiano de origen distinto de Greenwich (`meridiano_origen.csv`: Madrid, París y Lisboa en LCC, Mercator, OM, oblicua y polar, y un caso que cruza el antimeridiano) y su prueba con `ProjectionFactory`. (RF-17)
      Hecho cuando: `PrimeMeridianProjectionTest` pasa con los vectores de todos los métodos.
- [ ] T17. Vectores con falsos en `ft` y `us-ft` (`unidades_proyeccion.csv`) y su prueba. (RF-16)
      Hecho cuando: `ProjectionUnitsTest` pasa con los vectores de pyproj (EPSG:32040 y 29873 entre ellos).
- [ ] T18. Ida y vuelta en una rejilla por método y rechazo de los puntos sin imagen. (RF-18, RF-19)
      Hecho cuando: `ProjectionRoundTripTest` (≤ 0,1 mm en los 11 métodos) y `ProjectionNoImageTest` (`null` en cada caso de RF-19, nunca un valor extrapolado) pasan.
- [ ] T19. Rendimiento: 100.000 puntos por método en menos de 1 s. (NFR)
      Hecho cuando: `ProjectionPerformanceTest` pasa con los 11 métodos (y, si falla bajo carga, a la segunda con `--no-parallel`).
- [ ] T20. `ProjectionFactory` completo (los 11 métodos) y no regresión: `ProjectionMethod.implemented` coincide con lo que `create` sabe crear, y UTM y Web Mercator dan lo de siempre. (RF-20, RF-21)
      Hecho cuando: `ProjectionMethodTest` y `ExistingProjectionsUnchangedTest` pasan (con vectores de `utm_etrs89.csv`, `factores_tm.csv` y `web_mercator.csv`).

## Cierre
- [ ] T21. Validación final: recorrer la spec RF por RF, `rf_report.py`, `check`, `pytest` de `tools`, `forbidden_files`, `assembleDebug`, sección «Cambios al implementar» y «Validación» de `plan.md`, `fixtures/LEEME.md` y `tools/LEEME.md`, y hoja de ruta con S032 `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y lo que no se pudo contrastar con la nota GN 7-2 queda anotado.
