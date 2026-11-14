# Spec 037 — Mapas sin conexión

> Hito: H5 · Depende de: S036, S009, S006 · Estado: aprobada (14/11/2026)

## Contexto y objetivo
Muchas obras están donde no hay cobertura de datos. S009 guarda en el móvil, hasta 200 MB, el mapa base «ya visto», pero eso solo sirve donde el topógrafo ya pasó con conexión: la primera vez en una zona nueva, el mapa sale vacío (S009/RF-14). El topógrafo quiere preparar el trabajo en la oficina o en el hotel: «descargo la zona de la obra con la ortofoto y mis capas, y en el campo el mapa está».

Esta spec añade la descarga explícita de una zona del proyecto (mapa base y capas WMS de S036) y su gestión (tamaño, actualización, borrado). No cambia la caché automática de S009: la zona descargada es un almacén aparte, que no se descarta solo. El Catastro queda fuera porque sus condiciones de uso prohíben la descarga masiva (S009, «Fuera de alcance»); OpenFreeMap sí permite descargas sin conexión, con un tope por descarga (RF-9).

## Usuarios / actores
- Topógrafo que prepara un proyecto en una zona sin cobertura, y en especial en una obra lineal.
- Servicios de mapas que el usuario usa: IGN (mapa vectorial y PNOA), OpenFreeMap y los WMS de la biblioteca de S036.

## Historias de usuario
- H1: Como topógrafo quiero descargar la zona de mi proyecto, sabiendo antes cuánto ocupa, para tener el mapa sin cobertura.
- H2: Como topógrafo quiero que el mapa use lo descargado sin avisos de «incompleto» dentro de la zona.
- H3: Como topógrafo quiero ver, actualizar y borrar mis zonas para controlar el espacio del móvil.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Zona sin conexión**: un área del proyecto con las fuentes elegidas descargadas hasta un nivel de detalle; pertenece a un proyecto.
- **Nivel de detalle**: «Normal» (hasta escala 1:5.000 aprox.), «Alto» (1:1.000) o «Máximo» (1 m en la barra de escala, S009/RF-12). Cada uno equivale a un nivel de zoom máximo de tesela.
- **Fuente descargable**: mapa vectorial del IGN, ortofoto PNOA, mapa vectorial de OpenStreetMap (OpenFreeMap) y cada capa WMS de S036 pedida por teselas (S036/RF-12). El Catastro y las capas WMS pedidas como una imagen nunca lo son.
- **Todo el proyecto**: el rectángulo, en latitud y longitud, más pequeño que contiene los puntos del proyecto, ampliado en cada lado el margen elegido.

### Descargar una zona (H1)
- RF-1: CUANDO el usuario pulse «Mapas sin conexión» en «Capas» del mapa, EL SISTEMA mostrará las zonas del proyecto y la opción «Descargar zona».
- RF-2: EL SISTEMA permitirá definir el área de una zona como un radio alrededor del centro del proyecto (S006/RF-37), como el encuadre visible en el mapa o como todo el proyecto.
- RF-3: EL SISTEMA aceptará un radio de 100 m a 20 km.
- RF-4: EL SISTEMA aceptará para todo el proyecto un margen de 200 m a 2 km.
- RF-5: SI el proyecto no tiene centro, ENTONCES EL SISTEMA solo ofrecerá el encuadre visible.
- RF-6: SI el proyecto no tiene puntos, ENTONCES EL SISTEMA no ofrecerá todo el proyecto.
- RF-7: EL SISTEMA incluirá en la zona todo punto del terreno a una distancia del centro igual o menor que el radio, todo el encuadre visible elegido y todo el rectángulo de todo el proyecto.
- RF-8: EL SISTEMA marcará de entrada como fuentes el mapa base elegido en el mapa y las capas WMS descargables del proyecto, permitirá cambiarlas y elegir el nivel de detalle, y recordará el área y el nivel de la última descarga.
- RF-9: SI la zona necesita de OpenFreeMap más de 3.000 teselas o más de 50 MB estimados, ENTONCES EL SISTEMA no permitirá empezar e indicará que ese servicio solo admite descargas de ese tamaño y cómo reducirla (menos área o menos detalle).
- RF-10: ANTES de descargar capas de un servicio WMS añadido por el usuario (no de los presets), EL SISTEMA pedirá confirmación con el nombre del servicio y el texto «Comprueba que las condiciones de este servicio permiten guardar teselas», una vez por zona y servicio.
- RF-11: ANTES de descargar, EL SISTEMA mostrará el número exacto de teselas por fuente, el tamaño estimado, el espacio libre del móvil y el espacio que ocupan ya los mapas sin conexión.
- RF-12: SI el espacio libre es menor que el estimado más 500 MB, o la zona superaría el límite de almacenamiento de mapas (RF-31), ENTONCES EL SISTEMA no permitirá empezar e indicará cuánto falta y cómo reducirlo (menos radio, menos detalle, menos fuentes).
- RF-13: SI no hay red con internet, ENTONCES EL SISTEMA no empezará la descarga e indicará el motivo.
- RF-14: SI la conexión es de datos móviles y la descarga supera 50 MB, ENTONCES EL SISTEMA pedirá confirmación antes de empezar (RNF-T4).
- RF-15: MIENTRAS descargue, EL SISTEMA mostrará el progreso (teselas, MB y fuente en curso).
- RF-16: MIENTRAS descargue, EL SISTEMA permitirá pausar, reanudar y cancelar.
- RF-17: MIENTRAS descargue y la pantalla de la descarga esté a la vista, EL SISTEMA mantendrá la pantalla encendida.
- RF-18: SI la descarga se interrumpe (sin red, app cerrada, reinicio, la app pasa a segundo plano o se bloquea la pantalla), ENTONCES EL SISTEMA dejará la zona como «Incompleta» con su porcentaje, conservará lo ya descargado y permitirá reanudar o borrar.
- RF-19: SI más del 2 % de las teselas de una fuente fallan tras los reintentos, ENTONCES EL SISTEMA marcará esa fuente como «Incompleta», dirá cuántas faltan y permitirá reintentarlas.

