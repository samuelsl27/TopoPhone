# Spec 018 — Replanteo de puntos

> Hito: H2 · Depende de: S016 · Estado: borrador (pendiente de aprobación)

## Contexto y objetivo
Medir un punto dice dónde está algo; replantearlo es lo contrario: se sabe dónde tiene que estar un punto (un vértice de una parcela, una esquina de un edificio, un eje) y hay que llevar el jalón hasta allí y marcarlo. Es el trabajo de campo más habitual después de levantar, y sin él la app solo sirve para medir.

Esta spec añade el replanteo de puntos: se elige un punto del proyecto, la app guía hasta él con una flecha o con las diferencias de Norte y Este, una diana métrica ayuda en los últimos 40 cm, avisa al llegar dentro de la tolerancia y, al medir, guarda un punto nuevo con su desviación respecto al diseño. La altura se ve como dato; el desmonte y el terraplén con tolerancia y el informe llegan en S019.

Los datos de campo siguen siendo sagrados (constitución nº 1) y la calidad va antes que la rapidez (nº 3): con el control de precisión del recolector, sin FIX la guía se apaga en vez de enseñar una diana verde que no vale, y cada replanteo medido queda guardado sin sustituir a ningún otro.

## Usuarios / actores
- Topógrafo en campo con el jalón y el RS3 en FIX (o una reproducción para probarlo), que busca un punto de diseño y lo marca.
- Técnico en oficina que después revisa cuánto se desvió cada punto replanteado respecto al diseño.
- Receptor RS3, con su IMU si hay rumbo del IMU, o una reproducción como fuente de las épocas.

## Historias de usuario
- H1: Como topógrafo quiero elegir un punto del proyecto y que la app me guíe hasta él, con una flecha o con las diferencias de Norte y Este.
- H2: Como topógrafo quiero una diana métrica y un aviso al entrar en la tolerancia, para clavar la punta sin mirar la pantalla todo el rato.
- H3: Como técnico quiero que el punto replanteado se guarde como un punto más, con la diferencia respecto al diseño, para justificar el trabajo después.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Punto de diseño**: el punto del proyecto que se replantea.
- **Punta**: la punta del jalón del usuario, la misma que muestra el panel de posición y que mide el recolector (S016/RF-16, S002/RF-32).
- **Distancia horizontal**: la distancia en planta, en el SC del proyecto, entre la punta y el punto de diseño. Con un SC geográfico se mide en el plano local de la punta.
- **Diferencias de Norte y Este**: lo que hay que moverse para llegar al punto de diseño, en el SC del proyecto, con signo: positivo hacia el Norte o el Este. Con un SC geográfico, en el plano local.
- **Rumbo del usuario**: hacia dónde mira o camina el usuario, respecto al norte del SC del proyecto.
- **Diferencia de cota**: la altura de la punta menos la del punto de diseño, con signo.
- **Tolerancia**: la distancia horizontal máxima a la que el punto se da por llegado.
- **Zona de ayuda**: la que queda a 40 cm o menos del punto de diseño.

### Elegir el punto de diseño (H1)
- RF-1: CUANDO el usuario pulse "Replantear" en la ficha de un punto, en el menú de un punto de la lista de objetos o en la ficha que se abre al tocar un punto del mapa, EL SISTEMA abrirá el replanteo con ese punto de diseño.
- RF-2: EL SISTEMA permitirá replantear cualquier punto activo del proyecto, medido o manual.
- RF-3: CUANDO el usuario pulse "Siguiente" o "Anterior", EL SISTEMA pasará al punto siguiente o anterior de la lista de objetos con su búsqueda aplicada, ordenados por nombre en orden natural (1, 2, 10), y no pasará de los extremos.
- RF-4: MIENTRAS el replanteo esté abierto, EL SISTEMA mostrará el nombre, la descripción y las coordenadas del punto de diseño, y su altura si la tiene, en el SC del proyecto.

