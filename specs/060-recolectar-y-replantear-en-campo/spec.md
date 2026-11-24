# Spec 060 — Recolectar y replantear en campo

> Hito: H9 · Depende de: S010, S017, S016, S046, S047, S018, S019, S020, S024, S025, S027, S039, S041, S051, S054 · Estado: implementada (24/11/2026)

## Contexto y objetivo
Medir un punto y replantearlo son lo que el topógrafo hace todo el día, de pie, con el móvil en una mano, al sol y a menudo con guantes. Hoy las dos pantallas se ven mal:
- El panel del recolector y el del replanteo ocupan unos tres cuartos de la pantalla y tapan el mapa: no se ve dónde está el jalón respecto a los puntos, y en el replanteo el mapa se queda a una escala inútil.
- Lo que más se cambia al medir (altura del jalón, tiempo de medición, modo, inclinación) no está a la vista; en el replanteo las tolerancias y la cota de diseño están detrás de enlaces de texto de poco contraste.
- El botón «Medir» aparece gris sin decir por qué, y los avisos (solo FIX, inclinación, base shift, localización, cambio de base) salen como líneas rojas largas apiladas, también cuando no impiden nada.
- La fila del código queda cortada bajo la descripción.
- En el replanteo solo hay números (distancia, Norte, Este) y una barra vertical: no hay una guía de hacia dónde caminar.

Esta spec rehace la presentación de **recolectar** y de **replantear** (punto, línea, eje y talud) con los mismos componentes: el mapa queda libre y a la vista; una **hoja inferior** de dos alturas lleva, plegada, el estado en vivo, una **fila de ajustes** con pastillas grandes y el botón Medir; y el replanteo añade una **placa de guiado** con flecha, distancia y desplazamientos y un mapa que te sigue. Cada aviso dice una sola cosa y, si bloquea, ofrece su solución con un toque. No añade funciones de medida ni de cálculo: reubica y hace visible lo que ya existe.

Lo que la guía consultada pide para el trabajo en exterior (objetivos táctiles grandes, una tarea reducida a lo mínimo, hojas no modales con un estado plegado que dejan ver el mapa, valores que se cambian sin salir de la pantalla) y lo que hacen otras apps de campo (mapa centrado y orientado por la marcha con zoom automático al acercarse, indicador de llegada que cambia de color) se adapta con diseño propio (RNF-T14).

## Usuarios / actores
- Topógrafo en campo que mide y replantea con el RS3 (o con otra fuente de posición).

## Historias de usuario
- H1: Como topógrafo quiero ver el mapa mientras mido, con el estado de la solución y los ajustes de la medición siempre a la vista y a un toque.
- H2: Como topógrafo quiero cambiar el jalón o el tiempo de medición sin salir de la pantalla ni abrir ajustes.
- H3: Como topógrafo quiero saber al instante por qué no puedo medir y arreglarlo con un toque.
- H4: Como topógrafo quiero replantear con un mapa que me sigue, una flecha y la distancia, y saber sin dudar cuándo he llegado.
- H5: Como topógrafo quiero que recolectar y replantear (punto, línea, eje y talud) se vean y se manejen igual.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Hoja de campo**: la hoja inferior no modal que lleva los controles de recolectar o de replantear sobre el mapa.
- **Plegada / expandida**: las dos alturas de la hoja de campo.
- **Línea de estado**: la solución con su color y su nombre, la precisión horizontal y vertical y los satélites usados.
- **Fila de ajustes**: la fila de pastillas de lo que se cambia al medir o al replantear.
- **Pastilla**: un control de 56 dp con el nombre del ajuste y su valor actual en texto; al tocarla se abre su selector.
- **Placa de guiado**: la zona de la hoja de replanteo con la flecha, la distancia, los desplazamientos y la diferencia de cota.
- **Motivo de bloqueo**: la línea que dice por qué «Medir» no se puede pulsar y cómo arreglarlo.

