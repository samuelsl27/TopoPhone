# Plan técnico — Spec 040

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-9, ADR-14), `specs/040-capa-cad-dxf/spec.md`, `specs/009-mapa-proyecto/`, `specs/010-*` (capa de puntos del mapa) y `specs/028-importar-csv-txt/` (el asistente, el resumen y el patrón de importación). Sin código.

Sin dependencias nuevas (Gradle ni Python). El lector de DXF es propio (ADR-14): JDXF necesita AWT, Kabeja está abandonado y `ezdxf` sería una dependencia nueva de `tools`. Los ficheros de ejemplo los escribe `tools/formatos/generar_dxf.py` como texto, sin librería.

## Estructura de módulos
- `core:model`:
  - tipos del dibujo, sin lógica: `CadDrawing`, `DesignLayer`, `DesignLayerKind { POINTS, LINES }`, `CadEntityKind { POINT, LINE, POLYLINE, ARC, CIRCLE, SPLINE, BLOCK_INSERT }`, `CadEntityRef` (capa, trozo y posición), `CadColor` (ARGB y la marca «blanco o negro del DXF», RF-25);
  - la importación: `CadImportOptions` (sistema de origen, unidad, `useElevations`), `CadSourceCrs { PROJECT | UTM(datum, huso) }`, `CadImportSummary`, `CadIgnoredReason` (RF-16, RF-17, RF-18);
  - `CadChunkCodec`: de una lista de entidades a un bloque binario y al revés (RF-19, RF-22, RF-23). Vive en `core:model` porque lo usan `data`, `core:survey` y `app`, y `core:survey` no puede depender de `core:formats`.
- `core:formats` (`dxf/`):
  - `DxfFileKind`: reconoce DWG, DXF binario, DXF de texto y fichero vacío o ajeno por el contenido, y aplica el límite de 100 MB (A1; RF-2 a RF-5);
  - `DxfTagReader`: pares código/valor por flujo, con la codificación, CRLF/LF y el punto decimal (A2; RF-2, RNF-T9);
  - `DxfHeader`, `DxfTables` y `AciPalette`: versión, unidad declarada, página de códigos, capas con su color y su estado, y la tabla de 255 colores (A2, A3; RF-7, RF-24, RF-33);
  - `DxfEntities`: punto, línea, LWPOLYLINE, POLYLINE con VERTEX y SEQEND, arco, círculo, spline e INSERT, con la extrusión (OCS) (A4; RF-14);
  - `CadCurves`: arcos, bulges, círculos y splines como polilíneas a menos de 1 cm de la curva (A5; RF-15);
  - `DxfBlocks`: despliegue de los INSERT con escala, giro, matrices y bloques anidados (A6; RF-14, RF-15);
  - `DxfReader`: une todo y entrega los elementos a un `DxfSink`, cuenta lo ignorado y no se cae con un fichero cortado (A7; RF-13, RF-16).
- `core:survey`:
  - `CadProjector`: de las coordenadas del dibujo (sistema de origen y unidad) a las del proyecto, con el ámbito del SC (A8; RF-6, RF-7, RF-17, RF-21);
  - `DesignHitTest`: el elemento más cercano a un toque dentro de una tolerancia (A11; RF-29).
- `core:geodesy`: sin cambios.
- `data`:
  - Room v13: `cad_drawings`, `cad_layers` y `cad_chunks`, con `AutoMigration(12, 13)` (RNF-T6; RF-12, RF-19, RF-27, RF-30);
  - `CadRepository`: escritura por tandas, estado, lectura por zona, visibilidad y borrado (A9);
  - `CadDrawingImporter`: la importación de un fichero: leer, convertir, agrupar y escribir, con progreso y cancelación (A7, A9; RF-11 a RF-22, RF-33);
  - `DatabaseSnapshot.TABLES` con las tablas nuevas, para la copia y el paquete (RF-31).
- `app`:
  - `CadImportViewModel` y `CadImportScreen`: la pantalla de importación, el progreso y el resumen (RF-1 a RF-20, RF-32);
  - entradas «Importar DXF» en el menú del proyecto y en el de la lista de objetos (RF-1);
  - `MapController.showDesign` y `DesignSceneBuilder`: lo que se ve en el mapa (A10; RF-24, RF-25);
  - el panel «Dibujos» con las capas, sus interruptores y el borrado, y la ficha de un elemento (RF-26 a RF-30).
