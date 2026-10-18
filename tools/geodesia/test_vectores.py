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
    expected = {
        "utm_etrs89.csv": rejillas.GEOID_PENINSULA,
        "geoide_egm08_rednap.csv": rejillas.GEOID_PENINSULA,
        "proyecto_huso_fijo.csv": rejillas.GEOID_PENINSULA,
        "utm_regcan95.csv": rejillas.GEOID_CANARIAS,
        "geoide_egm08_rednap_canarias.csv": rejillas.GEOID_CANARIAS,
        "ed50_utm.csv": rejillas.ED50_SHIFT,
        "factores_tm.csv": rejillas.GEOID_PENINSULA,
    }
    for name, grid in expected.items():
        text = (FIXTURES / name).read_text(encoding="utf-8")
        assert f"sha256={grid.sha256}" in text, name


def rows(name: str) -> list[list[str]]:
    lines = (FIXTURES / name).read_text(encoding="utf-8").splitlines()
    return [line.split(",") for line in lines if line and not line.startswith(("#", "latitud", "elipsoide"))]


def test_las_rejillas_de_s008_van_en_su_carpeta_y_ocupan_menos_de_300_kb() -> None:
    """S008, RNF: las rejillas nuevas no aumentan el APK en más de 300 KB."""
    assert rejillas.ED50_SHIFT.path.parent.name == "shift"
    assert rejillas.GEOID_CANARIAS.path.parent.name == "geoid"
    assert rejillas.ED50_SHIFT.path.stat().st_size + rejillas.GEOID_CANARIAS.path.stat().st_size < 300 * 1024


def test_s008_rf_7_rf_8_ed50_tiene_puntos_dentro_y_fuera_de_la_rejilla() -> None:
    ed50 = rows("ed50_utm.csv")
    assert {row[4] for row in ed50} == {"29", "30", "31"}
    assert sum(1 for row in ed50 if row[2]) > 150
    assert any(row[2] == "" for row in ed50)
    # Baleares (subrejilla propia) también está.
    assert any(abs(float(row[0]) - 39.5696) < 1e-9 and row[2] for row in ed50)


def test_s008_rf_4_el_geoide_de_canarias_tiene_puntos_dentro_y_fuera() -> None:
    geoid = rows("geoide_egm08_rednap_canarias.csv")
    assert sum(1 for row in geoid if row[2]) > 50
    assert sum(1 for row in geoid if row[2] == "") >= 5


def test_s008_rf_13_rf_14_los_factores_incluyen_huso_forzado_y_los_tres_datums() -> None:
    factors = rows("factores_tm.csv")
    assert {row[0] for row in factors} == {"GRS80", "intl"}
    assert {row[3] for row in factors} == {"27", "28", "29", "30", "31"}
    central = {row[3]: 6 * int(row[3]) - 183 for row in factors}
    assert any(abs(float(row[2]) - central[row[3]]) > 3.0 for row in factors)


def test_s008_rf_1_regcan95_cubre_los_husos_27_y_28() -> None:
    assert {row[2] for row in rows("utm_regcan95.csv")} == {"27", "28"}


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


def test_s009_rf_17_el_circulo_cubre_radios_de_5_mm_a_100_m_con_16_rumbos() -> None:
    circle = rows("circulo_precision.csv")
    assert {float(row[2]) for row in circle} == {0.005, 0.012, 0.5, 2.0, 25.0, 100.0}
    assert len({float(row[3]) for row in circle}) == 16
    # Cerca del ecuador, en Canarias y en el norte de la Península.
    assert {round(float(row[0])) for row in circle} >= {0, 28, 44}


def test_s009_rf_12_web_mercator_llega_al_zoom_maximo_con_metros_por_pixel_del_elipsoide() -> None:
    mercator = rows("web_mercator.csv")
    assert {float(row[4]) for row in mercator} >= {5.0, 17.0, 22.0}
    assert len(mercator) > 100
    # A 44° N y zoom 22, 100 px son menos de 1,7 m (la barra de escala puede bajar a 1 m).
    assert all(100 * float(row[5]) < 1.7 for row in mercator if float(row[4]) == 22.0 and 27.0 <= float(row[0]) <= 44.0)
