# Spec 048 — Importar un GeoPackage

> Hito: H4 · Depende de: S040, S006 (punto central) · Estado: aprobada (26/10/2026)

## Contexto y objetivo
QGIS guarda hoy casi todo en GeoPackage (`.gpkg`): un solo fichero con varias capas de puntos, líneas y polígonos, con sus atributos y su sistema de coordenadas. Es el formato en el que llegan las parcelas, las redes, los límites municipales y los trabajos de un SIG. Con lo que hay, esos datos hay que convertirlos a DXF o a CSV con QGIS y se pierden los atributos o las capas.

Esta spec trae los `.gpkg` a la app como **capa de diseño**, igual que un DXF (S040, S041): se ven en el mapa, se tocan para ver sus atributos y se replantean. Como un GeoPackage puede contener un municipio o un país entero, solo se importa lo que cae dentro de una zona alrededor del **centro del proyecto**, que el usuario puede fijar al crear el proyecto (S006/RF-32 a RF-38). La exportación a `.gpkg` es otra spec (S053).

Lo importado es **referencia de diseño**, no dato de campo (constitución nº 1): no se mezcla con los puntos medidos, no aparece en la lista de puntos ni en las exportaciones del proyecto y no se edita.

## Usuarios / actores
- Topógrafo en oficina o en campo, con un `.gpkg` de QGIS con parcelas, redes o puntos de replanteo.

## Historias de usuario
- H1: Como topógrafo quiero elegir un `.gpkg`, ver qué capas trae y marcar las que me interesan.
- H2: Como topógrafo quiero que la app sepa en qué sistema de coordenadas está cada capa y la reproyecte, o me lo pregunte si no lo sabe.
- H3: Como topógrafo quiero importar solo lo que cae cerca del centro de mi proyecto, para no cargar un municipio entero.
- H4: Como topógrafo quiero ver en el mapa los puntos, las líneas y los polígonos, tocar un elemento y ver sus atributos, y replantearlo.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **GeoPackage**: un fichero `.gpkg`; una **capa** es cada tabla de objetos con geometría que contiene.
- **Capa de diseño** y **dibujo**: como en S040. El dibujo tiene el nombre del fichero; un polígono se convierte en líneas (RF-17).
- **Centro del proyecto**: el definido en S006/RF-32 a RF-38.
- **Zona de importación**: el círculo alrededor del centro del proyecto fuera del cual no se importa nada.
- **Campo de nombre**: la columna de una capa que da nombre a sus objetos.

### Elegir el fichero y las capas (H1)
- RF-1: CUANDO el usuario elija "Importar GeoPackage" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido, no por su extensión.
- RF-3: SI el fichero no es un GeoPackage, está dañado, está vacío o no tiene ninguna capa con geometría, ENTONCES EL SISTEMA no abrirá la pantalla e indicará el motivo.
- RF-4: SI el fichero supera los 100 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite (como S040/RF-5).
- RF-5: EL SISTEMA listará las capas con geometría del fichero con su nombre, su tipo (puntos, líneas o polígonos), el número de objetos y su sistema de coordenadas, y permitirá marcar cuáles se importan, todas marcadas por defecto.
- RF-6: EL SISTEMA no importará las tablas sin geometría ni las de teselas y avisará de que las ha dejado fuera.
- RF-7: SI el fichero se está usando por otra app o tiene cambios sin consolidar de QGIS, ENTONCES EL SISTEMA lo leerá igualmente con el contenido consolidado y no lo modificará nunca.

### Sistema de coordenadas (H2)
- RF-8: EL SISTEMA leerá el sistema de coordenadas de cada capa y, si es uno de los sistemas disponibles en la app (S008), lo propondrá y reproyectará las coordenadas al del proyecto.
- RF-9: SI el sistema de coordenadas de una capa no es uno de los disponibles o el fichero no lo declara, ENTONCES EL SISTEMA no importará la capa hasta que el usuario elija uno de los sistemas disponibles de la app para ella.
- RF-10: EL SISTEMA propondrá para las coordenadas la unidad del sistema de la capa y no permitirá cambiarla si el sistema la fija.
- RF-11: SI un objeto tiene un vértice fuera del ámbito del sistema del proyecto, ENTONCES EL SISTEMA no lo importará y lo contará como ignorado con el motivo (como S040/RF-17).
- RF-12: MIENTRAS la pantalla de importación esté abierta, EL SISTEMA mostrará la extensión de cada capa marcada en el sistema del proyecto y la distancia entre su centro y el centro del proyecto, y pedirá confirmación antes de importar si supera los 100 km (como S040/RF-9 y RF-10).

