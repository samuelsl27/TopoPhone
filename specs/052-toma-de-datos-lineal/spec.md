# Spec 052 — Toma de datos lineal

> Hito: H8 · Depende de: S054, S050, S049, S010, S017 · Estado: pendiente de aprobación (entrevistada 02/11/2026)

## Contexto y objetivo
Hasta ahora el eje venía siempre de fuera (S049) y el trabajo era replantearlo (S054, S050, S051). Pero en muchos trabajos el camino inverso: no hay proyecto y hay que **levantar lo que existe**: el eje de un camino, de una tubería o de un canal; el perfil longitudinal del terreno a lo largo de un eje; o la sección transversal del terreno en un PK, para que el proyectista diseñe o para calcular el talud (S051). Hoy esto se haría midiendo puntos sueltos y calculando PK y desplazamiento a mano.

Esta spec añade tres tomas de datos con el receptor: **tomar un eje**, **perfil longitudinal** y **perfil transversal**. Todo lo que se mide son puntos normales del proyecto (S010), guardados al instante y sin tocarse (constitución nº 1); el eje, los perfiles y sus gráficos se derivan de ellos y se pueden rehacer. Un eje tomado es un eje más (S049): se activa, se ve en el mapa y se replantea (S054).

## Usuarios / actores
- Topógrafo que levanta con el jalón y el receptor en FIX (o una reproducción) un trazado, un perfil o una sección.

## Historias de usuario
- H1: Como topógrafo quiero tomar un eje midiendo sus vértices y obtener un eje del proyecto.
- H2: Como topógrafo quiero tomar el perfil longitudinal del terreno a lo largo de un eje y compararlo con su rasante.
- H3: Como topógrafo quiero tomar el perfil transversal del terreno en un PK y compararlo con la sección de diseño.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S049, S050 y S054 más):
- **Toma**: una sesión de medida de uno de los tres tipos; guarda de qué puntos está hecha.
- **Eje tomado**: un eje cuyas rectas pasan por los puntos medidos de una toma, en el orden en que se tomaron.
- **Perfil longitudinal**: lista de puntos del terreno con su PK y su cota, a lo largo de un eje, con sus desplazamientos pequeños.
- **Perfil transversal**: puntos del terreno de un PK ordenados por desplazamiento; al guardarlo es un **perfil de terreno** de S050.
- **Tolerancia de eje**: distancia máxima al eje (0,50 m por defecto, editable en cada toma) con que un punto entra en un perfil longitudinal.
- **Tolerancia de sección**: distancia máxima a la línea de la sección (0,10 m por defecto, editable en cada toma) con que un punto se considera del PK.

### Tomar un eje (H1)
- RF-1: CUANDO el usuario elija «Tomar eje», EL SISTEMA abrirá el recolector (S010) en una toma de eje, con el nombre propuesto del siguiente punto, los modos de medida de S010 y S017 (instantáneo, promediado, automático por distancia) y el control de precisión del proyecto.
- RF-2: CUANDO el usuario mida un punto en una toma de eje, EL SISTEMA lo guardará como cualquier punto medido (S010/RF-14 y siguientes) y lo añadirá a la toma; el punto no depende de ella.
- RF-3: MIENTRAS haya una toma de eje, EL SISTEMA dibujará en el mapa los puntos de la toma unidos en el orden de medida y mostrará la longitud acumulada.
- RF-4: CUANDO el usuario pulse «Quitar último» en una toma de eje, EL SISTEMA lo excluirá de la toma, sin borrar el punto del proyecto.
- RF-5: CUANDO el usuario pulse «Terminar» con al menos 2 puntos, EL SISTEMA pedirá el nombre, el PK inicial y, opcionalmente, el radio de curva de cada vértice (S049/RF-8), y creará un eje con rectas por los puntos (y los arcos de los radios dados), con la longitud y el resumen de la toma.
- RF-6: SI dos puntos consecutivos de la toma distan menos de 1 cm, o la toma tiene menos de 2 puntos, o un radio no cabe entre sus vértices, ENTONCES EL SISTEMA no creará el eje e indicará el vértice y el motivo.
- RF-7: DONDE los puntos de la toma tengan cota y el usuario lo pida, EL SISTEMA creará también una rasante con ellos (S049/RF-7), sin acuerdos.
- RF-8: EL SISTEMA guardará el eje tomado como cualquier otro (S049/RF-13), con el origen «Tomado en campo», una copia sin redondear de las coordenadas y la referencia a sus puntos, no lo activará si ya hay un eje activo y borrar el eje no borrará sus puntos.
- RF-9: SI la app se cierra o el móvil se reinicia durante una toma, ENTONCES EL SISTEMA conservará los puntos medidos y la toma y permitirá reanudarla o terminarla.

