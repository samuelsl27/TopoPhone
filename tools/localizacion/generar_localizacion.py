"""Vectores de contraste de S039: la localizacion (semejanza 2D o movimiento rigido, y desplazamiento o plano vertical) calculada con otro algoritmo que el
del movil (constitucion n 2).

Uso: uv run --project tools tools/localizacion/generar_localizacion.py   (escribe `fixtures/localizacion/pares.csv` y `parametros.csv`)

Los pares salen de unas posiciones del suelo en ETRS89 pasadas a UTM 30N o a Lambert-93 con pyproj (las del SC sin localizar), y de unas coordenadas
conocidas que son esas posiciones con una transformacion conocida y un ruido fijo (sin ruido en el caso 1).

El ajuste de la semejanza usa las ecuaciones normales completas (a, b, tx, ty) en aritmetica racional (`fractions`), sin centrar: no es el algoritmo del
movil, que centra y usa formulas cerradas. El movimiento rigido usa las sumas centradas exactas y un unico atan2 al final. El plano vertical resuelve las
ecuaciones normales 3x3 en racionales.
"""

from __future__ import annotations

import math
import sys
from fractions import Fraction
from pathlib import Path

import pyproj
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
CARPETA = ROOT / "fixtures" / "localizacion"
PARES = CARPETA / "pares.csv"
PARAMETROS = CARPETA / "parametros.csv"

# Transformacion conocida: giro (rad), escala, traslacion del origen del SC, y plano vertical (dh en el origen, pendientes).
GIRO, ESCALA, TE, TN = 0.0035, 1.00002, 1000.123, -2000.456
DH, PE, PN = 0.25, 3e-6, -2e-6

# (caso, epsg, escala fija, ruido, [(lat, lon, h, usa_h, usa_v)])
PUNTOS_UTM = [
    (40.40, -3.70, 650.0), (40.41, -3.69, 652.5), (40.39, -3.68, 648.0), (40.42, -3.71, 655.2), (40.405, -3.695, 651.1),
]
PUNTOS_LAMBERT = [(48.85, 2.35, 60.0), (48.86, 2.36, 62.0), (48.84, 2.34, 58.5), (48.855, 2.37, 61.0)]
RUIDO = [(0.012, -0.008, 0.015), (-0.006, 0.014, -0.010), (0.009, 0.004, 0.007), (-0.015, -0.011, -0.004), (0.003, -0.002, 0.012)]

CASOS = [
    # nombre, epsg, puntos, escala fija, con ruido, componentes (h, v) por par
    ("c1_utm30_sin_ruido", 25830, PUNTOS_UTM[:4], False, False, [(True, True)] * 4),
    ("c2_utm30_con_ruido", 25830, PUNTOS_UTM, False, True, [(True, True)] * 5),
    ("c3_lambert93_rigido", 2154, PUNTOS_LAMBERT, True, True, [(True, True), (True, True), (True, False), (True, False)]),
    ("c4_utm30_un_par", 25830, PUNTOS_UTM[:1], False, False, [(True, True)]),
    ("c5_utm30_dos_pares_desplazamiento", 25830, PUNTOS_UTM[:2], False, True, [(True, True), (True, True)]),
]
COLUMNAS_PARES = ["caso", "epsg", "punto", "sc_e", "sc_n", "sc_h", "conocida_e", "conocida_n", "conocida_h", "usa_h", "usa_v", "res_e", "res_n", "res_v"]
COLUMNAS_PARAMETROS = [
    "caso", "epsg", "escala_fija", "c_e", "c_n", "c_e2", "c_n2", "giro_rad", "escala", "tipo_vertical", "dh", "pend_e", "pend_n", "h_e", "h_n",
    "rms_h", "rms_v", "mayor_residuo_punto",
]


def num(valor: float) -> str:
    return repr(float(valor))


def fr(valor: float) -> Fraction:
    return Fraction(valor)


