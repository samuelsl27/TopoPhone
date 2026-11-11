# Spec 055 — Exportar a Shapefile

> Hito: H4 · Depende de: S012, S024, S025 (las líneas y polígonos), S030 (la comprobación de ida y vuelta) · Estado: aprobada (11/11/2026)

## Contexto y objetivo
Muchas administraciones y técnicos SIG siguen pidiendo el trabajo de campo en Shapefile. Un Shapefile son varios ficheros y tiene límites que el GeoPackage (S053) no tiene: los nombres de columna de 10 caracteres, los textos de 254 bytes y ningún valor vacío en la altura. Por eso viaja siempre en un ZIP.

Esta spec exporta los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un ZIP con un Shapefile por tipo de geometría (`PointZ`, `PolyLineZ` y `PolygonZ`), con su `.prj` y su `.cpg`, y con los datos de calidad de cada punto como atributos. No modifica nada del proyecto (constitución nº 1). La importación de un Shapefile es S030.

## Usuarios / actores
- Técnico SIG o administración que recibe los datos del campo.
- Topógrafo que entrega un trabajo con sus datos de calidad.

## Historias de usuario
- H1: Como técnico SIG quiero abrir el ZIP exportado en QGIS con su sistema de coordenadas ya declarado.
- H2: Como topógrafo quiero que cada punto, línea y polígono lleve sus datos y sus atributos de código como columnas, con su tipo.
- H3: Como topógrafo quiero exportar todo el proyecto o una selección, y guardar o compartir el ZIP.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Shapefile** y **capa**: como en S030. El ZIP lleva un Shapefile por tipo de geometría.
- **Columnas fijas**: las de "Todas las columnas" de S012/RF-6, más las propias de las líneas y polígonos (RF-7). Las demás son los atributos de código (S024/RF-21).
- **Coordenadas proyectadas**: las del SC del proyecto. **Coordenadas geográficas**: longitud y latitud del datum de los cálculos (como S012/RF-6 y S053/RF-7).

### Contenido (H1, H3)
- RF-1: CUANDO el usuario elija "Exportar Shapefile" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA preparará la exportación con todos los puntos activos del proyecto, ocultos incluidos, y nunca los de la papelera (como S012/RF-1, RF-3).
- RF-2: CUANDO el usuario elija "Exportar" con puntos seleccionados en la lista de objetos, EL SISTEMA exportará solo esos puntos y las líneas y polígonos cuyos vértices estén todos entre ellos (como S012/RF-2 y S029/RF-2).
- RF-3: EL SISTEMA escribirá un ZIP con un Shapefile de puntos (`PointZ`) y, DONDE el proyecto tenga líneas (S025), uno de líneas (`PolyLineZ`) y, DONDE tenga polígonos, uno de polígonos (`PolygonZ`), cada uno con su `.shp`, `.shx`, `.dbf`, `.prj` y `.cpg`, y ninguno vacío.
- RF-4: EL SISTEMA dará a cada Shapefile el nombre del proyecto, con solo letras sin acentos, cifras, "_" y "-", seguido de "_puntos", "_lineas" o "_poligonos".
- RF-5: EL SISTEMA escribirá cada polígono con su contorno en sentido horario y su primer vértice repetido al final, como exige el formato.

### Atributos (H2)
- RF-6: EL SISTEMA escribirá en el Shapefile de puntos, como columnas, las columnas de "Todas las columnas" de S012/RF-6, la columna del código y una por cada atributo de código (S024/RF-21), con su tipo (texto, entero o decimal), con los valores y formatos de S012 (las fechas como texto en ISO 8601 con la Z de UTC) y vacío el valor que el punto no tiene.
- RF-7: EL SISTEMA escribirá en los Shapefiles de líneas y de polígonos, como columnas, el nombre, el código, el número de vértices y los atributos del código de cada línea o polígono, con su tipo y vacío el valor que no tiene.
- RF-8: EL SISTEMA dará a las columnas fijas el nombre abreviado, de hasta 10 caracteres, en mayúsculas, sin acentos ni eñes y de una tabla fija, en el idioma de la interfaz en el momento de exportar.
- RF-9: EL SISTEMA dará a cada atributo de código un nombre de hasta 10 caracteres, en mayúsculas, con solo letras, cifras y "_" (sustituyendo el resto por "_") y, si coincide con otra columna, lo distinguirá con un número al final, e indicará en el resumen los nombres que ha cambiado.
- RF-10: EL SISTEMA recortará a 254 bytes, sin partir ningún carácter, los textos que no caben en una columna del `.dbf` y contará los recortados en el resumen.
- RF-11: EL SISTEMA escribirá el `.dbf` en UTF-8 y lo declarará en el `.cpg`.

### Coordenadas y sistema (H1)
- RF-12: EL SISTEMA escribirá por defecto coordenadas proyectadas, sin redondear, en metros, con el Este como X, el Norte como Y y la altura del proyecto como Z, o el SC geográfico si el proyecto lo es.
- RF-13: DONDE el usuario elija coordenadas geográficas, EL SISTEMA escribirá la longitud como X, la latitud como Y, en grados decimales y sin redondear, y la altura elipsoidal como Z.
- RF-14: EL SISTEMA escribirá 0 como Z de cada punto y vértice sin altura y contará esos casos en el resumen.
- RF-15: EL SISTEMA declarará en el `.prj` de cada Shapefile el sistema de coordenadas de lo escrito, proyectado del proyecto o geográfico de su datum, e indicará en el resumen el sistema de alturas de la Z, porque el `.prj` no puede declararlo.
- RF-16: SI el sistema de coordenadas escrito no se puede expresar en un `.prj`, ENTONCES EL SISTEMA no escribirá el `.prj` y lo indicará en el resumen.

