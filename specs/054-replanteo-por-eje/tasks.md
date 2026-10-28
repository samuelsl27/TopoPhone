# Tareas — Spec 054

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/ejes`: ampliar `generar_ejes.py` con `fixtures/ejes/replanteo/`: PK y desplazamiento a ambos lados con espesor, series con intervalo y puntos singulares, guía adelante/atrás y dentro/fuera, y una reproducción NMEA que recorre un eje con sus épocas esperadas (series de Fresnel); y su test. (RF-2 a RF-7, constitución nº 2)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `StakeSource.Alignment`, `StakeoutRef` con `dChainageM` y `thicknessM`, `AxisRef` con `dHeightM` y `heightRef`, `Point.measured` con `AxisRef`. (RF-10, RF-13)
      Hecho cuando: los tests de `core:model` ampliados en verde.
- [x] T3. `core:survey`: `AxisStakeTarget` y `AxisStakeInputs` (punto de diseño, cota con espesor, sin rasante, PK fuera del eje con su inicio y final). (RF-1, RF-7, RF-9)
      Hecho cuando: `AxisStakeTargetTest` y `AxisStakeInputsTest` en verde contra `fixtures/ejes/replanteo/` a menos de 1 mm.
- [x] T4. `core:survey`: `KeyStations` y `AxisChainageSeries` (intervalo, PK final, singulares sin repetidos, hacia atrás, siguiente y anterior). (RF-2 a RF-4)
      Hecho cuando: `KeyStationsTest` y `AxisChainageSeriesTest` en verde.
- [x] T5. `core:survey`: `AxisGuidance` (adelante o atrás y dentro o fuera con el azimut del eje; desplazamiento 0). (RF-6)
      Hecho cuando: `AxisGuidanceTest` en verde contra `fixtures/ejes/replanteo/` en recta, arco y clotoide.
- [x] T6. `core:survey`: `AxisAnalysis` (PK, desplazamiento y diferencia de cota con la rasante o la superficie; sin proyección). (RF-8, RF-12, RF-14)
      Hecho cuando: `AxisAnalysisTest` en verde.
- [x] T7. `data`: Room v21 (cinco columnas de `points`), `AutoMigration(20, 21)` y el esquema 21 en `data/schemas/`. (RF-10, RF-13, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 20 → 21 en verde con proyectos, ejes y puntos que se conservan.
- [x] T8. `data`: guardar el punto replanteado por PK y el punto medido con su `AxisRef` en una transacción, y borrar el eje sin tocarlos. (RF-10, RF-13, RF-15 de S049)
      Hecho cuando: `AxisStakePointTest` y `AxisMeasuredPointTest` en verde (nombres de 60 caracteres o menos).
- [x] T9. `core:formats`: `StakeoutReportCsv` con `Alignment`, `Thickness` y `Delta chainage`. (RF-10)
      Hecho cuando: `StakeoutReportCsvTest` ampliado en verde.
- [x] T10. `app`: `AxisStakeoutViewModel` (eje activo, campos, serie, validaciones, cambio de eje que cambia el activo, cierre por borrado del eje) y su guía con el `StakeoutController`. (RF-1 a RF-3, RF-5, RF-7, RF-9, RF-11)
      Hecho cuando: `AxisStakeoutViewModelTest` en verde.
- [x] T11. `app`: `AxisStakeoutPanel` (campos, PK siguiente y anterior, adelante/atrás y dentro/fuera, Norte y Este, desmonte y terraplén, fuera del eje, TalkBack). (RF-1, RF-4, RF-6, RF-7, RF-9, RNF-T8)
      Hecho cuando: `AxisStakeoutPanelTest` en verde.
- [x] T12. `app`: «Replantear eje» en `AlignmentsSheet` y `AlignmentFicha`, el estado único `StakeMode` en `ProjectViewScreen` y el objetivo en el mapa. (RF-1, RF-11)
      Hecho cuando: `AlignmentsSheetTest`, `AlignmentFichaTest` y `AxisStakeoutPanelTest` ampliados en verde.
- [x] T13. `app`: medir y guardar el punto replanteado con el eje, el PK, el desplazamiento, la cota de diseño y las diferencias; la ficha del punto y el informe. (RF-10)
      Hecho cuando: `AxisStakeoutMeasureTest` y `PointDetailsScreenTest` ampliado en verde.
- [x] T14. `app`: reproducción que recorre un eje, guía época a época contra el cálculo aparte. (RF-6, RF-7, RNF)
      Hecho cuando: `AxisStakeoutReplayTest` en verde a menos de 1 mm.
- [x] T15. `app`: `AxisAnalysisViewModel` y `AxisAnalysisPanel` (PK, desplazamiento y diferencia de cota en vivo, referencia rasante o superficie, sin proyección). (RF-8, RF-12, RF-14)
      Hecho cuando: `AxisAnalysisViewModelTest` y `AxisAnalysisPanelTest` en verde.
- [x] T16. `app`: «Analizar» en `AlignmentsSheet` y `AlignmentFicha`, y «Medir» con el `AxisRef` y la diferencia de cota. (RF-12, RF-13)
      Hecho cuando: los mismos tests y `AxisMeasuredPointTest` en verde.
- [x] T17. Rendimiento: la guía de una época en un eje de 5.000 tramos en menos de 16 ms. (RNF-T13)
      Hecho cuando: `AxisStakeoutPerformanceTest` en verde.
- [x] T18. Validación: cambios en documentos anteriores (hoja de ruta, S017b a la v22), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
      Nota de la demo (28/10/2026, emulador, Room v20 → v21 sobre datos existentes): «Replantear eje» del eje A con PK 150, desplazamiento 3,5 y espesor 0,1 abre con «PK 0+150,00, a 3,500 m a la derecha del eje» y cota de diseño 102,683 m (igual que lo esperado de `tools`); con la reproducción `reproduccion-eje-a.nmea` la guía da «Adelante en PK» y «Hacia fuera del eje» en vivo; «Analizar» da el PK, el desplazamiento con su palabra y la diferencia de cota con la rasante, y «Medir» guarda el punto. Quedan para el usuario el avance de PK con intervalo y singulares en campo y el caso sin proyección.
