# Spec 056 — Exportar las fotos de los puntos

> Hito: H4 · Depende de: S017b (las fotos), S012 (qué puntos se exportan) · Estado: aprobada (12/11/2026)

## Contexto y objetivo
Las fotos de los puntos (S017b) viven en el almacenamiento privado de la app y hoy solo salen dentro de una copia de seguridad o de un paquete de proyecto, que no se abren fuera de TopoPhone. El técnico que recibe el trabajo, o el cliente que pide «las fotos de los vértices», necesita las imágenes sueltas y saber de qué punto es cada una.

Esta spec exporta las fotos de los puntos del proyecto, o de los que se seleccionen, a un ZIP, con un índice que relaciona cada foto con su punto. Las fotos se escriben tal como están guardadas. No modifica nada del proyecto (constitución nº 1). Las fotos dentro de un KMZ son S031. El CSV de puntos de S012 no cambia: el vínculo entre foto y punto va solo en el índice del ZIP.

## Usuarios / actores
- Técnico o cliente que recibe las fotos del trabajo.
- Topógrafo que entrega las fotos junto a sus puntos.

## Historias de usuario
- H1: Como técnico quiero sacar las fotos de los puntos de un proyecto, o de unos puntos seleccionados, en un ZIP.
- H2: Como técnico quiero saber de qué punto es cada foto, y cuándo y cómo se hizo, sin abrirlas una a una.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Foto**: la de un punto (S017b), tal como está guardada: reducida a 2.048 px como máximo y con sus metadatos.
- **ZIP de fotos**: el ZIP con las fotos y su índice. El **índice** es el fichero `fotos.csv` de su raíz.

### Qué fotos (H1)
- RF-1: CUANDO el usuario elija "Exportar fotos" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA preparará las fotos de todos los puntos activos del proyecto, ocultos incluidos, y nunca las de los puntos de la papelera (como S012/RF-1 y RF-3).
- RF-2: CUANDO el usuario elija "Exportar fotos" con puntos seleccionados en la lista de objetos, EL SISTEMA preparará solo las fotos de esos puntos (como S012/RF-2).
- RF-3: MIENTRAS la pantalla de exportar fotos esté abierta, EL SISTEMA mostrará cuántas fotos de cuántos puntos se exportarán y su tamaño total.
### El ZIP y su índice (H1, H2)
- RF-4: EL SISTEMA escribirá cada foto en el ZIP byte a byte como está guardada, sin volver a codificar la imagen y con todos sus metadatos (S017b/RF-18).
- RF-5: EL SISTEMA dará a cada foto el nombre `<punto>_<n>.<extensión>`, con el nombre del punto saneado (solo letras sin acentos, cifras, «_» y «-», y «punto» si no queda nada), `<n>` el número de la foto en su punto (el de S017b/RF-9) y la extensión del fichero guardado, y distinguirá con un número al final del nombre del punto los nombres que se repitan, sin distinguir mayúsculas.
- RF-6: EL SISTEMA escribirá en el índice, con una primera fila de cabecera y una fila por foto, su nombre en el ZIP, el nombre del punto, el número de la foto, su fecha y hora, su origen (cámara o galería) y su tamaño en bytes.
- RF-7: EL SISTEMA escribirá el índice en UTF-8 sin BOM, con la coma como separador, con entre comillas dobles los textos que tengan coma, comillas o un salto de línea (como S012/RF-15), con la fecha y hora en ISO 8601 con la Z de UTC y los números con punto decimal.

