# Herramientas de desarrollo (no van en el APK)

| Carpeta | Para qué sirve | Spec |
|---|---|---|
| `geodesia/` | Scripts con pyproj/PROJ en versiones fijas (dentro del proyecto uv de `tools/`). `rejillas.py` descarga y verifica (SHA-256) las rejillas del CDN de PROJ que van en la app; `generar_vectores.py` genera los vectores de referencia (`fixtures/geodesia/`). Más adelante, el catálogo de SC desde EPSG | S002, S008, S033 |
| `nmea/` | `generar_sintetico.py`: sesión NMEA sintética (basura binaria, frases cortadas y con suma errónea) y lo que la app debe mostrar en cada época. `generar_rs3_completo.py`: todas las frases del RS3, con medianoche y un hueco, y sus épocas esperadas. Coordenadas con pyproj (`fixtures/nmea/sintetico/`) | S002, S003 |
| `trazabilidad/` | `rf_report.py`: cruza los RF de cada `spec.md` con las citas `S###/RF-n` de los tests y hace fallar el CI si algún RF queda sin test | S001 |
| `validacion/` | `g0_sesion.py`: pyproj aplicado a las GGA de una sesión grabada en campo (lo que debe mostrar TopoPhone, el CSV para `RecordedSessionsTest` y la media para comparar con la reseña; ver `docs/validacion-campo/g0-procedimiento.md`). Más adelante, comparación con Emlid Flow y comprobación de las exportaciones con GDAL/ogr | S002, S012+ |

`tools/` es un único proyecto uv (`pyproject.toml` + `uv.lock`); cada spec añade sus dependencias (p. ej. pyproj en S002).
Los scripts se ejecutan con `uv run --project tools …` desde la raíz, nunca con el Python del sistema. Tests: `uv run --project tools pytest tools`.

Convención de citas en tests de Python: docstring que empieza por `S###/RF-n:` o nombre `test_s###_rfn_…`. Los datos de prueba no deben contener citas literales falsas (constrúyelas en tiempo de ejecución), porque `rf_report` también analiza estos ficheros.
