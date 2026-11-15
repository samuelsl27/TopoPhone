# Spec 043 — Receptor guiado

> Hito: H7 · Depende de: S016 (además, de S015, S021, S022, S046 y S005; ver Cambios) · Estado: spec (aprobada el 15/11/2026)

## Contexto y objetivo
TopoPhone no configura el receptor: no existe una API oficial para ello y la constitución (nº 5) prohíbe apoyarse en una no documentada. Pero la configuración del receptor (salida de posición, frecuencia, mensajes de la base, inclinación) es donde más falla el trabajo de campo, y hoy el usuario tiene que saberse de memoria qué activar y dónde. Muchas de esas cosas se pueden comprobar sin tocar el receptor, mirando lo que llega por NMEA y RTCM.

Esta spec ayuda al usuario a dejar el receptor bien configurado sin automatizarlo: listas de configuración por tarea, comprobaciones previas que usan solo lo que el receptor ya emite, la interfaz web oficial del receptor dentro de la app y un perfil de equipo con sus datos. Todo con interfaces documentadas.

## Usuarios / actores
- Topógrafo que prepara un receptor antes de salir al campo o que busca por qué no llegan datos.

## Historias de usuario
- H1: Como topógrafo quiero una lista de pasos para dejar el receptor listo para cada tarea, y tacharla.
- H2: Como topógrafo quiero que la app compruebe lo que pueda de esa configuración con los datos que llegan.
- H3: Como topógrafo quiero abrir la interfaz web del receptor sin salir de TopoPhone.
- H4: Como topógrafo quiero guardar el modelo, el número de serie y el firmware de mi receptor y que consten en cada punto.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Lista de configuración**: pasos ordenados con el ajuste que hay que poner en el receptor y dónde; los comprobables llevan una comprobación asociada.
- **Comprobación previa**: resultado "Correcto", "Aviso" o "Fallo" de mirar los datos del receptor en las últimas épocas; nunca impide medir (el control de precisión es de S010).
- **Perfil de equipo**: modelo, número de serie y versión de firmware de un receptor, escritos por el usuario. En la interfaz es la entrada «Datos del receptor», para no confundirla con «Antena y jalón» (S046).
- **Receptor real**: uno Bluetooth o Wi-Fi (S021); el GNSS del móvil y el receptor virtual (S022) no lo son.

### Listas de configuración (H1)
- RF-1: EL SISTEMA ofrecerá en el menú de la pantalla del receptor (S005/RF-9) una entrada "Guía" con las listas "Rover con NTRIP de la app", "Rover por Wi-Fi", "Rover con inclinación" y "Base en un punto conocido".
- RF-2: EL SISTEMA mostrará en cada paso qué ajuste cambiar y en qué pantalla del receptor, con textos propios de TopoPhone, e indicará que se redactaron para el firmware estable de 2026, sin número de versión, y que con otro firmware pueden diferir.
- RF-3: CUANDO el usuario marque un paso, EL SISTEMA lo recordará para ese receptor, y permitirá desmarcarlo y reiniciar la lista.
- RF-4: EL SISTEMA mostrará en cada lista cuántos pasos están hechos y cuántos comprobados por la app.

### Comprobaciones previas (H2)
- RF-5: CUANDO el usuario pulse "Comprobar" con un receptor real conectado, una reproducción o el receptor virtual, EL SISTEMA analizará las épocas de los últimos 10 s y mostrará una comprobación por paso comprobable.
- RF-6: EL SISTEMA comprobará, con lo que llegue: frecuencia de la GGA (aviso por debajo de 1 Hz, fallo si no llega), presencia de GST, GSV, ZDA y RMC, tipo de solución, edad de las correcciones, y que las épocas lleguen con la hora UTC del receptor.
- RF-7: DONDE el receptor envíe ETC, EL SISTEMA comprobará su estado y aviso (S016) y que el modo de inclinación del receptor esté elegido y comprobado (S016/RF-2, RF-10).
- RF-8: DONDE haya correcciones de un NTRIP, EL SISTEMA comprobará que llegan mensajes de posición de la base (RTCM 1005/1006), de observaciones y, si el receptor las usa, de GLONASS, y que coincide el datum de las correcciones con el del proyecto (S006).
- RF-9: CUANDO una comprobación falle, EL SISTEMA mostrará la causa probable y el paso de la lista que la resuelve.
- RF-10: SI el receptor no envía datos, ENTONCES EL SISTEMA dejará las comprobaciones como "Sin datos" y remitirá al diagnóstico de S004/RF-27 y RF-28.
- RF-11: EL SISTEMA no escribirá nada en el receptor ni cambiará su configuración desde las listas ni desde las comprobaciones.

### Interfaz web del receptor (H3)
- RF-12: CUANDO el usuario pulse "Abrir interfaz web" en un receptor Wi-Fi (S021), EL SISTEMA abrirá en una vista web dentro de la app la dirección guardada de ese receptor, en el puerto 80.
- RF-13: CUANDO el receptor esté conectado por Bluetooth, EL SISTEMA pedirá la dirección IP del receptor y la recordará para ese receptor.
- RF-14: EL SISTEMA solo cargará direcciones IP privadas (10.x.x.x, 172.16.x.x a 172.31.x.x, 192.168.x.x) o de enlace local (169.254.x.x) (RNF-T4), no aceptará nombres de equipo como reach.local ni seguirá enlaces a otras direcciones.
- RF-15: EL SISTEMA cargará la vista por la red Wi-Fi aunque no tenga internet, sin compartir cookies ni datos con otras apps ni ofrecer a la página acceso a funciones de la app.
- RF-16: SI la página no carga en 10 s, ENTONCES EL SISTEMA lo indicará con las causas probables y ofrecerá reintentar o abrir la dirección en el navegador del móvil.
- RF-17: MIENTRAS la vista esté abierta, EL SISTEMA no cerrará la conexión de posición con el receptor.

