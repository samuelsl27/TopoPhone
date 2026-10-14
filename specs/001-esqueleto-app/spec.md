# Spec 001 — Esqueleto de la app

> Hito: H0 · Depende de: — · Estado: aprobada (revisada tras la clarificación QA)

## Contexto y objetivo
TopoPhone se construirá en 42 specs encadenadas, implementadas casi siempre por un agente de IA, una tarea cada vez. Antes de la primera función de topografía hacen falta dos cosas.

La primera es una app instalable con su estructura visible: las tres secciones, los temas legibles a pleno sol, los dos idiomas, una pantalla de ajustes que cada spec irá ampliando y la información legal.

La segunda es una red de seguridad automática que impida avanzar con tests en rojo, con requisitos sin test o con lógica de dominio atada a Android. Esa red es lo que hace cumplir la constitución (nº 6 y nº 8) a lo largo de todo el proyecto.

Como el repositorio será público y las versiones se distribuirán como APK en GitHub Releases, esta spec también fija cómo se publican las versiones y qué no puede entrar nunca en el repositorio.

## Usuarios / actores
- **Topógrafo** (usuario final): instala la app y se orienta en ella, en el campo o en la oficina.
- **Desarrollador o agente de IA**: contribuye al código siguiendo el ciclo SDD; puede trabajar desde un fork.
- **Mantenedor**: publica las versiones y custodia la clave de firma.
- **Integración continua** (GitHub Actions): comprueba cada cambio y publica las versiones.

## Historias de usuario
- H1: Como topógrafo quiero instalar TopoPhone y ver sus tres secciones (Levantamiento, Receptor, Ajustes) para orientarme desde el primer arranque.
- H2: Como topógrafo quiero elegir el tema y alternar rápido el modo sol para leer la pantalla a pleno sol.
- H3: Como topógrafo quiero usar la app en español o en inglés.
- H4: Como topógrafo quiero consultar la versión, la licencia y las atribuciones de la app.
- H5: Como desarrollador quiero que cada cambio se compruebe automáticamente, para que ningún requisito quede sin test ni se rompa la separación entre la lógica y la plataforma.
- H6: Como mantenedor quiero publicar versiones firmadas en GitHub Releases sin exponer nada que no deba ser público.

## Requisitos funcionales (criterios de aceptación en EARS)

### Instalación y navegación (H1)
- RF-1: EL SISTEMA se instalará y ejecutará en Android 8.0 (API 26) o superior con el nombre visible "TopoPhone" y el identificador `es.topophone.app`.
- RF-2: CUANDO la app arranque en frío desde el lanzador, EL SISTEMA mostrará la sección Levantamiento y una navegación principal con tres secciones: Levantamiento, Receptor y Ajustes.
- RF-3: CUANDO el usuario elija una sección de la navegación principal, EL SISTEMA mostrará esa sección y la marcará como activa en la navegación.
- RF-4: MIENTRAS una sección no tenga funciones implementadas, EL SISTEMA mostrará en ella un estado vacío con el nombre de la sección y una frase que describe lo que contendrá.
- RF-5: CUANDO el usuario pulse "Atrás" en Receptor o en Ajustes, EL SISTEMA volverá a Levantamiento.
- RF-6: CUANDO el usuario pulse "Atrás" en Levantamiento, EL SISTEMA saldrá de la app.
- RF-7: CUANDO el dispositivo gire entre vertical y horizontal, EL SISTEMA conservará la sección activa, la posición de desplazamiento y cualquier diálogo abierto.
- RF-8: SI Android cierra el proceso de la app mientras está en segundo plano, ENTONCES EL SISTEMA restaurará la sección activa cuando el usuario vuelva a la app desde recientes.

### Temas (H2)
- RF-9: EL SISTEMA ofrecerá en Ajustes cuatro temas (Sistema, Claro, Oscuro y Sol), con Sistema por defecto.
- RF-10: MIENTRAS el tema elegido sea Sistema, EL SISTEMA aplicará el tema claro u oscuro según el dispositivo, también cuando este cambie con la app abierta.
- RF-11: EL SISTEMA mostrará un acceso rápido al modo sol en la barra superior de las tres secciones principales.
- RF-12: CUANDO el usuario pulse el acceso rápido al modo sol, EL SISTEMA alternará entre el tema Sol y el tema que estaba activo antes.
- RF-13: MIENTRAS el tema Sol esté activo, EL SISTEMA mostrará texto oscuro sobre fondo claro sin cambiar con el modo oscuro del dispositivo.
- RF-14: MIENTRAS el tema Sol esté activo, EL SISTEMA mostrará todos los textos e iconos con un contraste ≥ 7:1 respecto a su fondo.
- RF-15: CUANDO el usuario vuelva a abrir la app, EL SISTEMA aplicará el último tema activo, tanto si se eligió en Ajustes como con el acceso rápido.

