# Tareas — Spec 053

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/formatos`: `generar_gpkg_exportado.py` con las definiciones WKT de los sistemas (pyproj), las coordenadas geográficas esperadas de unos puntos en cada sistema y un `.gpkg` de referencia leído con `sqlite3`; y su test. (RF-5 a RF-7, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/gpkg-exportado/` y su test de `tools` está en verde.
- [x] T2. `core:formats`: `GpkgAttributeType` y `GpkgPointAttributes` (tipo y valor tipado sin redondear de cada columna, vacío lo que no hay). (RF-4, RF-5)
      Hecho cuando: `GpkgPointAttributesTest` en verde, incluido cada valor frente a la celda del CSV.
- [x] T3. `core:formats`: `GpkgGeometryWriter` (blob con `Point` y `Point Z`, geometría vacía). (RF-5)
      Hecho cuando: `GpkgGeometryWriterTest` en verde con ida y vuelta por `GpkgGeometryReader`.
- [x] T4. `core:formats`: `GpkgExportSchema` y `GpkgSrsDefinitions` (tablas, filas obligatorias, SC con su WKT y la definición completa sin código). (RF-6, RF-8)
      Hecho cuando: `GpkgExportSchemaTest` y `GpkgSrsDefinitionsTest` en verde contra `esperado-srs.csv`.
- [x] T5. `data`: `GeoPackageWriter` (fichero temporal, una transacción, sistema del proyecto o geográficas, selección, sin puntos, fallo sin dejar fichero, punto sin Este y Norte con geometría vacía, el punto de extensión de capas). (RF-1 a RF-8, RF-11 a RF-13)
      Hecho cuando: `GeoPackageWriterTest` en verde, con el fichero abierto por el `GeoPackageReader` de S048.
- [x] T6. `data`: el `.gpkg` dorado de cada caso y 10.000 puntos en menos de 5 s. (RF-5 a RF-7, RNF)
      Hecho cuando: `GeoPackageWriterPerformanceTest` en verde y los dorados leídos por el test de `tools`.
- [x] T7. `app`: `GpkgExportViewModel` (puntos o selección, sistema, nombre del fichero, escribir en la caché y copiar, resultado y resumen). (RF-1, RF-2, RF-7, RF-9 a RF-13)
      Hecho cuando: `GpkgExportViewModelTest` en verde.
- [x] T8. `app`: `GpkgExportScreen` (recuento, selector de sistema, resumen, guardar y compartir, mensajes, 56 dp). (RF-7, RF-10, RF-11, RF-12, RNF-T7, RNF-T8)
      Hecho cuando: `GpkgExportScreenTest` en verde.
- [ ] T9. `app`: «Exportar GeoPackage» en el menú del proyecto, en el de su lista de objetos y con una selección, y la navegación. (RF-1, RF-2)
      Hecho cuando: las pruebas de las tres entradas en verde.
- [ ] T10. Validación: cambios en documentos anteriores (hoja de ruta, glosario), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files`, el `.gpkg` abierto en QGIS con su herramienta y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos, QGIS da los mismos puntos, coordenadas y atributos y la demo funciona. La demo de campo queda para el usuario.
