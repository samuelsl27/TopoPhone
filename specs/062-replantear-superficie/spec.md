# Spec 062 — Replantear la superficie

> Hito: H2 · Depende de: S042, S045, S019, S060, S061 · Estado: aprobada

## Contexto y objetivo
Hoy una superficie (TIN de S042 o ráster de S045) solo da la cota de diseño a un punto o a una línea que no la tienen, o informa en el recolector sin guardar nada (S042/RF-33). Pero una superficie no tiene puntos que replantear: lo que se replantea de ella es la **cota**. En obra se recorre la explanación y en cada sitio hay que saber si se excava o se rellena, y cuánto, y dejarlo medido para comprobarlo después. Esta spec añade un replanteo de la superficie solo en cota, sin objetivo en planta: dice en la posición de la punta el desmonte, el terraplén o «En cota» con la tolerancia vertical, y cada medida queda guardada como un punto replanteado con sus datos (constitución nº 1 y nº 3).

## Usuarios / actores
- Topógrafo en campo, replanteando una explanación, una rasante o un movimiento de tierras contra la superficie de proyecto.

## Historias de usuario
- H1: Como topógrafo quiero empezar a replantear una superficie desde donde la veo (la ficha, el mapa o el replanteo) para no buscar la función.
- H2: Como topógrafo quiero que, donde esté la punta, la app me diga si hay que excavar o rellenar y cuánto, para marcarlo en el terreno.
- H3: Como topógrafo quiero medir y guardar cada sitio replanteado con su diferencia para comprobar la obra y entregar el informe.

## Requisitos funcionales (criterios de aceptación en EARS)
### Empezar el replanteo (H1)
- RF-1: EL SISTEMA mostrará el botón «Replantear» en cada superficie de la ficha de Superficies (S042/RF-16).
- RF-2: EL SISTEMA mostrará el botón «Replantear» en la tarjeta de una superficie tocada en el mapa (S061/RF-16).
- RF-3: EL SISTEMA mostrará la fila «Replantear superficie» en el menú «Herramientas» de la lista de Objetos (S059/RF-4).
- RF-4: CUANDO el usuario elija «Replantear superficie» en «Herramientas», EL SISTEMA abrirá el replanteo de la superficie activa o, si no hay ninguna activa, la lista de superficies del proyecto para elegir una.
- RF-5: SI el proyecto no tiene ninguna superficie, ENTONCES EL SISTEMA mostrará la fila «Replantear superficie» desactivada con el motivo «Este proyecto no tiene superficies».
- RF-6: CUANDO el usuario replantee una superficie que no es la activa, EL SISTEMA la activará antes de abrir el replanteo (S042/RF-16, S045/RF-18) y la hará visible en el mapa (S061/RF-3).

### Guiar en cota (H2)
- RF-7: CUANDO empiece el replanteo de la superficie, EL SISTEMA mostrará la hoja de campo de S060 con una placa de guiado sin objetivo en planta: sin flecha, sin distancia y sin desplazamientos.
- RF-8: MIENTRAS se replantee la superficie, EL SISTEMA mostrará en la placa la cota de la superficie bajo la punta, el desplazamiento vertical, la cota de diseño (la de la superficie más el desplazamiento) y la cota de la punta, recalculadas con cada época.
- RF-9: MIENTRAS se replantee la superficie, EL SISTEMA mostrará la diferencia entre la cota de la punta y la de diseño como «Desmonte» (hay que excavar), «Terraplén» (hay que rellenar) o «En cota» dentro de la tolerancia vertical, con el nivel vertical, los colores, los avisos y los sonidos de S019/RF-2 a RF-16.
- RF-10: MIENTRAS se replantee la superficie, EL SISTEMA mostrará en la fila de ajustes las pastillas «Jalón» (S046), «Tolerancia V» (S019/RF-10) y «Desplazamiento» (el desplazamiento vertical del proyecto, S042/RF-19), y no las de «Tolerancia H», «Cota» ni «Modo».
- RF-11: CUANDO el usuario cambie el desplazamiento vertical en la pastilla «Desplazamiento», EL SISTEMA lo aplicará en la época siguiente y lo guardará como el desplazamiento del proyecto (S042/RF-19, RF-20).
- RF-12: MIENTRAS se replantee la superficie, EL SISTEMA centrará el mapa en el jalón con el norte arriba y una escala en la que se vean los triángulos o las celdas de la superficie (S061/RF-7, RF-9), y dejará de seguirlo si el usuario mueve el mapa (S060/RF-26).
- RF-13: SI la punta está fuera de la superficie (fuera del contorno, en un hueco o en una celda sin dato), ENTONCES EL SISTEMA mostrará «Fuera de la superficie» en lugar de las cotas de diseño y la diferencia (S042/RF-23).
- RF-14: SI la punta está fuera de la superficie, ENTONCES EL SISTEMA no permitirá medir y mostrará «Fuera de la superficie» como motivo de bloqueo (S060/RF-16).
- RF-15: MIENTRAS la solución o la inclinación no cumplan los umbrales del proyecto, EL SISTEMA bloqueará la medida como en cualquier replanteo (S047, S060/RF-16).

