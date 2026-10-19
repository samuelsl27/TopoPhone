# Spec 009 — Mapa del proyecto

> Hito: H1 · Depende de: S006, S008 · Estado: aprobada (con suposiciones a revisar; ver "Dudas abiertas")

## Contexto y objetivo
La vista de proyecto (S006, S008) es hoy una lista de tarjetas con números: no hay ningún mapa. En campo, el topógrafo necesita ver dónde está sobre un mapa o una ortofoto, la calidad de su posición y la parcela en la que trabaja, antes de empezar a recolectar puntos (S010).

Esta spec convierte la vista de proyecto en un mapa a pantalla completa:
- con un panel de posición abajo, con las coordenadas del proyecto;
- con los datos del proyecto a un toque;
- con dos mapas base vectoriales (IGN y OpenStreetMap), la ortofoto PNOA y el Catastro superpuesto;
- con el rover dibujado con el color de su solución y su círculo de precisión.

Sin cobertura, el mapa sigue sirviendo: muestra lo que ya se vio y, donde no hay nada, un fondo neutro con el rover encima. La descarga de zonas para trabajar sin conexión llega en S037.

## Usuarios / actores
- Topógrafo en campo con el RS3, con o sin cobertura de datos.
- Receptor RS3, o una reproducción, como fuente de la posición.
- Servicios de mapas del IGN, de OpenStreetMap y del Catastro.

## Historias de usuario
- H1: Como topógrafo quiero abrir un proyecto y ver el mapa con mi posición y sus coordenadas, sin perder los datos del proyecto.
- H2: Como topógrafo quiero elegir entre mapa vectorial y ortofoto y superponer el Catastro para saber en qué parcela estoy.
- H3: Como topógrafo quiero que el mapa siga sirviendo sin cobertura.
- H4: Como topógrafo quiero ver en el mapa dónde está el rover y con qué calidad, y que el mapa lo siga.

## Requisitos funcionales (criterios de aceptación en EARS)
### Vista de proyecto con mapa (H1)
- RF-1: CUANDO el usuario abra un proyecto, EL SISTEMA mostrará el mapa en toda la vista de proyecto, con el nombre del proyecto en la barra superior y un panel de posición en la parte inferior.
- RF-2: MIENTRAS haya posición del receptor o de una reproducción, el panel de posición mostrará las coordenadas y la altura del punto del suelo en el SC del proyecto, con sus avisos y motivos de no disponible (S006/RF-29, RF-30; S008/RF-5, RF-6, RF-8, RF-12).
- RF-3: MIENTRAS haya precisión horizontal (S002/RF-14), el panel de posición la mostrará en las unidades lineales del proyecto.
- RF-4: CUANDO el usuario despliegue el panel de posición, EL SISTEMA mostrará además el factor de escala, la convergencia y el factor combinado (S008/RF-13 a RF-16).
- RF-5: CUANDO el usuario pulse "Datos del proyecto", EL SISTEMA mostrará el SC, la altura, las unidades, el datum de las correcciones con su aviso y si el proyecto tiene puntos (S006/RF-11, RF-28, RF-31; S008/RF-18).

### Mapa base y Catastro (H2)
- RF-6: EL SISTEMA ofrecerá como mapa base el mapa vectorial del IGN, el mapa vectorial de OpenStreetMap, la ortofoto PNOA del IGN o ninguno.
- RF-7: EL SISTEMA ofrecerá superponer al mapa base la cartografía catastral (parcelas y edificios) de la Dirección General del Catastro.
- RF-8: CUANDO el usuario cambie el mapa base o active o desactive el Catastro, EL SISTEMA lo recordará para todos los proyectos, también después de cerrar la app.
- RF-9: EL SISTEMA mostrará en el mapa la atribución de las fuentes que estén a la vista.
- RF-10: EL SISTEMA mostrará en el mapa una barra de escala en las unidades lineales del proyecto.
- RF-11: EL SISTEMA mantendrá el norte arriba, sin que los gestos giren el mapa.
- RF-12: EL SISTEMA permitirá acercar el mapa hasta que la barra de escala represente 1 m o menos.

### Sin cobertura (H3)
- RF-13: EL SISTEMA guardará en el móvil, hasta 200 MB, el mapa base ya visto, y lo mostrará cuando no haya conexión.
- RF-14: SI una zona del mapa no se puede descargar ni está guardada, ENTONCES EL SISTEMA la mostrará con un fondo neutro con cuadrícula, con el rover encima.
- RF-15: MIENTRAS falle la descarga del mapa por falta de conexión, EL SISTEMA mostrará el aviso "Sin conexión: el mapa puede estar incompleto."

### Rover (H4)
- RF-16: MIENTRAS haya posición del receptor o de una reproducción, EL SISTEMA dibujará el rover en el mapa en su posición, con el color de la solución del indicador de estado (S002/RF-16, S003/RF-21).
- RF-17: MIENTRAS haya precisión horizontal, EL SISTEMA dibujará alrededor del rover un círculo a escala con un radio igual a esa precisión.
- RF-18: SI la posición deja de llegar (sin datos o con la conexión perdida, S003/RF-13), ENTONCES EL SISTEMA dejará de dibujar el rover y su círculo.
- RF-19: CUANDO llegue la primera posición tras abrir el proyecto, o el usuario pulse "Centrar en el rover", EL SISTEMA centrará el mapa en el rover, acercándolo a 1:2.000 si estaba más lejos, y lo seguirá mientras se mueva.
- RF-20: CUANDO el usuario mueva el mapa con el dedo, EL SISTEMA dejará de seguir al rover hasta que pulse "Centrar en el rover".
- RF-21: CUANDO el usuario abra un proyecto sin posición del receptor, EL SISTEMA mostrará el último encuadre de ese proyecto, o España entera si es la primera vez que lo abre.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T4: el mapa se descarga por la red con internet, no por la Wi-Fi del receptor.
  - RNF-T7, RNF-T8 (modo sol, 56 dp, TalkBack en los controles del mapa; la solución también se dice con texto en el indicador de estado).
  - RNF-T13: el rover se actualiza a 5 Hz como máximo y el mapa no bloquea el hilo principal más de 16 ms.
  - RNF-T14: atribución visible con el texto que pide cada servicio (IGN: "CC BY 4.0 scne.es"; OpenStreetMap; Dirección General del Catastro); dependencias compatibles con GPL-3.0.
