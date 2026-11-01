"""Secciones transversales y peraltes de ejemplo y lo que debe salir de cada uno (S050).

Genera en fixtures/ejes/secciones/:

- plantillas.csv: las secciones tipo de los ejes A y B (un tramo por fila: lado, orden desde el eje, código, ancho, pendiente y si sigue el
  peralte; el código `EJE` es el eje, implícito) con su rango de PK y su punto de rasante. La pendiente es positiva si el terreno baja al
  alejarse del eje (D2).
- peralte.csv: la tabla de peralte (PK, pendiente izquierda y derecha, con el mismo signo). El eje A tiene cinco filas y el eje B una.
- secciones-eje-c.csv: secciones explícitas del eje C en el formato del CSV de secciones (PK, código, desplazamiento, cota absoluta y si es
  de diseño o de terreno) y secciones-eje-c.xml, las mismas en un LandXML sintético con `CrossSects` (una sin nombres de punto, otra con
  `DesignCrossSectSurf` y un perfil de terreno `existing`). No hay ningún LandXML real con secciones (D8).
- esperado-tipo.csv: por eje, PK y código de una sección tipo, el desplazamiento y la diferencia de cota con la rasante.
- esperado-puntos.csv: por eje, PK y código, el desplazamiento, el Este, el Norte, la cota (vacía si no hay rasante y la sección es relativa)
  y la pendiente transversal en porcentaje (positiva si baja al alejarse del eje). Vale para tipo y para explícitas interpoladas.
- esperado-resolucion.csv: por eje y PK, `ok`, `faltan` (con los códigos que faltan) o `fuera`.
- esperado-desplazamientos.csv: por eje, PK y desplazamiento, la cota de la sección o `fuera` (RF-10).

Cómo se calcula (otro algoritmo que el del móvil, para que la coincidencia no sea una tautología):
- Todo lo vertical, en aritmética racional con `fractions`: pendientes interpoladas con el PK, desplazamientos y diferencias de cota. Solo
  al escribir se pasa a `float`.
- Peralte: lineal entre filas; con una fila se mantiene en todo el eje; con dos o más, fuera de la primera y de la última vale la
  pendiente propia del tramo (D3). La sección gira alrededor del punto de rasante: a todas las diferencias se les resta la de ese punto.
- Interpolación entre secciones explícitas: lineal con el PK en el desplazamiento y en la cota de cada código (D1, D4).
- La planta y la rasante son las de generar_ejes.py (S049): Este y Norte con la clotoide en serie y el desplazamiento a la derecha.

Uso: uv run --project tools tools/ejes/generar_secciones.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_ejes as g  # noqa: E402

OUT = g.ROOT / "fixtures" / "ejes" / "secciones"
AXIS = "EJE"


def F(x) -> Fraction:
    return Fraction(str(x)) if not isinstance(x, Fraction) else x


def fl(x: Fraction) -> str:
    return g.A(float(x))


@dataclass(frozen=True)
class Seg:
    code: str
    width: Fraction
    slope: Fraction  # positiva si baja al alejarse del eje
    follows: bool


@dataclass(frozen=True)
class Template:
    axis: g.Axis
    start: float
    end: float
    grade: str
    left: tuple[Seg, ...]
    right: tuple[Seg, ...]
    super_rows: tuple[tuple[Fraction, Fraction, Fraction], ...]  # (pk, izquierda, derecha)


def seg(code: str, width: str, slope: str, follows: bool = False) -> Seg:
    return Seg(code, F(width), F(slope), follows)


LEFT = (seg("BI", "3.5", "0.02", True), seg("AI", "1.5", "0.04"), seg("CI", "1.0", "-0.5"))
RIGHT = (seg("BD", "3.5", "0.02", True), seg("AD", "1.5", "0.04"), seg("CD", "1.0", "-0.5"))
SUPER_A = (
    (F(0), F("0.02"), F("0.02")),
    (F(120), F("0.02"), F("0.02")),
    (F(180), F("-0.06"), F("0.06")),
    (F(280), F("-0.06"), F("0.06")),
    (F(380), F("0.02"), F("0.02")),
)
SUPER_B = ((F(1100), F("-0.04"), F("0.04")),)
TEMPLATES = (
    Template(g.AXIS_A, 0.0, 420.0, AXIS, LEFT, RIGHT, SUPER_A),
    Template(g.AXIS_B, 1000.5, 1380.5, "BD", LEFT, RIGHT, SUPER_B),
)


def super_slopes(rows, pk: Fraction) -> tuple[Fraction, Fraction] | None:
    """Pendientes izquierda y derecha del peralte en [pk], o None si fuera de la tabla con dos o más filas (D3)."""
    if len(rows) == 1:
        return rows[0][1], rows[0][2]
    if pk < rows[0][0] or pk > rows[-1][0]:
        return None
    for (p0, l0, r0), (p1, l1, r1) in zip(rows, rows[1:]):
        if p0 <= pk <= p1:
            t = (pk - p0) / (p1 - p0)
            return l0 + (l1 - l0) * t, r0 + (r1 - r0) * t
    raise AssertionError


def template_points(t: Template, pk: Fraction) -> list[tuple[str, Fraction, Fraction, Fraction]]:
    """(código, desplazamiento, diferencia de cota, pendiente transversal) de izquierda a derecha, con el giro en el punto de rasante."""
    sup = super_slopes(t.super_rows, pk)
    out: list[tuple[str, Fraction, Fraction, Fraction]] = [(AXIS, F(0), F(0), F(0))]
    for side, segs in ((-1, t.left), (1, t.right)):
        off, dz = F(0), F(0)
        for s in segs:
            slope = (sup[0] if side < 0 else sup[1]) if (s.follows and sup is not None) else s.slope
            off += s.width * side
            dz -= s.width * slope
            out.append((s.code, off, dz, slope))
    # La pendiente del eje es la del primer tramo de la derecha.
    out[0] = (AXIS, F(0), F(0), out[[p[0] for p in out].index(t.right[0].code)][3])
    out.sort(key=lambda p: p[1])
    pivot = next(p[2] for p in out if p[0] == t.grade)
    return [(c, o, dz - pivot, s) for c, o, dz, s in out]


# ----------------------------------------------------------------------------------------------------------- explícitas (eje C)
SET_X = ("CI", "BI", AXIS, "BD", "CD")
SET_Y = ("BI", AXIS, "BD")
# (pk, código, desplazamiento, cota absoluta). Las cotas del eje C (sin rasante) son absolutas.
EXPLICIT = {
    0: {"CI": ("-8.0", "99.80"), "BI": ("-6.0", "100.10"), AXIS: ("0.0", "100.30"), "BD": ("6.0", "100.10"), "CD": ("8.0", "99.80")},
    100: {"CI": ("-9.0", "101.00"), "BI": ("-7.0", "101.40"), AXIS: ("0.0", "101.70"), "BD": ("7.0", "101.40"), "CD": ("9.0", "101.00")},
    150: {"BI": ("-7.0", "102.00"), AXIS: ("0.0", "102.30"), "BD": ("7.0", "102.00")},
    190: {"BI": ("-7.0", "102.50"), AXIS: ("0.0", "102.80"), "BD": ("7.0", "102.50")},
}
GROUND = {50: {"T1": ("-10.0", "99.00"), "T2": ("-2.0", "100.20"), "T3": ("5.0", "100.60"), "T4": ("12.0", "101.10")}}


def explicit_section(pk: Fraction) -> dict[str, tuple[Fraction, Fraction]] | str | None:
    """La sección explícita del eje C en [pk]: sus puntos, o la lista (texto) de códigos que faltan, o None si fuera (RF-12)."""
    pks = sorted(EXPLICIT)
    if pk < pks[0] or pk > pks[-1]:
        return None
    for a, b in zip(pks, pks[1:]):
        if a <= pk <= b:
            if pk == a:
                return {c: (F(o), F(z)) for c, (o, z) in EXPLICIT[a].items()}
            if pk == b:
                return {c: (F(o), F(z)) for c, (o, z) in EXPLICIT[b].items()}
            if set(EXPLICIT[a]) != set(EXPLICIT[b]):
                return ";".join(sorted(set(EXPLICIT[a]) ^ set(EXPLICIT[b])))
            t = (pk - a) / F(b - a)
            return {
                c: (F(EXPLICIT[a][c][0]) + (F(EXPLICIT[b][c][0]) - F(EXPLICIT[a][c][0])) * t,
                    F(EXPLICIT[a][c][1]) + (F(EXPLICIT[b][c][1]) - F(EXPLICIT[a][c][1])) * t)
                for c in EXPLICIT[a]
            }
    raise AssertionError


def explicit_points(pk: Fraction) -> list[tuple[str, Fraction, Fraction, Fraction]] | str | None:
    sec = explicit_section(pk)
    if sec is None or isinstance(sec, str):
        return sec
    pts = sorted(((c, o, z) for c, (o, z) in sec.items()), key=lambda p: p[1])
    out = []
    for i, (c, o, z) in enumerate(pts):
        if o == 0:
            nb = pts[i + 1]
            slope = -(nb[2] - z) / (nb[1] - o)
        elif o < 0:
            nb = pts[i + 1]
            slope = (nb[2] - z) / (nb[1] - o)
        else:
            nb = pts[i - 1]
            slope = (nb[2] - z) / (o - nb[1])
        out.append((c, o, z, slope))
    return out


# ------------------------------------------------------------------------------------------------------------------ esperados
def ordered(rows):
    return sorted(rows, key=lambda r: r[0])


TYPE_PKS = {
    "Eje A": (0, 60, 120, 150, 180, 230, 280, 330, 380, 400, 420),
    "Eje B": ("1000.5", 1050, 1100, "1200.25", "1300.5", "1380.5"),
}
EXPLICIT_PKS = (0, 25, 50, 100, 125, 150, 175, 190, 195)
OFFSETS = ("-12", "-9", "-8", "-3.5", "-1", "0", "2", "5.25", "7", "9", "12")


def rasante(axis: g.Axis, pk: Fraction) -> Fraction | None:
    if not axis.profile:
        return None
    return F(g.profile_at(axis.profile, float(pk))[0])


def height_at(pts, offset: Fraction) -> Fraction | None:
    for (c0, o0, z0, _), (c1, o1, z1, _) in zip(pts, pts[1:]):
        if o0 <= offset <= o1:
            return z0 + (z1 - z0) * (offset - o0) / (o1 - o0)
    return None


def expected() -> dict[str, str]:
    typ = ["eje,pk,codigo,desplazamiento,dz"]
    pts = ["eje,pk,codigo,desplazamiento,este,norte,cota,pendiente_pct"]
    res = ["eje,pk,estado,faltan"]
    off = ["eje,pk,desplazamiento,cota"]

    def add_points(axis: g.Axis, pk: Fraction, section, absolute: bool) -> None:
        state, _ = g.point_at(axis, float(pk))
        z0 = rasante(axis, pk)
        for code, o, dz, slope in section:
            e, n = g.offset_point(state, float(o))
            if absolute:
                z = dz
            else:
                z = None if z0 is None else z0 + dz
            pts.append(",".join([axis.name, g.A(float(pk)), code, fl(o), g.A(e), g.A(n), "" if z is None else fl(z), g.A(float(slope * 100))]))

    def add_offsets(axis: g.Axis, pk: Fraction, section, absolute: bool) -> None:
        z0 = rasante(axis, pk)
        for o in OFFSETS:
            dz = height_at(section, F(o))
            if dz is None:
                value = "fuera"
            elif absolute:
                value = fl(dz)
            else:
                value = "" if z0 is None else fl(z0 + dz)
            off.append(f"{axis.name},{g.A(float(pk))},{g.A(float(F(o)))},{value}")

    for t in TEMPLATES:
        for raw in TYPE_PKS[t.axis.name]:
            pk = F(raw)
            section = template_points(t, pk)
            res.append(f"{t.axis.name},{g.A(float(pk))},ok,")
            for code, o, dz, _ in section:
                typ.append(f"{t.axis.name},{g.A(float(pk))},{code},{fl(o)},{fl(dz)}")
            add_points(t.axis, pk, section, False)
            add_offsets(t.axis, pk, section, False)
    for raw in EXPLICIT_PKS:
        pk = F(raw)
        section = explicit_points(pk)
        name = g.AXIS_C.name
        if section is None:
            res.append(f"{name},{g.A(float(pk))},fuera,")
        elif isinstance(section, str):
            res.append(f"{name},{g.A(float(pk))},faltan,{section}")
        else:
            res.append(f"{name},{g.A(float(pk))},ok,")
            add_points(g.AXIS_C, pk, section, True)
            add_offsets(g.AXIS_C, pk, section, True)
    return {
        "esperado-tipo.csv": "\n".join(typ) + "\n",
        "esperado-puntos.csv": "\n".join(pts) + "\n",
        "esperado-resolucion.csv": "\n".join(res) + "\n",
        "esperado-desplazamientos.csv": "\n".join(off) + "\n",
    }


# ------------------------------------------------------------------------------------------------------------------- entradas
def inputs() -> dict[str, str]:
    tpl = ["eje,desde,hasta,punto_rasante,lado,orden,codigo,ancho,pendiente,sigue_peralte"]
    sup = ["eje,pk,izquierda,derecha"]
    for t in TEMPLATES:
        for side, segs in (("izquierda", t.left), ("derecha", t.right)):
            for i, s in enumerate(segs, 1):
                tpl.append(f"{t.axis.name},{g.A(t.start)},{g.A(t.end)},{t.grade},{side},{i},{s.code},{fl(s.width)},{fl(s.slope)},{int(s.follows)}")
        for pk, left, right in t.super_rows:
            sup.append(f"{t.axis.name},{fl(pk)},{fl(left)},{fl(right)}")
    csv = ["pk,codigo,desplazamiento,cota,tipo"]
    for pk in sorted(EXPLICIT):
        for code, (o, z) in sorted(EXPLICIT[pk].items(), key=lambda kv: F(kv[1][0])):
            csv.append(f"{pk},{code},{o},{z},diseno")
    for pk, items in GROUND.items():
        for code, (o, z) in items.items():
            csv.append(f"{pk},{code},{o},{z},terreno")
    return {
        "plantillas.csv": "\n".join(tpl) + "\n",
        "peralte.csv": "\n".join(sup) + "\n",
        "secciones-eje-c.csv": "\n".join(csv) + "\n",
        "secciones-eje-c.xml": landxml(),
    }


def landxml() -> str:
    def surf(tag: str, attrs: str, items: dict, named: bool = True) -> str:
        pnts = "".join(
            f"<CrossSectPnt{f' code={chr(34)}{c}{chr(34)}' if named else ''}>{o} {z}</CrossSectPnt>"
            for c, (o, z) in sorted(items.items(), key=lambda kv: F(kv[1][0]))
        )
        return f"<{tag}{attrs}>{pnts}</{tag}>"

    secs = []
    for pk in sorted(EXPLICIT):
        if pk == 100:
            body = surf("DesignCrossSectSurf", ' name="Rasante"', EXPLICIT[pk])
        elif pk == 150:
            body = surf("CrossSectSurf", ' name="Rasante" state="proposed"', EXPLICIT[pk], named=False)
        else:
            body = surf("CrossSectSurf", ' name="Rasante" state="proposed"', EXPLICIT[pk])
        secs.append(f'<CrossSect sta="{pk}" name="PK {pk}">{body}</CrossSect>')
    for pk, items in GROUND.items():
        secs.append(f'<CrossSect sta="{pk}" name="PK {pk}">{surf("CrossSectSurf", " name=" + chr(34) + "Terreno" + chr(34) + " state=" + chr(34) + "existing" + chr(34), items)}</CrossSect>')
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<LandXML xmlns="{g.NS}" version="1.2" date="2026-11-01" time="00:00:00">\n'
        '<Units><Metric areaUnit="squareMeter" linearUnit="meter" volumeUnit="cubicMeter" angularUnit="decimal degrees" directionUnit="decimal degrees"/></Units>\n'
        f'<CrossSects name="Secciones" alignmentRef="{g.AXIS_C.name}">\n' + "\n".join(secs) + "\n</CrossSects>\n</LandXML>\n"
    )


def all_files() -> dict[str, str]:
    return {**inputs(), **expected()}


def main(out: Path = OUT) -> int:
    out.mkdir(parents=True, exist_ok=True)
    for name, text in all_files().items():
        (out / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(all_files())} ficheros en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
