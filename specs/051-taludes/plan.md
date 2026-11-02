# Plan técnico — Spec 051

> Lee antes: `docs/constitution.md`, `docs/arquitectura.md`, `specs/051-taludes/spec.md`, `specs/050-peraltes-y-transversales/plan.md` (`SectionResolver`, `SectionEval`, los perfiles de terreno `CrossSection` con `SectionKind.GROUND`), `specs/054-replanteo-por-eje/plan.md` (`AxisStakeoutViewModel`, `AxisGuidance`, el objetivo fijo dentro de `StakeoutController`), `specs/042-superficies-landxml/plan.md` (`SurfaceGuide`, `SurfaceHeightSource`) y `specs/018-replanteo-guiado/`, `specs/019-replanteo-cota/` (tolerancias, aviso de llegada, informe). Sin código.

Sin dependencias nuevas, ni Gradle ni Python:
- La intersección con un perfil de terreno poligonal es una resolución lineal por tramos; con una superficie se tantea y se afina por bisección sobre `SurfaceHeightSource`. Alternativa descartada: recorrer los triángulos del TIN (obliga a conocer sus teselas y duplica S042).
- Las referencias independientes las calcula `tools/ejes/generar_taludes.py` con `fractions` (intersección exacta con el perfil poligonal y con un TIN pequeño recorriendo sus triángulos) y las fórmulas de S049 para la planta. Alternativa descartada: reutilizar el solucionador del móvil en los tests (sería una tautología).

## Decisiones que pide esta spec (para tu aprobación)
| # | Decisión | Propuesta | Alternativa |
|---|---|---|---|
| D1 | Dónde se guardan los taludes y la sección manual (RF-1, RF-2, RF-4) | Una tabla `slope_rules` con una fila por lado y rango de PK: las dos relaciones, el código del borde y, si no hay sección de S050, el borde manual (desplazamiento, cota y si es absoluta o relativa). **Room v26** | Dos tablas, una de taludes y otra de bordes manuales |
| D2 | Cómo se escribe la pendiente (RF-1, RNF-T9) | Como **metros horizontales por cada metro vertical**: 1,5 es 3:2. Se muestra «1,5 : 1» con el formato del proyecto | Escribir las dos cifras H y V por separado |
| D3 | Desmonte o terraplén en un PK (RF-5, caso límite) | Lo decide el terreno en el borde: por encima del borde es desmonte (cabeza) y por debajo es terraplén (pie). **En vivo y sin terreno**, lo decide la cota de la punta respecto a la del borde | Que el usuario elija el tipo |
| D4 | Terreno de un perfil de S050 en un PK sin perfil exacto (RF-6) | La cota del terreno en un desplazamiento se interpola con el PK entre los dos perfiles de terreno más próximos que lo rodean (cada uno es lineal entre sus puntos en el desplazamiento); fuera de ellos no hay terreno de perfiles | Exigir un perfil en ese PK |
| D5 | Intersección con la superficie (RF-6, RF-7) | Se avanza desde el borde cada 5 cm hasta 100 m buscando el cambio de signo de «terreno menos talud» y se afina por bisección hasta 1e-6 m; una superficie sin cota en ese tramo corta la búsqueda (sin terreno), como pide el caso límite | Resolverla por triángulos |
| D6 | Dónde se abre «Replantear taludes» (RF-9) | En cada fila de `AlignmentsSheet` y en la ficha del eje, junto a «Replantear eje», con un panel propio (`SlopeStakePanel`) y un `StakeMode.SLOPE` en `ProjectView` | Un tercer modo dentro del panel de «Replantear eje» |
| D7 | Qué guía lleva el replanteo (RF-9, RF-10) | El objetivo de la guía de S018 es **fijo** por PK: el punto de talud teórico o, sin él, el borde. El alzado y la corrección de RF-11 salen de la punta en vivo, sin mover el objetivo | Un objetivo que se mueve con la punta |
| D8 | Desplazamiento de estaca (RF-14) | Positivo hacia fuera, medido sobre la sección desde el punto de talud; por defecto 0; el objetivo de la guía pasa a ser ese punto desplazado | Medido horizontalmente en planta |
| D9 | Tolerancias y alcance de búsqueda | Las de S018 (horizontal) y S019 (vertical); el terreno se busca hasta 100 m del borde; la sección manual necesita un eje (suposiciones de la spec) | Tolerancias propias del talud |
| D10 | Símbolo en el mapa (RF-16) | Los puntos de talud usan una propiedad `slope` del elemento del punto y un icono propio (triángulo) en la capa de puntos | Una capa aparte |

