# Spec 033 — Transformaciones de datum

> Hito: H5 · Depende de: S032, S008 · Estado: spec (pendiente de aprobar; entrevista del 20/11/2026)

## Contexto y objetivo
Hasta ahora la app solo cambia de datum en un caso: de ETRS89 a ED50 con la rejilla SPED2ETV2 del IGN (S008). Fuera de España casi todos los sistemas de coordenadas (SC) tienen su propio datum y su propia transformación hacia WGS84/ETRS89, y los fabricantes y los organismos las publican como parámetros de Helmert, de Molodensky-Badekas o como rejillas de desplazamiento. Sin ellas, un SC de otro país con proyección soportada (S032) daría coordenadas desplazadas de varios metros.

Esta spec añade al núcleo geodésico las transformaciones de datum en tres familias: Helmert de 3, 4, 6 y 7 parámetros (en convención «vector de posición» y «marco de coordenadas»), Molodensky-Badekas de 10 parámetros y rejillas de desplazamiento horizontal. Cada transformación se puede aplicar en los dos sentidos con ida y vuelta ≤ 0,1 mm. No cambia nada visible por sí sola: la interfaz para elegirlas llega con S034 y S035. El meridiano de origen distinto de Greenwich ya lo resolvió S032 y no es cosa de esta spec.

## Usuarios / actores
- Topógrafo que trabaja en un SC con datum distinto del de sus correcciones (S034, S035).
- Desarrollador que genera el catálogo (ADR-3) y debe saber qué transformaciones se calculan.

## Historias de usuario
- H1: Como topógrafo quiero que mis coordenadas en un datum local coincidan con las oficiales del país, al milímetro.
- H2: Como topógrafo quiero que la app use los parámetros que publica mi organismo, sea Helmert, Molodensky-Badekas o una rejilla.
- H3: Como topógrafo quiero que ninguna coordenada se extrapole fuera de la rejilla de la transformación.
- H4: Como topógrafo quiero que, si falta una rejilla, la app me diga cuál y no me dé una coordenada sin ese paso.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Helmert de N parámetros**: 3 = tres traslaciones; 4 = tres traslaciones y un factor de escala (sin rotaciones); 6 = tres traslaciones y tres rotaciones; 7 = tres traslaciones, tres rotaciones y un factor de escala. Se aplica a coordenadas cartesianas geocéntricas. La semejanza plana de cuatro parámetros (dos traslaciones, rotación y escala) no es una transformación de datum: es la localización (S039).
- **Convención PV / CF**: «vector de posición» y «marco de coordenadas»; difieren en el signo de las rotaciones.
- **Molodensky-Badekas (MB 10)**: Helmert de 7 parámetros referido a un punto de rotación (X₀, Y₀, Z₀) propio de la transformación.
- **Rejilla de desplazamiento**: fichero GeoTIFF (ADR-4) con desplazamientos de latitud y longitud en segundos de arco, interpolados bilinealmente. Si trae varias subrejillas, se usa la primera del fichero que contiene el punto.
- **Cadena**: sucesión ordenada de transformaciones (por ejemplo, datum local → WGS84 → ETRS89 → datum del SC).
- **Ámbito**: la zona geográfica donde una transformación de parámetros declara ser válida.

### Helmert y Molodensky-Badekas (H1, H2)
- RF-1: EL SISTEMA transformará coordenadas geográficas de un datum a otro con Helmert de 3, 4, 6 y 7 parámetros.
- RF-2: EL SISTEMA aplicará Helmert en convención PV o CF según declare la transformación, sin mezclar los signos.
- RF-3: EL SISTEMA aplicará Molodensky-Badekas de 10 parámetros con su punto de rotación.
- RF-4: EL SISTEMA aceptará las traslaciones en metros, las rotaciones en segundos de arco o radianes y la escala en partes por millón, y las tratará internamente en metros, radianes y adimensional (RNF-T9).
- RF-5: EL SISTEMA aplicará cada transformación de parámetros en sentido inverso como el inverso exacto del cálculo directo, no como el cambio de signo de los parámetros.
- RF-6: SI un parámetro de una transformación no es un número finito, ENTONCES EL SISTEMA rechazará la transformación e indicará cuál.

### Rejillas de desplazamiento horizontal (H2, H3, H4)
- RF-7: EL SISTEMA aplicará como transformación de datum cualquier rejilla de desplazamiento horizontal instalada, con el mismo comportamiento que la SPED2ETV2 de S008/RF-7.
- RF-8: SI el punto queda fuera de la rejilla, ENTONCES EL SISTEMA lo rechazará como no disponible e indicará que está fuera de la transformación, sin extrapolar (S008/RF-8, ADR-5).
- RF-9: SI la rejilla no está instalada o no es legible, ENTONCES EL SISTEMA indicará qué rejilla falta y no calculará las coordenadas.
- RF-10: EL SISTEMA aplicará la rejilla en sentido inverso con ida y vuelta ≤ 0,1 mm, y rechazará el punto si no lo consigue.

