"""El CSV del informe de replanteo que escribe TopoPhone, leído con otra herramienta (S019, constitución nº 2).

Los ficheros de fixtures/formatos/csv/informe_replanteo_*.csv los escribe StakeoutReportCsv (StakeoutReportCsvGoldenTest los
compara byte a byte) a partir de los puntos de fixtures/replanteo/replanteo-informe.csv, que calcula pyproj. Aquí se comprueba
que el módulo csv de Python lee de ellos las mismas diferencias, cotas y veredictos que calculó pyproj.
"""

from __future__ import annotations

import csv
import io
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / "fixtures" / "formatos" / "csv"
INFORME = ROOT / "fixtures" / "replanteo" / "replanteo-informe.csv"

# Fichero → (proyecto de los fixtures, separador, signo decimal)
FILES = {
    "informe_replanteo_utm30.csv": ("UTM30", ",", "."),
    "informe_replanteo_canarias_puntoycoma_coma.csv": ("UTM28", ";", ","),
    "informe_replanteo_geografico_tabulador_punto.csv": ("GEOGRAFICO", "\t", "."),
}

UTM_HEADER = [
    "Design name", "Stakeout number", "Staked name", "Description", "Design easting", "Design northing", "Design elevation",
    "Measured easting", "Measured northing", "Measured elevation", "Delta northing", "Delta easting", "Horizontal distance",
    "Delta elevation", "Horizontal tolerance", "Vertical tolerance", "Within tolerance", "Last", "Solution", "Measured from",
    "Drawing", "Layer", "Element type", "Mode", "Chainage", "Lateral offset",
    "Elevation source", "Surface", "Surface elevation", "Vertical offset",
]  # fmt: skip


def expected(project: str) -> list[dict[str, str]]:
    lines = [line for line in INFORME.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    header = lines[0].split(",")
    rows = [dict(zip(header, line.split(","))) for line in lines[1:]]
    return [row for row in rows if row["proyecto"] == project]


def read(name: str) -> list[dict[str, str]]:
    _, separator, _ = FILES[name]
    text = (FOLDER / name).read_bytes().decode("utf-8")
    return list(csv.DictReader(io.StringIO(text, newline=""), delimiter=separator))


def number(text: str, decimal: str) -> float | None:
    return None if text == "" else float(text.replace(decimal, "."))


def test_s019_rf23_utf8_sin_bom_y_crlf() -> None:
    for name, (project, _, _) in FILES.items():
        data = (FOLDER / name).read_bytes()
        assert not data.startswith(b"\xef\xbb\xbf"), name
        assert data.endswith(b"\r\n"), name
        assert data.count(b"\r\n") == len(expected(project)) + 1, name


def test_s019_rf21_cabecera_y_una_fila_por_replanteo_en_el_orden_del_informe() -> None:
    for name, (project, _, _) in FILES.items():
        rows = read(name)
        header = list(rows[0].keys())
        if project == "GEOGRAFICO":
            assert header[4:6] == ["Design latitude", "Design longitude"], name
            assert header[7:9] == ["Measured latitude", "Measured longitude"], name
            assert header[:4] + header[6:7] + header[9:] == UTM_HEADER[:4] + UTM_HEADER[6:7] + UTM_HEADER[9:], name
        else:
            assert header == UTM_HEADER, name
        assert [row["Staked name"] for row in rows] == [row["nombre"] for row in expected(project)], name
        assert [row["Stakeout number"] for row in rows] == [row["numero"] for row in expected(project)], name


def test_s019_rf21_las_diferencias_y_la_cota_leidas_son_las_de_pyproj_a_menos_de_0_05_mm() -> None:
    for name, (project, _, decimal) in FILES.items():
        for row, ref in zip(read(name), expected(project)):
            assert abs(number(row["Delta northing"], decimal) - float(ref["d_norte"])) <= 0.00005 + 1e-12, (name, ref["nombre"])
            assert abs(number(row["Delta easting"], decimal) - float(ref["d_este"])) <= 0.00005 + 1e-12, (name, ref["nombre"])
            assert abs(number(row["Horizontal distance"], decimal) - float(ref["d_horizontal"])) <= 0.00005 + 1e-12, (name, ref["nombre"])
            if ref["d_cota"] == "":
                assert row["Delta elevation"] == "", (name, ref["nombre"])
            else:
                assert abs(number(row["Delta elevation"], decimal) - float(ref["d_cota"])) <= 0.00005 + 1e-12, (name, ref["nombre"])
            if ref["cota_usada"] == "":
                assert row["Design elevation"] == "", (name, ref["nombre"])
            else:
                assert abs(number(row["Design elevation"], decimal) - float(ref["cota_usada"])) <= 0.00005 + 1e-12, (name, ref["nombre"])


def test_s019_rf14_rf26_dentro_de_la_tolerancia_y_el_ultimo_replanteo_son_los_calculados_aparte() -> None:
    for name, (project, _, _) in FILES.items():
        for row, ref in zip(read(name), expected(project)):
            within = {"1": "true", "0": "false", "": ""}[ref["dentro"]]
            assert row["Within tolerance"] == within, (name, ref["nombre"])
            assert row["Last"] == ("true" if ref["ultimo"] == "1" else "false"), (name, ref["nombre"])
            assert (row["Horizontal tolerance"] == "") == (ref["tol_h"] == ""), (name, ref["nombre"])


def test_s019_rf22_las_coordenadas_del_proyecto_son_las_de_pyproj_o_la_latitud_y_la_longitud() -> None:
    for name, (project, _, decimal) in FILES.items():
        for row, ref in zip(read(name), expected(project)):
            if project == "GEOGRAFICO":
                assert abs(number(row["Design latitude"], decimal) - float(ref["diseno_lat"])) <= 5e-10 + 1e-15, name
                assert abs(number(row["Measured longitude"], decimal) - float(ref["medido_lon"])) <= 5e-10 + 1e-15, name
            else:
                assert abs(number(row["Design easting"], decimal) - float(ref["diseno_e"])) <= 0.00005 + 1e-12, name
                assert abs(number(row["Measured northing"], decimal) - float(ref["medido_n"])) <= 0.00005 + 1e-12, name
