# Spec 041 — Replanteo de puntos y líneas de un dibujo DXF

> Hito: H2 · Depende de: S040, S019 · Estado: aprobada

## Contexto y objetivo
Con S040 el plano del proyectista ya se ve en el mapa, con sus capas y sus cotas. Esta spec lo lleva al terreno: replantear lo que dibuja el plano, tanto sus puntos y los vértices de sus líneas como las propias líneas (un eje, un borde de calzada, una alineación a la que hay que subir cada 10 m).

Hay dos casos. Un punto, un centro de bloque o un vértice es un punto de diseño fijo y se replantea con la guía, la diana, la tolerancia y los avisos de S018 y con la cota de S019. Una línea no tiene un punto de diseño fijo, sino un recorrido, y pide un motor propio con cinco modos: por segmentos, a la línea, a lo largo de la línea con desplazamiento, entre dos puntos A y B, y por intervalos. Ese motor se escribe aquí una sola vez y S027 lo reutiliza para las líneas propias del proyecto.

Los datos de campo siguen siendo sagrados (constitución nº 1): el dibujo es referencia y no se modifica; lo que se mide se guarda como un punto del proyecto, con su desviación respecto al diseño y de qué elemento del dibujo viene, sin sustituir a ningún otro. La calidad va antes que la rapidez (nº 3): con el control de precisión de S010, sin FIX la guía se apaga en todos los modos.

## Usuarios / actores
- Topógrafo en campo con el jalón y el RS3 en FIX (o una reproducción), que marca en el terreno lo que dice el plano.
- Técnico en oficina que después revisa cuánto se desvió cada punto replanteado respecto al dibujo.
- Receptor RS3 o una reproducción como fuente de las épocas.

## Historias de usuario
- H1: Como topógrafo quiero replantear un punto, un centro de bloque o un vértice del dibujo con la misma guía que un punto del proyecto.
- H2: Como topógrafo quiero que la app me diga a qué distancia estoy de una línea del dibujo, a derecha o izquierda, y en qué PK, y que me lleve a ella o a una paralela a ella.
- H3: Como topógrafo quiero replantear el tramo de una línea entre dos PK, y puntos a intervalos regulares, uno tras otro.
- H4: Como topógrafo quiero replantear también la cota del dibujo, o escribir una a mano si el elemento no la tiene.
- H5: Como técnico quiero que cada punto replanteado guarde de qué elemento, capa y dibujo viene y cuánto se desvió, y verlo en el informe de replanteo.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Elemento**, **capa**, **dibujo**, **con cota** y **sin cota**: los de S040.
- **Línea del dibujo**: un elemento que no es un punto: una línea, una polilínea, un arco, un círculo o una spline.
- **Punto de diseño del dibujo**: un punto, un centro de bloque (el punto de inserción de un bloque) o un vértice de una línea del dibujo.
- **Vértice original**: un vértice que estaba escrito en el DXF. Los de la aproximación de un arco, un círculo, una spline o un tramo con bulge no lo son (RF-29). Los extremos de un arco, de una spline y el punto de partida de un círculo cuentan como vértices.
- **Sentido de la línea**: el del orden de sus vértices.
- **PK**: la distancia a lo largo de la línea, en el SC del proyecto, desde su primer vértice hasta un punto de ella. En una línea cerrada, desde el primer vértice y hasta su perímetro.
- **Punto más cercano**: el punto de la línea más cercano a la punta.
- **Desplazamiento lateral**: la distancia a la que se quiere estar de la línea, medida perpendicularmente a ella; es positiva hacia la derecha del sentido de la línea y negativa hacia la izquierda.
- **Distancia lateral**: la distancia perpendicular con signo entre la punta y la línea, positiva a la derecha del sentido de la línea.
- **Error lateral**: la distancia lateral menos el desplazamiento lateral. Es lo que hay que corregir para estar donde se quiere.
- **Tolerancia**, **zona de ayuda**, **punta** y **rumbo del usuario**: los de S018.