### La guía (H1)
- RF-5: EL SISTEMA ofrecerá dos modos de guía, "Por dirección" y "Por distancia", y recordará el último elegido para todos los proyectos.
- RF-6: DONDE el modo sea "Por distancia", EL SISTEMA mostrará la distancia horizontal y las diferencias de Norte y Este, con su signo, en las unidades lineales del proyecto.
- RF-7: DONDE el modo sea "Por dirección", EL SISTEMA mostrará una flecha hacia el punto de diseño respecto al rumbo del usuario, la distancia horizontal y su descomposición en adelante o atrás y derecha o izquierda.
- RF-8: EL SISTEMA permitirá elegir el origen del rumbo del usuario, "Rumbo de marcha" o "IMU del receptor", y recordará la elección para todos los proyectos.
- RF-9: DONDE el rumbo sea el de marcha, EL SISTEMA lo tomará del avance de la punta mientras el usuario vaya a 0,3 m/s o más, lo conservará 10 s al pararse y después dirá que no está disponible.
- RF-10: DONDE el rumbo sea el del IMU, EL SISTEMA lo tomará del rumbo del ETC de la época, mientras esté en estado 30 (S016/RF-16).
- RF-11: SI el rumbo elegido no está disponible, ENTONCES EL SISTEMA no dibujará la flecha, indicará el motivo y seguirá mostrando la distancia horizontal y las diferencias de Norte y Este.
- RF-12: SI el punto de diseño tiene altura, ENTONCES EL SISTEMA mostrará la diferencia de cota como un dato, sin tolerancia ni colores, y sin mostrarla si la punta no tiene altura.
- RF-13: MIENTRAS el replanteo esté abierto, EL SISTEMA mostrará el estado de la solución, la precisión horizontal y, si la inclinación está en uso, si la punta está compensada (S010/RF-6, S016/RF-17).

### La diana y el mapa (H1, H2)
- RF-14: MIENTRAS la distancia horizontal sea de más de 40 cm, EL SISTEMA mostrará en el mapa la punta, el punto de diseño y la línea entre ellos, con el norte arriba (S009/RF-11).
- RF-15: MIENTRAS el punto esté en la zona de ayuda, EL SISTEMA mostrará una diana métrica con el punto de diseño en el centro, la punta y el círculo de la tolerancia, a escala.
- RF-16: EL SISTEMA ajustará solo la escala de la diana, en pasos de 1, 2 y 5, para que la punta y el círculo de la tolerancia estén siempre a la vista.
- RF-17: DONDE el modo sea "Por dirección" y el rumbo esté disponible, EL SISTEMA orientará la diana con el rumbo del usuario hacia arriba, y en los demás casos con el norte arriba.

### Tolerancia y avisos (H2)
- RF-18: EL SISTEMA permitirá cambiar la tolerancia, de 1 cm a 1 m, con 2,5 cm por defecto, en las unidades lineales del proyecto, y la recordará para todos los proyectos.
- RF-19: MIENTRAS la distancia horizontal sea igual o menor que la tolerancia, EL SISTEMA mostrará la diana en verde y el texto "Dentro de la tolerancia" (RNF-T8).
- RF-20: CUANDO la distancia horizontal pase a ser igual o menor que la tolerancia, EL SISTEMA avisará con un sonido y una vibración, y CUANDO supere la tolerancia en más de 5 mm después de haber estado dentro, con otro sonido y otra vibración distintos.
- RF-21: CUANDO el punto entre en la zona de ayuda viniendo de fuera, EL SISTEMA avisará con un sonido y una vibración.
- RF-22: SI el control de precisión de S010/RF-13 tiene "Solo FIX" activo y la solución no es FIX, ENTONCES EL SISTEMA apagará la guía, indicará el estado de la solución, avisará con un sonido y una vibración (S010/RF-10) y la volverá a mostrar al recuperar el FIX.
- RF-23: DONDE el control de precisión no tenga "Solo FIX" activo, EL SISTEMA mantendrá la guía con cualquier solución y mostrará su estado de forma destacada.
- RF-24: MIENTRAS el modo use la inclinación y la época no esté en estado 30 (S016/RF-18), EL SISTEMA apagará la guía e indicará el motivo con la guía de S016/RF-12.
- RF-25: SI hay un corte ("Sin datos") o se pierde la conexión con el receptor (RNF-T3), ENTONCES EL SISTEMA apagará la guía, avisará con un sonido y una vibración y la volverá a mostrar al volver los datos.