### Idioma (H3)
- RF-16: EL SISTEMA ofrecerá en Ajustes los idiomas Sistema, Español e English, con Sistema por defecto.
- RF-17: SI el idioma del dispositivo no es español ni inglés, ENTONCES EL SISTEMA mostrará la interfaz en español.
- RF-18: CUANDO el usuario cambie el idioma en cualquier versión de Android soportada, EL SISTEMA mostrará toda la interfaz en el idioma nuevo sin perder la sección activa.
- RF-19: EL SISTEMA tendrá cada texto visible traducido al español y al inglés, sin ninguna cadena sin traducir ni escrita directamente en el código.

### Acerca de y legal (H4)
- RF-20: CUANDO el usuario abra "Acerca de" en Ajustes, EL SISTEMA mostrará el nombre, la versión, el identificador del commit compilado, la licencia GPL-3.0 y el enlace al repositorio `https://github.com/samuelsl27/TopoPhone`.
- RF-21: SI la compilación no procede de una etiqueta de versión, ENTONCES EL SISTEMA mostrará la versión con el sufijo `-dev`.
- RF-22: EL SISTEMA mostrará en "Acerca de" que TopoPhone es un proyecto independiente, no afiliado a Emlid.
- RF-23: CUANDO el usuario abra "Licencias y atribuciones", EL SISTEMA mostrará cada componente de terceros incluido en el APK, también las dependencias indirectas, junto con su licencia.
- RF-24: EL SISTEMA no enviará datos de uso, errores ni analítica a ningún servicio.

### Comprobación automática de cada cambio (H5)
- RF-25: CUANDO se suba un cambio al repositorio (push o pull request, también desde un fork), la integración continua compilará la app, ejecutará todos los tests y las comprobaciones de estilo, y mostrará el resultado en el propio cambio.
- RF-26: SI algún RF de una spec en estado "implementada" no está citado por ningún test, ENTONCES el informe de trazabilidad fallará y listará esos RF.
- RF-35: MIENTRAS una spec esté "en curso", el informe de trazabilidad listará sus RF todavía sin test sin hacer fallar la comprobación.
- RF-27: SI la lógica de dominio (geodesia, GNSS, NTRIP, topografía o formatos) depende de la plataforma Android, ENTONCES la comprobación automática fallará e indicará dónde.
- RF-28: SI un test, una comprobación de estilo o el informe de trazabilidad falla, ENTONCES la integración continua marcará el cambio como no apto.
- RF-29: SI un cambio añade al repositorio ficheros PDF, almacenes de claves, claves privadas o ficheros de contraseñas, ENTONCES la integración continua marcará el cambio como no apto y nombrará los ficheros.

### Publicación (H6)
- RF-30: CUANDO se cree una etiqueta de versión con el formato `vX.Y.Z` sobre un commit que pasa todas las comprobaciones, EL SISTEMA publicará en GitHub Releases un APK firmado con la versión X.Y.Z.
- RF-31: SI la etiqueta no sigue el formato `vX.Y.Z`, ENTONCES EL SISTEMA no publicará ningún APK y marcará la ejecución como fallida.
- RF-32: SI falta la clave de firma de publicación, ENTONCES EL SISTEMA no publicará ningún APK y marcará la ejecución como fallida.
- RF-33: SI la versión de la etiqueta no es mayor que la última versión publicada, ENTONCES EL SISTEMA no publicará ningún APK y marcará la ejecución como fallida.
- RF-34: SI el certificado del APK firmado no coincide con la huella del certificado de publicación registrada en el repositorio, ENTONCES EL SISTEMA no publicará el APK y marcará la ejecución como fallida.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: plataforma.
  - RNF-T7: idioma.
  - RNF-T8: uso en campo; zonas táctiles ≥ 56 dp en todos los controles de esta spec.
  - RNF-T13: arranque en frío < 2 s en el móvil de referencia.
  - RNF-T14: GPL-3.0 y dependencias compatibles.
