# Constitución de TopoPhone

Principios innegociables. Toda spec, plan y tarea debe cumplirlos.

1. **El dato de campo es sagrado**: cada medida se guarda al instante y de forma atómica, con su observación bruta y sus metadatos de calidad; nunca se pierde ni se sobrescribe sin rastro y siempre puede exportarse o respaldarse.
2. **Precisión demostrada**: ningún cálculo geodésico o topográfico se acepta sin tests contra referencias independientes (EPSG, pyproj, IGN); tolerancia ≤ 1 mm, ida y vuelta ≤ 0,1 mm.
3. **Calidad antes que rapidez**: no se recolecta ni replantea fuera de los umbrales exigidos (FIX, precisión, inclinación válida) salvo decisión explícita del usuario; el estado de la solución está siempre visible.
4. **Local y sin cuentas**: ninguna función exige registro ni nube; la red solo se usa para servicios que el usuario configura (NTRIP, WMS, rejillas).
5. **Interfaces documentadas**: el núcleo usa solo interfaces públicas del receptor (NMEA, SPP, TCP, RTCM, NTRIP); lo no documentado vive en un módulo experimental aislado y desactivado por defecto.
6. **Núcleo puro y tests primero**: la lógica (geodesia, GNSS, NTRIP, topografía, formatos) es Kotlin sin Android; cada tarea empieza por un test que cita su `S###/RF-n`.
7. **Hecha para el campo**: legible a pleno sol, usable con guantes y una mano, operativa sin cobertura; código en inglés, interfaz y documentación en español.
8. **La spec manda**: todo cambio nace en `specs/`; una tarea cada vez, y ningún RF se da por cumplido sin un test que lo verifique.
