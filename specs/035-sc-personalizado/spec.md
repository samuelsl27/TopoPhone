# Spec 035 — SC personalizado

> Hito: H5 · Depende de: S034, S033, S032 · Estado: spec (entrevista 22/11/2026)

## Contexto y objetivo
La biblioteca (S034) cubre los SC del registro EPSG, pero los trabajos reales usan también SC que no están allí: una proyección local con el meridiano central de la obra, un datum con los parámetros que da el cliente. Quien no puede definirlo hoy tiene que abandonar la app o aceptar coordenadas equivocadas.

Esta spec añade un editor de SC personalizado con elipsoide, datum, proyección y altura; una lista «Mis SC» con importar y exportar JSON para pasar los SC a otros móviles; y el cambio del SC de un proyecto ya empezado dentro del mismo datum, con historial y «Deshacer». Todo sin nube y con las mismas garantías de precisión que los SC de la biblioteca. La localización (S039) usará este editor para «guardar como SC». Las coordenadas de terreno (escala desde un punto de origen) se separan en S058.

## Usuarios / actores
- Topógrafo que recibe de su cliente los parámetros de un SC que no está en la biblioteca.
- Jefe de equipo que prepara un SC y lo pasa a los demás móviles por fichero.
- Topógrafo que se da cuenta de que eligió mal el SC de un proyecto ya empezado.

## Historias de usuario
- H1: Como topógrafo quiero definir mi propio SC y comprobar que da los valores de mi cliente antes de usarlo.
- H2: Como jefe de equipo quiero guardar los SC en «Mis SC» y pasarlos a otros móviles.
- H3: Como topógrafo quiero cambiar el SC de un proyecto ya empezado sin perder ninguna medida y poder volver atrás.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **SC personalizado**: SC definido por el usuario con elipsoide, datum (con su transformación, S033), proyección (S032 o UTM/TM), unidades y altura.
- **Mis SC**: lista de los SC personalizados guardados en el móvil.
- **Historial del proyecto**: lista de los cambios de SC de un proyecto, del más reciente al más antiguo.

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
- RF-9: EL SISTEMA mostrará la lista Mis SC junto a la biblioteca en el formulario de proyecto y en el selector de SC de origen de las importaciones (S057), y en Ajustes para gestionarla.
- RF-10: EL SISTEMA permitirá duplicar, editar, renombrar y borrar un SC de Mis SC, con los nombres sin repetirse.
- RF-11: SI se edita o se borra un SC que usa un proyecto, ENTONCES EL SISTEMA no cambiará el SC del proyecto: este conserva una copia de la definición (S034/RF-10).
- RF-12: CUANDO el usuario exporte un SC, EL SISTEMA escribirá un fichero JSON con la definición completa, sus números sin redondear y con `Locale.ROOT`, y lo guardará o compartirá (RNF-T5).
- RF-13: CUANDO el usuario importe un fichero JSON de SC, EL SISTEMA validará su contenido, no solo su extensión, y mostrará el SC para confirmarlo antes de añadirlo a Mis SC.
- RF-14: SI el fichero JSON no es válido, tiene una versión que la app no conoce o usa un método no soportado, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-15: SI el nombre del SC importado ya existe y su definición es distinta, ENTONCES EL SISTEMA pedirá reemplazar, conservar ambos con otro nombre u omitirlo.
- RF-16: EL SISTEMA incluirá Mis SC en la copia de seguridad completa y la restaurará con ella (S013).

