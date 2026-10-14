# TopoPhone

App Android de topografía GNSS para receptores **Emlid Reach**, con prioridad para el **Reach RS3**.
Cubre el mismo terreno que Emlid Flow: proyectos, sistemas de coordenadas, recolección y replanteo de puntos y líneas, códigos, capas, COGO, localización y CAD.
Es **100 % local**, sin cuentas ni nube, y está pensada primero para España (ETRS89 / REGCAN95, UTM, EGM08-REDNAP).

> Proyecto independiente, sin relación con Emlid Tech Kft. "Emlid", "Reach" y "Emlid Flow" son marcas de sus propietarios.

## Cómo se conecta al receptor
- **Posición**: NMEA por Bluetooth SPP o por TCP (Wi-Fi), incluida la inclinación del RS3 (mensaje ETC).
- **Correcciones**: cliente NTRIP propio; el RTCM3 se envía al receptor por el mismo enlace.
- **Configuración del receptor** (modo base, LoRa, logs, firmware): se hace en Emlid Flow o en Reach Panel, con asistentes en la app. Hay además un módulo experimental opcional.

## Metodología: Spec-Driven Development
El proyecto sigue el método de [mouredev/hello-sdd](https://github.com/mouredev/hello-sdd): constitución → spec (EARS) → clarificación → plan → tareas → implementación tarea a tarea con tests primero → validación. Cada cambio empieza por la spec.

| Documento | Qué contiene |
|---|---|
| [docs/constitution.md](docs/constitution.md) | Principios innegociables |
| [AGENTS.md](AGENTS.md) | Instrucciones para agentes de IA (Claude Code, opencode…) |
| [docs/hoja-de-ruta.md](docs/hoja-de-ruta.md) | Las 42 specs, hitos H0–H7 y puertas de validación |
| [docs/rnf-transversales.md](docs/rnf-transversales.md) | Requisitos no funcionales comunes (RNF-T1…T14) |
| [docs/arquitectura.md](docs/arquitectura.md) | Módulos, flujo de datos y ADR |
| [docs/inventario-funcional.md](docs/inventario-funcional.md) | Funciones de Emlid Flow y del RS3, y cómo las cubre TopoPhone |
| [docs/glosario.md](docs/glosario.md) | Términos español ↔ código |
| [docs/prompts-sdd.md](docs/prompts-sdd.md) | Prompts para cada fase |
| [docs/validacion-campo/protocolo.md](docs/validacion-campo/protocolo.md) | Ensayos de campo y actas |
| [specs/](specs/) | Una carpeta por funcionalidad: `spec.md`, `plan.md`, `tasks.md` |

## Requisitos de desarrollo
- Android Studio (actual) con Android SDK; JDK 21, que Gradle gestiona con toolchains.
- Python con [uv](https://docs.astral.sh/uv/) para `tools/`.
- Git LFS.
- Para probar en campo: Reach RS3 con firmware ≥ 32 (recomendado 33.x) y acceso NTRIP.
