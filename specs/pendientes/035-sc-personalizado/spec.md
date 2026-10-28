# Spec 035 — SC personalizado

> Hito: H5 · Depende de: S034, S033, S032 · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
La biblioteca (S034) cubre los SC del registro EPSG, pero los trabajos reales usan también SC que no están allí: una proyección local con el meridiano central de la obra, un datum con los parámetros que da el cliente, un plano a cota de terreno. Quien no puede definirlo hoy tiene que abandonar la app o aceptar coordenadas equivocadas.

Esta spec añade un editor de SC personalizado con elipsoide, datum, proyección y geoide, una lista «Mis SC» con importar y exportar JSON para compartir con otros móviles, el cambio del SC de un proyecto ya creado, y las coordenadas de cuadrícula o de terreno. Todo sin nube y con las mismas garantías de precisión que los SC de la biblioteca. La localización (S039) usa este editor para «guardar como SC».

## Usuarios / actores
- Topógrafo que recibe de su cliente los parámetros de un SC que no está en la biblioteca.
- Jefe de equipo que prepara un SC y lo pasa a los demás móviles por fichero.
- Topógrafo que trabaja con distancias de terreno en una obra pequeña.

## Historias de usuario
- H1: Como topógrafo quiero definir mi propio SC y comprobar que da los valores de mi cliente antes de usarlo.
- H2: Como jefe de equipo quiero guardar los SC en «Mis SC» y pasarlos a otros móviles.
- H3: Como topógrafo quiero cambiar el SC de un proyecto ya empezado sin perder ninguna medida.
- H4: Como topógrafo quiero elegir entre coordenadas de cuadrícula y de terreno.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **SC personalizado**: SC definido por el usuario con elipsoide, datum (con su transformación, S033), proyección (S032 o UTM/TM) y unidades.
- **Mis SC**: lista de los SC personalizados guardados en el móvil.
- **Coordenadas de cuadrícula**: las de la proyección tal cual.
- **Coordenadas de terreno**: las de cuadrícula escaladas respecto a un punto de origen del terreno con un factor, para que las distancias entre ellas sean las del terreno.

### Editor (H1)
- RF-1: CUANDO el usuario cree un SC personalizado, EL SISTEMA pedirá un nombre y permitirá partir de uno vacío, de un SC de la biblioteca o de uno de Mis SC.
- RF-2: EL SISTEMA permitirá elegir el elipsoide entre los de la biblioteca o definirlo con su semieje mayor y su aplanamiento o su inverso.
- RF-3: EL SISTEMA permitirá elegir la transformación del datum hasta ETRS89/WGS84 entre: ninguna (el datum es el de las correcciones), Helmert 3/4/6/7 PV o CF, Molodensky-Badekas 10 o una rejilla instalada (S033).
- RF-4: EL SISTEMA permitirá elegir la proyección entre TM, LCC 1SP, LCC 2SP, OM A, OM B o ninguna (geográficas), con sus parámetros (S032).
- RF-5: EL SISTEMA permitirá elegir las unidades lineales del SC (m, ft, usft) y la altura: elipsoidal, un geoide instalado (S034) o una altura ortométrica constante respecto al elipsoide.
- RF-6: MIENTRAS el usuario edite, EL SISTEMA validará cada campo, marcará el que falla y no permitirá guardar un SC con parámetros no válidos (S032/RF-3, RF-6).
- RF-7: CUANDO el usuario escriba unas coordenadas de prueba, EL SISTEMA mostrará su resultado en el SC en los dos sentidos, para compararlo con los valores del cliente.
- RF-8: SI el SC usa una rejilla o un geoide no instalado, ENTONCES EL SISTEMA lo indicará y ofrecerá la descarga (S034/RF-9).

### Mis SC (H2)
- RF-9: EL SISTEMA mostrará en el formulario de proyecto y en Ajustes la lista Mis SC junto a la biblioteca.
- RF-10: EL SISTEMA permitirá duplicar, editar, renombrar y borrar un SC de Mis SC, con los nombres sin repetirse.
- RF-11: SI se edita o se borra un SC que usa un proyecto, ENTONCES EL SISTEMA no cambiará el SC del proyecto: este conserva una copia de la definición (S034/RF-10).
- RF-12: CUANDO el usuario exporte un SC, EL SISTEMA escribirá un fichero JSON con la definición completa, sus números sin redondear y con `Locale.ROOT`, y lo guardará o compartirá (RNF-T5).
- RF-13: CUANDO el usuario importe un fichero JSON de SC, EL SISTEMA validará su contenido, no solo su extensión, y mostrará el SC para confirmarlo antes de añadirlo a Mis SC.
- RF-14: SI el fichero JSON no es válido, tiene una versión que la app no conoce o usa un método no soportado, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-15: SI el nombre del SC importado ya existe y su definición es distinta, ENTONCES EL SISTEMA pedirá reemplazar, conservar ambos con otro nombre u omitirlo.

