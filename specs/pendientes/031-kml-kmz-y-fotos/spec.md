# Spec 031 — KML/KMZ y fotos en un ZIP

> Hito: H4 · Depende de: S025, S017b (importar: S048; exportar puntos: S012) · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
El KML es el formato de Google Earth y de los visores que usan clientes y propietarios: ven en un globo los puntos del levantamiento, con su nombre y su foto. También llegan KML y KMZ (un KML comprimido) con parcelas o trazados dibujados sobre una ortofoto. Y las fotos de los puntos (S017b) no salen de la app más que dentro de una copia de seguridad o un paquete.

Esta spec hace tres cosas:
- **Importar** un KML o KMZ como **capa de diseño**, igual que un GeoPackage (S048): referencia de diseño, no dato de campo (constitución nº 1).
- **Exportar** los puntos del proyecto, y sus líneas y polígonos cuando exista S025, a KML o KMZ.
- **Exportar las fotos** de los puntos a un ZIP.

## Usuarios / actores
- Cliente o propietario que mira el trabajo en Google Earth.
- Topógrafo que recibe un trazado en KML y lo replantea, y que entrega las fotos del trabajo.

## Historias de usuario
- H1: Como topógrafo quiero importar un KML o KMZ, ver sus carpetas como capas y replantear sus puntos y líneas.
- H2: Como topógrafo quiero exportar los puntos a KML o KMZ, con sus datos y, si quiero, sus fotos, para abrirlos en Google Earth.
- H3: Como técnico quiero sacar las fotos de los puntos, o de una selección, en un ZIP con un índice.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **KML**: fichero XML de Google Earth; **KMZ**: ZIP con un KML y sus imágenes. Una **marca** (`Placemark`) es un elemento con nombre y geometría.
- **Capa de diseño**, **dibujo**, **zona de importación**, **campo de nombre** y **contorno de polígono**: los de S040 y S048.
- **ZIP de fotos**: el ZIP con las fotos de los puntos (S017b) y su índice.

### Importar KML o KMZ (H1)
- RF-1: CUANDO el usuario elija "Importar KML/KMZ" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido y aceptará un KML 2.x, o un KMZ cuyo primer KML en la raíz sea el principal.
- RF-3: SI el fichero no es un KML o KMZ, está dañado o vacío, o no tiene ninguna marca con geometría, ENTONCES EL SISTEMA no importará e indicará el motivo.
- RF-4: SI el fichero supera los 100 MB o un KMZ descomprime más de 500 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite.
- RF-5: EL SISTEMA leerá las coordenadas como longitud y latitud WGS84 y las convertirá al SC del proyecto, tomando como idéntico el datum de los cálculos del proyecto, e indicará en el resumen esa equivalencia.
- RF-6: EL SISTEMA importará puntos, líneas, anillos, polígonos con huecos y colecciones de geometrías, y contará como ignorados, con el motivo, los vínculos de red, las imágenes sobre el suelo, los modelos, los recorridos y las geometrías vacías o fuera del ámbito del SC (como S048/RF-11).
- RF-7: EL SISTEMA creará una capa de diseño por carpeta y tipo (puntos y líneas), con el nombre de la carpeta o "KML" si no la tiene, y tratará los polígonos como en S048/RF-17.
- RF-8: EL SISTEMA usará el nombre de la marca como nombre del objeto y conservará como atributos su descripción, sin etiquetas HTML, y sus datos extendidos, y los mostrará en su ficha.
- RF-9: EL SISTEMA usará el color del estilo de la marca, de ABGR a RGB, si lo declara, y el de la capa si no.
- RF-10: DONDE el KML traiga altitudes en modo absoluto, EL SISTEMA preguntará «¿Usar las cotas del fichero?», con «No» por defecto, y las guardará como cota sin convertirlas; las marcas pegadas al suelo o relativas se importan sin cota (como S040/RF-21).
- RF-11: EL SISTEMA propondrá la zona de importación y limitará el número de objetos como S048/RF-13 a RF-16.
- RF-12: EL SISTEMA mostrará las capas en el mapa como capas de diseño de origen "KML", las podrá replantear con S041 y las leerá en segundo plano con progreso y "Cancelar", sin guardar nada si se cancela o falla (como S048/RF-23, RF-24, RF-29 y RF-30).
- RF-13: EL SISTEMA mostrará un resumen por capa, guardará sin redondear las coordenadas convertidas, permitirá borrar el dibujo e incluirá los dibujos en copias y paquetes (como S048/RF-25 a RF-28).