### La hoja de campo (H1, H5)
- RF-1: EL SISTEMA mostrará el recolector y cada replanteo como una hoja de campo no modal sobre el mapa, con dos alturas: plegada y expandida.
- RF-2: MIENTRAS la hoja de campo esté plegada, EL SISTEMA dejará libre y a la vista al menos el 60 % del alto de la pantalla en dispositivos de 700 dp de alto o más, y el 55 % en los de menos, incluso con los avisos del caso más cargado, y el usuario podrá mover y acercar el mapa.
- RF-3: EL SISTEMA abrirá la hoja de campo plegada.
- RF-4: CUANDO el usuario arrastre el asa de la hoja o la toque, EL SISTEMA alternará entre plegada y expandida; con TalkBack, ofrecerá las acciones «Expandir» y «Plegar».
- RF-5: EL SISTEMA conservará la altura de la hoja al girar el dispositivo y al restaurar la app tras cerrar su proceso (como S001/RF-7 y S001/RF-8).
- RF-6: CUANDO el usuario pulse «Cerrar» o «Atrás», EL SISTEMA cerrará la hoja de campo.
- RF-7: CUANDO el teclado se abra para un campo de la hoja, EL SISTEMA dejará ese campo visible por encima del teclado.

### Recolectar: hoja plegada (H1, H2)
- RF-8: MIENTRAS el recolector esté plegado, EL SISTEMA mostrará en la hoja, de arriba abajo: la línea de estado, la fila de ajustes, las pastillas informativas (RF-14), el motivo de bloqueo si lo hay (RF-16) y el botón «Medir» con el nombre del punto que se medirá.
- RF-9: EL SISTEMA mostrará en la fila de ajustes del recolector cuatro pastillas: «Jalón» (con la altura del jalón, S046), «Tiempo» (con el valor del modo de medición), «Modo» (instantáneo, promediado, automático por tiempo o automático por distancia, S010 y S017) e «Inclinación» (el modo de inclinación, S016).
- RF-10: EL SISTEMA mostrará en la pastilla «Tiempo» el tiempo de promediado en el modo promediado y el intervalo o la distancia en los automáticos, y «Instantáneo» sin selector en el modo instantáneo.
- RF-11: CUANDO el usuario toque «Jalón» o «Tiempo», EL SISTEMA abrirá un teclado numérico con el valor actual y los rangos y el formato de S046, S010 y S017, y aplicará el valor al confirmarlo.
- RF-12: SI el valor escrito no vale, ENTONCES EL SISTEMA dirá el motivo junto al campo y no lo aplicará.
- RF-13: CUANDO el usuario toque «Modo», EL SISTEMA abrirá una lista corta con las opciones y marcará la vigente; CUANDO toque «Inclinación», abrirá la pantalla de inclinación del receptor (S016), donde se elige el modo con su asistente.
- RF-14: EL SISTEMA mostrará como pastillas informativas de texto neutro lo que condiciona la medición: los tipos de solución admitidos y el control de precisión (S047), el base shift activo (S020) y la localización activa (S039); al tocar una, abrirá la pantalla donde se cambia.
- RF-15: MIENTRAS haya una medición en curso, EL SISTEMA mantendrá en las pastillas los valores con que empezó, las desactivará e indicará el progreso (tiempo y épocas) con los botones «Terminar» y «Cancelar» (S017).

### Recolectar: motivos de bloqueo (H3)
- RF-16: SI «Medir» no se puede pulsar, ENTONCES EL SISTEMA mostrará bajo el botón una sola línea con el motivo de mayor prioridad y, junto a ella, la acción que lo resuelve con un toque.
- RF-17: EL SISTEMA dirá, cada uno con su acción, los motivos de S010, S016, S017, S020, S024, S025 y S046: sin receptor o sin posición, modo de inclinación sin elegir, inclinación no lista, jalón no válido, atributos obligatorios del código, posición fuera del ámbito del SC y equipo no válido.
- RF-17b: SI la solución no es admitida o la precisión supera el límite (S047, S010), ENTONCES EL SISTEMA lo dirá en la línea de estado, en texto neutro y sin color de error, sin bloquear «Medir».
- RF-18: EL SISTEMA usará el color de error solo para el motivo de bloqueo y nunca para una pastilla informativa.
- RF-19: EL SISTEMA no mostrará avisos permanentes de configuración que no impidan medir (por ejemplo, «No solo FIX» estando en FIX).

