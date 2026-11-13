# Spec 036 — Capas WMS

> Hito: H5 · Depende de: S009, S008, S013 · Estado: aprobada (13/11/2026)

## Contexto y objetivo
El mapa del proyecto (S009) ofrece cuatro mapas base y el Catastro, fijos. En España hay decenas de servicios WMS públicos que el topógrafo necesita según el trabajo: ortofotos autonómicas más recientes que el PNOA y de años anteriores, cartografía histórica, planeamiento, redes de servicios, MDT, límites. Hoy no puede verlos sin salir de la app, ni preguntar por lo que hay bajo un punto del mapa (la referencia catastral de una parcela, por ejemplo).

Esta spec añade una biblioteca de servicios WMS en el móvil (el usuario escribe una dirección y la app lee sus capas con GetCapabilities 1.3.0 o 1.1.1), una lista de presets de servicios públicos españoles con su atribución, las capas de cada proyecto con su orden, visibilidad y opacidad, y un menú que sale al mantener pulsado el mapa con la consulta de información de las capas y las coordenadas del punto. Todo se gestiona desde «Capas» del mapa. La red se usa solo para los servicios que el usuario configura (constitución nº 4). El mapa base y el Catastro de S009 siguen igual. La descarga para uso sin conexión llega con S037.

## Usuarios / actores
- Topógrafo en campo que quiere ver ortofotos, catastro u otras capas oficiales debajo de sus puntos y saber qué hay en un punto del mapa.
- Servicios WMS públicos y los que el usuario añada.

## Historias de usuario
- H1: Como topógrafo quiero añadir un servicio WMS escribiendo su dirección y ver qué capas tiene.
- H2: Como topógrafo quiero partir de servicios españoles ya configurados.
- H3: Como topógrafo quiero elegir qué capas ve cada proyecto, en qué orden y con qué transparencia.
- H4: Como topógrafo quiero ver siempre de quién son los datos.
- H5: Como topógrafo quiero mantener pulsado el mapa para consultar la información de las capas y las coordenadas de ese punto.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Servicio WMS**: una dirección de servidor WMS guardada en la biblioteca del móvil, con sus capas leídas.
- **Biblioteca WMS**: la lista de servicios del móvil, común a todos los proyectos.
- **Capa del proyecto**: una capa de un servicio, añadida a un proyecto con su orden, visibilidad y opacidad.
- **Preset**: servicio público de la app ya preparado, con su atribución y su licencia.
- **Modo de petición**: «por teselas» o «una imagen» de la zona visible, según lo que permitan las condiciones del servicio.
- **Menú del mapa**: el que sale al mantener pulsado un punto del mapa.

### Biblioteca WMS (H1)
- RF-1: CUANDO el usuario escriba la dirección de un servicio WMS en «Añadir servicio» y pulse «Añadir», EL SISTEMA pedirá su GetCapabilities en la versión 1.3.0 y, si el servicio no la ofrece, en la 1.1.1.
- RF-2: EL SISTEMA leerá el documento de capacidades en la versión en que responda el servicio, aunque no sea la que se pidió.
- RF-3: CUANDO responda el servicio, EL SISTEMA mostrará su título, su versión, su atribución y el árbol de capas con su título, su resumen y si admiten transparencia y consulta.
- RF-4: EL SISTEMA guardará el servicio en la biblioteca con la lista de capas leída, para verla de nuevo sin conexión.
- RF-5: SI la dirección ya lleva parámetros (por ejemplo `?map=`), ENTONCES EL SISTEMA los conservará en cada petición y añadirá solo los que falten.
- RF-6: CUANDO el usuario pulse «Actualizar» en un servicio, EL SISTEMA volverá a leer sus capas y avisará de las capas de los proyectos que hayan desaparecido.
- RF-7: EL SISTEMA permitirá renombrar y borrar un servicio de la biblioteca; al borrarlo, avisará de los proyectos que usan sus capas.
- RF-8: SI el servicio no responde, no devuelve un documento de capacidades válido, devuelve una excepción del servicio o tarda más de 20 s, ENTONCES EL SISTEMA no lo añadirá e indicará el motivo.
- RF-9: SI el servicio no ofrece ninguna capa en EPSG:3857, ENTONCES EL SISTEMA lo rechazará e indicará que no es compatible con el mapa.
- RF-10: SI la dirección es HTTP y no corresponde a una red privada o de enlace local, ENTONCES EL SISTEMA no la admitirá e indicará que debe usar HTTPS (RNF-T4).
- RF-11: EL SISTEMA leerá el documento de capacidades por streaming, hasta 20 MB y con capas anidadas hasta 10 niveles, tolerando su codificación de caracteres y su declaración de tipo de documento.
- RF-12: SI una capa no tiene nombre (solo agrupa) o no ofrece EPSG:3857, ENTONCES EL SISTEMA la mostrará pero no dejará añadirla a un proyecto e indicará por qué.

