"""Coherencia entre la rejilla incluida en la app y los vectores de referencia (ADR-4)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rejillas  # noqa: E402
from pyproj import Geod  # noqa: E402

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
        "puntos_manuales.csv": rejillas.ED50_SHIFT,
        "jalon_corregido.csv": rejillas.ED50_SHIFT,
        "ecef.csv": rejillas.GEOID_PENINSULA,
        "linea_base.csv": rejillas.GEOID_PENINSULA,
        "punta_jalon.csv": rejillas.GEOID_PENINSULA,
    }
    for name, grid in expected.items():
        text = (FIXTURES / name).read_text(encoding="utf-8")
        assert f"sha256={grid.sha256}" in text, name


def rows(name: str) -> list[list[str]]:
    lines = (FIXTURES / name).read_text(encoding="utf-8").splitlines()
    return [line.split(",") for line in lines if line and not line.startswith(("#", "latitud", "elipsoide", "datum"))]


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


def test_s011_rf_17_rf_18_rf_20_los_puntos_manuales_cubren_cada_datum_forma_y_altura() -> None:
    manual = [row for row in rows("puntos_manuales.csv") if not row[14]]
    assert {(row[0], row[1]) for row in manual} >= {
        ("ETRS89", "29"), ("ETRS89", "30"), ("ETRS89", "31"), ("ETRS89", ""),
        ("REGCAN95", "27"), ("REGCAN95", "28"), ("REGCAN95", ""),
        ("ED50", "29"), ("ED50", "30"), ("ED50", "31"),
    }
    assert {row[3] for row in manual} == {"PROJECT_CRS", "GEOGRAPHIC"}
    assert {row[7] for row in manual} == {"PROJECT", "ELLIPSOIDAL"}
    assert {row[2] for row in manual} == {"ORTHOMETRIC", "ELLIPSOIDAL"}
    # Sin altura (S011/RF-18): no hay altura elipsoidal ni del proyecto.
    assert any(row[6] == "" and row[10] == "" and row[13] == "" for row in manual)
    # ED50 solo admite altitud (S008/RF-3).
    assert all(row[2] == "ORTHOMETRIC" and row[7] == "PROJECT" for row in manual if row[0] == "ED50")


def test_s011_rf_19_hay_rechazos_por_cada_motivo() -> None:
    reasons = {row[14] for row in rows("puntos_manuales.csv")}
    assert reasons == {"", "OUTSIDE_CRS_AREA", "OUTSIDE_ED50_GRID", "OUTSIDE_GEOID"}


def test_s011_rf_14_el_jalon_corregido_baja_el_suelo_jalon_mas_0_134_m() -> None:
    pole = rows("jalon_corregido.csv")
    assert {row[0] for row in pole} == {"ETRS89", "REGCAN95", "ED50"}
    for row in pole:
        assert abs(float(row[4]) - float(row[5]) - 0.134 - float(row[6])) < 1e-9


def test_s015_rf_13_ecef_cubre_espana_canarias_avl13m_y_extremos() -> None:
    ecef = rows("ecef.csv")
    assert len(ecef) > 50
    lats = [float(row[0]) for row in ecef]
    assert any(27.6 < lat < 29.4 for lat in lats) and any(lat > 89.0 for lat in lats) and any(lat < -89.0 for lat in lats)
    # La estación AVL13M del 1006 real: Avilés, a 78 m de altura elipsoidal.
    avl13m = next(row for row in ecef if row[3] == "4604889.1789")
    assert abs(float(avl13m[0]) - 43.5578666) < 1e-6 and abs(float(avl13m[1]) + 5.9130704) < 1e-6
    assert abs(float(avl13m[2]) - 78.434) < 1e-3


def test_s015_rf_14_la_linea_base_va_de_cero_a_80_km_y_rodea_los_20_km() -> None:
    distances = [float(row[6]) for row in rows("linea_base.csv")]
    assert min(distances) < 1e-6  # la misma posición, salvo el redondeo de las geocéntricas
    assert any(0.1 < d < 3.0 for d in distances)
    assert max(distances) > 79_000.0
    assert any(19_000.0 < d < 20_000.0 for d in distances) and any(20_000.0 < d < 21_000.0 for d in distances)


def test_s016_rf_13_la_punta_cubre_espana_canarias_inclinaciones_y_alturas() -> None:
    tips = rows("punta_jalon.csv")
    assert len(tips) > 60
    lats = [float(row[0]) for row in tips]
    tilts = [float(row[3]) for row in tips]
    directions = [float(row[4]) for row in tips]
    antennas = [float(row[5]) for row in tips]
    assert any(27.6 < lat < 29.4 for lat in lats) and any(38.6 < lat < 40.1 and 1.2 < float(row[1]) < 4.4 for lat, row in zip(lats, tips))
    assert 0.0 in tilts and 60.0 in tilts and max(tilts) <= 60.0
    assert 0.0 in directions and max(directions) > 350.0
    assert min(antennas) == 0.134 and max(antennas) == 10.134
    assert max(float(row[2]) for row in tips) > 2_900.0


def test_s016_rf_13_la_punta_queda_a_h_sen_inclinacion_en_sentido_contrario_a_la_direccion() -> None:
    """Comprobación independiente de la tubería topocéntrica: distancia y acimut con la geodésica de GRS80."""
    geod = Geod(ellps="GRS80")
    for row in rows("punta_jalon.csv"):
        lat, lon, h, tilt, direction, antenna, lat_t, lon_t, h_t = (float(v) for v in row)
        theta = math.radians(tilt)
        if tilt == 0.0:
            assert abs(lat_t - lat) < 1e-12 and abs(lon_t - lon) < 1e-12
        else:
            azimuth, _, distance = geod.inv(lon, lat, lon_t, lat_t)
            # La geodésica va sobre el elipsoide y el desplazamiento a la altura h: difieren en d·h/R (< 5 mm).
            assert abs(distance - antenna * math.sin(theta)) < 0.005
            assert abs((azimuth - direction - 180.0 + 540.0) % 360.0 - 180.0) < 0.01
        # El desnivel es H·cos θ más la caída de la curvatura (d²/2R, menos de 1 mm con 10 m de jalón).
        assert abs(h - h_t - antenna * math.cos(theta)) < 0.001