### Usar el mapa sin conexión (H2)
- RF-20: MIENTRAS no haya conexión, EL SISTEMA mostrará las teselas guardadas de las zonas, con las atribuciones (S009/RF-9), y dentro del área de una fuente completa no mostrará el aviso de S009/RF-15; fuera de ella seguirá S009/RF-13 a RF-15.
- RF-21: SI el usuario acerca el mapa por encima del nivel de detalle descargado, ENTONCES EL SISTEMA ampliará el último nivel y mostrará «Detalle máximo guardado».
- RF-22: DONDE exista una zona, EL SISTEMA usará sus teselas aunque haya conexión, y las capas con su orden, visibilidad y opacidad de S036.
- RF-23: EL SISTEMA no pedirá a la red las teselas que ya tiene guardadas en una zona completa.

### Gestionar las zonas (H3)
- RF-24: EL SISTEMA listará las zonas con su nombre (propuesto «<proyecto> – <fecha>»), fuentes, nivel de detalle, tamaño real, fecha de descarga y estado (Completa, Incompleta), y la ocupación total.
- RF-25: EL SISTEMA permitirá renombrar una zona.
- RF-26: CUANDO el usuario pulse «Actualizar» en una zona, EL SISTEMA marcará sus teselas como caducadas para que, con conexión, cada una se sustituya por la nueva cuando el mapa la pida, y mantendrá la zona utilizable sin conexión con las teselas que tiene.
- RF-27: CUANDO el usuario borre una zona, EL SISTEMA pedirá confirmación y liberará el espacio que mostraba la lista.
- RF-28: CUANDO se borre un proyecto definitivamente, EL SISTEMA borrará sus zonas; mientras esté en la papelera (S006), las conservará.
- RF-29: EL SISTEMA no incluirá el contenido de las zonas en las copias de seguridad ni en los paquetes de proyecto (S013).
- RF-30: EL SISTEMA anotará en el log (S014) cada descarga, actualización y borrado de una zona, sin coordenadas, con fuente, teselas y resultado.
- RF-31: EL SISTEMA permitirá fijar en la pantalla «Mapas sin conexión» el límite de almacenamiento de estos mapas (2 GB por defecto, de 200 MB a 10 GB), distinto de la caché de S009.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T4: la descarga va por la red con internet, nunca por la Wi-Fi del receptor.
  - RNF-T5: nada se escribe fuera del almacenamiento privado de la app.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el estado de cada zona con texto además de color.
  - RNF-T13: durante la descarga el hilo principal no se bloquea más de 16 ms; el mapa se abre en menos de 1 s con o sin zonas (S009).
  - RNF-T14: atribución visible de IGN, de OpenFreeMap y de cada fuente WMS; las condiciones de uso de cada fuente se respetan.
- No más de 4 peticiones simultáneas a un mismo servidor, y a OpenFreeMap la app se identifica con su propio User-Agent (S009).
- Borrar una zona de 1 GB tarda menos de 5 s.
- Constitución nº 2 (aplicada a la cobertura): el conjunto de teselas de una zona, para un radio, un encuadre y todo el proyecto, y la extensión (bbox) de cada una coinciden con un cálculo independiente: números de tesela exactos y bbox a menos de 1 mm en EPSG:3857.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin centro | RF-5 |
| Proyecto sin puntos | RF-6 |
| Obra lineal de varios kilómetros | RF-4 |
| OpenFreeMap con más de 3.000 teselas o 50 MB | RF-9 |
| Servicio WMS añadido por el usuario | RF-10 |
| Espacio insuficiente o límite superado | RF-12 |
| Sin red o con datos móviles | RF-13, RF-14 |
| Descarga interrumpida o app en segundo plano | RF-18 |
| Fuente que falla en parte | RF-19 |
| Acercar más allá de lo descargado | RF-21 |
| Zona incompleta usada sin conexión | RF-20 (se ve lo descargado; el aviso de S009/RF-15 sigue) |
| Actualizar sin conexión | RF-26 (la zona sigue sirviendo con lo que tiene y se renueva al verla con conexión) |
| Borrar un proyecto en la papelera | RF-28 |

