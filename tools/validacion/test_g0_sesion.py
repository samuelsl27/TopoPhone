"""La herramienta de la puerta G0 lee una sesión como la app y da lo mismo que el generador."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import g0_sesion  # noqa: E402

SYNTHETIC = g0_sesion.g.OUT / f"{g0_sesion.g.NAME}.nmea"
EXPECTED = g0_sesion.g.OUT / f"{g0_sesion.g.NAME}.esperado.csv"


def expected_rows() -> list[dict[str, str]]:
    lines = [line for line in EXPECTED.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:]]


def test_descarta_la_gga_corrupta_y_la_cortada_como_la_app() -> None:
    epochs = g0_sesion.gga_epochs(SYNTHETIC.read_bytes())

    assert [e.utc for e in epochs] == [row["hora"] for row in expected_rows()]


def test_las_coordenadas_coinciden_con_las_esperadas_salvo_las_precisiones() -> None:
    geoid = g0_sesion.g.generar_vectores.geoid_transformer()
    epochs = g0_sesion.gga_epochs(SYNTHETIC.read_bytes())

    for epoch, row in zip(epochs, expected_rows(), strict=True):
        got = g0_sesion.expected(epoch, 1.8, geoid)
        for key in ("estado", "satelites", "latitud", "longitud", "h", "huso", "este", "norte", "H"):
            assert g0_sesion.g.same_value(got[key], row[key]), (row["hora"], key, got[key], row[key])


def test_formatos_como_en_la_pantalla_en_espanol() -> None:
    assert g0_sesion.dms(40.41668, 2, "N", "S") == "40° 25′ 00,04800″ N"
    assert g0_sesion.dms(-3.70371, 3, "E", "W") == "003° 42′ 13,35600″ W"
    assert g0_sesion.metres("4474257.3818") == "4.474.257,382 m"
    assert g0_sesion.metres("") == "no disponible"


def test_la_herramienta_muestra_la_epoca_pedida(capsys) -> None:  # type: ignore[no-untyped-def]
    assert g0_sesion.main([str(SYNTHETIC), "--hora", "101530.00"]) == 0

    out = capsys.readouterr().out
    assert "40° 25′ 00,48000″ N" in out
    assert "699,423 m" in out
    assert "Huso UTM  30" in out


def test_esperado_escribe_el_csv_que_lee_recorded_sessions_test(tmp_path: Path) -> None:
    session = tmp_path / "estatico-fix-vertice.nmea"
    session.write_bytes(SYNTHETIC.read_bytes())

    assert g0_sesion.main([str(session), "--jalon", "1.8", "--esperado"]) == 0

    written = (tmp_path / "estatico-fix-vertice.esperado.csv").read_text(encoding="utf-8").splitlines()
    assert any(line.startswith("# jalon_m=1.8;") for line in written)
    rows = [line.split(",") for line in written if line and not line.startswith(("#", "frase"))]
    synthetic = expected_rows()
    assert [r[1] for r in rows] == [row["hora"] for row in synthetic]
    assert all(g0_sesion.g.same_value(r[12], row["este"]) for r, row in zip(rows, synthetic, strict=True))