### Cambiar el SC de un proyecto (H3)
- RF-17: CUANDO el usuario edite un proyecto con puntos, EL SISTEMA permitirá elegir un SC de la biblioteca o de Mis SC del mismo datum que el actual (S006/RF-15, S008/RF-19).
- RF-18: CUANDO el usuario cambie el SC, EL SISTEMA mostrará todas las coordenadas en el nuevo SC sin modificar las medidas guardadas ni las geodésicas brutas.
- RF-19: SI el nuevo SC no puede calcular algún punto (fuera del ámbito, sin rejilla), ENTONCES EL SISTEMA lo indicará antes de aplicar el cambio y dejará elegir entre cancelar y continuar con esos puntos como no disponibles.
- RF-20: CUANDO se aplique un cambio de SC, EL SISTEMA lo guardará en el historial del proyecto con su fecha, el SC anterior y el nuevo (constitución nº 1).
- RF-21: CUANDO el usuario abra el historial del proyecto, EL SISTEMA mostrará sus cambios de SC y ofrecerá «Volver al SC anterior» en el más reciente, que se registrará como un cambio más.
- RF-22: EL SISTEMA incluirá el historial del proyecto en las copias de seguridad y en los paquetes de proyecto (S013).

## Requisitos no funcionales
- Transversales que aplican: RNF-T5, RNF-T6 (migración sin pérdida y cambios atómicos), RNF-T7, RNF-T8, RNF-T9 (internamente m, rad) y RNF-T14.
- Constitución nº 2: un SC personalizado definido con los parámetros de un SC de la biblioteca da los mismos resultados que este (≤ 1 mm, ida y vuelta ≤ 0,1 mm), con vectores de pyproj.
- El JSON exportado se vuelve a importar con la misma definición (ida y vuelta exacta de los números).
- El editor responde en menos de 100 ms por cambio de campo en el móvil de referencia.
- Un cambio de SC en un proyecto de 10.000 puntos se aplica en menos de 2 s.

## Casos límite
| Caso | RF |
|---|---|
| Aplanamiento 0 (esfera) | RF-2 |
| Parámetros fuera de rango | RF-6 |
| Editar o borrar un SC usado por proyectos | RF-11 |
| JSON de otra versión, dañado o con método no soportado | RF-14 |
| Nombre repetido al importar | RF-15 |
| Cambio de SC con puntos fuera de ámbito | RF-19 |
| Intentar cambiar a un SC de otro datum | RF-17 |
| Volver al SC anterior cuando este ya se borró de Mis SC | RF-11, RF-21 |
| Restaurar una copia con Mis SC de nombre igual a uno existente | RF-15, RF-16 |

## Fuera de alcance
- Cambiar el SC por otro de distinto datum → no previsto (S008 ya lo deja fuera).
- Coordenadas de cuadrícula o de terreno, punto de origen y factor → S058.
- Localización (calibración con pares H/V) → S039.
- Importar un SC desde WKT o `.prj` → no previsto.
- Proyecciones o transformaciones que no calculamos → S032, S033; transformaciones con el tiempo → no previsto.
- Descargar rejillas → S034.
- Compartir con otro móvil sin fichero (QR, Bluetooth) → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Vectores de pyproj generados en `tools/geodesia` para SC personalizados equivalentes a los de la biblioteca.
- Demo en el emulador: crear un SC, probarlo con coordenadas, exportarlo e importarlo, cambiar el SC de un proyecto con puntos y deshacerlo.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: el proyecto guarda una copia de la definición del SC, de modo que borrar un SC de Mis SC no estropea los proyectos.]
- [SUPOSICIÓN: los puntos se guardan por sus geodésicas (arquitectura.md), de modo que se pueden reexpresar; si algún tipo de objeto (COGO, importados) se guarda en coordenadas del SC, el cambio de SC lo reproyectará; se comprueba en el plan.]
- [SUPOSICIÓN: el JSON es un formato propio de TopoPhone, versionado, sin relación con el de la app de referencia.]

## Cambios en documentos anteriores (no se aplican hasta aprobar)
- `docs/hoja-de-ruta.md`: S035 pasa a `spec` con el alcance sin cuadrícula/terreno; fila nueva S058 «coordenadas-de-terreno» (cuadrícula o terreno, punto de origen y factor, pantallas, CSV, replanteo y todos los formatos; depende de 035) antes de S039.
- `docs/glosario.md`: se añaden «SC personalizado», «Mis SC» e «Historial del proyecto».
- S008 «Fuera de alcance» no cambia: ya dice que S035 cambia el SC dentro del mismo datum.
