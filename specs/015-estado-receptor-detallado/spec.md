# Spec 015 — Estado detallado del receptor

> Hito: H2 · Depende de: S014 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
La pantalla Estado enseña hoy lo mínimo para trabajar: la solución, los satélites usados y en vista, el HDOP, la edad de las correcciones, las precisiones y la hora de la época (S002, S003, S005). No responde a las preguntas que se hace un topógrafo cuando la solución no fija o fija mal:
- ¿dónde están los satélites y cuáles usa?
- ¿con qué señal llegan al rover, y a la base?
- ¿con qué base trabajo y a qué distancia está?

Esta spec completa la pantalla Estado con la posición, los DOP, un skyplot, la SNR del rover y de la base por satélite y señal, la posición de la base y la línea base. Además, la base se dibuja en el mapa del proyecto y cada punto medido guarda la base con la que se midió, para saber después de qué base sale cada coordenada (constitución nº 1).

## Usuarios / actores
- Topógrafo en campo que comprueba la calidad de la solución antes de medir o cuando no fija.
- Técnico en oficina que revisa con qué base se midió cada punto.
- Receptor RS3: frases GGA, GSA, GSV y EBP.
- Caster NTRIP o base propia: mensajes RTCM 1005/1006 (posición de la estación de referencia) y MSM (observaciones, con la SNR de la base desde el nivel 4).

## Historias de usuario
- H1: Como topógrafo quiero ver la posición del receptor y los DOP para valorar la geometría de los satélites.
- H2: Como topógrafo quiero ver en un skyplot dónde están los satélites y cuáles se usan para saber qué tapan los obstáculos.
- H3: Como topógrafo quiero comparar la SNR del rover y la de la base por satélite y señal para distinguir un problema de mi sitio de uno de la base.
- H4: Como topógrafo quiero ver la posición de la base y la línea base, y que la app me avise si la base está lejos o cambia, para no medir con una base que no es la que creo.
- H5: Como topógrafo quiero ver la base en el mapa del proyecto para situarla respecto a mi trabajo.
- H6: Como técnico quiero que cada punto guarde la base con la que se midió para comprobarlo en la ficha y en el CSV.

## Requisitos funcionales (criterios de aceptación en EARS)
### Posición y DOP (H1)
- RF-1: CUANDO se complete una época con GGA, EL SISTEMA mostrará en la pantalla Estado la latitud, la longitud y la altura elipsoidal de la antena que da el receptor, con el formato angular de los proyectos nuevos (S006).
- RF-2: CUANDO se complete una época con frases GSA, EL SISTEMA mostrará en la pantalla Estado el PDOP, el HDOP y el VDOP.
- RF-3: CUANDO se complete una época con frases GSA y GSV, EL SISTEMA mostrará en la pantalla Estado, por constelación, los satélites usados y los satélites en vista.

### Skyplot (H2)
- RF-4: CUANDO se complete una época con frases GSV, EL SISTEMA dibujará en un skyplot cada satélite que tenga elevación y azimut, con el norte arriba, el azimut en sentido horario, el horizonte en el borde y el cénit en el centro.
- RF-5: EL SISTEMA indicará en el skyplot la constelación de cada satélite con un color y una letra, y su número.
- RF-6: EL SISTEMA distinguirá en el skyplot, con relleno y sin relleno, los satélites usados en la solución de los que solo están en vista.

### SNR del rover y de la base (H3)
- RF-7: CUANDO se complete una época con frases GSV, EL SISTEMA mostrará una barra con la SNR del rover para cada satélite y banda de frecuencia, agrupadas por constelación y banda.
- RF-8: CUANDO lleguen en las correcciones mensajes MSM de nivel 4 o superior, EL SISTEMA mostrará junto a la barra del rover la barra de la base para el mismo satélite y la misma banda.
- RF-9: EL SISTEMA dará a cada barra un color según su SNR (menos de 35 dB-Hz, de 35 a 44 dB-Hz, y 45 dB-Hz o más) y escribirá su valor.
- RF-10: SI en los últimos 10 s no ha llegado ningún mensaje MSM de nivel 4 o superior, ENTONCES EL SISTEMA indicará que la SNR de la base no está disponible.

