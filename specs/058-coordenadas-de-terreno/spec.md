# Spec 058 — Coordenadas de terreno

> Hito: H5 · Depende de: S035, S008, S006, S011, S012, S029 · Estado: spec (entrevista 22/11/2026)

## Contexto y objetivo
Las coordenadas de un SC proyectado son de cuadrícula: la proyección deforma las distancias (la escala de UTM en el borde de un huso ronda 1,0004 y la altura añade otro tanto). En una obra pequeña el topógrafo quiere que la distancia entre dos puntos en pantalla sea la que mide con la cinta, y que el CSV y el DXF que entrega lleven esas coordenadas.

Esta spec añade al proyecto la opción de trabajar en **coordenadas de terreno**: las de cuadrícula escaladas respecto a un punto de origen con un factor único, de modo que el origen conserva sus coordenadas y cada distancia a él se multiplica por el factor. Se elige en el proyecto, se ve y se escribe en terreno en toda la app, se importa y se exporta en terreno en los ficheros que no declaran SC y se anota en el historial del proyecto (S035). Las coordenadas geodésicas y las medidas guardadas no cambian nunca.

## Usuarios / actores
- Topógrafo que trabaja en una obra pequeña con distancias de terreno.
- Quien recibe del topógrafo el CSV o el DXF y necesita saber en qué coordenadas están.

## Historias de usuario
- H1: Como topógrafo quiero elegir coordenadas de terreno en mi proyecto, con un origen y un factor que me proponga la app.
- H2: Como topógrafo quiero ver, escribir, importar y exportar coordenadas y distancias de terreno sin recalcular nada a mano.
- H3: Como topógrafo quiero pasar de cuadrícula a terreno y volver sin perder ninguna medida.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Coordenadas de cuadrícula**: las de la proyección del SC del proyecto, tal cual.
- **Coordenadas de terreno**: las de cuadrícula escaladas respecto a un **origen** (un punto) con un **factor** único: el origen conserva sus coordenadas y la distancia de cualquier punto al origen se multiplica por el factor.
- **Plano de terreno**: el origen y el factor de un proyecto en terreno.

### Elegir y definir (H1)
- RF-1: CUANDO el usuario cree o edite un proyecto con un SC proyectado, EL SISTEMA permitirá elegir coordenadas de cuadrícula (por defecto) o de terreno.
- RF-2: SI el SC del proyecto es geográfico, ENTONCES EL SISTEMA no ofrecerá coordenadas de terreno.
- RF-3: CUANDO el usuario elija terreno, EL SISTEMA pedirá el origen, con el centro del proyecto por defecto (S006/RF-33), y el factor, y propondrá el factor combinado en el origen (S008/RF-15), que el usuario puede cambiar.
- RF-4: SI el factor no es un número entre 0,9 y 1,1, ENTONCES EL SISTEMA marcará el campo y no guardará el cambio.
- RF-5: SI el origen cae fuera del ámbito del SC del proyecto, ENTONCES EL SISTEMA no guardará el cambio e indicará el motivo (S006/RF-30).
- RF-6: EL SISTEMA guardará el origen por su posición del suelo y no por sus coordenadas de cuadrícula, de modo que al cambiar el SC del proyecto (S035) siga en el mismo punto y el factor no cambie.

### Cálculo (H2)
- RF-7: EL SISTEMA calculará el paso entre cuadrícula y terreno con una ida y vuelta ≤ 0,1 mm, sin modificar las coordenadas geodésicas, las medidas guardadas ni las alturas.
- RF-8: EL SISTEMA guardará las coordenadas de los puntos y de los objetos importados en cuadrícula y pasará a terreno solo al mostrarlas, al escribirlas y al exportarlas.

### Ver y escribir (H2)
- RF-9: MIENTRAS el proyecto esté en terreno, EL SISTEMA mostrará en terreno las coordenadas y las distancias del panel de captura, de la lista y la ficha de los puntos, del mapa, del replanteo, de COGO y de la información geométrica, e indicará «Terreno» junto a ellas.
- RF-10: MIENTRAS el proyecto esté en terreno, EL SISTEMA entenderá como de terreno las coordenadas que el usuario escriba (punto manual, edición de un punto, punto central, COGO y replanteo de unas coordenadas).
- RF-11: MIENTRAS el proyecto esté en terreno, EL SISTEMA mostrará las alturas igual que en cuadrícula.

