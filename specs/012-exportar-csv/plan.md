# Plan técnico — Spec 012

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-14, ADR-19), `specs/012-exportar-csv/spec.md`. Sin código.

## Estructura de módulos
| Módulo | Qué añade | RF |
|---|---|---|
| `core:formats` (nuevo) | `ExportColumn`, `ExportFormat`, `CsvOptions` y `CsvExport.write`, que escribe los puntos por streaming; `ExportFileName` | RF-5 a RF-16, RF-19 |
| `fixtures/formatos/csv` + `tools/formatos` | Ficheros dorados escritos por `CsvExport` y un test en Python que los lee con su módulo `csv` y compara los valores | RF-11, RF-12, RF-15, RF-16 (constitución nº 2) |
| `data` | `PointRepository.pointsForExport` (puntos activos sin las frases NMEA, en orden de creación); `ExportPreferences` en DataStore | RF-1, RF-3, RF-21 |
| `app` | Pantalla "Exportar CSV" con su ViewModel; accesos desde el menú del proyecto, el menú de la lista de objetos y la selección; guardar con el selector de ficheros y compartir con FileProvider | RF-1 a RF-4, RF-13, RF-14, RF-17 a RF-21 |

`core:formats` es Kotlin puro y solo depende de `core:model`, como ya prevén las reglas de módulos (ADR-14: lectores y escritores propios por streaming). Sin dependencias nuevas.

## Modelo de datos
```kotlin
// core:formats
enum class ExportColumn(val header: String) {
    NAME("Name"), DESCRIPTION("Description"), ORIGIN("Origin"),
    EASTING("Easting"), NORTHING("Northing"), ELEVATION("Elevation"),
    LATITUDE("Latitude"), LONGITUDE("Longitude"), ELLIPSOIDAL_HEIGHT("Ellipsoidal height"),
    CRS("CRS"), HEIGHT_SYSTEM("Height system"), UNITS("Units"),
    POLE_HEIGHT("Pole height"), SOLUTION("Solution"), SATELLITES("Satellites"), HDOP("HDOP"),
    CORRECTION_AGE("Correction age"), HORIZONTAL_ACCURACY("Horizontal accuracy"), VERTICAL_ACCURACY("Vertical accuracy"),
    MODE("Mode"), EPOCHS_USED("Epochs used"), EPOCHS_DISCARDED("Epochs discarded"), REPLAY("Replay"),
    OUT_OF_TOLERANCE("Out of tolerance"), MEASURED_FROM("Measured from"), MEASURED_TO("Measured to"),
    EDITED("Edited"), COPY_OF("Copy of"),
}

enum class ExportFormat { ALL, PENZD, PNEZD, CUSTOM }   // S012/RF-5

enum class ColumnSeparator(val char: Char) { COMMA(','), SEMICOLON(';'), TAB('	') }
enum class DecimalSeparator(val char: Char) { POINT('.'), COMMA(',') }

data class CsvOptions(
    val format: ExportFormat = ExportFormat.ALL,
    val separator: ColumnSeparator = ColumnSeparator.COMMA,
    val decimal: DecimalSeparator = DecimalSeparator.POINT,
    val customColumns: Set<ExportColumn> = ExportColumn.entries.toSet(),   // las del personalizado (S012/RF-8)
) {
    val valid: Boolean get() = !(separator == ColumnSeparator.COMMA && decimal == DecimalSeparator.COMMA)   // S012/RF-14
    val columns: List<ExportColumn>   // todas; PENZD; PNEZD; o las marcadas, en el orden de ExportColumn (S012/RF-6 a RF-8)
}

object CsvExport {
    /** Formatos que admite el SC del proyecto: sin PENZD ni PNEZD en geográficas (S012/RF-10). */
    fun formatsFor(crs: ProjectCrs): List<ExportFormat>
    /** Cabecera y una fila por punto, en el orden dado, con CRLF (S012/RF-9, RF-15). */
    fun write(points: Sequence<Point>, project: Project, options: CsvOptions, out: Appendable)
}

object ExportFileName { fun of(projectName: String, format: ExportFormat, at: Instant, zone: ZoneId): String }  // S012/RF-19
```

