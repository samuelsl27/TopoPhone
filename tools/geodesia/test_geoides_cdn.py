"""Los vectores de geoides del CDN (`geoides_cdn.py`): rejillas con su SHA-256, puntos dentro y fuera, y regeneración idéntica."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import geoides_cdn as g  # noqa: E402
import rejillas_cdn  # noqa: E402


def test_s034_rf_14_las_rejillas_de_prueba_coinciden_con_el_sha256_del_cdn() -> None:
    por_nombre = {x["name"]: x for x in rejillas_cdn.leer_instantanea()["grids"]}

    for nombre in g.REJILLAS:
        assert hashlib.sha256((g.CARPETA / nombre).read_bytes()).hexdigest() == por_nombre[nombre]["sha256"]
        assert por_nombre[nombre]["license"]["open"]


def test_s034_rf_14_el_csv_tiene_puntos_dentro_y_fuera_de_cada_rejilla() -> None:
    filas = [f.split(",") for f in g.SALIDA.read_text(encoding="utf-8").splitlines() if f and not f.startswith("#")][1:]

    for nombre in g.REJILLAS:
        propias = [f for f in filas if f[0] == nombre]
        assert sum(1 for f in propias if f[3]) >= 40
        assert sum(1 for f in propias if not f[3]) >= 3


def test_s034_rf_14_se_regenera_identico() -> None:
    assert g.SALIDA.read_text(encoding="utf-8") == g.generar()
