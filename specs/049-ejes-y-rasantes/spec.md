# Spec 049 — Ejes y rasantes

> Hito: H8 · Depende de: S040, S042, S006 · Estado: aprobada (26/10/2026)

## Contexto y objetivo
Una carretera, una zanja, una tubería o una línea eléctrica no se replantean por puntos sueltos: se replantean por **PK y desplazamiento** respecto a un **eje** con su **rasante**. «Estoy en el PK 1+240, a 3,50 m a la derecha, y me faltan 12 cm de desmonte» es lo que el jefe de equipo quiere ver en pantalla. S041 replantea las líneas de un DXF a lo largo de ellas, pero no sabe de PK, de desplazamientos a derecha o izquierda del eje ni de la cota que da una rasante.

Esta spec trae el **eje en planta** (rectas, arcos y clotoides) y su **rasante** (vértices con acuerdos verticales) al proyecto, los muestra en el mapa con sus marcas de PK y calcula puntos sobre el eje y sobre la rasante. El replanteo por PK y desplazamiento y el análisis en vivo de la posición del jalón son S054. Los peraltes y las secciones transversales (S050), los taludes (S051) y la toma de datos lineal (S052) usan lo que aquí se define.

El eje y la rasante son **referencia de diseño**, no dato de campo (constitución nº 1): no se mezclan con los puntos medidos ni se editan en el campo, salvo la definición manual (RF-8).

## Usuarios / actores
- Topógrafo en oficina o en campo con el eje y la rasante de una obra lineal.
- Proyectista que entrega el eje y la rasante en LandXML o en un plano DXF.

## Historias de usuario
- H1: Como topógrafo quiero importar un eje y su rasante desde un LandXML, desde una polilínea de un DXF, desde un CSV de PK y cota o escribirlos a mano.
- H2: Como topógrafo quiero ver el eje en el mapa con sus marcas de PK y consultar un PK tocando.
- H3: Como topógrafo quiero calcular las coordenadas y la cota de un punto dado su PK y su desplazamiento.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Eje**: el trazado en planta, con tramos rectos, circulares y de clotoide.
- **Rasante**: el perfil vertical del eje: vértices (PK y cota) con acuerdos verticales parabólicos o circulares.
- **PK**: la distancia medida sobre el eje desde su origen, en metros; el **PK inicial** es el del primer punto del eje (0 por defecto).
- **Desplazamiento**: la distancia perpendicular al eje; negativo a la izquierda y positivo a la derecha, en el sentido de los PK crecientes.
- **Punto singular**: el PK en el que cambia el tipo de tramo del eje o el vértice de la rasante.
- **Ejes**: un proyecto puede tener varios; uno es el **eje activo**.