### Recolectar: hoja expandida (H1, H5)
- RF-20: MIENTRAS el recolector esté expandido, EL SISTEMA mostrará además el nombre y la descripción del punto, el selector de código con sus atributos (S024), la foto y la galería (S017b) y lo propio de las líneas y polígonos (S025), sin que ninguna fila quede cortada ni tapada.
- RF-21: SI no hay un punto al que añadir fotos, ENTONCES EL SISTEMA no mostrará los botones de foto y galería desactivados, sino un texto que lo diga.
- RF-22: EL SISTEMA mostrará las guías y los avisos de la inclinación (S016/RF-12), del cambio de base (S020/RF-15) y de la medición automática (S017) en la hoja con su texto actual, sin apilar más de una línea de aviso a la vez además del motivo de bloqueo.

### Replantear (H4, H5)
- RF-23: CUANDO el usuario replantee un punto, una línea, un eje o un talud, EL SISTEMA mostrará la hoja de campo con la placa de guiado en la altura plegada y la misma fila de ajustes y el mismo botón «Medir» que el recolector.
- RF-24: EL SISTEMA mostrará la placa de guiado con la flecha hacia el destino, la distancia, los desplazamientos (Norte y Este con el modo «Por distancia», y adelante, atrás, izquierda y derecha con el modo «Por dirección», S018) y, con cota de diseño, la diferencia de cota con «Desmonte» o «Terraplén» (S019).
- RF-25: MIENTRAS se replantee, EL SISTEMA centrará el mapa en la posición del jalón, lo orientará hacia la dirección de marcha (con el norte arriba cuando no haya rumbo, S018) y acercará el zoom para que el destino y el jalón quepan, hasta un mínimo de escala.
- RF-26: CUANDO el usuario mueva o acerque el mapa a mano, EL SISTEMA dejará de seguir al jalón y mostrará un botón «Recentrar».
- RF-27: EL SISTEMA mostrará en la fila de ajustes del replanteo las pastillas «Tolerancia H», «Tolerancia V» (solo con cota de diseño), «Cota» (la de diseño) y «Modo» (por dirección o por distancia), con los valores y los rangos de S018 y S019, y abrirá un teclado numérico o una lista corta al tocarlas.
- RF-28: MIENTRAS el jalón esté dentro de las tolerancias activas, EL SISTEMA pondrá la placa de guiado en verde con el texto «En el punto».
- RF-29: EL SISTEMA mostrará en la placa de una línea, un eje o un talud la información propia de cada una (objetivo, PK, desplazamiento, error lateral, talud) con el diseño de la placa de un punto (S027, S041, S054, S051).
- RF-30: EL SISTEMA mostrará los botones «Anterior» y «Siguiente» de la navegación entre puntos (S018) con zonas táctiles de 56 dp.
- RF-31: EL SISTEMA mantendrá sin cambios los avisos sonoros y la vibración del replanteo (S018, S019).

### Lo que no cambia (H5)
- RF-32: EL SISTEMA mantendrá el comportamiento, los valores por defecto, los rangos y los textos de cada acción de S010, S016, S017, S018, S019, S020, S024, S025, S027, S039, S041, S046, S047, S051 y S054: ninguna función se pierde ni cambia de resultado.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T7: los textos de las pastillas, los motivos y la placa en recursos de español y de inglés.
  - RNF-T8: zonas táctiles de 56 dp en pastillas y botones; estado de la solución, llegada y bloqueo en texto además de color; contraste ≥ 7:1 en modo sol; etiquetas de TalkBack en todos los controles.
  - RNF-T13: la línea de estado y la placa se refrescan a 5 Hz como máximo sin bloquear el hilo principal más de 16 ms; seguir al jalón con el mapa no baja de esa cadencia.
  - RNF-T15 (de S059, cuando exista): se aplica por analogía a las listas cortas de las pastillas.
