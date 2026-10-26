"""La reproducción sintética de la superficie está al día y tiene lo que promete su generador (S042)."""

from __future__ import annotations

import sys
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_sintetico as g  # noqa: E402
import generar_superficie_rs3 as r  # noqa: E402
from pyproj import Transformer  # noqa: E402

UTM = Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True)
ANTENNA_HEIGHT = g.POLE_M + g.RS3_BASE_TO_REFERENCE_M
HEADER = ["hora", "estado", "este", "norte", "h", "guia", "cota_sup", "estado_sup", "d_cota", "nivel", "aviso_nivel", "aviso"]
SURFACES = r.gs.OUT / "dos-superficies.xml"


def lines() -> list[str]:
    return [line.decode("ascii") for line in (g.OUT / r.NAME).read_bytes().split(b"\r\n") if line]


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
    text = (g.OUT / r.CSV).read_text(encoding="utf-8")
    body = [line.split(",") for line in text.splitlines() if line and not line.startswith(("#", "hora,"))]
    return [dict(zip(HEADER, row)) for row in body]


def terrain() -> tuple[dict[str, tuple[float, float, float]], list[tuple[str, str, str]]]:
    """El terreno del XML: los puntos (Este, Norte, cota) y las caras visibles. Otra lectura que la del generador de la superficie."""
    root = ElementTree.fromstring(SURFACES.read_text(encoding="utf-8"))
    ns = {"l": "http://www.landxml.org/schema/LandXML-1.2"}
    surface = next(s for s in root.findall(".//l:Surface", ns) if s.get("name") == "terreno")
    points = {}
    for p in surface.findall(".//l:Pnts/l:P", ns):
        northing, easting, height = (float(v) for v in p.text.split())
        points[p.get("id")] = (easting, northing, height)
    faces = [tuple(f.text.split()) for f in surface.findall(".//l:Faces/l:F", ns) if f.get("i") != "1"]
    return points, faces  # type: ignore[return-value]


def height_at(east: float, north: float) -> float | None:
    """La cota por coordenadas baricéntricas del triángulo que contiene el punto; None fuera del contorno y en el hueco."""
    points, faces = terrain()
    for a, b, c in faces:
        (ea, na, za), (eb, nb, zb), (ec, nc, zc) = points[a], points[b], points[c]
        det = (nb - nc) * (ea - ec) + (ec - eb) * (na - nc)
        w1 = ((nb - nc) * (east - ec) + (ec - eb) * (north - nc)) / det
        w2 = ((nc - na) * (east - ec) + (ea - ec) * (north - nc)) / det
        w3 = 1 - w1 - w2
        if min(w1, w2, w3) >= -1e-9:
            return w1 * za + w2 * zb + w3 * zc
    return None


def test_s042_los_ficheros_guardados_son_los_que_genera_el_script() -> None:
    for name, content in r.generate().items():
        path = g.OUT / name
        if isinstance(content, bytes):
            assert path.read_bytes() == content, name
        else:
            assert g.same_csv(path.read_text(encoding="utf-8"), content), name


def test_s042_las_frases_tienen_suma_correcta() -> None:
    sentences = lines()
    assert all(g.checksum(line[1:].split("*")[0]) == line.split("*")[1] for line in sentences)
    assert {line[3:6] for line in sentences} == {"GGA", "GST", "RMC", "ZDA"}


def test_s042_hay_360_epocas_a_5_hz_y_una_fila_por_epoca() -> None:
    assert len(epochs()) == r.EPOCHS == len(rows()) == 360
    times = [r.millis(e[0]) for e in epochs()]
    assert all(b - a == 200 for a, b in zip(times, times[1:]))


def test_s042_la_cota_de_la_superficie_sale_del_terreno_en_la_posicion_de_la_gga() -> None:
    """Comprobación independiente: la cota y la diferencia desde el XML, sin las funciones del generador de la superficie."""
    for epoch, row in zip(epochs(), rows()):
        if row["guia"] != "SI":
            assert row["cota_sup"] == "" and row["estado_sup"] == "", row["hora"]
            continue
        expected = height_at(epoch[2], epoch[3])
        if expected is None:
            assert row["estado_sup"] == "FUERA" and row["cota_sup"] == "" and row["d_cota"] == "", row["hora"]
        else:
            assert row["estado_sup"] == "VALOR", row["hora"]
            assert abs(float(row["cota_sup"]) - expected) < 1e-6, row["hora"]
            assert abs(float(row["d_cota"]) - (epoch[4] - (expected + r.OFFSET_M))) < 1e-6, row["hora"]


def test_s042_el_recorrido_empieza_fuera_cruza_el_hueco_y_sale_por_el_otro_lado() -> None:
    states = [row["estado_sup"] for row in rows() if row["guia"] == "SI"]
    compact = [s for i, s in enumerate(states) if i == 0 or s != states[i - 1]]
    assert compact == ["FUERA", "VALOR", "FUERA", "VALOR", "FUERA"]  # oeste, terreno, hueco, terreno, este


def test_s042_hay_cuatro_segundos_sin_fix_y_el_aviso_de_perdido() -> None:
    lost = [row for row in rows() if row["guia"] == "NO_FIX"]
    assert len(lost) == len(r.LOSS) == 20
    assert [row["aviso"] for row in rows() if row["aviso"]] == ["PERDIDO"]


def test_s042_el_nivel_pasa_de_desmonte_a_en_cota_y_a_terraplen_y_vuelve_a_empezar_tras_el_hueco() -> None:
    levels = [(row["nivel"], row["aviso_nivel"]) for row in rows() if row["nivel"]]
    kinds = [level for level, _ in levels]
    compact = [k for i, k in enumerate(kinds) if i == 0 or k != kinds[i - 1]]
    assert compact == ["CUT", "ON_GRADE", "FILL"]
    alerts = [alert for _, alert in levels if alert]
    # Entrar en cota, y salir de ella; tras el hueco el nivel empieza de cero y vuelve a avisar al estar en cota.
    assert alerts.count("LEVEL_ARRIVED") == 3  # al entrar en cota, tras el corte de FIX y tras el hueco
    assert "LEVEL_LEFT" in alerts
