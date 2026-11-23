# Spec 039 — Localización

> Hito: H6 · Depende de: S035, S058, S011, S008, S020 (orden de cálculo) · Estado: spec (entrevista 23/11/2026)

## Contexto y objetivo
Un plano de obra, un levantamiento antiguo o una red local no están en el sistema de la corrección RTK: entre las coordenadas GNSS y las del plano hay un giro, una escala y una traslación, y en altura un desplazamiento o un plano inclinado (el geoide no es perfecto en una zona pequeña). El topógrafo mide con GNSS unos puntos de coordenadas conocidas y quiere que la app calcule esa transformación y la aplique: «mido los cuatro clavos del plano y a partir de ahí todo sale en el sistema del plano».

Esta spec calcula una **localización** a partir de pares de puntos, muestra sus residuos, la aplica al proyecto y la puede guardar como sistema de coordenadas (S035). Es el paso 5 del orden fijo de cálculo (`docs/arquitectura.md`): bruto → base shift (S020) → SC → localización. Nunca altera la observación bruta ni las coordenadas del SC de un punto (constitución nº 1).

## Usuarios / actores
- Topógrafo con una red de puntos de control de coordenadas conocidas en el sistema de la obra.

## Historias de usuario
- H1: Como topógrafo quiero formar pares de «punto medido» y «coordenadas conocidas», y excluir componentes que no sean fiables.
- H2: Como topógrafo quiero ver la transformación calculada y los residuos de cada par antes de aplicarla, y poder fijar la escala a 1.
- H3: Como topógrafo quiero aplicar la localización al proyecto, quitarla sin perder nada y guardarla como sistema de coordenadas.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Par**: un punto medido con el receptor y las coordenadas conocidas (Este, Norte y cota) que le corresponden en el sistema de la obra.
- **Punto GNSS**: un punto de origen medido o replanteado; su posición de entrada es la del SC del proyecto **sin localizar**.
- **Componente**: horizontal (Este y Norte) o vertical (cota); cada par puede usar una, otra o ambas.
- **Localización**: la transformación calculada: traslación, giro y escala horizontales, y desplazamiento vertical con o sin pendiente.
- **Residuo**: diferencia (conocido − transformado) de cada par, horizontal y vertical, tras el ajuste.
- **Localización activa**: la que usa el proyecto; una o ninguna.
- **Borrador**: la localización en edición (pares, componentes, escala fija y umbrales) que aún no se ha aplicado.

### Formar los pares (H1)
- RF-1: CUANDO el usuario elija «Localización» en el menú de un proyecto, EL SISTEMA mostrará su estado (sin localización o activa con su RMS) y la lista de pares.
- RF-2: CUANDO el usuario añada un par, EL SISTEMA le pedirá un punto GNSS del proyecto y las coordenadas conocidas, escritas en la unidad lineal del proyecto o tomadas de otro punto manual o importado.
- RF-3: SI el punto elegido como GNSS no tiene observación bruta (manual, importado, calculado) o ya está en otro par, ENTONCES EL SISTEMA no añadirá el par e indicará el motivo.
- RF-4: EL SISTEMA admitirá hasta 50 pares; SI se intenta añadir el 51, ENTONCES lo indicará.
- RF-5: EL SISTEMA permitirá activar o excluir cada componente de cada par sin borrarlo.
- RF-6: SI un punto GNSS no se midió en FIX (S047) o su precisión es peor que la del control de precisión del proyecto, ENTONCES EL SISTEMA lo avisará en el par y exigirá confirmar su uso (constitución nº 3).

