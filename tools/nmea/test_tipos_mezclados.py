"""La reproducción de tipos de solución mezclados está al día y su resultado esperado se confirma por otro camino (S047)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_sintetico as g  # noqa: E402
import generar_tipos_mezclados as r  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
NAME = "tipos-mezclados.nmea"
CSV = "tipos-mezclados.esperado.csv"
QUALITY_TYPE = {"1": "SINGLE", "2": "SINGLE", "4": "FIX", "5": "FLOAT"}  # una DGPS cuenta como SINGLE (S047/RF-18)


def lines() -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / NAME).read_bytes().split(b"\r\n") if line]


def epochs() -> list[tuple[str, float, float, float]]:
    """Por cada GGA: el tipo (desde el indicador de calidad) y el suelo (E, N, h) calculado aquí aparte."""
    out = []
    for line in lines():
        f = line.split("*")[0].split(",")
        if f[0] != "$GNGGA":
            continue
        lat, lon = g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)
        east, north = UTM.transform(lon, lat)
        out.append((QUALITY_TYPE[f[6]], east, north, (float(f[9]) + float(f[11])) - ANTENNA_HEIGHT))
    return out


def scenarios() -> list[dict[str, str]]:
    text = (g.OUT / CSV).read_text(encoding="utf-8")
    body = [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "escenario,"))]
    return [dict(zip(r.COLUMNS.split(","), row)) for row in body]


def test_s047_rf8_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s047_rf8_las_frases_tienen_suma_correcta_y_hay_400_epocas_a_5_hz() -> None:
    sentences = lines()
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences)
    assert len(epochs()) == r.EPOCHS == 400


def test_s047_rf18_los_indicadores_de_calidad_siguen_los_tramos() -> None:
    qualities = [line.split(",")[6] for line in lines() if line.startswith("$GNGGA")]
    for first, last, status, _, _ in r.SEGMENTS:
        assert set(qualities[first : last + 1]) == {str(g.QUALITY[status])}, status
    assert {"1", "2", "4", "5"} == set(qualities)


def test_s047_rf9_rf10_el_resultado_sale_de_las_epocas_del_mejor_tipo_admitido() -> None:
    """Comprobación independiente: lo usado es todo lo del mejor tipo admitido visto hasta el final, sin la máquina del script."""
    data = epochs()
    rows = scenarios()
    assert [row["escenario"] for row in rows] == [s[0] for s in r.SCENARIOS]
    for row in rows:
        admitted = set(row["admitidos"].split("+"))
        start, end = int(row["inicio"]), int(row["epoca_fin"])
        window = data[start : end + 1]
        best = min((e[0] for e in window if e[0] in admitted), key=lambda t: r.RANK[t])
        used = [e for e in window if e[0] == best]
        assert int(row["usadas"]) == len(used) == round(float(row["pedidos_s"]) / r.EPOCH_S), row["escenario"]
        assert int(row["descartadas_tipo"]) == len(window) - len(used), row["escenario"]
        for column, index in (("media_e", 1), ("media_n", 2), ("media_h", 3)):
            assert abs(float(row[column]) - math.fsum(e[index] for e in used) / len(used)) < 1e-6, (row["escenario"], column)
        # Los avisos: las veces que, con la medida en marcha, se pasa del mejor tipo a otro.
        kinds = [e[0] for e in window]
        first = kinds.index(best)
        changes = sum(1 for a, b in zip(kinds[first:], kinds[first + 1 :]) if a == best and b != best)
        assert int(row["avisos"]) == changes, row["escenario"]


def test_s047_rf9_la_medida_con_los_tres_tipos_acaba_en_fix_y_la_de_solo_float_en_float() -> None:
    by_name = {row["escenario"]: row for row in scenarios()}
    assert by_name["los-tres"]["solucion"] == "FIX"
    assert by_name["solo-fix"]["solucion"] == "FIX"
    assert by_name["fix-y-float"]["solucion"] == "FLOAT"
    assert by_name["solo-float"]["solucion"] == "FLOAT"
    assert by_name["solo-single-con-dgps"]["solucion"] == "SINGLE"
