# Spec 031 — Importar y exportar KML/KMZ

> Hito: H4 · Depende de: S048, S040, S006 (punto central), S012, S024, S025 (las líneas y polígonos), S017b (las fotos) · Estado: aprobada (12/11/2026)

## Contexto y objetivo
El KML es el formato de Google Earth y de los visores que usan clientes y propietarios: ven en un globo los puntos del levantamiento, con su nombre y, si hace falta, su foto. También llegan KML y KMZ (un KML comprimido, a veces con imágenes) con parcelas o trazados dibujados sobre una ortofoto. Con lo que hay, esos ficheros hay que convertirlos con QGIS antes de verlos en la app, y los puntos del proyecto solo salen en CSV, DXF, GeoPackage o Shapefile.

Esta spec hace dos cosas:
- **Importar** un KML o KMZ como **capa de diseño**, igual que un GeoPackage (S048) o un Shapefile (S030): referencia de diseño, no dato de campo (constitución nº 1).
- **Exportar** los puntos del proyecto, y sus líneas y polígonos, a KML o KMZ, con la opción de llevar las fotos de los puntos dentro del KMZ.

Un KML siempre va en WGS84 y no puede declarar otro sistema. Los proyectos de TopoPhone están en ETRS89 o REGCAN95, que en España difieren de WGS84 entre 0,5 y 1 m. S033 (transformaciones de datum) aún no existe, así que esta spec toma los dos datums como idénticos, sin transformación, y lo avisa en cada resumen. Las fotos en un ZIP suelto son otra spec (S056).

## Usuarios / actores
- Cliente o propietario que mira el trabajo en Google Earth.
- Topógrafo que recibe un trazado en KML o KMZ y lo replantea, y que entrega sus puntos para verlos en un globo.

## Historias de usuario
- H1: Como topógrafo quiero importar un KML o KMZ, ver sus carpetas como capas, marcar las que me interesan y replantear sus puntos y líneas.
- H2: Como topógrafo quiero exportar los puntos, líneas y polígonos a KML o KMZ, con sus datos y su color de código, para abrirlos en Google Earth.
- H3: Como topógrafo quiero que el KMZ lleve las fotos de los puntos, y verlas en la descripción de cada marca.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **KML**: fichero XML de Google Earth; **KMZ**: ZIP con un KML y sus imágenes. Una **marca** (`Placemark`) es un elemento con nombre y geometría.
- **Carpeta**: un `Folder` o un `Document` del KML, salvo el `Document` raíz. Una **capa** es una carpeta con marcas, y sus puntos y líneas van en capas separadas; los polígonos cuentan como líneas.
- **Capa de diseño**, **dibujo**, **zona de importación**, **centro del proyecto**, **campo de nombre** y **contorno de polígono**: los de S040, S048 y S030.
- **Foto**: la de un punto (S017b), tal como está guardada.

### Elegir el fichero y las capas (H1)
- RF-1: CUANDO el usuario elija "Importar KML/KMZ" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá un KML o un KMZ por su contenido, no por su extensión, y aceptará un KML de la versión 2.x o un KMZ cuyo KML principal sea `doc.kml` o, si no lo hay, el primer `.kml` de la raíz por orden alfabético.
- RF-3: SI el fichero no es un KML o un KMZ, está dañado o vacío, o no tiene ninguna marca con geometría, ENTONCES EL SISTEMA no abrirá la pantalla e indicará el motivo.
- RF-4: SI el fichero supera los 100 MB o un KMZ descomprime más de 500 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite (como S040/RF-5).
- RF-5: EL SISTEMA creará una capa de puntos y otra de líneas por cada carpeta que tenga marcas, con la ruta de la carpeta desde la raíz, separada por " / " (por ejemplo «Obra / Parcelas / Norte»), como nombre, y pondrá las marcas que no están en ninguna carpeta en capas con el nombre del fichero sin extensión.
- RF-6: EL SISTEMA listará las capas con su nombre, su tipo, el número de objetos y su extensión, y permitirá marcar cuáles se importan, todas marcadas por defecto (como S048/RF-5).

