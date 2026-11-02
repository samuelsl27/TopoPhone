# Spec 051 — Taludes

> Hito: H8 · Depende de: S050, S054, S042 (opcional) · Estado: borrador entrevistado (2/11/2026), pendiente de aprobación

## Contexto y objetivo
Al borde de la plataforma de una obra lineal empieza el talud: en desmonte baja hacia la plataforma, en terraplén sube desde el terreno. Donde el talud de diseño encuentra el terreno natural está el punto que marca el inicio del movimiento de tierras: la **cabeza** del talud en desmonte y el **pie** en terraplén. Ese punto no se conoce de antemano con exactitud (depende del terreno real), así que se busca en el campo: «me desplazo hacia fuera hasta que la cota de mi punta coincide con la del talud». S050 deja la plataforma; esta spec añade el talud y su replanteo.

Esta spec define los taludes de un eje, calcula el punto de talud teórico cuando se conoce el terreno, y lo replantea en vivo con un seguimiento en alzado de la punta del jalón. El talud es referencia de diseño (constitución nº 1); el punto replanteado es dato de campo.

## Usuarios / actores
- Topógrafo en campo con el jalón y el receptor en FIX (o una reproducción), que marca las cabezas y los pies de talud.

## Historias de usuario
- H1: Como topógrafo quiero definir las pendientes de desmonte y terraplén de cada lado, o una sección manual.
- H2: Como topógrafo quiero el punto de talud teórico en un PK a partir del terreno que conozco.
- H3: Como topógrafo quiero encontrar en vivo el punto de talud, viendo mi cota frente al talud en alzado.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S049, S050 y S054 más):
- **Talud**: plano inclinado que parte del **borde de explanación**, expresado como relación horizontal:vertical (por ejemplo 3:2 = 1,5 m en horizontal por 1 m en vertical).
- **Borde de explanación**: el punto exterior de la sección de diseño de un lado (o el código que se elija).
- **Punto de talud**: donde el talud corta el terreno; **cabeza** si el terreno está por encima del borde (desmonte) y **pie** si está por debajo (terraplén).
- **Altura de talud**: diferencia de cota entre el borde de explanación y el punto de talud.
- **Sección manual**: borde de explanación escrito a mano (lado, desplazamiento y cota, o cota relativa a la rasante) para un rango de PK, sin sección de S050.

### Definir los taludes (H1)
- RF-1: CUANDO el usuario elija «Taludes» en el menú de un eje, EL SISTEMA le permitirá definir por lado (izquierda y derecha) y por rango de PK la pendiente en desmonte y la pendiente en terraplén, y el código del borde de explanación (el exterior de la sección por defecto).
- RF-2: DONDE el usuario no tenga sección de S050, EL SISTEMA permitirá definir una sección manual con el desplazamiento y la cota (o la diferencia con la rasante) del borde de explanación de cada lado.
- RF-3: SI una pendiente es cero, negativa o no es un número, o dos rangos de PK se solapan en un mismo lado, ENTONCES EL SISTEMA no la guardará e indicará el motivo.
- RF-4: EL SISTEMA guardará los taludes sin redondear con el eje, los incluirá en las copias de seguridad y los paquetes (S013) y anotará cada alta y baja en el log sin coordenadas; borrar el eje los borra.

### Punto de talud teórico (H2)
- RF-5: CUANDO el usuario escriba un PK y un lado, EL SISTEMA calculará el borde de explanación (desplazamiento, cota, Este y Norte), el tipo (desmonte o terraplén), el punto de talud (desplazamiento, cota, Este y Norte) y la altura de talud.
- RF-6: EL SISTEMA tomará el terreno, por orden, del perfil de terreno de S050 de ese PK (interpolado entre los dos más próximos), de la superficie activa (S042) o, SI no hay ninguno, no calculará el punto teórico y se podrá replantear solo en vivo (RF-9).
- RF-7: SI el terreno no corta al talud a menos de 100 m del borde, ENTONCES EL SISTEMA indicará «Sin intersección con el terreno».
- RF-8: SI el terreno está a la cota del borde, ENTONCES EL SISTEMA dará el borde como punto de talud con altura cero; SI el PK queda fuera del eje, de la sección o de los taludes, ENTONCES no calculará e indicará el motivo.

