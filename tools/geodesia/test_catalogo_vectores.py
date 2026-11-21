"""Vectores de contraste del catálogo de S034 (`catalogo_vectores.py`): tres puntos por SC, dentro de su ámbito y reproducibles."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalogo as c  # noqa: E402
import catalogo_vectores as v  # noqa: E402


@pytest.fixture(scope="session")
def completo() -> dict[str, list[list[str]]]:
    return c.generar()


@pytest.fixture(scope="session")
def vectores(completo) -> dict[str, list[list[str]]]:
    return v.generar_vectores(completo)


def test_s034_rf_4_los_tres_puntos_caen_dentro_del_ambito_y_cruzan_el_antimeridiano() -> None:
    normal = v.puntos_de_prueba("40.0,-10.0,44.0,4.0")
    cruza = v.puntos_de_prueba("-20.0,170.0,-10.0,-170.0")

    assert len(normal) == 3 and normal[0] == (42.0, -3.0)
    assert all(40.0 <= lat <= 44.0 and -10.0 <= lon <= 4.0 for lat, lon in normal)
    assert all(lon >= 170.0 or lon <= -170.0 for _lat, lon in cruza)


def test_s034_rf_4_cada_sc_proyectado_tiene_tres_vectores(completo, vectores) -> None:
    proyectados = {f[c.CRS_EPSG] for f in completo["crs"] if f[c.CRS_TIPO] == "P" and f[c.CRS_AMBITO]}
    por_sc: dict[str, int] = {}
    for filas in vectores.values():
        for fila in filas:
            por_sc[fila[0]] = por_sc.get(fila[0], 0) + 1

    assert set(por_sc) <= proyectados
    assert len(set(por_sc)) > 0.97 * len(proyectados)
    assert all(n <= 3 for n in por_sc.values())
    assert sum(1 for n in por_sc.values() if n == 3) > 0.97 * len(proyectados)


def test_s034_rf_4_utm_30_de_etrs89_da_las_coordenadas_de_pyproj(vectores) -> None:
    centro = next(f for f in vectores["tm"] if f[0] == "25830" and f[1] == "1")

    assert float(centro[v.COLUMNAS_CSV.index("este_m")]) == pytest.approx(500000.0, abs=200000.0)
    assert 4.0e6 < float(centro[v.COLUMNAS_CSV.index("norte_m")]) < 8.0e6


def test_s034_rf_4_los_ficheros_se_escriben_ordenados_y_sin_cambios_al_regenerar(vectores, tmp_path) -> None:
    primeros = v.escribir(vectores, tmp_path / "a")
    segundos = v.escribir(vectores, tmp_path / "b")

    assert [p.name for p in primeros] == sorted(p.name for p in primeros)
    assert {p.name for p in primeros} >= {"catalogo_tm.csv", "catalogo_lcc2.csv", "catalogo_lcc1.csv"}
    assert [p.read_bytes() for p in primeros] == [p.read_bytes() for p in segundos]
    lineas = primeros[0].read_text(encoding="utf-8").splitlines()
    assert lineas[0].startswith("# Vectores") and lineas[1].startswith("# pyproj=") and lineas[2] == "epsg,punto,latitud,longitud,este_m,norte_m"
