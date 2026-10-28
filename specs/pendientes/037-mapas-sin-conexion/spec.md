# Spec 037 — Mapas sin conexión

> Hito: H5 · Depende de: S036, S009, S006 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Muchas obras están donde no hay cobertura de datos. S009 guarda en el móvil, hasta 200 MB, el mapa base «ya visto», pero eso solo sirve donde el topógrafo ya pasó con conexión: la primera vez en una zona nueva, el mapa sale vacío (S009/RF-14). El topógrafo quiere preparar el trabajo en la oficina o en el hotel: «descargo la zona de la obra con la ortofoto y mis capas, y en el campo el mapa está».

Esta spec añade la descarga explícita de una zona del proyecto (mapa base y capas WMS de S036) y su gestión (tamaño, actualización, borrado). No cambia la caché automática de S009: la zona descargada es un almacén aparte, que no se descarta solo. El Catastro y OpenStreetMap quedan fuera porque sus condiciones de uso prohíben la descarga masiva (S009, «Fuera de alcance»).

## Usuarios / actores
- Topógrafo que prepara un proyecto en una zona sin cobertura.
- Servicios de mapas que el usuario usa: IGN (mapa vectorial y PNOA) y los WMS de la biblioteca de S036.

## Historias de usuario
- H1: Como topógrafo quiero descargar la zona de mi proyecto, sabiendo antes cuánto ocupa, para tener el mapa sin cobertura.
- H2: Como topógrafo quiero que el mapa use lo descargado sin avisos de «incompleto» dentro de la zona.
- H3: Como topógrafo quiero ver, actualizar y borrar mis zonas para controlar el espacio del móvil.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Zona sin conexión**: un área del proyecto con las fuentes elegidas descargadas hasta un nivel de detalle; pertenece a un proyecto.
- **Nivel de detalle**: «Normal» (hasta escala 1:5.000 aprox.), «Alto» (1:1.000) o «Máximo» (1 m en la barra de escala, S009/RF-12). Cada uno equivale a un nivel de zoom máximo de tesela.
- **Fuente descargable**: mapa vectorial del IGN, ortofoto PNOA y cada capa WMS de S036 pedida por teselas (S036/RF-12), salvo que el usuario la excluya. OpenStreetMap y Catastro nunca lo son.

### Descargar una zona (H1)
- RF-1: CUANDO el usuario elija «Mapas sin conexión» en el menú del proyecto o en el del mapa, EL SISTEMA mostrará las zonas del proyecto y la opción «Descargar zona».
- RF-2: EL SISTEMA permitirá definir el área como un radio de 100 m a 20 km alrededor del centro del proyecto (S006/RF-37) o como lo que se ve en el mapa; SI el proyecto no tiene centro, ENTONCES solo ofrecerá lo visible.
- RF-3: EL SISTEMA incluirá en la zona todo punto del terreno a una distancia del centro igual o menor que el radio, y todo el encuadre visible elegido.
- RF-4: EL SISTEMA permitirá marcar las fuentes descargables que se guardan y el nivel de detalle, y recordará la última elección.
- RF-5: ANTES de descargar, EL SISTEMA mostrará el número exacto de teselas por fuente, el tamaño estimado, el espacio libre del móvil y el espacio que ocupan ya los mapas sin conexión.
- RF-6: SI el espacio libre es menor que el estimado más 500 MB, o la zona superaría el límite de almacenamiento de mapas (RF-19), ENTONCES EL SISTEMA no permitirá empezar e indicará cuánto falta y cómo reducirlo (menos radio, menos detalle, menos fuentes).
- RF-7: SI no hay red con internet, ENTONCES EL SISTEMA no empezará e indicará el motivo; SI la conexión es de datos móviles y la descarga supera 50 MB, ENTONCES pedirá confirmación (RNF-T4).
- RF-8: MIENTRAS descargue, EL SISTEMA mostrará el progreso (teselas, MB y fuente en curso) y permitirá pausar, reanudar y cancelar.
- RF-9: SI la descarga se interrumpe (sin red, app cerrada, reinicio), ENTONCES EL SISTEMA dejará la zona como «Incompleta» con su porcentaje, conservará lo ya descargado y permitirá reanudar o borrar.
- RF-10: SI más del 2 % de las teselas de una fuente fallan tras los reintentos, ENTONCES EL SISTEMA marcará esa fuente como «Incompleta», dirá cuántas faltan y permitirá reintentarlas.

### Usar el mapa sin conexión (H2)
- RF-11: MIENTRAS no haya conexión, EL SISTEMA mostrará dentro de una zona completa el mapa descargado, sin el aviso de S009/RF-15 y con las atribuciones (S009/RF-9); fuera de ella seguirá S009/RF-13 a RF-15.
- RF-12: SI el usuario acerca el mapa por encima del nivel de detalle descargado, ENTONCES EL SISTEMA ampliará el último nivel y mostrará «Detalle máximo guardado».
- RF-13: DONDE exista una zona completa, EL SISTEMA usará sus teselas aunque haya conexión, y las capas con su orden, visibilidad y opacidad de S036.
- RF-14: EL SISTEMA no pedirá a la red las teselas que ya tiene en una zona completa.

