# Spec 007 — Perfiles NTRIP y tabla de fuentes

> Hito: H1 · Depende de: S005 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Hoy las correcciones NTRIP se configuran a mano en la pantalla Correcciones:
- un único caster, con el punto de montaje escrito a mano;
- la contraseña hay que escribirla en cada sesión (S002/RF-27);
- si la conexión se corta, solo se avisa y se ofrece reconectar (S002/RF-24).

Quien viene de Emlid Flow espera guardar sus casters, elegir el punto de montaje de una lista y olvidarse de la conexión mientras trabaja.

Esta spec trae:
- **perfiles NTRIP** con la contraseña guardada cifrada (RNF-T11);
- la **tabla de fuentes** del caster, con los puntos de montaje ordenados por distancia;
- los **casters públicos de España** listos para elegir;
- **reconexión y arranque automáticos**;
- la **alarma por edad de las correcciones**;
- el **recuento de mensajes RTCM** que llegan, para saber qué envía la base.

La conexión NTRIP 2.0 por bloques (chunked) ya funciona desde S002 y no cambia.

## Usuarios / actores
- Topógrafo en campo que se conecta a una red de estaciones o a una base propia.
- Caster NTRIP (IGN, redes autonómicas, casters privados).
- Receptor RS3, que recibe las correcciones por el mismo enlace.

## Historias de usuario
- H1: Como topógrafo quiero guardar los datos de mis casters, con la contraseña, para no escribirlos en cada sesión.
- H2: Como topógrafo quiero elegir el punto de montaje de la lista del caster, con los más cercanos primero.
- H3: Como topógrafo en España quiero elegir mi red (IGN o autonómica) sin buscar su servidor y su puerto.
- H4: Como topógrafo quiero que las correcciones se reconecten solas y que me avise si dejan de llegar, aunque tenga el móvil en el bolsillo.
- H5: Como topógrafo quiero ver qué mensajes RTCM llegan para comprobar que la base envía lo que necesito.

## Requisitos funcionales (criterios de aceptación en EARS)
### Perfiles NTRIP (H1)
- RF-1: EL SISTEMA mostrará en la pantalla Correcciones la lista de perfiles NTRIP, con el nombre, el servidor, el puerto y el punto de montaje de cada uno.
- RF-2: CUANDO el usuario cree o edite un perfil, EL SISTEMA permitirá indicar el nombre, el servidor, el puerto (2101 por defecto), si la conexión es segura (TLS), el usuario, la contraseña y el punto de montaje.
- RF-3: SI el nombre está vacío o coincide con el de otro perfil sin distinguir mayúsculas, o el servidor está vacío, o el puerto no está entre 1 y 65535, ENTONCES EL SISTEMA no guardará el perfil e indicará el motivo.
- RF-4: CUANDO el usuario guarde un perfil, EL SISTEMA guardará la contraseña cifrada en el dispositivo (RNF-T11).
- RF-5: EL SISTEMA no mostrará la contraseña guardada, salvo que el usuario pulse el control para verla mientras edita el perfil.
- RF-6: SI la contraseña guardada no se puede descifrar, ENTONCES EL SISTEMA la pedirá de nuevo al conectar, sin perder el resto del perfil.
- RF-7: CUANDO el usuario borre un perfil, EL SISTEMA pedirá confirmación y lo eliminará con su contraseña.
- RF-8: SI el usuario borra el perfil de la conexión en curso, ENTONCES EL SISTEMA cerrará la conexión NTRIP.
- RF-9: CUANDO el usuario guarde cambios en el perfil de la conexión en curso, EL SISTEMA volverá a conectar con los datos nuevos.
- RF-10: CUANDO se actualice la app con los datos de caster que guardaba S002, EL SISTEMA creará con ellos un perfil con el servidor, el puerto, el punto de montaje y el usuario.

