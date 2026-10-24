"""La cota de diseño y el informe del replanteo están al día y salen de otro cálculo (S019)."""

from __future__ import annotations

import math
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_informe as r  # noqa: E402
from pyproj import Transformer  # noqa: E402

PROJECTIONS = {"UTM30": ("EPSG:4258", "EPSG:25830"), "UTM28": ("EPSG:4081", "EPSG:4083")}


def read(name: str) -> list[dict[str, str]]:
    lines = [line for line in (r.OUT / name).read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:]]


def number(row: dict[str, str], key: str) -> float:
    return float(row[key])


def optional(row: dict[str, str], key: str) -> float | None:
    return float(row[key]) if row[key] else None


def test_s019_rf6_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    informe, rows = r.generate_informe()
    assert r.g.same_csv((r.OUT / r.NAME_COTA).read_text(encoding="utf-8"), r.generate_cota())
    assert r.g.same_csv((r.OUT / r.NAME_INFORME).read_text(encoding="utf-8"), informe)
    assert r.g.same_csv((r.OUT / r.NAME_RESUMEN).read_text(encoding="utf-8"), r.generate_resumen(rows))


def test_s019_rf6_la_diferencia_de_cota_es_la_de_la_punta_menos_la_cota_efectiva() -> None:
    cases = read(r.NAME_COTA)
    assert len(cases) >= 15
    for row in cases:
        written = optional(row, "cota_escrita")
        design = optional(row, "diseno_h")
        tip = optional(row, "punta_h")
        effective = written if written is not None else design
        assert optional(row, "cota_efectiva") == effective, row["caso"]
        if effective is None or tip is None:
            assert row["d_cota"] == "" and row["estado"] == "", row["caso"]
        else:
            assert abs(number(row, "d_cota") - (tip - effective)) < 1e-9, row["caso"]


def test_s019_rf6_y_rf8_el_desmonte_es_la_punta_por_encima_y_el_terraplen_por_debajo() -> None:
    states = {row["caso"]: row["estado"] for row in read(r.NAME_COTA)}
    assert states["desmonte-1-cm"] == "ON_GRADE" and states["terraplen-1-cm"] == "ON_GRADE"
    assert states["desmonte-justo-fuera"] == "CUT" and states["terraplen-justo-fuera"] == "FILL"
    assert states["desmonte-de-metros"] == "CUT" and states["terraplen-de-metros"] == "FILL"
    assert states["desmonte-de-decenas"] == "CUT" and states["terraplen-de-decenas"] == "FILL"
    for row in read(r.NAME_COTA):
        if row["d_cota"]:
            d_cota, tolerance = number(row, "d_cota"), number(row, "tol_v")
            expected = "ON_GRADE" if abs(d_cota) <= tolerance else ("CUT" if d_cota > 0 else "FILL")
            assert row["estado"] == expected, row["caso"]


def test_s019_rf6_hay_diferencias_de_varios_metros_y_el_limite_exacto() -> None:
    cases = {row["caso"]: row for row in read(r.NAME_COTA)}
    assert abs(number(cases["desmonte-de-metros"], "d_cota") - 3.25) < 1e-9
    assert abs(number(cases["terraplen-de-decenas"], "d_cota") + 40.0) < 1e-9
    assert cases["desmonte-en-el-limite"]["estado"] == "ON_GRADE"
    assert cases["terraplen-en-el-limite"]["estado"] == "ON_GRADE"


def test_s019_rf21_hay_proyectos_de_la_peninsula_de_canarias_y_geograficos() -> None:
    rows = read(r.NAME_INFORME)
    assert {row["proyecto"] for row in rows} == {"UTM30", "UTM28", "GEOGRAFICO"}
    for project in ("UTM30", "UTM28", "GEOGRAFICO"):
        mine = [row for row in rows if row["proyecto"] == project]
        assert len(mine) >= 10
        assert {row["numero"] for row in mine} >= {"1", "2", "3"}


def test_s019_rf21_los_nombres_siguen_el_patron_de_replanteo_y_el_numero_es_el_del_sufijo() -> None:
    for row in read(r.NAME_INFORME):
        number_ = int(row["numero"])
        expected = f"{row['diseno']}_stkd" + ("" if number_ == 1 else str(number_))
        assert row["nombre"] == expected


def test_s019_rf13_las_diferencias_salen_de_las_coordenadas_de_la_fila() -> None:
    """Comprobación independiente: ΔN, ΔE, distancia y ΔH desde las coordenadas, sin las funciones del script."""
    for row in read(r.NAME_INFORME):
        if row["proyecto"] == "GEOGRAFICO":
            continue
        d_east = number(row, "medido_e") - number(row, "diseno_e")
        d_north = number(row, "medido_n") - number(row, "diseno_n")
        assert abs(number(row, "d_este") - d_east) < 1e-9, row["nombre"]
        assert abs(number(row, "d_norte") - d_north) < 1e-9, row["nombre"]
        assert abs(number(row, "d_horizontal") - math.hypot(d_east, d_north)) < 1e-9, row["nombre"]


def test_s019_rf13_los_este_y_norte_son_los_de_pyproj_de_la_latitud_y_la_longitud() -> None:
    for row in read(r.NAME_INFORME):
        if row["proyecto"] not in PROJECTIONS:
            continue
        transformer = Transformer.from_crs(*PROJECTIONS[row["proyecto"]], always_xy=True)
        for who in ("diseno", "medido"):
            east, north = transformer.transform(number(row, f"{who}_lon"), number(row, f"{who}_lat"))
            assert abs(east - number(row, f"{who}_e")) < 1e-5, row["nombre"]
            assert abs(north - number(row, f"{who}_n")) < 1e-5, row["nombre"]


