# Spec 040 — Capa de diseño desde un DXF

> Hito: H2 · Depende de: S009 · Estado: aprobada

## Contexto y objetivo
El diseño de una obra casi siempre llega como un plano en DXF: líneas de eje, bordes de calzada, parcelas, redes, puntos de replanteo. Hoy ese plano se mira en otro programa y los puntos se teclean o se pasan a un CSV (S028), con lo que se pierden las líneas. Esta spec trae el DXF al proyecto: se elige el fichero, se dice en qué sistema de coordenadas está, se importa en segundo plano y su contenido se ve en el mapa como una capa de diseño, ordenada por capas del DXF y por tipo (puntos y líneas), con cada capa con su color y un interruptor para mostrarla u ocultarla.

El dibujo es **referencia de diseño**, no dato de campo (constitución nº 1): no se mezcla con los puntos del proyecto, no aparece en la lista de puntos ni en las exportaciones y no se puede editar. Su sentido es dejarlo listo para replantearlo con S041, incluida la cota.

Los DWG no se leen (licencia, RNF-T14): se detectan y se avisa de que hay que convertirlos a DXF fuera de la app.

## Usuarios / actores
- Topógrafo en oficina o en campo, con el DXF del proyectista.
- Proyectista o cliente que entrega el plano, a veces con bloques, polilíneas con arcos, splines, textos y acotaciones.

## Historias de usuario
- H1: Como topógrafo quiero elegir un DXF, decir en qué sistema está y si uso sus cotas, e importarlo sin bloquear la app aunque sea grande.
- H2: Como topógrafo quiero ver el dibujo en el mapa con los colores de sus capas y mostrar u ocultar cada una.
- H3: Como topógrafo quiero importar varios DXF en el mismo proyecto y que sus capas convivan.
- H4: Como topógrafo quiero saber qué se ha importado y qué se ha ignorado, y que un DWG o un fichero malo no me dejen el proyecto a medias.
- H5: Como topógrafo quiero tocar un elemento del dibujo y ver sus datos, y borrar un dibujo entero si me he equivocado.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Dibujo**: el contenido de un DXF importado. Un proyecto puede tener varios, sin límite.
- **Capa de diseño**: una capa del DXF, separada por tipo de elemento. Una capa del DXF con puntos y líneas da dos capas de diseño, una de puntos y otra de líneas, con el mismo nombre.
- **Elemento**: un punto, o una línea (línea, polilínea, arco, círculo o spline) del dibujo, con los bloques ya desplegados.
- **Con cota**: un elemento cuyos vértices traen altura propia en el DXF.

