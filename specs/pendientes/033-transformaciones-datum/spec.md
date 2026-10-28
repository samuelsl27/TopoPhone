# Spec 033 — Transformaciones de datum

> Hito: H5 · Depende de: S032, S008 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Hasta ahora la app solo cambia de datum en un caso: de ETRS89 a ED50 con la rejilla SPED2ETV2 del IGN (S008). Fuera de España casi todos los SC tienen su propio datum y su propia transformación hacia WGS84/ETRS89, y los fabricantes y los organismos las publican como parámetros de Helmert, de Molodensky-Badekas o como rejillas de desplazamiento. Sin ellas, un SC de otro país con proyección soportada (S032) daría coordenadas desplazadas de varios metros.

Esta spec añade al núcleo geodésico las transformaciones de datum en tres familias: Helmert de 3, 4, 6 y 7 parámetros (en convención «vector de posición» y «marco de coordenadas»), Molodensky-Badekas de 10 parámetros y rejillas de desplazamiento horizontal. Cada transformación se puede aplicar en los dos sentidos con ida y vuelta ≤ 0,1 mm. La interfaz para elegirlas llega con S034 y S035.

## Usuarios / actores
- Topógrafo que trabaja en un SC con datum distinto del de sus correcciones (S034, S035).
- Desarrollador que genera el catálogo (ADR-3) y debe saber qué transformaciones se calculan.

## Historias de usuario
- H1: Como topógrafo quiero que mis coordenadas en un datum local coincidan con las oficiales del país, al milímetro.
- H2: Como topógrafo quiero que la app use los parámetros que publica mi organismo, sea Helmert, Molodensky-Badekas o una rejilla.
- H3: Como topógrafo quiero que ninguna coordenada se extrapole fuera de la rejilla de la transformación.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Helmert de N parámetros**: 3 = tres traslaciones; 4 = tres traslaciones y un factor de escala; 6 = tres traslaciones y tres rotaciones; 7 = tres traslaciones, tres rotaciones y un factor de escala. Se aplica a coordenadas cartesianas geocéntricas.
- **Convención PV / CF**: «vector de posición» y «marco de coordenadas»; difieren en el signo de las rotaciones.
- **Molodensky-Badekas (MB 10)**: Helmert de 7 parámetros referido a un punto de rotación (X₀, Y₀, Z₀) propio de la transformación.
- **Rejilla de desplazamiento**: fichero GeoTIFF (ADR-4) con desplazamientos de latitud y longitud en segundos de arco, interpolados bilinealmente.

### Helmert y Molodensky-Badekas (H1, H2)
- RF-1: EL SISTEMA transformará coordenadas geográficas de un datum a otro pasando por cartesianas geocéntricas con Helmert de 3, 4, 6 y 7 parámetros.
- RF-2: EL SISTEMA aplicará Helmert en convención PV o CF según declare la transformación, sin mezclar los signos.
- RF-3: EL SISTEMA aplicará Molodensky-Badekas de 10 parámetros con su punto de rotación.
- RF-4: EL SISTEMA expresará las traslaciones en metros, las rotaciones en segundos de arco o radianes y la escala en partes por millón, y las pasará a metros, radianes y adimensional internamente (RNF-T9).
- RF-5: EL SISTEMA aplicará cada transformación en sentido directo y en sentido inverso como el inverso exacto del cálculo directo, no como el cambio de signo de los parámetros.

### Rejillas de desplazamiento horizontal (H2, H3)
- RF-6: EL SISTEMA aplicará como transformación de datum cualquier rejilla de desplazamiento horizontal instalada, con el mismo comportamiento que la SPED2ETV2 de S008/RF-7.
- RF-7: SI el punto queda fuera de la rejilla, ENTONCES EL SISTEMA lo rechazará como no disponible e indicará que está fuera de la transformación, sin extrapolar (S008/RF-8, ADR-5).
- RF-8: SI la rejilla no está instalada o no es legible, ENTONCES EL SISTEMA indicará qué rejilla falta y no calculará las coordenadas.
- RF-9: EL SISTEMA invertirá una rejilla de forma iterativa hasta que la ida y vuelta difiera ≤ 0,1 mm, y rechazará el punto si no converge.

