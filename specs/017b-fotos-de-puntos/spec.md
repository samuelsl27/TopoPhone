# Spec 017b — Fotos de los puntos

> Hito: H2 · Depende de: S017 · Estado: borrador (pendiente de aprobación)

## Contexto y objetivo
Un punto de campo rara vez se entiende solo con sus coordenadas: la foto de un vértice, de la arqueta que se ha medido o del detalle que justifica una cota evita volver a la obra. Hoy el punto guarda su posición, su calidad y sus frases NMEA, pero nada de lo que se vio.

Esta spec añade fotos a los puntos, varias por punto: se hacen con la cámara del móvil o se eligen de la galería, desde el recolector (durante la medida o justo después de guardar) y desde la ficha (ver, añadir y borrar). Las fotos pesan mucho más que un punto, así que se guardan reducidas y con tope, y viajan con el punto en la papelera, en las copias de seguridad y en los paquetes, sin multiplicar por 24 el espacio de las copias automáticas.

Los datos de campo siguen siendo sagrados (constitución nº 1): una foto añadida se guarda al instante y no se pierde sin rastro; borrarla pide confirmación y queda anotado en el historial del punto.

## Usuarios / actores
- Topógrafo en campo, con el móvil en la mano y el jalón en la otra, que fotografía el punto que acaba de medir.
- Técnico en oficina que revisa un punto y quiere ver qué había allí.

## Historias de usuario
- H1: Como topógrafo quiero hacer una foto del punto mientras lo mido o justo después de guardarlo, sin salir del recolector.
- H2: Como técnico quiero ver, añadir y borrar las fotos de un punto desde su ficha, y saber qué se ha borrado.
- H3: Como topógrafo quiero que las fotos viajen con el punto (papelera, copias de seguridad y paquetes) sin que las copias automáticas se vuelvan enormes.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Foto de un punto**: una imagen asociada a un punto, medido o manual, hecha con la cámara del móvil o elegida de su galería.
- **Fecha de la foto**: la de sus metadatos y, si no los tiene, la de cuando se añadió.
- **Último punto guardado**: el último que se ha guardado desde el recolector abierto, sea manual o del modo automático.

### Añadir fotos desde el recolector (H1)
- RF-1: EL SISTEMA ofrecerá en el recolector "Foto", que abre la cámara del móvil, y "Galería", que deja elegir fotos del móvil, para añadirlas a un punto.
- RF-2: MIENTRAS haya una medida de un punto en marcha, también en pausa, EL SISTEMA añadirá las fotos al punto que se está midiendo, y se guardarán con él cuando se guarde.
- RF-3: CUANDO el usuario pulse "Cancelar" en una medida que tiene fotos, EL SISTEMA pedirá confirmación diciendo cuántas fotos se descartan.
- RF-4: SI una medida termina sin guardar su punto (cancelada, fallida, o fuera de tolerancia sin que el usuario la confirme), ENTONCES EL SISTEMA descartará las fotos que se añadieron durante ella.
- RF-5: MIENTRAS no haya una medida en marcha ni el modo automático, EL SISTEMA añadirá las fotos al último punto guardado, hasta que empiece otra medida o se cierre el recolector.
- RF-6: MIENTRAS el modo automático esté en marcha, EL SISTEMA añadirá las fotos al último punto que haya guardado, aunque haya otro en medida.
- RF-7: SI no hay ningún punto al que añadir la foto, ENTONCES EL SISTEMA mostrará "Foto" y "Galería" desactivados, con el motivo.
- RF-8: EL SISTEMA dirá en el recolector a qué punto irá la foto y cuántas fotos tiene ya, de un máximo de 10.

### Ver y gestionar las fotos en la ficha (H2)
- RF-9: EL SISTEMA mostrará en la ficha de un punto, medido o manual, sus fotos en miniatura, en el orden en que se añadieron, con su número.
- RF-10: CUANDO el usuario toque una miniatura, EL SISTEMA abrirá la foto a pantalla completa, con zoom, y permitirá pasar a las otras fotos del punto.
- RF-11: EL SISTEMA mostrará con la foto abierta su fecha y hora, si es de cámara o de galería y su tamaño.
- RF-12: CUANDO el usuario pulse "Foto" o "Galería" en la ficha, EL SISTEMA añadirá la foto al punto de la ficha.
- RF-13: CUANDO el usuario elija varias fotos en la galería, EL SISTEMA añadirá las que quepan hasta las 10 del punto e indicará cuántas se han quedado fuera.
- RF-14: SI el punto ya tiene 10 fotos, ENTONCES EL SISTEMA no añadirá más e indicará el límite.
- RF-15: SI la foto no se puede leer o guardar (sin espacio, fichero dañado), ENTONCES EL SISTEMA no la añadirá, indicará el motivo y dejará el punto como estaba.
- RF-16: SI el usuario cierra la cámara o la galería sin elegir ninguna foto, ENTONCES EL SISTEMA no cambiará nada.
- RF-17: SI el fichero de una foto guardada no se puede leer al abrirla, ENTONCES EL SISTEMA mostrará en su lugar un aviso con el nombre de la foto, sin afectar a las demás.