### Cadenas y avisos (H1, H2, H4)
- RF-11: EL SISTEMA encadenará varias transformaciones y calculará el conjunto con una única conversión por punto.
- RF-12: SI un paso de una cadena es una rejilla ausente o ilegible, ENTONCES EL SISTEMA rechazará el punto, indicará qué rejilla falta y no calculará ningún paso de esa cadena.
- RF-13: EL SISTEMA no redondeará ningún valor intermedio ni final al transformar.
- RF-14: EL SISTEMA describirá cada transformación con su nombre, el código EPSG si lo tiene, su precisión declarada y su ámbito de validez, para mostrarlos en la ficha del SC (S034).
- RF-15: SI el punto queda fuera del ámbito declarado de una transformación de parámetros, ENTONCES EL SISTEMA calculará la coordenada y la marcará con un aviso de ámbito.
- RF-16: EL SISTEMA no cambiará el cálculo de ED50 ni el de ETRS89/REGCAN95 de S008.

## Requisitos no funcionales
- Transversales que aplican: RNF-T9 y RNF-T14 (cálculo propio en Kotlin puro, ADR-2; las rejillas que se instalan traen su licencia, ver S034).
- Constitución nº 2: frente a pyproj, las coordenadas transformadas difieren ≤ 1 mm (geográficas ≤ 1·10⁻⁸ °) y la ida y vuelta ≤ 0,1 mm, con y sin rejilla. En sentido inverso, la comparación con pyproj admite la diferencia entre el inverso exacto y el cambio de signo de los parámetros, siempre < 1 mm.
- Las referencias independientes son pyproj y los ejemplos resueltos de EPSG GN 7-2 (Helmert PV y CF, Molodensky-Badekas); para rejillas, pyproj con las mismas rejillas del CDN de PROJ. Para ED50 también las coordenadas oficiales del IGN de S008.
- Transformar 100.000 puntos con rejilla tarda menos de 2 s en el móvil de referencia.
- Un punto transformado con una cadena de tres pasos coincide con el de pyproj con la misma cadena a ≤ 1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Parámetros todos cero | RF-1, RF-5 |
| Rotaciones grandes (≥ 1″) y escala ≥ 10 ppm | RF-5 |
| Helmert con la convención equivocada (se detecta con el ejemplo EPSG) | RF-2 |
| Parámetro no finito (NaN, infinito) | RF-6 |
| MB con el punto de rotación en el centro de la Tierra | RF-3 |
| Punto en el borde de la rejilla | RF-7, RF-8 |
| Rejilla ausente o dañada | RF-9, RF-12 |
| Rejilla con varias subrejillas solapadas | RF-7 |
| Inversa de una rejilla con desplazamientos de varios metros | RF-10 |
| Punto fuera del ámbito de unos parámetros | RF-15 |
| Cadena con ED50 de S008 | RF-11, RF-16 |

## Fuera de alcance
- Transformaciones dependientes del tiempo (14 parámetros, velocidades, ITRF a una época) → no previsto (arquitectura): la solución es el base shift (S020) o la localización (S039).
- Descargar las rejillas de desplazamiento (el mecanismo, el SHA-256 y la gestión del espacio) → S034, que las descarga junto con los geoides; esta spec solo lee las instaladas.
- Alturas, geoides y datums verticales → S034.
- Rejillas de desplazamiento vertical combinadas con las horizontales → S034.
- Elegir una transformación en la interfaz → S034 y S035.
- Semejanza plana de cuatro parámetros → S039.
- Meridiano de origen distinto de Greenwich → ya resuelto en S032.
- Molodensky abreviado y transformación por polinomios → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores pyproj por método (Helmert 3, 4, 6, 7 en PV y CF; MB; rejilla; cadena de tres pasos) en `tools/geodesia`, y los ejemplos de GN 7-2.
- Demo en el emulador: no aplica (sin cambios visibles); la demo es la ejecución de los tests de referencia.

## Dudas abiertas
- [SUPOSICIÓN: «Helmert de 4 parámetros» es traslaciones + escala; el usuario pidió investigarlo y no hay definición en la documentación del proyecto. Se contrasta con pyproj (Helmert con las rotaciones a cero). Si la app de referencia lo define de otro modo, hay que corregirlo.]
- [SUPOSICIÓN: sin cambios visibles hasta S034.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S034 descarga también las rejillas de desplazamiento horizontal, además de los geoides.
- `docs/glosario.md`: se añaden «Helmert», «Molodensky-Badekas» y «Rejilla de desplazamiento» (hoy «Rejilla» es genérica).
- `specs/pendientes/README.md`: «La descarga de rejillas de desplazamiento no tiene dueño» queda resuelta (S034).
