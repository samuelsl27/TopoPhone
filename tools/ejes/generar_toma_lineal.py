"""Tomas lineales de ejemplo y lo que debe salir de cada una (S052).

Genera en fixtures/ejes/toma/:

- puntos-eje.csv: los cinco puntos medidos de una toma de eje (nombre, Este, Norte y cota), los vértices de `pi-vertices.csv` de S049 sin
  clotoides.
- esperado-eje.csv: los tramos del eje tomado, por caso: `rectas` (sin radios, PK inicial 0) y `radios` (radios 0, 150, 200, 100 y 0 en los
  vértices, PK inicial 250), con el mismo formato que `esperado-pi.csv`.
- esperado-rasante.csv: la rasante de la toma con cota en los puntos (`radios`): el PK de cada vértice y su cota. El PK de un vértice con
  curva es el del punto medio del arco (el pie de la perpendicular desde el vértice cae en la bisectriz) y el de uno sin curva es el
  del final de la recta anterior.
- esperado-longitud.csv: la longitud acumulada en planta de los n primeros puntos.
- rechazos-eje.csv y esperado-rechazos.csv: tomas que no se pueden convertir en eje (`pocos_puntos`, `duplicado` a menos de 1 cm y
  `radio_no_cabe`) y el motivo y el vértice (desde 1) esperados.
- longitudinal-puntos.csv y esperado-longitudinal.csv: puntos del terreno a lo largo del Eje A de S049 (con rasante), con su PK,
  desplazamiento, cota de la rasante, diferencia y marca esperados, con la tolerancia de eje de 0,50 m.
- seccion-diseno.csv, transversal-puntos.csv y esperado-transversal.csv: puntos medidos en la sección del PK 105 del Eje C, con el error en
  PK, la diferencia de cota con la sección de diseño (interpolada, vacía fuera de ella), la marca con la tolerancia de sección de 0,10 m
  y el orden y el código con que quedan en el perfil de terreno.
- esperado-perfil-longitudinal.csv y esperado-perfil-transversal.csv: el CSV de exportación de los puntos que entran en cada perfil
  (S052/RF-15, RF-22): coma, punto decimal, CRLF, 4 decimales con redondeo hacia arriba en el empate y las cabeceras en inglés.

- reproduccion-longitudinal.csv/.nmea y reproduccion-transversal.csv/.nmea: reproducciones de un FIX por segundo con la punta recorriendo
  el Eje A (42 PK de 10 en 10 m desde el 5, con tres épocas a 0,75 m del eje y cuatro más allá del final) y la sección del PK 105 del
  Eje C (de -9,5 a +9,5 m de metro en metro, con tres épocas a 12, 15 y 20 cm del PK); ninguna cae justo en un extremo, donde el redondeo
  de la latitud y la longitud decidiría si el punto cae dentro o fuera; en cada época, lo esperado del perfil (PK, desplazamiento, cota de la referencia, diferencia
  de cota y marca), calculado por construcción en el plano con la posición que recupera el móvil de la latitud y la longitud escritas.

Cómo se calcula (otro algoritmo que el del móvil, para que la coincidencia no sea una tautología):
- Los puntos de los perfiles se generan desde su PK y su desplazamiento con la geometría de S049 (`generar_ejes`), así que lo esperado
  es el dato de partida y no el resultado de una proyección.
- Los tramos del eje tomado salen de las tangentes `T = R·tan(Δ/2)` de cada vértice y del encadenado de rectas y arcos.
- La interpolación con la sección de diseño se hace con `fractions`.

Uso: uv run --project tools tools/ejes/generar_toma_lineal.py
"""

from __future__ import annotations

import math
import sys
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_ejes as g  # noqa: E402

OUT = g.ROOT / "fixtures" / "ejes" / "toma"
A = g.A

FIELD_POINTS = (  # nombre, Este, Norte, cota
    ("T1", 440000.0, 4474000.0, 100.0),
    ("T2", 440300.0, 4474100.0, 101.5),
    ("T3", 440500.0, 4473900.0, 103.0),
    ("T4", 440900.0, 4473950.0, 102.0),
    ("T5", 441200.0, 4474300.0, 104.0),
)
RADII = (0.0, 150.0, 200.0, 100.0, 0.0)
START_PK = 250.0
AXIS_TOLERANCE = 0.50
SECTION_TOLERANCE = 0.10
SECTION_PK = 105.0


