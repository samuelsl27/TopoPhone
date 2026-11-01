# Spec 050 — Peraltes y secciones transversales

> Hito: H8 · Depende de: S054, S049 · Estado: borrador entrevistado (1/11/2026), pendiente de aprobación

## Contexto y objetivo
S054 replantea por PK y desplazamiento con la cota de la rasante, que es la cota **del eje**. Pero en una carretera nadie replantea el eje solo: se replantea «el borde de la calzada derecha en el PK 1+240», y su cota no es la de la rasante: depende del ancho, del bombeo y, en las curvas, del **peralte**. S054 y S049 lo dejaron para esta spec (→ S050).

Esta spec añade al eje las **secciones transversales de diseño** (la plataforma con sus puntos con nombre) y los **peraltes**, calcula la cota y la posición de cualquier punto de la sección en cualquier PK, interpolando entre secciones, y deja replantear por **PK y código**. Los taludes son S051 y la toma de secciones de terreno, S052. Como el eje y la rasante, la sección es referencia de diseño, no dato de campo (constitución nº 1).

## Usuarios / actores
- Topógrafo en oficina o en campo con la sección tipo y la tabla de peraltes de la obra.
- Proyectista que entrega las secciones en un LandXML o en una hoja de cálculo (CSV).

## Historias de usuario
- H1: Como topógrafo quiero definir o importar la sección de la obra y su peralte para cada tramo del eje.
- H2: Como topógrafo quiero la cota y la posición de cualquier punto de la sección en un PK, con el peralte.
- H3: Como topógrafo quiero replantear «el borde derecho cada 20 m» con su cota exacta.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec (los de S049 y S054 más):
- **Sección de diseño**: lista de **puntos de sección** ordenados de izquierda a derecha del eje (desplazamiento negativo a la izquierda, S049).
- **Código de sección**: nombre del punto de sección (hasta 12 caracteres, único en la sección). Es independiente de los códigos de levantamiento de S023.
- **Sección tipo**: sección definida por tramos desde el eje hacia cada lado: ancho (m), pendiente transversal (%) y marca «sigue el peralte».
- **Peralte**: tabla de PK con la pendiente transversal (%) de cada lado. Entre dos PK de la tabla varía linealmente; fuera del primero y del último, los tramos «siguen el peralte» usan su pendiente propia (el bombeo).
- **Punto de rasante**: el punto de la sección al que se refiere la rasante: el eje (por defecto) o un código; la sección gira alrededor de él con el peralte.
- **Perfil de terreno**: sección con puntos de terreno natural en un PK, que no es de diseño; se guarda para S051 y lo produce S052.

### Definir o importar secciones y peraltes (H1)
- RF-1: CUANDO el usuario elija «Secciones» en el menú de un eje, EL SISTEMA permitirá definir una sección tipo a mano, importar un CSV de secciones o importar las secciones de un LandXML.
- RF-2: CUANDO el usuario defina una sección tipo, EL SISTEMA le permitirá escribir los tramos de cada lado (código del punto exterior, ancho, pendiente transversal y «sigue el peralte»), el punto de rasante y el rango de PK en que se aplica.
- RF-3: EL SISTEMA admitirá varias secciones de diseño por eje, cada una con su PK; entre dos consecutivas con los mismos códigos interpolará (RF-12).
- RF-4: CUANDO el usuario importe un CSV de secciones, EL SISTEMA permitirá asignar las columnas PK, código, desplazamiento y cota (absoluta o diferencia con la rasante) y si cada fila es de diseño o de terreno, con las opciones de S028/RF-4 a RF-9.
- RF-5: CUANDO el usuario elija un LandXML, EL SISTEMA listará sus secciones por PK, permitirá elegir las de diseño y las de terreno, y asignará a los puntos sin nombre los códigos P1, P2… ordenados por desplazamiento (RNF-T5).
- RF-6: CUANDO el usuario escriba o importe un peralte, EL SISTEMA aceptará filas de PK con la pendiente izquierda y derecha, a mano o desde un CSV con las columnas asignables.
- RF-7: SI una sección tiene códigos repetidos, puntos desordenados, una pendiente de módulo mayor de 100 %, un PK fuera del eje, o un peralte con PK repetidos, ENTONCES EL SISTEMA no la guardará e indicará el punto y el motivo.
- RF-8: EL SISTEMA guardará las secciones y los peraltes sin redondear, con el eje al que pertenecen y la fecha, los incluirá en las copias de seguridad y los paquetes (S013) y anotará cada alta y baja en el log sin nombres ni coordenadas; borrar un eje los borra, sin tocar ningún punto.

### Calcular puntos de la sección (H2)
- RF-9: CUANDO el usuario escriba un PK y un código, EL SISTEMA calculará su desplazamiento, su Este y Norte, su cota (rasante más la sección más el peralte) y la pendiente transversal en ese punto.
- RF-10: CUANDO el usuario escriba un PK y un desplazamiento, EL SISTEMA dará además la cota de la sección en ese desplazamiento, y, SI está más allá de los puntos exteriores, lo indicará como «Fuera de la sección» sin cota.
- RF-11: SI el eje no tiene rasante y la sección es de cotas absolutas (CSV), ENTONCES EL SISTEMA dará la cota de la sección; SI no, solo la planta.
- RF-12: ENTRE dos secciones de diseño, EL SISTEMA interpolará linealmente con el PK el desplazamiento y la diferencia de cota de cada código; SI los códigos de las dos secciones no coinciden, ENTONCES no interpolará e indicará los códigos que faltan; FUERA del rango de secciones no habrá sección.
- RF-13: CUANDO el usuario pulse «Ver sección», EL SISTEMA dibujará en alzado la sección de diseño en el PK elegido con sus códigos, desplazamientos y cotas, y el perfil de terreno de ese PK si lo hay.
- RF-14: DONDE el usuario lo active, EL SISTEMA dibujará en el mapa una línea por código de sección sobre el eje activo.
- RF-15: CUANDO el usuario pulse «Guardar punto» en un punto calculado, EL SISTEMA lo guardará como «Calculado sobre eje» (S049/RF-22) con su código de sección.