### Calcular y revisar (H2)
- RF-7: EL SISTEMA calculará la parte horizontal con los pares de componente horizontal activa: con 1 par, una traslación; con 2 o más, una semejanza 2D (traslación, giro y escala) por mínimos cuadrados.
- RF-8: DONDE el usuario active «Fijar escala = 1», EL SISTEMA calculará con 2 o más pares un movimiento rígido 2D (traslación y giro) por mínimos cuadrados, con la escala exactamente 1.
- RF-9: EL SISTEMA calculará la parte vertical con los pares de componente vertical activa: con 1 o 2 pares, un desplazamiento (la media de las diferencias); con 3 o más no alineados, un plano inclinado por mínimos cuadrados; SI son 3 o más alineados, ENTONCES usará el desplazamiento y lo avisará.
- RF-10: EL SISTEMA mostrará la traslación, el giro en el formato angular del proyecto, la escala como factor y en ppm, la pendiente del plano vertical en ppm, el residuo de cada par (Este, Norte, horizontal y vertical), el RMS horizontal y vertical y el par con mayor residuo.
- RF-11: EL SISTEMA recalculará el borrador al instante al añadir, quitar o excluir un par o un componente y al cambiar la escala fija.
- RF-12: SI no hay ningún par con componente activa, o el cálculo es degenerado (puntos GNSS coincidentes en la parte horizontal con 2 o más pares), ENTONCES EL SISTEMA no calculará e indicará el motivo.
- RF-13: EL SISTEMA permitirá ajustar el umbral de residuo (3 cm por defecto, horizontal y vertical) y el de escala (|escala − 1| > 0,001 por defecto), y destacará con texto además de color los residuos y la escala que los superen.

### Aplicar, editar, quitar y guardar (H3)
- RF-14: CUANDO el usuario pulse «Aplicar al proyecto», EL SISTEMA pedirá confirmación indicando cuántos puntos cambiarán sus coordenadas mostradas y dejará el borrador como localización activa con sus pares, sus parámetros y la fecha y hora.
- RF-15: SI algún residuo o la escala del borrador superan sus umbrales, ENTONCES EL SISTEMA exigirá además una confirmación explícita que los nombre, pero no impedirá aplicar.
- RF-16: MIENTRAS haya una localización activa, EL SISTEMA mantendrá sin cambios la activa al editar los pares, y solo la sustituirá al aplicar el borrador, pasando la anterior al historial.
- RF-17: MIENTRAS haya una localización activa, EL SISTEMA aplicará su transformación a las coordenadas de proyecto de todos los puntos GNSS, también a los medidos antes de aplicarla (panel de posición, recolector, replanteo, lista de objetos, fichas, mapa), y mostrará un indicador «Localizado».
- RF-18: EL SISTEMA no modificará nunca las coordenadas geodésicas brutas ni las del SC sin localizar de un punto; las localizadas se derivan y la ficha del punto mostrará ambas.
- RF-19: CUANDO se guarde un punto, EL SISTEMA guardará con él el identificador de la localización activa en ese momento.
- RF-20: EL SISTEMA no transformará los puntos manuales e importados, los dibujos, los ejes ni las superficies del proyecto: sus coordenadas ya están en el sistema de la obra.
- RF-21: CUANDO el usuario quite la localización, EL SISTEMA volverá a mostrar las coordenadas sin localizar, conservará la localización quitada en un historial con sus pares y permitirá reactivarla.
- RF-22: SI el proyecto está en coordenadas de terreno (S058), ENTONCES EL SISTEMA no permitirá aplicar ni reactivar una localización y pedirá volver antes a cuadrícula; SI hay una localización activa, ENTONCES no permitirá pasar a terreno.
- RF-23: MIENTRAS haya localización activa, EL SISTEMA exportará el CSV (S012) con las coordenadas localizadas y el identificador de la localización, y ofrecerá la opción de exportar sin localizar.
- RF-24: CUANDO el usuario pulse «Guardar como SC», EL SISTEMA creará en «Mis SC» (S035) un sistema con el SC del proyecto y esta localización, elegible en otros proyectos.
- RF-25: CUANDO el usuario pulse «Informe», EL SISTEMA generará un CSV con los pares, los parámetros y los residuos para guardar o compartir (RNF-T5).
- RF-26: EL SISTEMA incluirá las localizaciones en las copias de seguridad y en los paquetes (S013) y anotará cada aplicación y cada retirada en el log sin coordenadas.
- RF-27: MIENTRAS haya una localización activa, EL SISTEMA no permitirá cambiar el SC ni el sistema de alturas del proyecto (S035) e indicará que hay que quitar antes la localización.
- RF-28: CUANDO el usuario elija en un proyecto un SC de «Mis SC» guardado con una localización (RF-24), EL SISTEMA dejará el proyecto con el SC base y la localización como activa, sin pares, y lo indicará en la pantalla de localización.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: aplicar, quitar y guardar el identificador en los puntos, en una sola transacción; migración probada.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; residuos altos con texto además de color.
  - RNF-T9: radianes en el interior; la escala y el giro en el formato del proyecto; ficheros con `Locale.ROOT`.
  - RNF-T13: recalcular con 50 pares en menos de 100 ms.