- `tools`:
  - `tools/formatos/generar_dxf.py`: los ficheros de ejemplo de `fixtures/formatos/dxf/` y lo esperado de cada uno, con las conversiones de sistema calculadas con pyproj (constitución nº 2).

## Modelo de datos

### Room v13
```
cad_drawings  id TEXT PK, project_id → projects (CASCADE), file_name, imported_at_ms,
              source_crs TEXT, source_unit TEXT, use_elevations INTEGER, has_elevations INTEGER,
              incomplete INTEGER, status TEXT ('IMPORTING' | 'READY'),
              min_e, max_e, min_n, max_n REAL
              INDEX(project_id)
cad_layers    id TEXT PK, drawing_id → cad_drawings (CASCADE), name, kind TEXT ('POINTS' | 'LINES'),
              color_argb INTEGER, auto_contrast INTEGER, visible INTEGER, entity_count INTEGER, position INTEGER
              INDEX(drawing_id)
cad_chunks    id INTEGER PK AUTOINCREMENT, layer_id → cad_layers (CASCADE),
              min_e, max_e, min_n, max_n REAL, entity_count INTEGER, data BLOB
              INDEX(layer_id)
```
- `AutoMigration(12, 13)` sin especificación: solo tablas nuevas. S017b (fotos) pasa de la 13 a la 14 cuando se retome; hay que avisar en su `plan.md` (requiere permiso para tocar `specs/`).
- Las coordenadas van en el SC del proyecto (Este, Norte y, con cota, altura), como `Point.coordinates`, sin redondear (RF-19). Las geográficas del mapa se calculan al dibujar (A10).
- `has_elevations` es la cota del dibujo entero: es falso si el usuario dijo «No» (RF-8) o si todas las Z valían 0 (RF-22). Un elemento está con cota si `has_elevations` y su marca de altura (`hasZ`) están a la vez (RF-23). Con «No» no se guarda ninguna Z.
- `visible` va en la capa (RF-27), y la capa cuelga del dibujo y este del proyecto: borrar el proyecto borra todo (CASCADE).
- Un dibujo en `IMPORTING` no se ve ni se copia como válido; se borra al cancelar, al fallar y, por si la app muere a mitad, al arrancar (A9).

### Bloque binario de un trozo (`CadChunkCodec`)
- Sin tablas por elemento: un trozo agrupa unos cientos de elementos de una capa, de la misma zona, y no pasa de 256 kB (el límite de una fila de `CursorWindow` es de 2 MB).
- Por elemento: tipo (1 byte), marcas (con altura, con color propio, punto de inserción de bloque; 1 byte), el color si lo trae (4 bytes), el número de vértices (entero de longitud variable) y los `Double` de Este y Norte, y los de la altura si la lleva.
- Un elemento se identifica por `CadEntityRef(layerId, chunkId, index)`, estable mientras no se borre el dibujo (S041 lo usará).

### Opciones de la importación
Viven en el `ViewModel`; no se guardan entre importaciones.
```
CadImportOptions(
  source: CadSourceCrs = PROJECT,            // RF-6
  unit: LinearUnit,                          // la del DXF si la declara, y si no la del proyecto (RF-7)
  useElevations: Boolean = true,             // RF-8
)
```

## Algoritmos

### A1. Qué es el fichero (RF-2 a RF-5)
- El fichero se abre con `ContentResolver` y no se copia: el selector da un `Uri` legible mientras la pantalla esté abierta (RNF-T5). El tamaño sale de `OpenableColumns.SIZE`.
- Con los primeros 1 kB:
  - empieza por `AC10` y cuatro dígitos (`AC1009`…`AC1032`) y no es texto: **DWG** (RF-3);
  - empieza por `AutoCAD Binary DXF` y el centinela: **DXF binario** (RF-4);
  - un byte nulo antes de una primera línea `0` / `SECTION`: no es DXF; fichero vacío: error (RF-4);
  - una primera línea con el código `0` o `999` (comentario) y después `SECTION`: **DXF de texto** (RF-2).
- Más de 100 MB: error antes de abrir la pantalla (RF-5).
- Las versiones R12 a R2018 se aceptan sin distinguirlas: el lector solo necesita los códigos que comparten.