### Casters públicos de España (H3)
- RF-11: CUANDO el usuario cree un perfil, EL SISTEMA ofrecerá elegir un caster público de España (el del IGN y los de las redes autonómicas de la tabla de abajo), que rellena el nombre, el servidor, el puerto y el TLS del perfil.
- RF-12: EL SISTEMA mostrará, al elegir un caster público, la dirección web donde se solicita el usuario de esa red.
- RF-13: DONDE el caster público sea de acceso libre, EL SISTEMA rellenará también el usuario y la contraseña públicos que indica su web.

Casters públicos (comprobados el 16/10/2026 en las webs de cada red y con su tabla de fuentes). Si una red ofrece la solución de red y las estaciones sueltas en puertos distintos, cada puerto es una entrada de la lista.

| Red | Ámbito | Acceso |
|---|---|---|
| ERGNSS (IGN): solución de red y estación más cercana | España | Registro gratuito |
| ERGNSS (IGN): estación única | España | Registro gratuito |
| RAP (IECA): solución de red y estación más cercana | Andalucía | Solicitud por correo |
| RAP (IECA): estación única | Andalucía | Solicitud por correo |
| ARAGEA (IGEAR) | Aragón | Registro gratuito |
| RGAPA | Asturias | Libre |
| XGAIB (ICGIB) | Illes Balears | Registro gratuito |
| Red GNSS de Canarias (GRAFCAN) | Canarias | De pago |
| RGAC | Cantabria | Libre |
| Red GNSS de Castilla y León (ITACyL) | Castilla y León | Registro gratuito |
| CatNet (ICGC) | Cataluña | Registro gratuito |
| ERVA (ICV) | Comunitat Valenciana | Registro gratuito |
| REP: solución de red | Extremadura | Registro gratuito |
| REP: estación única | Extremadura | Registro gratuito |
| Red GNSS de La Rioja | La Rioja | Registro |
| REGAM | Región de Murcia | Solicitud |
| RGAN: GPS y GLONASS | Navarra | Registro gratuito |
| RGAN: GPS, GLONASS, Galileo y BeiDou | Navarra | Registro gratuito |
| Red GNSS de Euskadi: solución de red y estación más cercana | País Vasco | Registro gratuito |
| Red GNSS de Euskadi: estación única | País Vasco | Registro gratuito |
| Red de Bizkaia (Diputación Foral) | Bizkaia | Libre |

Castilla-La Mancha, Galicia, la Comunidad de Madrid, Ceuta y Melilla no tienen caster propio: se usa el del IGN.

### Tabla de fuentes y puntos de montaje (H2)
- RF-14: CUANDO el usuario pida la lista de puntos de montaje de un perfil, EL SISTEMA descargará la tabla de fuentes del caster, con el usuario y la contraseña si los hay, y mostrará sus puntos de montaje.
- RF-15: EL SISTEMA mostrará de cada punto de montaje su nombre, su identificador, su formato, sus constelaciones, si necesita la posición del receptor (GGA) y su distancia al receptor en km.
- RF-16: MIENTRAS haya posición del receptor, EL SISTEMA ordenará los puntos de montaje de más cercano a más lejano, con los que no tienen coordenadas al final.
- RF-17: SI no hay posición del receptor, ENTONCES EL SISTEMA ordenará los puntos de montaje por nombre y no mostrará distancias.
- RF-18: CUANDO el usuario escriba en la búsqueda de puntos de montaje, EL SISTEMA mostrará los que contienen el texto en el nombre o el identificador, sin distinguir mayúsculas.
- RF-19: CUANDO el usuario elija un punto de montaje, EL SISTEMA lo pondrá en el perfil y recordará si necesita la GGA.
- RF-20: SI no se puede descargar la tabla de fuentes o la respuesta no es una tabla de fuentes, ENTONCES EL SISTEMA indicará el motivo y permitirá escribir el punto de montaje a mano.
- RF-21: MIENTRAS la conexión NTRIP esté abierta, EL SISTEMA enviará la GGA al caster cada 5 s si el punto de montaje la necesita según la tabla de fuentes o si no se sabe porque se escribió a mano, y no la enviará en otro caso.

