# Spec 032 — Proyecciones completas

> Hito: H5 · Depende de: S008 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Hoy la app solo sabe proyectar con Transversa de Mercator (UTM, S008) y con Web Mercator para el mapa (S009). Muchos sistemas de coordenadas (SC) del mundo, y varios de uso común en cartografía oficial, usan otras proyecciones: Cónica Conforme de Lambert (LCC) con uno o dos paralelos estándar y Mercator Oblicua (Oblique Mercator, OM) en sus variantes A y B. Sin ellas, la biblioteca mundial de SC (S034) y el editor de SC personalizado (S035) no podrían ofrecer esos sistemas, y la app tendría que rechazarlos o, peor, calcularlos mal.

Esta spec añade al núcleo geodésico esos tres métodos de proyección, con sus factores de escala y su convergencia de meridianos, con la misma exigencia de precisión que UTM. No cambia nada visible por sí sola: los usa S034 y S035. Es la base de cálculo; el usuario no la ve hasta entonces.

## Usuarios / actores
- Topógrafo que trabaja en un SC con proyección Lambert u Oblique Mercator (S034, S035).
- Desarrollador que genera el catálogo de SC (ADR-3) y necesita saber qué métodos se pueden calcular.

## Historias de usuario
- H1: Como topógrafo quiero coordenadas Lambert (1 y 2 paralelos) correctas al milímetro.
- H2: Como topógrafo quiero coordenadas en Oblique Mercator A y B correctas al milímetro.
- H3: Como topógrafo quiero el factor de escala y la convergencia en estas proyecciones, como en UTM.
- H4: Como desarrollador quiero saber con certeza qué métodos de proyección admite la app.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **LCC 1SP / LCC 2SP**: Cónica Conforme de Lambert definida por un paralelo de origen y un factor de escala (1SP) o por dos paralelos estándar (2SP).
- **OM variante A / B**: Mercator Oblicua con el origen en el centro de la proyección (A) o en el punto donde la línea central corta la línea de rectificación (B, «Hotine»).
- **Parámetros de la proyección**: los que fija el SC (latitud y longitud de origen, paralelos, azimut de la línea central, ángulo de rectificación, factor de escala, falso Este y falso Norte).

### Lambert Conformal Conic (H1, H3)
- RF-1: EL SISTEMA proyectará coordenadas geográficas a Este y Norte, y al revés, con LCC 1SP y LCC 2SP sobre cualquier elipsoide definido por su semieje mayor y su aplanamiento.
- RF-2: EL SISTEMA calculará en LCC el factor de escala y la convergencia de meridianos en un punto, con el mismo criterio de signos que en UTM (S008/RF-13, RF-14).
- RF-3: SI los parámetros de un LCC no son válidos (paralelos opuestos respecto al ecuador, un paralelo en un polo, factor de escala no positivo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Oblique Mercator (H2, H3)
- RF-4: EL SISTEMA proyectará y desproyectará con OM variantes A y B sobre cualquier elipsoide.
- RF-5: EL SISTEMA calculará en OM el factor de escala y la convergencia de meridianos en un punto.
- RF-6: SI los parámetros de una OM no son válidos (azimut o ángulo de rectificación fuera de rango, latitud de origen en un polo), ENTONCES EL SISTEMA rechazará la proyección e indicará qué parámetro falla.

### Todas las proyecciones (H1, H2, H3, H4)
- RF-7: EL SISTEMA aplicará el falso Este y el falso Norte en la unidad lineal del SC (m, ft o usft) y los pasará a metros en el cálculo interno (RNF-T9).
- RF-8: MIENTRAS el punto quede dentro del ámbito de validez de la proyección, EL SISTEMA devolverá la ida y la vuelta (geográficas → proyectadas → geográficas) con una diferencia ≤ 0,1 mm.
- RF-9: SI un punto no tiene imagen en la proyección (por ejemplo, el polo opuesto al origen de un LCC o un punto a más de 90° de la línea central de una OM), ENTONCES EL SISTEMA lo rechazará como no disponible, sin devolver un valor extrapolado (como ADR-5).
- RF-10: EL SISTEMA publicará la lista de métodos de proyección que calcula (TM, LCC 1SP, LCC 2SP, OM A, OM B, Web Mercator) para que el generador del catálogo (ADR-3) excluya los SC que usan otros.
- RF-11: EL SISTEMA no cambiará ningún resultado de las proyecciones ya existentes (UTM, S008; Web Mercator, S009).

## Requisitos no funcionales
- Transversales que aplican: RNF-T9 (unidades) y RNF-T14 (sin librería de geodesia pesada: cálculo propio en Kotlin puro, ADR-2; si el plan propusiera una, justifica la alternativa descartada).
- Constitución nº 2: frente a pyproj, las coordenadas proyectadas difieren ≤ 1 mm, las geográficas ≤ 1·10⁻⁸ °, el factor de escala ≤ 1·10⁻⁹ y la convergencia ≤ 0,01″.
- Además de pyproj, se usan los ejemplos resueltos de la nota EPSG GN 7-2 de cada método (valores publicados por el IOGP).
- Rejilla de puntos de prueba por método: centro, bordes del ámbito, hemisferio sur, falsos Este y Norte no nulos, elipsoides distintos y una esfera.
- Convertir 100.000 puntos tarda menos de 1 s en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| LCC 2SP con los dos paralelos iguales (equivale a 1SP) | RF-1 |
| LCC con paralelo de origen en el ecuador | RF-3 |
| OM con azimut de 0°, 90° o 180° | RF-4, RF-6 |
| OM B con ángulo de rectificación distinto del azimut | RF-4 |
| Elipsoide esférico (aplanamiento 0) | RF-1, RF-4 |
| Falso Este y Norte en ft o usft | RF-7 |
| Punto en el polo o en las antípodas del origen | RF-9 |

## Fuera de alcance
- Otras proyecciones (Albers, Estereográfica Polar, Mercator, Cassini, Cilíndrica de Área Igual…) → no previsto; solo si el catálogo las necesita (hoja de ruta) y entonces en una spec nueva.
- Meridiano de origen distinto de Greenwich (por ejemplo, Madrid o París) → S033 (como cambio de datum) o no previsto.
- Elegir estas proyecciones en la interfaz → S034 y S035.
- Transformación entre datums → S033.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores de referencia generados con pyproj (versión fijada con uv) por método y por caso límite, guardados en `tools/geodesia` y contrastados con los ejemplos de EPSG GN 7-2.
- Demo en el emulador: no aplica (sin cambios visibles); la demo es la ejecución de los tests de referencia.

## Dudas abiertas
- [SUPOSICIÓN: esta spec no cambia la interfaz; el usuario solo ve estas proyecciones cuando S034 o S035 le dejen elegir un SC que las use.]
- [SUPOSICIÓN: solo meridiano de origen de Greenwich; los SC con otro meridiano de origen quedan fuera del catálogo.]
- [SUPOSICIÓN: el ámbito de validez de LCC y OM es el que declare el SC (S034); fuera de él la app avisa pero calcula, salvo en los puntos sin imagen (RF-9).]
- [SUPOSICIÓN: Mercator con paralelo estándar y otras proyecciones quedan fuera aunque reduzcan la cobertura del catálogo mundial.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: fila S032 en estado `spec`; nota de que S032 no tiene demo visible.
- `docs/glosario.md`: se añaden «LCC (Cónica Conforme de Lambert)» y «Oblique Mercator (OM)» con sus variantes.