- Cambio de sección: < 300 ms.
- Alternar el modo sol: < 500 ms.
- Todas las pantallas de esta spec se ven sin recortes ni desplazamiento horizontal en 360 × 640 dp, en vertical y en horizontal, con una escala de fuente del sistema de hasta el 150 %. Con una escala de hasta el 200 %, todo el contenido sigue accesible desplazando la pantalla.
- La app funciona entera en modo avión. En esta spec no hace ninguna conexión de red.
- APK de publicación de esta spec: ≤ 15 MB. Las specs posteriores pueden fijar su propio límite.
- Una ejecución completa de la integración continua dura ≤ 15 min.

## Casos límite
| Caso | RF que lo cubre |
|---|---|
| Idioma del dispositivo distinto de español e inglés | RF-17 |
| Cambio de idioma en Android 8–12, sin idioma por app nativo | RF-18 |
| El sistema pasa a modo oscuro con la app abierta y el tema en Sistema | RF-10 |
| El sistema está en modo oscuro con el tema Sol activo | RF-13 |
| Giro del dispositivo con un diálogo abierto o a mitad de lista | RF-7 |
| Arranque en frío frente a vuelta desde recientes tras la muerte del proceso | RF-2, RF-8 |
| El modo sol se alterna cuando el tema previo era Sistema y luego se cierra la app | RF-12, RF-15 |
| Compilación local sin etiqueta | RF-21 |
| Etiqueta mal formada (`v1.2`, `1.2.3`) | RF-31 |
| Clave de firma ausente, también en los pull requests de forks | RF-32; RF-25 (los pull requests no necesitan la clave) |
| Etiqueta repetida o con una versión anterior | RF-33 |
| La clave de firma se ha sustituido por error | RF-34 |
| Etiqueta sobre un commit con tests en rojo | RF-30 |
| Un RF sin test en una spec en curso / implementada | RF-35 / RF-26 |
| Alguien sube un PDF de Emlid o un fichero de claves | RF-29 |
| Escala de fuente grande | RNF (150 % / 200 %) |

## Fuera de alcance
- El contenido real de las secciones Levantamiento y Receptor → S002 y siguientes.
- Los ajustes de cada función (unidades, sonidos, NTRIP…) → la spec de cada función.
- La conexión con el receptor → S002 y S004.
- El log de la app y el informe de sistema (RNF-T12) → S003.
- La disposición específica para tablet (dos paneles); en tablet se usa el diseño de móvil ampliado. No está planificada.
- Publicar en Google Play o F-Droid. No está planificado.
- Más idiomas además de español e inglés. No está planificado.
- El tema automático por sensor de luz. Descartado en la entrevista.
- Los informes de errores o la analítica remota: excluidos por la constitución (nº 4).
- La guía de primer uso del receptor → S041.

## Criterios de finalización
- Todos los RF tienen al menos un test automático en verde que los cita. Esto incluye las reglas de integración continua y de publicación (RF-25 a RF-34), que se prueban con tests de los scripts y de la definición del flujo.
- `rf_report` termina sin RF huérfanos.
- La integración continua sale en verde en un pull request del repositorio público.
- La versión `v0.0.1` está publicada en GitHub Releases, se instala en un móvil real con Android 8 o superior y `v0.0.2` se instala encima como actualización.
- Demo manual: girar la pantalla, los cuatro temas, el modo sol al aire libre, los dos idiomas y "Acerca de".

## Dudas abiertas
Ninguna. Resueltas en la entrevista:
- Repositorio público: `samuelsl27/TopoPhone` en GitHub.
- Clave de firma: la genera y custodia el mantenedor, con una copia en su gestor de contraseñas; en GitHub Actions está como secreto cifrado. La huella SHA-256 del certificado se registra en el repositorio (RF-34).
- Icono: provisional, genérico, propio (jalón con satélite) y sin parecido con el de Emlid. Se podrá sustituir más adelante.
- Móvil de referencia para los tiempos: Android 11 o superior con 4 GB de RAM (RNF-T13).
- Numeración de versiones: etiquetas `vX.Y.Z` empezando por `v0.0.1`; cada publicación incrementa el número (`v0.0.2`, `v0.0.3`…).