### Conexión, reconexión y arranque automáticos (H4)
- RF-22: CUANDO el usuario pulse un perfil con el receptor conectado, EL SISTEMA cerrará la conexión NTRIP en curso, si la hay, y se conectará al caster con ese perfil.
- RF-23: SI la conexión NTRIP se corta, no llegan correcciones durante 10 s o no se puede contactar con el caster, ENTONCES EL SISTEMA reintentará la conexión sola, con esperas de 1 s a 30 s que crecen al doble en cada intento (RNF-T3), mientras el usuario no la detenga.
- RF-24: SI el caster rechaza el usuario o la contraseña, o el punto de montaje no existe, ENTONCES EL SISTEMA no reintentará e indicará el motivo.
- RF-25: MIENTRAS se reintenta la conexión NTRIP, EL SISTEMA mostrará que está reconectando, el número de intento y un control para detenerla.
- RF-26: CUANDO se conecte un receptor y la conexión NTRIP estuviera abierta al terminar la conexión anterior con un receptor, EL SISTEMA la abrirá con el mismo perfil, si sigue existiendo.
- RF-27: DONDE el perfil tenga activada la conexión segura, EL SISTEMA cifrará la conexión con el caster con TLS.
- RF-28: SI el certificado TLS del caster no es válido, ENTONCES EL SISTEMA no se conectará e indicará que el certificado no es válido.

### Alarma por edad de las correcciones (H4)
- RF-29: SI con la conexión NTRIP abierta la edad de las correcciones supera el umbral, ENTONCES EL SISTEMA mostrará un aviso en la pantalla y en la notificación, y emitirá un sonido y una vibración en menos de 1 s, también con la app en segundo plano.
- RF-30: CUANDO vuelvan a llegar correcciones tras una alarma, EL SISTEMA quitará el aviso.
- RF-31: EL SISTEMA ofrecerá en Ajustes el umbral de la alarma, entre 2 y 60 s, con 10 s por defecto.

### Mensajes RTCM (H5)
- RF-32: MIENTRAS lleguen correcciones, EL SISTEMA mostrará en la pantalla Correcciones cada tipo de mensaje RTCM 3 recibido, con su número, su nombre si es un tipo conocido y cuántos han llegado.
- RF-33: SI llega un mensaje RTCM 3 con el código de comprobación (CRC) erróneo, ENTONCES EL SISTEMA lo contará como erróneo, lo mostrará en el total de errores y lo reenviará igualmente al receptor sin modificarlo (S002/RF-19).

## Requisitos no funcionales
- Transversales que aplican: RNF-T2, RNF-T3, RNF-T4, RNF-T6, RNF-T7, RNF-T8, RNF-T11 y RNF-T12.
- La contraseña no aparece en logs, sesiones grabadas, informes ni copias (RNF-T11); en el dispositivo solo está cifrada con una clave del Android Keystore.
- Una tabla de fuentes de 5000 puntos de montaje se lee y se muestra en menos de 2 s en el móvil de referencia (RNF-T13).
- El recuento de mensajes RTCM no añade más de 50 ms de retraso a las correcciones que se reenvían al receptor.
- Al actualizar la app se conservan los perfiles y sus contraseñas (RNF-T6).

## Casos límite
| Caso | RF |
|---|---|
| Nombre de perfil vacío o repetido, servidor vacío, puerto fuera de rango | RF-3 |
| Clave del Keystore perdida (móvil restaurado) | RF-6 |
| Editar o borrar el perfil de la conexión en curso | RF-8, RF-9 |
| Datos de caster guardados por S002 | RF-10 |
| Caster sin tabla de fuentes, tabla vacía o respuesta que no es una tabla | RF-20 |
| Punto de montaje sin coordenadas, o sin posición del receptor | RF-16, RF-17 |
| Punto de montaje escrito a mano | RF-20, RF-21 |
| Sin internet o caster caído | RF-23 |
| Usuario o contraseña incorrectos, punto de montaje inexistente | RF-24 |
| Certificado TLS caducado o de otro servidor | RF-28 |
| El usuario detuvo la NTRIP antes de desconectar el receptor, o borró el perfil | RF-26 |
| Pulsar otro perfil con una conexión abierta | RF-22 |
| Red de acceso libre o de pago | RF-12, RF-13 |
| Correcciones que se cortan con el móvil en el bolsillo | RF-29 |
| Trama RTCM con CRC erróneo | RF-33 |

