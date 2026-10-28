# Origen de los ficheros de `fixtures/raster/`

Casi todo lo de esta carpeta lo genera `tools/raster/generar_rasters.py` (y `generar_replay_raster.py` para `replay-rs3.tif`): no se
edita a mano. Dos ficheros **no** salen de un script:

## `mdt05-ign-madrid.tif` y `mdt05-ign-madrid-gdal.csv`

Un trozo real del **MDT05** del IGN (modelo digital del terreno de paso de malla de 5 m): 500 × 500 m en el centro de Madrid, 100 × 100
celdas de 5 m, ETRS89 / UTM 30 (EPSG:25830), enteros de 16 bits, sin compresión, sin valor sin dato.

- Descargado el 28/10/2026 del servicio WCS INSPIRE de modelos digitales del terreno del IGN
  (`https://servicios.idee.es/wcs-inspire/mdt`, cobertura `Elevacion25830_5`), con
  `subset=x(440200,440700)` y `subset=y(4474000,4474500)` en formato `image/tiff`.
- Datos © Instituto Geográfico Nacional de España (IGN/CNIG), licencia CC-BY 4.0 (RNF-T14).
- `mdt05-ign-madrid-gdal.csv` es lo esperado, calculado aparte con GDAL a través de QGIS: el valor de diez celdas y la interpolación
  bilineal (`gdal.ReprojectImage` con `GRA_Bilinear` sobre una celda de salida de 1 m centrada en cada posición) en diez posiciones.
  No lo calcula ningún código de TopoPhone.

Lo usa `RasterElevationTest` (S045/RF-21) para comprobar que el lector y la cota coinciden con GDAL con un fichero que no hemos escrito
nosotros.
