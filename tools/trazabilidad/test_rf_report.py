"""Tests del informe de trazabilidad RF ↔ tests (S001/RF-26, S001/RF-35).

Los datos de prueba construyen las citas en tiempo de ejecución con `cite()` para que este
fichero no contenga citas literales falsas: el propio informe también lo analiza.
"""

from __future__ import annotations

import textwrap
from pathlib import Path

from trazabilidad import rf_report

S = "S"  # prefijo de cita construido aparte: el código fuente no contiene citas falsas


def cite(spec: str, rf: int | str, sep: str = "/") -> str:
    return f"{S}{spec}{sep}RF-{rf}"


def write(root: Path, relative: str, content: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content), encoding="utf-8")


def roadmap(root: Path, *rows: tuple[str, str]) -> None:
    lines = ["# Hoja de ruta", "", "| Spec | Alcance | Depende de | Estado |", "|---|---|---|---|"]
    lines += [f"| {spec} | algo | — | {state} |" for spec, state in rows]
    write(root, "docs/hoja-de-ruta.md", "\n".join(lines) + "\n")


def spec(root: Path, number: str, slug: str, rf_count: int) -> None:
    rfs = "\n".join(f"- RF-{i}: EL SISTEMA hará la cosa {i}." for i in range(1, rf_count + 1))
    write(root, f"specs/{number}-{slug}/spec.md", f"# Spec {number}\n\n## Requisitos funcionales\n{rfs}\n")


def test_s001_rf26_spec_implementada_con_rf_sin_test_falla_y_lo_lista(tmp_path: Path, capsys) -> None:
    """S001/RF-26: una spec implementada con un RF sin test hace fallar el informe."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"))
    spec(tmp_path, "001", "esqueleto-app", 3)
    write(tmp_path, "app/src/test/kotlin/ATest.kt", f"// {cite('001', 1)}\n// {cite('001', 2)}\n")

    code = rf_report.main(["--root", str(tmp_path)])

    out = capsys.readouterr().out
    assert code == 1
    assert "RF-3" in out
    assert "S001" in out


def test_s001_rf26_spec_implementada_completa_pasa(tmp_path: Path) -> None:
    """S001/RF-26: si todos los RF tienen test, el informe sale con código 0."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"))
    spec(tmp_path, "001", "esqueleto-app", 2)
    write(
        tmp_path,
        "core/model/src/test/kotlin/BTest.kt",
        f"fun `{cite('001', 1, ' ')} algo`() {{}}\nfun `{cite('001', 2, ' ')} otra`() {{}}\n",
    )

    assert rf_report.main(["--root", str(tmp_path)]) == 0


def test_s001_rf35_spec_en_curso_lista_pendientes_sin_fallar(tmp_path: Path, capsys) -> None:
    """S001/RF-35: una spec en curso muestra sus RF sin test pero no falla."""
    roadmap(tmp_path, ("001 esqueleto-app", "en curso"))
    spec(tmp_path, "001", "esqueleto-app", 3)
    write(tmp_path, "app/src/test/kotlin/ATest.kt", f"// {cite('001', 2)}\n")

    code = rf_report.main(["--root", str(tmp_path)])

    out = capsys.readouterr().out
    assert code == 0
    assert "RF-1" in out and "RF-3" in out
    assert "en curso" in out


