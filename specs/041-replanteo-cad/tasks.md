# Tareas — Spec 041

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. S040/RF-34: añadir RF-34 a `specs/040-capa-cad-dxf/spec.md` y la nota en su `plan.md` (se muestra el diff); `core:model`: `CadChunkCodec` con la marca de vértice original por bit y la lectura de un trozo antiguo con todo original. (S040/RF-34, RF-33, RF-34)
      Hecho cuando: `CadChunkCodecTest` en verde: ida y vuelta con marcas, un trozo sin el bit nuevo con todo original y un trozo de 256 kB; los tests de S040 siguen en verde.
- [x] T2. `core:formats`: `CadCurves`, `DxfBlocks` y `DxfReader` marcan los vértices originales y los de la aproximación de arcos, círculos, splines y bulges. (S040/RF-34, RF-33)
      Hecho cuando: `CadCurvesTest`, `DxfBlocksTest` y `DxfReaderTest` en verde: extremos de arco y de spline, punto de partida del círculo, vértices de bulge y bloques escalados.
- [x] T3. `core:survey` y `data`: `CadProjector` y `CadDrawingImporter` conservan las marcas al convertir y al partir una polilínea en trozos; `CadEntity.withVertices` y `slice`. (S040/RF-34, RF-34)
      Hecho cuando: `CadDrawingImporterTest` en verde: arco, spline, círculo y bulge con sus marcas tras importar, y una polilínea partida en trozos.
- [ ] T4. `tools/replanteo`: `generar_lineas.py` con los casos de `fixtures/replanteo/lineas/` (rectas, polilíneas, cerradas, antes y después, ambos lados, A–B e intervalos) y lo esperado con pyproj y con un cálculo geométrico aparte. (Constitución nº 2, RF-10, RF-11, RF-15, RF-18)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [ ] T5. `core:model`: `LineStakeoutMode`, `StakeSource`, `LineInputError` y las ampliaciones de `StakeoutRef` (origen, modo, PK, desplazamiento y error lateral). (RF-3, RF-28, RF-29)
      Hecho cuando: los tests de `core:model` en verde, con los valores por defecto (los puntos de S018 y S019 no cambian).
- [ ] T6. `core:survey`: `StakeLine`, el PK acumulado, las líneas cerradas y la cota interpolada. (RF-15, RF-23)
      Hecho cuando: `StakeLineTest` en verde con la línea abierta y cerrada, con y sin cota, y los vértices originales.
- [ ] T7. `core:survey`: `LineLocator`, el punto más cercano, el PK, la distancia lateral con signo, antes del inicio y después del final, con la rejilla de segmentos. (RF-10, RF-14, RF-15)
      Hecho cuando: `LineLocatorTest` en verde frente a `fixtures/replanteo/lineas/` (≤ 1 mm) con rectas, polilíneas, cerradas, ambos lados, extremos y 100.000 vértices.
- [ ] T8. `core:survey`: `LineObjective`, el objetivo de «A la línea», «A lo largo» y «Tramo A–B» con el desplazamiento y el error lateral. (RF-10, RF-11, RF-17, RF-23, RF-25)
      Hecho cuando: `LineObjectiveTest` en verde con desplazamientos a derecha e izquierda, esquinas, la cota interpolada y el extremo más cercano fuera del tramo.
- [ ] T9. `core:survey`: `LineSeries`, el tramo A–B, «Invertir», «Mi posición», los intervalos y todas las validaciones. (RF-16 a RF-21)
      Hecho cuando: `LineSeriesTest` en verde: B fuera de múltiplo, intervalo mayor que el tramo, 0 y negativo, A igual a B, PK fuera de la línea, no numérico y sentido inverso.
- [ ] T10. `core:survey`: `LineVertices`, los vértices de «Por segmentos» por tipo de línea, el siguiente y el anterior, y el aviso de un dibujo antiguo. (RF-6 a RF-9, RF-33, RF-34)
      Hecho cuando: `LineVerticesTest` en verde: polilínea, cerrada, arco, spline, círculo y un dibujo sin marcas.
- [ ] T11. `data`: columnas nuevas de `points`, sus DAO y `AutoMigration(13, 14)`. (RNF-T6, RF-28)
      Hecho cuando: `TopoPhoneDatabaseTest` 13 → 14 en verde: los proyectos, los puntos, los replanteos y los dibujos conservan sus datos.