def resolver(matriz: list[list[Fraction]], termino: list[Fraction]) -> list[Fraction]:
    """Gauss con pivote en racionales: sin errores de redondeo."""
    n = len(termino)
    m = [fila[:] + [termino[i]] for i, fila in enumerate(matriz)]
    for col in range(n):
        pivote = next(i for i in range(col, n) if m[i][col] != 0)
        m[col], m[pivote] = m[pivote], m[col]
        for i in range(n):
            if i != col and m[i][col] != 0:
                f = m[i][col] / m[col][col]
                m[i] = [a - f * b for a, b in zip(m[i], m[col])]
    return [m[i][n] / m[i][i] for i in range(n)]


def semejanza(pares: list[tuple[float, float, float, float]]) -> tuple[float, float, float, float]:
    """(a, b, tx, ty) con x' = a x - b y + tx, y' = b x + a y + ty, por las ecuaciones normales completas."""
    filas: list[list[Fraction]] = []
    valores: list[Fraction] = []
    for x, y, xp, yp in pares:
        filas.append([fr(x), fr(-y), Fraction(1), Fraction(0)])
        valores.append(fr(xp))
        filas.append([fr(y), fr(x), Fraction(0), Fraction(1)])
        valores.append(fr(yp))
    normal = [[sum(f[i] * f[j] for f in filas) for j in range(4)] for i in range(4)]
    termino = [sum(f[i] * v for f, v in zip(filas, valores)) for i in range(4)]
    a, b, tx, ty = resolver(normal, termino)
    return float(a), float(b), float(tx), float(ty)


def rigido(pares: list[tuple[float, float, float, float]]) -> float:
    n = len(pares)
    cx = sum(fr(p[0]) for p in pares) / n
    cy = sum(fr(p[1]) for p in pares) / n
    cxp = sum(fr(p[2]) for p in pares) / n
    cyp = sum(fr(p[3]) for p in pares) / n
    cruz = sum((fr(p[0]) - cx) * (fr(p[3]) - cyp) - (fr(p[1]) - cy) * (fr(p[2]) - cxp) for p in pares)
    punto = sum((fr(p[0]) - cx) * (fr(p[2]) - cxp) + (fr(p[1]) - cy) * (fr(p[3]) - cyp) for p in pares)
    return math.atan2(float(cruz), float(punto))


def plano(datos: list[tuple[float, float, float]]) -> tuple[float, float, float]:
    """Delta h = d0 + px E + py N por minimos cuadrados (3x3 en racionales); (d0, px, py) con E y N sin centrar."""
    filas = [[Fraction(1), fr(e), fr(n)] for e, n, _ in datos]
    valores = [fr(d) for _, _, d in datos]
    normal = [[sum(f[i] * f[j] for f in filas) for j in range(3)] for i in range(3)]
    termino = [sum(f[i] * v for f, v in zip(filas, valores)) for i in range(3)]
    d0, px, py = resolver(normal, termino)
    return float(d0), float(px), float(py)


def posiciones(epsg: int, puntos: list[tuple[float, float, float]]) -> list[tuple[float, float, float]]:
    t = Transformer.from_crs(4258, epsg, always_xy=True)
    return [(*t.transform(lon, lat), h) for lat, lon, h in puntos]


def conocida(e: float, n: float, h: float, ruido: tuple[float, float, float] | None) -> tuple[float, float, float]:
    c, s = math.cos(GIRO), math.sin(GIRO)
    ep = ESCALA * (c * e - s * n) + TE
    np_ = ESCALA * (s * e + c * n) + TN
    hp = h + DH + PE * e + PN * n
    if ruido:
        ep, np_, hp = ep + ruido[0], np_ + ruido[1], hp + ruido[2]
    return ep, np_, hp


