# Spec 002 — Cadena RTK mínima

> Hito: H0 · Depende de: S001 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Antes de construir proyectos, mapas o replanteo hay que demostrar, cuanto antes y con el equipo real, que la cadena completa funciona:

1. El Reach RS3 envía su posición al móvil por Bluetooth.
2. El móvil obtiene correcciones de un caster NTRIP y se las reenvía al receptor.
3. El receptor alcanza la solución fija.
4. El móvil convierte esa posición en coordenadas del punto del suelo, en ETRS89 / UTM y con altitud ortométrica EGM08-REDNAP, con una exactitud de cálculo de 1 mm frente a una referencia independiente.

Si cualquiera de estos eslabones falla, todo lo demás se construiría sobre arena. Esta spec es la "trazadora" del proyecto: una sola pantalla, sin proyectos ni guardado de puntos. Termina con la puerta de validación de campo G0.

## Usuarios / actores
- **Topógrafo**, con el móvil y el RS3 en campo.
- **Reach RS3**, ya emparejado con el móvil y configurado una vez en Emlid Flow:
  - salida de posición NMEA por Bluetooth con al menos GGA y GST;
  - entrada de correcciones por Bluetooth;
  - inclinación "sin compensar".
- **Caster NTRIP**, por ejemplo la red del IGN o una red autonómica, que envía correcciones RTCM3.

## Historias de usuario
- H1: Como topógrafo quiero conectar el móvil con mi RS3 ya emparejado para recibir su posición.
- H2: Como topógrafo quiero que el móvil reciba correcciones NTRIP y se las pase al receptor para obtener la solución fija sin depender de Emlid Flow.
- H3: Como topógrafo quiero ver el estado de la solución y su precisión para saber si puedo fiarme de la posición.
- H4: Como topógrafo quiero ver las coordenadas del punto del suelo en ETRS89 / UTM y su altitud ortométrica para compararlas con una reseña.
- H5: Como topógrafo quiero guardar y compartir los datos brutos de la sesión para comprobar los cálculos con una referencia independiente.

## Requisitos funcionales (criterios de aceptación en EARS)

### Conexión con el receptor (H1)
- RF-1: CUANDO el usuario abra la sección Receptor, EL SISTEMA mostrará la lista de dispositivos Bluetooth emparejados con el móvil.
- RF-2: SI el permiso de Bluetooth no está concedido, ENTONCES EL SISTEMA lo pedirá explicando para qué se usa.
- RF-3: SI el usuario deniega el permiso de Bluetooth, ENTONCES EL SISTEMA mostrará que sin él no puede conectar con el receptor y cómo concederlo desde los ajustes del móvil.
- RF-4: SI el Bluetooth del móvil está apagado, ENTONCES EL SISTEMA lo indicará y ofrecerá activarlo.
- RF-5: CUANDO el usuario elija un dispositivo de la lista, EL SISTEMA abrirá la conexión serie con él y mostrará el estado "Conectando".
- RF-6: CUANDO llegue la primera frase NMEA válida, EL SISTEMA mostrará el estado "Recibiendo".
- RF-7: SI la conexión no se abre en 10 s, ENTONCES EL SISTEMA mostrará el error y las causas probables: receptor apagado o fuera de alcance, Emlid Flow conectado al receptor, o salida de posición por Bluetooth sin configurar.
- RF-8: SI con la conexión abierta no llega ninguna frase NMEA válida durante 3 s, ENTONCES EL SISTEMA mostrará el estado "Sin datos".
- RF-9: SI la conexión con el receptor se pierde, ENTONCES EL SISTEMA lo indicará en menos de 3 s con texto y vibración y ofrecerá reconectar.
- RF-10: CUANDO el usuario pulse "Desconectar", EL SISTEMA cerrará la conexión con el receptor y la conexión NTRIP.

### Estado de la solución (H3)
- RF-11: EL SISTEMA solo tendrá en cuenta las frases NMEA cuya suma de control sea correcta, con cualquier identificador de constelación (GN, GP…).
- RF-12: CUANDO llegue una frase GGA válida, EL SISTEMA mostrará el estado de la solución: Sin solución, Autónoma, Diferencial, Flotante, Fija o Estimada.
- RF-13: CUANDO llegue una frase GGA válida, EL SISTEMA mostrará el número de satélites usados, el HDOP y la edad de las correcciones en segundos.
- RF-14: CUANDO llegue una frase GST válida, EL SISTEMA mostrará la precisión horizontal estimada en metros con 3 decimales, calculada como raíz cuadrada de la suma de los cuadrados de las desviaciones en latitud y longitud.
- RF-15: CUANDO llegue una frase GST válida, EL SISTEMA mostrará la precisión vertical estimada, la desviación de la altura, en metros con 3 decimales.
- RF-16: EL SISTEMA indicará el estado de la solución con un color y con su nombre en texto.
- RF-17: SI hace más de 3 s que no llega una GST, ENTONCES EL SISTEMA mostrará las precisiones como no disponibles.

