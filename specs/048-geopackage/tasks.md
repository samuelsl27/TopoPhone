# Tareas — Spec 048

> Una tarea por prompt: tests primero, luego código; marca la casilla y para. Al terminar cada una: `./gradlew check` (y `uv run --project tools pytest tools` si se toca `tools/`).

- [x] T1. `tools/formatos`: `generar_geopackage.py` con los `.gpkg` de ejemplo (puntos, líneas, polígonos con huecos, múltiples, EPSG 25830, 4258, 4326 y 3857, con y sin R-tree, con altura, tabla sin geometría, tabla de teselas, dañado y no GeoPackage) y lo esperado con pyproj; y su test. (RF-2 a RF-9, RF-17, RF-19, constitución nº 2)
      Hecho cuando: el script genera `fixtures/formatos/gpkg/` y su test de `tools` está en verde.
- [x] T2. `core:model`: `CadSourceCrs.Geographic`, `CadOrigin`, `ImportArea`, `GpkgLayerInfo`, `GpkgImportSummary`; los términos nuevos en `docs/glosario.md`. (RF-8, RF-13, RF-26)
      Hecho cuando: `ImportAreaTest` en verde (radio de 100 m a 100 km, «Todo el contenido») y `core:model` compila con los usos adaptados.
- [x] T3. `core:model`: `CadChunkCodec` con las marcas `NAME` y `POLYGON_RING`. (RF-17, RF-22)
      Hecho cuando: `CadChunkCodecTest` en verde con nombre y contorno, y los trozos de S040 se leen igual.
- [ ] T4. `core:formats`: `GpkgFileKind` (cabecera SQLite y 100 MB). (RF-2, RF-3, RF-4)
      Hecho cuando: `GpkgFileKindTest` en verde: GeoPackage, SQLite que no lo es, texto, vacío, dañado y más de 100 MB.
- [ ] T5. `core:formats`: `GpkgGeometry` (cabecera del blob y WKB ISO y EWKB, Z/M/ZM, múltiples, vacío y curvas). (RF-17, RF-18, RF-19)
      Hecho cuando: `GpkgGeometryTest` en verde contra los blobs de `tools`.
- [ ] T6. `core:formats`: `GpkgCrsCatalog` (EPSG a `CadSourceCrs`, unidad y «no disponible»; WGS 84 como ETRS89 con aviso). (RF-8, RF-9, RF-10)
      Hecho cuando: `GpkgCrsCatalogTest` en verde contra la lista de pyproj.
- [ ] T7. `core:survey`: `CadProjector` con origen geográfico y motivos de ignorado por vértice. (RF-8, RF-11)
      Hecho cuando: `CadProjectorGeographicTest` en verde (≤ 1 mm con pyproj, ida y vuelta < 0,1 mm) y los tests de S040 sin cambios.
- [ ] T8. `core:survey`: `ImportAreaFilter` (recuadro en el SC de la capa, distancia, algún vértice dentro). (RF-13, RF-15)
      Hecho cuando: `ImportAreaFilterTest` en verde.
- [ ] T9. `data`: Room v19 (`origin`, `source_table`, `name_field` y `cad_chunk_attributes`) y `AutoMigration(18, 19)`; las tablas nuevas en `DatabaseSnapshot.TABLES`. (RF-20, RF-26, RF-28, RNF-T6)
      Hecho cuando: `TopoPhoneDatabaseTest` 18 → 19 en verde con dibujos de DXF que se leen como `DXF`, y el esquema 19 en `data/schemas/`.
- [ ] T10. `data`: `GeoPackageReader` (copia temporal, solo lectura, capas, columnas, tablas ajenas y limpieza). (RF-1 a RF-7)
      Hecho cuando: `GeoPackageReaderTest` en verde con los `.gpkg` de `tools`, sin dejar el temporal.
- [ ] T11. `data`: `GeoPackageScan` (extensión, distancia y recuento por capa para un radio, con R-tree o con envolventes). (RF-12, RF-13, RF-16)
      Hecho cuando: `GeoPackageScanTest` en verde, incluida la capa sin R-tree y el límite de 500.000.
- [ ] T12. `data`: `GeoPackageImporter` (reproyección, zona, polígonos como líneas, cotas, nombre, atributos, tandas, cancelar y fallar sin dejar nada). (RF-11, RF-15 a RF-22, RF-26, RF-29, RF-30)
      Hecho cuando: `GeoPackageImporterTest` en verde con el resumen por capa y los valores contra pyproj.
- [ ] T13. `data`: `CadRepository.attributes` y las copias de seguridad y los paquetes con las tablas nuevas. (RF-20, RF-28)
      Hecho cuando: `CadRepositoryAttributesTest` y `GpkgBackupTest` en verde.
- [ ] T14. `app`: «Importar GeoPackage» en los dos menús, `GpkgImportViewModel` y la pantalla con el fichero, sus errores y la lista de capas marcables. (RF-1 a RF-7)
      Hecho cuando: `GpkgImportViewModelTest` y `GpkgImportScreenTest` en verde para esos casos.
- [ ] T15. `app`: sistema de coordenadas de cada capa (propuesto, elegido, WGS 84), extensión, distancia y aviso de 100 km. (RF-8 a RF-12)
      Hecho cuando: los mismos tests en verde para esos casos.
- [ ] T16. `app`: zona de importación (radio, «Todo el contenido», recuento, límite) y «Fijar el punto central» con el selector de S006 si el proyecto no tiene centro. (RF-13, RF-14, RF-16)
      Hecho cuando: los mismos tests en verde, con el recuento actualizado en menos de 2 s.
- [ ] T17. `app`: campo de nombre, cotas, «Importar» con progreso y cancelar, y resumen por capa con guardar y compartir. (RF-19, RF-21, RF-22, RF-25, RF-29, RF-30)
      Hecho cuando: los mismos tests en verde y el resumen se guarda como texto.
- [ ] T18. `app`: las capas en «Dibujos» con su procedencia, etiquetas de nombre y contornos marcados en el mapa, y la ficha con atributos al tocar. (RF-20, RF-22, RF-23, RF-27, RNF-T8)
      Hecho cuando: `DesignLabelsTest` y `GpkgFeatureSheetTest` en verde.
- [ ] T19. `app`: replanteo de los puntos y las líneas importados con S041, con su nombre. (RF-24)
      Hecho cuando: `GpkgStakeoutTest` en verde.
- [ ] T20. Rendimiento: un `.gpkg` de 100 MB se lee y recorta en menos de 20 s y cambiar el radio recuenta en menos de 2 s. (RNF)
      Hecho cuando: el test de rendimiento en verde.
- [ ] T21. Validación: cambios en documentos anteriores (hoja de ruta, glosario, S040, S017b a la v20), recorrer la spec RF por RF, comparar con QGIS un `.gpkg` en EPSG:25830 y otro en 4258 (≤ 1 mm), `rf_report`, `pytest tools`, `forbidden_files` y la demo en el emulador según los criterios de finalización. (Todos)
      Hecho cuando: cada RF tiene un test en verde, `rf_report` no muestra RF huérfanos y la demo funciona. La demo de campo queda para el usuario.
