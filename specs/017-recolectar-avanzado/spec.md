# Spec 017 — Recolectar avanzado

> Hito: H2 · Depende de: S016 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
El recolector de S010 mide un punto cada vez que se pulsa "Medir", en un instante o promediando hasta 60 s. Para dos trabajos habituales eso se queda corto:
- **Puntos importantes** (un vértice, una base de replanteo, una referencia): hace falta promediar muchos minutos y saber, mientras tanto, si la posición ya es estable y cuánto se dispersa. Un corte de unos segundos en el enlace no puede tirar a la basura 40 minutos de medida.
- **Trazas** (un perfil, el borde de un camino, una nube de puntos del terreno): pulsar "Medir" en cada punto es lento y obliga a parar. El modo automático los mide solo, cada cierto tiempo o cada cierta distancia.

Esta spec amplía el promediado de S010 hasta 60 min con estadísticas en vivo y pausa ante los cortes, y añade el modo automático. Las fotos de los puntos, que la hoja de ruta incluía aquí, pasan a S017b.

Los datos de campo siguen siendo sagrados (constitución nº 1): un promediado largo guarda las frases de todas sus épocas, y un punto que no cumple el control de precisión no se guarda sin que el usuario lo decida; en el modo automático, donde no se puede preguntar punto a punto, se omite (constitución nº 3).

## Usuarios / actores
- Topógrafo en campo con el RS3 en FIX, parado en un punto (promediado largo) o caminando una traza (modo automático).
- Técnico en oficina que revisa cuánto se promedió un punto y con qué dispersión.
- Receptor RS3, o una reproducción, como fuente de las épocas.

## Historias de usuario
- H1: Como topógrafo quiero promediar un punto hasta 60 min viendo cuánto se dispersa y si la media ya es estable, y poder terminar cuando me valga.
- H2: Como topógrafo quiero que un corte de unos segundos en el enlace con el receptor no cancele un promediado largo.
- H3: Como topógrafo quiero que la app mida sola un punto cada cierto tiempo o cada cierta distancia mientras camino una traza.
- H4: Como técnico quiero que cada punto promediado guarde cuánto se promedió y con qué dispersión, para justificarlo después en la ficha y en el CSV.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Desviación típica horizontal**: la raíz de la suma de los cuadrados de las desviaciones típicas muestrales del Este y del Norte de las épocas usadas, en el SC del proyecto. **Vertical**: la de la altura.
- **Tiempo promediado**: la suma de los intervalos entre épocas usadas consecutivas, según la hora UTC del receptor (RNF-T10); los huecos por cortes o por épocas descartadas no suman.
- **Estabilidad de la media**: lo que se ha movido la posición media respecto a la que tenía 1 min de tiempo promediado antes; en planta, la distancia entre las dos medias y, en vertical, la diferencia de alturas en valor absoluto.
- **Corte**: el estado "Sin datos" (RNF-T3) o la pérdida de la conexión con el receptor.
- **Disparo** (modo automático): el momento en que toca medir un punto.

### Promediado largo (H1)
- RF-1: EL SISTEMA permitirá elegir el tiempo de promediado de 1 s a 60 min, escrito en minutos y segundos, 5 s por defecto, y lo recordará para todos los proyectos (amplía S010/RF-9).
- RF-2: MIENTRAS un promediado esté en marcha, EL SISTEMA mostrará el tiempo transcurrido y el que falta, y las épocas usadas y descartadas.
- RF-3: MIENTRAS un promediado esté en marcha, EL SISTEMA mostrará la desviación típica horizontal y la vertical de las épocas usadas, en la unidad lineal del proyecto con 3 decimales, y "Sin dato" con menos de 2 épocas usadas.
- RF-4: MIENTRAS un promediado esté en marcha, EL SISTEMA mostrará la estabilidad de la media, y "Sin dato" hasta que haya 1 min de tiempo promediado.
- RF-5: MIENTRAS un promediado esté en marcha, EL SISTEMA mostrará un gráfico de dispersión con las épocas usadas, en Este y Norte, alrededor de la posición media, con una escala automática que abarque todas las épocas (de ±1 cm como mínimo), dibujando como máximo 2.000 épocas repartidas por igual sin cambiar las estadísticas, que usan todas, y con una descripción de texto de la dispersión para TalkBack.
- RF-6: CUANDO el usuario pulse "Terminar" durante un promediado, también en pausa, EL SISTEMA lo cerrará con las épocas reunidas hasta ese momento y lo guardará como cualquier medida, pasando por el control de precisión (S010/RF-14).
- RF-7: SI el usuario pulsa "Terminar" sin ninguna época usada, ENTONCES EL SISTEMA no guardará nada e indicará que aún no hay épocas.

