"""Ejes y rasantes de ejemplo y lo que debe salir de cada uno (S049).

Genera en fixtures/ejes/ los LandXML (rectas, arcos y clotoides; rasantes con acuerdos parabólicos y circulares; casos que se rechazan),
un CSV de rasante y los ficheros de lo esperado:

- esperado-tramos.csv: por eje y tramo, el PK de inicio, la longitud, las curvaturas de inicio y de fin (positiva a la izquierda), los
  puntos de inicio y de fin y el azimut de inicio en grados.
- esperado-puntos.csv: por eje, PK y desplazamiento (negativo a la izquierda, positivo a la derecha): Este, Norte, azimut en grados, radio
  del eje (`inf` en una recta), cota y pendiente en porcentaje (vacías si el eje no tiene rasante). La Kotlin los lee también al revés (de
  la posición al PK y al desplazamiento).
- esperado-rasantes.csv: por eje y vértice de la rasante, PK, cota, el acuerdo (`none`, `para` o `circ`), su longitud horizontal (parábola)
  o la de su arco (círculo) y su radio.
- pi-vertices.csv y esperado-pi.csv: un eje dado por vértices con su radio y la longitud de sus clotoides (RF-8), sus tramos con inicio y
  fin (PK inicial 250), calculados por geometría de tangentes y comprobados en el propio script.
- esperado-crs.csv: los puntos del eje A, escrito en ETRS89 UTM 31, en ETRS89 UTM 30 con pyproj (RF-11).
- esperado-resumen.csv: por fichero y alineación, longitud, PK inicial, si trae rasante y el estado que debe dar la importación.

Cómo se calcula (otro algoritmo que el del móvil, para que la coincidencia no sea una tautología):
- La recta y el arco, en forma cerrada. La clotoide, con el desarrollo en serie de las integrales de Fresnel de la clotoide completa:
  un tramo de curvatura k0 a k1 es el trozo [u0, u0+s] de la clotoide de pendiente c = (k1 - k0) / L, con u0 = k0 / c, llevado a su
  rumbo de inicio. El móvil integra por cuadratura de Gauss-Legendre.
- La rasante: pendiente entre vértices; parábola simétrica de longitud Kv·|Δi| (z = z0 + g1·x + (g2 - g1)·x²/(2L)); acuerdo circular de
  radio R tangente a las dos rasantes, con la circunferencia exacta (z = zc ± sqrt(R² - (x - xc)²)).
- El azimut es horario desde el Norte; el desplazamiento positivo es a la derecha en el sentido de los PK crecientes.
- En LandXML las coordenadas van Norte Este y los ángulos en grados decimales.

Uso: uv run --project tools tools/ejes/generar_ejes.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures" / "ejes"
NS = "http://www.landxml.org/schema/LandXML-1.2"
FOOT = 0.3048
OFFSETS = (-3.5, 0.0, 3.5)


@dataclass(frozen=True)
class Element:
    kind: str  # line | arc | spiral
    length: float
    k0: float = 0.0
    k1: float = 0.0


@dataclass(frozen=True)
class Vertex:
    pk: float
    z: float
    curve: tuple | None = None  # ("para", Kv) | ("circ", radio)


@dataclass(frozen=True)
class Axis:
    name: str
    east: float
    north: float
    azimuth_deg: float
    start_pk: float
    elements: tuple[Element, ...]
    profile: tuple[Vertex, ...] = ()


def A(a: float) -> str:
    return repr(float(a))


# ---------------------------------------------------------------------------------------------------------------------- planta
def fresnel(c: float, u: float) -> tuple[float, float]:
    """∫0..u de (cos, sen)(c·v²/2) dv por series (par e impar en u, valen con u negativo)."""
    x = y = 0.0
    h = c / 2.0
    for n in range(40):
        x += (-1) ** n * h ** (2 * n) * u ** (4 * n + 1) / (math.factorial(2 * n) * (4 * n + 1))
        y += (-1) ** n * h ** (2 * n + 1) * u ** (4 * n + 3) / (math.factorial(2 * n + 1) * (4 * n + 3))
    return x, y


def local(e: Element, s: float) -> tuple[float, float, float]:
    """Desplazamiento (dx, dy) y giro (rad, antihorario) tras s metros del tramo, con el rumbo de inicio sobre +x."""
    if e.kind == "line" or (e.k0 == 0.0 and e.k1 == 0.0):
        return s, 0.0, 0.0
    if e.kind == "arc":
        k = e.k0
        return math.sin(k * s) / k, (1 - math.cos(k * s)) / k, k * s
    c = (e.k1 - e.k0) / e.length
    u0 = e.k0 / c
    fx0, fy0 = fresnel(c, u0)
    fx1, fy1 = fresnel(c, u0 + s)
    vx, vy = fx1 - fx0, fy1 - fy0
    psi0 = c * u0 * u0 / 2.0
    ca, sa = math.cos(-psi0), math.sin(-psi0)
    return vx * ca - vy * sa, vx * sa + vy * ca, c * (u0 + s) ** 2 / 2.0 - psi0


def curvature(e: Element, s: float) -> float:
    return e.k0 + (e.k1 - e.k0) * s / e.length


@dataclass(frozen=True)
class State:
    east: float
    north: float
    heading: float  # rad, antihorario desde el Este
    pk: float

    @property
    def azimuth(self) -> float:
        return math.pi / 2 - self.heading


def advance(state: State, e: Element, s: float) -> State:
    dx, dy, dh = local(e, s)
    ch, sh = math.cos(state.heading), math.sin(state.heading)
    return State(state.east + dx * ch - dy * sh, state.north + dx * sh + dy * ch, state.heading + dh, state.pk + s)


def start_state(axis: Axis) -> State:
    return State(axis.east, axis.north, math.pi / 2 - math.radians(axis.azimuth_deg), axis.start_pk)


def boundaries(axis: Axis) -> list[State]:
    states = [start_state(axis)]
    for e in axis.elements:
        states.append(advance(states[-1], e, e.length))
    return states


def length_of(axis: Axis) -> float:
    return sum(e.length for e in axis.elements)


def point_at(axis: Axis, pk: float) -> tuple[State, float]:
    """El estado del eje en [pk] y la curvatura ahí."""
    states = boundaries(axis)
    for e, st in zip(axis.elements, states):
        if pk <= st.pk + e.length + 1e-9:
            s = min(max(pk - st.pk, 0.0), e.length)
            return advance(st, e, s), curvature(e, s)
    raise ValueError(f"PK {pk} fuera del eje {axis.name}")


def offset_point(state: State, d: float) -> tuple[float, float]:
    az = state.azimuth
    return state.east + d * math.cos(az), state.north - d * math.sin(az)


# --------------------------------------------------------------------------------------------------------------------- rasante
def _grades(vs: tuple[Vertex, ...]) -> list[float]:
    return [(b.z - a.z) / (b.pk - a.pk) for a, b in zip(vs, vs[1:])]


def _curve_span(vs: tuple[Vertex, ...], i: int) -> tuple[float, float, dict] | None:
    """Inicio y fin (PK horizontal) del acuerdo del vértice i, y lo que hace falta para evaluarlo."""
    v = vs[i]
    if v.curve is None:
        return None
    g = _grades(vs)
    g1, g2 = g[i - 1], g[i]
    if v.curve[0] == "para":
        length = v.curve[1] * abs(g2 - g1)
        return v.pk - length / 2, v.pk + length / 2, {"kind": "para", "g1": g1, "g2": g2, "length": length}
    r = v.curve[1]
    a1, a2 = math.atan(g1), math.atan(g2)
    t = r * math.tan(abs(a2 - a1) / 2)
    x1, x2 = v.pk - t * math.cos(a1), v.pk + t * math.cos(a2)
    z1 = v.z - t * math.sin(a1)
    crest = a2 < a1
    nx, ny = (math.sin(a1), -math.cos(a1)) if crest else (-math.sin(a1), math.cos(a1))
    return x1, x2, {"kind": "circ", "r": r, "xc": x1 + r * nx, "zc": z1 + r * ny, "crest": crest, "length": r * abs(a2 - a1), "z1": z1, "g1": g1}


def profile_at(vs: tuple[Vertex, ...], pk: float) -> tuple[float, float]:
    """Cota y pendiente (m/m) de la rasante en [pk]."""
    for i in range(1, len(vs) - 1):
        span = _curve_span(vs, i)
        if span is None or not (span[0] <= pk <= span[1]):
            continue
        x1, _, d = span
        if d["kind"] == "para":
            g1, g2, length = d["g1"], d["g2"], d["length"]
            z1 = vs[i].z - g1 * (vs[i].pk - x1)
            x = pk - x1
            return z1 + g1 * x + (g2 - g1) * x * x / (2 * length), g1 + (g2 - g1) * x / length
        root = math.sqrt(d["r"] ** 2 - (pk - d["xc"]) ** 2)
        sign = 1.0 if d["crest"] else -1.0
        z = d["zc"] + sign * root
        return z, -(pk - d["xc"]) / (z - d["zc"])
    for a, b in zip(vs, vs[1:]):
        if a.pk <= pk <= b.pk:
            g = (b.z - a.z) / (b.pk - a.pk)
            return a.z + g * (pk - a.pk), g
    raise ValueError(f"PK {pk} fuera de la rasante")


# ----------------------------------------------------------------------------------------------------------------------- ejes
AXIS_A = Axis(
    "Eje A", 440000.0, 4474000.0, 60.0, 0.0,
    (
        Element("line", 120.0),
        Element("spiral", 60.0, 0.0, -1 / 150),
        Element("arc", 100.0, -1 / 150, -1 / 150),
        Element("spiral", 60.0, -1 / 150, 0.0),
        Element("line", 80.0),
    ),
    (Vertex(0.0, 100.0), Vertex(150.0, 103.0, ("para", 3000.0)), Vertex(300.0, 101.0, ("circ", 2000.0)), Vertex(420.0, 102.0)),
)
AXIS_B = Axis(
    "Eje B", 440500.0, 4474300.0, 200.0, 1000.5,
    (
        Element("line", 50.0),
        Element("spiral", 40.0, 0.0, 1 / 100),
        Element("arc", 50.0, 1 / 100, 1 / 100),
        Element("spiral", 40.0, 1 / 100, 0.0),
        Element("spiral", 40.0, 0.0, -1 / 120),
        Element("arc", 60.0, -1 / 120, -1 / 120),
        Element("spiral", 40.0, -1 / 120, 0.0),
        Element("line", 60.0),
    ),
    (Vertex(1000.5, 50.0), Vertex(1200.0, 52.0, ("para", 2000.0)), Vertex(1380.5, 49.0)),
)
AXIS_C = Axis("Eje C", 441000.0, 4474000.0, 90.0, 0.0, (Element("line", 200.0),))
AXES = (AXIS_A, AXIS_B, AXIS_C)


# ------------------------------------------------------------------------------------------- definición por vértices (RF-8)
PI_VERTICES = (
    (440000.0, 4474000.0, 0.0, 0.0),
    (440300.0, 4474100.0, 150.0, 60.0),
    (440500.0, 4473900.0, 200.0, 0.0),
    (440900.0, 4473950.0, 100.0, 40.0),
    (441200.0, 4474300.0, 0.0, 0.0),
)
PI_START_PK = 250.0


def pi_elements() -> list[tuple[Element, State]]:
    """Los tramos de un eje dado por vértices con radio y clotoides simétricas, con su estado de inicio.

    Cada curva se coloca por geometría: el inicio de la entrada está a la tangente T del vértice, con T = (R + p)·tan(Δ/2) + k, y se
    comprueba que el final de la salida cae sobre la alineación siguiente a T del vértice (el cálculo de T es independiente del encadenado).
    """
    pts = PI_VERTICES
    az = [math.atan2(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:])]
    dist = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:])]
    tangents = [0.0] * len(pts)
    curves = {}
    for i in range(1, len(pts) - 1):
        _, _, radius, ls = pts[i]
        delta = az[i - 1] - az[i]  # positivo a la izquierda
        sign = 1.0 if delta > 0 else -1.0
        ang = abs(delta)
        if radius == 0.0:
            continue
        theta_s = ls / (2 * radius)
        p = k = 0.0
        if ls > 0:
            sx, sy, _ = local(Element("spiral", ls, 0.0, 1 / radius), ls)
            p, k = sy - radius * (1 - math.cos(theta_s)), sx - radius * math.sin(theta_s)
        tangents[i] = (radius + p) * math.tan(ang / 2) + k
        curves[i] = (sign, ang, radius, ls, theta_s)
    out: list[tuple[Element, State]] = []
    pk = PI_START_PK
    state = State(pts[0][0], pts[0][1], math.pi / 2 - az[0], pk)
    for seg in range(len(pts) - 1):
        line_len = dist[seg] - tangents[seg] - tangents[seg + 1]
        assert line_len >= 0, "las tangentes se solapan"
        if line_len > 1e-9:
            e = Element("line", line_len)
            out.append((e, state))
            state = advance(state, e, e.length)
        nxt = seg + 1
        if nxt in curves:
            sign, ang, radius, ls, theta_s = curves[nxt]
            k = sign / radius
            parts = []
            if ls > 0:
                parts.append(Element("spiral", ls, 0.0, k))
            parts.append(Element("arc", radius * (ang - 2 * theta_s), k, k))
            if ls > 0:
                parts.append(Element("spiral", ls, k, 0.0))
            for e in parts:
                out.append((e, state))
                state = advance(state, e, e.length)
            end_vertex = (pts[nxt][0] + tangents[nxt] * math.sin(az[nxt]), pts[nxt][1] + tangents[nxt] * math.cos(az[nxt]))
            assert math.hypot(state.east - end_vertex[0], state.north - end_vertex[1]) < 1e-8, "la salida no cae sobre la alineación"
    return out


def sample_pks(axis: Axis) -> list[float]:
    states = boundaries(axis)
    pks = [st.pk for st in states]
    pks += [st.pk + e.length / 2 for e, st in zip(axis.elements, states)]
    pks += [axis.start_pk + 0.37, axis.start_pk + length_of(axis) - 0.37]
    return sorted(set(pks))


# -------------------------------------------------------------------------------------------------------------------- LandXML
def scaled(value: float, unit: float) -> float:
    return value / unit


def xml_coord(e: float, n: float, unit: float) -> str:
    return f"{A(scaled(n, unit))} {A(scaled(e, unit))}"


def rot_of(k: float) -> str:
    return "ccw" if k > 0 else "cw"


def element_xml(el: Element, st: State, end: State, unit: float) -> str:
    az0, az1 = math.degrees(st.azimuth), math.degrees(end.azimuth)
    start = f"<Start>{xml_coord(st.east, st.north, unit)}</Start>"
    stop = f"<End>{xml_coord(end.east, end.north, unit)}</End>"
    length = A(scaled(el.length, unit))
    if el.kind == "line":
        return f'<Line dir="{A(az0)}" length="{length}">{start}{stop}</Line>'
    if el.kind == "arc":
        r = 1 / abs(el.k0)
        sign = 1 if el.k0 > 0 else -1
        # el centro está a la izquierda (k > 0) o a la derecha (k < 0) del rumbo
        cx = st.east - sign * r * math.sin(st.heading)
        cy = st.north + sign * r * math.cos(st.heading)
        return (
            f'<Curve rot="{rot_of(el.k0)}" crvType="arc" radius="{A(scaled(r, unit))}" length="{length}" '
            f'dirStart="{A(az0)}" dirEnd="{A(az1)}">{start}<Center>{xml_coord(cx, cy, unit)}</Center>{stop}</Curve>'
        )
    r0 = "INF" if el.k0 == 0 else A(scaled(1 / abs(el.k0), unit))
    r1 = "INF" if el.k1 == 0 else A(scaled(1 / abs(el.k1), unit))
    turning = el.k1 if el.k1 != 0 else el.k0
    return (
        f'<Spiral length="{length}" radiusStart="{r0}" radiusEnd="{r1}" rot="{rot_of(turning)}" spiType="clothoid" '
        f'dirStart="{A(az0)}" dirEnd="{A(az1)}">{start}{stop}</Spiral>'
    )


def profile_xml(axis: Axis, unit: float, curve_tag: str | None = None) -> str:
    out = []
    for i, v in enumerate(axis.profile):
        pos = f"{A(scaled(v.pk, unit))} {A(scaled(v.z, unit))}"
        if v.curve is None:
            out.append(f"<PVI>{pos}</PVI>")
        elif curve_tag:
            out.append(f'<{curve_tag} lengthIn="1.0" lengthOut="1.0">{pos}</{curve_tag}>')
        elif v.curve[0] == "para":
            out.append(f'<ParaCurve length="{A(scaled(_curve_span(axis.profile, i)[2]["length"], unit))}">{pos}</ParaCurve>')
        else:
            d = _curve_span(axis.profile, i)[2]
            out.append(f'<CircCurve length="{A(scaled(d["length"], unit))}" radius="{A(scaled(v.curve[1], unit))}">{pos}</CircCurve>')
    return f'<Profile name="Perfil"><ProfAlign name="Rasante">{"".join(out)}</ProfAlign></Profile>' if out else ""


def alignment_xml(axis: Axis, unit: float = 1.0, *, profile: str | None = None, extra: str = "", mutate=None, sta_start: float | None = None) -> str:
    states = boundaries(axis)
    parts = []
    for i, el in enumerate(axis.elements):
        text = element_xml(el, states[i], states[i + 1], unit)
        parts.append(mutate(i, text) if mutate else text)
    prof = profile if profile is not None else profile_xml(axis, unit)
    start = axis.start_pk if sta_start is None else sta_start
    return (
        f'<Alignment name="{axis.name}" length="{A(scaled(length_of(axis), unit))}" staStart="{A(scaled(start, unit))}">'
        f'{extra}<CoordGeom>{"".join(parts)}</CoordGeom>{prof}</Alignment>'
    )


def landxml(alignments: list[str], unit: str = "metric") -> str:
    units = (
        '<Metric areaUnit="squareMeter" linearUnit="meter" volumeUnit="cubicMeter" angularUnit="decimal degrees" directionUnit="decimal degrees"/>'
        if unit == "metric"
        else '<Imperial areaUnit="squareFoot" linearUnit="foot" volumeUnit="cubicFeet" angularUnit="decimal degrees" directionUnit="decimal degrees"/>'
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<LandXML xmlns="{NS}" version="1.2" date="2026-10-27" time="00:00:00">\n'
        f"<Units>{units}</Units>\n<Alignments>\n" + "\n".join(alignments) + "\n</Alignments>\n</LandXML>\n"
    )


def shifted_gap(i: int, text: str) -> str:
    """Corre 5 cm al Norte el inicio del segundo tramo (un hueco de más de 1 cm)."""
    if i != 1:
        return text
    head, _, rest = text.partition("<Start>")
    coords, _, tail = rest.partition("</Start>")
    north, east = coords.split()
    return f"{head}<Start>{A(float(north) + 0.05)} {east}</Start>{tail}"


def files() -> dict[str, str]:
    foot = FOOT
    out = {
        "ejes.xml": landxml([alignment_xml(AXIS_A), alignment_xml(AXIS_B), alignment_xml(AXIS_C)]),
        "eje-a-pies.xml": landxml([alignment_xml(AXIS_A, foot)], unit="imperial"),
        "eje-hueco.xml": landxml([alignment_xml(AXIS_A, mutate=shifted_gap)]),
        "eje-irregular.xml": landxml(
            [alignment_xml(AXIS_C, mutate=lambda i, t: '<IrregularLine length="200.0"><Start>4474000.0 441000.0</Start><End>4474000.0 441200.0</End></IrregularLine>')]
        ),
        "eje-espiral-cubica.xml": landxml([alignment_xml(AXIS_A, mutate=lambda i, t: t.replace('spiType="clothoid"', 'spiType="cubic"'))]),
        "eje-ecuacion-pk.xml": landxml(
            [alignment_xml(AXIS_C, extra='<StaEquation staAhead="500.0" staBack="200.0" staInternal="200.0"/>')]
        ),
        "eje-acuerdo-asimetrico.xml": landxml([alignment_xml(AXIS_A, profile=profile_xml(AXIS_A, 1.0, curve_tag="UnsymParaCurve"))]),
        "eje-rasante-mala.xml": landxml(bad_profile_alignments()),
        "sin-alineaciones.xml": landxml([]),
        "no-es-landxml.txt": "Esto no es un fichero LandXML.\n",
        "mal-formado.xml": '<?xml version="1.0"?>\n<LandXML xmlns="' + NS + '"><Alignments><Alignment name="x"\n',
        "rasante-b.csv": "PK;Cota;Kv\n"
        + "".join(f"{str(v.pk).replace('.', ',')};{str(v.z).replace('.', ',')};{'' if v.curve is None else str(v.curve[1]).replace('.', ',')}\n" for v in AXIS_B.profile),
    }
    return out


def bad_profile_alignments() -> list[str]:
    """Tres rasantes sin válidas; en estas, el segundo valor de un acuerdo es su longitud (no el Kv)."""

    def named(name: str, vertices: tuple[Vertex, ...]) -> str:
        axis = Axis(name, AXIS_C.east, AXIS_C.north, AXIS_C.azimuth_deg, AXIS_C.start_pk, AXIS_C.elements)
        body = "".join(f"<PVI>{A(v.pk)} {A(v.z)}</PVI>" if v.curve is None else f'<ParaCurve length="{A(v.curve[1])}">{A(v.pk)} {A(v.z)}</ParaCurve>' for v in vertices)
        return alignment_xml(axis, profile=f'<Profile name="Perfil"><ProfAlign name="Rasante">{body}</ProfAlign></Profile>')

    return [
        named("Repetido", (Vertex(0.0, 10.0), Vertex(100.0, 11.0), Vertex(100.0, 12.0), Vertex(200.0, 13.0))),
        named("FueraDelEje", (Vertex(0.0, 10.0), Vertex(100.0, 11.0), Vertex(250.0, 12.0))),
        named("NoCabe", (Vertex(0.0, 10.0), Vertex(100.0, 12.0, ("para", 36.0)), Vertex(110.0, 11.0), Vertex(200.0, 13.0))),
    ]


# ------------------------------------------------------------------------------------------------------------------ esperado
def summary_rows() -> list[tuple]:
    def row(file, name, length, sta, profile, status):
        return (file, name, A(length), A(sta), "si" if profile else "no", status)

    rows = [row("ejes.xml", a.name, length_of(a), a.start_pk, bool(a.profile), "OK") for a in AXES]
    rows.append(row("eje-a-pies.xml", AXIS_A.name, length_of(AXIS_A), AXIS_A.start_pk, True, "OK"))
    rows.append(row("eje-hueco.xml", AXIS_A.name, length_of(AXIS_A), AXIS_A.start_pk, True, "hueco_tramo_2"))
    rows.append(row("eje-irregular.xml", AXIS_C.name, length_of(AXIS_C), AXIS_C.start_pk, False, "tipo_no_admitido_tramo_1"))
    rows.append(row("eje-espiral-cubica.xml", AXIS_A.name, length_of(AXIS_A), AXIS_A.start_pk, True, "tipo_no_admitido_tramo_2"))
    rows.append(row("eje-ecuacion-pk.xml", AXIS_C.name, length_of(AXIS_C), AXIS_C.start_pk, False, "ecuacion_de_pk"))
    rows.append(row("eje-acuerdo-asimetrico.xml", AXIS_A.name, length_of(AXIS_A), AXIS_A.start_pk, True, "OK_sin_rasante_acuerdo_no_admitido"))
    rows.append(row("eje-rasante-mala.xml", "Repetido", 200.0, 0.0, True, "OK_sin_rasante_pk_repetido"))
    rows.append(row("eje-rasante-mala.xml", "FueraDelEje", 200.0, 0.0, True, "OK_sin_rasante_fuera_del_eje"))
    rows.append(row("eje-rasante-mala.xml", "NoCabe", 200.0, 0.0, True, "OK_sin_rasante_acuerdo_no_cabe"))
    return rows


def crs_rows() -> list[str]:
    """El eje A tomado como ETRS89 UTM 31 y sus puntos en ETRS89 UTM 30 (el del proyecto), con pyproj (RF-11)."""
    from pyproj import Transformer

    transformer = Transformer.from_crs("EPSG:25831", "EPSG:25830", always_xy=True)
    rows = ["eje,pk,este_origen,norte_origen,este_proyecto,norte_proyecto"]
    for pk in sample_pks(AXIS_A):
        state, _ = point_at(AXIS_A, pk)
        e, n = transformer.transform(state.east, state.north)
        rows.append(",".join([AXIS_A.name, A(pk), A(state.east), A(state.north), A(e), A(n)]))
    return rows


def expected() -> dict[str, str]:
    tramos = ["eje,tramo,tipo,pk_inicio,longitud,k_inicio,k_fin,este_inicio,norte_inicio,este_fin,norte_fin,azimut_inicio_deg"]
    for a in AXES:
        states = boundaries(a)
        for i, e in enumerate(a.elements):
            s0, s1 = states[i], states[i + 1]
            tramos.append(",".join([a.name, str(i + 1), e.kind, A(s0.pk), A(e.length), A(e.k0), A(e.k1), A(s0.east), A(s0.north), A(s1.east), A(s1.north), A(math.degrees(s0.azimuth))]))
    puntos = ["eje,pk,desplazamiento,este,norte,azimut_deg,radio,cota,pendiente_pct"]
    for a in AXES:
        for pk in sample_pks(a):
            st, k = point_at(a, pk)
            radius = "inf" if abs(k) < 1e-15 else A(1 / abs(k))
            z, g = profile_at(a.profile, pk) if a.profile else (None, None)
            for d in OFFSETS:
                e, n = offset_point(st, d)
                puntos.append(
                    ",".join([a.name, A(pk), A(d), A(e), A(n), A(math.degrees(st.azimuth)), radius, "" if z is None else A(z), "" if g is None else A(g * 100)])
                )
    rasantes = ["eje,vertice,pk,cota,curva,longitud,radio"]
    for a in AXES:
        for i, v in enumerate(a.profile):
            d = _curve_span(a.profile, i)[2] if v.curve else None
            kind = "none" if v.curve is None else v.curve[0]
            rasantes.append(",".join([a.name, str(i + 1), A(v.pk), A(v.z), kind, "" if d is None else A(d["length"]), "" if kind != "circ" else A(d["r"])]))
    pi_rows = ["tramo,tipo,pk_inicio,longitud,k_inicio,k_fin,este_inicio,norte_inicio,este_fin,norte_fin"]
    for i, (e, st) in enumerate(pi_elements()):
        end = advance(st, e, e.length)
        pi_rows.append(",".join([str(i + 1), e.kind, A(st.pk), A(e.length), A(e.k0), A(e.k1), A(st.east), A(st.north), A(end.east), A(end.north)]))
    pi_vertices = ["vertice,este,norte,radio,longitud_clotoide"] + [",".join([str(i + 1)] + [A(v) for v in row]) for i, row in enumerate(PI_VERTICES)]
    resumen = ["archivo,alineacion,longitud,pk_inicial,rasante,estado"] + [",".join(r) for r in summary_rows()]
    return {
        "esperado-tramos.csv": "\n".join(tramos) + "\n",
        "esperado-puntos.csv": "\n".join(puntos) + "\n",
        "pi-vertices.csv": "\n".join(pi_vertices) + "\n",
        "esperado-pi.csv": "\n".join(pi_rows) + "\n",
        "esperado-crs.csv": "\n".join(crs_rows()) + "\n",
        "esperado-rasantes.csv": "\n".join(rasantes) + "\n",
        "esperado-resumen.csv": "\n".join(resumen) + "\n",
    }


def all_files() -> dict[str, str]:
    return {**files(), **expected()}


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(all_files())} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