- [ ] T12. `data`: `PointRepository` guarda y lee el replanteo de un elemento en una transacción; borrar el dibujo no toca el punto. (RF-27, RF-28, RF-32)
      Hecho cuando: `PointRepositoryTest` en verde: punto y replanteo juntos, nombres copiados, dibujo borrado después, y un punto del proyecto que no cambia.
- [ ] T13. `app`: `StakeTarget` con su origen y `StakeContext.line`; `StakeoutController` con el objetivo que se mueve, el rumbo respecto a un origen fijo y `LineGuidanceUi`. (RF-6, RF-10, RF-12, RF-13, RF-25, RF-26)
      Hecho cuando: `StakeoutControllerLineTest` en verde: el objetivo que se mueve, la diana, las zonas con el error lateral, los avisos y el apagado en todos los casos de S018; los tests de S018 y S019 siguen en verde.
- [ ] T14. `app`: la cota de diseño en las líneas: la interpolada, y la escrita a mano si el elemento o el dibujo no tienen cota. (RF-23, RF-24, RF-25)
      Hecho cuando: `StakeoutControllerLineTest` y `LineStakeoutViewModelTest` en verde con una línea con cota, otra sin cota y el nivel de S019.
- [ ] T15. `app`: `LineStakeoutViewModel`, parte 1: abrir un elemento, el modo recordado, los campos, las validaciones, los bloqueos con una medida en marcha y el cierre si se borra el dibujo. (RF-1 a RF-3, RF-16 a RF-22, RF-31)
      Hecho cuando: `LineStakeoutViewModelTest` en verde, con el modo que se recuerda entre proyectos y los campos que no.
- [ ] T16. `app`: `LineStakeoutViewModel`, parte 2: medir y guardar, el nombre `<capa>_<n>_stkd` y el replanteo con el origen, el modo, el PK y las diferencias. (RF-27, RF-28)
      Hecho cuando: `StakeoutMeasureLineTest` en verde: dos veces el mismo elemento, el objetivo de cuando se midió, el error lateral con su signo y un punto de dibujo con cota.
- [ ] T17. `app`: `LineStakeoutPanel`, los cinco modos, las listas de vértices y de puntos de la serie, los textos de RF-14 y las etiquetas de TalkBack, con sus textos en es y en. (RF-4, RF-8, RF-14, RF-20, RNF-T7, RNF-T8)
      Hecho cuando: `LineStakeoutPanelTest` en verde, con el lado y «Dentro de la tolerancia» en texto y las zonas táctiles de 56 dp.
- [ ] T18. `app`: «Replantear» en `DesignElementSheet`, la navegación a S018 para un punto y a la línea para una línea. (RF-1, RF-2)
      Hecho cuando: `DesignElementSheetTest` en verde: un punto, un centro de bloque, una línea, un círculo y un elemento sin cota.
- [ ] T19. `app`: el mapa con la línea elegida más gruesa, el objetivo y la línea de la punta. (RF-5)
      Hecho cuando: `LineMapMarkersTest` en verde, con la capa del dibujo oculta y el modo «Por segmentos».
- [ ] T20. `app`: la ficha del punto replanteado con el elemento, el modo, el PK y la desviación. (RF-29)
      Hecho cuando: `StakeoutMeasureLineTest` y el test de la ficha en verde con un punto de línea y un punto de dibujo.
- [ ] T21. `app`: el informe de replanteo con los puntos de un dibujo y las seis columnas nuevas del CSV. (RF-30)
      Hecho cuando: `StakeoutReportCsvTest` en verde: columnas vacías en un punto del proyecto, `Locale.ROOT` y unidades del proyecto.
- [ ] T22. Rendimiento: `LineStakeoutPerformanceTest`. (RNF)
      Hecho cuando: una línea de 100.000 vértices empieza a guiar en menos de 1 s, el punto más cercano a 10 Hz no pasa de 16 ms por consulta y la guía refleja una época en menos de 300 ms.
- [ ] T23. Reproducción sintética de un recorrido paralelo a una línea con desplazamiento y su resultado esperado calculado aparte. (Criterios de finalización)
      Hecho cuando: el test en verde con desplazamientos a derecha e izquierda y el PK y el error lateral esperados.
- [ ] T24. Copia, paquete y restauración con los puntos replanteados de un dibujo; revisión de los cambios en documentos anteriores (S018/RF-28, S019/RF-21, hoja de ruta). (RF-32, RNF-T6)
      Hecho cuando: `BackupCadTest` ampliado en verde y los documentos al día (se muestra el diff).
- [ ] T25. Validación: recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2b) quedan para el usuario.
