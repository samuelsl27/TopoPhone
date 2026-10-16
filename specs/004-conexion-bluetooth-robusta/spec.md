# Spec 004 — Conexión Bluetooth robusta

> Hito: H1 · Depende de: S003 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Con la S002 y la S003 la conexión con el RS3 funciona, pero solo mientras la app está delante.

En campo eso no basta:
- **Segundo plano:** el móvil se bloquea, el topógrafo cambia de app o Android cierra lo que está en segundo plano, y la conexión, el NTRIP y la grabación se cortan sin aviso.
- **Reconexión:** un corte de Bluetooth de unos segundos, por alejarse del jalón o por una interferencia, obliga a volver a conectar a mano.
- **Emparejamiento:** el receptor hay que emparejarlo desde los ajustes del móvil.
- **Firmware 33:** el RS3 solo admite una conexión Bluetooth, y si Emlid Flow sigue conectado el error no se entiende.

Esta spec hace que la conexión con el receptor aguante el uso real de una jornada. Funciona en segundo plano con una notificación, se recupera sola de los cortes, conecta sola al abrir la app, empareja receptores nuevos desde la propia app y explica la causa más probable cuando el receptor está ocupado.

## Usuarios / actores
- **Topógrafo** con el móvil en el bolsillo o en el soporte del jalón, que bloquea la pantalla y cambia de app.
- **Reach RS3** (firmware 33): una sola conexión Bluetooth a la vez; se empareja con un PIN.
- **Android**, que limita lo que una app hace en segundo plano y, en algunos fabricantes, la cierra.

## Historias de usuario
- H1: Como topógrafo quiero añadir un receptor nuevo desde la app para no tener que emparejarlo en los ajustes del móvil.
- H2: Como topógrafo quiero que la conexión, el NTRIP y la grabación sigan con la pantalla apagada o con otra app delante para no perder datos.
- H3: Como topógrafo quiero que, si se corta el Bluetooth, la app se reconecte sola para no tener que estar pendiente.
- H4: Como topógrafo quiero que la app se conecte sola al último receptor al abrirla para empezar a trabajar sin pasos.
- H5: Como topógrafo quiero saber por qué no conecta cuando el receptor está ocupado o no envía datos para resolverlo en el momento.

## Requisitos funcionales (criterios de aceptación en EARS)

### Añadir un receptor (H1)
- RF-1: CUANDO el usuario pulse "Añadir receptor", EL SISTEMA buscará los dispositivos Bluetooth cercanos y le dejará elegir uno.
- RF-2: CUANDO el usuario elija un dispositivo que no está emparejado, EL SISTEMA iniciará el emparejamiento, en el que el móvil pide el PIN del receptor.
- RF-3: CUANDO termine el emparejamiento, o si el dispositivo elegido ya estaba emparejado, EL SISTEMA conectará con él.
- RF-4: SI el usuario cancela la búsqueda o el emparejamiento, o el emparejamiento falla, ENTONCES EL SISTEMA lo indicará y seguirá mostrando la lista de receptores emparejados.

### Permiso de notificaciones (H2)
- RF-5: CUANDO se conecte un receptor por primera vez en un móvil con Android 13 o superior, EL SISTEMA pedirá el permiso de notificaciones explicando que sirve para ver la conexión con el receptor mientras la app está en segundo plano.
- RF-6: SI el usuario deniega el permiso de notificaciones, ENTONCES EL SISTEMA mantendrá igualmente la conexión en segundo plano e indicará en la sección Receptor cómo concederlo.

### Segundo plano (H2)
- RF-7: MIENTRAS haya un receptor conectado o reconectándose, EL SISTEMA mantendrá la conexión, el NTRIP y la grabación aunque la app pase a segundo plano o se apague la pantalla.
- RF-8: MIENTRAS haya un receptor conectado o reconectándose, EL SISTEMA mostrará una notificación permanente con el estado de la solución y la edad de las correcciones.
- RF-9: CUANDO el usuario pulse la notificación, EL SISTEMA abrirá la sección Receptor.
- RF-10: CUANDO el usuario pulse "Desconectar" en la notificación, EL SISTEMA cerrará la conexión con el receptor y el NTRIP.
- RF-11: CUANDO deje de haber un receptor conectado o reconectándose, EL SISTEMA quitará la notificación.
- RF-12: EL SISTEMA ofrecerá en Ajustes una ayuda con los pasos para que el fabricante del móvil no cierre la app en segundo plano, con acceso directo a los ajustes de batería de la app (RNF-T2).

### Reconexión (H3)
- RF-13: SI se pierde la conexión con el receptor sin que el usuario la haya cerrado, ENTONCES EL SISTEMA pasará al estado "Reconectando" y avisará con vibración en menos de 3 s.
- RF-14: MIENTRAS se reconecta, EL SISTEMA lo reintentará tras esperas de 1, 2, 4, 8 y 16 s y después cada 30 s (RNF-T3).
- RF-15: MIENTRAS se reconecta, EL SISTEMA mostrará el número de intentos y los segundos hasta el siguiente.
- RF-16: MIENTRAS se reconecta, EL SISTEMA ofrecerá "Reintentar ahora", que hace un intento al momento y vuelve a empezar las esperas desde 1 s.
- RF-17: CUANDO se reconecte, EL SISTEMA volverá a "Recibiendo" sin que el usuario haga nada.
- RF-18: MIENTRAS se reconecta, EL SISTEMA pausará el NTRIP y, al reconectar, lo reanudará con el mismo caster y los mismos datos sin pedirlos de nuevo (RNF-T3).
- RF-19: MIENTRAS se reconecta, EL SISTEMA seguirá grabando la sesión en curso, de modo que el corte quede dentro de la misma sesión.
- RF-20: SI tras 30 min de reintentos no se ha reconectado, ENTONCES EL SISTEMA dejará de intentarlo, mostrará "Conexión perdida" y ofrecerá reconectar.
- RF-21: CUANDO el usuario pulse "Desconectar" mientras se reconecta, EL SISTEMA dejará de reintentar.
- RF-22: SI el Bluetooth del móvil se apaga mientras se reconecta, ENTONCES EL SISTEMA lo indicará y seguirá reintentando cuando se vuelva a encender, dentro de los 30 min.
- RF-23: MIENTRAS se reconecta, el indicador de estado mostrará "Reconectando" en lugar de la solución.