def azimuth(a, b) -> float:
    return math.atan2(b[0] - a[0], b[1] - a[1])


def built(points: list[tuple[float, float]], radii: list[float], start_pk: float):
    """(tramos, pk de cada vértice) de un eje por vértices con radios y sin clotoides, o (None, motivo, vértice)."""
    az = [azimuth(a, b) for a, b in zip(points, points[1:])]
    dist = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(points, points[1:])]
    tangents = [0.0] * len(points)
    curves = {}
    for i in range(1, len(points) - 1):
        if radii[i] == 0.0:
            continue
        delta = az[i - 1] - az[i]
        angle = abs(delta)
        if angle < 1e-9:
            continue
        tangents[i] = radii[i] * math.tan(angle / 2)
        curves[i] = (1.0 if delta > 0 else -1.0, angle, radii[i])
    for seg in range(len(points) - 1):
        if dist[seg] - tangents[seg] - tangents[seg + 1] < -1e-9:
            return None, "radio_no_cabe", seg + 2 if tangents[seg + 1] else seg + 1
    out: list[tuple[g.Element, g.State]] = []
    vertex_pk = [start_pk]
    state = g.State(points[0][0], points[0][1], math.pi / 2 - az[0], start_pk)
    for seg in range(len(points) - 1):
        line_len = dist[seg] - tangents[seg] - tangents[seg + 1]
        if line_len > 1e-9:
            e = g.Element("line", line_len)
            out.append((e, state))
            state = g.advance(state, e, e.length)
        nxt = seg + 1
        if nxt in curves:
            sign, angle, radius = curves[nxt]
            k = sign / radius
            e = g.Element("arc", radius * angle, k, k)
            out.append((e, state))
            vertex_pk.append(state.pk + e.length / 2)
            state = g.advance(state, e, e.length)
        else:
            vertex_pk.append(state.pk)
            if nxt < len(az):  # una esquina sin curva: la recta siguiente sale del vértice con su propio rumbo
                state = g.State(points[nxt][0], points[nxt][1], math.pi / 2 - az[nxt], state.pk)
    return out, vertex_pk


def element_rows(case: str, elements) -> list[str]:
    rows = []
    for n, (e, st) in enumerate(elements, 1):
        end = g.advance(st, e, e.length)
        rows.append(
            ",".join(
                [case, str(n), e.kind, A(st.pk), A(e.length), A(e.k0), A(e.k1), A(st.east), A(st.north), A(end.east), A(end.north)]
            )
        )
    return rows


def axis_files() -> dict[str, str]:
    pts = [(e, n) for _, e, n, _ in FIELD_POINTS]
    straight, _ = built(pts, [0.0] * 5, 0.0)
    curved, vertex_pk = built(pts, list(RADII), START_PK)
    assert straight is not None and curved is not None
    lines = ["caso,tramo,tipo,pk_inicio,longitud,k_inicio,k_fin,este_inicio,norte_inicio,este_fin,norte_fin"]
    lines += element_rows("rectas", straight) + element_rows("radios", curved)
    points = ["nombre,este,norte,cota"] + [f"{n},{A(e)},{A(no)},{A(z)}" for n, e, no, z in FIELD_POINTS]
    grade = ["vertice,pk,cota"] + [f"{i + 1},{A(pk)},{A(p[3])}" for i, (pk, p) in enumerate(zip(vertex_pk, FIELD_POINTS))]
    length = ["puntos,longitud"]
    total = 0.0
    for i in range(1, len(pts)):
        total += math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        length.append(f"{i + 1},{A(total)}")
    return {
        "puntos-eje.csv": "\n".join(points) + "\n",
        "esperado-eje.csv": "\n".join(lines) + "\n",
        "esperado-rasante.csv": "\n".join(grade) + "\n",
        "esperado-longitud.csv": "\n".join(length) + "\n",
    }


REJECTIONS = (
    ("pocos_puntos", [(440000.0, 4474000.0, 0.0)]),
    ("duplicado", [(440000.0, 4474000.0, 0.0), (440000.005, 4474000.0, 0.0), (440100.0, 4474000.0, 0.0)]),
    ("radio_no_cabe", [(440000.0, 4474000.0, 0.0), (440100.0, 4474000.0, 150.0), (440100.0, 4474100.0, 0.0)]),
)


