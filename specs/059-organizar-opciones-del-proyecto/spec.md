# Spec 059 — Organizar las opciones del proyecto

> Hito: H9 · Depende de: S011, S006, S005, S012, S020, S025, S038, S039, S042, S049, S058 · Estado: spec (entrevista 23/11/2026)

## Contexto y objetivo
Cada spec que añadió una función al proyecto (exportar, importar, COGO, base shift, localización…) la puso en el menú «⋮» de la lista de Objetos. Hoy ese menú es una lista plana de 21 opciones que no cabe en la pantalla (hay que desplazarla para llegar a COGO, Base shift, Localización, el informe de replanteo o la papelera), mezcla cinco tipos de cosas sin orden de uso y no explica las funciones que no se entienden por su nombre. La propia lista de Objetos pone las líneas y polígonos, con tres filas de texto cada una, delante de los puntos, y «Datos del proyecto» solo informa del sistema de coordenadas y de los recuentos: no dice qué está activo (terreno, localización, base shift, superficie, eje, capas) ni deja llegar a ello.

La guía de diseño consultada (Material Design, Android, NN/g) coincide en que un menú desplegable es para lo secundario y poco frecuente, debe ser corto, agrupar lo parecido y no sustituir a la navegación, que lo que se usa a menudo no va escondido en un menú, que la información se revela por pasos (una hoja o un menú breve por grupo) y que las zonas táctiles de al menos 48 dp (56 dp aquí, RNF-T8) y los controles con texto además de icono sirven a quien trabaja con guantes, sol y una mano.

Esta spec reorganiza **cómo se presentan** las opciones del proyecto: tres menús cortos agrupados por lo que hacen, una lista de Objetos con pestañas y un resumen de lo activo en «Datos del proyecto». No añade funciones ni cambia ningún comportamiento: reubica lo que ya existe. Añade además una regla transversal de presentación de opciones (RNF-T15) para las specs que vengan.

## Usuarios / actores
- Topógrafo en campo o en el coche, con el móvil en una mano, que busca una función del proyecto (importar un plano, exportar los puntos, calcular con COGO, activar una localización).
- Quien revisa un proyecto y quiere saber de un vistazo qué está activo.

## Historias de usuario
- H1: Como topógrafo quiero encontrar importar, exportar y las herramientas de cálculo en menús cortos y agrupados, sin desplazarme por una lista de veinte opciones.
- H2: Como topógrafo quiero ver mis puntos en cuanto abro Objetos, y mis líneas aparte, sin que unos tapen a los otros.
- H3: Como topógrafo quiero ver en «Datos del proyecto» qué está activo (terreno, localización, base shift…) y llegar a ello con un toque.
- H4: Como persona que escribe specs quiero una regla clara de cómo se presentan las opciones para no volver a llenar un menú.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Barra de grupos**: la fila de tres botones «Importar», «Exportar» y «Herramientas» de la lista de Objetos.
- **Menú de grupo**: el menú corto que abre cada botón de la barra de grupos.
- **Activo**: algo que el proyecto usa ahora y cambia lo que se ve o se calcula: el plano de terreno (S058), la localización (S039), el base shift (S020), la superficie (S042), el eje (S049) y las capas del mapa visibles (S009, S036).

