# Spec 025 — Líneas y polígonos

> Hito: H3 · Depende de: S024, S011 · Estado: pendiente de aprobación (entrevistada 04/11/2026)

## Contexto y objetivo
Un bordillo, una tapia, una zanja o una parcela no son puntos sueltos: son líneas que se levantan punto a punto y que el técnico necesita ver, exportar y replantear como un solo elemento. Hoy el proyecto solo tiene puntos (S011 anuncia las líneas para esta spec) y las líneas que se ven en el mapa vienen de un dibujo importado (S040), que es referencia de diseño y no se toca.

Esta spec trae las **líneas del proyecto** y los **polígonos**: se empiezan al medir con un código de línea o de polígono, se continúan, se cierran, se crean desde puntos que ya existen y se editan vértice a vértice. Una línea es un dato de campo (constitución nº 1): se construye con los puntos del proyecto, que no se modifican ni se copian, y cada cambio deja rastro. Medir su longitud o su área es S026 y replantearla es S027.

## Usuarios / actores
- Topógrafo en campo que levanta elementos lineales y parcelas con el RS3 en FIX.
- Técnico en oficina que ordena los puntos de un levantamiento en líneas y polígonos.

## Historias de usuario
- H1: Como topógrafo quiero empezar una línea al medir con un código de línea y seguir añadiéndole puntos.
- H2: Como topógrafo quiero cerrar una línea en un polígono, y continuar una línea que dejé abierta.
- H3: Como técnico quiero crear una línea o un polígono desde puntos que ya medí, en el orden que elija.
- H4: Como topógrafo quiero corregir los vértices de una línea sin perder nunca un punto medido.
- H5: Como técnico quiero ver, buscar, ocultar y borrar las líneas junto a los puntos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Línea del proyecto**: una sucesión ordenada de **vértices** con nombre, código de geometría Línea o Polígono (S023) y los atributos de ese código (S024). No se confunde con la línea de un dibujo (S040).
- **Vértice**: un punto del proyecto (medido, manual, importado o calculado sobre eje) que la línea referencia; el mismo punto puede ser vértice de varias líneas.
- **Polígono**: una línea cerrada: su último vértice se une con el primero sin repetirlo. Se llama **abierta** a la que no lo es.
- **Línea activa**: la que recibe los puntos que se miden con su código.
- **Sentido de la línea** y **PK**: los de S041.

### Empezar y continuar al medir (H1, H2)
- RF-1: EL SISTEMA ofrecerá en el selector de códigos del recolector (S024/RF-1) también los de geometría Línea y Polígono, marcados con su geometría.
- RF-2: CUANDO el usuario mida un punto con un código de línea o de polígono y no haya línea activa de ese código, EL SISTEMA creará una línea con el nombre del código y el siguiente número libre entre las líneas del proyecto (por ejemplo `BORD_3`), con ese punto como primer vértice, y la dejará como línea activa.
- RF-3: MIENTRAS haya una línea activa, EL SISTEMA añadirá como último vértice cada punto que se mida con su código, y mostrará en el recolector su nombre y su número de vértices.
- RF-4: CUANDO el usuario pulse "Terminar línea", EL SISTEMA dejará de tener línea activa; si el código es de polígono y la línea tiene al menos 3 vértices, la cerrará.
- RF-5: CUANDO el usuario elija "Continuar línea", EL SISTEMA listará las líneas abiertas del proyecto con su código y su número de vértices y, al elegir una, la dejará como línea activa, de modo que los nuevos puntos se añadan tras su último vértice.
- RF-6: CUANDO el usuario pulse "Deshacer vértice" con una línea activa, EL SISTEMA quitará de la línea su último vértice sin borrar el punto, que sigue en el proyecto.
- RF-7: SI el punto que se mide es el mismo que el último vértice de la línea activa, ENTONCES EL SISTEMA no lo repetirá.
- RF-8: SI se mide un punto con un código distinto del de la línea activa, o sin código, ENTONCES EL SISTEMA no lo añadirá a la línea y esta seguirá activa; y si el código es de línea o de polígono, creará una nueva línea con ese punto y la dejará como línea activa en lugar de la anterior.
- RF-8b: CUANDO se mida un punto con un código de línea o de polígono, EL SISTEMA pedirá los atributos del código solo al crear la línea, los guardará en la línea, y guardará en el punto el código por valor como en S024/RF-10 sin volver a pedirlos.
- RF-9: EL SISTEMA conservará la línea activa al cerrar la app y la mantendrá al volver al recolector.

