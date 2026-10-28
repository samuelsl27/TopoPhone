# Tareas — Spec 049

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/ejes`: `generar_ejes.py` con LandXML sintéticos (rectas, arcos, clotoides de entrada y salida, curva en S, PK inicial ≠ 0, rasante con acuerdos parabólicos y circulares, tipos no admitidos, hueco, `StaEquation`), un CSV de rasante y lo esperado por PK y desplazamiento con series de Fresnel y fórmulas analíticas; y su test. (RF-2 a RF-5, RF-9, RF-20, constitución nº 2)
      Hecho cuando: el script genera `fixtures/ejes/` y su test de `tools` está en verde.
- [x] T2. `core:model`: `Alignment`, `AlignmentElement`, `VerticalProfile`, `AlignmentCodec`, `AlignmentSource`, `ChainageFormat` y `OffsetFormat`; los términos nuevos en `docs/glosario.md`. (RF-13, RNF-T7, RNF-T9)
      Hecho cuando: `AlignmentCodecTest` y `ChainageFormatTest` en verde.
- [x] T3. `core:survey`: `Clothoid` y `AlignmentGeometry.pointAt` (recta, arco y clotoide, PK inicial, desplazamiento a ambos lados). (RF-4, RF-20)
      Hecho cuando: `AlignmentGeometryTest` en verde contra `fixtures/ejes/` a menos de 1 mm.
- [x] T4. `core:survey`: `AlignmentGeometry.locate` (PK y desplazamiento de una posición, también en clotoides). (RF-18, RF-21)
      Hecho cuando: `AlignmentLocateTest` en verde a menos de 1 mm y ida y vuelta con `pointAt`.
- [x] T5. `core:survey`: `VerticalProfileEval` (cota y pendiente con parábola, círculo y vértices). (RF-18, RF-20)
      Hecho cuando: `VerticalProfileEvalTest` en verde a menos de 1 mm.
- [x] T6. `core:survey`: `AlignmentValidator` y `ProfileValidator`. (RF-5, RF-10)
      Hecho cuando: `AlignmentValidatorTest` y `ProfileValidatorTest` en verde con el tramo o el vértice y el motivo.
- [x] T7. `core:survey`: `PiAlignmentBuilder` (vértices con radio y clotoides) y rasante a mano con Kv (D4). (RF-8)
      Hecho cuando: `PiAlignmentBuilderTest` en verde contra `tools`.
- [x] T8. `core:survey`: `PolylineAlignmentBuilder` (inicio en el extremo más cercano, reconstrucción de arcos de D1, rasante sin acuerdos). (RF-6, RF-7)
      Hecho cuando: `PolylineAlignmentBuilderTest` en verde con arcos, una spline rechazada y una polilínea sin cotas.
- [x] T9. `core:formats`: `LandXmlAlignmentReader.scan` (alineaciones, longitud, PK inicial, rasante, unidad; sin alineaciones, no válido y más de 100 MB). (RF-2, RF-3)
      Hecho cuando: `LandXmlAlignmentReaderTest` en verde para esos casos.
- [x] T10. `core:formats`: `LandXmlAlignmentReader.read` (tramos, rasante, orden Norte-Este, tipos no admitidos y D3). (RF-4, RF-5, RF-10)
      Hecho cuando: el mismo test en verde y los ejes de `tools` dan sus tramos.
- [x] T11. `core:formats`: `ProfileCsvReader` (columnas PK, cota y Kv con las opciones de S028). (RF-9)
      Hecho cuando: `ProfileCsvReaderTest` en verde.
- [x] T12. `core:survey`: `AlignmentProjector` (unidad, ámbito y SC distinto por muestreo, D2). (RF-11, RF-12)
      Hecho cuando: `AlignmentProjectorTest` en verde contra pyproj (≤ 1 mm).
- [x] T13. `data`: Room v20 (`alignments`, `active_alignment_id` y las tres columnas de `points`), `AutoMigration(19, 20)`, `PointOrigin.ON_ALIGNMENT` y la tabla en `DatabaseSnapshot.TABLES`. (RF-14, RF-16, RF-22, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 19 → 20 en verde con proyectos y puntos que se conservan, y el esquema 20 en `data/schemas/`.
- [x] T14. `data`: `AlignmentRepository` y `AlignmentImporter` (una transacción, varios ejes, el primero activo, renombrar, mostrar u ocultar, borrar sin tocar puntos, resumen). (RF-13 a RF-15, RF-19)
      Hecho cuando: `AlignmentRepositoryTest` y `AlignmentImporterTest` en verde, incluido fallar sin dejar nada.
- [x] T15. `data`: copias de seguridad, paquetes y log sin nombres ni coordenadas. (RF-16)
      Hecho cuando: `AlignmentBackupTest` en verde.
- [x] T16. `data`: guardar un punto calculado sobre el eje con su PK y desplazamiento, en una transacción. (RF-22)
      Hecho cuando: `PointOnAxisTest` en verde.
- [x] T17. `app`: «Importar eje» en los dos menús, `AlignmentImportViewModel` y pantalla con el origen LandXML (lista de alineaciones, rechazos y selección). (RF-1 a RF-3)
      Hecho cuando: `AlignmentImportViewModelTest` y `AlignmentImportScreenTest` en verde para esos casos.
- [x] T18. `app`: los orígenes polilínea de un dibujo y CSV de rasante. (RF-6, RF-7, RF-9, RF-10)
      Hecho cuando: los mismos tests en verde para esos casos.
- [x] T19. `app`: definición a mano del eje y de la rasante. (RF-8)
      Hecho cuando: `ManualAlignmentTest` en verde.
- [x] T20. `app`: sistema y unidad del eje, aviso de D2 y resumen de lo importado e ignorado. (RF-11 a RF-13)
      Hecho cuando: los mismos tests en verde.
- [x] T21. `app`: `AlignmentsSheet` (eje activo, renombrar, borrar con confirmación, mostrar u ocultar y recordarlo). (RF-14, RF-15, RF-19)
      Hecho cuando: `AlignmentsSheetTest` en verde.
- [x] T22. `app`: los ejes en el mapa con el activo destacado y las marcas rotuladas de PK. (RF-17, RNF-T13)
      Hecho cuando: `ChainageMarksTest` y `AlignmentSceneTest` en verde.
- [x] T23. `app`: la ficha al tocar un eje, con el PK del punto tocado y la cota de la rasante, y la opción de activarlo. (RF-18)
      Hecho cuando: `AlignmentFichaTest` en verde.
- [ ] T24. `app`: «Punto sobre eje» (PK y desplazamiento, resultado, PK fuera del eje y «Guardar punto»). (RF-20 a RF-22, RNF-T8)
      Hecho cuando: `AlignmentPointViewModelTest` en verde.
- [ ] T25. Rendimiento: LandXML de 100 MB con un eje de 50 km en menos de 10 s y el dibujo del eje en menos de 16 ms. (RNF)
      Hecho cuando: los tests de rendimiento en verde.
- [ ] T26. Validación: cambios en documentos anteriores (hoja de ruta, glosario, S017b a la v21), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
