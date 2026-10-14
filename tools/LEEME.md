# Herramientas de desarrollo (no van en el APK)

| Carpeta | Para qué sirve | Spec |
|---|---|---|
| `geodesia/` | Scripts con pyproj/PROJ en versiones fijas (dentro del proyecto uv de `tools/`). Genera los vectores de referencia (`fixtures/geodesia/`) y el catálogo de SC desde EPSG, y descarga las rejillas del CDN de PROJ con verificación SHA-256 | S002, S006, S032 |
| `trazabilidad/` | `rf_report.py`: cruza los RF de cada `spec.md` con las citas `S###/RF-n` de los tests y hace fallar el CI si algún RF queda sin test | S001 |
| `validacion/` | Comparación de resultados de campo con Emlid Flow y con las reseñas; comprobación de las exportaciones con GDAL/ogr | S011+ |

`tools/` es un único proyecto uv (`pyproject.toml` + `uv.lock`); cada spec añade sus dependencias (p. ej. pyproj en S002).
Los scripts se ejecutan con `uv run --project tools …` desde la raíz, nunca con el Python del sistema. Tests: `uv run --project tools pytest tools`.

Convención de citas en tests de Python: docstring que empieza por `S###/RF-n:` o nombre `test_s###_rfn_…`. Los datos de prueba no deben contener citas literales falsas (constrúyelas en tiempo de ejecución), porque `rf_report` también analiza estos ficheros.