```kotlin
// data
interface ExportPreferences { val settings: Flow<CsvOptions>; suspend fun save(options: CsvOptions) }   // DataStore "export" (S012/RF-21)
PointRepository.pointsForExport(projectId): List<Point>   // activos, por created_at, con rawNmea vacío
```
- Ejemplo de "Todas las columnas", con coma y punto, para el punto 4 de la demo de S010 (FLOAT, fuera de tolerancia):
  ```
  Name,Description,Origin,Easting,Northing,Elevation,Latitude,Longitude,Ellipsoidal height,CRS,Height system,Units,Pole height,Solution,Satellites,HDOP,Correction age,Horizontal accuracy,Vertical accuracy,Mode,Epochs used,Epochs discarded,Replay,Out of tolerance,Measured from,Measured to,Edited,Copy of
  4,,Measured,440313.1221,4474258.8769,648.3114,40.416815094,-3.703533016,699.4230,EPSG:25830,EGM08-REDNAP,m,1.8000,FLOAT,24,0.6,1.0,0.1383,0.2100,Instant,1,0,true,NOT_FIX,2026-10-18T18:11:09Z,2026-10-18T18:11:09Z,false,
  ```

## Algoritmos
### Valores de cada celda (RF-6, RF-11, RF-12)
- **Longitudes** (Este, Norte, alturas, jalón y precisiones): en la unidad del proyecto, `BigDecimal(valor).setScale(4, HALF_UP).toPlainString()`, con el signo decimal elegido.
- **Latitud y longitud:** las del datum de los cálculos, con 9 decimales y signo (S negativo, W negativo).
- **HDOP y edad de las correcciones:** con 1 decimal.
- **Textos fijos** (en inglés, como las cabeceras): origen `Measured` o `Manual`; modo `Instant` o `Averaged`; `true` o `false`.
- **Códigos:**
  - CRS: `EPSG:<código>`;
  - sistema de alturas: `EGM08-REDNAP` o `Ellipsoidal`;
  - unidades: `m`, `ft` o `US ft`;
  - condiciones incumplidas: unidas con `|`.
- **Fechas:** `Instant` en UTC truncado al segundo, `DateTimeFormatter.ISO_INSTANT` (RNF-T10).
- **Sin valor:** celda vacía (RF-12). Por ejemplo, la altura de un punto sin altura, o la calidad de un punto manual.
- **Negativo cero:** se escribe sin el signo. `BigDecimal` normalizado evita `-0.0000`.

### Texto y separadores (RF-13 a RF-16)
- Un campo va entre comillas si contiene el separador, `"`, `\r` o `\n`; dentro, cada `"` se escribe `""` (RFC 4180).
- Cada línea acaba en `\r\n`.
- Con coma decimal y punto y coma, los números llevan coma y no se entrecomillan, porque la coma no es el separador.
- El fichero se escribe con un `OutputStreamWriter` en UTF-8, que no añade BOM.

### Nombre del fichero (RF-19)
`<proyecto>_<formato>_<AAAAMMDD-HHMM>.csv`:
- la hora es la local del móvil;
- los caracteres `\ / : * ? " < > |` y los de control se cambian por `_`;
- el nombre se corta en 100 caracteres sin partir un carácter.

### Selección (RF-2)
`ExportSelection` es un `@Singleton` de `app` con los ids elegidos. La lista de objetos lo rellena antes de navegar a la exportación con `selection = true`.
- Así no se pasan miles de ids en la ruta.
- Si Android cerró la app entretanto y la selección se ha perdido, no hay puntos que exportar y se dice (RF-4).

### Guardar y compartir (RF-17, RF-18, RF-20)
- **Guardar:** `ActivityResultContracts.CreateDocument("text/csv")` con el nombre propuesto, y `contentResolver.openOutputStream(uri)`. Si el usuario cancela, no se escribe nada.
- **Compartir:**
  - se escribe en `cache/exports/` y se comparte con el FileProvider de la app (`<cache-path name="exports" path="exports/" />`), con `ACTION_SEND`, `text/csv` y `FLAG_GRANT_READ_URI_PERMISSION`;
  - antes de cada exportación se borran los ficheros de esa carpeta con más de 24 h.
- **Errores:** la escritura va en `Dispatchers.IO`. Una `IOException` o un `SecurityException` dan "No se ha podido exportar el fichero." (RF-20). El proyecto no cambia nunca.

## Interfaz
- **Accesos:**
  - "Exportar CSV" en el menú "⋮" de cada proyecto (lista de proyectos, S006) y en el de la lista de objetos;
  - "Exportar" en la barra de la selección.
- **Pantalla "Exportar CSV":**
  - cuántos puntos se van a exportar ("Se exportarán 12 puntos"), o "No hay puntos que exportar." con los botones deshabilitados (RF-4);
  - formato: chips "Todas las columnas", "PENZD", "PNEZD" y "Personalizado" (PENZD y PNEZD no aparecen en un SC geográfico);
  - con "Personalizado", una casilla por columna con su nombre en la interfaz;
  - separador: chips "Coma", "Punto y coma" y "Tabulador"; signo decimal: chips "Punto" y "Coma";
  - con coma y coma, el aviso "El separador y el signo decimal no pueden ser los dos la coma." (RF-14);
  - el nombre del fichero propuesto;
  - botones "Guardar" y "Compartir", de 56 dp;
  - tras guardar, "Fichero guardado."; si falla, el error.

