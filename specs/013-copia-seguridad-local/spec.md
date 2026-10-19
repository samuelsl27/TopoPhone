# Spec 013 — Copia de seguridad local y paquetes de proyecto

> Hito: H1 · Depende de: S012 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
TopoPhone no tiene cuentas ni nube (constitución nº 4). Si el móvil se rompe, se pierde o se reinstala la app, todo el trabajo de campo desaparece con él. Además, no hay forma de pasar un proyecto a un compañero o a otro móvil: el CSV de S012 lleva los puntos, pero no el proyecto ni su historial. Esta spec cubre las dos necesidades sin salir del móvil:
- una **copia de seguridad completa**, que se guarda donde quiera el usuario y se restaura entera;
- **copias automáticas** en una carpeta elegida por el usuario, que puede ser una tarjeta, una memoria USB o la carpeta de un proveedor de almacenamiento del móvil;
- **paquetes de proyecto** (`.topoproj`) para llevar un proyecto completo de un móvil a otro.

La constitución nº 1 exige que el dato de campo "siempre pueda exportarse o respaldarse" y nunca se pierda. Lo que entra en una copia tiene que volver exactamente igual: ni un punto de menos ni una coordenada redondeada.

## Usuarios / actores
- Topógrafo que quiere no perder el trabajo si le pasa algo al móvil.
- Topógrafo que cambia de móvil o reinstala la app.
- Compañero que recibe un proyecto para seguir midiendo o revisarlo.

## Historias de usuario
- H1: Como topógrafo quiero guardar una copia de todo lo que tengo en la app para recuperarlo si pierdo el móvil.
- H2: Como topógrafo quiero restaurar esa copia en un móvil nuevo y tenerlo todo como estaba, y poder deshacerlo si me equivoco de copia.
- H3: Como topógrafo quiero que las copias se hagan solas mientras trabajo, sin acordarme.
- H4: Como topógrafo quiero mandar un proyecto completo a un compañero y que él me lo devuelva con sus puntos.

## Requisitos funcionales (criterios de aceptación en EARS)
### Copia completa (H1)
- RF-1: CUANDO el usuario pulse "Crear copia de seguridad" en Ajustes, EL SISTEMA pedirá con el selector de ficheros del móvil dónde guardarla y escribirá allí la copia completa.
- RF-2: EL SISTEMA incluirá en la copia completa las carpetas y los proyectos, también los de la papelera de proyectos, con sus puntos, su historial de cambios y su papelera de puntos, los ajustes de la app y los perfiles NTRIP.
- RF-3: EL SISTEMA no incluirá en ninguna copia ni paquete las contraseñas NTRIP (RNF-T11), las sesiones grabadas, la caché del mapa ni los ajustes propios de este móvil (el receptor asociado y la carpeta de las copias automáticas).
- RF-4: EL SISTEMA propondrá como nombre de la copia "TopoPhone_<AAAAMMDD-HHMM>.zip" y como nombre del paquete "<proyecto>_<AAAAMMDD-HHMM>.topoproj", con los caracteres que no valen en un nombre de fichero cambiados por "_" (como en S012/RF-19).

### Restaurar (H2)
- RF-5: CUANDO el usuario elija "Restaurar copia de seguridad" en Ajustes y un fichero, EL SISTEMA mostrará la fecha de la copia y cuántos proyectos y puntos tiene, y pedirá confirmación avisando de que sustituirá todos los datos del móvil.
- RF-6: CUANDO el usuario confirme la restauración, EL SISTEMA guardará antes, en el propio móvil, una copia completa del estado actual.
- RF-7: CUANDO esa copia del estado actual esté guardada, EL SISTEMA sustituirá las carpetas, los proyectos, los puntos, los ajustes y los perfiles NTRIP por los de la copia.
- RF-8: SI la restauración no puede terminar, ENTONCES EL SISTEMA dejará todos los datos como estaban antes de empezar e indicará que no se ha restaurado.
- RF-9: CUANDO el usuario pulse "Deshacer la restauración" en Ajustes, EL SISTEMA pedirá confirmación y devolverá los datos al estado guardado antes de la última restauración.
- RF-10: SI el fichero elegido no es una copia o un paquete de TopoPhone, o está dañado o incompleto, ENTONCES EL SISTEMA no cambiará nada e indicará el motivo.
- RF-11: SI la copia o el paquete es de una versión de los datos más reciente que la de la app, ENTONCES EL SISTEMA no cambiará nada y pedirá actualizar la app.
- RF-12: CUANDO se restaure una copia o se importe un paquete de una versión de los datos anterior, EL SISTEMA lo pasará a la versión actual sin perder datos (RNF-T6).
- RF-13: CUANDO el usuario se conecte por primera vez con un perfil NTRIP restaurado que tenía contraseña, EL SISTEMA se la pedirá, sin perder el resto del perfil (como en S007/RF-6).