### Base y línea base (H4)
- RF-11: CUANDO el receptor envíe una frase EBP, EL SISTEMA tomará de ella la posición de la base.
- RF-12: MIENTRAS el receptor no envíe frases EBP, EL SISTEMA tomará la posición de la base del último mensaje RTCM 1005 o 1006 de las correcciones.
- RF-13: EL SISTEMA mostrará en la pantalla Estado la latitud, la longitud y la altura elipsoidal de la base, y si la da el receptor o las correcciones, con el identificador de la estación de referencia en este caso.
- RF-14: MIENTRAS haya posición del rover y de la base, EL SISTEMA mostrará la línea base: la distancia en línea recta entre la antena del rover y la base, en km con 3 decimales.
- RF-15: SI la línea base supera 20 km, ENTONCES EL SISTEMA mostrará el aviso "Línea base larga" en la pantalla Estado y en el recolector, sin impedir medir.
- RF-16: SI en los últimos 60 s no ha llegado ninguna frase EBP ni ningún mensaje RTCM 1005 o 1006, ENTONCES EL SISTEMA mostrará la base y la línea base como no disponibles.

### Cambio de base (H4)
- RF-17: CUANDO la posición de la base se mueva más de 0,1 m, EL SISTEMA mostrará en la pantalla Estado y en el recolector el aviso "La base ha cambiado", con la distancia entre la base anterior y la nueva.
- RF-18: SI la base cambia durante una medida, ENTONCES EL SISTEMA descartará las épocas ya tomadas y empezará la medida de nuevo con la base nueva.

### Base en el mapa (H5)
- RF-19: MIENTRAS haya posición de la base, EL SISTEMA la dibujará en el mapa del proyecto con un símbolo propio, distinto del rover y de los puntos.

### Base de cada punto (H6)
- RF-20: CUANDO se guarde un punto medido con posición de la base, EL SISTEMA guardará con él la posición de la base, de dónde salió, el identificador de la estación si lo hay y la línea base.
- RF-21: DONDE el punto tenga base, EL SISTEMA mostrará en su ficha la posición de la base en geográficas y en el SC del proyecto, de dónde salió y la línea base.
- RF-22: EL SISTEMA escribirá en "Todas las columnas" de la exportación CSV (S012/RF-6), tras las columnas de la medida, la latitud, la longitud y la altura elipsoidal de la base, su Este, Norte y altura en el SC del proyecto, y la línea base.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: la base se guarda en la misma transacción que el punto; al actualizar la app, los puntos existentes se conservan, sin base.
  - RNF-T7 y RNF-T8: textos en recursos; la constelación y el umbral de SNR se indican con color y con letra o valor, nunca solo con color; zonas táctiles de 56 dp; TalkBack.
  - RNF-T9: azimut en sentido horario desde el norte; unidades del proyecto en la ficha y en el CSV.
  - RNF-T13: el skyplot y el gráfico de SNR se refrescan a 5 Hz como máximo sin bloquear el hilo principal más de 16 ms.
- Constitución nº 2: frente a pyproj, la posición de la base sacada de un mensaje 1005/1006 difiere ≤ 1 mm y la línea base ≤ 1 mm.
- El skyplot y el gráfico de SNR muestran una época en menos de 1 s desde que se completa, con hasta 64 satélites en vista y 3 bandas por satélite.