### Coordenadas y geometrías (H1)
- RF-7: EL SISTEMA leerá las coordenadas como longitud y latitud WGS84 en grados, las tomará como del datum de los cálculos del proyecto, sin ninguna transformación de datum, y las convertirá al sistema de coordenadas del proyecto.
- RF-8: EL SISTEMA importará los puntos, las líneas (`LineString`), los anillos (`LinearRing`), los polígonos y las colecciones de geometrías (`MultiGeometry`) de las marcas, y convertirá cada polígono en una línea cerrada para su contorno y en otra para cada hueco, marcadas como contorno de polígono (como S048/RF-17).
- RF-9: EL SISTEMA no importará los vínculos de red (`NetworkLink`), las imágenes sobre el suelo, de pantalla y de foto (`GroundOverlay`, `ScreenOverlay`, `PhotoOverlay`), los modelos (`Model`), los recorridos (`gx:Tour`, `gx:Track`, `gx:MultiTrack`), las geometrías vacías ni las que tengan un vértice fuera del ámbito del sistema del proyecto, y los contará como ignorados con el motivo (como S048/RF-18 y S040/RF-17).

### Nombre, atributos y color (H1)
- RF-10: EL SISTEMA usará el nombre de la marca como nombre del objeto para etiquetarlo en el mapa y nombrarlo en el replanteo y, si no tiene, su número dentro de la capa (como S048/RF-22).
- RF-11: EL SISTEMA conservará como atributos de cada objeto su descripción, sin etiquetas HTML, y sus datos extendidos (`Data` y `SimpleData`), y los mostrará en su ficha al tocarlo en el mapa.
- RF-12: EL SISTEMA dará a cada objeto el color de su estilo, convertido de ABGR a RGB, con el de `IconStyle` para los puntos y el de `LineStyle` para las líneas y los contornos, tanto si el estilo está en la marca como si viene de un `styleUrl` y, en un `StyleMap`, el estilo normal, y el color propio de la capa si no lo declara.

### Altitudes (H1)
- RF-13: DONDE alguna marca de las capas marcadas tenga altitudes en modo absoluto (`altitudeMode` `absolute`), EL SISTEMA preguntará «¿Usar las altitudes del fichero?», con «No» por defecto, y con «Sí» guardará la altitud de cada vértice como su cota, en el sistema de alturas del proyecto y sin convertirla, y avisará en la pregunta de que las altitudes de un KML suelen ir sobre el geoide EGM96.
- RF-14: EL SISTEMA importará sin cota las marcas pegadas al suelo (`clampToGround`, el modo por defecto) o relativas al suelo (`relativeToGround`), aunque sus coordenadas traigan altitud (como S040/RF-21).

### Zona, mapa y replanteo (H1)
- RF-15: EL SISTEMA propondrá la zona de importación, pedirá confirmación a más de 100 km, pedirá fijar el punto central si el proyecto no lo tiene, importará solo los objetos con algún vértice dentro de la zona, enteros, y no importará más de 500.000 objetos, todo como S048/RF-12 a RF-16.
- RF-16: EL SISTEMA mostrará las capas en el mapa como capas de diseño de origen "KML", con interruptor para mostrarlas u ocultarlas y distinguidas de los puntos del proyecto, y permitirá replantear sus puntos y líneas con S041 (como S048/RF-23 y RF-24).
- RF-17: CUANDO el usuario pulse "Importar", EL SISTEMA leerá el fichero en segundo plano, con progreso y un botón de "Cancelar", sin guardar nada del dibujo si se cancela o falla (como S048/RF-29 y RF-30; RNF-T6).

