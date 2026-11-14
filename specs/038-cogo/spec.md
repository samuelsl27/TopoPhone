# Spec 038 — COGO: inversa, poligonal e intersecciones

> Hito: H6 · Depende de: S011, S008, S026 · Estado: aprobada (entrevistada 14/11/2026)

## Contexto y objetivo
En campo hay puntos que no se pueden medir con el receptor: una esquina bajo un árbol, el cruce de dos alineaciones, un punto a una distancia y un rumbo de otro. Hoy el topógrafo los calcula en una libreta o en otra app y los teclea como puntos manuales (S011), sin rastro de cómo salieron. COGO (geometría de coordenadas) son esos cálculos de topografía plana, hechos con los puntos del proyecto:
- **Inversa**: distancia y azimut entre dos puntos.
- **Poligonal**: puntos sucesivos a partir de un punto, con un azimut, un ángulo o una deflexión y una distancia en cada tramo. Los tramos escritos se conservan en el proyecto aunque se cierre la app.
- **Intersección**: el punto que cumple dos condiciones de distancia o de dirección respecto a dos puntos.

Los resultados se guardan como puntos del proyecto de origen COGO, con la operación y las entradas con que se calcularon, y se replantean como cualquier otro. Los puntos de entrada nunca se modifican (constitución nº 1) y todo cálculo se demuestra frente a referencias independientes (nº 2).

## Usuarios / actores
- Topógrafo en campo o en oficina que necesita puntos calculados, o comprobar una distancia o un azimut.

## Historias de usuario
- H1: Como topógrafo quiero la distancia y el azimut entre dos puntos del proyecto.
- H2: Como topógrafo quiero calcular los puntos de una poligonal a partir de azimuts, ángulos o deflexiones y distancias, ver cuánto cierra y no perder lo escrito si se cierra la app.
- H3: Como topógrafo quiero calcular el punto de una intersección de distancias y direcciones y elegir la solución que me vale.
- H4: Como topógrafo quiero guardar los puntos calculados en el proyecto y replantearlos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Punto de entrada**: un punto activo del proyecto (ocultos incluidos, nunca la papelera) o un punto escrito a mano (Este y Norte) que no se guarda.
- **Azimut**: el de cuadrícula, en sentido horario desde el norte, en las unidades angulares del proyecto (RNF-T9). **Tramo**: cada distancia con la dirección que se le da en una poligonal.
- **Visual atrás** de un tramo: la dirección desde su punto de partida al punto anterior; en el primer tramo, al punto de referencia.
- **Ángulo a la derecha / a la izquierda**: el que forman, en el punto de partida del tramo, la visual atrás y la dirección del tramo, medido en sentido horario / antihorario.
- **Deflexión a la derecha / a la izquierda**: el que forman la prolongación de la visual atrás (la dirección del tramo anterior) y la dirección del tramo, hacia la derecha / hacia la izquierda.
- **Punto COGO**: un punto guardado desde un resultado, de origen "Calculado por COGO".
- **Solución**: cada punto que cumple las condiciones de una intersección; puede haber ninguna, una o dos.
- **Borrador de la poligonal**: los puntos de entrada y los tramos escritos y aún sin guardar como puntos; vive en el proyecto y no es ningún punto.

### Entradas (H1, H2, H3)
- RF-1: CUANDO el usuario abra "COGO" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA ofrecerá "Inversa", "Poligonal" e "Intersección".
- RF-2: SI el SC del proyecto es geográfico, ENTONCES EL SISTEMA no ofrecerá COGO e indicará que necesita un SC proyectado.
- RF-3: EL SISTEMA permitirá elegir cada punto de entrada de la lista de objetos, con búsqueda, o escribir su Este y su Norte, y mostrará su altura si la tiene.
- RF-4: EL SISTEMA aceptará los azimuts y ángulos en DD, DMS o gon según las unidades del proyecto y las distancias en su unidad lineal, y no aceptará lo que no sea un número, una distancia negativa, un azimut o un ángulo fuera de 0 (incluido) a 360° (400 gon, excluido), ni una deflexión fuera de 0 a 180° (200 gon).
- RF-5: EL SISTEMA usará la elección "Cuadrícula" o "Terreno" de S026/RF-9 (la misma para toda la app, "Cuadrícula" por defecto), mostrará siempre cuál está activa y permitirá cambiarla desde el cálculo, con lo que cambia también en S026; DONDE esté "Terreno", las distancias que se escriben y las que se muestran serán de terreno, convertidas con el factor combinado (S008) del primer punto de entrada, calculado como en S026/RF-10.