### Presets españoles (H2, H4)
- RF-13: EL SISTEMA ofrecerá como presets los servicios WMS públicos del IGN (PNOA máxima actualidad, cartografía base, modelo digital del terreno, límites administrativos e hidrografía), del Catastro, del Institut Cartogràfic Valencià (ortofoto más reciente y las de años anteriores), del Institut Cartogràfic de Catalunya, de GRAFCAN (Canarias) y de Andalucía, Navarra, País Vasco, Madrid y Galicia, cada uno con su atribución y su licencia.
- RF-14: CUANDO el usuario añada un preset, EL SISTEMA lo guardará como un servicio de la biblioteca y leerá sus capas como en RF-1.
- RF-15: EL SISTEMA mostrará en la lista de presets cuáles ya están en la biblioteca.
- RF-16: EL SISTEMA aplicará a cada preset el modo de petición que piden sus condiciones de uso: el Catastro en «una imagen» al detenerse el mapa, sin teselas ni descargas masivas, y los demás por teselas, como S009.
- RF-17: DONDE el usuario añada un servicio propio, EL SISTEMA usará «por teselas» y permitirá cambiar a «una imagen».

### Capas del proyecto (H3)
- RF-18: CUANDO el usuario abra «Capas» en el mapa del proyecto, EL SISTEMA mostrará, además del mapa base y el Catastro de S009, las capas del proyecto con «Añadir capa» y «Añadir servicio».
- RF-19: CUANDO el usuario pulse «Añadir capa», EL SISTEMA mostrará la biblioteca con las capas de cada servicio y los presets, y añadirá al proyecto la que elija.
- RF-20: EL SISTEMA permitirá marcar o desmarcar la visibilidad de cada capa sin quitarla del proyecto.
- RF-21: EL SISTEMA permitirá cambiar la opacidad de cada capa del 0 al 100 % en pasos del 5 %, con el valor visible.
- RF-22: EL SISTEMA permitirá reordenar las capas, pintando la primera de la lista encima de las demás y todas encima del mapa base y debajo del Catastro y del rover.
- RF-23: EL SISTEMA permitirá quitar una capa del proyecto sin borrar el servicio.
- RF-24: EL SISTEMA guardará las capas de cada proyecto con su orden, su visibilidad y su opacidad, y las recuperará al reabrirlo.
- RF-25: EL SISTEMA incluirá en la copia de seguridad y en el paquete de proyecto (S013) las capas del proyecto y los servicios que usan, y al restaurarlos añadirá a la biblioteca los servicios que el móvil no tenga.
- RF-26: EL SISTEMA permitirá hasta 10 capas en un proyecto.
- RF-27: SI una capa del proyecto ya no existe en el servicio, ENTONCES EL SISTEMA la mostrará marcada como no disponible y no la pedirá.
- RF-28: SI hay dos capas con el mismo nombre en servicios distintos, ENTONCES EL SISTEMA mostrará junto a cada una el nombre de su servicio.

### Pintado y atribución (H3, H4)
- RF-29: MIENTRAS una capa sea visible, EL SISTEMA la pedirá al servicio en EPSG:3857 para la zona visible, con la transparencia que admita su formato, y solo mientras el mapa esté a la vista.
- RF-30: EL SISTEMA mostrará en el mapa la atribución de cada servicio con una capa visible, tomada del preset o del campo de atribución del servicio, y su título si el servicio no declara atribución (S009/RF-9).
- RF-31: SI una capa no puede descargarse, ENTONCES EL SISTEMA mostrará el aviso de falta de conexión de S009/RF-15 y seguirá dibujando lo ya descargado de ella y el resto del mapa.
- RF-32: EL SISTEMA guardará en la caché del mapa lo ya visto de las capas por teselas dentro del límite de S009/RF-13, y no guardará lo de las capas en «una imagen».
- RF-33: EL SISTEMA no cambiará el mapa base ni el Catastro de S009 al añadir, quitar o reordenar capas.