- Constitución nº 4: los mapas base solo se descargan mientras el mapa está a la vista, sin cuentas ni claves, y el usuario puede quitarlos eligiendo "Ninguno".
- El Catastro se pide como una sola imagen de la zona visible cuando el mapa se detiene, sin teselas ni descargas masivas, porque sus condiciones de uso lo prohíben. Por eso tarda un momento en aparecer tras mover el mapa y no se guarda para usarlo sin conexión.
- El APK publicado no crece más de 15 MB con el mapa.
- Constitución nº 2: el rover y el círculo de precisión se dibujan en su posición con un error ≤ 1 cm frente a pyproj (EPSG:3857).
- Se cumplen las condiciones de uso de cada servicio de mapas (identificación de la app y límites de descarga).
- Sin conexión, la vista de proyecto se abre en menos de 1 s en el móvil de referencia: no espera a que fallen las descargas.

## Casos límite
| Caso | RF |
|---|---|
| Posición fuera de España (sin datos del IGN, del PNOA ni del Catastro) | RF-14, RF-16 |
| Posición fuera del ámbito del SC del proyecto | RF-2 (motivo en el panel), RF-16 (el rover se sigue dibujando) |
| Proyecto en ED50 o REGCAN95 | RF-16 (el rover se dibuja en su posición real) |
| Sin frase GST | RF-3, RF-17 (sin precisión ni círculo) |
| Receptor perdido, "Sin datos" o reproducción detenida | RF-18 |
| Sin red, o conectado solo a la Wi-Fi del receptor | RF-13 a RF-15, RNF-T4 |
| Caché llena | RF-13 (se descarta lo visto hace más tiempo) |
| Mapa base "Ninguno" | RF-6, RF-14 |
| Proyecto abierto por primera vez sin receptor | RF-21 |
| Mapa movido a mano mientras llega la posición | RF-20 |

## Fuera de alcance
- Puntos del proyecto en el mapa (capa de objetos) → S010; líneas y polígonos → S025.
- Biblioteca WMS, capas por proyecto, orden y opacidad → S036.
- Descargar una zona para trabajar sin conexión → S037.
- Girar el mapa u orientarlo con la brújula o el IMU → S018.
- Diana de replanteo → S018.
- Posición de la base en el mapa → S015.
- Seleccionar o medir sobre el mapa → S011 y S026.
- Otros mapas base fuera de España → S034.
- Guardar el Catastro para usarlo sin conexión → no previsto: sus condiciones prohíben la descarga masiva.

## Criterios de finalización
- Cada RF tiene un test automático en verde que lo cita, y `rf_report` no muestra RF huérfanos.
- Test de la posición del rover y del radio del círculo en Web Mercator frente a pyproj.
- Demo en el emulador con una reproducción:
  - el rover con su círculo sobre los cuatro mapas base, con y sin Catastro;
  - seguir al rover, moverlo a mano y volver a centrar;
  - el panel plegado y desplegado, y los datos del proyecto;
  - sin red: lo ya visto, el fondo neutro y el aviso.
- Demo de campo con el RS3: queda para el usuario.

## Dudas abiertas
Resueltas con una suposición. Están marcadas para revisarlas; si alguna no te vale, se cambia la spec antes de implementarla.
- [SUPOSICIÓN: el mapa base y el Catastro se recuerdan para toda la app, no por proyecto; las capas por proyecto llegan con S036.]
- [SUPOSICIÓN: por defecto, el mapa vectorial del IGN, sin Catastro.]
- [SUPOSICIÓN: los mapas base integrados cuentan como servicios que configura el usuario (constitución nº 4): los elige en el mapa y puede quitarlos con "Ninguno".]
- [SUPOSICIÓN: el límite de la caché es fijo (200 MB); cuando se llena, se descarta lo visto hace más tiempo.]
- [SUPOSICIÓN: la cuadrícula del fondo neutro sigue la barra de escala, para dar idea de distancias.]
- [SUPOSICIÓN: el panel plegado muestra las coordenadas, la altura, la precisión y los avisos; desplegado, también los factores y la convergencia.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- S006/RF-28 pasa a decir: "CUANDO el usuario abra un proyecto, EL SISTEMA mostrará su nombre en la vista de proyecto, y su sistema de coordenadas, sus unidades y el datum de las correcciones en sus datos (S009/RF-5)."
- S006/RF-31 pasa a decir: "MIENTRAS el proyecto no tenga puntos, los datos del proyecto mostrarán que todavía no hay puntos."
- `docs/hoja-de-ruta.md`:
  - fila S009: "Mapa a pantalla completa en la vista de proyecto con panel de posición y datos del proyecto; mapas base vectoriales del IGN y de OpenStreetMap, ortofoto PNOA y Catastro superpuesto; caché para trabajar sin cobertura; rover con círculo de precisión y color según la solución; barra de escala, atribución, norte arriba";
  - fila S010: se añade "puntos en el mapa (capa de objetos)".
- `docs/glosario.md`: se añaden "Ortofoto", "Barra de escala", "Encuadre", "Tesela", "Círculo de precisión", "Panel de posición" y "Datos del proyecto".
