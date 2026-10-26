# Spec 046 — Antena y jalón

> Hito: H2 · Depende de: S016, S011 · Estado: aprobada (26/10/2026)

## Contexto y objetivo
Hoy la app trata la altura de la antena con un solo dato suelto: la altura del jalón (1,800 m por defecto), a la que suma un 0,134 m fijo que solo vale para el RS3 (S002/RF-29, RF-32). Eso falla en tres situaciones habituales de campo: se usa otro receptor o antena con otro desplazamiento, se alternan dos jalones (uno de 1,8 m y otro de 2,0 m, o uno corto para zanjas) y hay que reescribir la altura cada vez, o se mide la altura inclinada hasta el borde del receptor porque no se llega a la base con la cinta. Un error de 134 mm en la altura de antena es un error de 134 mm en la cota de todos los puntos (constitución nº 1 y nº 2).

Esta spec da a la altura de antena una configuración propia: un catálogo de modelos Reach con su desplazamiento al centro de fase, perfiles de antena propios, una lista de jalones con nombre, la medida vertical o inclinada y un selector rápido en el recolector y en el replanteo. No cambia cómo se calcula la punta del jalón con la inclinación (S016): solo de dónde salen la altura del jalón y el desplazamiento al ARP.

## Usuarios / actores
- Topógrafo en campo que alterna jalones o receptores y quiere cambiar de uno a otro con un toque.
- Topógrafo que mide la altura inclinada con una cinta hasta el borde del receptor.
- Receptor Reach (RS3 y los demás modelos del catálogo).

## Historias de usuario
- H1: Como topógrafo quiero elegir el modelo de mi receptor, o crear un perfil propio, para que la altura de antena use su desplazamiento al centro de fase y no un valor fijo.
- H2: Como topógrafo quiero guardar varios jalones con nombre y cambiar de uno a otro en el recolector y en el replanteo sin reescribir la altura.
- H3: Como topógrafo quiero medir la altura inclinada hasta el borde del receptor y que la app calcule la vertical.
- H4: Como topógrafo quiero ver en cada punto qué jalón y qué antena se usaron, y saber que un cambio de jalón no altera los puntos ya guardados.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Perfil de antena**: un modelo de receptor o de antena con su desplazamiento al centro de fase y, si se conoce, el radio de su base.
- **Desplazamiento al centro de fase**: distancia vertical del ARP (el punto de referencia de la antena, en la base del receptor) al centro de fase de la antena (APC); 0,134 m en el RS3 (glosario: ARP, APC).
- **Jalón guardado**: un jalón con nombre y su altura, del regatón a la base del receptor.
- **Jalón activo**: el que usan el recolector, el replanteo y las coordenadas para un receptor.
- **Altura vertical del jalón**: del regatón a la base del receptor, medida en vertical; la que se usa en todos los cálculos.
- **Altura inclinada**: la que se mide con la cinta del regatón al borde de la base del receptor, sin estar el jalón en vertical.
- **Altura de antena**: altura vertical del jalón más el desplazamiento al centro de fase (glosario).

### Catálogo y perfiles de antena (H1)
- RF-1: EL SISTEMA ofrecerá un catálogo de modelos Reach con su desplazamiento al centro de fase, que el usuario no podrá editar ni borrar, con estos valores de la documentación de Emlid:

  | Modelo | Desplazamiento | Fuente |
  |---|---|---|
  | Reach RS3 | 0,134 m | ETC_rev_3 (la documentación da el centro de fase en 134,9 mm en L1 y 137,1 mm en L2) |
  | Reach RS2 y RS2+ | 0,134 m | Emlid (centro de fase: 134,9 mm en L1 y 137,1 mm en L2) |
  | Reach RS y RS+ | 0,065 m | Emlid (distancia del ARP al centro de la antena) |
  | Reach RS4 y RS4 Pro | 0,095 m | Emlid (centro de fase: 95 mm en L1 y 77 mm en L2) |
  | Reach RX | 0,1471 m | Emlid (centro de fase: 147,1 mm en L1 y 149,0 mm en L2) |
- RF-2: EL SISTEMA incluirá el RS3 con un desplazamiento de 0,134 m, el mismo que usa S002/RF-32.
- RF-3: CUANDO el usuario cree un perfil de antena propio, EL SISTEMA le pedirá un nombre único de hasta 40 caracteres, un desplazamiento al centro de fase entre 0 y 1 m y, opcionalmente, un radio de la base entre 0 y 0,3 m, con punto o coma decimal.
- RF-4: SI el nombre, el desplazamiento o el radio de un perfil propio no son válidos, ENTONCES EL SISTEMA no lo guardará e indicará el motivo y el rango.
- RF-5: EL SISTEMA permitirá editar y borrar los perfiles propios y crear uno a partir de un modelo del catálogo, con sus valores como punto de partida.
- RF-6: SI el usuario intenta borrar un perfil propio que usa algún receptor, ENTONCES EL SISTEMA no lo borrará y dirá qué receptores lo usan.
- RF-7: EL SISTEMA recordará un perfil de antena para cada receptor y usará el RS3 como perfil de un receptor que no tenga ninguno.
- RF-8: CUANDO el usuario cambie el perfil de un receptor, EL SISTEMA mostrará la altura de antena resultante con el jalón activo, en metros con 3 decimales.