### Elegir qué replantear (H1)
- RF-1: CUANDO el usuario pulse "Replantear" en la ficha de un punto o de un centro de bloque del dibujo (S040/RF-29), EL SISTEMA abrirá el replanteo de S018 con ese punto de diseño del dibujo, con su Este, su Norte y su cota si la tiene, en el SC del proyecto.
- RF-2: CUANDO el usuario pulse "Replantear" en la ficha de una línea del dibujo, EL SISTEMA abrirá el replanteo de línea con el último modo elegido o, la primera vez, con "A la línea".
- RF-3: EL SISTEMA ofrecerá en el replanteo de línea cinco modos, "Por segmentos", "A la línea", "A lo largo de la línea", "Tramo A–B" e "Intervalos", y recordará el último elegido para todos los proyectos.
- RF-4: MIENTRAS el replanteo de línea esté abierto, EL SISTEMA mostrará el nombre de la capa y del dibujo, el tipo de la línea, su longitud y, si la tiene, su cota.
- RF-5: EL SISTEMA dibujará en el mapa la línea elegida más gruesa que las demás y el objetivo de ahora (el vértice, el punto más cercano o el punto de la serie), con la punta y la línea hasta él (S018/RF-14).

### Por segmentos y vértices (H1)
- RF-6: DONDE el modo sea "Por segmentos", EL SISTEMA tomará como punto de diseño un vértice original de la línea y lo replanteará con toda la guía de S018 (RF-5 a RF-25).
- RF-7: CUANDO el usuario pulse "Siguiente" o "Anterior" en "Por segmentos", EL SISTEMA pasará al vértice siguiente o anterior en el sentido de la línea y no pasará de los extremos; en una línea cerrada, tras el último vértice volverá al primero.
- RF-8: EL SISTEMA mostrará en "Por segmentos" la lista de los vértices originales con su número y su PK, y permitirá elegir cualquiera como punto de diseño.
- RF-9: SI la línea es un arco o una spline, ENTONCES EL SISTEMA ofrecerá en "Por segmentos" solo sus dos extremos, y si es un círculo, solo su punto de partida.

### A la línea y a lo largo de la línea (H2)
- RF-10: DONDE el modo sea "A la línea", EL SISTEMA tomará como objetivo el punto más cercano de la línea, con desplazamiento lateral 0, y mostrará la distancia lateral con su lado (izquierda o derecha), el PK del punto más cercano y lo que falta hasta el final de la línea, en las unidades del proyecto.
- RF-11: DONDE el modo sea "A lo largo de la línea", EL SISTEMA permitirá escribir un desplazamiento lateral, con 0 por defecto, y tomará como objetivo el punto de la paralela a esa distancia más cercano a la punta, y mostrará el error lateral con su lado, el PK y lo que falta hasta el final.
- RF-12: EL SISTEMA mostrará en estos modos la flecha hacia el objetivo respecto al rumbo del usuario (S018/RF-7 a RF-11) y, en la zona de ayuda, la diana de S018 centrada en el objetivo.
- RF-13: MIENTRAS el valor absoluto del error lateral sea igual o menor que la tolerancia, EL SISTEMA mostrará la diana en verde y el texto "Dentro de la tolerancia", y avisará al entrar y al salir con los sonidos y las vibraciones de S018/RF-19 a RF-21.
- RF-14: SI la punta queda más allá de un extremo de la línea, ENTONCES EL SISTEMA tomará ese extremo como punto más cercano, dará la distancia hasta él sin lado y dirá "Antes del inicio" o "Después del final".
- RF-15: EL SISTEMA calculará el PK, la distancia lateral y el error lateral en el plano del SC del proyecto, con las distancias de cuadrícula.

