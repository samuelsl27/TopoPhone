# Spec 027 — Replanteo de líneas del proyecto

> Hito: H3 · Depende de: S025, S041, S049 · Estado: pendiente de aprobación (entrevistada 05/11/2026)

## Contexto y objetivo
Con S025 el proyecto tiene líneas y polígonos propios: una alineación de farolas medida el lunes, el borde de una parcela, una zanja replanteada sobre lo levantado. El topógrafo quiere volver a ellos con el jalón: ir a un vértice, acercarse a la línea, caminar a lo largo de ella a una distancia fija o marcar un punto cada cierto número de metros. Todo eso ya lo hace S041 para las líneas de un DXF, con su motor de cinco modos, su PK, su error lateral, su cota y sus avisos.

Esta spec **no escribe un motor nuevo**: abre el replanteo de línea de S041 con una línea del proyecto como fuente, y se limita a lo que cambia cuando la línea es del proyecto y no de un dibujo: de dónde salen los vértices y la cota, cómo se nombra y qué se guarda del punto replanteado. Lo que necesite PK de un eje, derecha e izquierda con peralte o cota de rasante es S049 y S054; aquí solo se ofrece el paso a ellos. El dato de campo es sagrado (constitución nº 1): replantear no modifica la línea ni sus puntos, y el punto replanteado guarda el diseño tal como era.

## Usuarios / actores
- Topógrafo en campo con el jalón y el RS3 en FIX (o una reproducción), que replantea lo que levantó o preparó en el proyecto.

## Historias de usuario
- H1: Como topógrafo quiero replantear una línea o un polígono del proyecto con los mismos cinco modos que una línea de un plano.
- H2: Como topógrafo quiero que cada punto replanteado recuerde de qué línea viene y cuánto se desvió.
- H3: Como topógrafo quiero pasar una línea del proyecto a un eje cuando necesito PK, desplazamientos y rasante.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S025 y S041 más):
- **Replanteo de línea**, **modos**, **PK**, **desplazamiento lateral**, **distancia lateral**, **error lateral**, **punto más cercano**, **tolerancia**, **zona de ayuda**: los de S041 y S018.
- **Línea de diseño**: la línea del proyecto (S025) que se replantea; sus vértices son sus puntos, en el orden y el sentido de la línea; un polígono es una línea cerrada.
- **Cota de diseño de un objetivo**: la interpolada linealmente en su PK entre los dos vértices con altura que lo rodean; los vértices sin altura entre ellos se saltan. Antes del primer vértice con altura y después del último no hay cota de diseño.

### Abrir el replanteo (H1)
- RF-1: CUANDO el usuario pulse "Replantear" en la ficha de una línea o un polígono del proyecto, en su menú de la lista de objetos o en su menú del mapa, EL SISTEMA abrirá el replanteo de línea de S041 con esa línea como línea de diseño y con el último modo elegido (S041/RF-2, RF-3).
- RF-2: EL SISTEMA aplicará a la línea de diseño los mismos cinco modos, el mismo cálculo y los mismos avisos de S041/RF-3 a RF-26, sin duplicarlos: dada la misma geometría, una línea del proyecto y una de un dibujo darán el mismo PK, distancia lateral, error lateral, punto más cercano y serie de intervalos.
- RF-3: SI la línea tiene menos de 2 vértices, ENTONCES EL SISTEMA no abrirá el replanteo e indicará el motivo.
- RF-4: MIENTRAS el replanteo esté abierto, EL SISTEMA mostrará el nombre de la línea, su código, su longitud o su perímetro y si es una línea abierta o un polígono, y dibujará en el mapa la línea elegida más gruesa que las demás (S041/RF-4, RF-5).

### Por segmentos (H1)
- RF-5: DONDE el modo sea "Por segmentos", EL SISTEMA tomará como punto de diseño el punto del proyecto de cada vértice y mostrará en la lista su nombre, su número y su PK, con "Siguiente" y "Anterior" que en un polígono pasan del último al primero (S041/RF-7, RF-8).
- RF-6: EL SISTEMA tratará todos los vértices de la línea como vértices originales, sin arcos ni curvas que aproximar (S041/RF-9 no se aplica).
- RF-7: SI un vértice es un punto en la papelera, ENTONCES EL SISTEMA no lo ofrecerá en la lista ni en la línea (S025/RF-25).

