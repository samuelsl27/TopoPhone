# Spec 006 — Proyectos

> Hito: H1 · Depende de: S005 · Estado: aprobada

## Contexto y objetivo
Todo levantamiento empieza con un proyecto. Hoy la sección Levantamiento está vacía y no se pueden crear trabajos, que es lo primero que echa en falta quien viene de Emlid Flow.

Esta spec trae los proyectos, organizados como en la app de referencia y con diseño propio (RNF-T14):
- Levantamiento muestra la lista de proyectos, con "+" para crear y un menú con la papelera;
- cada proyecto guarda su sistema de coordenadas, sus unidades y el datum de las correcciones;
- al abrirlo se ve la posición actual del receptor en su sistema de coordenadas.

Los proyectos se agrupan en carpetas, y uno borrado pasa 30 días en la papelera antes de desaparecer, porque el dato de campo es sagrado (constitución nº 1). Los puntos llegan en S010 y el mapa en S009: esta spec deja el proyecto preparado para ellos.

## Usuarios / actores
- Topógrafo en campo que crea, abre y organiza trabajos.
- Receptor RS3, o una sesión reproducida, como fuente de la posición actual.

## Historias de usuario
- H1: Como topógrafo quiero ver mis proyectos, del último abierto al más antiguo, y encontrar uno escribiendo parte de su nombre.
- H2: Como topógrafo quiero crear un proyecto con su sistema de coordenadas, sus unidades y el datum de las correcciones, partiendo de mis valores habituales.
- H3: Como topógrafo quiero cambiar los datos de un proyecto, duplicarlo y agruparlo en carpetas.
- H4: Como topógrafo quiero recuperar un proyecto que he borrado por error.
- H5: Como topógrafo quiero abrir un proyecto y ver dónde estoy en su sistema de coordenadas.

## Requisitos funcionales (criterios de aceptación en EARS)
### Lista de proyectos (H1)
- RF-1: CUANDO el usuario abra Levantamiento, EL SISTEMA mostrará sus carpetas y, después, los proyectos que no están en ninguna carpeta, del abierto más recientemente al más antiguo.
- RF-2: EL SISTEMA mostrará de cada proyecto de la lista su nombre, su sistema de coordenadas y la fecha de su última modificación.
- RF-3: SI no hay ningún proyecto ni carpeta, ENTONCES EL SISTEMA mostrará que aún no hay proyectos y ofrecerá crear uno.
- RF-4: CUANDO el usuario escriba en la búsqueda, EL SISTEMA mostrará los proyectos de todas las carpetas cuyo nombre o descripción contenga el texto, sin distinguir mayúsculas ni tildes, con la carpeta de cada uno.

### Crear un proyecto (H2)
- RF-5: CUANDO el usuario pulse "+", EL SISTEMA abrirá el formulario de proyecto nuevo con el nombre, el autor, la descripción, el sistema de coordenadas, las unidades lineales, las unidades angulares y el datum de las correcciones.
- RF-6: EL SISTEMA rellenará el formulario de proyecto nuevo con los valores por defecto de Ajustes, salvo el nombre.
- RF-7: SI el nombre está vacío o coincide con el de otro proyecto sin distinguir mayúsculas, ENTONCES EL SISTEMA no guardará el proyecto e indicará el motivo.
- RF-8: EL SISTEMA ofrecerá como sistema de coordenadas del proyecto ETRS89 / UTM husos 29, 30 o 31, con altitudes EGM08-REDNAP.
- RF-9: EL SISTEMA ofrecerá como unidades lineales metro, pie internacional y pie topográfico de EE. UU., y como unidades angulares grados decimales, grados-minutos-segundos y gon (RNF-T9).
- RF-10: EL SISTEMA ofrecerá como datum de las correcciones ETRS89, REGCAN95 o ITRF/WGS84.
- RF-11: SI el datum de las correcciones es ITRF/WGS84 y el del sistema de coordenadas es ETRS89, ENTONCES EL SISTEMA avisará en el formulario y en la vista de proyecto de que las coordenadas pueden diferir unos 0,9 m.
- RF-12: CUANDO el usuario guarde un proyecto nuevo válido, EL SISTEMA lo guardará y lo abrirá.
- RF-13: EL SISTEMA ofrecerá en Ajustes los valores por defecto de los proyectos nuevos: autor, sistema de coordenadas, unidades lineales, unidades angulares y datum de las correcciones.

### Editar, duplicar y organizar (H3)
- RF-14: CUANDO el usuario edite un proyecto, EL SISTEMA permitirá cambiar todos los datos del formulario, con las mismas comprobaciones que al crearlo.
- RF-15: CUANDO el usuario cambie el sistema de coordenadas de un proyecto por otro del mismo datum, EL SISTEMA mostrará a partir de ese momento todas sus coordenadas en el sistema nuevo, sin modificar las medidas guardadas.
- RF-16: CUANDO el usuario duplique un proyecto, EL SISTEMA creará una copia con todos sus datos y contenido, en la misma carpeta, llamada "<nombre> (copia)" o, si ya existe, "<nombre> (copia 2)", "(copia 3)"…
- RF-17: CUANDO el usuario cree una carpeta con un nombre no vacío y distinto del de las demás carpetas, EL SISTEMA la añadirá a la lista.
- RF-18: CUANDO el usuario renombre una carpeta, EL SISTEMA aplicará las mismas comprobaciones que al crearla.
- RF-19: CUANDO el usuario mueva un proyecto a una carpeta o lo saque de ella, EL SISTEMA lo mostrará en su nuevo sitio.
- RF-20: CUANDO el usuario abra una carpeta, EL SISTEMA mostrará sus proyectos, del abierto más recientemente al más antiguo.
- RF-21: CUANDO el usuario borre una carpeta, EL SISTEMA pedirá confirmación y moverá sus proyectos fuera de ella antes de borrarla.

