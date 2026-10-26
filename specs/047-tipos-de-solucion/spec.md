# Spec 047 — Tipos de solución admitidos

> Hito: H2 · Depende de: S010, S017, S018 · Estado: aprobada (26/10/2026)

## Contexto y objetivo
El control de precisión de S010 solo sabe una cosa sobre el tipo de solución: "solo FIX" activado o desactivado (S010/RF-13). Con "solo FIX" activado no se puede medir en un sitio con poca visibilidad (bajo arbolado, junto a un muro) aunque la solución sea FLOAT y la precisión servible para un trabajo de cartografía o de inventario. Con "solo FIX" desactivado se mide con cualquier solución, incluida SINGLE, que da errores de metros, y el usuario no ha decidido nada: simplemente no hay control. Un TcpGPS tiene este ajuste con más detalle: "posiciones a grabar".

Esta spec sustituye el interruptor por una elección explícita de los tipos de solución que se aceptan al medir y al replantear (FIX, FLOAT y SINGLE), con FIX como valor por defecto, una confirmación al admitir los de peor precisión y un tratamiento definido de las épocas de distinto tipo durante un promediado. Mantiene la constitución nº 3 (no se recolecta fuera de los umbrales salvo decisión explícita) y la nº 1 (cada punto guarda el tipo con el que se midió).

## Usuarios / actores
- Topógrafo que trabaja en sitios con poca visibilidad y acepta una solución FLOAT, con conocimiento.
- Topógrafo que necesita estar seguro de que solo mide en FIX.

## Historias de usuario
- H1: Como topógrafo quiero elegir qué tipos de solución acepta la app para medir y replantear.
- H2: Como topógrafo quiero que admitir FLOAT o SINGLE sea una decisión consciente, explicada y visible en pantalla.
- H3: Como topógrafo quiero que un promediado no mezcle épocas FIX con FLOAT.
- H4: Como topógrafo quiero saber con qué tipo de solución se midió cada punto.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Tipo de solución**: el de la GGA (S002): FIX, FLOAT o SINGLE. Una época sin posición válida no tiene tipo y nunca se admite.
- **Tipo admitido**: un tipo de solución con el que la app deja medir y replantear.
- **Calidad de los tipos**: FIX mejor que FLOAT y FLOAT mejor que SINGLE.

### Elegir los tipos admitidos (H1, H2)
- RF-1: EL SISTEMA ofrecerá en el control de precisión (S010/RF-13) la elección de los tipos admitidos entre FIX, FLOAT y SINGLE, y sustituirá con ella la condición "solo FIX".
- RF-2: EL SISTEMA tendrá admitido solo FIX por defecto.
- RF-3: SI el usuario intenta desmarcar el último tipo admitido, ENTONCES EL SISTEMA no lo permitirá e indicará que debe haber uno.
- RF-4: CUANDO el usuario marque FLOAT o SINGLE, EL SISTEMA pedirá confirmación explicando la precisión esperada ("submétrica" para FLOAT, "de varios metros" para SINGLE) y no lo admitirá sin ella.
- RF-5: EL SISTEMA recordará los tipos admitidos para todos los proyectos y receptores, entre sesiones.

### Medir (H1, H3)
- RF-6: MIENTRAS el tipo de solución de la época no sea admitido, EL SISTEMA no permitirá medir sin confirmación e indicará el tipo actual y los admitidos (S010/RF-14).
- RF-7: CUANDO el usuario confirme guardar una medida cuyo tipo no era admitido, EL SISTEMA la guardará marcada como fuera de tolerancia, con "tipo de solución" entre las condiciones incumplidas (S010/RF-15).
- RF-8: CUANDO el usuario pulse "Medir" en modo instantáneo, EL SISTEMA medirá con la primera época completa de un tipo admitido (S010/RF-8).
- RF-9: CUANDO durante un promediado llegue una época de un tipo admitido mejor que el de las ya reunidas, EL SISTEMA descartará las ya reunidas, seguirá solo con las de ese tipo y las contará como descartadas por tipo.
- RF-10: MIENTRAS dure un promediado, EL SISTEMA descartará y contará las épocas de un tipo peor que el mejor visto en esa medida, y avisará "Solución degradada" con un sonido y una vibración (S010/RF-10).
- RF-11: MIENTRAS el modo automático (S017) esté activo, EL SISTEMA omitirá los puntos cuya época no sea de un tipo admitido, como los que no cumplen el control de precisión (S017/RF-6).