### La cota (H1)
- RF-8: DONDE el objetivo tenga cota de diseño, EL SISTEMA tomará la altura del punto en un vértice y, en cualquier otro PK, la interpolada de la definición (S041/RF-23); en "Por segmentos", un vértice sin altura tomará la interpolada y se indicará que lo es.
- RF-9: SI el objetivo no tiene cota de diseño (la línea no tiene ninguna altura, o el objetivo cae antes del primer vértice con altura o después del último), ENTONCES EL SISTEMA lo indicará con los vértices sin altura y permitirá escribir una cota a mano para ese objetivo y replantearla con la tolerancia vertical (S041/RF-24, S019).
- RF-10: DONDE haya una superficie activa (S042) y el objetivo no tenga cota de diseño, EL SISTEMA tomará como cota de diseño la de la superficie, como en una línea sin cota de un dibujo (S042/RF-24).

### Medir y guardar (H2)
- RF-11: CUANDO el usuario pulse "Medir" en "Por segmentos", EL SISTEMA guardará un punto replanteado con el nombre del punto del vértice y `_stkd` (S018/RF-27); en los demás modos, con el nombre de la línea, el siguiente número libre entre los nombres del proyecto y `_stkd` (S041/RF-27).
- RF-12: CUANDO termine la medida, EL SISTEMA guardará, sin redondear, lo de S041/RF-28 (coordenadas y cota de diseño del objetivo al medir, modo, PK, desplazamiento lateral, diferencias y tolerancias) con la línea de la que viene (su identificador, su nombre y su código) en lugar del dibujo y la capa, y el punto heredará el código y los atributos de la línea (S024/RF-20).
- RF-13: EL SISTEMA mostrará en la ficha del punto replanteado de qué línea del proyecto viene, el modo, el PK y el desplazamiento lateral, y la desviación respecto al diseño (S041/RF-29).
- RF-14: EL SISTEMA incluirá los puntos de este replanteo en el informe de S019, con las columnas de S041/RF-30 y una columna «línea» con su nombre y vacías las de dibujo y capa.
- RF-15: SI se edita o se borra la línea, o cambia uno de sus vértices (se mueve, se edita o se borra el punto), mientras el replanteo está abierto, ENTONCES EL SISTEMA cerrará el replanteo e indicará el motivo, sin recalcular (S041/RF-31).
- RF-16: CUANDO se borre la línea, EL SISTEMA conservará los puntos replanteados con lo guardado en RF-12 (S041/RF-32).

