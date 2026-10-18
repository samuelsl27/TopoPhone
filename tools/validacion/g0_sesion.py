"""Puerta G0, ensayo (a): lo que TopoPhone debe mostrar para una sesión grabada en campo.

Lee el fichero que comparte la app ("Compartir sesión", `.nmea` tal como llegó del receptor), aplica
pyproj/PROJ a cada GGA válida con la misma cadena que la app (jalón vertical + 0,134 m del RS3, UTM
EPSG:258zz, EGM08-REDNAP) y muestra la época pedida con los formatos de la pantalla, para comparar
con lo que se ve en el móvil (tolerancia 1 mm). Con `--resumen` da la media y la dispersión de las
épocas fijas, útil para el ensayo (c) contra la reseña del vértice.

Con `--esperado` escribe `<sesion>.esperado.csv` junto a la sesión, en el formato que comprueba
`RecordedSessionsTest` (core:survey) a 1 mm.

Uso:
  uv run --project tools tools/validacion/g0_sesion.py <sesion.nmea> [--jalon 1.8] [--hora hhmmss.ss] [--resumen] [--esperado]
"""

from __future__ import annotations

import argparse
import statistics
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "nmea"))
import generar_sintetico as g  # noqa: E402

STATUS = {0: "NONE", 1: "SINGLE", 2: "DGPS", 4: "FIX", 5: "FLOAT", 6: "ESTIMATED"}


def gga_epochs(data: bytes) -> list[g.Epoch]:
    """GGA con suma de control correcta (cualquier talker), en orden; el resto se descarta como en la app."""
    epochs = []
    for raw in data.split(b"\n"):
        start = raw.rfind(b"$")
        if start < 0 or b"*" not in raw[start:]:
            continue
        try:
            text = raw[start + 1 :].decode("ascii").strip()
        except UnicodeDecodeError:
            continue
        body, _, cs = text.partition("*")
        if g.checksum(body) != cs.upper() or not body[2:].startswith("GGA,"):
            continue
        f = body.split(",")
        if len(f) < 15:
            continue
        try:
            lat = g.parsed_coordinate(f[2], f[3], 2) if f[2] else None
            lon = g.parsed_coordinate(f[4], f[5], 3) if f[4] else None
            epochs.append(
                g.Epoch(
                    utc=f[1],
                    talker=body[:2],
                    status=STATUS.get(int(f[6] or 0), "NONE"),
                    lat=lat,
                    lon=lon,
                    satellites=int(f[7] or 0),
                    hdop=float(f[8]) if f[8] else float("nan"),
                    altitude=float(f[9]) if f[9] else None,
                    separation=float(f[11]) if f[11] else None,
                    age=float(f[13]) if f[13] else None,
                    sigma=None,
                )
            )
        except ValueError:
            continue
    return epochs


COLUMNS = ["frase", "hora", "estado", "satelites", "hdop", "edad", "sigma_h", "sigma_v", "latitud", "longitud", "h", "huso", "este", "norte", "H"]


def expected(epoch: g.Epoch, pole_m: float, geoid, canarias_geoid=None) -> dict[str, str]:
    return dict(zip(COLUMNS, g.expected_row(epoch, epoch.lat, epoch.lon, geoid, canarias_geoid, pole_m)))


