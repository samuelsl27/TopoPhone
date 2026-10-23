# Spec 016 — Inclinación del RS3

> Hito: H2 · Depende de: S015, S010 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Hasta ahora la app supone que el jalón está vertical: el punto del suelo queda bajo la antena, a la altura del jalón más 0,134 m (S002/RF-32). Pero el RS3 lleva un IMU que mide la inclinación del jalón y la manda en la frase ETC, que la app ya lee (S003/RF-6). Con la inclinación se mide sin nivelar la burbuja y en sitios donde no se puede poner el jalón vertical, como una esquina de un edificio o el borde de una arqueta.

El receptor trabaja de dos formas, y por NMEA no se sabe cuál tiene puesta:
- **No compensa la posición**: la GGA da la antena, y la app calcula la punta del jalón con la inclinación del ETC. Es el modo recomendado (ADR-7), porque la altura del jalón está en la app.
- **Compensa la posición**: la GGA ya da la punta, con la altura de antena que tenga configurada el receptor.

Si la app se equivoca de modo, resta el jalón dos veces o ninguna: la cota sale 1,9 m mal y la planta, hasta 1,7 m mal con el jalón inclinado. Por eso esta spec trae un asistente que detecta el modo con una prueba de inclinación y no deja medir con un receptor cuyo modo no se conoce.

Además, la app muestra el estado y los avisos de la inclinación, guía la inicialización con el movimiento en ocho y no deja medir sin el estado 30 (Compensando), ni con un aviso del ETC (constitución nº 3). Cada punto guarda su inclinación y su rumbo, y su precisión incluye la de la inclinación (constitución nº 1).

## Usuarios / actores
- Topógrafo en campo con el RS3 en FIX que mide con el jalón inclinado o sin nivelar la burbuja.
- Técnico en oficina que revisa con qué inclinación y en qué modo se midió cada punto.
- Receptor RS3: frases GGA, GST y ETC (estado, aviso, rumbo, dirección y valor de la inclinación, y coeficientes de precisión por eje, según ETC_rev_3).

## Historias de usuario
- H1: Como topógrafo quiero decirle a la app, una vez por receptor, si el receptor compensa la posición o no, para que la punta del jalón salga bien.
- H2: Como topógrafo quiero que la app compruebe ese modo con una prueba sencilla en lugar de fiarme de lo que recuerdo haber configurado.
- H3: Como topógrafo quiero ver el estado de la inclinación y saber qué hacer cuando no está lista, como el movimiento en ocho.
- H4: Como topógrafo quiero ver en el mapa y en las coordenadas la punta del jalón, no la antena.
- H5: Como topógrafo quiero que la app no mida si la inclinación no es válida, y que la precisión del punto incluya la de la inclinación.
- H6: Como técnico quiero que cada punto guarde su modo, su inclinación y su rumbo para justificarlo después en la ficha y en el CSV.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **El modo usa la inclinación**: el modo es "Calculada por la app" o "Compensada por el receptor".
- **Inclinación encendida**: el ETC trae un estado (0, 10, 20 o 30); con el estado vacío, está apagada.
- **Época en estado 30**: su ETC trae el estado 30 con la inclinación, su dirección y los tres coeficientes; si falta alguno, la época cuenta como fuera del estado 30.
- **Movimiento esperado** (asistente): el que tendría la antena en planta entre la referencia y la posición inclinada, según las inclinaciones del ETC y una altura de antena igual a la altura del jalón de la app más 0,134 m.
- **Movimiento de la GGA** (asistente): el vector en planta entre la media de 1 s de posiciones GGA de la referencia y la de la posición inclinada.

### Modo de inclinación (H1)
- RF-1: EL SISTEMA ofrecerá en el apartado "Inclinación" del menú del receptor (S005), junto al asistente del modo, tres modos de inclinación ("Calculada por la app", "Compensada por el receptor" y "Jalón vertical"), y recordará el elegido para cada receptor.
- RF-2: MIENTRAS un receptor no tenga modo elegido y envíe frases ETC con la inclinación encendida, EL SISTEMA no permitirá medir con él e indicará que hay que pasar el asistente o elegir el modo, con un enlace al apartado "Inclinación".
- RF-3: CUANDO el usuario elija el modo a mano, EL SISTEMA lo guardará como "sin comprobar".
- RF-4: SI el usuario elige "Jalón vertical" para un receptor que el asistente encontró compensando, ENTONCES EL SISTEMA avisará de que el receptor tiene que estar sin compensar la posición o con la inclinación apagada.