### Guardado de las fotos (H3)
- RF-18: CUANDO se añada una foto, EL SISTEMA la guardará reducida a 2.048 px de lado largo como máximo, sin ampliar las menores, y conservará sus metadatos tal como venían, ubicación del móvil incluida.
- RF-19: EL SISTEMA no modificará ni borrará nunca las fotos originales de la galería del móvil.
- RF-20: CUANDO se añada una foto, EL SISTEMA la guardará en el almacenamiento privado de la app (RNF-T5), con su fecha, su origen y su tamaño, y de forma atómica: no quedará una foto a medias.
- RF-21: EL SISTEMA no mirará si una foto está repetida: la misma foto añadida dos veces cuenta como dos.

### Borrar y rastro (H2)
- RF-22: CUANDO el usuario borre una foto desde la ficha, EL SISTEMA pedirá confirmación y la eliminará de forma definitiva.
- RF-23: CUANDO se añada o se borre una foto de un punto ya guardado, EL SISTEMA lo anotará en su historial (S011/RF-10) con la fecha y hora del cambio y con el nombre y la fecha de la foto, sin marcar el punto como editado (S011/RF-12).
- RF-24: CUANDO se borre un punto a la papelera (S011/RF-22), EL SISTEMA llevará sus fotos con él, las devolverá al restaurarlo (S011/RF-24) y las eliminará con él al borrarlo definitivamente (S011/RF-26).
- RF-25: CUANDO se duplique un punto (S011/RF-21), EL SISTEMA no copiará sus fotos.
- RF-26: EL SISTEMA marcará en la lista de objetos, con un icono y un texto, los puntos que tienen fotos y cuántas (S011/RF-3).
- RF-27: EL SISTEMA mostrará en los datos del proyecto cuántas fotos tiene y cuánto espacio ocupan.