### Cortes (H2)
- RF-8: SI hay un corte durante un promediado, ENTONCES EL SISTEMA lo pausará, conservará las épocas ya reunidas y avisará "Medida en pausa" con un sonido y una vibración y lo mostrará mientras dure.
- RF-9: CUANDO vuelvan los datos del receptor con un promediado en pausa, EL SISTEMA lo reanudará solo, sin contar el tiempo del corte, y cerrará la pregunta de RF-10 si estaba abierta.
- RF-10: SI el corte dura más de 5 min, ENTONCES EL SISTEMA pedirá elegir entre guardar el punto con las épocas reunidas, pasando por el control de precisión, descartarlo o seguir esperando.
- RF-11: MIENTRAS haya un promediado o el modo automático en marcha, EL SISTEMA no los interrumpirá porque la app pase a segundo plano o se apague la pantalla, y al volver a la app mostrará su estado actual.
- RF-30: SI la base cambia durante un promediado (S015/RF-18), ENTONCES EL SISTEMA descartará las épocas ya tomadas y lo empezará de nuevo, y avisará con un sonido y una vibración cuánto tiempo promediado se ha descartado.

### Datos del punto promediado (H4)
- RF-12: EL SISTEMA guardará con cada punto promediado el tiempo realmente promediado y las desviaciones típicas horizontal y vertical de sus épocas usadas (vacías con menos de 2), y con ellos, como siempre (S010/RF-17), las frases NMEA de todas sus épocas usadas.
- RF-13: DONDE un punto tenga el tiempo promediado y las desviaciones típicas, EL SISTEMA los mostrará en su ficha (S011/RF-7), en la unidad lineal del proyecto.
- RF-14: EL SISTEMA escribirá en "Todas las columnas" de la exportación CSV (S012/RF-6), tras las columnas de la inclinación, el tiempo promediado, las desviaciones típicas horizontal y vertical y si el punto se midió en modo automático (sí o no).

