# Spec 042 — Superficies LandXML: desmonte y terraplén respecto a una superficie

> Hito: H2 · Depende de: S019, S041 · Estado: aprobada

## Contexto y objetivo
Hasta ahora la cota de diseño de un replanteo es la de un punto, la de un vértice del dibujo o la que escribe el usuario (S019, S041). Muchas obras no tienen una cota por punto, sino una **superficie**: el terreno de proyecto, una rasante, una explanación. El proyectista la entrega como un modelo de triángulos en un fichero LandXML. Esta spec lleva esa superficie al móvil para que la app diga, en la posición del jalón, cuánto hay que desmontar o terraplenar, y para que un punto sin cota se replantee a la cota que dicta la superficie.

La superficie es referencia de diseño, como un dibujo de S040: no se modifica y no entra en la lista de puntos ni en las exportaciones. Lo que se mide se guarda como un punto del proyecto, con la cota de la superficie y el desplazamiento vertical que se usaron, para poder revisarlo después (constitución nº 1). La calidad va antes que la rapidez (nº 3): fuera de la superficie la app no inventa ninguna cota, y la guía sigue los mismos umbrales de S018, S019 y S010.

Un proyecto puede guardar varias superficies importadas, pero solo una está activa. El ráster de cotas (GeoTIFF y ASCII Grid) llega en S045, con el mismo uso.

## Usuarios / actores
- Topógrafo en campo con el jalón y el RS3 en FIX (o una reproducción), que necesita saber cuánto desmontar o rellenar respecto a la superficie de proyecto.
- Técnico en oficina que prepara el LandXML y después revisa cuánto se desvió cada punto replanteado.
- Receptor RS3 o una reproducción como fuente de las épocas.

## Historias de usuario
- H1: Como técnico quiero importar un LandXML con una o varias superficies y decir en qué sistema y unidad están sus coordenadas.
- H2: Como topógrafo quiero tener varias superficies en el proyecto, activar la que toca en un toque y definir un desplazamiento vertical sobre ella (por ejemplo +0,30 m para una subbase).
- H3: Como topógrafo quiero replantear a la cota de la superficie un punto o una línea que no tienen cota, y ver el desmonte o el terraplén en la posición del jalón.
- H4: Como topógrafo quiero elegir si un punto con cota propia se replantea contra su cota o contra la superficie.
- H5: Como técnico quiero que cada punto replanteado guarde contra qué cota se replanteó, la cota de la superficie, el desplazamiento y la diferencia.
- H6: Como topógrafo quiero ver la cota de la superficie bajo la punta mientras recolecto, sin replantear.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Superficie**: un modelo del terreno de triángulos con su cota en cada vértice. **Superficie activa**: la que usan el replanteo y el recolector; un proyecto tiene una o ninguna.
- **Cota de la superficie**: la cota de la superficie en una posición Este y Norte, en el sistema de alturas del proyecto.
- **Desplazamiento vertical**: la distancia por encima (positiva) o por debajo (negativa) de la superficie a la que se quiere estar.
- **Cota de la superficie con desplazamiento**: la cota de la superficie más el desplazamiento vertical.
- **Fuera de la superficie**: la posición queda fuera del contorno de la superficie o dentro de un hueco, y por tanto no tiene cota de la superficie.
- **Punta**, **cota de diseño**, **tolerancia vertical**, **desmonte** y **terraplén**: los de S019. **Punto de diseño** y **línea del dibujo**: los de S041.
- **Origen de la cota**: "Cota del punto" (la propia del punto de diseño, la del dibujo, interpolada en una línea, o la escrita a mano) o "Superficie".