### A2. Leer pares código/valor y la cabecera (RF-2, RF-7, RNF-T9)
- El DXF es una lista de pares: una línea con el código de grupo (entero) y otra con el valor. `DxfTagReader` lee por flujo con un búfer de 1 MB y no guarda nada del fichero; acepta CRLF, LF y CR y quita los espacios del principio del código.
- **Codificación**: de R2007 en adelante (`$ACADVER` ≥ `AC1021`) es UTF-8; antes, la que diga `$DWGCODEPAGE` (`ANSI_1252` por defecto, y `ANSI_1250`, `ANSI_1251`… las que reconoce `java.nio.charset`). Como la cabecera va primero, el lector cambia de codificación al leerla. Los nombres de capa con acentos salen bien en los dos casos.
- **Números**: con `Locale.ROOT` y punto decimal, nunca con la configuración regional (RNF-T9). Un valor que no es número da un error de ese elemento, no del fichero.
- De `HEADER` se leen `$ACADVER`, `$INSUNITS` (1 pulgada → no soportada, 2 pies, 6 metros, 21 pies topográficos de EE. UU.; los demás, sin declarar) y `$DWGCODEPAGE`. `$EXTMIN/$EXTMAX` no se usan: suelen estar desfasados.

### A3. Capas, colores y bloques definidos (RF-24, RF-25, RF-33)
- **Tabla `LAYER`**: nombre, color (código 62; **negativo = capa apagada**), estado (código 70; bit 1 = congelada) y color verdadero (código 420). Una capa apagada o congelada se importa con `visible = false` (RF-33).
- **Color de un elemento**: el del propio elemento (62, o 420 que manda sobre él) si no es `BYLAYER` (256); si es `BYBLOCK` (0), el del INSERT; y si es `BYLAYER`, el de su capa. Los elementos de un bloque sobre la capa `0` toman la capa del INSERT y, con ella, el color de esa capa si son `BYLAYER` (como hace AutoCAD); si son `BYBLOCK`, toman el color del propio INSERT.
- **ACI** a RGB con `AciPalette` (la tabla estándar de 255 entradas). El ACI 7, y el color verdadero `FFFFFF` o `000000`, se guardan como «blanco o negro del DXF» (`auto_contrast`) y se pintan del color de contraste del mapa (A10; RF-25).
- **`BLOCKS`**: cada bloque se guarda en memoria como una lista de entidades sin desplegar. Los bloques son pequeños (la memoria la dominan las entidades de `ENTITIES`, que no se guardan); si un bloque supera los 100.000 vértices acumulados se ignora el resto y se cuenta como ignorado.

### A4. Los elementos (RF-14)
| Código DXF | Resultado | Notas |
|---|---|---|
| `POINT` | punto | |
| `LINE` | línea de 2 vértices | |
| `LWPOLYLINE` | polilínea | vértices 2D, bulge (42), cerrada (70 bit 1), elevación única (38) |
| `POLYLINE`, `VERTEX`, `SEQEND` | polilínea 2D o 3D | bulge, cerrada, malla y cara (flags 16 y 64) ignoradas |
| `ARC`, `CIRCLE` | polilínea (A5) | radio y ángulos, en el plano del OCS |
| `SPLINE` | polilínea (A5) | grado, nudos, puntos de control, pesos; si solo trae puntos de ajuste, polilínea por ellos |
| `INSERT` | los elementos del bloque (A6) y un punto en la inserción | |
- **OCS**: si la extrusión (210/220/230) no es (0, 0, 1), las coordenadas de arcos, círculos, LWPOLYLINE y POLYLINE 2D se pasan al sistema global con el algoritmo del eje arbitrario (el caso habitual es (0, 0, −1), que invierte el Este). Es lo que hace que un dibujo en espejo no salga al revés.
- Una polilínea cerrada repite el primer vértice al final. El grosor, el tipo de línea y el estilo se ignoran.
- Lo demás (`TEXT`, `MTEXT`, `DIMENSION`, `HATCH`, `SOLID`, `3DFACE`, `IMAGE`, `ELLIPSE`, `LEADER`, `ATTRIB`, `ACAD_TABLE`…) no entra y se cuenta por tipo como ignorado (RF-16).

