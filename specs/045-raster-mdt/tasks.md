# Tareas — Spec 045

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/raster`: `generar_rasters.py` con un escritor TIFF en Python puro y los casos de `fixtures/raster/` (GeoTIFF sin comprimir, LZW y Deflate con y sin predictor, por tiras y por teselas, int16, int32, float32 y float64, celda como área y como punto, BigTIFF, girado, de color, multibanda y sin georreferencia; ASCII Grid con esquina y con centro) y los valores esperados con `pyproj` y una bilineal aparte (centro de celda, medio camino, borde, celda sin dato, otro SC, pies y el geoide en los dos sentidos). (Constitución nº 2, RF-7 a RF-10, RF-21 a RF-23)
      Hecho cuando: el script genera los ficheros y su test de `tools` está en verde.
- [x] T2. `core:model`: `SurfaceKind`, `RasterInfo`, `Surface` ampliada, `RasterTile` y `RasterTileCodec` (float32 y float64 sin redondear, `NaN` sin dato). (RF-14, RF-17)
      Hecho cuando: los tests de `core:model` en verde, con los valores por defecto (las superficies TIN no cambian).
- [x] T3. `core:formats`: `RasterSource`, `RasterSink`, `TiffDecoder` (sin comprimir, LZW, Deflate, predictores 2 y 3, big y little endian). (RF-2)
      Hecho cuando: `TiffDecoderTest` en verde con los bloques de `fixtures/raster/`.
- [ ] T4. `core:formats`: `GeoTiffRasterReader` (`scan` y lectura por bandas, georreferencia, `PixelIs*`, sin dato, unidad de la cota, tipos de muestra, tiras y teselas, rechazos). (RF-2 a RF-4, RF-6, RF-7, RF-9)
      Hecho cuando: `GeoTiffRasterReaderTest` en verde con cada rechazo y cada caso de `fixtures/raster/`.
- [ ] T5. `core:formats`: `AsciiGridReader`, `RasterReader` (reconocer por contenido) y `RasterCrsResolver`. (RF-2, RF-3, RF-7, RF-8)
      Hecho cuando: `AsciiGridReaderTest`, `RasterReaderTest` y `RasterCrsResolverTest` en verde.
- [ ] T6. `core:survey`: `RasterTiler` y `RasterExtent`. (RF-11, RF-12, RF-14, RF-16)
      Hecho cuando: `RasterTilerTest` y los tests de la extensión en verde.
- [ ] T7. `core:survey`: `RasterElevation`: de la posición del proyecto a la cota del ráster, con el cambio de SC, la bilineal estricta, la unidad y la conversión con el geoide. (RF-9, RF-10, RF-21 a RF-23)
      Hecho cuando: `RasterElevationTest` en verde contra `fixtures/raster/` a menos de 1 mm.
- [ ] T8. `data`: Room v22 (columnas de `surfaces`, `raster_tiles`), sus DAO, `AutoMigration(21, 22)` y el esquema 22 en `data/schemas/`. (RNF-T6, RF-14)
      Hecho cuando: `TopoPhoneDatabaseTest` 21 → 22 en verde: proyectos, superficies, puntos y replanteos conservan sus datos.
- [ ] T9. `data`: `SurfaceRepository` con `beginRaster`, `addRasterTiles` y `finishRaster`, la lista con el tipo, y activar, borrar y la limpieza para los rásteres. (RF-13, RF-17, RF-18)
      Hecho cuando: `RasterRepositoryTest` en verde: activar un ráster con un TIN activo y a la inversa, borrar el activo y el desplazamiento que se conserva.
- [ ] T10. `data`: `RasterImporter`, `scan` e `import` por bandas con el temporal en la caché, el progreso, la cancelación, el límite de tamaño y de anchura y los fallos. (RF-3, RF-5, RF-7, RF-13 a RF-16)
      Hecho cuando: `RasterImporterTest` en verde: cada formato, cancelar sin dejar nada ni el temporal, fallo de escritura, sin celdas con dato y los límites.
- [ ] T11. `data`: `ActiveRaster` y el `ActiveSurfaceProvider` que elige la fuente por tipo, con la caché, `Loading`, la precarga y el vaciado al desactivar. (RF-17, RF-21, RF-22)
      Hecho cuando: `ActiveRasterTest` en verde y `ActiveSurfaceTest` de S042 sigue en verde.
- [ ] T12. `data`: copia de seguridad, paquete y restauración con los rásteres, con el aviso del espacio. (RF-20, RNF-T6)
      Hecho cuando: `RasterBackupTest` en verde: copia completa, paquete con identificadores nuevos y restauración, con el activo y el desplazamiento.
- [ ] T13. `app`: `RasterImportViewModel`: pasos, rechazos, SC, unidad, sistema de alturas, extensión y lejanía, nombre repetido, cancelar. (RF-1 a RF-12, RF-15)
      Hecho cuando: `RasterImportViewModelTest` en verde.
- [ ] T14. `app`: `RasterImportScreen`, `RasterImportRoute` y «Importar ráster» en los menús, con sus textos en es y en. (RF-1, RF-6, RF-11, RF-13, RF-16, RNF-T7, RNF-T8)
      Hecho cuando: `RasterImportScreenTest` en verde, con las zonas táctiles de 56 dp y el resumen.
- [ ] T15. `app`: `SurfacesSheet` y `SurfacesViewModel` con el tipo, el tamaño de celda, la extensión y las cotas del ráster. (RF-17, RF-18)
      Hecho cuando: `SurfacesSheetTest` ampliado en verde.
- [ ] T16. `app`: el ráster en el replanteo y el recolector sin tocar su código: `StakeoutControllerRasterTest`, `StakeoutViewModelRasterTest`, `StakeoutMeasureRasterTest`, `CollectorRasterTest` y la ficha y el informe con un ráster. (RF-19, RF-24, RF-25)
      Hecho cuando: esos tests en verde y los de S018, S019, S041 y S042 siguen en verde.
- [ ] T17. Rendimiento: `RasterPerformanceTest`. (RNF)
      Hecho cuando: un ráster de 250 MB generado al vuelo se importa en menos de 180 s sin quedarse sin memoria, la primera cota tras activarlo tarda menos de 1 s y la consulta a 10 Hz no pasa de 16 ms.
- [ ] T18. Reproducción sintética `rs3-raster`: un recorrido que cruza un ráster con un hueco sin dato y su resultado esperado calculado aparte. (Criterios de finalización)
      Hecho cuando: el test en verde con la cota, el desmonte o terraplén esperados y el paso por «Fuera de la superficie».
- [ ] T19. Validación: cambios en documentos anteriores (hoja de ruta con S017b a la v23, glosario), recorrer la spec RF por RF, `rf_report`, `pytest tools`, `forbidden_files`, ficheros de GDAL comprobados con QGIS (`sample_raster_values`, bilineal) y el trozo de MDT05, y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. Las pruebas de campo (puerta G2b) quedan para el usuario.