### Asistente del modo (H2)
- RF-5: MIENTRAS el asistente esté abierto sin FIX o sin épocas en estado 30 sin aviso, EL SISTEMA indicará qué falta y no dejará empezar la prueba.
- RF-6: CUANDO el usuario empiece la prueba, EL SISTEMA pedirá mantener la punta del jalón en el mismo sitio, tomará como referencia la media de 1 s de posiciones GGA con el jalón a menos de 5° de la vertical y pedirá inclinarlo 15° o más, y más aún mientras el movimiento esperado sea menor de 20 cm.
- RF-7: CUANDO el jalón lleve 1 s inclinado 15° o más con un movimiento esperado de 20 cm o más, y el movimiento de la GGA sea menor de 5 cm, EL SISTEMA propondrá "Compensada por el receptor" y recordará que el modo recomendado es "Calculada por la app".
- RF-8: CUANDO el jalón lleve 1 s inclinado 15° o más con un movimiento esperado de 20 cm o más, y la diferencia entre el movimiento de la GGA y el esperado sea menor que el 25 % del esperado, EL SISTEMA propondrá "Calculada por la app".
- RF-9: SI el movimiento de la GGA no encaja con ninguno de los dos modos, se pierde el FIX o el estado 30 o llega un aviso del ETC durante la prueba, o en 60 s el jalón no llega a 15° con un movimiento esperado de 20 cm, ENTONCES EL SISTEMA dirá que la prueba no es concluyente y permitirá repetirla.
- RF-10: CUANDO el usuario confirme el modo propuesto, EL SISTEMA lo guardará como "comprobado" para ese receptor.

### Estado y guía (H3)
- RF-11: MIENTRAS lleguen frases ETC, EL SISTEMA mostrará en la pantalla Estado el estado y el aviso de la inclinación y, en estado 30, la inclinación, su dirección y el rumbo con el formato angular de los proyectos nuevos (S006), y la precisión de la inclinación en planta y en vertical, en metros con 3 decimales.
- RF-12: MIENTRAS el modo use la inclinación y la época no esté en estado 30 sin aviso, EL SISTEMA mostrará en el recolector la guía del estado o del aviso:

  | ETC | Guía |
  |---|---|
  | Sin ETC, o inclinación apagada | Activar la inclinación y la frase ETC en el receptor, o elegir "Jalón vertical" |
  | 0 · Error | Reiniciar el receptor y, si sigue, contactar con el soporte del fabricante |
  | 10 · Configuración | Esperar: el receptor está preparando la inclinación |
  | 20 · Alineación | Mover el receptor en ocho hasta que la inclinación pase a "Compensando", con una ilustración propia del movimiento |
  | 30 con aviso 10 · Movimiento rápido | Mover el receptor más despacio |
  | 30 con aviso 20 · GNSS malo | Buscar un sitio con más cielo despejado: la inclinación puede volver a alineación |
  | 30 con aviso 30 · Fallo del filtro | Mover el receptor en ocho para volver a alinear |

### Punta del jalón (H4)
- RF-13: MIENTRAS el modo sea "Calculada por la app" y la época esté en estado 30, EL SISTEMA calculará en cada época la punta del jalón como indica ETC_rev_3, desde la posición GGA con su altura elipsoidal (ADR-6) y con una altura de antena igual a la altura del jalón más 0,134 m, antes de pasar al SC del proyecto.
- RF-14: MIENTRAS el modo sea "Compensada por el receptor", EL SISTEMA tomará la posición GGA como la punta del jalón, sin restarle la altura del jalón.
- RF-15: MIENTRAS el modo sea "Compensada por el receptor", EL SISTEMA rotulará la altura del jalón (la misma de toda la app, S010/RF-5) como la configurada en el receptor y recordará en el recolector que tienen que coincidir.
- RF-16: MIENTRAS el modo use la inclinación y la época esté en estado 30, EL SISTEMA mostrará la punta del jalón en el panel de posición, en el rover del mapa y en la pantalla Coordenadas.
- RF-17: MIENTRAS el modo use la inclinación y la época no esté en estado 30, EL SISTEMA mostrará "Punta sin compensar" junto a la posición del panel de posición y de la pantalla Coordenadas.
- RF-27: SI la GGA no trae altitud o separación del geoide, ENTONCES EL SISTEMA calculará la punta del jalón solo en planta y la dejará sin altura, como el punto del jalón vertical sin altura elipsoidal (S002).

