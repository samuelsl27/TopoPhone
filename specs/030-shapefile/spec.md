# Spec 030 — Importar un Shapefile

> Hito: H4 · Depende de: S048, S040, S006 (punto central) · Estado: aprobada (11/11/2026)

## Contexto y objetivo
El Shapefile sigue siendo el formato que piden las administraciones y los técnicos SIG: parcelas, redes y límites llegan como un ZIP con `.shp`, `.shx`, `.dbf` y `.prj`, o salen de QGIS como una carpeta con esos ficheros sueltos. Con lo que hay, hay que convertirlos a DXF o a GeoPackage con QGIS antes de verlos en la app.

Esta spec trae los Shapefiles a la app como **capa de diseño**, igual que un GeoPackage (S048) o un DXF (S040, S041): se ven en el mapa, se tocan sus elementos para ver los atributos y se replantean. Como un Shapefile puede contener un municipio entero, solo se importa lo que cae dentro de una zona alrededor del **centro del proyecto** (S006/RF-32 a RF-38). Se elige un ZIP o los ficheros sueltos. La exportación a Shapefile es otra spec (S055).

Lo importado es **referencia de diseño**, no dato de campo (constitución nº 1): no se mezcla con los puntos medidos, no aparece en la lista de puntos ni en las exportaciones del proyecto y no se edita.

## Usuarios / actores
- Técnico SIG que entrega Shapefiles al topógrafo.
- Topógrafo que replantea parcelas o redes recibidas.

## Historias de usuario
- H1: Como topógrafo quiero elegir un ZIP o los ficheros sueltos de un Shapefile, ver qué capas trae y su sistema de coordenadas, y marcar las que me interesan.
- H2: Como topógrafo quiero que la app sepa en qué sistema de coordenadas está cada capa y la reproyecte, o me lo pregunte si no lo sabe.
- H3: Como topógrafo quiero importar solo lo que cae cerca del centro de mi proyecto, para no cargar un municipio entero.
- H4: Como topógrafo quiero ver los elementos en el mapa, tocarlos para ver sus atributos y replantearlos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Shapefile**: el conjunto de ficheros `.shp` (geometría), `.shx` (índice), `.dbf` (atributos), `.prj` (sistema de coordenadas) y `.cpg` (codificación) con el mismo nombre. Una **capa** es un Shapefile, identificado por su `.shp`.
- **Capa de diseño**, **dibujo**, **zona de importación**, **centro del proyecto**, **campo de nombre** y **contorno de polígono**: los de S040 y S048.

### Elegir los ficheros y las capas (H1)
- RF-1: CUANDO el usuario elija "Importar Shapefile" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil, que permitirá marcar un ZIP o varios ficheros sueltos, y, con lo elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá un ZIP y cada `.shp` por su contenido, no por su extensión, y asociará a cada `.shp` el `.shx`, el `.dbf`, el `.prj` y el `.cpg` que tengan su mismo nombre, sin distinguir mayúsculas y, dentro de un ZIP, en la misma carpeta.
- RF-3: SI el ZIP está dañado o vacío, o lo elegido no contiene ningún `.shp` legible, ENTONCES EL SISTEMA no abrirá la pantalla e indicará el motivo; y si lo elegido son ficheros sueltos sin `.shp`, indicará que hay que elegir también el `.shp` o el ZIP.
- RF-4: SI el usuario elige más de un ZIP, o un ZIP junto a otros ficheros, ENTONCES EL SISTEMA no abrirá la pantalla e indicará que hay que elegir un solo ZIP o ficheros sueltos.
- RF-5: SI hay un `.shx`, `.dbf`, `.prj` o `.cpg` sin un `.shp` de su mismo nombre, ENTONCES EL SISTEMA lo ignorará y lo avisará en la pantalla con su nombre.
- RF-6: SI los ficheros elegidos suman más de 100 MB o el contenido descomprimido de un ZIP supera los 500 MB, ENTONCES EL SISTEMA no los importará e indicará el límite (como S040/RF-5).
- RF-7: EL SISTEMA listará las capas con su nombre (el del fichero sin extensión y, si dos capas se llaman igual, con su carpeta dentro del ZIP), su tipo (puntos, líneas o polígonos), el número de objetos y su sistema de coordenadas, y permitirá marcar cuáles se importan, todas marcadas por defecto (como S048/RF-5).