### Resumen y dibujo (H1)
- RF-18: CUANDO termine la importación, EL SISTEMA mostrará el resumen por capa (importados, fuera de la zona e ignorados con su motivo) con acceso a guardarlo o compartirlo como fichero de texto, e indicará en él que WGS84 y el datum del proyecto se han tomado como iguales (como S048/RF-25).
- RF-19: EL SISTEMA guardará las coordenadas convertidas sin redondear con el nombre del fichero y la fecha y hora de la importación, permitirá borrar el dibujo entero sin tocar ningún punto del proyecto, y lo incluirá en las copias de seguridad y los paquetes y lo anotará en el log (como S048/RF-26 a RF-28).

### Exportar a KML o KMZ (H2)
- RF-20: CUANDO el usuario elija "Exportar KML/KMZ" en el menú de un proyecto o en el de su lista de objetos, o "Exportar" con puntos seleccionados, EL SISTEMA preparará todos los puntos activos del proyecto, ocultos incluidos y nunca los de la papelera, o solo los seleccionados con las líneas y polígonos cuyos vértices estén todos entre ellos, y permitirá elegir entre KML y KMZ (como S012/RF-1 a RF-3 y S055/RF-2).
- RF-21: EL SISTEMA escribirá una marca por punto con su nombre, su descripción y como datos extendidos las columnas de "Todas las columnas" de S012/RF-6, la del código y una por atributo de código (S024/RF-21), con los valores y las unidades de S055/RF-6 y vacío lo que el punto no tiene.
- RF-22: DONDE el proyecto tenga líneas o polígonos (S025), EL SISTEMA los escribirá como líneas y como polígonos, con el contorno exterior en sentido antihorario y su primer vértice repetido, y con su nombre, su código, su número de vértices y sus atributos de código como datos extendidos (como S055/RF-7).
- RF-23: EL SISTEMA agrupará las marcas en una carpeta por código con el color de ese código y, las marcas sin código, y todas si el proyecto no tiene códigos, en una carpeta con el nombre del proyecto y sin color.

### Coordenadas y altura (H2)
- RF-24: EL SISTEMA escribirá la longitud y la latitud WGS84 en grados decimales con 9 decimales, las del datum de los cálculos del proyecto (como S012/RF-6), sin transformación de datum.
- RF-25: SI un punto no tiene posición, ENTONCES EL SISTEMA no escribirá su marca y lo contará en el resumen.
- RF-26: DONDE el usuario marque «Incluir altura», desmarcada por defecto, EL SISTEMA escribirá la altura del proyecto de cada punto y vértice en metros con 4 decimales y en modo absoluto, sin convertirla a EGM96.
- RF-27: EL SISTEMA escribirá pegada al suelo la marca que no tenga altura en alguno de sus vértices, o todas si «Incluir altura» está desmarcada, y contará en el resumen las marcas sin altura.

### Fotos en el KMZ (H3)
- RF-28: DONDE el usuario elija KMZ y marque «Incluir fotos», desmarcada por defecto, EL SISTEMA añadirá al KMZ las fotos de los puntos exportados, byte a byte como están guardadas, con el nombre `<punto>_<n>.<extensión>`, donde `<n>` es el número de la foto en su punto, con el nombre del punto saneado (solo letras sin acentos, cifras, «_» y «-») y sin repetir.
- RF-29: EL SISTEMA mostrará en la descripción de cada marca las fotos que tenga en el KMZ, después de su texto.
- RF-30: SI el fichero de una foto no se puede leer (S017b/RF-17), ENTONCES EL SISTEMA no la añadirá al KMZ y la contará en el resumen; y SI el KMZ va a pasar de 1 GB, ENTONCES EL SISTEMA avisará del tamaño antes de escribirlo y permitirá continuar o cancelar.