def generar() -> tuple[list[list[str]], list[list[str]]]:
    filas_pares: list[list[str]] = []
    filas_parametros: list[list[str]] = []
    for nombre, epsg, puntos, escala_fija, con_ruido, comps in CASOS:
        sc = posiciones(epsg, puntos)
        cono = [conocida(e, n, h, RUIDO[i] if con_ruido else None) for i, (e, n, h) in enumerate(sc)]
        horizontales = [(sc[i][0], sc[i][1], cono[i][0], cono[i][1]) for i in range(len(sc)) if comps[i][0]]
        nh = len(horizontales)
        ce = sum(fr(p[0]) for p in horizontales) / nh
        cn = sum(fr(p[1]) for p in horizontales) / nh
        ce2 = sum(fr(p[2]) for p in horizontales) / nh
        cn2 = sum(fr(p[3]) for p in horizontales) / nh
        if nh == 1:
            giro, escala = 0.0, 1.0
        elif escala_fija:
            giro, escala = rigido(horizontales), 1.0
        else:
            a, b, _, _ = semejanza(horizontales)
            giro, escala = math.atan2(b, a), math.hypot(a, b)
        c, s = math.cos(giro), math.sin(giro)
        cxf, cyf, cx2, cy2 = float(ce), float(cn), float(ce2), float(cn2)

        def localizar(e: float, n: float) -> tuple[float, float]:
            u, v = e - cxf, n - cyf
            return cx2 + escala * (c * u - s * v), cy2 + escala * (s * u + c * v)

        verticales = [i for i in range(len(sc)) if comps[i][1]]
        nv = len(verticales)
        ve = float(sum(fr(sc[i][0]) for i in verticales) / nv) if nv else 0.0
        vn = float(sum(fr(sc[i][1]) for i in verticales) / nv) if nv else 0.0
        if nv == 0:
            tipo, dh, pe, pn = "NONE", 0.0, 0.0, 0.0
        elif nv < 3:
            tipo, pe, pn = "OFFSET", 0.0, 0.0
            dh = float(sum(fr(cono[i][2]) - fr(sc[i][2]) for i in verticales) / nv)
        else:
            tipo = "PLANE"
            d0, pe, pn = plano([(sc[i][0], sc[i][1], cono[i][2] - sc[i][2]) for i in verticales])
            dh = d0 + pe * ve + pn * vn  # el valor en el centroide vertical

        def vertical(e: float, n: float, h: float) -> float:
            return h + dh + pe * (e - ve) + pn * (n - vn)

        suma_h = suma_v = 0.0
        mayor = ("", -1.0)
        for i, (e, n, h) in enumerate(sc):
            le, ln = localizar(e, n)
            re_, rn = (cono[i][0] - le, cono[i][1] - ln) if comps[i][0] else (0.0, 0.0)
            rv = cono[i][2] - vertical(e, n, h) if comps[i][1] else 0.0
            if comps[i][0]:
                suma_h += re_ * re_ + rn * rn
                if math.hypot(re_, rn) > mayor[1]:
                    mayor = (str(i + 1), math.hypot(re_, rn))
            if comps[i][1]:
                suma_v += rv * rv
            filas_pares.append([
                nombre, str(epsg), str(i + 1), num(e), num(n), num(h), num(cono[i][0]), num(cono[i][1]), num(cono[i][2]),
                "1" if comps[i][0] else "0", "1" if comps[i][1] else "0", num(re_), num(rn), num(rv),
            ])
        rms_h = math.sqrt(suma_h / nh)
        rms_v = math.sqrt(suma_v / nv) if nv else 0.0
        filas_parametros.append([
            nombre, str(epsg), "1" if escala_fija else "0", num(cxf), num(cyf), num(cx2), num(cy2), num(giro), num(escala), tipo, num(dh), num(pe),
            num(pn), num(ve), num(vn), num(rms_h), num(rms_v), mayor[0],
        ])
    return filas_pares, filas_parametros


def a_csv(columnas: list[str], filas: list[list[str]], titulo: str) -> str:
    cabecera = [
        f"# Vectores de S039: {titulo}. Generado por tools/localizacion/generar_localizacion.py; no editar a mano.",
        f"# pyproj={pyproj.__version__}; PROJ={pyproj.proj_version_str}",
        ",".join(columnas),
    ]
    return "\n".join(cabecera + [",".join(f) for f in filas]) + "\n"


def main() -> int:
    pares, parametros = generar()
    CARPETA.mkdir(parents=True, exist_ok=True)
    PARES.write_text(a_csv(COLUMNAS_PARES, pares, "los pares y sus residuos"), encoding="utf-8", newline="\n")
    PARAMETROS.write_text(a_csv(COLUMNAS_PARAMETROS, parametros, "los parametros de cada localizacion"), encoding="utf-8", newline="\n")
    print(f"{len(CASOS)} casos escritos en {CARPETA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