### Tramo A–B e intervalos (H3)
- RF-16: DONDE el modo sea "Tramo A–B" o "Intervalos", EL SISTEMA permitirá escribir el PK de A y el de B, con el inicio y el final de la línea por defecto, un botón "Invertir" que los cambia entre sí y un botón "Mi posición" que pone como A el PK del punto más cercano a la punta.
- RF-17: DONDE el modo sea "Tramo A–B", EL SISTEMA actuará como "A lo largo de la línea" (con su desplazamiento lateral) limitado al tramo entre A y B, mostrará el PK respecto a A y lo que falta hasta B, y tomará como objetivo el extremo del tramo más cercano si la punta queda fuera de él.
- RF-18: DONDE el modo sea "Intervalos", EL SISTEMA permitirá escribir un intervalo y calculará los puntos de diseño en A, en A más un intervalo, más dos intervalos y así hasta B, que siempre se incluye aunque no caiga en un múltiplo, sobre la línea o sobre la paralela con el desplazamiento lateral.
- RF-19: SI el intervalo es igual o mayor que la longitud del tramo, ENTONCES EL SISTEMA dará solo los puntos A y B.
- RF-20: EL SISTEMA mostrará en "Intervalos" la lista de los puntos con su número y su PK, replanteará el elegido como un punto fijo con toda la guía de S018 y permitirá pasar al siguiente o al anterior.
- RF-21: SI el PK de A o de B, el intervalo o el desplazamiento lateral no es un número, o un PK queda fuera de la línea, o A y B son iguales, o el intervalo es 0 o negativo, ENTONCES EL SISTEMA no lo aplicará e indicará el motivo.
- RF-22: MIENTRAS haya una medida en marcha, EL SISTEMA no permitirá cambiar de modo, de línea, de vértice o de punto de la serie, de A, de B, de intervalo ni de desplazamiento lateral (S018/RF-30).

### La cota (H4)
- RF-23: EL SISTEMA tomará como cota de diseño la Z del punto o del vértice, y en una línea la Z interpolada linealmente a lo largo de ella en el PK del objetivo: el del punto más cercano en "A la línea", "A lo largo" y "Tramo A–B", y el de cada punto en "Intervalos".
- RF-24: SI el elemento está sin cota, o el dibujo está sin cotas, ENTONCES EL SISTEMA lo replanteará solo en planta y permitirá escribir una cota de diseño a mano, con la tolerancia vertical, el desmonte, el terraplén y los avisos de S019/RF-2 a RF-16.
- RF-25: EL SISTEMA mantendrá la llegada horizontal dependiendo solo del error lateral o de la distancia horizontal, con o sin cota (S019/RF-12).

### Guía apagada (H1, H2)
- RF-26: EL SISTEMA apagará la guía de todos los modos, con el motivo, el sonido y la vibración que corresponda, en los casos de S018/RF-22 a RF-25 y RF-35 y los volverá a encender al desaparecer la causa.

### Medir y guardar (H5)
- RF-27: CUANDO el usuario pulse "Medir", EL SISTEMA medirá el punto como en S018/RF-26 y lo guardará como un punto replanteado, con el nombre propuesto de la capa, el siguiente número libre entre los nombres del proyecto y `_stkd` (por ejemplo `Pozos_1_stkd`), que el usuario podrá cambiar antes de medir.
- RF-28: CUANDO termine la medida, EL SISTEMA guardará con el punto, sin redondear, las coordenadas y la cota de diseño del objetivo en ese momento, de qué elemento viene (dibujo, capa y tipo), el modo, el PK y el desplazamiento lateral si los hay, y las diferencias de Norte, Este, distancia horizontal, error lateral y cota entre lo medido y el diseño (medido menos diseño), y la tolerancia horizontal y vertical (S018/RF-28, S019/RF-13).
- RF-29: EL SISTEMA mostrará en la ficha del punto replanteado de qué elemento, capa y dibujo viene, el modo, el PK y el desplazamiento lateral, y la desviación respecto al diseño (S018/RF-32, S019/RF-15).
- RF-30: EL SISTEMA incluirá en el informe de replanteo de S019 los puntos replanteados de un dibujo, y añadirá a su CSV las columnas dibujo, capa, tipo de elemento, modo, PK y desplazamiento lateral, vacías en los puntos replanteados que vienen de un punto del proyecto (S019/RF-17 a RF-24).
- RF-31: SI el dibujo se borra mientras el replanteo está abierto, ENTONCES EL SISTEMA cerrará el replanteo e indicará el motivo.
- RF-32: EL SISTEMA conservará los puntos replanteados cuando se borre el dibujo, con lo guardado en RF-28.

