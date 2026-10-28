# Spec 038 — COGO: inversa, poligonal e intersecciones

> Hito: H6 · Depende de: S011 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
En campo hay puntos que no se pueden medir con el receptor: una esquina bajo un árbol, el cruce de dos alineaciones, un punto a una distancia y un rumbo de otro. Hoy el topógrafo los calcula en una libreta o en otra app y los teclea como puntos manuales (S011), sin rastro de cómo salieron. COGO (geometría de coordenadas) son esos cálculos de topografía plana, hechos con los puntos del proyecto:
- **Inversa**: distancia y azimut entre dos puntos.
- **Poligonal**: puntos sucesivos a partir de un punto, con un azimut o un ángulo y una distancia en cada tramo.
- **Intersección**: el punto que cumple dos condiciones de distancia o de dirección respecto a dos puntos.

Los resultados se guardan como puntos del proyecto de origen COGO, con la operación y las entradas con que se calcularon, y se replantean como cualquier otro. Los puntos de entrada nunca se modifican (constitución nº 1) y todo cálculo se demuestra frente a referencias independientes (nº 2).

## Usuarios / actores
- Topógrafo en campo o en oficina que necesita puntos calculados, o comprobar una distancia o un azimut.

## Historias de usuario
- H1: Como topógrafo quiero la distancia y el azimut entre dos puntos del proyecto.
- H2: Como topógrafo quiero calcular los puntos de una poligonal a partir de azimuts o ángulos y distancias, y ver cuánto cierra.
- H3: Como topógrafo quiero calcular el punto de una intersección de distancias y direcciones y elegir la solución que me vale.
- H4: Como topógrafo quiero guardar los puntos calculados en el proyecto y replantearlos.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Punto de entrada**: un punto activo del proyecto (ocultos incluidos, nunca la papelera) o un punto escrito a mano (Este y Norte) que no se guarda.
- **Azimut**: el de cuadrícula, en sentido horario desde el norte, en las unidades angulares del proyecto (RNF-T9). **Tramo**: cada distancia con su azimut o su ángulo en una poligonal.
- **Punto COGO**: un punto guardado desde un resultado, de origen "Calculado por COGO".
- **Solución**: cada punto que cumple las condiciones de una intersección; puede haber ninguna, una o dos.

### Entradas (H1, H2, H3)
- RF-1: CUANDO el usuario abra "COGO" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA ofrecerá "Inversa", "Poligonal" e "Intersección".
- RF-2: SI el SC del proyecto es geográfico, ENTONCES EL SISTEMA no ofrecerá COGO e indicará que necesita un SC proyectado.
- RF-3: EL SISTEMA permitirá elegir cada punto de entrada de la lista de objetos, con búsqueda, o escribir su Este y su Norte, y mostrará su altura si la tiene.
- RF-4: EL SISTEMA aceptará los azimuts y ángulos en DD, DMS o gon según las unidades del proyecto y las distancias en su unidad lineal, y no aceptará lo que no sea un número, una distancia negativa o un azimut fuera de 0 a 360° (400 gon).
- RF-5: EL SISTEMA ofrecerá "Cuadrícula" y "Terreno", con "Cuadrícula" por defecto, y en "Terreno" convertirá las distancias con el factor combinado (S008) del primer punto de entrada.

### Inversa (H1)
- RF-6: CUANDO se elijan dos puntos, EL SISTEMA mostrará la distancia horizontal de cuadrícula y de terreno, los azimuts directo e inverso, y la diferencia de Este, de Norte y de altura.
- RF-7: DONDE los dos puntos tengan altura, EL SISTEMA mostrará también la distancia inclinada y la pendiente en porcentaje.
- RF-8: SI los dos puntos coinciden, ENTONCES EL SISTEMA mostrará distancia 0 y azimut indefinido, sin error.
- RF-9: EL SISTEMA permitirá intercambiar los puntos y copiar el resultado como texto.