def rejection_files() -> dict[str, str]:
    cases = ["caso,vertice,este,norte,radio"]
    expected = ["caso,motivo,vertice"]
    for name, vertices in REJECTIONS:
        for i, (e, n, r) in enumerate(vertices, 1):
            cases.append(f"{name},{i},{A(e)},{A(n)},{A(r)}")
        if name == "pocos_puntos":
            expected.append(f"{name},pocos_puntos,")
        elif name == "duplicado":
            gap = math.hypot(vertices[1][0] - vertices[0][0], vertices[1][1] - vertices[0][1])
            assert gap < 0.01
            expected.append(f"{name},duplicado,2")
        else:
            result = built([(e, n) for e, n, _ in vertices], [r for _, _, r in vertices], 0.0)
            assert result[0] is None, "el radio debería no caber"
            expected.append(f"{name},{result[1]},{result[2]}")
    return {"rechazos-eje.csv": "\n".join(cases) + "\n", "esperado-rechazos.csv": "\n".join(expected) + "\n"}


# nombre, PK, desplazamiento (positivo a la derecha), cota. Eje A de S049, PK 0 a 420; el último y el primero caen fuera de sus extremos.
LONGITUDINAL = (
    ("L1", 0.0, 0.0, 100.10),
    ("L2", 25.0, 0.20, 100.50),
    ("L3", 75.0, -0.30, 101.20),
    ("L4", 130.0, 0.10, 102.60),
    ("L5", 190.0, 0.40, 103.20),
    ("L6", 250.0, -0.45, 102.40),
    ("L7", 300.0, 0.0, 101.10),
    ("L8", 345.5, 0.80, 101.30),
    ("L9", 400.0, -0.10, 101.60),
    ("L10", 420.0, 0.25, 102.15),
    ("L11", 435.0, 0.0, 102.40),
    ("L12", -5.0, 0.0, 100.00),
)


def axis_a_point(pk: float, offset: float) -> tuple[float, float]:
    axis = g.AXIS_A
    length = g.length_of(axis)
    if pk > length:
        st, _ = g.point_at(axis, length)
        st = g.advance(st, g.Element("line", 1.0), pk - length)
    elif pk < axis.start_pk:
        st = g.advance(g.start_state(axis), g.Element("line", 1.0), pk - axis.start_pk)
    else:
        st, _ = g.point_at(axis, pk)
    return g.offset_point(st, offset)


def longitudinal_files() -> dict[str, str]:
    points = ["nombre,este,norte,cota"]
    expected = ["nombre,pk,desplazamiento,cota_rasante,d_cota,marca"]
    for name, pk, d, z in LONGITUDINAL:
        e, n = axis_a_point(pk, d)
        points.append(f"{name},{A(e)},{A(n)},{A(z)}")
        if pk < 0.0 or pk > g.length_of(g.AXIS_A):
            expected.append(f"{name},,,,,sin_proyeccion")
            continue
        grade = g.profile_at(g.AXIS_A.profile, pk)[0]
        flag = "fuera_del_eje" if abs(d) > AXIS_TOLERANCE else "ninguna"
        expected.append(f"{name},{A(pk)},{A(d)},{A(grade)},{A(z - grade)},{flag}")
    return {"longitudinal-puntos.csv": "\n".join(points) + "\n", "esperado-longitudinal.csv": "\n".join(expected) + "\n"}


DESIGN = ((-10.0, 99.0), (-3.5, 100.0), (0.0, 100.5), (3.5, 100.0), (10.0, 99.0))
# nombre, PK, desplazamiento, cota. Eje C de S049: recta al Este desde 441000; la derecha es el Sur.
CROSS = (
    ("X1", 105.00, 8.0, 99.40),
    ("X2", 105.02, 3.5, 100.10),
    ("X3", 104.95, -2.0, 100.30),
    ("X4", 105.15, 5.0, 99.90),
    ("X5", 105.00, -6.0, 99.00),
    ("X6", 105.00, 0.0, 100.45),
    ("X7", 105.00, -12.0, 98.00),
    ("X8", 104.85, 1.0, 100.60),
)


def design_at(d: Fraction) -> Fraction | None:
    for (d0, z0), (d1, z1) in zip(DESIGN, DESIGN[1:]):
        a, b = Fraction(str(d0)), Fraction(str(d1))
        if a <= d <= b:
            za, zb = Fraction(str(z0)), Fraction(str(z1))
            return za + (zb - za) * (d - a) / (b - a)
    return None