### Importar o definir un eje (H1)
- RF-1: CUANDO el usuario elija "Importar eje" en el menú de un proyecto o en el de su lista de objetos, EL SISTEMA ofrecerá importarlo desde un LandXML, desde una polilínea de un dibujo del proyecto (S040), desde un CSV de rasante o definirlo a mano.
- RF-2: CUANDO el usuario elija un LandXML, EL SISTEMA reconocerá el fichero por su contenido, listará sus alineaciones con su longitud y su PK inicial, y dirá cuáles traen rasante, y permitirá marcar cuáles se importan (RNF-T5).
- RF-3: SI el LandXML no tiene alineaciones, no es válido o supera los 100 MB, ENTONCES EL SISTEMA no abrirá la lista e indicará el motivo.
- RF-4: EL SISTEMA importará de cada alineación los tramos rectos, circulares y de clotoide, y su rasante con los vértices y los acuerdos parabólicos y circulares del perfil que el LandXML marque como rasante.
- RF-5: SI en una alineación el final de un tramo y el principio del siguiente distan más de 1 cm o el tipo de tramo no es uno de los admitidos, ENTONCES EL SISTEMA no la importará y dirá el motivo y el tramo.
- RF-6: CUANDO el usuario convierta una polilínea de un dibujo, EL SISTEMA creará un eje con sus tramos rectos y los arcos de sus curvas, tomará como inicio el extremo más cercano al punto tocado y permitirá escribir el PK inicial.
- RF-7: DONDE la polilínea de un dibujo traiga cotas, EL SISTEMA creará la rasante con los vértices de la polilínea, sin acuerdos.
- RF-8: CUANDO el usuario defina un eje a mano, EL SISTEMA le permitirá escribir los vértices del eje (Este y Norte), el radio de la curva de cada vértice y, opcionalmente, la longitud de sus clotoides, y los vértices de la rasante (PK, cota y el parámetro del acuerdo vertical).
- RF-9: CUANDO el usuario importe un CSV de rasante, EL SISTEMA permitirá asignar las columnas PK, cota y, opcionalmente, parámetro del acuerdo vertical, con las opciones de codificación, separador y signo decimal de S028/RF-4 a RF-9, y la asociará al eje que elija.
- RF-10: SI una rasante tiene vértices fuera del PK del eje, con PK repetidos o con un acuerdo vertical que no cabe entre sus vértices, ENTONCES EL SISTEMA no la importará y dirá el vértice y el motivo.
- RF-11: EL SISTEMA propondrá que las coordenadas del eje están en el sistema del proyecto y permitirá elegir otro de los disponibles de la app y la unidad (metros, pies o pies topográficos de EE. UU.), como en S040/RF-6 y RF-7.
- RF-12: SI un vértice del eje queda fuera del ámbito del sistema del proyecto, ENTONCES EL SISTEMA no importará el eje e indicará el motivo.
- RF-13: EL SISTEMA guardará las coordenadas del eje sin redondear, con el nombre del fichero o de la alineación y la fecha y hora de la importación, y mostrará un resumen de lo importado e ignorado (como S040/RF-18).

### Varios ejes (H1, H2)
- RF-14: EL SISTEMA admitirá varios ejes por proyecto, dejará como eje activo el primero que se importe y permitirá cambiar el eje activo, renombrarlo y borrarlo con confirmación.
- RF-15: CUANDO el usuario borre un eje, EL SISTEMA lo quitará entero con su rasante y no modificará ningún punto del proyecto.
- RF-16: EL SISTEMA incluirá los ejes en las copias de seguridad y en los paquetes de proyecto (S013) y anotará cada importación y cada borrado en el log sin nombres ni coordenadas (S014/RF-5).

### Ver el eje (H2)
- RF-17: EL SISTEMA dibujará en el mapa del proyecto los ejes visibles, con el eje activo destacado, y con marcas rotuladas de PK a un intervalo redondo que dependa de la escala del mapa.
- RF-18: CUANDO el usuario toque un eje en el mapa, EL SISTEMA mostrará su ficha con su nombre, su longitud, su PK inicial y final, el PK del punto tocado y la cota de la rasante en ese PK si la tiene, con la opción de activarlo.
- RF-19: EL SISTEMA permitirá mostrar u ocultar cada eje y recordará la elección para el proyecto, también al cerrar la app.

