"""Lo esperado de importar la capa en EPSG:3857 en un proyecto en ETRS89 UTM 30 (`generar_biblioteca.py`, S057/RF-16): tres puntos, reproducibles."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_biblioteca as g  # noqa: E402


def test_s057_rf_16_el_fichero_esperado_tiene_los_tres_puntos_de_madrid() -> None:
    lineas = [l for l in g.SALIDA.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]

    assert lineas[0].split(",") == g.COLUMNAS
    filas = [l.split(",") for l in lineas[1:]]
    assert len(filas) == 3
    for fila in filas:
        assert 400_000 < float(fila[6]) < 500_000 and 4_400_000 < float(fila[7]) < 4_500_000  # Madrid en UTM 30N


def test_s057_rf_16_regenerarlo_da_el_mismo_fichero() -> None:
    assert g.a_csv(g.generar()) == g.SALIDA.read_text(encoding="utf-8")


def test_s057_rf_16_el_shapefile_esperado_tiene_sus_puntos_en_utm_30() -> None:
    lineas = [l for l in g.SALIDA_SHP.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]

    assert lineas[0].split(",") == g.COLUMNAS
    filas = [l.split(",") for l in lineas[1:]]
    assert len(filas) == 3
    assert g.a_csv_shp(g.generar_shp()) == g.SALIDA_SHP.read_text(encoding="utf-8")
