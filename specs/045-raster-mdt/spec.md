# Spec 045 — Ráster de cotas (GeoTIFF y ASCII Grid) como superficie

> Hito: H2 · Depende de: S042 · Estado: spec (entrevistada el 28/10/2026, pendiente de aprobación)

## Contexto y objetivo
Muchas veces el terreno o el diseño no llega como un modelo de triángulos (S042) sino como un **ráster de cotas**: una malla regular con una altura por celda. Es el formato de los modelos digitales del terreno (el MDT del IGN se distribuye en GeoTIFF y en ASCII Grid), de los levantamientos con dron y de muchos proyectos de explanación. Con S042 el topógrafo ya sabe cuánto desmontar o terraplenar respecto a una superficie TIN; esta spec le da lo mismo con un ráster.

El ráster entra como **otra superficie** de las de S042: se lista con ellas, se activa con un toque (una activa por proyecto, sea TIN o ráster), comparte el desplazamiento vertical y alimenta, sin cambiarlos, el replanteo de cota y el recolector de S042. Se replanta solo la cota; la posición la sigue dando el punto o la línea. Es referencia de diseño, no dato de campo (constitución nº 1). La spec cierra la puerta G2b: una cota replanteada contra un LandXML y contra un ráster.

## Usuarios / actores
- Topógrafo en campo con el jalón y el receptor en FIX (o una reproducción) que replantea una cota contra un MDT.
- Técnico que prepara el ráster y revisa después cuánto se desvió cada punto.

## Historias de usuario
- H1: Como técnico quiero importar un GeoTIFF o un ASCII Grid y decir en qué sistema de coordenadas y en qué sistema de alturas está.
- H2: Como topógrafo quiero tener el ráster entre las superficies del proyecto, activarlo en un toque y usar el desplazamiento vertical de S042.
- H3: Como topógrafo quiero ver el desmonte o el terraplén contra el ráster en la posición del jalón, y que fuera del ráster o sobre celdas sin dato la app no invente ninguna cota.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S042 más):
- **Ráster de cotas**: una malla regular de celdas cuadradas con una cota por celda. **Celda sin dato**: la que el fichero declara sin valor, o no es un número.
- **Superficie**: ahora puede ser un TIN (S042) o un ráster de cotas; todo lo que S042 dice de una superficie vale para ambos.
- **Cota del ráster**: la interpolación bilineal de las cuatro celdas más próximas a la posición, tomando el valor de cada celda en su centro.
- **Sistema de alturas del ráster**: el de las cotas del fichero: el del proyecto, altitud ortométrica o altura elipsoidal.

### Elegir el fichero e importar (H1)
- RF-1: CUANDO el usuario elija "Importar ráster" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido, no por su extensión, y aceptará GeoTIFF (TIFF clásico, sin comprimir, LZW o Deflate, por bandas o por teselas, con enteros de 16 o 32 bits o reales de 32 o 64) y ASCII Grid de Esri.
- RF-3: SI el fichero no es un ráster, está vacío, no está georreferenciado o no tiene ninguna celda con dato, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-4: SI el fichero es un BigTIFF, tiene una compresión que no se lee, está girado o inclinado, o es de color (8 bits con 3 o más bandas), ENTONCES EL SISTEMA no lo importará e indicará cuál es el motivo.
- RF-5: SI el fichero supera los 250 MB o las 250 millones de celdas, ENTONCES EL SISTEMA no lo importará e indicará el límite.
- RF-6: DONDE el GeoTIFF tenga varias bandas, EL SISTEMA usará la primera e indicará en la pantalla cuántas hay.
- RF-7: EL SISTEMA colocará cada celda según la convención del fichero (celda como área o como punto en el GeoTIFF; esquina o centro en el ASCII Grid) y tratará como sin dato el valor declarado para ello (por defecto -9999 en un ASCII Grid) y los que no sean un número.
- RF-8: EL SISTEMA propondrá el sistema de coordenadas que declare el GeoTIFF, si es uno de los disponibles en la app (S008), y si no, o en un ASCII Grid, el del proyecto, y permitirá elegir otro de los disponibles.
- RF-9: EL SISTEMA propondrá para las cotas los metros, o la unidad que declare el GeoTIFF, y permitirá elegir entre metros, pies y pies topográficos de EE. UU. (RNF-T9).
- RF-10: EL SISTEMA propondrá como sistema de alturas del ráster el del proyecto y permitirá elegir altitud ortométrica o altura elipsoidal; si difiere del del proyecto, convertirá cada cota con la ondulación del geoide de S008.
- RF-11: MIENTRAS la pantalla de importación esté abierta, EL SISTEMA mostrará la extensión del ráster en el sistema del proyecto, el tamaño de celda, el número de celdas y el porcentaje sin dato, sus cotas mínima y máxima y la distancia entre su centro y el del proyecto, y lo actualizará al cambiar el sistema, la unidad o el sistema de alturas.
- RF-12: SI esa distancia supera los 100 km, ENTONCES EL SISTEMA mostrará un aviso y pedirá confirmación antes de importar (como S042/RF-9).
- RF-13: CUANDO el usuario pulse "Importar", EL SISTEMA leerá el fichero en segundo plano, con un progreso y un botón de "Cancelar", y no guardará nada si se cancela o no se puede escribir en el almacenamiento (RNF-T6).
- RF-14: EL SISTEMA guardará el valor de cada celda tal como viene, sin redondear, y sin remuestrear ni reproyectar el ráster, junto con el nombre del fichero y la fecha y hora de la importación.
- RF-15: SI el proyecto ya tiene una superficie con el mismo nombre de fichero, ENTONCES EL SISTEMA avisará antes de importar y, si el usuario confirma, creará otra aparte (como S042/RF-15).
- RF-16: CUANDO termine la importación, EL SISTEMA mostrará el resumen: celdas con dato y sin dato, tamaño de celda, extensión y cotas mínima y máxima.