### Pasar a un eje (H3)
- RF-17: CUANDO el usuario pulse "Crear eje" en la ficha de una línea abierta con al menos 2 vértices, EL SISTEMA abrirá la importación de S049/RF-6 y RF-7 con esa línea como si fuera la polilínea de un dibujo, con sus vértices como tramos rectos, sus alturas como rasante sin acuerdos si las tiene, y la línea y sus puntos intactos.
- RF-18: EL SISTEMA dejará el replanteo por PK y desplazamiento a la izquierda y a la derecha, con cota de rasante, en el eje creado (S054), sin repetir sus funciones aquí.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el replanteo abierto.
  - RNF-T3: el corte se avisa con sonido o vibración en menos de 3 s.
  - RNF-T6: cada punto replanteado en una sola transacción con durabilidad completa; al actualizar la app se conservan los puntos y las líneas existentes (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el lado de la distancia lateral nunca solo con color.
  - RNF-T9: unidades del proyecto en la guía, el PK y el intervalo.
  - RNF-T13: los de S041 (refresco a 5 Hz, línea de 100.000 vértices a 10 Hz sin bloquear más de 16 ms).
- Constitución nº 2: los resultados de RF-2 coinciden con los de S041 y con el cálculo independiente de S041 a menos de 1 mm.
- Abrir el replanteo de una línea de 100.000 vértices del proyecto tarda menos de 1 s.

## Casos límite
| Caso | RF |
|---|---|
| Línea de 1 vértice | RF-3 |
| Polígono: «Siguiente» tras el último vértice, perímetro y PK | RF-5 |
| Vértices sin altura, con altura mezclada o solo en el centro de la línea | RF-8, RF-9 |
| Vértice a la papelera durante el replanteo | RF-7, RF-15 |
| Editar un punto-vértice con el replanteo abierto | RF-15 |
| Replantear dos veces la misma línea | RF-11 (siguiente número libre) |
| Pérdida de FIX, de inclinación, de datos | S041/RF-26 |
| Línea con puntos «Calculado sobre eje» como vértices | RF-8 (su altura es la del punto) |
| SC geográfico | S041/RF-15 (plano local) |
| Línea oculta en el mapa | RF-4 (el replanteo sigue) |

## Fuera de alcance
- Replantear varias líneas seguidas o armar un orden entre elementos → no previsto.
- Recortar la paralela en las esquinas → no previsto.
- PK de un eje con curvas, peraltes, taludes y toma lineal → S049, S054, S050, S051, S052.
- Información geométrica de la línea (longitud, área) → S026.
- Editar la línea desde el replanteo → S025 (se edita fuera).
- Replanteo con realidad aumentada → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` en verde y test de migración de la base de datos.
- Un test compara la misma geometría como línea de un dibujo y como línea del proyecto (RF-2) en los cinco modos.
- Demo en el emulador con una reproducción que recorre una línea del proyecto:
  - «Por segmentos» con `_stkd` por vértice, y «A la línea» y «A lo largo» con desplazamiento a ambos lados;
  - «Tramo A–B» e «Intervalos» sobre un polígono;
  - una línea con y sin alturas;
  - el informe de replanteo con la columna de línea y «Crear eje» hasta ver el eje en el mapa.
- Demo de campo: queda para el usuario (replantear con el RS3 la línea que levantó, puerta de validación de H3).

## Dudas abiertas
- [SUPOSICIÓN: la línea se replantea con vértices rectos; no hay arcos ni curvas y todos los vértices son «originales».]
- [SUPOSICIÓN: S027 depende también de S049, solo por «Crear eje» (RF-17).]
- [SUPOSICIÓN: la cota escrita a mano (RF-9) vale solo para el objetivo en curso, como en S041/RF-24.]

## Decisiones de la entrevista (05/11/2026)
- «Crear eje» va dentro de S027 (RF-17 y RF-18).
- Una línea con alturas en unos vértices y no en otros interpola la cota entre los vértices que la tienen, saltando los que no (RF-8). Es distinto de S041, donde un dibujo mezclado se trata entero como sin cota.
- Antes del primer vértice con altura y después del último no hay cota de diseño: se avisa y se puede escribir una a mano o usar la superficie activa (RF-9, RF-10).
- Si la línea o uno de sus vértices cambia con el replanteo abierto, se cierra con el motivo y no se recalcula (RF-15).
- Los nombres son los del borrador: «Por segmentos», el del punto del vértice más `_stkd`; los demás modos, el de la línea, el siguiente número libre y `_stkd` (RF-11).
- El punto replanteado hereda el código y los atributos de la línea (RF-12).
- Sin preguntar, por defecto razonable: el replanteo se abre con el último modo elegido; la base de datos gana una versión (v31 y S017b a v32) para guardar la línea de la que viene el punto replanteado.

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S027: dependencias «025, 041, 049» (la última para «Crear eje»).
- `docs/glosario.md`: se añade «Línea de diseño» (`DesignLine`); «Replanteo de línea» y «Modo de línea» (S041) pasan a citar también a S027.
- S041: la referencia del punto replanteado puede ser también una línea del proyecto (RF-12 de esta spec); su `StakeLine.create` queda disponible para S027.
- S019/RF-21: el CSV del informe gana la columna «línea» (RF-14).
- S049/RF-1 y RF-6: la importación de un eje admite también una línea del proyecto como origen (RF-17).
- La base de datos gana una versión (S017b se desplaza una más).
