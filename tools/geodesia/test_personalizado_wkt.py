"""WKT1 de los SC personalizados de S035/RF-23 (`personalizado_wkt.py`): PROJ, leyendolo, da lo mismo que los parametros sueltos."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import personalizado_vectores as v  # noqa: E402
import personalizado_wkt as w  # noqa: E402


def _leer(ruta: Path) -> list[list[str]]:
    lineas = [l for l in ruta.read_text(encoding="utf-8").splitlines() if not l.startswith("#")]
    return [l.split("\t") if ruta == w.SALIDA else l.split(",") for l in lineas[1:]]


def _metros(lon_a: float, lat_a: float, lon_b: float, lat_b: float) -> float:
    por_grado = 111_319.49
    return math.hypot((lat_a - lat_b) * por_grado, (lon_a - lon_b) * por_grado * math.cos(math.radians(lat_b)))


def test_s035_rf_23_el_wkt_leido_por_proj_da_lo_mismo_que_los_parametros_sueltos_a_1_mm() -> None:
    wkt = _leer(w.SALIDA)
    sueltos = _leer(v.SALIDA)
    assert len(wkt) == len(sueltos) == 24
    for a, b in zip(wkt, sueltos):
        assert a[0] == b[0] and a[2] == b[13]
        assert _metros(float(a[5]), float(a[6]), float(b[16]), float(b[17])) < 0.001, f"{a[0]} #{a[2]}"


def test_s035_rf_23_el_wkt_de_un_helmert_de_marco_de_coordenadas_lleva_las_rotaciones_con_el_signo_cambiado() -> None:
    cf = next(f for f in _leer(w.SALIDA) if f[0] == "tm_helmert7_cf")[1]
    # tx, ty, tz, rx, ry, rz, s de la fila: 30.0, -100.0, -80.0, 0.5, -0.3, 0.2, 2.0
    assert "TOWGS84[30.0,-100.0,-80.0,-0.5,0.3,-0.2,2.0]" in cf


def test_s035_rf_23_regenerarlo_da_el_mismo_fichero() -> None:
    assert w.a_csv(w.generar()) == w.SALIDA.read_text(encoding="utf-8")
