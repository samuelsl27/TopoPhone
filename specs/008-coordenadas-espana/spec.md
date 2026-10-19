# Spec 008 — Sistemas de coordenadas de España

> Hito: H1 · Depende de: S006 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
Hoy un proyecto solo puede estar en ETRS89 / UTM 29, 30 o 31 con altitudes EGM08-REDNAP (S006). Con eso no se puede trabajar:
- en Canarias, que usa REGCAN95, los husos 27 y 28 y su propio modelo de geoide;
- con coordenadas geográficas o altura elipsoidal;
- sobre cartografía y planos en ED50, que siguen siendo muy comunes.

El topógrafo tampoco ve el factor de escala ni la convergencia, que necesita para pasar distancias de cuadrícula a terreno y orientar.

Esta spec trae todos los sistemas de coordenadas (SC) de uso habitual en España, cada uno con la transformación y el modelo de geoide oficiales del IGN:
- ED50 con la rejilla oficial (adelantado desde S033);
- el huso propuesto por la posición al crear un proyecto, y un aviso si se trabaja fuera de él;
- factor de escala, convergencia y factor combinado en la vista de proyecto.

Las coordenadas que se calculan tienen que coincidir con una referencia independiente (constitución nº 2).

## Usuarios / actores
- Topógrafo en campo en la Península, Baleares o Canarias.
- Topógrafo que replantea o completa planos antiguos en ED50.
- Receptor RS3, o una reproducción, como fuente de la posición.

## Historias de usuario
- H1: Como topógrafo quiero elegir para el proyecto el SC y la altura que usa mi cliente: UTM o geográficas, ETRS89, REGCAN95 o ED50, altitud o altura elipsoidal.
- H2: Como topógrafo quiero que la app me proponga el huso en el que estoy y me avise si trabajo fuera del huso del proyecto.
- H3: Como topógrafo quiero ver el factor de escala, la convergencia y el factor combinado donde estoy.
- H4: Como topógrafo en Canarias quiero que la app funcione igual que en la Península.

## Requisitos funcionales (criterios de aceptación en EARS)
### Sistemas de coordenadas del proyecto (H1, H4)
- RF-1: EL SISTEMA ofrecerá como SC horizontal del proyecto ETRS89 / UTM husos 29, 30 y 31, ETRS89 geográficas, REGCAN95 / UTM husos 27 y 28, REGCAN95 geográficas y ED50 / UTM husos 29, 30 y 31.
- RF-2: EL SISTEMA ofrecerá como altura del proyecto la altitud EGM08-REDNAP o la altura elipsoidal.
- RF-3: SI el SC horizontal es ED50, ENTONCES EL SISTEMA solo ofrecerá la altitud EGM08-REDNAP.
- RF-4: EL SISTEMA calculará la altitud con el modelo EGM08-REDNAP de la Península y Baleares en los SC ETRS89 y ED50, y con el de Canarias en los SC REGCAN95.
- RF-5: MIENTRAS la altura del proyecto sea elipsoidal, la vista de proyecto mostrará la altura elipsoidal del punto del suelo en el datum del SC del proyecto, en sus unidades lineales.
- RF-6: MIENTRAS el SC del proyecto sea de coordenadas geográficas, la vista de proyecto mostrará la latitud y la longitud del punto del suelo en las unidades angulares del proyecto, con una resolución de 0,1 mm o mejor.
- RF-7: EL SISTEMA calculará las coordenadas ED50 a partir de ETRS89 con la transformación oficial del IGN (rejilla SPED2ETV2).
- RF-8: SI el punto del suelo queda fuera de la rejilla SPED2ETV2 en un proyecto ED50, ENTONCES EL SISTEMA mostrará las coordenadas como no disponibles e indicará que el punto está fuera de la transformación ED50, sin extrapolar.
- RF-9: EL SISTEMA tomará como ámbito de los SC ETRS89 y ED50 la Península y Baleares, y como ámbito de los SC REGCAN95, Canarias (S006/RF-30).
- RF-10: EL SISTEMA ofrecerá los mismos SC y alturas en los valores por defecto de los proyectos nuevos en Ajustes (S006/RF-13).

### Huso propuesto y aviso (H2)
- RF-11: CUANDO el usuario cree un proyecto con posición del receptor y el SC por defecto sea UTM, EL SISTEMA propondrá el SC UTM del huso y del territorio de la posición: ETRS89 (o ED50, si el de por defecto es ED50) en la Península y Baleares, y REGCAN95 en Canarias.
- RF-12: MIENTRAS la posición del receptor caiga en un huso distinto del del proyecto, la vista de proyecto mostrará "Estás en el huso N; el proyecto usa el M" y seguirá calculando las coordenadas en el huso del proyecto.

### Factor de escala, convergencia y factor combinado (H3)
- RF-13: MIENTRAS haya posición del receptor y el SC del proyecto sea UTM, la vista de proyecto mostrará el factor de escala de la proyección en el punto del suelo.
- RF-14: MIENTRAS haya posición del receptor y el SC del proyecto sea UTM, la vista de proyecto mostrará la convergencia de meridianos en el punto, en las unidades angulares del proyecto.
- RF-15: MIENTRAS haya posición del receptor y el SC del proyecto sea UTM, la vista de proyecto mostrará el factor combinado, producto del factor de escala y del factor de altura del punto.
- RF-16: MIENTRAS el SC del proyecto sea de coordenadas geográficas, la vista de proyecto no mostrará factores ni convergencia.