### Menús de grupo (H1)
- RF-1: EL SISTEMA mostrará en la lista de Objetos, bajo la barra superior, la barra de grupos con tres botones de igual ancho y con icono y texto: «Importar», «Exportar» y «Herramientas».
- RF-2: CUANDO el usuario pulse «Importar», EL SISTEMA abrirá un menú de grupo con una fila por formato, en este orden: CSV/TXT, DXF, superficie, ráster, GeoPackage, Shapefile, KML/KMZ y eje.
- RF-3: CUANDO el usuario pulse «Exportar», EL SISTEMA abrirá un menú de grupo con una fila por formato, en este orden: CSV, GeoPackage, DXF, Shapefile, KML/KMZ y fotos.
- RF-4: CUANDO el usuario pulse «Herramientas», EL SISTEMA abrirá un menú de grupo con las filas, en este orden: línea desde puntos, ángulo entre tres puntos, COGO, base shift y localización.
- RF-5: EL SISTEMA mostrará cada fila de «Herramientas» con su icono, su nombre y una descripción de una línea de lo que hace, y cada fila de «Importar» y «Exportar» con su icono y el nombre del formato, sin repetir el verbo del grupo.
- RF-6: EL SISTEMA dejará en el menú «⋮» de la lista de Objetos solo «Informe de replanteo» y «Papelera de puntos».
- RF-7: EL SISTEMA abrirá cada opción de los menús con el mismo comportamiento, la misma pantalla y los mismos textos de sus specs de origen (S012, S029, S030, S031, S040, S042, S045, S048, S049, S053, S055, S056, S025, S026, S038, S020, S039, S019): ninguna se pierde ni cambia.
- RF-8: EL SISTEMA dará a cada fila de un menú de grupo y del «⋮» una etiqueta de accesibilidad con el verbo y el formato completos (por ejemplo, «Importar DXF»).
- RF-9: CUANDO el usuario pulse fuera de un menú de grupo o la tecla «Atrás», EL SISTEMA lo cerrará sin hacer nada.
- RF-10: EL SISTEMA mostrará cada menú de grupo y el «⋮» enteros, sin desplazamiento, en una pantalla de 640 dp de alto con el tamaño de letra al 100 %.

### Lista de Objetos (H2)
- RF-11: EL SISTEMA mostrará la lista de Objetos con dos pestañas: «Puntos» y «Líneas y polígonos», cada una con el número de elementos que contiene.
- RF-12: CUANDO el usuario abra la lista de Objetos, EL SISTEMA mostrará la pestaña «Puntos».
- RF-13: EL SISTEMA conservará la pestaña elegida al girar el dispositivo y al restaurar la app tras cerrar su proceso (como S001/RF-7 y S001/RF-8).
- RF-14: EL SISTEMA mostrará la búsqueda de puntos y los filtros por código solo en la pestaña «Puntos», con el comportamiento de S011 y S024, y los filtros por código en una sola fila que se desplaza de lado.
- RF-15: EL SISTEMA mostrará cada línea o polígono con como mucho dos filas de texto: el nombre, y el tipo con el número de vértices y el código.
- RF-16: SI el proyecto no tiene líneas ni polígonos, ENTONCES EL SISTEMA mostrará en la pestaña «Líneas y polígonos» un texto que lo diga.
- RF-17: EL SISTEMA mantendrá en la pestaña «Puntos» la barra de selección y sus acciones (ocultar, mostrar, borrar, exportar la selección, corregir el jalón y cambiar el código) y en la pestaña «Líneas y polígonos» sus acciones por fila (mostrar u ocultar, abrir, replantear, borrar), todas como están.

### Lo activo en «Datos del proyecto» (H3)
- RF-18: EL SISTEMA mostrará en «Datos del proyecto» una sección «Activo en este proyecto» con una fila por cada elemento activo, en este orden: terreno, localización, base shift, superficie, eje y capas del mapa.
- RF-19: EL SISTEMA mostrará en cada fila el nombre del elemento y su estado en texto: del terreno, su factor; de la localización, su RMS horizontal o el nombre del SC guardado; del base shift, el nombre de su punto conocido; de la superficie y del eje, su nombre; de las capas, cuántas hay visibles de cuántas.
- RF-20: CUANDO el usuario pulse una fila de la sección, EL SISTEMA abrirá la pantalla donde ese elemento se gestiona (terreno: el formulario del proyecto en su sección de terreno; localización; base shift; superficie; eje; capas).
- RF-21: SI el proyecto no tiene nada activo, ENTONCES EL SISTEMA no mostrará la sección.
- RF-22: EL SISTEMA actualizará la sección al activar o desactivar un elemento sin reabrir la pantalla.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T7: los textos de los menús, las pestañas y la sección nueva, con sus descripciones, en recursos de español y de inglés.
  - RNF-T8: zonas táctiles de 56 dp en los botones, las pestañas y las filas; etiquetas de TalkBack en todos los controles; el estado de lo activo, en texto además de color.
  - RNF-T13: abrir un menú de grupo o cambiar de pestaña no bloquea el hilo principal más de 16 ms.
