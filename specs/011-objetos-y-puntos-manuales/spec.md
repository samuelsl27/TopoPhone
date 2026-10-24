# Spec 011 — Objetos y puntos manuales

> Hito: H1 · Depende de: S010 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Con S010 el topógrafo ya recolecta puntos y los ve en el mapa, pero no puede consultarlos ni corregirlos. Esta spec trae la lista de objetos del proyecto (de momento, solo puntos; las líneas llegan en S025), la ficha de cada punto y las herramientas para mantenerlos:
- buscarlos y elegir cuáles se ven en el mapa;
- editarlos, incluidas sus coordenadas, sin perder nunca la medida original;
- corregir una altura del jalón equivocada en uno o en varios puntos, recalculándolos desde la antena guardada;
- añadir puntos conocidos escribiendo sus coordenadas, como un vértice REGENTE o la esquina de una parcela, con altura o sin ella;
- duplicarlos y borrarlos, con una papelera de la que se pueden recuperar.

El dato de campo es sagrado (constitución nº 1): editar un punto no borra lo que se midió, y borrar un punto no lo elimina de inmediato (RNF-T6).

## Usuarios / actores
- Topógrafo en campo o en oficina, con el proyecto abierto y con receptor o sin él.

## Historias de usuario
- H1: Como topógrafo quiero ver la lista de puntos del proyecto, buscar uno y elegir cuáles se ven en el mapa.
- H2: Como topógrafo quiero abrir la ficha de un punto desde la lista o tocándolo en el mapa, y ver todo lo que se guardó de él.
- H3: Como topógrafo quiero corregir el nombre, la descripción o las coordenadas de un punto y poder volver siempre a lo que se midió.
- H4: Como topógrafo quiero corregir la altura del jalón de uno o de varios puntos cuando me doy cuenta de que estaba mal.
- H5: Como topógrafo quiero añadir un punto escribiendo sus coordenadas, proyectadas o geográficas, aunque no conozca su altura.
- H6: Como topógrafo quiero duplicar un punto para usarlo como base de otro.
- H7: Como topógrafo quiero borrar puntos sabiendo que puedo recuperarlos si me equivoco.

## Requisitos funcionales (criterios de aceptación en EARS)
### Lista de objetos (H1)
- RF-1: CUANDO el usuario abra la lista de objetos desde la vista de proyecto, EL SISTEMA mostrará los puntos del proyecto, del más reciente al más antiguo, con su nombre, su descripción y si es medido o manual.
- RF-2: CUANDO el usuario escriba en el buscador de la lista, EL SISTEMA mostrará solo los puntos cuyo nombre o descripción contengan ese texto.
- RF-3: EL SISTEMA marcará en la lista, con un icono y un texto, los puntos fuera de tolerancia, los que tienen coordenadas editadas y los que no tienen altura.
- RF-4: CUANDO el usuario oculte o muestre un punto, o varios seleccionados en la lista, EL SISTEMA los quitará del mapa o los pondrá en él, y lo recordará al volver a abrir el proyecto.

### Ficha del punto (H2)
- RF-5: CUANDO el usuario toque un punto en la lista o en el mapa, EL SISTEMA abrirá su ficha.
- RF-6: EL SISTEMA mostrará en la ficha las coordenadas actuales del punto en el SC del proyecto y en geográficas, en las unidades del proyecto.
- RF-7: DONDE el punto sea medido, EL SISTEMA mostrará en la ficha la altura del jalón, la posición de la antena, los metadatos de calidad (S010/RF-18), la fecha y hora de la medida, las condiciones del control de precisión que incumplió y las frases NMEA guardadas con él y, si se promedió, el tiempo promediado y las desviaciones típicas (S017/RF-13).

### Editar con rastro (H3)
- RF-8: CUANDO el usuario edite un punto, EL SISTEMA permitirá cambiar su nombre, su descripción y sus coordenadas, escritas como en un punto manual (RF-16 a RF-18).
- RF-9: SI el nombre editado ya existe en el proyecto, ENTONCES EL SISTEMA lo indicará y no guardará el cambio.
- RF-10: CUANDO se guarde la edición de un punto, EL SISTEMA guardará en su historial la fecha y hora del cambio y los valores anteriores, sin modificar nunca la observación bruta (RNF-T6).
- RF-11: CUANDO el usuario abra el historial en la ficha, EL SISTEMA mostrará los cambios del punto, del más reciente al más antiguo.
- RF-12: MIENTRAS las coordenadas de un punto medido difieran de las de su medida, EL SISTEMA lo marcará como editado y mostrará en la ficha también las coordenadas de la medida.
- RF-13: CUANDO el usuario pulse "Volver a la medida" en la ficha de un punto editado, EL SISTEMA le devolverá las coordenadas de su medida, como un cambio más del historial.

