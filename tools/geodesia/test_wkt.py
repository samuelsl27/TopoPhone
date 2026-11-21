"""Definiciones WKT1 de S057 (`catalogo_wkt.py`): una por SC del catálogo, con AUTHORITY, deterministas y de menos de 1 MB."""

from __future__ import annotations

import gzip
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalogo_wkt as w  # noqa: E402


@pytest.fixture(scope="session")
def filas() -> list[list[str]]:
    with gzip.open(w.SALIDA, "rt", encoding="utf-8") as f:
        return [linea.rstrip("\n").split("\t") for linea in f if linea.strip()]


def test_s057_rf_26_hay_una_definicion_por_cada_sc_del_catalogo(filas) -> None:
    assert [int(f[0]) for f in filas] == w.epsg_del_catalogo()
    assert all(len(f) == 3 for f in filas)


def test_s057_rf_22_las_definiciones_de_geopackage_llevan_authority_de_epsg(filas) -> None:
    con_definicion = [f for f in filas if f[1]]
    assert len(con_definicion) > len(filas) * 0.95
    for f in con_definicion:
        assert f[1].startswith(("PROJCS[", "GEOGCS[")), f[0]
        assert f[1].endswith(f'AUTHORITY["EPSG","{f[0]}"]]'), f[0]


def test_s057_rf_23_las_definiciones_del_prj_son_wkt1_de_esri(filas) -> None:
    por_epsg = {int(f[0]): f for f in filas}
    assert por_epsg[25830][2].startswith('PROJCS["ETRS_1989_UTM_Zone_30N"')
    assert por_epsg[4326][2].startswith('GEOGCS["GCS_WGS_1984"')


def test_s057_rf_26_las_columnas_no_llevan_tabuladores_ni_saltos_de_linea(filas) -> None:
    assert all("\n" not in c and "\t" not in c for f in filas for c in f)


def test_s057_rnf_el_recurso_pesa_menos_de_1_mb() -> None:
    assert w.SALIDA.stat().st_size <= w.TAMANO_MAXIMO


def test_s057_rf_26_regenerarlo_da_el_mismo_fichero(filas, tmp_path) -> None:
    muestra = [int(f[0]) for f in filas[:50]]
    destino = tmp_path / "wkt.tsv.gz"
    w.escribir(w.definiciones(muestra), destino)
    w.escribir(w.definiciones(muestra), tmp_path / "otra.tsv.gz")
    assert destino.read_bytes() == (tmp_path / "otra.tsv.gz").read_bytes()
    with gzip.open(destino, "rt", encoding="utf-8") as f:
        generado = [linea.rstrip("\n").split("\t") for linea in f]
    assert generado == filas[:50]
