"""Los rásteres de prueba están al día y sus valores esperados salen de otro cálculo (S045)."""

from __future__ import annotations

import csv
import io
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_rasters as gr  # noqa: E402


# replay-rs3.tif lo genera generar_replay_raster.py; el trozo de MDT05 del IGN y sus valores de GDAL vienen de fuera (ORIGEN.md).
NOT_GENERATED_HERE = {"replay-rs3.tif", "mdt05-ign-madrid.tif", "mdt05-ign-madrid-gdal.csv", "ORIGEN.md"}


def rows_of(name: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO((gr.OUT / name).read_text(encoding="utf-8"), newline="")))


def lzw_decode(data: bytes) -> bytes:
    """Un decodificador de LZW de TIFF distinto del codificador: lee los códigos y reconstruye la tabla como libtiff."""
    out = bytearray()
    bit_pos, width = 0, 9
    table: list[bytes] = []

    def reset() -> None:
        nonlocal table, width
        table = [bytes([i]) for i in range(256)] + [b"", b""]
        width = 9

    def read() -> int:
        nonlocal bit_pos
        value = 0
        for _ in range(width):
            byte = data[bit_pos >> 3]
            value = (value << 1) | ((byte >> (7 - (bit_pos & 7))) & 1)
            bit_pos += 1
        return value

    reset()
    previous: bytes | None = None
    while True:
        code = read()
        if code == 257:
            return bytes(out)
        if code == 256:
            reset()
            previous = None
            continue
        if previous is None:
            entry = table[code]
        else:
            entry = table[code] if code < len(table) else previous + previous[:1]
            table.append(previous + entry[:1])
        out += entry
        previous = entry
        if len(table) + 1 >= (1 << width) and width < 12:
            width += 1


def test_s045_rf2_lzw_de_tiff_ida_y_vuelta_con_reinicio_de_tabla() -> None:
    """S045/RF-2: el codificador LZW de las pruebas produce el LZW de TIFF (códigos de 9 a 12 bits, con reinicio de la tabla)."""
    rng = random.Random(45)
    samples = [
        b"",
        b"a",
        bytes(range(256)) * 40,
        bytes(rng.randrange(256) for _ in range(30_000)),
        bytes(rng.choice(b"ab") for _ in range(30_000)),
    ]
    for data in samples:
        assert lzw_decode(gr.lzw_encode(data)) == data


def test_s045_rf2_ficheros_al_dia() -> None:
    """S045/RF-2: los ficheros de fixtures/raster/ son los que genera el script."""
    expected = gr.generate(gr.OUT)
    assert {p.name for p in gr.OUT.iterdir()} - NOT_GENERATED_HERE == set(expected)
    for name, data in expected.items():
        assert (gr.OUT / name).read_bytes() == data, name


def test_s045_rf7_celdas_con_y_sin_dato() -> None:
    """S045/RF-7: el hueco es sin dato en todos los ficheros y el resto de celdas conserva el valor del terreno sin redondear."""
    for row in rows_of("celdas.csv"):
        if not row["fichero"].startswith(("f32-utm29", "f32-geograficas", "f32-sin-geoide")):
            i, j = int(row["columna"]), int(row["fila"])
            if (i, j) in gr.HOLE:
                assert row["valor"] == "nodata", row
            elif not row["fichero"].startswith("valores-raros"):
                kind = row["fichero"].split("-")[0]
                value = float(row["valor"])
                exact = gr.terrain(i, j)
                tolerance = {"i16": 0.5, "i32": 0.5, "u16": 0.5, "f32": 1e-4}.get(kind, 0.0)
                assert abs(value - exact) <= tolerance, row


def test_s045_rf7_valores_que_no_son_un_numero() -> None:
    """S045/RF-7: en un ASCII Grid, NaN y un texto cuentan como sin dato y dejan sin cota a las posiciones que los usan."""
    text = (gr.OUT / "valores-raros.asc").read_text(encoding="ascii").splitlines()
    cells = text[6 + 3].split()  # seis líneas de cabecera, con NODATA_value
    assert cells[3] == "NaN" and cells[4] == "abc"
    by_kind = {r["tipo"]: r for r in rows_of("consultas.csv") if r["fichero"] == "valores-raros.asc"}
    for kind in ("celda_nan", "celda_texto", "entre_nan"):
        assert by_kind[kind]["cota_esperada_m"] == "", kind
    assert by_kind["centro_celda"]["cota_esperada_m"] != ""


