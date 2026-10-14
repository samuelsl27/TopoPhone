# Plan técnico — Spec NNN

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/NNN-*/spec.md`. Sin código.

## Estructura de módulos
- `core:<módulo>` → <responsabilidad> (RF-1, RF-2)
- `app` / `data` / `receiver` → <responsabilidad> (RF-3)

## Modelo de datos
<Entidades, campos y unidades. Ejemplo concreto (JSON o tabla). Si toca Room: migración y versión del esquema.>

## Algoritmos
<Pseudocódigo numerado de los cálculos no triviales, con su referencia (EPSG GN 7-2, ETC_rev_3, NTRIP 2.0…). (RF-n)>

## Contratos
<Interfaces entre módulos, estados (máquina de estados), mensajes de error y textos que ve el usuario.>

## Decisiones técnicas
| Decisión | Motivo | Alternativa descartada |
|---|---|---|
| <…> | <constitución nº X / RNF-Tn / medida> | <…> |

## Estrategia de tests
- Unitarios JVM: <qué se prueba, con qué fixtures o vectores de referencia, tolerancias>.
- Robolectric / instrumentados: <flujos>.
- Replay NMEA: <escenarios de `fixtures/nmea/`>.
- Manual o de campo: <qué parte del protocolo de `docs/validacion-campo/protocolo.md`>.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1 | <…> | <…> |
