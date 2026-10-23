# Spec 010 — Recolectar puntos

> Hito: H1 · Depende de: S009, S007 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Con S009 el proyecto ya tiene mapa y el rover se ve en él, pero todavía no se puede guardar ningún punto: la app solo mira. Esta spec trae la primera herramienta de levantamiento, el recolector. Con él, el topógrafo:
- mide un punto en un instante o promediando unos segundos;
- se asegura de que cumple la precisión que pide el trabajo o, si decide guardarlo igualmente, queda marcado;
- lo ve en el mapa nada más guardarlo.

El dato de campo es sagrado (constitución nº 1): cada punto se guarda al momento, de una vez, con la observación del receptor tal como llegó y con sus metadatos de calidad. Y no se recolecta fuera de los umbrales sin una decisión explícita del usuario (constitución nº 3).

## Usuarios / actores
- Topógrafo en campo con el RS3 en FIX, o con peor solución para trabajos de menos precisión.
- Receptor RS3, o una reproducción, como fuente de las épocas.

## Historias de usuario
- H1: Como topógrafo quiero abrir el recolector sin perder de vista el mapa y medir con el nombre siguiente ya propuesto.
- H2: Como topógrafo quiero medir un punto en un instante o promediando unos segundos, y que una pérdida momentánea del FIX no estropee la medida.
- H3: Como topógrafo quiero que la app compruebe la precisión antes de guardar y que me deje decidir cuando no se cumple.
- H4: Como topógrafo quiero que cada punto se guarde al momento con todo lo necesario para justificarlo después.
- H5: Como topógrafo quiero ver en el mapa los puntos que llevo medidos.

## Requisitos funcionales (criterios de aceptación en EARS)
### Recolector (H1)
- RF-1: CUANDO el usuario pulse "Recolectar" en la vista de proyecto, EL SISTEMA abrirá el recolector en una hoja inferior, con el mapa y el rover a la vista.
- RF-2: EL SISTEMA propondrá como nombre del punto el del último punto recolectado en el proyecto con su número final aumentado en uno, o "1" si no hay ninguno.
- RF-3: SI el nombre del punto ya existe en el proyecto, ENTONCES EL SISTEMA lo indicará y no permitirá medir hasta cambiarlo.
- RF-4: EL SISTEMA permitirá escribir una descripción del punto, que se conservará para el siguiente.
- RF-5: EL SISTEMA mostrará en el recolector la altura del jalón, la misma de la pantalla Coordenadas (S002/RF-29), y permitirá cambiarla.
- RF-6: MIENTRAS el recolector esté abierto, EL SISTEMA mostrará el estado de la solución y las precisiones horizontal y vertical de la GST.
- RF-7: MIENTRAS el recolector esté abierto, EL SISTEMA mantendrá la pantalla encendida (RNF-T2).

### Medida (H2)
- RF-8: CUANDO el usuario pulse "Medir" en modo instantáneo, EL SISTEMA medirá el punto con la primera época completa que llegue.
- RF-9: CUANDO el usuario pulse "Medir" en modo promediado, EL SISTEMA promediará las posiciones de las épocas hasta reunir el tiempo elegido (de 1 s a 60 min, 5 s por defecto; S017/RF-1), mostrando el progreso.
- RF-10: SI la solución deja de ser FIX durante un promediado con el control de solo FIX activo, ENTONCES EL SISTEMA descartará esas épocas, avisará "Solución perdida" con un sonido y seguirá hasta completar el tiempo con épocas en FIX.
- RF-11: CUANDO el usuario pulse "Cancelar" durante una medida, EL SISTEMA la descartará sin guardar nada.
- RF-12: MIENTRAS no haya posición del receptor o de una reproducción, o el punto del suelo quede fuera del ámbito del SC del proyecto, EL SISTEMA no permitirá medir e indicará el motivo.

### Control de precisión (H3)
- RF-13: EL SISTEMA ofrecerá un control de precisión con tres condiciones: solo FIX (activa por defecto), límite de precisión horizontal y límite de precisión vertical (desactivados por defecto), que se recuerdan para todos los proyectos, y, con la inclinación, la de inclinación máxima (S016/RF-20).
- RF-14: SI una medida no cumple el control de precisión, ENTONCES EL SISTEMA indicará qué condiciones incumple y pedirá confirmación antes de guardarla.
- RF-15: CUANDO el usuario confirme guardar una medida que no cumple el control de precisión, EL SISTEMA guardará el punto marcado como fuera de tolerancia, con las condiciones incumplidas.

### Guardado y metadatos (H4)
- RF-16: CUANDO termine una medida que cumple el control de precisión, EL SISTEMA guardará el punto en menos de 1 s, en una sola operación que sobrevive a un cierre de la app justo después (RNF-T6).
- RF-17: EL SISTEMA guardará con cada punto la posición de la antena tal como la da el receptor, las frases NMEA de las épocas usadas, la altura del jalón, el punto del suelo en geográficas y en el SC del proyecto, y la fecha y hora de la medida.
- RF-18: EL SISTEMA guardará con cada punto sus metadatos de calidad: estado de la solución, satélites, HDOP, edad de las correcciones, precisiones de la GST, modo de medida, épocas usadas y descartadas, y si viene de una reproducción.
- RF-19: CUANDO se guarde un punto, EL SISTEMA lo confirmará con un sonido y una vibración y propondrá el nombre siguiente.
- RF-22: EL SISTEMA guardará como precisiones de un punto las de la última GST recibida hasta el final de la medida, si tiene menos de 3 s (S002/RF-17), aunque no llegue en una de las épocas usadas.
- RF-23: EL SISTEMA guardará con las frases NMEA del punto la GST de la que salen sus precisiones.

