"""Reproducción sintética del RS3 cruzando un ráster de cotas con un hueco sin dato y lo que debe salir de ella época a época (S045).

Genera:
- fixtures/raster/replay-rs3.tif: un ráster de 111 × 101 celdas de 1 m en ETRS89 / UTM 30 (float32, por tiras) con una cota que no es un
  plano y un hueco sin dato de 6 × 16 celdas, entre 60 y 65 m al este de su borde oeste.
- fixtures/nmea/sintetico/rs3-raster.nmea: 360 épocas con el mismo recorrido que `rs3-superficie` (S042): el usuario camina hacia el este
  a 2,25 m/s por una línea a 50 m al norte del origen, empieza 20 m antes del borde oeste del ráster (fuera), lo recorre, atraviesa el
  hueco y sale por el borde este. La punta va 20 cm por encima de la cota de diseño (el ráster más el desplazamiento vertical de 0,30 m)
  hasta la época 59 (desmonte), en cota de la 60 a la 249 y 15 cm por debajo desde la 250 (terraplén). En las épocas 100 a 119 pierde el
  FIX (4 s).
- fixtures/nmea/sintetico/rs3-raster.nivel.csv: por época, si la guía está encendida y, si lo está, la cota del ráster en la punta (vacía
  fuera de la extensión y en el hueco), VALOR o FUERA, la diferencia de cota respecto al ráster con desplazamiento, el estado del nivel y
  su aviso.

La cota esperada sale de otro cálculo que el de la app: la bilineal entre centros de celda de `tools/raster/generar_rasters.py` sobre las
celdas del propio fichero, con las coordenadas de la GGA tal como las lee la app (pyproj). Las alturas son elipsoidales: el proyecto de
la prueba lo es también y el ráster declara la misma altura, así que no se convierte nada.

Uso: uv run --project tools tools/raster/generar_replay_raster.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[0] / "nmea"))
import generar_avanzado as a  # noqa: E402
import generar_rasters as gr  # noqa: E402
import generar_superficie_rs3 as rs  # noqa: E402
import generar_sintetico as g  # noqa: E402

NAME = "rs3-raster.nmea"
CSV = "rs3-raster.nivel.csv"
TIFF = "replay-rs3.tif"
COLS, ROWS = 111, 101
X0, Y0 = 440290.0, 4474360.0  # el centro de la celda (0, 0): arriba a la izquierda
HOLE_COLS, HOLE_ROWS = range(60, 66), range(45, 61)


def replay_raster() -> gr.Raster:
    r = gr.Raster(TIFF, 25830, COLS, ROWS, X0, Y0, 1.0, 1.0, dtype="f32", strip_rows=16)
    r.values = [
        math.nan
        if (i in HOLE_COLS and j in HOLE_ROWS)
        else gr.stored("f32", round(600.0 + 0.05 * i + 0.03 * (ROWS - 1 - j) + 1.2 * math.sin(i / 7.0) * math.cos(j / 5.0), 3))
        for j in range(ROWS)
        for i in range(COLS)
    ]
    return r


RASTER = replay_raster()


def surface_height(p: tuple[float, float]) -> float | None:
    return gr.sample(RASTER, p[0], p[1])


def fixes() -> list[a.Fix]:
    """Dos pasadas: la primera con la punta a la altura del origen, para saber dónde cae cada época y qué cota tiene el ráster ahí."""
    _, first = a.write(rs.fixes_with_heights([0.0] * rs.EPOCHS))
    up: list[float] = []
    previous = rs.TYPICAL_HEIGHT_M
    for i, epoch in enumerate(first):
        height = surface_height((epoch.ground_e, epoch.ground_n))
        # Sin cota del ráster (fuera o en el hueco) la punta conserva la altura que traía.
        wanted = previous if height is None else height + rs.OFFSET_M + rs.target_dz(i)
        previous = wanted
        up.append(wanted - epoch.ground_h)
    return rs.fixes_with_heights(up)


def expected(parsed: list[a.Parsed]) -> str:
    level: str | None = None
    was_on = False
    rows = []
    for i, e in enumerate(parsed):
        ground = [a.p.fmt(e.ground_e), a.p.fmt(e.ground_n), a.p.fmt(e.ground_h)]
        if e.status != "FIX":
            alert = "PERDIDO" if was_on else ""
            was_on, level = False, None
            rows.append(",".join([e.utc, e.status, *ground, "NO_FIX", "", "", "", "", "", alert]))
            continue
        was_on = True
        height = surface_height((e.ground_e, e.ground_n))
        if height is None:
            # Fuera de la extensión o en el hueco no hay cota ni diferencia, y el nivel vuelve a empezar de cero.
            level = None
            rows.append(",".join([e.utc, e.status, *ground, "SI", "", "FUERA", "", "", "", ""]))
            continue
        dz = e.ground_h - (height + rs.OFFSET_M)
        rs.check_clearance(dz, i)
        level, level_alert = rs.next_level(level, dz)
        rows.append(",".join([e.utc, e.status, *ground, "SI", a.p.fmt(height), "VALOR", a.p.fmt(dz), level, level_alert, ""]))
    header = [
        "# Lo esperado de rs3-raster.nmea (S045). Generado por tools/raster/generar_replay_raster.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; EPSG:4258 → EPSG:25830; "
        f"jalon_m={g.POLE_M}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        f"# raster: fixtures/raster/{TIFF} (111 × 101 celdas de 1 m, hueco de 6 × 16 celdas); desplazamiento_vertical_m={rs.OFFSET_M}; "
        f"tolerancia_vertical_m={rs.TOLERANCE_V_M}; margen_m={rs.MARGIN_M}",
        f"# {rs.EPOCHS} épocas a 5 Hz hacia el este desde {abs(rs.START_EAST_M):g} m al oeste del origen, a {rs.NORTH_M:g} m al norte, cruzando "
        f"el hueco; {rs.CUT_M:g} m de desmonte hasta la 59, en cota de la 60 a la 249 y {abs(rs.FILL_M):g} m de terraplén desde la 250; "
        "sin FIX de la 100 a la 119.",
        "# cota_sup: la cota del ráster en la punta (vacía fuera y en el hueco); estado_sup: VALOR o FUERA; d_cota: punta menos "
        "(ráster más desplazamiento), positiva en desmonte. nivel: CUT, FILL u ON_GRADE; aviso_nivel: LEVEL_ARRIVED o LEVEL_LEFT; "
        "aviso: PERDIDO al apagarse la guía.",
        "hora,estado,este,norte,h,guia,cota_sup,estado_sup,d_cota,nivel,aviso_nivel,aviso",
    ]
    return "\n".join(header + rows) + "\n"


def generate() -> dict[str, tuple[Path, bytes | str]]:
    """Cada fichero con su carpeta: el ráster va en `fixtures/raster/` y la reproducción en `fixtures/nmea/sintetico/`."""
    nmea, parsed = a.write(fixes())
    return {
        TIFF: (gr.OUT, gr.write_geotiff(RASTER)),
        NAME: (g.OUT, nmea),
        CSV: (g.OUT, expected(parsed)),
    }


def main() -> int:
    for name, (folder, content) in generate().items():
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")
        print(f"{name}: {len(content)} {'bytes' if isinstance(content, bytes) else 'caracteres'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