## Estructura de módulos
- `core:model` (`alignment/slope/`)
  - `SlopeSide { LEFT, RIGHT }`, `SlopeKind { CUT, FILL }` (cabeza y pie), `SlopeRule(side, fromChainage, toChainage, cutRatio, fillRatio, edgeCode, manualEdge)` y `ManualEdge(offsetM, heightM, heightKind)`.
  - `SlopeIssue` (pendiente no válida, rangos solapados, PK fuera del eje) para el rechazo (RF-3).
  - `StakeoutRef` gana `slope: SlopeStakeInfo?` (tipo, lado, relación, altura de talud, diferencia de cota y desplazamiento de estaca).
- `core:formats`: `StakeoutReportCsv` con las columnas de talud (RF-16).
- `core:survey` (sin Android ni Hilt)
  - `SlopeValidator` (RF-3).
  - `SlopeEdge`: el borde de explanación de un PK y un lado: el punto de la sección de S050 elegido o el manual (RF-1, RF-2, RF-5).
  - `TerrainAt`: la cota del terreno en un PK y un desplazamiento, de los perfiles de S050 (D4) o de la superficie (`SurfaceHeightSource`), en ese orden (RF-6).
  - `SlopeCatch`: tipo, punto de talud, altura y «sin intersección» (RF-5, RF-7, RF-8; A2 y A3).
  - `SlopeLive`: diferencia de cota con el talud, distancia a moverse, error en PK, tipo en vivo y «encontrado» (RF-11 a RF-13), y el punto de estaca (RF-14).
- `data`
  - Room v26: `slope_rules` y seis columnas anulables en `points`; `SlopeRepository` con altas y bajas en una transacción (RF-4, RNF-T6); `DatabaseSnapshot.TABLES`.
- `app`
  - `ui/slope/`: `SlopesViewModel` y `SlopesScreen` (definir, sección manual, rechazos), `SlopePointViewModel` y su pantalla (punto teórico), `SlopeStakeViewModel`, `SlopeStakePanel` y `SlopeElevationView` (alzado en vivo).
  - `AlignmentsSheet` y `AlignmentFicha`: «Taludes» y «Replantear taludes»; `ProjectViewScreen`: `StakeMode.SLOPE`.
  - Ficha del punto y mapa: la sección de talud y el símbolo (RF-15, RF-16).
- `tools/ejes`: `generar_taludes.py`, `fixtures/ejes/taludes/` y una reproducción que sube por una sección.

## Modelo de datos
### Room v26
```
slope_rules  id TEXT PK, alignment_id TEXT FK(alignments, CASCADE), side TEXT, from_chainage REAL, to_chainage REAL,
             cut_ratio REAL, fill_ratio REAL, edge_code TEXT NULL,
             manual_offset REAL NULL, manual_height REAL NULL, manual_height_kind TEXT NULL, created_at_ms INTEGER
points  + slope_kind TEXT NULL, slope_side TEXT NULL, slope_ratio REAL NULL, slope_height REAL NULL,
          slope_d_height REAL NULL, slope_stake_offset REAL NULL
```
- `AutoMigration(25, 26)` sin especificación: tabla nueva y columnas anulables. Los datos van sin redondear. Borrar el eje borra sus taludes y no toca ningún punto (RF-4).
- Las relaciones se guardan como número (H por 1 V, D2). El punto replanteado usa las columnas `stake_*` de S054 y el `StakeSource.Alignment`; las seis nuevas llevan lo propio del talud (RF-15).

