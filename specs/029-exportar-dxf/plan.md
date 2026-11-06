# Plan técnico — Spec 029

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/029-exportar-dxf/spec.md`, `specs/012-exportar-csv/plan.md` (nombre del fichero, guardar y compartir, opciones recordadas) y `specs/053-exportar-geopackage/plan.md` (el modelo de la exportación con puntos, líneas y selección, que esta copia). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- El DXF es texto de pares «código / valor». Se escribe en Kotlin puro en `core:formats`, sin `android.*`, y se prueba en la JVM. Alternativa descartada: una librería de DXF (`kabeja`, `dxf-writer`: arrastran AWT/XML y no corren bien en Android).
- La referencia independiente la genera `tools/formatos/generar_dxf_exportado.py` con `pyproj` (las coordenadas geográficas) y la tabla ACI calculada aparte, y un lector propio del script que parte el fichero en pares. Alternativa descartada: `ezdxf` para releerlo (dependencia de Python nueva). Además, QGIS (OGR) lo abre a mano en la validación.

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Cómo se escribe un punto con su cota (RF-4, RF-9) | Entidad `POINT` en (X, Y, Z) con la altura del proyecto en locales y la elipsoidal en globales (decisión de la entrevista); Z = 0 sin altura | `INSERT` de un bloque (fuera de alcance) |
| D2 | Posición de las etiquetas (RF-7) | `TEXT` con el punto de inserción desplazado del punto, sin giro: nombre a la derecha y encima (+0,5 × altura del texto en X y en Y), cota debajo a la derecha (−1,5 × altura en Y) y descripción más abajo (−3 × altura en Y) | Colocarlas según la línea cercana (fuera de alcance) |
| D3 | Altura del texto con coordenadas globales (RF-8) | Se pide en metros y se convierte a grados con 1° = 111 320 m; los desplazamientos de D2 usan la misma altura convertida | Pedirla en grados |
| D4 | Polilínea (RF-3) | R12: `POLYLINE` con el indicador de 3D (8) y `VERTEX` de indicador 32, terminada en `SEQEND`; un polígono suma el bit de cerrada (1). Sin `LWPOLYLINE` (es de R13 en adelante) | `LWPOLYLINE` (no la lee R12) |
| D5 | Capas declaradas (RF-4 a RF-7) | Tabla `LAYER` con las capas que tienen algún elemento (SIN_CODIGO, una por código, NOMBRES, COTAS, DESCRIPCIONES); tipo de línea `CONTINUOUS` | Declarar todas las capas de la biblioteca |
| D6 | Nombre de capa (RF-5) | Mayúsculas del código sin acentos (`ñ` → `N`); solo `A-Z 0-9 _ - $`, el resto pasa a `_`; máximo 31 caracteres; si dos dan el mismo nombre, el segundo lleva `_2`, `_3`… recortando el cuerpo para no pasar de 31. Un código vacío o sin ningún carácter válido es SIN_CODIGO | Error al repetirse |
| D7 | Color ACI (RF-6) | Distancia euclídea al cuadrado en RGB a los colores 1–255; en un empate, el índice menor. No se usan el 0 («por bloque») ni el 256 («por capa»). Sin color, 7 | Distancia perceptual (CIELAB) |
| D8 | Codificación (RF-15) | Windows-1252; el carácter que no cabe pasa a `?` y se cuenta | UTF-8 con `\U+` (R12 no lo entiende) |
| D9 | Opciones recordadas (RF-20) | `DxfExportPreferences` en DataStore, como `ExportPreferences` (S012): etiquetas marcadas, altura del texto y sistema (locales o globales) | Reutilizar `ExportPreferences` (mezclaría opciones del CSV) |
| D10 | Punto sin Este y Norte (fuera del ámbito del sistema) en locales | No se escribe y el resumen lo cuenta («sin posición»): un POINT del DXF sin posición no existe. Es un caso que la spec no nombraba | Escribirlo en (0, 0) |

## Estructura de módulos
- `core:formats` (`dxf/export/`), sin `android.*`
  - `AciColor`: la tabla de colores 1 a 255 por la regla estándar (1–9 fijos, 10–249 en 24 tonos × 10 variantes, 250–255 grises) y `nearest(rgb)` (RF-6, D7). Un test la compara con `fixtures/formatos/dxf-exportado/aci.csv`.
  - `DxfLayerNames`: nombres de capa limpios y sin repetir (RF-5, D6).
  - `DxfNumberFormat`: números con `Locale.ROOT`, 4 decimales en unidades lineales y 9 en grados, sin notación científica (RF-10).
  - `DxfWriter` y `DxfCharset`: escriben HEADER (`$ACADVER` `AC1009`), TABLES (LAYER), ENTITIES (POINT, TEXT, POLYLINE/VERTEX/SEQEND) y EOF a un flujo en Windows-1252, y cuentan los caracteres sustituidos (RF-15).
  - `DxfDocumentBuilder`: de los puntos, las líneas, las opciones y el sistema construye el `DxfDocument` (capas con color y entidades) y el `DxfExportSummary` (puntos, líneas, polígonos, capas, sin altura, sin posición, caracteres sustituidos, unidad) (RF-3 a RF-12, RF-14, RF-20). Los vértices salen de los puntos con sus coordenadas actuales; una línea con menos de 2 vértices con posición, o un polígono con menos de 3, no se escribe, como en S053.
  - `DxfExportOptions(labels, textHeightM, system)`; `DxfLabel { NAME, ELEVATION, DESCRIPTION }`; `DxfCoordinates { LOCAL, GLOBAL }`.
- `data` (`export/`): `DxfExportPreferences` en DataStore.
- `app` (`ui/export/`): `DxfExportViewModel` y `DxfExportScreen` (recuento, casillas, altura del texto, sistema, guardar, compartir, resumen, mensajes). «Exportar DXF» en el menú del proyecto, en el de su lista de objetos y con una selección. `SurveyPage.ExportDxf(projectId, selection)`.
- `tools/formatos`: `generar_dxf_exportado.py`, los `.dxf` dorados de `fixtures/formatos/dxf-exportado/` y su test.

## Modelo del fichero
```
0 SECTION / 2 HEADER / 9 $ACADVER / 1 AC1009 / 0 ENDSEC
0 SECTION / 2 TABLES / 0 TABLE / 2 LAYER / 70 n
  0 LAYER / 2 nombre / 70 0 / 62 color / 6 CONTINUOUS   (una por capa)
