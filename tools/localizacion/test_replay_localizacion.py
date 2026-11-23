"""La reproducción sintética de la localización está al día y cumple lo que promete su generador (S039)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_localizacion as loc  # noqa: E402
import generar_replay_localizacion as r  # noqa: E402
import generar_sintetico as g  # noqa: E402


def rows() -> list[dict[str, str]]:
    text = (g.OUT / r.CSV).read_text(encoding="utf-8")
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    return list(csv.DictReader(io.StringIO(body)))


def test_s039_rf_17_los_ficheros_son_los_que_genera_el_script() -> None:
    generated = r.generate()
    assert (g.OUT / r.NAME).read_bytes() == generated[r.NAME]
    assert (g.OUT / r.CSV).read_text(encoding="utf-8") == generated[r.CSV]


def test_s039_rf_7_hay_cuatro_pares_y_un_punto_medido_despues() -> None:
    roles = [fila["rol"] for fila in rows()]
    assert roles == ["par", "par", "par", "par", "medido"]


def test_s039_rf_9_las_coordenadas_conocidas_salen_de_la_transformacion_sin_ruido() -> None:
    for fila in rows():
        e, n, h = float(fila["este"]), float(fila["norte"]), float(fila["h"])
        ke, kn, kh = loc.conocida(e, n, h, None)
        assert math.isclose(ke, float(fila["conocido_este"]), abs_tol=1e-3)
        assert math.isclose(kn, float(fila["conocido_norte"]), abs_tol=1e-3)
        assert math.isclose(kh, float(fila["conocido_h"]), abs_tol=1e-3)
