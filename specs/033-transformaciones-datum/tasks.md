# Tareas — Spec 033

> Cada tarea dura 30 min como máximo, sigue el orden de dependencias y se implementa con los tests primero. Cada tarea cierra con `./gradlew check` en verde (se repite con `--no-parallel` si falla un test ajeno intermitente), la casilla marcada y un commit `S033 Tn: …` en `main`. Las tareas de vectores añaden también `uv run --project tools pytest tools`.

## Contrato común
- [ ] T1. Contrato del paquete `es.topophone.core.geodesy.datum`: `DatumTransformation`, `TransformResult`, `TransformWarning`, `TransformationInfo`, `AreaOfUse`, `ParameterProblem`, `HelmertParameters` con sus factorías de 3, 4, 6 y 7 parámetros y la conversión de segundos de arco, ppm y metros a SI, y la validación de parámetros no finitos. (RF-4, RF-6)
      Hecho cuando: `HelmertParametersTest` pasa (conversiones, factorías de 3/4/6/7, todos los problemas a la vez) y `check` pasa.

## Helmert
- [ ] T2. `tools/geodesia/datum.py` (cabecera de versiones y comprobación del método EPSG en PROJ) y `fixtures/geodesia/helmert.csv`: 3, 4, 6 y 7 parámetros en PV y CF con varios pares de elipsoides, parámetros a cero y rotaciones ≥ 1″ con escala ≥ 10 ppm, con ida y vuelta de pyproj; `generar_vectores.py` lo invoca. (RF-1, RF-2)
      Hecho cuando: `test_datum.py` comprueba columnas, recuento y método de cada caso, y el fichero se regenera igual.
- [ ] T3. `HelmertTransformation.forward` (PV y CF, ECEF, elipsoides de origen y destino). (RF-1, RF-2)
      Hecho cuando: `HelmertTransformationTest` cubre los vectores a ≤ 1 mm y ≤ 1·10⁻⁸ °, y `HelmertConventionTest` comprueba que la convención contraria da otro resultado.
- [ ] T4. Inverso exacto de `HelmertTransformation` (inversión de la matriz) y ida y vuelta. (RF-5)
      Hecho cuando: `HelmertInverseTest` comprueba ida y vuelta ≤ 0,1 mm y diferencia con el inverso de pyproj < 1 mm, también con rotaciones ≥ 1″ y escala ≥ 10 ppm.

## Molodensky-Badekas
- [ ] T5. Vectores de MB (`molodensky_badekas.csv`): operaciones de EPSG de método 9636 y una rejilla propia con el punto de rotación en el centro de la Tierra. (RF-3)
      Hecho cuando: `test_datum.py` los comprueba y el fichero se regenera igual.
- [ ] T6. `MolodenskyBadekas`: directa e inversa exacta. (RF-3, RF-5)
      Hecho cuando: `MolodenskyBadekasTest` pasa a ≤ 1 mm, con `P = 0` igual al Helmert de 7 y ida y vuelta ≤ 0,1 mm.

## Rejillas de desplazamiento
- [ ] T7. Vectores de rejilla (`rejilla_desplazamiento.csv`): `hgridshift` de pyproj con la SPED2ETV2 en el interior, en el borde, en dos subrejillas y fuera de la rejilla. (RF-7, RF-8, RF-10)
      Hecho cuando: `test_datum.py` los comprueba y el fichero se regenera igual.
- [ ] T8. `GridShiftTransformation.forward` con `GridSource` y rechazo fuera de la rejilla (`OutsideGrid`). (RF-7, RF-8)
      Hecho cuando: `GridShiftTransformationTest` y `GridShiftOutsideTest` pasan con los vectores.
- [ ] T9. Rejilla ausente o ilegible: `GridUnavailable` con el nombre de la rejilla, sin calcular. (RF-9)
      Hecho cuando: `GridShiftUnavailableTest` pasa con una fuente que devuelve `null`, otra con bytes dañados y otra que lanza excepción.
- [ ] T10. Inversa de la rejilla iterativa con `NotConverged` y `OutsideGrid`. (RF-10)
      Hecho cuando: `GridShiftInverseTest` comprueba ida y vuelta ≤ 0,1 mm en los puntos de la rejilla (desplazamientos de más de 100 m) y el rechazo si no converge.

## Cadenas, metadatos y avisos
- [ ] T11. Vectores de una cadena de tres pasos (`cadena_tres_pasos.csv`: Helmert → rejilla → Helmert) con la misma cadena en pyproj. (RF-11)
      Hecho cuando: `test_datum.py` los comprueba y el fichero se regenera igual.
- [ ] T12. `TransformationChain` en sentido directo e inverso: pasos geocéntricos consecutivos en ECEF, una conversión por punto y sin redondeos. (RF-11, RF-13)
      Hecho cuando: `TransformationChainTest` coincide con pyproj a ≤ 1 mm y `TransformationNoRoundingTest` coincide bit a bit con la composición manual.
- [ ] T13. Rejilla ausente en un paso de la cadena: se rechaza el punto con el nombre de la rejilla y no se calcula ningún otro paso. (RF-12)
      Hecho cuando: `TransformationChainMissingGridTest` pasa con la rejilla ausente en primero, medio y último lugar.
- [ ] T14. Metadatos (`TransformationInfo`) y aviso de ámbito de las transformaciones de parámetros. (RF-14, RF-15)
      Hecho cuando: `TransformationInfoTest` y `TransformationAreaWarningTest` pasan (dentro, borde y fuera del ámbito, que calcula con aviso).
- [ ] T15. No regresión de S008: ED50 y las proyecciones dan lo de siempre y una cadena con la rejilla de ED50 coincide con `Ed50.toEtrs89`. (RF-16)
      Hecho cuando: `Ed50UnchangedTest` pasa con los vectores de `ed50_utm.csv` y `Ed50.kt` no tiene cambios en el diff de la spec.

## Transversales y cierre
- [ ] T16. Ida y vuelta de todas las familias (Helmert 3/4/6/7, MB, rejilla y cadena) en una rejilla de puntos. (RF-5, RF-10, RF-11)
      Hecho cuando: `DatumRoundTripTest` pasa con ≤ 0,1 mm.
- [ ] T17. Rendimiento: 100.000 puntos con rejilla en menos de 2 s. (NFR)
      Hecho cuando: `DatumPerformanceTest` pasa (y, si falla bajo carga, a la segunda con `--no-parallel`).
- [ ] T18. Validación final: recorrer la spec RF por RF, `rf_report.py`, `check`, `pytest` de `tools`, `forbidden_files`, `assembleDebug`, secciones «Cambios al implementar» y «Validación» de `plan.md`, `fixtures/LEEME.md` y `tools/LEEME.md`, y hoja de ruta con S033 `implementada`. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos y lo que no se pudo contrastar con la nota GN 7-2 queda anotado.
