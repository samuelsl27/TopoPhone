# Plan técnico — Spec 058

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/058-coordenadas-de-terreno/spec.md`, y los planes de S035 (historial, `ProjectCrsHistoryRepository`) y S057 (importaciones).

## Estructura de módulos
- `core:model` → `TerrainPlane` (origen por su posición del suelo y factor) y `Project.terrain` / `ProjectDraft.terrain` (RF-1, RF-6); `ProjectCrsChange` gana `fromTerrain` y `toTerrain` (RF-19).
- `core:survey` → `TerrainFrame` (el paso cuadrícula ↔ terreno de un plano en un SC), `TerrainView` (lo que usan las pantallas: coordenadas, distancias, deltas y áreas en terreno, y la inversa para lo que escribe el usuario), `TerrainProposal` (factor combinado propuesto) y `TerrainPlaneValidator` (RF-2 a RF-5, RF-7, RF-8, RF-9, RF-10).
- `core:formats` → cabeceras de terreno del CSV y del DXF (RF-13, RF-14).
- `data` → Room v39 (columnas del plano en `projects` y del plano anterior y nuevo en `project_crs_history`), `RoomProjectRepository`, `RoomProjectCrsHistoryRepository.apply(…, terrain)` (RF-6, RF-18, RF-19, RF-20).
- `app` → el formulario del proyecto, `TerrainView` en las pantallas que muestran o piden coordenadas y distancias, y los avisos de las exportaciones a Shapefile y GeoPackage (RF-1 a RF-5, RF-9 a RF-17).

## Modelo de datos
Terreno es una **homotecia de la cuadrícula** respecto a un origen: el origen conserva sus coordenadas y cada distancia a él se multiplica por el factor `k`. Por eso todo lo planimétrico que se calcule sobre coordenadas de terreno (distancias, deltas de replanteo, longitudes) sale ya en terreno, los ángulos y acimutes no cambian y las áreas se multiplican por `k²`. Las alturas no cambian.

```
TerrainPlane(originLatitudeDeg: Double, originLongitudeDeg: Double, factor: Double)
TerrainFrame(plane, crs, corrections): originEasting E0, originNorthing N0 (de la posición del suelo en el SC), k
  toTerrain(e, n) = (E0 + k·(e − E0), N0 + k·(n − N0));  toGrid(e, n) = (E0 + (e − E0)/k, N0 + (n − N0)/k)