### Cerrar y abrir (H2)
- RF-10: CUANDO el usuario pulse "Cerrar en polígono" en una línea abierta de al menos 3 vértices, EL SISTEMA la convertirá en un polígono; y CUANDO pulse "Abrir" en un polígono, volverá a ser una línea abierta, con su sentido y sus vértices intactos y con ambos cambios en su historial.
- RF-11: SI la línea tiene menos de 3 vértices, ENTONCES EL SISTEMA no la cerrará e indicará el motivo.

### Crear desde puntos existentes (H3)
- RF-12: CUANDO el usuario elija "Línea desde puntos" en la lista de objetos, EL SISTEMA le permitirá marcar puntos del proyecto en el orden en que los toca, en la lista o en el mapa, y ordenarlos por nombre.
- RF-13: EL SISTEMA permitirá en ese asistente quitar un punto, invertir el orden, elegir el código (de geometría Línea o Polígono), rellenar sus atributos y elegir si la línea queda abierta o cerrada.
- RF-14: CUANDO el usuario confirme, EL SISTEMA creará la línea con esos puntos como vértices, sin modificarlos, y con un nombre propuesto como en RF-2 que el usuario podrá cambiar.
- RF-15: SI faltan puntos (menos de 2, o menos de 3 en un polígono) o hay dos iguales seguidos, ENTONCES EL SISTEMA no creará la línea e indicará el motivo.

### Editar vértices (H4)
- RF-16: CUANDO el usuario abra la ficha de una línea (RF-21), EL SISTEMA mostrará la lista de sus vértices con el nombre del punto y su PK, y permitirá añadir un punto del proyecto al final o entre dos vértices, quitar un vértice, cambiar el punto de un vértice, mover un vértice en el orden e invertir el sentido de la línea.
- RF-17: EL SISTEMA no modificará ningún punto al editar los vértices de una línea; editar las coordenadas de un punto se hace en su ficha (S011/RF-8) y la línea lo refleja al momento.
- RF-18: CUANDO se guarde una edición de vértices, de nombre, de código o de atributos de una línea, EL SISTEMA anotará en su historial la fecha y hora y los valores anteriores, como en S011/RF-10.
- RF-19: SI una edición deja la línea con menos de 2 vértices (3 en un polígono), ENTONCES EL SISTEMA no la guardará e indicará el motivo.
- RF-20: CUANDO el usuario cambie el código de una línea, EL SISTEMA aplicará S024/RF-14 y RF-15 a sus atributos y solo ofrecerá códigos de geometría Línea o Polígono.