### Perfil de equipo (H4)
- RF-18: EL SISTEMA permitirá escribir, en una entrada "Equipo" del menú del receptor, para cada receptor un modelo (de un catálogo o libre), un número de serie y una versión de firmware, y editarlos.
- RF-19: CUANDO se guarde un punto medido, EL SISTEMA guardará con él el perfil de equipo del receptor en ese momento; un cambio posterior no afecta a los puntos ya guardados.
- RF-20: EL SISTEMA mostrará el perfil en la ficha del punto y escribirá modelo, serie y firmware en "Todas las columnas" de la exportación CSV (S012/RF-6) y como atributos de los puntos de la exportación GeoPackage (S053) y Shapefile (S055).
- RF-21: CUANDO el usuario elija un modelo del catálogo de S046, EL SISTEMA propondrá su perfil de antena sin cambiar el que ya tenga el receptor.

### Otras fuentes (S022)
- RF-22: SI la fuente es el GNSS del móvil, ENTONCES EL SISTEMA no ofrecerá la guía, las comprobaciones, la interfaz web ni los datos del receptor, e indicará que no aplican a esa fuente.
- RF-23: SI la fuente es el receptor virtual, ENTONCES EL SISTEMA ofrecerá las comprobaciones pero no las listas, la interfaz web ni los datos del receptor, y no guardará con sus puntos ningún perfil de equipo.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T4: HTTP sin cifrar solo hacia direcciones privadas y de enlace local.
  - RNF-T7: todos los textos de las listas, en recursos y en español.
  - RNF-T8: zonas táctiles de 56 dp; las comprobaciones se distinguen por texto o icono, no solo por color.
  - RNF-T14: ningún texto, icono ni recurso del fabricante en las listas; se redactan de nuevo.
- Una comprobación termina en menos de 1 s con los datos ya recibidos.
- La vista web no añade más de 50 MB de RAM al abrirla.

## Casos límite
| Caso | RF |
|---|---|
| Receptor sin ETC (no inclinado) | RF-7 |
| Frecuencia de GGA de 1 Hz con GNSS a 5 Hz | RF-6, RF-9 |
| Correcciones sin 1005/1006 | RF-8, RF-9 |
| Dirección pública escrita por error | RF-14 |
| Interfaz web con el móvil en datos móviles | RF-15, RF-16 |
| Dirección escrita como reach.local | RF-14 |
| GNSS del móvil o receptor virtual | RF-22, RF-23 |
| Cambio de firmware tras marcar la lista | RF-2, RF-3 |

## Fuera de alcance
- Escribir ajustes en el receptor (altura de antena, logs) → S044.
- Lectura de batería, firmware y configuración del receptor → S044.
- Actualizar el firmware desde la app → no previsto.
- Configurar la base (LoRa, NTRIP local, salidas) → no previsto; solo se guía.
- Comprobaciones de SNR o de skyplot → ya en S015.

## Criterios de finalización
- Todos los RF con test en verde (con sesiones grabadas como fixtures, RNF-T12), informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Demo en el emulador con reproducciones: cada comprobación en Correcto, Aviso y Fallo, y la vista web contra un servidor local falso.
- Demo de campo: queda para el usuario (comparar listas y comprobaciones con un RS3 mal y bien configurado).

## Dudas abiertas
Resueltas en la entrevista del 15/11/2026:
- Las listas se redactan sin número de versión de firmware (RF-2).
- La entrada nueva del menú es «Datos del receptor», no «Equipo» (RF-18).
- El perfil de equipo se escribe también en GeoPackage y Shapefile, no solo en el CSV (RF-20).
- La interfaz web solo carga IP privadas, en el puerto 80 con un receptor Wi-Fi (RF-12, RF-14).
- Las comprobaciones valen con el receptor virtual y no con el GNSS del móvil (RF-22, RF-23).

Se mantienen como suposiciones, sin cambiar lo que se construye:
- [SUPOSICIÓN: «Reach Panel» es la interfaz web oficial y documentada del receptor, por eso va en el núcleo y no en el módulo experimental.]
- [SUPOSICIÓN: las comprobaciones solo avisan; no bloquean ni sustituyen al control de precisión.]
- [SUPOSICIÓN: el perfil de equipo es solo manual; la lectura automática desde el receptor es de S044.]
- [SUPOSICIÓN: las cuatro listas de RF-1 son el contenido inicial; su redacción final la valida el usuario en la demo de campo.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S043 pasa a `spec`; dependencias ampliadas con S015, S021 (dirección del receptor), S022 y S046.
- `docs/glosario.md`: «Perfil de equipo» ya existe; añadir «Lista de configuración» y «Comprobación previa».
- S005/RF-9: el menú pasa a ocho entradas (Guía y Datos del receptor, además de las seis que tiene); S012/RF-6: columnas del perfil de equipo; S053 y S055: atributos del perfil en los puntos; `docs/inventario-funcional.md`: las filas «Guía» apuntan a S043.
- `docs/glosario.md`: añadir «Lista de configuración», «Comprobación previa» y «Datos del receptor» (entrada del perfil de equipo).