### Inversa (H1)
- RF-6: CUANDO se elijan dos puntos, EL SISTEMA mostrará la distancia horizontal de cuadrícula y de terreno, los azimuts directo e inverso, y la diferencia de Este, de Norte y de altura.
- RF-7: DONDE los dos puntos tengan altura, EL SISTEMA mostrará también la distancia inclinada y la pendiente en porcentaje.
- RF-8: SI los dos puntos coinciden, ENTONCES EL SISTEMA mostrará distancia 0 y azimut indefinido, sin error.
- RF-9: EL SISTEMA permitirá intercambiar los puntos y copiar el resultado como texto.

### Poligonal (H2)
- RF-10: EL SISTEMA pedirá un punto de inicio y, si algún tramo es por ángulo o por deflexión, un punto de referencia, y permitirá añadir, editar y quitar tramos de tres clases: azimut y distancia; ángulo (a la derecha o a la izquierda) y distancia; deflexión (a la derecha o a la izquierda) y distancia.
- RF-11: EL SISTEMA calculará en vivo el punto al final de cada tramo y mostrará su Este y su Norte, con un desnivel opcional por tramo que da su altura si el punto de inicio la tiene.
- RF-12: DONDE el usuario elija un punto de cierre, EL SISTEMA mostrará el error de cierre en Este y Norte, el lineal y la precisión relativa 1:N, sin corregir ningún punto.
- RF-31: SI la visual atrás de un tramo es indefinida (el punto de referencia coincide con el de inicio, o el tramo anterior mide 0), ENTONCES EL SISTEMA no calculará ese tramo ni los siguientes e indicará el motivo.

### Intersección (H3)
- RF-13: EL SISTEMA ofrecerá "Distancia–distancia", "Dirección–dirección" y "Distancia–dirección", cada una con sus dos puntos de entrada y su condición.
- RF-14: DONDE el usuario elija "Distancia–distancia", EL SISTEMA mostrará las dos soluciones, a la izquierda y a la derecha del sentido del primer al segundo punto, y una sola si las circunferencias son tangentes.
- RF-15: DONDE el usuario elija "Dirección–dirección", EL SISTEMA mostrará la solución con la distancia desde cada punto, y avisará si queda detrás de alguno (distancia negativa).
- RF-16: DONDE el usuario elija "Distancia–dirección", EL SISTEMA mostrará las soluciones, ninguna, una o dos, de la distancia al primer punto y el azimut desde el segundo.
- RF-17: SI no hay solución (circunferencias que no se cortan, concéntricas, direcciones paralelas), ENTONCES EL SISTEMA lo indicará con el motivo y no calculará nada.
- RF-18: EL SISTEMA permitirá escribir una altura opcional para cada solución; sin ella, el punto se guarda sin altura (S011/RF-18).