### Menú del mapa (H5)
- RF-34: CUANDO el usuario mantenga pulsado un punto del mapa, EL SISTEMA marcará ese punto y abrirá el menú del mapa con «Consultar información de las capas» y «Ver coordenadas».
- RF-35: CUANDO el usuario cierre el menú del mapa, EL SISTEMA quitará la marca del punto.
- RF-36: CUANDO el usuario elija «Consultar información de las capas», EL SISTEMA preguntará a cada capa visible que admita consulta qué hay en el punto marcado y mostrará la respuesta de cada una en texto, con el nombre de la capa y el de su servicio.
- RF-37: SI ninguna capa visible admite consulta, ENTONCES EL SISTEMA dejará desactivada «Consultar información de las capas» e indicará el motivo.
- RF-38: SI una capa no tiene datos en el punto marcado, ENTONCES EL SISTEMA lo indicará en esa capa.
- RF-39: SI un servicio no responde, devuelve una excepción o tarda más de 20 s, ENTONCES EL SISTEMA lo indicará en su capa y mostrará las respuestas de las demás.
- RF-40: CUANDO el usuario elija «Ver coordenadas», EL SISTEMA mostrará las del punto marcado en el SC del proyecto (Este y Norte, en sus unidades) y su latitud y longitud, y permitirá copiarlas.
- RF-41: SI el punto marcado está fuera del ámbito del SC del proyecto, ENTONCES EL SISTEMA mostrará solo su latitud y longitud, con el motivo (S008/RF-5, RF-6).

## Requisitos no funcionales
- Transversales que aplican: RNF-T4 (las peticiones van por la red con internet), RNF-T6 (la biblioteca y las capas se guardan sin pérdida), RNF-T7, RNF-T8 (56 dp, arrastrar con alternativa de botones subir y bajar, etiquetas TalkBack), RNF-T13 (el mapa no bloquea el hilo principal más de 16 ms) y RNF-T14 (atribución visible, licencias de los presets).
- Constitución nº 4: la red se usa solo para los servicios que el usuario añade o elige como preset; no hay servicio por defecto activo.
- Constitución nº 2: el cuadro envolvente que se pide al servicio coincide con el de pyproj (EPSG:3857) a ≤ 1 cm, y las coordenadas del menú del mapa coinciden con las de pyproj a ≤ 1 mm.
- Las coordenadas del cuadro envolvente se escriben con `Locale.ROOT`.
- Las pruebas usan los GetCapabilities (1.1.1 y 1.3.0) y las respuestas de consulta grabados de cada preset, no los servicios en vivo.
- Leer el GetCapabilities de 5 MB tarda menos de 2 s en el móvil de referencia, sin bloquear la interfaz.
- Se cumplen las condiciones de uso de cada servicio (identificación de la app y límites de petición).
- La app no envía a ningún servicio datos del usuario ni de sus proyectos: solo el cuadro envolvente de la zona visible, o el punto consultado, y la identificación de la app.

## Casos límite
| Caso | RF |
|---|---|
| Servicio sin 3857, sin respuesta o con XML dañado | RF-8, RF-9 |
| Servicio que contesta en 1.1.1 a una petición 1.3.0 (el Catastro) | RF-1, RF-2 |
| Servicio solo en versión 1.1.1, con capas anidadas, DOCTYPE o ISO-8859-1 | RF-2, RF-11 |
| URL con parámetros ya incluidos (`?map=`) | RF-5 |
| URL HTTP pública | RF-10 |
| Capa que solo agrupa, o sin 3857 | RF-12 |
| Dos capas con el mismo nombre en servicios distintos | RF-28 |
| Capa borrada del servicio | RF-6, RF-27 |
| Servicio ya en la biblioteca y también como preset | RF-15 |
| Sin conexión | RF-4, RF-31 |
| Más de 10 capas | RF-26 |
| Opacidad 0 % | RF-21 |
| Zona fuera de la cobertura del servicio | RF-29 |
| Ortofoto de una capa encima del Catastro activado | RF-22, RF-33 |
| Pulsación larga sin capas visibles o sin capas consultables | RF-37 |
| Pulsación larga fuera de España o fuera del ámbito del SC | RF-40, RF-41 |
| Servicio que no responde a la consulta | RF-39 |
| Restaurar una copia con servicios que el móvil no tiene | RF-25 |