def test_s045_rf21_rf22_rf23_consultas() -> None:
    """S045/RF-21, RF-22, RF-23: centro de celda, a medio camino, borde y huecos de las consultas esperadas."""
    by_kind: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows_of("consultas.csv"):
        by_kind[(row["fichero"], row["tipo"])] = row
    base = "f64-sin-tiras-punto.tif"
    centre = float(by_kind[(base, "centro_celda")]["cota_esperada_m"])
    assert abs(centre - gr.terrain(7, 9)) < 1e-9  # RF-23: el centro de una celda da el valor de esa celda
    half = float(by_kind[(base, "medio_camino")]["cota_esperada_m"])
    mean = sum(gr.terrain(i, j) for i in (7, 8) for j in (9, 10)) / 4
    assert abs(half - mean) < 1e-9
    for kind in ("media_celda_borde", "fuera_lejos", "centro_del_hueco", "borde_del_hueco", "fuera_dos_milimetros"):
        assert by_kind[(base, kind)]["cota_esperada_m"] == "", kind  # RF-22
    assert by_kind[(base, "junto_al_hueco")]["cota_esperada_m"] != ""
    edge = float(by_kind[(base, "borde_medio_milimetro")]["cota_esperada_m"])  # dentro de la tolerancia de 1 mm
    assert abs(edge - gr.terrain(0, 5)) < 1e-9
    for name in ("f32-utm29.tif", "f32-geograficas.tif"):
        assert by_kind[(name, "hueco")]["cota_esperada_m"] == ""
        assert 598.0 < float(by_kind[(name, "centro_celda")]["cota_esperada_m"]) < 665.0  # dentro del rango del terreno


def test_s045_rf9_pies_a_metros() -> None:
    """S045/RF-9: la cota en pies se lleva a metros con el pie internacional."""
    by_kind = {(r["fichero"], r["tipo"]): r for r in rows_of("consultas.csv")}
    feet = float(by_kind[("f32-pies.tif", "centro_celda")]["cota_esperada_m"])
    metres = float(by_kind[("f32-sin-tiras-area.tif", "centro_celda")]["cota_esperada_m"])
    assert abs(feet - metres * gr.FOOT_M) < 1e-9


def test_s045_rf10_geoide_en_los_dos_sentidos() -> None:
    """S045/RF-10: H = h − N y h = H + N con la ondulación del geoide de la app; sin geoide, sin cota."""
    by_key = {(r["fichero"], r["altura_raster"], r["altura_proyecto"], r["tipo"]): r for r in rows_of("consultas.csv")}
    reference = "f32-sin-tiras-area.tif"
    plain = float(by_key[(reference, "ORTHOMETRIC", "ORTHOMETRIC", "centro_celda")]["cota_esperada_m"])
    down = float(by_key[(reference, "ELLIPSOIDAL", "ORTHOMETRIC", "centro_celda_geoide")]["cota_esperada_m"])
    up = float(by_key[(reference, "ORTHOMETRIC", "ELLIPSOIDAL", "centro_celda_geoide")]["cota_esperada_m"])
    undulation = plain - down
    assert 40.0 < undulation < 60.0  # Madrid: N de EGM08-REDNAP
    assert abs((up - plain) - undulation) < 1e-9
    far = "f32-sin-geoide.tif"
    assert by_key[(far, "ELLIPSOIDAL", "ORTHOMETRIC", "medio_camino_geoide")]["cota_esperada_m"] == ""
    assert by_key[(far, "ORTHOMETRIC", "ORTHOMETRIC", "medio_camino_geoide")]["cota_esperada_m"] != ""


def test_s045_rf3_rf4_rechazos_con_su_motivo() -> None:
    """S045/RF-3, RF-4: cada fichero que no se importa dice por qué."""
    results = {r["fichero"]: r["resultado"] for r in rows_of("ficheros.csv")}
    assert results["bigtiff.tif"] == "BIGTIFF"
    assert results["girado.tif"] == "ROTATED"
    assert results["color-rgb.tif"] == "COLOUR"
    assert results["compresion-jpeg.tif"] == "UNSUPPORTED_COMPRESSION"
    assert results["sin-georreferencia.tif"] == "NOT_GEOREFERENCED"
    assert results["todo-sin-dato.tif"] == "NO_DATA_CELLS"
    assert results["no-es-raster.txt"] == "NOT_A_RASTER"
    assert results["vacio.tif"] == "EMPTY"
    assert (gr.OUT / "vacio.tif").stat().st_size == 0