### A5. Curvas como polilíneas a menos de 1 cm (RF-15)
- La tolerancia es de 1 cm sobre el terreno, que se pasa a unidades del dibujo con la unidad elegida (RF-7).
- **Arco y círculo**: el número de segmentos sale de la flecha máxima: `r·(1 − cos(Δθ/2)) ≤ tolerancia`, con `Δθ = 2·acos(1 − tolerancia/r)`. Mínimo de 4 segmentos por círculo. Un radio menor que la tolerancia es un punto.
- **Bulge**: un arco entre dos vértices con radio `r = d / (2·sin(θ/2))` y `θ = 4·atan(bulge)`; se trata como un arco.
- **Spline**: se evalúa con el algoritmo de de Boor en los nudos del DXF y se subdivide por mitades cada tramo hasta que la distancia del punto medio de la cuerda a la curva es ≤ la tolerancia (como máximo 10 niveles). Las splines racionales usan los pesos.
- Los extremos de la curva y los vértices originales se conservan siempre.

### A6. Bloques (RF-14, RF-15)
- Un `INSERT` lleva posición, escala en X, Y y Z (negativa permitida), giro, extrusión y, si tiene filas y columnas (`MINSERT`), sus separaciones. Se construye la matriz `traslación · giro · escala` (con el OCS) y se aplica a cada vértice del bloque, también a los bloques anidados, con una profundidad máxima de 16.
- Un bloque que se contiene a sí mismo, directa o indirectamente, se corta en el primer ciclo y se cuenta como ignorado.
- El punto de inserción del INSERT sale como un punto en la capa del INSERT (RF-15). Los atributos (`ATTRIB`) se ignoran.
- El despliegue se hace elemento a elemento y se entrega al `DxfSink` sin guardar el resultado entero.

### A7. El lector y su resultado (RF-13, RF-14, RF-16)
- `DxfReader.read(input, options, sink)` recorre las secciones en orden (`HEADER`, `TABLES`, `BLOCKS`, `ENTITIES`) y entrega al `DxfSink` cada elemento ya desplegado, con su capa, su color y su cota, en las coordenadas del dibujo. Devuelve los recuentos de ignorados por tipo y si terminó bien.
- **Fichero cortado o con una sección rota** (RF-13): un error de lectura dentro de `ENTITIES` termina la lectura con `complete = false`; lo entregado hasta ahí vale. Un error en un elemento (valor que no es número, vértices que faltan) descarta ese elemento y sigue. Si no se entregó ningún elemento, es un error y no se guarda nada.
- El `DxfSink` de la **exploración** solo calcula la extensión y los recuentos (A9); el de la **importación** es el que escribe.

### A8. De las coordenadas del dibujo a las del proyecto (RF-6, RF-7, RF-17, RF-21)
1. Unidad: Este, Norte y altura a metros con la unidad elegida (RNF-T9).
2. Con `PROJECT`, Este y Norte ya están en el SC del proyecto: solo se comprueba el ámbito (punto 4).
3. Con `UTM(datum, huso)`: se pasa a latitud y longitud con `Utm.inverse` (y, en ED50, a ETRS89 por la rejilla SPED2ETV2, `Ed50.toEtrs89`) y de ahí al SC del proyecto con `ProjectPositionCalculator.fromGround`: es la misma ruta que S011 (`ManualCoordinates`) y da lo mismo que pyproj a menos de 1 mm.
4. Ámbito: un vértice fuera de `CrsArea` (o fuera de la rejilla de ED50) hace que se descarte **el elemento entero** y se cuente como ignorado con su motivo (RF-17).
5. La altura no se convierte (RF-21): la Z del DXF es ya la del sistema de alturas del proyecto.
6. La comprobación del ámbito en `PROJECT` pasa por la latitud y longitud (`Utm.inverse`) una vez por vértice. Para que 100 MB quepan en 60 s, un elemento cuyo recuadro cae entero dentro del ámbito se da por válido con la prueba de las cuatro esquinas (el ámbito es convexo en los husos de España).

