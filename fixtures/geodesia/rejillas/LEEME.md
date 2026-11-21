# Rejillas de geoide de prueba (S034)

Tres geoides pequeños del CDN de PROJ (https://cdn.proj.org) para contrastar la lectura de geoides descargados con PROJ. No van en el APK.

- `fr_ign_ggpf02-Bora.tif`: © IGN (Francia), Licence Ouverte 2.0 (Etalab).
- `cz_cuzk_CR-2005.tif`: © ČÚZK (República Checa), CC-BY 4.0.
- `us_noaa_g2012bg0.tif`: NOAA (EE. UU.), dominio público.

El SHA-256 de cada una está en `tools/geodesia/datos/rejillas_cdn.json`; `tools/geodesia/geoides_cdn.py` las comprueba al regenerar.