- Constitución nº 2: los parámetros y los residuos coinciden con un cálculo independiente a menos de 0,1 mm (en metros) y 1e-9 (escala, giro en radianes); ida y vuelta (localizar y deslocalizar) a menos de 0,1 mm.
- Una localización sin ruido (pares generados con una transformación conocida) recupera sus parámetros con residuos menores que 0,1 mm.
- Con la escala fija a 1, la escala del resultado es exactamente 1.

## Casos límite
| Caso | RF |
|---|---|
| Punto manual como punto GNSS | RF-3 |
| Mismo punto en dos pares / 51 pares | RF-3, RF-4 |
| Punto GNSS en FLOAT | RF-6 |
| 3 pares verticales alineados | RF-9 |
| Pares con la misma posición horizontal | RF-12 |
| Escala muy alejada de 1 o residuos altos | RF-13, RF-15 |
| Editar pares con una localización activa | RF-16 |
| Cambiar la localización con puntos ya medidos | RF-17, RF-18, RF-19 |
| Quitar la localización | RF-21 |
| Proyecto en terreno | RF-22 |
| Cambiar el SC con una localización activa / elegir un SC guardado con localización | RF-27, RF-28 |
| Proyecto con base shift (S020) | Orden fijo: la entrada es la posición tras el shift |

## Fuera de alcance
- Base shift (desplazamiento ECEF) → S020.
- Catálogo, editor de SC propios y «Mis SC» → S034, S035 (esta spec solo guarda en ellos).
- Localización junto con coordenadas de terreno → no previsto (excluyentes, RF-22).
- Pares con más de 50 puntos o transformaciones de 7 parámetros por pares → no previsto.
- Importar o pegar varias filas de coordenadas conocidas de una vez → no previsto.
- Localización con puntos medidos con otro método (estación total) → no previsto.
- Transformar dibujos, ejes y superficies al sistema localizado → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/localizacion/`: pares sintéticos con una transformación conocida (con y sin ruido, con y sin escala fija), ajuste por las ecuaciones normales en aritmética racional (`fractions`, sin dependencias nuevas, distinto algoritmo que el del móvil), coordenadas de los pares obtenidas con pyproj desde geográficas ETRS89 a UTM.
- Demo en el emulador: reproducción de varios puntos, 4 pares, residuos, aplicar, ver el indicador «Localizado» y los puntos recalculados, quitar y exportar.
- Demo de campo (medir puntos de control reales): queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el GeoPackage con localización activa solo puede declarar el SC guardado (RF-24) o geográficas, no el del proyecto sin localización.]

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S039 depende además de S058 y, para el orden de cálculo, de S020.
- `docs/glosario.md`: «Par de localización» (`LocalizationPair`), «Localización activa», «Localizado».
- S012: su «Fuera de alcance» («Coordenadas de una localización → S039») pasa a RF-23 de esta spec.
- S053: con localización activa, la opción «sistema del proyecto» no debe declarar un EPSG que ya no corresponde.
- S035: debe aceptar un SC derivado del del proyecto más una localización.
- S058: con una localización activa no se puede pasar a terreno (RF-22).