### Medir el punto replanteado (H3)
- RF-26: CUANDO el usuario pulse "Medir" en el replanteo, EL SISTEMA medirá el punto con el modo de medida del recolector (instantáneo o promediado), la altura del jalón, el control de precisión y las reglas de corte, descarte y confirmación de S010/RF-8 a RF-15 y de S017/RF-6 a RF-10.
- RF-27: CUANDO termine la medida, EL SISTEMA guardará un punto de origen replanteado llamado "<nombre del punto de diseño>_stkd" o, si ya existe, "_stkd2", "_stkd3" y siguientes, con la descripción del punto de diseño, como cualquier punto medido (S010/RF-16 a RF-18).
- RF-28: EL SISTEMA guardará con el punto replanteado el nombre y el identificador del punto de diseño, sus coordenadas de diseño tal como eran al medir y las diferencias de Norte, Este, distancia horizontal y cota entre lo medido y el diseño (medido menos diseño), sin redondear.
- RF-29: CUANDO se guarde el punto replanteado, EL SISTEMA lo confirmará con un sonido y una vibración (S010/RF-19), mostrará sus diferencias respecto al diseño y se quedará en el mismo punto de diseño.
- RF-30: MIENTRAS haya una medida en marcha en el replanteo, EL SISTEMA no permitirá cambiar de punto de diseño, de modo de guía ni de tolerancia, y mostrará el progreso con "Cancelar" (S010/RF-11).
- RF-31: SI el punto del suelo de la medida queda fuera del ámbito del SC del proyecto, ENTONCES EL SISTEMA no permitirá medir e indicará el motivo (S010/RF-12).

