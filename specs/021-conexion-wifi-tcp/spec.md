# Spec 021 — Conexión Wi-Fi por TCP

> Hito: H2 · Depende de: S015 (además, de S004, S005 y S007, de las que reutiliza la reconexión, la lista de receptores y el NTRIP) · Estado: aprobada (entrevistada 15/11/2026)

## Contexto y objetivo
Hoy el receptor solo se conecta por Bluetooth SPP. La salida de posición por TCP está documentada por el fabricante y tiene ventajas: no depende del emparejamiento, no sufre la limitación de una sola conexión Bluetooth (S004/RF-27) y permite que otra app siga conectada por Bluetooth mientras TopoPhone lee por Wi-Fi (es el montaje A/B del protocolo de campo). Cuando el móvil se conecta al hotspot del receptor, esa red no tiene internet: el tráfico del receptor debe ir por el Wi-Fi y el NTRIP por los datos móviles (RNF-T4).

Esta spec añade el transporte TCP como segundo tipo de receptor, con su encaminamiento de red y el envío de correcciones al puerto de entrada del receptor. El receptor se añade escribiendo su dirección y su puerto; buscarlo en la red es del módulo experimental (S044). Todo lo demás (estados, reconexión, grabación, notificación) es lo de S002 a S005.

## Usuarios / actores
- Topógrafo que conecta el móvil al hotspot del receptor o a la misma red local.

## Historias de usuario
- H1: Como topógrafo quiero añadir un receptor por Wi-Fi con su IP y su puerto, y poder cambiarlos sin perder sus ajustes.
- H2: Como topógrafo quiero que mientras el móvil está en el Wi-Fi del receptor las correcciones sigan llegando por datos móviles y se envíen al receptor.
- H3: Como topógrafo quiero que la conexión por Wi-Fi se comporte como la de Bluetooth: estados, reconexión, grabación y diagnóstico.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Receptor Wi-Fi**: receptor guardado con un nombre, una dirección (IP o nombre) y un puerto de posición; opcionalmente, un puerto de correcciones.
- **Puerto de posición**: el del servidor TCP del receptor que emite el NMEA (9001 por defecto); la app es el cliente.
- **Puerto de correcciones**: el del receptor que acepta RTCM3 por TCP; si coincide con el de posición, se usa la misma conexión.

### Añadir y gestionar receptores (H1)
- RF-1: CUANDO el usuario pulse "Añadir receptor" (S004/RF-1), EL SISTEMA le dejará elegir entre "Bluetooth" y "Wi-Fi (TCP)".
- RF-2: CUANDO el usuario elija "Wi-Fi (TCP)", EL SISTEMA pedirá nombre, dirección y puerto de posición, con 192.168.42.1 y 9001 como valores iniciales, y un puerto de correcciones opcional.
- RF-3: SI la dirección está vacía o es inválida, o un puerto no es un número entre 1 y 65535, ENTONCES EL SISTEMA no guardará el receptor e indicará el motivo.
- RF-4: EL SISTEMA mostrará los receptores Wi-Fi guardados en la lista de receptores (S005/RF-2) junto a los emparejados, con su dirección y puerto, y permitirá editarlos y borrarlos.
- RF-5: EL SISTEMA identificará cada receptor Wi-Fi con una identidad propia que no cambia al editar su nombre, su dirección o sus puertos; CUANDO el usuario los edite, el receptor conservará sus ajustes (RF-18) y seguirá siendo el último receptor si lo era (S004/RF-24).
- RF-6: DONDE Android exija un permiso para acceder a la red local, EL SISTEMA lo pedirá al conectar, con una explicación en español; SI se deniega, ENTONCES indicará cómo concederlo y el resto de la app seguirá funcionando (RNF-T1).

### Conectar y encaminar la red (H2, H3)
- RF-7: CUANDO el usuario elija un receptor Wi-Fi, EL SISTEMA abrirá la conexión TCP con su puerto de posición por la red Wi-Fi, mostrará "Conectando" y pasará a "Recibiendo" con la primera frase NMEA válida (como S002/RF-5 y RF-6).
- RF-8: MIENTRAS la conexión con el receptor sea por Wi-Fi, EL SISTEMA mantendrá ese tráfico en la red Wi-Fi aunque no tenga internet y no permitirá que Android lo pase a los datos móviles.
- RF-9: MIENTRAS el NTRIP esté activo con un receptor Wi-Fi, EL SISTEMA abrirá el caster por una red con internet (datos móviles u otro Wi-Fi), no por la del receptor.
- RF-10: SI no hay ninguna red con internet para el caster, ENTONCES EL SISTEMA indicará que el NTRIP necesita datos móviles activos, sin cerrar la conexión con el receptor.
- RF-11: CUANDO haya correcciones RTCM3 del NTRIP de la app y el receptor tenga puerto de correcciones, EL SISTEMA las escribirá en él sin modificarlas, por la red del receptor.
- RF-12: SI el receptor Wi-Fi no tiene puerto de correcciones, ENTONCES EL SISTEMA no enviará correcciones por Wi-Fi e indicará en Correcciones que el receptor debe recibirlas por otra vía.
- RF-13: SI falla la escritura en el puerto de correcciones, ENTONCES EL SISTEMA lo indicará, mantendrá la posición y reintentará con la espera de RF-15.