### Último receptor y conexión automática (H4)
- RF-24: EL SISTEMA recordará el último receptor con el que se conectó.
- RF-25: CUANDO se abra la app con el Bluetooth encendido, el permiso concedido y el último receptor emparejado, EL SISTEMA conectará con él sin que el usuario haga nada.
- RF-26: SI la conexión automática al abrir la app falla, ENTONCES EL SISTEMA lo indicará como cualquier fallo de conexión (S002/RF-7) y no reintentará hasta que el usuario lo pida.

### Diagnóstico (H5)
- RF-27: SI el receptor rechaza la conexión en menos de 3 s, ENTONCES EL SISTEMA indicará como causa más probable que otro dispositivo, como Emlid Flow, está conectado al receptor por Bluetooth, y cómo desconectarlo.
- RF-28: SI la conexión se abre pero durante 10 s no llega ninguna frase NMEA válida, ENTONCES EL SISTEMA indicará que la salida de posición por Bluetooth del receptor puede estar desactivada y dónde activarla.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: permisos con su explicación; sin el de notificaciones, la app sigue funcionando.
  - RNF-T2: servicio en primer plano de tipo "dispositivo conectado", procesador activo mientras llegan datos, y ayuda para los fabricantes.
  - RNF-T3: estados, esperas de 1 s a 30 s, NTRIP pausado y reanudado, aviso en menos de 3 s.
  - RNF-T7: idioma.
  - RNF-T8: 56 dp, TalkBack y textos en la notificación.
  - RNF-T11: la contraseña NTRIP se guarda cifrada (S007/RF-4) y se usa también para reanudar el NTRIP.
- **Continuidad**: con la pantalla apagada durante 30 min y el receptor cerca, no hay ningún hueco de más de 2 s sin época, salvo los cortes de Bluetooth.
- **Reconexión**: cuando el receptor vuelve a estar disponible, la conexión se recupera como mucho en el siguiente intento programado (≤ 30 s).
- **Notificación**: se actualiza como mucho una vez por segundo.
- **Conexión automática**: con el receptor encendido y cerca, se ven datos en menos de 5 s desde que se abre la app.

## Casos límite
| Caso | RF |
|---|---|
| Búsqueda o emparejamiento cancelados, PIN incorrecto | RF-4 |
| Permiso de notificaciones denegado | RF-6 |
| Pantalla apagada u otra app delante durante la jornada | RF-7 |
| El usuario cierra la conexión desde la notificación | RF-10, RF-11 |
| Corte breve de Bluetooth (alejarse del jalón) | RF-13 a RF-19 |
| El receptor se apaga y no vuelve | RF-20 |
| El usuario desconecta durante la reconexión | RF-21 |
| Bluetooth del móvil apagado durante la reconexión | RF-22 |
| La app se abre con el Bluetooth apagado o sin el último receptor | RF-25 |
| Emlid Flow conectado al receptor (firmware 33) | RF-26, RF-27 |
| Receptor conectado sin salida de posición por Bluetooth | RF-28 |

## Fuera de alcance
- La pantalla siempre encendida en el recolector y en el replanteo → S010 y S017.
- Los perfiles NTRIP, la contraseña cifrada y la alarma por edad de las correcciones → S007.
- El aviso con sonido por la pérdida de FIX → S010.
- La conexión por Wi-Fi o TCP → S020.
- Conectar con el receptor cuando aparece cerca sin abrir la app → no previsto.
- Varios receptores conectados a la vez → no previsto.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita y `rf_report` no da huérfanos.
- Demo con el RS3 real:
  - emparejarlo desde la app;
  - conexión automática al abrirla;
  - 30 min con la pantalla apagada sin huecos;
  - apagar y encender el receptor, con reconexión y NTRIP reanudado;
  - desconectar desde la notificación;
  - el caso de Emlid Flow conectado.
- Probado en al menos un móvil de un fabricante que cierra apps en segundo plano (p. ej. Xiaomi o Samsung).

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: cuando otro dispositivo está conectado, el RS3 rechaza la conexión en menos de 3 s, mientras que un receptor apagado o lejos agota el plazo de 10 s. Se comprueba con el RS3 real; si no se distinguen, RF-27 pasa a ser solo una causa más de la lista de S002/RF-7.]
- [SUPOSICIÓN: la conexión automática se intenta siempre al abrir la app, aunque la última vez se desconectara a mano; si falla, no molesta y no reintenta.]

## Cambios en documentos anteriores (aplicados al aprobar esta spec)
- `docs/hoja-de-ruta.md`:
  - S004: sin "pantalla siempre encendida"; con la conexión automática al abrir y la ayuda para los fabricantes (RNF-T2).
  - S010 y S017: añaden "pantalla siempre encendida" (RNF-T2).