### Guardar los puntos (H4)
- RF-19: CUANDO el usuario marque soluciones o puntos de la poligonal y pulse "Guardar puntos", EL SISTEMA los creará en una sola transacción con durabilidad completa, con los nombres propuestos por la regla de S011/RF-16 y editables.
- RF-20: SI un nombre ya existe o se repite, ENTONCES EL SISTEMA lo indicará y no guardará ninguno.
- RF-21: SI un resultado queda fuera del ámbito del SC del proyecto, ENTONCES EL SISTEMA lo indicará y no lo guardará (como S028/RF-23).
- RF-22: EL SISTEMA guardará con cada punto COGO las coordenadas sin redondear junto con las calculadas en la otra forma, sin jalón ni calidad, con la operación, los puntos de entrada y los valores escritos (como S049/RF-22).
- RF-23: EL SISTEMA no modificará nunca un punto de entrada, y si el usuario sale sin guardar los puntos, no creará ninguno (el borrador de la poligonal se conserva, RF-26).
- RF-24: EL SISTEMA tratará un punto COGO como uno manual en todo lo demás: se edita con historial, se duplica, se borra a la papelera, se replantea (S018, S019), se oculta y sale en las exportaciones con el origen "Cogo" (S011, S012, S053).
- RF-25: EL SISTEMA anotará en el log cada cálculo guardado, sin nombres ni coordenadas (S014/RF-5).

### Borrador de la poligonal (H2)
- RF-26: CUANDO el usuario cambie el punto de inicio, el de referencia, el de cierre o un tramo de la poligonal, EL SISTEMA guardará el borrador en el proyecto al instante, en una sola transacción con durabilidad completa y sin crear ningún punto; habrá un solo borrador por proyecto, y la inversa y las intersecciones no se conservan.
- RF-27: CUANDO el usuario abra la poligonal de un proyecto que tiene borrador, EL SISTEMA lo recuperará tal como estaba, recalculado con las coordenadas actuales de los puntos, y ofrecerá "Descartar borrador" con confirmación.
- RF-28: SI un punto de entrada del borrador ya no está activo en el proyecto (borrado o en la papelera), ENTONCES EL SISTEMA indicará cuál y no calculará hasta que se elija otro.
- RF-29: EL SISTEMA tratará el borrador como un dato del proyecto: lo incluirá en la copia de seguridad (S013) y lo eliminará con el proyecto, y no lo llevará al duplicado ni a los paquetes de proyecto.
- RF-30: CUANDO el usuario guarde como puntos todos los puntos calculados de la poligonal, EL SISTEMA vaciará el borrador; si guarda solo una parte, lo conservará.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: los puntos COGO y el borrador se guardan en una sola transacción; al actualizar la app los puntos y los proyectos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; las soluciones de una intersección con texto ("izquierda", "derecha"), nunca solo con color.
  - RNF-T9: unidades lineales y angulares del proyecto; los números del log y de los ficheros con punto decimal.
  - RNF-T13: recalcular con cada cambio no bloquea el hilo principal más de 16 ms, y guardar el borrador no lo bloquea.
- Constitución nº 2: las distancias, azimuts y coordenadas coinciden con un cálculo aparte (Python) a menos de 1 mm y de 0,001″; el factor combinado, con `Proj.get_factors` de pyproj y la altura elipsoidal, a menos de 1e-9.
- Una poligonal de 200 tramos se recalcula en menos de 100 ms en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto geográfico | RF-2 |
| Puntos de entrada coincidentes | RF-8 |
| Un punto sin altura | RF-6, RF-7 |
| Azimut o ángulo de 360° o negativo, deflexión de más de 180°, distancia negativa | RF-4 |
| Circunferencias tangentes, concéntricas o que no se cortan | RF-14, RF-17 |
| Direcciones paralelas o coincidentes | RF-17 |
| Intersección detrás de un punto | RF-15 |
| Poligonal que no cierra | RF-12 |
| Primer tramo por ángulo sin punto de referencia, o con la referencia en el inicio | RF-10, RF-31 |
| Tramo anterior de longitud 0 seguido de uno por ángulo o deflexión | RF-31 |
| Deflexión de 180° (el tramo vuelve por donde vino) | RF-4 |
| Cerrar la app o girar el móvil con tramos escritos | RF-26, RF-27 |
| Punto del borrador borrado o en la papelera | RF-28 |
| Guardar solo parte de los puntos de la poligonal | RF-30 |
| Nombre repetido al guardar | RF-20 |
| Resultado fuera del huso | RF-21 |
| Entrada con coma decimal o en DMS | RF-4 |
| Borrar un punto de entrada después de guardar | RF-22 (el punto COGO conserva sus valores) |
| Cambiar Cuadrícula/Terreno en COGO | RF-5 (cambia también en S026) |

