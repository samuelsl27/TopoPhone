# Spec 044 — Módulo experimental del receptor

> Hito: H7 · Depende de: S043 (además, de S021, S046, S005, S014 y S016; ver Cambios) · Estado: spec (aprobada el 18/11/2026)

## Contexto y objetivo
El receptor tiene una interfaz local que el fabricante no documenta y que sirve para encontrarlo en la red, leer su información, su batería y su configuración, ver su estado en directo y cambiar un par de ajustes. Es útil (por ejemplo, la altura de antena del modo compensado, o parar un log sin salir de la app), pero se obtuvo por ingeniería inversa, los términos del fabricante la prohíben y puede cambiar con cualquier firmware (`docs/referencias/emlid/enlaces.md`).

La constitución (nº 5) exige aislarla: vive en un módulo y en un flavor `experimental`, desactivada por defecto y con aviso legal. Esta spec define ese módulo y sus límites. El núcleo de la app (S002 a S043) no la necesita ni depende de ella: nada de lo que mide o replantea TopoPhone puede venir de aquí.

## Usuarios / actores
- Usuario avanzado que compila o instala el flavor `experimental` bajo su responsabilidad.
- Receptor Reach en la red local (hotspot propio o red del móvil), que responde con la forma que el módulo conoce.

## Historias de usuario
- H1: Como usuario quiero que el módulo no exista en la app normal y que, en la experimental, esté apagado hasta que acepte el aviso legal.
- H2: Como usuario quiero que la app encuentre los receptores de mi red y me deje añadirlos sin escribir su dirección.
- H3: Como usuario quiero leer del receptor su información, su batería, su configuración y su estado en directo.
- H4: Como usuario quiero cambiar la altura de antena y arrancar o parar los logs del receptor, viendo antes y después lo que cambia.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Módulo experimental**: la parte de la app que habla con la interfaz local no documentada del receptor; solo está en el flavor `experimental`.
- **Aviso legal**: texto que explica que la interfaz no es oficial, que su uso puede infringir los términos del fabricante y que puede dejar de funcionar.
- **Receptor compatible**: el que responde con la forma que el módulo conoce; con cualquier otra, el módulo no escribe y marca lo que no entiende.

### Aislamiento y activación (H1)
- RF-1: EL SISTEMA no incluirá el módulo experimental en el flavor normal: ni sus clases, ni sus permisos, ni su configuración de red, ni sus textos.
- RF-2: EL SISTEMA no hará depender ningún módulo de dominio, de datos ni de receptor del módulo experimental, y la regla de módulos lo comprobará.
- RF-3: DONDE el flavor sea `experimental`, EL SISTEMA tendrá el módulo desactivado de fábrica, con un interruptor "Módulo experimental" en Ajustes.
- RF-4: CUANDO el usuario active el interruptor, EL SISTEMA mostrará el aviso legal completo, activará el módulo solo si el usuario lo acepta y guardará la fecha de la aceptación.
- RF-5: CUANDO el usuario desactive el módulo, EL SISTEMA cerrará sus conexiones, ocultará sus pantallas y olvidará la aceptación, de modo que la siguiente activación vuelva a mostrar el aviso legal, sin afectar a la sesión del receptor.
- RF-6: MIENTRAS el módulo esté activo, EL SISTEMA mostrará en la cabecera del receptor la marca "Experimental".

### Encontrar receptores (H2)
- RF-7: CUANDO el usuario pulse "Buscar receptores", EL SISTEMA buscará receptores en la red Wi-Fi durante 10 s y mostrará el nombre y la dirección de cada uno que responda.
- RF-8: CUANDO el usuario elija un receptor encontrado y confirme, EL SISTEMA propondrá darlo de alta como receptor Wi-Fi (S021) con el nombre, la dirección y el puerto 9001 editables, y no guardará nada sin esa confirmación.
- RF-9: SI la búsqueda no encuentra ningún receptor en 10 s, ENTONCES EL SISTEMA lo indicará con las causas probables (móvil fuera de la red del receptor, red que no permite la búsqueda, receptor apagado), remitirá a escribir la dirección a mano (S021/RF-2) y ofrecerá reintentar.
- RF-10: EL SISTEMA descartará de la búsqueda cualquier respuesta cuya dirección no sea privada o de enlace local.