### Elegir el fichero e importar (H1)
- RF-1: CUANDO el usuario elija "Importar superficie" en el menú de un proyecto de la lista de proyectos o en el de su lista de objetos, EL SISTEMA abrirá el selector de ficheros del móvil y, con el fichero elegido, la pantalla de importación de la superficie (RNF-T5).
- RF-2: EL SISTEMA reconocerá el fichero por su contenido, no por su extensión, y aceptará LandXML de las versiones 1.0 a 1.2.
- RF-3: SI el fichero no es un LandXML, está vacío o no contiene ninguna superficie definida por triángulos, ENTONCES EL SISTEMA no lo importará e indicará el motivo.
- RF-4: SI el fichero supera los 100 MB, ENTONCES EL SISTEMA no lo importará e indicará el límite.
- RF-5: SI el fichero contiene varias superficies, ENTONCES EL SISTEMA mostrará su nombre, su número de triángulos y su extensión y permitirá elegir una; cada importación trae una sola superficie.
- RF-6: EL SISTEMA propondrá que las coordenadas están en el sistema del proyecto y permitirá elegir «En otro sistema» y entonces uno de los sistemas disponibles de la app; en ese caso las convertirá al del proyecto.
- RF-7: EL SISTEMA propondrá para las coordenadas y las cotas la unidad que declare el fichero, si es metros, pies o pies topográficos de EE. UU., y si no la unidad lineal del proyecto, y permitirá elegir entre las tres (RNF-T9).
- RF-8: MIENTRAS la pantalla de importación esté abierta, EL SISTEMA mostrará la extensión de la superficie (Este y Norte mínimos y máximos en el sistema del proyecto), su cota mínima y máxima y, si el proyecto tiene puntos o un encuadre guardado, la distancia entre su centro y el del proyecto, y lo actualizará al cambiar el sistema o la unidad.
- RF-9: SI esa distancia supera los 100 km, ENTONCES EL SISTEMA mostrará un aviso y pedirá confirmación antes de importar.
- RF-10: CUANDO el usuario pulse "Importar", EL SISTEMA leerá el fichero en segundo plano, con un progreso y un botón de "Cancelar", sin bloquear el resto de la app.
- RF-11: SI el usuario cancela, o no se puede escribir en el almacenamiento, ENTONCES EL SISTEMA no guardará nada de la superficie e indicará que no se ha importado (RNF-T6).
- RF-12: EL SISTEMA respetará los triángulos que el fichero marque como invisibles y los tratará como huecos de la superficie.
- RF-13: CUANDO termine la importación, EL SISTEMA mostrará el resumen: puntos, triángulos y huecos de la superficie, su extensión y sus cotas mínima y máxima.
- RF-14: EL SISTEMA guardará las coordenadas y las cotas de la superficie tal como se convierten, sin redondear, y asociará a cada superficie el nombre del fichero y la fecha y hora de la importación.
- RF-15: SI el proyecto ya tiene una superficie con el mismo nombre de fichero, ENTONCES EL SISTEMA avisará antes de importar y, si el usuario confirma, creará otra superficie aparte.

### Gestionar las superficies y el desplazamiento vertical (H2)
- RF-16: EL SISTEMA listará las superficies del proyecto con su nombre, su fecha, su extensión y sus cotas mínima y máxima, indicará cuál es la activa y permitirá activar otra, o ninguna, con un solo toque.
- RF-17: CUANDO el usuario importe una superficie en un proyecto sin superficie activa, EL SISTEMA la dejará activa; en caso contrario la dejará inactiva.
- RF-18: CUANDO el usuario borre una superficie y confirme, EL SISTEMA la quitará entera, y si era la activa el proyecto quedará sin superficie activa; no modificará ningún punto del proyecto ni lo guardado en los puntos replanteados.
- RF-19: EL SISTEMA guardará un solo desplazamiento vertical por proyecto, con 0 por defecto, positivo hacia arriba, en las unidades lineales del proyecto, que el usuario podrá cambiar en cualquier momento y que se conservará al activar otra superficie, al desactivarla y al borrarla.
- RF-20: SI el desplazamiento vertical que escribe el usuario no es un número, ENTONCES EL SISTEMA no lo aplicará e indicará el motivo.
- RF-21: EL SISTEMA incluirá las superficies, la que está activa y el desplazamiento vertical en las copias de seguridad y en los paquetes de proyecto (S013).

### La cota de la superficie (H3, H4)
- RF-22: EL SISTEMA calculará la cota de la superficie en una posición interpolando linealmente la cota de los vértices del triángulo que la contiene.
- RF-23: SI la punta está fuera de la superficie, ENTONCES EL SISTEMA mostrará "Fuera de la superficie" en lugar del desmonte o el terraplén, no calculará ninguna cota ni diferencia vertical, mantendrá la guía horizontal y permitirá escribir una cota de diseño a mano (S041/RF-24).
- RF-24: DONDE haya una superficie activa y el punto de diseño no tenga cota (ni propia ni escrita a mano), EL SISTEMA tomará como cota de diseño la cota de la superficie con desplazamiento en la posición de la punta, y lo mismo en cada objetivo de una línea del dibujo sin cota.
- RF-25: DONDE haya una superficie activa y el punto de diseño tenga cota propia, EL SISTEMA replanteará contra esa cota y ofrecerá un conmutador "Cota del punto" / "Superficie" para elegir contra cuál; por defecto "Cota del punto", y la superficie nunca sustituye una cota propia sin que el usuario lo elija.
- RF-26: MIENTRAS el origen de la cota sea la superficie, EL SISTEMA mostrará la cota de la superficie, el desplazamiento vertical y el desmonte, el terraplén o "En cota" con la tolerancia vertical, los avisos y los sonidos de S019/RF-2 a RF-16, recalculados con cada época.
- RF-27: CUANDO el usuario desactive o borre la superficie activa con un replanteo abierto cuyo origen de la cota es la superficie, EL SISTEMA pasará a la cota propia del punto si la tiene y, si no, a replantear solo en planta, e indicará el motivo.