```
- **El plano se guarda por la posición del suelo del origen** (RF-6): al cambiar el SC (S035) se recalculan `E0` y `N0` y el factor no cambia.
- **Lo guardado sigue en cuadrícula** (RF-8): las columnas `easting`/`northing` de los puntos, los trozos de dibujos, las teselas de superficies y rásteres, los ejes y las líneas no se tocan al cambiar de plano. Se pasa a terreno solo al mostrar, al escribir y al exportar. Es la diferencia con S035, que sí reescribe las columnas derivadas de los puntos porque cambia la proyección.
- **Room v39** (migración automática 38→39): `projects` gana `terrain_origin_lat REAL NULL`, `terrain_origin_lon REAL NULL`, `terrain_factor REAL NULL` (las tres a la vez o ninguna); `project_crs_history` gana `from_terrain TEXT NULL` y `to_terrain TEXT NULL` (el plano como `lat;lon;factor`, sin redondear). Las copias y los paquetes copian columnas, así que el plano viaja sin más (RF-18).

## Algoritmos
1. **El marco** (RF-7): `E0, N0 = ProjectPositionCalculator.fromGround(origen, crs)`; `toTerrain` y `toGrid` son las fórmulas de arriba. Con `k = 1,0000` es la identidad. La ida y vuelta es exacta salvo redondeo de `Double` (≪ 0,1 mm).
2. **Factor propuesto** (RF-3): `TerrainProposal.factorAt(origen, crs, alturaElipsoidal)` = el factor combinado de S008/RF-15 en el origen (`ProjectPosition.combinedFactor`, que ya es factor de escala de la proyección por el de altura). La altura elipsoidal que se usa es la media de las de los puntos del proyecto si los hay y, si no, 0. Se propone y el usuario lo puede cambiar.
3. **Validación** (RF-4, RF-5): el factor es un número finito entre 0,9 y 1,1; el origen cae dentro del ámbito del SC (`crs.containsPosition`) y el SC es proyectado (RF-2).
4. **Mostrar** (RF-9): `TerrainView.coordinates(e, n)`, `distance(d)` y `delta(d)` (× `k`), `area(a)` (× `k²`) y `label` («Terreno»); con cuadrícula son la identidad. Una pantalla que ya tiene el proyecto construye su `TerrainView` y lo pasa a donde formatea.
5. **Escribir** (RF-10, RF-12): `TerrainView.toGrid(e, n)` antes de calcular nada con lo que escribe el usuario (punto manual, edición de un punto, punto central, COGO, replanteo de unas coordenadas, CSV).
6. **Distancias de COGO y geometría**: ya tienen el modo cuadrícula/terreno de S038 y S026 (`DistanceMode`, `GroundScale`), que pasa de cuadrícula a terreno con el factor combinado de cada tramo. Con el proyecto en terreno de S058 las coordenadas ya se escalan con `k`: se fuerza el modo de cuadrícula (la distancia sobre las coordenadas de terreno ya es de terreno) y se oculta la opción.
7. **Ficheros** (RF-12 a RF-17): el CSV y el DXF escriben `toTerrain` de lo guardado y su cabecera; el CSV y el DXF leídos con «las coordenadas del proyecto» pasan por `toGrid` antes de guardarse (`CadSourceCrs.Project` de S040 es «las coordenadas del proyecto»); los demás orígenes de S057 no cambian. Shapefile y GeoPackage no cambian (cuadrícula) y las pantallas avisan; KML va en geográficas.
8. **Historial** (RF-19, RF-20): `apply(…, terrain)` guarda el cambio de plano sin tocar los puntos; cambiar el SC y el plano a la vez es un solo cambio.

## Contratos
- `TerrainPlaneValidator.validate(plane, crs): Map<TerrainField, TerrainError>` con `ORIGIN`, `FACTOR`, `CRS` y `NOT_PROJECTED`, `OUT_OF_AREA`, `OUT_OF_RANGE`, `NOT_FINITE`, `REQUIRED`.
- `Project.terrain: TerrainPlane?` (`null` = cuadrícula) y `TerrainView.of(project, calculator)`.
- Textos nuevos en `strings.xml` (es/en): «Cuadrícula», «Terreno», el factor y su propuesta, los errores y los avisos de exportación.

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| Lo guardado sigue en cuadrícula y se pasa a terreno al mostrar, escribir y exportar | Cambiar de plano es instantáneo y reversible sin tocar ninguna tabla (RF-8, RF-20); S035 ya reescribe los puntos y no reproyecta los objetos importados, y aquí no se repite ese límite | Guardar en terreno y reescribirlo todo (puntos, dibujos, superficies, rásteres, ejes) al cambiar |
| Homotecia respecto a un origen con un factor único | Es lo que pide la spec; con ella las distancias y los deltas se calculan sobre coordenadas de terreno sin fórmulas nuevas | Un factor por punto (RNF: fuera de alcance) |
| El origen se guarda por su posición del suelo | Sobrevive a un cambio de SC (RF-6) | Guardar `E0, N0`: se invalida al cambiar el SC |
| Un `TerrainView` por pantalla, no un formateador global | Los formateadores de longitud no saben si un número es una coordenada, una distancia o un área | Enchufar la conversión en `LengthFormat` (convertiría también los números que no son del plano) |
| El modo de distancias de COGO y geometría se fuerza a cuadrícula con el proyecto en terreno | Evita escalar dos veces | Combinar el factor del tramo con `k` |
| Shapefile y GeoPackage siempre en cuadrícula | Declaran un SC y QGIS los coloca con él (decidido en la entrevista) | Escribirlos en terreno sin SC declarado |

## Estrategia de tests
- Unitarios JVM: `TerrainFrameTest` (ida y vuelta ≤ 0,1 mm, el origen no se mueve, `k = 1` es la identidad, distancias × `k`, áreas × `k²`), contraste con `fixtures/geodesia/terreno.csv` generado con pyproj (la cuadrícula de unos puntos, el origen y el factor combinado de pyproj a 1e-9), `TerrainPlaneValidatorTest`, `TerrainProposalTest`.
- Room/Robolectric: `Migration38To39Test`, `ProjectTerrainRepositoryTest` (guardar, leer, borrar el plano), `TerrainHistoryTest` (cambio y deshacer; cambiar el SC con el plano activo conserva el origen), copia y paquete con plano.
- Compose/Robolectric: formulario (elegir terreno, origen, factor propuesto, errores), cada grupo de pantallas con el proyecto en terreno (los números y las distancias salen de terreno y «Terreno» se ve), entradas (punto manual, punto central, COGO), ficheros (CSV, DXF), avisos de Shapefile y GeoPackage.
- Rendimiento: `TerrainFrameTest` pasa 10.000 puntos a terreno en menos de 100 ms.
- Manual o de campo: demo del emulador (elegir terreno en un proyecto con puntos, ver y exportar un CSV y un DXF, deshacer); la de campo queda para el usuario.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | formulario y `TerrainPlaneValidator` | `ProjectTerrainFormTest`, `TerrainPlaneValidatorTest` |
| RF-3 | `TerrainProposal` | `TerrainProposalTest`, `ProjectTerrainFormTest` |
| RF-4, RF-5 | `TerrainPlaneValidator` | `TerrainPlaneValidatorTest` |
| RF-6 | origen por su posición del suelo | `TerrainHistoryTest`, `ProjectTerrainRepositoryTest` |
| RF-7, RF-8 | `TerrainFrame` | `TerrainFrameTest`, `TerrainVectorsTest` |
| RF-9, RF-11 | `TerrainView` en las pantallas | `TerrainScreensTest` y uno por grupo |
| RF-10 | `TerrainView.toGrid` en las entradas | `TerrainInputsTest` |
| RF-12, RF-13 | CSV | `TerrainCsvTest` |
| RF-14, RF-17 | DXF y `CadSourceCrs.Project` | `TerrainDxfTest`, `TerrainImportTest` |
| RF-15, RF-16 | Shapefile, GeoPackage y KML | `TerrainExportNoticeTest` |
| RF-18 | copia y paquete | `TerrainBackupTest` |
| RF-19, RF-20 | historial | `TerrainHistoryTest`, `TerrainHistoryScreenTest` |