### Copias automáticas (H3)
- RF-14: EL SISTEMA permitirá en Ajustes elegir la carpeta de las copias automáticas, lo que las activa, o quitarla, lo que las desactiva (RNF-T5).
- RF-15: MIENTRAS las copias automáticas estén activas, EL SISTEMA hará una copia completa en la carpeta cuando haya cambios sin copiar en los proyectos, los puntos, los ajustes o los perfiles NTRIP y haya pasado al menos 1 h desde la anterior, también en el momento en que la app pase a segundo plano.
- RF-16: EL SISTEMA conservará en la carpeta las 24 copias automáticas más recientes y borrará las más antiguas, sin tocar ningún otro fichero de la carpeta.
- RF-17: EL SISTEMA mostrará en Ajustes la fecha y hora de la última copia automática que se ha guardado bien.
- RF-18: SI una copia automática no se puede guardar (carpeta borrada, permiso retirado, sin espacio), ENTONCES EL SISTEMA lo avisará en la lista de proyectos y en Ajustes, y lo volverá a intentar en la siguiente ocasión.
- RF-19: MIENTRAS no haya carpeta de copias automáticas y exista al menos un proyecto, EL SISTEMA mostrará en la lista de proyectos un aviso con un botón para elegirla.
- RF-20: CUANDO el usuario cierre ese aviso, EL SISTEMA no lo volverá a mostrar hasta 7 días después.

### Paquetes de proyecto (H4)
- RF-21: CUANDO el usuario elija "Exportar paquete" en el menú de un proyecto, EL SISTEMA creará un paquete con el proyecto, sus puntos, su historial de cambios y su papelera de puntos, y ofrecerá guardarlo con el selector de ficheros o compartirlo con otras apps.
- RF-22: CUANDO el usuario elija "Importar paquete" en el menú de la lista de proyectos y un fichero, EL SISTEMA añadirá el proyecto del paquete con todo su contenido.
- RF-23: SI el proyecto del paquete ya está en el móvil, ENTONCES EL SISTEMA preguntará si se sustituye o se importa como un proyecto nuevo.
- RF-24: CUANDO el usuario elija sustituirlo, EL SISTEMA moverá el proyecto del móvil a la papelera de proyectos (S006/RF-22) y añadirá el del paquete.
- RF-25: SI el usuario elige importarlo como proyecto nuevo, o ya hay otro proyecto con su nombre, ENTONCES EL SISTEMA lo añadirá como un proyecto distinto llamado "<nombre> (importado)" o, si ya existe, "<nombre> (importado 2)", "(importado 3)"…

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: guardar con el selector de ficheros o compartir; importar validando por el contenido y no por el tipo; carpeta de las copias automáticas con el permiso persistido.
  - RNF-T6: migraciones sin pérdida de datos; la copia automática de Android en la nube sigue desactivada.
  - RNF-T7 y RNF-T8 (textos en recursos, 56 dp, TalkBack).
  - RNF-T10: las fechas de la copia van en UTC y se muestran en hora local.
  - RNF-T11: ninguna copia ni paquete lleva contraseñas.
  - RNF-T13: crear, restaurar e importar no bloquean la interfaz.
- Constituciones nº 1 y nº 2: tras restaurar una copia o importar un paquete, cada dato es idéntico al que se guardó; las coordenadas, sin ningún redondeo.
- Cada copia refleja un estado coherente de los datos: nunca lleva un punto a medio guardar, aunque se esté recolectando mientras se hace.
- Una copia automática en curso no impide guardar un punto.
- Con 10 000 puntos, la copia completa se escribe en menos de 5 s y se restaura en menos de 10 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| El usuario cancela el selector de ficheros | RF-1, RF-21 (no se escribe nada) |
| Fichero que no es de TopoPhone, ZIP dañado o cortado | RF-10 |
| Copia o paquete de una app más nueva | RF-11 |
| Copia o paquete de una app más antigua | RF-12 |
| La restauración falla a medias (sin espacio, app cerrada) | RF-8 |
| Restaurar la copia equivocada | RF-9 |
| Restaurar en otro móvil: receptor sin asociar y sin carpeta de copias | RF-3 (se asocian de nuevo) |
| Perfil NTRIP restaurado sin contraseña | RF-13 |
| Carpeta de copias borrada, tarjeta retirada o permiso revocado | RF-18 |
| Carpeta de copias con otros ficheros del usuario | RF-16 |
| Recolectar mientras se hace una copia automática | RNF (estado coherente, no impide guardar) |
| Sin cambios desde la última copia | RF-15 (no se hace otra) |
| Paquete de un proyecto que ya está en el móvil | RF-23 a RF-25 |
| Paquete de otro proyecto con el mismo nombre | RF-25 |
| Paquete importado varias veces como nuevo | RF-25 |
| Proyecto sin puntos | RF-2, RF-21 (se copia igual) |