### Modo automático (H3)
- RF-15: EL SISTEMA ofrecerá en el recolector la opción "Automático", que repite la medida del modo elegido (instantáneo o promediado) sin pulsar "Medir", con el disparo por tiempo o por distancia.
- RF-16: DONDE el disparo sea por tiempo, EL SISTEMA permitirá elegir el intervalo de 1 s a 60 min, 5 s por defecto, y lo recordará para todos los proyectos.
- RF-17: DONDE el disparo sea por distancia, EL SISTEMA permitirá elegir la distancia de 0,1 m a 1.000 m, 1 m por defecto, mostrada en la unidad lineal del proyecto con los límites convertidos, y la recordará para todos los proyectos.
- RF-18: SI el modo elegido es el promediado y su tiempo es mayor que el intervalo por tiempo, ENTONCES EL SISTEMA no permitirá iniciar el modo automático e indicará el motivo.
- RF-19: CUANDO el usuario pulse "Iniciar" en el modo automático, EL SISTEMA medirá un primer punto de inmediato.
- RF-20: DONDE el disparo sea por tiempo, EL SISTEMA medirá un punto cada intervalo, contado desde el último disparo, se guardara su punto o se omitiera.
- RF-21: DONDE el disparo sea por distancia, EL SISTEMA medirá un punto cuando el punto del suelo esté a la distancia elegida o más, en planta, del último punto guardado por el modo automático.
- RF-22: EL SISTEMA dará a cada punto del modo automático el nombre siguiente (S010/RF-2), con la descripción y la altura del jalón del recolector, y lo guardará como cualquier otro punto (S010/RF-16 a RF-18), con un sonido y una vibración (S010/RF-19).
- RF-23: SI el nombre siguiente ya existe en el proyecto, ENTONCES EL SISTEMA usará el siguiente nombre libre.
- RF-33: SI el nombre del campo ya existe en el proyecto al pulsar "Iniciar", ENTONCES EL SISTEMA no iniciará el modo automático e indicará el motivo (S010/RF-3).
- RF-24: SI el punto de un disparo no cumple el control de precisión, no tiene posición dentro del ámbito del SC del proyecto (S010/RF-12) o su medida no termina antes del siguiente disparo, ENTONCES EL SISTEMA lo omitirá sin guardarlo, avisará con un sonido y una vibración una sola vez hasta que vuelva a cumplir, y seguirá.
- RF-25: SI el punto de un disparo por distancia se omite, ENTONCES EL SISTEMA volverá a intentarlo con la siguiente época y lo contará como un solo punto omitido.
- RF-31: CUANDO el modo automático omita un punto, EL SISTEMA anotará en el log su hora y su causa, aunque el aviso suene una sola vez (S014/RF-5).
- RF-26: MIENTRAS el modo automático esté en marcha, EL SISTEMA mostrará los puntos guardados y omitidos, y el tiempo o la distancia que faltan para el siguiente.
- RF-27: CUANDO el usuario pulse "Parar" (con el modo automático en marcha no hay "Cancelar"), EL SISTEMA lo detendrá, descartará el punto en curso si lo hay (S010/RF-11), conservará los ya guardados y dirá cuántos se han guardado y cuántos se han omitido.
- RF-28: SI hay un corte con el modo automático en marcha, ENTONCES EL SISTEMA lo pausará, avisará y lo reanudará al volver los datos, sin medir los puntos que habrían tocado durante el corte, contando de nuevo el intervalo desde la reanudación (por tiempo) o midiendo un único punto si ya se cumple la distancia (por distancia).
- RF-32: MIENTRAS el modo automático esté en marcha, EL SISTEMA no permitirá cambiar la medida elegida ni el disparo, y sí el nombre, la descripción y la altura del jalón, que valdrán desde el siguiente punto.
- RF-29: MIENTRAS el modo de inclinación use la inclinación (S016/RF-1), EL SISTEMA aplicará al modo automático y al promediado largo las reglas de S016/RF-18, RF-19 y RF-21 y, si el usuario cambia el modo o abre el asistente con el modo automático en marcha, lo detendrá como "Parar" (S016/RF-28).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el recolector abierto.
  - RNF-T3: el corte se avisa con sonido o vibración en menos de 3 s.
  - RNF-T6: cada punto en una sola transacción con durabilidad completa; la medida bruta no se modifica nunca; al actualizar la app los puntos existentes se conservan, con las columnas nuevas vacías (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; TalkBack lee el tiempo, las épocas, las estadísticas y el paso en que está el modo automático, sin anunciar cada refresco: anuncia al cambiar de minuto, al pausar y al omitir un punto; el gráfico lleva descripción de texto; el aviso de pausa y el de punto omitido llevan sonido y vibración, nunca solo color.
  - RNF-T9: unidad lineal del proyecto en las estadísticas y la distancia del disparo.
  - RNF-T10: inicio y fin de cada medida en la hora UTC del receptor.
  - RNF-T13: las estadísticas, el gráfico y el progreso se refrescan a 5 Hz como máximo y calcularlas a 10 Hz no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: con 60 min a 10 Hz (36.000 épocas), la media, las desviaciones típicas y la estabilidad coinciden con las calculadas aparte a menos de 0,1 mm.
- Constitución nº 1: un promediado de 60 min a 10 Hz guarda las frases de todas sus épocas usadas, sin submuestrear.
- Guardar un punto de 60 min a 10 Hz tarda menos de 3 s en el móvil de referencia (RNF-T13), sin agotar la memoria.
- Pausar una medida tarda menos de 1 s desde que se declara el corte ("Sin datos", RNF-T3), y reanudarla, menos de 1 s desde la primera época nueva.
- La ficha de un punto de 60 min a 10 Hz se abre en menos de 1 s en el móvil de referencia, y "Ver frases NMEA" las muestra por tramos.

## Casos límite
| Caso | RF |
|---|---|
| Promediado de 60 min con el FIX perdido un rato | S010/RF-10 (se descartan esas épocas y sigue), RF-6 (se puede terminar con lo reunido) |
| Corte de unos segundos durante un promediado | RF-8, RF-9 |
| Corte de más de 5 min durante un promediado | RF-10 (también "Seguir esperando"; si vuelven los datos, RF-9) |
| Corte durante una medida instantánea | S010/RF-11 y RF-12 (se cancela, como hasta ahora) |
| "Terminar" con épocas reunidas pero fuera de tolerancia | RF-6 (control de precisión: pide confirmar y, si se confirma, queda marcado, S010/RF-15) |
| "Terminar" o "Cancelar" con el promediado en pausa | RF-6, S010/RF-11 |
| "Terminar" sin ninguna época | RF-7 |
| Cancelar un promediado largo | S010/RF-11 (no se guarda nada) |
| Pasar la app a segundo plano durante una medida | RF-11 |
| Cierre de la app o del móvil durante un promediado | Fuera de alcance (la medida se pierde; ver "Dudas abiertas") |
| Cambio de base durante un promediado largo | RF-30 (S015/RF-18) |
| Primer punto del modo automático | RF-19 |
| Nombre del campo ya existente al iniciar | RF-33 |
| Cambiar la altura del jalón con el modo automático en marcha | RF-32 |
| Disparo por tiempo con promediado más largo que el intervalo | RF-18 |
| Punto automático con el FIX perdido o sin precisión suficiente | RF-24, RF-31 |
| Posición fuera del ámbito del SC durante el modo automático | RF-24 (al iniciar, S010/RF-12) |
| Parado en el mismo sitio con el disparo por tiempo | RF-20 (se guardan puntos en la misma posición, sin filtrar) |
| Reproducción a ×5 con el modo automático | Términos (tiempo según la hora UTC de las épocas), RF-20 |
| Cambiar el modo de inclinación o abrir el asistente con el modo automático en marcha | RF-29 (S016/RF-28) |
| Restaurar una copia con el modo automático en marcha, o en pausa | S013/RF-16 (cuenta como medida en curso) |
| Abrir la ficha de un punto de 60 min | RNF (menos de 1 s, frases por tramos) |
| Disparo por distancia que no cumple la precisión durante un trecho | RF-25 (un solo omitido) |
| Medida automática que no acaba antes del siguiente disparo | RF-24 |
| Nombre siguiente ya ocupado | RF-23 |
| Corte con el modo automático en marcha | RF-28 |
| Modo automático con el jalón inclinado y el ETC con aviso | RF-29 (S016/RF-19) |
| Modo automático con inclinación y sin estado 30 al iniciar | RF-29 (S016/RF-18) |
| Parar con un punto promediado a medias | RF-27 |
| Punto sin GST con límites H/V activos | S010/RF-14 (la condición no se cumple), RF-24 en automático |
| Punto de una reproducción | S010/RF-18; RF-12 y RF-14 igual |
| Puntos anteriores a esta versión | RF-13, RF-14 (sin datos: no se muestran y las columnas van vacías) |

## Fuera de alcance
- Fotos de los puntos → S017b.
- Recuperar una medida interrumpida por el cierre de la app → no previsto.
- Pausar o reanudar a mano un promediado o el modo automático → no previsto.
- Líneas y polígonos con los puntos del modo automático → S025.
- Códigos y atributos en los puntos del modo automático → S024.
- Replanteo → S018.
- Histograma o estadísticas de un punto ya guardado → no previsto.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la media, la desviación típica y la estabilidad de 36.000 épocas frente a un cálculo aparte (≤ 0,1 mm).
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Reproducciones sintéticas con un corte corto y uno largo, con una traza para el modo automático por tiempo y por distancia, y con una pérdida de FIX.
- Demo en el emulador con esas reproducciones:
  - un promediado largo con sus estadísticas y el gráfico;
  - un corte de unos segundos con pausa y reanudación;
  - "Terminar" con un punto en la ficha y en el CSV;
  - el modo automático por tiempo y por distancia con un punto omitido.
- Pruebas de campo con el RS3 (del usuario):
  - un promediado de 60 min sobre un vértice, frente a su media calculada aparte;
  - una traza en modo automático.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: el modo automático no es un modo más, sino una opción sobre el modo instantáneo o el promediado ya elegido, para no duplicar el tiempo de promediado.]
