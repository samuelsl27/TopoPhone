"""Tests de la definición del workflow de publicación (S001/RF-30 a S001/RF-34)."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / ".github" / "workflows" / "release.yml"


@pytest.fixture(scope="module")
def workflow() -> dict:
    return yaml.safe_load(RELEASE.read_text(encoding="utf-8"))


def release_steps(workflow: dict) -> list[dict]:
    return workflow["jobs"]["release"]["steps"]


def commands(workflow: dict) -> list[str]:
    return [step.get("run", "") for step in release_steps(workflow)]


def index_of(workflow: dict, needle: str) -> int:
    return next(i for i, run in enumerate(commands(workflow)) if needle in run)


def test_s001_rf30_solo_se_publica_al_crear_una_etiqueta_v(workflow: dict) -> None:
    """S001/RF-30: el disparador es la creación de etiquetas vX.Y.Z."""
    on = workflow.get("on", workflow.get(True))

    assert list(on) == ["push"]
    assert on["push"] == {"tags": ["v*"]}


def test_s001_rf30_antes_de_publicar_pasan_todas_las_comprobaciones(workflow: dict) -> None:
    """S001/RF-30: el commit etiquetado pasa el mismo CI que cualquier cambio."""
    assert workflow["jobs"]["checks"]["uses"] == "./.github/workflows/ci.yml"
    assert workflow["jobs"]["release"]["needs"] == "checks"


def test_s001_rf31_s001_rf33_se_valida_la_etiqueta_frente_a_la_ultima_publicada(workflow: dict) -> None:
    """S001/RF-31, S001/RF-33: formato y versión mayor que la publicada, antes de compilar."""
    verify = commands(workflow)[index_of(workflow, "verifyReleaseTag")]

    assert "-PreleaseTag=" in verify and "-PlastPublishedTag=" in verify
    assert index_of(workflow, "verifyReleaseTag") < index_of(workflow, "assembleRelease")


def test_s001_rf32_se_comprueba_la_firma_antes_de_compilar_el_apk(workflow: dict) -> None:
    """S001/RF-32: sin clave de firma no hay APK."""
    build = commands(workflow)[index_of(workflow, "assembleRelease")]

    assert build.index("verifyReleaseSigning") < build.index("assembleRelease")


def test_s001_rf34_se_comprueba_el_certificado_antes_de_publicar(workflow: dict) -> None:
    """S001/RF-34: el APK debe estar firmado con el certificado registrado."""
    build = commands(workflow)[index_of(workflow, "assembleRelease")]

    assert build.index("assembleRelease") < build.index("verifyReleaseCertificate")
    assert index_of(workflow, "verifyReleaseCertificate") < index_of(workflow, "gh release create")


def test_s001_rf30_publica_el_apk_de_la_etiqueta_en_github_releases(workflow: dict) -> None:
    """S001/RF-30: el APK se sube a una release con el nombre de la etiqueta."""
    publish = commands(workflow)[index_of(workflow, "gh release create")]

    assert '"$RELEASE_TAG"' in publish
    assert "--verify-tag" in publish
    assert workflow["jobs"]["release"]["env"]["RELEASE_TAG"] == "${{ github.ref_name }}"


def test_s001_rf32_los_secretos_solo_llegan_al_trabajo_de_publicacion(workflow: dict) -> None:
    """S001/RF-32: permisos mínimos y secretos solo donde se firma."""
    assert workflow["permissions"] == {"contents": "read"}
    assert workflow["jobs"]["release"]["permissions"] == {"contents": "write"}
    text = RELEASE.read_text(encoding="utf-8")
    assert text.count("secrets.") == 4


def test_s001_rf32_la_clave_se_borra_al_terminar_aunque_algo_falle(workflow: dict) -> None:
    """S001/RF-32: el almacén de claves descodificado no sobrevive al trabajo."""
    cleanup = release_steps(workflow)[-1]

    assert cleanup.get("if") == "always()"
    assert "rm -f" in cleanup["run"]


def test_s001_rf28_la_publicacion_no_ignora_fallos(workflow: dict) -> None:
    """S001/RF-28: ningún paso de compilación, firma o publicación puede fallar en silencio."""
    for step in release_steps(workflow):
        assert not step.get("continue-on-error", False), step
    for needle in ("verifyReleaseTag", "assembleRelease", "gh release create"):
        assert "|| true" not in commands(workflow)[index_of(workflow, needle)]