## Fuera de alcance
- Log de la app e informe de sistema (RNF-T12) → S014.
- Sesiones grabadas en la copia → no previsto (se comparten una a una, S003).
- Fotos, bibliotecas de códigos, SC personalizados, capas y ficheros importados en la copia y en el paquete → S017 (fotos), S023 (códigos), S035 (SC personalizados), S036 (capas WMS) y S040 (DXF) añadirán su contenido (números de la hoja de ruta ya renumerada).
- Restaurar solo algunos proyectos de una copia → no previsto (se importan con paquetes).
- Fusionar los puntos de dos versiones del mismo proyecto → no previsto.
- Cifrar la copia con una contraseña → no previsto.
- Copias en la nube o con cuentas → no previsto (constitución nº 4; el usuario puede elegir como carpeta la de un proveedor de almacenamiento del móvil).
- Exportar o importar los perfiles NTRIP sueltos → no previsto (viajan en la copia completa, sin contraseñas).
- Abrir copias o proyectos de otras apps de topografía → no previsto.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de ida y vuelta: una base de datos con proyectos de todos los SC, puntos medidos, manuales, editados, ocultos y en la papelera, carpetas, ajustes y perfiles NTRIP sale idéntica tras copiarla y restaurarla, y tras exportar e importar cada proyecto en paquete.
- Test de restauración desde copias de cada versión anterior de los datos.
- Demo en el emulador:
  - crear una copia, borrar un proyecto, restaurar la copia y deshacer la restauración;
  - activar las copias automáticas en una carpeta, recolectar puntos y ver aparecer la copia;
  - exportar un paquete, importarlo como nuevo y después sustituyendo al original.
- La puerta G1 pasa a la S014, que cierra el hito H1.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: la copia completa es un fichero ZIP, para que se pueda abrir con cualquier programa; el paquete usa la extensión `.topoproj`.]
- [SUPOSICIÓN: las copias automáticas se llaman "TopoPhone_auto_<AAAAMMDD-HHMM>.zip"; RF-16 solo borra los ficheros con ese nombre.]
- [SUPOSICIÓN: "Deshacer la restauración" está disponible hasta la siguiente restauración, que guarda un nuevo estado anterior.]
- [SUPOSICIÓN: "el proyecto ya está en el móvil" significa el mismo proyecto (el que se exportó en el paquete), no otro con el mismo nombre; un proyecto en la papelera no cuenta.]
- [SUPOSICIÓN: el proyecto importado se añade fuera de las carpetas; el paquete no lleva la carpeta.]
- [SUPOSICIÓN: el paquete no lleva ajustes de la app ni perfiles NTRIP; solo el proyecto.]
- [SUPOSICIÓN: las opciones de copia y restauración están en una sección "Copias de seguridad" de Ajustes; "Exportar paquete" en el menú de cada proyecto, junto a "Exportar CSV", e "Importar paquete" en el menú de la lista de proyectos (S006).]
- [SUPOSICIÓN: el aviso de RF-19 desaparece en cuanto se elige una carpeta.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`:
  - fila S013: "ZIP de copia completa con restauración y deshacer, copias automáticas en una carpeta SAF (cada hora con cambios, las 24 últimas), paquetes `.topoproj` para importar y exportar entre dispositivos, perfiles NTRIP sin contraseñas";
  - fila nueva **S014 log-e-informe-de-sistema** (depende de S013): "Log de la app (rotativo, de tamaño limitado, sin secretos) e informe de sistema exportable con el log, las sesiones recientes y los datos de la app y del móvil, sin contraseñas (RNF-T12). **Puerta G1**";
  - las specs S014 a S043 pasan a ser S015 a S044, con sus dependencias, y la puerta G2 pasa de "S021" a "S022".
- Las referencias `S014` a `S043` de `docs/inventario-funcional.md`, `docs/glosario.md`, `docs/arquitectura.md`, `docs/validacion-campo/protocolo.md`, `docs/referencias/emlid/enlaces.md`, las specs y planes de S001 a S012 y los comentarios del código suben un número.
- `docs/inventario-funcional.md`, informe de sistema: de "S042 (informe propio, RNF-T12)" a "S014 (informe propio, RNF-T12)".
- `specs/001-esqueleto-app/spec.md` y `specs/003-flujo-nmea-y-simulador/spec.md`, fuera de alcance: el log de la app y el informe de sistema pasan de → S013 a → S014.
- `specs/007-ntrip-perfiles-y-sourcetable/spec.md`, fuera de alcance: "Copia y traspaso de los perfiles a otro dispositivo (sin contraseñas) → S013 (en la copia completa)".
- `docs/glosario.md`: se añaden "Restaurar" (`restore`), "Copia automática" (`AutoBackup`) y "Deshacer la restauración" (`undoRestore`).
