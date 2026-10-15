# Spec 003 — Flujo NMEA completo y simulador

> Hito: H1 · Depende de: S002 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
La S002 solo entiende GGA y GST, y trata cada frase por separado. Las funciones siguientes necesitan más datos:
- el estado detallado (S013): satélites, DOP y base;
- la inclinación (S014): la frase ETC emparejada con su GGA;
- el replanteo (S016): la velocidad y el rumbo;
- la fecha de las medidas (RNF-T10): ZDA o RMC.

Además, la app debe saber en todo momento si lo que muestra es actual, y el usuario debe ver el estado de la solución en cualquier pantalla, no solo en Receptor.

Por último, hoy solo se puede probar TopoPhone con el receptor delante. Un simulador que reproduzca sesiones grabadas o ficheros NMEA permite probar la app en el emulador o en la oficina, y repetir un problema de campo tantas veces como haga falta. Para eso las sesiones tienen que guardar también las correcciones y poder gestionarse sin llenar el móvil.

## Usuarios / actores
- **Topógrafo**, con el móvil y el RS3 en campo, o en la oficina revisando sesiones.
- **Reach RS3**, que envía por defecto GGA y ETC a 5 Hz, y GSA, GST, GSV, RMC, VTG, ZDA y EBP a 1 Hz. GSA y GSV llegan con el identificador de cada constelación (GP, GL, GA, GB); el resto, con GN.
- **Desarrollador o probador**, que usa el simulador en el emulador sin receptor.

## Historias de usuario
- H1: Como topógrafo quiero que la app entienda todas las frases que envía el RS3 para que las funciones siguientes tengan sus datos.
- H2: Como topógrafo quiero ver el estado de la solución en todas las pantallas para no trabajar sin saberlo.
- H3: Como topógrafo quiero que la app detecte cuándo los datos dejan de ser actuales para no fiarme de una posición antigua.
- H4: Como topógrafo quiero que las sesiones guarden también las correcciones y poder gestionarlas para diagnosticar problemas sin llenar el móvil.
- H5: Como topógrafo o probador quiero reproducir una sesión grabada o un fichero NMEA como si fuera el receptor para probar la app sin salir al campo.

## Requisitos funcionales (criterios de aceptación en EARS)

### Frases NMEA (H1)
- RF-1: CUANDO llegue una frase GSA válida, EL SISTEMA obtendrá el modo de posición (sin posición, 2D o 3D), los satélites usados con su constelación y los valores PDOP, HDOP y VDOP.
- RF-2: CUANDO llegue una frase GSV válida, EL SISTEMA obtendrá los satélites en vista de su constelación con la elevación, el azimut y la relación señal/ruido de cada uno, separada por señal cuando la frase la indique.
- RF-3: CUANDO llegue una frase RMC o ZDA válida, EL SISTEMA obtendrá la fecha y la hora UTC.
- RF-4: CUANDO llegue una frase RMC o VTG válida, EL SISTEMA obtendrá la velocidad y el rumbo sobre el suelo.
- RF-5: CUANDO llegue una frase EBP válida, EL SISTEMA obtendrá la posición de la base: latitud, longitud y altura elipsoidal.
- RF-6: CUANDO llegue una frase ETC válida, EL SISTEMA obtendrá la hora, el estado y el aviso de la inclinación, el rumbo, la dirección y el valor de la inclinación, y sus tres coeficientes de precisión.
- RF-7: SI una frase de un tipo conocido tiene un campo que no se puede interpretar, ENTONCES EL SISTEMA descartará la frase entera.

### Épocas y datos obsoletos (H3)
- RF-8: EL SISTEMA agrupará en una misma época las frases con la misma hora UTC y las frases sin hora (GSA, GSV, VTG y EBP) que lleguen entre ellas.
- RF-9: EL SISTEMA dará por completa una época cuando llegue una frase con otra hora o cuando pasen 150 ms sin llegar ninguna frase.
- RF-10: SI llega una frase con una hora anterior a la de la última época completa, ENTONCES EL SISTEMA la descartará, salvo en el paso de medianoche UTC y al reiniciarse el bucle de una reproducción.
- RF-11: EL SISTEMA asignará a cada época la fecha UTC que dan las frases RMC o ZDA, y la dejará sin fecha mientras no haya llegado ninguna.
- RF-12: SI pasan más de 2 s sin que se complete ninguna época, ENTONCES EL SISTEMA mostrará el estado "Sin datos" (RNF-T3).
- RF-13: MIENTRAS el estado sea "Sin datos", EL SISTEMA no mostrará la solución ni las coordenadas de la última época.
- RF-14: CUANDO se complete una época, EL SISTEMA mostrará en la tarjeta Estado su fecha y su hora en la hora local del móvil, o "fecha no disponible" si la época no tiene fecha (RNF-T10).
- RF-15: CUANDO se complete una época con frases GSV, EL SISTEMA mostrará en la tarjeta Estado el número de satélites en vista junto al de satélites usados.