## Algoritmos
### A1. Borde de explanación (RF-1, RF-2, RF-5)
Para un PK y un lado se busca la regla que lo contiene. Con sección en ese PK (`SectionResolver`), el borde es el punto del código elegido o el más exterior del lado (mayor desplazamiento en valor absoluto con el signo del lado); su cota es la de `SectionEval`. Sin sección, el borde manual de la regla: desplazamiento y cota absoluta, o relativa a la rasante en ese PK. Sin regla o sin borde no hay cálculo y se dice el motivo (RF-8).

### A2. Punto de talud con un perfil de terreno (RF-5, RF-6, RF-7, RF-8)
Con `t` la distancia en planta desde el borde hacia fuera sobre la perpendicular al eje, el talud de desmonte es `z = z0 + t/rc` y el de terraplén `z = z0 - t/rf`. La cota del terreno `g(t)` sale de `TerrainAt`. El tipo es desmonte si `g(0) > z0`, terraplén si `g(0) < z0` (D3) y altura cero si son iguales. Entre cada dos puntos del terreno `g` es lineal, así que `g(t) = talud(t)` es una ecuación lineal por tramo: se toma la primera raíz con `t > 0` y `t <= 100`. Sin raíz, «Sin intersección con el terreno».

### A3. Punto de talud con una superficie (RF-6, RF-7; D5)
`f(t) = g(t) - talud(t)` con `g` de `SurfaceHeightSource`. Se avanza `t` de 5 cm en 5 cm hasta 100 m buscando el primer cambio de signo, y se afina por bisección hasta 1e-6 m. Una cota `Outside` o `Loading` corta la búsqueda (sin terreno en ese tramo, caso límite). Este y Norte salen de `AlignmentGeometry.pointAt(pk, d0 +/- t)`.

### A4. Replanteo en vivo (RF-9 a RF-14)
La punta se proyecta sobre el eje (`locate`): `pk_p` y `d_p`. Con la sección del PK de diseño `pk` se toma el borde y el talud del tipo vigente; la diferencia de cota es `dz = h - talud(d_p)` y la distancia a moverse sobre la perpendicular es `r*dz` hacia fuera en desmonte y `-r*dz` en terraplén (positivo hacia fuera). El error en PK es `pk_p - pk` y debe ser menor que la tolerancia horizontal para que se acepte la llegada. «Encontrado» es `|dz| <= tolV` y `|pk_p - pk| <= tolH`. Con estaca `e`, el objetivo es el punto a `e` fuera del punto de talud y se da la altura de talud y la distancia de la estaca al punto.

### A5. Medir y guardar (RF-15)
Como S054/A5: el recolector pide el objetivo de ese momento al terminar la medida y guarda el punto con el tipo, el lado, el PK, el desplazamiento y la cota reales, la relación, la altura y la diferencia de cota con el talud.

## Contratos
- `SlopeCatch.of(edge, rule, terrain: TerrainAt?, geometry, profile): SlopeCatchResult` (`Found(kind, point, heightM)`, `AtEdge`, `NoIntersection`, `NoTerrain`, `Unavailable(reason)`).
- `SlopeLive.of(edge, kind, rule, tip, tolerances): SlopeLiveValue(dzM, moveM, dChainageM, found)`.
- `SlopeRepository.add/delete/rules(alignmentId)`.
- Navegación: `SurveyPage.Slopes(projectId, alignmentId)` y `SurveyPage.SlopePoint(projectId, alignmentId)` (ADR-19); el replanteo vive en `ProjectView`.

Textos nuevos (es y en): Taludes, Replantear taludes, desmonte y terraplén, cabeza y pie, relación H:V, borde de explanación, sección manual, altura de talud, estaca, «Punto de talud encontrado», «Sin intersección con el terreno», dentro y fuera, los motivos de rechazo.

Accesibilidad (RNF-T8): campos y botones de 56 dp; cabeza, pie, dentro y fuera con palabra además de color; el alzado se acompaña de una frase para TalkBack («Cabeza del talud derecho, a 2,40 m hacia fuera»).

