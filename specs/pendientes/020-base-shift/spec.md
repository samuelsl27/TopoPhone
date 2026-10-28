# Spec 020 — Base shift (desplazamiento de base)

> Hito: H2 · Depende de: S011, S010 (además, de S008, S015, S016, S018, S046 y S047, de las que reutiliza términos) · Estado: borrador (sin entrevista, 28/10/2026)

## Contexto y objetivo
Cuando la base del rover no está sobre un punto de coordenadas oficiales (por ejemplo, se promedió en SINGLE), todas las posiciones salen desplazadas de forma casi constante respecto a las coordenadas buenas, y las correcciones pueden venir además en un datum distinto al del proyecto (≈ 0,9 m entre ITRF y ETRS89, `docs/arquitectura.md`). La solución rápida es medir un punto de coordenadas conocidas y aplicar la diferencia a todo lo que se mida después. La localización (S039) resuelve lo mismo con más puntos y más parámetros, pero llega mucho más tarde.

Esta spec calcula ese desplazamiento como vector ECEF (conocido − medido), lo aplica a los puntos siguientes y lo guarda en cada uno. Nunca toca la observación bruta ni los puntos ya guardados (constitución nº 1).

## Usuarios / actores
- Topógrafo con una base sobre un punto sin coordenadas fiables, que dispone de un punto conocido en la obra.

## Historias de usuario
- H1: Como topógrafo quiero medir un punto conocido y calcular el desplazamiento de la base.
- H2: Como topógrafo quiero que los puntos siguientes lleven el desplazamiento aplicado y registrado, y poder desactivarlo.
- H3: Como topógrafo quiero que la app me avise cuando el desplazamiento activo ya no vale.

## Requisitos funcionales (criterios de aceptación en EARS)
Términos de esta spec:
- **Punto conocido**: el punto cuyas coordenadas se dan por buenas, en el SC del proyecto (un punto del proyecto o coordenadas escritas a mano, como S011/RF-1).
- **Desplazamiento de base**: vector ECEF (ΔX, ΔY, ΔZ) en metros igual a la posición ECEF del punto conocido menos la del punto medido. Se suma a la posición del punto del suelo antes de pasar al SC del proyecto (orden de `docs/arquitectura.md`: bruto → punta del jalón → desplazamiento → SC → localización).
- **Desplazamiento activo**: el que se aplica a los puntos que se midan; uno o ninguno por proyecto.

### Calcular el desplazamiento (H1)
- RF-1: CUANDO el usuario elija "Base shift" en el menú del proyecto, EL SISTEMA le pedirá elegir el punto conocido y medir su posición con el receptor.
- RF-2: EL SISTEMA medirá el punto como el recolector (S010, promediado de 1 s a 60 min) con los tipos de solución admitidos (S047) y la altura del jalón activo (S046), sin guardarlo como punto del proyecto.
- RF-3: EL SISTEMA convertirá las coordenadas del punto conocido a ECEF en el datum del SC del proyecto, y la posición medida en el datum de las correcciones, sin transformar entre ambos: la diferencia de datum queda absorbida por el desplazamiento.
- RF-4: CUANDO termine la medida, EL SISTEMA mostrará el desplazamiento en ΔX, ΔY, ΔZ, su equivalente en Este, Norte y altura en el SC del proyecto, y su módulo, y pedirá confirmación para activarlo.
- RF-5: SI el módulo del desplazamiento supera 10 m, ENTONCES EL SISTEMA avisará de que es poco probable y exigirá una confirmación aparte; SI supera 1.000 m, ENTONCES no lo permitirá.
- RF-6: SI el punto conocido no tiene altura, ENTONCES EL SISTEMA ofrecerá aplicar solo el desplazamiento horizontal, con la componente vertical a cero, e indicará que no corrige la altura.