### Medida con inclinación (H5)
- RF-18: MIENTRAS el modo use la inclinación y la época no esté en estado 30, EL SISTEMA no permitirá empezar una medida e indicará el motivo con la guía de RF-12 y un enlace al apartado "Inclinación".
- RF-19: SI durante una medida llega una época fuera del estado 30 o con un aviso del ETC, ENTONCES EL SISTEMA la descartará, avisará con el texto de la guía, un sonido y una vibración una sola vez hasta que vuelvan épocas válidas, y seguirá hasta completar la medida con ellas.
- RF-20: DONDE el modo use la inclinación, EL SISTEMA añadirá al control de precisión (S010/RF-13) la condición "Inclinación máxima", activa por defecto con 60°, ajustable de 1° a 60° y recordada para todos los proyectos, que no se cumple si alguna época usada la supera.
- RF-21: MIENTRAS el modo sea "Calculada por la app", EL SISTEMA dará como precisión horizontal la de la GST (S010/RF-22) más la de la inclinación en planta, y como vertical la de la GST más la de la inclinación en vertical, calculadas como indica ETC_rev_3, en el recolector, en el círculo de precisión, en el control de precisión y en el punto guardado.
- RF-28: SI el usuario cambia el modo de inclinación o abre el asistente durante una medida, ENTONCES EL SISTEMA cancelará la medida sin guardar nada (S010/RF-11).

### Inclinación de cada punto (H6)
- RF-22: CUANDO se guarde un punto medido, EL SISTEMA guardará con él el modo de inclinación, si estaba comprobado y las frases ETC de las épocas usadas y, si estas están en estado 30, en cualquier modo, también la inclinación, su dirección y el rumbo de la última época usada, la mayor inclinación de las épocas usadas y las precisiones de la inclinación en planta y en vertical.
- RF-23: DONDE el punto tenga inclinación, EL SISTEMA mostrará en su ficha el modo, la inclinación, su dirección, el rumbo, la mayor inclinación y las precisiones de la inclinación en planta y en vertical, en la unidad angular y lineal del proyecto.
- RF-24: EL SISTEMA escribirá en "Todas las columnas" de la exportación CSV (S012/RF-6), tras las columnas de la base, el modo de inclinación, si estaba comprobado, la inclinación, su dirección, el rumbo y la mayor inclinación, en grados decimales, y las precisiones de la inclinación en planta y en vertical, en la unidad lineal del proyecto.
- RF-25: CUANDO el usuario cambie la altura del jalón de un punto medido con inclinación (S011/RF-14), EL SISTEMA recalculará la punta desplazándola a lo largo del jalón con las inclinaciones guardadas de sus épocas.
- RF-26: DONDE el punto se haya medido en "Compensada por el receptor", EL SISTEMA rotulará en su ficha la posición del receptor guardada (S010/RF-17) como punta compensada por el receptor, porque la GGA ya es la punta.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: el punto se guarda con su inclinación en la misma transacción; al actualizar la app, los puntos existentes se conservan y quedan como medidos con "Jalón vertical", que es como se calcularon.
  - RNF-T7 y RNF-T8: textos en recursos; la guía se da con texto e ilustración, nunca solo con color; zonas táctiles de 56 dp; sonido y vibración al descartar épocas; TalkBack lee el estado, el aviso, la inclinación y el paso del asistente en que se está.
  - RNF-T9: dirección de la inclinación y rumbo en sentido horario desde el norte; unidades del proyecto en la ficha.
  - RNF-T13: la pantalla Estado, el panel de posición y el rover se refrescan a 5 Hz como máximo, con la punta calculada en cada época de hasta 10 Hz sin bloquear el hilo principal más de 16 ms.
  - RNF-T14: la ilustración del movimiento en ocho y los textos de la guía son propios.
- Constitución nº 2:
  - la punta calculada coincide con pyproj (de geográficas a ECEF, desplazamiento en el plano tangente local y vuelta a geográficas) a menos de 1 mm, con inclinaciones de 0° a 60°, en la Península y en Canarias;
  - con la inclinación a 0°, la punta coincide con la del jalón vertical (S002/RF-32) a menos de 0,1 mm;
  - la punta recalculada tras corregir el jalón (RF-25) coincide con la calculada aparte a menos de 1 mm.