### Gestionar el ráster entre las superficies (H2)
- RF-17: EL SISTEMA listará el ráster entre las superficies del proyecto, con su tipo, su tamaño de celda, su extensión y sus cotas mínima y máxima, y permitirá activarlo, desactivarlo y borrarlo como una superficie TIN (S042/RF-16 a RF-18).
- RF-18: CUANDO el usuario active una superficie, EL SISTEMA dejará inactiva cualquier otra, sea ráster o TIN, de modo que nunca haya dos activas.
- RF-19: EL SISTEMA aplicará al ráster el único desplazamiento vertical del proyecto, el mismo para TIN y ráster (S042/RF-19 y RF-20).
- RF-20: EL SISTEMA incluirá los rásteres en las copias de seguridad y los paquetes de proyecto, con aviso del espacio como S013/RF-5 (S042/RF-21).

### La cota del ráster (H3)
- RF-21: EL SISTEMA calculará la cota del ráster en una posición pasando la posición al sistema de coordenadas del ráster e interpolando bilinealmente las cuatro celdas más próximas, sin remuestrear el ráster.
- RF-22: SI la posición queda fuera de la extensión de los centros de las celdas, o alguna de sus cuatro celdas no tiene dato, o no hay geoide para convertir su cota, ENTONCES EL SISTEMA la tratará como "Fuera de la superficie" (S042/RF-23), sin calcular ninguna cota.
- RF-23: DONDE la posición caiga exactamente en el centro de una celda, EL SISTEMA devolverá el valor de esa celda.
- RF-24: MIENTRAS el ráster sea la superficie activa, EL SISTEMA aplicará sin cambios los requisitos S042/RF-24 a RF-33: cota de diseño de los puntos y líneas sin cota, conmutador "Cota del punto" / "Superficie", desmonte, terraplén y "En cota", guardado en el punto replanteado, ficha, columnas del informe de replanteo (S019, S041) y cota bajo la punta en el recolector.
- RF-25: CUANDO el usuario guarde un punto replanteado contra un ráster, EL SISTEMA guardará con él el nombre y la fecha de importación del ráster, de forma que siga ahí aunque se borre (S042/RF-28 y RF-32).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: importar con selector de ficheros, validando por contenido; los datos se guardan en el almacenamiento privado del proyecto.
  - RNF-T6: un ráster se guarda entero o no se guarda; al actualizar la app los datos se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; "Fuera de la superficie", desmonte y terraplén con texto además de color.
  - RNF-T9: unidades del proyecto en las cotas mostradas; los números leídos de un ASCII Grid con punto decimal.
  - RNF-T13: la cota del ráster a 10 Hz no bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura de GeoTIFF y ASCII Grid es propia, sin librerías incompatibles con GPL-3.0.
- Constitución nº 2: la cota bilineal, la conversión de altura con el geoide y el desmonte o terraplén coinciden con un cálculo aparte (GDAL o NumPy, y pyproj para el cambio de sistema) a menos de 1 mm.
- Un ráster de 250 MB (unos 62 millones de celdas de 32 bits) se importa en menos de 180 s sin cerrar la app por falta de memoria, y activarlo y obtener la primera cota tarda menos de 1 s en el móvil de referencia.
- La lectura va por flujo: no se carga el ráster entero en memoria al importar ni al consultarlo.