def dms(value: float, degree_digits: int, positive: str, negative: str) -> str:
    """Mismo redondeo que AngleFormat: segundos totales con 5 decimales, una sola vez y HALF_UP."""
    units = int((Decimal(abs(value)) * 3600 * 100_000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    seconds, fraction = divmod(units, 100_000)
    degrees, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    hemisphere = negative if value < 0 and units else positive
    return f"{degrees:0{degree_digits}d}° {minutes:02d}′ {secs:02d},{fraction:05d}″ {hemisphere}"


def spanish(value: float, decimals: int, grouping: bool) -> str:
    """Formato de la interfaz en español: coma decimal y punto de millares."""
    text = f"{value:,.{decimals}f}" if grouping else f"{value:.{decimals}f}"
    return text.replace(",", "_").replace(".", ",").replace("_", ".")


def metres(text: str) -> str:
    return spanish(float(text), 3, grouping=True) + " m" if text else "no disponible"


def show(row: dict[str, str]) -> None:
    print(f"Hora {row['hora']} · {row['frase']} · {row['estado']} · {row['satelites']} satélites")
    if not row["latitud"]:
        print("  Sin posición")
        return
    lat, lon = float(row["latitud"]), float(row["longitud"])
    print(f"  Latitud   {dms(lat, 2, 'N', 'S')}   {spanish(lat, 9, grouping=False)}")
    print(f"  Longitud  {dms(lon, 3, 'E', 'W')}   {spanish(lon, 9, grouping=False)}")
    print(f"  h         {metres(row['h'])}")
    print(f"  Huso UTM  {row['huso'] or 'fuera de 27 a 31'}")
    print(f"  E         {metres(row['este'])}")
    print(f"  N         {metres(row['norte'])}")
    print(f"  H         {metres(row['H'])}")


def summary(rows: list[dict[str, str]]) -> None:
    fixed = [r for r in rows if r["estado"] == "FIX" and r["este"] and r["H"]]
    if not fixed:
        print("No hay épocas fijas con UTM y altitud.")
        return
    print(f"Épocas fijas: {len(fixed)} de {len(rows)} (de {fixed[0]['hora']} a {fixed[-1]['hora']})")
    for key, label in (("este", "E"), ("norte", "N"), ("H", "H")):
        values = [float(r[key]) for r in fixed]
        spread = statistics.pstdev(values) if len(values) > 1 else 0.0
        print(f"  {label}: media {statistics.fmean(values):.4f} m · desviación {spread * 1000:.1f} mm")


def write_expected(session: Path, rows: list[dict[str, str]], pole_m: float) -> Path:
    path = session.with_name(session.name.removesuffix(".nmea") + ".esperado.csv")
    grid = g.rejillas.GRIDS[0]
    header = [
        f"# Sesión {session.name}: pyproj aplicado a sus GGA. Generado por tools/validacion/g0_sesion.py; no editar a mano.",
        f"# pyproj={g.pyproj.__version__}; PROJ={g.pyproj.proj_version_str}; rejilla={grid.name} sha256={grid.sha256}",
        f"# jalon_m={pole_m}; receptor=RS3 ({g.RS3_BASE_TO_REFERENCE_M} m)",
        ",".join(COLUMNS),
    ]
    body = [",".join(row[c] for c in COLUMNS) for row in rows]
    path.write_text("\n".join(header + body) + "\n", encoding="utf-8", newline="\n")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("sesion", type=Path)
    parser.add_argument("--jalon", type=float, default=g.POLE_M, help="altura del jalón en metros (la de la app)")
    parser.add_argument("--hora", help="hora UTC de la GGA (hhmmss.ss); por omisión, la última fija")
    parser.add_argument("--resumen", action="store_true", help="media y dispersión de las épocas fijas")
    parser.add_argument("--esperado", action="store_true", help="escribe <sesion>.esperado.csv para RecordedSessionsTest")
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    geoid = g.generar_vectores.geoid_transformer()
    canarias_geoid = g.generar_vectores.geoid_transformer(g.rejillas.GEOID_CANARIAS)
    rows = [expected(epoch, args.jalon, geoid, canarias_geoid) for epoch in gga_epochs(args.sesion.read_bytes())]
    if not rows:
        print("La sesión no tiene ninguna GGA válida.")
        return 1
    print(f"Jalón {args.jalon} m + RS3 {g.RS3_BASE_TO_REFERENCE_M} m · {len(rows)} GGA válidas")
    if args.esperado:
        print(f"Escrito {write_expected(args.sesion, rows, args.jalon)}")
    if args.resumen:
        summary(rows)
        return 0
    if args.hora:
        chosen = next((r for r in rows if r["hora"] == args.hora), None)
        if chosen is None:
            print(f"No hay ninguna GGA con hora {args.hora}.")
            return 1
    else:
        chosen = next((r for r in reversed(rows) if r["estado"] == "FIX"), rows[-1])
    show(chosen)
    return 0


if __name__ == "__main__":
    sys.exit(main())
