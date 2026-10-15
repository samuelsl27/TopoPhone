# Spec 005 — Estructura de la interfaz

> Hito: H1 · Depende de: S004 · Estado: aprobada

## Contexto y objetivo
TopoPhone la usan topógrafos acostumbrados a Emlid Flow. Hoy la sección Receptor es una página larga con cinco tarjetas (Conexión, Estado, Correcciones NTRIP, Coordenadas y Sesión), y la barra de estado ocupa una franja fija en todas las pantallas. La organización no se parece a la de la app de referencia y obliga a desplazarse para llegar a cada función.

Esta spec reorganiza la interfaz siguiendo la organización de Emlid Flow, con textos, iconos y diseño propios (RNF-T14):
- sin conexión, Receptor muestra la lista de receptores;
- con un receptor conectado, muestra una cabecera y un menú de pantallas con un resumen de cada una;
- el estado de la solución pasa al extremo derecho de la barra superior.

No añade funciones: reubica las de S002, S003 y S004 sin perder ningún requisito. La sección Levantamiento recibe los proyectos en la spec siguiente (S006).

## Usuarios / actores
- Topógrafo en campo, con el móvil en una mano o en el soporte del jalón.
- Receptor RS3, o una sesión reproducida, como fuente de los datos.

## Historias de usuario
- H1: Como topógrafo quiero ver primero la lista de mis receptores y conectar con uno de un toque, como en la app que ya conozco.
- H2: Como topógrafo quiero que, con el receptor conectado, sus funciones estén en un menú con un resumen de cada una, para llegar a cualquiera sin desplazarme por una página larga.
- H3: Como topógrafo quiero ver el estado de la solución siempre arriba a la derecha, ocupando poco espacio.
- H4: Como topógrafo que ya usa S002 a S004 quiero encontrar todo lo que había: NTRIP, coordenadas, sesiones, reproducción y diagnósticos.

## Requisitos funcionales (criterios de aceptación en EARS)
### Lista de receptores (H1)
- RF-1: MIENTRAS no haya ningún receptor conectado, conectándose o reconectándose ni ninguna reproducción en curso, la sección Receptor mostrará la lista de receptores.
- RF-2: EL SISTEMA mostrará en la lista de receptores los dispositivos emparejados (S002/RF-1 a RF-4), el botón "Añadir receptor" (S004/RF-1) y el acceso a las sesiones grabadas.
- RF-3: EL SISTEMA señalará en la lista de receptores el último receptor con el que se conectó (S004/RF-24).
- RF-4: SI la última conexión falló o se perdió, ENTONCES EL SISTEMA mostrará encima de la lista el motivo, las causas probables y la acción que corresponda, con los textos de S002/RF-7, S002/RF-9, S004/RF-20 y S004/RF-27.

### Pantalla del receptor (H2)
- RF-5: MIENTRAS haya un receptor conectado, conectándose o reconectándose, o una reproducción en curso, la sección Receptor mostrará la pantalla del receptor en lugar de la lista.
- RF-6: EL SISTEMA mostrará en la cabecera de la pantalla del receptor el nombre del receptor, o el de la sesión reproducida, y el estado de la conexión.
- RF-7: EL SISTEMA mostrará en la cabecera las acciones del estado de la conexión: "Desconectar", "Reintentar ahora" (S004/RF-16) o los controles de la reproducción (S003/RF-37 a RF-41).
- RF-8: EL SISTEMA mostrará en la cabecera los avisos de la conexión: salida de posición desactivada (S004/RF-28), permiso de notificaciones denegado (S004/RF-6) y grabación detenida por falta de espacio (S003/RF-28).
- RF-9: EL SISTEMA mostrará bajo la cabecera un menú con cuatro entradas, en este orden: Estado, Coordenadas, Correcciones y Sesiones grabadas.
- RF-10: MIENTRAS se completen épocas, la entrada Estado mostrará como resumen el estado de la solución, con su color y su nombre, y el número de satélites usados.
- RF-11: EL SISTEMA mostrará en la entrada Coordenadas, como resumen, la altura del jalón en uso.
- RF-12: EL SISTEMA mostrará en la entrada Correcciones, como resumen, el estado del NTRIP y, mientras lleguen correcciones, su edad en segundos.
- RF-13: MIENTRAS se grabe una sesión, la entrada Sesiones grabadas mostrará como resumen "Grabando".
- RF-14: CUANDO el usuario pulse una entrada del menú, EL SISTEMA abrirá su pantalla con el contenido que indica la tabla "Ubicación de lo existente".
- RF-15: CUANDO el usuario pulse "Atrás" o la flecha de volver en una pantalla del menú, EL SISTEMA volverá a la pantalla del receptor.
- RF-16: SI la conexión termina mientras está abierta una pantalla del menú, ENTONCES EL SISTEMA la mantendrá abierta y mostrará en ella que no hay receptor.
- RF-17: CUANDO el dispositivo gire o Android restaure la app tras cerrar su proceso, EL SISTEMA conservará la pantalla que estaba abierta dentro de la sección Receptor (como S001/RF-7 y S001/RF-8).