### Guardar y resumen (H2)
- RF-31: EL SISTEMA propondrá como nombre del fichero el del proyecto y la fecha y hora, permitirá guardarlo o compartirlo, no creará nada si no hay puntos ni dejará un fichero a medias, y no modificará ningún dato del proyecto (como S053/RF-9 a RF-13).
- RF-32: EL SISTEMA mostrará al terminar un resumen con los puntos, líneas y polígonos escritos, las carpetas, los puntos sin posición, las marcas sin altura, las fotos incluidas y omitidas y que WGS84 y el datum del proyecto se han tomado como iguales, y recordará el formato, «Incluir altura» e «Incluir fotos» para todos los proyectos (como S012/RF-21).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: importar con el selector de documentos, validando por contenido, sin guardar una copia del fichero en el proyecto (excepción justificada como en S048); exportar con `CreateDocument` o FileProvider.
  - RNF-T6: una exportación y un dibujo se escriben enteros o no se escriben.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; la distinción entre dibujo de diseño y puntos medidos, con icono y texto, nunca solo con color.
  - RNF-T9: los números de los ficheros con punto decimal y sin la configuración regional.
  - RNF-T13: ninguna operación bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura y la escritura de KML y KMZ son propias, sin librerías incompatibles con GPL-3.0.
- Constitución nº 2: las coordenadas importadas y las exportadas coinciden con pyproj a menos de 1 mm, tomando el KML como ETRS89 o REGCAN95, y la ida y vuelta difiere menos de 0,1 mm.
- Exportar 10.000 puntos a KML tarda menos de 5 s en el móvil de referencia.
- Un KML de 100 MB se lee y se recorta a la zona en menos de 30 s, por flujo, sin cargarlo entero en memoria, y cambiar el radio de la zona actualiza el recuento en menos de 2 s.
- Las fotos del KMZ son byte a byte las guardadas; 100 fotos de 1 MB se escriben en menos de 10 s.
- El KML y el KMZ exportados se abren en QGIS y en un descompresor sin errores, con el mismo número de marcas, las mismas coordenadas y las mismas fotos que el proyecto.

## Casos límite
| Caso | RF |
|---|---|
| KML dañado, vacío, sin marcas o que no es un KML | RF-3 |
| KMZ con varios KML, con imágenes o sin KML | RF-2, RF-3 |
| Fichero de más de 100 MB o KMZ que descomprime más de 500 MB | RF-4 |
| Carpetas anidadas, o marcas sin carpeta | RF-5 |
| Coordenadas de España tratadas como WGS84 | RF-7, RF-18, RF-24 |
| `NetworkLink`, `GroundOverlay`, `Model`, recorridos o geometrías vacías | RF-9 |
| Polígono con huecos o `MultiGeometry` | RF-8 |
| Marca sin nombre | RF-10 |
| Descripción con HTML | RF-11 |
| `StyleMap`, estilo compartido por `styleUrl` o sin estilo | RF-12 |
| Altitud absoluta, pegada al suelo o relativa | RF-13, RF-14 |
| Capa lejos del proyecto, o proyecto sin punto central | RF-15 |
| Cancelar o fallar al escribir | RF-17, RF-31 |
| Exportar sin puntos | RF-31 |
| Proyecto sin códigos, o puntos con y sin código | RF-23 |
| Proyecto en proyectadas, en pies o en geográficas | RF-24 |
| Punto sin posición | RF-25 |
| Punto o vértice sin altura con «Incluir altura» | RF-27 |
| Punto con 10 fotos, o sin ninguna | RF-28 |
| Dos puntos con nombre igual o con símbolos en el nombre | RF-28 |
| Foto cuyo fichero falta, o KMZ de más de 1 GB | RF-30 |

