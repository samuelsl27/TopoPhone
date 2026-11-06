# Tareas — Spec 029

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/formatos`: `generar_dxf_exportado.py` con la tabla ACI, las coordenadas geográficas de unos puntos (pyproj) y un DXF de referencia partido en pares; y su test. (RF-6, RF-10 a RF-12, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/dxf-exportado/` y su test de `tools` está en verde.
- [x] T2. `core:formats`: `AciColor` (tabla de 255 colores y color más cercano). (RF-6)
      Hecho cuando: `AciColorTest` en verde contra `aci.csv`.
- [x] T3. `core:formats`: `DxfLayerNames` y `DxfNumberFormat`. (RF-5, RF-10)
      Hecho cuando: `DxfLayerNamesTest` y `DxfNumberFormatTest` en verde (acentos, 31 caracteres, repetidos, `Locale.ROOT`).
- [x] T4. `core:formats`: `DxfWriter` y `DxfCharset` (cabecera, capas, POINT, TEXT, POLYLINE/VERTEX/SEQEND, Windows-1252 con sustituidos). (RF-3, RF-4, RF-15)
      Hecho cuando: `DxfWriterTest` en verde.
- [x] T5. `core:formats`: `DxfDocumentBuilder` y `DxfExportSummary` (capas por código, etiquetas, Z, locales y globales, pies, selección, sin altura, sin posición). (RF-1 a RF-4, RF-7 a RF-9, RF-11 a RF-14, RF-20)
      Hecho cuando: `DxfDocumentBuilderTest` en verde.
- [x] T6. `core:formats`: ida y vuelta con el `DxfReader` de S040 y 10.000 puntos con dos etiquetas en menos de 5 s y menos de 10 MB. (RF-3, RF-4, RF-10, RNF)
      Hecho cuando: `DxfRoundTripTest` y `DxfExportPerformanceTest` en verde, y el dorado leído por `tools`.
- [x] T7. `data`: `DxfExportPreferences` (etiquetas, altura del texto y sistema, para todos los proyectos). (RF-20)
      Hecho cuando: `DxfExportPreferencesTest` en verde.
- [x] T8. `app`: `DxfExportViewModel` (puntos o selección, opciones, nombre del fichero, escribir en caché y copiar, resultado y resumen). (RF-1, RF-2, RF-8, RF-13, RF-16 a RF-21)
      Hecho cuando: `DxfExportViewModelTest` en verde.
- [x] T9. `app`: `DxfExportScreen` (recuento, casillas, altura, sistema, resumen, guardar y compartir, mensajes, 56 dp). (RF-7, RF-8, RF-13, RF-14, RF-17, RF-18, RF-20, RNF-T7, RNF-T8)
      Hecho cuando: `DxfExportScreenTest` en verde.
- [ ] T10. `app`: «Exportar DXF» en el menú del proyecto, en el de su lista de objetos y con una selección, y la navegación. (RF-1, RF-2)
      Hecho cuando: las pruebas de las tres entradas en verde.
- [ ] T11. Validación: hoja de ruta, recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files`, el `.dxf` abierto en QGIS con su herramienta y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos, QGIS da las mismas coordenadas, capas y colores y la demo funciona. La demo de campo queda para el usuario.