### Cambiar el SC de un proyecto (H3)
- RF-16: CUANDO el usuario edite un proyecto con puntos, EL SISTEMA permitirá elegir un SC de la biblioteca o de Mis SC del mismo datum que el actual (S006/RF-15, S008/RF-19).
- RF-17: CUANDO el usuario cambie el SC, EL SISTEMA mostrará todas las coordenadas en el nuevo SC sin modificar las medidas guardadas ni las geodésicas brutas.
- RF-18: SI el nuevo SC no puede calcular algún punto (fuera del ámbito, sin rejilla), ENTONCES EL SISTEMA lo indicará antes de aplicar el cambio y dejará elegir entre cancelar y continuar con esos puntos como no disponibles.
- RF-19: EL SISTEMA guardará en el historial del proyecto el cambio de SC con su fecha, el SC anterior y el nuevo, y permitirá deshacerlo (constitución nº 1).

### Cuadrícula o terreno (H4)
- RF-20: EL SISTEMA permitirá elegir en el proyecto coordenadas de cuadrícula o de terreno.
- RF-21: CUANDO el usuario elija terreno, EL SISTEMA pedirá un punto de origen (por defecto, el centro del proyecto, S006/RF-33) y un factor de escala, y propondrá el factor combinado en ese punto (S008/RF-15).
- RF-22: MIENTRAS el proyecto esté en terreno, EL SISTEMA mostrará, introducirá, importará y exportará las coordenadas y las distancias en terreno, y las identificará como tales en el panel y en los ficheros.
- RF-23: EL SISTEMA calculará el paso entre cuadrícula y terreno con una ida y vuelta ≤ 0,1 mm y sin tocar las coordenadas geodésicas guardadas.
- RF-24: CUANDO el usuario cambie entre cuadrícula y terreno o el factor, EL SISTEMA lo registrará como un cambio de SC (RF-19).

## Requisitos no funcionales
- Transversales que aplican: RNF-T5, RNF-T6 (migración sin pérdida y cambios atómicos), RNF-T7, RNF-T8, RNF-T9 (internamente m, rad) y RNF-T14.
- Constitución nº 2: un SC personalizado definido con los parámetros de un SC de la biblioteca da los mismos resultados que este (≤ 1 mm, ida y vuelta ≤ 0,1 mm), con vectores de pyproj; el paso a terreno se contrasta con pyproj más la fórmula de escala.
- El JSON exportado se vuelve a importar con la misma definición (ida y vuelta exacta de los números).
- El editor responde en menos de 100 ms por cambio de campo en el móvil de referencia.

## Casos límite
| Caso | RF |
|---|---|
| Aplanamiento 0 (esfera) | RF-2 |
| Parámetros fuera de rango | RF-6 |
| Editar un SC usado por proyectos | RF-11 |
| JSON de otra versión, dañado o con método no soportado | RF-14 |
| Nombre repetido al importar | RF-15 |
| Cambio de SC con puntos fuera de ámbito | RF-18 |
| Cambio a otro datum | RF-16 |
| Terreno con factor 1,0000 o muy distinto de 1 | RF-21 |
| Cambiar entre cuadrícula y terreno con puntos replanteados ya guardados | RF-17, RF-24 |

## Fuera de alcance
- Cambiar el SC por otro de distinto datum → no previsto: es una decisión pendiente (ver Dudas abiertas).
- Localización (calibración con pares H/V) → S039.
- Importar un SC desde WKT o `.prj` → no previsto.
- Proyecciones o transformaciones que no calculamos → S032, S033; transformaciones con el tiempo → no previsto.
- Descargar rejillas → S034.
- Compartir con otro móvil sin fichero (QR, Bluetooth) → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores de pyproj generados en `tools/geodesia` para SC personalizados equivalentes a los de la biblioteca y para terreno.
- Demo en el emulador: crear un SC, probarlo con coordenadas, exportarlo e importarlo, cambiar el SC de un proyecto con puntos y deshacerlo, pasar a terreno.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: solo se cambia a SC del mismo datum, como dice la hoja de ruta; S008 («Fuera de alcance») decía que cambiar a otro datum era de S035, y hay que decidir cuál vale.]
- [SUPOSICIÓN: el terreno es una escala respecto a un punto de origen con un factor único; no se modelan elevaciones por punto.]
- [SUPOSICIÓN: el proyecto guarda una copia de la definición del SC, de modo que borrar un SC de Mis SC no estropea los proyectos.]
- [SUPOSICIÓN: los puntos manuales, importados y COGO se guardan por sus geodésicas, de modo que se pueden reexpresar; si S011/S028 los guardan en coordenadas del SC, el cambio de SC los reproyectará.]
- [SUPOSICIÓN: el JSON es un formato propio de TopoPhone, versionado, sin relación con el de la app de referencia.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S035 en estado `spec`.
- S008 «Fuera de alcance»: se corrige si el cambio de datum se queda fuera de S035.
- `docs/glosario.md`: se añaden «SC personalizado», «Mis SC», «Coordenadas de cuadrícula» y «Coordenadas de terreno».