### Jalones guardados (H2)
- RF-9: EL SISTEMA mantendrá una lista de jalones guardados, con nombre único de hasta 30 caracteres y una altura entre 0 y 10 m, y con "Jalón estándar" de 1,800 m como jalón de partida.
- RF-10: CUANDO el usuario añada o edite un jalón, EL SISTEMA aceptará la altura con punto o coma decimal y no la guardará si no es un número entre 0 y 10 m (S002/RF-31).
- RF-11: EL SISTEMA permitirá borrar un jalón guardado salvo el último que quede.
- RF-12: SI el usuario intenta borrar el jalón activo de algún receptor, ENTONCES EL SISTEMA pedirá elegir antes otro jalón activo para ese receptor.
- RF-13: EL SISTEMA recordará un jalón activo para cada receptor, entre sesiones.
- RF-14: EL SISTEMA ofrecerá un selector del jalón activo en el recolector, en el replanteo y en el menú del receptor, que cambia de jalón con dos toques como máximo.
- RF-15: MIENTRAS el recolector o el replanteo estén abiertos, EL SISTEMA mostrará el nombre del jalón activo, su altura vertical y la altura de antena resultante.

### Medida vertical e inclinada (H3)
- RF-16: EL SISTEMA permitirá elegir para cada jalón guardado el tipo de medida, "Vertical" o "Inclinada", con "Vertical" por defecto.
- RF-17: DONDE el tipo de medida sea "Inclinada" y el perfil de antena del receptor tenga radio, EL SISTEMA calculará la altura vertical del jalón desde la altura inclinada y ese radio y la mostrará con 3 decimales.
- RF-18: SI el perfil de antena del receptor no tiene radio, o la altura inclinada es menor que el radio, ENTONCES EL SISTEMA no la aceptará e indicará que hay que usar un perfil con radio o que la medida no es posible.
- RF-19: CUANDO cambie el perfil de antena de un receptor, EL SISTEMA recalculará la altura vertical de los jalones inclinados que use ese receptor y avisará de que cambia.

### Cálculo y uso (H1, H2)
- RF-20: EL SISTEMA usará el desplazamiento al centro de fase del perfil del receptor, y no un valor fijo, en la altura de antena y en el cálculo de la punta del jalón (S002/RF-32).
- RF-21: EL SISTEMA dará con el perfil RS3 y el jalón de 1,800 m el mismo punto del suelo que antes de esta spec, con diferencias menores de 0,1 mm.
- RF-22: MIENTRAS el modo de inclinación del receptor sea "Compensada por el receptor" (S016/RF-1), EL SISTEMA no restará ninguna altura de antena a la posición del receptor, mostrará la altura de antena de la app solo como dato y avisará de que la altura real es la configurada en el receptor.
- RF-23: EL SISTEMA aplicará el mismo jalón activo en el recolector, en el replanteo (S018/RF-26) y en el panel de posición.

### Cambios y registro (H4)
- RF-24: EL SISTEMA guardará con cada punto medido el nombre y la altura vertical del jalón, el tipo de medida y la altura introducida, el perfil de antena con su desplazamiento y la altura de antena (S010/RF-17).
- RF-25: CUANDO el usuario cambie el jalón activo o el perfil de antena, EL SISTEMA aplicará el cambio solo a los puntos que se midan después y no modificará ningún punto guardado.
- RF-26: CUANDO el usuario cambie el jalón activo o el perfil de antena con puntos ya medidos en la sesión, EL SISTEMA avisará de que el cambio no afecta a los puntos ya guardados y de que se corrigen con S011/RF-14.
- RF-27: MIENTRAS haya un promediado o un modo automático en curso, EL SISTEMA no permitirá cambiar el jalón activo ni el perfil de antena de ese receptor e indicará que hay que terminar la medida.
- RF-28: MIENTRAS el recolector o el replanteo estén abiertos sin ningún jalón ni perfil válido, EL SISTEMA no permitirá medir e indicará cómo configurarlos.