### Datum de las correcciones (H1)
- RF-17: CUANDO el usuario elija un SC en el formulario de proyecto, EL SISTEMA pondrá como datum de las correcciones el que le corresponde (REGCAN95 para los SC REGCAN95, ETRS89 para los ETRS89 y ED50), que el usuario puede cambiar después.
- RF-18: SI el datum de las correcciones no es el del SC del proyecto (ETRS89 para los SC ETRS89 y ED50, REGCAN95 para los REGCAN95), ENTONCES EL SISTEMA avisará en el formulario y en la vista de proyecto de que las coordenadas pueden diferir.

### Editar el SC (H1)
- RF-19: CUANDO el usuario edite un proyecto, EL SISTEMA solo ofrecerá los SC horizontales del mismo datum que el actual (S006/RF-15).

### Pantalla Coordenadas del receptor (H4)
- RF-20: EL SISTEMA mostrará en la pantalla Coordenadas del receptor las coordenadas UTM del punto del suelo en el huso que corresponde a su longitud, del 27 al 31: ETRS89 en la Península y Baleares, y REGCAN95 en Canarias.
- RF-21: EL SISTEMA mostrará en la pantalla Coordenadas del receptor la altitud con el modelo EGM08-REDNAP de Canarias cuando el punto del suelo esté en Canarias.

## Requisitos no funcionales
- Transversales que aplican: RNF-T6, RNF-T7, RNF-T8, RNF-T9, RNF-T13 y RNF-T14 (atribución del IGN, CC-BY 4.0).
- Constitución nº 2: frente a pyproj con las mismas rejillas, las coordenadas proyectadas y las altitudes difieren ≤ 1 mm, las geográficas ≤ 1·10⁻⁸ ° (≈ 1 mm), el factor de escala ≤ 1·10⁻⁹ y la convergencia ≤ 0,01″.
- Al actualizar la app, los proyectos existentes conservan su SC (ETRS89 / UTM con EGM08-REDNAP) y sus datos (RNF-T6).
- Las rejillas nuevas (ED50 y geoide de Canarias) van incluidas en la app, funcionan sin conexión y no aumentan el APK en más de 300 KB.

## Casos límite
| Caso | RF |
|---|---|
| Punto en Canarias con un proyecto ETRS89, o en la Península con uno REGCAN95 | RF-9 (S006/RF-30) |
| Punto en el borde o fuera de la rejilla ED50 | RF-8 |
| Proyecto ED50 con altura elipsoidal | RF-3 |
| Punto en otro huso (huso forzado) | RF-12 |
| Crear un proyecto sin posición del receptor | RF-11 (no se propone nada) |
| SC por defecto geográficas al crear un proyecto | RF-11 (no se propone huso) |
| Correcciones ETRS89 en un proyecto REGCAN95, o ITRF en uno ED50 | RF-18 |
| Proyectos de S006 al actualizar | RNF de la spec |

## Fuera de alcance
- ED50 geográficas, el antiguo datum de Canarias (Pico de las Nieves) y otros datums → S033 y S034.
- Cambiar el SC de un proyecto por otro de distinto datum → S035.
- Aplicar el factor combinado a distancias (cuadrícula o terreno) → S026.
- Introducir o importar puntos en estos SC → S011 y S027.
- ETRS89 / UTM 28 → no previsto: no hay territorio español en ETRS89 en ese huso (Canarias usa REGCAN95).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Vectores de referencia de pyproj, generados con las mismas rejillas, para cada SC, el geoide de Canarias, ED50 dentro y fuera de la rejilla, el factor de escala y la convergencia.
- Demo en el emulador: un proyecto en REGCAN95 / UTM 28 con una reproducción en Canarias, otro en ED50 / UTM 30 y otro en geográficas con altura elipsoidal; aviso de otro huso y factores en la vista de proyecto.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: el factor de altura usa la altura elipsoidal y el radio medio de Gauss del elipsoide en el punto; el combinado es factor de escala × factor de altura.]
- [SUPOSICIÓN: el huso propuesto (RF-11) solo cambia el SC del formulario; las demás opciones siguen siendo las de Ajustes.]
- [SUPOSICIÓN: no se ofrece ETRS89 / UTM 28; la hoja de ruta lo citaba, pero en España no hay territorio ETRS89 en ese huso.]
- [SUPOSICIÓN: las coordenadas geográficas se muestran con 10 decimales en grados decimales y en gon, y con 6 decimales de segundo en grados, minutos y segundos: es lo que da la resolución de 0,1 mm de RF-6 (9 y 5 se quedaban en 0,11 y 0,3 mm).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- S002/RF-36 pasa a decir: "EL SISTEMA mostrará las coordenadas UTM del punto del suelo (Este y Norte, en metros con 3 decimales) en el huso que corresponde a su longitud, del 27 al 31, indicando el huso, según S008/RF-20."
- S002/RF-37 pasa a decir: "SI la longitud del punto queda fuera de los husos 27 a 31, ENTONCES EL SISTEMA mostrará las coordenadas UTM como no disponibles."
- S002/RF-39 pasa a decir: "SI el punto queda fuera de la cobertura de los modelos EGM08-REDNAP de la Península y Baleares y de Canarias, ENTONCES EL SISTEMA mostrará la altitud ortométrica como no disponible e indicará el motivo, sin extrapolar."
- `docs/hoja-de-ruta.md`:
  - fila S008: "ETRS89 UTM 29–31, REGCAN95 UTM 27/28 y ED50 UTM 29–31 (rejilla SPED2ETV2, adelantada desde S033)…";
  - fila S033: se quita "SPED2ETV2 para ED50".
- `docs/glosario.md`: se añaden "Factor de altura", "Factor combinado", "Huso propuesto" y "ED50" ("Factor de escala", "Convergencia de meridianos" y "Altura elipsoidal" ya estaban).