### Barra de estado (H2)
- RF-16: EL SISTEMA mostrará una barra de estado en las secciones Levantamiento, Receptor y Ajustes.
- RF-17: MIENTRAS no haya ningún receptor conectado ni ninguna reproducción en curso, la barra de estado mostrará "Sin receptor".
- RF-18: MIENTRAS se completen épocas, la barra de estado mostrará el estado de la solución con su color y su nombre, el número de satélites usados y, si la época la trae, la edad de las correcciones en segundos.
- RF-19: MIENTRAS el estado sea "Sin datos", la barra de estado mostrará "Sin datos" en lugar de la solución.
- RF-20: MIENTRAS se grabe una sesión, la barra de estado mostrará el indicador "Grabando".
- RF-21: MIENTRAS haya una reproducción en curso, la barra de estado mostrará "Simulación" con un color propio y su texto.
- RF-22: MIENTRAS lleguen frases ETC, la barra de estado mostrará el estado de la inclinación: Apagada, Error, Configuración, Alineación o Compensando.
- RF-23: CUANDO el usuario pulse la barra de estado, EL SISTEMA abrirá la sección Receptor.

### Grabación y lista de sesiones (H4)
- RF-24: MIENTRAS el receptor esté conectado, EL SISTEMA guardará en la sesión, además de los datos recibidos del receptor (S002/RF-42), las correcciones enviadas al receptor tal como se envían.
- RF-25: EL SISTEMA conservará las sesiones más recientes hasta ocupar 500 MB en total y borrará las más antiguas.
- RF-26: CUANDO el usuario cambie en Ajustes el espacio para sesiones, entre 100 MB y 2 GB, EL SISTEMA aplicará el nuevo límite y borrará en ese momento las sesiones más antiguas que no quepan.
- RF-27: SI la sesión en curso supera por sí sola el límite de espacio, ENTONCES EL SISTEMA seguirá grabándola y conservará solo sus datos más recientes que quepan en el límite.
- RF-28: SI el espacio libre del móvil baja de 100 MB, ENTONCES EL SISTEMA dejará de grabar la sesión y avisará de ello.
- RF-29: CUANDO el usuario abra la lista de sesiones, EL SISTEMA mostrará cada sesión con su fecha y hora de inicio, su duración, su tamaño y el receptor o "Importada", de la más reciente a la más antigua.
- RF-30: CUANDO el usuario comparta una sesión de la lista, EL SISTEMA ofrecerá sus datos del receptor y sus correcciones para enviarlos o guardarlos con las apps del móvil.
- RF-31: CUANDO el usuario borre una sesión que no esté en curso y confirme el borrado, EL SISTEMA la eliminará.

### Reproducción (H5)
- RF-32: CUANDO el usuario elija reproducir una sesión de la lista, EL SISTEMA usará sus datos del receptor como si llegaran de un receptor conectado.
- RF-33: CUANDO el usuario importe un fichero NMEA de texto, EL SISTEMA lo añadirá a la lista de sesiones como "Importada", listo para reproducirlo (RNF-T5).
- RF-34: SI el fichero importado no contiene ninguna frase NMEA válida con hora o supera los 200 MB, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-35: MIENTRAS haya una reproducción en curso, EL SISTEMA mostrará en la tarjeta Conexión "Reproduciendo" con el nombre de la sesión y la velocidad.
- RF-36: MIENTRAS haya una reproducción en curso, EL SISTEMA entregará las épocas con los intervalos de hora UTC que tienen en el fichero, divididos por la velocidad elegida (×1 o ×5).
- RF-37: CUANDO el usuario cambie la velocidad durante una reproducción, EL SISTEMA la aplicará desde la época siguiente.
- RF-38: SI entre dos épocas del fichero hay más de 10 s, ENTONCES EL SISTEMA reproducirá ese hueco como si fuera de 10 s.
- RF-39: CUANDO termine el fichero con el bucle activado, EL SISTEMA volverá a reproducirlo desde el principio.
- RF-40: CUANDO termine el fichero con el bucle desactivado, EL SISTEMA mostrará "Reproducción terminada" y ofrecerá volver a reproducirlo.
- RF-41: CUANDO el usuario pulse "Detener", EL SISTEMA terminará la reproducción.
- RF-42: SI el usuario inicia una reproducción con un receptor conectado, ENTONCES EL SISTEMA pedirá confirmación y cerrará la conexión con el receptor y la NTRIP antes de reproducir.
- RF-43: CUANDO el usuario conecte un receptor durante una reproducción, EL SISTEMA terminará la reproducción antes de conectar.
- RF-44: SI el usuario pulsa "Conectar NTRIP" durante una reproducción, ENTONCES EL SISTEMA no se conectará al caster e indicará que la reproducción no admite correcciones.
- RF-45: MIENTRAS haya una reproducción en curso, EL SISTEMA no grabará ninguna sesión.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T3: "Sin datos" a los 2 s sin época.
  - RNF-T5: importar con el selector de documentos, validando por contenido.
  - RNF-T7: idioma.
  - RNF-T8: modo sol, 56 dp, color y texto, TalkBack en la barra de estado.
  - RNF-T10: fecha y hora del receptor.
  - RNF-T12: grabación de sesiones. El log de la app y el informe de sistema van en la S012.
  - RNF-T13: la barra de estado se refresca a 5 Hz como máximo.