### Copias de seguridad y paquetes (H3)
- RF-28: EL SISTEMA incluirá las fotos de los puntos, también las de los puntos de la papelera, en la copia de seguridad completa (S013/RF-2) y en la copia del estado actual que se guarda antes de restaurar (S013/RF-7), y las devolverá al restaurar (S013/RF-8) y al deshacer la restauración (S013/RF-11).
- RF-29: CUANDO el usuario exporte un paquete (S013/RF-24), EL SISTEMA incluirá las fotos de los puntos del proyecto y de su papelera de puntos y, al importarlo (S013/RF-25), las añadirá con sus puntos.
- RF-30: MIENTRAS las copias automáticas estén activas, EL SISTEMA copiará cada foto nueva una sola vez a una carpeta propia dentro de la carpeta de copias, sin repetirla en cada copia, y las copias automáticas (ZIP) no la llevarán dentro.
- RF-31: EL SISTEMA conservará en esa carpeta cada foto mientras la use alguna de las 24 copias automáticas guardadas (S013/RF-19) y la borrará cuando ninguna la use, sin tocar ningún otro fichero de la carpeta.
- RF-32: CUANDO el usuario restaure una copia automática, EL SISTEMA recuperará las fotos de sus puntos de esa carpeta.
- RF-33: SI falta alguna foto de una copia automática en esa carpeta, ENTONCES EL SISTEMA restaurará el punto sin ella e indicará cuántas fotos no se han podido recuperar.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T1: la cámara y la galería se usan en el momento de usarlas, con una explicación en español; si se deniega la cámara, "Galería" sigue funcionando y a la inversa, y el resto de la app no cambia.
  - RNF-T5: las fotos añadidas se copian al almacenamiento privado de la app; las que van en copias y paquetes se escriben con el selector de ficheros o se comparten, sin dejar un fichero a medias (S013/RF-5).
  - RNF-T6: cada foto se guarda al instante, de forma atómica y con durabilidad completa; borrar una foto nunca borra la medida bruta del punto; al actualizar la app los puntos existentes se conservan sin fotos (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; TalkBack lee "Foto 2 de 5 del punto 12" en cada miniatura y los avisos de límite y de error.
  - RNF-T12: las altas, las bajas y los errores de fotos van al log (S014/RF-5), sin la ruta de los ficheros del usuario.
  - RNF-T13: reducir y guardar una foto no bloquea el hilo principal más de 16 ms y termina en menos de 2 s en el móvil de referencia.
- La ficha de un punto con 10 fotos se abre en menos de 1 s en el móvil de referencia: las miniaturas no cargan las fotos a tamaño completo.
- Una foto reducida a 2.048 px ocupa de 0,5 a 1 MB según la escena; un proyecto de 200 puntos con 10 fotos cada uno pasa de 1 GB, y el aviso de espacio de S013/RF-5 lo cubre.

## Casos límite
| Caso | RF |
|---|---|
| Foto con una medida instantánea o promediada en marcha | RF-2 |
| Cancelar una medida con fotos | RF-3, RF-4 |
| Medida que falla por corte o por falta de datos | RF-4 |
| Punto fuera de tolerancia pendiente de confirmar | RF-2, RF-4 |
| Foto con el modo automático en marcha | RF-6 |
| Foto al empezar a trabajar, sin ningún punto medido | RF-7 |
| Foto justo después de guardar | RF-5 |
| Foto para un punto manual o uno de antes de esta versión | RF-9, RF-12 |
| Más de 10 fotos | RF-13, RF-14 |
| Cámara o galería cerradas sin elegir | RF-16 |
| Sin espacio o fichero dañado al añadir | RF-15 |
| Foto de la galería con la ubicación de otro sitio | RF-18 (se conserva, por decisión del usuario) |
| Foto original de la galería | RF-19 (no se toca) |
| Fichero de una foto guardada que ya no se puede leer | RF-17 |
| Borrar una foto | RF-22, RF-23 |
| Borrar un punto con fotos, restaurarlo o vaciar la papelera | RF-24 |
| Duplicar un punto con fotos | RF-25 |
| Copia manual, restauración y deshacer con fotos | RF-28 |
| Paquete con fotos, importado en otro móvil | RF-29 |
| Restaurar una copia con el recolector midiendo | S013/RF-16 |
| Copia automática con muchas fotos | RF-30, RF-31 |
| Foto borrada que sigue en alguna copia automática | RF-31 (se conserva hasta que rotan las copias) |
| Foto que falta en la carpeta de copias automáticas | RF-33 |
| Paquete o copia de una versión anterior, sin fotos | S013/RF-14 |
| Proyecto con muchas fotos y poco espacio | RF-15, S013/RF-5 |

## Fuera de alcance
- Exportar las fotos en un ZIP, y fotos o enlaces en KML/KMZ → S031.
- Una columna con las fotos en el CSV → S031.
- Compartir una foto suelta desde la ficha → no previsto.
- Dibujar, anotar, recortar o girar una foto, o ponerle un pie de texto → no previsto.
- Reordenar las fotos de un punto → no previsto.
- Fotos de líneas, polígonos o del proyecto, y fotos con códigos y atributos → S024, S025.
- Vídeo y audio → no previsto.
- Quitar la ubicación de las fotos antes de compartirlas → no previsto (las fotos conservan sus metadatos por decisión del usuario).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Test de la copia completa, la restauración, el deshacer y el paquete con fotos, y de la carpeta de copias automáticas con la rotación de las 24 copias.
- Demo en el emulador:
  - una foto durante una medida y otra justo después de guardar;
  - añadir de la galería y varias a la vez desde la ficha, con el límite de 10;
  - ver a pantalla completa, borrar una con el rastro en el historial;
  - borrar un punto con fotos, restaurarlo y comprobar que las conserva;
  - una copia manual con fotos, restaurarla y deshacer.
- Pruebas de campo con el móvil real (del usuario): fotos con la cámara en un proyecto de campo y una copia automática con unos 100 puntos con fotos.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: no se avisa al exportar un paquete o una copia de que las fotos llevan la ubicación del móvil; el usuario eligió conservar los metadatos tal cual.]
- [SUPOSICIÓN: si Android cierra la app mientras la cámara está abierta, la foto se añade al punto al volver si el punto sigue existiendo, y si no se pierde.]
- [SUPOSICIÓN: el máximo de 10 fotos por punto y los 2.048 px de lado largo son fijos; no se eligen en Ajustes.]
- [SUPOSICIÓN: las fotos de un punto se ven en el orden en que se añadieron y no se reordenan.]
- [SUPOSICIÓN: las fotos de las copias manuales y de los paquetes no se reducen más; el peso de un paquete con muchas fotos lo decide el usuario.]
- [SUPOSICIÓN: una foto borrada sigue en la carpeta de las copias automáticas hasta que rotan las 24 copias que la usan (una por hora con cambios, como mucho); borrarla en el móvil no la borra de la carpeta al instante.]