### Los vértices originales (H1)
- RF-33: EL SISTEMA tratará como vértices solo los vértices originales del DXF (S040/RF-34).
- RF-34: SI el dibujo se importó antes de que S040 marcara los vértices originales, ENTONCES EL SISTEMA tratará todos sus vértices como originales y avisará en "Por segmentos" de un arco, una spline o un círculo de que hay que volver a importar el dibujo para replantear solo sus extremos.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el replanteo abierto.
  - RNF-T3: el corte se avisa con sonido o vibración en menos de 3 s.
  - RNF-T6: cada punto replanteado en una sola transacción con durabilidad completa; la medida bruta no se modifica nunca; al actualizar la app los puntos y los dibujos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el lado de la distancia lateral y el «Dentro de la tolerancia» nunca solo con color; TalkBack lee «a 1,2 m a la derecha de la línea, en el PK 45,0» al entrar en cada zona y a petición, sin anunciar cada refresco.
  - RNF-T9: unidades lineales y angulares del proyecto en la guía, el PK, el desplazamiento, el intervalo y las diferencias.
  - RNF-T13: la guía y la diana se refrescan a 5 Hz como máximo y calcular el punto más cercano en una línea de 100.000 vértices a 10 Hz no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: el PK, la distancia lateral, el error lateral y el punto más cercano coinciden con los calculados aparte con pyproj y un cálculo geométrico independiente a menos de 1 mm.
- La guía refleja una época nueva en menos de 300 ms desde que llega.
- Elegir una línea de 100.000 vértices y empezar a guiar tarda menos de 1 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Línea de un solo segmento y de miles de vértices | RF-10, RNF-T13 |
| Punta antes del inicio o después del final | RF-14 |
| Línea cerrada: PK, «Siguiente» tras el último vértice | RF-7, términos |
| Círculo, arco o spline en «Por segmentos» | RF-9 |
| Dibujo importado antes de marcar los vértices originales | RF-34 |
| Desplazamiento lateral a la izquierda (negativo) | RF-11 |
| Intervalo mayor que el tramo, 0 o negativo | RF-19, RF-21 |
| A y B iguales, PK fuera de la línea, valores que no son un número | RF-21 |
| «Invertir» y «Mi posición» | RF-16 |
| B que no es múltiplo del intervalo | RF-18 |
| Elemento o dibujo sin cota | RF-24 |
| Línea con cota: interpolación entre vértices | RF-23 |
| FIX perdido, IMU sin estado 30, corte de datos, sin posición | RF-26 |
| Cambiar de modo o de ajustes durante la medida | RF-22 |
| Replantear dos veces el mismo elemento | RF-27 (siguiente número libre) |
| Borrar el dibujo con el replanteo abierto, o después | RF-31, RF-32 |
| Capa oculta mientras se replantea | RF-5 (el mapa no la dibuja; el replanteo sigue) |
| SC geográfico | RF-15 (plano local, como S018) |
| Polilínea partida por S040 en trozos (más de unos 90.000 vértices) | Dudas abiertas |