Textos nuevos (es):

| Caso | Texto |
|---|---|
| Accesos | "Exportar CSV", "Exportar" |
| Pantalla | "Exportar CSV", "Se exportarán %1$d puntos" (plurales), "No hay puntos que exportar.", "Formato", "Todas las columnas", "PENZD", "PNEZD", "Personalizado", "Columnas", "Separador", "Coma", "Punto y coma", "Tabulador", "Signo decimal", "Punto", "Nombre del fichero" |
| Columnas | Nombre, Descripción, Origen, Este, Norte, Altura, Latitud, Longitud, Altura elipsoidal, SC, Sistema de alturas, Unidades, Altura del jalón, Solución, Satélites, HDOP, Edad de las correcciones, Precisión H, Precisión V, Modo, Épocas usadas, Épocas descartadas, Reproducción, Fuera de tolerancia, Inicio de la medida, Fin de la medida, Editado, Copia de |
| Avisos | "El separador y el signo decimal no pueden ser los dos la coma.", "Fichero guardado.", "No se ha podido exportar el fichero." |
| Botones | "Guardar", "Compartir" |

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Escritor CSV propio en `core:formats` | ADR-14; unas decenas de líneas con el RFC 4180, probadas en la JVM | Una librería CSV (dependencia nueva para lo que es escribir texto) |
| Cabeceras y valores fijos en inglés | Los reconocen otros programas, y el fichero no cambia con el idioma de la app (suposición de la spec) | Cabeceras traducidas |
| Leer los puntos sin las frases NMEA | Un promediado de 60 s guarda cientos de frases que el CSV no usa; con 10 000 puntos serían cientos de MB | Leer el punto completo |
| `ExportFormat` como enumerado y las columnas del personalizado en `CsvOptions` | Se guarda y se elige en la interfaz como un valor más | Una clase sellada con las columnas dentro |
| Selección en un `@Singleton` | No se pasan miles de ids por la ruta de navegación | Lista de ids como argumento de la ruta |
| Ficheros dorados leídos por Python | Una herramienta distinta comprueba el CSV (constitución nº 2) | Comprobar solo con el mismo código Kotlin que lo escribe |
| Compartir desde `cache/exports/` con el FileProvider existente | Nada fuera de la app; Android puede vaciar la caché | Escribir en Descargas (permisos y ficheros sueltos) |

## Estrategia de tests
- **JVM `core:formats`:**
  - columnas de cada formato y su orden; cabecera en todos;
  - decimales y unidades (m, ft, ft EE. UU.); latitud y longitud con signo;
  - celdas vacías (sin altura, punto manual);
  - comillas (coma, punto y coma, tabulador, comillas y saltos de línea en la descripción);
  - separadores y signo decimal; combinación no válida; CRLF;
  - orden de las filas; formatos en un SC geográfico;
  - nombre del fichero con caracteres no válidos.
- **Dorados:**
  - `CsvExportGoldenTest` escribe tres combinaciones (coma y punto, punto y coma y coma, tabulador y punto) de un conjunto fijo de puntos y las compara con `fixtures/formatos/csv/*.csv`;
  - `tools/formatos/test_csv_exportado.py` los lee con `csv` y comprueba los valores frente al fichero de coma y punto (≤ 0,05 mm) y los textos con comillas.
- **Robolectric `data`:** `pointsForExport` (activos, por orden de creación, sin NMEA) y `ExportPreferences`.
- **Robolectric `app`:**
  - `ExportViewModel`: todos o la selección, recordar los ajustes, combinación no válida, sin puntos, escritura en un `OutputStream` (UTF-8 sin BOM) y error de escritura;
  - `ExportScreen`: chips según el SC, columnas del personalizado, aviso y botones;
  - accesos desde el menú del proyecto, el de la lista de objetos y la selección.
- **Rendimiento:** 10 000 puntos escritos en menos de 3 s en la JVM.
- **Validación:** demo en el emulador y apertura en QGIS.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 a RF-4 | Puntos de la exportación y accesos | `ExportViewModelTest`, `PointRepositoryExportTest`, `ExportEntryTest` |
| RF-5 a RF-10 | Formatos y columnas | `CsvExportTest`, `ExportScreenTest` |
| RF-11 a RF-16 | Números, textos y codificación | `CsvExportTest`, `CsvExportGoldenTest`, `test_csv_exportado.py`, `ExportViewModelTest` |
| RF-17 a RF-20 | Guardar, compartir, nombre y errores | `ExportFileNameTest`, `ExportViewModelTest`, `ExportScreenTest` |
| RF-21 | Ajustes recordados | `ExportPreferencesTest`, `ExportViewModelTest` |
