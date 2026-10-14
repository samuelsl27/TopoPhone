"""Tests de la definición del workflow de integración continua (S001/RF-25, S001/RF-28)."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci.yml"


@pytest.fixture(scope="module")
def workflow() -> dict:
    return yaml.safe_load(CI.read_text(encoding="utf-8"))


def triggers(workflow: dict) -> dict:
    # PyYAML (YAML 1.1) interpreta la clave `on` como el booleano True.
    return workflow.get("on", workflow.get(True))


def all_steps(workflow: dict) -> list[dict]:
    return [step for job in workflow["jobs"].values() for step in job.get("steps", [])]


def run_commands(workflow: dict) -> str:
    return "\n".join(step.get("run", "") for step in all_steps(workflow))


def test_s001_rf25_se_ejecuta_en_cada_push_y_pull_request(workflow: dict) -> None:
    """S001/RF-25: push a cualquier rama y pull requests (también desde forks)."""
    on = triggers(workflow)

    assert "pull_request" in on
    assert on["push"]["branches"] == ["**"]


def test_s001_rf25_es_reutilizable_por_el_workflow_de_publicacion(workflow: dict) -> None:
    """S001/RF-25: la publicación ejecuta exactamente las mismas comprobaciones."""
    assert "workflow_call" in triggers(workflow)


def test_s001_rf25_compila_y_ejecuta_tests_estilo_y_trazabilidad(workflow: dict) -> None:
    """S001/RF-25: compilación, tests, estilo, trazabilidad y ficheros prohibidos."""
    commands = run_commands(workflow)

    assert "./gradlew check" in commands
    assert "pytest tools" in commands
    assert "tools/trazabilidad/rf_report.py" in commands
    assert "tools/repo/forbidden_files.py" in commands
    assert "actionlint" in commands


def test_s001_rf25_descarga_el_historial_con_etiquetas_para_la_version(workflow: dict) -> None:
    """S001/RF-25: sin etiquetas la versión de la compilación no se podría calcular (S001/RF-21)."""
    checkout = next(step for step in all_steps(workflow) if str(step.get("uses", "")).startswith("actions/checkout@"))

    assert checkout["with"]["fetch-depth"] == 0


def test_s001_rf25_no_necesita_secretos_para_funcionar_en_forks(workflow: dict) -> None:
    """S001/RF-25: los pull requests de forks no tienen secretos; las comprobaciones no los usan."""
    assert "secrets." not in CI.read_text(encoding="utf-8")
    assert workflow["permissions"] == {"contents": "read"}


def test_s001_rf28_ningun_fallo_se_ignora(workflow: dict) -> None:
    """S001/RF-28: un fallo de cualquier comprobación marca el cambio como no apto."""
    for job in workflow["jobs"].values():
        assert not job.get("continue-on-error", False)
        for step in job.get("steps", []):
            assert not step.get("continue-on-error", False), step
            assert "|| true" not in step.get("run", ""), step


def test_s001_rf28_las_comprobaciones_rapidas_van_antes_que_gradle(workflow: dict) -> None:
    """S001/RF-28: los ficheros prohibidos se comprueban antes de compilar nada."""
    commands = [step.get("run", "") for step in all_steps(workflow)]
    forbidden = next(i for i, c in enumerate(commands) if "forbidden_files.py" in c)
    gradle = next(i for i, c in enumerate(commands) if "./gradlew check" in c)

    assert forbidden < gradle
