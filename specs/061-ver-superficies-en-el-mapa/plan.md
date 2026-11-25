# Plan técnico — Spec 061

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md` (ADR-8, ADR-9), `specs/061-ver-superficies-en-el-mapa/spec.md`, `specs/042-superficies-landxml/plan.md` (teselas `surface_tiles`, `TinTiler`, `TinElevation`, `ActiveSurfaceProvider`), `specs/045-raster-mdt/plan.md` (`raster_tiles` de 128 × 128 celdas en el SC del ráster, `RasterElevation`) y `specs/040-capa-cad-dxf/plan.md` A10 (cómo se construye una escena del mapa al pararse la cámara). Sin código.

Sin dependencias nuevas (Gradle ni Python). El dibujo usa las fuentes GeoJSON e imagen de MapLibre que ya usan los dibujos DXF y las capas WMS.

## Estructura de módulos
- `core:model`:
  - `surface/SurfaceNames.display(name, fileName)`: el nombre que se enseña, el del fichero sin la extensión si el de la superficie está vacío o en blanco (RF-1, RF-2);
  - `surface/ElevationRamp`: la rampa de colores entre una cota mínima y una máxima, cota → ARGB (RF-10, RF-11);
  - `surface/SurfaceOutlineCodec`: el formato binario de los anillos del contorno (A2).
- `core:survey` (sin Android ni Hilt):
  - `TinOutline.rings(easting, northing, triangles)`: los anillos del contorno exterior y de los huecos (A2; RF-6);
  - `SurfaceViewSelector`: dado lo que corta la vista, decide contorno o malla y entrega los triángulos o las celdas con su color (A3, A4; RF-6 a RF-10).
- `data`:
  - Room v41: columna `visible` en `surfaces` y tabla `surface_outlines`, con `AutoMigration(40, 41)` y una `AutoMigrationSpec` que deja visibles las superficies activas (RNF-T6; RF-3 a RF-5);
  - `SurfaceRepository` gana `setVisible`, `outline(surfaceId)` (calcula y guarda el contorno si falta, A2), `tilesIn(surfaceId, box)` y `heightAt(surfaceId, e, n)` para cualquier superficie, activa o no (RF-16, RF-18);
  - `SurfaceImporter` guarda el nombre de RF-1, el contorno y `visible = 1` al terminar (RF-1, RF-4, RF-6).
- `app`:
  - `MapController.showSurfaces(scene: SurfaceScene?)` con su implementación en `MapLibreMapController` (A5; RF-6 a RF-9, RF-12, RF-13);
  - `SurfaceSceneBuilder`: construye la escena al pararse la cámara, como `DesignSceneBuilder` (A3, A4);
  - `SurfaceLegend`: la leyenda sobre el mapa (RF-11);
  - `SurfacesSheet`: interruptor «Ver en el mapa» y botón «Ir a la superficie» (RF-3, RF-14, RF-15);
  - `SurfaceTapCard`: la tarjeta al tocar una superficie (RF-16 a RF-18).
- `tools`: `tools/superficies/generar_superficies.py` gana un caso con un hueco y un contorno conocido, y la rampa de referencia (constitución nº 2).

## Modelo de datos

### Room v41
```
surfaces          + visible INTEGER NOT NULL DEFAULT 0
surface_outlines    surface_id TEXT FK(surfaces, CASCADE), part INTEGER, data BLOB (≤ 256 kB), PRIMARY KEY(surface_id, part)
```
- `AutoMigration(40, 41)` con una `AutoMigrationSpec` cuyo `onPostMigrate` ejecuta `UPDATE surfaces SET visible = 1 WHERE id IN (SELECT active_surface_id FROM projects WHERE active_surface_id IS NOT NULL)` (suposición de la spec: las importadas antes quedan visibles solo si son la activa).
- El nombre **no se reescribe** en la base: `SurfaceNames.display` se aplica al leer (`SurfaceRepository.surfaces`) y al importar. Así lo cumplen también las copias de seguridad antiguas que se restauren (RF-2), sin una migración de datos.
- `surface_outlines` es un dato derivado: se calcula al importar (A2) y, para las superficies anteriores o restauradas, la primera vez que se dibujan. **No entra en las copias de seguridad ni en los paquetes**; `visible` sí, porque va en `surfaces`, que ya se copia entera (`SELECT *`, RF-5).
- Un ráster no tiene filas en `surface_outlines`: su contorno es el recuadro de su extensión (RF-8).

Ejemplo de una fila de `surfaces` tras importar un LandXML sintético cuya superficie no tiene nombre:
```
name = "explanacion"   (el fichero trae name="", RF-1)
file_name = "explanacion.xml", kind = "TIN", visible = 1, triangle_count = 1002528,
min_e = 440000.0, max_e = 441416.0, min_n = 4474000.0, max_n = 4475416.0, min_z = 600.0, max_z = 635.4
```

### Formato del contorno (`SurfaceOutlineCodec`)
Cabecera con el número de formato y el de anillos; por anillo, si es exterior o hueco, el número de vértices y los pares (Este, Norte) como `Double` en el SC del proyecto, sin redondear. Si pasa de 256 kB se parte en varias filas (`part`), con los anillos enteros.

## Algoritmos

### A1. El nombre (RF-1, RF-2)
`SurfaceNames.display(name, fileName)`: si `name.isBlank()`, el nombre del fichero sin su última extensión (`explanacion.xml` → `explanacion`; sin extensión, el nombre entero); si no, `name` tal cual. La pantalla de importación (la lista de RF-5 de S042), el resumen y la ficha lo usan.

### A2. El contorno de un TIN (RF-6, RF-13)
1. Cada triángulo aporta sus tres aristas como pares de vértices ordenados (menor, mayor). Una arista que aparece **una sola vez** es de borde (exterior o hueco); las que aparecen dos veces son interiores. Los triángulos invisibles ya no están (S042/RF-12), así que sus aristas quedan como borde del hueco.
2. Se encadenan las aristas de borde por sus vértices en anillos cerrados. En un vértice con más de dos aristas de borde (dos anillos que se tocan en un punto) se toma la arista que gira más a la derecha, para que los anillos no se crucen.
3. El anillo de mayor área absoluta es el exterior; los demás son huecos (una superficie de varias islas tiene varios exteriores: todo anillo que no esté dentro de otro es exterior).
4. Coste lineal con un mapa abierto de `Long` (par de índices) a recuento: con 1.000.000 de triángulos, unos 3 millones de aristas, menos de 2 s en el móvil.
- Al importar se calcula con los arrays completos, antes de teselar. Para una superficie sin filas en `surface_outlines`, `SurfaceRepository.outline` lee todas sus teselas en segundo plano, une los vértices repetidos entre teselas por sus coordenadas exactas (un mismo vértice tiene el mismo `Double` en todas) y quita los triángulos repetidos, y después aplica 1 a 3; lo guarda para la siguiente vez.

### A3. Qué se dibuja de un TIN (RF-6, RF-7, RF-10)
Al pararse la cámara (150 ms sin movimiento, como S040 A10), para cada superficie TIN visible:
1. La vista se pasa al SC del proyecto como recuadro, ampliada un 25 %.
2. Con el índice de `surface_tiles` (recuadro y `triangle_count`, sin leer bloques) se suman los triángulos de las teselas que cortan la vista. Si la suma pasa de 60.000, se dibuja **solo el contorno** sin leer ningún bloque (una tesela puede salirse de la vista, así que la suma es una cota superior).
3. Si no, se leen esas teselas, se quitan los triángulos repetidos entre teselas y se cuentan los que cortan la vista **sin ampliar**. Con más de 20.000, solo el contorno (RF-6); con 20.000 o menos, la malla (RF-7).
4. Cada triángulo de la malla lleva como color `ElevationRamp.argb(cota media de sus tres vértices)`.
5. Los vértices se pasan a latitud y longitud con el mismo cálculo que el resto de la app (`ProjectPositionCalculator`), en `Dispatchers.Default` (RNF-T13).

### A4. Qué se dibuja de un ráster (RF-8, RF-9, RF-10)
1. La vista se pasa al SC del ráster y se calcula, con su origen y su paso, el rango de columnas y filas que corta: el número de celdas sale de la aritmética, sin leer nada.
2. Con más de 1.000.000 de celdas, solo el recuadro de su extensión (los cuatro lados densificados cada 1/32 para que se curven bien al cambiar de SC).
3. Si no, se leen los bloques de `raster_tiles` de ese rango y se pinta un `Bitmap` de una celda por píxel: transparente sin dato, y `ElevationRamp.argb(cota)` con dato. El mapa lo coloca con las cuatro esquinas del rango pasadas a latitud y longitud (una `ImageSource`, como las capas WMS de S036). El remuestreo del píxel lo hace MapLibre; la cota de la tarjeta sigue saliendo de los datos (A7), no del dibujo.

### A5. La escena en el mapa (RF-6 a RF-13)
`SurfaceScene(ramp, legend, tins: [contorno, activa, malla?], rasters: [recuadro, activa, imagen?])`. `MapLibreMapController.showSurfaces`:
- Fuente GeoJSON `SURFACE_MESH_SOURCE` con un `FillLayer` (color de la propiedad `c`, opacidad 0,55) y un `LineLayer` de aristas (gris oscuro, 0,5 dp, opacidad 0,6).
- Fuente GeoJSON `SURFACE_OUTLINE_SOURCE` con un `LineLayer` (3 dp la activa y 1,5 dp las demás, con la propiedad `active`, RF-13); el contorno se dibuja también con la malla.
- Una `ImageSource` por ráster con imagen, con su `RasterLayer` a la opacidad 0,55.
- Orden (RF-12): todas debajo de la primera capa de dibujos (`DESIGN_LINES_LAYER`) y encima del ancla de las WMS (`WMS_ANCHOR_LAYER`).
- La rampa: el mínimo y el máximo de `min_z`/`max_z` de las superficies visibles (RF-10). Si son iguales, un solo color. Siete paradas: azul oscuro, azul, cian, verde, amarillo, naranja y rojo, interpoladas en RGB; el contraste a pleno sol se comprueba en la demo (RNF-T8).

### A6. Ir a la superficie (RF-14, RF-15)
El botón de la ficha hace `setVisible(true)` si hacía falta, cierra la ficha y llama a `MapController.moveTo` con el recuadro de la extensión pasado a latitud y longitud y un margen del 10 %. El modo de seguir al jalón se apaga como si el usuario hubiese movido el mapa (S060/RF-26), y aparece «Recentrar».

### A7. Tocar una superficie (RF-16 a RF-18)
- El toque del mapa ya prueba, en orden, los puntos, las líneas y los dibujos; si no hay ninguno, `SurfaceTapResolver` mira las superficies visibles cuyo recuadro contiene el toque y, para cada una, `SurfaceRepository.heightAt(surfaceId, e, n)`: con un TIN lee la tesela que contiene el punto (S042 A3) y con un ráster sus cuatro celdas (S045). Si ninguna lo contiene (fuera de todos los contornos), no sale tarjeta.
- Una superficie: tarjeta con nombre, tipo, «Activa» si lo es y la cota en las unidades del proyecto, o «Fuera de la superficie» en un hueco o en una celda sin dato (RF-18). Varias: una fila por superficie (RF-17).
- La cota no lleva el desplazamiento vertical (suposición de la spec).

## Contratos

### `MapController`
```
fun showSurfaces(scene: SurfaceScene?)      // null quita todo lo de las superficies
```
`SurfaceScene` lleva ya latitudes y longitudes: el controlador no conoce el SC del proyecto (ADR-9).

### Textos nuevos (`strings.xml`)
«Ver en el mapa», «Ir a la superficie», el tipo «TIN» y «Ráster», «Activa», «Cota de la superficie», «Fuera de la superficie» (ya existe) y la descripción de TalkBack de la leyenda («Colores por cota, de %1$s a %2$s»).

### Accesibilidad (RNF-T8)
El interruptor y el botón de 56 dp; la leyenda lleva las cifras, no solo los colores; la tarjeta se lee entera con TalkBack.

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Contorno precalculado y guardado aparte | Calcularlo con cada vista obligaría a leer todas las teselas (24 MB con un millón de triángulos) | Calcularlo al vuelo; o no dibujar nada de lejos |
| El contorno no va en las copias de seguridad | Es un dato derivado de las teselas y se recalcula | Copiarlo (más tamaño y una tabla más que mantener en S013) |
| Nombre por defecto al leer, no reescrito | Cubre también las copias antiguas restauradas, sin migración de datos | `UPDATE` en la migración (no alcanza a lo que se restaure después) |
| Malla como GeoJSON de ≤ 20.000 polígonos | Es lo que ya usa el mapa (S040, S025) y MapLibre lo pinta en la GPU | Un `Bitmap` rasterizado de la malla (pierde las aristas al acercar) |
| Ráster como una imagen por vista | ≤ 1.000.000 de celdas es ≤ 4 MB de `Bitmap`; una sola fuente por ráster | Un polígono por celda (inviable); una imagen por bloque (decenas de fuentes) |
| Suma de `triangle_count` del índice antes de leer bloques | Decide «solo contorno» sin E/S cuando se ve la superficie entera | Leer siempre las teselas de la vista |
| Rampa común a las visibles | Un color es la misma cota en todas (suposición de la spec) | Una rampa por superficie |
| `AutoMigration` con `AutoMigrationSpec` | Una columna con valor por defecto y una tabla nueva; el `UPDATE` de las activas en `onPostMigrate` | Migración escrita a mano |

## Estrategia de tests
- Unitarios JVM (`core:model`, `core:survey`): `SurfaceNames` (vacío, espacios, sin extensión, dos puntos); `ElevationRamp` (extremos, centro, mínimo = máximo, fuera de rango) contra los valores de `tools/`; `TinOutline` con un cuadrado de dos triángulos, una malla con un hueco, dos islas y dos anillos que se tocan en un vértice, comparado con el contorno que calcula `tools/` aparte; `SurfaceOutlineCodec` ida y vuelta sin pérdida; `SurfaceViewSelector` en los dos lados de 20.000 triángulos y de 1.000.000 de celdas, y con la suma del índice por encima de 60.000.
- Robolectric (`data`): migración 40 → 41 (visible solo la activa, el resto igual); `SurfaceImporter` deja nombre, contorno y `visible = 1`; `outline` calcula y guarda el que falta; `heightAt` de una superficie no activa (TIN y ráster) coincide con `ActiveSurface` (≤ 1 mm); la copia de seguridad conserva `visible` y no lleva `surface_outlines`.
- Robolectric (`app`): `SurfacesSheet` con el interruptor y «Ir a la superficie»; la tarjeta con una, con dos superficies y en un hueco; la leyenda con sus cifras en metros y en pies; `SurfaceSceneBuilder` con un `MapController` falso (contorno, malla y orden de las capas).
- Rendimiento (`data`, como `SurfacePerformanceTest`): contorno de 1.000.000 de triángulos en menos de 2 s; escena de la malla de una vista de 20.000 triángulos en menos de 1 s.
- Manual: demo en el emulador con un LandXML de cerca de 1.000.000 de triángulos sin nombre de superficie (un fichero de prueba que no entra en el repositorio) y un ráster de `fixtures/`: contorno de lejos, malla al acercar, ocultar y mostrar, «Ir a la superficie», tocar y leer la cota, y fluidez al mover el mapa.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | A1 | `SurfaceNamesTest`, `SurfaceImporterNameTest` |
| RF-2 | A1, modelo de datos | `SurfaceRepositoryNameTest` |
| RF-3 | A5, `SurfacesSheet` | `SurfacesSheetVisibilityTest` |
| RF-4 | `SurfaceImporter` | `SurfaceImporterNameTest` |
| RF-5 | Room v41 | `Migration40To41Test`, `SurfaceBackupVisibleTest` |
| RF-6 | A2, A3 | `TinOutlineTest`, `SurfaceViewSelectorTest` |
| RF-7 | A3 | `SurfaceViewSelectorTest`, `SurfaceSceneBuilderTest` |
| RF-8 | A4 | `SurfaceViewSelectorTest` |
| RF-9 | A4 | `RasterViewImageTest` |
| RF-10 | A5 | `ElevationRampTest`, `SurfaceSceneBuilderTest` |
| RF-11 | `SurfaceLegend` | `SurfaceLegendTest` |
| RF-12 | A5 | `SurfaceSceneBuilderTest` (orden de las capas) |
| RF-13 | A5 | `SurfaceSceneBuilderTest` |
| RF-14 | A6 | `GoToSurfaceTest` |
| RF-15 | A6 | `GoToSurfaceTest` |
| RF-16 | A7 | `SurfaceTapTest` |
| RF-17 | A7 | `SurfaceTapTest` |
| RF-18 | A7 | `SurfaceTapTest` |

## Pendiente para otras specs
- S062 añade el botón «Replantear» a la ficha y a la tarjeta de A7, y usa `setVisible` al replantear una superficie oculta.
- La base de datos pasa de la v40 a la v41; S062 tomará la siguiente si la necesita.