### Aplicar y registrar (H2)
- RF-7: MIENTRAS haya un desplazamiento activo, EL SISTEMA lo aplicará a la posición del punto del suelo de los puntos medidos (S010, S017) y de los replanteados (S018), y a las coordenadas, el mapa y el replanteo en vivo.
- RF-8: CUANDO se guarde un punto medido con el desplazamiento activo, EL SISTEMA guardará con él la posición bruta sin desplazar, el vector aplicado y el punto conocido que lo originó.
- RF-9: EL SISTEMA no modificará ningún punto guardado antes de activar, cambiar o desactivar un desplazamiento, ni sus observaciones brutas.
- RF-10: CUANDO el usuario corrija la altura del jalón de un punto medido (S011/RF-14), EL SISTEMA recalculará su punto del suelo con el vector que ese punto guardó, no con el activo.
- RF-11: MIENTRAS haya un desplazamiento activo, EL SISTEMA lo mostrará en el recolector y en el replanteo con el aviso "Base shift activo" y permitirá desactivarlo desde allí.
- RF-12: CUANDO el usuario desactive el desplazamiento, EL SISTEMA dejará de aplicarlo a los puntos siguientes y conservará el cálculo en una lista del proyecto, para reactivarlo o borrarlo.
- RF-13: EL SISTEMA mostrará en la ficha del punto el vector aplicado y el punto conocido, o "Sin base shift", y escribirá ΔX, ΔY y ΔZ en "Todas las columnas" de la exportación CSV (S012/RF-6), vacíos si no se aplicó.
- RF-14: EL SISTEMA aplicará el desplazamiento también a una reproducción o a un receptor virtual (S003, S022), y marcará esos puntos como tales, como en S010/RF-18.

### Avisos (H3)
- RF-15: SI la base cambia mientras hay un desplazamiento activo (S015/RF-17), ENTONCES EL SISTEMA avisará de que el desplazamiento puede haber dejado de valer y ofrecerá desactivarlo o repetirlo.
- RF-16: SI cambia el datum de las correcciones del proyecto con un desplazamiento activo, ENTONCES EL SISTEMA lo desactivará y lo indicará.

## Requisitos no funcionales
- Transversales que aplican:
  - RNF-T6: el punto guarda su vector en la misma transacción; la medida bruta no se modifica.
  - RNF-T7 y RNF-T8: textos en recursos y zonas táctiles de 56 dp.
  - RNF-T9: el vector se guarda en metros; la pantalla usa la unidad lineal del proyecto.
- Constitución nº 2: ECEF, geográficas y coordenadas del SC tras aplicar el desplazamiento coinciden con pyproj a menos de 1 mm; la ida y vuelta de coordenadas a ECEF, a menos de 0,1 mm.
- Aplicar el desplazamiento no añade más de 1 ms por época.

## Casos límite
| Caso | RF |
|---|---|
| Desplazamiento de varios metros por base en SINGLE | RF-4, RF-5 |
| Punto conocido sin altura | RF-6 |
| Corregir la altura del jalón de un punto con desplazamiento | RF-10 |
| Desactivar a mitad de un promediado | RF-9 (la medida en curso termina con el vector con que empezó) |
| Cambio de base o de datum | RF-15, RF-16 |
| Punto medido en reproducción | RF-14 |

## Fuera de alcance
- Calibración con varios puntos, rotación y escala → S039.
- Desplazar puntos ya guardados → no previsto (constitución nº 1; el recálculo masivo sería S039).
- Transformaciones de datum dependientes del tiempo → no previsto.
- Detectar la base con un punto conocido sin medirlo con el receptor → no previsto.

## Criterios de finalización
- Todos los RF con test en verde, informe de trazabilidad sin RF huérfanos, `./gradlew check` y `uv run --project tools pytest tools` en verde.
- Tests del vector y de su aplicación frente a pyproj en ETRS89, REGCAN95 y con correcciones en ITRF.
- Demo en el emulador con una reproducción: medir un punto conocido, activar, medir dos puntos, desactivar y comprobar la ficha y el CSV.
- Demo de campo: queda para el usuario (base en SINGLE y un vértice conocido).

## Dudas abiertas
- [SUPOSICIÓN: el punto conocido se mide aparte con el recolector y no se guarda como punto; no se admite elegir un punto ya medido como «medido».]
- [SUPOSICIÓN: un solo desplazamiento activo por proyecto; no uno por base ni por receptor.]
- [SUPOSICIÓN: aviso a los 10 m y rechazo a 1.000 m (RF-5); son cifras de partida.]
- [SUPOSICIÓN: el desplazamiento se aplica solo a los puntos siguientes y nunca a los ya guardados.]

## Cambios en documentos anteriores (se aplican al aprobar esta spec)
- `docs/hoja-de-ruta.md`: S020 pasa a `spec`; dependencias ampliadas con S015 (cambio de base) y S047 (tipos de solución).
- `docs/glosario.md`: «Base shift» ya existe; añadir «Punto conocido» y «Desplazamiento activo».
- S012: dos columnas nuevas en «Todas las columnas» (ΔX, ΔY, ΔZ); S011: el recálculo del jalón usa el vector del punto; S010/S018: aviso «Base shift activo».