### Ficheros (H2)
- RF-12: CUANDO el usuario importe un CSV o un TXT de puntos en un proyecto en terreno, EL SISTEMA entenderá su Este y su Norte como de terreno (S012).
- RF-13: CUANDO el usuario exporte un CSV en un proyecto en terreno, EL SISTEMA escribirá las coordenadas de terreno y una primera línea de comentario con el origen de cuadrícula y el factor, sin cambiar los nombres de las columnas.
- RF-14: CUANDO el usuario exporte un DXF en un proyecto en terreno, EL SISTEMA escribirá las coordenadas de terreno y un comentario de cabecera con el origen y el factor (S029).
- RF-15: CUANDO el usuario exporte un Shapefile o un GeoPackage en un proyecto en terreno, EL SISTEMA los escribirá en cuadrícula con su SC y lo dirá en pantalla (S053, S055).
- RF-16: EL SISTEMA no cambiará la exportación a KML y KMZ, que va en geográficas (S031).
- RF-17: CUANDO el usuario importe un DXF, una superficie, un eje, un ráster, un Shapefile, un GeoPackage o un KML en un proyecto en terreno, EL SISTEMA interpretará sus coordenadas según el SC del fichero o el que se elija; con «las coordenadas del proyecto» entenderá las de terreno.
- RF-18: EL SISTEMA incluirá el plano de terreno en las copias de seguridad y en los paquetes de proyecto (S013).

### Cambios (H3)
- RF-19: CUANDO el usuario cambie entre cuadrícula y terreno, o el origen o el factor, EL SISTEMA lo registrará en el historial del proyecto con su fecha, lo anterior y lo nuevo, y permitirá deshacerlo (S035/RF-20, RF-21).
- RF-20: CUANDO el usuario cambie entre cuadrícula y terreno, o el origen o el factor, EL SISTEMA mostrará todas las coordenadas en el plano nuevo sin modificar ninguna medida ni ningún objeto guardado.

## Requisitos no funcionales
- Transversales que aplican: RNF-T5, RNF-T6 (migración sin pérdida y cambios atómicos), RNF-T7, RNF-T8, RNF-T9 (internamente m, rad) y RNF-T14.
- Constitución nº 2: el paso a terreno y de vuelta se contrasta con pyproj más la definición de terreno (ida y vuelta ≤ 0,1 mm; el factor combinado propuesto coincide con el de pyproj a 1e-9).
- Una lista de 10.000 puntos se muestra en terreno sin retraso apreciable: el paso de todos a terreno tarda menos de 100 ms.

## Casos límite
| Caso | RF |
|---|---|
| Factor 1,0000 (terreno igual a cuadrícula) | RF-4, RF-7 |
| Factor fuera de 0,9–1,1 o que no es un número | RF-4 |
| Origen fuera del ámbito del SC | RF-5 |
| SC geográfico | RF-2 |
| Cambiar el SC con terreno activo | RF-6 |
| Cambiar de cuadrícula a terreno con puntos replanteados ya guardados | RF-8, RF-20 |
| Importar un CSV con el origen cambiado después | RF-12 |
| Exportar un Shapefile en terreno | RF-15 |
| Proyecto sin punto central | RF-3 |

## Fuera de alcance
- Un factor distinto por punto, por altura o por zona → no previsto.
- Rotación o desplazamiento del plano de terreno → no previsto; la calibración con pares de puntos es S039.
- Alturas de terreno distintas de las del proyecto → no previsto.
- Exportar a Shapefile o GeoPackage en terreno → no previsto (RF-15).
- Terreno con un SC geográfico → no previsto (RF-2).
- Un origen elegido con coordenadas de terreno anteriores → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores de pyproj en `tools/geodesia` para el paso a terreno y de vuelta, y para el factor combinado.
- Demo en el emulador: elegir terreno en un proyecto con puntos, ver las coordenadas y distancias en terreno, exportar un CSV y un DXF con su cabecera y deshacer el cambio.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el factor combinado propuesto usa la altura elipsoidal del origen si se conoce; si no, solo el factor de escala de la proyección.]
- [SUPOSICIÓN: el intervalo de 0,9 a 1,1 del factor es amplio a propósito: un factor real en una obra no pasa de 1 ± 0,001.]
- [SUPOSICIÓN: los objetos importados (dibujos, superficies, ejes, rásteres) están guardados en cuadrícula y se pasan a terreno solo al mostrarlos; se comprueba en el plan.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S058 pasa a `spec`.
- `docs/glosario.md`: se añaden «Coordenadas de cuadrícula», «Coordenadas de terreno» y «Plano de terreno».