### Replantear (H1)
- RF-12: SI la solución no es de un tipo admitido durante el replanteo, ENTONCES EL SISTEMA apagará la guía y avisará, como en S018/RF-22.
- RF-13: DONDE la solución sea de un tipo admitido, EL SISTEMA mantendrá la guía con cualquiera de ellos y mostrará el tipo de forma destacada (S018/RF-23).

### Visibilidad y registro (H2, H4)
- RF-14: MIENTRAS FLOAT o SINGLE estén admitidos, EL SISTEMA mostrará en el recolector y en el replanteo, junto al tipo de la solución, un indicador de que no solo se admite FIX, con icono y texto (RNF-T8).
- RF-15: EL SISTEMA guardará con cada punto medido el tipo de solución con el que se midió, los tipos admitidos en ese momento y el número de épocas descartadas por tipo (S010/RF-18).
- RF-16: EL SISTEMA mostrará en la ficha del punto el tipo de solución y los tipos admitidos al medirlo.

### Datos y actualización (H1)
- RF-17: CUANDO la app se actualice a esta versión, EL SISTEMA admitirá solo FIX si "solo FIX" estaba activado, y FIX, FLOAT y SINGLE si estaba desactivado, sin mostrar ninguna confirmación y sin modificar ningún punto.
- RF-18: EL SISTEMA tratará como SINGLE una época con calidad GGA de DGPS.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: el cambio de tipos admitidos se guarda de forma atómica y los puntos existentes se conservan al actualizar (migración probada).
  - RNF-T7 y RNF-T8: textos en recursos; zonas táctiles de 56 dp; el tipo de solución con color y texto, nunca solo color.
  - RNF-T3: el aviso de solución degradada se da en menos de 3 s.
- Cambiar un tipo admitido se refleja en el recolector y en el replanteo en menos de 200 ms.
- Ninguna época de un tipo no admitido entra en el promedio de un punto: comprobado con una reproducción con épocas FIX, FLOAT y SINGLE mezcladas.

## Casos límite
| Caso | RF |
|---|---|
| Desmarcar el último tipo | RF-3 |
| Solo FLOAT admitido y llega FIX | RF-6 (FIX no admitido) |
| Épocas FLOAT acumuladas y después aparece FIX | RF-9 |
| FIX perdido a mitad de un promediado con FLOAT admitido | RF-10 |
| Época sin posición válida | Términos (nunca se admite) |
| Calidad DGPS en la GGA | RF-18 |
| Modo automático con FLOAT admitido | RF-11 |
| Actualizar con "solo FIX" apagado | RF-17 |
| Guardar un punto de tipo no admitido | RF-7 |
| Replanteo con la solución cayendo a FLOAT | RF-12 |

## Fuera de alcance
- PDOP máximo, mínimo de satélites y edad máxima de las correcciones como condiciones del control → no previsto (descartado en la entrevista).
- Máscara de elevación y máscara SNR → S043 (se configuran en el receptor).
- Tipos de solución por proyecto → no previsto.
- Aviso de calidad de un punto en el mapa según su tipo → no previsto.
- El GNSS del propio móvil como fuente → S022 (nunca se presenta como FIX).

## Criterios de finalización
- Todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Una reproducción con FIX, FLOAT y SINGLE alternados da, con FIX admitido, un punto con solo épocas FIX, y con FIX y FLOAT admitidos, otro con las de FIX y las FLOAT descartadas o a la inversa según RF-9.
- Demo en el emulador: marcar FLOAT con su confirmación, medir con FLOAT admitido, ver el indicador y la ficha, y ver la guía de replanteo apagarse con SINGLE.
- Demo de campo: queda para el usuario.

## Dudas abiertas
- [SUPOSICIÓN: una época con calidad DGPS se trata como SINGLE (RF-18), porque el receptor no la da en el flujo habitual con NTRIP.]
- [SUPOSICIÓN: el texto de la confirmación (RF-4) usa "submétrica" y "de varios metros" sin cifras, porque la precisión depende de cada receptor.]
- [SUPOSICIÓN: la spec se llama "tipos de solución" y no "filtros de calidad" porque en la entrevista solo se eligió esta condición.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: nueva fila S047 en H2, en estado `spec`.
- `docs/glosario.md`: se añaden "Tipo de solución admitido" (`AdmittedSolution`) y "Solución degradada" (`DegradedSolution`).
- S010/RF-10, RF-13: "solo FIX" pasa a ser la elección de tipos admitidos; S017/RF-6 y S018/RF-22 y RF-23 se leen con ellos.
