# Spec 014 — Log de la app e informe de sistema

> Hito: H1 · Depende de: S013 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Cuando algo falla en el campo (el receptor se desconecta, el caster rechaza la conexión, un punto no se guarda, una restauración no termina), hoy no queda rastro de qué hacía la app ni por qué falló. Las sesiones grabadas (S003) guardan lo que llega del receptor, pero no lo que hace la app. Esta spec añade:
- un **log de la app**: una anotación de cada evento y cada error, con su fecha y hora, sin secretos;
- un **informe de sistema** que el usuario crea en un momento y guarda o comparte: el log, las sesiones recientes y los datos de la app y del móvil, sin contraseñas.

Con esto el usuario puede contar qué pasó, el desarrollador puede reproducirlo y la puerta G1 (el primer trabajo real con la app) se documenta con datos, no de memoria (RNF-T12).

## Usuarios / actores
- Topógrafo que ha tenido un fallo en el campo y quiere contarlo.
- Desarrollador que recibe el informe y reproduce el fallo con las sesiones.

## Historias de usuario
- H1: Como topógrafo quiero que la app anote qué hace y qué falla para poder explicar un problema sin recordarlo todo.
- H2: Como topógrafo quiero crear en un momento un informe con todo lo necesario y mandarlo o guardarlo, sin que salgan mis contraseñas.

## Requisitos funcionales (criterios de aceptación en EARS)
### Log de la app (H1)
- RF-1: EL SISTEMA anotará en el log de la app, con su fecha y hora, las conexiones y desconexiones del receptor, las reproducciones de sesiones y sus fallos.
- RF-2: EL SISTEMA anotará en el log los cambios de estado de la conexión NTRIP y sus fallos.
- RF-3: EL SISTEMA anotará en el log cada punto guardado, con su nombre, su proyecto y su solución.
- RF-4: EL SISTEMA anotará en el log cada copia de seguridad, restauración, deshacer e importación de un paquete, con su resultado.
- RF-5: EL SISTEMA anotará en el log cada aviso y cada error que se muestre al usuario, con su causa.
- RF-6: SI la app se cierra por un error inesperado, ENTONCES EL SISTEMA lo anotará en el log con el lugar del código donde ocurrió.
- RF-7: EL SISTEMA no anotará en el log contraseñas, usuarios NTRIP ni coordenadas de los puntos (RNF-T11).
- RF-8: EL SISTEMA conservará el log hasta el tamaño elegido en Ajustes, entre 5 y 50 MB, y borrará lo más antiguo cuando lo supere.
- RF-9: CUANDO el usuario cambie en Ajustes el tamaño del log, EL SISTEMA aplicará el nuevo límite en ese momento.

### Informe de sistema (H2)
- RF-10: CUANDO el usuario pulse "Crear informe de sistema" en Ajustes, EL SISTEMA ofrecerá guardarlo con el selector de ficheros o compartirlo con otras apps.
- RF-11: EL SISTEMA incluirá en el informe el log de la app completo.
- RF-12: EL SISTEMA incluirá en el informe la sesión en curso y las sesiones grabadas más recientes que quepan, entre todas, en 25 MB.
- RF-13: EL SISTEMA incluirá en el informe la versión de la app, el modelo del móvil, la versión de Android, la zona horaria, el espacio libre, los permisos concedidos, si la app está exenta de la optimización de batería y el nombre del último receptor conectado.
- RF-14: EL SISTEMA incluirá en el informe los ajustes de la app y, de cada perfil NTRIP, el servidor, el puerto, si usa TLS y el punto de montaje.
- RF-15: EL SISTEMA no incluirá en el informe contraseñas ni usuarios NTRIP (RNF-T11).
- RF-16: EL SISTEMA propondrá como nombre del informe "TopoPhone_informe_<AAAAMMDD-HHMM>.zip", con la fecha y hora locales.
- RF-17: SI el informe no se puede escribir, ENTONCES EL SISTEMA indicará que no se ha guardado y no dejará un fichero a medias.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con el selector de ficheros o compartir mediante FileProvider.
  - RNF-T7 y RNF-T8 (textos de la interfaz en recursos, 56 dp, TalkBack).
  - RNF-T10: las anotaciones llevan la hora UTC; el informe dice la zona horaria del móvil.
  - RNF-T11: ni el log ni el informe llevan contraseñas.
  - RNF-T12: esta spec completa el diagnóstico (las sesiones son de S003).
  - RNF-T13: anotar en el log no bloquea la interfaz ni retrasa el guardado de un punto.