- El asistente propone el modo en menos de 1 s desde que se cumplen las condiciones de RF-7 o RF-8.

## Casos límite
| Caso | RF |
|---|---|
| Receptor sin modo elegido que no manda ETC, o con la inclinación apagada | RF-2 (no bloquea): punto con el jalón vertical, como hasta ahora (S002/RF-32) |
| Receptor sin modo elegido con la inclinación encendida | RF-2 |
| Modo elegido a mano sin pasar el asistente | RF-3 (se mide; el punto queda "sin comprobar") |
| "Jalón vertical" con un receptor que compensa | RF-4 |
| El asistente empieza sin FIX, en alineación o con un aviso | RF-5 |
| Jalón de 0 m en el asistente (a 15°, la antena se mueve 3,5 cm) | RF-6 (pide inclinar más), RF-9 |
| Se mueve la punta durante la prueba | RF-9 (no concluyente), o un modo erróneo si el movimiento imita al otro: por eso se pide confirmación (RF-10) |
| Receptor que compensa con una altura de antena mal configurada | RF-9 (no concluyente si la diferencia mueve la GGA 5 cm o más), RF-15 |
| Aviso del ETC o pérdida del FIX durante la prueba | RF-9 |
| El jalón no llega a 15° en 60 s | RF-9 |
| ETC en alineación al abrir el recolector | RF-12 (ocho), RF-18 |
| ETC en error (estado 0) | RF-12 (reiniciar), RF-18 |
| Modo con inclinación y el receptor deja de mandar ETC | RF-12, RF-18, RF-19 |
| Época con GGA pero sin su ETC | RF-19 (se descarta) |
| ETC en estado 30 sin la inclinación, la dirección o algún coeficiente | Términos (fuera del estado 30): RF-18, RF-19 |
| Aviso del ETC o salida del estado 30 durante un promediado | RF-19 |
| Medida instantánea con el ETC en estado 30 y un aviso | RF-19 (espera a la primera época sin aviso) |
| Cambiar el modo o abrir el asistente durante una medida | RF-28 |
| GGA sin altitud o sin separación del geoide | RF-27 |
| Jalón inclinado más de 60° | RF-20 (no cumple la inclinación máxima por defecto) |
| "Jalón vertical" con la inclinación máxima activa | RF-20 (no se muestra ni se comprueba) |
| "Jalón vertical" con el ETC en estado 30 | RF-22 (la inclinación se guarda como dato; la punta es la del jalón vertical) |
| Inclinación de 0° | RF-13 (la punta es la del jalón vertical) |
| Sin GST con límites H/V activos | RF-21 (la condición no se cumple, como en S010) |
| Punto medido en "Compensada por el receptor" | RF-26 (la posición del receptor es la punta) |
| Corregir el jalón de un punto "Compensada por el receptor" | RF-25 (se mueve a lo largo del jalón con su inclinación) |
| Corregir el jalón de un punto "Jalón vertical" | S011/RF-14, sin cambios |
| Puntos medidos antes de esta versión, manuales y copias | RF-22: los medidos, "Jalón vertical" sin ETC; los manuales, sin inclinación; la ficha no la muestra y las columnas van vacías |
| Reproducción de una sesión | RF-1 (las reproducciones tienen su propio modo), RF-13 |
| Formato "Personalizado" guardado antes de esta versión (S012/RF-21) | RF-24 (las columnas de la inclinación salen sin marcar) |
| Copias de seguridad y paquetes (S013) | RF-22 (la inclinación viaja con el punto) |

## Fuera de alcance
- Orientación del mapa con el IMU y avisos de la inclinación en el replanteo → S018.
- Configurar el receptor (encender el IMU, compensar o no la posición, activar la frase ETC) → guía de S043.
- Leer o escribir la altura de antena configurada en el receptor → S044 (módulo experimental).
- Burbuja electrónica (aviso de jalón no vertical) en el modo "Jalón vertical" → no previsto.
- Detectar el modo sin la prueba, durante las medidas → no previsto.
- Calibrar el IMU desde la app → no previsto (viene calibrado de fábrica).
- Otros receptores con IMU, con otra distancia de la base al ARP → no previsto (solo el RS3).
- Inclinación en el modo automático y en el promediado largo → S017, que hereda estas reglas.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Tests de la punta del jalón frente a vectores de pyproj (Península y Canarias, inclinaciones de 0° a 60°, varias direcciones) y de las precisiones frente a un cálculo aparte.
- Reproducciones sintéticas del RS3 con ETC (configuración, alineación, compensando, avisos e inclinación variable) y con la prueba del asistente en los dos modos del receptor.
- Demo en el emulador con esas reproducciones:
  - el asistente en los dos modos;
  - la guía de la alineación;
  - la punta en el mapa;
  - un promediado con un aviso del ETC;
  - un punto con su inclinación en la ficha y en el CSV.
