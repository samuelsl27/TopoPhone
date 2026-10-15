"""Coherencia entre la rejilla incluida en la app y los vectores de referencia (ADR-4)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rejillas  # noqa: E402

FIXTURES = rejillas.ROOT / "fixtures" / "geodesia"


def test_la_rejilla_de_la_app_tiene_la_huella_declarada() -> None:
    for grid in rejillas.GRIDS:
        assert rejillas.sha256_of(grid.path) == grid.sha256, grid.name


def test_los_vectores_se_generaron_con_la_misma_rejilla() -> None:
    for name in ("utm_etrs89.csv", "geoide_egm08_rednap.csv", "proyecto_huso_fijo.csv"):
        text = (FIXTURES / name).read_text(encoding="utf-8")
        assert f"sha256={rejillas.GRIDS[0].sha256}" in text, name


def test_hay_vectores_suficientes_y_puntos_fuera_de_la_rejilla() -> None:
    rows = [line for line in (FIXTURES / "geoide_egm08_rednap.csv").read_text(encoding="utf-8").splitlines() if line and not line.startswith(("#", "latitud"))]
    assert len(rows) > 150
    assert any(row.endswith(",") for row in rows)


def test_los_vectores_de_proyecto_incluyen_huso_forzado_y_puntos_sin_geoide() -> None:
    """S006/RF-29, RF-30: puntos calculados en un huso que no es el suyo y puntos sin altitud."""
    rows = [line.split(",") for line in (FIXTURES / "proyecto_huso_fijo.csv").read_text(encoding="utf-8").splitlines() if line and not line.startswith(("#", "latitud"))]
    assert len(rows) > 200
    forced = [row for row in rows if int(row[3]) != int((float(row[1]) + 180) // 6) + 1]
    assert len(forced) > 100
    assert any(row[6] == "" for row in rows)
