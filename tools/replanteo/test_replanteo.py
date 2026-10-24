"""Las referencias del replanteo están al día y salen de otro cálculo (S018)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_replanteo as r  # noqa: E402
from pyproj import Transformer  # noqa: E402


def rows() -> list[dict[str, str]]:
    lines = [line for line in (r.OUT / r.NAME).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:]]


def number(row: dict[str, str], key: str) -> float:
    return float(row[key])


def test_s018_el_fichero_guardado_es_el_que_genera_el_script() -> None:
    assert r.g.same_csv((r.OUT / r.NAME).read_text(encoding="utf-8"), r.generate())


def test_s018_rf_6_hay_casos_de_la_peninsula_de_canarias_y_geograficos() -> None:
    cases = rows()
    assert {row["sc"] for row in cases} == {"UTM30", "UTM28", "GEOGRAFICO"}
    assert all(sum(1 for row in cases if row["sc"] == sc) >= 8 for sc in ("UTM30", "UTM28", "GEOGRAFICO"))


def test_s018_rf_6_rf_7_las_diferencias_salen_de_las_coordenadas_de_la_fila() -> None:
    """Comprobación independiente: d, ΔE, ΔN, rumbo, adelante y derecha desde las coordenadas, sin las funciones del script."""
    for row in rows():
        if row["sc"] == "GEOGRAFICO":
            continue
        d_east = number(row, "diseno_e") - number(row, "punta_e")
        d_north = number(row, "diseno_n") - number(row, "punta_n")
        assert abs(number(row, "d_este") - d_east) < 1e-9, row["caso"]
        assert abs(number(row, "d_norte") - d_north) < 1e-9, row["caso"]
        assert abs(number(row, "distancia") - math.hypot(d_east, d_north)) < 1e-9, row["caso"]
        if row["rumbo_deg"]:
            bearing = number(row, "rumbo_deg")
            assert 0.0 <= bearing < 360.0
            assert abs(math.sin(math.radians(bearing)) * number(row, "distancia") - d_east) < 1e-6, row["caso"]
            assert abs(math.cos(math.radians(bearing)) * number(row, "distancia") - d_north) < 1e-6, row["caso"]
            relative = math.radians(bearing - number(row, "rumbo_usuario_deg"))
            assert abs(number(row, "adelante") - number(row, "distancia") * math.cos(relative)) < 1e-6, row["caso"]
            assert abs(number(row, "derecha") - number(row, "distancia") * math.sin(relative)) < 1e-6, row["caso"]


def test_s018_rf_6_los_este_y_norte_son_los_de_pyproj_de_la_latitud_y_la_longitud() -> None:
    projections = {"UTM30": ("EPSG:4258", "EPSG:25830"), "UTM28": ("EPSG:4081", "EPSG:4083")}
    for row in rows():
        if row["sc"] not in projections:
            continue
        transformer = Transformer.from_crs(*projections[row["sc"]], always_xy=True)
        for who in ("diseno", "punta"):
            east, north = transformer.transform(number(row, f"{who}_lon"), number(row, f"{who}_lat"))
            assert abs(east - number(row, f"{who}_e")) < 1e-5, row["caso"]
            assert abs(north - number(row, f"{who}_n")) < 1e-5, row["caso"]


def test_s018_rf_6_el_caso_geografico_es_el_plano_local_de_la_punta() -> None:
    """Un SC geográfico no tiene Este ni Norte: las diferencias son las del plano tangente local de la punta."""
    for row in (row for row in rows() if row["sc"] == "GEOGRAFICO"):
        local = r.g.generar_vectores.topocentric(number(row, "punta_lat"), number(row, "punta_lon"), number(row, "punta_h"))
        east, north, _ = local.transform(number(row, "diseno_lon"), number(row, "diseno_lat"), number(row, "diseno_h"))
        assert abs(number(row, "d_este") - east) < 1e-6, row["caso"]
        assert abs(number(row, "d_norte") - north) < 1e-6, row["caso"]
        assert row["diseno_e"] == "" and row["punta_e"] == ""


def test_s018_rf_12_la_diferencia_de_cota_es_la_de_la_punta_menos_la_del_diseno() -> None:
    for row in rows():
        assert abs(number(row, "d_cota") - (number(row, "punta_h") - number(row, "diseno_h"))) < 1e-9, row["caso"]


def test_s018_rf_6_con_distancia_cero_el_rumbo_no_existe() -> None:
    zero = [row for row in rows() if number(row, "distancia") < 1e-9]
    assert zero, "falta el caso de distancia cero"
    assert all(row["rumbo_deg"] == "" and number(row, "adelante") == 0.0 and number(row, "derecha") == 0.0 for row in zero)