### Medir y guardar (H5)
- RF-28: CUANDO termine la medida de un punto replanteado con la superficie como origen de la cota, EL SISTEMA guardará con el punto, sin redondear, el origen de la cota, la superficie (nombre y fecha de importación), la cota de la superficie en la punta, el desplazamiento vertical, la cota de diseño, la diferencia de cota entre lo medido y el diseño (medido menos diseño) y la tolerancia vertical (S019/RF-13).
- RF-29: CUANDO termine la medida de un punto replanteado con la punta fuera de la superficie, EL SISTEMA lo guardará sin cota de la superficie ni diferencia vertical y con el origen de la cota vacío.
- RF-30: EL SISTEMA mostrará en la ficha del punto replanteado lo guardado en RF-28, o "Fuera de la superficie" en el caso de RF-29 (S019/RF-15).
- RF-31: EL SISTEMA añadirá al CSV del informe de replanteo de S019 las columnas origen de la cota, superficie, cota de la superficie y desplazamiento vertical, vacías en los puntos replanteados sin superficie (S019/RF-17 a RF-24, S041/RF-30).
- RF-32: EL SISTEMA conservará los puntos replanteados cuando se borre la superficie, con lo guardado en RF-28.

### Recolectar sobre la superficie (H6)
- RF-33: MIENTRAS el recolector esté abierto y haya una superficie activa, EL SISTEMA mostrará la cota de la superficie con desplazamiento bajo la punta y la diferencia con la cota de la punta como desmonte o terraplén, o "Fuera de la superficie", sin guardar nada de ello en el punto que se mide.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T5: importar con selector de ficheros, validando por contenido; el fichero importado se copia al almacenamiento privado del proyecto.
  - RNF-T6: cada superficie y cada punto replanteado en una sola transacción con durabilidad completa; la medida bruta no se modifica nunca; al actualizar la app los puntos, los dibujos y los desplazamientos existentes se conservan (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el desmonte, el terraplén y «Fuera de la superficie» nunca solo con color; TalkBack lee «terraplén de 0,12 m sobre la superficie» al entrar en cada zona y a petición, sin anunciar cada refresco.
  - RNF-T9: unidades lineales del proyecto en la cota de la superficie, el desplazamiento y las diferencias.
  - RNF-T13: la cota de la superficie bajo la punta a 10 Hz no bloquea el hilo principal más de 16 ms.
  - RNF-T14: sin dependencias nuevas con licencia incompatible con GPL-3.0.
- Constitución nº 2: la cota de la superficie, la cota con desplazamiento y el desmonte o terraplén coinciden con los calculados aparte con un cálculo geométrico independiente a menos de 1 mm, incluso con coordenadas convertidas desde otro sistema con pyproj.
- Activar una superficie de 1.000.000 de triángulos y obtener la primera cota de la superficie en la punta tarda menos de 1 s en el móvil de referencia.
- Importar un LandXML de 100 MB tarda menos de 90 s en el móvil de referencia y no cierra la app por falta de memoria.

## Casos límite
| Caso | RF |
|---|---|
| Fichero que no es LandXML, vacío o sin triángulos | RF-3 |
| Fichero de más de 100 MB | RF-4 |
| Varias superficies en un fichero | RF-5 |
| Coordenadas en pies o en otro sistema | RF-6, RF-7 |
| Superficie lejos del proyecto | RF-8, RF-9 |
| Cancelar o fallo de escritura al importar | RF-11 |
| Triángulos invisibles (huecos) | RF-12, RF-23 |
| Mismo fichero importado dos veces | RF-15 |
| Cambiar de superficie, desactivarla o borrarla | RF-16 a RF-19, RF-27, RF-32 |
| Desplazamiento que no es un número | RF-20 |
| Punta fuera del contorno o en un hueco | RF-23, RF-29, RF-33 |
| Punto con cota propia y superficie activa | RF-25 |
| Punto o línea sin cota con superficie activa | RF-24 |
| Punta justo sobre una arista o un vértice | RF-22 |
| Superficie desactivada con el replanteo abierto | RF-27 |
| FIX perdido, IMU sin estado 30, corte de datos | RF-26 (S019, S041/RF-26) |
| SC geográfico del proyecto | RF-22 (plano local, como S018) |

## Fuera de alcance
- Ráster de cotas (GeoTIFF y ASCII Grid) → S045.
- Dibujar la superficie, sus curvas de nivel o su contorno en el mapa → no previsto.
- Superficies definidas por malla regular (`GridSurface`) o por curvas de nivel sin triángulos en el LandXML → no previsto.
- Alineaciones, perfiles longitudinales y secciones de un LandXML → no previsto.
- Crear o editar superficies en el móvil, o calcular volúmenes entre dos superficies → no previsto.
- Un desplazamiento vertical distinto por superficie o por replanteo → no previsto.
- Guardar la cota de la superficie en los puntos que se miden con el recolector → no previsto.
- Conversión de la cota entre sistemas de alturas (ortométrica ↔ elipsoidal) → no previsto.
- Información geométrica de la superficie (área, pendiente) → S026.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la cota de la superficie, la cota con desplazamiento y el desmonte o terraplén frente a un cálculo geométrico aparte (≤ 1 mm), con puntos dentro de un triángulo, sobre una arista, sobre un vértice, fuera del contorno y en un hueco, y con coordenadas en pies y en otro sistema convertido con pyproj.
- Test de migración de la base de datos con un proyecto de la versión anterior (con dibujos y con puntos replanteados).
- Reproducción sintética de un recorrido que cruza una superficie con un hueco, con su resultado esperado calculado aparte.
- Demo en el emulador con esa reproducción:
  - importar un LandXML con dos superficies y elegir una;
  - activar otra en un toque y cambiar el desplazamiento vertical;
  - replantear un punto sin cota contra la superficie y ver el desmonte y el terraplén;
  - replantear un punto con cota propia con el conmutador en "Cota del punto" y en "Superficie";
  - cruzar el hueco y ver "Fuera de la superficie";
  - la ficha y el informe de replanteo con las columnas nuevas;
  - la cota de la superficie bajo la punta en el recolector.
- Pruebas de campo con el RS3 (del usuario, puerta G2b, junto con S045): una cota replanteada contra una superficie LandXML y comprobada después (≤ 3 cm en cota).

## Dudas abiertas
Resueltas con una suposición. Si alguna no te vale, se cambia la spec antes de planificarla.
- [SUPOSICIÓN: la cota de diseño se toma de la superficie en la posición de la **punta**, no en la del punto de diseño; así el desmonte o terraplén es lo que falta en el sitio donde estás. En un punto fijo, cuando la punta llega a su diana, ambas coinciden (RF-24).]
- [SUPOSICIÓN: la primera superficie importada en un proyecto se activa sola; las siguientes se importan inactivas (RF-17).]
- [SUPOSICIÓN: la cota de la superficie se lee en el sistema de alturas del proyecto y no se convierte, como las cotas del DXF en S040/RF-21.]
- [SUPOSICIÓN: el límite de 1.000.000 de triángulos y de 90 s para 100 MB es un umbral de partida; se ajusta tras medirlo en el móvil de referencia.]
- [SUPOSICIÓN: el conmutador "Cota del punto" / "Superficie" se guarda con el punto replanteado pero no se recuerda de un replanteo al siguiente: cada uno empieza en "Cota del punto" (RF-25).]
- [SUPOSICIÓN: el nivel vertical de S019 y la tolerancia vertical se reutilizan tal cual; no hay una tolerancia propia para la superficie (RF-26).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`, fila S042: pasa a `spec`, y después a `plan`, `tareas`, `en curso` e `implementada`. Se añade a la descripción «varias superficies por proyecto con una activa, cota de diseño de la superficie para puntos y líneas sin cota, y cota bajo la punta en el recolector».
- `docs/glosario.md`: se añaden «Superficie activa» (`ActiveSurface`), «Desplazamiento vertical» (`VerticalOffset`), «Cota de la superficie» (`SurfaceElevation`), «Fuera de la superficie» (`OutsideSurface`) y «Origen de la cota» (`ElevationSource`); la fila «Superficie (MDT/TIN)» (`Surface`) apunta a S042.
- S019/RF-21 y S041/RF-30: el CSV del informe gana las columnas de la superficie (RF-31 de esta spec).
- S018/RF-28 y S019/RF-13: el punto replanteado guarda también el origen de la cota y los datos de la superficie (RF-28 de esta spec).
- La base de datos pasa de la versión 14 a la 15; S017b (fotos) pasará a la 16.