### Puntos en el mapa (H5)
- RF-20: EL SISTEMA dibujará en el mapa los puntos del proyecto con su nombre, y los que están fuera de tolerancia con otro símbolo.
- RF-21: CUANDO se guarde un punto, EL SISTEMA lo mostrará en el mapa y en los datos del proyecto, que dirán cuántos puntos tiene (S006/RF-31).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el recolector abierto.
  - RNF-T6: cada punto en una sola transacción con durabilidad completa; identificador UUID; la medida bruta no se modifica nunca.
  - RNF-T7, RNF-T8 (56 dp, modo sol, sonido y vibración, TalkBack), RNF-T9 (unidades del proyecto), RNF-T10 (hora UTC guardada y local en pantalla).
  - RNF-T13: el progreso del promediado se refresca a 5 Hz como máximo.
- Constitución nº 2: la posición promediada coincide con la media de las épocas calculada aparte a menos de 0,1 mm.
- Al actualizar la app, los proyectos existentes conservan sus datos (migración de la base de datos probada).
- Un proyecto con 10 000 puntos se abre y se dibuja en el mapa en menos de 2 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Primer punto del proyecto | RF-2 ("1") |
| Último nombre sin número final ("arqueta") | RF-2 (se propone "arqueta1") |
| Nombre repetido | RF-3 |
| Pérdida de FIX durante el promediado | RF-10 |
| Pérdida de la conexión o "Sin datos" durante una medida | RF-11, RF-12 (la medida se cancela y se dice por qué; en un promediado se pausa, S017/RF-8) |
| Medida en FLOAT con el control de solo FIX activo | RF-14, RF-15 |
| Control de solo FIX desactivado | RF-10 (no se descartan épocas), RF-13 |
| Punto fuera del ámbito del SC del proyecto | RF-12 |
| Sin GST con límites H/V activos | RF-14 (la condición no se cumple: no hay precisión que comprobar) |
| GST a 1 Hz con épocas a 5 Hz (RS3) | RF-22 (la última GST, aunque llegue en una época anterior a la usada) |
| La última GST tiene más de 3 s | RF-22 (el punto va sin precisiones; con límites H/V activos, RF-14) |
| Reproducción en lugar de receptor | RF-18 (el punto queda marcado como de reproducción) |
| Cierre de la app justo después de guardar | RF-16 |

## Fuera de alcance
- Lista de puntos, ficha del punto, editar, duplicar y borrar → S011.
- Recalcular puntos al corregir la altura del jalón → S011.
- Exportar puntos → S012.
- Inclinación del jalón → S016.
- Modo automático por tiempo o distancia y promediado de hasta 60 min → S017. Fotos → S017b.
- Replanteo → S018.
- Códigos, atributos y líneas → S023 a S025.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la media de las épocas frente a una referencia calculada aparte (≤ 0,1 mm).
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Demo en el emulador con una reproducción: puntos instantáneos y promediados, una pérdida de FIX durante un promediado, un punto fuera de tolerancia confirmado y los puntos en el mapa.
- Demo de campo con el RS3: queda para el usuario.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: la altura del jalón es una sola para toda la app; cambiarla en el recolector la cambia también en Coordenadas, y cada punto guarda la que se usó.]
- [SUPOSICIÓN: al aumentar el número del nombre se conservan los ceros a la izquierda ("P009" → "P010").]
- [SUPOSICIÓN: la medida instantánea espera como mucho 2 s a la siguiente época; si no llega, se cancela y se dice que no hay datos del receptor.]
- [SUPOSICIÓN: el promediado llega a 60 s en esta spec; S017 lo amplía a 60 min con estadísticas en vivo (S017/RF-1 a RF-5).]
- [SUPOSICIÓN: el modo (instantáneo o promediado) y el tiempo de promediado se recuerdan para todos los proyectos, como el control de precisión.]
- [SUPOSICIÓN: la precisión vertical de la medida es la σ de altitud de la GST; la GST es la última recibida hasta el final de la medida (RF-22).]
- [SUPOSICIÓN: los puntos de una reproducción se pueden guardar, marcados como tales, para poder probar la app sin receptor.]
- [SUPOSICIÓN: el nombre del punto se dibuja en el mapa a escala 1:2.500 o más grande, para que no se amontonen; algo antes que la escala a la que el mapa sigue al rover (1:2.000), para que los nombres no parpadeen al seguirlo.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S010: "promediado de hasta 60 s" en lugar de "promediado", para distinguirlo de S017.
- `docs/glosario.md`: se añaden "Medida instantánea" (`InstantMeasurement`), "Fuera de tolerancia" (`OutOfTolerance`) y "Descripción (del punto)" (`description`).