### Replantear por PK y código (H3)
- RF-16: CUANDO el usuario elija «Replantear eje» en el modo «Por código», EL SISTEMA pedirá los datos de S054/RF-1 y uno o varios códigos, y el orden de recorrido: de PK en PK con todos los códigos, o de código en código.
- RF-17: DONDE el replanteo sea por código, EL SISTEMA tomará como puntos singulares (S054/RF-4) además los PK donde cambie la sección y los de la tabla de peralte.
- RF-18: MIENTRAS haya un replanteo por código, EL SISTEMA mostrará la guía y el desmonte o terraplén de S054/RF-6 y RF-7 con la cota de la sección en el punto objetivo, más el espesor, y su desplazamiento, su cota y su pendiente al cambiar de objetivo.
- RF-19: CUANDO el usuario guarde un punto replanteado por código, EL SISTEMA lo guardará como S054/RF-10 con el código de sección, y lo incluirá en el informe de replanteo (S019).
- RF-20: EL SISTEMA añadirá «Sección» a las referencias de cota del análisis de S054/RF-12, con la cota de la sección en el desplazamiento real de la punta del jalón.
- RF-21: SI el código no existe en la sección del PK, o el PK está fuera del rango de secciones, ENTONCES EL SISTEMA no abrirá el replanteo e indicará el motivo.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: ficheros con el selector de documentos, validados por su contenido.
  - RNF-T6: altas y bajas en una sola transacción; migración probada.
  - RNF-T7 y RNF-T8: textos en recursos; 56 dp; izquierda y derecha con palabra, nunca solo con signo.
  - RNF-T9: pendientes en % y también en la notación del proyecto; PK con el formato del proyecto.
  - RNF-T13: el replanteo se refresca a 5 Hz sin bloquear más de 16 ms.
- Constitución nº 2: Este, Norte y cota de un punto de la sección (con peralte y con interpolación) a menos de 1 mm de una referencia independiente; el peralte y las pendientes a menos de 1e-9 (relativo).
- Importar un LandXML con 2.000 secciones tarda menos de 5 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Códigos repetidos o pendiente absurda | RF-7 |
| Secciones con códigos distintos entre PK | RF-12 |
| PK antes de la primera sección o después de la última | RF-12, RF-21 |
| Desplazamiento más allá del último punto | RF-10 |
| Eje sin rasante | RF-11 |
| Punto de rasante en un borde | Términos, RF-9 |
| Peralte con una sola fila | Términos (se mantiene su pendiente fuera de ella) |
| Cambiar de eje en un replanteo por código | S054/RF-11 |

## Fuera de alcance
- Taludes (cabeza y pie) → S051.
- Tomar perfiles transversales de terreno con el receptor → S052.
- Editar secciones sobre el mapa o con gráficos → no previsto.
- Cunetas con pendiente variable, bermas escalonadas, ensanches calculados por fórmula → no previsto (se entran como puntos de sección).
- Peraltes calculados por norma (sin tabla) → no previsto.
- Exportar secciones → no previsto.
- Importar peraltes desde un LandXML → no previsto hasta disponer de un fichero real con peraltes con el que probarlo.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Referencia independiente en `tools/ejes/` (como S049): LandXML y CSV sintéticos y los valores esperados calculados aparte con aritmética racional (`fractions`) para la cota y la interpolación, y con las fórmulas de S049 (otro algoritmo que el móvil) para la planta; casos con peralte en curva, giro en un borde e interpolación entre secciones distintas.
- Demo en el emulador con una reproducción que recorre un eje con secciones: definir una sección tipo y un peralte, ver la sección en un PK y replantear el borde derecho cada 20 m con el desmonte.
- Demo de campo: queda para el usuario.

## Dudas abiertas
Confirmado en la entrevista (1/11/2026): el peralte varía linealmente entre filas y la sección gira alrededor del punto de rasante; la sección se entrega habitualmente por tramos (ancho y pendiente), con la entrada por puntos como alternativa; el replanteo por código admite recorrido por PK y por código.
- Decidido (1/11/2026): el LandXML solo se usa para secciones; los peraltes entran a mano o por CSV, porque no hay ningún LandXML con peraltes con el que comprobar el formato.
- [SUPOSICIÓN: «código de sección» es una etiqueta propia, sin relación con las bibliotecas de S023.]

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S050 depende además de S049; hay que indicar que produce perfiles de terreno para S051 y S052.
- `docs/glosario.md`: «Sección de diseño», «Código de sección» (`SectionCode`), «Peralte» (`Superelevation`), «Punto de rasante», «Perfil de terreno» (`GroundProfile`).
- S054: RF-12 (análisis) y RF-4 (puntos singulares) se amplían con RF-20 y RF-17.
- S049: «Fuera de alcance» ya apunta a esta spec; sin cambios.