### Indicador de estado (H3)
- RF-18: SI el ancho de la barra superior no basta para el título y el indicador de estado, ENTONCES EL SISTEMA acortará el título y mostrará el indicador completo.

El indicador de estado sustituye a la franja de S003: su posición y su contenido se fijan editando S003/RF-16 a RF-23 (ver "Cambios en documentos anteriores").

### Ubicación de lo existente (H4)
- RF-19: EL SISTEMA ofrecerá cada función de la sección Receptor de S002, S003 y S004 en el lugar que indica la tabla siguiente, sin cambiar su comportamiento.

| Hoy | Pasa a |
|---|---|
| Tarjeta Conexión sin conexión: Bluetooth, permiso, emparejados, "Añadir receptor", fallos (S002/RF-1 a RF-9, S004/RF-1 a RF-4, S004/RF-20, S004/RF-26 a RF-28) | Lista de receptores |
| Tarjeta Conexión con conexión: estado, "Desconectar", reconexión, reproducción, avisos (S002/RF-5, S002/RF-6, S002/RF-8, S002/RF-10, S003/RF-35 a RF-43, S004/RF-6, S004/RF-13 a RF-22, S004/RF-28) | Cabecera de la pantalla del receptor |
| Tarjeta Estado: solución, satélites, HDOP, edad, precisiones, fecha y hora de la época (S002/RF-12 a RF-17, S003/RF-14, S003/RF-15) | Pantalla Estado |
| Tarjeta Coordenadas: altura del jalón y coordenadas del punto del suelo (S002/RF-29 a RF-41) | Pantalla Coordenadas |
| Tarjeta Correcciones NTRIP (S002/RF-18 a RF-28) | Pantalla Correcciones |
| Tarjeta Sesión: compartir la última sesión y abrir la lista (S002/RF-43, S003/RF-29 a RF-34) | Pantalla Sesiones grabadas, desde la lista de receptores y desde el menú |
| Franja de estado: edad de las correcciones | Indicador de estado sin la edad; la edad, en la entrada y la pantalla Correcciones (RF-12) y en la pantalla Estado (S002/RF-13) |
| Franja de estado: "Grabando" e inclinación | Cabecera de la pantalla del receptor (S003/RF-20, S003/RF-22) |

## Requisitos no funcionales
- Transversales que aplican: RNF-T7, RNF-T8, RNF-T13 y RNF-T14.
- Cuando cambia el estado de la conexión, la sección Receptor pasa de la lista a la pantalla del receptor, o al revés, en menos de 1 s.
- Cada entrada del menú y cada fila de la lista de receptores mide al menos 56 dp de alto (RNF-T8).
- El indicador de estado se muestra completo en una pantalla de 360 dp de ancho con la fuente del sistema al 130 %.