def test_s019_rf13_el_caso_geografico_es_el_plano_local_del_punto_medido() -> None:
    geographic = [row for row in read(r.NAME_INFORME) if row["proyecto"] == "GEOGRAFICO"]
    assert geographic
    for row in geographic:
        local = r.g.generar_vectores.topocentric(number(row, "medido_lat"), number(row, "medido_lon"), number(row, "medido_gh"))
        east, north, _ = local.transform(number(row, "diseno_lon"), number(row, "diseno_lat"), number(row, "diseno_gh"))
        assert abs(number(row, "d_este") + east) < 1e-6, row["nombre"]  # medido menos diseño: el signo contrario
        assert abs(number(row, "d_norte") + north) < 1e-6, row["nombre"]
        assert row["diseno_e"] == "" and row["medido_e"] == ""


def test_s019_rf13_la_diferencia_de_cota_es_la_medida_menos_la_cota_usada() -> None:
    saw_written = saw_no_height = False
    for row in read(r.NAME_INFORME):
        written, design, used = optional(row, "cota_escrita"), optional(row, "diseno_h"), optional(row, "cota_usada")
        assert used == (written if written is not None else design), row["nombre"]
        if used is None:
            saw_no_height = True
            assert row["d_cota"] == "", row["nombre"]
        else:
            assert abs(number(row, "d_cota") - (number(row, "medido_h") - used)) < 1e-9, row["nombre"]
        saw_written = saw_written or written is not None
    assert saw_written and saw_no_height


def test_s019_rf14_dentro_exige_la_horizontal_y_la_vertical_si_hay_cota() -> None:
    seen = set()
    for row in read(r.NAME_INFORME):
        if not row["tol_h"]:
            continue
        horizontal = number(row, "d_horizontal") <= number(row, "tol_h")
        vertical = row["d_cota"] == "" or abs(number(row, "d_cota")) <= number(row, "tol_v")
        assert row["dentro"] == ("1" if horizontal and vertical else "0"), row["nombre"]
        seen.add((horizontal, vertical, row["d_cota"] != ""))
    # Dentro en H y fuera en V, fuera en H, dentro en los dos y sin cota.
    assert (True, False, True) in seen and (False, False, True) in seen and (True, True, True) in seen and (True, True, False) in seen


def test_s019_rf26_los_replanteos_de_antes_no_tienen_tolerancias_ni_veredicto() -> None:
    old = [row for row in read(r.NAME_INFORME) if not row["tol_h"]]
    assert old
    assert all(row["tol_v"] == "" and row["dentro"] == "" for row in old)
    assert all(row["tol_v"] != "" and row["dentro"] != "" for row in read(r.NAME_INFORME) if row["tol_h"])


def test_s019_rf17_el_ultimo_replanteo_de_cada_punto_es_el_mas_reciente() -> None:
    rows = read(r.NAME_INFORME)
    for project in ("UTM30", "UTM28", "GEOGRAFICO"):
        by_design: dict[str, list[dict[str, str]]] = {}
        for row in (row for row in rows if row["proyecto"] == project):
            by_design.setdefault(row["diseno"], []).append(row)
        for design, group in by_design.items():
            latest = max(group, key=lambda row: int(row["creado_ms"]))
            assert [row["ultimo"] for row in group if row is latest] == ["1"], design
            assert all(row["ultimo"] == "0" for row in group if row is not latest), design
    assert sum(1 for row in rows if row["proyecto"] == "UTM30" and row["ultimo"] == "0") >= 3


def test_s019_rf17_el_resumen_sale_de_los_ultimos_replanteos() -> None:
    rows = read(r.NAME_INFORME)
    summaries = {(row["proyecto"], row["ambito"]): row for row in read(r.NAME_RESUMEN)}
    assert len(summaries) == 6
    for (project, scope), summary in summaries.items():
        mine = [row for row in rows if row["proyecto"] == project and (scope == "todos" or row["en_seleccion"] == "1")]
        latest: dict[str, dict[str, str]] = {}
        for row in mine:
            if row["diseno"] not in latest or int(row["creado_ms"]) > int(latest[row["diseno"]]["creado_ms"]):
                latest[row["diseno"]] = row
        lasts = list(latest.values())
        horizontal = [number(row, "d_horizontal") for row in lasts]
        vertical = [abs(number(row, "d_cota")) for row in lasts if row["d_cota"]]
        assert int(summary["puntos"]) == len(lasts)
        assert int(summary["dentro"]) == sum(1 for row in lasts if row["dentro"] == "1")
        assert int(summary["fuera"]) == sum(1 for row in lasts if row["dentro"] == "0")
        assert int(summary["sin_evaluar"]) == sum(1 for row in lasts if row["dentro"] == "")
        assert abs(number(summary, "h_max") - max(horizontal)) < 1e-9
        assert abs(number(summary, "h_media") - statistics.fmean(horizontal)) < 1e-9
        assert int(summary["v_n"]) == len(vertical)
        assert abs(number(summary, "v_max") - max(vertical)) < 1e-9
        assert abs(number(summary, "v_media") - statistics.fmean(vertical)) < 1e-9


def test_s019_rf17_la_seleccion_cambia_el_resumen_y_hay_un_sin_evaluar() -> None:
    summaries = {(row["proyecto"], row["ambito"]): row for row in read(r.NAME_RESUMEN)}
    everything, selection = summaries[("UTM30", "todos")], summaries[("UTM30", "seleccion")]
    assert int(everything["puntos"]) > int(selection["puntos"]) == 3
    assert int(everything["sin_evaluar"]) >= 1