- Específicos:
  - Con la hoja plegada y un ancho de 360 dp, la línea de estado, las cuatro pastillas y «Medir» caben en pantalla sin desplazar.
  - Con el tamaño de letra al 130 %, la fila de ajustes pasa a dos filas sin cortar ningún texto.

## Casos límite
| Caso | RF |
|---|---|
| Sin receptor o sin posición | RF-16, RF-17 |
| El receptor se pierde durante una medición | RF-15, S010/RF-9 |
| GNSS del móvil como fuente | RF-14, RF-17 |
| Modo de inclinación sin elegir o inclinación no lista | RF-9, RF-17 |
| Valor del jalón o del tiempo fuera de rango | RF-11, RF-12 |
| Giro del dispositivo con la hoja expandida | RF-5 |
| Teclado abierto sobre un campo de la hoja | RF-7 |
| Sin rumbo (parado) al replantear | RF-25 |
| Destino fuera de la vista | RF-25 |
| Mapa movido a mano | RF-26 |
| Replanteo sin cota de diseño | RF-24, RF-27 |
| Punto sin fotos posibles | RF-21 |
| Medición en curso con el panel abierto | RF-15 |

## Fuera de alcance
- El análisis de ejes en vivo (S054) → pendiente de una spec posterior que reutilice estos componentes.
- Selectores de ruleta o botones de más y menos → descartados en la entrevista (solo teclado numérico).
- Cambiar cómo se mide, promedia, calcula o compara: solo cambia la presentación.
- Menús del proyecto, lista de Objetos y «Datos del proyecto» → S059.
- Replanteo en realidad aumentada, brújula del móvil como rumbo o cualquier sensor nuevo → no previsto.
- Cambiar los sonidos o la vibración → no previsto.
- Diseños para tableta o apaisado → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests de Compose con anchos de 360 dp y 411 dp y letra al 100 % y al 130 %: cada pastilla, motivo y botón de los RF existe, es alcanzable y mide 56 dp; la altura plegada deja el 60 % de la pantalla (55 % bajo 700 dp), medida a 411×891 y a 360×640 dp.
- Cada motivo de RF-17 tiene su test con su acción.
- Demo en el emulador con el receptor simulado (`tools/simulador/servidor_tcp.py`) y capturas de antes y después: recolectar plegado y expandido, cambiar el jalón y el tiempo, un motivo de bloqueo, replantear un punto cerca y lejos, y las tolerancias.
- Demo de campo con el RS3 real: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el orden de prioridad de los motivos de bloqueo (RF-16) es: sin receptor, sin posición, inclinación (sin elegir o no lista), jalón no válido, atributos obligatorios, posición fuera del SC.]
- [SUPOSICIÓN: el zoom automático del replanteo no baja de la escala en que caben unos 3 m; el plan fija el valor y el ritmo.]
- [SUPOSICIÓN: la pastilla «Cota» del replanteo abre el diálogo actual de la cota de diseño (con la superficie y el desplazamiento vertical de S042), que se mantiene tal como está.]
- [SUPOSICIÓN: «En el punto» exige estar dentro de la tolerancia H y, si hay cota de diseño, también de la V; por defecto H es la de S018 (0,025 m).]

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: fila de esta spec en H9.
- S010, S017, S018, S019: su presentación pasa a la hoja de campo; su comportamiento no cambia.
- S016, S020, S047: sus avisos pasan a pastillas y a motivos de bloqueo con texto nuevo (los mismos significados).