def cross_files() -> dict[str, str]:
    design = ["desplazamiento,cota"] + [f"{A(d)},{A(z)}" for d, z in DESIGN]
    points = ["nombre,este,norte,cota"]
    expected = ["nombre,error_pk,desplazamiento,d_cota,marca,orden,codigo"]
    included = []
    for name, pk, d, z in CROSS:
        points.append(f"{name},{A(g.AXIS_C.east + pk)},{A(g.AXIS_C.north - d)},{A(z)}")
        if abs(pk - SECTION_PK) <= SECTION_TOLERANCE + 1e-12:
            included.append((d, name))
    order = {name: (i + 1) for i, (_, name) in enumerate(sorted(included))}
    for name, pk, d, z in CROSS:
        error = Fraction(str(pk)) - Fraction(str(SECTION_PK))
        flag = "ninguna" if name in order else "fuera_de_seccion"
        base = design_at(Fraction(str(d)))
        dz = "" if base is None else A(float(Fraction(str(z)) - base))
        n = order.get(name)
        expected.append(f"{name},{A(float(error))},{A(d)},{dz},{flag},{n or ''},{('P' + str(n)) if n else ''}")
    return {
        "seccion-diseno.csv": "\n".join(design) + "\n",
        "transversal-puntos.csv": "\n".join(points) + "\n",
        "esperado-transversal.csv": "\n".join(expected) + "\n",
    }


def num(x: float | Fraction | None) -> str:
    if x is None:
        return ""
    exact = Decimal(x.numerator) / Decimal(x.denominator) if isinstance(x, Fraction) else Decimal(x)
    text = str(exact.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))
    return "0.0000" if float(text) == 0.0 else text


def profile_csv_files() -> dict[str, str]:
    longitudinal = ["Chainage,Offset,Ground elevation,Grade elevation,Delta elevation"]
    for name, pk, d, z in LONGITUDINAL:
        if pk < 0.0 or pk > g.length_of(g.AXIS_A) or abs(d) > AXIS_TOLERANCE:
            continue
        grade = g.profile_at(g.AXIS_A.profile, pk)[0]
        longitudinal.append(",".join([num(pk), num(d), num(z), num(grade), num(z - grade)]))
    cross = ["Code,Chainage,Offset,Ground elevation,Design elevation,Delta elevation"]
    kept = sorted((d, pk, z) for _, pk, d, z in CROSS if abs(pk - SECTION_PK) <= SECTION_TOLERANCE + 1e-12)
    for n, (d, pk, z) in enumerate(kept, 1):
        base = design_at(Fraction(str(d)))
        zf = Fraction(str(z))
        cross.append(",".join([f"P{n}", num(pk), num(d), num(z), num(base), num(None if base is None else zf - base)]))
    crlf = chr(13) + chr(10)
    return {
        "esperado-perfil-longitudinal.csv": crlf.join(longitudinal) + crlf,
        "esperado-perfil-transversal.csv": crlf.join(cross) + crlf,
    }


LONG_EPOCHS = 46
LONG_WIDE = (10, 11, 12)  # épocas a 0,75 m del eje: fuera de la tolerancia de 0,50 m
CROSS_PKS = {3: 105.15, 8: 105.2, 14: 104.88}  # épocas fuera de la tolerancia de sección de 0,10 m
CROSS_EPOCHS = 20


def geo_transformers():
    from pyproj import Transformer

    return (
        Transformer.from_crs("EPSG:25830", "EPSG:4258", always_xy=True),
        Transformer.from_crs("EPSG:4258", "EPSG:25830", always_xy=True),
    )


def recovered(to_geo, e: float, n: float) -> tuple[float, float]:
    """La latitud y la longitud con 11 decimales, como se escriben en el fichero."""
    lon, lat = to_geo.transform(e, n)
    return float(f"{lat:.11f}"), float(f"{lon:.11f}")


def long_epoch(i: int) -> tuple[float, float, float]:
    """(PK, desplazamiento, cota de la punta) de la época i del recorrido del Eje A; después del final del eje, nada que proyectar."""
    pk = 10.0 * i + 5.0
    offset = 0.75 if i in LONG_WIDE else round(0.2 * math.sin(i / 3.0), 3)
    if pk > g.length_of(g.AXIS_A):
        return pk, offset, round(102.0 + 0.05 * math.cos(i / 2.0), 3)
    return pk, offset, round(g.profile_at(g.AXIS_A.profile, pk)[0] + 0.05 * math.cos(i / 2.0), 3)