- En campo (del usuario, con el RS3):
  - el asistente en los dos modos;
  - punta calculada frente a jalón vertical sobre un mismo punto (parte de la puerta G2);
  - qué da la GGA en el modo que compensa fuera del estado 30;
  - si la GST ya incluye la inclinación en ese modo.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: cada receptor se reconoce por su dirección Bluetooth; todas las reproducciones comparten un modo propio, que se elige igual.]
- [SUPOSICIÓN: el FIX lo sigue exigiendo la condición "solo FIX" del control de precisión (S010/RF-13), activa por defecto; la inclinación no la fuerza, porque el estado 30 ya exige una solución buena (el aviso "GNSS malo" descarta la época).]
- [SUPOSICIÓN: la precisión de la inclinación por eje es el coeficiente del ETC por la altura de antena en metros, y sale en milímetros (ETC_rev_3). En planta es la raíz de la suma de los cuadrados de la de los ejes este y norte, y en vertical, la del eje vertical. La total suma la de la GST y la de la inclinación, como dice ETC_rev_3, sin componerlas cuadráticamente.]
- [SUPOSICIÓN: en un promediado, la precisión de la inclinación es la mayor de las épocas usadas.]
- [SUPOSICIÓN: en "Compensada por el receptor", la GST ya incluye la inclinación (firmware 32 o posterior), y la precisión es la de la GST sin sumarle nada. Se comprueba en campo.]
- [SUPOSICIÓN: con "Punta sin compensar" (RF-17), la posición que se muestra es la del jalón vertical en "Calculada por la app" y la GGA tal cual en "Compensada por el receptor"; no se sabe qué da el receptor que compensa fuera del estado 30, por eso no se mide (RF-18).]
- [SUPOSICIÓN: los umbrales del asistente (5° de referencia, 15° de prueba, medias de 1 s, 20 cm de movimiento esperado, 5 cm, 25 % y 60 s) bastan para separar los dos modos: con 1,934 m de altura de antena, a 15° la antena se mueve unos 50 cm.]
- [SUPOSICIÓN: en el CSV, la inclinación, su dirección, el rumbo y la mayor inclinación van en grados decimales con 3 decimales, como en el ETC, y las precisiones con 4 decimales, como las demás longitudes (S012/RF-11); los nombres de las columnas van en inglés, como el resto (S012).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S016: "Modo de inclinación por receptor (calculada por la app, compensada por el receptor o jalón vertical) con un asistente que lo detecta con una prueba de inclinación; estados y avisos de ETC con la guía de inicialización en ocho; punta del jalón en el mapa y en las coordenadas; bloqueo sin estado 30 y descarte de épocas con aviso; condición de inclinación máxima; precisión con los coeficientes de ETC; inclinación y rumbo guardados por punto (ficha y CSV)", y pasa a "en curso".
- `specs/005-estructura-interfaz/spec.md`, RF-9: el menú pasa a cinco entradas, con "Inclinación (S016/RF-1)" tras Coordenadas.
- `specs/010-recolectar-puntos/spec.md`, RF-13: se añade "y, con la inclinación, la de inclinación máxima (S016/RF-20)".
- `specs/011-objetos-y-puntos-manuales/spec.md`, RF-14: se añade "y, si se midió con inclinación, a lo largo del jalón (S016/RF-25)".
- `specs/012-exportar-csv/spec.md`, RF-6: en el grupo "de la medida" se añade "y su inclinación (S016/RF-24)".
- `docs/glosario.md`: se añaden "Modo de inclinación" (`TiltMode`: `APP`, `RECEIVER`, `VERTICAL`), "Modo comprobado" (`verified`), "Asistente del modo de inclinación" (`TiltModeWizard`), "Guía de la inclinación" (`TiltGuide`), "Punta del jalón" (`PoleTip`), "Precisión de la inclinación" (`tiltAccuracy`) e "Inclinación máxima" (`maxTilt`).