## Casos límite
| Caso | RF |
|---|---|
| La conexión termina con una pantalla del menú abierta (desde la notificación, por pérdida o al rendirse la reconexión) | RF-16 |
| Reconexión en curso: la pantalla del receptor se mantiene | RF-5 |
| Fallo de la conexión o conexión perdida | RF-4 |
| Bluetooth apagado o sin permiso | RF-2 |
| Reproducción en curso: Correcciones no está disponible | RF-12 (resumen) y S003 |
| Pantalla estrecha con fuente grande | RF-18 y RNF de la spec |
| Giro o cierre del proceso con una pantalla del menú abierta | RF-17 |

## Fuera de alcance
- Lista de proyectos, crear, abrir y editar proyectos, y la vista de proyecto en Levantamiento → S006. Hasta entonces Levantamiento conserva su estado vacío (S001/RF-4).
- El mapa de la vista de proyecto → S009.
- Los perfiles NTRIP dentro de Correcciones → S007.
- La pantalla Estado detallada (skyplot, SNR, base y línea base) → S014.
- La altura del jalón en el recolector → S010.
- "Mis bibliotecas de códigos", "Mis capas" y "Mis SC" en Ajustes → S022, S035 y S034.
- Los ajustes del propio receptor (salida de base, Wi-Fi, registro en el receptor) → S042 (guía) y S043 (experimental).
- Conectar por Wi-Fi desde la lista de receptores → S020.
- Copiar iconos, textos, colores o el diseño visual de Emlid Flow → nunca (RNF-T14).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Los tests de S002, S003 y S004 siguen en verde con la interfaz nueva.
- Demo en el emulador con una reproducción: lista de receptores → reproducir una sesión → pantalla del receptor con el menú → recorrer las cuatro pantallas → detener → lista.
- Demo con el RS3 real: conectar desde la lista, recorrer el menú, desconectar desde la cabecera.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: Correcciones solo se abre desde la pantalla del receptor, como en Flow; los datos del caster no se pueden rellenar antes de conectar un receptor.]
- [SUPOSICIÓN: Coordenadas es una entrada propia del menú hasta que llegue el recolector (S010); entonces la altura del jalón pasa al recolector.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`:
  - Se añade S005 estructura-interfaz y se renumeran las specs siguientes para que el número siga siendo el orden: proyectos pasa de 007 a 006, ntrip-perfiles-y-sourcetable de 005 a 007, coordenadas-espana de 006 a 008, y de la antigua 008 en adelante cada una suma 1 (de 42 specs se pasa a 43). Se actualizan todas las referencias `S0NN` de `docs/`, `specs/`, `tools/` y de los comentarios del código.
  - S006 proyectos depende de S005; su vista de proyecto muestra la lista en Levantamiento, y hasta S008 el SC del proyecto se limita a ETRS89/UTM con EGM08-REDNAP.
  - S008 coordenadas-espana añade los demás SC de España a los proyectos.
- `specs/003-flujo-nmea-y-simulador/spec.md`:
  - En RF-16 a RF-23, "la barra de estado" pasa a ser "el indicador de estado".
  - RF-16: "EL SISTEMA mostrará el indicador de estado en el extremo derecho de la barra superior de las secciones Levantamiento, Receptor y Ajustes."
  - RF-18: "MIENTRAS se completen épocas, el indicador de estado mostrará el estado de la solución con su color y su nombre y el número de satélites usados." (sin la edad de las correcciones)
  - RF-20: "MIENTRAS se grabe una sesión, la cabecera de la pantalla del receptor mostrará el indicador «Grabando»."
  - RF-22: "MIENTRAS lleguen frases ETC, la cabecera de la pantalla del receptor mostrará el estado de la inclinación: Apagada, Error, Configuración, Alineación o Compensando."
  - RF-14 y RF-15: "en la tarjeta Estado" pasa a ser "en la pantalla Estado".
  - RF-35: "en la tarjeta Conexión" pasa a ser "en la cabecera de la pantalla del receptor".
- `specs/004-conexion-bluetooth-robusta/spec.md`: en RF-23, "la barra de estado" pasa a ser "el indicador de estado".
- `docs/glosario.md`: "Barra de estado" pasa a ser "Indicador de estado"; se añaden "Lista de receptores", "Pantalla del receptor" y "Cabecera (de la pantalla del receptor)".