### Puntos sobre el eje (H3)
- RF-20: CUANDO el usuario escriba un PK y un desplazamiento, EL SISTEMA calculará el Este y el Norte del punto, el azimut del eje en ese PK, su radio, y, si hay rasante, la cota y la pendiente en porcentaje.
- RF-21: SI el PK está fuera del eje, ENTONCES EL SISTEMA no calculará nada e indicará el PK inicial y el final.
- RF-22: CUANDO el usuario pulse "Guardar punto" en un punto calculado, EL SISTEMA lo guardará como punto del proyecto de origen "Calculado sobre eje", con su PK y su desplazamiento, y se podrá replantear como cualquier otro.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: el fichero se elige con el selector de documentos y se valida por su contenido.
  - RNF-T6: importar, borrar y guardar puntos se hacen en una sola transacción con durabilidad completa; al actualizar se conservan los datos (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el desplazamiento con signo y con la palabra "izquierda" o "derecha", nunca solo con el signo.
  - RNF-T9: unidades, ángulos y PK con el formato del proyecto.
  - RNF-T13: dibujar el eje y sus marcas de PK no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: las coordenadas del punto de un PK y un desplazamiento coinciden con una referencia independiente (los valores del propio LandXML o de un cálculo con otra herramienta) a menos de 1 mm, también en los tramos de clotoide; la cota de la rasante en un PK, a menos de 1 mm; y el PK y el desplazamiento de una posición, a menos de 1 mm.
- Importar un LandXML de 100 MB con un eje de 50 km tarda menos de 10 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| LandXML sin alineaciones o inválido | RF-3 |
| Alineación con huecos o tipos de tramo no admitidos | RF-5 |
| Rasante con vértices repetidos, fuera del eje o con acuerdos que no caben | RF-10 |
| Polilínea del DXF sin cotas | RF-7 |
| Eje fuera del ámbito del sistema del proyecto | RF-12 |
| PK fuera del eje | RF-21 |
| Borrar el eje activo | RF-14, RF-15 |
| PK inicial distinto de 0 | Términos, RF-6 |
| Desplazamiento 0 (sobre el eje) | RF-20 |
| Importar dos veces el mismo eje | RF-13 (otro eje aparte) |

## Fuera de alcance
- Replanteo por PK y desplazamiento, intervalo, puntos singulares, espesor y análisis de la posición del jalón → S054.
- Peraltes, perfiles transversales y replanteo por PK y código → S050.
- Taludes (cabeza y pie) → S051.
- Tomar un eje con el receptor, perfil longitudinal y perfil transversal → S052.
- Ecuaciones de PK (saltos de PK) → no previsto.
- Importar ejes de otros formatos (ISPOL, Clip, InRoads) → no previsto.
- Exportar ejes o rasantes → no previsto.
- Editar un eje importado en la app → no previsto.
- Ejes en 3D sin rasante (cota solo por los vértices) → RF-7 lo cubre solo para polilíneas de dibujos.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un eje con rectas, arcos y clotoides y una rasante con acuerdos, sacados de un LandXML sintético con valores calculados aparte, dan en cada PK las coordenadas y la cota del fichero a menos de 1 mm.
- Demo en el emulador:
  - importar un LandXML y ver el eje con sus marcas de PK;
  - convertir una polilínea de un DXF en eje y definir uno a mano;
  - calcular un punto sobre el eje y guardarlo;
  - consultar un PK tocando el eje.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: no hay un LandXML real con ejes; la precisión de 1 mm se valida con ficheros sintéticos generados aparte con una herramienta independiente (rectas, arcos y clotoides con valores analíticos) y se confirma con un eje real cuando haya uno.]
- [SUPOSICIÓN: la tolerancia entre tramos consecutivos de un eje es de 1 cm (RF-5).]
- [SUPOSICIÓN: "Calculado sobre eje" es un origen nuevo de puntos, como "Importado" y "Manual" (RF-22).]
- [SUPOSICIÓN: el LandXML de un eje tiene el mismo límite de 100 MB que el de las superficies (RF-3).]
- [SUPOSICIÓN: el desplazamiento negativo es a la izquierda y el positivo a la derecha, como en TcpGPS.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nuevo hito H8 "Obra lineal" con las filas S049 a S054, S049 y S054 en estado `spec` y las demás `pendiente`.
- `docs/glosario.md`: se añaden "Eje" (`Alignment`), "Rasante" (`VerticalProfile`), "PK" (`Chainage`, ya existente: se precisa), "Punto singular" (`KeyStation`), "Espesor" (`Thickness`) y "Eje activo" (`ActiveAlignment`).
