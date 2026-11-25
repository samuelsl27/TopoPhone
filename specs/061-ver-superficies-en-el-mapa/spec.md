# Spec 061 — Ver las superficies en el mapa

> Hito: H2 · Depende de: S042, S045, S060 · Estado: aprobada

## Contexto y objetivo
Una superficie importada (un TIN de un LandXML, S042, o un ráster de cotas, S045) hoy no se ve: solo aparece el botón «Superficies» del mapa y una ficha con números. En campo, el topógrafo no sabe dónde empieza y dónde acaba la superficie, ni si está donde debe, hasta que la punta entra en ella. Con una superficie que el fichero no nombra (algunos programas de fotogrametría la exportan así) además queda una línea en blanco donde debería ir su nombre. Esta spec dibuja en el mapa las superficies que el usuario marque, con sus triángulos o sus celdas coloreados por cota, permite ir a una superficie con un toque y le da nombre a la que no lo trae. El replanteo de la superficie va en S062.

## Usuarios / actores
- Topógrafo en campo, con la superficie de proyecto o un modelo de vuelo de dron importados en el móvil.

## Historias de usuario
- H1: Como topógrafo quiero ver en el mapa los triángulos de la superficie coloreados por cota para saber dónde está y cómo es antes de replantear.
- H2: Como topógrafo quiero elegir qué superficies se ven, sea cual sea la activa, para comparar o despejar el mapa.
- H3: Como topógrafo quiero ir a una superficie con un toque para encontrarla cuando estoy lejos o cuando la acabo de importar.
- H4: Como topógrafo quiero que una superficie sin nombre se llame como su fichero para reconocerla en la lista.

## Requisitos funcionales (criterios de aceptación en EARS)
### Nombre de la superficie (H4)
- RF-1: SI la superficie de un LandXML no tiene nombre o su nombre solo tiene espacios, ENTONCES EL SISTEMA le dará el nombre del fichero sin la extensión en la pantalla de importación, en el resumen y en la lista de superficies.
- RF-2: CUANDO se actualice la app, EL SISTEMA dará el nombre de su fichero sin la extensión a las superficies ya importadas que no tengan nombre.

### Qué superficies se ven (H2)
- RF-3: EL SISTEMA mostrará en cada superficie de la ficha de Superficies (S042/RF-16) un interruptor «Ver en el mapa», independiente de cuál sea la superficie activa.
- RF-4: CUANDO termine la importación de una superficie, EL SISTEMA la dejará visible en el mapa.
- RF-5: EL SISTEMA conservará la visibilidad de cada superficie al cerrar la app, al cambiar la superficie activa y en las copias de seguridad y los paquetes de proyecto (S013).

### Dibujar la superficie (H1)
- RF-6: MIENTRAS una superficie TIN visible tenga más de 20.000 triángulos dentro de la vista del mapa, EL SISTEMA dibujará solo su contorno exterior y el de sus huecos.
- RF-7: MIENTRAS una superficie TIN visible tenga 20.000 triángulos o menos dentro de la vista del mapa, EL SISTEMA dibujará cada triángulo relleno con el color de su cota media y sus aristas encima.
- RF-8: MIENTRAS un ráster visible tenga más de 1.000.000 de celdas dentro de la vista del mapa, EL SISTEMA dibujará solo el recuadro de su extensión.
- RF-9: MIENTRAS un ráster visible tenga 1.000.000 de celdas o menos dentro de la vista del mapa, EL SISTEMA dibujará cada celda con dato rellena con el color de su cota y dejará sin dibujar las celdas sin dato.
- RF-10: EL SISTEMA coloreará por cota con una misma rampa de colores entre la cota mínima y la máxima del conjunto de superficies visibles, sin aplicar el desplazamiento vertical, y con el relleno semitransparente para que se vea el mapa base.
- RF-11: MIENTRAS alguna superficie esté coloreada en la vista, EL SISTEMA mostrará una leyenda con la rampa y las cotas mínima y máxima en las unidades del proyecto (RNF-T9).
- RF-12: EL SISTEMA dibujará las superficies encima del mapa base y de las capas WMS, y debajo de los dibujos, las líneas, los puntos y la posición del jalón.
- RF-13: EL SISTEMA dibujará la superficie activa con su contorno más grueso que el de las demás.

