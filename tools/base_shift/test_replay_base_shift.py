"""La reproducción sintética del base shift está al día y cumple lo que promete su generador (S020)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replay_base_shift as r  # noqa: E402
import generar_sintetico as g  # noqa: E402


def rows() -> list[dict[str, str]]:
    text = (g.OUT / r.CSV).read_text(encoding="utf-8")
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    return list(csv.DictReader(io.StringIO(body)))


def test_los_ficheros_son_los_que_genera_el_script() -> None:
    """S020/RF-7: la reproducción y lo esperado guardados son los del script."""
    generated = r.generate()
    assert (g.OUT / r.NAME).read_bytes() == generated[r.NAME]
    assert (g.OUT / r.CSV).read_text(encoding="utf-8") == generated[r.CSV]


def test_el_vertice_conocido_medido_y_desplazado_cae_en_su_sitio() -> None:
    """S020/RF-4: el vector lleva la medida de K al vértice conocido."""
    k = rows()[0]
    assert k["parada"] == "K"
    for measured, known in (("este_d", "conocido_este"), ("norte_d", "conocido_norte"), ("h_d", "conocido_h")):
        assert abs(float(k[measured]) - float(k[known])) < 1e-6


def test_el_vector_es_del_orden_del_error_de_la_base() -> None:
    """S020/RF-5: el módulo del vector es el del error de la base, que no pasa de 10 m."""
    k = rows()[0]
    module = math.sqrt(sum(float(k[c]) ** 2 for c in ("dx", "dy", "dz")))
    assert abs(module - math.sqrt(sum(v * v for v in r.ERROR_ENU))) < 0.01


def test_la_parada_tras_desactivar_va_sin_desplazar() -> None:
    """S020/RF-11: el punto medido tras desactivar conserva su punta."""
    c = rows()[-1]
    assert c["parada"] == "C"
    for raw, shifted in (("este", "este_d"), ("norte", "norte_d"), ("h", "h_d")):
        assert c[raw] == c[shifted]


def test_los_puntos_con_desplazamiento_se_mueven_lo_que_dice_el_vector() -> None:
    """S020/RF-7: A y B se desplazan en el sentido del vector."""
    for row in rows()[1:3]:
        moved = math.hypot(float(row["este_d"]) - float(row["este"]), float(row["norte_d"]) - float(row["norte"]))
        assert 1.0 < moved < 4.0