## Decisiones técnicas
| Decisión | Por qué | Alternativa descartada |
|---|---|---|
| Una fila por lado y rango, con el borde manual dentro | Un solo alta/baja y un solo índice por eje | Dos tablas |
| Objetivo de la guía fijo y alzado en vivo | Reutiliza `StakeoutController` (diana, zonas, avisos) | Un controlador nuevo |
| Resolver por tramos con perfiles y por tanteo con superficies | Exacto donde se puede y sin conocer las teselas | Triángulos del TIN |
| Tipo de talud por el terreno en el borde | Es lo que dice la definición de cabeza y pie | Elegirlo a mano |

## Estrategia de tests
- **`tools`:** `generar_taludes.py` y su test: los ficheros guardados son los que genera el script (aritmética racional).
- **JVM `core:model`:** `SlopeRule`, `SlopeStakeInfo` y los valores por omisión.
- **JVM `core:survey`:** `SlopeValidatorTest`, `SlopeEdgeTest`, `TerrainAtTest`, `SlopeCatchTest` (desmonte, terraplén, altura cero, sin intersección, sin terreno, perfiles y superficie contra `fixtures/ejes/taludes/` a 1 mm), `SlopeLiveTest` (a 1 mm).
- **`data`** (nombres de test de 60 caracteres o menos): migración 25 a 26, `SlopeRepositoryTest`, `SlopeBackupTest`.
- **`core:formats`:** `StakeoutReportCsvTest` ampliado y dorados regenerados.
- **Robolectric `app`:** `SlopesViewModelTest`, `SlopesScreenTest`, `SlopePointViewModelTest`, `SlopeStakeViewModelTest`, `SlopeStakePanelTest`, `SlopeElevationViewTest`, `SlopeStakeMeasureTest`, `PointDetails` ampliado, `AlignmentsSheetTest` y `AlignmentTouchTest` ampliados.
- **Reproducción:** `SlopeStakeReplayTest`: una reproducción que sube por una sección; la diferencia de cota, la distancia a moverse y el «encontrado» época a época contra el cálculo aparte a 1 mm.
- **Rendimiento:** el alzado y la guía de una época no pasan de 16 ms.

## Cobertura de RF
| RF | Parte del plan | Test previsto |
|---|---|---|
| RF-1, RF-2 | A1, `SlopesViewModel` | `SlopeEdgeTest`, `SlopesViewModelTest`, `SlopesScreenTest` |
| RF-3 | `SlopeValidator` | `SlopeValidatorTest`, `SlopesViewModelTest` |
| RF-4 | Room v26 | `SlopeRepositoryTest`, `SlopeBackupTest` |
| RF-5 | A1, A2, A3 | `SlopeCatchTest`, `SlopePointViewModelTest` |
| RF-6 | `TerrainAt` | `TerrainAtTest`, `SlopeCatchTest` |
| RF-7, RF-8 | A2, A3 | `SlopeCatchTest`, `SlopePointViewModelTest` |
| RF-9 | `SlopeStakeViewModel` | `SlopeStakeViewModelTest` |
| RF-10 | `SlopeElevationView` | `SlopeElevationViewTest`, `SlopeStakePanelTest` |
| RF-11, RF-12, RF-13 | A4 | `SlopeLiveTest`, `SlopeStakeReplayTest`, `SlopeStakePanelTest` |
| RF-14 | A4 | `SlopeLiveTest`, `SlopeStakeViewModelTest` |
| RF-15 | A5, Room v26 | `SlopeStakeMeasureTest`, `PointDetails` ampliado |
| RF-16 | CSV y mapa | `StakeoutReportCsvTest`, `AlignmentSceneTest` |
| RF-17 | `SlopeStakeViewModel` | `SlopeStakeViewModelTest` |
| RF-18 | `SlopeStakeViewModel` | `SlopeStakeViewModelTest` |

## Cambios en documentos anteriores
- `docs/hoja-de-ruta.md`: S051 a «implementada»; la siguiente spec que toque Room pasa a la v27.
- `docs/glosario.md`: «Talud», «Punto de talud», «Cabeza», «Pie», «Borde de explanación», «Altura de talud» y «Desplazamiento de estaca».
- S050: sin cambios; los perfiles de terreno ya se guardan como `CrossSection` de terreno. S019: el informe gana las columnas de talud. S054: sin cambios.
- La cabecera de estado de `specs/051-*/spec.md` no se toca sin que lo pidas.