### Reconexión y diagnóstico (H3)
- RF-14: SI la conexión no se abre en 10 s, ENTONCES EL SISTEMA mostrará el error y las causas probables: el móvil no está en la red del receptor, dirección o puerto incorrectos, salida de posición por TCP desactivada, o el receptor ya tiene otro cliente.
- RF-15: SI se pierde la conexión sin que el usuario la cierre, ENTONCES EL SISTEMA aplicará la reconexión de S004/RF-13 a RF-23, también con el Wi-Fi apagado o cambiado de red.
- RF-16: SI con la conexión abierta no llega ninguna frase NMEA durante 10 s, ENTONCES EL SISTEMA indicará que la salida de posición del receptor no está activada y dónde activarla (como S004/RF-28).
- RF-17: EL SISTEMA aplicará a los receptores Wi-Fi el servicio en primer plano, la notificación, la grabación de sesiones, el último receptor recordado y la conexión automática al abrir (S004/RF-7 a RF-11, RF-24 a RF-26; S003).
- RF-18: EL SISTEMA guardará los ajustes por receptor (modo de inclinación, jalón y antena) bajo la identidad de cada receptor Wi-Fi (RF-5), separados de los de cualquier receptor Bluetooth.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: permisos de red local solo cuando se usen.
  - RNF-T2 y RNF-T3: servicio, wakelock, estados y reconexión de 1 a 30 s.
  - RNF-T4: receptor por Wi-Fi y NTRIP por datos móviles; sin HTTP; ninguna dependencia de internet para leer la posición.
  - RNF-T6: los receptores Wi-Fi guardados se conservan al actualizar la app (migración o almacenamiento probado).
  - RNF-T7 y RNF-T8: textos en recursos y zonas táctiles de 56 dp.
  - RNF-T12: la grabación incluye el NMEA y el RTCM, como por Bluetooth.
- Con un receptor en la misma red, la primera época llega a la pantalla en menos de 3 s tras pulsar el receptor.
- Cualquier cambio de red del móvil se detecta y se avisa en menos de 3 s.

## Casos límite
| Caso | RF |
|---|---|
| Móvil en el hotspot del receptor, sin internet por Wi-Fi | RF-8, RF-9 |
| Sin datos móviles | RF-10 |
| IP del receptor distinta tras cambiar de red | RF-14, RF-15 |
| Editar la IP de un receptor guardado | RF-5 (conserva sus ajustes) |
| Puerto de correcciones igual al de posición | RF-11 |
| Receptor sin puerto de correcciones | RF-12 |
| Permiso de red local denegado | RF-6 |
| Wi-Fi apagado a mitad del trabajo | RF-15 |

## Fuera de alcance
- Buscar receptores en la red local (mDNS u otros) → S044: el nombre de servicio mDNS solo sale de la API no documentada (constitución nº 5).
- Servidor TCP en el móvil (que el receptor se conecte a la app) → no previsto.
- Configurar el Wi-Fi o la salida TCP del receptor desde la app → S043 (guía) y S044 (experimental).
- Reach Panel dentro de la app → S043.
- Conexión por BLE o por serie USB → no previsto.

## Criterios de finalización
- Todos los RF con test en verde (con un servidor TCP falso de `core:testing`), informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Demo en el emulador: un receptor Wi-Fi contra un servidor TCP que reproduce una sesión grabada, con corte y reconexión.
- Demo de campo: queda para el usuario (RS3 en hotspot con NTRIP por datos móviles, y el montaje A/B del protocolo).

## Dudas abiertas
- [SUPOSICIÓN: la app es siempre el cliente TCP; la salida de posición del receptor debe estar en modo servidor.]
- [SUPOSICIÓN: el puerto de correcciones es un campo opcional del receptor, sin autodetección (RF-2, RF-11).]

## Decisiones de la entrevista (15/11/2026)
- El receptor se añade solo escribiendo su dirección y su puerto; la búsqueda en la red (mDNS) queda fuera de S021 y pasa a S044, por la constitución nº 5.
- TopoPhone envía las correcciones del NTRIP al receptor por TCP, con un puerto de correcciones opcional por receptor (RF-11, RF-12).
- Un receptor Wi-Fi tiene identidad propia: editar su IP o su puerto no le hace perder ajustes ni el estar recordado como último (RF-5, RF-18).

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S021 pasa a `spec`, dependencias ampliadas con S004, S005 y S007, y sin el descubrimiento mDNS; la fila de S044 gana «búsqueda mDNS de receptores».
- `docs/glosario.md`: añadir «Receptor Wi-Fi», «Puerto de posición» y «Puerto de correcciones».
- S005/RF-2 (lista de receptores) y S004/RF-1 (Añadir receptor): admiten receptores Wi-Fi; `docs/inventario-funcional.md` (mDNS pasa a S044); `specs/pendientes/README.md` (el conflicto de mDNS queda resuelto).
