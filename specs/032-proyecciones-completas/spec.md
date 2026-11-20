# Spec 032 — Proyecciones completas

> Hito: H5 · Depende de: S008 · Estado: spec (pendiente de aprobar; entrevista del 20/11/2026)

## Contexto y objetivo
Hoy la app solo sabe proyectar con Transversa de Mercator (UTM, S008) y con Web Mercator para el mapa (S009). Muchos sistemas de coordenadas (SC) del mundo, y varios de uso común en cartografía oficial, usan otras proyecciones: Cónica Conforme de Lambert (LCC) con uno o dos paralelos estándar, Mercator con paralelo estándar, Mercator Oblicua (OM) en sus variantes A y B, y Estereográfica oblicua y polar. Sin ellas, la biblioteca mundial de SC (S034) y el editor de SC personalizado (S035) no podrían ofrecer esos sistemas, y la app tendría que rechazarlos o, peor, calcularlos mal.

Esta spec añade al núcleo geodésico esos métodos de proyección, con sus factores de escala y su convergencia de meridianos, con la misma exigencia de precisión que UTM, y deja que un SC declare un meridiano de origen distinto de Greenwich. No cambia nada visible por sí sola: los usan S034 y S035. Es la base de cálculo; el usuario no la ve hasta entonces.

## Usuarios / actores
- Topógrafo que trabaja en un SC con alguna de estas proyecciones (S034, S035).
- Desarrollador que genera el catálogo de SC (ADR-3) y necesita saber qué métodos se pueden calcular.

## Historias de usuario
- H1: Como topógrafo quiero coordenadas Lambert (1 y 2 paralelos) correctas al milímetro.
- H2: Como topógrafo quiero coordenadas en Mercator y en Oblique Mercator A y B correctas al milímetro.
- H3: Como topógrafo quiero coordenadas en Estereográfica oblicua y polar correctas al milímetro.
- H4: Como topógrafo quiero el factor de escala y la convergencia en estas proyecciones, como en UTM.
- H5: Como topógrafo quiero poder usar un SC cuyo meridiano de origen no es Greenwich (Madrid, París, Lisboa…).
- H6: Como desarrollador quiero saber con certeza qué métodos de proyección admite la app.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **LCC 1SP / LCC 2SP**: Cónica Conforme de Lambert definida por un paralelo de origen y un factor de escala (1SP) o por dos paralelos estándar (2SP).
- **Mercator A / B**: Mercator definida por un paralelo de origen con factor de escala (A) o por un paralelo estándar de escala verdadera (B).
- **OM variante A / B**: Mercator Oblicua (Hotine). En la A, el falso Este y el falso Norte se dan en el origen natural de la proyección (donde la línea central corta la línea de rectificación); en la B, en el centro de la proyección.
- **Estereográfica oblicua**: proyección conforme con plano tangente o secante en un punto cualquiera, con latitud y longitud de origen y factor de escala (doble estereográfica).
- **Estereográfica polar A / B / C**: la misma proyección con el origen en un polo. En la A, se da el factor de escala en el polo; en la B, el paralelo estándar de escala verdadera; en la C, también un paralelo estándar y el falso Este y falso Norte en un paralelo de origen falso.
- **Meridiano de origen**: la longitud respecto a Greenwich del meridiano desde el que el SC cuenta sus longitudes.
- **Parámetros de la proyección**: los que fija el SC (latitud y longitud de origen, paralelos, azimut de la línea central, ángulo de rectificación, factor de escala, falso Este y falso Norte).