### Zona y contenido (H3)
- RF-13: EL SISTEMA propondrá como zona de importación un radio de 5 km alrededor del centro del proyecto y permitirá cambiarlo entre 100 m y 100 km, o elegir "Todo el contenido".
- RF-14: SI el proyecto no tiene centro y el usuario no ha elegido "Todo el contenido", ENTONCES EL SISTEMA pedirá fijar el punto central del proyecto (S006/RF-33) antes de importar.
- RF-15: EL SISTEMA importará solo los objetos que tengan algún vértice dentro de la zona de importación, enteros y sin cortarlos.
- RF-16: SI el recuento de objetos que se importarían supera los 500.000, ENTONCES EL SISTEMA no importará e indicará que hay que reducir la zona o las capas.
- RF-17: EL SISTEMA importará puntos, líneas, polígonos y sus versiones múltiples, con altura o sin ella, y convertirá cada polígono en una línea cerrada para su contorno y en otra para cada hueco, marcadas como contorno de polígono.
- RF-18: EL SISTEMA no importará los objetos con geometría vacía ni las geometrías curvas (arcos y curvas compuestas) y los contará como ignorados con el motivo.
- RF-19: DONDE los objetos tengan altura en su geometría y el usuario responda "Sí" a "¿Usar las cotas de la capa?", con "Sí" por defecto, EL SISTEMA guardará la altura de cada vértice como su cota, en el sistema de alturas del proyecto y sin convertirla (como S040/RF-21).

### Atributos y capa de diseño (H4)
- RF-20: EL SISTEMA conservará con cada objeto todos los atributos de su capa y los mostrará en su ficha al tocarlo en el mapa.
- RF-21: EL SISTEMA propondrá como campo de nombre de cada capa el que se llame "name", "nombre", "id" o "codigo" (sin distinguir mayúsculas) y permitirá elegir otro o ninguno.
- RF-22: EL SISTEMA usará el campo de nombre para etiquetar el objeto en el mapa y para nombrarlo en el replanteo, y, sin campo de nombre, su número dentro de la capa.
- RF-23: EL SISTEMA mostrará las capas importadas en el mapa como capas de diseño, agrupadas por tipo (puntos y líneas), con un color propio por capa, con interruptor para mostrarlas u ocultarlas y distinguidas de los puntos del proyecto (como S040/RF-24 a RF-28).
- RF-24: EL SISTEMA permitirá replantear los puntos y las líneas importados como los de un dibujo DXF, con S041.
- RF-25: CUANDO termine la importación, EL SISTEMA mostrará el resumen por capa (importados, fuera de la zona e ignorados con su motivo) con acceso a guardarlo o compartirlo como fichero de texto (como S040/RF-18).
- RF-26: EL SISTEMA guardará las coordenadas tal como se convierten, sin redondear, y asociará a cada dibujo el nombre del fichero y la fecha y hora de la importación.
- RF-27: CUANDO el usuario borre un dibujo importado y confirme, EL SISTEMA lo quitará entero y no modificará ningún punto del proyecto.
- RF-28: EL SISTEMA incluirá los dibujos importados en las copias de seguridad y en los paquetes de proyecto, y anotará en el log cada importación y cada borrado sin nombres ni coordenadas (S013, S014/RF-5).
- RF-29: CUANDO el usuario pulse "Importar", EL SISTEMA leerá el fichero en segundo plano, con progreso y un botón de "Cancelar", sin bloquear el resto de la app.
- RF-30: SI el usuario cancela, o no se puede escribir en el almacenamiento, ENTONCES EL SISTEMA no guardará nada del dibujo e indicará que no se ha importado (RNF-T6).

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: el fichero se elige con el selector de documentos y se valida por su contenido; la importación no deja una copia del fichero en el proyecto, solo los datos que ha leído (excepción justificada como en S028).
  - RNF-T6: la importación y el borrado de un dibujo se guardan en una sola transacción con durabilidad completa.
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; la distinción entre dibujo de diseño y puntos medidos, con icono y texto, nunca solo con color.
  - RNF-T9: coordenadas leídas en la unidad de su sistema y mostradas en la del proyecto.
  - RNF-T13: leer y cambiar la zona no bloquea el hilo principal más de 16 ms.