### Cadenas y avisos (H1, H2)
- RF-10: EL SISTEMA encadenará varias transformaciones (por ejemplo, datum local → WGS84 → ETRS89 → datum del SC) y calculará el conjunto con una única conversión por punto, sin redondeos intermedios (RF-11).
- RF-11: EL SISTEMA no redondeará ningún valor intermedio ni final al transformar.
- RF-12: EL SISTEMA describirá cada transformación con su nombre, el código EPSG si lo tiene, su precisión declarada y su ámbito de validez, para mostrarlos en la ficha del SC (S034).
- RF-13: SI el punto queda fuera del ámbito declarado de una transformación de parámetros, ENTONCES EL SISTEMA calculará, pero lo indicará como aviso de ámbito.
- RF-14: EL SISTEMA no cambiará el cálculo de ED50 ni el de ETRS89/REGCAN95 de S008.

## Requisitos no funcionales
- Transversales que aplican: RNF-T9 y RNF-T14 (cálculo propio en Kotlin puro, ADR-2; las rejillas que se instalan traen su licencia, ver S034).
- Constitución nº 2: frente a pyproj, las coordenadas transformadas difieren ≤ 1 mm (geográficas ≤ 1·10⁻⁸ °) y la ida y vuelta ≤ 0,1 mm, con y sin rejilla.
- Las referencias independientes son pyproj y los ejemplos resueltos de EPSG GN 7-2 (Helmert PV y CF, Molodensky-Badekas); para rejillas, pyproj con las mismas rejillas del CDN de PROJ. Para ED50 también las coordenadas oficiales del IGN de S008.
- Transformar 100.000 puntos con rejilla tarda menos de 2 s en el móvil de referencia.
- Un punto transformado con una cadena de tres pasos coincide con el de pyproj con la misma cadena a ≤ 1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Parámetros todos cero | RF-1, RF-5 |
| Rotaciones grandes (≥ 1″) y escala ≥ 10 ppm | RF-5 |
| Helmert con la convención equivocada (se detecta con el ejemplo EPSG) | RF-2 |
| MB con el punto de rotación en el centro de la Tierra | RF-3 |
| Punto en el borde de la rejilla | RF-6, RF-7 |
| Rejilla ausente o dañada | RF-8 |
| Inversa de una rejilla con desplazamientos de varios metros | RF-9 |
| Punto fuera del ámbito de unos parámetros | RF-13 |
| Cadena con ED50 de S008 | RF-10, RF-14 |

## Fuera de alcance
- Transformaciones dependientes del tiempo (14 parámetros, velocidades, ITRF a una época) → no previsto (arquitectura): la solución es el base shift (S020) o la localización (S039).
- Descargar las rejillas (el mecanismo, el SHA-256 y la gestión del espacio) → S034.
- Alturas, geoides y datums verticales → S034.
- Rejillas de desplazamiento vertical combinadas con las horizontales → S034.
- Elegir una transformación en la interfaz → S034 y S035.
- Molodensky abreviado y transformación por polinomios → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores pyproj por método (Helmert 3, 4, 6, 7 en PV y CF; MB; rejilla; cadena de tres pasos) en `tools/geodesia`, y los ejemplos de GN 7-2.
- Demo en el emulador: no aplica (sin cambios visibles); la demo es la ejecución de los tests de referencia.

## Dudas abiertas
- [SUPOSICIÓN: «Helmert de 4 parámetros» es traslaciones + escala; si la app de referencia lo define de otro modo (por ejemplo, semejanza 2D), hay que corregirlo.]
- [SUPOSICIÓN: la descarga de rejillas de desplazamiento la hace S034 junto con los geoides; esta spec solo lee las instaladas.]
- [SUPOSICIÓN: el inverso del Helmert es el exacto del directo; se acepta que difiera del cambio de signo de EPSG en menos de 0,1 mm.]
- [SUPOSICIÓN: sin cambios visibles hasta S034.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: fila S033 en estado `spec`; se aclara que S034 descarga también las rejillas de desplazamiento.
- `docs/glosario.md`: se añaden «Helmert», «Molodensky-Badekas» y «Rejilla de desplazamiento» (hoy «Rejilla» es genérica).