## Fuera de alcance
- Posición de la base (RTCM 1005/1006), línea base y SNR de la base → S015.
- NTRIP por datos móviles mientras el receptor está en la Wi-Fi → S021.
- Copia y traspaso de los perfiles a otro dispositivo (sin contraseñas) → S013 (en la copia completa).
- NTRIP en el propio receptor, salida de correcciones del receptor y radio LoRa → guía de S043 (solo en Emlid Flow o Reach Panel).
- Gestionar perfiles sin un receptor conectado → no previsto (S005: Correcciones solo se abre desde el receptor).
- Elegir la versión de NTRIP → no previsto (la petición 2.0 sirve también para los casters 1.0, ADR-13).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Tests contra el caster NTRIP falso: tabla de fuentes, GGA según el punto de montaje, reconexión, TLS y rechazo de credenciales.
- Tests del lector de tramas RTCM 3 con tramas reales grabadas (CRC24Q correcto y erróneo).
- Demo en el emulador con el caster falso: crear un perfil desde un caster de España, elegir el punto de montaje de la tabla, cortar y recuperar la conexión, ver la alarma y el recuento de mensajes.
- Demo de campo (usuario): conexión a la red del IGN o autonómica con el RS3, con FIX.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: los casters públicos son plantillas para crear perfiles, no perfiles ya creados: la lista solo tiene los perfiles del usuario.]
- [SUPOSICIÓN: la conexión segura (TLS) entra en esta spec, como indicaba S002, como opción de cada perfil.]
- [SUPOSICIÓN: no hay límite de perfiles.]
- [SUPOSICIÓN: la lista de casters públicos se revisa en cada versión de la app; si una red cambia de dirección entretanto, el usuario edita su perfil. Ningún caster público ofrece hoy TLS.]
- [SUPOSICIÓN: la Red de Extremadura entra en la lista aunque el 16/10/2026 su servicio estaba caído; su web oficial mantiene la dirección.]
- [SUPOSICIÓN: la alarma suena una vez por cada corte de correcciones, no de forma continua.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- S002/RF-20 pasa a decir: "MIENTRAS la conexión NTRIP esté abierta y el receptor haya enviado alguna GGA, EL SISTEMA enviará al caster la última GGA recibida cada 5 s, según S007/RF-21."
- S002/RF-24 pasa a decir: "SI con la conexión NTRIP abierta no llegan correcciones durante 10 s o el caster corta la conexión, ENTONCES EL SISTEMA mostrará el aviso y reconectará según S007/RF-23."
- S002/RF-26 pasa a decir: "EL SISTEMA recordará entre sesiones los datos del caster en perfiles NTRIP (S007/RF-1 a RF-10)."
- S002/RF-27 pasa a decir: "EL SISTEMA solo guardará la contraseña del caster cifrada (S007/RF-4)."
- S004, RNF-T11: "la contraseña NTRIP solo vive en memoria" pasa a "la contraseña NTRIP se guarda cifrada (S007/RF-4) y se usa también para reanudar el NTRIP".
- `docs/hoja-de-ruta.md`, fila S007: depende de 005 (la pantalla Correcciones), y se añaden "casters públicos de España", "TLS" y "arranque automático".
- `docs/glosario.md`: se añaden "Caster público", "Conexión segura (TLS)", "Mensaje RTCM" y "Umbral de la alarma de correcciones".