### Medir y guardar (H3)
- RF-16: CUANDO termine la medida, EL SISTEMA guardará un punto replanteado llamado «SUP» seguido del primer número libre del proyecto (SUP1, SUP2…), como cualquier punto medido (S010/RF-16 a RF-18).
- RF-17: CUANDO guarde el punto, EL SISTEMA guardará con él, sin redondear, el origen de la cota «Superficie», la superficie (nombre y fecha de importación), la cota de la superficie en la punta, el desplazamiento vertical, la cota de diseño, la diferencia de cota (medido menos diseño) y la tolerancia vertical (S042/RF-28, S045/RF-25).
- RF-18: CUANDO guarde el punto, EL SISTEMA lo marcará como «dentro de la tolerancia» si el valor absoluto de la diferencia de cota es igual o menor que la tolerancia vertical, sin evaluar ninguna tolerancia horizontal.
- RF-19: CUANDO guarde el punto, EL SISTEMA mostrará la confirmación con su nombre y la diferencia como desmonte, terraplén o en cota, y seguirá en el replanteo de la misma superficie para medir el siguiente.
- RF-20: EL SISTEMA mostrará en la ficha del punto lo guardado en RF-17 (S042/RF-30).
- RF-21: EL SISTEMA incluirá cada punto replanteado contra la superficie como una fila del informe de replanteo y de su CSV (S019/RF-17 a RF-24, S042/RF-31), con el nombre de la superficie en lugar del punto de diseño y vacías las columnas de planta.

### Cambios con el replanteo abierto
- RF-22: CUANDO el usuario desactive o borre la superficie que se replantea, o active otra, EL SISTEMA cerrará el replanteo de la superficie e indicará el motivo; los puntos ya guardados se conservan (S042/RF-32).
- RF-23: MIENTRAS haya una medida en marcha, EL SISTEMA no permitirá cambiar el desplazamiento vertical ni la tolerancia vertical (S019/RF-16).

## Requisitos no funcionales
- Transversales que aplican: RNF-T6 (cada punto replanteado en una sola transacción; la medida bruta no se modifica), RNF-T7, RNF-T8 (la diferencia y su sentido se leen a pleno sol con guantes; «Desmonte» y «Terraplén» no se distinguen solo por el color), RNF-T9, RNF-T13.
- La placa se actualiza a 10 Hz y cada época tarda menos de 16 ms en calcular la cota de la superficie, también con 1.000.000 de triángulos (como S042).
- La cota de diseño y la diferencia coinciden con un cálculo geométrico aparte (≤ 1 mm), en un TIN y en un ráster, con y sin desplazamiento.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto sin superficies | RF-5 |
| Proyecto con superficies pero ninguna activa | RF-4 |
| Replantear una superficie inactiva u oculta | RF-6 |
| Punta fuera del contorno, en un hueco o en una celda sin dato | RF-13, RF-14 |
| FIX perdido, inclinación no válida, corte de datos | RF-15 |
| Cambiar el desplazamiento vertical durante el replanteo | RF-11, RF-23 |
| Desactivar, borrar o cambiar la superficie activa con el replanteo abierto | RF-22 |
| Nombre SUP1 ya ocupado por otro punto | RF-16 |
| Muchos puntos replanteados de la misma superficie en el informe | RF-21 |
| Ráster como superficie | RF-8, RF-13, RF-17 |

## Fuera de alcance
- Dibujar la superficie en el mapa → S061.
- Replantear en planta sobre la superficie (ir a una cota o a una curva de nivel concreta) → no previsto.
- Volúmenes de desmonte y terraplén entre lo medido y la superficie → no previsto (S042).
- Elegir el prefijo o el nombre de los puntos replanteados contra la superficie → no previsto.
- Guardar la cota de la superficie en los puntos del recolector (S042/RF-33 se queda como está) → no previsto.

## Criterios de finalización
Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos y una demo con el simulador (y con el RS3 real si está disponible): replantear un LandXML de cerca de 1.000.000 de triángulos sin nombre de superficie y un ráster desde la ficha, desde el mapa y desde «Herramientas»; ver desmonte, terraplén y «En cota» al mover la punta; salir de la superficie y ver el bloqueo; medir SUP1 y SUP2 y verlos en la ficha y en el CSV del informe.

## Dudas abiertas
Resueltas con una suposición. Si alguna no te vale, se cambia la spec antes de planificarla.
- [SUPOSICIÓN: la app no tiene un menú general de replanteo (cada replanteo se abre desde su objeto); la entrada «de replanteo» que pediste se pone como fila «Replantear superficie» en «Herramientas» de Objetos (RF-3), al final de la lista.]
- [SUPOSICIÓN: los puntos se llaman SUP1, SUP2… con el primer número libre del proyecto (RF-16), en lugar del «_stkd» de los puntos de diseño, porque aquí no hay punto de diseño.]
- [SUPOSICIÓN: fuera de la superficie no se puede medir (RF-14), a diferencia del replanteo de un punto (S042/RF-29), porque un punto replanteado contra la superficie sin cota de la superficie no informa de nada.]
- [SUPOSICIÓN: el desplazamiento vertical se puede cambiar desde el propio replanteo con una pastilla (RF-10, RF-11), y sigue siendo uno por proyecto.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: se añade la fila S062 en H2 con estado `spec`.
- S059/RF-4: «Herramientas» gana la fila «Replantear superficie» al final.
- S019/RF-21: el CSV del informe admite filas de puntos replanteados contra la superficie, sin punto de diseño (RF-21 de esta spec).
- `docs/glosario.md`: se añade «Replanteo de la superficie» (`SurfaceStakeout`): replanteo solo en cota contra la superficie activa, sin objetivo en planta (S062).
- La base de datos pasa a la versión siguiente solo si el punto replanteado necesita distinguir su origen «superficie sin punto de diseño»; lo decide el plan.
