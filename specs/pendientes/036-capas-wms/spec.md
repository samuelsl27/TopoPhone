# Spec 036 — Capas WMS

> Hito: H5 · Depende de: S009 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
El mapa del proyecto (S009) ofrece cuatro mapas base y el Catastro, fijos. En España hay decenas de servicios WMS públicos que el topógrafo necesita según el trabajo: ortofotos autonómicas más recientes que el PNOA, cartografía histórica, planeamiento, redes de servicios, MDT, límites. Hoy no puede verlos sin salir de la app.

Esta spec añade una biblioteca de servicios WMS en el móvil (el usuario escribe una URL y la app lee sus capas con GetCapabilities 1.1.1 o 1.3.0), una lista de presets de servicios públicos españoles (IGN, Catastro, ICV, ICGC, GRAFCAN…) con su atribución, y las capas de cada proyecto con su orden, visibilidad y opacidad. La red se usa solo para los servicios que el usuario configura (constitución nº 4). El mapa base y el Catastro de S009 siguen igual. La descarga para uso sin conexión llega con S037.

## Usuarios / actores
- Topógrafo en campo que quiere ver ortofotos, catastro u otras capas oficiales debajo de sus puntos.
- Servicios WMS públicos y los que el usuario añada.

## Historias de usuario
- H1: Como topógrafo quiero añadir un servicio WMS escribiendo su dirección y ver qué capas tiene.
- H2: Como topógrafo quiero partir de servicios españoles ya configurados.
- H3: Como topógrafo quiero elegir qué capas ve cada proyecto, en qué orden y con qué transparencia.
- H4: Como topógrafo quiero ver siempre de quién son los datos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Servicio WMS**: una dirección de servidor WMS guardada en la biblioteca del móvil, con sus capas leídas.
- **Biblioteca WMS**: la lista de servicios del móvil, común a todos los proyectos.
- **Capa del proyecto**: una capa de un servicio, añadida a un proyecto con su orden, visibilidad y opacidad.
- **Preset**: servicio público de la app ya preparado, con su atribución.
- **Modo de petición**: «por teselas» o «una imagen» de la zona visible, según lo que permitan las condiciones del servicio.

### Biblioteca WMS (H1)
- RF-1: CUANDO el usuario escriba la dirección de un servicio WMS y pulse «Añadir», EL SISTEMA pedirá su GetCapabilities en la versión 1.3.0 y, si el servicio no la ofrece, en la 1.1.1.
- RF-2: CUANDO responda el servicio, EL SISTEMA mostrará su título, su versión, su atribución y el árbol de capas con su título, resumen y si tienen transparencia.
- RF-3: EL SISTEMA guardará el servicio en la biblioteca con la lista de capas leída, para verla de nuevo sin conexión.
- RF-4: CUANDO el usuario pulse «Actualizar» en un servicio, EL SISTEMA volverá a leer sus capas y avisará de las capas del proyecto que hayan desaparecido.
- RF-5: EL SISTEMA permitirá renombrar y borrar un servicio de la biblioteca; al borrarlo, avisará de los proyectos que usan sus capas.
- RF-6: SI el servicio no responde, no devuelve un GetCapabilities válido, devuelve una excepción del servicio o supera 20 s, ENTONCES EL SISTEMA no lo añadirá e indicará el motivo.
- RF-7: SI el servicio no ofrece ninguna capa en EPSG:3857, ENTONCES EL SISTEMA lo rechazará e indicará que no es compatible con el mapa.
- RF-8: SI la dirección es HTTP y no corresponde a una red privada o de enlace local, ENTONCES EL SISTEMA no la admitirá e indicará que debe usar HTTPS (RNF-T4).
- RF-9: EL SISTEMA leerá el GetCapabilities por streaming, sin límite de tamaño razonable inferior a 20 MB, y con capas anidadas hasta 10 niveles.

### Presets españoles (H2, H4)
- RF-10: EL SISTEMA ofrecerá como presets los servicios WMS públicos del IGN (incluida la ortofoto PNOA y la cartografía base), del Catastro, del Institut Cartogràfic de Catalunya (ICGC), del Institut Cartogràfic Valencià (ICV) y de GRAFCAN (Canarias), cada uno con su atribución y su licencia.
- RF-11: CUANDO el usuario añada un preset, EL SISTEMA lo guardará como un servicio de la biblioteca y leerá sus capas como en RF-1.
- RF-12: EL SISTEMA aplicará a cada preset su modo de petición: el Catastro en «una imagen» al detenerse el mapa, sin teselas ni descargas masivas, y los demás por teselas, como S009.
- RF-13: DONDE el usuario añada un servicio propio, EL SISTEMA usará «por teselas» y permitirá cambiar a «una imagen».

### Capas del proyecto (H3)
- RF-14: CUANDO el usuario abra «Capas» en el mapa del proyecto, EL SISTEMA mostrará las capas del proyecto y permitirá añadir las de cualquier servicio de la biblioteca.
- RF-15: EL SISTEMA permitirá marcar o desmarcar la visibilidad de cada capa sin quitarla del proyecto.
- RF-16: EL SISTEMA permitirá cambiar la opacidad de cada capa del 0 al 100 % en pasos del 5 %, con el valor visible.
- RF-17: EL SISTEMA permitirá reordenar las capas, pintando la primera de la lista encima de las demás y todas encima del mapa base de S009, pero bajo el rover.
- RF-18: EL SISTEMA permitirá quitar una capa del proyecto sin borrar el servicio.
- RF-19: EL SISTEMA guardará las capas de cada proyecto con su orden, su visibilidad y su opacidad, y las recuperará al reabrirlo, también tras restaurar una copia o un paquete de proyecto (S013).
- RF-20: EL SISTEMA permitirá hasta 10 capas en un proyecto.
- RF-21: SI una capa del proyecto ya no existe en el servicio, ENTONCES EL SISTEMA la mostrará marcada como no disponible y no la pedirá.