- [SUPOSICIÓN: la distancia del disparo es la de cuadrícula, en planta, en el SC del proyecto y sin factor combinado; con la escala de España el error es de unos 0,4 m por km, irrelevante con las distancias de una traza.]
- [SUPOSICIÓN: si la app o el móvil se cierran durante un promediado, la medida se pierde y no se guarda nada; no se recupera. Un promediado de 60 min perdido por el cierre de la app es un coste alto, pero guardar medidas a medias necesita una spec propia.]
- [SUPOSICIÓN: las desviaciones típicas son muestrales (n − 1) y en el SC del proyecto, no en el local del receptor.]
- [SUPOSICIÓN: la ventana de la estabilidad de la media es fija, de 1 min; no se elige.]
- [SUPOSICIÓN: el corte máximo antes de preguntar es de 5 min, fijo.]
- [SUPOSICIÓN: cada punto del modo automático se confirma con sonido y vibración como cualquier punto (S010/RF-19), aunque el intervalo sea de 1 s; el usuario puede silenciar el móvil.]
- [SUPOSICIÓN: un promediado de 60 min a 10 Hz pesa de 15 a 30 MB por punto; las copias de seguridad y los paquetes crecen en proporción (S013), sin tope.]
- [SUPOSICIÓN: el intervalo, la distancia y el tiempo de promediado se recuerdan para todos los proyectos, como el control de precisión.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`:
  - fila S017: "Modo automático por tiempo o distancia con omisión de los puntos que no cumplen el control de precisión; promediado de hasta 60 min con estadísticas en vivo (desviación típica, estabilidad de la media y gráfico de dispersión), pausa ante los cortes y datos guardados en la ficha y el CSV", y pasa a "spec";
  - fila nueva S017b "fotos-de-puntos": "Fotos de los puntos, varias por punto: desde el recolector, durante la medida o justo después de guardar, y desde la ficha (ver, añadir y borrar con rastro en el historial); viajan con el punto en la papelera, las copias de seguridad y los paquetes", depende de S017, "pendiente";
  - S018 y S019 siguen dependiendo de S016; S031 pasa a depender de S025 y S017b.
- `specs/010-recolectar-puntos/spec.md`:
  - RF-9: "de 1 s a 60 min (S017/RF-1)" en lugar de "de 1 a 60 s";
  - suposición del promediado: "S017 lo amplía a 60 min con estadísticas en vivo";
  - caso límite de la pérdida de conexión: promediado → pausa (S017/RF-8), instantánea sin cambios;
  - "Fuera de alcance": "Modo automático y promediado de hasta 60 min → S017. Fotos → S017b".
- `specs/010-recolectar-puntos/spec.md`, RF-16: "en menos de 1 s, y en menos de 3 s con un promediado de más de 60 s (S017)".
- `specs/013-copia-seguridad-local/spec.md`, RF-16: se añade que el modo automático en marcha, también en pausa, cuenta como una medida en curso (S017).
- `specs/011-objetos-y-puntos-manuales/spec.md`, RF-7: se añade "y, si se promedió, el tiempo promediado y las desviaciones típicas (S017/RF-13)".
- `specs/012-exportar-csv/spec.md`, RF-6: en el grupo "de la medida" se añade "el tiempo promediado, las desviaciones típicas y si se midió en automático (S017/RF-14)".
- `docs/glosario.md`: se añaden "Modo automático" (`AutoMode`), "Disparo" (`trigger`), "Medida en pausa" (`MeasurementPaused`), "Desviación típica (de una medida)" (`standardDeviation`) y "Estabilidad de la media" (`meanStability`).