## Casos límite
| Caso | RF |
|---|---|
| Fichero que no es un ráster o no está georreferenciado | RF-3 |
| BigTIFF, compresión no admitida, girado, de color | RF-4 |
| Fichero de más de 250 MB o de 250 millones de celdas | RF-5 |
| GeoTIFF multibanda | RF-6 |
| Celdas sin dato, NaN o -9999 | RF-7, RF-22 |
| Celda como punto o como área, esquina o centro | RF-7 |
| ASCII Grid sin sistema de coordenadas | RF-8 |
| Ráster en otro sistema de coordenadas o en otro datum | RF-8, RF-21 |
| Cotas en pies o en altura elipsoidal | RF-9, RF-10 |
| Ráster lejos del proyecto | RF-11, RF-12 |
| Cancelar o fallo de escritura al importar | RF-13 |
| Mismo fichero dos veces | RF-15 |
| Activar un ráster con un TIN activo, o a la inversa | RF-18 |
| Posición en el borde, entre el centro de la última celda y el borde | RF-22 |
| Posición en el centro de una celda | RF-23 |
| Sin geoide en la zona | RF-22 |

## Fuera de alcance
- Dibujar el ráster, sus curvas de nivel o su sombreado en el mapa → no previsto.
- Rásteres de colores, ortofotos y varias bandas a la vez → no previsto.
- Otros formatos (ERDAS, DEM de USGS, NetCDF, Cloud Optimized con otra compresión) → no previsto.
- Un ZIP o varios ficheros con la georreferencia aparte (`.prj`, `.tfw`) → no previsto.
- Crear o editar rásteres, volúmenes entre superficies o pendientes → no previsto; la información geométrica → S026.
- Un desplazamiento vertical por superficie → no previsto (S042).
- Conversión de la cota entre sistemas de alturas de un LandXML → no previsto (S042); solo la hace el ráster.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Lectura frente a rásteres de ejemplo (`fixtures/formatos/raster/`) hechos con GDAL o QGIS: GeoTIFF sin comprimir, LZW y Deflate (con y sin predictor), por bandas y por teselas, de los cuatro tipos de dato, con celda como punto y como área, y ASCII Grid con esquina y con centro; un trozo de MDT05 del IGN.
- La cota del ráster, con el geoide y en otro sistema, comparada con GDAL (`gdallocationinfo -r bilinear`) y pyproj en centros de celda, a medio camino, en un borde y en una celda sin dato (≤ 1 mm).
- Test de migración de la base de datos con un proyecto de la versión anterior (con superficies y puntos replanteados).
- Reproducción sintética que cruza un ráster con un hueco sin dato, con su resultado esperado calculado aparte.
- Demo en el emulador: importar un GeoTIFF y un ASCII Grid, activar el ráster y un TIN alternativamente, replantear un punto sin cota y ver el desmonte y el terraplén, cruzar el hueco y ver "Fuera de la superficie", y la ficha y el informe con el ráster.
- Pruebas de campo con el RS3 (del usuario, puerta G2b, junto con S042): una cota replanteada contra una superficie LandXML y contra un ráster, y puntos de un CSV y líneas de un DXF ya replanteados, comprobados después (≤ 2,5 cm H, ≤ 3 cm V).

## Decisiones de la entrevista (28/10/2026)
- Alturas: el ráster convierte entre altitud ortométrica y altura elipsoidal con el geoide de S008 (RF-10); S042 sigue sin convertir el TIN, que se lee en el sistema de alturas del proyecto.
- Superficie activa: el ráster es una superficie más de S042, una sola activa por proyecto (TIN o ráster) y un único desplazamiento vertical compartido (RF-17 a RF-19).
- Interpolación: bilineal estricta entre centros de celda; fuera de la superficie la media celda del borde y toda posición con una celda vecina sin dato (RF-21, RF-22).
- Formatos: se queda como está (GeoTIFF clásico sin comprimir, LZW o Deflate, y ASCII Grid; una sola banda; sin BigTIFF ni ZIP con `.prj`) (RF-2, RF-4, RF-6).
- Límites: 250 MB y 250 millones de celdas; importación de 250 MB en menos de 180 s; umbrales de partida que se ajustan tras medirlos en el móvil de referencia (RF-5).
- Sistema: sin `.prj` ni sistema declarado, el ASCII Grid se interpreta en el del proyecto y el usuario puede cambiarlo (RF-8).
- El ráster no se remuestrea ni se reproyecta: lo que se convierte es la posición de la punta a su sistema (RF-14, RF-21).

## Dudas abiertas
Ninguna.

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S045: pasa a `spec`; se añade «conversión entre altitud ortométrica y elipsoidal con el geoide».
- `docs/glosario.md`: se añaden "Ráster de cotas" (`ElevationRaster`) y "Celda sin dato" (`NoDataCell`); la fila "Superficie (MDT/TIN)" (`Surface`) y "Superficie activa" (`ActiveSurface`) incluyen el ráster (S045).
- S042: su «Fuera de alcance» del ráster apunta a esta spec; sin más cambios en sus RF.
- La base de datos pasa de la versión vigente a la siguiente (el tipo de superficie gana el valor ráster); S017b pasará a la posterior.
