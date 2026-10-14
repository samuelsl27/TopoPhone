# Requisitos no funcionales transversales

Toda spec los cumple. Las specs los citan por su id (`RNF-T4`) en lugar de repetirlos. Si una spec necesita relajar alguno, lo justifica en su spec.

## RNF-T1 · Plataforma y permisos
- Android 8.0 (API 26) como mínimo; targetSdk el más reciente que exija Google Play.
- Bluetooth en API ≥ 31: `BLUETOOTH_CONNECT`. `BLUETOOTH_SCAN` (con `neverForLocation`) solo si se escanea. En API ≤ 30, permisos clásicos con `maxSdkVersion=30`.
- Notificaciones en API ≥ 33: `POST_NOTIFICATIONS`. `ACCESS_FINE_LOCATION` solo para el modo GNSS del móvil.
- Cada permiso se pide en el momento de usarlo, con una explicación en español. Si se deniega, la app sigue funcionando en todo lo que no dependa de él.
- No se usa `MANAGE_EXTERNAL_STORAGE`.

## RNF-T2 · Servicio en primer plano y batería
- La sesión con el receptor la mantiene un servicio en primer plano de tipo `connectedDevice` (y `location` solo en el modo GNSS del móvil), con una notificación que muestra la solución y la edad de las correcciones.
- Mientras llegan datos se mantiene un wakelock parcial. En el recolector y en el replanteo la pantalla no se apaga.
- Hay una pantalla de ayuda para los fabricantes que cierran apps en segundo plano.

## RNF-T3 · Conexión y reconexión
- Estados explícitos: Desconectado, Conectando, Recibiendo, Sin datos (más de 2 s sin época), Reconectando.
- Reconexión automática con espera exponencial de 1 s a 30 s. El NTRIP se pausa y se reanuda junto con el enlace al receptor.
- La pérdida de datos se notifica al usuario con sonido o vibración en menos de 3 s.

## RNF-T4 · Red
- El tráfico hacia el receptor (192.168.42.1 o la IP de la red local) va por la red Wi-Fi, y el NTRIP por la red con internet (datos móviles).
- HTTP sin cifrar permitido solo hacia direcciones privadas y de enlace local (Reach Panel y módulo experimental).
- NTRIP sobre TLS opcional.
- Se respeta el permiso de red local de Android en las versiones que lo exigen.

## RNF-T5 · Almacenamiento e intercambio de ficheros
- Importar con `OpenDocument`, validando por contenido y no por tipo MIME. Exportar con `CreateDocument` o compartir mediante FileProvider.
- Las copias automáticas van a una carpeta elegida por el usuario (SAF, con el permiso persistido).
- Los ficheros importados se copian al almacenamiento privado del proyecto.

## RNF-T6 · Integridad de datos
- Cada punto se guarda en una sola transacción, con durabilidad completa (nada se queda en buffer).
- Identificadores UUID estables. Las medidas brutas no se modifican nunca: las correcciones crean una versión nueva con referencia a la anterior.
- Cada versión del esquema tiene tests de migración, y las migraciones nunca destruyen datos.
- La copia automática en la nube de Android está desactivada; la transferencia entre dispositivos está permitida.

## RNF-T7 · Idioma y textos
- Español de España como idioma principal e inglés como segundo, con idioma seleccionable por app.
- Ningún texto de usuario en el código, todos en recursos.
- Términos según `docs/glosario.md`.

## RNF-T8 · Uso en campo y accesibilidad
- Tema "modo sol" con contraste ≥ 7:1. Zonas táctiles ≥ 56 dp. Coordenadas en tipografía grande y monoespaciada.
- El estado de la solución se indica siempre con color **y** texto o icono, nunca solo con color.
- Sonido y vibración para la pérdida de FIX y la proximidad en el replanteo. Etiquetas de TalkBack en todos los controles.

## RNF-T9 · Unidades y formatos numéricos
- Internamente: metros, radianes y UTC.
- Unidades lineales: m, ft internacional (0,3048 m) y ft topográfico de EE. UU. (1200/3937 m). Unidades angulares: DD, DMS y gon.
- Azimut en sentido horario desde el norte.
- Distancias de cuadrícula o de terreno según el factor combinado.
- La interfaz usa la configuración regional del usuario y los ficheros usan `Locale.ROOT` (punto decimal), salvo que el usuario elija otra cosa al exportar.

## RNF-T10 · Tiempo
- Las marcas de tiempo de las medidas salen de la hora UTC del receptor (ZDA/RMC), no del reloj del móvil.
- Se guardan en UTC junto con la zona horaria del dispositivo y se muestran en hora local.

## RNF-T11 · Secretos
- Las contraseñas NTRIP se cifran con una clave del Android Keystore (AES-GCM) y nunca aparecen en logs, exportaciones ni informes, salvo que el usuario lo indique expresamente.

## RNF-T12 · Diagnóstico
- Cada sesión graba el NMEA y el RTCM brutos en ficheros rotativos de tamaño limitado, configurable.
- Log de la app.
- El "informe de sistema" exportable reúne ambos sin contraseñas.
- Las grabaciones sirven como fixtures de test.

## RNF-T13 · Rendimiento
- La interfaz se refresca a 5 Hz como máximo a partir de un flujo de datos a 10 Hz como máximo, sin bloquear el hilo principal más de 16 ms.
- El tiempo de arranque en frío hasta la pantalla principal es inferior a 2 s en un móvil de gama media.

## RNF-T14 · Licencias y marca
- TopoPhone se publica con licencia **GPL-3.0** en un repositorio público. Toda dependencia del APK debe ser compatible con GPL-3.0 (Apache-2.0, MIT, BSD, LGPL, GPL-3.0); no se admiten GPL-2.0-only ni licencias privativas.
- El repositorio no contiene documentos de terceros con derechos reservados (manuales, PDF de Emlid): se guardan solo en local y en el repositorio se enlazan.
- Atribución visible de los datos (IGN/CNIG CC-BY 4.0, Catastro, OSM ODbL).
- Ningún recurso, icono, texto ni marca de Emlid. La app no lleva "Emlid" en el nombre.