### Lambert Conformal Conic (H1, H4)
- RF-1: EL SISTEMA proyectará coordenadas geográficas a Este y Norte, y al revés, con LCC 1SP y LCC 2SP sobre cualquier elipsoide definido por su semieje mayor y su aplanamiento.
- RF-2: EL SISTEMA calculará en LCC el factor de escala y la convergencia de meridianos en un punto, con el mismo criterio de signos que en UTM (S008/RF-13, RF-14).
- RF-3: SI los parámetros de un LCC no son válidos (paralelos opuestos respecto al ecuador, un paralelo en un polo, paralelo de origen en el ecuador, factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Mercator (H2, H4)
- RF-4: EL SISTEMA proyectará y desproyectará con Mercator A y B sobre cualquier elipsoide.
- RF-5: EL SISTEMA calculará en Mercator el factor de escala y la convergencia de meridianos en un punto (la convergencia es nula en toda la proyección).
- RF-6: SI los parámetros de un Mercator no son válidos (paralelo estándar en un polo, factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Oblique Mercator (H2, H4)
- RF-7: EL SISTEMA proyectará y desproyectará con OM variantes A y B sobre cualquier elipsoide.
- RF-8: EL SISTEMA calculará en OM el factor de escala y la convergencia de meridianos en un punto.
- RF-9: SI los parámetros de una OM no son válidos (azimut o ángulo de rectificación fuera de rango, latitud de origen en un polo, factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Estereográfica oblicua (H3, H4)
- RF-10: EL SISTEMA proyectará y desproyectará con la Estereográfica oblicua sobre cualquier elipsoide.
- RF-11: EL SISTEMA calculará en la Estereográfica oblicua el factor de escala y la convergencia de meridianos en un punto.
- RF-12: SI los parámetros de una Estereográfica oblicua no son válidos (latitud de origen fuera de ±90°, factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Estereográfica polar (H3, H4)
- RF-13: EL SISTEMA proyectará y desproyectará con la Estereográfica polar A, B y C sobre cualquier elipsoide, en el polo norte y en el polo sur.
- RF-14: EL SISTEMA calculará en la Estereográfica polar el factor de escala y la convergencia de meridianos en un punto.
- RF-15: SI los parámetros de una Estereográfica polar no son válidos (en la A, latitud de origen que no es un polo; en la B y la C, paralelo estándar en el hemisferio contrario al polo o fuera de ±90°; factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Todas las proyecciones (H1 a H6)
- RF-16: EL SISTEMA aplicará el falso Este y el falso Norte en la unidad lineal del SC (m, ft o usft) y los pasará a metros en el cálculo interno (RNF-T9).
- RF-17: CUANDO un SC declare un meridiano de origen distinto de Greenwich, EL SISTEMA contará las longitudes desde ese meridiano: sumará su desplazamiento a la longitud al desproyectar y lo restará al proyectar, en cualquiera de los métodos.
- RF-18: MIENTRAS el punto quede dentro del ámbito de validez de la proyección, EL SISTEMA devolverá la ida y la vuelta (geográficas → proyectadas → geográficas) con una diferencia ≤ 0,1 mm.
- RF-19: SI un punto no tiene imagen en la proyección (por ejemplo, el polo opuesto al origen de un LCC, el polo de una Mercator, un punto a más de 90° de la línea central de una OM o el punto antípoda de una estereográfica), ENTONCES EL SISTEMA lo rechazará como no disponible, sin devolver un valor extrapolado (como ADR-5).
- RF-20: EL SISTEMA publicará la lista de métodos de proyección que calcula (TM, LCC 1SP, LCC 2SP, Mercator A, Mercator B, OM A, OM B, Estereográfica oblicua, Estereográfica polar A, B y C, y Web Mercator) para que el generador del catálogo (ADR-3) excluya los SC que usan otros.
- RF-21: EL SISTEMA no cambiará ningún resultado de las proyecciones ya existentes (UTM, S008; Web Mercator, S009).

## Requisitos no funcionales
- Transversales que aplican: RNF-T9 (unidades) y RNF-T14 (sin librería de geodesia pesada: cálculo propio en Kotlin puro, ADR-2; si el plan propusiera una, justifica la alternativa descartada).
- Constitución nº 2: frente a pyproj, las coordenadas proyectadas difieren ≤ 1 mm, las geográficas ≤ 1·10⁻⁸ °, el factor de escala ≤ 1·10⁻⁹ y la convergencia ≤ 0,01″.
- Además de pyproj, se usan los ejemplos resueltos de la nota EPSG GN 7-2 de cada método (valores publicados por el IOGP).
- Rejilla de puntos de prueba por método: centro, bordes del ámbito, hemisferio sur, falsos Este y Norte no nulos, elipsoides distintos y una esfera; y un meridiano de origen distinto de Greenwich.
- Convertir 100.000 puntos tarda menos de 1 s en el móvil de referencia, con cualquier método.

## Casos límite
| Caso | RF |
|---|---|
| LCC 2SP con los dos paralelos iguales (equivale a 1SP) | RF-1 |
| LCC con paralelo de origen en el ecuador | RF-3 |
| Mercator B con el paralelo estándar en el ecuador (equivale a A con factor 1) | RF-4 |
| OM con azimut de 0°, 90° o 180° | RF-7, RF-9 |
| OM B con ángulo de rectificación distinto del azimut | RF-7 |
| Estereográfica oblicua con origen en el ecuador o en un polo | RF-10, RF-12 |
| Estereográfica polar en el polo sur y con el paralelo estándar en el polo | RF-13, RF-15 |
| Elipsoide esférico (aplanamiento 0) | RF-1, RF-4, RF-7, RF-10, RF-13 |
| Falso Este y Norte en ft o usft | RF-16 |
| Meridiano de origen de Madrid, de París y de Lisboa | RF-17 |
| Un punto que con el desplazamiento del meridiano cruza el antimeridiano | RF-17, RF-19 |
| Punto en el polo o en las antípodas del origen | RF-19 |

## Fuera de alcance
- Otras proyecciones (Albers, Cassini, Cilíndrica de Área Igual, Equidistante…) → no previsto; solo si el catálogo las necesita (hoja de ruta) y entonces en una spec nueva.
- Variantes menos usadas de los mismos métodos (LCC belga, Mercator esférica de Google, Transversa de Mercator sur-orientada) → no previsto.
- Transformación entre datums, incluido el cambio de meridiano de origen como cambio de datum → S033.
- Elegir estas proyecciones en la interfaz → S034 y S035.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores de referencia generados con pyproj (versión fijada con uv) por método y por caso límite, guardados en `tools/geodesia` y contrastados con los ejemplos de EPSG GN 7-2.
- Demo en el emulador: no aplica (sin cambios visibles); la demo es la ejecución de los tests de referencia.

## Dudas abiertas
Decisiones tomadas en la entrevista del 20/11/2026:
- **Alcance:** LCC (1SP y 2SP), Mercator (A y B), Oblique Mercator (A y B), Estereográfica oblicua y polar (A, B y C), todo en esta spec (la propuesta de dividirla en dos se rechazó). Son 10 variantes nuevas; si el plan no cabe en unas 25 tareas de 30 minutos, se vuelve a proponer.
- **Meridiano de origen:** se admite cualquiera, con un desplazamiento en longitud (RF-17).

Decisiones tomadas sin preguntarte, que se pueden corregir al aprobar:
- [SUPOSICIÓN: esta spec no cambia la interfaz; el usuario solo ve estas proyecciones cuando S034 o S035 le dejen elegir un SC que las use.]
- [SUPOSICIÓN: el ámbito de validez de cada proyección es el que declare el SC (S034); fuera de él la app avisa pero calcula, salvo en los puntos sin imagen (RF-19).]
- [SUPOSICIÓN: el desplazamiento del meridiano de origen se guarda en el SC como un ángulo respecto a Greenwich; el cambio de datum que acompañe a un meridiano distinto (por ejemplo ED50 Madrid) lo hace S033.]
- **Corrección respecto al borrador:** las variantes de la Oblique Mercator estaban al revés. Según la nota EPSG GN 7-2, en la A el falso Este y Norte se dan en el origen natural y en la B, en el centro de la proyección; los términos de arriba ya lo dicen así.
- [NECESITA ACLARACIÓN: versión de pyproj y de la nota EPSG GN 7-2 con las que se generan los vectores; se fijan en el plan.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: fila S032 con el alcance nuevo («LCC 1SP/2SP, Mercator A/B, Oblique Mercator A/B, Estereográfica oblicua y polar A/B/C y meridiano de origen distinto de Greenwich») y nota de que S032 no tiene demo visible.
- `docs/glosario.md`: se añaden «LCC (Cónica Conforme de Lambert)», «Oblique Mercator (OM)», «Estereográfica» y «Meridiano de origen» con sus variantes.
- `specs/pendientes/README.md`: fila de S032 con su enlace nuevo y el alcance ampliado.
