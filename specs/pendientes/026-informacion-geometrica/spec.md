# Spec 026 — Información geométrica

> Hito: H3 · Depende de: S025, S040, S008 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Después de levantar una línea o una parcela, lo primero que pregunta el cliente es cuánto mide: cuántos metros de bordillo, cuántos metros cuadrados de parcela, qué pendiente tiene la zanja. Hoy la app guarda los vértices (S025) pero no calcula nada con ellos, y el técnico lo hace después en QGIS o en una hoja de cálculo.

Esta spec calcula en el móvil la **información geométrica** de una línea, un polígono o tres puntos: longitud en 2D y en 3D, perímetro, área, azimut, desnivel y pendiente de cada segmento, y el ángulo entre tres puntos, con distancias de cuadrícula o de terreno a elegir. Solo calcula y muestra: no modifica ningún dato del proyecto (constitución nº 1) y cada cifra se contrasta con una referencia independiente (nº 2). S041 dejó aquí la información de las líneas de un dibujo y las distancias de terreno.

## Usuarios / actores
- Topógrafo que quiere una cifra al terminar de levantar una línea o una parcela.
- Técnico que lleva la tabla de segmentos a una hoja de cálculo.

## Historias de usuario
- H1: Como topógrafo quiero ver la longitud, el perímetro y el área de lo que acabo de levantar.
- H2: Como topógrafo quiero una tabla con la longitud, el azimut y la pendiente de cada segmento.
- H3: Como topógrafo quiero el ángulo que forman tres puntos.
- H4: Como técnico quiero elegir entre distancias de cuadrícula y de terreno, y llevarme la tabla a una hoja de cálculo.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Segmento**: el trozo de línea entre dos vértices consecutivos; un polígono tiene tantos segmentos como vértices, incluido el de cierre.
- **Distancia de cuadrícula**: la medida en el plano del SC del proyecto (como S041/RF-15). **Distancia de terreno**: la de cuadrícula dividida por el factor combinado (S008).
- **Longitud 2D**: la suma de las distancias horizontales de los segmentos; **longitud 3D**: la suma de las distancias inclinadas, con la altura de los vértices. **Perímetro**: la longitud 2D de un polígono.
- **Área**: la superficie horizontal que encierra un polígono, calculada con la distancia elegida (de terreno: la de cuadrícula entre el factor combinado al cuadrado).
- **Azimut** de un segmento: el ángulo horario desde el norte de cuadrícula, en el sentido de la línea (RNF-T9).
- **Pendiente** de un segmento: el desnivel entre sus extremos dividido por su distancia horizontal, en porcentaje.
- **Ángulo entre tres puntos**: el ángulo en el punto central B entre las direcciones a A y a C, de 0° a 180°.

### Información de una línea o un polígono (H1, H2)
- RF-1: CUANDO el usuario pulse "Información" en la ficha de una línea o un polígono del proyecto (S025), de una línea de un dibujo (S041/RF-2) o en su menú del mapa, EL SISTEMA mostrará su longitud 2D, su longitud 3D, su número de vértices y segmentos, y, si es un polígono, su perímetro y su área.
- RF-2: EL SISTEMA mostrará una tabla con una fila por segmento: número, vértice inicial y final, PK al inicio, longitud 2D, longitud 3D, azimut, desnivel y pendiente.
- RF-3: SI algún vértice no tiene altura, ENTONCES EL SISTEMA no mostrará la longitud 3D total ni el desnivel y la pendiente de los segmentos afectados, e indicará los segmentos sin altura; la longitud 2D y el área no dependen de la altura.
- RF-4: SI una línea de un dibujo no tiene cota (S040), ENTONCES EL SISTEMA mostrará solo las magnitudes en planta.
- RF-5: MIENTRAS la información de una línea del proyecto esté abierta y se edite un vértice o un punto (S025, S011), EL SISTEMA recalculará todas las cifras.
- RF-6: SI los segmentos de un polígono se cruzan entre sí, ENTONCES EL SISTEMA lo avisará y mostrará el área sin garantía de ser la de un polígono simple.
- RF-7: SI la línea tiene menos de 2 vértices, ENTONCES EL SISTEMA no calculará nada e indicará el motivo.
- RF-8: CUANDO el usuario pulse "Exportar tabla", EL SISTEMA escribirá la tabla de segmentos en un CSV en UTF-8, con punto decimal y las unidades del proyecto indicadas en la cabecera (RNF-T9), y la guardará con el selector de ficheros o la compartirá con el FileProvider (RNF-T5).

### Distancias de cuadrícula o de terreno (H4)
- RF-9: EL SISTEMA permitirá elegir en la información "Cuadrícula" o "Terreno", recordará la elección para todos los proyectos y mostrará siempre cuál está activa.
- RF-10: DONDE se elija "Terreno", EL SISTEMA dividirá cada distancia de cuadrícula por el factor combinado de S008 calculado en el centro del segmento, con la altura de los vértices o, si no la tienen, con la del proyecto.
- RF-11: DONDE el SC del proyecto sea geográfico, EL SISTEMA calculará las distancias en el plano local de S041/RF-15 y lo indicará.
- RF-12: EL SISTEMA mostrará las cifras en las unidades lineales y angulares del proyecto (m², ft² y usft² para el área; el azimut en DD, DMS o gon) y la pendiente siempre en porcentaje.

