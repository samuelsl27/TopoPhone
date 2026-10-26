# Tareas — Spec 040

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/formatos`: `generar_dxf.py` con los ficheros de `fixtures/formatos/dxf/` (R12, R2000, R2010 y R2018; con y sin bloques anidados, arcos, círculos, splines y polilíneas con bulge; capas apagadas y congeladas; un DWG y un DXF binario) y lo esperado de cada uno, con las conversiones de sistema (ETRS89, REGCAN95 y ED50, otro huso) calculadas con pyproj y los puntos exactos de las curvas. (RF-2 a RF-4, RF-6, RF-14, RF-15, RF-33)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `CadDrawing`, `DesignLayer`, `DesignLayerKind`, `CadEntityKind`, `CadEntityRef`, `CadColor`, `CadImportOptions`, `CadSourceCrs`, `CadImportSummary` y `CadIgnoredReason`. (RF-7, RF-8, RF-16, RF-18, RF-23)
      Hecho cuando: los tests de `core:model` en verde, con los valores por defecto de la spec (sistema del proyecto, usar las cotas).
- [x] T3. `core:model`: `CadChunkCodec`, los elementos de un trozo a bytes y al revés. (RF-19, RF-22, RF-23)
      Hecho cuando: `CadChunkCodecTest` en verde: cada tipo, con y sin Z y con color, sin redondear, y un trozo de 256 kB.
- [x] T4. `core:formats`: `DxfFileKind`, el tipo de fichero por su contenido y el límite de 100 MB. (RF-2, RF-3, RF-4, RF-5)
      Hecho cuando: `DxfFileKindTest` en verde con DWG renombrado, DXF binario, vacío, texto cualquiera, las versiones R12 a R2018 y más de 100 MB.
- [x] T5. `core:formats`: `DxfTagReader` y `DxfHeader`, los pares código/valor por flujo, la codificación y la cabecera. (RF-2, RF-7, RNF-T9)
      Hecho cuando: `DxfTagReaderTest` en verde: CRLF, LF y CR, punto decimal con otra región, UTF-8 y página de códigos, nombres con acentos, valor que no es número, y la unidad declarada.
- [x] T6. `core:formats`: `DxfTables` y `AciPalette`, las capas con su color y su estado, y la resolución del color de un elemento. (RF-24, RF-25, RF-33)
      Hecho cuando: `DxfTablesTest` en verde: ACI, color verdadero, BYLAYER, BYBLOCK, capa `0` en un bloque, capa apagada y congelada, y el blanco y el negro marcados.
- [x] T7. `core:formats`: `DxfEntities`, punto, línea, LWPOLYLINE, POLYLINE con VERTEX y SEQEND, arco y círculo, con la extrusión. (RF-14)
      Hecho cuando: `DxfEntitiesTest` en verde, con bulge, polilíneas cerradas, 3D y la extrusión (0, 0, −1) frente a lo esperado de `fixtures/`.
- [x] T8. `core:formats`: `CadCurves`, arcos, círculos, bulges y splines como polilíneas a menos de 1 cm. (RF-15)
      Hecho cuando: `CadCurvesTest` en verde frente a los puntos exactos de `tools`, con un radio muy grande, uno menor que la tolerancia y una spline racional.
- [x] T9. `core:formats`: `DxfBlocks`, el despliegue de los INSERT con escala, giro, anidados y `MINSERT`. (RF-14, RF-15)
      Hecho cuando: `DxfBlocksTest` en verde: escala negativa, ciclos, profundidad máxima, capa `0` y BYBLOCK, y el punto de inserción.
- [x] T10. `core:formats`: `DxfReader` y `DxfSink`, el recorrido completo, los ignorados por tipo y el fichero cortado. (RF-13, RF-14, RF-16)
      Hecho cuando: `DxfReaderTest` en verde con los fixtures R12, R2000, R2010 y R2018, un fichero cortado, un elemento roto y un fichero sin ningún elemento.
- [x] T11. `core:survey`: `CadProjector`, de las coordenadas del dibujo a las del proyecto. (RF-6, RF-7, RF-17, RF-21)
      Hecho cuando: `CadProjectorTest` en verde frente a pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm) en ETRS89, REGCAN95 y ED50 y otro huso, con metros, pies y pies topográficos, fuera del ámbito y la altura sin convertir.
- [x] T12. `core:survey`: `DesignHitTest`, el elemento más cercano a un toque. (RF-29)
      Hecho cuando: `DesignHitTestTest` en verde: tolerancia, vértice y segmento, y los puntos del proyecto con preferencia.
- [x] T13. `data`: las tablas `cad_drawings`, `cad_layers` y `cad_chunks`, sus DAO y `AutoMigration(12, 13)`. (RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 12 → 13 en verde: los proyectos, puntos y replanteos conservan sus datos.
- [x] T14. `data`: `CadRepository`, la escritura por tandas con `IMPORTING` y `READY`, la limpieza al arrancar, la lectura por zona, la visibilidad y el borrado. (RF-12, RF-19, RF-27, RF-28, RF-30)
      Hecho cuando: `CadRepositoryTest` en verde: lo no terminado no se ve, la visibilidad persiste, borrar un dibujo o un proyecto no deja trozos y los puntos no se tocan.
- [x] T15. `data`: `CadDrawingImporter`, leer, convertir, agrupar por capa de diseño y zona, escribir, cancelar y resumir. (RF-11 a RF-13, RF-15, RF-17 a RF-23, RF-33)
      Hecho cuando: `CadDrawingImporterTest` en verde: todo o nada, cancelar, fallo de escritura, fichero cortado, capas por tipo, cota con «Sí», con «No» y con todo a 0, capas apagadas, resumen y el mismo fichero dos veces.
- [x] T16. `data`: las tablas del dibujo en `DatabaseSnapshot.TABLES` para la copia y el paquete. (RF-31)
      Hecho cuando: `BackupCadTest` en verde: la copia y el paquete conservan los dibujos, sus capas y su visibilidad, y la restauración los devuelve.
- [x] T17. `app`: `CadImportViewModel`, parte 1: leer el tipo de fichero, explorar, y calcular la extensión, la distancia y los avisos. (RF-1 a RF-10, RF-20)
      Hecho cuando: `CadImportViewModelTest` en verde con un `ContentResolver` falso: DWG, binario, vacío, grande, exploración, cambio de sistema y de unidad, aviso de lejanía y nombre repetido.
- [x] T18. `app`: `CadImportViewModel`, parte 2: importar, el progreso, cancelar, el resumen, guardarlo y el log. (RF-11, RF-12, RF-13, RF-18, RF-32)
      Hecho cuando: los tests en verde: progreso, cancelar, fallo de escritura, dibujo incompleto, resumen y log sin nombres ni coordenadas.
- [x] T19. `app`: `CadImportScreen`, la configuración (sistema, unidad, cotas), la extensión con sus avisos, el progreso con «Cancelar» y el resumen, con sus textos en es y en. (RF-6 a RF-10, RF-13, RF-18)
      Hecho cuando: `CadImportScreenTest` en verde, con las etiquetas de TalkBack y el estado con icono y texto.
- [x] T20. `app`: «Importar DXF» en el menú del proyecto y en el de la lista de objetos, la navegación y el selector de ficheros. (RF-1)
      Hecho cuando: `CadImportEntriesTest` en verde, con el aviso de cada fichero que no se puede abrir.
- [x] T21. `app`: `MapController.showDesign`, `DesignSceneBuilder` y las capas de MapLibre: lo que cae en la vista, la simplificación por zoom y el color de contraste. (RF-24, RF-25, RF-27)
      Hecho cuando: `DesignSceneBuilderTest` en verde con capas ocultas, el recorte a la vista, el umbral por zoom y el blanco y el negro; el doble del mapa recoge la escena.
- [x] T22. `app`: el panel «Dibujos» del mapa con las capas por tipo, sus interruptores, y el borrado de un dibujo. (RF-26, RF-28, RF-30)
      Hecho cuando: `DesignLayersPanelTest` en verde: capas con el mismo nombre de dos dibujos, la visibilidad que se recuerda y el borrado con confirmación sin tocar los puntos.
- [x] T23. `app`: el toque en el mapa y la ficha del elemento. (RF-23, RF-29)
      Hecho cuando: `DesignElementSheetTest` en verde con un punto, una línea y un elemento sin cota.
- [x] T24. Rendimiento: `CadPerformanceTest`. (RNF)
      Hecho cuando: un DXF sintético de 100 MB se explora en menos de 20 s y se importa en menos de 60 s sin pasar de 256 MB, el proyecto lo muestra en menos de 3 s y reconstruir la escena tarda menos de 200 ms.
- [x] T25. Validación: recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2b) quedan para el usuario.