## Fuera de alcance
- Replantear las líneas propias del proyecto con este motor → S027.
- Información geométrica de una línea (longitud 2D y 3D, área, pendiente) → S026.
- Distancias de terreno en lugar de cuadrícula → S026.
- Líneas, textos y acotaciones del DXF que S040 ignora → no previsto.
- Recortar o unir la paralela en las esquinas de una línea con desplazamiento → no previsto.
- Replantear superficies o rásteres → S042 y S045.
- Marcar en la lista los puntos de una serie ya medidos → no previsto.
- Orden de replanteo armado por el usuario entre elementos distintos → no previsto.
- Replantear con realidad aumentada → no previsto (solo RS4 Pro).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test del PK, la distancia lateral, el punto más cercano y la serie de intervalos frente a un cálculo geométrico aparte con pyproj (≤ 1 mm), con líneas rectas, polilíneas, líneas cerradas y desplazamientos a ambos lados.
- Test de migración de la base de datos con un proyecto de la versión anterior (con dibujos y con puntos replanteados).
- Reproducción sintética de un recorrido paralelo a una línea con un desplazamiento y su resultado esperado calculado aparte.
- Demo en el emulador con esa reproducción:
  - replantear un punto y un vértice de un dibujo y ver su `_stkd`;
  - «A la línea» y «A lo largo» con desplazamiento a derecha e izquierda, con su PK y su error lateral;
  - un «Tramo A–B» con «Invertir» y «Mi posición», e «Intervalos» con el intervalo mayor que el tramo;
  - una línea con cota y otra sin cota, con la cota a mano;
  - el informe de replanteo con las columnas del dibujo.
- Pruebas de campo con el RS3 (del usuario, puerta G2b): puntos y líneas de un DXF replanteados y comprobados después (≤ 2,5 cm en planta y ≤ 3 cm en cota).

## Dudas abiertas
Resueltas con una suposición. Si alguna no te vale, se cambia la spec antes de planificarla.
- [SUPOSICIÓN: «A la línea» es siempre con desplazamiento 0; «A lo largo de la línea» permite escribir el desplazamiento (RF-10, RF-11). Los otros modos con serie o tramo también (RF-17, RF-18).]
- [SUPOSICIÓN: «Tramo A–B» es el recorrido continuo entre A y B y «Intervalos» es la serie de puntos fijos; no hay un modo que mezcle los dos (RF-17, RF-18).]
- [SUPOSICIÓN: la paralela con desplazamiento no se recorta ni se une en las esquinas; en un vértice se usa el segmento más cercano (fuera de alcance).]
- [SUPOSICIÓN: el PK es de cuadrícula, en el plano del SC del proyecto (RF-15).]
- [SUPOSICIÓN: el modo se recuerda para todos los proyectos como en S018/RF-5, pero el desplazamiento, A, B y el intervalo no se recuerdan: cada línea empieza con 0, el inicio y el final de la línea y sin intervalo.]
- [SUPOSICIÓN: una polilínea que S040 guardó partida en trozos (más de unos 90.000 vértices) se replantea por trozos, como elementos distintos; no se une.]
- [SUPOSICIÓN: «Siguiente» tras el último vértice de una línea cerrada vuelve al primero (RF-7).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S041: pasa a `spec`, y después a `tareas`, `en curso` e `implementada`. Se añade a la descripción «con cota interpolada y vértices originales marcados por S040».
- `docs/glosario.md`: se añaden «PK» (`Chainage`), «Desplazamiento lateral» (`LateralOffset`), «Distancia lateral» (`CrossTrack`), «Error lateral» (`CrossTrackError`), «Replanteo de línea» (`LineStakeout`) y «Modo de línea» (`LineStakeoutMode`).
- S040: un RF nuevo, RF-34, «EL SISTEMA marcará en cada elemento qué vértices son originales del DXF y cuáles son de la aproximación de una curva, y lo guardará con el elemento», y el cambio en el formato de guardado de los trozos (una marca por vértice, compatible con los dibujos ya importados, que se leen con todos sus vértices como originales). Se hace como la primera tarea de S041.
- S018/RF-28: la referencia del punto de diseño deja de ser siempre un punto del proyecto: también puede ser un elemento de un dibujo (RF-28 de esta spec).
- S019/RF-21: el CSV del informe gana las columnas del dibujo (RF-30 de esta spec).
- La base de datos pasa de la versión 13 a la 14 (el replanteo guarda de qué elemento viene); S017b (fotos) pasará a la 15.