### Corregir el jalón (H4)
- RF-14: CUANDO el usuario cambie la altura del jalón de un punto medido, desde su ficha o para varios seleccionados en la lista, EL SISTEMA recalculará el punto del suelo de cada uno desde la posición de la antena guardada, en geográficas y en el SC del proyecto, y, si se midió con inclinación, a lo largo del jalón (S016/RF-25).
- RF-15: SI la selección incluye puntos manuales, ENTONCES EL SISTEMA no los cambiará e indicará cuántos se han quedado sin cambiar.

### Punto manual (H5)
- RF-16: CUANDO el usuario pulse "Añadir punto" en la lista de objetos, EL SISTEMA pedirá el nombre, con el siguiente propuesto (S010/RF-2), la descripción y las coordenadas.
- RF-17: EL SISTEMA permitirá escribir las coordenadas en el SC del proyecto, con la altura en su sistema de alturas, o en geográficas del datum del proyecto, con la altura en ese sistema o elipsoidal a elegir, salvo en ED50, que solo admite altitud (S008/RF-3).
- RF-18: EL SISTEMA permitirá dejar la altura vacía y guardará entonces el punto sin altura.
- RF-19: SI las coordenadas escritas quedan fuera del ámbito del SC del proyecto, o fuera del modelo de geoide cuando la altura es una altitud, ENTONCES EL SISTEMA indicará el motivo y no guardará el punto.
- RF-20: CUANDO se guarde un punto manual, EL SISTEMA guardará las coordenadas tal como se escribieron, sin redondear, junto con las calculadas en la otra forma (proyectadas o geográficas) y la fecha y hora de creación.

### Duplicar (H6)
- RF-21: CUANDO el usuario duplique un punto, EL SISTEMA creará un punto manual con sus mismas coordenadas y descripción, el nombre siguiente libre y la referencia "copia de" al original, sin la observación bruta ni los metadatos de calidad.

### Borrar y papelera (H7)
- RF-22: CUANDO el usuario borre un punto, o varios seleccionados en la lista, EL SISTEMA pedirá confirmación y los moverá a la papelera del proyecto, de donde se pueden recuperar durante 30 días.
- RF-23: CUANDO el usuario abra la papelera del proyecto desde la lista de objetos, EL SISTEMA mostrará los puntos borrados con los días que les quedan.
- RF-24: CUANDO el usuario restaure un punto de la papelera, EL SISTEMA lo devolverá al proyecto con todos sus datos y su historial.
- RF-25: SI el nombre de un punto que se restaura ya lo tiene otro punto del proyecto, ENTONCES EL SISTEMA pedirá un nombre nuevo antes de restaurarlo.
- RF-26: CUANDO el usuario borre definitivamente un punto de la papelera, o cuando un punto cumpla 30 días en ella y se abra la app, EL SISTEMA lo eliminará con todos sus datos, pidiendo confirmación en el primer caso.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: cada edición, corrección del jalón, borrado o restauración se guarda en una sola transacción con durabilidad completa; la observación bruta y la posición de la antena no se modifican nunca.
  - RNF-T7, RNF-T8 (56 dp, modo sol, TalkBack; marcas con icono y texto), RNF-T9 (unidades lineales y angulares del proyecto), RNF-T10 (fechas en hora local).
  - RNF-T13: la lista no bloquea la interfaz mientras busca.
- Constitución nº 2:
  - Un punto manual escrito en proyectadas da las mismas geográficas que pyproj, y al revés, a menos de 1 mm; la ida y vuelta difiere menos de 0,1 mm.
  - El punto del suelo recalculado tras corregir el jalón coincide con pyproj a menos de 1 mm.
