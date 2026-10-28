"""La reproducción sintética del ráster está al día y tiene lo que promete su generador (S045)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replay_raster as rr  # noqa: E402

HEADER = ["hora", "estado", "este", "norte", "h", "guia", "cota_sup", "estado_sup", "d_cota", "nivel", "aviso_nivel", "aviso"]


def rows() -> list[dict[str, str]]:
    text = (rr.g.OUT / rr.CSV).read_text(encoding="utf-8")
    body = [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "hora,"))]
    return [dict(zip(HEADER, row)) for row in body]


def independent_height(east: float, north: float) -> float | None:
    """Otra bilineal que la del generador: índices de celda por redondeo hacia abajo y pesos explícitos, sin tolerancia de borde."""
    gx, gy = east - rr.X0, rr.Y0 - north
    if gx < 0 or gy < 0 or gx > rr.COLS - 1 or gy > rr.ROWS - 1:
        return None
    i, j = min(int(gx), rr.COLS - 2), min(int(gy), rr.ROWS - 2)
    fx, fy = gx - i, gy - j
    cell = rr.RASTER.values
    c = [cell[j * rr.COLS + i], cell[j * rr.COLS + i + 1], cell[(j + 1) * rr.COLS + i], cell[(j + 1) * rr.COLS + i + 1]]
    if any(math.isnan(v) for v in c):
        return None
    top = c[0] * (1 - fx) + c[1] * fx
    bottom = c[2] * (1 - fx) + c[3] * fx
    return top * (1 - fy) + bottom * fy


def test_s045_rf21_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    """S045/RF-21: fixtures/raster/replay-rs3.tif y rs3-raster.* son los que genera el script."""
    for name, (folder, content) in rr.generate().items():
        path = folder / name
        expected = content if isinstance(content, bytes) else content.encode("utf-8")
        assert path.read_bytes().replace(b"\r\n", b"\n") == expected.replace(b"\r\n", b"\n") or path.read_bytes() == expected, name


def test_s045_rf21_la_cota_esperada_coincide_con_otra_bilineal() -> None:
    """S045/RF-21: la cota del ráster en cada época coincide con otra bilineal escrita aparte (≤ 1 mm)."""
    checked = 0
    for row in rows():
        if row["guia"] != "SI":
            continue
        height = independent_height(float(row["este"]), float(row["norte"]))
        if row["estado_sup"] == "FUERA":
            assert height is None, row
        else:
            assert height is not None and abs(height - float(row["cota_sup"])) < 1e-3, row
            checked += 1
    assert checked > 150


def test_s045_rf22_el_recorrido_empieza_fuera_cruza_el_hueco_y_sale() -> None:
    """S045/RF-22: la reproducción pasa de fuera a dentro, por un hueco sin dato, y vuelve a salir; con 20 épocas sin FIX."""
    states = [(r["guia"], r["estado_sup"]) for r in rows()]
    assert states[0] == ("SI", "FUERA") and states[-1] == ("SI", "FUERA")
    assert sum(1 for s in states if s == ("NO_FIX", "")) == 20
    inside = [i for i, s in enumerate(states) if s[1] == "VALOR"]
    holes = [i for i in range(inside[0], inside[-1]) if states[i] == ("SI", "FUERA")]
    assert holes, "el recorrido tiene que cruzar el hueco"
    assert (inside[0], inside[-1]) == (inside[0], inside[-1]) and inside[0] > 30 and inside[-1] < 330


def test_s045_rf24_los_avisos_del_nivel_van_en_su_orden() -> None:
    """S045/RF-24: entrar en cota, sin FIX, entrar de nuevo, entrar tras el hueco y salir."""
    events = [e for r in rows() for e in (r["aviso_nivel"], r["aviso"]) if e]
    assert events == ["LEVEL_ARRIVED", "PERDIDO", "LEVEL_ARRIVED", "LEVEL_ARRIVED", "LEVEL_LEFT"]