## Fuera de alcance
- Las fotos de los puntos en un ZIP suelto → S056.
- Estilos de icono propios, imágenes sobre el suelo y recorridos en el KML exportado → no previsto.
- Fotos o enlaces a fotos en un KML sin comprimir → no previsto; solo van dentro de un KMZ.
- Importar las fotos o las imágenes de un KMZ → no previsto.
- Convertir entre WGS84 y ETRS89 con Helmert → S033.
- Convertir las altitudes entre EGM96 y el sistema de alturas del proyecto → no previsto.
- Convertir lo importado en puntos, líneas o polígonos del proyecto → no previsto; los puntos de un SIG se importan como CSV (S028).
- Exportar las capas de diseño importadas, las superficies o los ejes → no previsto.
- Reducir las fotos o quitarles la ubicación al exportarlas → no previsto (S017b).
- Editar los objetos importados o escribir sobre el fichero original → no previsto.
- Shapefile → S030 y S055; DXF → S029; GeoPackage → S048 y S053.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- KML y KMZ de ejemplo hechos con QGIS (y con Google Earth, si el usuario aporta alguno) que se leen y coinciden con QGIS a menos de 1 mm: carpetas anidadas, marcas sin carpeta, polígonos con hueco, `MultiGeometry`, `ExtendedData`, colores por estilo en la marca, por `styleUrl` y por `StyleMap`, y altitudes en los tres modos.
- Las coordenadas exportadas comparadas con pyproj (≤ 1 mm) y la ida y vuelta exportar → importar (< 0,1 mm).
- Un KML y un KMZ exportados con puntos, líneas, polígonos y fotos se abren en QGIS con las mismas marcas y coordenadas, y las fotos del KMZ coinciden byte a byte con las guardadas.
- Demo en el emulador:
  - importar un KMZ con carpetas anidadas, marcar solo algunas capas y replantear un punto y una línea;
  - responder «Sí» y «No» a las altitudes y ver la diferencia;
  - exportar KML y KMZ de un proyecto con y sin selección, con y sin altura, con fotos;
  - abrir lo exportado en QGIS.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: los 100 MB, los 500.000 objetos y los 5 km de la zona son los de S048 y no salen de un KML real; se revisan con uno de catastro o de Google Earth.]
- [SUPOSICIÓN: el KML exportado de 10.000 puntos con todas las columnas pesará varias decenas de MB; no se fija peso máximo y se mide en el plan. El KMZ lo comprime.]
- [SUPOSICIÓN: «Incluir fotos» sale desmarcada por defecto por su peso (0,5 a 1 MB por foto, S017b/RF-18).]
- [SUPOSICIÓN: el color de un punto exportado tiñe el icono por defecto de Google Earth, sin icono propio (RF-23).]
- [SUPOSICIÓN: un `StyleMap` se lee con su estilo normal y el de resaltado se ignora (RF-12).]
- [SUPOSICIÓN: el `Document` raíz no aparece en el nombre de la capa y sus marcas sueltas toman el nombre del fichero (RF-5).]
- [SUPOSICIÓN: las marcas sin posición no se escriben, aunque el KML admite marcas sin geometría, porque un visor no las muestra (RF-25).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: la fila S031 pasa a `spec` y a solo KML/KMZ ("Importar un KML o KMZ como capa de diseño… y exportar a KML o KMZ, con fotos opcionales en el KMZ"), con "Depende de" 048, 040, 006, 012, 024, 025, 017b; las fotos en un ZIP pasan a una fila nueva, S056.
- `docs/glosario.md`: se añaden "KML/KMZ" (`Kml`) y "Carpeta de KML" (`KmlFolder`); "Origen del dibujo" (`CadOrigin`) gana el valor `KML`.
- `docs/inventario-funcional.md`: las fotos en ZIP pasan de S031 a S056.
- S017b: «Exportar las fotos en un ZIP» pasa a S056, «fotos o enlaces en KML/KMZ» queda en S031 solo para el KMZ y «una columna con las fotos en el CSV» pasa a «no previsto».
- S019: «Informe con fotos de los puntos → S031» pasa a «no previsto».
- S028: «Importar las fotos de los puntos → S017b y S031» pasa a «S017b».
- S053: «Exportar fotos de los puntos dentro del fichero → S031» pasa a «no previsto» (S053 no se toca sin tu visto bueno).
- S012: sin cambios; la columna `Photos` que proponía el borrador no se añade.
