# Plan técnico — Spec 056

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/056-exportar-fotos/spec.md`, `specs/017b-fotos-de-puntos/plan.md` (dónde viven las fotos y cómo se leen) y `specs/055-exportar-shapefile/plan.md` (el ZIP escrito en un fichero temporal, guardar y compartir, y la pantalla que esta spec imita). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- Escribir el ZIP y el índice: `java.util.zip` de la JDK y código propio, como S055. Alternativa descartada: una librería de ZIP (ya hay uno en la JDK y en Android).
- Las referencias las genera `tools/formatos/generar_fotos_zip.py` con `zipfile` y `csv` de la biblioteca estándar, que `tools` ya usa para otros generadores.

## Idea: las fotos ya están reducidas y guardadas
S017b guarda cada foto reducida a 2.048 px y con sus metadatos en `photos/<id>.jpg` (`PhotoStore.photoFile`), con su nombre, su fecha, su origen y su tamaño en la tabla de fotos. Exportarlas es copiar esos ficheros a un ZIP **sin tocarlos** (RF-4) y escribir un índice. No hay nada que reducir, volver a codificar ni migrar: **sin Room nuevo** y sin preferencias (no hay opciones que recordar).

## Estructura de módulos
- `core:formats` (`photo/`), sin `android.*`
  - `PhotoArchiveNames`: de los puntos con sus fotos en orden a los nombres del ZIP `<punto>_<n>.jpg` (saneado, «punto» si queda vacío, sin repetir sin distinguir mayúsculas) (RF-5).
  - `PhotoIndexCsv`: la cabecera y una fila por foto, con las comillas y el fin de línea de S012 (`CsvExport.quoted` y `LINE_END`) (RF-6, RF-7).
  - `PhotoZipWriter`: escribe las entradas de las fotos desde flujos que da quien llama, cuenta las que no se pueden leer y añade el índice al final con las que sí se escribieron (RF-4, RF-6, RF-9, RF-14).
- `data` (`photo/`)
  - `PhotoRepository.photosForExport(pointIds)`: las fotos de varios puntos de una vez, en orden de alta, con la consulta troceada (SQLite admite 999 variables); sin esto, 10.000 puntos son 10.000 consultas (RF-1, RF-2).
  - `PhotoExporter`: de las fotos al ZIP en un fichero temporal de la caché, con el cálculo del tamaño, el espacio libre y el borrado del temporal si falla (RF-3, RF-10, RF-11).
- `app`
  - `PhotoExportViewModel` y `PhotoExportScreen`: puntos o selección, el recuento y el tamaño, el aviso de 1 GB, guardar y compartir, errores y resumen (RF-1 a RF-3, RF-8, RF-10 a RF-13).
  - Entradas «Exportar fotos» en el menú del proyecto y en el de su lista de objetos, y «Fotos» en la barra de una selección, con su navegación (RF-1, RF-2).
- `tools/formatos`: `generar_fotos_zip.py`, las referencias de `fixtures/formatos/fotos-zip/`.

## Modelo de datos
Sin Room ni formato propio. El ZIP exportado:
```
<punto>_<n>.jpg ...        una por foto, byte a byte la guardada
fotos.csv                  el índice, en la raíz
```
- **Nombres (RF-5).** `<punto>` es el nombre del punto con solo letras ASCII sin acentos (NFD, quitar marcas), cifras, `_` y `-` (lo demás pasa a `_`; si no queda nada, `punto`). `<n>` es la posición de la foto en su punto por orden de alta (`addedAtMs`, y `id` si empatan), desde 1: la que S017b muestra con su número (RF-9 de S017b). La extensión es la del fichero guardado, `jpg` (el almacén solo escribe JPEG). Un nombre repetido (otro punto que satura a lo mismo, o `A_1` y `A` con su foto 1) se distingue añadiendo `_2`, `_3`… **al nombre del punto**, comparando sin distinguir mayúsculas, para que se abra bien en Windows.
- **Índice (RF-6, RF-7).** UTF-8 sin BOM, coma como separador, fin de línea `\r\n` como el CSV de S012, una cabecera fija en inglés (`Photo,Point,Number,Date,Source,Size`) y una fila por foto en el orden del ZIP. `Photo` es el nombre del ZIP, `Point` el nombre real del punto (sin sanear), `Number` el `<n>`, `Date` la fecha de la foto en ISO 8601 con Z (`CsvExport.utc`), `Source` `camera` o `gallery` y `Size` los bytes del fichero que se escribió, no los del registro. Un texto con coma, comillas o salto de línea va entre comillas dobles con las comillas duplicadas.
- **Resumen (RF-13).** `PhotoExportSummary(photos, points, omitted, zipBytes)`.

## Algoritmos
### A1. Preparar (RF-1 a RF-3, RF-8)
1. Los puntos son los de `pointsForExport` (activos, ocultos incluidos, nunca la papelera) o los de la selección (`ExportSelection`), como S055.
2. `photosForExport` da las fotos de esos puntos; los que no tienen ninguna no cuentan. El recuento de la pantalla son las fotos, los puntos con fotos y la suma de `sizeBytes` (RF-3). Sin fotos, la pantalla lo dice y no deja guardar ni compartir (RF-8).

### A2. Escribir el ZIP (RF-4 a RF-7, RF-9, RF-14)
1. `PhotoArchiveNames` da los nombres antes de escribir nada, para que el índice y el ZIP coincidan.
2. `PhotoZipWriter` recorre las fotos de una en una: abre el fichero (`PhotoStore.photoFile(id)`), lo copia al `ZipOutputStream` con un buffer de 64 kB y suma los bytes. Una foto cuyo fichero no existe o falla al leerse **antes de escribir su primer byte** se omite y se cuenta (RF-9); si falla a mitad de copia, el ZIP queda inservible y se trata como un fallo de escritura (RF-11).
3. Las entradas llevan `Deflater.NO_COMPRESSION`: un JPEG no se comprime y no hay que gastar CPU ni batería (el contenido extraído es el mismo byte a byte).
4. El índice se escribe el último, con las fotos que sí se escribieron.
5. Nada modifica las fotos ni el proyecto: solo se leen (RF-14).

### A3. Tamaño, espacio, guardar y compartir (RF-10 a RF-12)
- El tamaño previsto es la suma de `sizeBytes` más el índice. Con más de 1 GB (`1_073_741_824`), «Guardar» y «Compartir» piden confirmar antes de escribir (RF-10).
- `PhotoExporter` comprueba `usableSpace` de la caché contra el tamaño previsto y un margen del 5 %; sin sitio, no escribe y lo dice (RF-11). Un fallo al escribir borra el temporal y no deja nada.
- El ZIP se escribe entero en `cacheDir` y solo entonces se copia al destino (`CreateDocument`) o se comparte con FileProvider, como S055. El nombre propuesto es `ExportFileName.of(proyecto, "fotos", ahora, zona, "zip")` (RF-12).

## Contratos
- `PhotoArchiveNames.of(points: List<PointPhotos>): Map<String, String>` (id de foto a nombre) y `PhotoIndexCsv.write(rows, out)`.
- `PhotoZipWriter.write(entries: List<PhotoZipEntry>, out: OutputStream): PhotoZipResult`, con `PhotoZipEntry(name, row, open: () -> InputStream?)`.
- `PhotoRepository.photosForExport(pointIds: List<String>): Map<String, List<PointPhoto>>`.
- `PhotoExportViewModel.start(projectId, selectionOnly)`, `fileName(at, zone)`, `confirmLarge()`, `writeTo(out, workDir)`, `writeFile(into)` y `report(outcome)`: como `ShpExportViewModel`.
- Navegación: `SurveyPage.ExportPhotos(projectId, selectionOnly)` (ADR-19).

Textos nuevos (es y en): Exportar fotos; «Se exportarán %d fotos de %d puntos (%s)»; el aviso de 1 GB; «No hay fotos que exportar.»; el resumen y las fotos omitidas; los errores (sin espacio, no se pudo escribir).

Accesibilidad (RNF-T8): zonas de 56 dp; el recuento y el resumen en texto; TalkBack lee el resumen al terminar.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Copiar el fichero guardado sin decodificarlo | RF-4: byte a byte, metadatos incluidos, y 100 fotos en menos de 10 s | Volver a codificar (pierde calidad y metadatos) |
| `NO_COMPRESSION` | Un JPEG no se comprime; ahorra CPU | `DEFLATED` por defecto (más lento sin ganancia) |
| Consulta troceada de fotos de varios puntos | 10.000 puntos no deben ser 10.000 consultas | Un `Flow` por punto |
| Números `<n>` por orden de alta | Coinciden con los que ve el usuario en la ficha | Numerar por fecha de la foto (cambia con fotos de galería) |
| Índice en inglés y sin opciones | Como las cabeceras de S012 | Cabecera en español |
| Sin Room ni preferencias | Solo se lee el proyecto | — |

## Estrategia de tests
Los nombres de clase y de test de `data` y `app` se mantienen en 60 caracteres o menos (SQLite y Robolectric en Windows).
- **JVM `core:formats`:** `PhotoArchiveNamesTest` (saneado, vacío, repetidos con mayúsculas, numeración), `PhotoIndexCsvTest` (comparado byte a byte con la referencia de `tools`, comillas, fechas UTC), `PhotoZipWriterTest` (bytes idénticos, orden, índice al final, foto omitida, error a mitad de copia) y un test de rendimiento (100 fotos de 1 MB en menos de 10 s).
- **`tools`:** `generar_fotos_zip.py` y su test: el ZIP y el índice de un proyecto de ejemplo (diez fotos en un punto, dos puntos con el mismo nombre, símbolos y acentos, un nombre con coma y comillas) y su lectura con `zipfile` y `csv`.
- **Robolectric `data`:** `PhotoExportRepoTest` (`photosForExport`, orden y troceado con 2.000 puntos) y `PhotoExporterTest` (ZIP entero, espacio, 1 GB simulado, fallo sin dejar fichero, fotos que faltan).
- **Robolectric `app`:** `PhotoExportViewModelTest` (todo, selección, sin fotos, aviso de 1 GB, escribir entero o nada, resumen, log sin nombres ni rutas), `PhotoExportScreenTest` y `PhotoExportEntriesTest` (los tres puntos de entrada).
- **Contra otras herramientas (demo):** descomprimir el ZIP con un descompresor externo (Python y 7-Zip), comprobar con `fc /b` que las fotos son las guardadas, y abrir `fotos.csv` en QGIS (Python de QGIS) como tabla.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A1, entradas de menú | `PhotoExportViewModelTest`, `PhotoExportEntriesTest`, `PhotoExportRepoTest` |
| RF-3 | A1 | `PhotoExportViewModelTest`, `PhotoExportScreenTest` |
| RF-4 | A2 | `PhotoZipWriterTest`, `PhotoExporterTest` |
| RF-5 | A2, `PhotoArchiveNames` | `PhotoArchiveNamesTest` |
| RF-6, RF-7 | A2, `PhotoIndexCsv` | `PhotoIndexCsvTest`, tests de `tools` |
| RF-8 | A1 | `PhotoExportViewModelTest` |
| RF-9 | A2 | `PhotoZipWriterTest`, `PhotoExporterTest` |
| RF-10, RF-11 | A3 | `PhotoExporterTest`, `PhotoExportViewModelTest` |
| RF-12 | A3 | `PhotoExportViewModelTest`, `PhotoExportScreenTest` |
| RF-13 | resumen | `PhotoExportViewModelTest`, `PhotoExportScreenTest` |
| RF-14 | A2 | `PhotoExporterTest` |