### Papelera (H4)
- RF-22: CUANDO el usuario borre un proyecto, EL SISTEMA pedirá confirmación y lo moverá a la papelera, de donde se puede recuperar durante 30 días.
- RF-23: CUANDO el usuario abra la papelera desde el menú de la lista, EL SISTEMA mostrará los proyectos borrados con los días que les quedan.
- RF-24: CUANDO el usuario restaure un proyecto de la papelera, EL SISTEMA lo devolverá a su carpeta, o fuera de las carpetas si la suya ya no existe, con todo su contenido.
- RF-25: SI al restaurar un proyecto ya existe otro con su nombre, ENTONCES EL SISTEMA lo restaurará con el nombre "<nombre> (restaurado)".
- RF-26: CUANDO el usuario borre definitivamente un proyecto de la papelera, EL SISTEMA pedirá confirmación y lo eliminará con todo su contenido.
- RF-27: CUANDO un proyecto cumpla 30 días en la papelera, EL SISTEMA lo eliminará definitivamente la siguiente vez que se abra la app.

### Vista de proyecto (H5)
- RF-28: CUANDO el usuario abra un proyecto, EL SISTEMA mostrará su nombre, su sistema de coordenadas, sus unidades y el datum de las correcciones.
- RF-29: MIENTRAS haya posición del receptor o de una reproducción, la vista de proyecto mostrará la posición del punto del suelo (con la altura del jalón de Receptor) en el sistema de coordenadas del proyecto: Este, Norte y altitud, en sus unidades lineales.
- RF-30: SI el punto del suelo queda fuera del ámbito del sistema de coordenadas del proyecto o del modelo de geoide, ENTONCES EL SISTEMA mostrará esas coordenadas como no disponibles e indicará el motivo.
- RF-31: MIENTRAS el proyecto no tenga puntos, la vista de proyecto mostrará que todavía no hay puntos.

## Requisitos no funcionales
- Transversales que aplican: RNF-T6, RNF-T7, RNF-T8, RNF-T9 y RNF-T13.
- Un proyecto creado, editado o movido a la papelera se conserva aunque Android cierre la app justo después de confirmarlo (RNF-T6).
- Con 500 proyectos, la lista aparece en menos de 1 s y la búsqueda filtra en menos de 300 ms en el móvil de referencia (RNF-T13).
- Las coordenadas de la vista de proyecto coinciden con las de pyproj para el mismo punto, SC y geoide con una diferencia ≤ 1 mm (constitución nº 2).
- Al actualizar la app, todos los proyectos, carpetas y proyectos de la papelera se conservan; cada cambio en los datos guardados tiene un test de migración (RNF-T6).

## Casos límite
| Caso | RF |
|---|---|
| Nombre vacío o repetido (también con otras mayúsculas) | RF-7 |
| Carpeta con nombre vacío o repetido | RF-17, RF-18 |
| Duplicar varias veces el mismo proyecto | RF-16 |
| Borrar una carpeta con proyectos | RF-21 |
| Restaurar un proyecto cuya carpeta ya no existe | RF-24 |
| Restaurar un proyecto cuyo nombre ya está en uso | RF-25 |
| Proyecto que cumple 30 días en la papelera con la app cerrada | RF-27 |
| Datum de las correcciones distinto del del SC | RF-11 |
| Posición fuera del huso o del geoide | RF-30 |
| Sin receptor ni reproducción en la vista de proyecto | RF-29 |
| La app se cierra justo después de crear un proyecto | RNF de la spec |

## Fuera de alcance
- El mapa en la vista de proyecto → S009.
- Recolectar puntos → S010; la lista de objetos (buscar, editar, borrar puntos) → S011.
- Exportar → S012 (CSV) y S013 (paquete `.topoproj` y copias); importar desde el menú de la lista → S013 (paquete) y S027 (CSV).
- Los demás SC de España (UTM 28, REGCAN95 UTM 27/28, geográficas, altura elipsoidal) y el huso sugerido por la posición → S008.
- Biblioteca de códigos vinculada al proyecto → S022; capas del proyecto → S035.
- Base shift → S019; localización → S038; biblioteca mundial de SC → S033.
- Subcarpetas → no previstas.
- Sincronización o nube → nunca (constitución nº 4).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de coordenadas de la vista de proyecto contra vectores de pyproj (≤ 1 mm).
- Demo en el emulador: crear dos proyectos, buscar, duplicar, crear una carpeta y mover un proyecto, borrar y restaurar desde la papelera, y abrir un proyecto con una reproducción para ver la posición en su SC.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: los nombres de proyecto son únicos en toda la app, no solo dentro de cada carpeta, para que los ficheros exportados no se confundan.]
- [SUPOSICIÓN: un solo nivel de carpetas; un proyecto está como mucho en una carpeta.]
- [SUPOSICIÓN: al abrir la app se muestra la lista de proyectos; no se reabre el último proyecto.]
- [SUPOSICIÓN: los valores por defecto de fábrica son ETRS89 / UTM huso 30 con EGM08-REDNAP, metros, grados-minutos-segundos y datum ETRS89, sin autor.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S006: "base de datos v1 con todos los tipos de objeto" pasa a ser "almacenamiento de proyectos con banco de pruebas de migraciones; cada spec posterior añade sus datos con una migración probada", y se añade "papelera de 30 días".
- `docs/arquitectura.md`, ADR-8: se anota (15/10/2026) que el esquema crece spec a spec, cada una con su migración y su test.
- `docs/glosario.md`: se añaden "Carpeta", "Papelera", "Vista de proyecto", "Datum de las correcciones", "Unidades lineales" y "Unidades angulares".