### Leer del receptor (H3)
- RF-11: CUANDO el módulo esté activo y haya un receptor Wi-Fi (S021) o una dirección de receptor guardada (S043/RF-13), EL SISTEMA leerá del receptor su modelo, número de serie, firmware, rol y altura de antena configurada.
- RF-12: CUANDO el usuario pulse "Rellenar el perfil de equipo", EL SISTEMA propondrá el modelo, la serie y el firmware leídos para el perfil de S043/RF-18, y los guardará solo si el usuario confirma.
- RF-13: MIENTRAS el módulo esté activo, EL SISTEMA mostrará en la pantalla del receptor el nivel de batería y el estado de los logs activos.
- RF-14: CUANDO el usuario abra "Configuración del receptor", EL SISTEMA mostrará en solo lectura los ajustes de posicionamiento y de salida que el receptor devuelva.
- RF-15: SI el receptor no responde en 3 s o su respuesta no tiene la forma esperada, ENTONCES EL SISTEMA lo indicará como "Receptor no compatible" en la parte afectada, no hará ninguna escritura mientras la información básica (RF-11) no sea válida y seguirá funcionando todo lo demás.
- RF-16: EL SISTEMA obtendrá el estado en directo por una conexión aparte de la sesión del receptor, y su pérdida o su error no afectarán a la posición, las correcciones ni la grabación.
- RF-17: SI el estado en directo se corta mientras la pantalla del módulo esté abierta, ENTONCES EL SISTEMA lo indicará como "Sin estado del receptor" y reintentará solo la lectura, con esperas crecientes de 1 s a 30 s.

### Escribir en el receptor (H4)
- RF-18: CUANDO el usuario pulse "Enviar altura de antena", EL SISTEMA mostrará el valor actual del receptor y el nuevo, y pedirá confirmación; la propuesta, siempre editable, será la altura del jalón activo (S046) con el modo "Compensada por el receptor" y 0 con cualquier otro modo, con un aviso de que en ese caso la app ya resta la altura y enviar otro valor la contaría dos veces.
- RF-19: CUANDO el usuario confirme, EL SISTEMA enviará la altura, volverá a leer el ajuste y mostrará si coincide con lo enviado.
- RF-20: CUANDO el usuario pulse "Iniciar log" o "Parar log", EL SISTEMA mostrará el estado actual de los logs y pedirá confirmación (con "Parar log", avisando de que el log activo se cerrará y no continuará) y, tras confirmar, comprobará el estado resultante.
- RF-21: SI una escritura falla, no se confirma o el valor leído después no coincide, ENTONCES EL SISTEMA lo indicará con el valor que quedó en el receptor y no reintentará solo.
- RF-22: EL SISTEMA no cambiará en el receptor ningún ajuste que no sea la altura de antena y el estado de los logs, ni siquiera a petición del usuario; el resto de los ajustes que la petición lleve irán con el mismo valor que el receptor tenía.
- RF-23: EL SISTEMA hará todas las peticiones del módulo por la red Wi-Fi del receptor y solo hacia direcciones privadas o de enlace local (RNF-T4).

### Rastro (H1, H4)
- RF-24: CUANDO se haga una lectura de configuración o una escritura, EL SISTEMA la anotará en el log de la app (S014) con la hora, la operación y los valores, sin secretos.
- RF-25: EL SISTEMA no guardará nunca datos del módulo en un punto, un proyecto o un CSV; solo se guardan el perfil de equipo confirmado (RF-12) y el receptor dado de alta (RF-8), que son acciones del usuario.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1 y RNF-T4: permiso de red local cuando el sistema lo exija; HTTP sin cifrar solo hacia direcciones privadas y de enlace local, y solo en el flavor `experimental`.
  - RNF-T7 y RNF-T8: textos en español y en recursos; zonas táctiles de 56 dp; la marca "Experimental" y los estados se distinguen por texto, no solo por color.
  - RNF-T11 y RNF-T12: el log y el informe de sistema no contienen secretos.
  - RNF-T14: el aviso legal no usa la marca del fabricante como nombre de la app ni recursos suyos.