### Ángulo entre tres puntos (H3)
- RF-13: CUANDO el usuario elija "Ángulo" en el menú de la lista de objetos o del mapa, EL SISTEMA le pedirá tres puntos del proyecto (o tres vértices de una línea) como A, B y C, desde la lista o tocándolos.
- RF-14: EL SISTEMA mostrará el ángulo en B entre las direcciones a A y a C, y los azimuts de BA y BC, y la distancia BA y BC con la distancia activa.
- RF-15: SI dos de los tres puntos tienen las mismas coordenadas horizontales, ENTONCES EL SISTEMA no calculará el ángulo e indicará cuáles.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; la tabla se lee a pleno sol con tipografía monoespaciada.
  - RNF-T9: unidades del proyecto en pantalla y `Locale.ROOT` en el fichero; azimut horario desde el norte.
  - RNF-T13: la información de una línea de 100.000 vértices se calcula en menos de 1 s y la tabla se desplaza sin bloquear el hilo principal más de 16 ms.
- Constitución nº 2: la longitud, el perímetro, el área, el azimut, la pendiente y el ángulo coinciden con una referencia independiente (pyproj y un cálculo aparte) a menos de 1 mm, 1 mm² y 1 µrad, con distancias de cuadrícula y de terreno.
- Solo lee: no modifica ningún dato del proyecto ni de los dibujos.

## Casos límite
| Caso | RF |
|---|---|
| Línea con vértices sin altura, o dibujo sin cotas | RF-3, RF-4 |
| Línea de un solo segmento y de 100.000 vértices | RF-2, RNF-T13 |
| Polígono con cruces | RF-6 |
| Línea abierta: sin área ni perímetro | RF-1 |
| Dos puntos coincidentes en el ángulo | RF-15 |
| Segmento vertical (distancia horizontal 0): pendiente indefinida | RF-2 (se muestra «—») |
| Proyecto geográfico | RF-11 |
| Elegir terreno con un SC sin factor combinado | RF-10 |
| Cambiar la unidad lineal del proyecto | RF-12 |

## Fuera de alcance
- Inversa, poligonal e intersecciones con resultado guardado como punto → S038.
- Cambiar el proyecto entero a distancias de terreno (el SC de terreno del proyecto) → S035.
- Medir sobre el mapa con el dedo sin puntos → no previsto.
- Área sobre el elipsoide o geodésica → no previsto.
- Volúmenes y áreas de una superficie → no previsto.
- Guardar el resultado como informe o añadirlo a la ficha de la línea → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests de longitud, área, azimut, pendiente y ángulo frente a un cálculo independiente con pyproj (≤ 1 mm) sobre líneas rectas, un polígono cóncavo y uno con cruces, en cuadrícula y en terreno.
- Demo en el emulador:
  - la información de una línea con y sin alturas, y la de un polígono con su área;
  - cambiar de cuadrícula a terreno y ver el cambio;
  - el ángulo entre tres puntos y la tabla exportada a CSV abierta en QGIS o en una hoja de cálculo.
- Demo de campo: queda para el usuario (una parcela medida con el RS3 y comparada con su área en catastro).

## Dudas abiertas
- [SUPOSICIÓN: la longitud 3D usa la distancia horizontal elegida (cuadrícula o terreno) y el desnivel; la altura es la del proyecto, sin corregir por curvatura.]
- [SUPOSICIÓN: el factor combinado de terreno se toma en el centro de cada segmento (RF-10); si el usuario prefiere uno único para todo el proyecto, se cambia.]
- [SUPOSICIÓN: el área es horizontal (plano del SC o de terreno), no el área de la superficie inclinada.]
- [SUPOSICIÓN: el azimut es el de cuadrícula, no el geodésico; la diferencia es la convergencia de meridianos (S008).]
- [SUPOSICIÓN: la información cubre también las líneas de un dibujo CAD (RF-1), porque S041 la dejó aquí; por eso S026 depende además de S040 y S008.]
- [SUPOSICIÓN: la elección entre cuadrícula y terreno se recuerda para todos los proyectos, no por proyecto.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S026: dependencias «025, 040, 008».
- `docs/glosario.md`: se añaden «Información geométrica» (`GeometryInfo`), «Segmento» (`Segment`), «Perímetro» (`Perimeter`), «Área» (`Area`), «Pendiente» (`Slope`) y «Ángulo entre tres puntos» (`ThreePointAngle`); «Distancia de cuadrícula / de terreno» ya existe.
- `docs/inventario-funcional.md`: la fila «Cambiar el SC después, cuadrícula/terreno…» cita S026 para el cálculo de distancias; el cambio del SC del proyecto sigue en S035.
- S041: su «fuera de alcance» (información de una línea y distancias de terreno → S026) queda cubierto.