### Perfil longitudinal (H2)
- RF-10: CUANDO el usuario elija «Perfil longitudinal» en el eje activo, EL SISTEMA permitirá el modo «Guiado» (PK inicial, final e intervalo, con la guía de replanteo de S054 hacia cada PK sobre el eje) o el modo «Libre» (caminar por el eje con el modo automático por distancia de S017).
- RF-11: CUANDO el usuario mida un punto en un perfil longitudinal, EL SISTEMA lo guardará como punto medido con su PK, su desplazamiento reales (S054/RF-12) y la diferencia de cota con la rasante.
- RF-12: SI el desplazamiento de un punto supera la tolerancia de eje, o no tiene proyección sobre el eje, ENTONCES EL SISTEMA lo guardará igualmente pero no lo incluirá en el perfil, y lo mostrará con «Fuera del eje».
- RF-13: EL SISTEMA dibujará el perfil longitudinal en alzado, con PK en horizontal y cota en vertical, con la rasante si el eje la tiene y la diferencia (desmonte o terraplén) en cada punto, y permitirá acercar y desplazar el gráfico.
- RF-14: CUANDO el usuario elija una selección de puntos del proyecto (S011) y «Perfil longitudinal», EL SISTEMA construirá el perfil con ellos proyectándolos sobre el eje activo.
- RF-15: CUANDO el usuario pulse «Exportar», EL SISTEMA generará un CSV con PK, desplazamiento, cota del terreno, cota de la rasante y diferencia, para guardar o compartir (RNF-T5).

### Perfil transversal (H3)
- RF-16: CUANDO el usuario elija «Perfil transversal» e indique un PK del eje activo, EL SISTEMA guiará hacia ese PK en el eje (S054) y, al llegar, mostrará la línea de la sección y la distancia de la punta a ella (error en PK).
- RF-17: CUANDO el usuario mida un punto en un perfil transversal, EL SISTEMA lo guardará como punto medido con el PK y el desplazamiento reales, el PK de la sección, un código de sección o texto (opcional, el de S050 si existe) y la diferencia de cota con la sección de diseño.
- RF-18: SI el error en PK de un punto supera la tolerancia de sección, ENTONCES EL SISTEMA lo guardará igualmente pero lo marcará «Fuera de sección» y no lo incluirá en el perfil.
- RF-19: MIENTRAS haya un perfil transversal, EL SISTEMA dibujará en alzado los puntos medidos ordenados por desplazamiento y, si existe, la sección de diseño de S050 en ese PK.
- RF-20: CUANDO el usuario pulse «Guardar perfil», EL SISTEMA guardará un perfil de terreno del eje en ese PK (S050) con los puntos incluidos; SI ya hay uno en ese PK, ENTONCES lo conservará en el historial y creará uno nuevo.
- RF-21: EL SISTEMA permitirá quitar un punto de un perfil (longitudinal o transversal) sin borrarlo del proyecto, y volver a medirlo creando otro punto.
- RF-22: EL SISTEMA listará los perfiles tomados (tipo, eje, PK, fecha y número de puntos), permitirá verlos, exportarlos a CSV y borrarlos con confirmación, sin tocar los puntos.
- RF-23: EL SISTEMA incluirá los ejes tomados, las tomas y los perfiles en las copias de seguridad y los paquetes (S013) y anotará cada alta y baja en el log sin nombres ni coordenadas.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T2: pantalla encendida en todas las tomas.
  - RNF-T3 y RNF-T6: cada punto se guarda al instante y de forma atómica aunque se pierda la conexión.
  - RNF-T7 y RNF-T8: textos en recursos; 56 dp; izquierda y derecha con palabra; los gráficos legibles a pleno sol y con TalkBack en sus controles.
  - RNF-T9: PK, cotas y pendientes con el formato del proyecto.
  - RNF-T13: gráficos y guía a 5 Hz como máximo sin bloquear el hilo principal más de 16 ms; un perfil de 2.000 puntos se dibuja en menos de 100 ms.