### Sistema de coordenadas (H2)
- RF-8: EL SISTEMA leerá el sistema de coordenadas y su unidad del `.prj` de cada capa y, si es uno de los sistemas disponibles en la app (S008), lo propondrá y reproyectará las coordenadas al del proyecto (como S048/RF-8).
- RF-9: SI una capa no tiene `.prj` o su sistema no es uno de los disponibles, ENTONCES EL SISTEMA no importará la capa hasta que el usuario elija uno de los sistemas disponibles para ella, y propondrá la unidad de ese sistema (como S048/RF-9 y RF-10).
- RF-10: MIENTRAS la pantalla de importación esté abierta, EL SISTEMA mostrará la extensión de cada capa marcada en el sistema del proyecto y la distancia entre su centro y el centro del proyecto, y pedirá confirmación antes de importar si supera los 100 km (como S048/RF-12).

### Zona y contenido (H3)
- RF-11: EL SISTEMA propondrá como zona de importación un radio de 5 km alrededor del centro del proyecto y permitirá cambiarlo entre 100 m y 100 km, o elegir "Todo el contenido" (como S048/RF-13).
- RF-12: SI el proyecto no tiene centro y el usuario no ha elegido "Todo el contenido", ENTONCES EL SISTEMA pedirá fijar el punto central del proyecto (S006/RF-33) antes de importar (como S048/RF-14).
- RF-13: EL SISTEMA importará solo los objetos que tengan algún vértice dentro de la zona de importación, enteros y sin cortarlos (como S048/RF-15).
- RF-14: SI el recuento de objetos que se importarían supera los 500.000, ENTONCES EL SISTEMA no importará e indicará que hay que reducir la zona o las capas (como S048/RF-16).
- RF-15: EL SISTEMA importará los objetos de tipo punto, multipunto, línea y polígono, con Z, con M o sin ellas, convertirá cada polígono en una línea cerrada para su contorno y en otra para cada hueco, marcadas como contorno de polígono, e ignorará la M (como S048/RF-17).
- RF-16: EL SISTEMA no importará los objetos vacíos, los `MultiPatch`, los marcados como borrados en el `.dbf` ni los que tengan un vértice fuera del ámbito del sistema del proyecto, y los contará como ignorados con el motivo (como S048/RF-18 y S040/RF-17).
- RF-17: DONDE las capas traigan Z, EL SISTEMA preguntará "¿Usar las cotas de la capa?", con "Sí" por defecto, y con "Sí" guardará la Z de cada vértice como su cota, en el sistema de alturas del proyecto y sin convertirla (como S048/RF-19).
- RF-18: EL SISTEMA leerá el `.dbf` en la codificación que declare el `.cpg` y, sin `.cpg`, en UTF-8 y, si el texto no es UTF-8 válido, en Windows-1252, y permitirá elegir para cada capa entre UTF-8 y Windows-1252.
- RF-19: SI una capa no tiene `.shx` o no tiene `.dbf`, ENTONCES EL SISTEMA la importará igualmente, y sin atributos si falta el `.dbf`.
- RF-20: SI el `.dbf` tiene menos registros que objetos el `.shp`, ENTONCES EL SISTEMA importará los objetos sin registro sin atributos y lo avisará en el resumen.