- Lo anotado hasta 1 s antes de un cierre inesperado de la app está en el log.
- Con 25 MB de sesiones, el informe se crea en menos de 10 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Primera vez que se abre la app (log vacío) | RF-11 (el informe lleva un log vacío) |
| El log llega al tamaño elegido | RF-8 |
| Se reduce el tamaño del log por debajo de lo que ocupa | RF-9 |
| Sin sesiones grabadas | RF-12 (el informe va sin sesiones) |
| La sesión más reciente ocupa por sí sola más de 25 MB | RF-12 (ver "Dudas abiertas") |
| Reproducción en curso (no se graba sesión, S003/RF-45) | RF-1, RF-12 |
| El usuario cancela el selector de ficheros | RF-10 (no se escribe nada) |
| Sin espacio o sitio no disponible al guardar | RF-17 |
| Error del caster por usuario o contraseña | RF-2, RF-7 (se anota el rechazo, sin el usuario) |
| Cierre inesperado en mitad de una medida | RF-6 |

## Fuera de alcance
- Ver el log dentro de la app, con o sin filtros → no previsto (se lee en el informe).
- Anotar cada época o cada frase NMEA en el log → no previsto (son las sesiones, S003).
- Proyectos y puntos en el informe → no previsto (se comparten con los paquetes, S013).
- Enviar el informe a un servidor o automáticamente → no previsto (constitución nº 4).
- Informe del propio receptor (FSR/SSR de Reach Panel) → guía de S043.
- Batería, número de serie y firmware del receptor → S043 (perfil de equipo) y S044 (módulo experimental).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de que ni el log ni el informe contienen la contraseña ni el usuario de un perfil NTRIP usado en una conexión rechazada.
- Demo en el emulador:
  - conectar con una reproducción, guardar un punto, hacer una copia y provocar un error;
  - crear el informe, guardarlo y comprobar en el ordenador su contenido (log, sesiones, datos del móvil y de la app);
  - cambiar el tamaño del log.
- Puerta G1 (campo, del usuario): los ensayos a, b, c, f (CSV) y g del protocolo; el acta lleva el informe de sistema.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: el tamaño del log es de 10 MB por defecto.]
- [SUPOSICIÓN: si la sesión más reciente ocupa por sí sola más de 25 MB, el informe lleva su parte más reciente hasta 25 MB.]
- [SUPOSICIÓN: las sesiones importadas (S003/RF-33) no entran en el informe; solo las grabadas en este móvil.]
- [SUPOSICIÓN: el informe es un fichero ZIP con el log y los datos de la app y del móvil en texto, legibles en el ordenador, y las sesiones tal como se graban.]
- [SUPOSICIÓN: las anotaciones del log van en español y no cambian con el idioma de la app.]
- [SUPOSICIÓN: el log y el informe solo se crean en el móvil; el log no viaja en la copia de seguridad (S013), y el tamaño del log sí, como el espacio para sesiones.]
- [SUPOSICIÓN: las opciones están en una sección "Diagnóstico" de Ajustes: "Crear informe de sistema" y "Tamaño del log".]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S014: "Log de la app (rotativo, de 5 a 50 MB elegidos en Ajustes, sin secretos) e informe de sistema exportable con el log, las sesiones recientes (hasta 25 MB) y los datos de la app y del móvil, sin contraseñas ni usuarios NTRIP (RNF-T12). **Puerta G1**".
- `docs/validacion-campo/protocolo.md`: la puerta G1 pasa de "(S013)" a "(S014)", y el acta lleva el informe de sistema.
- `docs/glosario.md`: se añaden "Log de la app" (`AppLog`) e "Informe de sistema" (`SystemReport`).
