"""Las referencias del contorno y de la rampa de colores están al día y cumplen lo que se sabe de cada caso (S061)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generar_contornos as gc  # noqa: E402


def read_case(name: str) -> tuple[list[tuple[float, float]], list[tuple[int, int, int]], list[tuple[str, list[tuple[float, float]]]]]:
    vertices, tris, rings = [], [], []
    for line in (gc.OUT / f"{name}.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        kind, *rest = line.split(",")
        if kind == "V":
            vertices.append((float(rest[0]), float(rest[1])))
        elif kind == "T":
            tris.append(tuple(int(v) for v in rest))
        else:
            ring = [tuple(float(x) for x in p.split()) for p in rest[1].split(";")]
            rings.append((rest[0], ring))
    return vertices, tris, rings


def test_los_ficheros_estan_al_dia():
    for name, case in gc.cases().items():
        assert (gc.OUT / f"{name}.txt").read_text(encoding="utf-8") == gc.case_text(name, *case), name
    assert (gc.OUT / "rampa.csv").read_text(encoding="utf-8") == gc.ramp_csv()


def test_s061_rf6_cuadrado_un_exterior_de_cuatro_vertices():
    _, _, rings = read_case("cuadrado")
    assert [(k, len(r)) for k, r in rings] == [("EXT", 4)]
    assert gc.signed_area(rings[0][1]) == 100.0


def test_s061_rf6_hueco_exterior_y_hueco_con_sus_areas():
    _, _, rings = read_case("hueco")
    kinds = {k: r for k, r in rings}
    assert len(kinds["EXT"]) == 16 and len(kinds["HOLE"]) == 8
    assert gc.signed_area(kinds["EXT"]) == 400.0  # 20 × 20 m, antihorario
    assert gc.signed_area(kinds["HOLE"]) == -100.0  # 10 × 10 m, horario


def test_s061_rf6_islas_dos_exteriores():
    _, _, rings = read_case("islas")
    assert [k for k, _ in rings] == ["EXT", "EXT"]
    assert all(gc.signed_area(r) == 100.0 for _, r in rings)


def test_s061_rf6_dos_anillos_que_se_tocan_en_un_vertice_no_se_mezclan():
    _, _, rings = read_case("vertice")
    assert [(k, len(r)) for k, r in rings] == [("EXT", 4), ("EXT", 4)]
    shared = set(rings[0][1]) & set(rings[1][1])
    assert shared == {(gc.E0 + 10.0, gc.N0 + 10.0)}


def test_s061_rf10_la_rampa_da_cada_parada_y_satura_fuera_del_rango():
    stops = [0xFF000000 | c for c in gc.RAMP]
    for i, stop in enumerate(stops):
        assert gc.ramp_argb(600.0 + 20.0 * i, 600.0, 720.0) == stop
    assert gc.ramp_argb(500.0, 600.0, 720.0) == stops[0]
    assert gc.ramp_argb(900.0, 600.0, 720.0) == stops[-1]
    assert gc.ramp_argb(10.0, 600.0, 600.0) == stops[3]  # mínimo igual al máximo: el color del centro
