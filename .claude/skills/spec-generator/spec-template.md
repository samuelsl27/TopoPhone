# Spec NNN — <Nombre de la funcionalidad>

> Hito: <H0…H7> · Depende de: <S00X, S00Y> · Estado: borrador | aprobada | implementada

## Contexto y objetivo
<Qué problema resuelve y por qué merece la pena. Un párrafo.>

## Usuarios / actores
<Quién lo usa: topógrafo en campo, técnico en oficina, receptor RS3, caster NTRIP…>

## Historias de usuario
- H1: Como <rol> quiero <acción> para <beneficio>.

## Requisitos funcionales (criterios de aceptación en EARS)
### <Bloque funcional (H1)>
- RF-1: CUANDO <evento>, EL SISTEMA <respuesta> (salida/resultado esperado).
- RF-2: SI <condición no deseada>, ENTONCES EL SISTEMA <respuesta>.
- RF-3: MIENTRAS <estado>, EL SISTEMA <respuesta>.
- RF-4: DONDE <característica>, EL SISTEMA <respuesta>.
- RF-5: EL SISTEMA <comportamiento permanente>.

## Requisitos no funcionales
- Transversales que aplican: RNF-T<n>, RNF-T<m> (ver `docs/rnf-transversales.md`).
- <Específicos de esta spec, con umbral: tolerancia en mm, latencia en s, frecuencia en Hz…>

## Casos límite
<Vacíos, duplicados, datos corruptos, límites de rango, pérdida de conexión, concurrencia… Cada uno → RF que lo cubre.>

## Fuera de alcance
- <Lo que NO se hace en esta iteración> → S0NN (si se hace más adelante)

## Criterios de finalización
<Ej.: todos los RF con test en verde, el informe de trazabilidad sin RF huérfanos y una demo manual del flujo principal (con el simulador o con el RS3 real).>

## Dudas abiertas
- [NECESITA ACLARACIÓN: <pregunta concreta>]