### Atributos, mapa y replanteo (H4)
- RF-21: EL SISTEMA conservará con cada objeto todos los atributos de su capa, con el tipo que tengan en el `.dbf` (texto, número, fecha o lógico), y los mostrará en su ficha al tocarlo en el mapa (como S048/RF-20).
- RF-22: EL SISTEMA propondrá como campo de nombre de cada capa el que se llame "name", "nombre", "id" o "codigo" (sin distinguir mayúsculas), permitirá elegir otro o ninguno, y lo usará para etiquetar el objeto en el mapa y para nombrarlo en el replanteo y, sin campo de nombre, usará su número dentro de la capa (como S048/RF-21 y RF-22).
- RF-23: EL SISTEMA mostrará las capas importadas en el mapa como capas de diseño, agrupadas por tipo (puntos y líneas), con un color propio por capa, con interruptor para mostrarlas u ocultarlas y distinguidas de los puntos del proyecto (como S048/RF-23).
- RF-24: EL SISTEMA permitirá replantear los puntos y las líneas importados como los de un dibujo DXF, con S041.

### Resumen, dibujo y borrado
- RF-25: CUANDO termine la importación, EL SISTEMA mostrará el resumen por capa (importados, fuera de la zona e ignorados con su motivo) con acceso a guardarlo o compartirlo como fichero de texto (como S048/RF-25).
- RF-26: EL SISTEMA guardará las coordenadas tal como se convierten, sin redondear, y asociará a cada dibujo con origen "Shapefile" el nombre del ZIP (o el del primer `.shp` por orden alfabético, con ficheros sueltos) y la fecha y hora de la importación (como S048/RF-26).
- RF-27: CUANDO el usuario borre un dibujo importado y confirme, EL SISTEMA lo quitará entero y no modificará ningún punto del proyecto (como S048/RF-27).
- RF-28: EL SISTEMA incluirá los dibujos importados en las copias de seguridad y en los paquetes de proyecto, y anotará en el log cada importación y cada borrado sin nombres ni coordenadas (como S048/RF-28; S013, S014/RF-5).
- RF-29: CUANDO el usuario pulse "Importar", EL SISTEMA leerá los ficheros en segundo plano, con progreso y un botón de "Cancelar", sin bloquear el resto de la app (como S048/RF-29).
- RF-30: SI el usuario cancela, o no se puede escribir en el almacenamiento, ENTONCES EL SISTEMA no guardará nada del dibujo e indicará que no se ha importado (RNF-T6; como S048/RF-30).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: los ficheros se eligen con el selector de documentos y se validan por su contenido; la importación no deja una copia de los ficheros en el proyecto, solo los datos que ha leído (excepción justificada como en S048 y S028).
  - RNF-T6: la importación y el borrado de un dibujo se guardan en una sola transacción con durabilidad completa.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; la distinción entre dibujo de diseño y puntos medidos, con icono y texto, nunca solo con color.
  - RNF-T9: coordenadas leídas en la unidad de su sistema y mostradas en la del proyecto.
  - RNF-T13: leer y cambiar la zona no bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura del ZIP, del `.shp`, del `.dbf` y del `.prj` no usa librerías incompatibles con GPL-3.0.
- Constitución nº 2: las coordenadas reproyectadas coinciden con pyproj a menos de 1 mm y la ida y vuelta difiere menos de 0,1 mm.
- Un ZIP de 100 MB se lee y se recorta a la zona en menos de 20 s en el móvil de referencia, y cambiar el radio de la zona actualiza el recuento en menos de 2 s.
- La lectura va por flujo: no se carga el ZIP ni el `.shp` enteros en memoria.