- Constitución nº 2: el eje tomado, con vértices y radios dados, coincide con una referencia independiente (la construcción de S049/RF-8) a menos de 1 mm; el PK y el desplazamiento de cada punto de los perfiles a menos de 1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Menos de 2 puntos, puntos duplicados o radio que no cabe | RF-6 |
| Punto quitado de la toma | RF-4, RF-21 |
| Cierre de la app a mitad de una toma | RF-9 |
| Punto fuera de tolerancia de eje o de sección | RF-12, RF-18 |
| Eje sin rasante | RF-13 (solo terreno) |
| Perfil ya guardado en ese PK | RF-20 |
| Pérdida de FIX durante una toma | S010, S047 |
| Medida sin proyección sobre el eje | RF-12 |

## Fuera de alcance
- Ajuste automático de arcos y clotoides a puntos medidos → no previsto.
- Cubicaciones y comparación de volúmenes entre perfiles → no previsto.
- Exportar perfiles a LandXML o DXF → S029 (DXF); LandXML no previsto.
- Taludes a partir del perfil → S051.
- Levantar líneas con códigos y atributos → S025.
- Edición de ejes tomados → no previsto (como S049).

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/ejes/` (como S049): reproducciones sintéticas que recorren un eje conocido con desplazamientos y alturas dados, con el PK, el desplazamiento y la cota esperados calculados aparte (fórmulas de S049, aritmética racional) y un eje tomado a partir de vértices de un tramo conocido comprobado contra `pi-vertices.csv`.
- Demo en el emulador: tomar un eje con una reproducción, activarlo y replantearlo; tomar un perfil longitudinal guiado y uno transversal y verlos en alzado.
- Demo de campo: queda para el usuario.

## Decisiones de la entrevista (02/11/2026)
- El eje tomado pasa por los puntos con rectas, con arcos solo si el usuario da el radio de un vértice; no hay ajuste automático.
- Los puntos de una toma son puntos medidos normales; la toma solo los agrupa.
- Tolerancia de eje de 0,50 m y de sección de 0,10 m por defecto, editables al empezar cada toma.
- Un perfil transversal guardado se convierte en perfil de terreno de S050 para S051; el anterior del mismo PK queda en el historial.
- La rasante del eje tomado solo se crea si el usuario lo pide al terminar la toma.
- Se mantienen los modos guiado, libre y desde selección de puntos del perfil longitudinal.
- Sin dudas abiertas.

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S052 depende además de S049, S010 y S017.
- `docs/glosario.md`: «Toma» (`LinearSurvey`), «Eje tomado», «Perfil longitudinal» (`LongitudinalProfile`), «Perfil transversal» (`CrossProfile`), «Tolerancia de eje», «Tolerancia de sección»; en «Eje», que no es solo referencia de diseño.
- S049: un eje puede tener el origen «Tomado en campo»; su RF-14 sobre el eje activo no cambia.
- S050: define el perfil de terreno que esta spec guarda.
- S012 y S053: considerar añadir las columnas PK, desplazamiento y código de sección a los puntos tomados sobre un eje.