### Correcciones NTRIP (H2)
- RF-18: EL SISTEMA permitirá introducir el servidor, el puerto (2101 por defecto), el punto de montaje, el usuario y la contraseña del caster.
- RF-19: CUANDO el usuario pulse "Conectar NTRIP" con el receptor conectado, EL SISTEMA se conectará al caster y reenviará al receptor todos los datos de corrección recibidos, sin modificarlos y en el mismo orden.
- RF-20: MIENTRAS la conexión NTRIP esté abierta y el receptor haya enviado alguna GGA, EL SISTEMA enviará al caster la última GGA recibida cada 5 s.
- RF-21: SI el caster rechaza el usuario o la contraseña, ENTONCES EL SISTEMA mostrará "Usuario o contraseña incorrectos".
- RF-22: SI el punto de montaje no existe en el caster, ENTONCES EL SISTEMA mostrará "El punto de montaje no existe".
- RF-23: SI no se puede contactar con el caster en 10 s, ENTONCES EL SISTEMA mostrará "No se puede conectar con el caster" y comprobará si el móvil tiene conexión a internet.
- RF-24: SI con la conexión NTRIP abierta no llegan correcciones durante 10 s o el caster corta la conexión, ENTONCES EL SISTEMA mostrará el aviso y ofrecerá reconectar.
- RF-25: MIENTRAS la conexión NTRIP esté abierta, EL SISTEMA mostrará los bytes de corrección recibidos y los segundos desde la última corrección.
- RF-26: EL SISTEMA recordará entre sesiones el servidor, el puerto, el punto de montaje y el usuario.
- RF-27: EL SISTEMA no guardará la contraseña del caster en el dispositivo (se guardará cifrada a partir de S007).
- RF-28: SI el usuario pulsa "Conectar NTRIP" sin el receptor conectado, ENTONCES EL SISTEMA no se conectará al caster e indicará que antes hay que conectar el receptor.

### Coordenadas del punto del suelo (H4)
- RF-29: EL SISTEMA permitirá introducir la altura del jalón, desde la punta hasta la base del receptor, en metros, entre 0 y 10 m, con 1,800 m por defecto.
- RF-30: EL SISTEMA recordará la altura del jalón entre sesiones.
- RF-31: SI la altura del jalón introducida no es un número entre 0 y 10 m, ENTONCES EL SISTEMA no la aceptará e indicará el rango válido.
- RF-32: EL SISTEMA calculará la posición del punto del suelo suponiendo el jalón vertical: está bajo la posición del receptor a una distancia igual a la altura del jalón más 0,134 m (de la base del RS3 a su centro de referencia).
- RF-33: EL SISTEMA obtendrá la altura elipsoidal de la posición del receptor sumando la altitud y la separación del geoide que da la frase GGA.
- RF-34: EL SISTEMA mostrará la latitud y la longitud del punto del suelo en grados, minutos y segundos con 5 decimales de segundo, y en grados decimales con 9 decimales.
- RF-35: EL SISTEMA mostrará la altura elipsoidal del punto del suelo en metros con 3 decimales.
- RF-36: EL SISTEMA mostrará las coordenadas UTM del punto del suelo (Este y Norte, en metros con 3 decimales) en el huso que corresponde a su longitud, entre el 29 y el 31, indicando el huso.
- RF-37: SI la longitud del punto queda fuera de los husos 29 a 31, ENTONCES EL SISTEMA mostrará las coordenadas UTM como no disponibles en esta versión.
- RF-38: EL SISTEMA mostrará la altitud ortométrica del punto del suelo, igual a la altura elipsoidal menos la ondulación del modelo de geoide EGM08-REDNAP del IGN, en metros con 3 decimales.
- RF-39: SI el punto queda fuera de la cobertura del modelo EGM08-REDNAP de la Península y Baleares, ENTONCES EL SISTEMA mostrará la altitud ortométrica como no disponible e indicará el motivo, sin extrapolar.
- RF-40: MIENTRAS la solución no sea Fija, EL SISTEMA mostrará junto a las coordenadas un aviso de que no tienen precisión centimétrica.
- RF-41: EL SISTEMA indicará que las coordenadas están en el sistema de referencia de las correcciones y que se suponen ETRS89.

### Sesión (H5)
- RF-42: MIENTRAS el receptor esté conectado, EL SISTEMA guardará en un fichero de sesión todos los datos recibidos del receptor, tal como llegan.
- RF-43: CUANDO el usuario pulse "Compartir sesión", EL SISTEMA ofrecerá el fichero de la última sesión para enviarlo o guardarlo con las apps del móvil.
- RF-44: EL SISTEMA conservará las sesiones más recientes y borrará las más antiguas cuando se supere el límite de espacio de grabación (S003/RF-25).