### Encontrar la superficie (H3)
- RF-14: CUANDO el usuario pulse «Ir a la superficie» en una superficie de la ficha, EL SISTEMA la hará visible si no lo era, cerrará la ficha y encuadrará el mapa en su extensión con un margen.
- RF-15: CUANDO el mapa se encuadre en una superficie, EL SISTEMA dejará de seguir al jalón y mostrará el botón «Recentrar» (S060/RF-26).
- RF-16: CUANDO el usuario toque en el mapa una superficie visible fuera de cualquier punto, línea o dibujo, EL SISTEMA mostrará una tarjeta con su nombre, su tipo (TIN o ráster), si es la activa y la cota de la superficie en el sitio tocado.
- RF-17: SI el toque cae sobre varias superficies visibles, ENTONCES EL SISTEMA mostrará en la tarjeta una fila por cada una para elegir.
- RF-18: SI el sitio tocado cae en un hueco o en una celda sin dato, ENTONCES EL SISTEMA mostrará en la tarjeta «Fuera de la superficie» en lugar de la cota (S042/RF-23).

## Requisitos no funcionales
- Transversales que aplican: RNF-T6 (la visibilidad se guarda de forma atómica; la migración conserva las superficies), RNF-T7, RNF-T8 (colores legibles a pleno sol y en modo sol; la leyenda no depende solo del color: lleva las cifras), RNF-T9, RNF-T13.
- Con una superficie de 1.000.000 de triángulos visible, mover o acercar el mapa mantiene al menos 30 imágenes por segundo en el móvil de referencia y la malla de la nueva vista aparece en menos de 1 s.
- Con la superficie visible, la hoja de campo del recolector y del replanteo sigue a 10 Hz sin retraso perceptible (S060).
- La cota de la tarjeta (RF-16) coincide con la de S042/RF-22 y S045/RF-21 (≤ 1 mm).

## Casos límite
| Caso | RF |
|---|---|
| LandXML con `name=""` o sin atributo `name` | RF-1 |
| Superficies sin nombre importadas antes de esta spec | RF-2 |
| Superficie de casi un millón de triángulos vista entera | RF-6 |
| Ráster de 250 millones de celdas | RF-8 |
| Varias superficies visibles con cotas muy distintas | RF-10, RF-11 |
| Superficie visible pero no activa | RF-3, RF-13 |
| Toque sobre dos superficies que se solapan | RF-17 |
| Toque en un hueco o en una celda sin dato | RF-18 |
| Superficie lejos de la posición actual | RF-14, RF-15 |
| Superficie borrada con el mapa encuadrado en ella | RF-3 (deja de dibujarse con la ficha, S042/RF-18) |

## Fuera de alcance
- Replantear la superficie (solo cota, desmonte o terraplén donde está el jalón) → S062.
- Curvas de nivel, sombreado o vista 3D de la superficie → no previsto.
- Elegir la rampa de colores, la transparencia o el intervalo de la leyenda → no previsto.
- Editar la superficie o sus triángulos en el móvil → no previsto (S042).
- Información geométrica de la superficie (área, pendiente) → S026.

## Criterios de finalización
Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos y una demo en el emulador y en el móvil de referencia con un LandXML de cerca de 1.000.000 de triángulos sin nombre de superficie y con un ráster: se importan, se ven con sus colores al acercarse y su contorno de lejos, se ocultan y se muestran, «Ir a la superficie» las encuadra y el toque da la cota.

## Dudas abiertas
Resueltas con una suposición. Si alguna no te vale, se cambia la spec antes de planificarla.
- [SUPOSICIÓN: los umbrales de 20.000 triángulos y 1.000.000 de celdas en la vista son de partida; se ajustan tras medir la fluidez del mapa en el móvil de referencia.]
- [SUPOSICIÓN: una superficie recién importada queda visible (RF-4); las importadas antes de esta spec quedan visibles solo si son la activa.]
- [SUPOSICIÓN: la rampa es común a todas las superficies visibles (RF-10), para que un mismo color sea la misma cota en todas; con una sola visible equivale a su propia rampa.]
- [SUPOSICIÓN: la tarjeta del toque (RF-16) muestra la cota de la superficie sin el desplazamiento vertical; S062 le añade el botón «Replantear».]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: se añade la fila S061 en H2 con estado `spec`.
- S042: su «Fuera de alcance» «Dibujar la superficie, sus curvas de nivel o su contorno en el mapa → no previsto» pasa a «Dibujar la superficie en el mapa → S061; curvas de nivel → no previsto».
- S045: su «Fuera de alcance» «Dibujar el ráster, sus curvas de nivel o su sombreado en el mapa → no previsto» pasa a «Dibujar el ráster en el mapa → S061; curvas de nivel y sombreado → no previsto».
- `docs/glosario.md`: se añade «Superficie visible» (`visibleOnMap`): la que se dibuja en el mapa, independiente de la activa (S061).
- La base de datos pasa a la versión siguiente (la visibilidad de cada superficie y el nombre de las que no lo tenían).
