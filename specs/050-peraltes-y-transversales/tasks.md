# Tareas — Spec 050

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/ejes`: `generar_secciones.py` con `fixtures/ejes/secciones/`: sección tipo con bombeo, peralte en curva, giro en un borde, interpolación entre secciones explícitas, CSV y LandXML sintéticos y lo esperado en aritmética racional. (RF-9 a RF-12)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `SectionPoint`, `DesignSection`, `GroundProfile`, `SectionTemplate`, `Superelevation`, `SectionCode`, `SectionSlope`, `HeightKind`, `SectionKind` y los términos en `docs/glosario.md`. (RF-2, RF-3, RF-6)
      Hecho cuando: `SectionPolicyTest` en verde.
- [x] T3. `core:model`: `SectionCodec` (un bloque por sección tipo y por sección explícita, sin redondear). (RF-8)
      Hecho cuando: `SectionCodecTest` en verde (ida y vuelta exacta).
- [x] T4. `core:survey`: `SectionValidator` y `SuperelevationValidator` (códigos repetidos, desordenados, pendiente mayor de 100 %, PK fuera del eje, PK repetidos, rangos solapados). (RF-7)
      Hecho cuando: `SectionValidatorTest` y `SuperelevationValidatorTest` en verde.
- [ ] T5. `core:survey`: `SectionBuilder` (sección tipo, peralte lineal, una fila, fuera de la tabla, punto de rasante). (RF-2, RF-9)
      Hecho cuando: `SectionBuilderTest` en verde contra `fixtures/ejes/secciones/` a 1 mm y 1e-9.
- [ ] T6. `core:survey`: `SectionResolver` (explícita interpolada, tipo, códigos que faltan, fuera de rango, D1 y D4). (RF-3, RF-12)
      Hecho cuando: `SectionResolverTest` en verde.
- [ ] T7. `core:survey`: `SectionEval` (por código y por desplazamiento, pendiente, «Fuera de la sección», sin rasante). (RF-9 a RF-11)
      Hecho cuando: `SectionEvalTest` en verde contra `fixtures/ejes/secciones/` a 1 mm.
- [ ] T8. `core:formats`: `SectionCsvReader` y `SuperelevationCsvReader`. (RF-4, RF-6)
      Hecho cuando: `SectionCsvReaderTest` y `SuperelevationCsvReaderTest` en verde.
- [ ] T9. `core:formats`: `LandXmlCrossSectReader` (`scan` y `read`, diseño y terreno, `P1`, `P2`…, unidad, rechazos). (RF-5)
      Hecho cuando: `LandXmlCrossSectReaderTest` en verde.
- [ ] T10. `data`: Room v25 (tres tablas y `points.section_code`), `AutoMigration(24, 25)` y el esquema 25 en `data/schemas/`. (RF-8, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 24 → 25 en verde con proyectos, ejes y puntos que se conservan.
- [ ] T11. `data`: `SectionRepository` y `SectionImporter` (altas y bajas en una transacción, log sin nombres ni coordenadas, borrar el eje). (RF-8)
      Hecho cuando: `SectionRepositoryTest` en verde (nombres de 60 caracteres o menos).
- [ ] T12. `data`: copias de seguridad y paquetes con las tablas nuevas. (RF-8)
      Hecho cuando: `SectionBackupTest` en verde.
- [ ] T13. `core:survey`: `SectionStakeTarget` y `SectionStationSeries` (los dos órdenes, singulares de la sección y del peralte, sin repetidos). (RF-16, RF-17, RF-21)
      Hecho cuando: `SectionStakeTargetTest` y `SectionStationSeriesTest` en verde.
- [ ] T14. `core:survey`: `AxisAnalysis` con la referencia «Sección» en el desplazamiento real de la punta. (RF-20)
      Hecho cuando: `AxisAnalysisTest` ampliado en verde.
- [ ] T15. `app`: `SectionImportViewModel` (CSV de secciones, LandXML con lista, diseño o terreno, asignación de columnas, rechazos). (RF-4, RF-5)
      Hecho cuando: `SectionImportViewModelTest` en verde.
- [ ] T16. `app`: `SectionsViewModel` (sección tipo a mano, peralte a mano o por CSV, validaciones con punto y motivo). (RF-2, RF-3, RF-6, RF-7)
      Hecho cuando: `SectionsViewModelTest` en verde.
- [ ] T17. `app`: `SectionsScreen` y «Secciones» en `AlignmentsSheet` y `AlignmentFicha`. (RF-1, RNF-T7, RNF-T8)
      Hecho cuando: `SectionsScreenTest`, `AlignmentsSheetTest` y `AlignmentFichaTest` ampliados en verde.
- [ ] T18. `app` y `data`: `SectionPointViewModel` y su pantalla: calcular por código o por desplazamiento y «Guardar punto» como «Calculado sobre eje» con su código. (RF-9, RF-10, RF-11, RF-15)
      Hecho cuando: `SectionPointViewModelTest` y `SectionPointTest` en verde.
- [ ] T19. `app`: «Ver sección» (alzado con códigos, desplazamientos, cotas y perfil de terreno). (RF-13)
      Hecho cuando: `SectionViewScreenTest` en verde.
- [ ] T20. `app`: líneas por código en el mapa, activables desde la ficha. (RF-14)
      Hecho cuando: `AlignmentSceneTest` ampliado en verde.
- [ ] T21. `app`: el modo «Por código» en `AxisStakeoutViewModel` (códigos, orden de recorrido, serie, motivos de no abrir). (RF-16, RF-17, RF-21)
      Hecho cuando: `AxisStakeoutViewModelTest` ampliado en verde.
- [ ] T22. `app`: el modo «Por código» en `AxisStakeoutPanel` (guía, desmonte o terraplén con cota de la sección y espesor, desplazamiento, cota y pendiente al cambiar de objetivo). (RF-18, RNF-T8)
      Hecho cuando: `AxisStakeoutPanelTest` ampliado en verde.
- [ ] T23. `app` y `core:formats`: guardar el punto replanteado por código con su código de sección y la columna `Section code` del informe de replanteo. (RF-19)
      Hecho cuando: `SectionStakeMeasureTest` y `StakeoutReportCsvTest` ampliado en verde.
- [ ] T24. `app`: la referencia «Sección» en `AxisAnalysisViewModel` y `AxisAnalysisPanel`. (RF-20)
      Hecho cuando: `AxisAnalysisViewModelTest` ampliado en verde.
- [ ] T25. `app`: reproducción que recorre el borde derecho de un eje con peralte, época a época contra el cálculo aparte. (RF-18, RNF-T13)
      Hecho cuando: `SectionStakeReplayTest` en verde a menos de 1 mm.
- [ ] T26. Rendimiento: un LandXML con 2.000 secciones se importa en menos de 5 s y la guía de una época por código no pasa de 16 ms.
      Hecho cuando: `SectionImportPerformanceTest` y `SectionStakePerformanceTest` en verde.
- [ ] T27. Validación: cambios en documentos anteriores (hoja de ruta, Room v25), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