def test_s001_rf26_specs_pendientes_o_en_spec_no_se_exigen(tmp_path: Path) -> None:
    """S001/RF-26: solo se exigen las specs implementadas; las pendientes no cuentan."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"), ("002 cadena-rtk-minima", "pendiente"), ("003 flujo", "spec"))
    spec(tmp_path, "001", "esqueleto-app", 1)
    spec(tmp_path, "003", "flujo", 4)
    write(tmp_path, "app/src/test/kotlin/ATest.kt", f"// {cite('001', 1)}\n")

    assert rf_report.main(["--root", str(tmp_path)]) == 0


def test_s001_rf26_formatos_de_cita_aceptados() -> None:
    """S001/RF-26: barra, espacio y guion bajo, listas y rangos cuentan como citas."""
    text = "\n".join(
        [
            f"// {cite('001', 1)}",
            f"fun `{cite('001', 2, ' ')} nombre de test`() {{}}",
            f"def test_{S.lower()}001_rf3_python(): ...",
            f"/** Tests ({cite('002', 1)}, RF-2 y RF-4). */",
            f"// {cite('003', 2)}..RF-5",
        ],
    )

    cited = rf_report.extract_citations(text)

    assert cited == {
        ("001", 1), ("001", 2), ("001", 3),
        ("002", 1), ("002", 2), ("002", 4),
        ("003", 2), ("003", 3), ("003", 4), ("003", 5),
    }


def test_s001_rf26_una_cita_a_un_rf_inexistente_falla(tmp_path: Path, capsys) -> None:
    """S001/RF-26: citar un RF que no existe en la spec es un error (cita obsoleta)."""
    roadmap(tmp_path, ("001 esqueleto-app", "en curso"))
    spec(tmp_path, "001", "esqueleto-app", 2)
    write(tmp_path, "app/src/test/kotlin/ATest.kt", f"// {cite('001', 1)}\n// {cite('001', 99)}\n")

    code = rf_report.main(["--root", str(tmp_path)])

    assert code == 1
    assert "RF-99" in capsys.readouterr().out


def test_s001_rf26_una_cita_a_una_spec_sin_spec_md_falla(tmp_path: Path, capsys) -> None:
    """S001/RF-26: citar una spec que todavía no tiene spec.md es un error."""
    roadmap(tmp_path, ("001 esqueleto-app", "en curso"), ("002 cadena-rtk-minima", "pendiente"))
    spec(tmp_path, "001", "esqueleto-app", 1)
    write(tmp_path, "app/src/test/kotlin/ATest.kt", f"// {cite('001', 1)}\n// {cite('002', 1)}\n")

    assert rf_report.main(["--root", str(tmp_path)]) == 1
    assert "S002" in capsys.readouterr().out


def test_s001_rf26_las_citas_en_codigo_de_produccion_o_build_no_cuentan(tmp_path: Path) -> None:
    """S001/RF-26: solo cuentan los tests; ni src/main ni build/ cubren un RF."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"))
    spec(tmp_path, "001", "esqueleto-app", 1)
    write(tmp_path, "app/src/main/kotlin/A.kt", f"// {cite('001', 1)}\n")
    write(tmp_path, "app/build/tmp/src/test/kotlin/Gen.kt", f"// {cite('001', 1)}\n")

    assert rf_report.main(["--root", str(tmp_path)]) == 1


def test_s001_rf26_cuentan_tests_de_build_logic_y_de_tools(tmp_path: Path) -> None:
    """S001/RF-26: los tests de build-logic (Kotlin) y de tools (Python) citan RF."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"))
    spec(tmp_path, "001", "esqueleto-app", 2)
    write(tmp_path, "build-logic/convention/src/test/kotlin/XTest.kt", f"// {cite('001', 1)}\n")
    write(tmp_path, "tools/repo/test_algo.py", f'"""{cite("001", 2)}: algo."""\n')

    assert rf_report.main(["--root", str(tmp_path)]) == 0


def test_s001_rf26_lee_varias_tablas_de_la_hoja_de_ruta(tmp_path: Path) -> None:
    """S001/RF-26: el estado se lee de cualquier tabla de la hoja de ruta."""
    write(
        tmp_path,
        "docs/hoja-de-ruta.md",
        """\
        ## H0
        | Spec | Alcance | Depende de | Estado |
        |---|---|---|---|
        | 001 esqueleto-app | Proyecto | — | implementada |

        ## H1
        | Spec | Alcance | Depende de | Estado |
        |---|---|---|---|
        | 003 flujo-nmea-y-simulador | Parser | 002 | en curso |
        """,
    )

    states = rf_report.read_roadmap(tmp_path / "docs/hoja-de-ruta.md")

    assert states == {"001": "implementada", "003": "en curso"}


def test_s001_rf26_una_spec_exigible_sin_spec_md_es_un_error(tmp_path: Path, capsys) -> None:
    """S001/RF-26: una spec en curso o implementada debe tener su spec.md."""
    roadmap(tmp_path, ("001 esqueleto-app", "implementada"))

    assert rf_report.main(["--root", str(tmp_path)]) == 1
    assert "spec.md" in capsys.readouterr().out