## Casos límite
| Caso | RF |
|---|---|
| ZIP dañado o vacío, sin `.shp` legible o solo `.dbf`/`.prj` sueltos | RF-3 |
| Más de un ZIP, o un ZIP junto a ficheros sueltos | RF-4 |
| `.dbf`, `.prj` o `.shx` sin su `.shp` | RF-5 |
| Más de 100 MB o más de 500 MB descomprimidos | RF-6 |
| Dos capas con el mismo nombre en carpetas distintas | RF-7 |
| Capa sin `.prj` o en un sistema desconocido | RF-9 |
| Capa lejos del proyecto (más de 100 km) | RF-10 |
| Proyecto sin punto central, sin puntos y sin encuadre | RF-12 |
| Capa muy grande, más de 500.000 objetos en la zona | RF-14 |
| Polígonos con huecos o multipunto | RF-15 |
| Objetos vacíos, `MultiPatch` o borrados | RF-16 |
| Objetos sin Z con "Usar las cotas" activado | RF-17 |
| `.dbf` en Windows-1252 sin `.cpg` | RF-18 |
| Capa sin `.shx` o sin `.dbf` | RF-19 |
| `.dbf` con menos registros que objetos | RF-20 |
| Capa sin campo de nombre | RF-22 |
| Cancelar o fallar al escribir | RF-30 |

## Fuera de alcance
- Exportar a Shapefile → S055.
- Convertir lo importado en puntos, líneas o polígonos del proyecto → no previsto; los puntos de un SIG se importan como CSV (S028).
- Elegir más de un ZIP a la vez → no previsto.
- Valores M, `MultiPatch`, campos memo del `.dbf` (`.dbt`), codificaciones que no sean UTF-8 y Windows-1252, y `.qpj` → no previsto.
- Estilos y simbología (`.qml`, `.sld`, `.lyr`), índices (`.sbn`, `.sbx`, `.qix`) y metadatos (`.xml`) que acompañen al Shapefile → no previsto; se ignoran.
- Campo de altura de un atributo como cota → no previsto.
- Editar los objetos importados o escribir sobre los ficheros originales → no previsto.
- GeoPackage → S048 y S053; DXF → S029 y S040; KML y KMZ → S031.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Shapefiles de ejemplo hechos con QGIS que se leen y coinciden con QGIS a menos de 1 mm: puntos, líneas, polígonos con hueco, con Z y con M, en ETRS89 UTM, REGCAN95 y ED50, con y sin `.cpg`, con un `.dbf` en Windows-1252, sin `.prj`, sin `.shx` y sin `.dbf`, en un ZIP con varias capas y como ficheros sueltos.
- Demo en el emulador:
  - importar un ZIP de parcelas con varias capas y marcar solo algunas;
  - importar los ficheros sueltos de un Shapefile;
  - ver una capa sin `.prj` y elegir su sistema;
  - fijar el punto central de un proyecto nuevo, cambiar la zona y ver el recuento;
  - ver los objetos en el mapa, tocar uno y ver su ficha con atributos;
  - replantear un punto y una línea importados.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: los 500.000 objetos (RF-14), los 5 km de la zona (RF-11) y los 100 MB (RF-6) son los de S048 y no salen de un fichero real; se revisan con uno de catastro.]
- [SUPOSICIÓN: con ficheros sueltos el dibujo toma el nombre del primer `.shp` por orden alfabético (RF-26).]
- [SUPOSICIÓN: los objetos marcados como borrados en el `.dbf` se cuentan como ignorados y no se importan, como hacen QGIS y GDAL (RF-16).]
- [SUPOSICIÓN: solo se ofrecen UTF-8 y Windows-1252 al elegir la codificación del `.dbf`; un `.cpg` con otra codificación se lee como Windows-1252 y se avisa (RF-18).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: la fila S030 pasa a `spec` y a solo importación ("Importar un Shapefile desde un ZIP o ficheros sueltos como capa de diseño"), con "Depende de" 048, 040, 006; la exportación pasa a una fila nueva, S055.
- `docs/glosario.md`: se añade "Shapefile" (`Shapefile`); "Origen del dibujo" (`CadOrigin`) gana el valor `SHAPEFILE`.
- S048 y S040: los dibujos pasan a poder venir también de un Shapefile; sin cambios en su texto.