### A9. Importar: explorar, escribir por tandas y cancelar (RF-9 a RF-13, RF-18 a RF-20, RF-33)
- **Exploración** (al elegir el fichero): una pasada con el `DxfSink` de extensión. Da el recuadro del dibujo en coordenadas del origen, el número de elementos y la unidad declarada, en segundo plano y con progreso. Cambiar el sistema o la unidad no la repite: se convierten las esquinas del recuadro y su centro (RF-9). Es una pasada completa del lector, de coste parecido a la importación.
- **Centro del proyecto** (RF-9, RF-10): el centro de sus puntos y, sin puntos, el de su encuadre guardado (S009); sin ninguno de los dos no se conoce su posición y no se avisa, porque el ámbito de España entera daría falsos avisos. La distancia es la de la gran circunferencia entre ese centro y el del dibujo.
- **Importación**:
  1. Se crea la fila del dibujo con `status = 'IMPORTING'` y se leen los elementos por la ruta A7.
  2. Cada elemento pasa por `CadProjector` (A8) y por la regla de la cota: con `useElevations` y una Z presente, se guarda con `hasZ`; si no, sin Z. Se apunta si alguna Z es distinta de 0.
  3. Se agrupa por **capa de diseño** (el nombre de la capa y el tipo del elemento: puntos o líneas) y, dentro de ella, por una cuadrícula fija de 500 m del SC del proyecto, por la esquina mínima del recuadro del elemento. Cada grupo es un buffer; al llegar a 256 kB, o cuando todos los buffers pasan de 32 MB juntos (se vacía el mayor), se escribe un trozo (`cad_chunks`) con el recuadro unión de sus elementos. La importación no pasa dos veces por el fichero.
  4. Los trozos se escriben en transacciones de 64 trozos; no hay una transacción de 100 MB. Al terminar, una última transacción escribe las capas, el recuadro, `has_elevations` (falso si todas las Z valían 0, RF-22) y `incomplete`, y pasa el dibujo a `READY`. Hasta entonces el dibujo no existe para el resto de la app (RF-12).
  5. Cancelar, un fallo de escritura o una excepción borran el dibujo en `IMPORTING` con todos sus trozos (CASCADE) y devuelven «no se ha importado» (RF-12). Al arrancar, `CadRepository` borra cualquier `IMPORTING` que haya quedado de un cierre brusco.
- El resumen (RF-18) lo lleva el importador: elementos por tipo, ignorados por tipo y por motivo (`TEXT`, fuera de ámbito, ciclo de bloques…), capas creadas. Solo vive en el resultado; no se guarda en la base de datos. El fichero de texto del resumen lleva una línea por concepto.
- Mismo nombre de fichero: la pantalla avisa (RF-20) y, si se confirma, se crea otro dibujo con otro `id`.
- Las capas se numeran (`position`) por orden de aparición para listarlas siempre igual.

### A10. Ver el dibujo en el mapa (RF-24 a RF-27)
- `MapController.showDesign(scene: DesignScene?)`, con un `DesignScene` de líneas y puntos ya en latitud y longitud con su color (ADR-9). Dos fuentes GeoJSON: `DESIGN_LINES_SOURCE`, con un `LineLayer`, y `DESIGN_POINTS_SOURCE`, con un `CircleLayer`; el color va en una propiedad del elemento. Los puntos del dibujo son anillos huecos y sin etiqueta, para distinguirlos de los del proyecto (círculos rellenos con nombre, S010).
- **Qué se carga**: cuando la cámara se para (150 ms sin movimiento), `DesignSceneBuilder` lee los trozos de las capas visibles cuyo recuadro corta la vista, ampliada un 50 %, y los pasa a latitud y longitud con el mismo cálculo que el resto de la app (`ProjectPositionCalculator`/UTM inversa). Todo en `Dispatchers.Default`, sin tocar el hilo principal (RNF-T13).
- **Simplificación por zoom**: se descartan los vértices que quedan a menos de 1 píxel del anterior (según la escala de la cámara); si el total pasa de 60.000 vértices, se sube el umbral de 1 en 1 píxel hasta que quepa. Con el mapa muy alejado se ve un dibujo más basto, nunca uno que bloquee.
- **Color de contraste** (RF-25): con las capas marcadas `auto_contrast` se usa negro si el mapa base es claro (calles, Catastro o sin mapa base) y blanco con la ortofoto. Se comprueba contra `Basemap` al escribir la tarea.
- **Interruptores** (RF-26 a RF-28): la lista se arma con `CadRepository.drawings(projectId)` como `Flow`; los interruptores hacen `UPDATE cad_layers SET visible` y la escena se reconstruye. Las capas se agrupan por tipo (puntos, líneas) y cada una lleva el nombre de su dibujo, así dos capas con el mismo nombre de dos dibujos no se confunden.