### Pintado y atribución (H3, H4)
- RF-22: MIENTRAS una capa sea visible, EL SISTEMA la pedirá al servicio en EPSG:3857 para la zona visible, con la transparencia que admita su formato, y solo mientras el mapa esté a la vista.
- RF-23: EL SISTEMA mostrará en el mapa la atribución de cada servicio con una capa visible, tomada del preset o del campo de atribución del servicio, y su título si el servicio no declara atribución (S009/RF-9).
- RF-24: SI una capa no puede descargarse, ENTONCES EL SISTEMA mostrará el aviso de falta de conexión de S009/RF-15 y seguirá dibujando lo ya descargado de ella y el resto del mapa.
- RF-25: EL SISTEMA guardará en la caché del mapa lo ya visto de las capas por teselas dentro del límite de S009/RF-13, y no guardará las de «una imagen».
- RF-26: EL SISTEMA no cambiará el mapa base ni el Catastro de S009 al añadir o quitar capas.

## Requisitos no funcionales
- Transversales que aplican: RNF-T4 (las peticiones van por la red con internet), RNF-T6 (la biblioteca y las capas se guardan sin pérdida), RNF-T7, RNF-T8 (56 dp, arrastrar con alternativa de botones subir/bajar, etiquetas TalkBack), RNF-T13 (el mapa no bloquea el hilo principal más de 16 ms) y RNF-T14 (atribución visible, licencias de los presets).
- Constitución nº 4: la red se usa solo para los servicios que el usuario añade o elige como preset; no hay servicio por defecto activo.
- Constitución nº 2: el cuadro envolvente que se pide al servicio coincide con el de pyproj (EPSG:3857) a ≤ 1 cm.
- Las coordenadas del cuadro envolvente se escriben con `Locale.ROOT`.
- Las pruebas usan GetCapabilities 1.1.1 y 1.3.0 grabados de cada preset, no los servicios en vivo.
- Leer el GetCapabilities de 5 MB tarda menos de 2 s en el móvil de referencia, sin bloquear la interfaz.
- Se cumplen las condiciones de uso de cada servicio (identificación de la app y límites de petición).

## Casos límite
| Caso | RF |
|---|---|
| Servicio sin 3857, sin respuesta o con XML dañado | RF-6, RF-7 |
| Servicio solo en versión 1.1.1 o con capas anidadas | RF-1, RF-9 |
| URL con parámetros ya incluidos (`?map=`) | RF-1 |
| URL HTTP pública | RF-8 |
| Dos capas con el mismo nombre en servicios distintos | RF-14 |
| Capa borrada del servicio | RF-4, RF-21 |
| Sin conexión | RF-3, RF-24 |
| Más de 10 capas | RF-20 |
| Opacidad 0 % | RF-16 |
| Zona fuera de la cobertura del servicio | RF-22 |

## Fuera de alcance
- Descargar capas WMS para usarlas sin conexión → S037.
- Servicios con usuario y contraseña → no previsto.
- WMTS, WFS, XYZ y vectoriales (KML en línea) → no previsto.
- GetFeatureInfo (pulsar sobre el mapa para ver datos del objeto) y leyendas → no previsto.
- Estilos distintos del predeterminado, y capas con dimensión temporal → no previsto.
- Cambiar el mapa base o el Catastro de S009 → S009.
- Puntos del proyecto sobre el mapa → S010.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- GetCapabilities grabados (1.1.1 y 1.3.0) de cada preset en fixtures; bbox frente a pyproj.
- Demo en el emulador: añadir un preset y un servicio por URL, ver sus capas, reordenarlas, ocultarlas, cambiar su opacidad, ver la atribución y abrir sin red.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: solo se piden capas en EPSG:3857 (ADR-9); no se reproyecta el resultado en el móvil.]
- [SUPOSICIÓN: la biblioteca es común a todos los proyectos y las capas son por proyecto; no hay una lista global.]
- [SUPOSICIÓN: no hay contraseñas en los servicios WMS.]
- [SUPOSICIÓN: el límite es de 10 capas por proyecto, para no saturar la red del móvil.]
- [SUPOSICIÓN: las direcciones de los presets se verifican al planificar; si alguna cambió, se corrige en la spec.]
- [SUPOSICIÓN: el Catastro de S009 queda como interruptor propio; su preset de S036 es otra forma de añadirlo, con el mismo modo de petición.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S036 en estado `spec`.
- S009 «Fuera de alcance» y «Dudas»: el mapa base y el Catastro se recuerdan para toda la app; las capas por proyecto de S036 son aparte.
- `docs/glosario.md`: se añaden «Servicio WMS», «Biblioteca WMS», «Capa del proyecto», «Preset» y «Modo de petición»; «Capa (WMS)» se precisa como capa del proyecto.
