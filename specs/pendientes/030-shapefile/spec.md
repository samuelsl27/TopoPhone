# Spec 030 — Shapefile: importar desde ZIP y exportar

> Hito: H4 · Depende de: S025 (importar: S048; exportar puntos: S012) · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
El Shapefile sigue siendo el formato que piden las administraciones y los técnicos SIG: parcelas, redes y límites llegan como un ZIP con `.shp`, `.shx`, `.dbf` y `.prj`, y el trabajo de campo se entrega igual. Un Shapefile son varios ficheros, así que viaja siempre en un ZIP.

Esta spec hace las dos direcciones:
- **Importar** un ZIP como **capa de diseño**, igual que un GeoPackage (S048): se ve en el mapa, se tocan sus elementos para ver los atributos y se replantea con S041. Es referencia de diseño, no dato de campo (constitución nº 1).
- **Exportar** los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a un ZIP con un Shapefile por tipo de geometría (`PointZ`, `PolyLineZ`, `PolygonZ`), con su `.prj` y su `.cpg`.

## Usuarios / actores
- Técnico SIG que entrega o recibe Shapefiles.
- Topógrafo que replantea parcelas o redes recibidas y entrega su trabajo.

## Historias de usuario
- H1: Como topógrafo quiero elegir un ZIP con un Shapefile, ver qué capas trae y su sistema de coordenadas, e importar solo lo que cae cerca de mi proyecto.
- H2: Como topógrafo quiero ver los elementos en el mapa, tocarlos para ver sus atributos y replantearlos.
- H3: Como técnico SIG quiero un ZIP exportado que QGIS abra con su sistema de coordenadas declarado y los datos de calidad como atributos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Shapefile**: el conjunto de ficheros `.shp` (geometría), `.dbf` (atributos), `.prj` (sistema de coordenadas), `.cpg` (codificación) y `.shx` (índice) con el mismo nombre; una **capa** es un Shapefile del ZIP.
- **Capa de diseño**, **dibujo**, **zona de importación**, **campo de nombre** y **contorno de polígono**: los de S040 y S048.

### Importar un ZIP (H1)
- RF-1: CUANDO el usuario elija "Importar Shapefile" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido, no por su extensión, y aceptará un ZIP con al menos un `.shp`.
- RF-3: SI el fichero no es un ZIP, está dañado o vacío, o no contiene ningún `.shp` legible, ENTONCES EL SISTEMA no abrirá la pantalla e indicará el motivo; si es un `.shp` suelto, indicará que hay que elegir el ZIP entero.
- RF-4: SI el ZIP supera los 100 MB o su contenido descomprimido los 500 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite (como S040/RF-5).
- RF-5: EL SISTEMA listará las capas del ZIP con su nombre, su tipo (puntos, líneas o polígonos), el número de objetos y su sistema de coordenadas, y permitirá marcar cuáles se importan, todas marcadas por defecto (como S048/RF-5).
- RF-6: EL SISTEMA leerá el sistema de coordenadas del `.prj` de cada capa y, si es uno de los disponibles en la app, lo propondrá y reproyectará las coordenadas al del proyecto.
- RF-7: SI una capa no tiene `.prj` o su sistema no es uno de los disponibles, ENTONCES EL SISTEMA no importará la capa hasta que el usuario elija uno de los sistemas disponibles para ella (como S048/RF-9).
- RF-8: EL SISTEMA propondrá una zona de importación, la pedirá fijar si falta el centro del proyecto y limitará el número de objetos como S048/RF-13 a RF-16, e importará los objetos enteros que tengan algún vértice dentro.
- RF-9: EL SISTEMA importará `Point`, `MultiPoint`, `PolyLine` y `Polygon` con o sin Z y M, tratará los polígonos como en S048/RF-17, ignorará la M y contará como ignorados, con el motivo, los objetos vacíos, los `MultiPatch` y los de un vértice fuera del ámbito del SC (como S048/RF-11).
- RF-10: DONDE las capas traigan Z, EL SISTEMA preguntará «¿Usar las cotas de la capa?», con «Sí» por defecto, y guardará la Z como cota sin convertirla (como S048/RF-19).
- RF-11: EL SISTEMA leerá el `.dbf` en la codificación que declare el `.cpg` y, sin `.cpg`, en UTF-8 y, si no lo es, en Windows-1252, permitiendo elegir otra.
- RF-12: EL SISTEMA conservará los atributos de cada objeto y los mostrará en su ficha, y propondrá y usará el campo de nombre como en S048/RF-20 a RF-22.

### Ver, replantear y borrar (H2)
- RF-13: EL SISTEMA mostrará las capas importadas como capas de diseño en el mapa, con interruptor, color y ficha, las podrá replantear con S041 y las tratará como un dibujo de origen "Shapefile" (como S048/RF-23 y RF-24).
- RF-14: CUANDO termine la importación, EL SISTEMA mostrará el resumen por capa (importados, fuera de la zona e ignorados con su motivo), con acceso a guardarlo o compartirlo.
- RF-15: EL SISTEMA leerá en segundo plano con progreso y "Cancelar", guardará sin redondear las coordenadas convertidas, no guardará nada si se cancela o falla la escritura (RNF-T6), permitirá borrar el dibujo entero e incluirá los dibujos en las copias y los paquetes (como S048/RF-26 a RF-30).