### Atribución (H4)
- RF-45: CUANDO el usuario abra "Licencias y atribuciones", EL SISTEMA mostrará la atribución del modelo de geoide EGM08-REDNAP (© Instituto Geográfico Nacional, CC-BY 4.0).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: el permiso `BLUETOOTH_CONNECT` en Android 12+ y los permisos clásicos en Android 11 o inferior.
  - RNF-T7: idioma.
  - RNF-T8: modo sol, 56 dp, color y texto.
  - RNF-T9: números en ficheros con `Locale.ROOT`.
  - RNF-T11: no se guarda la contraseña.
  - RNF-T13: la interfaz se refresca a 5 Hz como máximo.
  - RNF-T14: atribución del IGN.
- **Exactitud de cálculo** (constitución nº 2):
  - Coordenadas UTM y altitud ortométrica ≤ 1 mm frente a pyproj/PROJ con la misma rejilla, en los husos 29, 30 y 31, también a ±3° del meridiano central.
- **Latencia**: desde que llega una GGA hasta que se ven sus coordenadas, < 300 ms.
- **Funcionamiento sin red**: sin internet (o sin NTRIP), todo excepto las correcciones sigue funcionando, incluido el geoide, que va dentro de la app.
- **Tamaño**: el APK de publicación sigue ≤ 15 MB.

## Casos límite
| Caso | RF |
|---|---|
| Permiso de Bluetooth denegado o Bluetooth apagado | RF-2, RF-3, RF-4 |
| Emlid Flow conectado al receptor (firmware 33: una sola conexión) | RF-7 |
| Receptor conectado que no envía NMEA (salida de posición sin configurar) | RF-8 |
| Frases corruptas, cortadas o con otros datos binarios intercalados | RF-11 |
| GGA sin solución (campos vacíos) | RF-12, RF-13 |
| GST que deja de llegar | RF-17 |
| Credenciales incorrectas o punto de montaje inexistente | RF-21, RF-22 |
| Sin internet | RF-23 |
| El caster deja de enviar o corta | RF-24 |
| NTRIP sin receptor conectado | RF-28 |
| Altura del jalón vacía, negativa, mayor de 10 m o con coma decimal | RF-31 (la coma decimal se acepta) |
| Punto en Canarias o fuera de España | RF-37, RF-39 |
| Solución no fija | RF-40 |
| Correcciones en otro datum (por ejemplo, ITRF) | RF-41 |
| Muchas sesiones acumuladas | RF-44 |

## Fuera de alcance
- La conexión en segundo plano, la reconexión automática y el servicio en primer plano → S004.
- Los perfiles NTRIP, el sourcetable, la contraseña cifrada y NTRIP sobre TLS → S007.
- Las frases GSA, GSV, RMC, ZDA, EBP y ETC, y el simulador por reproducción → S003.
- La compensación de inclinación (ETC) → S015. Aquí el jalón se supone vertical.
- La conexión por Wi-Fi/TCP → S020.
- Canarias (REGCAN95, UTM 27/28, geoide de Canarias), el huso forzado, el factor de escala y la convergencia → S008.
- Proyectos, guardar puntos, el mapa y el skyplot → S006, S010, S009 y S014.
- Elegir otro sistema de coordenadas o datum → S008 y S031–S034.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita y `rf_report` no da huérfanos.
- Los tests de referencia geodésicos (UTM y geoide frente a pyproj) pasan con una tolerancia de ≤ 1 mm.
- Una sesión NMEA de prueba reproducida de principio a fin da las coordenadas esperadas.
- Demo con el RS3 real: conexión, NTRIP, solución fija, coordenadas en pantalla y sesión compartida.
- **Puerta G0** (campo, `docs/validacion-campo/protocolo.md`): FIX sobre un vértice REGENTE; las coordenadas de la pantalla coinciden a 1 mm con pyproj aplicado a la GGA grabada de la sesión, y la diferencia con la reseña queda dentro de la tolerancia (c).

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementar esa parte.
- [SUPOSICIÓN: el desplazamiento de la base del RS3 a su centro de referencia es de 0,134 m, según la especificación ETC_rev_3 de Emlid. Se confirma en G0 comparando con Emlid Flow sobre el mismo punto.]
- [SUPOSICIÓN: se envía la GGA al caster cada 5 s siempre, aunque el punto de montaje no la necesite; los casters de estación única la ignoran.]
- [SUPOSICIÓN: la contraseña NTRIP se escribe en cada sesión hasta S007, que la guardará cifrada.]
- [SUPOSICIÓN: las coordenadas se tratan como ETRS89 (redes NTRIP españolas). El datum por proyecto llega en S006.]
