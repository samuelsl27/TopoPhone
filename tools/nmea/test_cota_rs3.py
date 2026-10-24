"""La reproducción sintética de la cota está al día y tiene lo que promete su generador (S019)."""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_cota_rs3 as r  # noqa: E402
import generar_sintetico as g  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
NAME = "rs3-cota.nmea"
CSV = "rs3-cota.nivel.csv"
HEADER = [
    "hora", "estado", "este", "norte", "h", "guia", "distancia_m", "d_este_m", "d_norte_m", "d_cota_m", "nivel", "aviso_nivel",
    "radio_m", "aviso",
]  # fmt: skip


def lines() -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / NAME).read_bytes().split(b"\r\n") if line]


def epochs() -> list[tuple[str, str, float, float, float]]:
    """Por cada GGA: hora, estado y el suelo (E, N, h) calculado aquí aparte, desde la frase tal como la lee la app."""
    out = []
    for line in lines():
        f = line.split("*")[0].split(",")
        if f[0] != "$GNGGA":
            continue
        lat, lon = g.parsed_coordinate(f[2], f[3], 2), g.parsed_coordinate(f[4], f[5], 3)
        east, north = UTM.transform(lon, lat)
        out.append((f[1], f[6], east, north, (float(f[9]) + float(f[11])) - ANTENNA_HEIGHT))
    return out


def rows() -> list[dict[str, str]]:
    text = (g.OUT / CSV).read_text(encoding="utf-8")
    body = [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "hora,"))]
    return [dict(zip(HEADER, row)) for row in body]


def target() -> tuple[float, float, float]:
    header = next(line for line in (g.OUT / CSV).read_text(encoding="utf-8").splitlines() if line.startswith("# diseno:"))
    values = dict(part.split("=") for part in header.removeprefix("# diseno: ").split(";")[0].split())
    return float(values["este"]), float(values["norte"]), float(values["h"])


def test_s019_rf6_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s019_rf6_las_frases_tienen_suma_correcta() -> None:
    sentences = lines()
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences)
    assert {line[3:6] for line in sentences} == {"GGA", "GST", "RMC", "ZDA"}


def test_s019_rf6_hay_360_epocas_a_5_hz_y_una_fila_por_epoca() -> None:
    assert len(epochs()) == r.EPOCHS == len(rows()) == 360
    times = [r.millis(e[0]) for e in epochs()]
    assert all(b - a == 200 for a, b in zip(times, times[1:]))


def test_s019_rf6_la_diferencia_de_cota_sale_de_la_altura_de_la_gga_menos_la_del_diseno() -> None:
    """Comprobación independiente: d_cota desde la altura de la fila de la GGA, sin las funciones del script."""
    _, _, design_h = target()
    for epoch, row in zip(epochs(), rows()):
        if row["guia"] != "SI":
            continue
        assert abs(float(row["d_cota_m"]) - (epoch[4] - design_h)) < 1e-6, row["hora"]
        assert abs(float(row["h"]) - epoch[4]) < 1e-6


def test_s019_rf6_el_estado_del_nivel_es_desmonte_terraplen_o_en_cota_segun_la_diferencia() -> None:
    seen = set()
    for row in rows():
        if row["guia"] != "SI":
            assert row["nivel"] == ""
            continue
        dz = float(row["d_cota_m"])
        seen.add(row["nivel"])
        if row["nivel"] == "CUT":
            assert dz > r.TOLERANCE_V_M, row["hora"]
        elif row["nivel"] == "FILL":
            assert dz < -r.TOLERANCE_V_M, row["hora"]
        else:
            # En cota: hasta la tolerancia y, ya dentro, hasta 5 mm más.
            assert abs(dz) <= r.TOLERANCE_V_M + r.MARGIN_M, row["hora"]
    assert seen == {"CUT", "FILL", "ON_GRADE"}


def test_s019_rf6_hay_diferencias_de_varios_metros_y_de_terraplen() -> None:
    values = [float(row["d_cota_m"]) for row in rows() if row["guia"] == "SI"]
    assert max(values) > 3.4
    assert min(values) < -1.19


