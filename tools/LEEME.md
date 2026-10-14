# Herramientas de desarrollo (no van en el APK)

| Carpeta | Para qué sirve | Spec |
|---|---|---|
| `geodesia/` | Proyecto Python con `uv` y pyproj/PROJ en versiones fijas. Genera los vectores de referencia (`fixtures/geodesia/`) y el catálogo de SC desde EPSG, y descarga las rejillas del CDN de PROJ con verificación SHA-256 | S002, S006, S032 |
| `trazabilidad/` | `rf_report.py`: cruza los RF de cada `spec.md` con las citas `S###/RF-n` de los tests y hace fallar el CI si algún RF queda sin test | S001 |
| `validacion/` | Comparación de resultados de campo con Emlid Flow y con las reseñas; comprobación de las exportaciones con GDAL/ogr | S011+ |

Las dependencias se fijan con `uv.lock`. Los scripts se ejecutan con `uv run`, nunca con el Python del sistema.