### Datos y actualización (H4)
- RF-29: CUANDO la app se actualice a esta versión, EL SISTEMA convertirá la altura de jalón que había guardada en el jalón activo de cada receptor y asignará el perfil RS3 a los receptores conocidos, sin modificar ningún punto.
- RF-30: EL SISTEMA incluirá los jalones, los perfiles propios y los activos de cada receptor en la copia completa (S013/RF-2) y no en los paquetes de un proyecto.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: cada alta, edición o borrado de un perfil o jalón se guarda en una sola transacción con durabilidad completa, y al actualizar se conservan los puntos existentes (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el selector de jalón legible a pleno sol; TalkBack lee el jalón activo y la altura de antena.
  - RNF-T9: las alturas se muestran con la unidad lineal del proyecto y se guardan en metros.
  - RNF-T13: cambiar de jalón activo actualiza la altura de antena en pantalla en menos de 200 ms.
- Constitución nº 2: la punta del jalón con cualquier perfil coincide con el cálculo independiente (pyproj sobre la posición de la antena menos el desplazamiento) a menos de 1 mm.
- La altura vertical calculada desde la inclinada y el radio coincide con el cálculo √(inclinada² − radio²) a menos de 0,1 mm.

## Casos límite
| Caso | RF |
|---|---|
| Jalón de 0 m (receptor en el suelo) | RF-10 |
| Jalón de 10,001 m o con letras | RF-10 |
| Dos jalones o dos perfiles con el mismo nombre | RF-3, RF-9 |
| Borrar el último jalón | RF-11 |
| Borrar el jalón activo de un receptor | RF-12 |
| Borrar un perfil usado por un receptor | RF-6 |
| Altura inclinada menor que el radio | RF-18 |
| Cambiar el perfil con jalones inclinados guardados | RF-19 |
| Cambiar de jalón en mitad de un promediado | RF-27 |
| Cambiar de jalón con puntos medidos en la sesión | RF-25, RF-26 |
| Receptor con modo "Compensada por el receptor" | RF-22 |
| Receptor nuevo sin perfil | RF-7 |
| Actualizar la app con un jalón ya guardado | RF-29 |
| Restaurar una copia en otro móvil | RF-30 |

## Fuera de alcance
- Calibración del centro de fase de la antena (APC) y variaciones por frecuencia → no previsto.
- Radio de la base de los modelos del catálogo (Emlid no lo publica; se escribe en un perfil propio) → no previsto.
- Escribir la altura de antena en el receptor → S044 (módulo experimental).
- Comprobar la altura configurada en el receptor → S043.
- Recalcular los puntos ya guardados al cambiar de jalón → no previsto: se hace con S011/RF-14.
- Antenas externas con ficheros de calibración (ANTEX) → no previsto.
- Medida de la altura con un distanciómetro o sensor → no previsto.
- Jalones o perfiles por proyecto → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` en verde y `uv run --project tools pytest tools` en verde.
- Con el RS3 y el jalón de 1,800 m, una reproducción grabada da el mismo punto que antes de la spec.
- Demo en el emulador con una reproducción:
  - cambiar de jalón en el recolector y ver la altura de antena;
  - crear un jalón inclinado y ver la altura vertical;
  - crear un perfil propio y verlo en el cálculo;
  - ver el jalón y el perfil guardados en la ficha de un punto.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: los desplazamientos del catálogo salen de la documentación de Emlid (RS2, RS2+, RS3 en 0,134 m aunque su centro de fase en L1 es 134,9 mm; RS4 y RX con su centro de fase en L1); la diferencia de 0,9 mm del RS3 se comprueba en la puerta G0.]
- [SUPOSICIÓN: el Reach M2 y las antenas externas no están en el catálogo (su antena no es del receptor): se usa un perfil propio.]
- [SUPOSICIÓN: un receptor nuevo usa el perfil RS3 (RF-7), igual que hasta ahora.]
- [SUPOSICIÓN: los jalones y los perfiles son de la app y no de un proyecto (RF-30).]
- [SUPOSICIÓN: la lista de jalones es una sola para todos los receptores; cada receptor guarda cuál es el activo (RF-13).]
- [SUPOSICIÓN: el tipo de medida es una propiedad de cada jalón (RF-16).]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S046 en H2, en estado `spec`.
- `docs/glosario.md`: "Altura de antena" pasa a "altura del jalón + desplazamiento al centro de fase (APC)" (RS3: 0,134 m) y se añaden "Perfil de antena" (`AntennaProfile`), "Jalón guardado" (`SavedPole`), "Jalón activo" (`ActivePole`), "Altura inclinada" (`slantHeight`) y "Altura vertical del jalón" (`verticalPoleHeight`).
- S002/RF-29 a RF-32 y S010/RF-5 y RF-17: la altura del jalón pasa a ser el jalón activo, y el 0,134 m, el desplazamiento del perfil.