### Elegir el fichero y configurar la importación (H1)
- RF-1: CUANDO el usuario elija "Importar DXF" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación del dibujo (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido, no por su extensión, y aceptará DXF de texto de las versiones R12 a R2018.
- RF-3: SI el fichero es un DWG, ENTONCES EL SISTEMA no lo importará e indicará que los DWG no se leen y que hay que convertirlo a DXF fuera de la app.
- RF-4: SI el fichero es un DXF binario, está vacío o no es un DXF, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-5: SI el fichero supera los 100 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite.
- RF-6: EL SISTEMA propondrá que las coordenadas están en el sistema del proyecto y permitirá elegir «En otro sistema» y entonces uno de los sistemas disponibles de la app; en ese caso las convertirá al del proyecto.
- RF-7: EL SISTEMA propondrá para las coordenadas la unidad que declare el DXF, si es metros, pies o pies topográficos de EE. UU., y si no la unidad lineal del proyecto, y permitirá elegir entre las tres (RNF-T9).
- RF-8: EL SISTEMA preguntará «¿Usar las cotas del dibujo?», con «Sí» por defecto, y, si la respuesta es «No», importará todos los elementos sin cota.
- RF-9: MIENTRAS la pantalla de importación esté abierta, EL SISTEMA mostrará la extensión del dibujo (Este y Norte mínimos y máximos en el sistema del proyecto) y la distancia entre su centro y el del proyecto, y actualizará ambos al cambiar el sistema o la unidad.
- RF-10: SI la distancia entre el centro del dibujo y el del proyecto supera los 100 km, ENTONCES EL SISTEMA mostrará un aviso y pedirá confirmación antes de importar.

### Importar (H1, H3, H4)
- RF-11: CUANDO el usuario pulse "Importar", EL SISTEMA leerá el fichero en segundo plano, con un progreso y un botón de "Cancelar", sin bloquear el resto de la app.
- RF-12: SI el usuario cancela, o no se puede escribir en el almacenamiento, ENTONCES EL SISTEMA no guardará nada del dibujo e indicará que no se ha importado (RNF-T6).
- RF-13: SI un DXF está dañado o incompleto pero se puede leer algún elemento, ENTONCES EL SISTEMA importará lo leído y avisará de que el dibujo está incompleto; si no se puede leer ninguno, no importará nada e indicará el motivo.
- RF-14: EL SISTEMA importará los puntos, las líneas, las polilíneas (2D, 3D y con arcos), los arcos, los círculos y las splines, y desplegará los bloques insertados, con su posición, escala y giro y también los anidados.
- RF-15: EL SISTEMA convertirá los arcos, los círculos y las splines en polilíneas que no se aparten de la curva más de 1 cm, y conservará el punto de inserción de cada bloque como un punto del dibujo.
- RF-16: EL SISTEMA ignorará los textos, las acotaciones, los sombreados, los sólidos, las imágenes y cualquier otro tipo no listado en RF-14.
- RF-17: SI un elemento tiene un vértice fuera del ámbito del sistema del proyecto, ENTONCES EL SISTEMA no lo importará y lo contará como ignorado, con el motivo.
- RF-18: CUANDO termine la importación, EL SISTEMA mostrará el resumen: elementos importados por tipo, elementos ignorados por tipo o motivo, y capas creadas, con acceso a guardar o compartir el resumen como un fichero de texto.
- RF-19: EL SISTEMA guardará las coordenadas del dibujo tal como se convierten, sin redondear, y asociará a cada dibujo el nombre del fichero y la fecha y hora de la importación.
- RF-20: SI el proyecto ya tiene un dibujo con el mismo nombre de fichero, ENTONCES EL SISTEMA avisará antes de importar y, si el usuario confirma, creará otro dibujo aparte.

### Las cotas (H1)
- RF-21: DONDE el usuario use las cotas del dibujo, EL SISTEMA guardará la Z de cada vértice como su cota, en el sistema de alturas del proyecto y sin convertirla.
- RF-22: EL SISTEMA dará por elemento sin cota el que no trae Z (entidades 2D) y todo el dibujo si todas las Z son 0.
- RF-23: EL SISTEMA marcará en cada elemento si está con cota o sin cota, para que S041 pueda replantear la cota de los que la tienen y solo la planta de los demás.

### Ver el dibujo en el mapa (H2, H3)
- RF-24: EL SISTEMA dibujará en el mapa del proyecto los elementos de las capas de diseño visibles, con el color de su capa o el propio del elemento, y los distinguirá de los puntos del proyecto.
- RF-25: EL SISTEMA dibujará en negro sobre fondo claro y en blanco sobre fondo oscuro el color blanco o negro del DXF, para que no desaparezca (RNF-T8).
- RF-26: EL SISTEMA listará las capas de diseño agrupadas por tipo (puntos y líneas), con su nombre, su color, el número de elementos, el dibujo del que vienen y un interruptor para mostrarla u ocultarla.
- RF-27: CUANDO el usuario oculte o muestre una capa de diseño, EL SISTEMA lo recordará para ese proyecto, también al cerrar la app.
- RF-28: EL SISTEMA no mezclará las capas de dos dibujos que se llamen igual: las listará aparte, cada una con su dibujo.
- RF-29: CUANDO el usuario toque un elemento visible del dibujo en el mapa, EL SISTEMA mostrará su ficha: tipo, capa, dibujo, coordenadas del punto o longitud y número de vértices de la línea, y su cota o «Sin cota».
- RF-33: DONDE una capa del DXF esté apagada o congelada en el propio fichero, EL SISTEMA la importará oculta.

### Borrar un dibujo (H5)
- RF-30: CUANDO el usuario borre un dibujo y confirme, EL SISTEMA lo quitará entero, con sus capas, y no modificará ningún punto del proyecto.
- RF-31: EL SISTEMA incluirá los dibujos en las copias de seguridad y en los paquetes de proyecto (S013).
- RF-32: EL SISTEMA anotará en el log cada importación y cada borrado con su resultado, sin nombres de elementos ni coordenadas (S014/RF-5).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: el fichero se elige con el selector de documentos y se valida por su contenido, no por su tipo MIME. Los datos del dibujo se guardan en el almacenamiento privado del proyecto.
  - RNF-T6: un dibujo se guarda entero o no se guarda (salvo RF-13), con durabilidad completa; al actualizar la app, los proyectos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el estado de cada capa y el resumen con icono y texto, nunca solo con color; TalkBack lee los recuentos y los interruptores de las capas.
  - RNF-T9: las coordenadas se leen con el punto decimal del DXF, no con la configuración regional.
  - RNF-T13: dibujar el mapa con un dibujo cargado no bloquea el hilo principal más de 16 ms.
  - RNF-T14: la lectura del DXF es propia (ADR-14), sin librerías que no sean GPL-3.0 compatibles.
- Constitución nº 2: las coordenadas del dibujo convertidas de otro sistema al del proyecto dan las mismas que pyproj a menos de 1 mm; la ida y vuelta difiere menos de 0,1 mm.
- La aproximación de un arco, un círculo o una spline como polilínea se aparta de la curva exacta no más de 1 cm (RF-15).
- Un DXF de 100 MB se lee sin cargar el fichero entero en memoria, sin cerrar la app por falta de memoria en el móvil de referencia, y se importa en menos de 60 s.
- Con un dibujo de 100 MB importado, mover y ampliar el mapa mantiene los 5 Hz de refresco: solo se dibuja lo que cae dentro de la vista.
- Abrir un proyecto con un dibujo importado de 100 MB tarda menos de 3 s en mostrarlo.

## Casos límite
| Caso | RF |
|---|---|
| DWG renombrado como .dxf, o DXF renombrado como .txt | RF-2, RF-3 |
| DXF binario, vacío o texto cualquiera | RF-4 |
| Fichero de más de 100 MB | RF-5 |
| DXF en otro sistema, o en pies | RF-6, RF-7 |
| DXF todo con Z = 0, o 2D | RF-22 |
| Dibujo a más de 100 km del proyecto | RF-10 |
| Cancelar o fallo de escritura a mitad | RF-12 |
| DXF cortado a mitad o con una sección rota | RF-13 |
| Bloque dentro de bloque, con escala negativa o girado | RF-14 |
| Círculo, arco y spline muy grandes | RF-15 |
| Textos, acotaciones, sombreados | RF-16 |
| Elemento con un vértice fuera del ámbito del sistema | RF-17 |
| Mismo fichero importado dos veces | RF-20 |
| Capa con puntos y líneas | Términos, RF-26 |
| Dos dibujos con una capa del mismo nombre | RF-28 |
| Color blanco sobre el mapa claro | RF-25 |
| Capa apagada o congelada en el DXF | RF-33 |
| Nombre de capa con acentos o caracteres no latinos | RF-14, RF-26 |
| Borrar un dibujo con una capa oculta | RF-30 |

## Fuera de alcance
- Replantear puntos, vértices, líneas y segmentos del dibujo → S041.
- Pasar los puntos o las líneas del dibujo a puntos o líneas del proyecto → no previsto.
- Mostrar los textos, las acotaciones y los sombreados → no previsto.
- Importar DWG → no previsto (RNF-T14): se convierten fuera.
- Editar el dibujo, medir sobre él o cambiar el color de una capa → no previsto.
- Desplazar o girar un dibujo a mano para colocarlo → no previsto.
- Exportar un DXF → S029.
- Superficies y rasters → S042 y S045.
- Shapefile y KML → S030 y S031.
- Elipses, mallas 3D y 3DFACE → no previsto (RF-16).

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Tests de la lectura frente a ficheros de ejemplo (`fixtures/formatos/dxf/`): R12, R2000, R2010 y R2018, con y sin bloques anidados, arcos, círculos, splines, polilíneas con bulge, capas apagadas y congeladas, un DWG y un DXF binario.
- Test de las coordenadas convertidas de otro sistema frente a pyproj (≤ 1 mm; ida y vuelta ≤ 0,1 mm) en ETRS89 y REGCAN95.
- Test de migración de la base de datos con un proyecto de la versión anterior.
- Rendimiento: un DXF de 100 MB se importa en menos de 60 s y se dibuja a 5 Hz.
- Demo en el emulador:
  - importar un DXF con puntos, líneas, un bloque y un arco, y ver las capas con su color en el mapa;
  - ocultar una capa y comprobar que sigue oculta al reabrir el proyecto;
  - importar un segundo DXF y ver sus capas aparte;
  - intentar importar un DWG y ver el aviso;
  - tocar una línea y un punto y ver su ficha;
  - borrar un dibujo y comprobar que los puntos del proyecto siguen igual.
- Demo de campo: queda para el usuario (puerta G2b, tras S041).

## Dudas abiertas
Resueltas con una suposición. Si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: el límite de 100 MB (RF-5) es el de la hoja de ruta; no hay un fichero real que lo fije.]
- [SUPOSICIÓN: el aviso de lejanía son 100 km (RF-10).]
- [SUPOSICIÓN: la aproximación de las curvas a polilíneas es de 1 cm (RF-15), lo bastante fino para replantear una curva con tolerancia de 2,5 cm.]
- [SUPOSICIÓN: «todas las Z son 0» (RF-22) se aplica a todo el dibujo, no por capa; un dibujo con algunas Z reales y otras 0 las respeta todas.]
- [SUPOSICIÓN: la unidad de las coordenadas se propone con la que declare el DXF (RF-7).]
- [SUPOSICIÓN: importar un fichero con el mismo nombre crea otro dibujo tras avisar, no lo sustituye (RF-20).]
- [SUPOSICIÓN: las elipses se ignoran en esta versión (RF-16).]
- [RESUELTA: una capa apagada o congelada en el DXF llega oculta (RF-33).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S040: pasa a `spec`, y después a `tareas`, `en curso` e `implementada`. Se añade a la descripción "capas por tipo, varios dibujos por proyecto, cota opcional".
- `docs/glosario.md`: se añaden "Dibujo" (`CadDrawing`, ya existe como "Dibujo CAD"), "Capa de diseño" (`DesignLayer`) y "Elemento del dibujo" (`CadEntity`).
- S013 (copia y paquete): incluyen los dibujos (RF-31).