### Errores y guardado (H1)
- RF-8: SI no hay ninguna foto que exportar, ENTONCES EL SISTEMA lo indicará y no creará el ZIP (como S012/RF-4).
- RF-9: SI el fichero de una foto no se puede leer (S017b/RF-17), ENTONCES EL SISTEMA no la añadirá al ZIP ni al índice y la contará en el resumen.
- RF-10: CUANDO el usuario pulse "Guardar" o "Compartir" y el ZIP vaya a pasar de 1 GB, EL SISTEMA avisará del tamaño y permitirá continuar o cancelar.
- RF-11: SI no hay espacio para el ZIP o no se puede escribir, ENTONCES EL SISTEMA indicará que no se ha exportado y no dejará un fichero a medias (RNF-T6).
- RF-12: EL SISTEMA propondrá como nombre del ZIP el del proyecto, «fotos» y la fecha y hora, y permitirá guardarlo con el selector de ficheros del móvil o compartirlo con las apps del móvil (como S012/RF-17 a RF-19).
- RF-13: EL SISTEMA mostrará al terminar un resumen con las fotos y los puntos escritos, las fotos omitidas y el tamaño del ZIP.
- RF-14: EL SISTEMA no modificará ningún dato del proyecto ni ninguna foto al exportar.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con `CreateDocument` o compartir mediante FileProvider.
  - RNF-T6: el ZIP se escribe entero o no se escribe.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp.
  - RNF-T9: los números del índice con punto decimal y sin la configuración regional.
  - RNF-T12: la exportación va al log (S014/RF-5) sin nombres de puntos ni rutas de ficheros.
  - RNF-T13: exportar no bloquea el hilo principal más de 16 ms; el ZIP se escribe por flujo, sin cargar las fotos enteras en memoria.
  - RNF-T14: la escritura del ZIP y del índice no usa librerías incompatibles con GPL-3.0.
- Las fotos del ZIP son byte a byte las guardadas.
- Exportar 100 fotos de 1 MB tarda menos de 10 s en el móvil de referencia.
- El ZIP se abre en un descompresor y el índice en QGIS sin errores, con las mismas fotos y los mismos datos que el proyecto.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin ninguna foto, o selección sin fotos | RF-8 |
| Puntos ocultos | RF-1 |
| Puntos de la papelera | RF-1 |
| Selección de algunos puntos | RF-2 |
| Punto con 10 fotos, o sin ninguna | RF-5, RF-6 |
| Dos puntos con nombre igual, o con símbolos o acentos | RF-5 |
| Nombre de punto que se queda vacío al sanearlo | RF-5 |
| Foto de una galería sin metadatos, o con la ubicación de otro sitio | RF-4 |
| Foto cuyo fichero falta o está dañado | RF-9 |
| ZIP de más de 1 GB | RF-10 |
| Sin espacio al guardar | RF-11 |
| Nombre de punto con coma o comillas en el índice | RF-7 |

## Fuera de alcance
- Fotos dentro de un KMZ → S031.
- Una columna con las fotos en el CSV de puntos → no previsto; el vínculo va en el índice.
- Reducir las fotos o quitarles la ubicación al exportarlas → no previsto (S017b).
- Partir el ZIP en varios si pasa de 1 GB → no previsto.
- Compartir una foto suelta desde la ficha → no previsto (S017b).
- Exportar las fotos de los puntos de la papelera → no previsto.
- Informe de replanteo con fotos → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un ZIP exportado con varios puntos, un punto con 10 fotos y dos puntos con el mismo nombre se abre en un descompresor con las mismas fotos byte a byte, y su índice se abre en QGIS como tabla.
- Demo en el emulador: exportar las fotos de un proyecto y de una selección, ver el recuento antes de guardar, guardar y compartir, y comprobar el índice.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el número `<n>` de una foto sigue el orden en que se añadió al punto (S017b/RF-9); no cambia si se borra otra foto del punto.]
- [SUPOSICIÓN: el aviso de 1 GB es fijo y el ZIP no se parte en varios (RF-10).]
- [SUPOSICIÓN: no hay opciones que recordar, porque el ZIP siempre lleva todas las fotos preparadas y su índice.]
- [SUPOSICIÓN: la cabecera del índice y los dos valores de «origen» se fijan en el plan, en inglés como las de S012.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S056 en H4, en estado `spec`.
- `docs/glosario.md`: se añaden "ZIP de fotos" (`PhotoArchive`) e "Índice de fotos" (`fotos.csv`).
- `docs/inventario-funcional.md`: las fotos en ZIP pasan de S031 a S056.
- S017b: «Exportar las fotos en un ZIP» pasa a S056 y «una columna con las fotos en el CSV» a «no previsto» (los mismos cambios que lista S031).