### A11. Tocar un elemento (RF-29)
- El mapa avisa del toque (posición y metros por píxel). Con 24 dp de tolerancia en metros, `DesignHitTest` mira los trozos de las capas visibles que corta el recuadro del toque y elige el elemento cuyo vértice o segmento está más cerca (distancia de punto a segmento en el plano del SC del proyecto). Los puntos del proyecto tienen preferencia sobre los del dibujo.
- La ficha: tipo (el original, no «polilínea» para un arco), capa, dibujo, Este y Norte o la longitud y el número de vértices, y la cota o «Sin cota» (RF-23).

## Contratos

### Estado de la importación (`CadImportUiState`)
`file` (nombre, tamaño), `fileError`, `scan` (explorando o recuadro, recuento y unidad declarada), `options`, `extent` (en el SC del proyecto), `distanceKm`, `farWarning`, `duplicateName`, `canImport`, `progress` (leído de `x` bytes de `y`), `result: CadImportResult?` (RF-18).

### Pantallas
- **Importar DXF**, una pantalla: el fichero y su tamaño; «Sistema de coordenadas» (el del proyecto u otro, con la lista), «Unidad», «¿Usar las cotas del dibujo?», la extensión con la distancia y los avisos, y «Importar». Durante la importación, el progreso y «Cancelar»; al terminar, el resumen con «Guardar» y «Compartir».
- **Dibujos** (hoja inferior en el mapa del proyecto): cada dibujo con su nombre, la fecha y el borrado con confirmación; debajo, sus capas agrupadas en «Puntos» y «Líneas», con color, nombre, recuento e interruptor.
- **Ficha del elemento**: una hoja inferior.
- **Entradas**: «Importar DXF» en el menú del proyecto y en el de la lista de objetos; el selector es `OpenDocument` con cualquier tipo (RF-1).

### Textos nuevos (en `strings.xml`, es y en)
Importar DXF, Fichero, «Sistema de coordenadas», «El del proyecto», «Otro sistema», Unidad, «¿Usar las cotas del dibujo?», Extensión, «Distancia al proyecto», aviso de lejanía, aviso de nombre repetido, Importando, Cancelar, los motivos de rechazo (DWG, DXF binario, vacío, no es DXF, más de 100 MB), «Dibujo incompleto», el resumen («Importados %d · ignorados %d · capas %d»), los tipos y los motivos ignorados, «Guardar resumen», Dibujos, Puntos, Líneas, «Borrar dibujo», «Sin cota», y la ficha.

### Accesibilidad (RNF-T8)
- Los interruptores de las capas llevan el nombre de la capa y su dibujo; TalkBack lee el recuento al terminar y no el progreso. Zonas táctiles de 56 dp. Cada capa muestra su color con un cuadrado y su estado (visible u oculta) con texto.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| El dibujo vive en la base de datos de la app, en trozos binarios | La copia, el paquete y el borrado del proyecto ya funcionan por tablas (`DatabaseSnapshot`); RNF-T6 y RF-31 sin código aparte | Un fichero por dibujo en el almacenamiento privado (copia y paquete con otra ruta, huérfanos al borrar) |
| Trozos de unos cientos de elementos y no una fila por elemento | 100 MB son millones de elementos: una fila por elemento haría la copia y el índice inviables | Una fila por elemento con un R-tree |
| Cuadrícula fija de 500 m, sin pasar dos veces por el fichero | No hace falta conocer la extensión antes de escribir y la memoria queda acotada | Un índice espacial en memoria; un R-tree de SQLite (Room no lo gestiona) |
| Un dibujo en `IMPORTING` y una última transacción | RF-12 y RNF-T6 sin una transacción de 100 MB | Todo en una transacción |
| Trozos de 256 kB como máximo | El `CursorWindow` de Android limita una fila a 2 MB | Trozos mayores |
| Coordenadas en el SC del proyecto y las geográficas al dibujar | S041 y la ficha necesitan Este y Norte; el mapa solo ve lo que cae en la vista | Guardar las dos formas de cada vértice (el doble de datos) |
| La cota del dibujo (`has_elevations`) y la del elemento (`hasZ`) por separado | «Todas las Z valen 0» se sabe al final y no obliga a reescribir los trozos | Reescribir los trozos al terminar |
| Lector de DXF propio | ADR-14, RNF-T14 | JDXF, Kabeja, `ezdxf` |
| La exploración repite la lectura | Es la única manera de dar una extensión fiable sin fiarse de `$EXTMIN` | Fiarse de `$EXTMIN/$EXTMAX` |
| Los bloques se despliegan al leer y no al dibujar | RF-14 y S041 trabajan con elementos ya colocados | Guardar los INSERT y desplegarlos en cada dibujado |
| Las elipses y las mallas se ignoran | Fuera de alcance de la spec | Aproximarlas como polilíneas |
| `AutoMigration(12, 13)` sin especificación | Solo tablas nuevas | Migración a mano |