### Replantear en vivo (H3)
- RF-9: CUANDO el usuario elija «Replantear taludes», EL SISTEMA pedirá los datos de S054/RF-1 (PK inicial y final, intervalo, puntos singulares) y los lados, y abrirá el replanteo con la guía de S018 hacia el punto de talud teórico o, sin él, hacia el borde de explanación.
- RF-10: MIENTRAS haya un replanteo de talud, EL SISTEMA mostrará un alzado de la sección del PK con la plataforma, el talud de diseño, el terreno (si se conoce) y la punta del jalón en su desplazamiento y su cota, en vivo.
- RF-11: MIENTRAS haya un replanteo de talud, EL SISTEMA calculará la diferencia entre la cota de la punta y la del talud de diseño en su desplazamiento, y la distancia a moverse perpendicularmente al eje, hacia dentro o fuera, para que ambas cotas coincidan.
- RF-12: MIENTRAS haya un replanteo de talud, EL SISTEMA mostrará la distancia de la punta a la línea de la sección del PK (error en PK) y apagará el aviso de llegada si supera la tolerancia horizontal.
- RF-13: CUANDO la diferencia de cota sea igual o menor que la tolerancia vertical de S019 y la punta esté en la línea de la sección, EL SISTEMA mostrará «Punto de talud encontrado» (cabeza o pie) con un sonido y una vibración.
- RF-14: DONDE el usuario fije un desplazamiento de estaca, EL SISTEMA guiará hacia un punto desplazado esa distancia fuera del punto de talud, sobre la misma sección, y dará la altura de talud y la distancia desde la estaca al punto de talud.
- RF-15: CUANDO el usuario pulse «Medir», EL SISTEMA medirá como S018/RF-26 y guardará un punto replanteado (`_stkd`, S018) con el tipo (cabeza o pie), el lado, el PK, el desplazamiento y la cota reales, la pendiente de talud, la altura de talud y la diferencia de cota con el talud.
- RF-16: EL SISTEMA incluirá los puntos de talud en el informe de replanteo (S019) con esas columnas y los dibujará en el mapa con un símbolo propio.
- RF-17: SI la solución no es la admitida, hay un corte, falta inclinación o la posición no tiene proyección sobre el eje, ENTONCES EL SISTEMA apagará la guía con los motivos de S018/RF-22 a RF-25 y S054/RF-8.
- RF-18: MIENTRAS haya una medida en marcha, EL SISTEMA no permitirá cambiar de PK, lado ni talud; al terminar, ofrecerá el siguiente PK o lado.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida en el replanteo.
  - RNF-T6: guardar puntos y taludes en una sola transacción; migración probada.
  - RNF-T7 y RNF-T8: textos en recursos; 56 dp; el alzado legible a pleno sol; cabeza, pie, dentro y fuera con palabra además de color.
  - RNF-T9: relación de pendiente en el formato del proyecto (H:V); PK y distancias en la unidad del proyecto.
  - RNF-T13: alzado y guía a 5 Hz como máximo sin bloquear el hilo principal más de 16 ms.
- Constitución nº 2: el punto de talud teórico (desplazamiento, cota, Este y Norte) con terreno de perfiles y con superficie TIN coincide con una referencia independiente a menos de 1 mm; la distancia a moverse del RF-11 a menos de 1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Terreno por encima o por debajo del borde | RF-5, RF-8 |
| Terreno paralelo al talud o sin intersección | RF-7 |
| Sin terreno conocido | RF-6, RF-9 |
| Terreno sin cota en esa posición (fuera de la superficie) | RF-6 (pasa a la fuente siguiente) |
| Pendiente inválida o rangos solapados | RF-3 |
| Cambio de desmonte a terraplén entre PK | RF-5 (el tipo se decide en cada PK) |
| Punta fuera de la sección (error en PK alto) | RF-12 |

## Fuera de alcance
- Bermas escalonadas y taludes con varias pendientes → no previsto.
- Cunetas y obras de drenaje → se entran como puntos de sección (→ S050).
- Cubicaciones y volúmenes → no previsto.
- Tomar perfiles de terreno con el receptor → S052.
- Editar la sección o los taludes en el mapa → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/ejes/` o `tools/replanteo/`: el punto de talud calculado con aritmética racional (`fractions`) por intersección exacta del talud con el perfil de terreno poligonal, y con la superficie recorriendo triángulos con otra implementación que la del móvil; el Este y el Norte con las fórmulas de S049.
- Demo en el emulador con una reproducción que sube por una sección: ver el alzado, mover la punta hasta «Punto de talud encontrado» y medir la cabeza de un desmonte y el pie de un terraplén.
- Demo de campo: queda para el usuario.

## Dudas abiertas
Confirmado en la entrevista (2/11/2026): una sola pendiente de desmonte y otra de terraplén por lado y rango de PK (las bermas quedan fuera); el terreno de partida es, por este orden, el perfil de terreno de S050 o la superficie activa de S042, y sin ninguno solo hay seguimiento en vivo; se admite un desplazamiento de estaca con valor por defecto 0.
- [SUPOSICIÓN: la sección manual también necesita un eje (para el PK y el lado).]
- [SUPOSICIÓN: las tolerancias son las de S018 (horizontal) y S019 (vertical), y el terreno se busca hasta 100 m del borde.]

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S051 depende además de S054 (replanteo) y, de forma opcional, de S042; el «perfil de terreno» sale de S050 y S052.
- `docs/glosario.md`: «Talud» (`Slope`), «Punto de talud» (`SlopeCatchPoint`), «Cabeza» y «Pie» de talud, «Borde de explanación», «Altura de talud», «Desplazamiento de estaca».
- S050: debe definir el perfil de terreno (RF-4, RF-5) y su guardado.
- S019: el informe admite las columnas de talud.