- La corrección del jalón de una selección se aplica a todos sus puntos o a ninguno.
- Con 10 000 puntos, la lista se abre y la búsqueda responde en menos de 1 s en el móvil de referencia.
- Al actualizar la app, los proyectos existentes conservan sus puntos (migración de la base de datos probada).

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin puntos | RF-1 (la lista lo dice y ofrece "Añadir punto") |
| Búsqueda sin resultados | RF-2 |
| Nombre editado que ya existe | RF-9 |
| Punto editado varias veces | RF-10, RF-11 |
| Corregir el jalón de un punto con coordenadas editadas | RF-14 (ver "Dudas abiertas") |
| Selección con puntos medidos y manuales | RF-15 |
| Punto manual sin altura | RF-18 (marcado en la lista, RF-3) |
| Coordenadas fuera del huso, del ámbito del SC o del geoide | RF-19 |
| Proyecto en geográficas | RF-17 (la forma "en el SC del proyecto" ya es la geográfica) |
| Proyecto en ED50 | RF-17 (solo altitud) |
| Punto sin altura en un proyecto con altitud | RF-19 (no se comprueba el geoide) |
| Duplicar un punto manual o un duplicado | RF-21 |
| Restaurar un punto cuyo nombre se ha reutilizado | RF-25 |
| Punto oculto | RF-4 (sigue en la lista y en el recuento de los datos del proyecto) |
| Punto en la papelera | RF-22 (fuera del mapa, de la lista y del recuento) |
| Cierre de la app justo después de una edición o de un borrado | RNF-T6 |

## Fuera de alcance
- Exportar puntos → S012.
- Paquetes y copias de seguridad del proyecto → S013.
- Puntos con inclinación del jalón → S016.
- Fotos del punto → S017b (ficha, historial, papelera y lista de objetos: S017b/RF-9, RF-23, RF-24, RF-26).
- Replantear un punto desde la lista o la ficha → S018.
- Códigos y atributos, y cambiar el código de un punto → S023 y S024.
- Líneas y polígonos en la lista de objetos → S025.
- Importar puntos de un fichero → S028.
- Medir distancias o ángulos entre puntos sobre el mapa (inversa, COGO) → S038.
- Escribir un punto manual en el datum de las correcciones (coordenadas "globales") → no previsto.
- Corregir el jalón de todos los puntos que tienen una altura dada → no previsto: se hace seleccionándolos (RF-14).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Tests de puntos manuales y del recálculo del jalón frente a pyproj en ETRS89, REGCAN95 y ED50, con altitud y con altura elipsoidal.
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Demo en el emulador:
  - lista, búsqueda y ficha abierta tocando el punto en el mapa;
  - editar las coordenadas de un punto medido y volver a la medida;
  - corregir el jalón de una selección;
  - un punto manual en proyectadas, uno en geográficas y uno sin altura;
  - duplicar;
  - borrar, ver la papelera y restaurar.
- Demo de campo: queda para el usuario. Por ejemplo, escribir un vértice REGENTE desde su reseña, medirlo con el RS3 y comparar las fichas.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: la latitud y la longitud se escriben en la unidad angular del proyecto (DD, DMS o gon), y Este, Norte y altura en su unidad lineal.]
- [SUPOSICIÓN: la búsqueda no distingue mayúsculas ni acentos.]
- [SUPOSICIÓN: ocultar un punto solo lo quita del mapa; sigue en la lista, en el recuento de los datos del proyecto y, más adelante, en las exportaciones.]
- [SUPOSICIÓN: un punto manual no tiene jalón ni metadatos de calidad; su fecha es la de creación.]
- [SUPOSICIÓN: el nombre que se propone al añadir o duplicar sigue la regla de S010/RF-2 a partir del último punto del proyecto, saltando los nombres ocupados.]
- [SUPOSICIÓN: los nombres de los puntos de la papelera quedan libres para otros puntos; por eso al restaurar puede hacer falta otro nombre (RF-25).]
- [SUPOSICIÓN: al corregir el jalón de un punto con coordenadas editadas se recalcula su medida, se mantienen las coordenadas editadas y se avisa de ello.]
- [SUPOSICIÓN: un punto editado conserva sus metadatos de calidad y su marca de fuera de tolerancia, porque describen la medida, no las coordenadas escritas.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S011: se añaden "historial de cambios" y "papelera de puntos".
- `docs/glosario.md`:
  - se añaden "Lista de objetos" (`ObjectList`), "Ficha del punto" (`PointDetails`), "Punto medido / manual" (`PointOrigin.MEASURED` / `.MANUAL`), "Punto sin altura" (`hasHeight`), "Punto editado" (`edited`) e "Historial (del punto)" (`PointHistory`);
  - la fila "Papelera" pasa a decir "Proyectos (S006) y puntos de un proyecto (S011), recuperables 30 días".