### Ver, buscar y borrar (H5)
- RF-21: EL SISTEMA mostrará las líneas y los polígonos en la lista de objetos junto a los puntos, con su nombre, su código, su número de vértices y su estado (abierta o polígono), y los buscará por nombre o código (S011/RF-1, RF-2).
- RF-22: EL SISTEMA dibujará en el mapa cada línea con el color de su código y su nombre, cada polígono además con un relleno translúcido, y abrirá su ficha al tocarla; la ficha también mostrará el código, sus atributos y el historial.
- RF-23: CUANDO el usuario oculte o muestre una línea, EL SISTEMA la quitará del mapa o la pondrá en él y lo recordará (S011/RF-4); sus puntos no cambian.
- RF-24: CUANDO el usuario borre una línea, EL SISTEMA la moverá a la papelera del proyecto sin tocar sus puntos, y podrá restaurarla con todos sus datos y su historial como en S011/RF-22 a RF-26, con nombre nuevo si el suyo se ha reutilizado.
- RF-25: CUANDO el usuario borre a la papelera un punto que es vértice de líneas, EL SISTEMA lo avisará con los nombres de las líneas y, si se confirma, mientras esté en la papelera esas líneas lo omitirán y lo recuperarán en su sitio al restaurarlo; si se borra definitivamente, lo quitarán anotándolo en su historial.
- RF-26: EL SISTEMA incluirá las líneas en las copias de seguridad y en los paquetes de proyecto (S013) y las ofrecerá a las exportaciones de S053/RF-3.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con una línea activa.
  - RNF-T6: crear, editar, cerrar, borrar y restaurar una línea se guarda en una sola transacción con durabilidad completa; al actualizar la app se conservan los datos (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; abierta, polígono y activa con texto y no solo con color.
  - RNF-T13: dibujar 1.000 líneas con 100.000 vértices en total no bloquea el hilo principal más de 16 ms.
- Añadir un vértice a la línea activa al medir cuesta menos de 100 ms sobre el guardado del punto.
- Una línea de 100.000 vértices se abre en su ficha en menos de 1 s.

## Casos límite
| Caso | RF |
|---|---|
| Primer punto con código de línea sin línea activa | RF-2 |
| Cambiar de código a mitad de una línea | RF-8 |
| Mismo punto dos veces seguidas, o repetido en la misma línea sin ser seguido | RF-7, RF-15 |
| Polígono de 2 vértices, línea de 1 vértice | RF-11, RF-19 |
| Punto vértice de varias líneas, borrado a la papelera | RF-25 |
| Cierre de la app con una línea activa | RF-9 |
| Puntos «Calculado sobre eje» (S049/RF-22), importados o manuales como vértices | RF-12 |
| Polígono con aristas que se cruzan | Decisiones de la entrevista (se permite) |
| Punto medido con otro código con una línea activa | RF-8 |
| Proyecto con líneas importadas de un dibujo y líneas propias | RF-21 (solo las propias se editan) |

## Fuera de alcance
- Longitud, área, pendiente y ángulos → S026.
- Replantear una línea → S027.
- Exportar a DXF, Shapefile y KML → S029, S030, S031; a GeoPackage ya lo espera S053/RF-3.
- Vértices con coordenadas propias que no sean un punto del proyecto → no previsto.
- Arcos y curvas entre vértices → no previsto (el eje con curvas es S049).
- Convertir una línea de un dibujo en línea del proyecto → no previsto.
- Líneas de más de un trozo (multilíneas) y polígonos con huecos → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` en verde y test de migración de la base de datos con puntos de la versión anterior.
- Demo en el emulador con una reproducción:
  - medir una línea con un código de línea, deshacer un vértice, terminarla y continuarla después;
  - medir un polígono con un código de polígono y cerrar y abrir otra línea;
  - crear una línea desde puntos en un orden elegido y editar sus vértices;
  - borrar un punto que es vértice y restaurarlo; ver la historia.
- Demo de campo: queda para el usuario (levantar una acera con su código de línea).

## Decisiones de la entrevista (04/11/2026)
- Los atributos del código de una línea se piden una vez, al crearla, y quedan en la línea; cada vértice medido guarda además el código por valor, sin pedirlos otra vez (RF-8b).
- No se comprueba que un polígono se cruce a sí mismo y no se avisa (aristas que se cruzan: sin límite ni aviso).
- Al borrar a la papelera un punto que es vértice, se avisa con los nombres de las líneas y estas lo omiten hasta que se restaure (RF-25).
- Medir con otro código, o sin código, no termina la línea activa: sigue activa; un código de línea o de polígono distinto crea otra línea y la deja activa (RF-8).
- Sin preguntar, por defecto razonable: un vértice es siempre un punto del proyecto, referenciado y no copiado; solo hay una línea activa y se añade por el final; un código de polígono cierra la línea al terminarla y uno de línea puede cerrarse a mano (RF-4, RF-10); el sentido de la línea y su PK parten del primer vértice como en S041, sin «ordenar por distancia».

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S025: dependencias «024, 011».
- `docs/glosario.md`: «Línea / polilínea» (`Line`) pasa a llamarse «Línea (del proyecto)» (`ProjectLine`) para no confundirla con la del dibujo; se añaden «Línea activa» (`ActiveLine`) y «Línea abierta» (`OpenLine`); «Polígono» y «Vértice» ya existen; la fila «Lista de objetos» deja de decir «las líneas llegan en S025».
- S011: la lista de objetos y la papelera incluyen líneas (RF-21, RF-24).
- S024: el selector de códigos gana los de línea y polígono (RF-1).
- S053/RF-3: ya preparada para recibir estas líneas, sin cambios.
- La base de datos gana una versión (S017b se desplaza una más).