## Estrategia de tests
- **Unitarios JVM**:
  - `CadChunkCodecTest`: ida y vuelta de cada tipo de elemento, con y sin Z y con color, sin redondear (RF-19, RF-22, RF-23).
  - `DxfFileKindTest`: DWG renombrado, DXF binario, vacío, texto cualquiera, más de 100 MB (RF-2 a RF-5).
  - `DxfTagReaderTest`: CRLF, LF y CR, punto decimal, UTF-8 y página de códigos, nombres con acentos, valores que no son números (RF-2).
  - `DxfTablesTest`: ACI, color verdadero, BYLAYER y BYBLOCK, capa apagada y congelada, blanco y negro (RF-24, RF-25, RF-33).
  - `DxfEntitiesTest`: cada tipo, bulge, polilíneas cerradas, POLYLINE 3D, extrusión (0, 0, −1) y ecuaciones del eje arbitrario (RF-14).
  - `CadCurvesTest`: arcos, círculos, bulges y splines frente a puntos exactos calculados en `tools` (≤ 1 cm) (RF-15).
  - `DxfBlocksTest`: escala negativa, giro, anidados, `MINSERT`, ciclos, capa `0` y BYBLOCK, punto de inserción (RF-14, RF-15).
  - `DxfReaderTest`: los fixtures R12, R2000, R2010 y R2018, ignorados por tipo, fichero cortado, elemento roto (RF-13, RF-14, RF-16).
  - `CadProjectorTest`: unidades, UTM de otro huso, ETRS89, REGCAN95 y ED50 frente a pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm), fuera del ámbito, la altura sin convertir (RF-6, RF-7, RF-17, RF-21).
  - `DesignHitTestTest`: tolerancia, vértice y segmento, preferencia de los puntos del proyecto (RF-29).
- **`data`**:
  - `TopoPhoneDatabaseTest` 12 → 13: los proyectos y puntos conservan sus datos.
  - `CadRepositoryTest`: lectura por zona, visibilidad que persiste, borrar un dibujo sin tocar los puntos, `IMPORTING` que se limpia al arrancar, borrar el proyecto (RF-12, RF-27, RF-28, RF-30).
  - `CadDrawingImporterTest`: todo o nada, cancelar, fallo de escritura, fichero cortado, capas por tipo, cota con «Sí», con «No» y con todo a 0, capas apagadas, resumen, mismo fichero dos veces (RF-11 a RF-13, RF-15, RF-17 a RF-23, RF-33).
  - `BackupCadTest`: la copia y el paquete conservan los dibujos, sus capas y su visibilidad (RF-31).
- **Robolectric (`app`)**:
  - `CadImportViewModelTest`: con un `ContentResolver` falso: DWG, binario, vacío, grande, exploración, cambio de sistema y unidad, aviso de lejanía, nombre repetido, progreso, cancelar, resumen y log sin nombres ni coordenadas (RF-1 a RF-12, RF-18, RF-20, RF-32).
  - `CadImportScreenTest`: cada sección, los avisos, el progreso con «Cancelar» y el resumen (RF-6 a RF-10, RF-13, RF-18).
  - `CadImportEntriesTest`: el acceso desde los dos menús (RF-1).
  - `DesignSceneBuilderTest`: solo lo que cae en la vista, capas ocultas, simplificación por zoom y color de contraste (RF-24, RF-25, RF-27).
  - `DesignLayersPanelTest`: agrupadas por tipo, capas del mismo nombre de dos dibujos, interruptores con etiquetas y borrado con confirmación (RF-26, RF-28, RF-30).
  - `DesignElementSheetTest`: la ficha de un punto, una línea y un elemento sin cota (RF-23, RF-29).