def long_replay_rows() -> list[str]:
    to_geo, _ = geo_transformers()
    rows = ["epoca,pk,desplazamiento,lat,lon,altura,cota_rasante,d_cota,marca"]
    for i in range(LONG_EPOCHS):
        pk, offset, height = long_epoch(i)
        e, n = axis_a_point(pk, offset)
        lat, lon = recovered(to_geo, e, n)
        if pk > g.length_of(g.AXIS_A):
            rows.append(",".join([str(i + 1), "", "", f"{lat:.11f}", f"{lon:.11f}", f"{height:.3f}", "", "", "sin_proyeccion"]))
            continue
        grade = g.profile_at(g.AXIS_A.profile, pk)[0]
        flag = "fuera_del_eje" if abs(offset) > AXIS_TOLERANCE else "ninguna"
        rows.append(",".join([str(i + 1), A(pk), A(offset), f"{lat:.11f}", f"{lon:.11f}", f"{height:.3f}", A(grade), A(height - grade), flag]))
    return rows


def cross_epoch(i: int) -> tuple[float, float, float]:
    """(PK, desplazamiento, cota de la punta) de la época i de la sección: de -9,5 a +9,5 m, de metro en metro."""
    offset = -9.5 + i
    pk = CROSS_PKS.get(i, SECTION_PK + (0.04 if i % 2 else -0.03))
    design = design_at(Fraction(str(offset)))
    return pk, offset, round(float(design) + 0.12 * math.sin(i / 2.0), 3)


def cross_replay_rows() -> list[str]:
    to_geo, _ = geo_transformers()
    rows = ["epoca,pk,desplazamiento,lat,lon,altura,error_pk,cota_diseno,d_cota,marca"]
    for i in range(CROSS_EPOCHS):
        pk, offset, height = cross_epoch(i)
        lat, lon = recovered(to_geo, g.AXIS_C.east + pk, g.AXIS_C.north - offset)
        error = Fraction(str(pk)) - Fraction(str(SECTION_PK))
        flag = "ninguna" if abs(error) <= Fraction(str(SECTION_TOLERANCE)) else "fuera_de_seccion"
        base = design_at(Fraction(str(offset)))
        reference = "" if base is None else A(float(base))
        dz = "" if base is None else A(float(Fraction(str(height)) - base))
        rows.append(",".join([str(i + 1), A(pk), A(offset), f"{lat:.11f}", f"{lon:.11f}", f"{height:.3f}", A(float(error)), reference, dz, flag]))
    return rows


def replay_nmea(rows: list[str]) -> str:
    """Las épocas de una reproducción como GGA y GST de un FIX por segundo desde las 10:00:00 UTC, con la antena sobre la punta."""
    out = []
    for row in rows[1:]:
        cells = row.split(",")
        i = int(cells[0]) - 1
        lat, lon, height = float(cells[3]), float(cells[4]), float(cells[5])
        time = f"10{i // 60:02d}{i % 60:02d}.00"
        alt = f"{height + g.ANTENNA_ABOVE_TIP_M:.3f}"
        out.append(g.nmea_sentence(f"GNGGA,{time},{g.nmea_angle(lat, 2)},N,{g.nmea_angle(-lon, 3)},W,4,24,0.6,{alt},M,0.000,M,1.0,0001"))
        out.append(g.nmea_sentence(f"GNGST,{time},0.012,0.010,0.007,45.0,0.008,0.006,0.015"))
    return "".join(out)


def replay_files() -> dict[str, str]:
    long_rows = long_replay_rows()
    cross_rows = cross_replay_rows()
    return {
        "reproduccion-longitudinal.csv": "\n".join(long_rows) + "\n",
        "reproduccion-longitudinal.nmea": replay_nmea(long_rows),
        "reproduccion-transversal.csv": "\n".join(cross_rows) + "\n",
        "reproduccion-transversal.nmea": replay_nmea(cross_rows),
    }


def all_files() -> dict[str, str]:
    return {**axis_files(), **rejection_files(), **longitudinal_files(), **cross_files(), **profile_csv_files(), **replay_files()}


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_bytes(text.encode("utf-8"))
    print(f"{len(all_files())} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