### Exportar a KML o KMZ (H2)
- RF-14: CUANDO el usuario elija "Exportar KML/KMZ" en el menú de un proyecto o en el de su lista de objetos, o "Exportar" con una selección, EL SISTEMA preparará los puntos activos, ocultos incluidos y nunca los de la papelera, o solo los seleccionados (como S012/RF-1 a RF-3), y permitirá elegir KML o KMZ.
- RF-15: EL SISTEMA escribirá una marca por punto con su nombre, su descripción y como datos extendidos las columnas de "Todas las columnas" de S012/RF-6, y dejará vacío lo que el punto no tiene.
- RF-16: DONDE el proyecto tenga líneas o polígonos (S025), EL SISTEMA los escribirá como líneas y polígonos.
- RF-17: EL SISTEMA agrupará las marcas en una carpeta por código y dará a cada marca el color del código, y sin códigos las pondrá todas en una carpeta con el nombre del proyecto.
- RF-18: EL SISTEMA escribirá la longitud y la latitud WGS84 en grados decimales con 9 decimales, las del datum de los cálculos del proyecto (como S012/RF-6), e indicará en el resumen que ETRS89 y WGS84 se tratan como iguales.
- RF-19: DONDE el usuario marque «Incluir altura», desmarcada por defecto, EL SISTEMA escribirá la altura del proyecto en modo absoluto, y si no, las marcas irán pegadas al suelo.
- RF-20: DONDE el usuario elija KMZ y marque «Incluir fotos», EL SISTEMA añadirá las fotos de los puntos (S017b) al KMZ y las mostrará en la descripción de cada marca.
- RF-21: EL SISTEMA propondrá como nombre del fichero el del proyecto y la fecha y hora, permitirá guardarlo o compartirlo, no creará nada si no hay puntos ni dejará un fichero a medias, y no modificará ningún dato del proyecto (como S053/RF-9 a RF-13).

### Exportar las fotos (H3)
- RF-22: CUANDO el usuario elija "Exportar fotos" en el menú de un proyecto o en el de su lista de objetos, o con puntos seleccionados, EL SISTEMA preparará las fotos de los puntos activos del proyecto, o de los seleccionados, y nunca las de la papelera.
- RF-23: EL SISTEMA escribirá cada foto en un ZIP tal como está guardada, sin volver a comprimirla y con todos sus metadatos (S017b/RF-18), con el nombre `<punto>_<n>.<extensión>` saneado y sin repetir.
- RF-24: EL SISTEMA añadirá al ZIP un índice `fotos.csv` en UTF-8 con la foto, el punto, su fecha, su origen (cámara o galería) y su tamaño.
- RF-25: DONDE un punto tenga fotos, EL SISTEMA escribirá en la columna `Photos` del CSV de "Todas las columnas" (S012/RF-6) los nombres que tendrían en el ZIP, separados por "|", y la dejará vacía si no tiene.
- RF-26: SI no hay ninguna foto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el ZIP; SI el fichero de una foto no se puede leer (S017b/RF-17), ENTONCES EL SISTEMA la omitirá y la contará en el resumen.
- RF-27: CUANDO el usuario pulse "Guardar" o "Compartir", EL SISTEMA comprobará el espacio libre y avisará si el ZIP pasará de 1 GB; SI no se puede escribir, ENTONCES EL SISTEMA no dejará un fichero a medias (RNF-T6).
- RF-28: EL SISTEMA propondrá un nombre de ZIP con el del proyecto y la fecha y hora y permitirá guardarlo o compartirlo (como S012/RF-17 y RF-18).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: importar con selector de documentos, validando por contenido; exportar con `CreateDocument` o FileProvider.
  - RNF-T6: una exportación y un dibujo se escriben enteros o no se escriben.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp.
  - RNF-T9: los números de los ficheros con punto decimal.
  - RNF-T13: ninguna operación bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura y la escritura de KML son propias, sin librerías incompatibles con GPL-3.0.