### Gestionar las zonas (H3)
- RF-15: EL SISTEMA listará las zonas con su nombre (propuesto «<proyecto> – <fecha>»), fuentes, nivel de detalle, tamaño real, fecha de descarga y estado (Completa, Incompleta), y la ocupación total.
- RF-16: EL SISTEMA permitirá renombrar, «Actualizar» (volver a descargar) y borrar una zona con confirmación; borrar libera el espacio que mostraba la lista.
- RF-17: CUANDO se borre un proyecto definitivamente, EL SISTEMA borrará sus zonas; mientras esté en la papelera (S006), las conservará.
- RF-18: EL SISTEMA no incluirá el contenido de las zonas en las copias de seguridad ni en los paquetes de proyecto (S013), y las anotará en el log (S014) sin coordenadas, con fuente, teselas y resultado.
- RF-19: EL SISTEMA permitirá fijar en Ajustes el límite de almacenamiento de mapas sin conexión (2 GB por defecto, de 200 MB a 10 GB), distinto de la caché de S009.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T4: la descarga va por la red con internet, nunca por la Wi-Fi del receptor.
  - RNF-T5: nada se escribe fuera del almacenamiento privado de la app.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el estado de cada zona con texto además de color.
  - RNF-T13: durante la descarga el hilo principal no se bloquea más de 16 ms; el mapa se abre en menos de 1 s con o sin zonas (S009).
  - RNF-T14: atribución visible de IGN y de cada fuente WMS; las condiciones de uso de cada fuente se respetan.
- No más de 4 peticiones simultáneas a un mismo servidor.
- Borrar una zona de 1 GB tarda menos de 5 s.
- Constitución nº 2 (aplicada a la cobertura): el conjunto de teselas de una zona y la extensión (bbox) de cada una coinciden con un cálculo independiente: números de tesela exactos y bbox a menos de 1 mm en EPSG:3857.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin centro | RF-2 |
| Espacio insuficiente o límite superado | RF-6 |
| Sin red o con datos móviles | RF-7 |
| Descarga interrumpida | RF-9 |
| Fuente que falla en parte | RF-10 |
| Acercar más allá de lo descargado | RF-12 |
| Zona incompleta usada sin conexión | RF-11 (solo se da por completa si lo está) |
| Borrar un proyecto en la papelera | RF-17 |

## Fuera de alcance
- Descargar OpenStreetMap o el Catastro → no previsto (sus condiciones de uso lo prohíben).
- Descarga en segundo plano con la app cerrada o con notificación → no previsto.
- Importar o exportar ficheros de mapas (MBTiles) → no previsto.
- Descarga de rejillas y geoides → S034.
- Compartir zonas entre móviles o en las copias de seguridad → no previsto.
- Cartografía vectorial de otros países → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/` (como `tools/ejes`): teselas cubiertas por un radio y por un encuadre en cada nivel, con fórmulas analíticas de Web Mercator comprobadas con pyproj (EPSG:4326 → 3857) y la pertenencia del círculo con `pyproj.Geod` (puntos de la circunferencia a la distancia exacta); bbox de cada tesela a menos de 1 mm.
- Demo en el emulador con la red cortada: descargar una zona pequeña, activar el modo avión, abrir el proyecto y ver el mapa y las capas dentro de la zona y el fondo neutro fuera.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: OpenStreetMap y Catastro no se pueden descargar por sus condiciones de uso; solo IGN (vectorial y PNOA) y WMS pedidos por teselas (S036/RF-12; el Catastro, pedido como una imagen, no), cuyas condiciones de uso son responsabilidad del usuario, que responde de las condiciones del servicio.]
- [SUPOSICIÓN: la descarga solo avanza con la app abierta (sin servicio nuevo en primer plano, que exigiría otro tipo de servicio, RNF-T2).]
- [SUPOSICIÓN: las zonas no entran en las copias ni en los paquetes; se vuelven a descargar.]
- [SUPOSICIÓN: la zona descargada tiene prioridad sobre la red aunque haya conexión, y se actualiza solo a mano.]
- [SUPOSICIÓN: tres niveles de detalle, radio de 100 m a 20 km y límite global de 2 GB.]

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: fila S037: dependencias «036, 009, 006 (centro del proyecto)»; el mapa base del IGN podría descargarse sin esperar a S036.
- `docs/glosario.md`: «Zona sin conexión» (`OfflineRegion`), «Nivel de detalle» (`DetailLevel`), «Fuente descargable».
- S036: su modo de petición (RF-12) decide qué capas son descargables; un WMS propio por teselas podría ser descargable, uno en «una imagen», no.
- S009: su «Fuera de alcance» ya apunta a S037; sin cambios.