- **`tools`**: tests de `generar_dxf.py` (que los ficheros y lo esperado guardados son los que genera el script).
- **Rendimiento**: `CadPerformanceTest`: un DXF sintético de 100 MB se explora en menos de 20 s y se importa en menos de 60 s sin pasar de 256 MB de memoria; abrir el proyecto con ese dibujo lo muestra en menos de 3 s; reconstruir la escena tras mover el mapa tarda menos de 200 ms (RNF).
- **Manual o de campo**: demo en el emulador (criterios de finalización); en campo, la puerta G2b tras S041.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | Entradas, A1 | `CadImportEntriesTest`, `CadImportViewModelTest` |
| RF-2 | A1, A2 | `DxfFileKindTest`, `DxfTagReaderTest`, `DxfReaderTest` |
| RF-3 | A1 | `DxfFileKindTest`, `CadImportViewModelTest` |
| RF-4 | A1 | `DxfFileKindTest`, `CadImportViewModelTest` |
| RF-5 | A1 | `DxfFileKindTest`, `CadImportViewModelTest` |
| RF-6 | A8 | `CadProjectorTest`, `CadImportScreenTest` |
| RF-7 | A2, A8 | `CadProjectorTest`, `CadImportViewModelTest` |
| RF-8 | A9 | `CadDrawingImporterTest`, `CadImportScreenTest` |
| RF-9 | A9 | `CadImportViewModelTest`, `CadImportScreenTest` |
| RF-10 | A9 | `CadImportViewModelTest`, `CadImportScreenTest` |
| RF-11 | A9 | `CadDrawingImporterTest`, `CadImportViewModelTest` |
| RF-12 | A9 | `CadDrawingImporterTest`, `CadRepositoryTest` |
| RF-13 | A7, A9 | `DxfReaderTest`, `CadDrawingImporterTest` |
| RF-14 | A4, A6 | `DxfEntitiesTest`, `DxfBlocksTest`, `DxfReaderTest` |
| RF-15 | A5, A6 | `CadCurvesTest`, `DxfBlocksTest` |
| RF-16 | A4, A7 | `DxfReaderTest` |
| RF-17 | A8 | `CadProjectorTest`, `CadDrawingImporterTest` |
| RF-18 | A9 | `CadDrawingImporterTest`, `CadImportViewModelTest`, `CadImportScreenTest` |
| RF-19 | A9, `CadChunkCodec` | `CadChunkCodecTest`, `CadDrawingImporterTest` |
| RF-20 | A9 | `CadImportViewModelTest`, `CadDrawingImporterTest` |
| RF-21 | A8, A9 | `CadProjectorTest`, `CadDrawingImporterTest` |
| RF-22 | A9 | `CadDrawingImporterTest`, `CadChunkCodecTest` |
| RF-23 | A9 | `CadChunkCodecTest`, `DesignElementSheetTest` |
| RF-24 | A3, A10 | `DxfTablesTest`, `DesignSceneBuilderTest` |
| RF-25 | A3, A10 | `DxfTablesTest`, `DesignSceneBuilderTest` |
| RF-26 | A10 | `DesignLayersPanelTest` |
| RF-27 | A10 | `CadRepositoryTest`, `DesignSceneBuilderTest` |
| RF-28 | A10 | `CadRepositoryTest`, `DesignLayersPanelTest` |
| RF-29 | A11 | `DesignHitTestTest`, `DesignElementSheetTest` |
| RF-30 | A9, A10 | `CadRepositoryTest`, `DesignLayersPanelTest` |
| RF-31 | `DatabaseSnapshot` | `BackupCadTest` |
| RF-32 | `CadImportViewModel` | `CadImportViewModelTest` |
| RF-33 | A3, A9 | `DxfTablesTest`, `CadDrawingImporterTest` |

## Pendiente para otras specs
- S017b (fotos) pasa a la versión 14 de la base de datos (`AutoMigration(13, 14)` y `TopoPhoneDatabaseTest` 13 → 14). Hay que actualizar el aviso de su `plan.md`, lo que requiere permiso para tocar `specs/`.
- S041 usa `CadEntityRef` para elegir qué replantear, y la cota de cada elemento (`hasZ`) para decidir si hay replanteo vertical.
- El copiar y el paquete de S013 incluyen los dibujos desde esta spec (RF-31); su `spec.md` no se modifica.