### Exportar un ZIP (H3)
- RF-16: CUANDO el usuario elija "Exportar Shapefile" en el menú de un proyecto o en el de su lista de objetos, o "Exportar" con una selección, EL SISTEMA preparará los puntos activos, ocultos incluidos y nunca los de la papelera, o solo los seleccionados (como S012/RF-1 a RF-3).
- RF-17: EL SISTEMA escribirá un ZIP con un Shapefile de puntos (`PointZ`) y, DONDE el proyecto tenga líneas o polígonos (S025), uno de líneas (`PolyLineZ`) y otro de polígonos (`PolygonZ`), cada uno con `.shp`, `.shx`, `.dbf`, `.prj` y `.cpg`, y ninguno vacío.
- RF-18: EL SISTEMA escribirá como atributos las columnas de "Todas las columnas" de S012/RF-6 con su tipo (texto, entero o decimal), con nombres de hasta 10 caracteres según una tabla fija y sin repetir, y dejará vacío el valor que el punto no tiene.
- RF-19: EL SISTEMA recortará a 254 caracteres los textos que no caben en el `.dbf` y contará los recortados en el resumen.
- RF-20: EL SISTEMA escribirá el `.dbf` en UTF-8 y lo declarará en el `.cpg`.
- RF-21: EL SISTEMA escribirá las coordenadas sin redondear y la altura del proyecto como Z, y 0 si no la tiene, contando esos casos en el resumen.
- RF-22: EL SISTEMA escribirá las capas en el SC del proyecto o, a elegir, en geográficas del datum del proyecto, y declarará el sistema en el `.prj` (como S053/RF-6 y RF-7).
- RF-23: SI el SC del proyecto no tiene una definición reconocida para el `.prj`, ENTONCES EL SISTEMA no escribirá el `.prj` y lo indicará en el resumen.
- RF-24: EL SISTEMA orientará los polígonos como exige el formato (exterior en sentido horario).
- RF-25: EL SISTEMA propondrá como nombre del ZIP el del proyecto y la fecha y hora, permitirá guardarlo o compartirlo, no creará nada si no hay puntos ni se quedará un fichero a medias, y no modificará ningún dato del proyecto (como S053/RF-9 a RF-13).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: importar con selector de documentos, validando por contenido; exportar con `CreateDocument` o FileProvider.
  - RNF-T6: un dibujo y un ZIP se escriben enteros o no se escriben.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp.
  - RNF-T9: coordenadas en la unidad de su sistema; los números del `.dbf` con punto decimal.
  - RNF-T13: leer, cambiar la zona o exportar no bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura y la escritura son propias, sin librerías incompatibles con GPL-3.0.
- Constitución nº 2: las coordenadas reproyectadas y las geográficas exportadas coinciden con pyproj a menos de 1 mm; la ida y vuelta difiere menos de 0,1 mm; el `.prj` exportado se lee con pyproj como el mismo sistema que el del proyecto.
- Un ZIP de 100 MB se lee y se recorta a la zona en menos de 20 s, por flujo y sin cargarlo entero en memoria; exportar 10.000 puntos tarda menos de 5 s.

## Casos límite
| Caso | RF |
|---|---|
| `.shp` suelto, ZIP sin `.shp` o dañado | RF-3 |
| ZIP de más de 100 MB o que descomprime más de 500 MB | RF-4 |
| Capa sin `.prj` o en un sistema desconocido | RF-7 |
| `.dbf` en Windows-1252 sin `.cpg` | RF-11 |
| Objetos vacíos, `MultiPatch` o con M | RF-9 |
| Polígono con huecos | RF-9 |
| Exportar sin puntos | RF-25 |
| Atributos de nombre largo o que se repiten al truncar | RF-18 |
| Texto de más de 254 caracteres | RF-19 |
| Proyecto con sistema sin `.prj` reconocido | RF-23 |
| Proyecto sin líneas ni polígonos | RF-17 |

## Fuera de alcance
- Convertir lo importado en puntos o líneas del proyecto → no previsto.
- Importar el `.shp` sin su ZIP, o varios ZIP a la vez → no previsto.
- Valores M, `MultiPatch`, `.dbf` en otras codificaciones que no declare el `.cpg` y `.qpj` → no previsto.
- GeoPackage → S048 y S053; DXF → S029, S040; KML → S031.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Lectura frente a Shapefiles de ejemplo (`fixtures/formatos/shp/`) hechos con QGIS: puntos, líneas, polígonos con hueco, con Z y M, en ETRS89 UTM, REGCAN95 y ED50, con y sin `.cpg`.
- El ZIP exportado se abre en QGIS con las mismas coordenadas, atributos y sistema, y se relee con el lector de la propia spec.
- Test de migración de la base de datos (el origen del dibujo gana un valor).
- Demo en el emulador: importar un ZIP de parcelas y replantear una; exportar un proyecto con y sin selección, en proyectadas y en geográficas, y abrirlo en QGIS.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: lo importado es una capa de diseño y no puntos del proyecto, como S048; la referencia hace puntos y líneas del proyecto, pero aquí se evita mezclar diseño con dato de campo.]
- [SUPOSICIÓN: se admite un ZIP sin `.shx` y sin `.dbf` (sin atributos); un `.shp` suelto no se admite porque el selector da un solo fichero.]
- [SUPOSICIÓN: los nombres de los atributos de 10 caracteres salen de una tabla fija de abreviaturas, no de un truncado automático (RF-18).]
- [SUPOSICIÓN: el punto o vértice sin altura se exporta con Z = 0 y se avisa (RF-21).]
- [SUPOSICIÓN: mismos límites de 100 MB y de zona de 5 km que S048.]
- [SUPOSICIÓN: la dependencia de S025 se mantiene; los puntos se exportan ya con S012 y la importación solo necesita S048.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S030: pasa a `spec`; "Depende de" pasa a "012, 048 (líneas y polígonos: 025)".
- `docs/glosario.md`: se añade "Shapefile" (`Shapefile`); "Origen del dibujo" (`CadOrigin`) gana el valor `SHAPEFILE`.
- S048: la lectura por zona y el origen del dibujo se reutilizan; sin cambios en su texto.