- Una petición del módulo tiene un tiempo máximo de 3 s y no se hace en el hilo principal.
- La búsqueda de receptores dura como máximo 10 s y se detiene al salir de su pantalla.
- Una comprobación de compilación demuestra que el APK del flavor normal no contiene clases, permisos ni configuración de red del módulo.
- La aceptación del aviso legal y el interruptor son locales al dispositivo y no viajan en las copias de seguridad ni en los paquetes de proyecto.

## Casos límite
| Caso | RF |
|---|---|
| Receptor con firmware que cambia la forma de las respuestas | RF-15 |
| Receptor apagado a mitad de una escritura | RF-21 |
| Estado en directo que se corta | RF-16, RF-17 |
| Usuario rechaza el aviso legal | RF-4 |
| Dirección pública escrita por error o respuesta de búsqueda con dirección pública | RF-10, RF-23 |
| Perfil de equipo ya rellenado a mano | RF-12 |
| Enviar la altura con la inclinación calculada por la app | RF-18 |
| Dos receptores con el mismo nombre en la red | RF-7, RF-8 |
| Receptor encontrado que ya está dado de alta | RF-8 |
| Desactivar el módulo con una escritura en curso | RF-5, RF-21 |
| Red que no permite la búsqueda por multidifusión | RF-9 |

## Fuera de alcance
- Cambiar rol, modo de posicionamiento, salidas, Wi-Fi o LoRa del receptor → no previsto.
- Descargar, borrar o convertir logs → no previsto.
- Actualizar el firmware o reiniciar el receptor → no previsto.
- Usar datos del módulo para medir, replantear o validar puntos → no previsto (constitución nº 5).
- Distribuir el flavor `experimental` como release público → no previsto hasta tener el permiso del fabricante (`enlaces.md`).
- Conectar o dar de alta un receptor sin confirmación del usuario → no previsto.

## Criterios de finalización
- Todos los RF con test en verde (con un servidor falso basado en respuestas registradas), informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde, también para el flavor `experimental`.
- Demo en el emulador: activar con el aviso legal, buscar y dar de alta un receptor, leer y escribir contra el servidor falso, el estado en directo cortándose y volviendo, y un receptor no compatible.
- Demo de campo: queda para el usuario (búsqueda, lectura y escritura reales con su RS3, con un firmware anotado).

## Dudas abiertas
Decisiones tomadas sin preguntarte, que se pueden corregir al aprobar:
- [SUPOSICIÓN: la aceptación y el interruptor no entran en la copia de seguridad (S013) ni en los paquetes: cada dispositivo acepta el aviso por su cuenta; así esta spec no toca S013.]
- [SUPOSICIÓN: no se toca código de S006, S010, S011, S012, S013, S018 ni S053; sí el de S021 (alta del receptor, RF-8), S005 (marca de la cabecera y entrada de pantallas), S043 (perfil de equipo, RF-12), S014 (rastro, RF-24) y Ajustes.]
- [SUPOSICIÓN: el interruptor de Ajustes es único para toda la app, no por receptor; el módulo no guarda datos por receptor, salvo lo de RF-8 y RF-12.]
- [SUPOSICIÓN: la lectura de cada parte (información, batería, logs, configuración) se valida por separado: una parte con forma inesperada se marca como no compatible y las demás siguen; las escrituras exigen que la información básica sea válida.]
- [SUPOSICIÓN: la batería y el estado de los logs solo llegan por el estado en directo (el código de referencia no tiene lectura puntual de ninguno); por eso RF-13 y la comprobación de RF-20 dependen de esa conexión, y sin ella RF-20 acaba como «no se confirma» (RF-21).]
- [NECESITA ACLARACIÓN: versión de firmware de referencia con la que se prueba el módulo; sale de la demo de campo del usuario y se anotará entonces.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S044 ya está en `spec` (18/11/2026); dependencias ampliadas con S021 y S046.
- `docs/glosario.md`: añadir «Módulo experimental» y «Aviso legal».
- `docs/arquitectura.md` (ADR-18) no cambia; `docs/inventario-funcional.md`: las filas «Exp» apuntan a S044, con la búsqueda de receptores añadida.
- `specs/021-conexion-wifi-tcp/spec.md`: sin cambios (ya remite la búsqueda a S044); el alta de RF-8 usa su receptor Wi-Fi.
- `specs/pendientes/README.md`: S044 sale del índice y la nota de mDNS queda resuelta.