## Fuera de alcance
- Descargar el Catastro o una capa WMS pedida como una imagen → no previsto (sus condiciones de uso lo prohíben o no tiene teselas).
- Descargas de OpenFreeMap por encima de 3.000 teselas o 50 MB → no previsto: exigen un acuerdo con ese servicio.
- Descarga con la app cerrada, en segundo plano o con notificación → no previsto.
- Importar o exportar ficheros de mapas (MBTiles) → no previsto.
- Descarga de rejillas y geoides → S034.
- Compartir zonas entre móviles o en las copias de seguridad → no previsto.
- Cartografía vectorial de otros países → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/` (como `tools/ejes`): teselas cubiertas por un radio, por un encuadre y por el rectángulo de todo el proyecto en cada nivel, con fórmulas analíticas de Web Mercator comprobadas con pyproj (EPSG:4326 → 3857) y la pertenencia del círculo con `pyproj.Geod` (puntos de la circunferencia a la distancia exacta); bbox de cada tesela a menos de 1 mm.
- Demo en el emulador con la red cortada: descargar una zona pequeña, activar el modo avión, abrir el proyecto y ver el mapa y las capas dentro de la zona y el fondo neutro fuera.
- Demo de campo: queda para el usuario.

## Decisiones de la entrevista (14/11/2026)
- OpenFreeMap entra como fuente descargable con su tope de 3.000 teselas o 50 MB por descarga y 4 peticiones simultáneas (<https://openfreemap.org/tos/>, leído el 14/11/2026); el borrador lo excluía por error. S009 solo prohíbe la descarga del Catastro.
- La descarga solo avanza con la app abierta: sin servicio en primer plano ni notificación; al salir de la app queda «Incompleta» y se reanuda (RF-17, RF-18).
- El área admite también «Todo el proyecto» con margen, para obras lineales (RF-2, RF-4).
- Tres niveles de detalle, radio de 100 m a 20 km y límite de 2 GB, ajustable de 200 MB a 10 GB. Con la ortofoto del PNOA, en «Máximo», 500 m de radio son unos 12 MB, 2 km unos 190 MB y 5 km más de 1 GB; 20 km no caben.
- Con conexión manda la zona guardada y se actualiza solo a mano (RF-22, RF-23).
- Los servicios WMS añadidos a mano llevan una confirmación sobre sus condiciones de uso; los presets no (RF-10).

## Dudas abiertas
- [SUPOSICIÓN, no preguntada: las zonas no entran en las copias ni en los paquetes; se vuelven a descargar (RF-29).]
- [SUPOSICIÓN, no preguntada: el límite de almacenamiento está en la pantalla «Mapas sin conexión» y no en Ajustes, como la biblioteca WMS de S036, para no tocar S005 (RF-31).]
- [DECIDIDO 14/11/2026, tras T20: «Actualizar» renueva cada tesela al verla con conexión y no baja la zona entera de una vez, porque MapLibre no vuelve a pedir lo que ya tiene guardado; la zona sigue «Completa» y con su fecha de descarga (RF-26).]
- [SUPOSICIÓN, no preguntada: se usan las teselas guardadas de una zona incompleta, aunque el aviso de S009/RF-15 solo se oculta dentro del área de una fuente completa (RF-20).]
- [SUPOSICIÓN, no preguntada: el centro del proyecto del borrador (S006/RF-37) puede venir de su último encuadre, y entonces el radio se mide desde ahí.]

## Cambios en documentos anteriores (aplicados al aprobar esta spec)
- `docs/hoja-de-ruta.md`: fila S037 en estado `spec`, con dependencias «036, 009, 006 (centro del proyecto)»; el mapa base del IGN podría descargarse sin esperar a S036.
- `docs/glosario.md`: «Zona sin conexión» (`OfflineRegion`), «Nivel de detalle» (`DetailLevel`), «Fuente descargable» (`DownloadableSource`) y «Todo el proyecto» (`ProjectExtent`).
- S036: su modo de petición (RF-12) decide qué capas son descargables; un WMS propio por teselas es descargable, uno en «una imagen», no. Sin cambios en su spec.
- S009: su «Fuera de alcance» ya apunta a S037; sin cambios.
- `specs/pendientes/README.md`: la fila S037 sale del índice al moverla a `specs/`.