- Constitución nº 2: las coordenadas reproyectadas coinciden con pyproj a menos de 1 mm y la ida y vuelta difiere menos de 0,1 mm.
- Un GeoPackage de 100 MB se lee y se recorta a la zona en menos de 20 s en el móvil de referencia, y cambiar el radio de la zona actualiza el recuento en menos de 2 s.
- La lectura va por flujo: no se carga el fichero entero en memoria.

## Casos límite
| Caso | RF |
|---|---|
| Fichero no GeoPackage, dañado o sin capas | RF-3 |
| Fichero de más de 100 MB | RF-4 |
| Capas sin geometría o de teselas | RF-6 |
| Fichero con cambios sin consolidar o abierto por otra app | RF-7 |
| Sistema de coordenadas desconocido o no declarado | RF-9 |
| Capa en un sistema lejos del proyecto (más de 100 km) | RF-12 |
| Proyecto sin punto central, sin puntos y sin encuadre | RF-14 |
| Capa muy grande, más de 500.000 objetos en la zona | RF-16 |
| Polígonos con huecos o múltiples | RF-17 |
| Geometrías curvas o vacías | RF-18 |
| Objetos sin altura con "Usar las cotas" activado | RF-19 |
| Capa sin campo de nombre | RF-22 |
| Nombres repetidos de capas en dos ficheros | RF-23 |
| Cancelar o fallar al escribir | RF-30 |

## Fuera de alcance
- Exportar a GeoPackage → S053.
- Convertir lo importado en puntos, líneas o polígonos del proyecto → no previsto; los puntos de un SIG se importan como CSV (S028).
- Estilos, simbología y etiquetas de QGIS (la tabla de estilos del fichero se ignora) → no previsto.
- Rásteres y teselas dentro de un GeoPackage → S045 (rásteres de cotas) y no previsto para teselas.
- Geometrías curvas → no previsto.
- Campo de altura de un atributo como cota → no previsto.
- Editar los objetos importados o escribir sobre el `.gpkg` original → no previsto.
- Importar un proyecto de QGIS (`.qgz`) → no previsto.
- Shapefile → S030; KML y KMZ → S031.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Un `.gpkg` creado con QGIS con capas de puntos, líneas y polígonos en EPSG:25830 y en EPSG:4258 se importa y coincide con QGIS a menos de 1 mm.
- Demo en el emulador:
  - importar un gpkg con tres capas y marcar solo dos;
  - ver una capa en un sistema no soportado y elegir su sistema;
  - fijar el punto central de un proyecto nuevo, cambiar la zona y ver el recuento;
  - ver los objetos en el mapa, tocar uno y ver su ficha con atributos;
  - replantear un punto y una línea importados.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: los 500.000 objetos (RF-16) y los 5 km de la zona (RF-13) no salen de un fichero real; se revisan con uno de catastro.]
- [SUPOSICIÓN: los polígonos se tratan como líneas cerradas porque S041 no replantea superficies, solo puntos y líneas (RF-17).]
- [SUPOSICIÓN: los colores de las capas importadas se asignan automáticamente porque el fichero no trae un color propio (RF-23).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: fila S048 solo de importación (la exportación pasa a S053).
- `docs/glosario.md`: se añaden "GeoPackage" (`GeoPackage`), "Zona de importación" (`ImportArea`) y "Campo de nombre" (`nameField`).
- S040: los dibujos pasan a poder venir de un DXF o de un GeoPackage (el nombre del fichero y su procedencia se guardan con el dibujo); su aviso de distancia usa el centro del proyecto de S006/RF-37.