### Ficha, lista y mapa (H3)
- RF-32: DONDE el punto sea replanteado, EL SISTEMA mostrará en su ficha de qué punto de diseño viene, las coordenadas de diseño y las diferencias de Norte, Este, distancia horizontal y cota respecto al diseño, en las unidades del proyecto.
- RF-33: EL SISTEMA marcará en la lista de objetos, con un icono y un texto, los puntos de diseño que tienen algún replanteo y cuántos (S011/RF-3).
- RF-34: EL SISTEMA dibujará en el mapa los puntos replanteados con un símbolo distinto del de los medidos (S010/RF-20).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida con el replanteo abierto.
  - RNF-T3: el corte se avisa con sonido o vibración en menos de 3 s.
  - RNF-T6: cada punto replanteado en una sola transacción con durabilidad completa; la medida bruta no se modifica nunca; al actualizar la app los puntos existentes se conservan, con las columnas nuevas vacías (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el estado y el "Dentro de la tolerancia" nunca solo con color; TalkBack lee la distancia y la dirección ("a 1,2 m, 30° a la derecha") al entrar en cada zona y a petición, sin anunciar cada refresco; los avisos son sonido y vibración.
  - RNF-T9: unidades lineales y angulares del proyecto en la guía, la tolerancia y las diferencias.
  - RNF-T13: la guía y la diana se refrescan a 5 Hz como máximo y calcularlas a 10 Hz no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: la distancia horizontal, las diferencias de Norte y Este y el rumbo al punto, en el SC del proyecto, coinciden con los calculados aparte con pyproj a menos de 1 mm y a menos de 0,001° respectivamente.
- La guía refleja una época nueva en menos de 300 ms desde que llega.
- Guardar un punto replanteado tarda menos de 1 s en el móvil de referencia (RNF-T13).

## Casos límite
| Caso | RF |
|---|---|
| Primer o último punto de la lista al pulsar siguiente o anterior | RF-3 |
| Punto de diseño sin altura, o punta sin altura | RF-12 |
| Parado, con el rumbo de marcha | RF-9, RF-11 |
| IMU sin inicializar o sin estado 30, con el rumbo del IMU | RF-10, RF-11 |
| FIX perdido con "Solo FIX" activo | RF-22 |
| FIX perdido con "Solo FIX" desactivado | RF-23 |
| Inclinación en uso y época sin estado 30 | RF-24 |
| Corte de datos o pérdida de la conexión | RF-25 |
| Oscilar sobre el borde de la tolerancia | RF-20 (5 mm de margen) |
| Replantear dos veces el mismo punto | RF-27 (`_stkd2`) |
| Medida fuera del control de precisión | RF-26 (S010/RF-14, RF-15) |
| Corte durante un promediado en el replanteo | RF-26 (S017/RF-8 a RF-10) |
| Cambiar de punto o de ajustes durante la medida | RF-30 |
| Posición fuera del ámbito del SC del proyecto | RF-31 |
| SC geográfico | Términos (plano local) |
| Reproducción en vez de un receptor | RF-27 (S010/RF-18) |
| Punto de diseño editado después de replantearlo | RF-28 (conserva las coordenadas de diseño de entonces) |
| Punto replanteado borrado a la papelera | S011/RF-22 (como cualquier punto) |
| Copia de seguridad y paquete con puntos replanteados | S013 (son puntos como los demás) |

## Fuera de alcance
- Cota de diseño con tolerancia, desmonte y terraplén, e informe de replanteo → S019.
- Columnas del replanteo en el CSV de puntos → S019 (informe).
- Replanteo de líneas, vértices y segmentos → S041 y S027.
- Replanteo de puntos de un DXF → S041.
- Puntos importados de un CSV → S028 (ya se pueden replantear: son puntos del proyecto).
- Desplazamiento de la base (base shift) → S020.
- Distancias de terreno en vez de cuadrícula → S026.
- Lista de replanteo armada y ordenada por el usuario → no previsto.
- Medir solo al estar dentro de la tolerancia → no previsto.
- Rumbo de la brújula del móvil → no previsto.
- Replanteo con realidad aumentada → no previsto (solo RS4 Pro).
- Mapa orientado con el rumbo del usuario → no previsto (el mapa sigue con el norte arriba, S009/RF-11).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la distancia, las diferencias de Norte y Este y el rumbo al punto frente a un cálculo aparte con pyproj (≤ 1 mm), con varios puntos de diseño y puntas en la Península y Canarias.
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Reproducción sintética de un recorrido hacia un punto de diseño, con el FIX perdido un rato, y su resultado esperado calculado aparte.
- Demo en el emulador con esa reproducción:
  - elegir un punto, la guía por dirección y por distancia;
  - la diana y los avisos al entrar en la zona de ayuda y en la tolerancia;
  - el FIX perdido con la guía apagada y su vuelta;
  - medir y ver el punto `_stkd` con sus diferencias en la ficha, y replantear otra vez para ver `_stkd2`.
- Pruebas de campo con el RS3 (del usuario, puerta G2): 5 puntos replantados, marcados y comprobados después (≤ 2,5 cm).

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: la zona de ayuda de 40 cm es fija y no se elige; viene de la hoja de ruta.]
- [SUPOSICIÓN: para el rumbo de marcha basta una velocidad de 0,3 m/s y se conserva 10 s al pararse; son valores iniciales a ajustar en el campo.]
- [SUPOSICIÓN: "Siguiente" y "Anterior" recorren los puntos por nombre en orden natural, no del más reciente al más antiguo como se ve la lista, porque en un replanteo se va por números de punto.]
- [SUPOSICIÓN: las distancias y diferencias son de cuadrícula, en el SC del proyecto y sin factor combinado, como en S017; con la escala de España el error es de unos 0,4 m por km, irrelevante con las distancias de un replanteo.]
- [SUPOSICIÓN: la diferencia que se guarda con el punto replanteado es "medido menos diseño" y la que muestra la guía es "diseño menos punta", lo que hay que moverse; cada una en el sentido en que se usa.]
- [SUPOSICIÓN: el punto de diseño elegido no se recuerda al cerrar el replanteo ni la app.]
- [SUPOSICIÓN: el punto replanteado recibe la descripción del punto de diseño, sin añadir texto propio.]
- [SUPOSICIÓN: el mapa de replanteo no gira con el rumbo (S009/RF-11); el inventario hablaba de orientar el mapa con el IMU y aquí lo hace solo la diana.]
- [SUPOSICIÓN: con una reproducción se puede replantear y los puntos quedan marcados como de reproducción (S010/RF-18).]