### Guardar, errores y resumen (H3)
- RF-17: EL SISTEMA propondrá como nombre del ZIP el del proyecto y la fecha y hora (como S012/RF-19).
- RF-18: CUANDO el usuario pulse "Guardar" o "Compartir", EL SISTEMA pedirá dónde guardarlo con el selector de ficheros del móvil o lo ofrecerá a las apps del móvil (como S012/RF-17 y RF-18).
- RF-19: SI no hay ningún punto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el ZIP (como S012/RF-4).
- RF-20: SI no se puede escribir el ZIP, ENTONCES EL SISTEMA indicará que no se ha exportado y no dejará un fichero a medias (RNF-T6).
- RF-21: EL SISTEMA no modificará ningún dato del proyecto al exportar.
- RF-22: EL SISTEMA mostrará al terminar un resumen con los puntos, líneas y polígonos escritos, los vértices sin altura, los textos recortados, los atributos de código renombrados y los avisos del `.prj`.
- RF-23: EL SISTEMA recordará para todos los proyectos el sistema elegido (proyectadas o geográficas), como S012/RF-21.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con `CreateDocument` o compartir mediante FileProvider.
  - RNF-T6: el ZIP se escribe entero o no se escribe.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp.
  - RNF-T9: las coordenadas y la Z se escriben en metros o en grados, sin unidades del proyecto; los números del `.dbf` con punto decimal y sin la configuración regional.
  - RNF-T13: exportar no bloquea el hilo principal más de 16 ms.
  - RNF-T14: la escritura del ZIP, del `.shp`, del `.dbf` y del `.prj` no usa librerías incompatibles con GPL-3.0.
- Constitución nº 2: leídas de nuevo, las coordenadas del ZIP difieren de las guardadas menos de 0,05 mm (5e-10° en grados), las geográficas coinciden con pyproj a menos de 1 mm y el `.prj` se lee con pyproj como el mismo sistema que el del proyecto.
- Exportar 10.000 puntos con sus columnas tarda menos de 5 s en el móvil de referencia.
- El ZIP exportado se abre en QGIS sin errores, con el mismo número de objetos, las mismas coordenadas y los mismos atributos que el proyecto, y su sistema de coordenadas es el del proyecto.

## Casos límite
| Caso | RF |
|---|---|
| Exportar sin puntos | RF-19 |
| Proyecto sin líneas ni polígonos | RF-3 |
| Selección con puntos sin las líneas completas | RF-2 |
| Proyecto con sistema geográfico | RF-12, RF-13 |
| Sistema sin `.prj` posible | RF-16 |
| Puntos sin altura o manuales sin calidad | RF-6, RF-14 |
| Nombre de proyecto con espacios, acentos o símbolos | RF-4 |
| Atributo de código de más de 10 caracteres o que se repite al abreviar | RF-9 |
| Texto de más de 254 bytes | RF-10 |
| Exportar con espacio insuficiente | RF-20 |

## Fuera de alcance
- Exportar las capas de diseño importadas, las superficies o los ejes → no previsto.
- Un Shapefile por código → no previsto; el código va en una columna.
- Valores M, `MultiPatch` y codificaciones del `.dbf` distintas de UTF-8 → no previsto.
- Estilos de QGIS (`.qml`) dentro del ZIP → no previsto.
- Fotos de los puntos → S031.
- Importar un Shapefile → S030.
- GeoPackage → S053; DXF → S029; KML → S031.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un ZIP exportado con puntos, líneas y polígonos se abre en QGIS con las mismas coordenadas, atributos y sistema (comprobado con la herramienta de QGIS), y se relee con el lector de S030.
- Demo en el emulador: exportar un proyecto con y sin selección, en proyectadas y en geográficas, con el idioma de la app en español y en inglés, y abrir el ZIP en QGIS.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: los nombres de las columnas fijas de la tabla de RF-8, en español y en inglés, se fijan en el plan a partir de las columnas de S012/RF-6.]
- [SUPOSICIÓN: la Z es la altura del proyecto en proyectadas y la elipsoidal en geográficas, y el sistema de alturas va en el resumen y no en el fichero (RF-12 a RF-15).]
- [SUPOSICIÓN: los booleanos de las columnas de calidad (reproducción, editado, medido en automático) salen como enteros 0 y 1, como en S053/RF-4 (RF-6).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S055 en H4, en estado `spec`; la fila S030 queda solo con la importación.
- `docs/inventario-funcional.md`: la exportación a SHP pasa de S030 a S055.
- S024/RF-21: la columna del código y los atributos de código se escriben también en la exportación a Shapefile (S055/RF-6); S024, S025, S029 y S053 citan «→ S030» para el Shapefile exportado, que pasa a ser S055.