## Fuera de alcance
- Descargar capas WMS para usarlas sin conexión → S037.
- Servicios con usuario y contraseña → no previsto.
- WMTS, WFS, XYZ y vectoriales (KML en línea) → no previsto.
- Leyendas → no previsto.
- Estilos distintos del predeterminado, y capas con dimensión temporal → no previsto.
- Mostrar la respuesta de la consulta como mapa, con geometrías o con enlaces que se abren → no previsto (solo texto).
- Otras opciones del menú del mapa (replantear aquí, añadir un punto…) → sus propias specs.
- Altura del punto pulsado → no previsto (el mapa no tiene modelo del terreno).
- Cambiar el mapa base o el Catastro de S009 → S009.
- Puntos del proyecto sobre el mapa → S010.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- GetCapabilities grabados (1.1.1 y 1.3.0) y una respuesta de consulta de cada preset en fixtures; cuadros envolventes y coordenadas del menú frente a pyproj.
- Demo en el emulador: añadir un preset y un servicio por dirección, ver sus capas, añadirlas a un proyecto, reordenarlas, ocultarlas, cambiar su opacidad, ver la atribución, mantener pulsado el mapa para consultar la información de una capa y ver las coordenadas, restaurar una copia con capas y abrir sin red.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: solo se piden capas en EPSG:3857 (ADR-9); no se reproyecta el resultado en el móvil.]
- [SUPOSICIÓN: la biblioteca es común a todos los proyectos y las capas son por proyecto; no hay una lista global de capas activas.]
- [SUPOSICIÓN: no hay contraseñas en los servicios WMS.]
- [SUPOSICIÓN: el límite es de 10 capas por proyecto, para no saturar la red del móvil.]
- [SUPOSICIÓN: las capas WMS se pintan encima del mapa base y debajo del Catastro de S009, para que las parcelas se vean sobre una ortofoto.]
- [SUPOSICIÓN: las direcciones de los presets se verifican al planificar; si alguna cambió, se corrige en la spec. De cada comunidad (Andalucía, Navarra, País Vasco, Madrid y Galicia) entran su ortofoto y su cartografía base.]
- [SUPOSICIÓN: el Catastro de S009 queda como interruptor propio; su preset de S036 es otra forma de añadirlo, con el mismo modo de petición. Si el usuario activa las dos, se pide dos veces.]
- [SUPOSICIÓN: la consulta de información sale en texto (el formato se elige en el plan según lo que ofrezca cada servicio) y solo para las capas marcadas como consultables por su servicio.]
- [SUPOSICIÓN: la biblioteca de servicios no tiene entrada en Ajustes: se gestiona desde «Capas» del mapa (S005 preveía «Mis capas» en Ajustes).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec; el de S013, tras enseñar su diff)
- `docs/hoja-de-ruta.md`: S036 en estado `spec`, con dependencias S009, S008 y S013.
- S009 «Fuera de alcance» y «Dudas»: el mapa base y el Catastro se recuerdan para toda la app; las capas por proyecto de S036 son aparte y se pintan entre el mapa base y el Catastro.
- S013 RF-2, RF-24 y «Fuera de alcance»: la copia y el paquete incluyen las capas WMS del proyecto y los servicios que usan (nueva versión de los datos de la copia); el diff se muestra antes de tocar su spec.
- S005 «Fuera de alcance»: «Mis capas» deja de ser una entrada de Ajustes y pasa a «Capas» del mapa (S036).
- `docs/glosario.md`: se añaden «Servicio WMS», «Biblioteca WMS», «Capa del proyecto», «Preset», «Modo de petición» y «Menú del mapa»; «Capa (WMS)» se precisa como capa del proyecto.
- `docs/inventario-funcional.md`: la fila de la biblioteca WMS pasa a S036 con el menú de pulsación larga (consulta de capas y coordenadas).