## Fuera de alcance
- Compensar la poligonal (Bowditch u otro método) → no previsto.
- Varios borradores por proyecto, o borradores de la inversa y de las intersecciones → no previsto.
- Intersección con desplazamientos, resección y arcos por tres puntos → no previsto.
- Área, perímetro y ángulo entre puntos → S026.
- Replantear desde el cálculo sin guardar el punto → no previsto.
- Guardar una inversa como informe → no previsto.
- Cálculos en proyectos geográficos o con puntos del dibujo CAD → no previsto.
- Cuadrícula o terreno como ajuste del proyecto → S035.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests frente a un cálculo aparte con ejemplos de manual y casos aleatorios fijados con semilla, en ETRS89 UTM, REGCAN95 y ED50, y en pies; los tres tipos de dirección de la poligonal con una poligonal cerrada de la que se conocen los puntos; el factor combinado frente a pyproj.
- Test de migración de la base de datos (un origen nuevo de punto y el borrador).
- Demo en el emulador: una inversa, una poligonal de cuatro tramos con cierre (con los tres tipos de dirección), cerrar la app a medias y recuperar el borrador, las tres intersecciones eligiendo solución, guardar los puntos y replantear uno.
- Demo de campo: queda para el usuario (medir dos puntos, calcular un tercero y replantearlo).

## Dudas abiertas
- [SUPOSICIÓN: el azimut es el de cuadrícula, sin convergencia de meridianos; la distancia de terreno es solo un cambio de escala con el factor combinado del primer punto (RF-5).]
- [SUPOSICIÓN: la inversa no crea puntos; solo se copia el resultado (RF-9).]
- [SUPOSICIÓN: COGO no está en proyectos geográficos, porque la geometría plana pierde sentido (RF-2).]
- [SUPOSICIÓN: un punto escrito a mano en el cálculo no se guarda en el proyecto salvo que sea un resultado (RF-3).]

## Decisiones de la entrevista (14/11/2026)
- Cuadrícula/Terreno es la elección global de S026, no una propia de cada cálculo (RF-5).
- La poligonal solo informa del error de cierre; no se compensa (RF-12).
- Los tramos por ángulo admiten ángulo a la derecha, a la izquierda y deflexión (RF-10).
- Los tramos escritos se conservan en el proyecto como borrador, aunque se cierre la app (RF-26 a RF-30).
- Sin preguntar, por defecto razonable: un solo borrador por proyecto y solo de la poligonal; el borrador se vacía al guardar todos los puntos y entra en la copia de seguridad pero no en el duplicado ni en los paquetes (RF-29, RF-30); la deflexión va de 0 a 180° y el ángulo de 0 a menos de 360° (RF-4); la visual atrás indefinida no calcula (RF-31); la columna Origin de las exportaciones escribe "Cogo" (RF-24).

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S038: pasa a `spec`; dependencias «011, 008, 026»; el alcance añade «ángulo o deflexión» y «borrador de la poligonal en el proyecto».
- `docs/glosario.md`: se añaden "Punto COGO" (`PointOrigin.COGO`), "Error de cierre" (`ClosureError`), "Deflexión" (`Deflection`) y "Borrador de la poligonal" (`TraverseDraft`); las filas "Inversa / poligonal / intersección" apuntan a S038.
- S012/RF-6: la columna Origin escribe "Cogo" para estos puntos (el GeoPackage de S053 escribe la misma columna, sin cambiar su spec); S011: el origen nuevo se trata como manual.
- S026/RF-9: la elección Cuadrícula/Terreno también la usa y la cambia COGO; no cambia su comportamiento.