0 ENDTAB / 0 ENDSEC
0 SECTION / 2 ENTITIES
  0 POINT    8 capa  10 x 20 y 30 z
  0 TEXT     8 NOMBRES  10 x 20 y 30 z  40 altura  1 texto
  0 POLYLINE 8 capa  66 1  70 8 (+1 si cerrada) / 0 VERTEX 8 capa 10 x 20 y 30 z 70 32 ... / 0 SEQEND 8 capa
0 ENDSEC / 0 EOF
```
- Números siempre con punto (`Locale.ROOT`), nunca el idioma del móvil (RNF-T9). Líneas terminadas en `\r\n`.
- Locales: Este, Norte y altura del proyecto en la unidad lineal del proyecto (m, ft o usft). Globales: longitud, latitud en grados y altura elipsoidal en metros.

## Algoritmos
### A1. Contenido (RF-1 a RF-4)
Los puntos son los de `PointRepository.pointsForExport` (activos, ocultos incluidos, sin la papelera) o la selección. Las líneas y polígonos son los del proyecto o, con selección, los que tienen todos sus vértices entre los puntos elegidos (como `GpkgExportViewModel`). La capa de un punto es la de su código, SIN_CODIGO si no lo tiene; la de una línea, la de su código.

### A2. Coordenadas (RF-9, RF-11 a RF-13, D10)
- Locales: `easting`, `northing` y `height` del punto; sin Este y Norte, el punto no se escribe y se cuenta (D10).
- Globales: longitud y latitud del datum de los cálculos y la altura elipsoidal como Z (RF-12).
- Z = 0 sin altura; se cuenta en el resumen y no se escribe la etiqueta «Cota» de ese punto (RF-9).
- Proyecto geográfico: solo globales (RF-13); la pantalla no ofrece el selector.

### A3. Etiquetas (RF-7, RF-8, D2, D3)
Un `TEXT` por etiqueta marcada de cada punto, en NOMBRES, COTAS o DESCRIPCIONES. La cota se escribe con 3 decimales en la unidad del proyecto (en metros con globales). Sin descripción no hay texto. La altura del texto va en unidades del dibujo (m convertidos a la unidad del proyecto; a grados con globales, D3).

### A4. Color ACI y capa (RF-5, RF-6)
`AciColor.nearest(0xRRGGBB)` y `DxfLayerNames.assign(codes)` en el orden de aparición. Las capas de etiquetas llevan el color 7.

### A5. Escritura entera o ninguna (RF-17 a RF-19, RNF-T6)
1. Sin puntos no se crea nada (RF-18).
2. El documento se escribe en la carpeta de trabajo y solo con el fichero completo se copia al destino (`CreateDocument`) o se ofrece al FileProvider; un fallo borra el temporal (RF-19). Igual que `GpkgExportViewModel.writeFile` y `writeTo`.
3. El proyecto nunca se modifica (RF-21): el constructor solo lee datos ya cargados.

### A6. Nombre y resumen (RF-16, RF-20)
`ExportFileName.of(proyecto, "dxf", ahora, zona, "dxf")` da `Obra_dxf_20261106-1030.dxf`. El resumen dice los puntos, líneas, polígonos y capas, los puntos y vértices sin altura, los puntos sin posición, los caracteres sustituidos y la unidad (m, ft, usft o grados).

## Contratos
- `DxfDocumentBuilder.build(points, lines, allPoints, project, options): DxfBuild(document, summary)`.
- `DxfWriter.write(document, out: OutputStream): Int` (los caracteres sustituidos; lanza `IOException` si falla el flujo).
- `DxfExportPreferences { val settings: Flow<DxfExportOptions>; suspend fun save(options) }`.
- Navegación: `SurveyPage.ExportDxf(projectId, selection)`. MIME al compartir: `application/dxf`.

Textos nuevos (es y en): Exportar DXF, el recuento de puntos, líneas y polígonos, las casillas «Nombre», «Cota» y «Descripción», la altura del texto con su límite (0,01 a 100 m), el sistema (locales o globales), el resumen con la unidad, guardar y compartir (los de S012), «Sin puntos que exportar», «No se ha exportado el fichero». Accesibilidad (RNF-T8): casillas, botones y selector de 56 dp.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Escribir en caché y copiar | Entero o nada (RF-19, RNF-T6); el selector y el FileProvider ya trabajan así (S012, S053) | Escribir directo en el `Uri` |
| Escribir al flujo, sin la cadena entera | 10.000 puntos con etiquetas son varios MB; el pico de memoria no crece con el proyecto | Construir un `String` |
| Tabla ACI por la regla, verificada con un CSV de `tools` | Sin copiar 255 líneas a mano; el test cruza las dos implementaciones y fija a mano los colores conocidos (1 rojo, 7 blanco, 250–255 grises) | Tabla literal |
| Un `TEXT` por etiqueta, sin atributos de bloque | Lo lee cualquier CAD en R12; no hay bloques en el alcance | `ATTDEF`/`ATTRIB` |

## Estrategia de tests
- **`tools`:** `generar_dxf_exportado.py` (coordenadas geográficas con pyproj a menos de 1 mm, tabla ACI y un DXF de referencia partido en pares) y su test: los dorados guardados son los que genera el script.
- **JVM `core:formats`:** `AciColorTest`, `DxfLayerNamesTest`, `DxfNumberFormatTest`, `DxfWriterTest` (estructura, Windows-1252, sustituidos), `DxfDocumentBuilderTest` (cada RF de contenido, locales y globales, pies, sin altura, sin posición), `DxfRoundTripTest` (se relee con el `DxfReader` de S040: mismos puntos, líneas, polígonos, capas y colores, a menos de 0,05 mm), `DxfExportPerformanceTest` (10.000 puntos con dos etiquetas en menos de 5 s y menos de 10 MB).
- **`data`:** `DxfExportPreferencesTest`.
- **Robolectric `app`:** `DxfExportViewModelTest` y `DxfExportScreenTest`; las entradas en los tres sitios.
- **QGIS:** el `.dxf` dorado se abre con la herramienta de QGIS (OGR): mismas coordenadas, capas y colores (criterio de finalización).

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A1 | `DxfExportViewModelTest`, `DxfDocumentBuilderTest` |
| RF-3 | D4, A1 | `DxfDocumentBuilderTest`, `DxfWriterTest`, `DxfRoundTripTest` |
| RF-4 | D1, A1 | `DxfDocumentBuilderTest`, `DxfRoundTripTest` |
| RF-5 | D6 | `DxfLayerNamesTest` |
| RF-6 | D7, A4 | `AciColorTest`, `tools` |
| RF-7 | A3, D2 | `DxfDocumentBuilderTest`, `DxfExportScreenTest` |
| RF-8 | A3, D3 | `DxfDocumentBuilderTest`, `DxfExportViewModelTest` |
| RF-9 | A2 | `DxfDocumentBuilderTest` |
| RF-10 | `DxfNumberFormat` | `DxfNumberFormatTest`, `DxfRoundTripTest` |
| RF-11, RF-12 | A2 | `DxfDocumentBuilderTest`, `tools` |
| RF-13 | A2 | `DxfExportViewModelTest`, `DxfExportScreenTest` |
| RF-14 | A6 | `DxfDocumentBuilderTest`, `DxfExportScreenTest` |
| RF-15 | D8 | `DxfWriterTest` |
| RF-16 | A6 | `DxfExportViewModelTest` |
| RF-17 | A5 | `DxfExportScreenTest`, `DxfExportViewModelTest` |
| RF-18, RF-19 | A5 | `DxfExportViewModelTest` |
| RF-20 | A6, D9 | `DxfDocumentBuilderTest`, `DxfExportPreferencesTest`, `DxfExportViewModelTest` |
| RF-21 | A5 | `DxfExportViewModelTest` (los puntos antes y después son iguales) |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S029 a «implementada» al terminar (hoy «spec»).
- `docs/glosario.md`: «Capa del DXF» y «Color ACI» (ya añadidos).
- Sin cambios de Room: las opciones van a DataStore.
- Los `spec.md` de otras specs no se tocan; la cabecera «Estado» de S029 no se toca sin que lo pidas.