- Específicos:
  - Ningún menú de grupo ni el «⋮» tiene más de 8 filas.
  - Con el tamaño de letra ampliado al 130 %, los menús se pueden desplazar, pero ningún texto se corta ni deja de ser alcanzable.
  - Con un ancho de 360 dp, los tres botones de la barra de grupos se ven completos, con su texto.

## Casos límite
| Caso | RF |
|---|---|
| Pantalla de 360 dp de ancho | RF-1, RNF específico |
| Letra ampliada al 130 % | RF-10, RNF específico |
| Girar el dispositivo o restaurar la app | RF-13 |
| Proyecto sin líneas | RF-16 |
| Proyecto sin nada activo | RF-21 |
| Activar algo con «Datos del proyecto» abierto | RF-22 |
| Selección de puntos activa | RF-17 |

## Fuera de alcance
- Revisar el recolector, el replanteo, el formulario del proyecto, el receptor y Ajustes → pendiente de una auditoría posterior; esta spec solo fija la regla (RNF-T15) para ellas.
- Añadir, quitar o cambiar el comportamiento de cualquier opción → no previsto.
- Cambiar los iconos de la barra inferior, el mapa o la barra superior del proyecto → no previsto.
- Una pantalla propia de «Herramientas del proyecto» o una hoja inferior con todas las opciones → descartadas en la entrevista.
- Buscador de opciones, favoritos o personalización del orden de los menús → no previsto.
- Diseños para tableta o apaisado → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests de Compose con anchos de 360 dp y 411 dp y letra al 100 % y al 130 %, que comprueban que ninguna opción desaparece (una por una, contra la lista de RF-2 a RF-6) y que los menús caben (RF-10).
- Demo en el emulador con capturas de antes y después: lista de Objetos, cada menú de grupo, «Datos del proyecto» con un proyecto con terreno o localización activos y otro sin nada activo.
- `docs/rnf-transversales.md` con RNF-T15 y `docs/glosario.md` con «Barra de grupos» y «Menú de grupo».

## Dudas abiertas
- [SUPOSICIÓN: «Informe de replanteo» y «Papelera de puntos» se quedan en el «⋮» porque se usan poco y no son importar, exportar ni calcular.]
- [SUPOSICIÓN: las capas del mapa visibles cuentan como activas, con la pantalla de capas del mapa como destino de su fila.]
- [SUPOSICIÓN: la superficie y el eje activos se gestionan donde hoy se activan; el plan lo confirma antes de fijar el destino de sus filas (RF-20).]
- [SUPOSICIÓN: dentro de «Importar» y «Exportar» las filas llevan solo el formato; la etiqueta de TalkBack lleva el verbo.]

## Cambios en documentos anteriores
- `docs/rnf-transversales.md`: nuevo RNF-T15 · Presentación de opciones (ver abajo).
- `docs/glosario.md`: «Barra de grupos» y «Menú de grupo».
- `docs/hoja-de-ruta.md`: sección «H9 · Interfaz» con esta spec.
- S011: la lista de Objetos pasa a tener pestañas y sus acciones de menú pasan a los menús de grupo (el comportamiento no cambia).
- S006: «Datos del proyecto» gana la sección «Activo en este proyecto».

### RNF-T15 · Presentación de opciones (texto que se añade a `docs/rnf-transversales.md`)
- Un menú desplegable tiene como máximo 8 filas y se ve entero, sin desplazamiento, en una pantalla de 640 dp de alto con la letra al 100 %.
- Cada fila lleva icono y texto, una zona táctil de 56 dp y una etiqueta de TalkBack con el verbo y el objeto completos.
- Una opción aparece en un solo menú.
- Las opciones se agrupan por lo que hacen (importar, exportar, calcular) y cada grupo tiene su propia entrada; un menú no sustituye a la navegación principal ni esconde lo que se usa a menudo.
- Una función que no se entiende por su nombre lleva una descripción de una línea; las filas que se explican solas no la llevan.
- Un menú que supere el límite se divide en grupos antes de aceptarse la spec que lo añade.