def test_s019_rf9_los_avisos_son_entrar_en_cota_y_salir_de_ella_cada_uno_una_vez_por_cruce() -> None:
    alerts = [(i, row["aviso_nivel"]) for i, row in enumerate(rows()) if row["aviso_nivel"]]
    kinds = [kind for _, kind in alerts]
    # Entra a la cota (época 99), sale hacia el terraplén, y tras el corte vuelve a entrar y sale al desmonte.
    assert kinds == ["LEVEL_ARRIVED", "LEVEL_LEFT", "LEVEL_ARRIVED", "LEVEL_LEFT"], alerts
    assert alerts[0][0] == 99
    assert alerts[1][0] == 151
    assert alerts[3][0] == 340


def test_s019_rf9_oscilar_dentro_del_margen_de_la_tolerancia_no_avisa() -> None:
    inside = [row for row in rows()[300:340]]
    assert {row["nivel"] for row in inside} == {"ON_GRADE"}
    assert all(row["aviso_nivel"] == "" for row in inside)
    values = [abs(float(row["d_cota_m"])) for row in inside]
    # Pasa de la tolerancia (3 cm) sin salir, porque el margen llega a 3,5 cm.
    assert max(values) > r.TOLERANCE_V_M and max(values) < r.TOLERANCE_V_M + r.MARGIN_M


def test_s019_rf11_sin_fix_la_guia_y_el_nivel_se_apagan_y_al_volver_empieza_de_cero() -> None:
    all_rows = rows()
    lost = all_rows[200:230]
    assert all(row["estado"] == "FLOAT" and row["guia"] == "NO_FIX" for row in lost)
    assert all(row["nivel"] == "" and row["aviso_nivel"] == "" and row["radio_m"] == "" for row in lost)
    assert [row["aviso"] for row in lost][0] == "PERDIDO"
    assert sum(1 for row in lost if row["aviso"]) == 1
    # Fuera de la guia no hay aviso de nivel; la primera época con FIX vuelve a calcular el estado desde cero.
    assert all_rows[230]["guia"] == "SI" and all_rows[230]["nivel"] == "FILL"


def test_s019_rf12_el_nivel_no_depende_de_la_distancia_horizontal_que_se_queda_a_1_m() -> None:
    for row in rows():
        if row["guia"] != "SI":
            continue
        assert abs(float(row["distancia_m"]) - 1.0) < 0.02, row["hora"]
        assert row["aviso"] == ""  # ni la zona de ayuda ni la tolerancia horizontal avisan a 1 m


def test_s019_rf7_el_radio_del_nivel_va_en_pasos_de_1_2_y_5_y_no_salta_de_ida_y_vuelta() -> None:
    steps = set(r.steps())
    radii = [float(row["radio_m"]) for row in rows() if row["guia"] == "SI"]
    assert set(radii) <= steps
    for row in rows():
        if row["guia"] == "SI":
            # La burbuja con su margen del 25 % siempre cabe.
            assert 1.25 * abs(float(row["d_cota_m"])) <= float(row["radio_m"]) + 1e-9, row["hora"]
    # Sin saltos de ida y vuelta: nunca A, B, A con B distinto de A en tres épocas seguidas.
    assert not any(a == c != b for a, b, c in zip(radii, radii[1:], radii[2:]))
    assert max(radii) == 5.0
    assert min(radii) == 0.05


def test_s019_rf7_el_radio_se_reinicia_al_volver_la_guia() -> None:
    all_rows = rows()
    # Antes del corte, a 1,2 m de terraplén, el radio era de 2 m; al volver, con la misma diferencia, se calcula de cero.
    assert float(all_rows[199]["radio_m"]) == float(all_rows[230]["radio_m"]) == 2.0


def test_s019_rf6_la_horizontal_es_la_de_un_punto_a_1_m_del_diseno_en_el_utm() -> None:
    east, north, _ = target()
    for epoch in epochs():
        d = math.hypot(east - epoch[2], north - epoch[3])
        assert abs(d - 1.0) < 0.9 or epoch[1] != "FIX", epoch[0]
