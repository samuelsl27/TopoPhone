"""Los vectores de S020 están al día y cumplen lo que dice la spec con otro cálculo (constitución nº 2)."""

from __future__ import annotations

import csv
import io
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_base_shift as gb  # noqa: E402


def read(name: str) -> list[dict[str, str]]:
    text = (gb.OUT / name).read_text(encoding="utf-8")
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    return list(csv.DictReader(io.StringIO(body)))


def test_los_ficheros_son_los_que_genera_el_script() -> None:
    """S020/RF-4: los vectores guardados son los del script."""
    assert (gb.OUT / "vectores.csv").read_text(encoding="utf-8") == "\n".join(gb.vectors()) + "\n"
    assert (gb.OUT / "aplicados.csv").read_text(encoding="utf-8") == "\n".join(gb.applied()) + "\n"


def test_el_modulo_y_la_clasificacion_salen_del_vector() -> None:
    """S020/RF-5: el módulo y la clasificación salen del vector."""
    for row in read("vectores.csv"):
        module = math.sqrt(sum(float(row[k]) ** 2 for k in ("dx", "dy", "dz")))
        assert abs(module - float(row["modulo"])) < 1e-9, row["caso"]
        expected = "REJECTED" if module > 1000.0 else "NEEDS_CONFIRMATION" if module > 10.0 else "OK"
        assert row["clasificacion"] == expected, row["caso"]


def test_los_umbrales_tienen_un_caso_a_cada_lado() -> None:
    """S020/RF-5: avisa a partir de 10 m y rechaza por encima de 1.000 m."""
    classes = {row["caso"]: row["clasificacion"] for row in read("vectores.csv")}
    assert classes["modulo-9_99"] == "OK"
    assert classes["modulo-10_01"] == "NEEDS_CONFIRMATION"
    assert classes["modulo-999_9"] == "NEEDS_CONFIRMATION"
    assert classes["modulo-1000_1"] == "REJECTED"


def test_la_diferencia_de_datum_itrf_absorbida_ronda_0_9_m() -> None:
    """S020/RF-3: la diferencia de datum queda absorbida por el vector."""
    row = next(r for r in read("vectores.csv") if r["caso"] == "itrf-sobre-etrs89")
    assert 0.5 < float(row["modulo"]) < 1.3


def test_con_altura_el_punto_medido_desplazado_cae_en_el_conocido() -> None:
    """S020/RF-7: la medida desplazada coincide con el punto conocido."""
    known = {r["caso"]: r for r in read("vectores.csv")}
    for row in read("aplicados.csv")[::3]:  # el primero de cada caso es la propia medida
        k = known[row["caso"]]
        east, north = float(row["este"]), float(row["norte"])
        tolerance = 0.01 if k["conocido_altura"] == "" else 1e-6
        assert abs(east - float(k["conocido_este"])) < tolerance, row["caso"]
        assert abs(north - float(k["conocido_norte"])) < tolerance, row["caso"]
        if k["conocido_altura"]:
            assert abs(float(row["altura_proyecto"]) - float(k["conocido_altura"])) < 1e-6, row["caso"]


def test_sin_altura_el_vector_es_horizontal_en_el_plano_local_de_la_medida() -> None:
    """S020/RF-6: sin altura el vector no tiene componente vertical."""
    for row in read("vectores.csv"):
        if row["conocido_altura"]:
            continue
        lat, lon = float(row["medido_latitud"]), float(row["medido_longitud"])
        d = (float(row["dx"]), float(row["dy"]), float(row["dz"]))
        up = (
            math.cos(math.radians(lat)) * math.cos(math.radians(lon)),
            math.cos(math.radians(lat)) * math.sin(math.radians(lon)),
            math.sin(math.radians(lat)),
        )
        assert abs(sum(a * b for a, b in zip(d, up))) < 1e-6, row["caso"]


def test_la_suma_en_ecef_conserva_la_distancia_entre_puntos() -> None:
    """S020/RF-7: sumar el vector en ECEF no deforma las distancias."""
    rows = [r for r in read("aplicados.csv") if r["caso"] == "etrs89-single-3m"]
    raw = [gb.ecef(float(r["latitud"]), float(r["longitud"]), float(r["altura"])) for r in rows]
    shifted_geo = [
        gb.geodetic(x + float(r["dx"]), y + float(r["dy"]), z + float(r["dz"])) for (x, y, z), r in zip(raw, rows)
    ]
    shifted = [gb.ecef(*g) for g in shifted_geo]
    for i in range(len(raw)):
        for j in range(i + 1, len(raw)):
            assert abs(math.dist(raw[i], raw[j]) - math.dist(shifted[i], shifted[j])) < 1e-6