## Casos límite
| Caso | RF |
|---|---|
| Sin correcciones (solución autónoma) | RF-16 (base no disponible), RF-10 |
| Correcciones por LoRa: hay EBP, pero las correcciones no pasan por la app | RF-11 (base del receptor), RF-10 (sin SNR de la base) |
| Red VRS: la base es una estación virtual a pocos metros | RF-14 (línea base hasta la estación virtual) |
| Las correcciones traen MSM de nivel 1 a 3 (sin SNR) | RF-10 |
| Satélite en GSV sin elevación o sin azimut | RF-4 (no se dibuja en el skyplot), RF-7 (sí tiene barra) |
| Satélite en vista sin SNR (no se sigue) | RF-7 (aparece con su número y sin barra) |
| Satélite que ve la base y no el rover | RF-8 (solo la barra de la base) |
| Llegan EBP y 1005/1006 de la misma base | RF-11 (manda la EBP); RF-17 (no es cambio si se mueve ≤ 0,1 m) |
| La base cambia durante una medida promediada | RF-18 |
| La base cambia sin medida en curso | RF-17 |
| NTRIP en pausa o reconectando | RF-16 (la base deja de estar disponible a los 60 s) |
| Reproducción de una sesión | RF-11 (EBP de la reproducción); RF-10 (la reproducción no admite correcciones, S003) |
| Puntos medidos antes de esta versión, puntos manuales y copias | RF-20 (sin base): la ficha no la muestra y las columnas van vacías |
| Base fuera del encuadre del mapa | RF-19 (se dibuja en su sitio; el mapa no se mueve) |
| Copias de seguridad y paquetes (S013) | RF-20 (la base viaja con el punto) |

## Fuera de alcance
- Inclinación (ETC) en la pantalla Estado → S016.
- Configurar la base (modo, mensajes RTCM, LoRa) → guía de S043.
- Recalcular los puntos si se corrigen las coordenadas de la base → S020 (base shift).
- GDOP y TDOP → no previsto (no vienen en NMEA).
- PDOP y satélites por constelación guardados en cada punto → no previsto en esta spec.
- Filtrar el skyplot por constelación, máscara de elevación en el skyplot e historial de SNR → no previsto.
- Tocar la base en el mapa o centrar el mapa en ella → no previsto.
- Coordenadas del rover en el SC del proyecto en la pantalla Estado → ya están en la pantalla Coordenadas (S008) y en la vista de proyecto (S006).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- La posición de la base de los mensajes 1005/1006 y la SNR de los MSM se comprueban con las tramas RTCM reales de `fixtures/` (S007), y la posición y la línea base contra pyproj.
- Demo en el emulador con la reproducción sintética del RS3 (`rs3-completo.nmea`, con GSA, GSV y EBP):
  - pantalla Estado con la posición, los DOP, el skyplot, la SNR del rover, la base y la línea base;
  - la base en el mapa;
  - un punto medido con su base en la ficha y en el CSV.
- En campo (del usuario, con el RS3 y NTRIP): la SNR de la base, la posición de la base frente a la publicada de la estación y la línea base frente a una distancia calculada aparte.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: si llegan EBP y RTCM 1005/1006, manda la EBP, que es la base que usa el receptor.]
- [SUPOSICIÓN: la base "cambia" cuando su posición se mueve más de 0,1 m; un cambio de fuente con la misma base no avisa.]
- [SUPOSICIÓN: la base deja de estar disponible si en 60 s no llega EBP ni 1005/1006; los casters suelen enviar 1005/1006 cada 10 a 30 s.]
- [SUPOSICIÓN: la línea base es la distancia en línea recta, en 3D, entre la antena del rover y la posición de la base, en km con 3 decimales.]
- [SUPOSICIÓN: las barras de SNR se nombran por banda (L1, L2, L5, G1, G2, E1, E5a, E5b, E6, B1, B1C, B2, B2a, B3), y rover y base se comparan en la misma banda.]
- [SUPOSICIÓN: los avisos "Línea base larga" y "La base ha cambiado" siguen visibles hasta que el usuario los cierra; no cambian el control de precisión (S010) y quedan en el log (S014/RF-5).]
- [SUPOSICIÓN: las columnas nuevas del CSV van en inglés, como el resto (S012), y vacías en los puntos sin base.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S015: "Pantalla de estado: posición, DOP, skyplot (GSV), SNR del rover y de la base por satélite y banda (MSM), base (EBP o RTCM 1005/1006) y línea base, con avisos de línea base larga y de cambio de base; la base en el mapa y guardada en cada punto (ficha y CSV)", y pasa a "en curso".
- `docs/glosario.md`: se añaden "Skyplot" (`Skyplot`), "SNR" (`snrDbHz`), "DOP (PDOP, HDOP, VDOP)" (`Dop`), "Banda de frecuencia" (`Band`) y "Estación de referencia" (`ReferenceStation`, RTCM 1005/1006); "Posición de la base" pasa a "Frase EBP o mensajes RTCM 1005/1006".