### Poligonal (H2)
- RF-10: EL SISTEMA pedirá un punto de inicio y, para tramos por ángulo, un punto de referencia, y permitirá añadir, editar y quitar tramos de dos clases: azimut y distancia, o ángulo a la derecha respecto al tramo anterior y distancia.
- RF-11: EL SISTEMA calculará en vivo el punto al final de cada tramo y mostrará su Este y su Norte, con un desnivel opcional por tramo que da su altura si el punto de inicio la tiene.
- RF-12: DONDE el usuario elija un punto de cierre, EL SISTEMA mostrará el error de cierre en Este y Norte, el lineal y la precisión relativa 1:N, sin corregir ningún punto.

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
- RF-23: EL SISTEMA no modificará nunca un punto de entrada, y si el usuario sale sin guardar, no creará ningún punto.
- RF-24: EL SISTEMA tratará un punto COGO como uno manual en todo lo demás: se edita con historial, se duplica, se borra a la papelera, se replantea (S018, S019), se oculta y sale en las exportaciones con el origen "COGO" (S011, S012).
- RF-25: EL SISTEMA anotará en el log cada cálculo guardado, sin nombres ni coordenadas (S014/RF-5).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: los puntos COGO se guardan en una sola transacción; al actualizar la app los puntos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; las soluciones de una intersección con texto ("izquierda", "derecha"), nunca solo con color.
  - RNF-T9: unidades lineales y angulares del proyecto; los números del log y de los ficheros con punto decimal.
  - RNF-T13: recalcular con cada cambio no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: las distancias, azimuts y coordenadas coinciden con un cálculo aparte (Python) a menos de 1 mm y de 0,001″; el factor combinado, con `Proj.get_factors` de pyproj y la altura elipsoidal, a menos de 1e-9.
- Una poligonal de 200 tramos se recalcula en menos de 100 ms en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Proyecto geográfico | RF-2 |
| Puntos de entrada coincidentes | RF-8 |
| Un punto sin altura | RF-6, RF-7 |
| Azimut de 360° o negativo, distancia negativa | RF-4 |
| Circunferencias tangentes, concéntricas o que no se cortan | RF-14, RF-17 |
| Direcciones paralelas o coincidentes | RF-17 |
| Intersección detrás de un punto | RF-15 |
| Poligonal que no cierra | RF-12 |
| Nombre repetido al guardar | RF-20 |
| Resultado fuera del huso | RF-21 |
| Entrada con coma decimal o en DMS | RF-4 |
| Borrar un punto de entrada después de guardar | RF-22 (el punto COGO conserva sus valores) |

## Fuera de alcance
- Compensar la poligonal (Bowditch u otro método) → no previsto.
- Intersección con desplazamientos, resección y arcos por tres puntos → no previsto.
- Área, perímetro y ángulo entre puntos → S026.
- Replantear desde el cálculo sin guardar el punto → no previsto.
- Guardar una inversa como informe → no previsto.
- Cálculos en proyectos geográficos o con puntos del dibujo CAD → no previsto.
- Cuadrícula o terreno como ajuste del proyecto → S035.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests frente a un cálculo aparte con ejemplos de manual y casos aleatorios fijados con semilla, en ETRS89 UTM, REGCAN95 y ED50, y en pies; el factor combinado frente a pyproj.
- Test de migración de la base de datos (un origen nuevo de punto).
- Demo en el emulador: una inversa, una poligonal de cuatro tramos con cierre, las tres intersecciones eligiendo solución, guardar los puntos y replantear uno.
- Demo de campo: queda para el usuario (medir dos puntos, calcular un tercero y replantearlo).

## Dudas abiertas
- [SUPOSICIÓN: el azimut es el de cuadrícula, sin convergencia de meridianos; la distancia de terreno es solo un cambio de escala con el factor combinado del primer punto (RF-5).]
- [SUPOSICIÓN: la poligonal es una radiación sucesiva sin compensación; el cierre solo se informa (RF-12).]
- [SUPOSICIÓN: la inversa no crea puntos; solo se copia el resultado (RF-9).]
- [SUPOSICIÓN: los tramos por ángulo miden el ángulo a la derecha respecto al tramo anterior, el de topografía de obra; no hay deflexión ni ángulo a la izquierda (RF-10).]
- [SUPOSICIÓN: COGO no está en proyectos geográficos, porque la geometría plana pierde sentido (RF-2).]
- [SUPOSICIÓN: un punto escrito a mano en el cálculo no se guarda en el proyecto salvo que sea un resultado (RF-3).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S038: pasa a `spec`.
- `docs/glosario.md`: se añaden "Punto COGO" (`PointOrigin.COGO`) y "Error de cierre" (`ClosureError`); las filas "Inversa / poligonal / intersección" apuntan a S038.
- S012/RF-6: la columna Origin escribe "COGO" para estos puntos; S011: el origen nuevo se trata como manual.