- **Latencia**: desde que llega una GGA hasta que se ven sus coordenadas, sigue siendo < 300 ms (S002), también con las épocas.
- **Ritmo de la reproducción**: a ×1, la duración de la reproducción difiere de la de la sesión en menos de un 1 % (sin contar los huecos de más de 10 s).
- **Grabación**: no se pierde ningún byte con flujos de hasta 20 kB/s; si la app se cierra de golpe, se pierden como máximo los últimos 2 s de la sesión.
- **Funcionamiento sin red**: la reproducción y la gestión de sesiones funcionan sin internet.

## Casos límite
| Caso | RF |
|---|---|
| GSA y GSV con el identificador de cada constelación (GP, GL, GA, GB) | RF-1, RF-2 |
| Frase conocida con un campo corrupto | RF-7 |
| GGA a 5 Hz y el resto de frases a 1 Hz | RF-8, RF-9 |
| La última época de una ráfaga (no llega una frase con otra hora) | RF-9 |
| Paso de medianoche UTC | RF-10, RF-11 |
| Frases desordenadas o repetidas | RF-10 |
| Todavía no ha llegado ninguna ZDA ni RMC | RF-11, RF-14 |
| El receptor deja de enviar sin cerrar la conexión | RF-12, RF-13, RF-19 |
| Sesión más grande que el límite de espacio | RF-27 |
| Móvil casi lleno | RF-28 |
| Borrar la sesión que se está grabando | RF-31 |
| Fichero importado vacío, binario o de más de 200 MB | RF-34 |
| Fichero con huecos largos (receptor apagado un rato) | RF-38 |
| Vuelta al principio del bucle (la hora retrocede) | RF-10, RF-39 |
| Reproducir con el receptor conectado, o conectar durante la reproducción | RF-42, RF-43 |
| NTRIP durante la reproducción | RF-44 |

## Fuera de alcance
- Las tramas RTCM3 (CRC24Q) y los contadores de mensajes RTCM → S005.
- El log de la app y el informe de sistema (RNF-T12) → S012.
- El skyplot, la SNR, los DOP, la posición de la base y la línea base en pantalla → S013.
- Los avisos de la inclinación y la punta del jalón calculada con ETC → S014.
- La velocidad y el rumbo en pantalla → S016.
- La reconexión automática y el servicio en primer plano → S004.
- El receptor virtual con escenarios guionizados y el GNSS del móvil → S020.
- Reproducir ficheros binarios (UBX, RINEX, RTCM) → no previsto.
- Pausar, avanzar o retroceder dentro de una reproducción → no previsto.
- Enviar al caster la GGA de una reproducción → no previsto.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita y `rf_report` no da huérfanos.
- Una sesión sintética con todas las frases del RS3 se reproduce de principio a fin y da las épocas, las fechas y las coordenadas esperadas.
- Demo en el emulador, sin receptor: importar un fichero NMEA, reproducirlo a ×1 y ×5 con bucle, y ver la barra de estado en las tres secciones.
- Demo con el RS3 real: la barra de estado en las tres secciones, la grabación con correcciones, y la sesión resultante en la lista, compartida y reproducida.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: 150 ms sin frases bastan para dar una época por completa, porque el receptor envía cada época de una vez. Se comprueba con las sesiones reales del RS3.]
- [SUPOSICIÓN: en la reproducción, los huecos de más de 10 s se acortan a 10 s, para no esperar minutos sin datos.]
- [SUPOSICIÓN: los ficheros importados pueden tener hasta 200 MB.]
- [SUPOSICIÓN: se deja de grabar cuando el móvil tiene menos de 100 MB libres.]

## Cambios en documentos anteriores (aplicados al aprobar esta spec)
- `specs/002-cadena-rtk-minima/spec.md`, RF-44: pasa de "conservará como máximo las 10 últimas sesiones" a "conservará las sesiones más recientes y borrará las más antiguas cuando se supere el límite de espacio de grabación (S003/RF-25)".
- `specs/001-esqueleto-app/spec.md`, fuera de alcance: el log de la app y el informe de sistema pasan de → S003 a → S012.
- `docs/hoja-de-ruta.md`:
  - S003: sin las tramas RTCM3; con la importación de ficheros NMEA y el límite de espacio de las sesiones.
  - S005: añade las tramas RTCM3 (CRC24Q).
  - S012: añade el log de la app y el informe de sistema.
