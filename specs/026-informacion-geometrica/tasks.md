# Tareas — Spec 026

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `core:model`: `DistanceMode`, `AreaFormat`, `SlopeFormat` y los términos de `docs/glosario.md` («Información geométrica», «Segmento», «Perímetro», «Área», «Pendiente», «Ángulo entre tres puntos»). (RF-12, RNF-T9)
      Hecho cuando: `AreaFormatTest` y `SlopeFormatTest` en verde con m², ft² y usft².
- [x] T2. `tools`: `generar_referencias.py` con `pyproj` y `fixtures/geometria/` (rectas, polígono cóncavo y con cruces, ángulos; cuadrícula y terreno) y su test de `pytest`. (Constitución nº 2)
      Hecho cuando: `uv run --project tools pytest tools` en verde y los ficheros generados en el repositorio.
- [x] T3. `core:survey`: `GeometryVertex`, `PlanarFrame` y `GeometryCalculator` en cuadrícula: longitud 2D y 3D, azimut, desnivel, pendiente, PK, alturas que faltan y menos de 2 vértices. (RF-1, RF-2, RF-3, RF-7)
      Hecho cuando: `GeometryCalculatorGridTest` en verde frente a las referencias (≤ 1 mm, 1 µrad).
- [x] T4. `core:survey`: perímetro, área (Gauss) y `CrossingFinder`. (RF-1, RF-6)
      Hecho cuando: `GeometryAreaTest` y `CrossingFinderTest` en verde (≤ 1 mm²) con el polígono cóncavo y el de cruces.
- [x] T5. `core:survey`: `SegmentScale` con el factor combinado de S008 por segmento y del polígono, la altura del proyecto como recurso y el SC geográfico con plano local. (RF-10, RF-11)
      Hecho cuando: `GeometryCalculatorGroundTest` y `GeometryGeographicTest` en verde frente a las referencias.
- [x] T6. `core:survey`: `ThreePointAngle`. (RF-14, RF-15)
      Hecho cuando: `ThreePointAngleTest` en verde, con ángulos cercanos a 0° y 180° y puntos coincidentes.
- [x] T7. `core:formats`: `GeometryCsv`. (RF-8, RNF-T9)
      Hecho cuando: `GeometryCsvTest` en verde con `Locale.ROOT`, unidades en la cabecera y fila de totales.
- [x] T8. `data`: `GeometryPreferences` y su binding. (RF-9)
      Hecho cuando: `GeometryPreferencesTest` en verde con valor por defecto y persistencia.
- [x] T9. `data`: `VertexPoint` con altura y posición geodésica. (RF-3, RF-5)
      Hecho cuando: `VertexPointTest` en verde y los tests de S025 intactos.
- [x] T10. `app`: `GeometryInput` (desde `VertexPoint`) y `GeometryInfoViewModel`: modo recordado, cálculo en segundo plano, recalcular al editar un vértice y motivo con menos de 2 vértices. (RF-5, RF-7, RF-9)
      Hecho cuando: `GeometryInfoViewModelTest` en verde sin modificar ningún dato.
- [x] T11. `app`: `GeometryInfoScreen` (totales, selector Cuadrícula/Terreno, avisos, tabla monoespaciada) y los botones «Información» de la ficha de la línea. (RF-1, RF-2, RF-3, RF-6, RF-9, RF-11, RF-12)
      Hecho cuando: `GeometryInfoScreenTest` en verde, con `performScrollToNode` en la tabla.
- [x] T12. `app`: «Exportar tabla» con el selector de ficheros y el FileProvider. (RF-8)
      Hecho cuando: `GeometryExportTest` en verde.
- [ ] T13. `app`: información de una línea de un dibujo desde su hoja, con y sin cota. (RF-1, RF-4)
      Hecho cuando: `CadLineInfoTest` en verde.
- [ ] T14. `app`: «Ángulo entre tres puntos» en la lista de objetos y en la ficha de la línea. (RF-13, RF-14, RF-15)
      Hecho cuando: `ThreePointAngleTest` de `app` en verde.
- [ ] T15. `app`: rendimiento con 100.000 vértices, en cuadrícula y en terreno. (RNF-T13)
      Hecho cuando: `GeometryPerformanceTest` en verde (menos de 1 s, sin bloquear el hilo principal más de 16 ms).
- [ ] T16. Accesibilidad y textos: etiquetas, 56 dp, tabla monoespaciada, es/en y sin literales en código. (RNF-T7, RNF-T8)
      Hecho cuando: `GeometryAccessibilityTest` en verde.
- [ ] T17. Validación final: recorrer la spec RF por RF, `rf_report`, `check`, `pytest`, `forbidden_files` y la demo en el emulador.
      Hecho cuando: cada RF tiene un test en verde, `rf_report.py` no muestra RF huérfanos, la demo de la spec funciona y la hoja de ruta marca S026 como implementada.