- Constitución nº 2: las coordenadas importadas y exportadas coinciden con pyproj a menos de 1 mm; la ida y vuelta difiere menos de 0,1 mm.
- Exportar 10.000 puntos a KML tarda menos de 5 s y el fichero pesa menos de 15 MB; un KML de 100 MB se lee por flujo en menos de 30 s.
- Las fotos del ZIP son byte a byte las guardadas; 100 fotos de 1 MB se escriben en menos de 10 s.

## Casos límite
| Caso | RF |
|---|---|
| KMZ con varios KML, con imágenes o sin KML | RF-2, RF-3 |
| Fichero de más de 100 MB o KMZ que descomprime más de 500 MB | RF-4 |
| NetworkLink, GroundOverlay, Model, Tour | RF-6 |
| Carpetas anidadas o marcas sin carpeta | RF-7 |
| Altitud pegada al suelo o relativa | RF-10 |
| Descripción con HTML | RF-8 |
| Exportar sin puntos | RF-21 |
| Proyecto sin códigos | RF-17 |
| Proyecto en proyectadas o en pies | RF-18 |
| Punto con 10 fotos, o sin ninguna | RF-23, RF-25, RF-26 |
| Foto cuyo fichero falta | RF-26 |
| ZIP de fotos de más de 1 GB o sin espacio | RF-27 |
| Dos puntos con nombre igual | RF-23 |

## Fuera de alcance
- Estilos de icono, imágenes sobre el suelo y recorridos en el KML exportado → no previsto.
- Importar las fotos de un KMZ a los puntos → no previsto.
- Cambiar de datum entre WGS84 y ETRS89 con Helmert → S033.
- Reducir o quitar la ubicación de las fotos al exportarlas → no previsto (S017b).
- Compartir una foto suelta desde la ficha → no previsto (S017b).
- Shapefile → S030; DXF → S029; GeoPackage → S053.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Lectura frente a KML y KMZ de ejemplo (`fixtures/formatos/kml/`) hechos con QGIS y Google Earth: carpetas anidadas, polígonos con hueco, `ExtendedData`, colores y altitudes en los tres modos.
- Las coordenadas importadas y exportadas comparadas con pyproj (≤ 1 mm); el KMZ y el ZIP exportados se abren en QGIS y con un descompresor, con las mismas fotos byte a byte.
- Test de migración de la base de datos (el origen del dibujo gana un valor).
- Demo en el emulador: importar un KMZ y replantear un punto; exportar KML y KMZ con fotos y abrirlos en QGIS o en Google Earth; exportar las fotos de una selección.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el KML y el KMZ se importan como capa de diseño, no como puntos del proyecto, igual que S048 (RF-12).]
- [SUPOSICIÓN: WGS84 y el datum de los cálculos se toman como idénticos, sin Helmert, porque S033 aún no existe; en España la diferencia es de alrededor de 1 m y se avisa en el resumen (RF-5, RF-18).]
- [SUPOSICIÓN: las altitudes se guardan sin convertir, sin pasar de EGM96 a la altura del proyecto, y por defecto no se usan (RF-10, RF-19).]
- [SUPOSICIÓN: las fotos se exportan sin reducir ni quitar la ubicación, como se guardaron (RF-23).]
- [SUPOSICIÓN: la columna `Photos` del CSV es un cambio en S012 y solo está en "Todas las columnas" y en el personalizado (RF-25).]
- [SUPOSICIÓN: esta spec reúne tres funciones y podría dividirse en KML/KMZ y fotos si el plan resulta largo.]
- [SUPOSICIÓN: las dependencias S025 y S017b de la hoja de ruta se mantienen; los puntos se exportan ya con S012 y la importación solo necesita S048.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S031: pasa a `spec`; "Depende de" pasa a "012, 048, 017b (líneas y polígonos: 025)".
- `docs/glosario.md`: se añaden "KML/KMZ" (`Kml`) y "ZIP de fotos" (`PhotoArchive`); "Origen del dibujo" (`CadOrigin`) gana el valor `KML`.
- S012/RF-6: la lista de columnas gana `Photos` (RF-25 de esta spec).
- S017b: su "Fuera de alcance" ya apunta a S031; sin cambios.
